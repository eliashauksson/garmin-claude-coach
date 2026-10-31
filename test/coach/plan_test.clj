(ns coach.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [coach.plan-rules :as rules]
            [coach.readiness :as readiness]
            [coach.season :as season]))

(def sample-season
  "A made-up season plan: 2 Reset, 3 Base, 2 Build 1 weeks, then race week."
  {:race_date "2026-12-27"
   :weeks (mapv (fn [i p] {:monday (season/add-days "2026-11-02" (* 7 i)) :phase p :tss (* 100 (inc i))})
                (range) ["Reset" "Reset" "Base" "Base" "Base" "Build 1" "Build 1" "Race week"])})

(deftest season-test
  (is (= {:week 1 :phase "Reset" :monday "2026-11-02" :weeks_total 8 :tss_target 100}
         (select-keys (season/week-info "2026-11-04" sample-season) [:week :phase :monday :weeks_total :tss_target])))
  (is (= [4 "Base" 2 3] ((juxt :week :phase :phase_week :phase_weeks) (season/week-info "2026-11-23" sample-season))))
  (is (= [7 "Build 1" 2 2] ((juxt :week :phase :phase_week :phase_weeks) (season/week-info "2026-12-19" sample-season))))
  (is (= [8 "Race week" 1 1] ((juxt :week :phase :phase_week :phase_weeks) (season/week-info "2026-12-27" sample-season))))
  (is (= "Before the plan" (:phase (season/week-info "2026-10-29" sample-season))))
  (is (= "After the race" (:phase (season/week-info "2026-12-28" sample-season))))
  (is (= 59 (:days_to_race (season/week-info "2026-10-29" sample-season))))
  (testing "without a season plan only the dates"
    (is (= {:monday "2026-10-26" :sunday "2026-11-01"} (season/week-info "2026-10-29" nil)))))

(defn- w [date session km & [slot]]
  {:date date :slot (or slot "AM") :session_id session :distance_km km})

(def ok-week ; week 4 (Base): 48 km, rest day Fri/Sun
  [(w "2026-11-23" "EASY" 8) (w "2026-11-23" "GYM_UPPER" 0 "PM")
   (w "2026-11-24" "HILL_2" 9) (w "2026-11-24" "GYM_LOWER" 0 "PM")
   (w "2026-11-25" "EASY" 7)
   (w "2026-11-26" "EASY_ST" 8)
   (w "2026-11-28" "LONG" 16)])

(deftest check-week-test
  (testing "a sound week has no warnings"
    (is (= [] (rules/check-week "2026-11-23" ok-week {:prev {:run_km 44 :longest_km 14}}))))
  (testing "volume, peak, long run and gym spacing"
    (let [ws (rules/check-week "2026-11-23"
                               (conj ok-week (w "2026-11-27" "GYM_BACK_SL" 0 "PM"))
                               {:prev {:run_km 30 :longest_km 12}})]
      (is (some #(str/includes? % "+10%") ws))
      (is (some #(str/includes? % "+3 km") ws))
      (is (some #(str/includes? % "GYM_BACK_SL on 2026-11-27") ws))))
  (testing "no recent long run: the +3 km check is skipped"
    (is (not-any? #(str/includes? % "+3 km")
                  (rules/check-week "2026-11-23" ok-week {:prev {:run_km 44 :longest_km 0}}))))
  (testing "back up to an earlier level +20% is allowed, above it +10%"
    (is (not-any? #(str/includes? % "over last week")
                  (rules/check-week "2026-11-23" ok-week {:prev {:run_km 41 :peak_km 50}})))
    (is (some #(str/includes? % "+10%")
              (rules/check-week "2026-11-23" ok-week {:prev {:run_km 41 :peak_km 44}}))))
  (testing "two thresholds, consecutive key days, no rest day"
    (let [ws (rules/check-week "2027-01-04"
                               (for [[i s] (map-indexed vector ["THR" "THR" "EASY" "EASY" "EASY" "LONG" "EASY"])]
                                 (w (season/add-days "2027-01-04" i) s 8))
                               {:phase "Build 1"})]
      (is (some #(str/includes? % "threshold") ws))
      (is (some #(str/includes? % "consecutive") ws))
      (is (some #(str/includes? % "rest day") ws))))
  (testing "the final 10 days"
    (is (some #(str/includes? % "final 10 days")
              (rules/check-week "2027-04-05" [(w "2027-04-06" "GYM_UPPER" 0)] {:race-date "2027-04-09"})))
    (is (not-any? #(str/includes? % "final 10 days")
                  (rules/check-week "2027-04-05" [(w "2027-04-06" "GYM_UPPER" 0)] {}))))
  (testing "the athlete's peak volume, only when known"
    (is (some #(str/includes? % "exceeds the 40 km peak") (rules/check-week "2026-11-23" ok-week {:peak-km 40})))
    (is (not-any? #(str/includes? % "peak") (rules/check-week "2026-11-23" ok-week {})))))

(deftest readiness-test
  (let [good {:hrv_avg 55 :hrv_status "BALANCED" :rhr 52 :sleep_s 27000 :sleep_score 80
              :readiness 70 :body_battery_high 80}
        plan [{:id 1 :session_id "THR" :slot "AM" :date "2027-01-05"}
              {:id 2 :session_id "GYM_LOWER" :slot "PM" :date "2027-01-05"}]
        th   {:hrv_low 46 :rhr_high 57}]
    (is (= {:signals [] :level :ok :changes []} (readiness/check [good good] plan "Build 1" th)))
    (testing "low HRV: key session becomes EASY"
      (let [r (readiness/check [good (assoc good :hrv_avg 40)] plan "Build 1" th)]
        (is (= :adjust (:level r)))
        (is (= [{:workout_id 1 :from "THR" :to "EASY"}] (:changes r)))))
    (testing "low HRV and high RHR: rest"
      (is (= [{:workout_id 1 :from "THR" :to "REST"}]
             (:changes (readiness/check [good (assoc good :hrv_avg 40 :rhr 58)] plan "Build 1" th)))))
    (testing "short sleep moves the morning run to the evening"
      (is (= [{:workout_id 3 :from "EASY" :slot "PM"}]
             (:changes (readiness/check [good (assoc good :sleep_s 18000)]
                                        [{:id 3 :session_id "EASY" :slot "AM"}] "Reset" th)))))
    (testing "low HRV turns an easy run into recovery"
      (is (= [{:workout_id 3 :from "EASY" :to "REC"}]
             (:changes (readiness/check [good (assoc good :hrv_avg 44)]
                                        [{:id 3 :session_id "EASY" :slot "PM"}] "Reset" th)))))
    (testing "without the athlete's thresholds, HRV and RHR aren't checked"
      (is (= :ok (:level (readiness/check [good (assoc good :hrv_avg 40 :rhr 58)] plan "Build 1" {})))))))

(deftest cost-test
  (require 'coach.planner)
  (let [cost (resolve 'coach.planner/cost-usd)]
    (testing "1M of each at Opus 5.5 list prices"
      (is (< (Math/abs (- 29.2 (cost {:input_tokens 1e6 :output_tokens 1e6 :cache_read 1e6 :cache_write 1e6}))) 1e-9)))
    (testing "a typical proposal: 3k input, 8k cache write, 9k output"
      (is (< (Math/abs (- 0.232 (cost {:input_tokens 3000 :output_tokens 9000 :cache_read 0 :cache_write 8000}))) 1e-9)))
    (is (zero? (cost {})))))
