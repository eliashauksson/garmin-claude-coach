(ns coach.analysis
  "Glue between the DB and coach.metrics: per-activity metric computation
  and the queries behind the API."
  (:require [clojure.data.json :as json]
            [next.jdbc]
            [clojure.string :as str]
            [coach.db :as db]
            [coach.metrics :as m])
  (:import (java.time LocalDate)))

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
  (let [row  (fn [rs]
               (let [rs (sort-by :valid_from rs)]
                 (or (last (filter #(<= (compare (:valid_from %) date) 0) rs))
                     (first rs))))
        pick (comp :value row)
        by   (group-by (juxt :sport :kind) rows)
        zones (keep #(pick (by ["all" (str "hr_z" %)])) (range 1 6))
        ftp  (row (by ["bike" "ftp"]))]
    (cond-> (into {} (for [[k rs] by :let [key (threshold-keys k)] :when key] [key (pick rs)]))
      (= 5 (count zones)) (assoc :hr_zones (vec zones))
      ftp                 (assoc :ftp_source (:source ftp)))))

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
;; Cycling power: eFTP and the power curve

(defn- bike-power-rides
  "[{:id :date :power {duration watts}}] for the bike activities with power
  (ids as stored; durations as numbers), oldest first."
  [ds]
  (->> (db/query ds ["SELECT a.id, a.date, a.sport, json_extract(m.peaks, '$.power') AS power
                      FROM activities a JOIN activity_metrics m ON m.activity_id = a.id
                      WHERE json_extract(m.peaks, '$.power') IS NOT NULL ORDER BY a.date"])
       (filter #(= :bike (m/sport-family (:sport %))))
       (keep (fn [r]
               (let [p (json/read-str (:power r))]
                 (when (seq p)
                   {:id (:id r) :date (:date r)
                    :power (into {} (map (fn [[k v]] [(parse-long (str k)) v])) p)}))))
       vec))

(defn sync-eftp!
  "Stores the eFTP history (coach.metrics/eftp-history) as bike FTP rows with
  source \"estimate\": new and changed values are written, ones no longer
  in the history removed. Returns the earliest date that changed, or nil."
  [ds]
  (let [hist  (m/eftp-history (bike-power-rides ds))
        want  (into {} (map (juxt :date :value)) hist)
        have  (into {} (map (juxt :valid_from :value))
                    (filter #(and (= "bike" (:sport %)) (= "ftp" (:kind %)) (= "estimate" (:source %)))
                            (db/thresholds ds)))
        stale (remove want (keys have))
        fresh (remove #(= (want %) (have %)) (keys want))]
    (doseq [d stale]
      (next.jdbc/execute! ds ["DELETE FROM thresholds WHERE sport = 'bike' AND kind = 'ftp'
                               AND source = 'estimate' AND valid_from = ?" d]))
    (doseq [d fresh]
      (db/add-threshold! ds {:sport "bike" :kind "ftp" :valid_from d :value (want d) :source "estimate"}))
    (first (sort (concat stale fresh)))))

(defn rescore-bikes-from!
  "Scores the bike activities from `date` on again (after the FTP changed)."
  [ds date]
  (let [ids (->> (db/query ds ["SELECT id, sport FROM activities WHERE date >= ?" date])
                 (filter #(= :bike (m/sport-family (:sport %))))
                 (map :id))]
    (when (seq ids) (compute-metrics! ds ids))))

(defn update-eftp!
  "sync-eftp!, then rescoring of the rides it affects. For the sync jobs,
  after new activities are scored."
  [ds]
  (when-let [from (sync-eftp! ds)]
    (rescore-bikes-from! ds from)))

(defn- curve-of [rides]
  (vec (for [[d {:keys [watts date]}] (m/best-curve rides)]
         {:s d :watts watts :date date})))

(defn cycling-power
  "The Cycling power section: power curves (90 days, the 90 before, all
  time), the bike FTP now and where it comes from, W/kg and the VO2 max
  estimate from the best 5 minutes of the last 90 days. nil when no ride has power."
  [ds today]
  (let [rides (bike-power-rides ds)]
    (when (seq rides)
      (let [day    (fn [n] (str (.minusDays (LocalDate/parse today) n)))
            within (fn [from to] (filter #(<= (compare from (:date %)) 0 (compare to (:date %))) rides))
            recent (within (day (dec m/eftp-window-days)) today)
            th     (thresholds-at (db/thresholds ds) today)
            ftp    (:ftp th)
            weight (some-> (db/get-state ds :weight_kg) parse-double)
            p5     (get (update-vals (m/best-curve recent) :watts) 300)]
        {:curve_90d      (curve-of recent)
         :curve_prev_90d (curve-of (within (day (dec (* 2 m/eftp-window-days))) (day m/eftp-window-days)))
         :curve_all      (curve-of rides)
         :ftp            ftp
         :ftp_source     (when ftp (:ftp_source th))
         :weight_kg      weight
         :wkg            (when (and ftp weight) (/ (Math/round (* 100.0 (/ ftp weight))) 100.0))
         :vo2max_est     (m/cycling-vo2max p5 weight)
         :eftp_history   (vec (for [r (db/thresholds ds)
                                    :when (and (= "bike" (:sport r)) (= "ftp" (:kind r)) (= "estimate" (:source r)))]
                                {:date (:valid_from r) :value (:value r)}))}))))

;; ---------------------------------------------------------------------------
;; Athlete feedback

(declare activities)

(defn save-feedback!
  "RPE 1-10, pain (bool) with location, and a note for one activity.
  `skipped` true records that the athlete dismissed the question."
  [ds activity-id {:keys [rpe pain pain_location note skipped]}]
  (when (and rpe (not (<= 1 rpe 10)))
    (throw (ex-info "RPE must be 1-10" {:rpe rpe})))
  (let [note (some-> note str/trim not-empty)
        loc  (when pain (some-> pain_location str/trim not-empty))]
    (next.jdbc/execute! ds ["INSERT INTO activity_feedback (activity_id, rpe, pain, pain_location, note, skipped, updated_at)
                             VALUES (?, ?, ?, ?, ?, ?, ?)
                             ON CONFLICT(activity_id) DO UPDATE SET rpe = excluded.rpe, pain = excluded.pain,
                               pain_location = excluded.pain_location, note = excluded.note,
                               skipped = excluded.skipped, updated_at = excluded.updated_at"
                            activity-id rpe (if pain 1 0) loc note (if skipped 1 0) (str (java.time.Instant/now))])))

(def feedback-days "Activities from this many days back are asked about." 7)

(defn feedback-due
  "Activities from the last `feedback-days` days (today included) with no
  feedback yet and not skipped, newest first: the \"How did it go?\" list."
  [ds today]
  (->> (activities ds {:from (str (.minusDays (LocalDate/parse today) (dec feedback-days))) :to today})
       (filter #(nil? (:feedback_at %)))
       (mapv #(select-keys % [:id :date :start_time_local :sport :name :distance_m :duration_s :moving_s
                              :avg_hr :tss :garmin_rpe]))))

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
          f.rpe, f.pain, f.pain_location, f.note, f.updated_at AS feedback_at
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
  "CTL/ATL/TSB for [from, to]. Always computed from the first activity so
  the values are correct regardless of the window; `from` \"all\" means
  from there."
  [ds from to]
  (let [daily (m/daily-tss (db/query ds ["SELECT a.date, m.tss FROM activities a
                                          JOIN activity_metrics m ON m.activity_id = a.id"]))
        start (or (first (sort (keys daily))) to)
        from  (if (= "all" from) start from)]
    (filterv #(>= (compare (:date %) from) 0) (m/pmc daily start to))))

(defn volume
  "Weekly volume for [from, to]; `from` \"all\" means from the first activity."
  [ds from to]
  (m/weekly-volume (activities ds {:from (when-not (= "all" from) from) :to to})))

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
     :intraday   (some-> (db/load-intraday ds date) (dissoc :date))
     ;; whether the day's detail was ever fetched (stored empty when Garmin had none)
     :intraday_saved (boolean (seq (db/query ds ["SELECT 1 FROM daily_intraday WHERE date = ?" date])))
     :intraday_window_start ((requiring-resolve 'coach.sync/intraday-window-start) (today))}))
