(ns coach.views.load
  (:require [coach.charts :as c]
            [coach.ui :as ui]))

(def ranges [["90" "90 days"] ["180" "180 days"] ["365" "1 year"] ["730" "2 years"] ["all" "All"]])

(def volume-metrics
  {"hours" {:label "Hours" :key :duration_s :scale (/ 1 3600) :fmt #(ui/fixed % 1)}
   "km"    {:label "Distance (km)" :key :distance_m :scale (/ 1 1000) :fmt #(str (js/Math.round %))}
   "tss"   {:label "TSS" :key :tss :scale 1 :fmt #(str (js/Math.round %))}})

(defn- weekly-by-family
  "week_start -> family id -> summed metric."
  [rows k scale]
  (reduce (fn [m r]
            (update-in m [(:week_start r) (:id (ui/family (:sport r)))]
                       (fnil + 0) (* scale (or (k r) 0))))
          (sorted-map) rows))

(defn- pmc-tooltip [rows]
  (fn [params]
    (let [r (nth rows (.-dataIndex (first params)) nil)]
      (c/tooltip-rows (ui/date-long (:date r))
                      [{:label "fitness (CTL)" :value (:ctl r) :color (c/css-var "--series-1")}
                       {:label "fatigue (ATL)" :value (:atl r) :color (c/css-var "--series-2")}
                       {:label "form (TSB)" :value (:tsb r)}
                       {:label "TSS that day" :value (:tss r)}]))))

(defn view [{:keys [data errors route tables-open] ui-state :ui} load-url volume-url]
  (let [pmc    (get data load-url)
        vol    (get data volume-url)
        range  (get-in route [:params :range] "180")
        vm-key (get ui-state :volume-metric "hours")
        vm     (volume-metrics vm-key)
        latest (last pmc)
        weeks  (weekly-by-family vol (:key vm) (:scale vm))
        fams   (filter (fn [f] (some #(get % (:id f)) (vals weeks))) ui/families)]
    [:div
     [:div.page-head [:h1 "Training load"]]
     [:div.filters (ui/seg range ranges [:set-param :range])]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (str "Could not load: " e)])) [load-url volume-url])
     (when pmc
       [:div.stack
        [:div.grid.tiles
         (ui/tile "Fitness (CTL)" (:ctl latest) nil "42-day load")
         (ui/tile "Fatigue (ATL)" (:atl latest) nil "7-day load")
         (ui/tile "Form (TSB)" (:tsb latest) nil "fitness − fatigue, yesterday")
         (ui/tile "Ramp rate" (:ramp latest) nil "CTL change over 7 days")]
        (ui/chart-card
         {:id :pmc :title "Fitness and fatigue" :tables-open tables-open
          :legend-items [{:label "Fitness (CTL)" :color "--series-1"}
                         {:label "Fatigue (ATL)" :color "--series-2"}]
          :table-columns [{:label "Date" :value #(ui/date-short (:date %))}
                          {:label "TSS" :value :tss :align :r} {:label "CTL" :value :ctl :align :r}
                          {:label "ATL" :value :atl :align :r} {:label "TSB" :value :tsb :align :r}
                          {:label "Ramp" :value :ramp :align :r}]
          :table-rows pmc
          :chart (c/echart
                  {:key "pmc" :class "tall" :group "load"
                   :option-fn (fn []
                                (merge (c/base {:y-min 0 :tooltip-fmt (pmc-tooltip pmc)})
                                       {:series [(c/area-wash (c/line "Fitness (CTL)" "--series-1" (mapv (juxt :date :ctl) pmc)))
                                                 (c/line "Fatigue (ATL)" "--series-2" (mapv (juxt :date :atl) pmc))]}))})})
        (ui/chart-card
         {:id :tsb :title "Form (TSB)" :tables-open tables-open
          :table-columns [{:label "Date" :value #(ui/date-short (:date %))} {:label "TSB" :value :tsb :align :r}]
          :table-rows pmc
          :chart [:div
                  (c/echart
                   {:key "tsb" :class "short" :group "load"
                    :option-fn (fn []
                                 (merge (c/base {:tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(-> (c/line "Form (TSB)" "--series-1" (mapv (juxt :date :tsb) pmc))
                                                      c/area-wash
                                                      (assoc :markLine {:silent true :symbol "none" :label {:show false}
                                                                        :lineStyle {:color (c/css-var "--axis") :type "solid" :width 1}
                                                                        :data [{:yAxis 0}]}))]}))})
                  [:div.muted "Above 0: fresher than your usual load. Around −10 to −30: building fitness. Below −30: high strain."]]})
        (ui/chart-card
         {:id :tss :title "Daily training stress (TSS)" :tables-open tables-open
          :table-columns [{:label "Date" :value #(ui/date-short (:date %))} {:label "TSS" :value :tss :align :r}]
          :table-rows (filter #(pos? (:tss %)) pmc)
          :chart (c/echart
                  {:key "tss-bars" :class "short" :group "load"
                   :option-fn (fn []
                                (merge (c/base {:y-min 0 :tooltip-fmt (pmc-tooltip pmc)})
                                       {:series [(c/bars "TSS" "--series-1" (mapv (juxt :date :tss) pmc) :barMaxWidth 6)]}))})})
        (ui/chart-card
         {:id :volume :title (str "Weekly volume · " (:label vm)) :tables-open tables-open
          :legend-items (for [f fams] {:label (:label f) :color (:color f) :kind :rect})
          :table-columns (into [{:label "Week of" :value #(ui/date-short (first %))}]
                               (for [f fams] {:label (:label f) :value #(some-> (get (second %) (:id f)) ((:fmt vm))) :align :r}))
          :table-rows (seq weeks)
          :chart [:div
                  [:div.filters {:style {:margin "0 0 4px"}}
                   (ui/seg vm-key (for [[k v] volume-metrics] [k (:label v)]) [:set-ui :volume-metric])]
                  (c/echart
                   {:key "volume" :class "tall"
                    :option-fn
                    (fn []
                      (merge (c/base {:y-min 0
                                      :tooltip-fmt
                                      (fn [params]
                                        (let [wk (first (js->clj (.-value (first params))))
                                              row (get weeks wk)]
                                          (c/tooltip-rows (str "Week of " (ui/date-short wk))
                                                          (for [f (reverse fams) :let [v (get row (:id f))] :when (and v (pos? v))]
                                                            {:label (:label f) :value ((:fmt vm) v) :color (c/css-var (:color f))}))))})
                             {:series (for [f fams]
                                        (c/bars (:label f) (:color f)
                                                (vec (for [[wk m] weeks] [wk (get m (:id f))]))
                                                :stack "vol"
                                                :itemStyle {:color (c/css-var (:color f))
                                                            :borderColor (c/css-var "--surface-1") :borderWidth 1}))}))})]})])]))
