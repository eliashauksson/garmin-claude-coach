(ns coach.planner
  "Asks Claude to propose one week of training from training-principles.md,
  athlete.md and the athlete's recent data. The proposal is stored for review; nothing
  enters the plan until the athlete accepts it."
  (:require [clojure.data.json :as json]
            [coach.adapt :as adapt]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.analysis :as analysis]
            [coach.athlete :as athlete]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.plan-rules :as rules]
            [coach.season :as season]
            [malli.core :as m]
            [malli.error :as me])
  (:import (com.anthropic.client.okhttp AnthropicOkHttpClient)
           (com.anthropic.core JsonValue)
           (com.anthropic.models.beta.messages BetaCacheControlEphemeral BetaJsonOutputFormat
                                               BetaJsonOutputFormat$Schema BetaOutputConfig
                                               BetaOutputConfig$Effort BetaTextBlockParam
                                               BetaThinkingConfigAdaptive MessageCreateParams)
           (java.time Duration LocalDate)))

(def model "claude-opus-5-5")

(def prices
  "USD per million tokens for `model` (Anthropic list prices, Oct 2026).
  Cache writes use the 5-minute TTL rate (1.25x input)."
  {:input 4.00 :output 20.00 :cache_read 0.20 :cache_write 5.00})

(def usd->chf
  "Approximate exchange rate for showing costs in CHF. Anthropic bills in
  USD; this is only for display. Mid-2026 rates were about 0.80-0.83."
  0.81)

