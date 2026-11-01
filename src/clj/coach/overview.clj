(ns coach.overview
  "The dashboard's coach overview (coach-dashboard-spec.md): this morning's
  readiness and vitals, the goal race, recovery over the last 30 days,
  training over the last 28 days and 26 weeks, and fitness over 6 months.

  Everything is worked out here; the dashboard only formats and draws. The
  pure functions take plain rows so each formula can be tested on its own.
  Field names are those of the local garmin_mcp server, which curates
  Garmin's JSON (see coach.garmin-mcp)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [coach.analysis :as analysis]
            [coach.athlete :as athlete]
            [coach.db :as db]
            [coach.garmin-mcp :as gm]
            [coach.season :as season]))

(def window-days "Recovery charts: the last 30 days, today included." 30)
(def period-days "Training KPIs: the last 28 days against the 28 before." 28)
(def history-days "Heatmap, weekly load, VO2 max and predictions: about 6 months." 182)
(def recent-count 12)

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- m [x] (when (map? x) x))
(defn- n? [x] (when (number? x) x))
(defn- round1 [x] (when (number? x) (/ (Math/round (* (double x) 10.0)) 10.0)))
(defn- round [x] (when (number? x) (Math/round (double x))))
(defn- sum [k rows] (reduce + 0.0 (keep #(n? (get % k)) rows)))
(defn- age "Days from `date` to `today`." [date today] (season/days-between date today))

(defn dates
  "The `n` ISO dates ending `today`, oldest first."
  [today n]
  (mapv #(season/add-days today (- %)) (range (dec n) -1 -1)))

(defn family
  "Sport family from Garmin's activity type key (spec §4)."
  [sport]
  (condp re-find (str/lower-case (str sport))
    #"run"                               "run"
    #"cycl|bik|ride"                     "bike"
    #"swim"                              "swim"
    #"hik|walk|mountaineer"              "hike"
    #"ski|snowboard"                     "ski"
    #"strength|fitness|cardio|hiit|yoga" "strength"
    "other"))

;; ---------------------------------------------------------------------------
;; This morning

(defn readiness
  "This morning's readiness from a daily_wellness raw map, nil before the
  watch has worked it out."
  [raw]
  (let [r (gm/morning-readiness (:readiness raw))]
    (when (n? (:score r))
      {:score      (:score r)
       :level      (:level r)
       :feedback   (:feedback r)
       :recovery_h (round (:recovery_time_hours r))
       :factors    {:sleep          (n? (:sleep_factor_percent r))
                    :recovery_time  (n? (:recovery_factor_percent r))
                    :acwr           (n? (:training_load_factor_percent r))
                    :stress_history (n? (:stress_history_factor_percent r))
                    :hrv            (n? (:hrv_factor_percent r))
                    :sleep_history  (n? (:sleep_history_factor_percent r))}})))

(defn night
  "One night from a daily_wellness row, its raw map and the sleep need."
  [date row raw need-min]
  (let [sl (or (m (:sleep raw)) {})
        hv (or (m (:hrv raw)) {})]
    {:date             date
     :sleep_score      (:sleep_score row)
     :quality          (:sleep_score_qualifier sl)
     :total_s          (:sleep_s row)
     :deep_s           (:deep_sleep_s row)
     :light_s          (n? (:light_sleep_seconds sl))
     :rem_s            (:rem_sleep_s row)
     :awake_s          (n? (:awake_seconds sl))
     :need_min         (n? need-min)
     :hrv_night        (round (:hrv_avg row))
     :hrv_weekly       (round (:hrv_weekly_avg row))
     :hrv_low_up       (n? (:baseline_low_upper_ms hv))
     :hrv_balanced_low (n? (:baseline_balanced_low_ms hv))
     :hrv_balanced_up  (n? (:baseline_balanced_upper_ms hv))
     :hrv_status       (:hrv_status row)}))

(defn day
  "One day's health summary from a daily_wellness row and its raw map."
  [date row raw]
  (let [st (or (m (:stats raw)) {})]
    {:date       date
     :rhr        (:rhr row)
     :stress_avg (:avg_stress row)
     :steps      (:steps row)
     :bb_min     (:body_battery_low row)
     :bb_max     (:body_battery_high row)
     :bb_latest  (n? (:body_battery_current st))
     :bb_charged (n? (:body_battery_charged st))
     :bb_drained (n? (:body_battery_drained st))}))

(defn vitals
  "The four tiles: sleep and HRV from the latest night, resting HR and Body
  Battery from the latest day (today, maybe partial)."
  [nights days]
  (let [n    (last nights)
        d    (last days)
        prev (keep :rhr (take-last 7 (butlast days)))
        rhr7 (when (seq prev) (round (/ (reduce + prev) (double (count prev)))))]
    {:sleep_score (:sleep_score n) :sleep_s (:total_s n) :sleep_quality (:quality n)
     :deep_s (:deep_s n) :light_s (:light_s n) :rem_s (:rem_s n) :awake_s (:awake_s n)
     :hrv_night (:hrv_night n) :hrv_weekly (:hrv_weekly n) :hrv_status (:hrv_status n)
     :rhr (:rhr d) :rhr_7d rhr7 :rhr_delta (when (and (:rhr d) rhr7) (- (:rhr d) rhr7))
     :bb_now (:bb_latest d) :bb_low (:bb_min d) :bb_high (:bb_max d)}))

;; ---------------------------------------------------------------------------
;; Training

(defn activity
  "An activity row (coach.analysis/activities) as the overview uses it."
  [a]
  {:id         (str (:id a))
   :date       (:date a)
   :start      (:start_time_local a)
   :sport      (:sport a)
   :family     (family (:sport a))
   :name       (:name a)
   :distance_m (n? (:distance_m a))
   :duration_s (n? (:duration_s a))
   :avg_hr     (round (:avg_hr a))
   :load       (n? (:garmin_load a))
   ;; [below Z1, Z1 ... Z5] seconds, from the app's own HR zones (coach.metrics)
   :zones_s    (:hr_zones_s a)})

(defn- totals [acts]
  {:hours    (round1 (/ (sum :duration_s acts) 3600))
   :km       (round (/ (sum :distance_m acts) 1000))
   :load     (round (sum :load acts))
   :sessions (count acts)})

(defn period
  "The last 28 days (today included) against the 28 before."
  [acts today]
  (let [in  (fn [lo hi] (filter #(let [a (age (:date %) today)] (and (<= lo a) (< a hi))) acts))
        now (totals (in 0 period-days))
        was (totals (in period-days (* 2 period-days)))]
    (assoc now
           :hours_delta    (round1 (- (:hours now) (:hours was)))
           :km_delta       (- (:km now) (:km was))
           :load_delta     (- (:load now) (:load was))
           :sessions_delta (- (:sessions now) (:sessions was)))))

(defn heatmap
  "One row per day with activity: load, minutes, sessions, names, and the
  family of the day's highest-load activity."
  [acts]
  (->> (group-by :date acts)
       (map (fn [[date xs]]
              (let [top (apply max-key #(or (:load %) 0) (sort-by :start xs))]
                {:date     date
                 :load     (round (sum :load xs))
                 :minutes  (round (/ (sum :duration_s xs) 60))
                 :sessions (count xs)
                 :family   (:family top)
                 :names    (str/join " · " (keep :name (sort-by :start xs)))})))
       (sort-by :date)
       vec))

(defn weekly
  "Load, hours, km and sessions per (Monday, family)."
  [acts]
  (->> (group-by (juxt #(season/monday (:date %)) :family) acts)
       (map (fn [[[week fam] xs]]
              {:week week :family fam
               :load (round (sum :load xs)) :hours (round1 (/ (sum :duration_s xs) 3600))
               :km (round1 (/ (sum :distance_m xs) 1000)) :sessions (count xs)}))
       (sort-by (juxt :week :family))
       vec))

(defn zones
  "Minutes in Z1-Z5 over the last 28 days' activities."
  [acts today]
  (let [xs (filter #(< (age (:date %) today) period-days) acts)]
    (vec (for [z (range 1 6)]
           {:zone (str "Z" z)
            :minutes (round (/ (reduce + 0.0 (keep #(n? (get (vec (:zones_s %)) z)) xs)) 60))}))))

(defn recent [acts]
  (->> acts
       (sort-by :start #(compare %2 %1))
       (take recent-count)
       (mapv (fn [a] {:id (:id a) :date (:date a) :sport (:sport a) :family (:family a) :name (:name a)
                      :km (some-> (:distance_m a) (/ 1000) round1) :duration_s (:duration_s a)
                      :avg_hr (:avg_hr a) :load (round (:load a))}))))

(defn status
  "Training status and load focus from a training_status raw map."
  [{:keys [status balance]}]
  (let [st (or (m status) {}) bl (or (m balance) {})
        band (fn [k] (or (m (get bl k)) {}))]
    {:status      (:training_status_feedback st)
     :device      (or (:reporting_device_name st) (:load_device_name bl))
     :acute       (n? (:acute_load st))
     :chronic     (n? (:chronic_load st))
     :chronic_min (round (:optimal_chronic_load_min st))
     :chronic_max (round (:optimal_chronic_load_max st))
     :acwr        (n? (:load_ratio st))
     :acwr_status (:acwr_status st)
     :aer_low     (round (:load (band :aerobic_low)))
     :aer_low_min (n? (:target_min (band :aerobic_low)))
     :aer_low_max (n? (:target_max (band :aerobic_low)))
     :aer_high    (round (:load (band :aerobic_high)))
     :aer_high_min (n? (:target_min (band :aerobic_high)))
     :aer_high_max (n? (:target_max (band :aerobic_high)))
     :anaer       (round (:load (band :anaerobic)))
     :anaer_min   (n? (:target_min (band :anaerobic)))
     :anaer_max   (n? (:target_max (band :anaerobic)))
     :balance     (:feedback bl)}))

;; ---------------------------------------------------------------------------
;; Goal race and fitness

(def leg-keys {"5k" :t5k "10k" :t10k "half" :thalf "marathon" :tmar})
(def leg-order ["5k" "10k" "half" "marathon"])

(defn monthly
  "The last row of each month (the newest row ends its month), oldest first."
  [rows]
  (->> (group-by #(subs (:date %) 0 7) rows)
       (map (fn [[_ xs]] (last (sort-by :date xs))))
       (sort-by :date)
       vec))

(defn race
  "The goal race (coach.athlete/race) against today's predictions: the legs'
  fresh-race predictions added up, and the margin to the goal."
  [{:keys [name date goal_s legs]} now trend today]
  (let [legs  (vec (sort-by #(.indexOf ^java.util.List leg-order %) legs))
        times (map #(get now (leg-keys %)) legs)
        total (when (and (seq legs) (every? number? times)) (round (reduce + times)))]
    {:name         name
     :date         date
     :goal_s       goal_s
     :legs         legs
     :now          (select-keys now [:t5k :t10k :thalf :tmar])
     :trend        (mapv #(select-keys % [:date :t10k :thalf :tmar]) trend)
     :total_s      total
     :margin_s     (when (and total goal_s) (- goal_s total))
     :days_to_race (when date (season/days-between today date))}))

(defn endurance
  "Score, class and gauge limits. garmin_mcp gives the class limits but not
  the gauge's ends, so those are two average class widths beyond the
  Intermediate and Elite limits (always wide enough to hold the score)."
  [score raw]
  (let [{:keys [intermediate trained well_trained expert superior elite]} (m (:thresholds raw))]
    (when (n? score)
      {:score          (round score)
       :classification (:classification raw)
       :limits (when (and (n? intermediate) (n? elite))
                 (let [w (/ (- elite intermediate) 5.0)]
                   {:low          (round (min (- intermediate (* 2 w)) score))
                    :intermediate intermediate :trained trained :well_trained well_trained
                    :expert expert :superior superior :elite elite
                    :high         (round (max (+ elite (* 2 w)) score))}))})))

;; ---------------------------------------------------------------------------
;; Queries

(defn- raw-of [s] (some-> s (json/read-str :key-fn keyword)))

(defn- latest-row
  "The newest row of `table` on or before `date`."
  [ds table date]
  (first (db/query ds [(str "SELECT * FROM " table " WHERE date <= ? ORDER BY date DESC LIMIT 1") date])))

(defn- wellness-window [ds today]
  (let [ds-dates (dates today window-days)
        rows  (into {} (map (juxt :date identity))
                    (db/query ds ["SELECT * FROM daily_wellness WHERE date BETWEEN ? AND ?"
                                  (first ds-dates) today]))
        needs (into {} (map (juxt :date :need_min))
                    (db/query ds ["SELECT date, need_min FROM sleep_need WHERE date BETWEEN ? AND ?"
                                  (first ds-dates) today]))]
    (for [d ds-dates :let [row (get rows d) raw (raw-of (:raw row))]]
      {:date d :row row :raw raw :need (get needs d)})))

(defn overview
  "Everything the dashboard's coach overview shows for `today`."
  [ds today]
  (let [from     (season/add-days today (- (dec history-days)))
        wellness (wellness-window ds today)
        nights   (mapv #(night (:date %) (:row %) (:raw %) (:need %)) wellness)
        days     (mapv #(day (:date %) (:row %) (:raw %)) wellness)
        acts     (mapv activity (analysis/activities ds {:from from :to today}))
        preds    (db/query ds ["SELECT date, t5k, t10k, thalf, tmar FROM race_predictions
                                WHERE date BETWEEN ? AND ? ORDER BY date" from today])
        now      (or (last preds) (latest-row ds "race_predictions" today))
        st       (latest-row ds "training_status" today)
        es       (latest-row ds "endurance_score" today)
        vo2      (db/query ds ["SELECT date, value AS vo2, carried_forward FROM vo2max
                                WHERE sport = 'running' AND date BETWEEN ? AND ? ORDER BY date" from today])]
    {:date      today
     :readiness (readiness (:raw (last wellness)))
     :vitals    (vitals nights days)
     :nights    nights
     :days      days
     :period    (period acts today)
     :heatmap   (heatmap acts)
     :weekly    (weekly acts)
     :zones     (zones acts today)
     :status    (when st (status (raw-of (:raw st))))
     :recent    (recent acts)
     ;; each day it changed, and today's value so the line reaches today
     :vo2       (let [changed (filterv #(zero? (:carried_forward %)) vo2)
                      rows    (cond-> changed
                                (and (seq vo2) (not= (last vo2) (last changed))) (conj (last vo2)))]
                  (mapv #(select-keys % [:date :vo2]) rows))
     :race      (race (athlete/race) now (monthly preds) today)
     :endurance (when es (endurance (:score es) (raw-of (:raw es))))}))
