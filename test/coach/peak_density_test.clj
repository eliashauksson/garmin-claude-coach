(ns coach.peak-density-test
  (:require [clojure.test :refer [deftest is]]
            [coach.peak-density :as d]))

(deftest density-grows-with-zoom
  (is (= [1500 1500 1000 1000 500 300 150 100 50 0] (mapv d/min-prominence [0 2 3 4 5 6 7 8 9 10])))
  (is (= 1500 (d/min-prominence -1)) "below the world view, as at it")
  (is (= 0 (d/min-prominence 16))))

(deftest what-is-drawn
  (is (d/shown? true 5 4) "a bagged peak always shows")
  (is (not (d/shown? false 40 9)))
  (is (d/shown? false 40 10))
  (is (d/shown? false 35 12)))

(deftest bands-agree-with-the-rule
  (let [tiers (d/tiers)
        in-band? (fn [{:keys [zoom min max]} p z] (and (>= z zoom) (>= p min) (or (nil? max) (< p max))))]
    (is (= [[0 1500 nil] [3 1000 1500] [5 500 1000] [6 300 500] [7 150 300] [8 100 150] [9 50 100] [10 0 50]]
           (mapv (juxt :zoom :min :max) tiers)))
    (doseq [z (range 0 17) p [0 20 49 50 99 100 149 150 299 300 499 500 999 1000 1499 1500 2000]]
      (is (= (d/shown? false p z)
             (= 1 (count (filter #(in-band? % p z) tiers))))
          (str "zoom " z ", prominence " p)))))
