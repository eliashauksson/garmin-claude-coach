(ns coach.views.dashboard.fitness
  "Fitness over the last 6 months: VO2 max, the endurance score, and where there is data, acclimation and weight."
  (:require [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
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

(defn- weight-chart [rows]
  (let [points (mapv (juxt :date :weight_kg) rows)
        fat    (vec (keep (fn [r] (when (:body_fat r) [(:date r) (:body_fat r)])) rows))
        [lo hi] (padded-range (map second points))]
    (list
     [:div.big.md {:replicant/key "n"} (str (.toFixed (:weight_kg (peek rows)) 1) " kg")]
     (when (seq fat)
       [:div.sub {:replicant/key "fat"} (t "body fat {0}%" (.toFixed (second (peek fat)) 1))])
     ;; a line needs two weigh-ins
     (if-not (next points)
       [:div.sub {:replicant/key "when"} (t "as of {0}" (ui/date-short (ffirst points)))]
       (c/echart
        {:key "weight" :class "h180"
         :option-fn
         (fn []
           (merge (off-grid-ends
                   (c/base {:y-min lo :y-max hi
                            :x-fmt (fn [v] (axis-date (.toISOString (js/Date. v))))
                            :tooltip-fmt (fn [params]
                                           (let [[d v] (js->clj (.-value (first params)))]
                                             (c/tooltip-rows (day-label d)
                                                             [{:label (t "Weight") :value (str (.toFixed v 1) " kg")
                                                               :color (c/css-var "--blue")}])))}))
                  {:series [(c/line (t "Weight") "--blue" points :lineStyle {:width 2.2} :sampling nil
                                    :showSymbol true :symbolSize 6)]}))})))))

(defn- acclimation-body [{:keys [heat_pct altitude_m date]}]
  (list
   [:div.row {:replicant/key "v"}
    (when heat_pct
      [:div [:div.lab (t "Heat acclimation")] [:div.big.md (str (js/Math.round heat_pct) "%")]])
    (when altitude_m
      [:div [:div.lab (t "Altitude")] [:div.big.md (str (js/Math.round altitude_m) " m")]])]
   [:div.sub {:replicant/key "d"} (t "as of {0}" (ui/date-short date))]))

(defn shown?
  "Whether the Fitness section has anything for this athlete."
  [{:keys [acclimation weigh_ins] :as d}]
  (boolean (or (ui/has? d :vo2max) (ui/has? d :endurance) acclimation (seq weigh_ins))))

(defn fitness [{:keys [vo2 endurance acclimation weigh_ins] :as d}]
  (list
   [:div.section-head {:replicant/key "fit-head" :id "tr-fitness"}
    [:h2.section-title (t "Fitness") [:span.section-note (t "Last 6 months")]]]
   [:div.chart-grid {:replicant/key "fit"}
    (when (ui/has? d :vo2max)
      (chart-card {:key "vo2" :info "vo2max" :title (t "VO₂ max (running)")
                   :text (t "Garmin's estimate in ml/kg/min, each day it changed.")
                   :body (end-line {:key "vo2" :class "h220" :color "--green" :rows vo2 :k :vo2
                                    :fmt #(.toFixed % 1)})}))
    (when (ui/has? d :endurance)
      (chart-card {:key "endurance" :info "endurance-score" :title (t "Endurance score")
                   :text (t "How long you can keep going, from all your training. The marker is your score on Garmin's classes.")
                   :body (if-not (:score endurance)
                           (empty-line)
                           (list [:div.big.md {:replicant/key "n"} (:score endurance)]
                                 (when (:limits endurance) (endurance-gauge endurance))))}))
    (when acclimation
      (chart-card {:key "acclimation" :info "acclimation" :title (t "Heat and altitude acclimation")
                   :text (t "How used your body is to heat and altitude, from Garmin. It fades within a few weeks.")
                   :body (acclimation-body acclimation)}))
    (when (seq weigh_ins)
      (chart-card {:key "weight" :info "weight" :title (t "Weight")
                   :text (t "From your scale or what you entered in Garmin Connect.")
                   :body (weight-chart (vec weigh_ins))}))]))
