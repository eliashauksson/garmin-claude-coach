(ns coach.analysis-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.analysis :as analysis]
            [coach.db :as db]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-analysis" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(def raw-29
  {:stats {:min_heart_rate_bpm 50 :max_heart_rate_bpm 128 :last_7_days_avg_resting_hr 52
           :body_battery_charged 38 :body_battery_drained 67 :body_battery_current 6
           :max_stress_level 99 :daily_step_goal 10100 :distance_meters 10700 :floors_ascended 21.0}
   :hrv {:baseline_balanced_low_ms 50 :baseline_balanced_upper_ms 70}
   :sleep {:light_sleep_seconds 9000 :awake_seconds 0}})

(deftest day-extras-test
  (is (= {:hr_min 50 :hr_max 128 :rhr_7d 52 :bb_charged 38 :bb_drained 67 :bb_current 6
          :stress_max 99 :step_goal 10100 :distance_m 10700 :floors 21.0
          :hrv_low 50 :hrv_high 70 :sleep_light_s 9000 :sleep_awake_s 0}
         (analysis/day-extras raw-29)))
  (testing "message-string days give nils"
    (is (every? nil? (vals (analysis/day-extras {:stats {} :hrv "No HRV data" :sleep nil}))))))

(deftest nights-test
  (let [ds (temp-ds)]
    (db/upsert-wellness!
     ds (for [[d score hrv] [["2026-10-22" 70 50] ["2026-10-23" 76 56] ["2026-10-28" 84 63] ["2026-10-29" 75 53]]]
          {:date d :sleep_score score :hrv_avg hrv :sleep_s 20000 :raw (when (= d "2026-10-29") raw-29)}))
    (let [n (analysis/nights ds "2026-10-29")]
      (is (= ["2026-10-23" "2026-10-28" "2026-10-29"] (map :date n)) "7 nights ending today, oldest first")
      (is (= {:date "2026-10-29" :sleep_score 75 :sleep_s 20000 :hrv_avg 53.0 :hrv_low 50 :hrv_high 70}
             (last n))))))