(defn cost-usd
  "Dollar cost of one request from its usage. input_tokens excludes cached
  tokens, which are billed separately; thinking is part of output_tokens."
  [{:keys [input_tokens output_tokens cache_read cache_write]}]
  (let [per-m #(* (or %1 0) (prices %2) 1e-6)]
    (+ (per-m input_tokens :input) (per-m output_tokens :output)
       (per-m cache_read :cache_read) (per-m cache_write :cache_write))))
(def general-file "training-principles.md")

(defn principles
  "The general principles and the athlete file, as one text for the cached system block."
  []
  (str (slurp general-file) "\n\n---\n\n"
       (or (athlete/content)
           (throw (ex-info "No athlete information yet. Add it on the Plan page first." {:type :no-athlete})))))
(def key-file (str (System/getProperty "user.home") "/.config/coach/anthropic-key"))

(defn api-key []
  (let [f (io/file key-file)]
    (or (when (.exists f) (not-empty (str/trim (slurp f))))
        (not-empty (System/getenv "ANTHROPIC_API_KEY"))
        (throw (ex-info (str "No Anthropic API key. Put it in " key-file) {:type :no-api-key})))))

;; ---------------------------------------------------------------------------
;; Context (what the coach sees)

(defn- r1 [x] (when x (/ (Math/round (* 10.0 x)) 10.0)))
(defn- run-act? [a] (re-find #"running" (str (:sport a))))
(defn- march? [a] (and (re-find #"hiking|walking" (str (:sport a)))
                       (>= (or (:moving_s a) (:duration_s a) 0) 5400)))
(defn- gym? [a] (re-find #"strength" (str (:sport a))))
(defn- secs [a] (or (:moving_s a) (:duration_s a) 0))

(defn week-summary
  "§8 weekly metrics from recorded activities. Easy = runs with avg HR ≤ 148 (§A3)."
  [monday acts]
  (let [runs (filter run-act? acts)
        rt   (reduce + 0 (map secs runs))
        easy (reduce + 0 (map secs (filter #(and (:avg_hr %) (<= (:avg_hr %) 148)) runs)))]
    {:monday       monday
     :run_km       (r1 (/ (reduce + 0 (keep :distance_m runs)) 1000.0))
     :run_hours    (r1 (/ rt 3600.0))
     :runs         (count runs)
     :easy_pct     (when (pos? rt) (Math/round (* 100.0 (/ easy rt))))
     :longest_km   (r1 (/ (apply max 0 (keep :distance_m runs)) 1000.0))
     :marches      (count (filter march? acts))
     :march_hours  (r1 (/ (reduce + 0 (map secs (filter march? acts))) 3600.0))
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
     :target_week      (season/week-info monday)
     :last_6_weeks     weeks
     :recent_activities (vec (for [a (filter #(>= (compare (:date %) (season/add-days today -14)) 0) acts)]
                               {:date (:date a) :sport (:sport a) :name (:name a)
                                :km (r1 (some-> (:distance_m a) (/ 1000.0))) :minutes (Math/round (/ (secs a) 60.0))
                                :avg_hr (some-> (:avg_hr a) Math/round) :tss (some-> (:tss a) Math/round)}))
     :readiness_last_7_days (vec (for [w (analysis/wellness ds (season/add-days today -6) today)]
                                   (select-keys w [:date :hrv_avg :hrv_status :rhr :sleep_s :sleep_score
                                                   :readiness :body_battery_high])))
     :load_today       (select-keys pmc [:ctl :atl :tsb])
     :season_plan_target (plan/season-target ds monday)
     :proposed_previous_week (proposed-week ds (season/add-days monday -7))
     :planned_previous_and_target_weeks
     (vec (for [w (plan/workouts ds (season/add-days monday -7) (season/add-days monday 6))]
            (select-keys w [:date :slot :session_id :title :distance_km :duration_min :status])))}))

;; ---------------------------------------------------------------------------
;; Prompt and response schema

(def system-intro
  "You are the running coach for the athlete described below. Two documents follow: the general
training principles (§0-§9) and the athlete's profile, goal and season plan (§A1-§A9).
You plan one week at a time; the athlete reviews every session before it enters the plan.
The plan lives only in the athlete's dashboard - nothing is sent to the Garmin watch.

Follow the principles. The hard rules (§9) are never broken; when rules conflict, use the
priority order in §0. Remember that Garmin-recorded load underestimates the athlete's real load (§A2).
Availability isn't known in advance: plan the full week for the current phase (§5.1, §6.1).
The recent activities show what the athlete actually did; a gap means time away (§6.2, §A2).
`season_plan_target`, when present, is the target week from the long-term season plan: its phase
and weekly TSS. Aim the week at it; when readiness or recent training say otherwise, the hard rules
and readiness win - say so in `warnings`.
`proposed_previous_week`, when present, lists sessions proposed for the week before that the
athlete hasn't reviewed yet; treat them as planned.

Return only sessions (rest days have no entry). Dates must fall inside the target week. Two
sessions on one day use the AM and PM slots. Give every session an estimated duration; use
distance_km 0 when distance doesn't apply. Keep the text short - the athlete reads it on a phone:
- `title`: a few words.
- `intensity`: the zone with its HR range, or the load guidance for gym work.
- `description`: one line with the session's structure (e.g. 15' warm-up, 3×10' T with 2' jog, 10' cool-down).
- `reason`: one short line, at most about 12 words.
- `summary`: 2-3 sentences on the week's intent.
- `warnings`: only what the athlete must know (readiness concerns, rule tensions, missing information), one line each.

# Coaching documents

")

(def response-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["summary" "targets" "workouts" "warnings"]
   "properties"
   {"summary"  {"type" "string"}
    "targets"  {"type" "object" "additionalProperties" false
                "required" ["run_km" "longest_run" "quality"]
                "properties" {"run_km" {"type" "number"}
                              "longest_run" {"type" "string"}
                              "quality" {"type" "string"}}}
    "warnings" {"type" "array" "items" {"type" "string"}}
    "workouts" {"type" "array"
                "items" {"type" "object" "additionalProperties" false
                         "required" ["date" "slot" "session_id" "title" "duration_min" "distance_km"
                                     "intensity" "description" "reason"]
                         "properties" {"date" {"type" "string" "format" "date"}
                                       "slot" {"type" "string" "enum" ["AM" "PM"]}
                                       "session_id" {"type" "string" "enum" (sort rules/session-ids)}
                                       "title" {"type" "string"}
                                       "duration_min" {"type" "integer"}
                                       "distance_km" {"type" "number"}
                                       "intensity" {"type" "string"}
                                       "description" {"type" "string"}
                                       "reason" {"type" "string"}}}}}})

(def Response
  [:map
   [:summary :string]
   [:targets [:map [:run_km number?] [:longest_run :string] [:quality :string]]]
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
                        [:reason :string]]]]])

(defn user-message [ctx]
  (str "Propose the sessions for the target week. Here is the athlete's current data as JSON:\n\n"
       (json/write-str ctx)))

;; ---------------------------------------------------------------------------
;; Claude call

(defn- ->json-value [x] (JsonValue/from (json/read-str (json/write-str x))))

(defn- client []
  (-> (AnthropicOkHttpClient/builder) (.apiKey ^String (api-key)) (.timeout (Duration/ofMinutes 10)) (.build)))

(defn request
  "One Claude request: `system` in a cached block, `messages` as
  [[:user text] [:assistant text] ...], JSON out via structured outputs.
  Returns {:response map :usage map}."
  [{:keys [system messages schema effort] :or {effort BetaOutputConfig$Effort/HIGH}}]
  (let [b      (-> (MessageCreateParams/builder)
                   (.model ^String model)
                   (.maxTokens 32000)
                   (.systemOfBetaTextBlockParams
                    [(-> (BetaTextBlockParam/builder)
                         (.text ^String system)
                         (.cacheControl (.build (BetaCacheControlEphemeral/builder)))
                         (.build))]))
        _      (doseq [[role text] messages]
                 (if (= :assistant role) (.addAssistantMessage b ^String text) (.addUserMessage b ^String text)))
        params (-> b
                   (.thinking (.build (BetaThinkingConfigAdaptive/builder)))
                   (.outputConfig (-> (BetaOutputConfig/builder)
                                      (.effort ^BetaOutputConfig$Effort effort)
                                      (.format (-> (BetaJsonOutputFormat/builder)
                                                   (.schema (-> (BetaJsonOutputFormat$Schema/builder)
                                                                (.additionalProperties
                                                                 (update-vals schema ->json-value))
                                                                (.build)))
                                                   (.build)))
                                      (.build)))
                   ;; if the model declines, the API retries on a fallback model
                   (.addBeta "server-side-fallback-2026-07-01")
                   (.putAdditionalBodyProperty "fallbacks" (JsonValue/from "default"))
                   (.build))
        msg    (.create (.messages (.beta (client))) params)
        stop   (some-> (.orElse (.stopReason msg) nil) (.asString))
        text   (apply str (keep #(some-> (.orElse (.text %) nil) (.text)) (.content msg)))
        usage  (.usage msg)]
    (when (#{"refusal" "max_tokens"} stop)
      (throw (ex-info (str "The model stopped early: " stop) {:stop_reason stop})))
    {:response (json/read-str text :key-fn keyword)
     :usage    (let [u {:input_tokens (.inputTokens usage) :output_tokens (.outputTokens usage)
                        :cache_read (.orElse (.cacheReadInputTokens usage) 0)
                        :cache_write (.orElse (.cacheCreationInputTokens usage) 0)
                        :stop_reason stop}]
                 (assoc u :cost_usd (cost-usd u) :cost_chf (* usd->chf (cost-usd u))))}))

(defn call-claude
  "The planner's request: principles in the system block, data in one user turn."
  [principles user-text schema]
  (request {:system (str system-intro principles) :messages [[:user user-text]] :schema schema}))

;; ---------------------------------------------------------------------------

(defn validate
  "Schema check plus the hard-rule checks. Returns the response with
  sessions outside the week dropped, and all warnings."
  [monday response ctx]
  (when-let [err (m/explain Response response)]
    (throw (ex-info (str "Invalid plan from the model: " (pr-str (me/humanize err))) {:response response})))
  (let [sunday (season/add-days monday 6)
        inside #(<= (compare monday (:date %)) 0 (compare sunday (:date %)))
        ws     (vec (filter inside (:workouts response)))
        prev   (assoc (last (:last_6_weeks ctx))
                      ;; "previous longest run" = longest of the recent weeks, not just last week
                      :longest_km (apply max 0 (keep :longest_km (:last_6_weeks ctx)))
                      :peak_km (apply max 0 (keep :run_km (:last_6_weeks ctx))))]
    {:response (assoc response :workouts ws)
     :warnings (rules/check-week monday ws {:prev prev})}))

(defn- run-proposal! [ds id monday]
  (try
    (let [ctx (context ds monday)
          {:keys [response usage]} (call-claude (principles) (user-message ctx) response-schema)
          {:keys [response warnings]} (validate monday response ctx)]
      (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage}))
    (catch Throwable e
      (plan/fail-proposal! ds id (or (ex-message e) (str (class e)))))))

(defn propose!
  "Starts a proposal for the week of `monday` in the background and returns
  its id. Poll coach.plan/proposal for the result."
  [ds monday]
  (let [monday (season/monday monday)
        id     (plan/create-proposal! ds monday model)]
    (future (run-proposal! ds id monday))
    id))

(defn propose-weeks!
  "Proposals for several weeks, one after the other, so each week sees the
  one before it. Returns their ids."
  [ds mondays]
  (let [ids (mapv #(plan/create-proposal! ds % model) mondays)]
    (future (doseq [[id m] (map vector ids mondays)] (run-proposal! ds id m)))
    ids))

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
   "reason" {"type" "string"}})

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
                       [:intensity :string] [:description :string] [:reason :string]]]]])

(defn adapt-inputs
  "What coach.adapt/signals needs, from the database."
  [ds today]
  (let [monday (season/monday today)]
    {:today    today
     :acts     (reverse (analysis/activities ds {:from (season/add-days today -42) :to today}))
     :wellness (analysis/wellness ds (season/add-days today -2) today)
     :workouts (plan/workouts ds monday (season/add-days monday 13))
     :tsb      (:tsb (last (analysis/pmc ds today today)))}))

(defn adjust-message [ctx today scope-end]
  (str "Adjust the planned sessions from " today " to " scope-end " in response to the signals"
       " (and the athlete's note, if there is one). Change, remove or add sessions only as far as the"
       " signals and the principles justify; leave everything else alone and don't list it."
       " Don't touch sessions before today or ones already done. Never make up missed sessions (§6.4)."
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
  fall outside the scope; rule-checks every affected week."
  [today scope-end response in-scope]
  (when-let [err (m/explain Adjustment response)]
    (throw (ex-info (str "Invalid adjustment from the model: " (pr-str (me/humanize err))) {:response response})))
  (let [open-ids (set (keep #(when (= "planned" (:status %)) (:id %)) in-scope))
        ok?      (fn [{:keys [op workout_id date]}]
                   (and (<= (compare today date) 0 (compare scope-end date))
                        (or (= op "add") (open-ids workout_id))))
        changes  (vec (filter ok? (:changes response)))
        after    (apply-changes (filter #(#{"planned" "done"} (:status %)) in-scope) changes)
        weeks    (distinct (map #(season/monday (:date %)) changes))]
    {:response (assoc response :changes changes)
     :warnings (vec (distinct (mapcat (fn [m] (rules/check-week m (filter #(= m (season/monday (:date %))) after) {}))
                                      weeks)))}))

(defn propose-adjustment!
  "Starts an adjustment proposal for today through the end of next week,
  triggered by `signals` (and/or an athlete `note`). Returns its id."
  [ds signals note]
  (let [today     (str (LocalDate/now))
        monday    (season/monday today)
        scope-end (season/add-days monday 13)
        id        (plan/create-proposal! ds monday model {:kind "adjust" :scope-end scope-end :signals signals})]
    (future
      (try
        (let [in-scope (plan/workouts ds today scope-end)
              ctx      (assoc (context ds monday)
                              :signals (mapv #(select-keys % [:id :severity :message :principle]) signals)
                              :athlete_note note
                              :plan_in_scope (mapv #(select-keys % [:id :date :slot :session_id :title :duration_min
                                                                     :distance_km :intensity :status])
                                                   in-scope)
                              :recent_feedback (vec (for [a (analysis/activities ds {:from (season/add-days today -14) :to today})
                                                          :when (or (:rpe a) (:garmin_rpe a) (pos? (or (:pain a) 0)) (:note a))]
                                                      (select-keys a [:date :name :rpe :garmin_rpe :pain :pain_location :note]))))
              {:keys [response usage]} (call-claude (principles) (adjust-message ctx today scope-end) adjust-schema)
              {:keys [response warnings]} (validate-adjustment today scope-end response in-scope)]
          (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage}))
        (catch Throwable e
          (plan/fail-proposal! ds id (or (ex-message e) (str (class e)))))))
    id))

(defn api-key? [] (try (api-key) true (catch Exception _ false)))

(defn- seen? [ds k] (seq (db/query ds ["SELECT 1 FROM adapt_signals WHERE key = ?" k])))

(def horizon-weeks "Weeks after the current one that are kept planned." 2)

(defn- retry? [p]
  ;; a failed proposal is retried after a while, not on every sync
  (and (= "failed" (:status p))
       (.isBefore (java.time.Instant/parse (:updated_at p))
                  (.minus (java.time.Instant/now) (Duration/ofHours 6)))))

(defn weeks-to-plan
  "The next `horizon-weeks` weeks after the current one (up to race week) with
  nothing planned or proposed yet."
  [ds today]
  (let [m (season/monday today)]
    (vec (for [i (range 1 (inc horizon-weeks))
               :let [mo (season/add-days m (* 7 i))
                     p  (plan/latest-proposal ds mo)]
               :when (and (<= (compare mo (season/monday season/race-date)) 0)
                          (empty? (plan/workouts ds mo (season/add-days mo 6)))
                          (or (nil? p) (= "withdrawn" (:status p)) (retry? p)))]
           mo))))

(defonce ^:private plan-ahead-lock (Object.))

(defn plan-ahead!
  "Keeps the weeks after the current one proposed, once a season plan exists:
  the next two weeks at first, then each Monday the new second week. Returns
  the ids of the proposals it started."
  [ds]
  (locking plan-ahead-lock
    (let [ms (when (and (api-key?) (athlete/content) (plan/latest-season-plan ds "ready"))
               (weeks-to-plan ds (str (LocalDate/now))))]
      (if (seq ms) (propose-weeks! ds ms) []))))

(defn check!
  "Runs after each sync. New triggering signals start one adjustment proposal
  (at most one a day, and not while another adjustment awaits review). Then
  the weeks ahead are proposed (plan-ahead!)."
  [ds]
  (let [today   (str (LocalDate/now))
        sigs    (adapt/signals (adapt-inputs ds today))
        new     (filter #(and (:trigger? %) (not (seen? ds (:key %)))) sigs)
        open    (plan/open-proposals ds)
        adjust-today? (seq (db/query ds ["SELECT 1 FROM plan_proposals WHERE kind = 'adjust' AND substr(created_at, 1, 10) = ?" today]))
        proposed (atom [])]
    (when (and (seq new) (api-key?) (athlete/content) (not adjust-today?) (not-any? #(= "adjust" (:kind %)) open))
      (let [id (propose-adjustment! ds new nil)]
        (doseq [s new]
          (next.jdbc/execute! ds ["INSERT OR IGNORE INTO adapt_signals (key, proposal_id, first_seen) VALUES (?, ?, ?)"
                                  (:key s) id today]))
        (swap! proposed conj id)))
    (swap! proposed into (plan-ahead! ds))
    {:signals sigs :proposed @proposed}))
