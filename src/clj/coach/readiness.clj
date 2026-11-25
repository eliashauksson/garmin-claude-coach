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
  :rhr :sleep_s :sleep_score :readiness :body_battery_high; :resp_sleep with :resp_limit, the athlete's normal,
  where the watch measures breathing; :readiness_estimated when
  :readiness is the app's estimate). `workouts` are
  today's planned workouts. `settings` are the athlete's thresholds (:hrv_low :rhr_high, athlete.md: Baselines);
  a check without its threshold is skipped. `cycle` is the phase (keyword), or {:phase :cycles :hrv_delta :rhr_delta}
  with her own usual shift in it, when the athlete tracks their cycle: in the luteal phase HRV falls and
  resting HR rises a little (BMJ Open SEM 2021), so one of the two alone is noted as likely cycle-related and
  changes nothing, unless three cycles show that her luteal phase usually doesn't shift her numbers."
  ([days workouts settings] (check days workouts settings nil))
  ([days workouts {:keys [hrv_low rhr_high]} cycle]
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
        resp-bad (and (:resp_sleep t) (:resp_limit t) (>= (:resp_sleep t) (:resp_limit t)))
        signals  (cond-> []
                   hrv-bad   (conj (i18n/t "HRV {0} ms is below {1}" (Math/round (double hrv)) hrv_low))
                   rhr-bad   (conj (i18n/t "Resting HR {0} is {1} or higher" rhr rhr_high))
                   unbal     (conj (i18n/t "HRV unbalanced or low for 2 days: rest of the week as recovery, plan next week lower"))
                   sleep-bad (conj (i18n/t "Short or poor sleep: move morning runs to the evening, or rest"))
                   ready-bad (conj (if (:readiness_estimated t)
                                     (i18n/t "Estimated training readiness {0} is below 30 (Garmin has no score this morning)"
                                             (:readiness t))
                                     (i18n/t "Training readiness {0} is below 30" (:readiness t))))
                   bb-bad    (conj (i18n/t "Body battery {0} this morning is below 30" (:body_battery_high t)))
                   resp-bad  (conj (i18n/t "Breathing rate in your sleep ({0} per minute) is above your normal"
                                           (:resp_sleep t))))
        {:keys [phase cycles hrv_delta rhr_delta]} (if (map? cycle) cycle {:phase cycle})
        no-shift? (and (>= (or cycles 0) 3) (some? hrv_delta) (> hrv_delta -1) (< (or rhr_delta 0) 1))
        luteal?  (and (#{:luteal :late-luteal} phase) (not no-shift?))
        ;; one vital low in the luteal phase, nothing else wrong: the usual shift, not fatigue
        explained? (and luteal? (not= (boolean hrv-bad) (boolean rhr-bad))
                        (not (or unbal sleep-bad ready-bad bb-bad resp-bad)))
        signals  (cond-> signals
                   explained? (conj (i18n/t "Luteal phase: HRV tends to be lower and resting HR higher now, likely cycle-related")))
        rest?    (and hrv-bad rhr-bad)
        no-key?  (and (not explained?) (or hrv-bad rhr-bad unbal ready-bad bb-bad))
        hrv-bad  (and hrv-bad (not explained?))]
    {:signals signals
     :level   (cond rest? :rest explained? :ok (seq signals) :adjust :else :ok)
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
                   move (assoc :slot "PM"))))})))
