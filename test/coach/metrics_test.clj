(ns coach.metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.metrics :as m]))

(defn- close? [a b tol] (and a b (< (Math/abs (- (double a) (double b))) tol)))

(def thresholds
  {:lthr 170 :max_hr 190 :rest_hr 50 :sex "MALE"
   :hr_zones [98 118 137 157 176] :threshold_speed 3.5555})

(defn- stream [n f]
  (let [rows (map f (range n))]
    {:t (vec (range n))
     :hr (mapv :hr rows) :speed (mapv :speed rows) :power (mapv :power rows)
     :alt (mapv :alt rows) :dist (mapv :dist rows)}))

(deftest resample-test
  (testing "short gaps forward-fill, long gaps are pauses"
    (is (= {:hr [100 110 110 120 130]}
           (m/resample {:t [0 1 3 20] :hr [100 110 120 130]}))))
  (testing "duplicate timestamps keep the later sample"
    (is (= {:hr [2 3]} (m/resample {:t [0 0 1] :hr [1 2 3]})))))

(deftest rolling-mean-test
  (is (= [2.0 3.0 4.0] (m/rolling-mean [1 2 3 4 5] 3)))
  (is (= [1.5] (m/rolling-mean [1 2] 3)))
  (is (= [] (m/rolling-mean [] 3))))

(deftest normalized-test
  (is (close? 200 (m/normalized (repeat 3600 200)) 1e-9))
  (testing "variable output normalises above its average"
    (let [xs (flatten (repeat 30 (concat (repeat 60 300) (repeat 60 100))))]
      (is (> (m/normalized xs) 200)))))

(deftest stress-score-test
  (is (close? 100 (m/tss 3600 1.0) 1e-9))
  (is (close? 25 (m/tss 3600 0.5) 1e-9))
  (is (close? 100 (m/stss 3600 1.0) 1e-9))
  (testing "one hour at LTHR = 100 hrTSS"
    (is (close? 100 (m/hrtss (repeat 3600 170) thresholds) 1e-6)))
  (is (< (m/hrtss (repeat 3600 140) thresholds) 100))
  (is (close? 50 (m/hrtss-estimate 170 1800 thresholds) 1e-6))
  (is (nil? (m/hrtss (repeat 10 150) (dissoc thresholds :lthr)))))

(deftest minetti-test
  (is (close? 1.0 (m/minetti-factor 0.0) 1e-9))
  (is (> (m/minetti-factor 0.1) 1.5))
  (is (< (m/minetti-factor -0.1) 1.0)))

(deftest time-in-zones-test
  (is (= [1 1 1 1 1 2] (m/time-in-zones [90 100 120 140 160 176 190 nil] [98 118 137 157 176]))))

