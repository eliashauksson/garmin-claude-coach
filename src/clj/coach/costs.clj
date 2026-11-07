(ns coach.costs
  "A log of every Claude API request (coach.claude/request writes it), with
  the cost of each computed when it ran, the way the Claude platform bills it:
  token counts from the response × that model's list prices.

  Costs are stored per request and never recomputed, so a later price change
  doesn't rewrite history. A request served by a fallback model is priced per
  model, from the response's usage.iterations."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.db :as db]
            [coach.paths :as paths]
            [next.jdbc :as jdbc]))

;; ---------------------------------------------------------------------------
;; Prices

(def prices
  "USD per million tokens (Anthropic list prices, Oct 2026). Cache writes:
  5-minute TTL = 1.25x input, 1-hour TTL = 2x input. Haiku 5.5 is priced for
  prompts up to 100K tokens; the coach's prompts are far below that."
  (let [p (fn [in out read] {:input in :output out :cache_read read
                             :cache_write_5m (* 1.25 in) :cache_write_1h (* 2.0 in)})]
    {"claude-fable-5-1"  (p 10.0 50.0 0.25)
     "claude-fable-5"    (p 10.0 50.0 1.00)
     "claude-opus-5-5"   (p 4.0 20.0 0.20)
     "claude-opus-5"     (p 5.0 25.0 0.50)
     "claude-opus-4-8"   (p 5.0 25.0 0.50)
     "claude-opus-4-7"   (p 5.0 25.0 0.50)
     "claude-sonnet-5-5" (p 2.0 10.0 0.20)
     "claude-sonnet-5"   (p 2.0 10.0 0.20)
     "claude-haiku-5-5"  (p 0.10 0.50 0.01)}))

(def batch-factor "Message Batches API requests cost half." 0.5)

(def usd->chf
  "Approximate exchange rate for showing costs in CHF. Anthropic bills in USD;
  this is only for display."
  0.81)

(def token-keys [:input_tokens :output_tokens :cache_read :cache_write_5m :cache_write_1h])

(def ^:private price-key
  {:input_tokens :input :output_tokens :output :cache_read :cache_read
   :cache_write_5m :cache_write_5m :cache_write_1h :cache_write_1h})

(defn line-cost
  "Pure: USD for one model's tokens, or nil if the model has no known price.
  `factor` scales the list price (batch-factor for batch requests)."
  ([model tokens] (line-cost model tokens 1.0))
  ([model tokens factor]
   (when-let [p (prices model)]
     (* factor (reduce + (for [k token-keys] (* (or (get tokens k) 0) (p (price-key k)) 1e-6)))))))

(defn- n [x] (if (number? x) (long x) 0))

(defn- tokens-of
  "API usage map (snake_case, as the API sends it) -> our token keys. Cache
  creation without the 5m/1h split counts as 5m (the default TTL)."
  [u]
  (let [w5 (get-in u [:cache_creation :ephemeral_5m_input_tokens])
        w1 (get-in u [:cache_creation :ephemeral_1h_input_tokens])
        total-w (n (:cache_creation_input_tokens u))]
    {:input_tokens   (n (:input_tokens u))
     :output_tokens  (n (:output_tokens u))
     :cache_read     (n (:cache_read_input_tokens u))
     :cache_write_5m (if (or w5 w1) (n w5) total-w)
     :cache_write_1h (n w1)}))

