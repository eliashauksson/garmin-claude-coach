(ns coach.readiness-estimate
  "Glue between the DB and coach.readiness-score: reads one morning's
  features, gives the rebuilt readiness for mornings Garmin has none, refits
  the parameters after a sync, and a CLI comparing it with Garmin's score:
  clojure -M -m coach.readiness-estimate [compare [from] | refit]"
  (:require [clojure.data.json :as json]
            [coach.db :as db]
            [coach.garmin-mcp :as gm]
            [coach.readiness-score :as rs]
            [coach.season :as season])
  (:import (java.time Duration Instant LocalDateTime ZoneId ZoneOffset)))

(defn- m [x] (when (map? x) x))

(defn- utc-time
  "\"2026-11-03T06:35:19.0\" (Garmin's UTC times) -> LocalDateTime."
  [s]
  (when (and (string? s) (>= (count s) 19))
    (try (LocalDateTime/parse (subs s 0 19)) (catch Exception _ nil))))

(defn- hours [^LocalDateTime a ^LocalDateTime b] (/ (.toMinutes (Duration/between a b)) 60.0))

(defn- wake-time
  "When the athlete woke, in UTC: Garmin's morning readiness snapshot, else
  the end of the night's HRV reading or sleep, else 06:00."
  [date raw]
  (or (utc-time (:timestamp (gm/morning-readiness (:readiness raw))))
      (utc-time (:sleep_end (m (:hrv raw))))
      (when-let [ms (:sleep_end (m (:sleep raw)))]
        (when (number? ms) (LocalDateTime/ofEpochSecond (quot (long ms) 1000) 0 ZoneOffset/UTC)))
      (LocalDateTime/parse (str date "T06:00:00"))))

(defn- stress48
  "Mean awake (07-23 h local) stress in the 48 h before waking, from the
  intraday series (minutes from local midnight)."
  [ds date ^LocalDateTime wake-utc]
  (let [wake (-> wake-utc (.atZone ZoneOffset/UTC) (.withZoneSameInstant (ZoneId/systemDefault)) .toLocalDateTime)
        from (.minusHours wake 48)
        vs   (for [d [(season/add-days date -2) (season/add-days date -1) date]
                   :let [midnight (LocalDateTime/parse (str d "T00:00:00"))]
                   [minute v] (:stress (db/load-intraday ds d))
                   :when (and (number? v) (>= v 0))
                   :let [t (.plusMinutes midnight (long minute))]
                   :when (and (not (.isBefore t from)) (.isBefore t wake) (<= 7 (.getHour t) 22))]
               v)]
    (when (>= (count vs) 50) (/ (reduce + 0.0 vs) (count vs)))))

