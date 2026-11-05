(ns coach.planner
  "Asks Claude to propose one week of training from training-principles.md,
  athlete.md and the athlete's recent data. The proposal is stored for review; nothing
  enters the plan until the athlete accepts it."
  (:require [clojure.data.json :as json]
            [coach.adapt :as adapt]
            [coach.ai-settings :as ai]
            [coach.analysis :as analysis]
            [coach.athlete :as athlete]
            [coach.claude :as claude]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.plan-rules :as rules]
            [coach.season :as season]
            [coach.translate :as translate]
            [coach.workout :as workout]
            [malli.core :as m]
            [malli.error :as me]
            [next.jdbc])
  (:import (java.time Duration LocalDate)))

;; ---------------------------------------------------------------------------
;; Context (what the coach sees)

(defn- r1 [x] (when x (/ (Math/round (* 10.0 x)) 10.0)))
(defn- run-act? [a] (re-find #"running" (str (:sport a))))
(defn- march? [a] (and (re-find #"hiking|walking" (str (:sport a)))
                       (>= (or (:moving_s a) (:duration_s a) 0) 5400)))
(defn- gym? [a] (re-find #"strength" (str (:sport a))))

(defn week-summary
  "Weekly metrics from recorded activities. Easy = runs with avg HR at or
  below the top of the athlete's Easy zone (athlete.md: Thresholds & zones); without it, no easy share."
  [monday acts]
  (let [runs (filter run-act? acts)
        rt   (reduce + 0 (map analysis/active-secs runs))
        easy-max (:easy_hr_max (athlete/settings))
        easy (reduce + 0
                     (map analysis/active-secs (filter #(and easy-max (:avg_hr %) (<= (:avg_hr %) easy-max)) runs)))]
    {:monday       monday
     :run_km       (r1 (/ (reduce + 0 (keep :distance_m runs)) 1000.0))
     :run_hours    (r1 (/ rt 3600.0))
     :run_min      (Math/round (/ rt 60.0))
     :longest_min  (Math/round (/ (apply max 0 (map analysis/active-secs runs)) 60.0))
     :runs         (count runs)
     :easy_pct     (when (and easy-max (pos? rt)) (Math/round (* 100.0 (/ easy rt))))
     :longest_km   (r1 (/ (apply max 0 (keep :distance_m runs)) 1000.0))
     :marches      (count (filter march? acts))
     :march_hours  (r1 (/ (reduce + 0 (map analysis/active-secs (filter march? acts))) 3600.0))
     :gym_sessions (count (filter gym? acts))
     :tss          (Math/round (double (reduce + 0 (keep :tss acts))))}))

(defn proposed-week
  "Sessions still awaiting review in the latest proposal for the week of `monday`."
  [ds monday]
  (let [p (plan/latest-proposal ds monday)]
    (when (= "open" (:status p))
      (not-empty (vec (for [i (:items p) :when (= "pending" (:decision i))]
                        (select-keys i [:date :slot :session_id :title :distance_km :duration_min])))))))

(defn context
  "Everything the planner needs for the week starting `monday`."
  [ds monday]
  (let [today   (str (LocalDate/now))
        from    (season/add-days monday -42)
        acts    (analysis/activities ds {:from from :to today})
        by-week (group-by #(season/monday (:date %)) acts)
        weeks   (for [i (range 6 0 -1) :let [m (season/add-days monday (* -7 i))]]
                  (week-summary m (by-week m)))
        pmc     (last (analysis/pmc ds today today))]
    {:today            today
     :target_week      (plan/week-info ds monday)
     :last_6_weeks     weeks
     :recent_activities (vec (for [a (filter #(>= (compare (:date %) (season/add-days today -14)) 0) acts)]
                               (merge
                                {:date (:date a) :sport (:sport a) :name (:name a)
                                 :km (r1 (some-> (:distance_m a) (/ 1000.0)))
                                 :minutes (Math/round (/ (analysis/active-secs a) 60.0))
                                 :avg_hr (some-> (:avg_hr a) Math/round) :tss (some-> (:tss a) Math/round)}
                                ;; how it went, in the athlete's words (coach.analysis/save-feedback!)
                                (when-let [r (analysis/effective-rpe a)] {:rpe (Math/round (double r))})
                                (when (pos? (or (:pain a) 0)) {:pain true :pain_location (:pain_location a)})
                                (when (:note a) {:note (:note a)}))))
     :readiness_last_7_days (vec (for [w (analysis/wellness ds (season/add-days today -6) today)]
                                   (select-keys w [:date :hrv_avg :hrv_status :rhr :sleep_s :sleep_score
                                                   :readiness :body_battery_high])))
     :load_today       (select-keys pmc [:ctl :atl :tsb])
     :cycling_power    (when-let [p (analysis/cycling-power ds today)]
                         (not-empty (into {} (remove (comp nil? val))
                                          {:ftp_w (some-> (:ftp p) Math/round) :ftp_source (:ftp_source p)
                                           :w_per_kg (:wkg p) :vo2max_estimate (:vo2max_est p)})))
     :season_plan_target (plan/season-target ds monday)
     :proposed_previous_week (proposed-week ds (season/add-days monday -7))
     :planned_previous_and_target_weeks
     (vec (for [w (plan/workouts ds (season/add-days monday -7) (season/add-days monday 6))]
            (select-keys w [:date :slot :session_id :title :distance_km :duration_min :status])))}))

;; ---------------------------------------------------------------------------
;; Prompt and response schema

(def structure-guide
  "How Claude writes a session's goal and blocks (coach.workout); shared by
  the week plan, adjustments and the one-time backfill."
  "- `goal`: 1-2 short sentences: what the session is for and what to focus on while doing it.
- `blocks`: the session's structure in order, the athlete's watch-free guide and what the
  recorded activity is compared with. A block has a `label` (a few words), `repeat` (1, or the
  number of repetitions) and `steps`, each with `kind` (warmup, steady, work, recovery,
  cooldown), `duration_s`, `target`, `from`, `to` and a short `note` (\"\" when nothing to add).
  The steps' durations times their repeats add up to `duration_min`. Choose each step's target
  by what matters in it: `hr` when the point is staying in a zone (easy and long running,
  warm-ups, recoveries), `pace` when it is about a specific speed (race pace, tempo,
  threshold, intervals), `power` for rides with a power meter, `open` when no number applies
  (strides, hill sprints by feel, gym, hikes). Use the athlete's zones and thresholds.
  `from`/`to` hold the range: bpm or watts as numbers in a string (\"135\"), pace as min/km
  \"m:ss\" (\"4:20\"); one value to hold goes in both; both \"\" for open. Gym sessions
  may have no blocks.")

(def system-intro
  (str
  "You are the running coach for the athlete described in the two documents above: the general
training principles and athlete.md (the athlete's profile, goal and season plan). Refer to
sections by their heading. Rules in athlete.md override the principles' (d) defaults.
You plan one week at a time; the athlete reviews every session before it enters the plan.
The plan lives only in the athlete's dashboard - nothing is sent to the Garmin watch.

Follow the principles and their plan procedure. The checks in Plan procedure step 7 are never
broken; when rules conflict, use the Conflict priority. Remember that Garmin-recorded load
underestimates the athlete's real load (athlete.md: Constraints).
Availability isn't known in advance: plan the full week for the current phase (athlete.md:
Constraints, Overrides & specifics).
The recent activities show what the athlete actually did; a gap means time away (Load & recovery:
return after break; athlete.md: Constraints). Where the athlete said how an activity went, it has
`rpe` (effort 1-10), `pain` with `pain_location`, and/or a `note` in their own words. Take these
into account (Monitoring): an easy session that felt hard, pain, illness, poor sleep or stress
mean less; sessions that felt easy can mean more. Say in a session's `reason` when a note shaped it.
`season_plan_target`, when present, is the target week from the long-term season plan: its phase
and weekly TSS. Aim the week at it; when readiness or recent training say otherwise, the hard rules
and readiness win - say so in `warnings`.
`proposed_previous_week`, when present, lists sessions proposed for the week before that the
athlete hasn't reviewed yet; treat them as planned.
`cycling_power`, when present, is from rides with a power meter (FTP estimated or from Garmin):
give ride intensities in watts as well.

Plan load in time per zone: give every session its duration; `targets.run_min` is the week's
total running time in minutes. Hikes and rides count as endurance time, not running volume
(Cross-training).
Use these session IDs (strides and hill sprints go with an easy run; sessions not listed - e.g. a
ski tour - are never planned):
" rules/session-guide "

Return only sessions (rest days have no entry). Dates must fall inside the target week. Two
sessions on one day use the AM and PM slots. Give every session an estimated duration; use
distance_km 0 when distance doesn't apply. Keep the text short - the athlete reads it on a phone:
- `title`: a few words.
- `intensity`: the zone with its HR range, or the load guidance for gym work.
- `description`: one line with the session's structure (e.g. 15' warm-up, 3×10' T with 2' jog, 10' cool-down).
" structure-guide "
- `reason`: one short line, at most about 12 words.
- `summary`: 2-3 sentences on the week's intent.
- `warnings`: only what the athlete must know (readiness concerns, rule tensions, missing information), one line each.

# Coaching documents

"))

(def step-schema
  {"type" "object" "additionalProperties" false
   "required" ["kind" "duration_s" "target" "from" "to" "note"]
   "properties" {"kind" {"type" "string" "enum" workout/kinds}
                 "duration_s" {"type" "integer"}
                 "target" {"type" "string" "enum" workout/target-types}
                 "from" {"type" "string"}
                 "to" {"type" "string"}
                 "note" {"type" "string"}}})

(def blocks-schema
  {"type" "array"
   "items" {"type" "object" "additionalProperties" false
            "required" ["label" "repeat" "steps"]
            "properties" {"label" {"type" "string"}
                          "repeat" {"type" "integer"}
                          "steps" {"type" "array" "items" step-schema}}}})

(def Blocks
  [:vector [:map
            [:label :string]
            [:repeat int?]
            [:steps [:vector [:map
                              [:kind (into [:enum] workout/kinds)]
                              [:duration_s int?]
                              [:target (into [:enum] workout/target-types)]
                              [:from :string] [:to :string] [:note :string]]]]]])

(defn with-block-time
  "A session's duration_min from its blocks, when it has some: the rule
  checks then see the time actually planned."
  [w]
  (let [s (workout/total-s (:blocks w))]
    (cond-> w (pos? s) (assoc :duration_min (Math/round (/ s 60.0))))))

(def response-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["summary" "targets" "workouts" "warnings"]
   "properties"
   {"summary"  {"type" "string"}
    "targets"  {"type" "object" "additionalProperties" false
                "required" ["run_min" "longest_run" "quality"]
                "properties" {"run_min" {"type" "integer"}
                              "longest_run" {"type" "string"}
                              "quality" {"type" "string"}}}
    "warnings" {"type" "array" "items" {"type" "string"}}
    "workouts" {"type" "array"
                "items" {"type" "object" "additionalProperties" false
                         "required" ["date" "slot" "session_id" "title" "duration_min" "distance_km"
                                     "intensity" "description" "reason" "goal" "blocks"]
                         "properties" {"date" {"type" "string" "format" "date"}
                                       "slot" {"type" "string" "enum" ["AM" "PM"]}
                                       "session_id" {"type" "string" "enum" (sort rules/session-ids)}
                                       "title" {"type" "string"}
                                       "duration_min" {"type" "integer"}
                                       "distance_km" {"type" "number"}
                                       "intensity" {"type" "string"}
                                       "description" {"type" "string"}
                                       "reason" {"type" "string"}
                                       "goal" {"type" "string"}
                                       "blocks" blocks-schema}}}}})

(def Response
  [:map
   [:summary :string]
   [:targets [:map [:run_min int?] [:longest_run :string] [:quality :string]]]
   [:warnings [:vector :string]]
   [:workouts [:vector [:map
                        [:date [:re #"^\d{4}-\d{2}-\d{2}$"]]
                        [:slot [:enum "AM" "PM"]]
                        [:session_id (into [:enum] rules/session-ids)]
                        [:title :string]
                        [:duration_min int?]
                        [:distance_km number?]
                        [:intensity :string]
                        [:description :string]
                        [:reason :string]
                        [:goal :string]
                        [:blocks Blocks]]]]])

(defn user-message [ctx]
  (str "Propose the sessions for the target week. Here is the athlete's current data as JSON:\n\n"
       (json/write-str ctx)))

(defn claude-opts
  "The planner's request options: the shared knowledge, then the planner's
  instructions, then the data in one user turn."
  [user-text schema & [log-opts]]
  (merge {:knowledge (claude/principles) :system system-intro
          :messages [[:user user-text]] :schema schema}
         log-opts))

(defn call-claude [user-text schema & [log-opts]]
  (claude/request (claude-opts user-text schema log-opts)))

;; ---------------------------------------------------------------------------

(defn rule-opts
  "The season and athlete values coach.plan-rules/check-week needs, for a week
  whose season position is `info` (coach.plan/week-info)."
  [info]
  {:phase (:phase info) :race-date (:race_date info) :settings (athlete/settings)})

(defn validate
  "Schema check plus the hard-rule checks. Returns the response with
  sessions outside the week dropped, and all warnings."
  [monday response ctx]
  (when-let [err (m/explain Response response)]
    (throw (ex-info (str "Invalid plan from the model: " (pr-str (me/humanize err))) {:response response})))
  (let [sunday (season/add-days monday 6)
        inside #(<= (compare monday (:date %)) 0 (compare sunday (:date %)))
        ws     (mapv with-block-time (filter inside (:workouts response)))
        weeks  (:last_6_weeks ctx)
        prev   {:run_min (:run_min (last weeks))
                ;; "previous longest run" = longest of the recent weeks, not just last week
                :longest_min (apply max 0 (keep :longest_min weeks))
                :peak_min (apply max 0 (keep :run_min weeks))}]
    {:response (assoc response :workouts ws)
     :warnings (rules/check-week monday ws (assoc (rule-opts (:target_week ctx)) :prev prev :full-week? true))}))

(defn- run-proposal! [ds id monday & [cache?]]
  (try
    (let [ctx (context ds monday)
          {:keys [response usage]} (call-claude (user-message ctx) response-schema
                                                    {:task :week_plan :ref (str "proposal " id) :cache? cache?})
          {:keys [response warnings]} (validate monday response ctx)]
      (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage})
      (claude/translate-soon! (translate/proposal-texts ds id)))
    (catch Throwable e
      (plan/fail-proposal! ds id (or (ex-message e) (str (class e)))))))

(defn- submit-week!
  "Sends the week of `monday` (proposal `id`) as a batch request. `later` are
  the [id monday] weeks after it: each is sent once the one before it is
  back, because a week's context includes the previous week's proposal."
  [ds id monday later]
  (try
    (let [ctx (context ds monday)]
      (plan/set-delivery! ds id "batch")
      (claude/submit-batch! ds (claude-opts (user-message ctx) response-schema
                                     {:task :week_plan :ref (str "proposal " id)
                                      :data {:id id :monday monday :ctx ctx :later later}})))
    (catch Throwable e
      (plan/fail-proposal! ds id (or (ex-message e) (str (class e))))
      (when-let [[[nid nm] & more] (seq later)]
        (submit-week! ds nid nm more)))))

(defn- next-week! [ds {:keys [later]}]
  (when-let [[[nid nm] & more] (seq later)]
    (submit-week! ds nid nm more)))

(claude/register-batch-handler!
 :week_plan
 {:done (fn [ds {:keys [id monday ctx] :as data} response usage]
          (try
            (let [{:keys [response warnings]} (validate monday response ctx)]
              (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage})
              (claude/translate-soon! (translate/proposal-texts ds id)))
            (finally (next-week! ds data))))
  :fail (fn [ds {:keys [id] :as data} msg]
          (plan/fail-proposal! ds id msg)
          (next-week! ds data))})

(defn propose-weeks!
  "Proposals for several weeks, one after the other, so each week sees the
  one before it. `trigger` :auto (the app started it) or :manual (a click)
  decides, with the task's delivery setting, between the half-price batch and
  an instant request. Instant: every week but the last caches the knowledge
  block for the next one. Returns their ids."
  [ds mondays & [trigger]]
  (let [batch? (ai/batch? :week_plan (or trigger :manual))
        ids    (vec (map-indexed (fn [i m] (plan/create-proposal! ds m (claude/model-for :week_plan)
                                                                  {:delivery (when batch?
                                                                               (if (zero? i) "batch" "queued"))}))
                                 mondays))
        last   (dec (count ids))]
    (future
      (if batch?
        (submit-week! ds (first ids) (first mondays) (vec (rest (map vector ids mondays))))
        (doseq [[i id m] (map vector (range) ids mondays)]
          (run-proposal! ds id m (< i last)))))
    ids))

(defn propose!
  "Starts a proposal for the week of `monday` in the background and returns
  its id. Poll coach.plan/proposal for the result."
  [ds monday & [trigger]]
  (first (propose-weeks! ds [(season/monday monday)] (or trigger :manual))))

;; ---------------------------------------------------------------------------
;; Adapting the plan between weekly proposals

(def change-fields
  {"date" {"type" "string" "format" "date"}
   "slot" {"type" "string" "enum" ["AM" "PM"]}
   "session_id" {"type" "string" "enum" (sort rules/session-ids)}
   "title" {"type" "string"}
   "duration_min" {"type" "integer"}
   "distance_km" {"type" "number"}
   "intensity" {"type" "string"}
   "description" {"type" "string"}
   "reason" {"type" "string"}
   "goal" {"type" "string"}
   "blocks" blocks-schema})

(def adjust-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["summary" "changes" "warnings"]
   "properties"
   {"summary"  {"type" "string"}
    "warnings" {"type" "array" "items" {"type" "string"}}
    "changes"  {"type" "array"
                "items" {"type" "object" "additionalProperties" false
                         "required" (into ["op" "workout_id"] (sort (keys change-fields)))
                         "properties" (merge {"op" {"type" "string" "enum" ["change" "remove" "add"]}
                                              "workout_id" {"type" "integer"}}
                                             change-fields)}}}})

(def Adjustment
  [:map
   [:summary :string]
   [:warnings [:vector :string]]
   [:changes [:vector [:map
                       [:op [:enum "change" "remove" "add"]]
                       [:workout_id int?]
                       [:date [:re #"^\d{4}-\d{2}-\d{2}$"]]
                       [:slot [:enum "AM" "PM"]]
                       [:session_id (into [:enum] rules/session-ids)]
                       [:title :string] [:duration_min int?] [:distance_km number?]
                       [:intensity :string] [:description :string] [:reason :string]
                       [:goal :string] [:blocks Blocks]]]]])

(defn adapt-inputs
  "What coach.adapt/signals needs, from the database."
  [ds today]
  (let [monday (season/monday today)]
    {:today    today
     :acts     (reverse (analysis/activities ds {:from (season/add-days today -42) :to today}))
     :wellness (analysis/wellness ds (season/add-days today -2) today)
     :workouts (plan/workouts ds monday (season/add-days monday 13))
     :tsb      (:tsb (last (analysis/pmc ds today today)))
     :phase    (:phase (plan/week-info ds today))
     :settings (athlete/settings)}))

(defn adjust-message [ctx today scope-end]
  (str "Adjust the planned sessions from " today " to " scope-end " in response to the signals"
       " (and the athlete's note, if there is one). Change, remove or add sessions only as far as the"
       " signals and the principles justify; leave everything else alone and don't list it."
       " Don't touch sessions before today or ones already done. Never make up missed sessions (Monitoring)."
       " For op change and remove, use the workout_id from plan_in_scope and give the session's new"
       " fields (for remove, its current ones). For op add, use workout_id 0."
       " If nothing should change, return no changes and explain why in the summary.\n\n"
       (json/write-str ctx)))

(defn- apply-changes
  "The planned sessions in scope as they'd be after accepting every change."
  [workouts changes]
  (let [by-id (into {} (map (juxt :id identity)) workouts)
        after (reduce (fn [m {:keys [op workout_id] :as c}]
                        (case op
                          "change" (update m workout_id merge (select-keys c plan/workout-cols))
                          "remove" (dissoc m workout_id)
                          "add"    (assoc m (keyword (str "new" (count m))) c)))
                      by-id changes)]
    (vals after)))

(defn validate-adjustment
  "Schema check; drops changes that target unknown or finished sessions or
  fall outside the scope; rule-checks every affected week. `info-of` gives a
  week's season position (coach.plan/week-info) from its Monday."
  [today scope-end response in-scope info-of]
  (when-let [err (m/explain Adjustment response)]
    (throw (ex-info (str "Invalid adjustment from the model: " (pr-str (me/humanize err))) {:response response})))
  (let [open-ids (set (keep #(when (= "planned" (:status %)) (:id %)) in-scope))
        ok?      (fn [{:keys [op workout_id date]}]
                   (and (<= (compare today date) 0 (compare scope-end date))
                        (or (= op "add") (open-ids workout_id))))
        changes  (mapv with-block-time (filter ok? (:changes response)))
        after    (apply-changes (filter #(#{"planned" "done"} (:status %)) in-scope) changes)
        weeks    (distinct (map #(season/monday (:date %)) changes))]
    {:response (assoc response :changes changes)
     :warnings (vec (distinct (mapcat (fn [m] (rules/check-week m (filter #(= m (season/monday (:date %))) after)
                                                                (rule-opts (info-of m))))
                                      weeks)))}))

(defn- finish-adjustment! [ds id today scope-end in-scope ctx response usage]
  (let [{:keys [response warnings]} (validate-adjustment today scope-end response in-scope
                                                         #(plan/week-info ds %))]
    (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage})
    (claude/translate-soon! (translate/proposal-texts ds id))))

(claude/register-batch-handler!
 :adjust
 {:done (fn [ds {:keys [id today scope_end in_scope ctx]} response usage]
          (finish-adjustment! ds id today scope_end in_scope ctx response usage))
  :fail (fn [ds {:keys [id]} msg] (plan/fail-proposal! ds id msg))})

(defn propose-adjustment!
  "Starts an adjustment proposal for today through the end of next week,
  triggered by `signals` (and/or an athlete `note`). Returns its id."
  [ds signals note & [trigger]]
  (let [today     (str (LocalDate/now))
        monday    (season/monday today)
        scope-end (season/add-days monday 13)
        batch?    (ai/batch? :adjust (or trigger :manual))
        id        (plan/create-proposal! ds monday (claude/model-for :adjust)
                                         {:kind "adjust" :scope-end scope-end :signals signals
                                   :delivery (when batch? "batch")})]
    (future
      (try
        (let [in-scope (plan/workouts ds today scope-end)
              ctx      (assoc (context ds monday)
                              :signals (mapv #(select-keys % [:id :severity :message :principle]) signals)
                              :athlete_note note
                              :plan_in_scope (mapv #(select-keys % [:id :date :slot :session_id :title :duration_min
                                                                     :distance_km :intensity :status])
                                                   in-scope)
                              :recent_feedback
                              (vec (for [a (analysis/activities ds {:from (season/add-days today -14) :to today})
                                         :when (or (:rpe a) (:garmin_rpe a) (pos? (or (:pain a) 0)) (:note a))]
                                     (select-keys a [:date :name :rpe :garmin_rpe :pain :pain_location :note]))))
              opts     (claude-opts (adjust-message ctx today scope-end) adjust-schema
                                    {:task :adjust :ref (str "proposal " id)})]
          (if batch?
            (claude/submit-batch! ds (assoc opts :data {:id id :today today :scope_end scope-end
                                                 :in_scope in-scope :ctx ctx}))
            (let [{:keys [response usage]} (claude/request opts)]
              (finish-adjustment! ds id today scope-end in-scope ctx response usage))))
        (catch Throwable e
          (plan/fail-proposal! ds id (or (ex-message e) (str (class e)))))))
    id))

;; ---------------------------------------------------------------------------
;; Structure for sessions planned before sessions had blocks (once)

(def structure-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["workouts"]
   "properties"
   {"workouts" {"type" "array"
                "items" {"type" "object" "additionalProperties" false
                         "required" ["workout_id" "goal" "blocks"]
                         "properties" {"workout_id" {"type" "integer"}
                                       "goal" {"type" "string"}
                                       "blocks" blocks-schema}}}}})

(def Structure
  [:map [:workouts [:vector [:map [:workout_id int?] [:goal :string] [:blocks Blocks]]]]])

(defn structure-message [sessions]
  (str "These sessions are already in the athlete's plan, accepted with only a one-line description. "
       "Don't change them: for each, write its `goal` and `blocks` so they match its description, "
       "duration and intensity exactly, using the workout_id given.\n\n"
       structure-guide "\n\nSessions as JSON:\n\n" (json/write-str sessions)))

(defn unstructured
  "Planned sessions from `today` on that have no blocks yet."
  [ds today]
  (filterv #(and (= "planned" (:status %)) (nil? (:blocks %)))
           (plan/workouts ds today "9999-12-31")))

(defn structure!
  "Asks Claude for the goal and blocks of the planned sessions that have
  none (coach.workout). Only adds structure; the sessions stay as accepted.
  Returns the number of sessions given a structure."
  [ds today]
  (let [ws (unstructured ds today)]
    (if (empty? ws)
      0
      (let [sessions (mapv #(select-keys % [:id :date :session_id :title :duration_min
                                            :distance_km :intensity :description :reason])
                           ws)
            {:keys [response]} (claude/request (claude-opts (structure-message sessions) structure-schema
                                                            {:task :structure :ref "structure"}))]
        (when-let [err (m/explain Structure response)]
          (throw (ex-info (str "Invalid structure from the model: " (pr-str (me/humanize err))) {:response response})))
        (let [ids (set (map :id ws))
              n   (count (for [{:keys [workout_id goal blocks]} (:workouts response)
                               :when (and (ids workout_id) (plan/set-structure! ds workout_id goal blocks))]
                           workout_id))]
          (claude/translate-soon! (mapcat (fn [{:keys [goal blocks]}]
                                     (cons goal (concat (map :label blocks) (map :note (mapcat :steps blocks)))))
                                   (:workouts response)))
          n)))))

(defn structure-once!
  "The one-time backfill at dashboard start, in the background; marked done
  in sync_state after it succeeds (a failure is retried at the next start)."
  [ds]
  (when-not (or (db/get-state ds :structure_backfill) (costs/credits-out ds))
    (future
      (try
        (let [n (structure! ds (str (LocalDate/now)))]
          (db/set-state! ds :structure_backfill (str (LocalDate/now)))
          (when (pos? n) (println "Added blocks to" n "planned sessions")))
        (catch Throwable e
          (println "Session structure backfill failed:" (or (ex-message e) (class e))))))))


(defn- seen? [ds k] (seq (db/query ds ["SELECT 1 FROM adapt_signals WHERE key = ?" k])))

(def horizon-weeks "Weeks after the current one that are kept planned." 2)

(defn- retry? [p]
  ;; a failed proposal is retried after a while, not on every sync
  (and (= "failed" (:status p))
       (.isBefore (java.time.Instant/parse (:updated_at p))
                  (.minus (java.time.Instant/now) (Duration/ofHours 6)))))

(defn- plan-again?
  "Whether a week with no sessions left gets a new proposal, given its latest
  proposal `p`: none yet, withdrawn, failed a while ago, or reviewed with
  something accepted whose sessions were cancelled since (a new season plan,
  an adjustment). Not while one is running or still has items to review, and
  not when the athlete turned the last one down (nothing accepted)."
  [p]
  (or (nil? p)
      (= "withdrawn" (:status p))
      (retry? p)
      (and (#{"open" "closed"} (:status p))
           (not-any? #(= "pending" (:decision %)) (:items p))
           (some #(#{"accepted" "edited"} (:decision %)) (:items p)))))

(defn weeks-to-plan
  "The next `horizon-weeks` weeks after the current one (up to race week)
  with no sessions planned and nothing proposed for them (see plan-again?)."
  [ds today]
  (let [m    (season/monday today)
        race (:race_date (plan/current-season ds))]
    (vec (for [i (range 1 (inc horizon-weeks))
               :let [mo (season/add-days m (* 7 i))
                     p  (plan/latest-proposal ds mo)]
               :when (and race (<= (compare mo (season/monday race)) 0)
                          (empty? (plan/workouts ds mo (season/add-days mo 6)))
                          (plan-again? p))]
           mo))))

(defonce ^:private plan-ahead-lock (Object.))

(defn plan-ahead!
  "Keeps the weeks after the current one proposed, once a season plan exists:
  the next two weeks at first, then each Monday the new second week. Returns
  the ids of the proposals it started."
  [ds]
  (locking plan-ahead-lock
    (let [ms (when (and (claude/api-key?) (athlete/content) (plan/latest-season-plan ds "ready"))
               (weeks-to-plan ds (str (LocalDate/now))))]
      (if (seq ms) (propose-weeks! ds ms :auto) []))))

(defn check!
  "Runs after each sync. New triggering signals start one adjustment proposal
  (at most one a day, and not while another adjustment awaits review). Then
  the weeks ahead are proposed (plan-ahead!)."
  [ds]
  (let [today   (str (LocalDate/now))
        sigs    (adapt/signals (adapt-inputs ds today))
        new     (filter #(and (:trigger? %) (not (seen? ds (:key %)))) sigs)
        open    (plan/open-proposals ds)
        adjust-today? (seq (db/query ds
                                     ["SELECT 1 FROM plan_proposals WHERE kind = 'adjust' AND substr(created_at, 1, 10) = ?" today]))
        proposed (atom [])]
    (when (and (seq new) (claude/api-key?) (athlete/content) (not adjust-today?)
               (not-any? #(= "adjust" (:kind %)) open))
      (let [id (propose-adjustment! ds new nil :auto)]
        (doseq [s new]
          (next.jdbc/execute! ds ["INSERT OR IGNORE INTO adapt_signals (key, proposal_id, first_seen) VALUES (?, ?, ?)"
                                  (:key s) id today]))
        (swap! proposed conj id)))
    (swap! proposed into (plan-ahead! ds))
    {:signals sigs :proposed @proposed}))
