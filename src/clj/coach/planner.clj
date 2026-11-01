(ns coach.planner
  "Asks Claude to propose one week of training from training-principles.md,
  athlete.md and the athlete's recent data. The proposal is stored for review; nothing
  enters the plan until the athlete accepts it."
  (:require [clojure.data.json :as json]
            [coach.adapt :as adapt]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.analysis :as analysis]
            [coach.ai-settings :as ai]
            [coach.athlete :as athlete]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.plan-rules :as rules]
            [coach.season :as season]
            [malli.core :as m]
            [malli.error :as me])
  (:import (com.anthropic.client.okhttp AnthropicOkHttpClient)
           (com.anthropic.core JsonValue ObjectMappers)
           (com.anthropic.models.beta.messages BetaCacheControlEphemeral BetaJsonOutputFormat
                                               BetaJsonOutputFormat$Schema BetaOutputConfig
                                               BetaOutputConfig$Effort BetaTextBlockParam
                                               BetaThinkingConfigAdaptive MessageCreateParams)
           (java.time Duration LocalDate)))

(defn model-for
  "The model the athlete chose for `task` (coach.ai-settings)."
  [task]
  (:model (ai/for-task task)))

(def general-file "training-principles.md")

(defn knowledge
  "The first system block of every Claude request: the general principles and
  athlete.md, the same text for every task. Task instructions come after it.
  It is cached (request :cache?) only where another request of the same task
  is likely within the cache's 5 minutes: the cache is per model and per
  output schema (measured: a different schema misses, a different effort
  hits), and an unread cache write costs 25% more than plain input."
  [doc]
  (str "# General training principles (read-only)\n\n" (slurp general-file)
       "\n\n# Current athlete.md\n\n" (or doc "(no athlete.md yet)")))

