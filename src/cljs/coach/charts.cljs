(ns coach.charts
  "ECharts and Leaflet, mounted through Replicant's :replicant/on-render hook.
  Options are built at render time from the CSS tokens, so light/dark both work."
  (:require ["echarts" :as echarts]
            ["leaflet" :as L]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]))

(defn css-var [n]
  (.trim (.getPropertyValue (js/getComputedStyle js/document.documentElement) n)))

(def font "Plus Jakarta Sans, system-ui, sans-serif")

(defn alpha
  "A CSS colour token at the given opacity (hex tokens only)."
  [var-name a]
  (let [hex (css-var var-name)]
    (if (re-matches #"#[0-9a-fA-F]{6}" hex)
      (let [n #(js/parseInt (subs hex % (+ % 2)) 16)]
        (str "rgba(" (n 1) "," (n 3) "," (n 5) "," a ")"))
      hex)))

(defn- theme []
  {:surface (css-var "--surface") :text (css-var "--text")
   :text-2 (css-var "--text-2") :text-3 (css-var "--text-3") :line (css-var "--line")})

;; ---------------------------------------------------------------------------
;; Base option pieces

(defn base
  "Common option: recessive hairline grid/axes, crosshair tooltip."
  [{:keys [x-type y-name y-min y-max y-inverse y-fmt x-fmt grid-left tooltip-fmt]
    :or {x-type "time" grid-left 44}}]
  (let [t     (theme)
        label {:color (:text-3 t) :fontSize 11 :fontWeight 500 :fontFamily font}]
    {:animation false
     :textStyle {:fontFamily font}
     :grid {:left grid-left :right 14 :top 12 :bottom 24}
     :tooltip (cond-> {:trigger "axis"
                       :axisPointer {:type "line" :lineStyle {:color (:text-3 t) :width 1 :opacity 0.5}}
                       :backgroundColor (:surface t) :borderWidth 0 :borderRadius 12 :padding 10
                       :extraCssText (str "box-shadow:" (css-var "--tooltip-shadow"))
                       :textStyle {:color (:text t) :fontSize 12 :fontFamily font}
                       :confine true}
                tooltip-fmt (assoc :formatter tooltip-fmt))
     :xAxis {:type x-type
             :axisLine {:show false}
             :axisTick {:show false}
             :axisLabel (cond-> (assoc label :hideOverlap true)
                          x-fmt (assoc :formatter x-fmt))
             :splitLine {:show false}}
     :yAxis (cond-> {:type "value" :name y-name :scale true
                     :axisLine {:show false} :axisTick {:show false}
                     :nameTextStyle label
                     :axisLabel (cond-> label y-fmt (assoc :formatter y-fmt))
                     :splitLine {:lineStyle {:color (:line t) :width 1}}}
              (some? y-min) (assoc :min y-min :scale false)
              (some? y-max) (assoc :max y-max)
              y-inverse     (assoc :inverse true))}))

(defn line
  "2px line, no symbols, LTTB-sampled."
  [name color-var data & {:as opts}]
  (merge {:type "line" :name name :data data :showSymbol false :connectNulls false
          :sampling "lttb" :lineStyle {:width 2 :cap "round" :join "round"}
          :itemStyle {:color (css-var color-var)} :emphasis {:disabled true}}
         opts))

(defn area-wash
  ([s] (area-wash s 0.18))
  ([s opacity] (assoc s :areaStyle {:opacity opacity})))

(defn bars [name color-var data & {:as opts}]
  (merge {:type "bar" :name name :data data :barMaxWidth 24
          :itemStyle {:color (css-var color-var) :borderRadius 4}}
         opts))

(defn stacked
  "Stacked bar series whose segments touch: no separator border, and only
  the top segment of each column has rounded corners (radius `r`). Points
  may be numbers, [x value] pairs or {:value …} maps, aligned by index."
  [r series]
  (let [series (mapv #(update % :data vec) series)
        value  (fn [p] (cond (map? p) (:value p) (vector? p) (second p) :else p))
        filled? #(let [v (value %)] (and (number? v) (pos? v)))
        n      (apply max 0 (map (comp count :data) series))
        top    (vec (for [i (range n)]
                      (last (filter #(filled? (get-in series [% :data i])) (range (count series))))))]
    (vec (map-indexed
          (fn [si s]
            (-> s
                (update :itemStyle merge {:borderRadius 0 :borderWidth 0})
                (update :data #(vec (map-indexed
                                     (fn [i p]
                                       (if (= si (top i))
                                         (assoc-in (if (map? p) p {:value p}) [:itemStyle :borderRadius] [r r 0 0])
                                         p))
                                     %)))))
          series))))

(defn tooltip-rows
  "Tooltip HTML: a heading, then value-first rows keyed by a short line."
  [heading rows]
  (str "<div style='font-weight:600;margin-bottom:4px'>" (ui/esc heading) "</div>"
       (apply str
              (for [{:keys [label value color]} rows :when value]
                (str "<div style='display:flex;align-items:center;gap:8px'>"
                     (if color
                       (str "<span style='display:inline-block;width:12px;height:2px;background:" color "'></span>")
                       "<span style='display:inline-block;width:12px'></span>")
                     "<b>" (ui/esc value) "</b> <span style='opacity:.7'>" (ui/esc label) "</span></div>")))))

;; ---------------------------------------------------------------------------
;; Replicant hooks

(defn- observe-resize! [^js node f]
  (let [ro (js/ResizeObserver. (fn [_] (f)))]
    (.observe ro node)
    (set! (.-__ro node) ro)))

(defn echart
  "Hiccup for an ECharts chart. `option-fn` returns a Clojure option map.
  Charts sharing `group` get a linked crosshair."
  [{:keys [class option-fn group key]}]
  [:div.chart
   {:class class
    :replicant/key key
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)]
      (if (= life-cycle :replicant.life-cycle/unmount)
        (do (some-> (.-__ro node) .disconnect)
            (some-> (.-__chart node) .dispose))
        (let [^js c (or (.-__chart node)
                    (let [c (echarts/init node nil #js {:renderer "svg"})]
                      (set! (.-__chart node) c)
                      (observe-resize! node #(.resize c))
                      c))]
          (.setOption c (clj->js (option-fn)) #js {:notMerge true})
          (when group
            (set! (.-group c) group)
            (echarts/connect group))))))}])

(defn fade
  "A vertical gradient of a colour token from `top` to 0 opacity (area fills)."
  [var-name top]
  {:type "linear" :x 0 :y 0 :x2 0 :y2 1
   :colorStops [{:offset 0 :color (alpha var-name top)} {:offset 1 :color (alpha var-name 0)}]})

(defn spark-option
  "A sparkline: 2px line, no axes, an area fading from 0.3 to 0 and a 3.5px end
  dot ringed in --surface. `values` may hold nils. `band` is [[lo hi] ...] drawn
  behind at 0.14. `inverse?` turns the y axis upside down (lower is better)."
  [{:keys [color values band inverse?]}]
  (let [n    (count values)
        xs   (vec (range n))
        last-i (last (keep-indexed #(when (some? %2) %1) values))]
    {:animation false
     :grid {:left 5 :right 7 :top 6 :bottom 6}
     :tooltip {:show false}
     :xAxis {:type "category" :show false :boundaryGap false :data xs}
     :yAxis {:type "value" :show false :scale true :inverse (boolean inverse?)}
     :series (concat
              (when (seq band)
                [{:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0}
                  :data (mapv first band)}
                 {:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0}
                  :areaStyle {:color (css-var color) :opacity 0.14}
                  :data (mapv (fn [[lo hi]] (when (and lo hi) (- hi lo))) band)}])
              [{:type "line" :data values :showSymbol false :connectNulls true :silent true
                :lineStyle {:width 2 :cap "round" :join "round" :color (css-var color)}
                :areaStyle {:color (fade color 0.3) :origin (if inverse? "end" "start")}}
               {:type "scatter" :silent true :symbolSize 7
                :itemStyle {:color (css-var color) :borderColor (css-var "--surface") :borderWidth 2}
                :data (if last-i [[last-i (nth values last-i)]] [])}])}))

(defn spark
  "A sparkline chart (see spark-option), aria-hidden."
  [key class opts]
  [:div {:aria-hidden "true" :replicant/key key}
   (echart {:key key :class class :option-fn #(spark-option opts)})])

(defn end-label
  "A line's end label (\"55 bpm\"), 12px / 700, and the right padding it needs."
  [text color-var]
  {:label {:show true :formatter (fn [_] text) :color (css-var color-var)
           :fontSize 12 :fontWeight 700 :fontFamily font :distance 8}
   :pad (+ 16 (* 7.5 (count text)))})

(defn dom-chart
  "A hand-drawn chart: `draw!` is called with the container node and its width
  on every render and resize, and builds the content with DOM calls (no
  innerHTML). `first?` is true for the first draw only (for entry animations)."
  [{:keys [key class draw!]}]
  [:div.dom-chart
   {:class class
    :replicant/key key
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)]
        (if (= life-cycle :replicant.life-cycle/unmount)
          (some-> (.-__ro node) .disconnect)
          (let [redraw (fn []
                         (let [w (.-clientWidth node)]
                           (when (pos? w)
                             (draw! node w (not (.-__drawn node)))
                             (set! (.-__drawn node) true))))]
            (set! (.-__draw node) redraw)
            (when-not (.-__ro node)
              (observe-resize! node #((.-__draw node))))
            (redraw)))))}])

(def svg-ns "http://www.w3.org/2000/svg")

(defn el
  "A DOM element (SVG when `tag` is a keyword in svg/…) with attributes and
  children; strings become text nodes."
  [tag attrs & children]
  (let [svg? (= "svg" (namespace tag))
        ^js e (if svg? (.createElementNS js/document svg-ns (name tag)) (.createElement js/document (name tag)))]
    (doseq [[k v] attrs :when (some? v)] (.setAttribute e (name k) (str v)))
    (doseq [c (flatten children) :when (some? c)]
      (.appendChild e (if (string? c) (.createTextNode js/document c) c)))
    e))

(defn tooltip!
  "Shows `lines` (first one bold) in the card's tooltip near the pointer:
  14px right of it, flipped left near the card's edge. nil hides it."
  [^js host ^js ev lines]
  (let [^js tip (or (.-__tip host)
                    (let [t (el :div {:class "dom-tip" :role "tooltip"})]
                      (.appendChild host t)
                      (set! (.-__tip host) t)
                      t))]
    (if (empty? lines)
      (set! (.. tip -style -display) "none")
      (let [r  (.getBoundingClientRect host)
            x  (- (.-clientX ev) (.-left r))
            y  (- (.-clientY ev) (.-top r))]
        (.replaceChildren tip)
        (doseq [[i l] (map-indexed vector lines)]
          (.appendChild tip (el :div (when (zero? i) {:class "b"}) l)))
        (set! (.. tip -style -display) "block")
        (let [tw (.-offsetWidth tip)
              left (if (> (+ x 14 tw) (.-width r)) (max 0 (- x 14 tw)) (+ x 14))]
          (set! (.. tip -style -left) (str left "px"))
          (set! (.. tip -style -top) (str (max 0 (- y 10)) "px")))))))

(defn- reset-control
  "A map button under the zoom buttons that calls `fit!` to show the whole track again."
  [fit!]
  (let [^js ctrl (L/control #js {:position "topright"})]
    (set! (.-onAdd ctrl)
          (fn [_]
            (let [du  (.-DomUtil L)
                  div (.create du "div" "leaflet-bar leaflet-control map-reset")
                  ^js a (.create du "a" "" div)]
              (set! (.-href a) "#")
              (.setAttribute a "role" "button")
              (.setAttribute a "title" (t "Show the whole track"))
              (.setAttribute a "aria-label" (t "Show the whole track"))
              (set! (.-innerHTML a)
                    (str "<svg aria-hidden=\"true\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\""
                         " stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
                         "<path d=\"M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5\"/><circle cx=\"12\" cy=\"12\" r=\"2.5\"/></svg>"))
              (.disableClickPropagation (.-DomEvent L) div)
              (.on (.-DomEvent L) a "click" (fn [e] (.preventDefault e) (fit!)))
              div)))
    ctrl))

(defn route-map
  "Leaflet map of the track. The OpenStreetMap tiles keep their colours (dark
  mode inverts them with a CSS filter), and the track, its casing and the start/end dots are
  coloured by CSS classes (.map in app.css), so the theme toggle restyles
  the map without remounting it. `family` is a ui/families id."
  [{:keys [latlngs key family]}]
  [:div.map
   {:replicant/key key
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)]
      (case life-cycle
        :replicant.life-cycle/mount
        (let [^js m (L/map node #js {:scrollWheelZoom false :zoomControl false :attributionControl false})
              pts  (clj->js latlngs)
              path (fn [class weight]
                     #js {:className class :weight weight :lineCap "round" :lineJoin "round" :interactive false})
              line (L/polyline pts (path (str "route-line " (name (or family :other))) 4))]
          (.addTo (L/tileLayer "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                               #js {:maxZoom 19 :className "map-tiles"})
                  m)
          (.addTo (.. L -control (zoom #js {:position "topright"})) m)
          (.addTo (doto (.. L -control (attribution #js {:prefix false}))
                    (.addAttribution "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>"))
                  m)
          (.addTo (L/polyline pts (path "route-casing" 8)) m)
          (.addTo line m)
          (doseq [[p class] [[(first latlngs) "route-dot start"] [(last latlngs) "route-dot end"]]]
            (.addTo (L/circleMarker (clj->js p) #js {:className class :radius 6 :weight 3 :interactive false})
                    m))
          (let [fit! #(.fitBounds m (.getBounds line) #js {:padding #js [24 24]})]
            (fit!)
            (.addTo (reset-control fit!) m))
          (set! (.-__map node) m)
          (observe-resize! node #(.invalidateSize m)))
        :replicant.life-cycle/unmount
        (do (some-> (.-__ro node) .disconnect)
            (some-> (.-__map node) .remove))
        nil)))}])
