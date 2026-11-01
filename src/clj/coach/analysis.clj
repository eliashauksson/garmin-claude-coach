(ns coach.analysis
  "Glue between the DB and coach.metrics: per-activity metric computation
  and the queries behind the API."
  (:require [clojure.data.json :as json]
            [next.jdbc]
            [clojure.string :as str]
            [coach.db :as db]
            [coach.metrics :as m])
  (:import (java.time LocalDate)))

(def epoch "2021-03-26")

;; ---------------------------------------------------------------------------
;; Thresholds

(def ^:private threshold-keys
  {["all" "lthr"]            :lthr
   ["all" "max_hr"]          :max_hr
   ["run" "threshold_speed"] :threshold_speed
   ["run" "ftp"]             :run_ftp
   ["bike" "ftp"]            :ftp
   ["swim" "css"]            :css})

(defn thresholds-at
  "Thresholds valid on `date`: per (sport, kind) the latest row with
  valid_from <= date, or the earliest one if all are later."
  [rows date]
  (let [pick (fn [rs]
               (let [rs (sort-by :valid_from rs)]
                 (:value (or (last (filter #(<= (compare (:valid_from %) date) 0) rs))
                             (first rs)))))
        by   (group-by (juxt :sport :kind) rows)
        zones (keep #(pick (by ["all" (str "hr_z" %)])) (range 1 6))]
    (cond-> (into {} (for [[k rs] by :let [key (threshold-keys k)] :when key] [key (pick rs)]))
      (= 5 (count zones)) (assoc :hr_zones (vec zones)))))

;; ---------------------------------------------------------------------------
;; Route thumbnail

(def route-points "Points kept for the list-view route thumbnail." 80)

(defn route-thumb
  "A simplified track for the activity cards: up to `route-points` [lat lon]
  pairs (evenly sampled, always keeping the last point), rounded to ~1 m.
  nil when the stream has fewer than two positions."
  [{:keys [lat lon]}]
  (let [pts (vec (keep (fn [[la lo]] (when (and la lo) [la lo])) (map vector lat lon)))
        n   (count pts)]
    (when (>= n 2)
      (let [step   (long (max 1 (Math/ceil (/ n (double route-points)))))
            picked (cond-> (vec (take-nth step pts))
                     (not= 0 (mod (dec n) step)) (conj (peek pts)))
            r5     #(/ (Math/round (* % 100000.0)) 100000.0)]
        (mapv (fn [[la lo]] [(r5 la) (r5 lo)]) picked)))))

;; ---------------------------------------------------------------------------
;; Per-activity metrics

(defn- rest-hr-lookup
  "date -> resting HR, falling back to the median over all days."
  [ds]
  (let [rows   (db/query ds ["SELECT date, rhr FROM daily_wellness WHERE rhr IS NOT NULL"])
        by     (into {} (map (juxt :date :rhr)) rows)
        median (let [v (vec (sort (vals by)))] (when (seq v) (v (quot (count v) 2))))]
    (fn [date] (or (by date) median 50))))

(defn compute-metrics!
  "Computes and stores metrics. `ids` limits it to those activities;
  without it, every activity is (re)computed."
  ([ds] (compute-metrics! ds nil))
  ([ds ids]
   (let [rows    (db/thresholds ds)
         sex     (db/get-state ds :sex)
         rest-hr (rest-hr-lookup ds)
         acts    (if ids
                   (db/query ds (into [(str "SELECT * FROM activities WHERE id IN ("
                                            (str/join "," (repeat (count ids) "?")) ")")]
                                      ids))
                   (db/query ds ["SELECT * FROM activities"]))]
     (doseq [batch (partition-all 50 acts)]
       (db/upsert-metrics!
        ds
        (for [a batch]
          (let [th     (assoc (thresholds-at rows (:date a)) :rest_hr (rest-hr (:date a)) :sex sex)
                stream (db/load-stream ds (:id a))]
            (assoc (m/activity-metrics a stream th)
                   :activity_id (:id a)
                   :inputs th
                   :route (route-thumb stream))))))
     (count acts))))

;; ---------------------------------------------------------------------------
;; Athlete feedback

(defn save-feedback!
  "RPE 1-10, pain (bool) with location, and a note for one activity."
  [ds activity-id {:keys [rpe pain pain_location note]}]
  (when (and rpe (not (<= 1 rpe 10)))
    (throw (ex-info "RPE must be 1-10" {:rpe rpe})))
  (next.jdbc/execute! ds ["INSERT INTO activity_feedback (activity_id, rpe, pain, pain_location, note, updated_at)
                           VALUES (?, ?, ?, ?, ?, ?)
                           ON CONFLICT(activity_id) DO UPDATE SET rpe = excluded.rpe, pain = excluded.pain,
                             pain_location = excluded.pain_location, note = excluded.note,
                             updated_at = excluded.updated_at"
                          activity-id rpe (if pain 1 0) pain_location note (str (java.time.Instant/now))]))

(defn effective-rpe
  "The athlete's in-app RPE, else the RPE entered on the watch."
  [a]
  (or (:rpe a) (:garmin_rpe a)))

;; ---------------------------------------------------------------------------
;; Queries

(defn- parse-json-cols [row cols]
  (reduce (fn [r c] (update r c #(some-> % (json/read-str :key-fn keyword)))) row cols))

(def ^:private activity-select
  "SELECT a.id, a.date, a.start_time_local, a.sport, a.name, a.distance_m, a.duration_s,
          a.moving_s, a.elev_gain_m, a.avg_hr, a.max_hr, a.calories,
          a.training_load AS garmin_load, a.aerobic_te,
          m.tss, m.tss_method, m.intensity, m.tss_per_hour, m.np, m.ngp_mps, m.vi, m.ef,
          m.decoupling, m.kj, m.hr_zones_s, m.peaks, m.route,
          json_extract(a.raw, '$.detail.workout_rpe') / 10.0 AS garmin_rpe,
          f.rpe, f.pain, f.pain_location, f.note
   FROM activities a
   LEFT JOIN activity_metrics m ON m.activity_id = a.id
   LEFT JOIN activity_feedback f ON f.activity_id = a.id")

(defn activities
  "Activities with metrics, newest first. Filters: :sport :from :to."
  [ds {:keys [sport from to limit]}]
  (let [where (cond-> []
                sport (conj ["a.sport = ?" sport])
                from  (conj ["a.date >= ?" from])
                to    (conj ["a.date <= ?" to]))
        sql   (str activity-select
                   (when (seq where) (str " WHERE " (str/join " AND " (map first where))))
                   " ORDER BY a.start_time_local DESC"
                   (when limit (str " LIMIT " (long limit))))]
    (mapv #(parse-json-cols % [:hr_zones_s :peaks :route])
          (db/query ds (into [sql] (map second where))))))

(defn activity [ds id]
  (when-let [a (first (db/query ds [(str activity-select " WHERE a.id = ?") id]))]
    (-> (parse-json-cols a [:hr_zones_s :peaks :route])
        (assoc :thresholds (some-> (:inputs (first (db/query ds ["SELECT inputs FROM activity_metrics WHERE activity_id = ?" id])))
                                   (json/read-str :key-fn keyword))))))

(defn- today [] (str (LocalDate/now)))

(defn pmc
  "CTL/ATL/TSB for [from, to]. Always computed from the start of history so
  the values are correct regardless of the window."
  [ds from to]
  (let [daily (m/daily-tss (db/query ds ["SELECT a.date, m.tss FROM activities a
                                          JOIN activity_metrics m ON m.activity_id = a.id"]))]
    (filterv #(>= (compare (:date %) from) 0) (m/pmc daily epoch to))))

(defn volume [ds from to]
  (m/weekly-volume (activities ds {:from from :to to})))

(def wellness-cols
  "SELECT w.date, w.rhr, w.hrv_avg, w.hrv_weekly_avg, w.hrv_status, w.sleep_score, w.sleep_s,
          w.deep_sleep_s, w.rem_sleep_s, w.readiness, w.readiness_level, w.avg_stress,
          w.body_battery_high, w.body_battery_low, w.steps, v.value AS vo2max
   FROM daily_wellness w LEFT JOIN vo2max v ON v.date = w.date AND v.sport = 'running'")

(defn wellness [ds from to]
  (db/query ds [(str wellness-cols " WHERE w.date BETWEEN ? AND ? ORDER BY w.date") from to]))

(def trend-keys [:rhr :hrv_avg :sleep_score :sleep_s :readiness :avg_stress :vo2max])

(defn trends
  "Rolling averages and baselines per wellness metric. Rows before `from`
  are included in the calculation so the first days have full windows."
  [ds from to]
  (let [lead (str (.minusDays (LocalDate/parse from) m/baseline-days))
        rows (wellness ds lead to)]
    (into {}
          (for [k trend-keys]
            [k (filterv #(>= (compare (:date %) from) 0) (m/trend rows k))]))))

(defn- raw-of [row] (some-> (:raw row) (json/read-str :key-fn keyword)))

(defn- num-or-nil [x] (when (number? x) x))

(defn day-extras
  "Daily extras for the Today page from a daily_wellness raw map. Days
  without data hold message strings, so every lookup tolerates that."
  [raw]
  (let [st (let [x (:stats raw)] (if (map? x) x {}))
        hv (let [x (:hrv raw)] (if (map? x) x {}))
        sl (let [x (:sleep raw)] (if (map? x) x {}))]
    (update-vals
     {:hr_min        (:min_heart_rate_bpm st)
      :hr_max        (:max_heart_rate_bpm st)
      :rhr_7d        (:last_7_days_avg_resting_hr st)
      :bb_charged    (:body_battery_charged st)
      :bb_drained    (:body_battery_drained st)
      :bb_current    (:body_battery_current st)
      :stress_max    (:max_stress_level st)
      :step_goal     (:daily_step_goal st)
      :distance_m    (:distance_meters st)
      :floors        (:floors_ascended st)
      :hrv_low       (:baseline_balanced_low_ms hv)
      :hrv_high      (:baseline_balanced_upper_ms hv)
      :sleep_light_s (:light_sleep_seconds sl)
      :sleep_awake_s (:awake_seconds sl)}
     num-or-nil)))

(defn nights
  "The 7 nights ending `date`, oldest first."
  [ds date]
  (let [from (str (.minusDays (LocalDate/parse date) 6))]
    (mapv (fn [r]
            (let [x (day-extras (raw-of r))]
              {:date (:date r) :sleep_score (:sleep_score r) :sleep_s (:sleep_s r)
               :hrv_avg (:hrv_avg r) :hrv_low (:hrv_low x) :hrv_high (:hrv_high x)}))
          (db/query ds ["SELECT date, sleep_score, sleep_s, hrv_avg, raw FROM daily_wellness
                         WHERE date BETWEEN ? AND ? ORDER BY date" from date]))))

(defn day-summary
  "One day's health data, as the day page shows it: the day's wellness, its
  extras and intraday series (kept for the last week only), the 7 nights up
  to it, the load and the day's activities."
  [ds date]
  (let [well (wellness ds date date)
        tr   (trends ds date date)
        raw  (raw-of (first (db/query ds ["SELECT raw FROM daily_wellness WHERE date = ?" date])))]
    {:date       date
     :today      (today)
     :wellness   (first well)
     :baselines  (into {} (for [[k v] tr] [k (dissoc (first v) :date :value)]))
     :load       (last (pmc ds date date))
     :activities (vec (sort-by :start_time_local (activities ds {:from date :to date})))
     :day        (day-extras raw)
     :nights     (nights ds date)
     :intraday   (some-> (db/load-intraday ds date) (dissoc :date))}))
