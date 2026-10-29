(ns coach.garmin-mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.garmin-mcp :as gm]))

;; Trimmed from real responses captured in Phase 1.
(def summary
  {:id 1000000001 :name "Morning Hike" :type "hiking"
   :start_time "2026-10-19 04:37:08" :distance_meters 37118.75
   :duration_seconds 28479.919921875 :avg_hr_bpm 109.0})

(def detail
  {:id 1000000001 :name "Morning Hike" :type "hiking"
   :start_time_local "2026-10-19T04:37:08.0" :start_time_gmt "2026-10-19T02:37:08.0"
   :distance_meters 37118.75 :duration_seconds 28479.92 :moving_duration_seconds 24198.0
   :elevation_gain_meters 110.0 :avg_hr_bpm 109.0 :max_hr_bpm 158.0 :calories 2525.0
   :training_load 56.490692138671875 :training_effect 2.9000000953674316})

(deftest activity-row-test
  (let [row (gm/activity-row summary detail)]
    (is (= 1000000001 (:id row)))
    (is (= "2026-10-19T04:37:08" (:start_time_local row)))
    (is (= "2026-10-19T02:37:08" (:start_time_gmt row)))
    (is (= "2026-10-19" (:date row)))
    (is (= "hiking" (:sport row)))
    (is (= 56.490692138671875 (:training_load row)))
    (is (= 24198.0 (:moving_s row)))
    (is (= {:summary summary :detail detail} (:raw row))))
  (testing "falls back to the list summary when detail is missing"
    (let [row (gm/activity-row summary nil)]
      (is (= "2026-10-19T04:37:08" (:start_time_local row)))
      (is (= "2026-10-19" (:date row)))
      (is (= 37118.75 (:distance_m row)))
      (is (nil? (:training_load row))))))

(def readiness
  [{:context "UPDATE_REALTIME_VARIABLES" :timestamp "2026-10-29T00:53:39.0" :score 76 :level "MODERATE"}
   {:context "AFTER_WAKEUP_RESET" :timestamp "2026-10-29T06:04:40.0" :score 73 :level "MODERATE"}])

(deftest morning-readiness-test
  (is (= 73 (:score (gm/morning-readiness readiness))))
  (is (= 76 (:score (gm/morning-readiness (take 1 readiness)))))
  (is (= 1 (:score (gm/morning-readiness {:score 1}))))
  (is (nil? (gm/morning-readiness "No training readiness data found for 2022-06-01"))))

(deftest wellness-row-test
  (testing "a full day"
    (let [row (gm/wellness-row
               {:date "2026-10-29"
                :stats {:resting_heart_rate_bpm 52 :avg_stress_level 29 :total_steps 10952
                        :body_battery_highest 71 :body_battery_lowest 24}
                :hrv {:last_night_avg_hrv_ms 53 :weekly_avg_hrv_ms 52 :status "BALANCED"}
                :readiness readiness
                :sleep {:date "2026-10-29" :sleep_score 75 :sleep_seconds 18960
                        :deep_sleep_seconds 4000 :rem_sleep_seconds 3000}})]
      (is (= {:date "2026-10-29" :rhr 52 :hrv_avg 53 :hrv_weekly_avg 52 :hrv_status "BALANCED"
              :sleep_score 75 :sleep_s 18960 :deep_sleep_s 4000 :rem_sleep_s 3000
              :readiness 73 :readiness_level "MODERATE" :avg_stress 29
              :body_battery_high 71 :body_battery_low 24 :steps 10952}
             (dissoc row :raw)))
      (is (not (contains? (:raw row) :date)))))
  (testing "a day without data (message strings, empty stats, no sleep)"
    (let [row (gm/wellness-row {:date "2021-03-27"
                                :stats {:date "2021-03-27"}
                                :hrv "No HRV data found for 2021-03-27."
                                :readiness "No training readiness data found for 2021-03-27"
                                :sleep nil})]
      (is (= "2021-03-27" (:date row)))
      (is (every? nil? (vals (dissoc row :date :raw)))))))

(deftest dates-test
  (is (= ["2026-02-27" "2026-02-28" "2026-03-01"] (gm/dates "2026-02-27" "2026-03-01")))
  (is (= ["2026-01-01"] (gm/dates "2026-01-01" "2026-01-01"))))

(deftest read-only-guard-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing"
                        (gm/call nil "delete_activity" {"activity_id" 1}))))
