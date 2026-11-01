(ns coach.garmin-mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.garmin-mcp :as gm]))

;; Shaped like the real responses (made-up values).
(def summary
  {:id 1000000001 :name "Morning Hike" :type "hiking"
   :start_time "2030-05-12 07:15:00" :distance_meters 21500.5
   :duration_seconds 18000.25 :avg_hr_bpm 112.0})

(def detail
  {:id 1000000001 :name "Morning Hike" :type "hiking"
   :start_time_local "2030-05-12T07:15:00.0" :start_time_gmt "2030-05-12T05:15:00.0"
   :distance_meters 21500.5 :duration_seconds 18000.25 :moving_duration_seconds 16200.0
   :elevation_gain_meters 640.0 :avg_hr_bpm 112.0 :max_hr_bpm 151.0 :calories 1500.0
   :training_load 48.25 :training_effect 2.5})

(deftest activity-row-test
  (let [row (gm/activity-row summary detail)]
    (is (= 1000000001 (:id row)))
    (is (= "2030-05-12T07:15:00" (:start_time_local row)))
    (is (= "2030-05-12T05:15:00" (:start_time_gmt row)))
    (is (= "2030-05-12" (:date row)))
    (is (= "hiking" (:sport row)))
    (is (= 48.25 (:training_load row)))
    (is (= 16200.0 (:moving_s row)))
    (is (= {:summary summary :detail detail} (:raw row))))
  (testing "falls back to the list summary when detail is missing"
    (let [row (gm/activity-row summary nil)]
      (is (= "2030-05-12T07:15:00" (:start_time_local row)))
      (is (= "2030-05-12" (:date row)))
      (is (= 21500.5 (:distance_m row)))
      (is (nil? (:training_load row))))))

(def readiness
  [{:context "UPDATE_REALTIME_VARIABLES" :timestamp "2030-05-13T00:50:00.0" :score 76 :level "MODERATE"}
   {:context "AFTER_WAKEUP_RESET" :timestamp "2030-05-13T06:05:00.0" :score 73 :level "MODERATE"}])

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
  (testing "negative stress means not enough data"
    (is (nil? (:avg_stress (gm/wellness-row {:date "2026-01-01" :stats {:avg_stress_level -1}})))))
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

;; Intraday: payload shapes trimmed from real 2026-10-29 responses (UTC+1).
(def hr-payload
  {:startTimestampGMT "2026-10-28T23:00:00.0" :startTimestampLocal "2026-10-29T00:00:00.0"
   :heartRateValues [[1793228400000 76] [1793228520000 66] [1793228640000 nil]]})

(def stress-payload
  {:startTimestampGMT "2026-10-28T23:00:00.0" :startTimestampLocal "2026-10-29T00:00:00.0"
   :stressValuesArray [[1793228400000 41] [1793228580000 -2] [1793228760000 -1]]
   :bodyBatteryValuesArray [[1793228400000 "MEASURED" 38 3.0] [1793228580000 "MEASURED" 36 3.0]]})

(def steps-payload
  [{:startGMT "2026-10-29T07:00:00.0" :endGMT "2026-10-29T07:15:00.0" :steps 100}
   {:startGMT "2026-10-29T07:45:00.0" :endGMT "2026-10-29T08:00:00.0" :steps 23}
   {:startGMT "2026-10-29T08:00:00.0" :endGMT "2026-10-29T08:15:00.0" :steps 5}
   {:startGMT "2026-10-28T23:00:00.0" :endGMT "2026-10-28T23:15:00.0" :steps 0}])

(deftest intraday-row-test
  (let [r (gm/intraday-row {:date "2026-10-29" :hr hr-payload :stress stress-payload :steps steps-payload})]
    (testing "minute of the local day, nil heart rates dropped"
      (is (= [[0 76] [2 66]] (:hr r))))
    (testing "unmeasured stress (-1/-2) kept as -1"
      (is (= [[0 41] [3 -1] [6 -1]] (:stress r))))
    (is (= [[0 38] [3 36]] (:body_battery r)))
    (testing "steps summed into local hours: local 08 = GMT 07:00–07:59"
      (is (= 24 (count (:steps r))))
      (is (= 123 (get-in r [:steps 8])))
      (is (= 5 (get-in r [:steps 9])))
      (is (= 128 (reduce + (:steps r)))))
    (is (= #{:hr :stress :steps} (set (keys (:raw r))))))
  (testing "steps fall back to the stress payload's offset"
    (is (= 123 (get-in (gm/intraday-row {:date "2026-10-29" :hr "No heart rate data" :stress stress-payload
                                         :steps steps-payload})
                       [:steps 8]))))
  (testing "a day without data gives empty series and never throws"
    (let [r (gm/intraday-row {:date "2021-03-27"
                              :hr {:startTimestampGMT nil :heartRateValues nil}
                              :stress "No stress data found for 2021-03-27"
                              :steps "No steps data found for 2021-03-27"})]
      (is (= {:date "2021-03-27" :hr [] :stress [] :body_battery [] :steps []}
             (dissoc r :raw))))))

(deftest error-kind-test
  (testing "messages the server really returns (captured with no valid tokens)"
    (is (= :auth (gm/error-kind "Error retrieving stats: Garmin login failed. Run 'garmin-mcp-auth' to authenticate, then restart the server.")))
    (is (= :auth (gm/error-kind "Error retrieving HRV data: Garmin authentication failed: 401 Client Error. Re-run 'garmin-mcp-auth' to refresh your tokens and restart the server."))))
  (is (= :rate-limit (gm/error-kind "Error retrieving stats: Garmin rate limit hit: 429. Wait a few minutes before retrying.")))
  (is (= :unreachable (gm/error-kind "Error retrieving stats: Garmin Connect request failed: Max retries. Garmin Connect may be unreachable; check your network connection or try again later.")))
  (is (= :timeout (gm/error-kind "Error retrieving stats: Garmin request 'get_stats' did not return within 60s and was abandoned.")))
  (is (= :timeout (gm/error-kind "MCP request timed out")))
  (is (= :garmin (gm/error-kind "Error retrieving stats: Garmin Connect server error: 503. Garmin returned a server error; try again later.")))
  (is (= :server (gm/error-kind "MCP error: MCP server exited")))
  (is (= :other (gm/error-kind "Error retrieving stats: 'NoneType' object is not subscriptable"))))

(deftest error-text-test
  (is (gm/error-text? "Error retrieving stats: boom"))
  (is (not (gm/error-text? "No stats found for 2026-10-30")) "a day without data is not an error")
  (is (not (gm/error-text? {:date "2026-10-30"}))))
