(ns coach.prominence
  "Pure. Topographic prominence of a summit over an elevation grid: how far it stands above the
  lowest saddle on the best path to higher ground. Used to tell real summits from the countless
  bumps OpenStreetMap tags as peaks.

  A grid is `{:w :h :elev <double-array, row-major> :cx :cy :res}`: width and height in pixels,
  the pixel of the OSM node, and metres per pixel. Instead of `:elev`, `:at` may hold an `Elevation`
  that answers for any pixel (the caller reads its tiles only where the flood gets to). An optional
  `:noise` overrides `noise-m`.

  The flood only looks at the pixels it needs, so the biggest window costs no more than a small one when
  higher ground is near: one pass over the biggest window gives the answer a pass over each of a series
  of growing windows would (the small flood is the start of the big one).")

(definterface Elevation
  (^double at [^long x ^long y]))

(definterface Heap
  (offer [^long k])
  (^long poll [])
  (^long size []))

(def noise-m
  "A pixel counts as higher ground only when it is this much above the summit (DEM noise)."
  3.0)

(def snap-m
  "The OSM node is rarely on the highest pixel: the summit is the highest pixel within this distance."
  60.0)

(def climb-m
  "A node on a steep flank is walked uphill to the top of its bump, at most this far."
  250.0)

(defn- array-elevation
  ^coach.prominence.Elevation [^doubles a ^long w]
  (reify Elevation
    (at [_ x y] (aget a (+ x (* y w))))))

(defn- sift-up! [^longs a ^long i ^long k]
  (loop [i i]
    (if (pos? i)
      (let [p (unsigned-bit-shift-right (dec i) 1)
            pk (aget a p)]
        (if (> k pk)
          (do (aset a i pk) (recur p))
          (aset a i k)))
      (aset a 0 k))))

(defn- sift-down! [^longs a ^long n ^long k]
  (loop [i 0]
    (let [l (inc (* 2 i))]
      (if (< l n)
        (let [r (inc l)
              c (if (and (< r n) (> (aget a r) (aget a l))) r l)
              ck (aget a c)]
          (if (> ck k)
            (do (aset a i ck) (recur c))
            (aset a i k)))
        (aset a i k)))))

(deftype LongHeap [^:unsynchronized-mutable ^longs a ^:unsynchronized-mutable ^long n]
  Heap
  (offer [_ k]
    (when (== n (alength a)) (set! a (java.util.Arrays/copyOf a (* 2 n))))
    (sift-up! a n k)
    (set! n (inc n)))
  (poll [_]
    (let [top (aget a 0)
          last-key (aget a (dec n))]
      (set! n (dec n))
      (when (pos? n) (sift-down! a n last-key))
      top))
  (size [_] n))

(defn- highest-near
  "The highest pixel within `r` pixels of [cx cy]."
  [^Elevation el w h cx cy r]
  (reduce (fn [best [x y]]
            (if (> (.at el x y) (.at el (best 0) (best 1))) [x y] best))
          [cx cy]
          (for [y (range (max 0 (- cy r)) (min h (inc (+ cy r))))
                x (range (max 0 (- cx r)) (min w (inc (+ cx r))))
                :when (<= (+ (* (- x cx) (- x cx)) (* (- y cy) (- y cy))) (* r r))]
            [x y])))

(defn- summit-pixel
  "The top of the node's bump: the highest pixel within `snap-m`, then uphill by steepest ascent
  (at most `climb-m`). Without the climb a node a little off the top of a steep peak would count as a
  point on the slope, and the flood would simply walk up the same mountain."
  [^Elevation el w h cx cy res]
  (let [w (long w) h (long h)
        r (max 1 (long (Math/ceil (/ snap-m (double res)))))
        steps (max 1 (long (Math/ceil (/ climb-m (double res)))))]
    (loop [[x y] (highest-near el w h cx cy r) n 0]
      (let [[nx ny] (highest-near el w h x y 1)]
        (if (and (< n steps) (> (.at el nx ny) (.at el x y)))
          (recur [nx ny] (inc n))
          [x y])))))

(defn- key-of
  "Priority-queue entry: the elevation (quantised, 1/8 m) above, the pixel index below."
  ^long [^double e ^long idx]
  (+ (bit-shift-left (long (Math/round (* 8.0 (+ e 12000.0)))) 28) idx))

(def ^:private idx-mask (dec (bit-shift-left 1 28)))

(defn prominence
  "Prominence of the summit near the grid's centre pixel, by a highest-first flood: pixels are taken in
  descending height, and the lowest one taken before reaching higher ground is the key col.
  Returns `{:prominence m :summit m :bound? bool}`. `:bound?` is true when the flood hit the grid's
  edge before finding higher ground: the true prominence is at least the returned value (any path
  to higher ground crosses the edge, and the flood has already found the best path to it)."
  [{:keys [w h cx cy res] :as grid}]
  (let [w (long w) h (long h)
        noise (double (or (:noise grid) noise-m))
        ^Elevation el (or (:at grid) (array-elevation (:elev grid) w))
        [sx sy] (summit-pixel el w h cx cy res)
        sx (long sx) sy (long sy)
        summit (.at el sx sy)
        limit (+ summit noise)
        ^Heap heap (LongHeap. (long-array 1024) 0)
        ^booleans seen (boolean-array (* w h))
        last-x (dec w) last-y (dec h)
        start (+ sx (* sy w))]
    (aset seen start true)
    (.offer heap (key-of summit start))
    (loop [low summit]
      (if (zero? (.size heap))
        {:prominence 0.0 :summit summit :bound? true}
        (let [k (.poll heap)
              idx (bit-and k idx-mask)
              x (rem idx w) y (quot idx w)
              e (.at el x y)]
          (cond
            (> e limit)
            {:prominence (- summit low) :summit summit :bound? false}

            (or (zero? x) (zero? y) (== x last-x) (== y last-y))
            {:prominence (- summit (Math/min low e)) :summit summit :bound? true}

            :else
            (do (dotimes [d 9]
                  (when-not (== d 4)
                    (let [nx (+ x (dec (rem d 3))) ny (+ y (dec (quot d 3))) ni (+ nx (* ny w))]
                      (when-not (aget seen ni)
                        (aset seen ni true)
                        (.offer heap (key-of (.at el nx ny) ni))))))
                (recur (Math/min low e)))))))))
