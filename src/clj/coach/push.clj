(ns coach.push
  "Sends one planned workout to the athlete's watch (Garmin) or bike computer
  (COROS). The only place the app writes to either: only when the athlete
  presses the button, never during a sync. The workout lands in the Garmin
  Connect calendar / the COROS schedule and reaches the device at its next
  sync. What was sent is kept on the planned workout, so sending again
  replaces it instead of piling up copies."
  (:require [coach.api.jobs :as jobs]
            [coach.auth :as auth]
            [coach.coros-auth :as coros-auth]
            [coach.coros-mcp :as coros]
            [coach.device-workout :as dw]
            [coach.garmin-mcp :as garmin]
            [coach.i18n :refer [t]]
            [coach.plan :as plan]
            [coach.plan-rules :as rules]
            [coach.season :as season])
  (:import (java.time Instant)))

(defn- kind [w] (get-in rules/sessions [(:session_id w) :kind]))

(defn- loaded
  "Workout `id` with its :kind, or an error."
  [ds id]
  (let [w (plan/workout ds id)]
    (when-not w (throw (ex-info (t "Workout not found.") {})))
    (assoc w :kind (kind w))))

(defn- reason-message [reason service]
  (let [device (if (= :garmin service) "Garmin" "Coros")]
    (case reason
      :not-planned (t "Only planned sessions can be sent.")
      :past        (t "This session's day has passed.")
      :unsupported (t "{0} can't receive this kind of session." device)
      :too-far     (t "Coros takes workouts up to {0} days ahead." dw/coros-max-days)
      (t "Not sent."))))

(defn- check! [w service]
  (when-let [reason (dw/sendable w service (season/today))]
    (throw (ex-info (reason-message reason service) {:reason reason}))))

(defn info
  "For the workout's page: per device, why it can't be sent (:reason, or nil)
  and what was sent (:sent_at, :changed after an edit; COROS also :date)."
  [w]
  (let [w (assoc w :kind (kind w))
        sent (fn [at] (when at {:sent_at at :changed (pos? (compare (str (:updated_at w)) at))}))]
    {:garmin (merge {:reason (or (dw/sendable w :garmin (season/today))
                                 (when-not (auth/connected?) :not-connected))}
                    (sent (:garmin_sent_at w)))
     :coros  (merge {:reason (or (dw/sendable w :coros (season/today))
                                 (when-not (coros-auth/connected?) :not-connected))
                     :date   (:coros_date w)}
                    (sent (:coros_sent_at w)))}))

(defn- now [] (str (Instant/now)))

(defn- exclusive
  "Runs `f` as the athlete's one Garmin session: refused while a sync runs,
  and a sync can't start meanwhile."
  [f]
  (let [[old _] (swap-vals! jobs/sync-state #(if (:running %) % (assoc % :running true :job "push")))]
    (when (:running old)
      (throw (ex-info (t "A sync is running. Try again when it is done.") {:kind :busy})))
    (try (f)
         (finally (swap! jobs/sync-state assoc :running false :job nil)))))

(defn garmin!
  "Sends workout `id` to Garmin Connect (a library workout, scheduled on its
  date). A workout sent before is deleted first. Returns the workout."
  [ds id]
  (let [w (loaded ds id)]
    (check! w :garmin)
    (when-not (auth/connected?) (throw (ex-info (t "Garmin isn't connected. Connect it in Settings.") {:kind :auth})))
    (exclusive
     (fn []
       (let [client (garmin/start-writer!)]
         (try
           (when-let [old (:garmin_workout_id w)]
             (try (garmin/call-write client "delete_workout" {"workout_id" old "confirm" true})
                  (catch Throwable e (println "Garmin: old workout" old "not deleted:" (ex-message e)))))
           (let [up (garmin/call-write client "upload_workout" {"workout_data" (dw/garmin-workout w)})
                 wid (some-> (:workout_id up) long)]
             (when-not wid
               (throw (ex-info (t "Garmin didn't say which workout it made.") {:kind :garmin})))
             (garmin/call-write client "schedule_workout" {"workout_id" wid "calendar_date" (:date w)})
             (plan/mark-sent! ds id {:garmin_workout_id wid :garmin_sent_at (now)}))
           (finally (garmin/stop! {:client client}))))))
    (plan/workout ds id)))

(defn coros!
  "Sends workout `id` to the COROS schedule on its date. Sent before on the
  same date: that one is updated. Sent before on another date: COROS can't
  move or delete it through the connector, so a new one is made and :old_left
  names the date of the old one. Returns the workout (with :old_left)."
  [ds id]
  (let [w (loaded ds id)]
    (check! w :coros)
    (let [client  (coros/connect!)
          course  (dw/coros-course w (get-in rules/sessions [(:session_id w) :label]))
          date    (dw/coros-date (:date w))
          old-id  (:coros_id_in_plan w)
          same?   (and old-id (= (:coros_date w) (:date w)))]
      (try
        (let [answer (if same?
                       (coros/call-write client "updateScheduledWorkout"
                                         {"date" date "idInPlan" old-id "course" course})
                       (coros/call-write client "createScheduledWorkout" {"date" date "course" course}))
              new-id (or (coros/id-in-plan answer) (when same? old-id))]
          (plan/mark-sent! ds id {:coros_id_in_plan new-id :coros_date (:date w) :coros_sent_at (now)})
          (cond-> (plan/workout ds id)
            (and old-id (not same?)) (assoc :old_left (:coros_date w))))
        (finally (coros/stop! {:client client}))))))
