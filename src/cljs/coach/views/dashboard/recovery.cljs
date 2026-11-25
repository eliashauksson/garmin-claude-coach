(ns coach.views.dashboard.recovery
  "Recovery over the last 30 days: HRV, sleep, Body Battery, resting HR, stress."
  (:require [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer
             [cat-base chart-card day-label empty-line off-grid-ends padded-range rows-tip]]
            [coach.views.dashboard.cycle :as cycle]))

(defn- cycle-row
  "The tooltip line for the cycle day of `date`, nil without cycle data."
  [by-date date]
  (when-let [{:keys [cycle_day phase]} (by-date date)]
    {:label (t "cycle") :value (str (t "day {0}" cycle_day) " · " (t (get cycle/phases phase phase)))}))

(defn- hrv-chart [nights cyc]
  (let [dates (mapv :date nights)
        by-date (into {} (map (juxt :date identity)) cyc)
        vals  (concat (keep :hrv_night nights) (keep :hrv_balanced_low nights) (keep :hrv_balanced_up nights)
                      (keep :hrv_weekly nights))
        teal  (c/css-var "--fam-swim")]
    (if (empty? (keep :hrv_night nights))
      (empty-line)
      (c/echart
       {:key "hrv" :class "h240"
        :option-fn
        (fn []
          (merge (off-grid-ends (cat-base dates {:y-min (- (apply min vals) 4) :y-max (+ (apply max vals) 4)
                                  :y-fmt (fn [v] (str v " ms"))
                                  :tooltip-fmt (rows-tip nights
                                                         (fn [n] [(day-label (:date n))
                                                                  (remove nil? [{:label (t "overnight")
                                                                    :value (some-> (:hrv_night n) (str " ms"))
                                                                    :color teal}
                                                                   {:label (t "7-day average")
                                                                    :value (some-> (:hrv_weekly n) (str " ms"))}
                                                                   {:label (t "balanced range")
                                                                    :value (when (:hrv_balanced_low n)
                                                                             (str (:hrv_balanced_low n) "–"
                                                                                  (:hrv_balanced_up n) " ms"))}
                                                                   {:label (t "status")
                                                                    :value (ui/enum (:hrv_status n))}
                                                                   (cycle-row by-date (:date n))])]))}))
                 {:series
                  [(cond-> {:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0}
                            :tooltip {:show false}
                            :data (mapv :hrv_balanced_low nights)}
                     (seq cyc) (assoc :markArea {:silent true :data (cycle/phase-areas cyc dates)}))
                   {:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0}
                    :tooltip {:show false}
                    :areaStyle {:color teal :opacity 0.13}
                    :data (mapv #(when (and (:hrv_balanced_low %) (:hrv_balanced_up %))
                                   (- (:hrv_balanced_up %) (:hrv_balanced_low %))) nights)}
                   {:type "line" :name (t "7-day average") :data (mapv :hrv_weekly nights) :showSymbol false
                    :lineStyle {:width 1.5 :type "dashed" :color (c/css-var "--text-3")} :emphasis {:disabled true}}
                   {:type "line" :name (t "Overnight") :showSymbol true :symbolSize 7 :connectNulls false
                    :lineStyle {:width 2.5 :color teal} :emphasis {:disabled true}
                    :data (mapv (fn [{:keys [hrv_night hrv_low_up hrv_balanced_low]}]
                                  {:value hrv_night
                                   :itemStyle {:borderColor (c/css-var "--surface") :borderWidth 2
                                               :color (cond
                                                        (and hrv_night hrv_low_up
                                                             (< hrv_night hrv_low_up)) (c/css-var "--red")
                                                        (and hrv_night hrv_balanced_low
                                                             (< hrv_night hrv_balanced_low)) (c/css-var "--amber")
                                                        :else teal)}})
                                nights)}]}))}))))

(def stage-series
  [[:deep_s (tk "Deep") "--deep"] [:light_s (tk "Light") "--purple-2"] [:rem_s (tk "REM") "--pink"]
   [:awake_s (tk "Awake") "--amber"]])

