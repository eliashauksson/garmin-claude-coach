(ns coach.overview-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.athlete :as athlete]
            [coach.garmin-mcp :as gm]
            [coach.overview :as o]))

(def today "2026-11-01")

(defn- act [date & {:as kv}]
  (merge {:id (str date) :date date :start (str date "T07:00:00") :sport "running" :family "run"
          :name "Run" :distance_m 10000 :duration_s 3600 :load 100}
         kv))

(deftest family-test
  (is (= ["run" "run" "bike" "bike" "bike" "swim" "hike" "hike" "hike" "ski" "ski"
          "strength" "strength" "strength" "strength" "other" "other"]
         (map o/family ["trail_running" "indoor_running" "road_biking" "mountain_biking" "cycling"
                        "lap_swimming" "hiking" "walking" "mountaineering" "backcountry_skiing"
                        "resort_snowboarding" "strength_training" "indoor_cardio" "hiit" "yoga"
                        "bouldering" nil]))))

(deftest readiness-test
  (let [raw {:readiness [{:context "AFTER_POST_EXERCISE_RESET" :score 30 :timestamp "2026-11-01T15:00:00"}
                         {:context "AFTER_WAKEUP_RESET" :score 52 :level "MODERATE" :feedback "LISTEN_TO_YOUR_BODY"
                          :recovery_time_hours 0.6 :sleep_factor_percent 62 :recovery_factor_percent 99
                          :training_load_factor_percent 100 :stress_history_factor_percent 74
                          :hrv_factor_percent 28 :sleep_history_factor_percent 38}]}]
    (is (= {:score 52 :level "MODERATE" :feedback "LISTEN_TO_YOUR_BODY" :recovery_h 1
            :factors {:sleep 62 :recovery_time 99 :acwr 100 :stress_history 74 :hrv 28 :sleep_history 38}}
           (o/readiness raw)) "the morning snapshot, its six factors in the spec's order")
    (is (nil? (o/readiness {:readiness "No training readiness data"})) "before the watch has it")))

(deftest vitals-test
  (let [days   (vec (concat [{:date "a" :rhr 99}] ; 8 days back: not counted
                            (for [r [52 54 nil 53 53 52 55]] {:rhr r})
                            [{:date today :rhr 55 :bb_latest 32 :bb_min 5 :bb_max 55}]))
        nights [{:sleep_score 70} {:sleep_score 75 :total_s 40020 :quality "FAIR" :hrv_night 44 :hrv_status "LOW"}]
        v      (o/vitals nights days)]
    (testing "resting HR against the mean of the 7 days before (nulls ignored)"
      (is (= [55 53 2] ((juxt :rhr :rhr_7d :rhr_delta) v))))
    (testing "sleep and HRV from the latest night, Body Battery from today"
      (is (= [75 40020 "FAIR" 44 "LOW"] ((juxt :sleep_score :sleep_s :sleep_quality :hrv_night :hrv_status) v)))
      (is (= [32 5 55] ((juxt :bb_now :bb_low :bb_high) v))))
    (testing "missing values stay nil"
      (is (= [nil nil nil] ((juxt :rhr :rhr_7d :rhr_delta) (o/vitals [] [{}])))))))

(deftest night-and-day-test
  (let [raw {:sleep {:sleep_score_qualifier "FAIR" :light_sleep_seconds 27900 :awake_seconds 2520}
             :hrv {:baseline_low_upper_ms 46 :baseline_balanced_low_ms 51 :baseline_balanced_upper_ms 71}
             :stats {:body_battery_current 32 :body_battery_charged 50 :body_battery_drained 23}}
        row {:sleep_score 75 :sleep_s 40020 :deep_sleep_s 4020 :rem_sleep_s 8100 :hrv_avg 44.0 :hrv_weekly_avg 44.0
             :hrv_status "LOW" :rhr 55 :avg_stress 34 :steps 2303 :body_battery_low 5 :body_battery_high 55}]
    (is (= {:date today :sleep_score 75 :quality "FAIR" :total_s 40020 :deep_s 4020 :light_s 27900 :rem_s 8100
            :awake_s 2520 :need_min 480 :hrv_night 44 :hrv_weekly 44 :hrv_low_up 46 :hrv_balanced_low 51
            :hrv_balanced_up 71 :hrv_status "LOW"}
           (o/night today row raw 480)))
    (is (= {:date today :rhr 55 :stress_avg 34 :steps 2303 :bb_min 5 :bb_max 55 :bb_latest 32
            :bb_charged 50 :bb_drained 23}
           (o/day today row raw)))
    (is (nil? (:light_s (o/night today {} {:sleep "No sleep data"} nil))) "days without data hold strings")))

