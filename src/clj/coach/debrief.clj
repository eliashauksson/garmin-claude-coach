(ns coach.debrief
  "The coach's short note after a workout: planned vs done and what it means for
  the week. Written once per activity (after a sync, through the half-price batch;
  on request, instantly), stored in activity_debriefs and shown on the activity
  page and Today. Claude only comments: it changes nothing in the plan."
  (:require [clojure.data.json :as json]
            [coach.ai-settings :as ai]
            [coach.analysis :as analysis]
            [coach.athlete :as athlete]
            [coach.claude :as claude]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.plan :as plan]
            [coach.season :as season]
            [coach.translate :as translate]
            [coach.workout :as workout]))

(def window-days "Activities from this many days back (today included) get a note automatically." 3)

(def min-active-s "Shorter activities (a walk to the bus) get no automatic note." 600)

(def system-intro
  "You write the coach's note after one workout. The athlete's data for it is in the user turn: the
activity, the planned session it fulfilled (goal and blocks, planned vs actual per step), how the athlete
rated it (often not yet: the note is written when the workout syncs), the week so
far and the current load and readiness.

How to write:
- `text`: at most 2 short sentences (under 40 words), plain words, to the athlete (\"you\"). Say what
  mattered most against the plan or, with no plan, against the training principles, and what it means for
  the next days. Skip anything that went as planned.
  Use only numbers that are in the data. Never praise by habit and never invent a cause.
- Pain or an RPE far above what the session called for comes first: say what to watch and, if it
  is more than a passing ache, to see a doctor or physio. Don't diagnose.
- No planned session: just describe the effort and its place in the week.
- You change nothing in the plan. If the plan should change, say so in one sentence (the athlete
  can ask for an adjustment).
- `takeaway`: at most 8 words, the one thing to remember (shown on the dashboard).")

(def schema
  {"type" "object"
   "additionalProperties" false
   "required" ["text" "takeaway"]
   "properties" {"text" {"type" "string"} "takeaway" {"type" "string"}}})

;; ---------------------------------------------------------------------------
;; What the coach sees

(defn- r1 [x] (when (number? x) (/ (Math/round (* 10.0 x)) 10.0)))
(defn- r0 [x] (when (number? x) (Math/round (double x))))

(def max-steps "Compared steps sent; a long interval session is cut." 24)

(defn steps-vs-actual
  "The planned steps with what the stream shows, from coach.workout/compare-segments: only the steps
  with a target and samples. `blocks` are the session's blocks, `stream` the activity's."
  [blocks stream]
  (when (and (seq blocks) (seq (:t stream)))
    (->> (workout/compare-segments (workout/expand blocks) stream)
         (filter :verdict)
         (take max-steps)
         (mapv (fn [{:keys [label target low high actual verdict reps rep]}]
                 (cond-> {:block label :target target :planned_low (r1 low) :planned_high (r1 high)
                          :actual (r1 actual) :verdict (name verdict)}
                   (< 1 (or reps 1)) (assoc :repeat (str rep "/" reps))))))))

(defn context
  "Everything the note is written from, for activity `a` (a row of coach.analysis/activities)."
  [ds a]
  (let [date    (:date a)
        monday  (season/monday date)
        planned (plan/planned-for-activity ds a)
        [stream] (analysis/stream-with-hr ds a)
        pmc     (last (analysis/pmc ds date date))
        well    (first (analysis/wellness ds date date))]
    {:today    (season/today)
     :activity (cond-> {:date date :sport (:sport a) :name (:name a)
                        :km (r1 (some-> (:distance_m a) (/ 1000.0)))
                        :minutes (r0 (/ (analysis/active-secs a) 60.0))
                        :avg_hr (r0 (:avg_hr a)) :max_hr (r0 (:max_hr a)) :elevation_m (r0 (:elev_gain_m a))
                        :tss (r0 (:tss a)) :intensity_factor (r1 (:intensity a))}
                 (:garmin_load a) (assoc :garmin_load (r0 (:garmin_load a))))
     :planned  (when planned
                 (-> (select-keys planned [:title :session_id :duration_min :distance_km :intensity :description
                                           :goal :deviation])
                     (assoc :steps_vs_actual (steps-vs-actual (:blocks planned) stream))))
     :feedback (not-empty (into {} (remove (comp nil? val))
                                {:rpe (some-> (analysis/effective-rpe a) r0)
                                 :pain (when (pos? (or (:pain a) 0)) true)
                                 :pain_location (:pain_location a) :note (:note a)}))
     :week     (vec (for [w (plan/workouts ds monday (season/add-days monday 6))]
                      (select-keys w [:date :slot :session_id :title :duration_min :status])))
     :load     (select-keys pmc [:ctl :atl :tsb])
     :readiness_that_morning (select-keys well [:readiness :hrv_avg :hrv_status :rhr :sleep_score])}))

(defn user-message [ctx]
  (str "Write the coach's note for this workout.\n\n" (json/write-str ctx)))

;; ---------------------------------------------------------------------------
;; Storage

(defn row
  "The stored note {:activity_id :status :text :takeaway :model :error :updated_at}, or nil."
  [ds activity-id]
  (first (db/query ds ["SELECT * FROM activity_debriefs WHERE activity_id = ?" activity-id])))

(defn- save! [ds activity-id {:keys [status text takeaway model error]}]
  (db/query ds ["INSERT INTO activity_debriefs (activity_id, status, text, takeaway, model, error, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(activity_id) DO UPDATE SET status = excluded.status, text = excluded.text,
                   takeaway = excluded.takeaway, model = excluded.model, error = excluded.error,
                   updated_at = excluded.updated_at"
                activity-id status text takeaway model error (str (java.time.Instant/now))]))

(defn- finish! [ds activity-id response model]
  (save! ds activity-id {:status "done" :text (:text response) :takeaway (:takeaway response) :model model})
  (claude/translate-soon! [(:text response) (:takeaway response)]))

(defn- fail! [ds activity-id msg]
  (save! ds activity-id {:status "failed" :error msg}))

(defn shown
  "The note as the API shows it: {:status :text :takeaway :error} or nil. Claude's texts are
  localized to the current language."
  [ds activity-id]
  (some->> (row ds activity-id)
           (#(select-keys % [:status :text :takeaway :error]))
           (translate/localize ds @i18n/lang)))

(defn latest
  "The newest finished note from the last `days` days with its activity, for Today:
  {:activity_id :date :name :text :takeaway}, or nil."
  [ds today days]
  (some->> (db/query ds ["SELECT d.activity_id, a.date, a.name, d.text, d.takeaway
                          FROM activity_debriefs d JOIN activities a ON a.id = d.activity_id
                          WHERE d.status = 'done' AND a.date >= ?
                          ORDER BY a.start_time_local DESC LIMIT 1"
                         (season/add-days today (- days))])
           first
           (translate/localize ds @i18n/lang)))

(defn takeaways
  "activity id -> takeaway of every finished note since `from` (a date), for the planner."
  [ds from]
  (into {} (map (juxt :activity_id :takeaway))
        (db/query ds ["SELECT d.activity_id, d.takeaway
                       FROM activity_debriefs d JOIN activities a ON a.id = d.activity_id
                       WHERE d.status = 'done' AND a.date >= ? AND d.takeaway <> ''" from])))

(defn recent
  "The takeaways of the newest finished notes from the last `days` days, newest first, for the
  planner and the coach chat: [{:date :name :takeaway}]."
  [ds today days n]
  (vec (db/query ds ["SELECT a.date, a.name, d.takeaway
                      FROM activity_debriefs d JOIN activities a ON a.id = d.activity_id
                      WHERE d.status = 'done' AND a.date >= ?
                      ORDER BY a.start_time_local DESC LIMIT ?"
                     (season/add-days today (- days)) n])))

;; ---------------------------------------------------------------------------
;; Which activities get a note

(defn due-from
  "The activities that should get a note now: from the last `window-days` days, long enough, with no note
  row yet (a failed one is retried by the athlete, not on every sync). It doesn't wait for the rating.
  `acts` as from coach.analysis/activities, `have` the ids with a row."
  [acts have today]
  (->> acts
       (remove #(have (:id %)))
       (filter #(>= (compare (:date %) (season/add-days today (- (dec window-days)))) 0))
       (filter #(>= (analysis/active-secs %) min-active-s))
       (sort-by :start_time_local)
       vec))

(defn due [ds today]
  (due-from (analysis/activities ds {:from (season/add-days today (- (dec window-days))) :to today})
            (set (map :activity_id (db/query ds ["SELECT activity_id FROM activity_debriefs"])))
            today))

;; ---------------------------------------------------------------------------
;; Asking Claude

(defn- opts [ds a more?]
  {:knowledge (claude/knowledge (athlete/content))
   :system    system-intro
   :messages  [[:user (user-message (context ds a))]]
   :schema    schema
   :task      :debrief
   :ref       (str "activity " (:id a))
   ;; the next note of the same run reads the knowledge from the cache
   :cache?    (boolean more?)})

(claude/register-batch-handler!
 :debrief
 {:done (fn [ds {:keys [id]} response usage]
          (finish! ds id response (:model usage)))
  :fail (fn [ds {:keys [id]} msg] (fail! ds id msg))})

(defn write!
  "Writes the note for activity `a` now (instant). The row goes pending first, so the page shows it
  working; a failure is kept on the row. Returns the stored row."
  [ds a & [more?]]
  (save! ds (:id a) {:status "pending"})
  (try
    (let [{:keys [response usage]} (claude/request (opts ds a more?))]
      (finish! ds (:id a) response (:model usage)))
    (catch Throwable e
      (fail! ds (:id a) (or (ex-message e) (str (class e))))))
  (row ds (:id a)))

(defn submit!
  "Sends the note for `a` through the half-price batch; the result is stored by the batch handler."
  [ds a]
  (save! ds (:id a) {:status "pending"})
  (try
    (claude/submit-batch! ds (assoc (opts ds a false) :data {:id (:id a)}))
    (catch Throwable e
      (fail! ds (:id a) (or (ex-message e) (str (class e)))))))

(defn queue!
  "Writes the notes for the activities `acts`, one after the other: through the batch when the task's
  delivery says so for `trigger` (:auto after a sync, :manual for a click), else instantly. Returns
  immediately when the work is done in the background; the ids queued."
  [ds acts trigger]
  (let [acts (vec acts)]
    (when (seq acts)
      (if (ai/batch? :debrief trigger)
        (doseq [a acts] (submit! ds a))
        (doseq [[i a] (map-indexed vector acts)] (write! ds a (< i (dec (count acts))))))
      (mapv :id acts))))

(defn- paused?
  "Automatic notes wait while the credits are out, the spending limit is reached or there is no key
  (like the automatic planning)."
  [ds]
  (or (not (claude/api-key?)) (costs/credits-out ds) (costs/limit-reached ds)))

(defn- queue-soon! [ds acts]
  (when (and (seq acts) (not (paused? ds)))
    (future (try (queue! ds acts :auto)
                 (catch Throwable e (println "Coach's notes failed:" (ex-message e)))))
    (mapv :id acts)))

(defn after-sync!
  "Notes for the activities that are due, started in the background (a sync doesn't wait for Claude)."
  [ds]
  (queue-soon! ds (due ds (season/today))))

(defn write-soon!
  "A note on request, for any activity (the \"Write a coach's note\" button and its retry): instant, in
  the background. The row is pending when this returns. Returns it."
  [ds a]
  (save! ds (:id a) {:status "pending"})
  (future (try (write! ds a)
               (catch Throwable e (fail! ds (:id a) (or (ex-message e) (str (class e)))))))
  (row ds (:id a)))
