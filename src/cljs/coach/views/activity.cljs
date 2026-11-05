(ns coach.views.activity
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.feedback :as feedback]
            [coach.views.structure :as st]
            [coach.workout :as w]))

(defn- rolling
  "Centered moving average over `w` samples (nils skipped), to calm GPS speed."
  [xs w]
  (let [v (vec xs) n (count v) h (quot w 2)]
    (mapv (fn [i]
            (let [win (keep v (range (max 0 (- i h)) (min n (+ i h 1))))]
              (when (and (v i) (seq win)) (/ (reduce + win) (count win)))))
          (range n))))

(defn- series-data [ts ys]
  (into [] (keep (fn [[x y]] (when (some? y) [x y]))) (map vector ts ys)))

(defn- percentile-bounds
  "[p2 p98] of the y values, padded 5%, so GPS spikes don't stretch the axis."
  [data]
  (let [ys (vec (sort (map second data))) n (count ys)]
    (when (> n 20)
      (let [lo (ys (js/Math.floor (* 0.02 n))) hi (ys (js/Math.floor (* 0.98 n))) pad (* 0.05 (- hi lo))]
        [(- lo pad) (+ hi pad)]))))

(defn- elapsed-fmt [s] (let [m (js/Math.round (/ s 60))] (str (quot m 60) ":" (.padStart (str (mod m 60)) 2 "0"))))

(defn- one-decimal
  "Axis label: at most one decimal (3.4, 5)."
  [v]
  (str (/ (js/Math.round (* v 10)) 10)))

(defn- stream-chart [{:keys [key title data y-fmt y-inverse unit color area? tip clip?]}]
  (let [[lo hi] (when clip? (percentile-bounds data))]
  (ui/card {:flex "1 1 420px"}
   [:div.lab title]
   (c/echart
    {:key key :class "short" :group "activity"
     :option-fn
     (fn []
       (-> (merge (c/base {:x-type "value" :x-fmt elapsed-fmt :y-fmt (or y-fmt one-decimal) :y-inverse y-inverse
                       ;; clipped to the data's percentiles: rounded outward so the end labels stay short
                       :y-min (some-> lo (* 10) js/Math.floor (/ 10))
                       :y-max (some-> hi (* 10) js/Math.ceil (/ 10))
                       :tooltip-fmt (fn [params]
                                      (let [p (first params) [secs y] (js->clj (.-value p))]
                                        (c/tooltip-rows (t "at {0}" (elapsed-fmt secs))
                                                        [{:label unit :value (tip y)
                                                          :color (c/css-var color)}])))})
              {:series [(cond-> (c/line title color data) area? c/area-wash)]})
              ;; the clipped ends fall between ticks: no label there
              (cond-> clip? (update-in [:yAxis :axisLabel] assoc :showMinLabel false :showMaxLabel false))))}))))

(defn- zones-chart [zones]
  (let [labels [(t "Below Z1") "Z1" "Z2" "Z3" "Z4" "Z5"]
        ramp   [["--text-3" 0.3] ["--blue" 0.25] ["--blue" 0.4] ["--blue" 0.55] ["--blue" 0.7] ["--blue" 0.85]]
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
                           :axisLabel {:color (c/css-var "--text-2") :fontFamily c/font}})
            (assoc-in [:xAxis :axisLabel :formatter] (fn [v] (str (js/Math.round (/ v 60)) "m")))
            (assoc :grid {:left 64 :right 90 :top 6 :bottom 24})
            (assoc :series [{:type "bar" :barMaxWidth 16
                             :data (for [[_ s color] rows]
                                     {:value s :itemStyle {:color (apply c/alpha color) :borderRadius [0 4 4 0]}})
                             :label {:show true :position "right" :color (c/css-var "--text-2") :fontFamily c/font
                                     :formatter (fn [p] (str (if (< (.-value p) 60) "<1m" (ui/hm (.-value p))) " · "
                                                             (js/Math.round (* 100 (/ (.-value p) (max 1 total)))) "%"))}}])))})))

;; ---------------------------------------------------------------------------
;; The planned session it fulfilled: planned vs actual per block

