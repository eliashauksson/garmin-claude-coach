(ns coach.views.load
  (:require [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

(def ranges [["90" (tk "90 days")] ["180" (tk "180 days")] ["365" (tk "1 year")] ["730" (tk "2 years")]
             ["all" (tk "All")]])

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
                       {:label (t "fatigue (ATL)") :value (:atl r) :color (c/css-var "--pink")}
                       {:label (t "form (TSB)") :value (:tsb r) :color (c/css-var "--amber")}
                       {:label (t "TSS that day") :value (:tss r)}]))))

(def forecast-url "/api/forecast")

;; coach.metrics/form-state, in words
(def state-texts
  {"overloaded"  [(tk "Overloaded") (tk "Fatigue is high: ease off before it costs you training.")]
   "building"    [(tk "Building") (tk "Fitness is growing; fatigue is the price. Keep recovery days easy.")]
   "maintaining" [(tk "Maintaining") (tk "Load and recovery are balanced.")]
   "recovering"  [(tk "Recovering") (tk "Rested, but fitness is slipping. Fine for a short break.")]
   "fresh"       [(tk "Fresh") (tk "Rested and ready for a race or a hard session.")]})

(defn- state-line [state]
  (when-let [[name sub] (state-texts state)]
    [:div.sub {:replicant/key "state"}
     [:b (t name)] " · " (t sub) (ui/info "form-state")]))

(defn- forecast-card
  "The last 4 weeks and, dashed, the plan's load to race day: fitness and form
  as the season plan would leave them."
  [pmc {:keys [forecast race_date]}]
  (when (seq forecast)
    (let [past   (vec (take-last 28 pmc))
          anchor (last past)
          rows   (into past forecast)
          by-day (into {} (map (juxt :date identity)) rows)
          series (fn [k] (mapv (juxt :date k) forecast))
          from   (fn [k] (into (if anchor [[(:date anchor) (get anchor k)]] []) (series k)))
          race   (or (by-day race_date) (last forecast))]
      (ui/chart-card
       {:title (t "Form on the way to race day") :info "form-state"
        :legend-items [{:label (t "Fitness (CTL)") :color "--blue"} {:label (t "Form (TSB)") :color "--amber"}]
        :chart (c/echart
                {:key "forecast" :class "h220"
                 :option-fn (fn []
                              (merge (c/base {:tooltip-fmt
                                              (fn [params]
                                                (let [r (by-day (aget (.-value (first params)) 0))]
                                                  (c/tooltip-rows (ui/date-long (:date r))
                                                                  [{:label (t "fitness (CTL)") :value (:ctl r)
                                                                    :color (c/css-var "--blue")}
                                                                   {:label (t "form (TSB)") :value (:tsb r)
                                                                    :color (c/css-var "--amber")}])))})
                                     {:series [(c/line (t "Fitness (CTL)") "--blue" (mapv (juxt :date :ctl) past)
                                                       :lineStyle {:width 3})
                                               (c/line (t "Fitness (CTL)") "--blue" (from :ctl)
                                                       :lineStyle {:width 3 :type "dashed"})
                                               (c/line (t "Form (TSB)") "--amber" (mapv (juxt :date :tsb) past)
                                                       :lineStyle {:width 2})
                                               (-> (c/line (t "Form (TSB)") "--amber" (from :tsb)
                                                           :lineStyle {:width 2 :type "dashed"})
                                                   (assoc :markLine
                                                          {:silent true :symbol "none"
                                                           :label {:show false}
                                                           :lineStyle {:color (c/css-var "--text-3")
                                                                       :opacity 0.5 :width 1}
                                                           :data (cond-> [{:yAxis 0}]
                                                                   race_date (conj {:xAxis race_date}))}))]}))})
        :footer [:div.sub (when race
                            (t "Race day: fitness {0}, form {1} if you train the plan as written."
                               (:ctl race) (:tsb race)))]}))))

(defn urls
  "Load and volume for the range chosen in the route (?range=, default 180 days)."
  [route]
  (let [r    (get-in route [:params :range] "180")
        from (if (= r "all") "all" (ui/days-ago (js/parseInt r)))]
    [(str "/api/load?from=" from) (str "/api/volume?from=" from)]))

