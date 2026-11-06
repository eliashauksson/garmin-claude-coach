(ns coach.sky-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.sky :as sky]))

(defn- near? [a b tol] (< (abs (- a b)) tol))

(deftest sidereal-time
  (testing "Meeus example 12.a: 1987 April 10, 0h UT -> 13h10m46.3668s"
    (is (near? 197.693195 (sky/gmst 2446895.5) 1e-5)))
  (testing "Julian day of the Unix epoch"
    (is (= 2440587.5 (sky/julian-day 0)))))

(deftest precession
  (testing "Meeus example 21.b (theta Persei, J2000 -> 2028 Nov 13.19)"
    (let [[ra dec] (sky/precess [41.054063 49.227750] 2462088.69)]
      (is (near? 41.547214 ra 2e-4))
      (is (near? 49.348483 dec 2e-4)))))

(deftest camera-and-projection
  (testing "straight down at 0/0: looking at the Earth's centre, north up, east right"
    (let [{:keys [right up view]} (sky/camera 0 0 0 0)]
      (is (every? true? (map #(near? %1 %2 1e-12) view [-1 0 0])))
      (is (every? true? (map #(near? %1 %2 1e-12) up [0 0 1])))
      (is (every? true? (map #(near? %1 %2 1e-12) right [0 1 0])))))
  (testing "the view direction projects to the centre; up is up on screen; behind is nil"
    (let [cam (sky/camera 46.6 7.9 165 68)
          f   (sky/focal 36.87 600)]
      (is (every? #(near? % 0 1e-9) (sky/project (:view cam) cam f)))
      (is (neg? (second (sky/project (mapv + (:view cam) (map #(* 0.1 %) (:up cam))) cam f))))
      (is (nil? (sky/project (mapv - (:view cam)) cam f)))))
  (testing "a star overhead at the map's centre, rotated into Earth-fixed coordinates, is straight up"
    (let [g    100.0
          star (sky/unit [(+ 30 g) 20])
          up   (sky/unit [30 20])]
      (is (every? true? (map #(near? %1 %2 1e-12) (sky/to-equatorial up g) star))))))

(deftest milky-way
  (testing "the galactic centre is the middle of the panorama, the galactic north pole its top"
    (let [[x y] (sky/panorama-xy (sky/to-galactic (sky/unit [266.405 -28.936])))]
      (is (near? x 0.5 1e-3))
      (is (near? y 0.5 1e-3)))
    (is (near? 0.0 (second (sky/panorama-xy (sky/to-galactic (sky/unit [192.859 27.128])))) 1e-3)))
  (testing "the Large Magellanic Cloud (l 280.5, b -32.9) is right of centre, below the plane"
    (let [[x y] (sky/panorama-xy (sky/to-galactic (sky/unit [80.894 -69.756])))]
      (is (near? x 0.721 2e-3))
      (is (near? y 0.683 2e-3)))))

(deftest star-colours
  (is (= [202 215 255] (sky/bv->rgb 0.0)))
  (is (= [155 176 255] (sky/bv->rgb -1.0)))
  (is (= [255 166 102] (sky/bv->rgb 3.0)))
  (is (= [255 244 234] (sky/bv->rgb 0.6))))

(deftest sun-and-moon
  (testing "Meeus example 25.a: the sun on 1992 October 13.0 TD"
    (let [{:keys [ra dec]} (sky/sun 2448908.5)]
      (is (near? 198.38083 ra 0.01))
      (is (near? -7.78507 dec 0.01))))
  (testing "Meeus example 47.a: the moon on 1992 April 12, 0h TD"
    (let [{:keys [ra dec km]} (sky/moon 2448724.5)]
      (is (near? 134.688470 ra 0.02))
      (is (near? 13.768368 dec 0.02))
      (is (near? 368409.7 km 30))))
  (testing "Earth-fixed and equatorial are inverse rotations"
    (let [v (sky/unit [123 -45])]
      (is (every? true? (map #(near? %1 %2 1e-12) v (sky/to-earth-fixed (sky/to-equatorial v 77) 77))))))
  (testing "the camera sits above the centre, farther out when zoomed out"
    (let [cam (sky/camera 0 0 0 0)
          p1  (sky/camera-position cam 0 0 1 800)
          p3  (sky/camera-position cam 0 0 3 800)]
      (is (> (first p1) (first p3) 1))
      (is (near? 0 (second p1) 1e-12))))
  (testing "phase: the sun behind the observer is a full moon, the sun behind the moon a new one"
    (is (near? 0 (sky/moon-phase [384400 0 0] [0 0 0] [-1 0 0]) 1e-6))
    (is (near? 180 (sky/moon-phase [384400 0 0] [0 0 0] [1 0 0]) 1e-6))))
