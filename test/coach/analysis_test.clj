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