(defn section
  "The training load, as a section of the Trends page."
  [{:keys [data errors route] ui-state :ui} [load-url volume-url forecast-url]]
  (let [pmc    (get data load-url)
        fc     (when forecast-url (get data forecast-url))
        vol    (get data volume-url)
        range  (get-in route [:params :range] "180")
        vm-key (get ui-state :volume-metric "hours")
        vm     (volume-metrics vm-key)
        latest (last pmc)
        weeks  (weekly-by-family vol (:key vm) (:scale vm))
        fams   (filter (fn [f] (some #(get % (:id f)) (vals weeks))) ui/families)
        any?   (some #(pos? (:tss %)) pmc)]
    [:div {:style {:display "contents"}}
     [:div.section-head {:replicant/key "load-head" :id "tr-load"}
      [:h2.section-title (t "Training load")]
      (ui/seg range ranges [:set-param :range])]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (t "Could not load: {0}" e)])) [load-url volume-url])
     (when (and pmc (not any?))
       (ui/card {} [:div.empty (t "No training data yet — run Sync.")]))
     (when (and pmc any?)
       (list
        (state-line (:state fc))
        [:div.row {:replicant/key "kpi"}
         (ui/tile {:info "ctl" :label (t "Fitness · CTL") :tint "blue" :size "md" :value (:ctl latest)
                   :sub (t "42-day load")})
         (ui/tile {:info "atl" :label (t "Fatigue · ATL") :tint "pink" :size "md" :value (:atl latest)
                   :sub (t "7-day load")})
         (ui/tile {:info "tsb" :label (t "Form · TSB") :tint "amber" :size "md" :value (:tsb latest)
                   :sub (t "fitness − fatigue")})
         (ui/tile {:info "ramp" :label (t "Ramp rate") :tint "purple" :size "md" :value (:ramp latest)
                   :sub (t "CTL change / 7 days")})]
        [:div {:replicant/key "pmc"}
         (ui/chart-card
          {:title (t "Fitness, fatigue and form") :info "tsb"
           :legend-items [{:label (t "Fitness (CTL)") :color "--blue"} {:label (t "Fatigue (ATL)") :color "--pink"}
                          {:label (t "Form (TSB)") :color "--amber"}]
           :chart (c/echart
                   {:key "pmc" :class "h280" :group "load"
                    :option-fn (fn []
                                 ;; one axis for all three: TSB dips below 0, so no y-min
                                 (merge (c/base {:tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(-> (c/line (t "Fitness (CTL)") "--blue" (mapv (juxt :date :ctl) pmc)
                                                              :lineStyle {:width 3})
                                                      (c/area-wash 0.18))
                                                  (c/line (t "Fatigue (ATL)") "--pink" (mapv (juxt :date :atl) pmc)
                                                          :lineStyle {:width 2.5})
                                                  (-> (c/line (t "Form (TSB)") "--amber"
                                                              (mapv (juxt :date :tsb) pmc)
                                                              :lineStyle {:width 2})
                                                      (assoc :markLine {:silent true :symbol "none" :label {:show false}
                                                                        :lineStyle {:color (c/css-var "--text-3")
                                                                                    :opacity 0.5
                                                                                    :type "solid" :width 1}
                                                                        :data [{:yAxis 0}]}))]}))})
           :footer [:div.sub (t "Above 0: fresh. −10 to −30: building fitness. Below −30: high strain.")]})]
        (when fc [:div {:replicant/key "forecast"} (forecast-card pmc fc)])
        [:div.row {:replicant/key "r2"}
         (ui/chart-card
          {:title (t "Daily stress · TSS") :info "tss" :tint "amber" :flex "1 1 100%"
           :chart (c/echart
                   {:key "tss-bars" :class "h150" :group "load"
                    :option-fn (fn []
                                 (merge (c/base {:y-min 0 :tooltip-fmt (pmc-tooltip pmc)})
                                        {:series [(c/bars "TSS" "--amber"
                                                          (mapv (juxt :date #(when (pos? (:tss %)) (:tss %))) pmc)
                                                          :barMaxWidth 14
                                                          :itemStyle {:color (c/css-var "--amber")
                                                                      :borderRadius 5})]}))})})]
        [:div {:replicant/key "vol"}
         (ui/chart-card
          {:title (t "Weekly volume") :info "volume"
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
                                                          (for [f (reverse fams) :let [v (get row (:id f))]
                                                                :when (and v (pos? v))]
                                                            {:label (t (:label f)) :value ((:fmt vm) v)
                                                             :color (c/css-var (:color f))}))))})
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
    (let [{:keys [ftp ftp_source wkg vo2max_est weight_kg weight_date]} p]
      [:div {:style {:display "contents"}}
       [:div.section-head {:replicant/key "power-head"}
        [:h2.section-title (t "Cycling power")]]
       (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
       [:div.row {:replicant/key "power-kpi"}
        (when ftp
          (ui/tile {:info "ftp" :label (t "FTP") :tint "amber" :size "md" :value (js/Math.round ftp) :unit "W"
                    :sub (case ftp_source
                           "estimate" (t "estimated from your best 20–60 min, last 90 days")
                           "garmin"   (t "from Garmin")
                           nil)}))
        (when wkg
          (ui/tile {:info "wkg" :label (t "Watts per kg") :tint "blue" :size "md" :value (ui/fixed wkg 2)
                    :unit "W/kg"
                    :sub (if weight_date
                           (t "at {0} kg, weighed {1}" (ui/fixed weight_kg 1) (ui/date-short weight_date))
                           (t "at {0} kg" weight_kg))}))
        (when vo2max_est
          (ui/tile {:info "vo2max-cycling" :label (t "VO2 max · cycling") :tint "green" :size "md"
                    :value vo2max_est :sub (t "estimate from your best 5 minutes, last 90 days")}))]
       [:div {:replicant/key "power-curve"}
        (ui/chart-card
         {:title (t "Power curve") :info "power-curve" :tint "amber"
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
