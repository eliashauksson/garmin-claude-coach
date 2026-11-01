(ns coach.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [coach.plan-rules :as rules]
            [coach.readiness :as readiness]
            [coach.season :as season]))

(def sample-season
  "A made-up season plan: 2 Transition, 3 Base, 2 Specific weeks, then race week."
  {:race_date "2026-12-27"
   :weeks (mapv (fn [i p] {:monday (season/add-days "2026-11-02" (* 7 i)) :phase p :tss (* 100 (inc i))})
                (range) ["Transition" "Transition" "Base" "Base" "Base" "Specific" "Specific" "Race week"])})

(deftest season-test
  (is (= {:week 1 :phase "Transition" :monday "2026-11-02" :weeks_total 8 :tss_target 100}
         (select-keys (season/week-info "2026-11-04" sample-season) [:week :phase :monday :weeks_total :tss_target])))
  (is (= [4 "Base" 2 3] ((juxt :week :phase :phase_week :phase_weeks) (season/week-info "2026-11-23" sample-season))))
  (is (= [7 "Specific" 2 2] ((juxt :week :phase :phase_week :phase_weeks) (season/week-info "2026-12-19" sample-season))))
  (is (= [8 "Race week" 1 1] ((juxt :week :phase :phase_week :phase_weeks) (season/week-info "2026-12-27" sample-season))))
  (is (= "Before the plan" (:phase (season/week-info "2026-10-29" sample-season))))
  (is (= "After the race" (:phase (season/week-info "2026-12-28" sample-season))))
  (is (= 59 (:days_to_race (season/week-info "2026-10-29" sample-season))))
  (testing "without a season plan only the dates"
    (is (= {:monday "2026-10-26" :sunday "2026-11-01"} (season/week-info "2026-10-29" nil)))))

(defn- w [date session mins & [slot]]
  {:date date :slot (or slot "AM") :session_id session :duration_min mins})

(def ok-week ; a Base week: 270 min running, 90 min hike, rest day Mon, long run Sun
  [(w "2026-11-24" "GYM_LOWER" 50) (w "2026-11-24" "EASY" 40 "PM")
   (w "2026-11-25" "EASY_ST" 45)
   (w "2026-11-26" "GYM_UPPER" 50) (w "2026-11-26" "EASY" 40 "PM")
   (w "2026-11-27" "HILL_SPRINT" 45)
   (w "2026-11-28" "HIKE" 90)
   (w "2026-11-29" "LONG" 100)])

(def opts {:phase "Base" :full-week? true :prev {:run_min 260 :longest_min 90}})

