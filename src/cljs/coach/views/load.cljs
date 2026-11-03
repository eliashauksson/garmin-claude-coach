(ns coach.views.load
  (:require [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

(def ranges [["90" (tk "90 days")] ["180" (tk "180 days")] ["365" (tk "1 year")] ["730" (tk "2 years")] ["all" (tk "All")]])

(def volume-metrics
  {"hours" {:label (tk "Hours") :key :duration_s :scale (/ 1 3600) :fmt #(ui/fixed % 1)}
   "km"    {:label (tk "Distance") :key :distance_m :scale (/ 1 1000) :fmt #(str (js/Math.round %))}
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
                      [{:label (t "fitness (CTL)") :value (:ctl r) :color (c/css-var "--blue")}
                       {:label (t "fatigue (ATL)") :value (:atl r) :color (c/css-var "--red")}
                       {:label (t "form (TSB)") :value (:tsb r) :color (c/css-var "--green-ink")}
                       {:label (t "TSS that day") :value (:tss r)}]))))

(defn urls
  "Load and volume for the range chosen in the route (?range=, default 180 days)."
  [route]
  (let [r    (get-in route [:params :range] "180")
        from (if (= r "all") "all" (ui/days-ago (js/parseInt r)))]
    [(str "/api/load?from=" from) (str "/api/volume?from=" from)]))

(defn section
  "The training load, as a section of the Dashboard."
  [{:keys [data errors route] ui-state :ui} [load-url volume-url]]
  (let [pmc    (get data load-url)
        vol    (get data volume-url)
        range  (get-in route [:params :range] "180")
        vm-key (get ui-state :volume-metric "hours")
        vm     (volume-metrics vm-key)
        latest (last pmc)
        weeks  (weekly-by-family vol (:key vm) (:scale vm))
        fams   (filter (fn [f] (some #(get % (:id f)) (vals weeks))) ui/families)
        any?   (some #(pos? (:tss %)) pmc)]
    [:div {:style {:display "contents"}}
     [:div.section-head {:replicant/key "load-head"}
      [:h2.section-title (t "Training load")]
      (ui/seg range ranges [:set-param :range])]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (t "Could not load: {0}" e)])) [load-url volume-url])
     (when (and pmc (not any?))
       (ui/card {} [:div.empty (t "No training data yet — run Sync.")]))
     (when (and pmc any?)
       (list
        [:div.row {:replicant/key "kpi"}
         (ui/tile {:label (t "Fitness · CTL") :tint "blue" :size "md" :value (:ctl latest) :sub (t "42-day load")})
         (ui/tile {:label (t "Fatigue · ATL") :tint "red" :size "md" :value (:atl latest) :sub (t "7-day load")})
         (ui/tile {:label (t "Form · TSB") :tint "green" :size "md" :value (:tsb latest) :sub (t "fitness − fatigue")})
         (ui/tile {:label (t "Ramp rate") :tint "amber" :size "md" :value (:ramp latest) :sub (t "CTL change / 7 days")})]
        [:div {:replicant/key "pmc"}
         (ui/chart-card
          {:title (t "Fitness, fatigue and form")
           :legend-items [{:label (t "Fitness (CTL)") :color "--blue"} {:label (t "Fatigue (ATL)") :color "--red"}
                          {:label (t "Form (TSB)") :color "--green-ink"}]
           :chart (c/echart
                   {:key "pmc" :class "h280" :group "load"
                    :option-fn (fn []
                                 ;; one axis for all three: TSB dips below 0, so no y-min
                                 (merge (c/base {:tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(-> (c/line (t "Fitness (CTL)") "--blue" (mapv (juxt :date :ctl) pmc)
                                                              :lineStyle {:width 3})
                                                      (c/area-wash 0.18))
                                                  (c/line (t "Fatigue (ATL)") "--red" (mapv (juxt :date :atl) pmc)
                                                          :lineStyle {:width 2.5})
                                                  (-> (c/line (t "Form (TSB)") "--green-ink" (mapv (juxt :date :tsb) pmc)
                                                              :lineStyle {:width 2})
                                                      (assoc :markLine {:silent true :symbol "none" :label {:show false}
                                                                        :lineStyle {:color (c/css-var "--text-3") :opacity 0.5
                                                                                    :type "solid" :width 1}
                                                                        :data [{:yAxis 0}]}))]}))})
           :footer [:div.sub (t "Above 0: fresh. −10 to −30: building fitness. Below −30: high strain.")]})]
        [:div.row {:replicant/key "r2"}
         (ui/chart-card
          {:title (t "Daily stress · TSS") :tint "amber" :flex "1 1 100%"
           :chart (c/echart
                   {:key "tss-bars" :class "h150" :group "load"
                    :option-fn (fn []
                                 (merge (c/base {:y-min 0 :tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(c/bars "TSS" "--amber" (mapv (juxt :date #(when (pos? (:tss %)) (:tss %))) pmc)
                                                          :barMaxWidth 14
                                                          :itemStyle {:color (c/css-var "--amber") :borderRadius 5})]}))})})]
        [:div {:replicant/key "vol"}
         (ui/chart-card
          {:title (t "Weekly volume")
           :controls (ui/seg vm-key (for [[k v] volume-metrics] [k (:label v)]) [:set-ui :volume-metric] {:soft? true})
           :legend-items (for [f fams] {:label (t (:label f)) :color (:color f)})
           :chart (c/echart
                   {:key "volume" :class "h240"
                    :option-fn
                    (fn []
                      (merge (c/base {:y-min 0
                                      :tooltip-fmt
                                      (fn [params]
                                        (let [wk (first (js->clj (.-value (first params))))
                                              row (get weeks wk)]
                                          (c/tooltip-rows (t "Week of {0}" (ui/date-short wk))
                                                          (for [f (reverse fams) :let [v (get row (:id f))] :when (and v (pos? v))]
                                                            {:label (t (:label f)) :value ((:fmt vm) v) :color (c/css-var (:color f))}))))})
                             {:series (c/stacked 6
                                                 (for [f fams]
                                                   (c/bars (t (:label f)) (:color f)
                                                           (vec (for [[wk m] weeks] [wk (get m (:id f))]))
                                                           :stack "vol" :barMaxWidth 28
                                                           :itemStyle {:color (c/css-var (:color f))})))}))})})]))]))

;; ---------------------------------------------------------------------------
;; Cycling power (coach.analysis/cycling-power): shown once a ride has power

(def power-url "/api/cycling-power")

(defn- dur-label
  "Seconds -> \"5 s\", \"1 min\", \"1 h\"."
  [s]
  (cond (< s 60)       (str s " s")
        (< s 3600)     (str (js/Math.round (/ s 60)) " min")
        :else          (str (js/Math.round (/ s 3600)) " h")))

(def ^:private curves
  [[:curve_90d      (tk "Last 90 days")   "--amber"]
   [:curve_prev_90d (tk "90 days before") "--purple-2"]
   [:curve_all      (tk "All time")       "--text-3"]])

(defn- power-tooltip [p]
  (fn [params]
    (let [s (js/parseInt (.-name (first params)))]
      (c/tooltip-rows (dur-label s)
                      (for [[k label color] curves
                            :let [pt (some #(when (= s (:s %)) %) (get p k))]
                            :when pt]
                        {:label (str (t label) " · " (ui/date-short (:date pt)))
                         :value (str (js/Math.round (:watts pt)) " W")
                         :color (c/css-var color)})))))

(defn power-section
  "Power curve, FTP, W/kg and a VO2 max estimate from rides with a power meter.
  Nothing until there is one."
  [{:keys [data errors]} url]
  (when-let [p (:power (get data url))]
    (let [{:keys [ftp ftp_source wkg vo2max_est weight_kg]} p]
      [:div {:style {:display "contents"}}
       [:div.section-head {:replicant/key "power-head"}
        [:h2.section-title (t "Cycling power")]]
       (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
       [:div.row {:replicant/key "power-kpi"}
        (ui/tile {:label (t "FTP") :tint "amber" :size "md" :value (some-> ftp js/Math.round) :unit "W"
                  :sub (case ftp_source
                         "estimate" (t "estimated from your best 20–60 min, last 90 days")
                         "garmin"   (t "from Garmin")
                         nil)})
        (ui/tile {:label (t "Watts per kg") :tint "blue" :size "md" :value (some-> wkg (ui/fixed 2)) :unit "W/kg"
                  :sub (if weight_kg (t "at {0} kg" weight_kg) (t "no weight in your Garmin profile"))})
        (ui/tile {:label (t "VO2 max · cycling") :tint "green" :size "md" :value vo2max_est
                  :sub (t "estimate from your best 5 minutes, last 90 days")})]
       [:div {:replicant/key "power-curve"}
        (ui/chart-card
         {:title (t "Power curve") :tint "amber"
          :legend-items (for [[k label color] curves :when (seq (get p k))] {:label (t label) :color color})
          :chart (c/echart
                  {:key "power-curve" :class "h240"
                   :option-fn
                   (fn []
                     ;; the durations are about evenly spaced on a log scale: one category each
                     (let [ds (sort (distinct (mapcat #(map :s (get p %)) (map first curves))))]
                       (-> (c/base {:x-type "category" :y-min 0 :tooltip-fmt (power-tooltip p)
                                    :x-fmt #(dur-label (js/parseInt %))})
                           (assoc-in [:xAxis :data] (mapv str ds))
                           (assoc-in [:xAxis :boundaryGap] false)
                           (assoc-in [:grid :right] 24) ; room for the last label
                           (assoc :series (vec (for [[k label color] curves :when (seq (get p k))
                                                     :let [by (into {} (map (juxt :s :watts)) (get p k))]]
                                                 (c/line (t label) color (mapv by ds)
                                                         :lineStyle {:width (if (= k :curve_90d) 3 2)})))))))})
          :footer [:div.sub (t "Best average power for each duration. FTP ≈ 95 % of the best 20 minutes.")]})]])))