(deftest activity-metrics-test
  (testing "1 h flat run at threshold pace ≈ 100 rTSS"
    (let [s (stream 3600 (fn [i] {:hr 170 :speed 3.5555 :alt 400.0 :dist (* i 3.5555)}))
          r (m/activity-metrics {:sport "running"} s thresholds)]
      (is (= "rTSS" (:tss_method r)))
      (is (close? 1.0 (:intensity r) 0.001))
      (is (close? 100 (:tss r) 0.2))
      (is (close? 0 (:decoupling r) 0.01))
      (is (= [0 0 0 0 3600 0] (:hr_zones_s r)))
      (is (= 3.556 (get-in r [:peaks :speed 300])))))
  (testing "uphill raises NGP above actual speed"
    (let [s (stream 1800 (fn [i] {:hr 160 :speed 3.0 :alt (+ 400.0 (* i 3.0 0.05)) :dist (* i 3.0)}))
          r (m/activity-metrics {:sport "trail_running"} s thresholds)]
      (is (> (:ngp_mps r) 3.0))))
  (testing "hike with HR uses hrTSS"
    (let [s (stream 3600 (fn [_] {:hr 170 :speed 1.3}))
          r (m/activity-metrics {:sport "hiking"} s thresholds)]
      (is (= "hrTSS" (:tss_method r)))
      (is (close? 100 (:tss r) 0.2))))
  (testing "bike without FTP falls back to hrTSS; NP still computed"
    (let [s (stream 3600 (fn [_] {:hr 150 :power 200}))
          r (m/activity-metrics {:sport "cycling"} s thresholds)]
      (is (= "hrTSS" (:tss_method r)))
      (is (= 200.0 (:np r)))
      (is (= 1.0 (:vi r)))
      (is (= 720.0 (:kj r)))
      (testing "and switches to power TSS once FTP is known"
        (is (= "TSS" (:tss_method (m/activity-metrics {:sport "cycling"} s (assoc thresholds :ftp 200))))))))
  (testing "HR on only part of a long hike is scaled up to the full duration"
    (let [s (stream 7200 (fn [i] {:hr (when (zero? (mod i 4)) 170) :speed 1.3}))
          r (m/activity-metrics {:sport "hiking"} s thresholds)]
      (is (= "hrTSS-scaled" (:tss_method r)))
      (is (close? 200 (:tss r) 0.5))))
  (testing "scaling stops at moving time when the watch kept recording"
    (let [s (stream 7200 (fn [i] {:hr (when (zero? (mod i 4)) 170) :speed 1.3}))
          r (m/activity-metrics {:sport "hiking" :moving_s 3600} s thresholds)]
      (is (close? 100 (:tss r) 0.5))))
  (testing "almost no HR samples: fall back to the average-HR estimate"
    (let [s (stream 3600 (fn [i] {:hr (when (< i 100) 150) :speed 1.3}))
          r (m/activity-metrics {:sport "hiking" :moving_s 3600 :avg_hr 170} s thresholds)]
      (is (= "hrTSS-est" (:tss_method r)))
      (is (close? 100 (:tss r) 0.5))))
  (testing "no stream: estimate from average HR"
    (let [r (m/activity-metrics {:sport "lap_swimming" :moving_s 1800 :avg_hr 170} nil thresholds)]
      (is (= "hrTSS-est" (:tss_method r)))
      (is (close? 50 (:tss r) 0.1))))
  (testing "nothing usable"
    (is (nil? (:tss (m/activity-metrics {:sport "yoga"} nil thresholds))))))

(deftest decoupling-test
  (let [hr  (concat (repeat 900 150) (repeat 900 165))
        out (repeat 1800 3.0)]
    (is (close? 9.09 (m/decoupling out hr) 0.01)))
  (is (nil? (m/decoupling (repeat 100 3.0) (repeat 100 150)))))

(deftest pmc-test
  (let [days (m/pmc {"2026-01-01" 100.0} "2026-01-01" "2026-01-10")
        d1   (first days)]
    (is (= 10 (count days)))
    (is (= {:date "2026-01-01" :tss 100.0 :ctl 2.4 :atl 14.3 :tsb 0.0 :ramp nil} d1))
    (is (= -11.9 (:tsb (second days))))
    (is (some? (:ramp (last days)))))
  (testing "steady 100 TSS/day converges to CTL = ATL = 100"
    (let [dates (map str (take 400 (iterate #(.plusDays ^java.time.LocalDate % 1)
                                            (java.time.LocalDate/parse "2025-01-01"))))
          last  (last (m/pmc (zipmap dates (repeat 100.0)) (first dates) (clojure.core/last dates)))]
      (is (close? 100 (:ctl last) 0.1))
      (is (close? 100 (:atl last) 0.1))
      (is (close? 0 (:ramp last) 0.1)))))

(deftest weekly-volume-test
  (is (= "2026-W44" (m/iso-week "2026-10-29")))
  (is (= "2026-W53" (m/iso-week "2027-01-01")))
  (is (= [{:week "2026-W43" :week_start "2026-10-19" :sport "running" :count 2 :duration_s 3600.0
           :distance_m 10000.0 :elev_gain_m 50.0 :tss 120.0}]
         (m/weekly-volume [{:date "2026-10-19" :sport "running" :moving_s 1800 :distance_m 5000 :elev_gain_m 25 :tss 60}
                           {:date "2026-10-20" :sport "running" :moving_s 1800 :distance_m 5000 :elev_gain_m 25 :tss 60}]))))

(deftest trend-test
  (let [rows (mapv (fn [i] {:date (str "d" i) :rhr (when-not (= i 2) (+ 50 i))}) (range 10))
        t    (m/trend rows :rhr)]
    (is (= 10 (count t)))
    (is (nil? (:value (t 2))))
    (is (= 50.5 (:avg7 (t 2))))
    (is (some? (:base_low (t 9))))))
