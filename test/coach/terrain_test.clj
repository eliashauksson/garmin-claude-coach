(ns coach.terrain-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.terrain :as terrain]))

(def ^:private z 13)
(def ^:private lat 46.5)
(def ^:private lon 8.0)
(def ^:private origin (mapv #(long (Math/floor %)) (terrain/world-px z lat lon)))

(defn- grid
  "A synthetic grid: `f` gets the offset [dx dy] in pixels (6.6 m each) from the summit's pixel."
  [f]
  (let [[ox oy] origin]
    (fn [px py] (double (f (- px ox) (- py oy))))))

(defn- cone
  "A cone of `top` m at offset [cx cy], falling `slope` m a pixel."
  [cx cy top slope]
  (fn [dx dy] (- top (* slope (Math/hypot (- dx cx) (- dy cy))))))

(defn- ground [& shapes]
  (fn [dx dy] (apply max 1000.0 (map #(% dx dy) shapes))))

(defn- offset
  "How many pixels east and south of the summit's pixel a snap landed."
  [{:keys [lat lon]}]
  (mapv (fn [p o] (- (long (Math/floor p)) o)) (terrain/world-px z lat lon) origin))

(defn- snap [f] (terrain/snap (grid f) z lat lon))

(deftest decoding
  (is (= 0.0 (terrain/terrarium 128 0 0)))
  (is (= 4158.5 (terrain/terrarium 144 62 128)))
  (is (= -10.0 (terrain/terrarium 127 246 0))))

(deftest mercator
  (let [[x y] (terrain/world-px 13 46.5378 7.9625)
        [la lo] (terrain/lat-lon 13 x y)]
    (is (< (Math/abs (- la 46.5378)) 1e-9))
    (is (< (Math/abs (- lo 7.9625)) 1e-9)))
  (is (= [0 0] (terrain/tile-at 0 46.5 8.0)))
  (is (= [33 22] (terrain/tile-at 6 46.5 8.0)))
  (is (< 6.4 (terrain/m-per-px 13 46.5) 6.8)))

(deftest zoom-by-coverage
  (is (= 13 (terrain/zoom-for {[33 22] 18} 46.5 8.0)))
  (is (= 11 (terrain/zoom-for {[33 22] 12} 46.5 8.0)))
  (is (= 11 (terrain/zoom-for {[33 22] 18} 27.99 86.92))))

(deftest circle-tiles
  (testing "inside a tile"
    (let [[la lo] (terrain/lat-lon z (* 512 4000.5) (* 512 2900.5))]
      (is (= [[4000 2900]] (terrain/tiles-for-circle z la lo 100.0)))))
  (testing "at a corner: all four"
    (let [[la lo] (terrain/lat-lon z (* 512 4000) (* 512 2900))]
      (is (= #{[3999 2899] [3999 2900] [4000 2899] [4000 2900]}
             (set (terrain/tiles-for-circle z la lo 100.0))))))
  (testing "across the date line"
    (is (= #{0 8191} (set (map first (terrain/tiles-for-circle z 10.0 180.0 100.0)))))))

(deftest stitching
  (let [tile (fn [v] (float-array (* 512 512) v))
        a (tile 1) b (tile 2)
        _ (aset b (+ 0 (* 512 3)) (float 7))
        at (terrain/stitched z {[10 20] a [11 20] b})]
    (is (= 1.0 (at (+ (* 512 10) 511) (* 512 20))))
    (is (= 7.0 (at (* 512 11) (+ (* 512 20) 3))))
    (is (nil? (at (* 512 12) (* 512 20))))))

(deftest an-offset-top-is-found
  (let [s (snap (ground (cone 8 -5 1200 5)))]
    (is (= [8 -5] (offset s)))
    (is (= 1200.0 (:ele s)))
    (is (< 40 (:gain s) 50))
    (is (< 55 (:dist_m s) 70))))

(deftest nothing-clearly-higher-stays
  (testing "flat"
    (is (nil? (snap (ground)))))
  (testing "a plateau with noise below the minimum gain"
    (is (nil? (snap (fn [dx dy] (if (= [dx dy] [6 6]) 1002 1000))))))
  (testing "already on top"
    (is (nil? (snap (ground (cone 0 0 1200 5)))))))

(deftest ground-rising-past-the-circle-is-no-top
  (is (nil? (snap (fn [dx _] (+ 1000 (* 2 dx)))))))

(deftest a-higher-neighbour-beyond-a-saddle-is-not-taken
  (testing "own top at the summit, a higher one 80 m east over a 40 m deep saddle"
    (is (nil? (snap (ground (cone 0 0 1100 12) (cone 12 0 1150 15))))))
  (testing "twin tops: each keeps its own, though the other is higher"
    (is (= [-2 0] (offset (snap (ground (cone -2 0 1100 12) (cone 12 0 1150 15))))))))

(deftest a-point-belonging-to-a-neighbour-is-dropped
  (let [summits [{:id 1 :lat 46.5 :lon 8.0} {:id 2 :lat 46.5 :lon 8.0012}]
        east {:lat 46.5 :lon 8.001 :dist_m 76.0}
        near {:lat 46.5 :lon 8.0003 :dist_m 23.0}]
    (is (= {} (terrain/own-snaps summits {1 east})))
    (is (= {1 near} (terrain/own-snaps summits {1 near})))))
