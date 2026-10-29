(ns coach.views.load
  (:require [coach.charts :as c]
            [coach.ui :as ui]))

(def ranges [["90" "90 days"] ["180" "180 days"] ["365" "1 year"] ["730" "2 years"] ["all" "All"]])

(def volume-metrics
  {"hours" {:label "Hours" :key :duration_s :scale (/ 1 3600) :fmt #(ui/fixed % 1)}
   "km"    {:label "Distance" :key :distance_m :scale (/ 1 1000) :fmt #(str (js/Math.round %))}
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
                      [{:label "fitness (CTL)" :value (:ctl r) :color (c/css-var "--blue")}
                       {:label "fatigue (ATL)" :value (:atl r) :color (c/css-var "--red")}
                       {:label "form (TSB)" :value (:tsb r)}
                       {:label "TSS that day" :value (:tss r)}]))))

(defn- date-col [] {:label "Date" :value #(ui/date-short (:date %))})

(defn view [{:keys [data errors route tables-open] ui-state :ui} load-url volume-url]
  (let [pmc    (get data load-url)
        vol    (get data volume-url)
        range  (get-in route [:params :range] "180")
        vm-key (get ui-state :volume-metric "hours")
        vm     (volume-metrics vm-key)
        latest (last pmc)
        weeks  (weekly-by-family vol (:key vm) (:scale vm))
        fams   (filter (fn [f] (some #(get % (:id f)) (vals weeks))) ui/families)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:h1.title "Training load"]
      (ui/seg range ranges [:set-param :range])]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (str "Could not load: " e)])) [load-url volume-url])
     (when pmc
       (list
        [:div.row {:replicant/key "kpi"}
         (ui/tile {:label "Fitness · CTL" :tint "blue" :size "md" :value (:ctl latest) :sub "42-day load"})
         (ui/tile {:label "Fatigue · ATL" :tint "red" :size "md" :value (:atl latest) :sub "7-day load"})
         (ui/tile {:label "Form · TSB" :tint "green" :size "md" :value (:tsb latest) :sub "fitness − fatigue"})
         (ui/tile {:label "Ramp rate" :tint "amber" :size "md" :value (:ramp latest) :sub "CTL change / 7 days"})]
        [:div {:replicant/key "pmc"}
         (ui/chart-card
          {:id :pmc :title "Fitness and fatigue" :tables-open tables-open
           :legend-items [{:label "Fitness (CTL)" :color "--blue"} {:label "Fatigue (ATL)" :color "--red"}]
           :table-columns [(date-col)
                           {:label "TSS" :value :tss :align :r} {:label "CTL" :value :ctl :align :r}
                           {:label "ATL" :value :atl :align :r} {:label "TSB" :value :tsb :align :r}
                           {:label "Ramp" :value :ramp :align :r}]
           :table-rows pmc
           :chart (c/echart
                   {:key "pmc" :class "h280" :group "load"
                    :option-fn (fn []
                                 (merge (c/base {:y-min 0 :tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(-> (c/line "Fitness (CTL)" "--blue" (mapv (juxt :date :ctl) pmc)
                                                              :lineStyle {:width 3})
                                                      (c/area-wash 0.18))
                                                  (c/line "Fatigue (ATL)" "--red" (mapv (juxt :date :atl) pmc)
                                                          :lineStyle {:width 2.5})]}))})})]
        [:div.row {:replicant/key "r2"}
         (ui/chart-card
          {:id :tsb :title "Form · TSB" :tint "green" :flex "1 1 420px" :tables-open tables-open
           :table-columns [(date-col) {:label "TSB" :value :tsb :align :r}]
           :table-rows pmc
           :chart (c/echart
                   {:key "tsb" :class "h150" :group "load"
                    :option-fn (fn []
                                 (merge (c/base {:tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(-> (c/line "Form (TSB)" "--green-ink" (mapv (juxt :date :tsb) pmc)
                                                              :lineStyle {:width 3})
                                                      (assoc :markLine {:silent true :symbol "none" :label {:show false}
                                                                        :lineStyle {:color (c/css-var "--green-ink") :opacity 0.5
                                                                                    :type "solid" :width 1}
                                                                        :data [{:yAxis 0}]}
                                                             :markArea {:silent true
                                                                        :itemStyle {:color (c/css-var "--pill-on-tint")}
                                                                        :data [[{:yAxis "min"} {:yAxis 0}]]}))]}))})
           :footer [:div.sub {:style {:color "var(--green-text)"}}
                    "Above 0: fresh. −10 to −30: building fitness. Below −30: high strain."]})
         (ui/chart-card
          {:id :tss :title "Daily stress · TSS" :tint "amber" :flex "1 1 420px" :tables-open tables-open
           :table-columns [(date-col) {:label "TSS" :value :tss :align :r}]
           :table-rows (filter #(pos? (:tss %)) pmc)
           :chart (c/echart
                   {:key "tss-bars" :class "h150" :group "load"
                    :option-fn (fn []
                                 (merge (c/base {:y-min 0 :tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(c/bars "TSS" "--amber" (mapv (juxt :date #(when (pos? (:tss %)) (:tss %))) pmc)
                                                          :barMaxWidth 14
                                                          :itemStyle {:color (c/css-var "--amber") :borderRadius 5})]}))})})]
        [:div {:replicant/key "vol"}
         (ui/chart-card
          {:id :volume :title "Weekly volume" :tables-open tables-open
           :controls (ui/seg vm-key (for [[k v] volume-metrics] [k (:label v)]) [:set-ui :volume-metric] {:soft? true})
           :legend-items (for [f fams] {:label (:label f) :color (:color f)})
           :table-columns (into [{:label "Week of" :value #(ui/date-short (first %))}]
                                (for [f fams] {:label (:label f) :value #(some-> (get (second %) (:id f)) ((:fmt vm))) :align :r}))
           :table-rows (seq weeks)
           :chart (c/echart
                   {:key "volume" :class "h240"
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
                                                :stack "vol" :barMaxWidth 28
                                                :itemStyle {:color (c/css-var (:color f)) :borderRadius 6
                                                            :borderColor (c/css-var "--surface") :borderWidth 3}))}))})})]))]))