(defn features
  "One morning's inputs for coach.readiness-score/components."
  [ds date]
  (let [rows (into {} (map (juxt :date identity))
                   (db/query ds ["SELECT date, sleep_score, raw FROM daily_wellness WHERE date BETWEEN ? AND ?"
                                 (season/add-days date -2) date]))
        raw  (db/read-json (:raw (rows date)))
        wake (wake-time date raw)
        hv   (m (:hrv raw))
        ;; Garmin's load where it has one, else ours (coach.epoc): rides from
        ;; Coros count too, which Garmin's own score never sees
        acts (for [{:keys [start_time_gmt duration_s training_load]}
                   (db/query ds ["SELECT a.start_time_gmt, a.duration_s,
                                         coalesce(a.training_load, m.load_est) AS training_load
                                  FROM activities a LEFT JOIN activity_metrics m ON m.activity_id = a.id
                                  WHERE coalesce(a.training_load, m.load_est) IS NOT NULL
                                    AND a.date BETWEEN ? AND ?"
                                 (season/add-days date -61) (season/add-days date 1)])
                   :let [start (utc-time start_time_gmt)]
                   :when (and start (.isBefore start wake))]
               {:start start :end (.plusSeconds start (long (or duration_s 0))) :load (double training_load)})]
    {:date      date
     :wake      (str wake)
     :sleep     (mapv #(:sleep_score (rows (season/add-days date (- %)))) [0 1 2])
     :hrv_delta (when (and (number? (:weekly_avg_hrv_ms hv)) (number? (:baseline_balanced_low_ms hv)))
                  (- (:weekly_avg_hrv_ms hv) (:baseline_balanced_low_ms hv)))
     :loads     (vec (for [{:keys [start load]} acts] [(/ (hours start wake) 24.0) load]))
     :workouts  (vec (for [{:keys [end load]} acts
                           :when (and (.isBefore ^LocalDateTime end wake) (< (hours end wake) 120))]
                       [(hours end wake) load]))
     :stress48  (stress48 ds date wake)}))

(def ^:private garmin-factors
  {:sleep :sleep_factor_percent :recovery_time :recovery_factor_percent :hrv :hrv_factor_percent
   :acwr :training_load_factor_percent :sleep_history :sleep_history_factor_percent
   :stress_history :stress_history_factor_percent})

(defn- garmin-of
  "Garmin's morning score with its factors (a factor of 0 means Garmin had no data)."
  [raw]
  (let [r (gm/morning-readiness (:readiness raw))]
    (when (number? (:score r))
      {:score      (:score r)
       :recovery_h (:recovery_time_hours r)
       :factors    (into {} (for [[k f] garmin-factors :let [v (get r f)] :when (and (number? v) (pos? v))] [k v]))})))

(defn params
  "The refitted parameters, or the defaults before the first refit."
  [ds]
  (merge rs/defaults
         (db/state-json ds :readiness_params)))

(defn for-date
  "The rebuilt readiness for `date` when Garmin has none, else nil."
  [ds date]
  (let [raw (db/read-json (:raw (first (db/query ds ["SELECT raw FROM daily_wellness WHERE date = ?" date]))))]
    (when (and raw (not (garmin-of raw)))
      (rs/estimate (params ds) (features ds date)))))

(defn training-rows
  "Mornings with Garmin's full score (all six factors) since `from`."
  [ds from]
  (vec (for [{:keys [date raw]} (db/query ds ["SELECT date, raw FROM daily_wellness
                                               WHERE readiness IS NOT NULL AND date >= ? ORDER BY date" from])
             :let [g (garmin-of (db/read-json raw))]
             :when (= 6 (count (:factors g)))]
         {:date date :garmin g :features (features ds date)})))

(def min-rows "Refit only with this many mornings of Garmin's full score." 60)

(defn refit!
  "Refits the parameters on the last year of Garmin's scores and keeps them
  if they match Garmin at least as well as the current ones. Returns the stats."
  [ds today]
  (let [rows (training-rows ds (season/add-days today -365))]
    (when (>= (count rows) min-rows)
      (let [cur   (params ds)
            new   (rs/fit cur rows)
            ec    (rs/evaluate cur rows)
            en    (rs/evaluate new rows)
            [p s] (if (<= (:mae en) (:mae ec)) [new en] [cur ec])
            stats (assoc s :fitted_at (str (Instant/now)))]
        (db/set-state! ds :readiness_params (json/write-str p))
        (db/set-state! ds :readiness_fit (json/write-str stats))
        (println "Readiness estimate refitted:" (rs/summary stats))
        stats))))

(defn -main
  "compare [from]: Garmin's score next to the rebuilt one, per morning.
  refit: refit the parameters now."
  [& [cmd from]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (case (or cmd "compare")
      "refit"   (prn (refit! ds (season/today)))
      "compare" (let [p    (params ds)
                      rows (training-rows ds (or from (season/add-days (season/today) -365)))]
                  (doseq [{:keys [date garmin features]} rows
                          :let [e (rs/estimate p features)]]
                    (println date (format "Garmin %3d  rebuilt %3s  %s" (:score garmin) (or (:score e) "—")
                                          (pr-str (:factors e)))))
                  (some-> (rs/evaluate p rows) rs/summary println)))
    (shutdown-agents)))
