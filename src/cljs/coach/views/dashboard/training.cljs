(ns coach.views.dashboard.training
  "Training: the KPIs, heatmap, weekly volume, load focus, ACWR, zones and recent activities."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [axis-date cat-base chart-card dash day-label empty-line]]))

(defn- delta-el [d unit]
  [:div.delta {:class (cond (nil? d) nil (pos? d) "up" (neg? d) "down" :else "flat")}
   (if (nil? d) "—" (str (ui/signed d) (when unit (str " " unit))))])

(defn- kpi [key label value unit delta & [info]]
  [:section.card.kpi {:replicant/key key}
   [:div.lab label (ui/info info)]
   [:div.big.md (dash value) (when (and value unit) [:small unit])]
   (delta-el delta unit)])

(defn- kpis [{:keys [period status]}]
  (let [{:keys [hours km load sessions hours_delta km_delta load_delta sessions_delta]} period]
    [:div.kpis {:replicant/key "kpis"}
     (kpi "hours" (t "Time") hours "h" hours_delta "period")
     (kpi "km" (t "Distance") km "km" km_delta "period")
     (kpi "load" (t "Training load") load nil load_delta "garmin-load")
     (kpi "sessions" (t "Sessions") sessions nil sessions_delta "period")
     [:section.card.kpi {:replicant/key "status"}
      [:div.lab (t "Training status") (ui/info "training-status")]
      [:div.status-phrase (dash (ui/enum (:status status)))]
      (when (:device status) [:div.sub (t "on your {0}" (:device status))])]]))

(def fam-order ["run" "bike" "swim" "hike" "ski" "strength" "other"])

