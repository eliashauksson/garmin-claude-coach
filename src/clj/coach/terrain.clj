(ns coach.terrain
  "Pure. Mapterhorn's elevation tiles (Web Mercator, 512 px, Terrarium encoding) and the search for the highest
  point near a summit of the prominence dataset, whose elevation model is coarser, so that its top is often
  tens of metres off the real one. Pixels are counted over the whole world of a zoom; pixel [x y] covers
  [x, x+1) × [y, y+1) and has its height at the centre.")

(def tile-size "Pixels along a tile's side." 512)

(def snap-radius-m "A summit moves at most this far (m)." 150.0)
(def snap-min-gain-m "…and only to a point at least this much higher than its own position on the same terrain (m)." 3.0)
(def snap-max-dip-m
  "…reached in a straight line that never drops this far below that height (m): beyond a saddle it is another top."
  10.0)

(def fine-zoom "The zoom used where Mapterhorn has finer data than the global 30 m (6.6 m a pixel at 46° N)." 13)
(def coarse-zoom "The zoom used where there is only the global 30 m data (26 m a pixel at 46° N)." 11)

(defn terrarium
  "Metres of a Terrarium pixel."
  ^double [^long r ^long g ^long b]
  (- (+ (* 256.0 r) g (/ b 256.0)) 32768.0))

;; ---------------------------------------------------------------------------
;; Web Mercator

(defn- world-size ^double [z] (* (double tile-size) (bit-shift-left 1 (long z))))

(defn world-px
  "[x y] (doubles) of a position in the world's pixels at zoom `z`."
  [z lat lon]
  (let [n (world-size z)
        la (Math/toRadians (double lat))]
    [(* n (/ (+ (double lon) 180.0) 360.0))
     (* n (/ (- 1.0 (/ (Math/log (+ (Math/tan la) (/ 1.0 (Math/cos la)))) Math/PI)) 2.0))]))

(defn lat-lon
  "[lat lon] of a world pixel position at zoom `z`: the inverse of `world-px`."
  [z x y]
  (let [n (world-size z)]
    [(Math/toDegrees (Math/atan (Math/sinh (* Math/PI (- 1.0 (/ (* 2.0 (double y)) n))))))
     (- (* 360.0 (/ (double x) n)) 180.0)]))

(defn m-per-px
  "Metres a pixel of zoom `z` covers at `lat`."
  ^double [z lat]
  (/ (* 40075016.686 (Math/cos (Math/toRadians (double lat)))) (world-size z)))

(defn tile-at
  "[x y] of the tile of zoom `z` a position is in."
  [z lat lon]
  (let [[x y] (world-px z lat lon)]
    [(long (Math/floor (/ (double x) tile-size))) (long (Math/floor (/ (double y) tile-size)))]))

(defn zoom-for
  "The zoom to snap a position at. `fine`: {[x y] max-zoom} of the zoom-6 tiles with finer data
  (Mapterhorn's regional archives)."
  [fine lat lon]
  (let [z (get fine (tile-at 6 lat lon))]
    (if (and z (>= z fine-zoom)) fine-zoom coarse-zoom)))

(defn tiles-for-circle
  "The tiles [x y] a search circle touches, with the ring of pixels around it that `snap` looks at; x wraps at
  the date line."
  [z lat lon radius-m]
  (let [[x y] (world-px z lat lon)
        r (+ (/ (double radius-m) (m-per-px z lat)) 2.0)
        n (bit-shift-left 1 (long z))
        idx (fn [p] (long (Math/floor (/ (double p) tile-size))))]
    (vec (distinct (for [tx (range (idx (- x r)) (inc (idx (+ x r))))
                         ty (range (idx (- y r)) (inc (idx (+ y r))))
                         :when (< -1 ty n)]
                     [(mod tx n) ty])))))

(defn stitched
  "The tiles of zoom `z` ({[x y] floats, row by row}) as one grid: (fn [px py]) giving the height at a world
  pixel, nil where there is no tile."
  [z tiles]
  (let [world (* tile-size (bit-shift-left 1 (long z)))]
    (fn [px py]
      (let [px (mod (long px) world)
            py (long py)]
        (when-let [^floats t (get tiles [(quot px tile-size) (Math/floorDiv py (long tile-size))])]
          (double (aget t (+ (mod px tile-size) (* tile-size (Math/floorMod py (long tile-size)))))))))))

;; ---------------------------------------------------------------------------
;; The highest point near a summit

(def ^:private around [[-1 -1] [0 -1] [1 -1] [-1 0] [1 0] [-1 1] [0 1] [1 1]])

(defn- line-min
  "The lowest height on the straight line between two pixels, sampled every half pixel."
  [at [ax ay] [bx by]]
  (let [steps (max 1 (long (Math/ceil (* 2.0 (Math/hypot (- (double bx) ax) (- (double by) ay))))))]
    (reduce (fn [low i]
              (let [f (/ (double i) steps)
                    between (fn [a b] (long (Math/floor (+ a 0.5 (* f (- b a))))))
                    h (at (between ax bx) (between ay by))]
                (if h (min low (double h)) low)))
            Double/MAX_VALUE
            (range (inc steps)))))

(defn- top?
  "Whether no neighbour of a pixel is higher, also one outside the search circle."
  [at px py h]
  (every? (fn [[dx dy]] (let [n (at (+ px dx) (+ py dy))] (or (nil? n) (<= n h)))) around))

(defn snap
  "The summit's top on the grid `at` of zoom `z`: the highest point within `radius-m` of lat/lon that no
  neighbouring pixel overtops (ground still rising past the circle is no top), is clearly higher than the
  position itself (`:min-gain-m`) and is reached without crossing a saddle (`:max-dip-m`): beyond one is
  another summit. {:lat :lon :ele :gain :dist_m}, or nil when the summit stays where it is. `opts` override
  the defaults."
  [at z lat lon & [opts]]
  (let [{:keys [radius-m min-gain-m max-dip-m]}
        (merge {:radius-m snap-radius-m :min-gain-m snap-min-gain-m :max-dip-m snap-max-dip-m} opts)
        [x y] (world-px z lat lon)
        x (double x) y (double y)
        ox (long (Math/floor x)) oy (long (Math/floor y))
        m (m-per-px z lat)
        r (/ (double radius-m) m)
        reach (long (Math/ceil r))]
    (when-let [here (at ox oy)]
      (let [tops (for [py (range (- oy reach) (+ oy reach 1))
                       px (range (- ox reach) (+ ox reach 1))
                       :let [d (Math/hypot (- (+ px 0.5) x) (- (+ py 0.5) y))]
                       :when (<= d r)
                       :let [h (at px py)]
                       :when (and h (>= (- h here) min-gain-m) (top? at px py h))]
                   [h d px py])
            ;; highest first, of equal ones the nearest
            [h d px py] (first (filter (fn [[_ _ px py]] (>= (line-min at [ox oy] [px py]) (- here max-dip-m)))
                                       (sort-by (fn [[h d]] [(- h) d]) tops)))]
        (when h
          (let [[la lo] (lat-lon z (+ px 0.5) (+ py 0.5))]
            {:lat la :lon lo :ele h :gain (- h here) :dist_m (* d m)}))))))

;; ---------------------------------------------------------------------------
;; Not onto a neighbour

(defn distance-m
  "Metres between two positions (flat: for distances of some hundred metres)."
  ^double [lat1 lon1 lat2 lon2]
  (let [k (/ Math/PI 180.0)]
    (Math/hypot (* 6371000.0 k (- (double lon2) lon1) (Math/cos (* k (/ (+ (double lat1) lat2) 2.0))))
                (* 6371000.0 k (- (double lat2) lat1)))))

(def ^:private row-deg 0.002) ; more than `snap-radius-m` of latitude (222 m)

(defn own-snaps
  "`snaps` ({id snap}) without those that ended up nearer to another summit's original position than to their
  own: that top is the other one's. `summits`: [{:id :lat :lon}], the snapped ones and all their neighbours."
  [summits snaps]
  (let [row (fn [lat] (long (Math/floor (/ (double lat) row-deg))))
        rows (group-by #(row (:lat %)) summits)]
    (into {}
          (filter (fn [[id {:keys [lat lon dist_m]}]]
                    (let [r (row lat)]
                      (not-any? (fn [s] (and (not= id (:id s))
                                             (< (distance-m lat lon (:lat s) (:lon s)) dist_m)))
                                (mapcat rows [(dec r) r (inc r)])))))
          snaps)))
