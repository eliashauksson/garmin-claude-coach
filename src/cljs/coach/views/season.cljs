(ns coach.views.season
  "Season plan card on the Plan page: every week to race day with its phase
  and target TSS, drafted by Claude (coach.season-plan), and the TSS actually
  recorded so far."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.ui :as ui]))

(def url "/api/plan/season")

(def phase-colors
  {"Transition" "--fam-other" "Base" "--blue" "Specific" "--amber" "Taper" "--green" "Race week" "--red"})

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

(defn- when-text [iso]
  (let [d (js/Date. iso)]
    (str (.getDate d) " " (ui/months (.getMonth d)) " "
         (.padStart (str (.getHours d)) 2 "0") ":" (.padStart (str (.getMinutes d)) 2 "0"))))

(def reason-label {"redraft" "replaced by a redraft" "delete" "deleted" "restore" "swapped out by a restore"})

(defn- confirm-box
  "A red confirm banner: title, explanation, then Yes / Cancel."
  [title text yes-label yes-actions]
  [:div.banner.critical
   [:span.icon "!"]
   [:div
    [:div [:b title]]
    [:div.sub text]
    [:div.plan-footer
     [:button.btn-ink {:on {:click (conj yes-actions [:set-ui :season nil])}} yes-label]
     [:button.pill {:on {:click [[:set-ui :season nil]]}} "Cancel"]]]])

(defn- backups-block [backups]
  (when (seq backups)
    [:details.athlete-backups
     [:summary (str "Backups (" (count backups) ")")]
     (for [{:keys [id created_at archived_at archived_reason weeks peak_tss]} backups]
       [:div.plan-footer {:replicant/key id}
        [:span (str "Drafted " (when-text created_at) " · " weeks " weeks"
                    (when peak_tss (str " · peak " peak_tss " TSS"))
                    " · " (reason-label archived_reason archived_reason) " " (when-text archived_at))]
        [:button.pill {:on {:click [[:api "POST" (str url "/restore") {:id id}]]}} "Restore"]])
     [:div.sub "Restoring keeps the current plan as a backup. The newest 3 backups are kept."]]))

(defn card [{:keys [status error plan backups current_monday days_to_race]} ui-state]
  (let [running? (= "running" status)
        {:keys [summary weeks]} (:response plan)
        confirm  (:confirm ui-state)
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
               (empty? weeks)    [:div.empty "No season plan yet. Create one first: the coach lays out every week to race day, with its phase and target TSS, from the training principles and athlete.md. Then it plans the next two weeks from your training so far, and adds a week every Monday."]
               :else             [:div.sub "The coach keeps the next two weeks planned and adds a new week every Monday, from your training so far. You review each week before it enters the plan."])
             (case confirm
               :redraft (confirm-box "Redraft the season plan?"
                                     "Claude drafts a new plan from your training so far (a paid call, about a minute or two). The current plan is kept as a backup, so you can restore it."
                                     "Yes, redraft" [[:api "POST" url nil]])
               :delete  (confirm-box "Delete the season plan?"
                                     "All upcoming planned sessions that aren't done yet, and open week proposals, are removed too. The coach stops planning until there is a plan again. The plan is kept as a backup: restoring it brings it back, and the coach then plans the next two weeks again."
                                     "Yes, delete" [[:api "DELETE" url nil]])
               nil)
             (when-not confirm
               [:div.plan-footer
                (if (seq weeks)
                  [:button.btn-ink {:disabled running? :on {:click [[:set-in-ui :season :confirm :redraft]]}}
                   (if running? "Drafting…" "Redraft season plan")]
                  [:button.btn-ink {:disabled running? :on {:click [[:api "POST" url nil]]}}
                   (if running? "Drafting…" "Create season plan")])
                (when (seq weeks)
                  [:button.pill {:disabled running? :on {:click [[:set-in-ui :season :confirm :delete]]}} "Delete"])
                (when (seq weeks) (usage-line plan))])
             (backups-block backups))))
