(ns coach.planner
  "Asks Claude to propose one week of training from training-principles.md
  and the athlete's recent data. The proposal is stored for review; nothing
  enters the plan until the athlete accepts it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.analysis :as analysis]
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
(def principles-file "training-principles.md")
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
  "§9 weekly metrics from recorded activities. Easy = runs with avg HR ≤ 148 (§3)."
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

(defn context
  "Everything the planner needs for the week starting `monday`."
  [ds monday]
  (let [today   (str (LocalDate/now))
        from    (season/add-days monday -42)
        acts    (analysis/activities ds {:from from :to today})
        by-week (group-by #(season/monday (:date %)) acts)
        weeks   (for [i (range 6 0 -1) :let [m (season/add-days monday (* -7 i))]]
                  (week-summary m (by-week m)))
        pmc     (last (analysis/pmc ds today today))
        types   (into {} (map (juxt :monday :week_type)) (plan/weeks ds (season/add-days monday -21) monday))]
    {:today            today
     :target_week      (assoc (season/week-info monday)
                              :week_type (types monday)
                              :note (:note (first (plan/weeks ds monday monday))))
     :previous_week_types (dissoc types monday)
     :last_6_weeks     weeks
     :recent_activities (vec (for [a (filter #(>= (compare (:date %) (season/add-days today -14)) 0) acts)]
                               {:date (:date a) :sport (:sport a) :name (:name a)
                                :km (r1 (some-> (:distance_m a) (/ 1000.0))) :minutes (Math/round (/ (secs a) 60.0))
                                :avg_hr (some-> (:avg_hr a) Math/round) :tss (some-> (:tss a) Math/round)}))
     :readiness_last_7_days (vec (for [w (analysis/wellness ds (season/add-days today -6) today)]
                                   (select-keys w [:date :hrv_avg :hrv_status :rhr :sleep_s :sleep_score
                                                   :readiness :body_battery_high])))
     :load_today       (select-keys pmc [:ctl :atl :tsb])
     :planned_previous_and_target_weeks
     (vec (for [w (plan/workouts ds (season/add-days monday -7) (season/add-days monday 6))]
            (select-keys w [:date :slot :session_id :title :distance_km :duration_min :status])))}))

;; ---------------------------------------------------------------------------
;; Prompt and response schema

(def system-intro
  "You are the running coach for the athlete described in the training principles below.
You plan one week at a time; the athlete reviews every session before it enters the plan.
The plan lives only in the athlete's dashboard - nothing is sent to the Garmin watch.

Follow the principles. The hard rules (§10) are never broken; when rules conflict, use the
priority order in §0. Remember that Garmin-recorded load underestimates the athlete's real load (§1).
If the target week's type is unknown, plan nothing and say so in the warnings (§7.1).

Return only sessions (rest days have no entry). Dates must fall inside the target week. Two
sessions on one day use the AM and PM slots. Give every session an estimated duration; use
distance_km 0 when distance doesn't apply. Keep the text short - the athlete reads it on a phone:
- `title`: a few words.
- `intensity`: the zone with its HR range, or the load guidance for gym work.
- `description`: one line with the session's structure (e.g. 15' warm-up, 3×10' T with 2' jog, 10' cool-down).
- `reason`: one short line, at most about 12 words.
- `summary`: 2-3 sentences on the week's intent.
- `warnings`: only what the athlete must know (readiness concerns, rule tensions, missing information), one line each.

# Training principles

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

(defn call-claude
  "One request: principles in a cached system block, data in the user turn,
  JSON out via structured outputs. Returns {:response map :usage map}."
  [principles ctx]
  (let [params (-> (MessageCreateParams/builder)
                   (.model ^String model)
                   (.maxTokens 32000)
                   (.systemOfBetaTextBlockParams
                    [(-> (BetaTextBlockParam/builder)
                         (.text ^String (str system-intro principles))
                         (.cacheControl (.build (BetaCacheControlEphemeral/builder)))
                         (.build))])
                   (.addUserMessage ^String (user-message ctx))
                   (.thinking (.build (BetaThinkingConfigAdaptive/builder)))
                   (.outputConfig (-> (BetaOutputConfig/builder)
                                      (.effort BetaOutputConfig$Effort/HIGH)
                                      (.format (-> (BetaJsonOutputFormat/builder)
                                                   (.schema (-> (BetaJsonOutputFormat$Schema/builder)
                                                                (.additionalProperties
                                                                 (update-vals response-schema ->json-value))
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
                      :longest_km (apply max 0 (keep :longest_km (:last_6_weeks ctx))))]
    {:response (assoc response :workouts ws)
     :warnings (rules/check-week monday ws {:prev (assoc prev :week_type
                                                         (get (:previous_week_types ctx)
                                                              (season/add-days monday -7)))
                                            :week-type (get-in ctx [:target_week :week_type])})}))

(defn propose!
  "Starts a proposal for the week of `monday` in the background and returns
  its id. Poll coach.plan/proposal for the result."
  [ds monday]
  (let [monday (season/monday monday)
        id     (plan/create-proposal! ds monday model)]
    (future
      (try
        (let [ctx (context ds monday)
              {:keys [response usage]} (call-claude (slurp principles-file) ctx)
              {:keys [response warnings]} (validate monday response ctx)]
          (plan/finish-proposal! ds id {:context ctx :response response :warnings warnings :usage usage}))
        (catch Throwable e
          (plan/fail-proposal! ds id (or (ex-message e) (str (class e)))))))
    id))