(defn- has? [ws s] (some #(str/includes? % s) ws))

(deftest check-week-test
  (testing "a sound week has no warnings"
    (is (= [] (rules/check-week "2026-11-23" ok-week opts))))
  (testing "running time: +10% by default, the athlete's limit, +20% back to an earlier level"
    (is (has? (rules/check-week "2026-11-23" ok-week (assoc-in opts [:prev :run_min] 240)) "+10%"))
    (is (empty? (rules/check-week "2026-11-23" ok-week (-> opts (assoc-in [:prev :run_min] 240)
                                                             (assoc :settings {:run_increase_pct 15})))))
    (is (empty? (rules/check-week "2026-11-23" ok-week (assoc opts :prev {:run_min 230 :longest_min 90 :peak_min 280})))))
  (testing "long run: +30 min, the cap, share of the week"
    (let [ws (rules/check-week "2026-11-23" ok-week (assoc opts :prev {:run_min 260 :longest_min 60}
                                                          :settings {:long_run_max_min 90}))]
      (is (has? ws "+30 min"))
      (is (has? ws "over the 90 min cap")))
    (is (has? (rules/check-week "2026-11-23" (conj (pop ok-week) (w "2026-11-29" "LONG" 150))
                                (assoc-in opts [:prev :longest_min] 150))
              "35%"))
    (is (not (has? (rules/check-week "2026-11-23" ok-week (assoc-in opts [:prev :longest_min] 0)) "+30 min"))))
  (testing "hard sessions: limit, ADS, Transition, consecutive days"
    (let [hard (conj ok-week (w "2026-11-25" "THR" 50 "PM") (w "2026-11-27" "TEMPO" 50 "PM"))]
      (is (empty? (filter #(str/includes? % "hard sessions") (rules/check-week "2026-11-23" hard opts))))
      (is (has? (rules/check-week "2026-11-23" hard (assoc opts :settings {:quality_max 1})) "2 hard sessions; the limit is 1"))
      (is (has? (rules/check-week "2026-11-23" hard (assoc opts :settings {:lt_hr 174 :easy_hr_max 140})) "ADS"))
      (is (not (has? (rules/check-week "2026-11-23" hard (assoc opts :settings {:lt_hr 174 :easy_hr_max 160})) "ADS")))
      (is (not (has? (rules/check-week "2026-11-23" (conj ok-week (w "2026-11-25" "LT_TEST" 50 "PM"))
                                       (assoc opts :settings {:lt_hr 174 :easy_hr_max 140}))
                     "ADS")) "tests are allowed")
      (is (has? (rules/check-week "2026-11-23" hard (assoc opts :phase "Transition")) "Transition"))
      (is (has? (rules/check-week "2026-11-23" (conj ok-week (w "2026-11-28" "THR" 50 "PM")) opts) "consecutive"))))
  (testing "a back-to-back may follow the long run"
    (is (not (has? (rules/check-week "2026-11-16" [(w "2026-11-21" "LONG" 90) (w "2026-11-22" "B2B" 60)] {}) "consecutive"))))
  (testing "rest day and strength, only for a full week"
    (let [busy (conj ok-week (w "2026-11-23" "EASY" 30))]
      (is (has? (rules/check-week "2026-11-23" busy opts) "No rest day"))
      (is (not (has? (rules/check-week "2026-11-23" (conj ok-week (w "2026-11-23" "REC" 30)) opts) "No rest day")))
      (is (not (has? (rules/check-week "2026-11-23" busy (dissoc opts :full-week?)) "No rest day"))))
    (is (has? (rules/check-week "2026-11-23" (remove #(re-find #"^GYM" (:session_id %)) ok-week) opts) "No strength"))
    (is (not (has? (rules/check-week "2026-11-23" (remove #(re-find #"^GYM" (:session_id %)) ok-week)
                                     (assoc opts :phase "Race week"))
                   "No strength"))))
  (testing "lower-body strength: 48 h before key sessions, not in the final 10 days"
    (is (has? (rules/check-week "2026-11-23" (conj ok-week (w "2026-11-27" "GYM_LOWER" 50 "PM")) opts)
              "GYM_LOWER on 2026-11-27 is less than 48 h before LONG on 2026-11-29"))
    (is (has? (rules/check-week "2027-04-05" [(w "2027-04-06" "GYM_LOWER" 40)] {:race-date "2027-04-11"}) "final 10 days"))
    (is (empty? (rules/check-week "2027-04-05" [(w "2027-04-06" "GYM_MAINT" 30)] {:race-date "2027-04-11"})))))

(deftest readiness-test
  (let [good {:hrv_avg 55 :hrv_status "BALANCED" :rhr 52 :sleep_s 27000 :sleep_score 80
              :readiness 70 :body_battery_high 80}
        plan [{:id 1 :session_id "THR" :slot "AM" :date "2027-01-05"}
              {:id 2 :session_id "GYM_LOWER" :slot "PM" :date "2027-01-05"}]
        th   {:hrv_low 46 :rhr_high 57}]
    (is (= {:signals [] :level :ok :changes []} (readiness/check [good good] plan th)))
    (testing "low HRV: key session becomes EASY"
      (let [r (readiness/check [good (assoc good :hrv_avg 40)] plan th)]
        (is (= :adjust (:level r)))
        (is (= [{:workout_id 1 :from "THR" :to "EASY"}] (:changes r)))))
    (testing "low HRV: the long run becomes EASY too"
      (is (= [{:workout_id 4 :from "LONG" :to "EASY"}]
             (:changes (readiness/check [good (assoc good :hrv_avg 40)] [{:id 4 :session_id "LONG" :slot "AM"}] th)))))
    (testing "low HRV and high RHR: rest"
      (is (= [{:workout_id 1 :from "THR" :to "REST"}]
             (:changes (readiness/check [good (assoc good :hrv_avg 40 :rhr 58)] plan th)))))
    (testing "short sleep moves the morning run to the evening"
      (is (= [{:workout_id 3 :from "EASY" :slot "PM"}]
             (:changes (readiness/check [good (assoc good :sleep_s 18000)]
                                        [{:id 3 :session_id "EASY" :slot "AM"}] th)))))
    (testing "low HRV turns an easy run into recovery"
      (is (= [{:workout_id 3 :from "HILL_SPRINT" :to "REC"}]
             (:changes (readiness/check [good (assoc good :hrv_avg 44)]
                                        [{:id 3 :session_id "HILL_SPRINT" :slot "PM"}] th)))))
    (testing "without the athlete's thresholds, HRV and RHR aren't checked"
      (is (= :ok (:level (readiness/check [good (assoc good :hrv_avg 40 :rhr 58)] plan {})))))))

(deftest cost-test
  (require 'coach.costs)
  (let [cost #((resolve 'coach.costs/line-cost) "claude-opus-5-5" %)]
    (testing "1M of each at Opus 5.5 list prices"
      (is (< (Math/abs (- 29.2 (cost {:input_tokens 1e6 :output_tokens 1e6 :cache_read 1e6 :cache_write_5m 1e6}))) 1e-9)))
    (testing "a typical proposal: 3k input, 8k cache write, 9k output"
      (is (< (Math/abs (- 0.232 (cost {:input_tokens 3000 :output_tokens 9000 :cache_read 0 :cache_write_5m 8000}))) 1e-9)))
    (is (zero? (cost {})))))
