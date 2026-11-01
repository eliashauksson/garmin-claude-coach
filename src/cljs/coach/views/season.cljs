(ns coach.views.season
  "Season plan card on the Plan page: every week to race day with its phase
  and target TSS, drafted by Claude (coach.season-plan), and the TSS actually
  recorded so far."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

(def url "/api/plan/season")

;; Phase names are data (the season plan stores them in English); shown with t.
(def phase-colors
  {(tk "Transition") "--fam-other" (tk "Base") "--blue" (tk "Specific") "--amber" (tk "Taper") "--green"
   (tk "Race week") "--red"})

(defn- day-month [iso] (ui/day-month (ui/parse-date iso)))

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
                     (t "Week {0} · {1} – {2}" (inc i) (day-month monday) (day-month (add-days monday 6)))
                     [{:value (str tss) :label (t "TSS planned") :color (c/css-var (phase-colors phase))}
                      {:value (some-> actual_tss str) :label (t "TSS done") :color ink}
                      {:value (t phase) :label (str focus (when cutback (str " · " (t "cutback"))))}])))]
    (-> (c/base {:x-type "category" :y-min 0 :tooltip-fmt tip})
        (assoc-in [:xAxis :data] labels)
        (assoc-in [:grid :top] 26) ; room for the "This week" label
        (assoc :series
               [(c/bars (t "Planned") "--blue"
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
                                     :label {:formatter (t "This week") :color text-3 :fontSize 11
                                             :fontFamily c/font}}))
                (c/line (t "Done") "--ink"
                        (vec (for [w weeks] (when (past? w) (or (:actual_tss w) 0))))
                        :showSymbol true :symbol "circle" :symbolSize 7 :z 3)]))))

(defn- phase-summary [weeks current]
  [:div.season-phases
   (for [{:keys [phase weeks]} (phase-blocks weeks)
         :let [f (first weeks) l (last weeks) tss (map :tss weeks)
               now? (some #(= current (:monday %)) weeks)]]
     [:div.season-phase {:replicant/key (str (:n f) phase) :class (when now? "now")}
      [:div.stat-label [:span.dot {:style {:background (str "var(" (phase-colors phase) ")")}}] (t phase)
       (when now? [:span.muted (str " · " (t "now"))])]
      [:div.stat-value (str (apply min tss) (when (not= (apply min tss) (apply max tss)) (str "–" (apply max tss))))
       [:small "TSS"]]
      [:div.sub (str (if (= f l) (t "Week {0}" (:n f)) (t "Weeks {0}–{1}" (:n f) (:n l)))
                     " · " (day-month (:monday f)) " – " (day-month (add-days (:monday l) 6)))]])])

(defn- usage-line [{:keys [model usage]}]
  (when usage
    [:div.sub (str/join " · " (remove nil? [(t "Model {0}" model)
                                            (when (:cost_chf usage) (t "cost ≈ CHF {0}" (.toFixed (:cost_chf usage) 2)))]))]))

(defn- when-text [iso]
  (let [d (js/Date. iso)]
    (str (ui/day-month d) " "
         (.padStart (str (.getHours d)) 2 "0") ":" (.padStart (str (.getMinutes d)) 2 "0"))))

(def reason-label {"redraft" (tk "replaced by a redraft") "delete" (tk "deleted") "restore" (tk "swapped out by a restore")})

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
     [:button.pill {:on {:click [[:set-ui :season nil]]}} (t "Cancel")]]]])

(defn- backups-block [backups]
  (when (seq backups)
    [:details.athlete-backups
     [:summary (t "Backups ({0})" (count backups))]
     (for [{:keys [id created_at archived_at archived_reason weeks peak_tss]} backups]
       [:div.plan-footer {:replicant/key id}
        [:span (str (t "Drafted {0}" (when-text created_at)) " · " (t "{0} weeks" weeks)
                    (when peak_tss (str " · " (t "peak {0} TSS" peak_tss)))
                    " · " (t (reason-label archived_reason archived_reason)) " " (when-text archived_at))]
        [:button.pill {:on {:click [[:api "POST" (str url "/restore") {:id id}]]}} (t "Restore")]])
     [:div.sub (t "Restoring keeps the current plan as a backup. The newest 3 backups are kept.")]]))

(defn card [{:keys [status error plan backups current_monday days_to_race delivery]} ui-state]
  (let [running? (= "running" status)
        {:keys [summary weeks]} (:response plan)
        confirm  (:confirm ui-state)
        this-wk  (some #(when (= current_monday (:monday %)) %) weeks)
        peak     (some->> (seq (remove #(= "Race week" (:phase %)) weeks)) (apply max-key :tss))]
    (ui/card {}
             (ui/label-row {:icon :mountain :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Season plan")})
             (when (seq weeks) [:div summary])
             (when (seq weeks)
               (ui/pills
                (ui/pill (t "{0} weeks · {1} days to race day" (count weeks) days_to_race))
                (when this-wk (ui/pill (phase-colors (:phase this-wk))
                                       (str (t "This week: {0} TSS target" (:tss this-wk))
                                            (when (:actual_tss this-wk) (str " · " (t "{0} done" (:actual_tss this-wk)))))))
                (when peak (ui/pill (t "Peak {0} TSS · week of {1}" (:tss peak) (day-month (:monday peak)))))))
             (when (seq weeks) (ui/legend (for [p (distinct (map :phase weeks))] {:label (t p) :color (phase-colors p)})))
             (when (seq weeks) (c/echart {:key "season" :class "h240" :option-fn #(chart-option weeks current_monday)}))
             (when (seq weeks) (phase-summary weeks current_monday))
             (cond
               running?          [:div.sub (if (= "batch" delivery)
                                             (t "Waiting for Claude in the half-price batch. Usually within an hour (at most 24 h); you can close the page meanwhile.")
                                             (t "The coach is drafting the season plan… this usually takes a minute or two."))]
               (= "failed" status) [:div.error (t "Drafting failed: {0}" error)]
               (empty? weeks)    [:div.empty (t "No season plan yet. Create one first: the coach lays out every week to race day, with its phase and target TSS, from the training principles and athlete.md. Then it plans the next two weeks from your training so far, and adds a week every Monday.")]
               :else             [:div.sub (t "The coach keeps the next two weeks planned and adds a new week every Monday, from your training so far. You review each week before it enters the plan.")])
             (case confirm
               :redraft (confirm-box (t "Redraft the season plan?")
                                     (t "Claude drafts a new plan from your training so far (a paid call, about a minute or two). The current plan is kept as a backup, so you can restore it.")
                                     (t "Yes, redraft") [[:api "POST" url nil]])
               :delete  (confirm-box (t "Delete the season plan?")
                                     (t "All upcoming planned sessions that aren't done yet, and open week proposals, are removed too. The coach stops planning until there is a plan again. The plan is kept as a backup: restoring it brings it back, and the coach then plans the next two weeks again.")
                                     (t "Yes, delete") [[:api "DELETE" url nil]])
               nil)
             (when-not confirm
               [:div.plan-footer
                (if (seq weeks)
                  [:button.btn-ink {:disabled running? :on {:click [[:set-in-ui :season :confirm :redraft]]}}
                   (if running? (t "Drafting…") (t "Redraft season plan"))]
                  [:button.btn-ink {:disabled running? :on {:click [[:api "POST" url nil]]}}
                   (if running? (t "Drafting…") (t "Create season plan"))])
                (when (seq weeks)
                  [:button.pill {:disabled running? :on {:click [[:set-in-ui :season :confirm :delete]]}} (t "Delete")])
                (when (seq weeks) (usage-line plan))])
             (backups-block backups))))
