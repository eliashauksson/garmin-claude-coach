(ns coach.charts
  "ECharts and Leaflet, mounted through Replicant's :replicant/on-render hook.
  Options are built at render time from the CSS tokens, so light/dark both work."
  (:require ["echarts" :as echarts]
            ["leaflet" :as L]
            [coach.ui :as ui]))

(defn css-var [n]
  (.trim (.getPropertyValue (js/getComputedStyle js/document.documentElement) n)))

(defn- theme []
  {:surface (css-var "--surface-1") :text (css-var "--text-primary")
   :secondary (css-var "--text-secondary") :muted (css-var "--text-muted")
   :grid (css-var "--grid") :axis (css-var "--axis") :border (css-var "--border")})

;; ---------------------------------------------------------------------------
;; Base option pieces

(defn base
  "Common option: recessive hairline grid/axes, crosshair tooltip."
  [{:keys [x-type y-name y-min y-max y-inverse y-fmt x-fmt grid-left tooltip-fmt]
    :or {x-type "time" grid-left 48}}]
  (let [t (theme)]
    {:animation false
     :textStyle {:fontFamily "system-ui, -apple-system, Segoe UI, sans-serif"}
     :grid {:left grid-left :right 18 :top 14 :bottom 28}
     :tooltip (cond-> {:trigger "axis"
                       :axisPointer {:type "line" :lineStyle {:color (:axis t) :width 1}}
                       :backgroundColor (:surface t) :borderColor (:border t)
                       :textStyle {:color (:text t) :fontSize 12}
                       :confine true}
                tooltip-fmt (assoc :formatter tooltip-fmt))
     :xAxis {:type x-type
             :axisLine {:lineStyle {:color (:axis t)}}
             :axisTick {:show false}
             :axisLabel (cond-> {:color (:muted t) :hideOverlap true}
                          x-fmt (assoc :formatter x-fmt))
             :splitLine {:show false}}
     :yAxis (cond-> {:type "value" :name y-name :scale true
                     :nameTextStyle {:color (:muted t)}
                     :axisLabel (cond-> {:color (:muted t)} y-fmt (assoc :formatter y-fmt))
                     :splitLine {:lineStyle {:color (:grid t) :width 1}}}
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

(defn area-wash [s] (assoc s :areaStyle {:opacity 0.1}))

(defn bars [name color-var data & {:as opts}]
  (merge {:type "bar" :name name :data data :barMaxWidth 24
          :itemStyle {:color (css-var color-var) :borderRadius [4 4 0 0]}}
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
  "Leaflet map with OpenStreetMap tiles and the track as a polyline."
  [{:keys [latlngs key]}]
  [:div.map
   {:replicant/key key
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)]
      (case life-cycle
        :replicant.life-cycle/mount
        (let [^js m (L/map node #js {:scrollWheelZoom false})
              pts  (clj->js latlngs)
              line (L/polyline pts #js {:color (css-var "--series-1") :weight 3})]
          (.addTo (L/tileLayer "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                               #js {:maxZoom 19
                                    :attribution "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"})
                  m)
          (.addTo line m)
          (doseq [[p fill] [[(first latlngs) "--status-good"] [(last latlngs) "--status-critical"]]]
            (.addTo (L/circleMarker (clj->js p) #js {:radius 5 :weight 2 :color (css-var "--surface-1")
                                                     :fillColor (css-var fill) :fillOpacity 1})
                    m))
          (.fitBounds m (.getBounds line) #js {:padding #js [16 16]})
          (set! (.-__map node) m)
          (observe-resize! node #(.invalidateSize m)))
        :replicant.life-cycle/unmount
        (do (some-> (.-__ro node) .disconnect)
            (some-> (.-__map node) .remove))
        nil)))}])
