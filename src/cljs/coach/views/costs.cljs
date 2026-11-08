(ns coach.views.costs
  "API costs: what the Claude requests cost, in total and per task, model and
  request, like the usage and cost pages of the Claude platform."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :as i18n :refer [t tk]]
            [coach.ui :as ui]))

(def ranges [["30" (tk "30 days")] ["90" (tk "90 days")] ["365" (tk "1 year")] ["all" (tk "All")]])

(def settings-url "/api/ai-settings")

(defn url [route]
  (let [r (get-in route [:params :range] "all")]
    (str "/api/costs" (when (not= r "all") (str "?from=" (ui/days-ago (js/parseInt r)))))))

;; Fixed order, so a task keeps its colour whatever the range shows.
(def tasks
  [{:id "week_plan"       :label (tk "Week plans")       :color "--task-1"}
   {:id "season_plan"     :label (tk "Season plans")     :color "--task-2"}
   {:id "adjust"          :label (tk "Plan adjustments") :color "--task-3"}
   {:id "athlete_chat"    :label (tk "Athlete chat")     :color "--task-4"}
   {:id "athlete_summary" :label (tk "Athlete summary")  :color "--task-5"}
   {:id "translation"     :label (tk "Translations")     :color "--task-6"}
   {:id "structure"       :label (tk "Session structure") :color "--task-7"}
   ;; neutral grey: not one of the five categorical slots
   {:id "test"            :label (tk "Test calls")       :color "--fam-other"}])

(def task-by-id (into {} (map (juxt :id identity) tasks)))
(defn- task-label [id] (t (:label (task-by-id id) id)))

;; ---------------------------------------------------------------------------
;; Formatting

(defn usd [x & [digits]]
  (when (ui/num? x)
    (str "$" (.toFixed x (or digits (cond (zero? x) 2 (< x 0.01) 4 (< x 1) 3 :else 2))))))

(defn chf [x usd->chf] (when (ui/num? x) (str "CHF " (.toFixed (* x usd->chf) 2))))

(defn tokens
  "12345 -> \"12.3k\", 1234567 -> \"1.23M\"."
  [n]
  (when (ui/num? n)
    (cond (>= n 1e6) (str (.toFixed (/ n 1e6) 2) "M")
          (>= n 1e4) (str (.toFixed (/ n 1e3) 1) "k")
          :else      (ui/thousands n))))

(defn- n-requests [n] (if (= 1 n) (t "1 request") (t "{0} requests" n)))

(defn- pct [a b] (if (and (ui/num? a) (pos? b)) (str (js/Math.round (* 100 (/ a b))) "%") "—"))

(defn- input-total [r] (+ (:input_tokens r 0) (:cache_read r 0) (:cache_write_5m r 0) (:cache_write_1h r 0)))