(defn lines
  "Pure: the API's usage map -> one priced line per model that ran.
  With usage.iterations (a fallback), each iteration is its own line: the
  plain \"message\" iteration ran on the requested model, fallback ones
  name theirs. Without iterations, everything ran on `served-model`."
  ([usage served-model] (lines usage served-model served-model 1.0))
  ([usage served-model requested-model] (lines usage served-model requested-model 1.0))
  ([usage served-model requested-model factor]
  (let [its (filter map? (:iterations usage))]
    (if (seq its)
      (vec (for [[model grp] (group-by #(or (:model %) requested-model) its)
                 :let [t (apply merge-with + (map tokens-of grp))]]
             (assoc t :model model :cost_usd (line-cost model t factor))))
      (let [t (tokens-of usage)]
        [(assoc t :model served-model :cost_usd (line-cost served-model t factor))])))))

(defn summarise
  "Pure: lines -> totals for one request (cost nil if any line is unpriced)."
  [ls]
  (let [costs (map :cost_usd ls)]
    (merge (apply merge-with + (map #(select-keys % token-keys) ls))
           {:cost_usd (when (every? some? costs) (reduce + 0.0 costs))
            :models   (vec (distinct (map :model ls)))})))

;; ---------------------------------------------------------------------------
;; Storage

(defonce ^{:doc "The DB requests are logged to; set by the dashboard on start.
  nil (tests, REPL) means requests aren't logged."}
  log-ds (atom nil))

(defn use-db! [ds] (reset! log-ds ds))

(defn insert! [ds {:keys [lines] :as row}]
  (let [cols [:at :task :ref :model_requested :model_used :effort :status :stop_reason :error
              :input_tokens :output_tokens :cache_read :cache_write_5m :cache_write_1h
              :cost_usd :duration_ms :request_id :lines :usage_raw :source :error_type :batch]
        row  (-> (merge (zipmap token-keys (repeat 0)) {:batch 0} row)
                 (update :lines #(some-> % json/write-str))
                 (update :usage_raw #(some-> % json/write-str))
                 (update :source #(or % "logged")))]
    (jdbc/execute! ds (into [(str "INSERT INTO api_calls (" (str/join ", " (map name cols)) ") VALUES ("
                                  (str/join ", " (repeat (count cols) "?")) ")")]
                            (map #(get row %) cols)))
    (some? lines)))

(defn log!
  "Records one request. `call`: {:task :ref :model :effort :duration_ms} plus,
  on success, {:usage <API usage map> :served_model :stop_reason :request_id}
  or, on failure, {:error msg}. Never throws: logging must not break a request."
  [{:keys [task ref model effort duration_ms usage served_model stop_reason request_id error error_type batch?]}]
  (when-let [ds @log-ds]
    (try
      (let [ls  (when usage (lines usage (or served_model model) model (if batch? batch-factor 1.0)))
            sum (when ls (summarise ls))]
        (insert! ds (merge {:at (str (java.time.Instant/now)) :task (name task) :ref (some-> ref str)
                            :model_requested model :model_used (some->> (:models sum) (str/join ", "))
                            :effort (some-> effort str/lower-case)
                            :status (cond error "error" (= "refusal" stop_reason) "refused" :else "ok")
                            :stop_reason stop_reason :error error :error_type error_type
                            :batch (if batch? 1 0)
                            :duration_ms duration_ms :request_id request_id
                            :lines ls :usage_raw usage}
                           (select-keys sum (conj token-keys :cost_usd)))))
      (catch Throwable e
        (println "Could not log the API request:" (ex-message e))))))

;; ---------------------------------------------------------------------------
;; Earlier requests: the week/adjustment proposals and season plans stored
;; their usage before this log existed. Imported once.

(defn- old-usage->tokens [u]
  {:input_tokens (n (:input_tokens u)) :output_tokens (n (:output_tokens u))
   :cache_read (n (:cache_read u)) :cache_write_5m (n (:cache_write u)) :cache_write_1h 0})

(defn import-earlier!
  "Copies usage stored on plan_proposals and season_plans into api_calls,
  once. Returns how many requests were imported."
  [ds]
  (if (db/get-state ds :api_calls_imported)
    0
    (let [rows (concat
                (for [p (db/query ds
                                  ["SELECT id, kind, model, usage, created_at FROM plan_proposals WHERE usage IS NOT NULL"])]
                  (assoc p :task (if (= "adjust" (:kind p)) "adjust" "week_plan") :ref (str "proposal " (:id p))))
                (for [p (db/query ds ["SELECT id, model, usage, created_at FROM season_plans WHERE usage IS NOT NULL"])]
                  (assoc p :task "season_plan" :ref (str "season plan " (:id p)))))]
      (doseq [{:keys [model usage created_at task ref]} rows
              :let [u (json/read-str usage :key-fn keyword)
                    t (old-usage->tokens u)]]
        (insert! ds (merge t {:at created_at :task task :ref ref
                              :model_requested model :model_used model :effort "high"
                              :status "ok" :stop_reason (:stop_reason u)
                              :cost_usd (line-cost model t)
                              :lines [(assoc t :model model :cost_usd (line-cost model t))]
                              :source "imported"})))
      (db/set-state! ds :api_calls_imported (str (java.time.Instant/now)))
      (count rows))))

;; ---------------------------------------------------------------------------
;; Overview (what the Costs page shows)

(defn- group-sums
  "Request rows -> per-group totals, most expensive first."
  [rows k]
  (->> (group-by k rows)
       (map (fn [[g rs]]
              (merge {:key g :requests (count rs)
                      :errors (count (remove #(= "ok" (:status %)) rs))
                      :cost_usd (reduce + 0.0 (keep :cost_usd rs))}
                     (into {} (for [t token-keys] [t (reduce + 0 (map #(or (get % t) 0) rs))])))))
       (sort-by :cost_usd >)
       vec))

(defn- by-model
  "Per-model totals from each request's priced lines (a fallback request
  counts toward both models)."
  [rows]
  (group-sums (for [r rows
                    l (or (some-> (:lines r) (json/read-str :key-fn keyword))
                          [{:model (or (:model_used r) (:model_requested r))}])]
                (assoc (merge (select-keys r [:status]) l) :model (or (:model l) "unknown")))
              :model))

(defn credits-out
  "Pure-ish: {:since at} when the latest Claude request failed for lack of
  credits (and none has succeeded since), else nil."
  [ds]
  (let [last (first (db/query ds ["SELECT at, status, error_type FROM api_calls
                                   WHERE status = 'ok' OR error_type IS NOT NULL
                                   ORDER BY at DESC LIMIT 1"]))]
    (when (= "no_credits" (:error_type last))
      {:since (:at last)})))

(defn local-date
  "\"2026-11-01T23:30:00Z\" -> the local calendar date (here: 2026-11-02)."
  [instant]
  (str (.toLocalDate (.atZone (java.time.Instant/parse instant) (java.time.ZoneId/systemDefault)))))

(def ^:private athlete-sums-sql
  "SELECT count(*) AS requests, coalesce(sum(cost_usd), 0) AS cost_usd,
          coalesce(sum(input_tokens), 0) + coalesce(sum(cache_read), 0)
            + coalesce(sum(cache_write_5m), 0) + coalesce(sum(cache_write_1h), 0) AS input_tokens,
          coalesce(sum(output_tokens), 0) AS output_tokens
   FROM api_calls")

(defn athlete-totals
  "One athlete's Claude costs from `from` (ISO date; nil = all), and this month."
  [ds from]
  (let [month (str (subs (str (java.time.LocalDate/now)) 0 7) "-01")
        sums  (fn [since]
                (first (db/query ds (if since
                                      [(str athlete-sums-sql " WHERE at >= ?") since]
                                      [athlete-sums-sql]))))]
    (assoc (sums from) :this_month (:cost_usd (sums month)))))

(defn by-athlete
  "Costs per athlete when several share this server (and the Claude key), else
  nil. `ds` is this athlete's database; the others are read read-only. An
  athlete whose database can't be read shows :error instead of numbers."
  [ds from]
  (let [as (paths/athletes)]
    (when (> (count as) 1)
      (vec (for [{:keys [name data-dir current]} as]
             (merge {:name name :current current}
                    (try (athlete-totals (if current
                                           ds
                                           (db/read-only-datasource (str (io/file data-dir "coach.db"))))
                                         from)
                         (catch Exception e {:error (ex-message e)}))))))))

(defn overview
  "Everything the Costs page needs for requests from `from` (ISO date,
  inclusive; nil = all)."
  [ds from]
  (let [rows (db/query ds (if from
                            ["SELECT * FROM api_calls WHERE at >= ? ORDER BY at DESC" from]
                            ["SELECT * FROM api_calls ORDER BY at DESC"]))
        all  (first (db/query ds ["SELECT coalesce(sum(cost_usd), 0) AS cost_usd, count(*) AS requests,
                                          min(at) AS first_at FROM api_calls"]))
        month (str (subs (str (java.time.LocalDate/now)) 0 7) "-01")]
    {:usd_chf   usd->chf
     :prices    prices
     :all_time  all
     :this_month (first (db/query ds ["SELECT coalesce(sum(cost_usd), 0) AS cost_usd, count(*) AS requests
                                       FROM api_calls WHERE at >= ?" month]))
     :totals    (first (group-sums rows (constantly :all)))
     :by_task   (group-sums rows :task)
     :by_model  (by-model rows)
     :daily     (->> rows
                     (group-by (juxt #(local-date (:at %)) :task))
                     (map (fn [[[d t] rs]] {:date d :task t :cost_usd (reduce + 0.0 (keep :cost_usd rs))
                                            :requests (count rs)}))
                     (sort-by :date)
                     vec)
     ;; what each task cost with each model and effort it has run with
     :by_setting (->> (filter #(= "ok" (:status %)) rows)
                      (group-by (juxt :task :model_requested :effort #(= 1 (:batch %))))
                      (map (fn [[[t m e b] rs]]
                             (let [n (count rs) avg #(/ (reduce + 0.0 (keep % rs)) n)]
                               {:task t :model m :effort e :batch b :requests n
                                :avg_cost_usd (avg :cost_usd)
                                :avg_output_tokens (avg :output_tokens)
                                :avg_duration_ms (let [ds (keep :duration_ms rs)]
                                                   (when (seq ds) (/ (reduce + ds) (count ds))))
                                :last_at (:at (first rs))})))
                      (sort-by :last_at #(compare %2 %1))
                      vec)
     :by_athlete (by-athlete ds from)
     :requests  (vec (for [r (take 200 rows)] (dissoc r :usage_raw :lines)))
     :pending_batches (first (db/query ds ["SELECT count(*) AS n, min(submitted_at) AS since
                                            FROM claude_batches WHERE status = 'pending'"]))
     :unpriced  (count (filter #(and (= "ok" (:status %)) (nil? (:cost_usd %))) rows))
     :imported_until (:at (first (db/query ds ["SELECT max(at) AS at FROM api_calls WHERE source = 'imported'"])))}))
