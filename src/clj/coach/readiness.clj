(ns coach.readiness
  "Morning readiness check (training-principles.md §7.1, thresholds in athlete.md §A3). Pure: takes the
  recent wellness rows and today's planned workouts, returns the signals and
  a suggested change per workout. Nothing is applied without approval."
  (:require [coach.plan-rules :as rules]))

(def hrv-low 46)
(def rhr-high 57)

(defn- easier [session-id key?]
  (cond key?                     "EASY"
        (#{"EASY" "EASY_ST"} session-id) "REC"
        :else                    session-id))

(defn check
  "`days` are wellness rows, oldest first, ending today (:hrv_avg :hrv_status
  :rhr :sleep_s :sleep_score :readiness :body_battery_high). `workouts` are
  today's planned workouts. `week-no` decides whether LONG counts as key."
  [days workouts week-no]
  (let [t        (last days)
        hrv      (:hrv_avg t)
        rhr      (:rhr t)
        statuses (map #(some-> (:hrv_status %) clojure.string/upper-case) (take-last 2 days))
        hrv-bad  (and hrv (< hrv hrv-low))
        rhr-bad  (and rhr (>= rhr rhr-high))
        unbal    (and (= 2 (count statuses)) (every? #{"UNBALANCED" "LOW"} statuses))
        sleep-bad (or (and (:sleep_s t) (< (:sleep_s t) (* 6 3600)))
                      (and (:sleep_score t) (< (:sleep_score t) 50)))
        ready-bad (and (:readiness t) (< (:readiness t) 30))
        bb-bad   (and (:body_battery_high t) (< (:body_battery_high t) 30))
        signals  (cond-> []
                   hrv-bad   (conj (str "HRV " (Math/round (double hrv)) " ms is below " hrv-low))
                   rhr-bad   (conj (str "Resting HR " rhr " is " rhr-high " or higher"))
                   unbal     (conj "HRV unbalanced or low for 2 days: reduce the week by 20%, no key session")
                   sleep-bad (conj "Short or poor sleep: move morning runs to the evening, or rest")
                   ready-bad (conj (str "Training readiness " (:readiness t) " is below 30"))
                   bb-bad    (conj (str "Body battery " (:body_battery_high t) " this morning is below 30")))
        rest?    (and hrv-bad rhr-bad)
        no-key?  (or hrv-bad rhr-bad unbal ready-bad bb-bad)]
    {:signals signals
     :level   (cond rest? :rest (seq signals) :adjust :else :ok)
     :changes (vec
               (for [w workouts
                     :let [key? (rules/key-session? w week-no)
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
