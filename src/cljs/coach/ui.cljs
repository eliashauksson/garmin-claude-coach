(ns coach.ui
  "Formatting helpers and small shared components."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Formatting

(defn num? [x] (and (number? x) (not (js/isNaN x))))

(defn fixed [x digits] (when (num? x) (.toFixed x digits)))

(defn thousands [x]
  (when (num? x) (.toLocaleString (js/Math.round x) "en-US")))

(defn hms
  "Seconds -> h:mm:ss (or m:ss under an hour)."
  [s]
  (when (num? s)
    (let [s (js/Math.round s) h (quot s 3600) m (quot (mod s 3600) 60) sec (mod s 60)
          pad #(if (< % 10) (str "0" %) (str %))]
      (if (pos? h) (str h ":" (pad m) ":" (pad sec)) (str m ":" (pad sec))))))

(defn hm
  "Seconds -> 6h 32m."
  [s]
  (when (num? s)
    (let [m (js/Math.round (/ s 60)) h (quot m 60)]
      (if (pos? h) (str h "h " (mod m 60) "m") (str m "m")))))

(defn km [m] (when (num? m) (fixed (/ m 1000) (if (< m 10000) 2 1))))

(defn pace
  "m/s -> m:ss per km."
  [mps]
  (when (and (num? mps) (> mps 0.3))
    (hms (/ 1000 mps))))

(defn kmh [mps] (when (num? mps) (fixed (* mps 3.6) 1)))

(def months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])
(def weekdays ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(defn parse-date [iso] (js/Date. (str (subs iso 0 10) "T12:00:00")))

(defn date-long [iso]
  (when (string? iso)
    (let [d (parse-date iso)]
      (str (weekdays (.getDay d)) " " (.getDate d) " " (months (.getMonth d)) " " (.getFullYear d)))))

(defn date-short [iso]
  (when (string? iso)
    (let [d (parse-date iso)]
      (str (.getDate d) " " (months (.getMonth d)) " " (.getFullYear d)))))

(defn time-of [iso] (when (and (string? iso) (> (count iso) 15)) (subs iso 11 16)))

(defn iso-today [] (let [d (js/Date.)] (.slice (.toISOString (js/Date. (- (.getTime d) (* 60000 (.getTimezoneOffset d))))) 0 10)))

(defn days-ago [n]
  (let [d (parse-date (iso-today))]
    (.setDate d (- (.getDate d) n))
    (.slice (.toISOString d) 0 10)))

(defn esc
  "Escape text for the (HTML) chart tooltips."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn sport-label [s]
  (when s (str/capitalize (str/replace s "_" " "))))

;; Sport families: a fixed colour per family, assigned by entity, never by rank.
(def families
  [{:id :run   :label "Run"       :plural "runs"   :color "--fam-run"   :re #"running"}
   {:id :bike  :label "Bike"      :plural "rides"  :color "--fam-bike"  :re #"cycling|biking|ride"}
   {:id :swim  :label "Swim"      :plural "swims"  :color "--fam-swim"  :re #"swimming"}
   {:id :hike  :label "Hike/walk" :plural "hikes"  :color "--fam-hike"  :re #"hiking|walking|mountaineering"}
   {:id :ski   :label "Ski/snow"  :plural "ski days" :color "--fam-ski" :re #"ski|snowboard"}
   {:id :other :label "Other"     :plural "other"  :color "--fam-other" :re #".*"}])

(def singular {:run "run" :bike "ride" :swim "swim" :hike "hike" :ski "ski day" :other "other"})

(defn family [sport]
  (some #(when (re-find (:re %) (str sport)) %) families))

;; Session library names (training-principles.md §4, athlete.md §A6)
(def session-names
  {"EASY" "Easy run" "EASY_ST" "Easy + strides" "REC" "Recovery run" "HILL_ST" "Hill strides"
   "HILL_2" "Hill repeats" "STEADY" "Steady block" "THR" "Threshold" "MP_BLOCK" "Marathon pace"
   "LONG" "Long run" "LONG_MP" "Long run, MP finish" "DOUBLE" "Double day" "B2B" "Back-to-back"
   "TRIPLE_MINI" "Mini-triple" "TRIPLE_SIM" "Full simulation" "TT5" "5 km time trial"
   "GYM_LOWER" "Gym: lower body" "GYM_UPPER" "Gym: upper body" "GYM_BACK_SL" "Gym: back + single-leg"
   "CALF_MIN" "Minimal strength" "REST" "Rest"})

;; ---------------------------------------------------------------------------
;; Icons (inline SVG, 24x24 stroke icons from the mockup)

(def icon-paths
  {:bolt       [[:path {:d "M13 2 3 14h9l-1 8 10-12h-9l1-8z"}]]
   :battery    [[:rect {:x 2 :y 7 :width 18 :height 10 :rx 2}] [:path {:d "M22 11v2"}]]
   :moon       [[:path {:d "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"}]]
   :sun        [[:circle {:cx 12 :cy 12 :r 4}]
                [:path {:d "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"}]]
   :heart      [[:path {:d "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z"}]]
   :pulse      [[:path {:d "M3 12h4l3-8 4 16 3-8h4"}]]
   :footprints [[:path {:d "M4 16v-2.4C4 11.5 3 10.5 3 8c0-2.7 1.5-6 4.5-6C9.4 2 10 3.8 10 5.5c0 3.1-2 5.7-2 8.7V16a2 2 0 1 1-4 0z"}]
                [:path {:d "M20 20v-2.4c0-2.1 1-3.1 1-5.6 0-2.7-1.5-6-4.5-6-1.9 0-2.5 1.8-2.5 3.5 0 3.1 2 5.7 2 8.7V20a2 2 0 1 0 4 0z"}]]
   :mountain   [[:path {:d "m8 3 4 8 5-5 5 15H2L8 3z"}]]})

(defn icon-chip
  "28px chip. Filled (accent background, white icon) on tinted cards; on white
  cards use :soft? true (tint background, accent icon)."
  [icon accent tint & [{:keys [soft?]}]]
  [:span.ic {:style {:background (str "var(" (if soft? tint accent) ")")}}
   (into [:svg {:aria-hidden "true" :width 16 :height 16 :viewBox "0 0 24 24" :fill "none"
                :stroke (if soft? (str "var(" accent ")") "#fff") :stroke-width 2.4
                :stroke-linecap "round" :stroke-linejoin "round"}]
         (icon-paths icon))])

;; ---------------------------------------------------------------------------
;; Components

(defn pill
  ([text] (pill nil text))
  ([dot-color text]
   [:span.pill (when dot-color
                 [:span.dot {:style {:background (if (.startsWith dot-color "--") (str "var(" dot-color ")") dot-color)}}])
    text]))

(defn pills [& items] (into [:div.pills] (remove nil? items)))

(defn label-row
  "Icon chip, label, spacer, then optional right-hand controls."
  [{:keys [icon accent tint soft? label right]}]
  [:div.lab
   (when icon (icon-chip icon accent tint {:soft? soft?}))
   label
   [:span.sp]
   right])

(defn card
  "A bento card. :tint is a token suffix (\"green\", \"blue\"…) or nil for white."
  [{:keys [tint flex class] :as opts} & children]
  (into [:section.card {:class (remove nil? [(when tint (str "tint-" tint)) class])
                        :style (when flex {:flex flex})}]
        children))

(defn tile
  "KPI card: label, big number (with optional unit) and a sub line."
  [{:keys [label value unit sub tint flex size]}]
  (card {:tint tint :flex (or flex "1 1 200px")}
        [:div.lab label]
        [:div.big {:class size} (or value "—") (when (and value unit) [:small unit])]
        (when sub [:div.sub sub])))

(defn seg
  "Segmented control. `options` is [[value label] ...]; `action` gets the value appended."
  [current options action & [{:keys [soft?]}]]
  [:div.seg {:class (when soft? "soft")}
   (for [[v label] options]
     [:button {:replicant/key v
               :class (when (= v current) "on")
               :on {:click [(conj action v)]}}
      label])])

(defn legend
  "Legend as pills with a colour dot. items: [{:label :color css-var}]"
  [items]
  (into [:div.pills] (for [{:keys [label color]} items] (pill color label))))

(defn table
  "columns: [{:label :value fn :align :r :class str :title fn}]"
  [columns rows & [{:keys [on-row key-fn]}]]
  [:div.table-wrap
   [:table
    [:thead [:tr (for [{:keys [label align]} columns]
                   [:th {:replicant/key label :class (when (= align :r) "r")} label])]]
    [:tbody
     (for [[i row] (map-indexed vector rows)]
       [:tr {:replicant/key (if key-fn (key-fn row) i)
             :class (when on-row "click")
             :on (when on-row {:click (on-row row)})}
        (for [{:keys [label value align class title render]} columns]
          [:td {:replicant/key label
                :class (remove nil? [(when (= align :r) "r") class])
                :title (when title (title row))}
           (if render (render row) (str (or (value row) "")))])])]]])

(defn chart-card
  "A bento card with a label row (icon, title, optional controls), optional
  legend pills, the chart and a footer."
  [{:keys [title icon accent tint soft? flex legend-items controls header chart footer]}]
  (card {:tint tint :flex flex}
        (label-row {:icon icon :accent accent :tint (str "--tint-" (or tint "blue")) :soft? soft?
                    :label title :right controls})
        header
        (when legend-items (legend legend-items))
        chart
        footer))

(defn sport-chip [sport]
  (let [f (family sport)]
    [:span.chip {:class (name (:id f))} [:span.dot] (sport-label sport)]))

(defn status-pill [level text]
  [:span.pill {:style {:background "var(--surface)"}}
   [:span.dot {:style {:background (case level
                                     (:ok "ok") "var(--green)"
                                     (:warn "warn") "var(--amber)"
                                     "var(--red)")}}]
   text])
