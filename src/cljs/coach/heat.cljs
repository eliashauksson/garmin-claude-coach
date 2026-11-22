(ns coach.heat
  "The Map tab's heatmap: every track drawn additively onto a canvas, then each pixel coloured by how
  often it was covered. 2D on Leaflet; 3D (terrain to tilt and turn) on MapLibre GL, which is not in
  the app bundle: /vendor/maplibre/ (copied there by `npm install`) is loaded the first time 3D is shown.
  Ground: the OpenStreetMap map (dark or light) or Esri satellite imagery; terrain: Mapterhorn (swisstopo's
  swissALTI3D in Switzerland, Copernicus and other open elevation models elsewhere).
  Zoomed out, 3D is a globe in front of the real night sky (coach.sky): the Yale Bright Star Catalogue and
  ESO's Milky Way panorama (resources/public/sky/, made by `make sky`)."
  (:require ["leaflet" :as L]
            [clojure.string :as str]
            [coach.charts :as charts]
            [coach.i18n :refer [t tk]]
            [coach.sky :as sky]
            [coach.track :as track]
            [coach.ui :as ui]))

;; ---------------------------------------------------------------------------
;; Colours

(def palettes
  "Colour schemes, from a pixel covered once to the most often used ones."
  [{:id "blue"  :label (tk "Blue")
    :stops [[0.0 "rgba(30,60,255,0)"] [0.08 "rgba(40,80,255,.55)"] [0.3 "#1fb6ff"] [0.6 "#ffd23f"] [1.0 "#ffffff"]]}
   {:id "fire"  :label (tk "Fire")
    :stops [[0.0 "rgba(120,0,0,0)"] [0.08 "rgba(170,20,10,.6)"] [0.3 "#ff4b1f"] [0.6 "#ffb000"] [1.0 "#fffbe0"]]}
   {:id "magma" :label (tk "Magma")
    :stops [[0.0 "rgba(80,20,140,0)"] [0.08 "rgba(120,40,190,.6)"] [0.3 "#e0369a"] [0.6 "#ff9a3c"] [1.0 "#fff3b0"]]}
   {:id "green" :label (tk "Green")
    :stops [[0.0 "rgba(0,90,60,0)"] [0.08 "rgba(0,140,90,.6)"] [0.3 "#18c37d"] [0.6 "#b6f23c"] [1.0 "#ffffff"]]}
   {:id "white" :label (tk "White")
    :stops [[0.0 "rgba(255,255,255,0)"] [0.08 "rgba(255,255,255,.35)"] [0.5 "rgba(255,255,255,.8)"]
            [1.0 "#ffffff"]]}])

