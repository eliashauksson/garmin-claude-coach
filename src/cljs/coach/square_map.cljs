(ns coach.square-map
  "The Peaks page's download map, like an offline-maps screen: the world in 1° squares on a Leaflet map.
  A tap on a free square selects it; a square that is waiting, loading or ready says so and can be
  cancelled while it waits. Squares are drawn on one canvas, only those in view, and from zoom
  `grid-zoom` (further out only the squares that have a state)."
  (:require ["leaflet" :as L]
            [clojure.string :as str]
            [coach.charts :as charts]
            [coach.heat :as heat]
            [coach.i18n :refer [t]]))

(def squares-url "/api/peaks/squares")

(def grid-zoom "From this zoom the whole grid is drawn, so any square can be tapped." 4)

(defn- bounds-of-box [[s w n e]] #js [#js [s w] #js [n e]])

(defn- bounds [k]
  (let [[la lo] (map js/parseInt (str/split k #","))]
    #js [#js [la lo] #js [(inc la) (inc lo)]]))

(defn- block-of
  "The 10° dataset block a 1° square is in (\"46,8\" -> \"40_0\"), as `coach.summits/square-block`."
  [k]
  (let [[la lo] (map js/parseInt (str/split k #","))]
    (str (* 10 (js/Math.floor (/ la 10))) "_" (* 10 (js/Math.floor (/ lo 10))))))

(defn- block-bounds [b]
  (let [[la lo] (map js/parseInt (str/split b #"_"))]
    #js [#js [la lo] #js [(+ la 10) (+ lo 10)]]))

(defn- derived-state
  "The state of a square nobody asked for, from the world download's blocks: peaks (block loaded, no names)
  or pending (block still to come)."
  [{:keys [loaded pending]} k]
  (let [b (block-of k)]
    (cond (contains? loaded b)  "peaks"
          (contains? pending b) "pending")))

(defn- style
  "Leaflet path options of a square: [selected? state]."
  [renderer selected? state]
  (let [c (charts/css-var (case state "ready" "--green" "peaks" "--green" "names" "--blue" "failed" "--red"
                                "queued" "--amber" "pending" "--amber" "--text-3"))
        base #js {:renderer renderer :weight 1 :color c :opacity 0.8 :fillColor c :fillOpacity 0}]
    (cond
      selected?          (doto base (aset "fillColor" (charts/css-var "--amber")) (aset "fillOpacity" 0.45)
                           (aset "color" (charts/css-var "--amber")) (aset "weight" 2))
      (= "ready" state)  (doto base (aset "fillOpacity" 0.3))
      (= "names" state) (doto base (aset "fillOpacity" 0.3))
      (= "peaks" state)  (doto base (aset "fillOpacity" 0.12) (aset "opacity" 0.4) (aset "weight" 0.5))
      (= "pending" state) (doto base (aset "dashArray" "4 3") (aset "fillOpacity" 0.06) (aset "opacity" 0.5)
                            (aset "weight" 0.5))
      (= "queued" state) (doto base (aset "dashArray" "4 3") (aset "weight" 2) (aset "fillOpacity" 0.1))
      (= "failed" state) (doto base (aset "weight" 2) (aset "fillOpacity" 0.1))
      :else              (doto base (aset "opacity" 0.35) (aset "weight" 0.5)))))

(defn- state-text [{:keys [state message]}]
  (case state
    "queued"  (t "Waiting to download")
    "failed"  (t "The map server is busy; trying again later. ({0})" (or message ""))
    "names"   (t "Peaks loaded, adding names")
    "ready"   (t "Downloaded")
    ""))

(defn- popup [{:keys [square state peaks bagged requested] :as sq}]
  (let [div (js/document.createElement "div")]
    (set! (.-className div) "square-popup")
    (set! (.-innerHTML div)
          (str "<b>" (t "Square {0}" square) "</b><br>" (state-text sq)
               (when (#{"names" "ready"} state) (str "<br>" (t "{0} peaks · {1} bagged" peaks bagged)))))
    (when (and requested (#{"queued" "failed"} state))
      (let [b (js/document.createElement "button")]
        (set! (.-className b) "btn-ghost")
        (set! (.-textContent b) (t "Cancel"))
        (.addEventListener b "click"
                           (fn [_] (when-let [d @heat/action-handler]
                                     (d [:api "DELETE" (str squares-url "/" square) nil]))))
        (.appendChild div b)))
    div))

(defn- redraw!
  "Draws the squares in view onto the node's grid layer from the latest props."
  [^js node]
  (let [^js st (.-__sq node)
        ^js m (.-map st) ^js layer (.-grid st) renderer (.-renderer st)
        {:keys [squares selected world]} (.-props st)
        by-key (into {} (map (juxt :square identity)) squares)
        wb {:loaded (set (:loaded_blocks world)) :pending (set (:pending_blocks world))}
        zoom (.getZoom m)
        keys (if (>= zoom grid-zoom)
               (let [b (.getBounds m)
                     la0 (max -85 (js/Math.floor (.getSouth b))) la1 (min 85 (js/Math.ceil (.getNorth b)))
                     lo0 (max -180 (js/Math.floor (.getWest b))) lo1 (min 180 (js/Math.ceil (.getEast b)))]
                 (for [la (range la0 la1) lo (range lo0 lo1)] (str la "," lo)))
               (map :square squares))]
    (.clearLayers layer)
    ;; zoomed out the world download shows as 10° blocks (a square each would be thousands of rectangles)
    (when (< zoom grid-zoom)
      (doseq [[b state] (concat (map vector (:loaded wb) (repeat "peaks"))
                                (map vector (:pending wb) (repeat "pending")))]
        (.addTo (L/rectangle (block-bounds b) (doto (style renderer false state) (aset "interactive" false)))
                layer)))
    (doseq [k keys
            :let [sq (get by-key k)
                  state (or (:state sq) (when (>= zoom grid-zoom) (derived-state wb k)))
                  rect (L/rectangle (bounds k) (style renderer (contains? selected k) state))]]
      (.on rect "click"
           (fn [^js e]
             (.stop (.-DomEvent L) e)
             (if (and sq (:requested sq))
               (.openPopup (.bindPopup rect (popup sq) #js {:closeButton false}))
               (when-let [d @heat/action-handler] (d [:square-toggle k])))))
      (.addTo rect layer))))

(defn- outline! [^js node]
  (let [^js st (.-__sq node) ^js m (.-map st) {:keys [outline fly fly-id]} (.-props st)]
    (some-> (.-outline st) (.remove))
    (set! (.-outline st) nil)
    (when outline
      (set! (.-outline st)
            (.addTo (L/geoJSON (clj->js outline) #js {:interactive false :style #js {:color (charts/css-var "--amber")
                                                                                    :weight 2 :fill false}})
                    m)))
    (when (and fly (not= fly-id (.-flyId st)))
      (set! (.-flyId st) fly-id)
      (.fitBounds m (bounds-of-box fly) #js {:padding #js [20 20] :maxZoom 8}))))

(defn view
  "`squares` from /api/peaks/squares, `selected` a set of keys, `outline` GeoJSON of the place searched for,
  `fly` its [s w n e] box with `fly-id` (a new id flies there once), `world` the whole-world download's status
  (its loaded and pending blocks are drawn too)."
  [{:keys [squares selected outline fly fly-id world]}]
  [:div.square-map
   {:replicant/key "square-map"
    :data-sig (str (hash [squares selected outline fly-id (:loaded world) (:pending_blocks world)]))
    :replicant/on-render
    (fn [{:replicant/keys [life-cycle] :as hook}]
      (let [^js node (:replicant/node hook)
            props {:squares squares :selected selected :outline outline :fly fly :fly-id fly-id :world world}]
        (case life-cycle
          :replicant.life-cycle/mount
          (let [^js m (L/map node #js {:zoomControl false :attributionControl false :worldCopyJump false
                                       :minZoom 2 :maxBounds #js [#js [-85 -180] #js [85 180]]})
                st #js {:map m :renderer (L/canvas #js {:padding 0.5}) :grid (L/layerGroup) :props props}]
            (.addTo (L/tileLayer "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                                 #js {:maxZoom 12 :className "map-tiles"})
                    m)
            (.addTo (.. L -control (zoom #js {:position "topright"})) m)
            (.addTo (doto (.. L -control (attribution #js {:prefix false}))
                      (.addAttribution "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>"))
                    m)
            (set! (.-__sq node) st)
            (.addTo (.-grid st) m)
            (if (seq squares)
              (.fitBounds m (let [la (map #(first (map js/parseInt (str/split (:square %) #","))) squares)
                                  lo (map #(second (map js/parseInt (str/split (:square %) #","))) squares)]
                              #js [#js [(apply min la) (apply min lo)] #js [(inc (apply max la)) (inc (apply max lo))]])
                          #js {:padding #js [20 20] :maxZoom 6})
              (.setView m #js [30 10] 2))
            (.on m "moveend" #(redraw! node))
            (charts/observe-resize! node #(.invalidateSize m))
            (outline! node)
            (redraw! node))
          :replicant.life-cycle/update
          (when-let [^js st (.-__sq node)]
            (set! (.-props st) props)
            (outline! node)
            (redraw! node))
          :replicant.life-cycle/unmount
          (do (some-> (.-__ro node) .disconnect)
              (some-> (.-__sq node) .-map .remove))
          nil)))}])