(deftest route-thumb-test
  (testing "short tracks are kept, rounded, nil positions dropped"
    (is (= [[45.12346 10.1] [45.2 10.2]]
           (map (fn [[a b]] [(double a) (double b)])
                (analysis/route-thumb {:lat [45.1234567 nil 45.2] :lon [10.1 10.15 10.2]})))))
  (testing "long tracks are sampled down and keep their last point"
    (let [n 1000
          r (analysis/route-thumb {:lat (mapv #(+ 45.0 (* % 1e-4)) (range n))
                                   :lon (mapv #(+ 10.0 (* % 1e-4)) (range n))})]
      (is (<= (count r) (inc analysis/route-points)))
      (is (= [45.0 10.0] (first r)))
      (is (= [45.0999 10.0999] (last r)))))
  (is (nil? (analysis/route-thumb {:lat [45.0] :lon [10.0]})))
  (is (nil? (analysis/route-thumb nil))))

(deftest feedback-due-test
  (let [ds  (temp-ds)
        act (fn [id date] {:id id :date date :start_time_local (str date "T07:00:00") :sport "running"
                           :name (str "Run " id) :raw {}})]
    (db/upsert-activities! ds [(act 1 "2026-10-25") (act 2 "2026-10-26") (act 3 "2026-10-30")
                               (act 4 "2026-11-01") (act 5 "2026-11-02")])
    (testing "the last 7 days (today included) without an answer, newest first"
      (is (= [4 3 2] (map :id (analysis/feedback-due ds "2026-11-01")))))
    (testing "an answer or a skip takes an activity off the list"
      (analysis/save-feedback! ds 4 {:note "  Legs heavy, slept badly  " :rpe 7})
      (analysis/save-feedback! ds 3 {:skipped true})
      (is (= [2] (map :id (analysis/feedback-due ds "2026-11-01")))))
    (testing "the note is trimmed; a pain location only counts with pain"
      (analysis/save-feedback! ds 2 {:note " " :pain false :pain_location "knee"})
      (is (= {:note "Legs heavy, slept badly" :rpe 7 :pain 0}
             (select-keys (analysis/activity ds 4) [:note :rpe :pain])))
      (is (= [nil nil] ((juxt :note :pain_location) (analysis/activity ds 2)))))))

(deftest pmc-from-first-activity-test
  (let [ds  (temp-ds)
        act (fn [id date] {:id id :date date :start_time_local (str date "T07:00:00") :sport "running"
                           :name (str "Run " id) :raw {}})]
    (db/upsert-activities! ds [(act 1 "2015-06-01") (act 2 "2015-06-03")])
    (db/upsert-metrics! ds [{:activity_id 1 :tss 100.0} {:activity_id 2 :tss 50.0}])
    (testing "\"all\" starts at the first activity, however old"
      (let [rows (analysis/pmc ds "all" "2015-06-05")]
        (is (= ["2015-06-01" "2015-06-05"] [(:date (first rows)) (:date (last rows))]))
        (is (pos? (:ctl (first rows))) "the first day's load counts")))
    (testing "a window gets the same values as the full history"
      (is (= (last (analysis/pmc ds "all" "2015-06-05"))
             (last (analysis/pmc ds "2015-06-04" "2015-06-05")))))
    (testing "volume from the start"
      (is (seq (analysis/volume ds "all" "2015-06-05"))))))

(deftest eftp-test
  (let [ds    (temp-ds)
        today (str (java.time.LocalDate/now))
        ride  {:id 7 :date today :start_time_local (str today "T09:00:00") :sport "road_biking"
               :name "Ride" :raw {}}
        ;; 30 min: 5 easy, 20 at 250 W, 5 easy
        n     1800
        power (mapv #(if (<= 300 % 1499) 250.0 120.0) (range n))]
    (db/upsert-activities! ds [ride])
    (db/save-stream! ds 7 {:t (vec (range n)) :hr (vec (repeat n 140)) :power power} nil)
    (db/set-state! ds :weight_kg 75.0)
    (analysis/compute-metrics! ds)
    (testing "without an FTP the ride is scored by heart rate"
      (is (not= "TSS" (:tss_method (analysis/activity ds 7)))))
    (analysis/update-eftp! ds)
    (testing "the eFTP is stored as an estimated bike FTP from the ride's day"
      (is (= [{:sport "bike" :kind "ftp" :valid_from today :value 238.0 :source "estimate"}]
             (filter #(= "bike" (:sport %)) (db/thresholds ds)))))
    (testing "and the ride is scored again with power"
      (let [a (analysis/activity ds 7)]
        (is (= "TSS" (:tss_method a)))
        (is (= "estimate" (get-in a [:thresholds :ftp_source])))))
    (testing "running it again changes nothing"
      (is (nil? (analysis/sync-eftp! ds))))
    (testing "the Cycling power section"
      (let [p (analysis/cycling-power ds today)]
        (is (= 238.0 (:ftp p)))
        (is (= "estimate" (:ftp_source p)))
        (is (= 3.17 (:wkg p)))
        (is (number? (:vo2max_est p)))
        (is (= 250.0 (:watts (some #(when (= 1200 (:s %)) %) (:curve_90d p)))))
        (is (empty? (:curve_prev_90d p)))))
    (testing "nothing without rides with power"
      (is (nil? (analysis/cycling-power (temp-ds) today))))))

(deftest estimated-lthr-test
  (let [row (fn [kind value] {:sport "all" :kind kind :value value :valid_from "2006-01-01" :source "garmin"})
        base [(row "max_hr" 200.0) (row "hr_z1" 100.0) (row "hr_z2" 120.0) (row "hr_z3" 140.0)
              (row "hr_z4" 160.0) (row "hr_z5" 180.0)]]
    (testing "no Garmin LTHR: middle of zone 4"
      (let [th (analysis/thresholds-at base "2026-01-01")]
        (is (= 170.0 (:lthr th)))
        (is (= "estimate" (:lthr_source th)))))
    (testing "without zones: 85% of max HR"
      (is (= 170.0 (:lthr (analysis/thresholds-at [(row "max_hr" 200.0)] "2026-01-01")))))
    (testing "Garmin's LTHR wins"
      (let [th (analysis/thresholds-at (conj base (row "lthr" 172.0)) "2026-01-01")]
        (is (= 172.0 (:lthr th)))
        (is (nil? (:lthr_source th)))))
    (testing "nothing to estimate from"
      (is (nil? (:lthr (analysis/thresholds-at [] "2026-01-01")))))))

(deftest lthr-from-peaks-test
  (testing "best 60 min average wins"
    (is (= 190.0 (analysis/lthr-from-peaks [{:p60 190.0 :p20 195.0} {:p60 180.0}] 215.0))))
  (testing "else 95% of the best 20 min average"
    (is (= 190.0 (analysis/lthr-from-peaks [{:p20 200.0} {:p20 150.0}] 215.0))))
  (testing "easy efforts say nothing"
    (is (nil? (analysis/lthr-from-peaks [{:p60 120.0 :p20 140.0}] 215.0))))
  (testing "clamped to 97% of max HR"
    (is (= (* 0.97 200.0) (analysis/lthr-from-peaks [{:p60 199.0}] 200.0))))
  (testing "no max HR or no peaks"
    (is (nil? (analysis/lthr-from-peaks [{:p60 190.0}] nil)))
    (is (nil? (analysis/lthr-from-peaks [] 200.0))))
  (testing "thresholds-at uses it only without a Garmin LTHR"
    (let [row (fn [kind value] {:sport "all" :kind kind :value value :valid_from "2006-01-01" :source "garmin"})]
      (is (= 188.0 (:lthr (analysis/thresholds-at [(row "max_hr" 200.0)] "2026-01-01" 188.0))))
      (is (= 172.0 (:lthr (analysis/thresholds-at [(row "max_hr" 200.0) (row "lthr" 172.0)] "2026-01-01" 188.0)))))))

(deftest vital-trend-test
  (let [ds (temp-ds)]
    (db/upsert-wellness!
     ds (for [[d rhr] [["2026-09-01" 60] ["2026-10-01" 54] ["2026-10-02" 52] ["2026-10-03" 50]]]
          {:date d :rhr rhr :sleep_score 70 :raw {}}))
    (testing "only the rows from `from`, with the days before it feeding the average and baseline"
      (let [rows (:rhr (analysis/vital-trend ds [:rhr] "2026-10-02" "2026-10-03"))]
        (is (= ["2026-10-02" "2026-10-03"] (map :date rows)))
        (is (= [52 50] (map :value rows)))
        ;; the windows count days that have data: 60, 54 and 52 (the day before `from` is in them)
        (is (= 55.3 (:avg7 (first rows))))
        (is (= 55.3 (:avg28 (first rows))))
        (is (some? (:base_low (first rows))))))
    (testing "\"all\" starts at the first day on record"
      (is (= "2026-09-01" (:date (first (:rhr (analysis/vital-trend ds [:rhr] "all" "2026-10-03")))))))
    (testing "several metrics at once"
      (is (= #{:rhr :sleep_score}
             (set (keys (analysis/vital-trend ds [:rhr :sleep_score] "2026-10-01" "2026-10-03"))))))
    (testing "only the allowlisted metrics"
      (is (thrown? clojure.lang.ExceptionInfo (analysis/vital-trend ds [:raw] "2026-10-01" "2026-10-03"))))))

(deftest availability-test
  (let [ds (temp-ds)]
    (testing "no health data at all: everything is shown"
      (is (nil? (analysis/available ds "2030-05-10"))))
    (db/upsert-wellness! ds [{:date "2030-05-01" :rhr 50 :avg_stress 20 :raw {}}
                             {:date "2030-05-02" :rhr 51 :raw {}}
                             {:date "2029-01-01" :hrv_avg 60.0 :raw {}}])
    (db/upsert-endurance! ds [{:date "2030-05-02" :score 5000 :raw {}}])
    (is (= ["endurance" "rhr" "stress"] (analysis/available ds "2030-05-10")))))

(deftest vital-limits-test
  (let [ds    (temp-ds)
        nights (for [i (range 1 21)] {:date (format "2030-05-%02d" i) :resp_sleep 12.0 :spo2_avg 96.0 :raw {}})]
    (db/upsert-wellness! ds nights)
    (db/upsert-wellness! ds [{:date "2030-05-24" :resp_sleep 14.0 :spo2_avg 92.0 :raw {}}])
    (let [[row] (analysis/wellness-with-limits ds "2030-05-24" "2030-05-24")]
      (is (= [13.0 93.0] [(:resp_limit row) (:spo2_floor row)])))
    (testing "too few earlier nights: no limit"
      (is (nil? (:resp_limit (first (analysis/wellness-with-limits ds "2030-05-08" "2030-05-08"))))))))

(deftest activity-extras-test
  (let [ds (temp-ds)]
    (db/upsert-activities! ds [{:id 1 :date "2030-05-01" :sport "strength_training"
                                :raw {:detail {:anaerobic_training_effect 1.5 :training_effect_label "TEMPO"
                                               :recovery_hr_bpm 30}}}])
    (db/upsert-exercise-sets! ds [{:activity_id 1 :summary "[{\"exercise\":\"PLANK\",\"sets\":2}]" :raw {}}])
    (let [x (analysis/activity ds 1)]
      (is (= [1.5 "TEMPO" 30 nil] ((juxt :anaerobic_te :te_label :recovery_hr :gct_ms) x)))
      (is (= [{:exercise "PLANK" :sets 2}] (:exercises x))))))