(defn- sleep-chart [nights]
  (let [dates (mapv :date nights)
        hours #(some-> % (/ 3600))
        top   (apply max 9 (concat (keep #(some-> (:total_s %) (+ (or (:awake_s %) 0)) hours) nights)
                                   (keep #(some-> (:need_min %) (/ 60)) nights)))]
    (if (empty? (keep :total_s nights))
      (empty-line)
      (list
       (ui/legend (concat (for [[_ label color] stage-series] {:label (t label) :color color})
                          [{:label (t "Sleep need") :color "--text"}]))
       (c/echart
        {:key "sleep" :class "h230"
         :option-fn
         (fn []
           (merge (cat-base dates {:y-min 0 :y-max (* 3 (js/Math.ceil (/ top 3))) :y-fmt (fn [v] (str v " h"))
                                   :tooltip-fmt (rows-tip nights
                                                          (fn [n] [(day-label (:date n))
                                                                   (concat
                                                                    [{:label (t "sleep score")
                                                                      :value (some-> (:sleep_score n)
                                                                                     (str (some->> (:quality n) ui/enum
                                                                                                   (str " · "))))}
                                                                     {:label (t "asleep")
                                                                      :value (ui/h-mm (:total_s n))}]
                                                                    (for [[k label color] stage-series]
                                                                      {:label (t label) :value (ui/h-mm (get n k))
                                                                       :color (c/css-var color)})
                                                                    [{:label (t "sleep need")
                                                                      :value (some-> (:need_min n) (* 60)
                                                                                     ui/h-mm)}])]))})
                  {:series
                   (concat
                    (c/stacked 3
                               (for [[k label color] stage-series]
                                 (c/bars (t label) color (mapv #(hours (get % k)) nights)
                                         :stack "sleep" :barMaxWidth 18
                                         :itemStyle {:color (c/css-var color)})))
                    [{:type "custom" :name (t "Sleep need") :z 10 :silent true :tooltip {:show false}
                      :data (vec (map-indexed (fn [i n] [i (or (some-> (:need_min n) (/ 60)) "-")]) nights))
                      :renderItem (fn [_ ^js api]
                                    (let [v (.value api 1)]
                                      (when (and (number? v) (not (js/isNaN v)))
                                        (let [p (.coord api #js [(.value api 0) v])
                                              w (min 24 (* 0.8 (aget (.size api #js [1 0]) 0)))]
                                          (clj->js {:type "line"
                                                    :shape {:x1 (- (aget p 0) (/ w 2)) :y1 (aget p 1)
                                                            :x2 (+ (aget p 0) (/ w 2)) :y2 (aget p 1)}
                                                    :style {:stroke (c/css-var "--text") :lineWidth 2}})))))}])}))})))))

(defn- bb-chart [days]
  (let [dates (mapv :date days)
        blue  (c/css-var "--blue")]
    (if (empty? (keep :bb_max days))
      (empty-line)
      (c/echart
       {:key "bb" :class "h230"
        :option-fn
        (fn []
          (merge (cat-base dates {:y-min 0 :y-max 100
                                  :tooltip-fmt (rows-tip days
                                                         (fn [d] [(day-label (:date d))
                                                                  [{:label (t "range")
                                                                    :value (when (:bb_min d)
                                                                             (str (:bb_min d) "–" (:bb_max d)))
                                                                    :color blue}
                                                                   {:label (t "end of day") :value (:bb_latest d)}
                                                                   {:label (t "charged")
                                                                    :value (some->> (:bb_charged d) (str "+"))}
                                                                   {:label (t "drained")
                                                                    :value (some->> (:bb_drained d) (str "−"))}]]))})
                 {:series
                  [{:type "bar" :stack "bb" :silent true :itemStyle {:color "transparent"} :tooltip {:show false}
                    :data (mapv :bb_min days) :barMaxWidth 14}
                   {:type "bar" :stack "bb" :name (t "Body Battery") :barMaxWidth 14
                    :itemStyle {:color blue :opacity 0.7 :borderRadius 5}
                    :emphasis {:focus "self" :itemStyle {:opacity 1}}
                    :blur {:itemStyle {:opacity 0.45}}
                    :data (mapv #(when (and (:bb_min %) (:bb_max %)) (- (:bb_max %) (:bb_min %))) days)}
                   {:type "scatter" :name (t "end of day") :symbolSize 8 :z 5
                    :itemStyle {:color (c/css-var "--surface") :borderColor blue :borderWidth 2}
                    :data (mapv :bb_latest days)}]}))}))))

(defn- rhr-chart [days cyc]
  (let [rows (vec days)
        by-date (into {} (map (juxt :date identity)) cyc)
        i    (last (keep-indexed #(when (:rhr %2) %1) rows))]
    (if-not i
      (empty-line)
      (let [lastv (:rhr (rows i))
            text  (str lastv " bpm")
            {:keys [label pad]} (c/end-label text "--red")
            [lo hi] (padded-range (keep :rhr rows))]
        (c/echart
         {:key "rhr" :class "h180"
          :option-fn
          (fn []
            (-> (off-grid-ends
                 (cat-base (mapv :date rows)
                          {:y-min lo :y-max hi
                           :tooltip-fmt (rows-tip rows (fn [r] [(day-label (:date r))
                                                                (remove nil?
                                                                        [{:label (t "resting HR")
                                                                          :value (some-> (:rhr r) (str " bpm"))
                                                                          :color (c/css-var "--red")}
                                                                         (cycle-row by-date (:date r))])]))}))
                (assoc-in [:grid :right] pad)
                (merge {:series [(c/line (t "Resting heart rate") "--red" (mapv :rhr rows)
                                         :lineStyle {:width 2.2} :connectNulls true :sampling nil
                                         :areaStyle {:color (c/fade "--red" 0.26)}
                                         :markArea (when (seq cyc)
                                                     {:silent true :data (cycle/phase-areas cyc (mapv :date rows))}))
                                 {:type "scatter" :silent true :symbolSize 8 :tooltip {:show false}
                                  :itemStyle {:color (c/css-var "--red") :borderColor (c/css-var "--surface")
                                              :borderWidth 2}
                                  :label (assoc label :position "right")
                                  :data [[i lastv]]}]})))})))))

(defn- stress-chart [days]
  (let [vs (keep :stress_avg days)]
    (if (empty? vs)
      (empty-line)
      (c/echart
       {:key "stress" :class "h180"
        :option-fn
        (fn []
          (merge (cat-base (mapv :date days)
                           {:y-min 0 :y-max (max 60 (apply max vs))
                            :tooltip-fmt (rows-tip days (fn [d] [(day-label (:date d))
                                                                 [{:label (t "average stress") :value (:stress_avg d)}
                                                                  {:label (t "steps")
                                                                   :value (ui/thousands (:steps d))}]]))})
                 {:series [(c/bars (t "Average stress") "--blue"
                                   (mapv (fn [{:keys [stress_avg]}]
                                           {:value stress_avg
                                            :itemStyle {:borderRadius 4
                                                        :color (c/css-var (cond (nil? stress_avg) "--line"
                                                                                (<= stress_avg 25) "--blue"
                                                                                (<= stress_avg 50) "--amber"
                                                                                :else "--red"))}})
                                         days)
                                   :barMaxWidth 14
                                   :markLine {:silent true :symbol "none" :label {:show false}
                                              :lineStyle {:color (c/css-var "--text-3") :type "dashed" :width 1}
                                              :data [{:yAxis 25}]})]}))}))))

(defn- night-vital-chart
  "One value measured during sleep, a point per night."
  [days k {:keys [key color label fmt]}]
  (let [vs (keep k days)]
    (if (empty? vs)
      (empty-line)
      (let [[lo hi] (padded-range vs)]
        (c/echart
         {:key key :class "h180"
          :option-fn
          (fn []
            (merge (off-grid-ends
                    (cat-base (mapv :date days)
                              {:y-min lo :y-max hi
                               :tooltip-fmt (rows-tip days (fn [d] [(day-label (:date d))
                                                                    [{:label label :value (some-> (k d) fmt)
                                                                      :color (c/css-var color)}]]))}))
                   {:series [(c/line label color (mapv k days) :lineStyle {:width 2.2} :connectNulls true
                                     :sampling nil :showSymbol true :symbolSize 5)]}))})))))

(defn shown?
  "Whether the Recovery section has any chart for this athlete."
  [d]
  (boolean (some #(ui/has? d %) [:hrv :sleep :body_battery :rhr :stress])))

(defn recovery [{:keys [nights days] :as d} cyc]
  (list
   [:div.section-head {:replicant/key "rec-head" :id "tr-recovery"}
    [:h2.section-title (t "Recovery") [:span.section-note (t "Last 30 days")]]]
   [:div.chart-grid {:replicant/key "rec"}
    (when (ui/has? d :hrv)
      (chart-card {:key "hrv" :info "hrv" :wide? true :title (t "Overnight HRV against your baseline")
                   :text (str (t "Nightly average in ms. The shaded band is Garmin's balanced range for you; the dashed line is the 7-day average.")
                              (when (seq cyc) (str " " (t "The background colour is your cycle phase."))))
                   :body (hrv-chart nights cyc)}))
    (when (ui/has? d :sleep)
      (chart-card {:key "sleep" :info "sleep" :title (t "Sleep stages per night")
                   :text (t "Hours asleep in each stage; the dark tick is the sleep Garmin says you needed.")
                   :body (sleep-chart nights)}))
    (when (ui/has? d :body_battery)
      (chart-card {:key "bb" :info "body-battery" :title (t "Body Battery range per day")
                   :text (t "From the day's low to its high; the ring is where the day ended.")
                   :body (bb-chart days)}))
    (when (ui/has? d :rhr)
      (chart-card {:key "rhr" :info "rhr" :title (t "Resting heart rate")
                   :text (str (t "Beats per minute at rest, each day.")
                              (when (seq cyc) (str " " (t "The background colour is your cycle phase."))))
                   :body (rhr-chart days cyc)}))
    (when (ui/has? d :stress)
      (chart-card {:key "stress" :info "stress" :title (t "Average stress per day")
                   :text (t "Garmin's stress score from 0 to 100; below the dashed line at 25 is rest.")
                   :body (stress-chart days)}))
    ;; only for watches that measure them
    (when (and (ui/measured? d :respiration) (some :resp_sleep days))
      (chart-card {:key "resp" :info "respiration" :title (t "Breathing rate asleep")
                   :text (t "Breaths per minute during the night. A clear rise can come before an illness shows.")
                   :body (night-vital-chart days :resp_sleep
                                            {:key "resp" :color "--blue" :label (t "Breaths per minute")
                                             :fmt #(.toFixed % 1)})}))
    (when (and (ui/measured? d :spo2) (some :spo2_avg days))
      (chart-card {:key "spo2" :info "respiration" :title (t "Blood oxygen asleep")
                   :text (t "Average during the night. It drops at altitude, and a loose watch reads low.")
                   :body (night-vital-chart days :spo2_avg {:key "spo2" :color "--fam-swim" :label (t "Blood oxygen")
                                                            :fmt #(str (js/Math.round %) "%")})}))]))
