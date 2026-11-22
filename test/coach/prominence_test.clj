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
