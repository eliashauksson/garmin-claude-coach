(ns coach.prominence
  "Pure. Topographic prominence of a summit over an elevation grid: how far it stands above the
  lowest saddle on the best path to higher ground. Used to tell real summits from the countless
  bumps OpenStreetMap tags as peaks.

  A grid is `{:w :h :elev <double-array, row-major> :cx :cy :res}`: width and height in pixels,
  the pixel of the OSM node, and metres per pixel. An optional `:noise` overrides `noise-m`.")

(def noise-m
  "A pixel counts as higher ground only when it is this much above the summit (DEM noise)."
  3.0)

(def snap-m
  "The OSM node is rarely on the highest pixel: the summit is the highest pixel within this distance."
  60.0)

(defn- elev ^double [{:keys [^doubles elev w]} x y]
  (aget elev (+ x (* y (long w)))))

(def climb-m
  "A node on a steep flank is walked uphill to the top of its bump, at most this far."
  250.0)

(defn- highest-near
  "The highest pixel within `r` pixels of [cx cy]."
  [{:keys [w h] :as grid} cx cy r]
  (reduce (fn [best [x y]]
            (if (> (elev grid x y) (elev grid (best 0) (best 1))) [x y] best))
          [cx cy]
          (for [y (range (max 0 (- cy r)) (min h (inc (+ cy r))))
                x (range (max 0 (- cx r)) (min w (inc (+ cx r))))
                :when (<= (+ (* (- x cx) (- x cx)) (* (- y cy) (- y cy))) (* r r))]
            [x y])))

(defn- summit-pixel
  "The top of the node's bump: the highest pixel within `snap-m`, then uphill by steepest ascent
  (at most `climb-m`). Without the climb a node a little off the top of a steep peak would count as a
  point on the slope, and the flood would simply walk up the same mountain."
  [{:keys [cx cy res] :as grid}]
  (let [r (max 1 (long (Math/ceil (/ snap-m (double res)))))
        steps (max 1 (long (Math/ceil (/ climb-m (double res)))))]
    (loop [[x y] (highest-near grid cx cy r) n 0]
      (let [[nx ny] (highest-near grid x y 1)]
        (if (and (< n steps) (> (elev grid nx ny) (elev grid x y)))
          (recur [nx ny] (inc n))
          [x y])))))

(defn- key-of
  "Priority-queue entry: the elevation (quantised, 1/8 m) above, the pixel index below."
  ^long [^double e ^long idx]
  (+ (bit-shift-left (long (Math/round (* 8.0 (+ e 12000.0)))) 28) idx))

(defn prominence
  "Prominence of the summit near the grid's centre pixel, by a highest-first flood: pixels are taken in
  descending height, and the lowest one taken before reaching higher ground is the key col.
  Returns `{:prominence m :summit m :bound? bool}`. `:bound?` is true when the flood hit the grid's
  edge before finding higher ground: the true prominence is at least the returned value (any path
  to higher ground crosses the edge, and the flood has already found the best path to it)."
  [{:keys [w h] :as grid}]
  (let [w (long w) h (long h)
        noise (double (or (:noise grid) noise-m))
        [sx sy] (summit-pixel grid)
        summit (elev grid sx sy)
        queue (java.util.PriorityQueue. 1024 (comparator (fn [a b] (> (long a) (long b)))))
        seen (boolean-array (* w h))
        push! (fn [x y]
                (let [i (+ x (* y w))]
                  (when-not (aget seen i)
                    (aset seen i true)
                    (.add queue (key-of (elev grid x y) i)))))]
    (push! sx sy)
    (loop [low summit]
      (if-let [k (.poll queue)]
        (let [idx (bit-and (long k) (dec (bit-shift-left 1 28)))
              x (rem idx w) y (quot idx w)
              e (elev grid x y)]
          (cond
            (> e (+ summit noise))
            {:prominence (- summit low) :summit summit :bound? false}

            (or (zero? x) (zero? y) (= x (dec w)) (= y (dec h)))
            {:prominence (- summit (min low e)) :summit summit :bound? true}

            :else
            (do (doseq [dy [-1 0 1] dx [-1 0 1] :when (not (and (zero? dx) (zero? dy)))]
                  (push! (+ x dx) (+ y dy)))
                (recur (min low e)))))
        {:prominence 0.0 :summit summit :bound? true}))))
