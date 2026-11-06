(ns coach.track-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.track :as track]))

(deftest encode-decode
  (testing "the reference example from Google's documentation"
    (is (= "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
           (track/encode [[38.5 -120.2] [40.7 -120.95] [43.252 -126.453]]))))
  (testing "round trip within 1e-5"
    (let [pts [[47.37689 8.54169] [47.37702 8.5419] [-33.85678 151.21528] [0.0 0.0]]]
      (is (every? true? (map (fn [[a b] [c d]] (and (< (abs (- a c)) 1e-5) (< (abs (- b d)) 1e-5)))
                             pts (track/decode (track/encode pts))))))))

(deftest segments
  (testing "a straight line collapses to its endpoints"
    (let [line (for [i (range 50)] [(+ 47.0 (* i 1e-4)) 8.0])]
      (is (= [[[47.0 8.0] (last line)]] (track/segments line)))))
  (testing "a corner is kept"
    (let [pts (concat (for [i (range 20)] [(+ 47.0 (* i 1e-4)) 8.0])
                      (for [i (range 1 20)] [47.0019 (+ 8.0 (* i 1e-4))]))]
      (is (= 3 (count (first (track/segments pts)))))))
  (testing "nils are dropped and a GPS jump splits the track"
    (let [segs (track/segments [[47.0 8.0] [nil nil] [47.0001 8.0] [48.0 8.0] [48.0001 8.0]])]
      (is (= 2 (count segs)))))
  (testing "fewer than two fixes: nothing"
    (is (nil? (track/segments [[47.0 8.0] [nil 8.0]])))))
