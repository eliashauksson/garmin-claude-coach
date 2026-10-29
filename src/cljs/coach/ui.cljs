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
  [{:id :run   :label "Run"        :color "--series-1" :re #"running"}
   {:id :bike  :label "Bike"       :color "--series-2" :re #"cycling|biking|ride"}
   {:id :swim  :label "Swim"       :color "--series-3" :re #"swimming"}
   {:id :hike  :label "Hike/walk"  :color "--series-4" :re #"hiking|walking|mountaineering"}
   {:id :ski   :label "Ski/snow"   :color "--series-5" :re #"ski|snowboard"}
   {:id :other :label "Other"      :color "--series-6" :re #".*"}])

(defn family [sport]
  (some #(when (re-find (:re %) (str sport)) %) families))

;; ---------------------------------------------------------------------------
;; Components

(defn tile
  ([label value] (tile label value nil nil))
  ([label value unit sub & [{:keys [hero]}]]
   [:div.card.tile {:class (when hero "hero")}
    [:div.label label]
    [:div.value (or value "—") (when (and value unit) [:small unit])]
    (when sub [:div.sub sub])]))

(defn seg
  "Segmented control. `options` is [[value label] ...]; `action` gets the value appended."
  [current options action]
  [:div.seg
   (for [[v label] options]
     [:button {:replicant/key v
               :class (when (= v current) "on")
               :on {:click (conj action v)}}
      label])])

(defn legend
  "items: [{:label :color css-var :kind (:line :rect :band)}]"
  [items]
  [:div.legend
   (for [{:keys [label color kind]} items]
     [:span {:replicant/key label}
      [(case kind :rect :span.key-rect :band :span.key-band :span.key-line)
       {:style {:background (str "var(" color ")")}}]
      label])])

(defn table
  "columns: [{:label :value fn :align :r}]"
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
        (for [{:keys [label value align]} columns]
          [:td {:replicant/key label :class (when (= align :r) "r")} (str (or (value row) ""))])])]]])

(defn chart-card
  "A card with a title, an optional legend, the chart, and a Table toggle
  (every chart has a table-view twin)."
  [{:keys [id title legend-items chart table-columns table-rows tables-open]}]
  (let [open? (contains? tables-open id)]
    [:div.card
     [:div.card-head
      [:h2 title]
      [:span.spacer]
      (when table-columns
        [:button.linkish {:on {:click [[:toggle-table id]]}} (if open? "Chart" "Table")])]
     (when (and legend-items (not open?)) (legend legend-items))
     (if open?
       (table table-columns (reverse table-rows))
       chart)]))

(defn status-chip [level text]
  [:span.chip
   [:span.dot {:style {:background (case level
                                     (:ok "ok") "var(--status-good)"
                                     (:warn "warn") "var(--status-warning)"
                                     "var(--status-critical)")}}]
   text])
