(ns coach.views.trends
  (:require [coach.charts :as c]
            [coach.ui :as ui]))

(def ranges [["90" "90 days"] ["365" "1 year"] ["730" "2 years"] ["all" "All"]])

(def metrics
  [{:k :rhr         :title "Resting heart rate" :unit "bpm"}
   {:k :hrv_avg     :title "HRV (overnight average)" :unit "ms"}
   {:k :sleep_score :title "Sleep score" :unit ""}
   {:k :sleep_s     :title "Sleep duration" :unit "h" :scale (/ 1 3600)}
   {:k :vo2max      :title "VO2 max (running)" :unit "ml/kg/min"}
   {:k :readiness   :title "Training readiness" :unit ""}
   {:k :avg_stress  :title "Stress (daily average)" :unit ""}])

(def legend-items
  [{:label "Daily" :color "--text-muted" :kind :line}
   {:label "28-day average" :color "--series-1" :kind :line}
   {:label "Normal range (60-day mean ± 1 SD)" :color "--series-1" :kind :band}])

(defn- fmt [v]
  (when (ui/num? v)
    (if (or (js/Number.isInteger v) (>= (js/Math.abs v) 20)) (str (js/Math.round v)) (ui/fixed v 1))))

(defn- scaled [rows scale]
  (if scale
    (mapv (fn [r] (reduce #(update %1 %2 (fn [v] (when v (* v scale)))) r [:value :avg7 :avg28 :base_low :base_high]))
          rows)
    rows))

(defn- metric-card [{:keys [k title unit scale]} rows tables-open]
  (let [rows (scaled rows scale)]
    (ui/chart-card
     {:id k :title title :tables-open tables-open
      :table-columns [{:label "Date" :value #(ui/date-short (:date %))}
                      {:label "Value" :value #(fmt (:value %)) :align :r}
                      {:label "7-day avg" :value #(fmt (:avg7 %)) :align :r}
                      {:label "28-day avg" :value #(fmt (:avg28 %)) :align :r}
                      {:label "Normal range" :value #(when (:base_low %) (str (fmt (:base_low %)) "–" (fmt (:base_high %))))
                       :align :r}]
      :table-rows rows
      :chart
      (if (not-any? :value rows)
        [:div.empty "No data in this range."]
        (c/echart
         {:key (name k) :group "trends"
          :option-fn
          (fn []
            (merge (c/base {:y-fmt fmt
                            :tooltip-fmt
                            (fn [params]
                              (let [r (nth rows (.-dataIndex (first params)) nil)]
                                (c/tooltip-rows (ui/date-long (:date r))
                                                [{:label (str unit " daily") :value (fmt (:value r)) :color (c/css-var "--text-muted")}
                                                 {:label "28-day avg" :value (fmt (:avg28 r)) :color (c/css-var "--series-1")}
                                                 {:label "normal range" :value (when (:base_low r) (str (fmt (:base_low r)) "–" (fmt (:base_high r))))}])))})
                   {:series (concat
                             (c/band "base" "--series-1" rows :base_low :base_high)
                             [(c/line "Daily" "--text-muted" (mapv (juxt :date :value) rows) :lineStyle {:width 1 :opacity 0.6})
                              (c/line "28-day average" "--series-1" (mapv (juxt :date :avg28) rows))])}))}))})))

(defn view [{:keys [data errors route tables-open]} url]
  (let [d     (get data url)
        range (get-in route [:params :range] "365")]
    [:div
     [:div.page-head [:h1 "Trends"]]
     [:div.filters (ui/seg range ranges [:set-param :range])]
     (when-let [e (get errors url)] [:div.error (str "Could not load: " e)])
     (ui/legend legend-items)
     (when d
       [:div.grid.two
        (for [m metrics]
          [:div {:replicant/key (name (:k m))}
           (metric-card m (get d (:k m)) tables-open)])])]))