(deftest period-test
  (let [acts [(act "2026-11-01" :duration_s 1800 :distance_m 5000 :load 50)       ; today: counted
              (act "2026-10-05" :duration_s 28800 :distance_m 37100 :load 169.4)  ; 27 days ago: counted
              (act "2026-10-04" :duration_s 7200 :distance_m 20000 :load 100)     ; 28 days ago: before
              (act "2026-09-06" :duration_s 3600 :distance_m 10000 :load 80)      ; 56 days ago: neither
              (act "2026-11-02" :load 999)]                                       ; tomorrow: neither
        p (o/period acts today)]
    (is (= {:hours 8.5 :km 42 :load 219 :sessions 2
            :hours_delta 6.5 :km_delta 22 :load_delta 119 :sessions_delta 1}
           p))))

(deftest heatmap-weekly-zones-test
  (let [acts [(act "2026-10-13" :load 162 :duration_s 1500 :name "Langenthal Running")
              (act "2026-10-19" :family "hike" :load 56 :duration_s 28479 :name "Siselen Hiking"
                   :start "2026-10-19T04:37:08" :distance_m 37118)
              (act "2026-10-19" :load 20 :duration_s 600 :name "Shakeout" :start "2026-10-19T18:00:00"
                   :zones_s [100 600 1200 0 60 0])
              (act "2026-09-30" :zones_s [0 6000 0 0 0 0])]]
    (testing "a day's colour is its highest-load activity's sport"
      (is (= {:date "2026-10-19" :load 76 :minutes 485 :sessions 2 :family "hike"
              :names "Siselen Hiking · Shakeout"}
             (some #(when (= "2026-10-19" (:date %)) %) (o/heatmap acts)))))
    (testing "weekly by Monday and family"
      (is (= [{:week "2026-09-28" :family "run" :load 100 :hours 1.0 :km 10.0 :sessions 1}
              {:week "2026-10-12" :family "run" :load 162 :hours 0.4 :km 10.0 :sessions 1}
              {:week "2026-10-19" :family "hike" :load 56 :hours 7.9 :km 37.1 :sessions 1}
              {:week "2026-10-19" :family "run" :load 20 :hours 0.2 :km 10.0 :sessions 1}]
             (o/weekly acts))))
    (testing "zones: Z1-Z5 minutes over the last 28 days only (below Z1 left out)"
      (is (= [10 20 0 1 0] (map :minutes (o/zones acts today)))))))

(deftest recent-test
  (let [acts (for [i (range 1 15)] (act (format "2026-10-%02d" i) :distance_m 12345 :load 99.6))
        r    (o/recent acts)]
    (is (= 12 (count r)))
    (is (= "2026-10-14" (:date (first r))) "newest first")
    (is (= [12.3 100] ((juxt :km :load) (first r))))))

(deftest status-test
  (let [raw {:status {:training_status_feedback "DETRAINING" :reporting_device_name "Enduro 3" :acute_load 0
                      :chronic_load 219 :optimal_chronic_load_min 175.2 :optimal_chronic_load_max 328.5
                      :load_ratio 0.0 :acwr_status "LOW"}
             :balance {:feedback "AEROBIC_LOW_SHORTAGE"
                       :aerobic_low {:load 56.5 :target_min 204 :target_max 467}
                       :aerobic_high {:load 135.3 :target_min 276 :target_max 539}
                       :anaerobic {:load 27.0 :target_min 0 :target_max 262}}}]
    (is (= {:status "DETRAINING" :device "Enduro 3" :acute 0 :chronic 219 :chronic_min 175 :chronic_max 329
            :acwr 0.0 :acwr_status "LOW" :aer_low 57 :aer_low_min 204 :aer_low_max 467
            :aer_high 135 :aer_high_min 276 :aer_high_max 539 :anaer 27 :anaer_min 0 :anaer_max 262
            :balance "AEROBIC_LOW_SHORTAGE"}
           (o/status raw)))))

(deftest race-test
  (let [goal {:name "Triple" :date "2027-04-11" :goal_s 25200 :legs ["marathon" "half" "10k"]}
        now  {:date today :t5k 1351 :t10k 2913 :thalf 6467 :tmar 14595}
        r    (o/race goal now [now] today)]
    (testing "the legs' fresh-race predictions added up, against the goal"
      (is (= 23975 (:total_s r)))
      (is (= 1225 (:margin_s r)))
      (is (= ["10k" "half" "marathon"] (:legs r)) "shortest first, as the goal bar draws them"))
    (is (= 161 (:days_to_race r)))
    (testing "over the goal: a negative margin"
      (is (= -575 (:margin_s (o/race (assoc goal :goal_s 23400) now [] today)))))
    (testing "no goal time or legs: no total, no margin"
      (is (= [nil nil] ((juxt :total_s :margin_s) (o/race (assoc goal :legs []) now [] today))))
      (is (= [23975 nil] ((juxt :total_s :margin_s) (o/race (dissoc goal :goal_s) now [] today)))))))

(deftest monthly-test
  (is (= ["2026-09-30" "2026-10-31" "2026-11-01"]
         (map :date (o/monthly [{:date "2026-10-01"} {:date "2026-09-12"} {:date "2026-10-31"}
                                {:date "2026-09-30"} {:date "2026-11-01"}])))))

(deftest endurance-test
  (let [raw {:classification "trained"
             :thresholds {:intermediate 5100 :trained 5800 :well_trained 6600 :expert 7300 :superior 8100 :elite 8800}}]
    (is (= {:score 6093 :classification "trained"
            :limits {:low 3620 :intermediate 5100 :trained 5800 :well_trained 6600 :expert 7300
                     :superior 8100 :elite 8800 :high 10280}}
           (o/endurance 6093 raw)) "the gauge's ends: two average class widths out")
    (is (= 3000 (get-in (o/endurance 3000 raw) [:limits :low])) "always wide enough for the score")
    (is (nil? (o/endurance nil raw)))))

(deftest athlete-race-fields-test
  (is (= 25200 (athlete/clock->s "7:00:00")))
  (is (= 12600 (athlete/clock->s "3:30")))
  (is (nil? (athlete/clock->s "")))
  (is (nil? (athlete/clock->s "sub 3"))))

(deftest garmin-rows-test
  (testing "race predictions"
    (is (= {:date today :t5k 1351 :t10k 2913 :thalf 6467 :tmar 14595}
           (dissoc (gm/race-prediction-row
                    {:prediction_date today
                     :predictions {:5K {:time_seconds 1351} :10K {:time_seconds 2913}
                                   :half_marathon {:time_seconds 6467} :marathon {:time_seconds 14595}}})
                   :raw)))
    (is (nil? (gm/race-prediction-row "No race predictions"))))
  (testing "endurance"
    (is (= [today 6093] ((juxt :date :score) (gm/endurance-row today {:current_score 6093 :current_date today}))))
    (is (nil? (gm/endurance-row today {:current_score nil}))))
  (testing "sleep need (minutes)"
    (is (= 480 (:need_min (gm/sleep-need-row today {:dailySleepDTO {:sleepNeed {:actual 480 :baseline 420}}}))))
    (is (nil? (gm/sleep-need-row today {:dailySleepDTO {}}))))
  (testing "training status"
    (is (some? (gm/training-status-row today {:training_status_feedback "DETRAINING"} nil)))
    (is (nil? (gm/training-status-row today "No training status" "No load balance")))))
