(ns coach.views.activity
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.ui :as ui]))

(defn- rolling
  "Centered moving average over `w` samples (nils skipped), to calm GPS speed."
  [xs w]
  (let [v (vec xs) n (count v) h (quot w 2)]
    (mapv (fn [i]
            (let [win (keep v (range (max 0 (- i h)) (min n (+ i h 1))))]
              (when (and (v i) (seq win)) (/ (reduce + win) (count win)))))
          (range n))))

(defn- series-data [ts ys]
  (into [] (keep (fn [[t y]] (when (some? y) [t y]))) (map vector ts ys)))

(defn- percentile-bounds
  "[p2 p98] of the y values, padded 5%, so GPS spikes don't stretch the axis."
  [data]
  (let [ys (vec (sort (map second data))) n (count ys)]
    (when (> n 20)
      (let [lo (ys (js/Math.floor (* 0.02 n))) hi (ys (js/Math.floor (* 0.98 n))) pad (* 0.05 (- hi lo))]
        [(- lo pad) (+ hi pad)]))))

(defn- elapsed-fmt [s] (let [m (js/Math.round (/ s 60))] (str (quot m 60) ":" (.padStart (str (mod m 60)) 2 "0"))))

(defn- stream-chart [{:keys [key title data y-fmt y-inverse unit color area? tip clip?]}]
  [:div.card
   [:h2 title]
   (c/echart
    {:key key :class "short" :group "activity"
     :option-fn
     (fn []
       (merge (c/base {:x-type "value" :x-fmt elapsed-fmt :y-fmt y-fmt :y-inverse y-inverse
                       :y-min (when clip? (first (percentile-bounds data)))
                       :y-max (when clip? (second (percentile-bounds data)))
                       :tooltip-fmt (fn [params]
                                      (let [p (first params) [t y] (js->clj (.-value p))]
                                        (c/tooltip-rows (str "at " (elapsed-fmt t))
                                                        [{:label unit :value (tip y)
                                                          :color (c/css-var color)}])))})
              {:series [(cond-> (c/line title color data) area? c/area-wash)]}))})])

(defn- zones-chart [zones]
  (let [labels ["Below Z1" "Z1" "Z2" "Z3" "Z4" "Z5"]
        ramp   ["--axis" "--seq-250" "--seq-350" "--seq-450" "--seq-550" "--seq-650"]
        total  (reduce + zones)
        rows   (map vector labels zones ramp)]
    (c/echart
     {:key "zones" :class "short"
      :option-fn
      (fn []
        (-> (c/base {:x-type "value" :grid-left 64})
            (assoc :tooltip {:show false})
            (assoc :yAxis {:type "category" :data labels :inverse true
                           :axisLine {:show false} :axisTick {:show false}
                           :axisLabel {:color (c/css-var "--text-secondary")}})
            (assoc-in [:xAxis :axisLabel :formatter] (fn [v] (str (js/Math.round (/ v 60)) "m")))
            (assoc :grid {:left 64 :right 90 :top 6 :bottom 24})
            (assoc :series [{:type "bar" :barMaxWidth 16
                             :data (for [[_ s color] rows]
                                     {:value s :itemStyle {:color (c/css-var color) :borderRadius [0 4 4 0]}})
                             :label {:show true :position "right" :color (c/css-var "--text-secondary")
                                     :formatter (fn [p] (str (if (< (.-value p) 60) "<1m" (ui/hm (.-value p))) " · "
                                                             (js/Math.round (* 100 (/ (.-value p) (max 1 total)))) "%"))}}])))})))

(def peak-labels {:5 "5 s" :60 "1 min" :300 "5 min" :1200 "20 min" :3600 "60 min"})