(defn- fam [id] (some #(when (= (name (:id %)) id) %) ui/families))

(defn- mondays
  "The 26 Mondays ending with this week's."
  [today]
  (let [d (ui/parse-date today)
        dow (mod (+ 6 (.getDay d)) 7)]
    (.setDate d (- (.getDate d) dow (* 7 25)))
    (vec (for [i (range 26)]
           (let [x (js/Date. (.getTime d))]
             (.setDate x (+ (.getDate x) (* 7 i)))
             (.slice (.toISOString x) 0 10))))))

(defn- draw-heatmap!
  "Every day of the last 26 weeks as a square, Monday at the top."
  [cells today ^js node width first?]
  (let [by     (into {} (map (juxt :date identity)) cells)
        maxl   (max 1 (apply max 1 (keep :load cells)))
        cell   (max 7 (min 26 (- (js/Math.floor (/ (- width 30) 26)) 4)))
        gap    4
        top    18
        left   30
        weeks  (mondays today)
        h      (+ top (* 7 (+ cell gap)))
        rects  (for [[col monday] (map-indexed vector weeks)
                     row (range 7)
                     :let [date (ui/add-days monday row)]
                     :when (<= (compare date today) 0)
                     :let [{:keys [load family] :as day} (get by date)
                           active (and day (pos? (or load 0)))]]
                 (c/el :svg/rect
                       {:x (+ left (* col (+ cell gap))) :y (+ top (* row (+ cell gap)))
                        :width cell :height cell :rx (/ cell 4)
                        :class (str "hm-cell" (when first? " anim") (when (= date today) " today"))
                        :style (str (if active
                                      (str "fill: var(" (:color (fam family)) "); fill-opacity: "
                                           (+ 0.3 (* 0.7 (js/Math.sqrt (/ load maxl)))))
                                      "fill: var(--line)")
                                    (when first? (str "; animation-delay: " (* col 18) "ms")))
                        :data-date date}))
        month-labels (loop [[[col monday] & more] (map-indexed vector weeks) last-x -100 acc []]
                       (if-not monday
                         acc
                         (let [d (ui/parse-date monday) x (+ left (* col (+ cell gap)))]
                           ;; and room for the label before the right edge (else "Nov" is cut)
                           (if (and (<= (.getDate d) 7) (>= (- x last-x) 30)
                                    (<= (+ x 24) (+ left (* 26 (+ cell gap)))))
                             (recur more x (conj acc (c/el :svg/text {:x x :y 11 :class "hm-lab"} (ui/months (.getMonth d)))))
                             (recur more last-x acc)))))
        day-labels (for [[row i] [[0 1] [2 3] [4 5] [6 0]]]
                     (c/el :svg/text {:x 0 :y (+ top (* row (+ cell gap)) (* 0.75 cell)) :class "hm-lab"}
                           (ui/weekdays i)))
        svg (c/el :svg/svg {:width (+ left (* 26 (+ cell gap))) :height h :role "img"
                            :aria-label (t "Every day of the last 26 weeks")}
                  month-labels day-labels rects)]
    (.replaceChildren node svg)
    (set! (.-__tip node) nil)
    (set! (.-onmousemove svg)
          (fn [^js ev]
            (let [date (some-> ev .-target (.getAttribute "data-date"))
                  d    (get by date)]
              (c/tooltip! node ev
                          (when date
                            (if d
                              [(day-label date) (:names d)
                               (str (t "load {0}" (:load d)) " · " (ui/hm (* 60 (:minutes d))))]
                              [(day-label date) (t "Rest day")]))))))
    (set! (.-onmouseleave svg) #(c/tooltip! node % nil))))

(defn- heatmap-card [{:keys [heatmap date]}]
  (let [fams (filter (set (map :family heatmap)) fam-order)]
    (chart-card {:key "heat" :info "garmin-load" :wide? true :title (t "Every day of the last 26 weeks")
                 :text (t "One square a day, Monday at the top. Colour is the sport with the most load that day, and the stronger the colour, the bigger the load.")
                 :body (list
                        (ui/legend (for [f fams :let [x (fam f)]] {:label (t (:label x)) :color (:color x)}))
                        (c/dom-chart {:key "heatmap" :class "heatmap"
                                      :draw! (fn [node w first?] (draw-heatmap! heatmap date node w first?))}))})))

(defn- weekly-card [{:keys [weekly date]}]
  (let [weeks (mondays date)
        by    (group-by :week weekly)
        fams  (filter (set (map :family weekly)) fam-order)]
    (chart-card
     {:key "weekly" :info "garmin-load" :wide? true :title (t "Weekly training load by sport")
      :text (t "Garmin's training load per week, stacked by sport.")
      :body (if (empty? weekly)
              (empty-line)
              (list
               (ui/legend (for [f fams :let [x (fam f)]] {:label (t (:label x)) :color (:color x)}))
               (c/echart
                {:key "weekly-load" :class "h230"
                 :option-fn
                 (fn []
                   (-> (cat-base weeks
                                 {:y-min 0
                                  :tooltip-fmt (fn [params]
                                                 (let [wk (nth weeks (.-dataIndex (first params)))
                                                       rows (get by wk)]
                                                   (c/tooltip-rows
                                                    (t "Week of {0}" (axis-date wk))
                                                    (concat
                                                     [{:label (t "total load") :value (reduce + 0 (keep :load rows))}]
                                                     (for [f fams :let [r (some #(when (= f (:family %)) %) rows)] :when r]
                                                       {:label (t (:label (fam f))) :value (str (:load r) " · " (:hours r) " h")
                                                        :color (c/css-var (:color (fam f)))})))))})
                       (assoc-in [:tooltip :axisPointer] {:type "shadow" :shadowStyle {:color (c/alpha "--text-3" 0.12)}})
                       (merge {:series (c/stacked 5
                                                  (for [f fams :let [x (fam f)]]
                                                    (c/bars (t (:label x)) (:color x)
                                                            (vec (for [wk weeks] (:load (some #(when (= f (:family %)) %) (get by wk)))))
                                                            :stack "load" :barMaxWidth 22
                                                            :itemStyle {:color (c/css-var (:color x))})))})))})))})))

(defn- focus-card [{:keys [status]}]
  (let [{:keys [aer_low aer_low_min aer_low_max aer_high aer_high_min aer_high_max anaer anaer_min anaer_max balance]} status
        rows  [[(t "Low aerobic") "--blue" aer_low aer_low_min aer_low_max]
               [(t "High aerobic") "--amber" aer_high aer_high_min aer_high_max]
               [(t "Anaerobic") "--pink" anaer anaer_min anaer_max]]
        scale (* 1.08 (apply max 1 (keep identity [aer_low aer_low_max aer_high aer_high_max anaer anaer_max])))
        pct   #(str (* 100 (/ % scale)) "%")]
    (chart-card
     {:key "focus" :info "load-focus" :title (t "Load focus, last 4 weeks")
      :text (t "Your load in each intensity band against the range Garmin targets for you (shaded).")
      :body (if (every? nil? [aer_low aer_high anaer])
              (empty-line)
              (list
               [:div.focus {:replicant/key "rows"}
                (for [[label color v lo hi] rows]
                  [:div.focus-row {:replicant/key label}
                   [:div.focus-lab label]
                   [:div.focus-line
                    [:div.focus-track
                     (when (and lo hi)
                       [:div.focus-target {:style {:left (pct lo) :width (pct (- hi lo))
                                                   :background (c/alpha color 0.16)
                                                   :border-color (c/alpha color 0.5)}}])
                     (when v [:div.focus-val.grow-x {:style {:width (pct v) :background (str "var(" color ")")}}])]
                    [:span.focus-num {:class (when (and v lo (< v lo)) "below")} (dash v)]]])]
               (when balance [:div.sub {:replicant/key "bal"} (ui/enum balance)])))})))

(defn- acwr-card [{:keys [status]}]
  (let [{:keys [acute chronic chronic_min chronic_max acwr acwr_status]} status
        scale (* 1.15 (apply max 1 (keep identity [acute chronic chronic_max])))
        pct   #(str (* 100 (/ % scale)) "%")
        edge  #(let [f (/ % scale)] (cond (< f 0.1) "start" (> f 0.9) "end"))]
    (chart-card
     {:key "acwr" :info "acwr" :title (t "Acute vs chronic load")
      :text (t "The last 7 days' load (acute) against the 4-week load you are used to (chronic). The shaded band is the optimal range.")
      :body (if (nil? chronic)
              (empty-line)
              (list
               [:div.acwr {:replicant/key "bar"}
                (when acute
                  [:div.acwr-lab.above {:class (edge acute)
                                        :style {:left (pct acute)
                                                :color (when (= "HIGH" acwr_status) "var(--red)")}}
                   (t "Acute {0}" acute)])
                [:div.acwr-track
                 (when (and chronic_min chronic_max)
                   [:div.acwr-band {:style {:left (pct chronic_min) :width (pct (- chronic_max chronic_min))}}])
                 [:div.acwr-mark.chronic {:style {:left (pct chronic)}}]
                 (when acute
                   [:div.acwr-mark.acute {:style {:left (pct acute)
                                                  :background (if (= "HIGH" acwr_status) "var(--red)" "var(--text)")}}])]
                [:div.acwr-lab.below {:class (edge chronic) :style {:left (pct chronic)}} (t "Chronic {0}" chronic)]]
               [:div.sub {:replicant/key "foot"}
                (str/join " · " (remove nil? [(when acwr (t "Ratio {0}" (.toFixed acwr 1))) (ui/enum acwr_status)]))]))})))

(def zone-colors {"Z1" "--fam-other" "Z2" "--blue" "Z3" "--green" "Z4" "--amber" "Z5" "--red"})

(defn- zones-card [{:keys [zones]}]
  (let [total (reduce + 0 (keep :minutes zones))]
    (chart-card
     {:key "zones" :info "zones-28d" :title (t "Time in heart-rate zones")
      :text (t "Minutes in each zone over all activities of the last 28 days.")
      :body (if (zero? total)
              (empty-line)
              (list
               [:div.zone-bar {:replicant/key "bar" :role "img" :aria-label (t "Time in heart-rate zones")}
                (for [{:keys [zone minutes]} zones :when (pos? minutes)]
                  [:div {:replicant/key zone :style {:flex minutes :background (str "var(" (zone-colors zone) ")")}}])]
               [:div.zone-legend {:replicant/key "legend"}
                (for [{:keys [zone minutes]} zones]
                  [:div {:replicant/key zone}
                   [:div.zl [:span.dot {:style {:background (str "var(" (zone-colors zone) ")")}}] zone]
                   [:div.zv (ui/h-mm (* 60 minutes))]
                   [:div.sub (str (js/Math.round (* 100 (/ minutes total))) "%")]])]))})))

(defn- recent-card [{:keys [recent]}]
  (chart-card
   {:key "recent" :wide? true :title (t "Latest activities")
    :body (if (empty? recent)
            (empty-line)
            (ui/table [{:label (t "Date") :class "date" :value #(ui/date-short (:date %))}
                       {:label (t "Sport") :render #(ui/sport-chip (:sport %))}
                       {:label (t "Name") :class "name-wrap" :value :name}
                       {:label "km" :align :r :value #(some-> (:km %) (.toFixed 1))}
                       {:label (t "Time") :align :r :value #(ui/h-mm (:duration_s %))}
                       {:label (t "Avg HR") :align :r :value :avg_hr}
                       {:label (t "Load") :align :r :value :load}]
                      recent
                      {:key-fn :id
                       :on-row (fn [a] [[:navigate (str "#/activities/" (:id a))]])}))}))

(defn training [d]
  (list
   [:div.section-head {:replicant/key "tr-head"}
    [:h2.section-title (t "Training") [:span.section-note (t "Last 28 days, change from the 28 days before")]]]
   (kpis d)
   [:div.chart-grid {:replicant/key "tr"}
    (heatmap-card d)
    (weekly-card d)
    (focus-card d)
    (acwr-card d)
    (zones-card d)
    (recent-card d)]))
