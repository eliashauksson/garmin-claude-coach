(ns coach.views.dashboard.fitness
  "Fitness over the last 6 months: VO2 max and the endurance score."
  (:require [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.views.dashboard.common :refer
             [axis-date chart-card day-label empty-line fit-labels off-grid-ends padded-range]]))

(defn- end-line
  "A line on a time axis with an area fade and a labelled end point (VO2 max)."
  [{:keys [key class color rows k fmt]}]
  (let [points (vec (keep (fn [r] (when (get r k) [(:date r) (get r k)])) rows))]
    (if (empty? points)
      (empty-line)
      (let [[lo hi] (padded-range (map second points))
            [_ lastv :as end] (peek points)
            {:keys [label pad]} (c/end-label (fmt lastv) color)]
        (c/echart
         {:key key :class class
          :option-fn
          (fn []
            (-> (off-grid-ends
                 (c/base {:y-min lo :y-max hi
                         :x-fmt (fn [v] (axis-date (.toISOString (js/Date. v))))
                         :tooltip-fmt (fn [params]
                                        (let [[d v] (js->clj (.-value (first params)))]
                                          (c/tooltip-rows (day-label d)
                                                          [{:label "VO₂ max" :value (fmt v)
                                                            :color (c/css-var color)}])))}))
                (assoc-in [:grid :right] pad)
                (merge {:series [(c/line "VO₂ max" color points :lineStyle {:width 2.2} :sampling nil
                                         :areaStyle {:color (c/fade color 0.26)})
                                 {:type "scatter" :silent true :symbolSize 8 :tooltip {:show false}
                                  :itemStyle {:color (c/css-var color) :borderColor (c/css-var "--surface")
                                              :borderWidth 2}
                                  :label (assoc label :position "right")
                                  :data [end]}]})))})))))

(def classes
  [[:low (tk "Recreational")] [:intermediate (tk "Intermediate")] [:trained (tk "Trained")]
   [:well_trained (tk "Well-trained")] [:expert (tk "Expert")] [:superior (tk "Superior")] [:elite (tk "Elite")]])

(defn- endurance-gauge [{:keys [score limits]}]
  (let [bounds (conj (mapv #(get limits (first %)) classes) (:high limits))
        lo (first bounds) hi (last bounds)
        pct #(* 100 (/ (- % lo) (- hi lo)))
        cur (last (keep-indexed (fn [i b] (when (>= score b) i)) (butlast bounds)))
        next-b (get bounds (inc cur))]
    (list
     (fit-labels "gauge"
      [:div.endurance
       [:div.end-marker {:style {:left (str (pct score) "%")}}]
       [:div.end-segs
        (for [[i [k label]] (map-indexed vector classes)
              :let [a (get bounds i) b (get bounds (inc i))]]
          [:div.end-seg {:replicant/key k :style {:flex (- b a)}}
           [:div.end-bar {:class (when (= i cur) "current")
                          :style {:opacity (+ 0.15 (* 0.75 (/ i 6)))}}]
           [:div.end-lab {:class (when (= i cur) "current")} [:span.fit (t label)]]])]])
     [:div.sub {:replicant/key "foot"}
      (if (and next-b (< (inc cur) (count classes)))
        (t "{0} points to {1}" (js/Math.round (- next-b score)) (t (second (classes (inc cur)))))
        (t "Top class"))])))

(defn fitness [{:keys [vo2 endurance]}]
  (list
   [:div.section-head {:replicant/key "fit-head"}
    [:h2.section-title (t "Fitness") [:span.section-note (t "Last 6 months")]]]
   [:div.chart-grid {:replicant/key "fit"}
    (chart-card {:key "vo2" :info "vo2max" :title (t "VO₂ max (running)")
                 :text (t "Garmin's estimate in ml/kg/min, each day it changed.")
                 :body (end-line {:key "vo2" :class "h220" :color "--green" :rows vo2 :k :vo2
                                  :fmt #(.toFixed % 1)})})
    (chart-card {:key "endurance" :info "endurance-score" :title (t "Endurance score")
                 :text (t "How long you can keep going, from all your training. The marker is your score on Garmin's classes.")
                 :body (if-not (:score endurance)
                         (empty-line)
                         (list [:div.big.md {:replicant/key "n"} (:score endurance)]
                               (when (:limits endurance) (endurance-gauge endurance))))})]))
