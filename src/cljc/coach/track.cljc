(ns coach.track
  "Pure. GPS tracks for the heatmap: split at gaps, simplified (Douglas–Peucker)
  and stored as Google encoded polylines (precision 5, ~1 m).")

(def gap-m "A jump longer than this between two fixes starts a new segment (lost GPS, tunnel)." 500.0)
(def tolerance-m "Douglas–Peucker tolerance: points closer than this to the simplified line go." 8.0)

(defn- xy
  "Metres on a local equirectangular projection around `lat0`."
  [lat0 [la lo]]
  (let [r 6371000.0
        k (/ #?(:clj Math/PI :cljs js/Math.PI) 180.0)]
    [(* r k lo (Math/cos (* k lat0))) (* r k la)]))

(defn- dist [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- bx ax) (- bx ax)) (* (- by ay) (- by ay)))))

(defn- seg-dist
  "Distance from p to the segment a-b."
  [p a b]
  (let [[px py] p [ax ay] a [bx by] b
        dx (- bx ax) dy (- by ay)
        l2 (+ (* dx dx) (* dy dy))]
    (if (zero? l2)
      (dist p a)
      (let [u (max 0.0 (min 1.0 (/ (+ (* (- px ax) dx) (* (- py ay) dy)) l2)))]
        (dist p [(+ ax (* u dx)) (+ ay (* u dy))])))))

(defn- dp
  "Indices kept by Douglas–Peucker over the projected points `ps` (iterative, no deep recursion)."
  [ps tol]
  (let [n (count ps)]
    (loop [stack [[0 (dec n)]] keep #{0 (dec n)}]
      (if-let [[i j] (peek stack)]
        (let [stack (pop stack)
              [far d] (reduce (fn [[bi bd] k]
                                (let [d (seg-dist (ps k) (ps i) (ps j))]
                                  (if (> d bd) [k d] [bi bd])))
                              [nil 0.0] (range (inc i) j))]
          (if (and far (> d tol))
            (recur (conj stack [i far] [far j]) (conj keep far))
            (recur stack keep)))
        (sort keep)))))

(defn segments
  "[lat lon] pairs (nils dropped) split where consecutive fixes are more than
  `gap-m` apart, each simplified to `tolerance-m`. Segments with fewer than two points are dropped."
  ([pts] (segments pts gap-m tolerance-m))
  ([pts gap tol]
   (let [pts (filterv (fn [[la lo]] (and (number? la) (number? lo))) pts)]
     (when (>= (count pts) 2)
       (let [lat0 (first (first pts))
             ps   (mapv #(xy lat0 %) pts)
             cuts (concat [0] (filter #(> (dist (ps (dec %)) (ps %)) gap) (range 1 (count ps))) [(count ps)])]
         (vec (for [[a b] (partition 2 1 cuts)
                    :when (>= (- b a) 2)
                    :let [sub (subvec ps a b)]]
                (mapv #(pts (+ a %)) (dp sub tol)))))))))

;; ---------------------------------------------------------------------------
;; Encoded polyline (https://developers.google.com/maps/documentation/utilities/polylinealgorithm)

(defn- round5 [x] (long (Math/round (* (double x) 1e5))))

(defn- encode-value [v]
  (let [v (if (neg? v) (bit-not (bit-shift-left v 1)) (bit-shift-left v 1))]
    (loop [v v out []]
      (if (>= v 0x20)
        (recur (unsigned-bit-shift-right v 5) (conj out (char (+ 63 (bit-or 0x20 (bit-and v 0x1f))))))
        (apply str (conj out (char (+ 63 v))))))))

(defn encode
  "[lat lon] pairs as an encoded polyline string."
  [pts]
  (loop [[[la lo] & more :as pts] pts plat 0 plon 0 out []]
    (if (empty? pts)
      (apply str out)
      (let [la (round5 la) lo (round5 lo)]
        (recur more la lo (conj out (encode-value (- la plat)) (encode-value (- lo plon))))))))

(defn decode
  "An encoded polyline string back into [lat lon] pairs."
  [s]
  (let [n (count s)
        code (fn [i] #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt s i)))
        value (fn [i]
                (loop [i i shift 0 acc 0]
                  (let [b (- (code i) 63)
                        acc (bit-or acc (bit-shift-left (bit-and b 0x1f) shift))]
                    (if (>= b 0x20)
                      (recur (inc i) (+ shift 5) acc)
                      [(inc i) (if (odd? acc) (bit-not (unsigned-bit-shift-right acc 1))
                                   (unsigned-bit-shift-right acc 1))]))))]
    (loop [i 0 la 0 lo 0 out []]
      (if (>= i n)
        out
        (let [[i dla] (value i)
              [i dlo] (value i)
              la (+ la dla) lo (+ lo dlo)]
          (recur i la lo (conj out [(/ la 1e5) (/ lo 1e5)])))))))
