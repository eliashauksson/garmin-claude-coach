(ns coach.readiness
  "Morning readiness check (training-principles.md: Monitoring; thresholds in athlete.md: Baselines). Pure: takes the
  recent wellness rows and today's planned workouts, returns the signals and
  a suggested change per workout. Nothing is applied without approval."
  (:require [clojure.string :as str]
            [coach.i18n :as i18n]
            [coach.plan-rules :as rules]))

(defn- easier [session-id key?]
  (cond key?                                         "EASY"
        (#{"EASY" "EASY_ST" "HILL_SPRINT" "DOWNHILL"} session-id) "REC"
        :else                                        session-id))

(defn check
  "`days` are wellness rows, oldest first, ending today (:hrv_avg :hrv_status
  :rhr :sleep_s :sleep_score :readiness :body_battery_high; :readiness_estimated when
  :readiness is the app's estimate). `workouts` are
  today's planned workouts. `settings` are the athlete's thresholds (:hrv_low :rhr_high, athlete.md: Baselines);
  a check without its threshold is skipped."
  [days workouts {:keys [hrv_low rhr_high]}]
  (let [t        (last days)
        hrv      (:hrv_avg t)
        rhr      (:rhr t)
        statuses (map #(some-> (:hrv_status %) str/upper-case) (take-last 2 days))
        hrv-bad  (and hrv hrv_low (< hrv hrv_low))
        rhr-bad  (and rhr rhr_high (>= rhr rhr_high))
        unbal    (and (= 2 (count statuses)) (every? #{"UNBALANCED" "LOW"} statuses))
        sleep-bad (or (and (:sleep_s t) (< (:sleep_s t) (* 6 3600)))
                      (and (:sleep_score t) (< (:sleep_score t) 50)))
        ready-bad (and (:readiness t) (< (:readiness t) 30))
        bb-bad   (and (:body_battery_high t) (< (:body_battery_high t) 30))
        signals  (cond-> []
                   hrv-bad   (conj (i18n/t "HRV {0} ms is below {1}" (Math/round (double hrv)) hrv_low))
                   rhr-bad   (conj (i18n/t "Resting HR {0} is {1} or higher" rhr rhr_high))
                   unbal     (conj (i18n/t "HRV unbalanced or low for 2 days: rest of the week as recovery, plan next week lower"))
                   sleep-bad (conj (i18n/t "Short or poor sleep: move morning runs to the evening, or rest"))
                   ready-bad (conj (if (:readiness_estimated t)
                                     (i18n/t "Estimated training readiness {0} is below 30 (Garmin has no score this morning)" (:readiness t))
                                     (i18n/t "Training readiness {0} is below 30" (:readiness t))))
                   bb-bad    (conj (i18n/t "Body battery {0} this morning is below 30" (:body_battery_high t))))
        rest?    (and hrv-bad rhr-bad)
        no-key?  (or hrv-bad rhr-bad unbal ready-bad bb-bad)]
    {:signals signals
     :level   (cond rest? :rest (seq signals) :adjust :else :ok)
     :changes (vec
               (for [w workouts
                     :let [key? (rules/key-session? w)
                           run? (rules/run? w)
                           to   (cond
                                  (and rest? run?)                   "REST"
                                  (and key? no-key?)                 "EASY"
                                  (and hrv-bad run?)                 (easier (:session_id w) false)
                                  :else nil)
                           move (and sleep-bad run? (= "AM" (:slot w)) (not= to "REST"))]
                     :when (or (and to (not= to (:session_id w))) move)]
                 (cond-> {:workout_id (:id w) :from (:session_id w)}
                   (and to (not= to (:session_id w))) (assoc :to to)
                   move (assoc :slot "PM"))))}))
