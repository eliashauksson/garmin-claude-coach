(ns coach.readiness-score-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.readiness :as readiness]
            [coach.readiness-score :as rs]))

(def p rs/defaults)

(defn- near [a b tol] (<= (Math/abs (double (- a b))) tol))
(defn- all-near [as bs tol] (and (= (count as) (count bs)) (every? true? (map #(near %1 %2 tol) as bs))))

(deftest factors
  (testing "sleep: Garmin's fixed mapping of the sleep score"
    (is (= [35.0 55.0 70.0 95.0] (mapv rs/sleep-pct [50 70 80 95])))
    (is (nil? (rs/sleep-pct nil))))
  (testing "HRV: 6 ms below the bottom of the normal range"
    (is (near 31.3 (rs/hrv-pct p -6) 0.01))
    (is (= (rs/hrv-pct p 4) (rs/hrv-pct p 10)) "capped above the range"))
  (testing "recovery: workouts don't add up, the longest remainder wins"
    (is (= (rs/recovery-hours p [[0 100]]) (rs/recovery-hours p [[0 100] [0 50]])))
    (is (near (- (* 0.37 (Math/pow 161 0.95)) 10) (rs/recovery-hours p [[10 161]]) 1e-9))
    (is (zero? (rs/recovery-hours p [[200 50]])) "long over")
    (is (near 63 (rs/recovery-pct p 24) 1) "Garmin: 24.4 h -> 61"))
  (testing "acute load fades and only costs above its threshold"
    (is (> (rs/acute-load p [[0 100]]) (rs/acute-load p [[5 100]])))
    (is (= 100.0 (rs/load-pct p 100)))
    (is (< (rs/load-pct p 400) 70))))

(deftest score
  (testing "Garmin's own factors on 3 Nov 2026 (Garmin: 77)"
    (is (near 77 (rs/combine p {:sleep 70 :recovery_time 99 :hrv 50 :acwr 100 :sleep_history 66 :stress_history 70})
              5)))
  (testing "levels use Garmin's boundaries"
    (is (= ["POOR" "LOW" "MODERATE" "HIGH" "PRIME"] (mapv rs/level [24 25 50 75 95]))))
  (let [f {:sleep [78 80 79] :hrv_delta -6 :loads [] :workouts [] :stress48 44.0}]
    (testing "an estimate with its factors and what holds it back"
      (let [e (rs/estimate p f)]
        (is (<= 1 (:score e) 100))
        (is (:estimate e))
        (is (not (:partial e)))
        (is (= "hrv" (first (:limiting e))))))
    (testing "without last night's sleep score there's no estimate"
      (is (nil? (rs/estimate p (assoc f :sleep [nil 80 79])))))
    (testing "a missing factor is left out, and the estimate says so"
      (let [e (rs/estimate p (assoc f :stress48 nil))]
        (is (:partial e))
        (is (nil? (get-in e [:factors :stress_history])))))))

(deftest ols
  (is (all-near [2.0 -3.0 5.0]
              (rs/ols [[1 0] [0 1] [1 1] [2 1] [3 5]]
                      (map (fn [[a b]] (+ (* 2 a) (* -3 b) 5)) [[1 0] [0 1] [1 1] [2 1] [3 5]])) 1e-9)))

(deftest fit-recovers-known-parameters
  (let [rnd   (java.util.Random. 7)
        r     (fn [lo hi] (+ lo (* (.nextDouble rnd) (- hi lo))))
        truth (assoc p :sleep-history [0.5 0.5 0.0] :stress [-2.0 150.0] :hrv [70.0 5.0 4.0])
        rows  (vec (for [_ (range 90)
                         :let [f {:sleep    (vec (repeatedly 3 #(Math/round (r 30 95))))
                                  :hrv_delta (Math/round (r -8 8))
                                  :loads    [[(r 0 3) (r 0 300)]]
                                  :workouts [[(r 0 30) (r 20 200)]]
                                  :stress48 (r 25 50)}
                               c (rs/components truth f)]]
                     {:features f
                      :garmin   {:score      (rs/combine truth c)
                                 :recovery_h (:recovery_h c)
                                 :factors    (select-keys c rs/factor-keys)}}))
        fitted (rs/fit p rows)]
    (is (all-near [0.5 0.5 0.0] (:sleep-history fitted) 0.01))
    (is (all-near [-2.0 150.0] (:stress fitted) 0.01))
    (is (< (:mae (rs/evaluate fitted rows)) 2.0))
    (is (<= (:mae (rs/evaluate fitted rows)) (:mae (rs/evaluate p rows))))))

(deftest morning-check-says-when-it-is-an-estimate
  (let [day {:readiness 20 :readiness_estimated true}]
    (is (re-find #"(?i)estimated" (first (:signals (readiness/check [day] [] {})))))
    (is (not (re-find #"(?i)estimated"
                      (first (:signals (readiness/check [(dissoc day :readiness_estimated)] [] {}))))))))
