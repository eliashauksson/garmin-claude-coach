(ns coach.prominence-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.prominence :as prominence]))

(defn- grid
  "A w×h grid with height (f x y), the node at the centre, 20 m per pixel."
  [w h f]
  (let [a (double-array (* w h))]
    (doseq [y (range h) x (range w)] (aset a (+ x (* y w)) (double (f x y))))
    {:w w :h h :elev a :cx (quot w 2) :cy (quot h 2) :res 20.0}))

(defn- cone [cx cy peak slope]
  (fn [x y] (max 0.0 (- peak (* slope (Math/sqrt (+ (* (- x cx) (- x cx)) (* (- y cy) (- y cy)))))))))

(deftest lone-summit
  (testing "a cone on flat ground reaching the edge: a lower bound, its height above the flat"
    (let [r (prominence/prominence (grid 61 61 (cone 30 30 100 10.0)))]
      (is (< (Math/abs (- 100.0 (:prominence r))) 1e-6))
      (is (:bound? r) "no higher ground inside the window"))))

(deftest sub-summit-on-a-ridge
  (testing "a 140 m summit joined to a 200 m summit by a 110 m col has 30 m prominence"
    (let [ridge (fn [x y]
                  (let [on-ridge (<= (Math/abs (- y 30)) 1)]
                    (cond
                      (not on-ridge) 0.0
                      (<= x 30) (+ 110.0 (* 30.0 (- 1.0 (/ (Math/abs (- x 30)) 30.0)))) ; 110 at x=0, 140 at x=30
                      :else (min 200.0 (+ 110.0 (* 3.0 (Math/abs (- x 40)))))))) ; col of 110 at x=40
          g (grid 90 61 ridge)
          r (prominence/prominence (assoc g :cx 30 :cy 30))]
      (is (< (Math/abs (- 30.0 (:prominence r))) 1e-6) (str r))
      (is (not (:bound? r))))))

(deftest rolling-terrain-is-not-a-peak
  (testing "a 10 m bump with a deep valley in the window and higher ground only outside it stays small"
    ;; ground falls away east and west, a valley 60 m deep fills the north; the best way out is the
    ;; flat ground to the south, 10 m below the bump. Summit minus the window's minimum would say 70 m.
    (let [f (fn [x y]
              (+ 100.0 (* -0.5 (Math/abs (- x 30.0)))
                 (max 0.0 (- 10.0 (* 5.0 (Math/sqrt (+ (* (- x 30.0) (- x 30.0)) (* (- y 30.0) (- y 30.0)))))))
                 (if (< y 10) -60.0 0.0)))
          r (prominence/prominence (grid 61 61 f))]
      (is (:bound? r))
      (is (< (:prominence r) 30.0) (str r))
      (is (< (Math/abs (- 10.0 (:prominence r))) 1e-6) (str r)))))

(deftest highest-point-in-the-window
  (testing "the highest ground in the window is only bounded from below"
    (let [r (prominence/prominence (grid 41 41 (cone 20 20 500 10.0)))]
      (is (:bound? r))
      (is (> (:prominence r) 100.0)))))

(deftest node-off-the-top
  (testing "a node on a steep flank, 100 m from the top, still finds the whole summit"
    (let [g (grid 81 81 (cone 40 40 400 40.0))] ; 40 m a pixel: steep
      (is (< (Math/abs (- 400.0 (:prominence (prominence/prominence (assoc g :cx 45 :cy 40))))) 1e-6))
      (is (< (Math/abs (- 400.0 (:prominence (prominence/prominence (assoc g :cx 40 :cy 43))))) 1e-6)))))

;; ---------------------------------------------------------------------------
;; The old way: a flood over a window of 3 km, started again over one of 10 km when it was still bounded.
;; The new way is one flood over the big window; the two must agree on everything.

(defn- ref-summit-pixel [{:keys [w h cx cy res] :as grid}]
  (let [elev (fn [x y] (aget ^doubles (:elev grid) (+ x (* y (long w)))))
        near (fn [cx cy r]
               (reduce (fn [best [x y]] (if (> (elev x y) (elev (best 0) (best 1))) [x y] best))
                       [cx cy]
                       (for [y (range (max 0 (- cy r)) (min h (inc (+ cy r))))
                             x (range (max 0 (- cx r)) (min w (inc (+ cx r))))
                             :when (<= (+ (* (- x cx) (- x cx)) (* (- y cy) (- y cy))) (* r r))]
                         [x y])))
        r (max 1 (long (Math/ceil (/ prominence/snap-m (double res)))))
        steps (max 1 (long (Math/ceil (/ prominence/climb-m (double res)))))]
    (loop [[x y] (near cx cy r) n 0]
      (let [[nx ny] (near x y 1)]
        (if (and (< n steps) (> (elev nx ny) (elev x y))) (recur [nx ny] (inc n)) [x y])))))