(defn- local-time [iso]
  (when iso
    (let [d (js/Date. iso)
          this-year? (= (.getFullYear d) (.getFullYear (js/Date.)))]
      (str (.toLocaleDateString d (i18n/locale) (if this-year?
                                            #js {:day "numeric" :month "short"}
                                            #js {:day "numeric" :month "short" :year "numeric"})) ", "
           (.toLocaleTimeString d (i18n/locale) #js {:hour "2-digit" :minute "2-digit"})))))

(defn- dot [color] [:span.dot {:style {:background (str "var(" color ")")}}])

;; ---------------------------------------------------------------------------
;; Pieces

(defn- kpis [{:keys [totals this_month usd_chf by_task]}]
  (let [in   (input-total totals)
        busy (first by_task)]
    [:div.row {:replicant/key "kpi"}
     (ui/tile {:label (t "Spent in this range") :tint "blue" :size "md"
               :value (usd (:cost_usd totals 0) 2)
               :sub (str (chf (:cost_usd totals 0) usd_chf) " · " (n-requests (:requests totals 0))
                         (when (pos? (:errors totals 0)) (str " · " (t "{0} failed" (:errors totals)))))})
     (ui/tile {:label (t "This month") :size "md"
               :value (usd (:cost_usd this_month 0) 2)
               :sub (str (chf (:cost_usd this_month 0) usd_chf) " · " (n-requests (:requests this_month 0)))})
     (ui/tile {:label (t "Tokens") :size "md"
               :value (tokens (+ in (:output_tokens totals 0)))
               :sub (t "{0} in · {1} out" (tokens in) (tokens (:output_tokens totals 0)))})
     (ui/tile {:label (t "Most spent on") :size "md"
               :value (when busy (pct (:cost_usd busy) (:cost_usd totals 0)))
               :sub (if busy (str (task-label (:key busy)) " · " (usd (:cost_usd busy))) (t "No requests yet"))})]))

(defn- daily-chart [daily]
  (let [dates (vec (distinct (map :date daily)))
        cost  (into {} (for [{:keys [date task cost_usd]} daily] [[date task] cost_usd]))
        shown (filter (fn [task] (some #(= (:id task) (:task %)) daily)) tasks)]
    (ui/chart-card
     {:title (t "Daily cost")
      :legend-items (for [task shown] {:label (t (:label task)) :color (:color task)})
      :chart (c/echart
              {:key "cost-daily" :class "h240"
               :option-fn
               (fn []
                 (merge (c/base {:x-type "category" :y-min 0 :grid-left 52
                                 :y-fmt (fn [v] (str "$" (.toFixed v (if (< v 10) 2 0))))
                                 :x-fmt (fn [d] (ui/date-short d))
                                 :tooltip-fmt
                                 (fn [params]
                                   (let [d (.-name (first params))
                                         rows (for [task (reverse shown) :let [v (get cost [d (:id task)])] :when v]
                                                {:label (t (:label task)) :value (usd v)
                                                 :color (c/css-var (:color task))})]
                                     (c/tooltip-rows (str (ui/date-long d) " · "
                                                          (usd (reduce + (map #(get cost [d (:id %)] 0) shown))))
                                                     rows)))})
                        {:xAxis (merge (:xAxis (c/base {:x-type "category" :x-fmt ui/date-short}))
                                       {:data dates})
                         :series (c/stacked 4
                                            (for [task shown]
                                              (c/bars (t (:label task)) (:color task)
                                                      (mapv #(get cost [% (:id task)]) dates)
                                                      :stack "cost" :barMaxWidth 28
                                                      :itemStyle {:color (c/css-var (:color task))})))}))})
      :footer [:div.sub (t "Days are in local time.")]})))

(defn- token-columns []
  [{:label (t "Input") :align :r :value #(ui/thousands (:input_tokens %))}
   {:label (t "Cache write") :align :r :value #(ui/thousands (+ (:cache_write_5m % 0) (:cache_write_1h % 0)))}
   {:label (t "Cache read") :align :r :value #(ui/thousands (:cache_read %))}
   {:label (t "Output") :align :r :value #(ui/thousands (:output_tokens %))}])

(defn- by-task-card [{:keys [by_task totals]}]
  (ui/card {}
           [:div.lab (t "By task")]
           (ui/table (concat
                      [{:label (t "Task") :render #(let [task (task-by-id (:key %))]
                                                     [:span.cell-dot (dot (:color task "--fam-other"))
                                                      (task-label (:key %))])}
                       {:label (t "Requests") :align :r :value :requests}]
                      (token-columns)
                      [{:label (t "Cost") :align :r :value #(usd (:cost_usd %))}
                       {:label (t "Per request") :align :r :value #(usd (/ (:cost_usd %) (max 1 (:requests %))))}
                       {:label (t "Share") :align :r :value #(pct (:cost_usd %) (:cost_usd totals 0))}])
                     by_task {:key-fn :key})))

(defn- by-athlete-card
  "Several athletes on one server share the Claude key: what each one's
  requests cost in this range (from their own databases)."
  [{:keys [by_athlete usd_chf]}]
  (let [total (reduce + 0 (keep :cost_usd by_athlete))]
    (ui/card {}
             [:div.lab (t "By athlete")]
             (ui/table [{:label (t "Athlete")
                         :render #(list (or (:label %) (:name %) (t "Main athlete"))
                                        (when (:current %) [:span.muted (str " · " (t "this dashboard"))]))}
                        {:label (t "Requests") :align :r :value #(when-not (:error %) (:requests %))}
                        {:label (t "Input") :align :r :value #(tokens (:input_tokens %))}
                        {:label (t "Output") :align :r :value #(tokens (:output_tokens %))}
                        {:label (t "Cost") :align :r
                         :render #(if (:error %)
                                    [:span.error {:title (:error %)} (t "Couldn't be read")]
                                    (usd (:cost_usd %)))}
                        {:label (t "Share") :align :r :value #(when-not (:error %) (pct (:cost_usd %) total))}
                        {:label (t "This month") :align :r :value #(usd (:this_month %) 2)}]
                       by_athlete {:key-fn #(or (:name %) "")})
             [:div.sub (t "All athletes together: {0} ({1}) in this range. They share one Claude key, so the Claude Console bills this total."
                          (usd total 2) (chf total usd_chf))])))

(defn- by-model-card [{:keys [by_model]}]
  (ui/card {}
           [:div.lab (t "By model")]
           (ui/table (concat
                      [{:label (t "Model") :value :key}
                       {:label (t "Requests") :align :r :value :requests}]
                      (token-columns)
                      [{:label (t "Cost") :align :r :value #(usd (:cost_usd %))}])
                     by_model {:key-fn :key})))

(defn- status-cell [{:keys [status error stop_reason]}]
  (case status
    "ok"      (ui/status-pill :ok (t "OK"))
    "refused" [:span {:title (str "stop_reason: " stop_reason)} (ui/status-pill :warn (t "Refused"))]
    [:span {:title error} (ui/status-pill :critical (t "Failed"))]))

(defn- model-cell
  "The model that answered; after a fallback, \"requested → fallback\"."
  [{:keys [model_requested model_used]}]
  (let [others (remove #{model_requested} (some-> model_used (.split ", ") js->clj))
        short  #(some-> % (.replace #"^claude-" ""))]
    (if (seq others)
      (str (short model_requested) " → " (short (first others)) " (" (t "fallback") ")")
      (short (or model_used model_requested)))))

(defn- input-breakdown [r]
  (t "{0} uncached · {1} cache write · {2} cache read"
     (ui/thousands (:input_tokens r 0)) (ui/thousands (+ (:cache_write_5m r 0) (:cache_write_1h r 0)))
     (ui/thousands (:cache_read r 0))))

(defn- requests-card [{:keys [requests]} show-all?]
  (let [rows (if show-all? requests (take 25 requests))]
    (ui/card {}
             (ui/label-row {:label (t "Requests")
                            :right (when (> (count requests) 25)
                                     [:button.pill {:on {:click [[:set-ui :costs-all (not show-all?)]]}}
                                      (if show-all? (t "Show fewer") (t "Show all {0}" (count requests)))])})
             (ui/table (concat
                        [{:label (t "Time") :value #(local-time (:at %)) :title :ref}
                         {:label (t "Task") :render #(let [task (task-by-id (:task %))]
                                                       [:span.cell-dot (dot (:color task "--fam-other"))
                                                        (task-label (:task %))])}
                         {:label (t "Model") :value model-cell :title #(or (:model_used %) (:model_requested %))}
                         {:label (t "Effort") :value #(some-> (:effort %) t)}
                         {:label (t "Input") :align :r :value #(ui/thousands (input-total %)) :title input-breakdown}
                         {:label (t "Output") :align :r :value #(ui/thousands (:output_tokens %))}
                         {:label (t "Time taken") :align :r
                          :value #(some-> (:duration_ms %) (/ 1000) (.toFixed 1) (str " s"))}
                         {:label (t "Cost") :align :r
                          :value #(str (or (usd (:cost_usd %)) "—") (when (= 1 (:batch %)) (str " · " (t "batch"))))
                          :title #(when (= 1 (:batch %)) (t "Half-price batch request"))}
                         {:label (t "Status") :render status-cell}])
                       rows {:key-fn :id})
             [:div.sub (t "Input includes cached tokens; hover a number for the split.")]
             (when (some #(= "imported" (:source %)) rows)
               [:div.sub (t "Requests from before the cost log existed were imported from the stored plans; their effort is assumed high and their time taken is unknown.")]))))

(defn- prices-card [{:keys [prices by_model usd_chf imported_until unpriced]}]
  (let [used (set (map :key by_model))
        rows (for [[k p] (sort-by key prices) :let [m (name k)]
                   :when (or (used m)
                             (#{"claude-fable-5-1" "claude-opus-5-5" "claude-sonnet-5-5" "claude-haiku-5-5"} m))]
               (assoc p :model m))]
    (ui/card {}
             [:div.lab (t "How costs are worked out")]
             [:div.sub (t "Each request's token counts, as the API reports them, times that model's list price. A request that fell back to another model is priced per model. Anthropic bills in USD; CHF uses a fixed rate of {0}. The Claude Console's billing page is the final word (taxes, credits)." usd_chf)]
             (ui/table [{:label (t "Model ($ per million tokens)") :value :model}
                        {:label (t "Input") :align :r :value #(usd (:input %) 2)}
                        {:label (t "Cache write 5 min") :align :r :value #(usd (:cache_write_5m %) 2)}
                        {:label (t "Cache write 1 h") :align :r :value #(usd (:cache_write_1h %) 2)}
                        {:label (t "Cache read") :align :r :value #(usd (:cache_read %) 2)}
                        {:label (t "Output") :align :r :value #(usd (:output %) 2)}]
                       rows {:key-fn :model})
             (when imported_until
               [:div.sub (t "Logged from {0} on. Before that only plans and adjustments kept their usage; athlete chat and summary requests from then aren't included." (local-time imported_until))])
             (when (pos? unpriced)
               [:div.error (t "{0} request(s) used a model without a known price and show no cost." unpriced)]))))

(defn- short-model [m] (some-> m (.replace #"^claude-" "")))

(def effort-output
  "Rough output-token factor per effort level, relative to high. Effort mostly
  changes thinking, which is billed as output. Anthropic's published runs show
  low at roughly a third to a half less cost per task than high; the xhigh and
  max factors are not published and are guesses. Measured data replaces these."
  {"low" 0.5 "medium" 0.75 "high" 1.0 "xhigh" 1.4 "max" 2.0})

(defn output-estimate
  "Average output tokens per request for a task at `effort`, from that task's
  successful requests `hist` (rows of :by_setting). Measured at that effort
  when there is history at it (any model); otherwise scaled from the other
  efforts with `effort-output`. -> {:tokens :source :from} or nil."
  [hist effort]
  (let [wavg (fn [rows f] (let [n (reduce + (map :requests rows))]
                            (when (pos? n) (/ (reduce + (map #(* (:requests %) (f %)) rows)) n))))
        same (filter #(= effort (:effort %)) hist)]
    (cond
      (seq same) {:tokens (wavg same :avg_output_tokens) :source :measured}
      (seq hist) {:tokens (* (effort-output effort 1.0)
                             (wavg hist #(/ (:avg_output_tokens %) (effort-output (:effort %) 1.0))))
                  :source :scaled
                  :from (vec (distinct (map :effort hist)))})))

(defn- est-cost
  "USD for the average tokens per request `avg` at `model`'s prices."
  [prices model avg]
  (when-let [p (get prices (keyword model))]
    (* 1e-6 (+ (* (:input_tokens avg 0) (:input p)) (* (:output_tokens avg 0) (:output p))
               (* (:cache_read avg 0) (:cache_read p))
               (* (:cache_write_5m avg 0) (:cache_write_5m p)) (* (:cache_write_1h avg 0) (:cache_write_1h p))))))

(def limits-url "/api/claude/limits")

(defn- limits-card
  "Each athlete's daily and monthly Claude limit: the main athlete edits them all, others see their own."
  [{:keys [can_edit athletes]} edits]
  (let [several (> (count athletes) 1)
        limit-cell
        (fn [r field k label]
          (if can_edit
            [:input.note {:type "number" :min "0" :step "0.5" :inputmode "decimal"
                          :style {:width "7em"} :placeholder (t "No limit") :aria-label label
                          :value (or (get-in edits [(or (:name r) "") field]) (some-> (k r) str) "")
                          :on {:input [[:set-in-ui :claude-limits (or (:name r) "") field :event/target-value]]}}]
            (or (usd (k r) 2) (t "No limit"))))]
    (ui/card {}
             [:div.lab (t "Spending limits")]
             (ui/table (filterv some?
                                [(when several
                                   {:label (t "Athlete")
                                    :render #(list (or (:label %) (:name %) (t "Main athlete"))
                                                   (when (:current %) [:span.muted (str " · " (t "this dashboard"))]))})
                                 {:label (t "Spent today") :align :r :value #(usd (:spent_today %) 2)}
                                 {:label (t "Daily limit") :align :r
                                  :render #(limit-cell % :daily :daily_usd (t "Daily limit"))}
                                 {:label (t "Spent this month") :align :r :value #(usd (:spent_month %) 2)}
                                 {:label (t "Monthly limit") :align :r
                                  :render #(limit-cell % :monthly :monthly_usd (t "Monthly limit"))}
                                 {:label "" :render #(cond (:error %) [:span.error {:title (:error %)}
                                                                       (t "Couldn't be read")]
                                                           (:hit %)   [:span.error (t "Reached")])}])
                       athletes {:key-fn #(or (:name %) "")})
             [:div.sub (t "Each athlete's own Claude spending, in US dollars as Anthropic bills it. Once a limit is reached, that athlete's new Claude requests stop until the next day or month, including automatic planning. Leave a field empty for no limit.")]
             (if can_edit
               [:div.connect-row [:button.btn-ink {:on {:click [[:claude-limits]]}} (t "Save limits")]]
               [:div.sub (t "Only the main athlete can change these limits.")]))))

(defn- settings-card
  "Choose the model and effort per task, with what each combination has cost so far."
  [{:keys [tasks models efforts defaults deliveries scopes]} {:keys [by_task by_setting prices pending_batches]}]
  (let [task-avg (into {} (for [row by_task :let [n (max 1 (:requests row))]]
                            [(:key row) (update-vals (select-keys row [:input_tokens :output_tokens :cache_read
                                                                       :cache_write_5m :cache_write_1h])
                                                     #(/ % n))]))]
    (ui/card {}
             (ui/label-row {:label (t "Model and effort per task")
                            :right [:button.pill {:on {:click [[:api "DELETE" settings-url nil]]}}
                                    (t "Reset to recommended")]})
             [:div.sub (t "Bigger models and higher effort plan with more care and cost more; effort mostly changes how long Claude thinks, so it shows in the output tokens. Changes apply to the next request.")]
             (when (pos? (:n pending_batches 0))
               [:div.sub
                (t "{0} request(s) waiting in the half-price batch since {1}. The dashboard collects them every minute."
                   (:n pending_batches) (local-time (:since pending_batches)))])
             [:div.table-wrap.settings-wrap
              [:table.settings
               [:thead [:tr [:th (t "Task")] [:th (t "Model")] [:th (t "Effort")]
                        [:th
                         {:title (t "Batch: the Message Batches API, half price, answers usually within an hour (at most 24 h).")} (t "Delivery")]
                        [:th.r {:title (t "This task's average tokens per request so far, priced for the chosen model. Effort and model change how many tokens are used, so this is a guide.")}
                         (t "Est. per request")]
                        [:th (t "Cost so far per request")]]]
               [:tbody
                (for [{:keys [id label about why model effort delivery scope]} tasks
                      :let [task (task-by-id id)
                            hist (filter #(= id (:task %)) by_setting)
                            avg  (task-avg id)]]
                  [:tr {:replicant/key id}
                   [:td [:div [:span.cell-dot (dot (:color task "--fam-other")) label]]
                    [:div.sub about]
                    [:div.sub.muted
                     (str (t "Recommended: {0} · {1}." (short-model (get-in defaults [(keyword id) :model]))
                             (t (get-in defaults [(keyword id) :effort])))
                          " " why)]]
                   [:td {:data-label (t "Model")} [:select {:aria-label (str label " · " (t "Model"))
                                  :on {:change [[:ai-setting id :model :event/target-value]]}}
                         (for [m models]
                           [:option {:replicant/key (:id m) :value (:id m) :selected (= model (:id m))} (:label m)])]]
                   [:td {:data-label (t "Effort")} [:select {:aria-label (str label " · " (t "Effort"))
                                  :on {:change [[:ai-setting id :effort :event/target-value]]}}
                         (for [e efforts]
                           [:option {:replicant/key e :value e :selected (= effort e)} (t e)])]]
                   [:td {:data-label (t "Delivery")} (cond
                          delivery
                          [:div
                           [:select {:aria-label (str label " · " (t "Delivery"))
                                     :on {:change [[:ai-setting id :delivery :event/target-value]]}}
                            (for [d deliveries]
                              [:option {:replicant/key (:id d) :value (:id d) :selected (= delivery (:id d))}
                               (:label d)])]
                           [:div.sub (:about (some #(when (= delivery (:id %)) %) deliveries))]]
                          ;; Translations: which languages Claude's texts are kept in
                          scope
                          [:div
                           [:select {:aria-label (str label " · " (t "Languages"))
                                     :on {:change [[:ai-setting id :scope :event/target-value]]}}
                            (for [d scopes]
                              [:option {:replicant/key (:id d) :value (:id d) :selected (= scope (:id d))} (:label d)])]
                           [:div.sub (:about (some #(when (= scope (:id %)) %) scopes))]]
                          :else
                          [:div.sub (t "Always instant: you're waiting for the answer.")])]
                   [:td.r {:data-label (t "Est. per request")}
                    (let [{:keys [tokens source from]} (output-estimate hist effort)
                                cost (when (and avg tokens) (est-cost prices model (assoc avg :output_tokens tokens)))]
                            (if cost
                              [:div {:title (str (t "Input: this task's average so far.") " "
                                                 (if (= source :measured)
                                                   (t "Output: measured at {0} effort." (t effort))
                                                   (t "Output: scaled from {0} effort with rough factors (low ×0.5, medium ×0.75, high ×1, xhigh ×1.4, max ×2)."
                                                      (str/join ", " (map t from))))
                                                 " " (t "Priced for {0}." model))}
                               (str (when (= source :scaled) "≈ ") (usd cost))
                               [:div.sub (if (= source :measured)
                                           (t "measured at {0}" (t effort))
                                           (t "scaled from {0}" (str/join ", " (map t from))))]
                               (when (#{"auto" "batch"} delivery)
                                 [:div.sub (t "batch: {0}" (usd (* 0.5 cost)))])]
                              [:span.muted (t "no history")]))]
                   [:td {:data-label (t "Cost so far per request")} (if (seq hist)
                          [:div.pills
                           (for [{m :model e :effort b :batch :keys [requests avg_cost_usd]} hist]
                             [:span.pill {:replicant/key (str m e b)
                                          :class (when (and (= m model) (= e effort)) "current")
                                          :title (str (t "{0} request(s) with {1} at {2} effort" requests m (t e))
                                                      (when b (str ", " (t "half-price batch"))))}
                              (str (short-model m) " · " (t e) (when b (str " · " (t "batch"))) " · " (usd avg_cost_usd)
                                   " (" requests ")")])]
                          [:span.muted "—"])]])]]])))

(defn view
  "The model settings and the cost overview; shown in Settings (Claude & API
  costs), so it has no page title of its own."
  [{:keys [data errors route flash] ui-state :ui} u]
  (let [d     (get data u)
        st    (get data settings-url)
        range (get-in route [:params :range] "all")]
    [:div {:style {:display "contents"}}
     [:div.section-head
      [:h2.section-title (t "API costs")]
      (ui/seg range ranges [:set-param :range])]
     (when flash [:div.error flash])
     (some (fn [x] (when-let [e (get errors x)] [:div.error (t "Could not load: {0}" e)])) [u settings-url])
     (when (:limits d) [:div {:replicant/key "limits"} (limits-card (:limits d) (:claude-limits ui-state))])
     (when (and st d) [:div {:replicant/key "settings"} (settings-card st d)])
     (when d
       (if (zero? (get-in d [:totals :requests] 0))
         (ui/card {} [:div.empty (if (pos? (get-in d [:all_time :requests] 0))
                                   (t "No Claude requests in this range.")
                                   (t "No Claude requests yet. Costs appear here after the first plan or athlete chat."))])
         (list
          (kpis d)
          (when (seq (:by_athlete d)) [:div {:replicant/key "athletes"} (by-athlete-card d)])
          [:div {:replicant/key "daily"} (daily-chart (:daily d))]
          [:div {:replicant/key "task"} (by-task-card d)]
          [:div {:replicant/key "model"} (by-model-card d)]
          [:div {:replicant/key "reqs"} (requests-card d (:costs-all ui-state))]
          [:div {:replicant/key "prices"} (prices-card d)])))]))
