(ns coach.claude
  "Every Claude request goes through here: the API key, the shared system
  prompt (training principles + athlete.md), instant requests with their cost
  log, and half-price batches (Message Batches API)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.ai-settings :as ai]
            [coach.athlete :as athlete]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.i18n :as i18n :refer [t]]
            [coach.paths :as paths]
            [coach.translate :as translate]
            [next.jdbc])
  (:import (com.anthropic.client.okhttp AnthropicOkHttpClient)
           (com.anthropic.core JsonValue ObjectMappers)
           (com.anthropic.models.beta.messages BetaCacheControlEphemeral BetaJsonOutputFormat
                                               BetaJsonOutputFormat$Schema BetaOutputConfig
                                               BetaOutputConfig$Effort BetaTextBlockParam
                                               BetaThinkingConfigAdaptive MessageCreateParams)
           (com.anthropic.models.beta.messages.batches BatchCreateParams BatchCreateParams$Request
                                                       BatchCreateParams$Request$Params BetaMessageBatchResult)
           (java.time Duration)))

(defn model-for
  "The model the athlete chose for `task` (coach.ai-settings)."
  [task]
  (:model (ai/for-task task)))

(def general-file "training-principles.md")

(defn- general-text
  "training-principles.md from the project folder, else the copy inside the
  installed app's jar."
  []
  (let [f (paths/app-file general-file)]
    (slurp (if (.exists f) f (or (io/resource general-file)
                                 (throw (ex-info "training-principles.md is missing" {})))))))

(defn knowledge
  "The first system block of every Claude request: the general principles and
  athlete.md, the same text for every task. Task instructions come after it.
  It is cached (request :cache?) only where another request of the same task
  is likely within the cache's 5 minutes: the cache is per model and per
  output schema (measured: a different schema misses, a different effort
  hits), and an unread cache write costs 25% more than plain input."
  [doc]
  (str "# General training principles (read-only)\n\n" (general-text)
       "\n\n# Current athlete.md\n\n" (or doc "(no athlete.md yet)")))

(defn principles
  "knowledge for the planning tasks, which need athlete.md."
  []
  (knowledge (or (athlete/content)
                 (throw (ex-info (t "No athlete information yet. Add it on the Plan page first.")
                                 {:type :no-athlete})))))
(def key-file (str (System/getProperty "user.home") "/.config/coach/anthropic-key"))

(def ^:dynamic *key-file* key-file)

(defn- file-key [] (let [f (io/file *key-file*)] (when (.exists f) (not-empty (str/trim (slurp f))))))

(defn api-key []
  (or (file-key)
      (not-empty (System/getenv "ANTHROPIC_API_KEY"))
      (throw (ex-info (t "Claude isn't connected yet. Add your API key in Settings.") {:type :no-api-key}))))

(defn key-hint
  "\"sk-ant-…a1b2\": enough to recognise a key, never the key itself."
  [k]
  (when (and k (> (count k) 12)) (str (subs k 0 7) "…" (subs k (- (count k) 4)))))

(defn key-status
  "Whether Claude is connected, and where its key comes from (\"file\": saved
  in Settings; \"env\": ANTHROPIC_API_KEY, which only the shell can change)."
  []
  (let [fk (file-key) ek (not-empty (System/getenv "ANTHROPIC_API_KEY"))]
    {:connected (boolean (or fk ek))
     :source    (cond fk "file" ek "env")
     :hint      (key-hint (or fk ek))
     :file      *key-file*}))