(defn- ref-prominence [{:keys [w h] :as grid}]
  (let [w (long w) h (long h)
        elev (fn [x y] (aget ^doubles (:elev grid) (+ x (* y w))))
        [sx sy] (ref-summit-pixel grid)
        summit (elev sx sy)
        key-of (fn [e idx] (+ (bit-shift-left (long (Math/round (* 8.0 (+ e 12000.0)))) 28) idx))
        queue (java.util.PriorityQueue. 1024 (comparator (fn [a b] (> (long a) (long b)))))
        seen (boolean-array (* w h))
        push! (fn [x y] (let [i (+ x (* y w))]
                          (when-not (aget seen i) (aset seen i true) (.add queue (key-of (elev x y) i)))))]
    (push! sx sy)
    (loop [low summit]
      (if-let [k (.poll queue)]
        (let [idx (bit-and (long k) (dec (bit-shift-left 1 28)))
              x (rem idx w) y (quot idx w) e (elev x y)]
          (cond
            (> e (+ summit prominence/noise-m)) {:prominence (- summit low) :bound? false}
            (or (zero? x) (zero? y) (= x (dec w)) (= y (dec h))) {:prominence (- summit (min low e)) :bound? true}
            :else (do (doseq [dy [-1 0 1] dx [-1 0 1] :when (not (and (zero? dx) (zero? dy)))]
                        (push! (+ x dx) (+ y dy)))
                      (recur (min low e)))))
        {:prominence 0.0 :bound? true}))))

(defn- sub-grid
  "The window of `half` pixels around the centre of a bigger grid, as a grid of its own."
  [big half]
  (let [bw (long (:w big)) c (:cx big) w (inc (* 2 half))
        a (double-array (* w w))]
    (dotimes [y w] (dotimes [x w]
                     (aset a (+ x (* y w))
                          (aget ^doubles (:elev big) (+ (- c half) x (* (+ (- c half) y) bw))))))
    {:w w :h w :elev a :cx half :cy half :res (:res big)}))

(defn- ref-windows
  "What the series of windows gave: the small window's answer unless that was bounded."
  [big small-half]
  (let [p (ref-prominence (sub-grid big small-half))]
    (if (:bound? p) (ref-prominence big) p)))

(defn- terrain
  "Random ground: a few cones (sometimes flat-topped), whole metres so heights tie, and some noise."
  [^java.util.Random rnd half res]
  (let [w (inc (* 2 half))
        cones (vec (repeatedly (+ 1 (.nextInt rnd 5))
                               #(let [r (fn [] (.nextInt rnd w))]
                                  {:x (r) :y (r) :peak (+ 20 (.nextInt rnd 200))
                                   :slope (+ 0.5 (* 4.0 (.nextDouble rnd)))
                                   :flat (.nextInt rnd 6)})))
        noise (* 3.0 (.nextDouble rnd))
        f (fn [x y]
            (Math/round
             (double (+ (reduce max 0.0 (for [{cx :x cy :y p :peak s :slope fl :flat} cones]
                                          (- p (* s (max 0.0 (- (Math/hypot (- x cx) (- y cy)) fl))))))
                        (* noise (.nextDouble rnd))))))
        a (double-array (* w w))]
    (dotimes [y w] (dotimes [x w] (aset a (+ x (* y w)) (double (f x y)))))
    {:w w :h w :elev a :cx half :cy half :res res}))

(deftest one-big-flood-is-the-series-of-windows
  (let [rnd (java.util.Random. 42)
        bounded (atom 0)]
    (dotimes [_ 400]
      (let [res (nth [8.0 20.0 40.0] (.nextInt rnd 3))
            half (+ 40 (.nextInt rnd 30))
            small (+ 8 (.nextInt rnd 20))
            g (terrain rnd half res)
            old (ref-windows g small)
            new (prominence/prominence g)]
        (when (:bound? new) (swap! bounded inc))
        (is (= (:prominence old) (:prominence new)) (str "res " res " half " half " small " small))
        (is (= (:bound? old) (:bound? new)))))
    (is (< 20 @bounded 380) "the random ground covers both kinds of answer")))

(deftest elevation-reader-is-the-same-as-an-array
  (let [g (terrain (java.util.Random. 7) 50 20.0)
        read (reify coach.prominence.Elevation
               (at [_ x y] (aget ^doubles (:elev g) (+ x (* y (long (:w g)))))))]
    (is (= (prominence/prominence g) (prominence/prominence (-> g (dissoc :elev) (assoc :at read)))))))