(def ^:private target-titles {"hr" (tk "Heart rate vs plan") "pace" (tk "Pace vs plan") "power" (tk "Power vs plan")})

(defn- target-data
  "[t value] pairs of the stream for a target type, smoothed like the
  stream charts (pace from 15 s speed, power over 5 s)."
  [s target]
  (case target
    "hr"    (series-data (:t s) (:hr s))
    "pace"  (series-data (:t s) (map #(when (and % (> % 1.0)) (/ 1000 %)) (rolling (:speed s) 15)))
    "power" (series-data (:t s) (rolling (:power s) 5))
    []))

(defn- goal-chart
  "The actual stream with a goal bar per block of this target type: the
  block's time across, its target range up and down."
  [target segments s]
  (let [color (st/target-colors target)
        segs  (filter #(and (= target (:target %)) (:low %)) segments)
        data  (target-data s target)
        [lo hi] (percentile-bounds data)
        lo    (apply min (remove nil? (cons lo (map :low segs))))
        hi    (apply max (remove nil? (cons hi (map :high segs))))
        pace? (= "pace" target)
        fmt   (if pace? w/pace-str #(str (js/Math.round %)))]
    (ui/card {:flex "1 1 420px"}
     [:div.lab (t (target-titles target)) (ui/info "planned-vs-actual")]
     (c/echart
      {:key (str "goal-" target) :class "short" :group "activity"
       :option-fn
       (fn []
         (-> (c/base {:x-type "value" :x-fmt elapsed-fmt :y-fmt fmt :y-inverse pace?
                      :y-min (js/Math.floor (- lo (* 0.05 (- hi lo)))) :y-max (js/Math.ceil (+ hi (* 0.05 (- hi lo))))
                      :tooltip-fmt (fn [params]
                                     (let [p (first params) [secs y] (js->clj (.-value p))
                                           seg (some #(when (and (<= (:start %) secs) (< secs (:end %))) %) segments)]
                                       (c/tooltip-rows (t "at {0}" (elapsed-fmt secs))
                                                       [{:label (case target "hr" "bpm" "pace" "/km" "power" "W")
                                                         :value (fmt y) :color (c/css-var color)}
                                                        (when seg
                                                          {:label (t "plan") :value (or (st/target-str seg) (st/kind-label (:kind seg)))})])))})
             (update-in [:yAxis :axisLabel] assoc :showMinLabel false :showMaxLabel false)
             (assoc :series [(assoc (c/line (t (target-titles target)) color data)
                                    :markArea {:silent true
                                               :itemStyle {:color (c/alpha color 0.18)}
                                               :data (vec (for [g segs]
                                                            [{:xAxis (:start g) :yAxis (:low g)}
                                                             {:xAxis (:end g) :yAxis (:high g)}]))}
                                    :markLine {:silent true :symbol "none" :label {:show false}
                                               :lineStyle {:color (c/css-var "--text-3") :opacity 0.25 :type "solid" :width 1}
                                               :data (vec (for [g (rest segments)] {:xAxis (:start g)}))})])))}))))

(defn- planned-section
  "The planned session (goal, profile) and, with a stream, each block's
  target against what was done."
  [p s]
  (let [blocks   (:blocks p)
        segments (w/expand blocks)
        compared (when (seq (:t s)) (w/compare-segments segments s))]
    (list
     [:div {:replicant/key "planned"}
      (ui/card {}
               [:div.lab (t "Planned session")]
               [:div [:a.plain {:href (str "#/workouts/" (:id p))} [:b (:title p)]]
                (when-not (str/blank? (:goal p)) [:div.sub (:goal p)])]
               (when (seq blocks) (st/profile blocks {:height "28px"})))]
     (when (and (seq blocks) (seq (:t s)))
       (into [:div.row {:replicant/key "goal-charts"}]
             (for [target (w/targets blocks)
                   :when (seq (target-data s target))]
               (goal-chart target segments s))))
     (when (seq compared)
       [:div {:replicant/key "goal-table"}
        (ui/card {}
                 [:div.lab (t "Planned vs actual") (ui/info "planned-vs-actual")]
                 (ui/table [{:label (t "Step") :render (fn [g] [:span [:span.wk-dot {:class (:kind g)}]
                                                                (str (:label g) (when (> (:reps g) 1) (str " " (:rep g) "/" (:reps g)))
                                                                     ;; the step's kind, when the block has several
                                                                     (when (< 1 (count (get-in blocks [(:block g) :steps])))
                                                                       (str " · " (st/kind-label (:kind g)))))])}
                            {:label (t "Time") :value #(str (st/clock (:start %)) "–" (st/clock (:end %)))}
                            {:label (t "Target") :value #(or (st/target-str %) "—")}
                            {:label (t "Actual") :value #(or (st/value-str (:target %) (:actual %)) "—") :align :r}
                            {:label "" :render #(or (st/verdict-pill %) "")}]
                           compared))]))))

(def peak-labels {:5 "5 s" :60 "1 min" :300 "5 min" :1200 "20 min" :3600 "60 min"})

(def tints (cycle ["blue" "purple" "green" "amber" "red"]))

(defn- tile [label value unit sub & [info]]
  {:label label :value value :unit unit :sub sub :info info})

(defn- tile-row [a run?]
  (let [secs (or (:moving_s a) (:duration_s a))
        mps  (when (and (:distance_m a) secs (pos? secs)) (/ (:distance_m a) secs))
        tiles (remove nil?
     [(tile (t "Distance") (ui/km (:distance_m a)) "km" nil)
     (tile (t "Moving time") (ui/hms secs) nil (t "elapsed {0}" (ui/hms (:duration_s a))) "moving-time")
     (if run?
       (tile (t "Avg pace") (ui/pace mps) "/km" (some->> (:ngp_mps a) ui/pace (str "NGP ")) (when (:ngp_mps a) "ngp"))
       (tile (t "Avg speed") (ui/kmh mps) "km/h" nil))
     (tile (t "Elevation gain") (some-> (:elev_gain_m a) js/Math.round) "m" nil)
     (tile (t "Heart rate") (some-> (:avg_hr a) js/Math.round) "bpm"
           (not-empty (str/join " · " (remove nil? [(some->> (:max_hr a) js/Math.round (t "max {0}"))
                                                    (when (= "watch" (:hr_source a)) (t "from the watch's all-day heart rate"))]))))
     (tile (t "Training stress") (some-> (:tss a) js/Math.round) (:tss_method a)
              (str/join " · " (remove nil? [(some->> (ui/fixed (:intensity a) 2) (str "IF "))
                                            (some-> (:tss_per_hour a) js/Math.round (str " TSS/h"))]))
              "tss")
     (when (:np a) (tile (t "Normalized power") (js/Math.round (:np a)) "W"
                            (str/join " · " (remove nil? [(some->> (:vi a) (str "VI ")) (some->> (:kj a) (str "kJ "))]))
                            "np"))
     (tile (t "Efficiency factor") (ui/fixed (:ef a) 2) nil
              (some->> (:decoupling a) (t "decoupling {0}%")) "ef")
     (if (or (:garmin_load a) (not (:load_est a)))
       (tile (t "Garmin load") (some-> (:garmin_load a) js/Math.round) nil
             (some->> (ui/fixed (:aerobic_te a) 1) (t "aerobic TE {0}")) "garmin-load")
       ;; a ride Garmin never saw: the app's own estimate (coach.epoc)
       (tile (t "Training load (estimate)") (js/Math.round (:load_est a)) nil
             (case (:load_method a)
               "hr+power" (t "from heart rate and power")
               "power"    (t "from power")
               "hr-watch" (t "from the watch's all-day heart rate")
               (t "from heart rate"))
             "load-estimate"))])]
    (into [:div.row]
          (map (fn [tl tint] (ui/tile (assoc tl :tint tint :size "sm" :flex "1 1 140px"))) tiles tints))))

(defn view [{:keys [data errors] ui-state :ui} url]
  (let [a    (get data url)
        s    (:streams a)
        run? (= :run (:id (ui/family (:sport a))))
        ts   (:t s)
        lat  (:lat s) lon (:lon s)
        pts  (when (and (seq lat) (seq lon))
               (let [pts (keep (fn [[la lo]] (when (and la lo) [la lo])) (map vector lat lon))
                     step (max 1 (quot (count pts) 3000))]
                 (vec (take-nth step pts))))
        speed (some-> (:speed s) (rolling 15))]
    [:div {:style {:display "contents"}}
     [:div [:button.link-back {:on {:click [[:back]]}} (t "← Back")]]
     (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
     (when a
       (list
        [:div {:replicant/key "head"}
         [:h1 (:name a)]
         [:div.sub (str (ui/sport-label (:sport a)) " · " (ui/date-long (:date a))
                              (some->> (ui/time-of (:start_time_local a)) (str " · ")))]]
        [:div {:replicant/key "tiles"} (tile-row a run?)]
        (feedback/activity-card ui-state a)
        (when-let [p (:planned a)] (planned-section p s))
        (when (seq pts)
          [:div {:replicant/key "map"}
           (ui/card {} (c/route-map {:key (str "map-" (:id a)) :latlngs pts
                                     :family (:id (ui/family (:sport a)))}))])
        (when (seq ts)
          [:div.row {:replicant/key "streams"}
           (when (some some? (:hr s))
             (stream-chart {:key "hr" :title (t "Heart rate") :unit "bpm" :color "--red"
                            :data (series-data ts (:hr s)) :tip #(str (js/Math.round %))}))
           (when (some some? speed)
             (if run?
               (stream-chart {:key "pace" :title (t "Pace (15 s smoothed)") :unit "/km" :color "--blue"
                              :y-inverse true :clip? true :y-fmt #(ui/hms %) :tip #(ui/hms %)
                              :data (series-data ts (map #(when (and % (> % 1.0)) (/ 1000 %)) speed))})
               (stream-chart {:key "speed" :title (t "Speed (15 s smoothed)") :unit "km/h" :color "--blue"
                              :clip? true :tip #(ui/fixed % 1) :data (series-data ts (map #(some-> % (* 3.6)) speed))})))
           (when (some some? (:alt s))
             (stream-chart {:key "alt" :title (t "Elevation") :unit "m" :color "--green" :area? true
                            :tip #(str (js/Math.round %)) :data (series-data ts (:alt s))}))
           (when (some some? (:power s))
             (stream-chart {:key "power" :title (t "Power") :unit "W" :color "--amber"
                            :tip #(str (js/Math.round %)) :data (series-data ts (:power s))}))])
        [:div.row {:replicant/key "zones"}
         (when (seq (:hr_zones_s a))
           (ui/card {:flex "1 1 420px"} [:div.lab (t "Time in heart-rate zones") (ui/info "zones-28d")] (zones-chart (:hr_zones_s a))))
         (when (seq (:peaks a))
           (let [pk (:peaks a)]
             (ui/card {:flex "1 1 420px"}
              [:div.lab (t "Peak values") (ui/info "peaks")]
              (ui/table [{:label (t "Duration") :value #(peak-labels %)}
                         {:label (t "Heart rate") :value #(some-> (get-in pk [:hr %]) js/Math.round) :align :r}
                         {:label (if run? (t "Pace") (t "Speed")) :value #(when-let [v (get-in pk [:speed %])]
                                                                            (if run? (str (ui/pace v) " /km") (str (ui/kmh v) " km/h")))
                          :align :r}
                         {:label (t "Power") :value #(some-> (get-in pk [:power %]) js/Math.round) :align :r}]
                        [:5 :60 :300 :1200 :3600]))))]
        (when-let [th (:thresholds a)]
          [:div.sub {:replicant/key "th"}
           (str (t "Scored with LTHR {0} bpm" (some-> (:lthr th) js/Math.round))
                (some->> (:threshold_speed th) ui/pace (t ", threshold pace {0} /km"))
                (when-let [ftp (some-> (:ftp th) js/Math.round)]
                  (if (= "estimate" (:ftp_source th)) (t ", estimated FTP {0}" ftp) (t ", FTP {0}" ftp)))
                (t ", resting HR {0}." (:rest_hr th)))
           " " (ui/info "lthr")])))]))
