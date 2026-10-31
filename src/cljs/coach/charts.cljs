(ns coach.charts
  "ECharts and Leaflet, mounted through Replicant's :replicant/on-render hook.
  Options are built at render time from the CSS tokens, so light/dark both work."
  (:require ["echarts" :as echarts]
            ["leaflet" :as L]
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

(defn band
  "Normal-range band between lo and hi as two stacked series (the lower one
  invisible). Returns two series."
  [id color-var rows lo-key hi-key]
  (let [x #(:date %)]
    [{:type "line" :name (str id "-lo") :stack id :symbol "none" :silent true
      :lineStyle {:opacity 0} :tooltip {:show false}
      :data (mapv (fn [r] [(x r) (lo-key r)]) rows)}
     {:type "line" :name (str id "-band") :stack id :symbol "none" :silent true
      :lineStyle {:opacity 0} :areaStyle {:color (css-var color-var) :opacity 0.12}
      :tooltip {:show false}
      :data (mapv (fn [r] [(x r) (when (and (lo-key r) (hi-key r)) (- (hi-key r) (lo-key r)))]) rows)}]))

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

(defn route-map
  "Leaflet map of the track. The OpenStreetMap tiles are muted to the theme
  by a CSS filter, and the track, its casing and the start/end dots are
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
          (.fitBounds m (.getBounds line) #js {:padding #js [24 24]})
          (set! (.-__map node) m)
          (observe-resize! node #(.invalidateSize m)))
        :replicant.life-cycle/unmount
        (do (some-> (.-__ro node) .disconnect)
            (some-> (.-__map node) .remove))
        nil)))}])
