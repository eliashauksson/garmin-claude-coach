(ns coach.epoc-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.epoc :as epoc]
            [coach.readiness-score :as rs]))

(def p epoc/defaults)

(defn- stream
  "A 1 Hz stream of `secs` seconds, `f` giving the channel value per second."
  [secs k f]
  {:t (vec (range secs)) k (mapv f (range secs))})

(defn- load-at
  "Training load of `minutes` at a steady heart rate (max HR 196)."
  [minutes hr]
  (epoc/peak p (epoc/hr-bins (stream (* 60 minutes) :hr (constantly hr)) 196)))

(deftest bins
  (testing "samples are averaged into 30 s bins"
    (let [b (epoc/hr-bins (stream 120 :hr #(if (< % 60) 98 196)) 196)]
      (is (= [0.5 0.5 0.5 0.5] (vec (:dt b))))
      (is (= [0.5 0.5 1.0 1.0] (vec (:i b))))))
  (testing "a pause becomes rest, so EPOC falls through it"
    (let [b (epoc/hr-bins {:t [0 10 20 630 640] :hr [150 150 150 150 150]} 196)]
      (is (= 0.0 (aget ^doubles (:i b) 1)))
      (is (= 11.0 (reduce + (:dt b))) "the stream's 10.7 minutes, in whole bins")))
  (testing "no heart rate, no bins"
    (is (nil? (epoc/hr-bins (stream 600 :hr (constantly nil)) 196)))
    (is (nil? (epoc/hr-bins (stream 600 :hr (constantly 150)) nil)))))

(deftest epoc
  (testing "longer and harder means more load"
    (is (< (load-at 30 150) (load-at 60 150)))
    (is (< (load-at 45 140) (load-at 45 160) (load-at 45 175))))
  (testing "easy efforts hardly build up EPOC"
    (is (< (load-at 60 95) 10)))
  (testing "in Garmin's range: an hour at threshold heart rate is a big session"
    (is (< 100 (load-at 60 170) 350)))
  (testing "the peak is kept: a cool-down doesn't lower the load"
    (let [hard (epoc/hr-bins (stream 1800 :hr (constantly 170)) 196)
          more (epoc/hr-bins (stream 3600 :hr #(if (< % 1800) 170 100)) 196)]
      (is (< (Math/abs (- (epoc/peak p hard) (epoc/peak p more))) 1.0))))
  (testing "fatigue: through a long effort EPOC keeps growing"
    (is (> (load-at 600 125) (* 1.25 (load-at 240 125)))))
  (testing "no bins, no load"
    (is (= 0.0 (epoc/peak p nil)))))

(deftest power
  (let [ftp 250.0
        ;; heart rate that follows power: HR/HRmax = 0.35 + 0.5 * P/FTP, a minute late
        watts  #(if (< (mod % 600) 300) 150.0 250.0)
        hr-of  #(* 196 (+ 0.35 (* 0.5 (/ (watts (max 0 (- % 60))) ftp))))
        ride   (fn [secs] (assoc (stream secs :power watts) :hr (mapv hr-of (range secs))))
        rides  (for [s [3600 4200 5400]]
                 {:hr (epoc/hr-bins (ride s) 196) :power (epoc/power-bins (ride s) ftp [0.0 1.0])})]
    (testing "calibration recovers how heart rate follows power"
      (let [[a b] (epoc/power-calibration rides)]
        (is (< (Math/abs (- a 0.35)) 0.02))
        (is (< (Math/abs (- b 0.5)) 0.03))))
    (testing "too little data, no calibration"
      (is (nil? (epoc/power-calibration (take 1
                                              (map #(update-in % [:hr :i]
                                                               (fn [a] (java.util.Arrays/copyOf ^doubles a 20)))
                                                   rides))))))
    (testing "which signal a ride's load comes from"
      (let [th {:max_hr 196 :ftp ftp} cal [0.35 0.5]]
        (is (= "hr+power" (:load_method (epoc/activity-load p cal th true (ride 3600)))))
        (is (= "power" (:load_method (epoc/activity-load p cal th true (stream 3600 :power watts)))))
        (is (= "hr" (:load_method (epoc/activity-load p nil th true (ride 3600)))) "not calibrated yet")
        (is (= "hr" (:load_method (epoc/activity-load p cal th false (ride 3600)))) "power only counts on a bike")
        (is (nil? (epoc/activity-load p cal th false (stream 3600 :power watts))))))
    (testing "power catches the surges heart rate is too slow for"
      (let [th {:max_hr 196 :ftp ftp} cal [0.35 0.5] r (ride 3600)]
        (is (>= (:load_est (epoc/activity-load p cal th true r))
                (:load_est (epoc/activity-load p nil th true r))))))))

(deftest acute
  (testing "a day's load fades with tau and leaves the window"
    (let [ap {:scale 1.0 :tau 6.0 :window 8}
          daily {"2026-11-01" 100.0}]
      (is (= 100.0 (epoc/acute-load ap daily "2026-11-01")))
      (is (< (Math/abs (- (epoc/acute-load ap daily "2026-11-07") (* 100 (Math/exp -1)))) 1e-9))
      (is (zero? (epoc/acute-load ap daily "2026-11-10"))))))

(deftest fitting
  (testing "the fit recovers the constants behind a set of loads"
    (let [truth (assoc p :a 30.0 :q 3.0)
          rows  (for [m [20 40 60 90 120 180] hr [120 140 155 165 175]
                      :let [b (epoc/hr-bins (stream (* 60 m) :hr (constantly hr)) 196)]]
                  {:bins b :load (epoc/peak truth b)})
          fitted (epoc/fit p rows rs/nelder-mead 400)
          e (epoc/evaluate fitted rows)]
      (is (< (:median e) 0.05))
      (is (> (:spearman e) 0.99))))
  (testing "evaluate"
    (let [b (epoc/hr-bins (stream 3600 :hr (constantly 160)) 196)
          l (epoc/peak p b)
          e (epoc/evaluate p [{:bins b :load l} {:bins b :load (* 2 l)} {:bins b :load (/ l 0.8)}])]
      (is (= 3 (:n e)))
      (is (< (Math/abs (- (:median e) 0.2)) 1e-9)))))