(defn- tile-row [a run?]
  (let [secs (or (:moving_s a) (:duration_s a))
        mps  (when (and (:distance_m a) secs (pos? secs)) (/ (:distance_m a) secs))]
    [:div.grid.tiles
     (ui/tile "Distance" (ui/km (:distance_m a)) "km" nil)
     (ui/tile "Moving time" (ui/hms secs) nil (str "elapsed " (ui/hms (:duration_s a))))
     (if run?
       (ui/tile "Avg pace" (ui/pace mps) "/km" (some->> (:ngp_mps a) ui/pace (str "NGP ")))
       (ui/tile "Avg speed" (ui/kmh mps) "km/h" nil))
     (ui/tile "Elevation gain" (some-> (:elev_gain_m a) js/Math.round) "m" nil)
     (ui/tile "Heart rate" (some-> (:avg_hr a) js/Math.round) "bpm" (some->> (:max_hr a) js/Math.round (str "max ")))
     (ui/tile "Training stress" (some-> (:tss a) js/Math.round) (:tss_method a)
              (str/join " · " (remove nil? [(some->> (ui/fixed (:intensity a) 2) (str "IF "))
                                            (some-> (:tss_per_hour a) js/Math.round (str " TSS/h"))])))
     (when (:np a) (ui/tile "Normalized power" (js/Math.round (:np a)) "W"
                            (str/join " · " (remove nil? [(some->> (:vi a) (str "VI ")) (some->> (:kj a) (str "kJ "))]))))
     (ui/tile "Efficiency factor" (ui/fixed (:ef a) 2) nil
              (some->> (:decoupling a) (str "decoupling ") (#(str % "%"))))
     (ui/tile "Garmin load" (some-> (:garmin_load a) js/Math.round) nil
              (some->> (ui/fixed (:aerobic_te a) 1) (str "aerobic TE ")))]))

(defn view [{:keys [data errors]} url]
  (let [a    (get data url)
        s    (:streams a)
        run? (= :run (:id (ui/family (:sport a))))
        t    (:t s)
        lat  (:lat s) lon (:lon s)
        pts  (when (and (seq lat) (seq lon))
               (let [pts (keep (fn [[la lo]] (when (and la lo) [la lo])) (map vector lat lon))
                     step (max 1 (quot (count pts) 3000))]
                 (vec (take-nth step pts))))
        speed (some-> (:speed s) (rolling 15))]
    [:div
     [:div.page-head
      [:a {:href "#/activities"} "← Activities"]]
     (when-let [e (get errors url)] [:div.error (str "Could not load: " e)])
     (when a
       [:div.stack
        [:div
         [:h1 (:name a)]
         [:div.secondary (str (ui/sport-label (:sport a)) " · " (ui/date-long (:date a))
                              (some->> (ui/time-of (:start_time_local a)) (str " · ")))]]
        (tile-row a run?)
        (when (seq pts) [:div.card (c/route-map {:key (str "map-" (:id a)) :latlngs pts})])
        (when (seq t)
          [:div.grid.two
           (when (some some? (:hr s))
             (stream-chart {:key "hr" :title "Heart rate" :unit "bpm" :color "--series-1"
                            :data (series-data t (:hr s)) :tip #(str (js/Math.round %))}))
           (when (some some? speed)
             (if run?
               (stream-chart {:key "pace" :title "Pace (15 s smoothed)" :unit "/km" :color "--series-1"
                              :y-inverse true :clip? true :y-fmt #(ui/hms %) :tip #(ui/hms %)
                              :data (series-data t (map #(when (and % (> % 1.0)) (/ 1000 %)) speed))})
               (stream-chart {:key "speed" :title "Speed (15 s smoothed)" :unit "km/h" :color "--series-1"
                              :clip? true :tip #(ui/fixed % 1) :data (series-data t (map #(some-> % (* 3.6)) speed))})))
           (when (some some? (:alt s))
             (stream-chart {:key "alt" :title "Elevation" :unit "m" :color "--series-1" :area? true
                            :tip #(str (js/Math.round %)) :data (series-data t (:alt s))}))
           (when (some some? (:power s))
             (stream-chart {:key "power" :title "Power" :unit "W" :color "--series-1"
                            :tip #(str (js/Math.round %)) :data (series-data t (:power s))}))])
        [:div.grid.two
         (when (seq (:hr_zones_s a))
           [:div.card [:h2 "Time in heart-rate zones"] (zones-chart (:hr_zones_s a))])
         (when (seq (:peaks a))
           (let [pk (:peaks a)]
             [:div.card
              [:h2 "Peak values"]
              (ui/table [{:label "Duration" :value #(peak-labels %)}
                         {:label "Heart rate" :value #(some-> (get-in pk [:hr %]) js/Math.round) :align :r}
                         {:label (if run? "Pace" "Speed") :value #(when-let [v (get-in pk [:speed %])]
                                                                    (if run? (str (ui/pace v) " /km") (str (ui/kmh v) " km/h")))
                          :align :r}
                         {:label "Power" :value #(some-> (get-in pk [:power %]) js/Math.round) :align :r}]
                        [:5 :60 :300 :1200 :3600])]))]
        (when-let [th (:thresholds a)]
          [:div.muted
           (str "Scored with LTHR " (some-> (:lthr th) js/Math.round) " bpm"
                (some-> (:threshold_speed th) ui/pace (#(str ", threshold pace " % " /km")))
                (some->> (:ftp th) js/Math.round (str ", FTP "))
                ", resting HR " (:rest_hr th) ".")])])]))
