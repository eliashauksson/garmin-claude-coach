(ns coach.views.season
  "Season plan card on the Plan page: every week to race day with its phase
  and target TSS, drafted by Claude (coach.season-plan), and the TSS actually
  recorded so far."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.ui :as ui]))

(def url "/api/plan/season")

(def phase-colors
  {"Reset" "--fam-other" "Base" "--blue" "Build 1" "--purple-2" "Build 2" "--purple"
   "Specific" "--amber" "Taper" "--green" "Race week" "--red"})

(defn- day-month [iso]
  (let [d (ui/parse-date iso)] (str (.getDate d) " " (ui/months (.getMonth d)))))

(defn- add-days [iso n]
  (let [d (ui/parse-date iso)] (.setDate d (+ (.getDate d) n)) (.slice (.toISOString d) 0 10)))

(defn- phase-blocks
  "Consecutive weeks of the same phase, with their week numbers (1-based)."
  [weeks]
  (->> (map-indexed #(assoc %2 :n (inc %1)) weeks)
       (partition-by :phase)
       (map (fn [ws] {:phase (:phase (first ws)) :weeks ws}))))

(defn- chart-option [weeks current]
  (let [labels  (mapv #(day-month (:monday %)) weeks)
        past?   #(<= (compare (:monday %) current) 0)
        ink     (c/css-var "--ink")
        text-3  (c/css-var "--text-3")
        now-idx (first (keep-indexed #(when (= current (:monday %2)) %1) weeks))
        tip     (fn [^js ps]
                  (let [i (.-dataIndex (aget ps 0))
                        {:keys [monday phase tss actual_tss cutback focus]} (nth weeks i)]
                    (c/tooltip-rows
                     (str "Week " (inc i) " · " (day-month monday) " – " (day-month (add-days monday 6)))
                     [{:value (str tss) :label "TSS planned" :color (c/css-var (phase-colors phase))}
                      {:value (some-> actual_tss str) :label "TSS done" :color ink}
                      {:value phase :label (str focus (when cutback " · cutback"))}])))]
    (-> (c/base {:x-type "category" :y-min 0 :tooltip-fmt tip})
        (assoc-in [:xAxis :data] labels)
        (assoc-in [:grid :top] 26) ; room for the "This week" label
        (assoc :series
               [(c/bars "Planned" "--blue"
                        (vec (for [{:keys [phase tss cutback]} weeks]
                               {:value tss
                                :itemStyle {:color (c/css-var (phase-colors phase)) :borderRadius 6
                                            :opacity (if cutback 0.55 1)}}))
                        :barMaxWidth 28
                        :label {:show true :position "top" :color text-3 :fontSize 10 :fontWeight 600
                                :fontFamily c/font}
                        :markLine (when now-idx
                                    {:silent true :symbol "none"
                                     :data [{:xAxis (labels now-idx)}]
                                     :lineStyle {:color text-3 :type "dashed" :width 1}
                                     :label {:formatter "This week" :color text-3 :fontSize 11
                                             :fontFamily c/font}}))
                (c/line "Done" "--ink"
                        (vec (for [w weeks] (when (past? w) (or (:actual_tss w) 0))))
                        :showSymbol true :symbol "circle" :symbolSize 7 :z 3)]))))

(defn- phase-summary [weeks current]
  [:div.season-phases
   (for [{:keys [phase weeks]} (phase-blocks weeks)
         :let [f (first weeks) l (last weeks) tss (map :tss weeks)
               now? (some #(= current (:monday %)) weeks)]]
     [:div.season-phase {:replicant/key (str (:n f) phase) :class (when now? "now")}
      [:div.stat-label [:span.dot {:style {:background (str "var(" (phase-colors phase) ")")}}] phase
       (when now? [:span.muted " · now"])]
      [:div.stat-value (str (apply min tss) (when (not= (apply min tss) (apply max tss)) (str "–" (apply max tss))))
       [:small "TSS"]]
      [:div.sub (str (if (= f l) (str "Week " (:n f)) (str "Weeks " (:n f) "–" (:n l)))
                     " · " (day-month (:monday f)) " – " (day-month (add-days (:monday l) 6)))]])])

(defn- usage-line [{:keys [model usage]}]
  (when usage
    [:div.sub (str/join " · " (remove nil? [(str "Model " model)
                                            (when (:cost_chf usage) (str "cost ≈ CHF " (.toFixed (:cost_chf usage) 2)))]))]))

(defn card [{:keys [status error plan current_monday days_to_race]}]
  (let [running? (= "running" status)
        {:keys [summary weeks]} (:response plan)
        this-wk  (some #(when (= current_monday (:monday %)) %) weeks)
        peak     (some->> (seq (remove #(= "Race week" (:phase %)) weeks)) (apply max-key :tss))]
    (ui/card {}
             (ui/label-row {:icon :mountain :accent "--blue" :tint "--tint-blue" :soft? true :label "Season plan"})
             (when (seq weeks) [:div summary])
             (when (seq weeks)
               (ui/pills
                (ui/pill (str (count weeks) " weeks · " days_to_race " days to race day"))
                (when this-wk (ui/pill (phase-colors (:phase this-wk))
                                       (str "This week: " (:tss this-wk) " TSS target"
                                            (when (:actual_tss this-wk) (str " · " (:actual_tss this-wk) " done")))))
                (when peak (ui/pill (str "Peak " (:tss peak) " TSS · week of " (day-month (:monday peak)))))))
             (when (seq weeks) (ui/legend (for [p (distinct (map :phase weeks))] {:label p :color (phase-colors p)})))
             (when (seq weeks) (c/echart {:key "season" :class "h240" :option-fn #(chart-option weeks current_monday)}))
             (when (seq weeks) (phase-summary weeks current_monday))
             (cond
               running?          [:div.sub "The coach is drafting the season plan… this usually takes a minute or two."]
               (= "failed" status) [:div.error (str "Drafting failed: " error)]
               (empty? weeks)    [:div.empty "No season plan yet. The coach lays out every week to race day, with its phase and target TSS, from the training principles and athlete.md."])
             [:div.plan-footer
              [:button.btn-ink {:disabled running? :on {:click [[:api "POST" url nil]]}}
               (cond running? "Drafting…" (seq weeks) "Redraft season plan" :else "Draft season plan")]
              (when (seq weeks) (usage-line plan))])))