(defn principles
  "knowledge for the planning tasks, which need athlete.md."
  []
  (knowledge (or (athlete/content)
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
  "Weekly metrics from recorded activities. Easy = runs with avg HR at or
  below the top of the athlete's Easy zone (athlete.md: Thresholds & zones); without it, no easy share."
  [monday acts]
  (let [runs (filter run-act? acts)
        rt   (reduce + 0 (map secs runs))
        easy-max (:easy_hr_max (athlete/settings))
        easy (reduce + 0 (map secs (filter #(and easy-max (:avg_hr %) (<= (:avg_hr %) easy-max)) runs)))]
    {:monday       monday
     :run_km       (r1 (/ (reduce + 0 (keep :distance_m runs)) 1000.0))
     :run_hours    (r1 (/ rt 3600.0))
     :run_min      (Math/round (/ rt 60.0))
     :longest_min  (Math/round (/ (apply max 0 (map secs runs)) 60.0))
     :runs         (count runs)
     :easy_pct     (when (and easy-max (pos? rt)) (Math/round (* 100.0 (/ easy rt))))
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
     :target_week      (plan/week-info ds monday)
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
return after break; athlete.md: Constraints).
`season_plan_target`, when present, is the target week from the long-term season plan: its phase
and weekly TSS. Aim the week at it; when readiness or recent training say otherwise, the hard rules
and readiness win - say so in `warnings`.
`proposed_previous_week`, when present, lists sessions proposed for the week before that the
athlete hasn't reviewed yet; treat them as planned.

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
- `reason`: one short line, at most about 12 words.
- `summary`: 2-3 sentences on the week's intent.
- `warnings`: only what the athlete must know (readiness concerns, rule tensions, missing information), one line each.

# Coaching documents

"))

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
                        [:reason :string]]]]])

(defn user-message [ctx]
  (str "Propose the sessions for the target week. Here is the athlete's current data as JSON:\n\n"
       (json/write-str ctx)))

;; ---------------------------------------------------------------------------
;; Claude call

(defn- ->json-value [x] (JsonValue/from (json/read-str (json/write-str x))))

(defn- client []
  (-> (AnthropicOkHttpClient/builder) (.apiKey ^String (api-key)) (.timeout (Duration/ofMinutes 10)) (.build)))

(defn- api-json
  "An SDK object as the API's own JSON (snake_case keys), via the SDK's mapper."
  [x]
  (json/read-str (.writeValueAsString (ObjectMappers/jsonMapper) x) :key-fn keyword))

(def billing-url
  "The Claude Console page where credits are bought."
  "https://platform.claude.com/settings/billing")

(def no-credits-message
  (str "Your Claude API credits have run out. Add funds in the Claude Console ("
       billing-url "), then try again."))

(defn no-credits?
  "True for the API's out-of-credits error: the typed billing_error (HTTP 402),
  or the 400 that says the credit balance is too low."
  [^Throwable e]
  (boolean
   (or (and (instance? com.anthropic.errors.AnthropicServiceException e)
            (let [^com.anthropic.errors.AnthropicServiceException se e]
              (or (= 402 (.statusCode se))
                  (= "billing_error" (some-> (.orElse (.errorType se) nil) str)))))
       (re-find #"(?i)credit balance is too low" (str (ex-message e))))))

(declare request*)

(defn request
  "One Claude request: `system` in a cached block, `messages` as
  [[:user text] [:assistant text] ...], JSON out via structured outputs.
  Returns {:response map :usage map}. The model and effort are the ones
  chosen for `task` (coach.ai-settings) unless opts give :model / :effort.
  Every request, failed ones too, is logged with its cost under `task`."
  [{:keys [task ref] :as opts}]
  (let [chosen (ai/for-task task)
        model  (or (:model opts) (:model chosen))
        effort (or (:effort opts) (:effort chosen))
        t0     (System/currentTimeMillis)
        log    (fn [m] (costs/log! (merge {:task (or task :other) :ref ref :model model
                                           :effort effort :duration_ms (- (System/currentTimeMillis) t0)}
                                          m)))]
    (try
      (let [{:keys [usage-raw served-model stop request-id] :as r}
            (request* (assoc opts :model model :effort effort))]
        (log {:usage usage-raw :served_model served-model :stop_reason stop :request_id request-id})
        (when (#{"refusal" "max_tokens"} stop)
          (throw (ex-info (str "The model stopped early: " stop) {:stop_reason stop})))
        (select-keys r [:response :usage]))
      (catch clojure.lang.ExceptionInfo e
        (when-not (:stop_reason (ex-data e)) (log {:error (ex-message e)}))
        (throw e))
      (catch Throwable e
        (if (no-credits? e)
          (do (log {:error (or (ex-message e) "billing_error") :error_type "no_credits"})
              (throw (ex-info no-credits-message {:type :no-credits :billing_url billing-url} e)))
          (do (log {:error (or (ex-message e) (str (class e)))})
              (throw e)))))))

(defn message-params
  "The request as SDK params. Batch requests leave out the server-side
  fallback, which the Message Batches API doesn't accept."
  [{:keys [knowledge system messages schema model effort cache? batch?]}]
  (let [block (fn [^String text cache?]
                (cond-> (.text (BetaTextBlockParam/builder) text)
                  cache? (.cacheControl (.build (BetaCacheControlEphemeral/builder)))
                  true   (.build)))
        b     (-> (MessageCreateParams/builder)
                  (.model ^String model)
                  (.maxTokens 32000)
                  (.systemOfBetaTextBlockParams
                   ;; the shared knowledge first; the task's own instructions after it
                   (vec (remove nil? [(some-> knowledge (block cache?))
                                      (some-> system (block false))]))))]
    (doseq [[role text] messages]
      (if (= :assistant role) (.addAssistantMessage b ^String text) (.addUserMessage b ^String text)))
    (-> b
        (.thinking (.build (BetaThinkingConfigAdaptive/builder)))
        (.outputConfig (-> (BetaOutputConfig/builder)
                           (.effort (BetaOutputConfig$Effort/of ^String effort))
                           (.format (-> (BetaJsonOutputFormat/builder)
                                        (.schema (-> (BetaJsonOutputFormat$Schema/builder)
                                                     (.additionalProperties
                                                      (update-vals schema ->json-value))
                                                     (.build)))
                                        (.build)))
                           (.build)))
        ;; if the model declines, the API retries on a fallback model
        ;; (not offered for Claude Haiku 5.5, nor in batches)
        (cond-> (and (not batch?) (ai/fallback? model))
          (-> (.addBeta "server-side-fallback-2026-07-01")
              (.putAdditionalBodyProperty "fallbacks" (JsonValue/from "default"))))
        (.build))))

(defn parse-message
  "A BetaMessage -> what request returns, plus the raw usage for the cost
  log. `factor` scales the cost (costs/batch-factor for batch requests)."
  [msg model factor]
  (let [stop   (some-> (.orElse (.stopReason msg) nil) (.asString))
        text   (apply str (keep #(some-> (.orElse (.text %) nil) (.text)) (.content msg)))
        raw    (api-json (.usage msg))
        served (str (.model msg))
        sum    (costs/summarise (costs/lines raw served model factor))]
    {:usage-raw    raw
     :served-model served
     :stop         stop
     :request-id   (.id msg)
     ;; parsed only for a finished answer
     :response     (when-not (#{"refusal" "max_tokens"} stop) (json/read-str text :key-fn keyword))
     :usage        {:input_tokens (:input_tokens sum) :output_tokens (:output_tokens sum)
                    :cache_read (:cache_read sum)
                    :cache_write (+ (:cache_write_5m sum) (:cache_write_1h sum))
                    :stop_reason stop :model served
                    :cost_usd (:cost_usd sum)
                    :cost_chf (some-> (:cost_usd sum) (* costs/usd->chf))}}))

(defn- request*
  [{:keys [model] :as opts}]
  (parse-message (.create (.messages (.beta (client))) (message-params opts)) model 1.0))

(defn claude-opts
  "The planner's request options: the shared knowledge, then the planner's
  instructions, then the data in one user turn."
  [user-text schema & [log-opts]]
  (merge {:knowledge (principles) :system system-intro
          :messages [[:user user-text]] :schema schema}
         log-opts))

(defn call-claude [user-text schema & [log-opts]]
  (request (claude-opts user-text schema log-opts)))

;; ---------------------------------------------------------------------------
;; Half-price batches (Message Batches API)
;;
;; A batch request is sent now and answered within minutes (at most 24 h).
;; The pending request is stored in claude_batches with everything needed to
;; finish the job (its `data`); poll-batches! collects results - also after a
;; restart, since Anthropic keeps them for 29 days - and hands each to the
;; task's handler, the same validation and storage as an instant request.

(defonce ^{:doc "task -> {:done (fn [ds data response usage]) :fail (fn [ds data message])}"}
  batch-handlers (atom {}))

(defn register-batch-handler! [task handlers]
  (swap! batch-handlers assoc (name task) handlers))

(defn batch-request-params
  "The request's params as a batch request entry (via the SDK's own JSON,
  so instant and batch requests are built by the same code)."
  [opts]
  (let [mapper (ObjectMappers/jsonMapper)]
    (.readValue mapper
                (.writeValueAsString mapper (._body ^MessageCreateParams (message-params (assoc opts :batch? true))))
                com.anthropic.models.beta.messages.batches.BatchCreateParams$Request$Params)))

(defn- now-str [] (str (java.time.Instant/now)))

(defn submit-batch!
  "Sends one request through the Message Batches API and stores it as
  pending. `data` (JSON-able) goes to the task's batch handler with the
  result. Throws like request does (no credits included)."
  [ds {:keys [task ref data] :as opts}]
  (let [chosen (ai/for-task task)
        model  (or (:model opts) (:model chosen))
        effort (or (:effort opts) (:effort chosen))
        opts   (assoc opts :model model :effort effort)
        cid    (str (name task) "-" (System/currentTimeMillis) "-" (rand-int 100000))]
    (try
      (let [batch (.create (.batches (.messages (.beta (client))))
                           (-> (com.anthropic.models.beta.messages.batches.BatchCreateParams/builder)
                               (.addRequest (-> (com.anthropic.models.beta.messages.batches.BatchCreateParams$Request/builder)
                                                (.customId cid)
                                                (.params (batch-request-params opts))
                                                (.build)))
                               (.build)))]
        (next.jdbc/execute! ds ["INSERT INTO claude_batches (custom_id, batch_id, task, ref, model, effort, data, status, submitted_at)
                                 VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)"
                                cid (.id batch) (name task) ref model effort (json/write-str data) (now-str)])
        cid)
      (catch Throwable e
        (let [credits? (no-credits? e)]
          (costs/log! {:task task :ref ref :model model :effort effort :batch? true
                       :error (or (ex-message e) (str (class e)))
                       :error_type (when credits? "no_credits")})
          (throw (if credits?
                   (ex-info no-credits-message {:type :no-credits :billing_url billing-url} e)
                   e)))))))

(defn- finish-batch-row! [ds cid status error]
  (next.jdbc/execute! ds ["UPDATE claude_batches SET status = ?, error = ?, ended_at = ? WHERE custom_id = ?"
                          status error (now-str) cid]))

(defn handle-batch-result!
  "One batch result -> the task's handler. `result` is {:type
  \"succeeded\"|\"errored\"|\"expired\"|\"canceled\" :message BetaMessage :error msg}."
  [ds {:keys [custom_id task ref model effort data submitted_at]} {:keys [type message error error-type]}]
  (let [{:keys [done fail]} (get @batch-handlers task)
        data     (some-> data (json/read-str :key-fn keyword))
        ms       (- (System/currentTimeMillis) (.toEpochMilli (java.time.Instant/parse submitted_at)))
        log      (fn [m] (costs/log! (merge {:task task :ref ref :model model :effort effort
                                             :duration_ms ms :batch? true} m)))
        failed!  (fn [msg]
                   (finish-batch-row! ds custom_id "failed" msg)
                   (when fail (fail ds data msg)))]
    (case type
      "succeeded"
      (let [{:keys [usage-raw served-model stop request-id response usage]}
            (parse-message message model costs/batch-factor)]
        (log {:usage usage-raw :served_model served-model :stop_reason stop :request_id request-id})
        (if (#{"refusal" "max_tokens"} stop)
          (failed! (if (= "refusal" stop)
                     "Claude declined this request in the half-price batch, which has no automatic fallback to another model. Try again: your own click runs it instantly."
                     "The answer was cut off (max tokens). Try again."))
          (try (when done (done ds data response usage))
               (finish-batch-row! ds custom_id "done" nil)
               (catch Throwable e (failed! (or (ex-message e) (str (class e))))))))
      "errored"
      (do (log {:error error :error_type (when (= "billing_error" error-type) "no_credits")})
          (failed! (if (= "billing_error" error-type) no-credits-message (str "Claude couldn't process it: " error))))
      ;; expired / canceled: not billed
      (failed! "The half-price batch didn't finish within 24 hours (not billed). Try again."))))

(defn- batch-result->map [^com.anthropic.models.beta.messages.batches.BetaMessageBatchResult r]
  (cond
    (.isSucceeded r) {:type "succeeded" :message (.message (.asSucceeded r))}
    (.isErrored r)   (let [err (.error (.error (.asErrored r)))
                           j   (api-json err)]
                       {:type "errored" :error (or (get-in j [:message]) (str j))
                        :error-type (:type j)})
    (.isExpired r)   {:type "expired"}
    :else            {:type "canceled"}))

(defn pending-batches [ds]
  (db/query ds ["SELECT * FROM claude_batches WHERE status = 'pending' ORDER BY submitted_at"]))

(defn poll-batches!
  "Collects finished batches. Safe to call often; does nothing without
  pending requests. Returns how many results were handled."
  [ds]
  (let [pending (pending-batches ds)]
    (if (empty? pending)
      0
      (let [svc (.batches (.messages (.beta (client))))]
        (reduce + (for [[batch-id rows] (group-by :batch_id pending)]
                    (try
                      (let [b (.retrieve svc ^String batch-id)]
                        (if (= "ended" (.asString (.processingStatus b)))
                          (let [by-id (into {} (map (juxt :custom_id identity)) rows)]
                            (with-open [results (.resultsStreaming svc ^String batch-id)]
                              (reduce + (for [r (iterator-seq (.iterator results))
                                              :let [row (by-id (.customId r))] :when row]
                                          (do (handle-batch-result! ds row (batch-result->map (.result r))) 1)))))
                          0))
                      (catch Throwable e
                        (println "Checking batch" batch-id "failed:" (ex-message e))
                        0))))))))

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
        ws     (vec (filter inside (:workouts response)))
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
      (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage}))
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
      (submit-batch! ds (claude-opts (user-message ctx) response-schema
                                     {:task :week_plan :ref (str "proposal " id)
                                      :data {:id id :monday monday :ctx ctx :later later}})))
    (catch Throwable e
      (plan/fail-proposal! ds id (or (ex-message e) (str (class e))))
      (when-let [[[nid nm] & more] (seq later)]
        (submit-week! ds nid nm more)))))

(defn- next-week! [ds {:keys [later]}]
  (when-let [[[nid nm] & more] (seq later)]
    (submit-week! ds nid nm more)))

(register-batch-handler!
 :week_plan
 {:done (fn [ds {:keys [id monday ctx] :as data} response usage]
          (try
            (let [{:keys [response warnings]} (validate monday response ctx)]
              (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage}))
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
        ids    (vec (map-indexed (fn [i m] (plan/create-proposal! ds m (model-for :week_plan)
                                                                  {:delivery (when batch? (if (zero? i) "batch" "queued"))}))
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
        changes  (vec (filter ok? (:changes response)))
        after    (apply-changes (filter #(#{"planned" "done"} (:status %)) in-scope) changes)
        weeks    (distinct (map #(season/monday (:date %)) changes))]
    {:response (assoc response :changes changes)
     :warnings (vec (distinct (mapcat (fn [m] (rules/check-week m (filter #(= m (season/monday (:date %))) after) (rule-opts (info-of m))))
                                      weeks)))}))

(defn- finish-adjustment! [ds id today scope-end in-scope ctx response usage]
  (let [{:keys [response warnings]} (validate-adjustment today scope-end response in-scope
                                                         #(plan/week-info ds %))]
    (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage})))

(register-batch-handler!
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
        id        (plan/create-proposal! ds monday (model-for :adjust) {:kind "adjust" :scope-end scope-end :signals signals
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
                              :recent_feedback (vec (for [a (analysis/activities ds {:from (season/add-days today -14) :to today})
                                                          :when (or (:rpe a) (:garmin_rpe a) (pos? (or (:pain a) 0)) (:note a))]
                                                      (select-keys a [:date :name :rpe :garmin_rpe :pain :pain_location :note]))))
              opts     (claude-opts (adjust-message ctx today scope-end) adjust-schema
                                    {:task :adjust :ref (str "proposal " id)})]
          (if batch?
            (submit-batch! ds (assoc opts :data {:id id :today today :scope_end scope-end
                                                 :in_scope in-scope :ctx ctx}))
            (let [{:keys [response usage]} (request opts)]
              (finish-adjustment! ds id today scope-end in-scope ctx response usage))))
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
  (let [m    (season/monday today)
        race (:race_date (plan/current-season ds))]
    (vec (for [i (range 1 (inc horizon-weeks))
               :let [mo (season/add-days m (* 7 i))
                     p  (plan/latest-proposal ds mo)]
               :when (and race (<= (compare mo (season/monday race)) 0)
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
        adjust-today? (seq (db/query ds ["SELECT 1 FROM plan_proposals WHERE kind = 'adjust' AND substr(created_at, 1, 10) = ?" today]))
        proposed (atom [])]
    (when (and (seq new) (api-key?) (athlete/content) (not adjust-today?) (not-any? #(= "adjust" (:kind %)) open))
      (let [id (propose-adjustment! ds new nil :auto)]
        (doseq [s new]
          (next.jdbc/execute! ds ["INSERT OR IGNORE INTO adapt_signals (key, proposal_id, first_seen) VALUES (?, ?, ?)"
                                  (:key s) id today]))
        (swap! proposed conj id)))
    (swap! proposed into (plan-ahead! ds))
    {:signals sigs :proposed @proposed}))
