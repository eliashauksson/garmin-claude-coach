(ns coach.views.costs
  "API costs: what the Claude requests cost, in total and per task, model and
  request, like the usage and cost pages of the Claude platform."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.ui :as ui]))

(def ranges [["30" "30 days"] ["90" "90 days"] ["365" "1 year"] ["all" "All"]])

(def settings-url "/api/ai-settings")

(defn url [route]
  (let [r (get-in route [:params :range] "all")]
    (str "/api/costs" (when (not= r "all") (str "?from=" (ui/days-ago (js/parseInt r)))))))

;; Fixed order, so a task keeps its colour whatever the range shows.
(def tasks
  [{:id "week_plan"       :label "Week plans"       :color "--task-1"}
   {:id "season_plan"     :label "Season plans"     :color "--task-2"}
   {:id "adjust"          :label "Plan adjustments" :color "--task-3"}
   {:id "athlete_chat"    :label "Athlete chat"     :color "--task-4"}
   {:id "athlete_summary" :label "Athlete summary"  :color "--task-5"}
   ;; neutral grey: not one of the five categorical slots
   {:id "test"            :label "Test calls"       :color "--fam-other"}])

(def task-by-id (into {} (map (juxt :id identity) tasks)))
(defn- task-label [id] (:label (task-by-id id) id))

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

(defn- n-requests [n] (str n (if (= 1 n) " request" " requests")))

(defn- pct [a b] (if (and (ui/num? a) (pos? b)) (str (js/Math.round (* 100 (/ a b))) "%") "—"))

(defn- input-total [r] (+ (:input_tokens r 0) (:cache_read r 0) (:cache_write_5m r 0) (:cache_write_1h r 0)))

(defn- local-time [iso]
  (when iso
    (let [d (js/Date. iso)
          this-year? (= (.getFullYear d) (.getFullYear (js/Date.)))]
      (str (.toLocaleDateString d "en-GB" (if this-year?
                                            #js {:day "numeric" :month "short"}
                                            #js {:day "numeric" :month "short" :year "numeric"})) ", "
           (.toLocaleTimeString d "en-GB" #js {:hour "2-digit" :minute "2-digit"})))))

(defn- dot [color] [:span.dot {:style {:background (str "var(" color ")")}}])

;; ---------------------------------------------------------------------------
;; Pieces

(defn- kpis [{:keys [totals this_month usd_chf by_task]}]
  (let [in   (input-total totals)
        busy (first by_task)]
    [:div.row {:replicant/key "kpi"}
     (ui/tile {:label "Spent in this range" :tint "blue" :size "md"
               :value (usd (:cost_usd totals 0) 2)
               :sub (str (chf (:cost_usd totals 0) usd_chf) " · " (n-requests (:requests totals 0))
                         (when (pos? (:errors totals 0)) (str " · " (:errors totals) " failed")))})
     (ui/tile {:label "This month" :size "md"
               :value (usd (:cost_usd this_month 0) 2)
               :sub (str (chf (:cost_usd this_month 0) usd_chf) " · " (n-requests (:requests this_month 0)))})
     (ui/tile {:label "Tokens" :size "md"
               :value (tokens (+ in (:output_tokens totals 0)))
               :sub (str (tokens in) " in · " (tokens (:output_tokens totals 0)) " out")})
     (ui/tile {:label "Most spent on" :size "md"
               :value (when busy (pct (:cost_usd busy) (:cost_usd totals 0)))
               :sub (if busy (str (task-label (:key busy)) " · " (usd (:cost_usd busy))) "No requests yet")})]))

(defn- daily-chart [daily]
  (let [dates (vec (distinct (map :date daily)))
        cost  (into {} (for [{:keys [date task cost_usd]} daily] [[date task] cost_usd]))
        shown (filter (fn [t] (some #(= (:id t) (:task %)) daily)) tasks)]
    (ui/chart-card
     {:title "Daily cost"
      :legend-items (for [t shown] {:label (:label t) :color (:color t)})
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
                                         rows (for [t (reverse shown) :let [v (get cost [d (:id t)])] :when v]
                                                {:label (:label t) :value (usd v) :color (c/css-var (:color t))})]
                                     (c/tooltip-rows (str (ui/date-long d) " · " (usd (reduce + (map #(get cost [d (:id %)] 0) shown))))
                                                     rows)))})
                        {:xAxis (merge (:xAxis (c/base {:x-type "category" :x-fmt ui/date-short}))
                                       {:data dates})
                         :series (for [t shown]
                                   (c/bars (:label t) (:color t)
                                           (mapv #(get cost [% (:id t)]) dates)
                                           :stack "cost" :barMaxWidth 28
                                           ;; 2px surface gap between stacked segments
                                           :itemStyle {:color (c/css-var (:color t)) :borderRadius 4
                                                       :borderColor (c/css-var "--surface") :borderWidth 2}))}))})
      :footer [:div.sub "Days are in local time."]})))

(def token-columns
  [{:label "Input" :align :r :value #(ui/thousands (:input_tokens %))}
   {:label "Cache write" :align :r :value #(ui/thousands (+ (:cache_write_5m % 0) (:cache_write_1h % 0)))}
   {:label "Cache read" :align :r :value #(ui/thousands (:cache_read %))}
   {:label "Output" :align :r :value #(ui/thousands (:output_tokens %))}])

(defn- by-task-card [{:keys [by_task totals]}]
  (ui/card {}
           [:div.lab "By task"]
           (ui/table (concat
                      [{:label "Task" :render #(let [t (task-by-id (:key %))]
                                                 [:span.cell-dot (dot (:color t "--fam-other")) (task-label (:key %))])}
                       {:label "Requests" :align :r :value :requests}]
                      token-columns
                      [{:label "Cost" :align :r :value #(usd (:cost_usd %))}
                       {:label "Per request" :align :r :value #(usd (/ (:cost_usd %) (max 1 (:requests %))))}
                       {:label "Share" :align :r :value #(pct (:cost_usd %) (:cost_usd totals 0))}])
                     by_task {:key-fn :key})))

(defn- by-model-card [{:keys [by_model]}]
  (ui/card {}
           [:div.lab "By model"]
           (ui/table (concat
                      [{:label "Model" :value :key}
                       {:label "Requests" :align :r :value :requests}]
                      token-columns
                      [{:label "Cost" :align :r :value #(usd (:cost_usd %))}])
                     by_model {:key-fn :key})))

(defn- status-cell [{:keys [status error stop_reason]}]
  (case status
    "ok"      (ui/status-pill :ok "OK")
    "refused" [:span {:title (str "stop_reason: " stop_reason)} (ui/status-pill :warn "Refused")]
    [:span {:title error} (ui/status-pill :critical "Failed")]))

(defn- model-cell
  "The model that answered; after a fallback, \"requested → fallback\"."
  [{:keys [model_requested model_used]}]
  (let [others (remove #{model_requested} (some-> model_used (.split ", ") js->clj))
        short  #(some-> % (.replace #"^claude-" ""))]
    (if (seq others)
      (str (short model_requested) " → " (short (first others)) " (fallback)")
      (short (or model_used model_requested)))))

(defn- input-breakdown [r]
  (str (ui/thousands (:input_tokens r 0)) " uncached · "
       (ui/thousands (+ (:cache_write_5m r 0) (:cache_write_1h r 0))) " cache write · "
       (ui/thousands (:cache_read r 0)) " cache read"))

(defn- requests-card [{:keys [requests]} show-all?]
  (let [rows (if show-all? requests (take 25 requests))]
    (ui/card {}
             (ui/label-row {:label "Requests"
                            :right (when (> (count requests) 25)
                                     [:button.pill {:on {:click [[:set-ui :costs-all (not show-all?)]]}}
                                      (if show-all? "Show fewer" (str "Show all " (count requests)))])})
             (ui/table (concat
                        [{:label "Time" :value #(local-time (:at %)) :title :ref}
                         {:label "Task" :render #(let [t (task-by-id (:task %))]
                                                   [:span.cell-dot (dot (:color t "--fam-other")) (task-label (:task %))])}
                         {:label "Model" :value model-cell :title #(or (:model_used %) (:model_requested %))}
                         {:label "Effort" :value :effort}
                         {:label "Input" :align :r :value #(ui/thousands (input-total %)) :title input-breakdown}
                         {:label "Output" :align :r :value #(ui/thousands (:output_tokens %))}
                         {:label "Time taken" :align :r :value #(some-> (:duration_ms %) (/ 1000) (.toFixed 1) (str " s"))}
                         {:label "Cost" :align :r :value #(str (or (usd (:cost_usd %)) "—") (when (= 1 (:batch %)) " · batch"))
                          :title #(when (= 1 (:batch %)) "Half-price batch request")}
                         {:label "Status" :render status-cell}])
                       rows {:key-fn :id})
             [:div.sub "Input includes cached tokens; hover a number for the split."]
             (when (some #(= "imported" (:source %)) rows)
               [:div.sub "Requests from before the cost log existed were imported from the stored plans; their effort is assumed high and their time taken is unknown."]))))

(defn- prices-card [{:keys [prices by_model usd_chf imported_until unpriced]}]
  (let [used (set (map :key by_model))
        rows (for [[k p] (sort-by key prices) :let [m (name k)]
                   :when (or (used m) (#{"claude-fable-5-1" "claude-opus-5-5" "claude-sonnet-5-5" "claude-haiku-5-5"} m))]
               (assoc p :model m))]
    (ui/card {}
             [:div.lab "How costs are worked out"]
             [:div.sub "Each request's token counts, as the API reports them, times that model's list price. "
              "A request that fell back to another model is priced per model. "
              "Anthropic bills in USD; CHF uses a fixed rate of " usd_chf ". "
              "The Claude Console's billing page is the final word (taxes, credits)."]
             (ui/table [{:label "Model ($ per million tokens)" :value :model}
                        {:label "Input" :align :r :value #(usd (:input %) 2)}
                        {:label "Cache write 5 min" :align :r :value #(usd (:cache_write_5m %) 2)}
                        {:label "Cache write 1 h" :align :r :value #(usd (:cache_write_1h %) 2)}
                        {:label "Cache read" :align :r :value #(usd (:cache_read %) 2)}
                        {:label "Output" :align :r :value #(usd (:output %) 2)}]
                       rows {:key-fn :model})
             (when imported_until
               [:div.sub (str "Logged from " (local-time imported_until)
                              " on. Before that only plans and adjustments kept their usage; athlete chat and summary requests from then aren't included.")])
             (when (pos? unpriced)
               [:div.error (str unpriced " request(s) used a model without a known price and show no cost.")]))))

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

(defn- settings-card
  "Choose the model and effort per task, with what each combination has cost so far."
  [{:keys [tasks models efforts defaults deliveries]} {:keys [by_task by_setting prices pending_batches]}]
  (let [task-avg (into {} (for [t by_task :let [n (max 1 (:requests t))]]
                            [(:key t) (update-vals (select-keys t [:input_tokens :output_tokens :cache_read
                                                                   :cache_write_5m :cache_write_1h])
                                                   #(/ % n))]))]
    (ui/card {}
             (ui/label-row {:label "Model and effort per task"
                            :right [:button.pill {:on {:click [[:api "DELETE" settings-url nil]]}}
                                    "Reset to recommended"]})
             [:div.sub "Bigger models and higher effort plan with more care and cost more; effort mostly changes how long Claude thinks, so it shows in the output tokens. Changes apply to the next request."]
             (when (pos? (:n pending_batches 0))
               [:div.sub (str (:n pending_batches) " request(s) waiting in the half-price batch since "
                              (local-time (:since pending_batches)) ". The dashboard collects them every minute.")])
             [:div.table-wrap
              [:table.settings
               [:thead [:tr [:th "Task"] [:th "Model"] [:th "Effort"]
                        [:th {:title "Batch: the Message Batches API, half price, answers usually within an hour (at most 24 h)."} "Delivery"]
                        [:th.r {:title "This task's average tokens per request so far, priced for the chosen model. Effort and model change how many tokens are used, so this is a guide."}
                         "Est. per request"]
                        [:th "Cost so far per request"]]]
               [:tbody
                (for [{:keys [id label about why model effort delivery]} tasks
                      :let [t    (task-by-id id)
                            hist (filter #(= id (:task %)) by_setting)
                            avg  (task-avg id)]]
                  [:tr {:replicant/key id}
                   [:td [:div [:span.cell-dot (dot (:color t "--fam-other")) label]]
                    [:div.sub about]
                    [:div.sub.muted (str "Recommended: " (short-model (get-in defaults [(keyword id) :model]))
                                         " · " (get-in defaults [(keyword id) :effort]) ". " why)]]
                   [:td [:select {:aria-label (str label " model")
                                  :on {:change [[:ai-setting id :model :event/target-value]]}}
                         (for [m models]
                           [:option {:replicant/key (:id m) :value (:id m) :selected (= model (:id m))} (:label m)])]]
                   [:td [:select {:aria-label (str label " effort")
                                  :on {:change [[:ai-setting id :effort :event/target-value]]}}
                         (for [e efforts]
                           [:option {:replicant/key e :value e :selected (= effort e)} e])]]
                   [:td (if delivery
                          [:div
                           [:select {:aria-label (str label " delivery")
                                     :on {:change [[:ai-setting id :delivery :event/target-value]]}}
                            (for [d deliveries]
                              [:option {:replicant/key (:id d) :value (:id d) :selected (= delivery (:id d))} (:label d)])]
                           [:div.sub (:about (some #(when (= delivery (:id %)) %) deliveries))]]
                          [:div.sub "Always instant: you're waiting for the answer."])]
                   [:td.r (let [{:keys [tokens source from]} (output-estimate hist effort)
                                cost (when (and avg tokens) (est-cost prices model (assoc avg :output_tokens tokens)))]
                            (if cost
                              [:div {:title (str "Input: this task's average so far. Output: "
                                                 (if (= source :measured)
                                                   (str "measured at " effort " effort.")
                                                   (str "scaled from " (str/join ", " from)
                                                        " effort with rough factors (low ×0.5, medium ×0.75, high ×1, xhigh ×1.4, max ×2)."))
                                                 " Priced for " model ".")}
                               (str (when (= source :scaled) "≈ ") (usd cost))
                               [:div.sub (if (= source :measured)
                                           (str "measured at " effort)
                                           (str "scaled from " (str/join ", " from)))]
                               (when (#{"auto" "batch"} delivery)
                                 [:div.sub (str "batch: " (usd (* 0.5 cost)))])]
                              [:span.muted "no history"]))]
                   [:td (if (seq hist)
                          [:div.pills
                           (for [{m :model e :effort b :batch :keys [requests avg_cost_usd]} hist]
                             [:span.pill {:replicant/key (str m e b)
                                          :class (when (and (= m model) (= e effort)) "current")
                                          :title (str requests " request(s) with " m " at " e " effort" (when b ", half-price batch"))}
                              (str (short-model m) " · " e (when b " · batch") " · " (usd avg_cost_usd) " (" requests ")")])]
                          [:span.muted "—"])]])]]])))

(defn view [{:keys [data errors route flash] ui-state :ui} u]
  (let [d     (get data u)
        st    (get data settings-url)
        range (get-in route [:params :range] "all")]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:h1.title "API costs"]
      (ui/seg range ranges [:set-param :range])]
     (when flash [:div.error flash])
     (some (fn [x] (when-let [e (get errors x)] [:div.error (str "Could not load: " e)])) [u settings-url])
     (when (and st d) [:div {:replicant/key "settings"} (settings-card st d)])
     (when d
       (if (zero? (get-in d [:totals :requests] 0))
         (ui/card {} [:div.empty (if (pos? (get-in d [:all_time :requests] 0))
                                   "No Claude requests in this range."
                                   "No Claude requests yet. Costs appear here after the first plan or athlete chat.")])
         (list
          (kpis d)
          [:div {:replicant/key "daily"} (daily-chart (:daily d))]
          [:div {:replicant/key "task"} (by-task-card d)]
          [:div {:replicant/key "model"} (by-model-card d)]
          [:div {:replicant/key "reqs"} (requests-card d (:costs-all ui-state))]
          [:div {:replicant/key "prices"} (prices-card d)])))]))