(defn valid-key-format? [k]
  (boolean (and (string? k) (re-matches #"sk-ant-[A-Za-z0-9_\-]{20,}" k))))

(defn save-key!
  "Stores the API key in *key-file*, readable only by this user."
  [k]
  (let [k (str/trim (str k))]
    (when-not (valid-key-format? k)
      (throw (ex-info (t "That doesn't look like a Claude API key. It starts with sk-ant- (Claude Console → API keys).")
                      {})))
    (paths/secure-spit! *key-file* k)
    (key-status)))

(defn remove-key!
  "Deletes the saved key (a key from ANTHROPIC_API_KEY stays)."
  []
  (io/delete-file (io/file *key-file*) true)
  (key-status))

(defn key-rejected?
  "Whether a failed request means the key itself is wrong (not credits or the network)."
  [msg]
  (boolean (re-find #"(?i)\b401\b|authentication_error|invalid x-api-key|invalid api key" (str msg))))


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

(defn no-credits-message
  "In the current language, with the link to add funds."
  []
  (t "Your Claude API credits have run out. Add funds in the Claude Console ({0}), then try again." billing-url))

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
          (throw (ex-info (t "The model stopped early: {0}" stop) {:stop_reason stop})))
        (select-keys r [:response :usage]))
      (catch clojure.lang.ExceptionInfo e
        (when-not (:stop_reason (ex-data e)) (log {:error (ex-message e)}))
        (throw e))
      (catch Throwable e
        (if (no-credits? e)
          (do (log {:error (or (ex-message e) "billing_error") :error_type "no_credits"})
              (throw (ex-info (no-credits-message) {:type :no-credits :billing_url billing-url} e)))
          (do (log {:error (or (ex-message e) (str (class e)))})
              (throw e)))))))

(def language-instructions
  "Added to the task instructions when the dashboard isn't in English. It sits
  after the cached knowledge block, so the cache is the same in every language."
  (let [keep (str "Keep session IDs, JSON field names, fixed values (such as phase names, slots and "
                  "intensity codes) and the section headings of the training principles and athlete.md "
                  "exactly as given. Text you write into athlete.md itself follows the language that "
                  "document is written in.")]
    {"de" (str "\n\nLanguage: write every text meant for the athlete (titles, descriptions, reasons, notes, "
               "summaries, chat replies) in German as written in Switzerland: Swiss Standard German spelling "
               "with \"ss\", never \"ß\". Address the athlete as \"du\". " keep)
     "fr" (str "\n\nLanguage: write every text meant for the athlete (titles, descriptions, reasons, notes, "
               "summaries, chat replies) in French as written in French-speaking Switzerland (standard "
               "French spelling; Swiss usage such as \"septante\" and \"nonante\" where numbers are "
               "written out). Address the athlete as \"tu\". " keep)}))

(defn with-language
  "The task instructions with the language instruction for the current
  dashboard language (none for English)."
  [system]
  (if-let [extra (and system (language-instructions @i18n/lang))]
    (str system extra)
    system))

(defn message-params
  "The request as SDK params. Batch requests leave out the server-side
  fallback, which the Message Batches API doesn't accept."
  [{:keys [knowledge system messages schema model effort cache? batch? task]}]
  (let [system (if (= :translation task) system (with-language system)) ; it names its own languages
        block (fn [^String text cache?]
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

(defn translate-soon!
  "Translates Claude's texts into the wanted dashboard languages, in the
  background (coach.translate). `new-texts`: a result Claude just wrote, in
  the current language. Skipped while the credits are out (like the
  automatic planning); the next run after that fills in what's missing."
  [& [new-texts]]
  (when-not (some-> @costs/log-ds costs/credits-out)
    (translate/soon! request new-texts)))


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
                BatchCreateParams$Request$Params)))

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
      (let [request (-> (BatchCreateParams$Request/builder)
                        (.customId cid)
                        (.params (batch-request-params opts))
                        (.build))
            batch   (.create (.batches (.messages (.beta (client))))
                             (-> (BatchCreateParams/builder) (.addRequest request) (.build)))]
        (next.jdbc/execute! ds
                            ["INSERT INTO claude_batches
                                (custom_id, batch_id, task, ref, model, effort, data, status, submitted_at)
                              VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)"
                             cid (.id batch) (name task) ref model effort (json/write-str data) (now-str)])
        cid)
      (catch Throwable e
        (let [credits? (no-credits? e)]
          (costs/log! {:task task :ref ref :model model :effort effort :batch? true
                       :error (or (ex-message e) (str (class e)))
                       :error_type (when credits? "no_credits")})
          (throw (if credits?
                   (ex-info (no-credits-message) {:type :no-credits :billing_url billing-url} e)
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
                     (t "Claude declined this request in the half-price batch, which has no automatic fallback to another model. Try again: your own click runs it instantly.")
                     (t "The answer was cut off (max tokens). Try again.")))
          (try (when done (done ds data response usage))
               (finish-batch-row! ds custom_id "done" nil)
               (catch Throwable e (failed! (or (ex-message e) (str (class e))))))))
      "errored"
      (do (log {:error error :error_type (when (= "billing_error" error-type) "no_credits")})
          (failed! (if (= "billing_error" error-type) (no-credits-message)
                     (t "Claude couldn't process it: {0}" error))))
      ;; expired / canceled: not billed
      (failed! (t "The half-price batch didn't finish within 24 hours (not billed). Try again.")))))

(defn- batch-result->map [^BetaMessageBatchResult r]
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


(defn api-key? [] (try (api-key) true (catch Exception _ false)))