(defn- palette [id]
  (or (some #(when (= id (:id %)) %) palettes) (first palettes)))

(defn gradient
  "CSS linear-gradient of a palette, for its swatch."
  [id]
  (str "linear-gradient(90deg, "
       (apply str (interpose ", " (for [[at col] (:stops (palette id))] (str col " " (* 100 at) "%"))))
       ")"))

(def ^:private lut
  "256 RGBA entries per palette id, built once from its stops."
  (memoize
   (fn [id]
     (let [c   (doto (js/document.createElement "canvas") (set! -width 256) (set! -height 1))
           ctx (.getContext c "2d")
           g   (.createLinearGradient ctx 0 0 256 0)]
       (doseq [[at col] (:stops (palette id))] (.addColorStop g at col))
       (set! (.-fillStyle ctx) g)
       (.fillRect ctx 0 0 256 1)
       (.-data (.getImageData ctx 0 0 256 1))))))

;; ---------------------------------------------------------------------------
;; Tracks and drawing

(def ^:private alpha "Alpha added per track: ~24 overlapping tracks saturate a pixel." 0.042)

(defonce ^:private decoded
  ;; activity id -> #js {:segs [[lat lng] …] :bounds [s w n e] :start [lat lng] :item activity}; tracks never change
  (atom {}))

(defn- decoded-track [{:keys [id track] :as item}]
  (or (when-let [^js d (@decoded id)] (set! (.-item d) item) d)
      (let [segs (clj->js (map track/decode track))
            b    #js [90 180 -90 -180]]
        (.forEach segs (fn [seg]
                         (.forEach seg (fn [ll]
                                         (let [la (aget ll 0) lo (aget ll 1)]
                                           (aset b 0 (min (aget b 0) la)) (aset b 1 (min (aget b 1) lo))
                                           (aset b 2 (max (aget b 2) la)) (aset b 3 (max (aget b 3) lo)))))))
        (let [d #js {:segs segs :bounds b :item item
                     :start (some-> (aget segs 0) (aget 0))}]
          (swap! decoded assoc id d)
          d))))

(defn- intersects? [^js b [s w n e]]
  (and (<= (aget b 0) n) (>= (aget b 2) s) (<= (aget b 1) e) (>= (aget b 3) w)))

(defn- all-bounds
  "[s w n e] around all tracks, nil without any."
  [tracks]
  (when (seq tracks)
    (reduce (fn [[s w n e] ^js tr]
              (let [b (.-bounds tr)]
                [(min s (aget b 0)) (min w (aget b 1)) (max n (aget b 2)) (max e (aget b 3))]))
            [90 180 -90 -180] tracks)))

(defn- line-width
  "Track width in CSS px at a Leaflet zoom level."
  [zoom]
  (max 1.2 (min 4 (- (* 0.3 zoom) 0.6))))

(defn- paint!
  "Draws the tracks inside `view` [s w n e] additively onto `canvas`, `w`×`h` CSS px at `scale`
  canvas px each (`project` gives a point's #js [x y] in CSS px), then colours every pixel by its
  coverage through the palette. `edge?` adds a thin dark outline, for light ground."
  [^js canvas {:keys [w h scale view project width edge?]} tracks palette-id]
  (let [cw (js/Math.max 1 (js/Math.round (* scale w)))
        ch (js/Math.max 1 (js/Math.round (* scale h)))
        ^js ctx (.getContext canvas "2d" #js {:willReadFrequently true})]
    (set! (.-width canvas) cw)
    (set! (.-height canvas) ch)
    (.setTransform ctx scale 0 0 scale 0 0)
    (set! (.-globalCompositeOperation ctx) "lighter")
    (set! (.-strokeStyle ctx) (str "rgba(255,255,255," alpha ")"))
    (set! (.-lineWidth ctx) (max width (/ 1 scale)))
    (set! (.-lineJoin ctx) "round")
    (set! (.-lineCap ctx) "round")
    (doseq [^js tr tracks :when (intersects? (.-bounds tr) view)]
      (.beginPath ctx)
      (.forEach (.-segs tr)
                (fn [seg]
                  (dotimes [i (.-length seg)]
                    (let [ll (aget seg i)
                          p  (project (aget ll 0) (aget ll 1))]
                      (if (zero? i) (.moveTo ctx (aget p 0) (aget p 1)) (.lineTo ctx (aget p 0) (aget p 1)))))))
      (.stroke ctx))
    ;; coverage (alpha) -> colour; sqrt so a single pass is already visible
    (let [img (.getImageData ctx 0 0 cw ch)
          px  (.-data img)
          lut (lut palette-id)]
      (loop [i 0]
        (when (< i (.-length px))
          (let [a (aget px (+ i 3))]
            (when (pos? a)
              (let [j (* 4 (js/Math.round (* 255 (js/Math.sqrt (/ a 255)))))]
                (aset px i (aget lut j))
                (aset px (+ i 1) (aget lut (+ j 1)))
                (aset px (+ i 2) (aget lut (+ j 2)))
                (aset px (+ i 3) (aget lut (+ j 3))))))
          (recur (+ i 4))))
      (.putImageData ctx img 0 0))
    (when edge?
      (let [copy (doto (js/document.createElement "canvas") (set! -width cw) (set! -height ch))]
        (.drawImage (.getContext copy "2d") canvas 0 0)
        (.setTransform ctx 1 0 0 1 0 0)
        (set! (.-globalCompositeOperation ctx) "source-over")
        (.clearRect ctx 0 0 cw ch)
        (set! (.-shadowColor ctx) "rgba(0,0,0,.75)")
        (set! (.-shadowBlur ctx) (max 1 scale))
        (.drawImage ctx copy 0 0)))))

;; ---------------------------------------------------------------------------
;; Pins: one per activity at its start, grouped when close; click for the activity

(def ^:private pin-cell "Pins and bubbles closer than this many px (on screen) are merged." 52)

(defn- fam-color
  "The family's colour as a CSS value (MapLibre can't use CSS variables)."
  [fam]
  (let [v (.trim (.getPropertyValue (js/getComputedStyle js/document.documentElement) (:color fam)))]
    (if (str/blank? v) "#8a90a0" v)))

(defn- dom [tag attrs & kids]
  (let [e (js/document.createElement tag)]
    (doseq [[k v] attrs] (.setAttribute e (name k) v))
    (doseq [k kids :when k] (.appendChild e (if (string? k) (js/document.createTextNode k) k)))
    e))

(defn- activity-row [{:keys [id date sport distance_m duration_s] :as item}]
  (let [fam   (ui/family sport)
        d     (ui/parse-date date)
        title (if (str/blank? (:name item)) (t (:label fam)) (:name item))
        stats (str/join " · " (remove nil? [(str (ui/day-month d) " " (.getFullYear d))
                                            (some-> (ui/km distance_m) (str " km"))
                                            (ui/hms duration_s)]))]
    (dom "a" {:class "map-popup-row" :href (str "#/activities/" id)}
         (dom "span" {:class "map-pin" :style (str "background:var(" (:color fam) ")")})
         (dom "span" {:class "map-popup-text"} (dom "b" {} title) (dom "span" {:class "sub"} stats)))))

(defn- popup-node
  "The popup's content for `items`: one activity with a link to open it, or a list of several."
  [items]
  (if (= 1 (count items))
    (let [item (first items)]
      (dom "div" {:class "map-popup-body"}
           (activity-row item)
           (dom "a" {:class "map-popup-open" :href (str "#/activities/" (:id item))} (t "Open activity"))))
    (apply dom "div" {:class "map-popup-body"}
           (dom "div" {:class "map-popup-head"} (t "{0} activities here" (count items)))
           (map activity-row (take 8 (sort-by :date #(compare %2 %1) items))))))

(defn- nearest-track
  "The track passing within `tol` px of the point [x y] (container px), nil if none. `project` as in
  `paint!`, `box` [s w n e] is the area around the point in degrees (to skip far tracks cheaply)."
  [tracks box project [x y] tol]
  (let [best (volatile! [nil (* tol tol)])]
    (doseq [^js tr tracks :when (intersects? (.-bounds tr) box)]
      (.forEach (.-segs tr)
                (fn [seg]
                  (dotimes [i (dec (.-length seg))]
                    (let [a (project (aget (aget seg i) 0) (aget (aget seg i) 1))
                          b (project (aget (aget seg (inc i)) 0) (aget (aget seg (inc i)) 1))
                          ax (aget a 0) ay (aget a 1)
                          dx (- (aget b 0) ax) dy (- (aget b 1) ay)
                          l2 (+ (* dx dx) (* dy dy))
                          u  (if (zero? l2) 0 (max 0 (min 1 (/ (+ (* (- x ax) dx) (* (- y ay) dy)) l2))))
                          ex (- x (+ ax (* u dx))) ey (- y (+ ay (* u dy)))
                          d2 (+ (* ex ex) (* ey ey))]
                      (when (< d2 (second @best)) (vreset! best [tr d2])))))))
    (first @best)))

(defn- box-around
  "[s w n e] of the square `tol` px around [x y], from `unproject` (container px -> #js [lat lng])."
  [unproject [x y] tol]
  (let [a (unproject (- x tol) (+ y tol)) b (unproject (+ x tol) (- y tol))]
    [(min (aget a 0) (aget b 0)) (min (aget a 1) (aget b 1)) (max (aget a 0) (aget b 0)) (max (aget a 1) (aget b 1))]))

(defn- same-spot?
  "Whether all these tracks start within about 30 m of each other (zooming in won't separate them)."
  [tracks]
  (let [ls (map #(aget (.-start ^js %) 0) tracks)
        os (map #(aget (.-start ^js %) 1) tracks)]
    (< (max (- (apply max ls) (apply min ls)) (- (apply max os) (apply min os))) 0.0003)))

(defn- pin-icon [cls html size]
  (.divIcon L #js {:className "map-pin-wrap" :html (str "<span class=\"" cls "\"" html "</span>")
                   :iconSize #js [size size]}))

(defn- pins-layer
  "Leaflet pins for `node`'s tracks (their starts), grouped by screen position, redrawn after every
  move. A click opens the popup and highlights the track; `select!` does that for an item list."
  [^js m ^js node select!]
  (let [group (.layerGroup L)
        refresh (fn []
                  (.clearLayers group)
                  (let [b   (.pad (.getBounds m) 0.1)
                        pts (for [^js tr (.-__tracks node)
                                  :let [st (.-start tr)]
                                  :when (and st (.contains b #js [(aget st 0) (aget st 1)]))]
                              (let [p (.latLngToContainerPoint m #js [(aget st 0) (aget st 1)])]
                                {:px [(.-x p) (.-y p)] :ll [(aget st 0) (aget st 1)] :v tr}))]
                    (doseq [{:keys [ll vs]} (track/cluster pts pin-cell)]
                      (let [n    (count vs)
                            mk   (if (= 1 n)
                                   (let [^js tr (first vs)
                                         fam (ui/family (:sport (.-item tr)))]
                                     (.marker L (clj->js ll)
                                              #js {:icon (pin-icon "map-pin" (str " style=\"background:var("
                                                                                  (:color fam) ")\">") 18)
                                                   :title (:name (.-item tr))}))
                                   (let [s (cond (< n 10) 30 (< n 100) 36 :else 42)]
                                     (.marker L (clj->js ll)
                                              #js {:icon (pin-icon "map-pin-group" (str ">" n) s)})))]
                        (.on mk "click"
                             (fn []
                               (if (or (= 1 n) (same-spot? vs) (>= (.getZoom m) 17))
                                 (select! (mapv #(.-item ^js %) vs) (clj->js ll))
                                 (.fitBounds m (clj->js (mapv #(vec (.-start ^js %)) vs))
                                             #js {:padding #js [60 60] :maxZoom 19}))))
                        (.addTo mk group)))))]
    (set! (.-refresh group) refresh)
    group))

;; ---------------------------------------------------------------------------
;; Ground

(def ^:private osm-url "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
(def ^:private satellite-url
  "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")
;; TileJSON rather than a tile URL: it carries the max zoom, which differs by region (up to z17 where
;; national high-resolution models exist, e.g. swissALTI3D at 0.5 m).
(def ^:private terrain-tilejson "https://tiles.mapterhorn.com/tilejson.json")

(def ^:private osm-credit "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>")
(def ^:private esri-credit "Imagery &copy; Esri")
(def ^:private terrain-credit "Terrain <a href=\"https://mapterhorn.com/attribution\">&copy; Mapterhorn</a>")

(defonce ^:private camera
  ;; the last view, shared by 2D and 3D so the Map tab (and a switch between them) keeps the place.
  ;; :zoom is on Leaflet's scale (MapLibre's is one less).
  (atom nil))

;; ---------------------------------------------------------------------------
;; Fullscreen: the map with its pill (not the navigation) fills the whole screen

(def ^:private fullscreen-icon
  (str "<svg aria-hidden=\"true\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\""
       " stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
       "<path d=\"M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5\"/></svg>"))

(def ^:private fullscreen-exit-icon
  (str "<svg aria-hidden=\"true\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\""
       " stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
       "<path d=\"M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5\"/></svg>"))

(defn- toggle-fullscreen! [^js node]
  (if (.-fullscreenElement js/document)
    (.exitFullscreen js/document)
    (some-> (.closest node ".heat-base") .requestFullscreen)))

(defn- fullscreen-button
  "A button (`tag`: \"a\" or \"button\") that toggles fullscreen for the map `node`, its icon and label
  following the state. `.cleanup` removes its document listener."
  [^js node tag]
  (let [^js b (js/document.createElement tag)
        update! (fn []
                  (let [on? (some? (.-fullscreenElement js/document))
                        label (if on? (t "Exit fullscreen") (t "Fullscreen"))]
                    (set! (.-title b) label)
                    (.setAttribute b "aria-label" label)
                    (set! (.-innerHTML b) (if on? fullscreen-exit-icon fullscreen-icon))))]
    (update!)
    (.addEventListener b "click" (fn [e] (.preventDefault e) (toggle-fullscreen! node)))
    (.addEventListener js/document "fullscreenchange" update!)
    (set! (.-cleanup b) #(.removeEventListener js/document "fullscreenchange" update!))
    b))

(defn- fullscreen-control-2d [^js node]
  (let [^js ctrl (L/control #js {:position "topright"})
        btn      (volatile! nil)]
    (set! (.-onAdd ctrl)
          (fn [_]
            (let [div (.create (.-DomUtil L) "div" "leaflet-bar leaflet-control map-reset")
                  ^js a (fullscreen-button node "a")]
              (set! (.-href a) "#")
              (.setAttribute a "role" "button")
              (.appendChild div a)
              (.disableClickPropagation (.-DomEvent L) div)
              (vreset! btn a)
              div)))
    (set! (.-onRemove ctrl) (fn [_] (some-> ^js @btn .-cleanup (#(%)))))
    ctrl))

(defn- fullscreen-control-3d [^js node]
  (let [div (js/document.createElement "div")
        ^js b (fullscreen-button node "button")]
    (set! (.-className div) "maplibregl-ctrl maplibregl-ctrl-group")
    (set! (.-type b) "button")
    (.appendChild div b)
    #js {:onAdd (fn [_] div) :onRemove (fn [_] ((.-cleanup b)) (.remove div))}))

;; ---------------------------------------------------------------------------
;; 2D (Leaflet)

(def ^:private pad "Canvas margin around the view (fraction of its size), so short pans show heat." 0.25)

(defn- heat-layer
  "A Leaflet layer with one canvas, redrawn after every move/zoom. `current` gives
  [tracks palette-id heat?]; without heat? the canvas is hidden and not drawn."
  [current]
  (let [canvas (js/document.createElement "canvas")
        state  #js {:map nil}
        redraw (fn []
                 (when-let [^js m (.-map state)]
                   (let [[tracks palette-id heat?] (current)
                         size (.getSize m)
                         off  (.round (.multiplyBy size pad))
                         full (.add size (.multiplyBy off 2))
                         b    (.pad (.getBounds m) pad)]
                     (set! (.. canvas -style -visibility) (if heat? "visible" "hidden"))
                     (when heat?
                       (set! (.. canvas -style -width) (str (.-x full) "px"))
                       (set! (.. canvas -style -height) (str (.-y full) "px"))
                       (.setPosition (.-DomUtil L) canvas
                                     (.subtract (.containerPointToLayerPoint m #js [0 0]) off))
                       (paint! canvas
                               {:w (.-x full) :h (.-y full) :scale (or js/window.devicePixelRatio 1)
                                :view [(.getSouth b) (.getWest b) (.getNorth b) (.getEast b)]
                                :width (line-width (.getZoom m))
                                :project (fn [la lo]
                                           (let [p (.latLngToContainerPoint m (L/latLng la lo))]
                                             #js [(+ (.-x p) (.-x off)) (+ (.-y p) (.-y off))]))}
                               tracks palette-id)))))
        hide   (fn [] (set! (.. canvas -style -visibility) "hidden"))
        Layer  (.extend (.-Layer L)
                        #js {:onAdd (fn [m]
                                      (set! (.-map state) m)
                                      (set! (.-className canvas) "heat-canvas")
                                      (.appendChild (.getPane m "overlayPane") canvas)
                                      (redraw))
                             :onRemove (fn [_] (.remove canvas) (set! (.-map state) nil))
                             :getEvents (fn [] #js {:moveend redraw :zoomstart hide :resize redraw})})
        layer  (Layer.)]
    (set! (.-redraw layer) redraw)
    layer))

(defn- set-ground-2d!
  "OpenStreetMap (dark or light by CSS) or satellite tiles, with their credit."
  [^js node ^js m ground]
  (let [sat? (= "satellite" ground)
        ^js attribution (.-__attribution node)]
    (some->> (.-__tiles node) (.removeLayer m))
    (.removeAttribution attribution osm-credit)
    (.removeAttribution attribution esri-credit)
    (.addAttribution attribution (if sat? esri-credit osm-credit))
    (set! (.-__tiles node)
          (.bringToBack (.addTo (if sat?
                                  (L/tileLayer satellite-url #js {:maxZoom 19 :maxNativeZoom 18 :className "map-tiles"})
                                  (L/tileLayer osm-url #js {:maxZoom 19 :className "map-tiles"}))
                                m)))
    (set! (.-__ground node) ground)))

(defn- select-2d!
  "Opens the popup for `items` at `ll` [lat lng]; one activity also gets its track highlighted."
  [^js node items ll]
  (let [^js m   (.-__map node)
        ^js sel (.-__sel node)]
    (.openOn (doto (.popup L #js {:className "map-popup" :maxWidth 300 :autoPanPaddingTopLeft #js [240 110]
                                  :autoPanPaddingBottomRight #js [60 90]})
               (.setLatLng ll)
               (.setContent (popup-node items)))
             m)
    (.clearLayers sel)
    (when (= 1 (count items))
      (let [segs (.-segs ^js (decoded-track (first items)))
            col  (fam-color (ui/family (:sport (first items))))]
        (.addTo (.polyline L segs #js {:color "#ffffff" :weight 7 :opacity 0.9 :interactive false}) sel)
        (.addTo (.polyline L segs #js {:color col :weight 4 :opacity 1 :interactive false}) sel)))))

(defn- on-map-click!
  "A click on the map that isn't on a pin: the track under it (within 10 px), if the heat shows."
  [^js node ^js e]
  (let [^js m (.-__map node)
        pt    (.-containerPoint e)
        xy    [(.-x pt) (.-y pt)]
        unproject (fn [x y] (let [ll (.containerPointToLatLng m #js [x y])] #js [(.-lat ll) (.-lng ll)]))
        project   (fn [la lo] (let [p (.latLngToContainerPoint m #js [la lo])] #js [(.-x p) (.-y p)]))]
    (when (.-__heat node)
      (when-let [^js tr (nearest-track (.-__tracks node) (box-around unproject xy 10) project xy 10)]
        (select-2d! node [(.-item tr)] (.-latlng e))))))

(defn- sync-pins-2d!
  "Shows or hides the pins layer by the `pins?` setting and redraws it."
  [^js node]
  (let [^js m (.-__map node) ^js g (.-__pins-layer node)]
    (if (.-__pins node)
      (do (when-not (.hasLayer m g) (.addTo g m)) ((.-refresh g)))
      (.remove g))))

(defn map-2d
  "Full-page Leaflet heatmap of `items` ([{:id :track [encoded …]}], coach.track) in the colours of
  `palette` on `ground` (dark, light, satellite), shown while `heat?`. `version` changes with the
  filter: the heat is redrawn, the view kept."
  [{:keys [items version palette ground heat? pins?]}]
  [:div.map.heatmap
   {:replicant/key "heatmap-2d"
    :data-version (str version "|" palette "|" ground "|" heat? "|" pins?)
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)
            tracks   (mapv decoded-track items)]
        (case life-cycle
          :replicant.life-cycle/mount
          (let [^js m (L/map node #js {:zoomControl false :attributionControl false :minZoom 2})
                fit! #(when-let [[s w n e] (all-bounds (.-__tracks node))]
                        (.fitBounds m #js [#js [s w] #js [n e]] #js {:padding #js [24 24]}))]
            (set! (.-__tracks node) tracks)
            (set! (.-__palette node) palette)
            (set! (.-__heat node) heat?)
            (set! (.-__pins node) pins?)
            (set! (.-__attribution node) (.addTo (.. L -control (attribution #js {:prefix false})) m))
            (set-ground-2d! node m ground)
            (.addTo (.. L -control (zoom #js {:position "topright"})) m)
            (if-let [{:keys [lat lng zoom]} @camera]
              (.setView m #js [lat lng] (js/Math.round zoom))
              (if (seq tracks) (fit!) (.setView m #js [46.8 8.2] 7)))
            (.on m "moveend" (fn []
                               (let [c (.getCenter m)]
                                 (swap! camera assoc :lat (.-lat c) :lng (.-lng c) :zoom (.getZoom m)))))
            (.addTo (charts/reset-control fit! (t "Show all activities")) m)
            (when js/document.fullscreenEnabled (.addTo (fullscreen-control-2d node) m))
            (let [layer (heat-layer #(vector (.-__tracks node) (.-__palette node) (.-__heat node)))]
              (.addTo layer m)
              (set! (.-__layer node) layer))
            (set! (.-__version node) version)
            (set! (.-__map node) m)
            (set! (.-__sel node) (.addTo (.layerGroup L) m))
            (set! (.-__pins-layer node) (pins-layer m node #(select-2d! node %1 %2)))
            (.on m "popupclose" #(.clearLayers ^js (.-__sel node)))
            (.on m "click" #(on-map-click! node %))
            (.on m "moveend" #(when (.-__pins node) ((.-refresh ^js (.-__pins-layer node)))))
            (sync-pins-2d! node)
            (charts/observe-resize! node #(.invalidateSize m)))

          :replicant.life-cycle/update
          (do (when (not= ground (.-__ground node))
                (set-ground-2d! node (.-__map node) ground))
              (when (or (not= version (.-__version node)) (not= palette (.-__palette node))
                        (not= heat? (.-__heat node)) (not= pins? (.-__pins node)))
                (set! (.-__version node) version)
                (set! (.-__tracks node) tracks)
                (set! (.-__palette node) palette)
                (set! (.-__heat node) heat?)
                (set! (.-__pins node) pins?)
                ((.-redraw (.-__layer node)))
                (.closePopup (.-__map node))
                (sync-pins-2d! node)))

          :replicant.life-cycle/unmount
          (do (some-> (.-__ro node) .disconnect)
              (some-> (.-__map node) .remove))
          nil)))}])

;; ---------------------------------------------------------------------------
;; 3D (MapLibre GL)

(defonce ^:private maplibre
  ;; a promise of the MapLibre module, loaded once (its CSS too)
  (delay
    (js/Promise.
     (fn [resolve reject]
       (let [link (js/document.createElement "link")
             s    (js/document.createElement "script")]
         (set! (.-rel link) "stylesheet")
         (set! (.-href link) "/vendor/maplibre/maplibre-gl.css")
         (.appendChild js/document.head link)
         (set! (.-__coachMaplibre js/window) #js {:ok resolve :fail reject})
         (set! (.-textContent s)
               (str "import('/vendor/maplibre/maplibre-gl.mjs')"
                    ".then(function (m) { window.__coachMaplibre.ok(m); },"
                    " function (e) { window.__coachMaplibre.fail(e); });"))
         (.appendChild js/document.head s))))))

(defn- merc-x [lng] (/ (+ lng 180) 360))
(defn- merc-y [lat]
  (let [r (* lat (/ js/Math.PI 180))]
    (/ (- 1 (/ (js/Math.log (js/Math.tan (+ (/ js/Math.PI 4) (/ r 2)))) js/Math.PI)) 2)))
(defn- lng-of [x] (- (* x 360) 180))
(defn- lat-of [y] (* (/ 180 js/Math.PI) (js/Math.atan (js/Math.sinh (* js/Math.PI (- 1 (* 2 y)))))))

(def ^:private max-canvas "Heat canvas side in px at most (a WebGL texture)." 3072)

(def ^:private whole-world-zoom
  "Below this zoom (a globe, half the earth in view) the heat covers the whole world." 3.5)

(defn- draw-3d!
  "Paints the heat for what the camera sees (the tilted view reaches far: at most 2.5 screens from the
  centre; zoomed out to the globe, the whole world) and drapes it onto the terrain through the `heat`
  canvas source."
  [^js m ^js canvas tracks palette-id ground]
  (let [b     (.getBounds m)
        z     (.getZoom m)
        world (* 512 (js/Math.pow 2 z))                 ; CSS px per mercator unit at the centre
        c     (.getCenter m)
        cx    (merc-x (.-lng c))
        cy    (merc-y (.-lat c))
        ^js el (.getContainer m)
        reach (/ (* 2.5 (max (.-clientWidth el) (.-clientHeight el))) world)
        ;; on the globe the view can wrap round or cover a hemisphere: bounds aren't a rectangle then
        all?  (or (< z whole-world-zoom) (> (- (.getEast b) (.getWest b)) 180))
        y-max (merc-y 85)
        y-min (merc-y -85)
        x0 (if all? 0 (max 0 (- cx reach) (merc-x (.getWest b))))
        x1 (if all? 1 (min 1 (+ cx reach) (merc-x (.getEast b))))
        y0 (if all? y-max (max y-max (- cy reach) (merc-y (min 85 (.getNorth b)))))
        y1 (if all? y-min (min y-min (+ cy reach) (merc-y (max -85 (.getSouth b)))))
        w  (* (- x1 x0) world)
        h  (* (- y1 y0) world)
        ^js src (.getSource m "heat")]
    (when (and src (pos? w) (pos? h))
      (paint! canvas
              {:w w :h h :scale (min (or js/window.devicePixelRatio 1) (/ max-canvas (max w h)))
               :view [(lat-of y1) (lng-of x0) (lat-of y0) (lng-of x1)]
               :width (line-width (inc z))
               :edge? (not= "dark" ground)
               :project (fn [la lo] #js [(* (- (merc-x lo) x0) world) (* (- (merc-y la) y0) world)])}
              tracks palette-id)
      (.setCoordinates src (clj->js [[(lng-of x0) (lat-of y0)] [(lng-of x1) (lat-of y0)]
                                     [(lng-of x1) (lat-of y1)] [(lng-of x0) (lat-of y1)]]))
      ;; a static canvas source re-reads its pixels only while playing: one frame
      (.play src)
      (.once m "render" #(.pause src))
      (.triggerRepaint m))))

(defn- ground-3d
  "Layer properties per ground: which raster shows, how the OSM map is tinted, relief and sky."
  [ground]
  (case ground
    "satellite" {:osm "none" :sat "visible" :bg "#0f1115" :shade 0.15
                 :sky ["#9cc3e6" "#e8eef4"] :tint {}}
    "light"     {:osm "visible" :sat "none" :bg "#e8e8e4" :shade 0.3
                 :sky ["#c9dcef" "#f4f5f2"]
                 :tint {"raster-brightness-min" 0 "raster-brightness-max" 1 "raster-hue-rotate" 0
                        "raster-saturation" -0.6 "raster-contrast" -0.1}}
    {:osm "visible" :sat "none" :bg "#0f1115" :shade 0.35
     :sky ["#0f1115" "#262b36"]
     ;; brightness min above max inverts: the same darkened map as in 2D
     :tint {"raster-brightness-min" 0.8 "raster-brightness-max" 0.08 "raster-hue-rotate" 180
            "raster-saturation" -0.7 "raster-contrast" 0}}))

(defn- sky-props [[top horizon]]
  #js {"sky-color" top "horizon-color" horizon "fog-color" horizon
       "sky-horizon-blend" 0.6 "horizon-fog-blend" 0.6 "fog-ground-blend" 0.85
       ;; the blue glow round the globe when far out, gone close up
       "atmosphere-blend" #js ["interpolate" #js ["linear"] #js ["zoom"] 0 1 5 1 7 0]})

(defn- style-3d [ground]
  (let [{:keys [osm sat bg shade tint] :as g} (ground-3d ground)
        dem #js {:type "raster-dem" :url terrain-tilejson :tileSize 512 :encoding "terrarium"}]
    #js {:version 8
         :sources #js {:osm   #js {:type "raster" :tiles #js [osm-url] :tileSize 256 :maxzoom 19}
                       :sat   #js {:type "raster" :tiles #js [satellite-url] :tileSize 256 :maxzoom 18}
                       :dem   dem
                       :shade (js/Object.assign #js {} dem)}
         ;; a globe when zoomed out, blending into the flat map close up (as in Google Earth)
         :projection #js {:type "globe"}
         :terrain #js {:source "dem" :exaggeration 1.0}
         :sky (sky-props (:sky g))
         :layers #js [#js {:id "bg" :type "background" :paint #js {"background-color" bg}}
                      #js {:id "osm" :type "raster" :source "osm" :layout #js {:visibility osm} :paint (clj->js tint)}
                      #js {:id "sat" :type "raster" :source "sat" :layout #js {:visibility sat}}
                      #js {:id "shade" :type "hillshade" :source "shade"
                           :paint #js {"hillshade-exaggeration" shade
                                       "hillshade-shadow-color" "rgba(0,0,0,.55)"
                                       "hillshade-highlight-color" "rgba(255,255,255,.12)"}}]}))

;; ---------------------------------------------------------------------------
;; Night sky behind the globe

(def ^:private sky-fade
  "Zooms between which the sky fades out (with MapLibre's atmosphere glow): close up there's no space."
  [5 7])

(def ^:private sky-credit "Stars: Yale Bright Star Catalogue · Milky Way: ESO/S. Brunier")

(defn- prepare-stars
  "The catalogue's flat [ra dec mag b-v …] as JS arrays: unit vectors in the equatorial frame of
  today (precessed from J2000), dot radius and rgba colour per star."
  [^js flat]
  (let [jd  (sky/julian-day (js/Date.now))
        n   (quot (.-length flat) 4)
        xyz (js/Float64Array. (* 3 n))
        size (js/Float64Array. n)
        colour (js/Array. n)]
    (dotimes [i n]
      (let [ra (aget flat (* 4 i)) dec (aget flat (+ 1 (* 4 i)))
            mag (aget flat (+ 2 (* 4 i))) bv (aget flat (+ 3 (* 4 i)))
            [x y z] (sky/unit (sky/precess [ra dec] jd))
            [r g b] (sky/bv->rgb bv)]
        (aset xyz (* 3 i) x) (aset xyz (+ 1 (* 3 i)) y) (aset xyz (+ 2 (* 3 i)) z)
        (aset size i (max 0.5 (min 2.8 (- 2.4 (* 0.32 mag)))))
        (aset colour i (str "rgba(" r "," g "," b "," (max 0.18 (min 1 (- 1.1 (* 0.13 mag)))) ")"))))
    #js {:n n :xyz xyz :size size :colour colour}))

(defn- load-panorama
  "A promise of the Milky Way panorama's pixels: #js {:w :h :px}."
  []
  (js/Promise.
   (fn [ok fail]
     (let [img (js/Image.)]
       (set! (.-onload img)
             (fn []
               (let [w (.-naturalWidth img) h (.-naturalHeight img)
                     c (doto (js/document.createElement "canvas") (set! -width w) (set! -height h))
                     ctx (.getContext c "2d" #js {:willReadFrequently true})]
                 (.drawImage ctx img 0 0)
                 (ok #js {:w w :h h :px (.-data (.getImageData ctx 0 0 w h))}))))
       (set! (.-onerror img) fail)
       (set! (.-src img) "/sky/milkyway.jpg")))))

(defonce ^:private sky-data
  ;; stars and Milky Way, loaded once, the first time 3D is shown
  (delay (js/Promise.all
          #js [(-> (js/fetch "/sky/stars.json") (.then #(.json %)) (.then prepare-stars))
               (load-panorama)])))

(defonce ^:private milkyway-buffer
  ;; the small canvas the Milky Way is rendered into before being scaled up
  (delay (js/document.createElement "canvas")))

(def ^:private milkyway-step "The Milky Way is rendered at 1/step of the screen resolution: it's a soft glow." 4)

(defn- draw-milkyway!
  "The panorama as seen through the camera: each buffer pixel's view ray -> galactic coordinates -> pixel."
  [^js ctx ^js pano eq f w h]
  (let [bw (js/Math.ceil (/ w milkyway-step))
        bh (js/Math.ceil (/ h milkyway-step))
        ^js buf @milkyway-buffer
        _   (when (or (not= bw (.-width buf)) (not= bh (.-height buf)))
              (set! (.-width buf) bw) (set! (.-height buf) bh))
        bctx (.getContext buf "2d")
        img (.createImageData bctx bw bh)
        out (.-data img)
        src (.-px pano) pw (.-w pano) ph (.-h pano)
        [dx dy dz] (sky/to-galactic (:view eq))
        [rx ry rz] (sky/to-galactic (:right eq))
        [ux uy uz] (sky/to-galactic (:up eq))
        cx (/ w 2) cy (/ h 2)
        two-pi (* 2 js/Math.PI)]
    (dotimes [j bh]
      (let [b (- (/ (- (* (+ j 0.5) milkyway-step) cy) f))]
        (dotimes [i bw]
          (let [a  (/ (- (* (+ i 0.5) milkyway-step) cx) f)
                x  (+ dx (* a rx) (* b ux))
                y  (+ dy (* a ry) (* b uy))
                z  (+ dz (* a rz) (* b uz))
                l  (js/Math.atan2 y x)
                gb (js/Math.asin (/ z (js/Math.sqrt (+ (* x x) (* y y) (* z z)))))
                u  (let [u (- 0.5 (/ l two-pi))] (- u (js/Math.floor u)))
                v  (- 0.5 (/ gb js/Math.PI))
                ;; bilinear between the four nearest panorama pixels (wrapping round in longitude)
                fx (- (* u pw) 0.5)
                fy (max 0 (min (- ph 1) (- (* v ph) 0.5)))
                x0 (js/Math.floor fx)
                y0 (js/Math.floor fy)
                tx (- fx x0)
                ty (- fy y0)
                xa (mod x0 pw)
                xb (mod (inc x0) pw)
                ya (* y0 pw)
                yb (* (min (dec ph) (inc y0)) pw)
                o  (* 4 (+ (* j bw) i))]
            (dotimes [k 3]
              (let [p00 (aget src (+ k (* 4 (+ ya xa)))) p10 (aget src (+ k (* 4 (+ ya xb))))
                    p01 (aget src (+ k (* 4 (+ yb xa)))) p11 (aget src (+ k (* 4 (+ yb xb))))]
                (aset out (+ o k) (+ (* (- 1 ty) (+ p00 (* tx (- p10 p00)))) (* ty (+ p01 (* tx (- p11 p01))))))))
            (aset out (+ o 3) 255)))))
    (.putImageData bctx img 0 0)
    (set! (.-imageSmoothingEnabled ctx) true)
    (.drawImage ctx buf 0 0 w h)))

(def ^:private body-scale
  "Sun and moon are drawn this many times their true size: true size is a few pixels, easy to miss.
  Their positions and the moon's phase stay exact."
  3)

(defn- draw-sun!
  "The sun: its real-size disc in a glow (a camera looking at the sun is dazzled, slightly)."
  [^js ctx [x y] r]
  (let [glow (* r 8)
        g    (.createRadialGradient ctx x y 0 x y glow)]
    (doseq [[at col] [[0 "rgba(255,252,240,1)"] [0.06 "rgba(255,246,220,.95)"] [0.18 "rgba(255,226,170,.35)"]
                      [0.5 "rgba(255,200,130,.08)"] [1 "rgba(255,190,120,0)"]]]
      (.addColorStop g at col))
    (set! (.-fillStyle ctx) g)
    (.fillRect ctx (- x glow) (- y glow) (* 2 glow) (* 2 glow))
    (set! (.-fillStyle ctx) "#fffdf6")
    (.beginPath ctx)
    (.arc ctx x y r 0 (* 2 js/Math.PI))
    (.fill ctx)))

(defn- draw-moon!
  "The moon: real-size disc, lit on the side facing the sun (`limb`: that side's screen angle) by its
  `phase` angle (0 full, 180 new); the dark part faintly (earthshine)."
  [^js ctx [x y] r phase limb]
  (let [a  (* r (js/Math.abs (js/Math.cos (* phase (/ js/Math.PI 180)))))
        pi js/Math.PI]
    (.save ctx)
    (.translate ctx x y)
    (.rotate ctx limb)
    (set! (.-fillStyle ctx) "rgba(70,74,86,.55)")
    (.beginPath ctx) (.arc ctx 0 0 r 0 (* 2 pi)) (.fill ctx)
    (set! (.-fillStyle ctx) "#f1eee4")
    (.beginPath ctx)
    (.arc ctx 0 0 r (- (/ pi 2)) (/ pi 2))                 ; the sunlit half...
    (if (< phase 90)
      (.ellipse ctx 0 0 a r 0 (/ pi 2) (* 1.5 pi))           ; ...and more: gibbous
      (.ellipse ctx 0 0 a r 0 (/ pi 2) (- (/ pi 2)) true))  ; ...less: crescent
    (.fill ctx)
    (.restore ctx)))

(defn- draw-sun-moon!
  "Sun and moon where they are right now. The moon is near enough that it matters where the camera is:
  seen from the camera's place in space (sky/camera-position), with its phase; both `body-scale` times
  their apparent size."
  [^js ctx cam {:keys [lat lng zoom f w h g]}]
  (let [jd      (+ (sky/julian-day (js/Date.now)) sky/delta-t)
        {sra :ra sdec :dec} (sky/sun jd)
        sun-dir (sky/to-earth-fixed (sky/unit [sra sdec]) g)
        {mra :ra mdec :dec km :km} (sky/moon jd)
        moon-km (mapv #(* km %) (sky/to-earth-fixed (sky/unit [mra mdec]) g))
        cam-km  (mapv #(* sky/earth-radius-km %) (sky/camera-position cam lat lng zoom f))
        to-moon (mapv - moon-km cam-km)
        dist    (js/Math.sqrt (sky/dot to-moon to-moon))
        screen  (fn [v] (when-let [[x y] (sky/project v cam f)] [(+ (/ w 2) x) (+ (/ h 2) y)]))
        near?   (fn [[x y] margin] (and (> x (- margin)) (< x (+ w margin)) (> y (- margin)) (< y (+ h margin))))
        px-of   (fn [deg] (* f (js/Math.tan (* deg (/ js/Math.PI 180)))))]
    (when-let [p (screen sun-dir)]
      (let [r (max 6 (* body-scale (px-of sky/sun-radius-deg)))]
        (when (near? p (* 8 r)) (draw-sun! ctx p r))))
    (when-let [p (screen to-moon)]
      (let [r (max 6 (* body-scale (px-of (/ (js/Math.asin (/ sky/moon-radius-km dist)) (/ js/Math.PI 180)))))]
        (when (near? p r)
          ;; the lit side faces the sun: a point a little towards the sun from the moon, on screen
          (let [toward (screen (mapv + (mapv #(/ % dist) to-moon) (mapv #(* 0.01 %) sun-dir)))
                limb   (if toward (js/Math.atan2 (- (second toward) (second p)) (- (first toward) (first p))) 0)]
            (draw-moon! ctx p r (sky/moon-phase moon-km cam-km sun-dir) limb)))))))

(defn- draw-sky!
  "Milky Way and stars as the camera sees them right now, on `canvas` under MapLibre's canvas (the
  globe hides what's behind it). Cleared close up."
  [^js m ^js canvas ^js data]
  (let [^js el (.getContainer m)
        w   (.-clientWidth el)
        h   (.-clientHeight el)
        dpr (or js/window.devicePixelRatio 1)
        [from to] sky-fade
        fade (max 0 (min 1 (/ (- to (.getZoom m)) (- to from))))
        ^js ctx (.getContext canvas "2d")]
    (when (or (not= (.-width canvas) (js/Math.round (* dpr w))) (not= (.-height canvas) (js/Math.round (* dpr h))))
      (set! (.-width canvas) (js/Math.round (* dpr w)))
      (set! (.-height canvas) (js/Math.round (* dpr h))))
    (.setTransform ctx 1 0 0 1 0 0)
    (.clearRect ctx 0 0 (.-width canvas) (.-height canvas))
    (when (and data (pos? fade) (pos? w) (pos? h))
      (let [c   (.getCenter m)
            g   (sky/gmst (sky/julian-day (js/Date.now)))
            cam (sky/camera (.-lat c) (.-lng c) (.getBearing m) (.getPitch m))
            ;; the camera's frame among the stars: Earth-fixed, turned by the sidereal time
            eq  (update-vals cam #(sky/to-equatorial % g))
            f   (sky/focal (.getVerticalFieldOfView m) h)
            cx  (/ w 2)
            cy  (/ h 2)
            ^js stars (aget data 0)
            xyz (.-xyz stars) size (.-size stars) colour (.-colour stars)
            [rx ry rz] (:right eq) [ux uy uz] (:up eq) [vx vy vz] (:view eq)]
        (.setTransform ctx dpr 0 0 dpr 0 0)
        (set! (.-globalAlpha ctx) (* 0.8 fade))
        (draw-milkyway! ctx (aget data 1) eq f w h)
        (set! (.-globalAlpha ctx) fade)
        (dotimes [i (.-n stars)]
          (let [x (aget xyz (* 3 i)) y (aget xyz (+ 1 (* 3 i))) z (aget xyz (+ 2 (* 3 i)))
                d (+ (* x vx) (* y vy) (* z vz))]
            (when (pos? d)
              (let [sx (+ cx (* f (/ (+ (* x rx) (* y ry) (* z rz)) d)))
                    sy (- cy (* f (/ (+ (* x ux) (* y uy) (* z uz)) d)))
                    r  (aget size i)]
                (when (and (> sx -4) (< sx (+ w 4)) (> sy -4) (< sy (+ h 4)))
                  (set! (.-fillStyle ctx) (aget colour i))
                  (if (< r 1.1)
                    (.fillRect ctx (- sx (/ r 2)) (- sy (/ r 2)) r r)
                    (do (.beginPath ctx)
                        (.arc ctx sx sy r 0 (* 2 js/Math.PI))
                        (.fill ctx))))))))
        (draw-sun-moon! ctx cam {:lat (.-lat c) :lng (.-lng c) :zoom (.getZoom m) :f f :w w :h h :g g})
        (set! (.-globalAlpha ctx) 1)))))

(defn- credit!
  "The 3D credit line: ground, terrain, and the sky while it's visible."
  [^js node ^js m]
  (set! (.-innerHTML (.-__credit node))
        (str (if (= "satellite" (.-__ground node)) esri-credit osm-credit) " · " terrain-credit
             (when (< (.getZoom m) (second sky-fade)) (str " · " sky-credit)))))

(defn- set-ground-3d! [^js node ^js m ground]
  (let [{:keys [osm sat bg shade tint] :as g} (ground-3d ground)]
    (.setLayoutProperty m "osm" "visibility" osm)
    (.setLayoutProperty m "sat" "visibility" sat)
    (doseq [[k v] tint] (.setPaintProperty m "osm" k v))
    (.setPaintProperty m "bg" "background-color" bg)
    (.setPaintProperty m "shade" "hillshade-exaggeration" shade)
    (.setSky m (sky-props (:sky g)))
    (set! (.-__ground node) ground)
    (credit! node m)))

(defn- fit-control
  "The \"show all activities\" button, as a MapLibre control."
  [fit!]
  (let [div (js/document.createElement "div")
        b   (js/document.createElement "button")]
    (set! (.-className div) "maplibregl-ctrl maplibregl-ctrl-group")
    (set! (.-type b) "button")
    (set! (.-title b) (t "Show all activities"))
    (.setAttribute b "aria-label" (t "Show all activities"))
    (set! (.-innerHTML b) charts/fit-icon)
    (.addEventListener b "click" fit!)
    (.appendChild div b)
    #js {:onAdd (fn [_] div) :onRemove (fn [_] (.remove div))}))

(defn- pin-collection
  "GeoJSON points (the activities' starts) for MapLibre's clustering."
  [tracks]
  (clj->js {:type "FeatureCollection"
            :features (for [^js tr tracks :let [st (.-start tr)] :when st]
                        {:type "Feature"
                         :properties {:id (:id (.-item tr)) :family (name (:id (ui/family (:sport (.-item tr)))))}
                         :geometry {:type "Point" :coordinates [(aget st 1) (aget st 0)]}})}))

(defn- selected-collection
  "GeoJSON of the highlighted activity's track (nothing without `item`)."
  [item]
  (clj->js {:type "FeatureCollection"
            :features (when item
                        [{:type "Feature"
                          :properties {:color (fam-color (ui/family (:sport item)))}
                          :geometry {:type "MultiLineString"
                                     :coordinates (for [seg (.-segs ^js (decoded-track item))]
                                                    (for [ll seg] [(aget ll 1) (aget ll 0)]))}}])}))

(defn- add-pin-layers!
  "The pins (clustered by MapLibre) and the highlighted track, as sources and layers."
  [^js m tracks pins?]
  (let [vis    #js {:visibility (if pins? "visible" "none")}
        colors (into ["match" ["get" "family"]]
                     (concat (mapcat (fn [f] [(name (:id f)) (fam-color f)]) ui/families) ["#8a90a0"]))]
    (.addSource m "pins" #js {:type "geojson" :data (pin-collection tracks) :cluster true
                              :clusterRadius pin-cell :clusterMaxZoom 16})
    (.addSource m "selected" #js {:type "geojson" :data (selected-collection nil)})
    (doseq [[id w c] [["selected-casing" 7 "#ffffff"] ["selected" 4 ["get" "color"]]]]
      (.addLayer m (clj->js {:id id :type "line" :source "selected"
                             :layout {"line-cap" "round" "line-join" "round"}
                             :paint {"line-color" c "line-width" w}})))
    (.addLayer m (clj->js {:id "pin-clusters" :type "circle" :source "pins" :filter ["has" "point_count"]
                           :layout vis
                           :paint {"circle-color" "#1fb6ff" "circle-opacity" 0.9
                                   "circle-radius" ["step" ["get" "point_count"] 10 10 14 100 19]
                                   "circle-stroke-width" 2.5 "circle-stroke-color" "#ffffff"}}))
    (.addLayer m (clj->js {:id "pin" :type "circle" :source "pins" :filter ["!" ["has" "point_count"]]
                           :layout vis
                           :paint {"circle-color" colors "circle-radius" 6
                                   "circle-stroke-width" 2 "circle-stroke-color" "#ffffff"}}))))

(defn- set-pins-visible! [^js m on?]
  (doseq [id ["pin-clusters" "pin"]]
    (.setLayoutProperty m id "visibility" (if on? "visible" "none"))))

(defn- open-popup-3d!
  "Popup for `items` at `lnglat`; a single activity also gets its track highlighted."
  [^js ml ^js node ^js m items lnglat]
  (when-let [^js old (.-__popup node)]
    (set! (.-__popup node) nil)
    (.remove old))
  (let [^js p (-> ^js (new (.-Popup ml) #js {:className "map-popup" :maxWidth "300px"})
                  (.setLngLat lnglat)
                  (.setDOMContent (popup-node items))
                  (.addTo m))]
    (set! (.-__popup node) p)
    (.on p "close" (fn []
                     (when (identical? p (.-__popup node))
                       (set! (.-__popup node) nil)
                       (.setData ^js (.getSource m "selected") (selected-collection nil)))))
    (.setData ^js (.getSource m "selected") (selected-collection (when (= 1 (count items)) (first items))))))

(defn- on-click-3d!
  "A click: a cluster zooms in (or lists its activities at one spot), a pin opens its popup, else the
  track under the point (within 10 px) if the heat shows."
  [^js ml ^js node ^js m ^js e]
  (let [^js f   (aget (.queryRenderedFeatures m (.-point e) #js {:layers #js ["pin-clusters" "pin"]}) 0)
        by-id   (fn [id] (some #(when (= id (:id (.-item ^js %))) (.-item ^js %)) (.-__tracks node)))
        coords  (some-> f .-geometry .-coordinates)
        xy      [(.. e -point -x) (.. e -point -y)]
        project (fn [la lo] (let [p (.project m #js [lo la])] #js [(.-x p) (.-y p)]))
        unproj  (fn [x y] (let [ll (.unproject m #js [x y])] #js [(.-lat ll) (.-lng ll)]))]
    (cond
      (and f (some? (.. f -properties -cluster_id)))
      (let [id (.. f -properties -cluster_id) ^js src (.getSource m "pins")]
        (-> (.getClusterExpansionZoom src id)
            (.then (fn [z]
                     (if (> z 16)
                       (-> (.getClusterLeaves src id 50 0)
                           (.then (fn [leaves]
                                    (open-popup-3d! ml node m
                                                    (keep #(by-id (.. ^js % -properties -id)) leaves) coords))))
                       (.easeTo m #js {:center coords :zoom z}))))))

      f (when-let [item (by-id (.. f -properties -id))] (open-popup-3d! ml node m [item] coords))

      (.-__heat node)
      (when-let [^js tr (nearest-track (.-__tracks node) (box-around unproj xy 10) project xy 10)]
        (open-popup-3d! ml node m [(.-item tr)] (.-lngLat e))))))

(defn- mount-3d! [^js ml ^js node tracks]
  (let [{:keys [lat lng zoom bearing pitch]} @camera
        ^js m (new (.-Map ml)
                   #js {:container node :style (style-3d (.-__ground node))
                        :center (if lat #js [lng lat] #js [8.2 46.8])
                        :zoom (cond zoom (dec zoom) (seq tracks) 6 :else 1.5)
                        :pitch (or pitch 60) :bearing (or bearing 0)
                        :minZoom 0 :maxPitch 80 :attributionControl false})
        canvas (js/document.createElement "canvas")
        stars  (js/document.createElement "canvas")
        credit (js/document.createElement "div")
        draw-sky #(draw-sky! m stars (.-__sky node))
        redraw #(when (.-__heat node)
                  (draw-3d! m canvas (.-__tracks node) (.-__palette node) (.-__ground node)))
        fit!   #(when-let [[s w n e] (all-bounds (.-__tracks node))]
                  (.fitBounds m #js [#js [w s] #js [e n]] #js {:padding 40}))]
    (set! (.-className credit) "map3d-credit")
    (.appendChild node credit)
    ;; under MapLibre's canvas: the space around the globe is transparent
    (set! (.-className stars) "sky-canvas")
    (.insertBefore node stars (.-firstChild node))
    (.on m "move" draw-sky)
    (.on m "resize" draw-sky)
    (set! (.-__skyTimer node) (js/setInterval draw-sky 60000)) ; the sky turns
    (.then @sky-data (fn [d] (set! (.-__sky node) d) (draw-sky)) #(js/console.error "Sky not loaded" %))
    (set! (.-__credit node) credit)
    (set! (.-__map node) m)
    (set! (.-__redraw node) redraw)
    (.addControl m (new (.-NavigationControl ml) #js {:visualizePitch true}) "top-right")
    (.addControl m (fit-control fit!) "top-right")
    (when js/document.fullscreenEnabled (.addControl m (fullscreen-control-3d node) "top-right"))
    (.on m "load"
         (fn []
           (set! (.-__loaded node) true)
           (set-ground-3d! node m (.-__ground node))
           (set! (.-width canvas) 1)
           (set! (.-height canvas) 1)
           (.addSource m "heat" #js {:type "canvas" :canvas canvas :animate false
                                     :coordinates #js [#js [0 1] #js [1 1] #js [1 0] #js [0 0]]})
           (.addLayer m #js {:id "heat" :type "raster" :source "heat"
                             :layout #js {:visibility (if (.-__heat node) "visible" "none")}
                             :paint #js {"raster-fade-duration" 0 "raster-resampling" "linear"}})
           (add-pin-layers! m (.-__tracks node) (.-__pins node))
           (when (and (nil? lat) (seq tracks)) (fit!))
           (redraw)
           (draw-sky)))
    (.on m "click" #(when (.-__loaded node) (on-click-3d! ml node m %)))
    (.on m "moveend"
         (fn []
           (let [c (.getCenter m)]
             (reset! camera {:lat (.-lat c) :lng (.-lng c) :zoom (inc (.getZoom m))
                             :bearing (.getBearing m) :pitch (.getPitch m)}))
           (credit! node m)
           (redraw)))
    (charts/observe-resize! node #(.resize m))))

(defn map-3d
  "The same heatmap on 3D terrain (drag to move, right-drag or ctrl-drag to tilt and turn).
  Props as `map-2d`."
  [{:keys [items version palette ground heat? pins?]}]
  [:div.map.heatmap.map3d
   {:replicant/key "heatmap-3d"
    :data-version (str version "|" palette "|" ground "|" heat? "|" pins?)
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)
            tracks   (mapv decoded-track items)]
        (case life-cycle
          :replicant.life-cycle/mount
          (do (set! (.-__tracks node) tracks)
              (set! (.-__palette node) palette)
              (set! (.-__ground node) ground)
              (set! (.-__heat node) heat?)
              (set! (.-__pins node) pins?)
              (set! (.-__version node) version)
              (-> @maplibre
                  (.then (fn [ml] (when-not (.-__gone node) (mount-3d! ml node tracks))))
                  (.catch (fn [e]
                            (js/console.error e)
                            (set! (.-textContent node) (t "The 3D map could not be loaded: {0}" (ex-message e)))))))

          :replicant.life-cycle/update
          (when (or (not= version (.-__version node)) (not= palette (.-__palette node))
                    (not= ground (.-__ground node)) (not= heat? (.-__heat node)) (not= pins? (.-__pins node)))
            (set! (.-__version node) version)
            (set! (.-__tracks node) tracks)
            (set! (.-__palette node) palette)
            (set! (.-__pins node) pins?)
            (when-let [^js m (.-__map node)]
              ;; before "load" the layers don't exist yet; the load handler applies __ground and __heat
              (when (.-__loaded node)
                (when (not= ground (.-__ground node)) (set-ground-3d! node m ground))
                (some-> (.-__popup node) .remove)
                (.setData ^js (.getSource m "pins") (pin-collection tracks))
                (set-pins-visible! m pins?)
                (.setLayoutProperty m "heat" "visibility" (if heat? "visible" "none"))))
            (set! (.-__ground node) ground)
            (set! (.-__heat node) heat?)
            (when-let [redraw (.-__redraw node)] (redraw)))

          :replicant.life-cycle/unmount
          (do (set! (.-__gone node) true)
              (some-> (.-__skyTimer node) js/clearInterval)
              (some-> (.-__ro node) .disconnect)
              (some-> (.-__map node) .remove))
          nil)))}])
