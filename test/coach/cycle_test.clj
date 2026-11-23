(ns coach.cycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.adapt :as adapt]
            [coach.cycle :as cycle]
            [coach.cycle-data :as cd]
            [coach.db :as db]
            [coach.garmin-mcp :as gm]
            [coach.readiness :as readiness]
            [coach.season]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-cycle" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(def starts ["2026-08-01" "2026-08-29" "2026-09-27" "2026-10-25"]) ; 28, 29, 28 days

(deftest starts-and-length-test
  (is (= ["2026-10-01" "2026-10-29"]
         (cycle/period-starts [{:date "2026-10-01" :period_start 1}]
                              [{:date "2026-10-03" :period 1 :predicted 0} ; same period as the logged start
                               {:date "2026-10-29" :period 1 :predicted 0}
                               {:date "2026-10-30" :period 1 :predicted 0}
                               {:date "2026-11-26" :period 1 :predicted 1}])) ; predicted: not a start
      "the log wins, the watch's runs of bleeding add their first day, predictions are ignored")
  (is (= [28 29 28] (cycle/lengths starts)))
  (is (= 28 (cycle/typical-length starts)) "the median")
  (is (= 28 (cycle/typical-length [])) "the default")
  (is (= [] (cycle/lengths ["2026-01-01" "2026-01-05" "2026-05-01"]))
      "a bleed logged twice and a missed period are no cycles"))

(deftest day-info-test
  (let [ctx {:starts starts :contraception "natural"}
        ph  #(:phase (cycle/day-info ctx %))]
    (is (nil? (cycle/day-info ctx "2026-07-01")) "before the first start")
    (is (= [:menstrual 1] ((juxt :phase :cycle_day) (cycle/day-info ctx "2026-10-25"))))
    (is (= :menstrual (ph "2026-10-29")))
    (is (= :follicular (ph "2026-11-02")))
    (is (= :ovulation (ph "2026-11-08")) "day 15")
    (is (= :luteal (ph "2026-11-12")))
    (is (= :late-luteal (ph "2026-11-20")))
    (is (= :overdue (ph "2026-12-10")) "more than a week late")
    (is (true? (:estimated (cycle/day-info ctx "2026-11-08"))))
    (is (false? (:estimated (cycle/day-info ctx "2026-10-26"))) "a bleeding day is not an estimate"))
  (testing "hormonal contraception: bleeding days only"
    (let [ctx {:starts starts :contraception "hormonal"}]
      (is (= :bleed (:phase (cycle/day-info ctx "2026-10-27"))))
      (is (= :no-bleed (:phase (cycle/day-info ctx "2026-11-08")))))))

(deftest predict-test
  (let [ctx {:starts starts :contraception "natural" :today "2026-11-10"}]
    (is (= "2026-11-22" (cycle/next-period (assoc ctx :typical 28))) "25 Oct + 28 days")
    (is (= ["luteal" "luteal"] (map (comp name :phase) (cycle/predict ctx "2026-11-10" "2026-11-11"))))
    (let [days (cycle/predict ctx "2026-11-21" "2026-11-24")]
      (is (= [:late-luteal :menstrual :menstrual :menstrual] (map :phase days)) "the next period starts on the 22nd")
      (is (= [28 1 2 3] (map :cycle_day days))))
    (is (nil? (cycle/next-period (assoc ctx :today "2026-12-01"))) "already late: no date")
    (is (empty? (cycle/predict {:starts [] :today "2026-11-10"} "2026-11-10" "2026-11-12")))))

(deftest flags-test
  (let [base {:starts starts :contraception "natural" :today "2026-11-10" :log []}
        long ["2026-01-01" "2026-02-10" "2026-03-20" "2026-04-02" "2026-05-30"] ; 40, 38, 13 (no cycle), 58
        irregular ["2026-06-01" "2026-06-24" "2026-07-31" "2026-08-30" "2026-09-26"] ; 23, 37, 30, 27
        bleeding (fn [start n] (for [i (range n)] {:date (coach.season/add-days start i) :flow "light"}))]
    (is (= [] (cycle/flags base)))
    (is (= [:missed-period] (cycle/flags (assoc base :today "2026-12-20"))))
    (is (= [] (cycle/flags (assoc base :today "2026-12-20" :contraception "hormonal"))) "no cycle, nothing missed")
    (is (= [] (cycle/flags (assoc base :starts irregular :today "2026-10-10"))) "23 to 37 days: 14 apart is fine")
    (is (= [] (cycle/flags (assoc base :starts ["2026-06-01" "2026-06-24" "2026-07-26" "2026-08-30" "2026-09-26"]
                                  :today "2026-10-10"))) "23, 32, 35, 27: 12 days apart")
    (is (= [:irregular] (cycle/flags (assoc base :starts ["2026-06-01" "2026-06-20" "2026-07-30" "2026-08-30"
                                                         "2026-09-26"] :today "2026-10-10"))) "19, 40, 31, 27")
    (is (= [:short-cycles] (cycle/flags (assoc base :starts ["2026-09-01" "2026-09-19" "2026-10-08" "2026-10-27"]
                                               :today "2026-11-01")))
        "two cycles under 21 days (18, 19, 19)")
    (is (= [] (cycle/flags (assoc base :starts ["2026-10-01" "2026-10-18"] :today "2026-10-20"))) "one is not enough")
    (is (= [:infrequent] (cycle/flags (assoc base :starts ["2026-04-20" "2026-06-20" "2026-09-20"]
                                                          :today "2026-10-25")))
        "two periods in six months of tracking")
    (is (= [] (cycle/flags (assoc base :starts ["2026-09-20" "2026-10-18"] :today "2026-11-10")))
        "a new user is not alarmed")
    (is (= [:prolonged] (cycle/flags (assoc base :log (concat (bleeding "2026-08-29" 11) (bleeding "2026-09-27" 10)
                                                              (bleeding "2026-10-25" 5)))))
        "ten bleeding days in two periods")
    (is (= [] (cycle/flags (assoc base :log (concat (bleeding "2026-08-29" 11) [{:date "2026-09-27" :flow "light"}]))))
        "once is not twice")
    (is (= [:spotting] (cycle/flags (assoc base :log [{:date "2026-09-12" :flow "spotting"}
                                                      {:date "2026-10-13" :flow "spotting_more"}])))
        "spotting in two cycles")
    (is (= [] (cycle/flags (assoc base :log [{:date "2026-09-12" :flow "spotting"}
                                             {:date "2026-09-14" :flow "spotting"}])))
        "twice in the same cycle")
    (is (= [:heavy-bleeding]
           (cycle/flags (assoc base :log [{:date "2026-09-28" :flow "heavy"} {:date "2026-10-26" :flow "heavy"}])))
        "heavy flow in two of the last three periods")
    (is (= [:irregular] (cycle/flags (assoc base :starts long :today "2026-06-10")))
        "40, 38, 58 days (13 is no cycle)")))

(deftest stats-window-test
  (let [log    (for [s ["2026-09-27" "2026-10-25"] i (range 4)] {:date (coach.season/add-days s i) :flow "medium"})
        bleed  (cycle/bleed-dates (concat log [{:date "2026-10-10" :flow "spotting"}]) [])
        ctx    {:starts starts :bleed bleed :today "2026-11-10" :contraception "natural"}
        st     (cycle/stats ctx)]
    (is (not (contains? bleed "2026-10-10")) "spotting is no bleeding day")
    (is (= {:typical 28 :cycles 3 :variability 1 :period_length 4} st) "28, 29, 28 days; four days of flow")
    (is (= ["2026-11-21" "2026-11-23"] (cycle/next-window ctx)) "expected 22 Nov, ±1 with 1 day of variability")
    (is (= ["2026-11-23" "2026-11-29"] (cycle/next-window (assoc ctx :starts ["2026-10-01" "2026-10-29"]
                                                                  :today "2026-11-10")))
        "fewer than three cycles: ±3")
    (is (nil? (cycle/next-window (assoc ctx :today "2026-12-30"))) "late: no window")
    (is (nil? (:period_length (cycle/stats (assoc ctx :bleed #{})))) "no flow logged")))

(deftest history-bar-test
  (let [log  [{:date "2026-10-26" :flow "heavy" :symptoms {"cramps" 3}} {:date "2026-10-27" :flow "spotting"}
              {:date "2026-11-03" :symptoms {"mood" 1}}]
        ctx  {:starts starts :log log :bleed (cycle/bleed-dates log []) :today "2026-11-10" :contraception "natural"}
        h    (cycle/history ctx 2)
        cur  (last h)]
    (is (= ["2026-09-27" "2026-10-25"] (map :start h)))
    (is (= 28 (:days (first h))))
    (is (= {:current true :days 28 :elapsed 17 :bleed [2] :spotting [3] :symptoms [2] :ovulation 14}
           (select-keys cur [:current :days :elapsed :bleed :spotting :symptoms :ovulation])))
    (is (nil? (:ovulation (last (cycle/history (assoc ctx :contraception "hormonal") 1)))))
    (let [b (cycle/bar ctx)]
      (is (= [28 17] ((juxt :days :today) b)))
      (is (= ["menstrual" "follicular" "ovulation" "luteal" "late-luteal"] (map :phase (:segments b))))
      (is (= [1 5] ((juxt :from :to) (first (:segments b)))))
      (is (= 28 (:to (last (:segments b)))))
      (is (= [28 30] (:window b)) "expected on day 29, ±1"))
    (is (nil? (cycle/bar {:starts [] :today "2026-11-10"})))))

(deftest period-end-test
  (let [log   [{:date "2026-10-25" :period_start 1 :flow "heavy"}
               {:date "2026-10-29" :period_end 1}]
        ends  (cycle/period-ends log)
        ctx   {:starts ["2026-10-25"] :typical 28 :ends ends :today "2026-11-10"}]
    (is (= ["2026-10-29"] ends))
    (is (= 5 (cycle/period-length ends "2026-10-25")))
    (is (nil? (cycle/period-length ends "2026-11-20")) "an end before a start belongs to no period")
    (is (= #{"2026-10-25" "2026-10-26" "2026-10-27" "2026-10-28" "2026-10-29"} (cycle/bleed-dates log []))
        "the days between start and end count as bleeding")
    (is (= 5 (:period_length (cycle/stats (assoc ctx :bleed (cycle/bleed-dates log []))))))
    (is (= :menstrual (:phase (cycle/day-info ctx "2026-10-29"))))
    (is (= :follicular (:phase (cycle/day-info ctx "2026-10-30"))))
    (let [short (assoc ctx :ends ["2026-10-27"])]
      (is (= :follicular (:phase (cycle/day-info short "2026-10-28"))) "a three day period ends the menstrual phase"))
    (is (= :menstrual (:phase (cycle/day-info (assoc ctx :ends [] :starts ["2026-10-25"]) "2026-10-29")))
        "without an end the period lasts five days")))

(deftest tags-test
  (is (= ["happy" "sex_protected" "dry"] (cycle/canonical-tags ["dry" "bogus" :happy "sex_protected" "happy"]))
      "known tags only, in the usual order, no repeats")
  (let [ctx {:starts starts :today "2026-11-03" :contraception "natural"
             :log [{:date "2026-11-02" :tags ["anxious" "sex_unprotected" "dry"]}
                   {:date "2026-11-03" :tags ["sex_protected"]}]}
        week (:symptoms_last_7_days (cycle/context ctx "2026-11-03" "2026-11-03"))]
    (is (= [{:date "2026-11-02" :tags ["anxious"]}] week)
        "the planner sees mood and illness, never sex or discharge")))

(deftest symptom-map-test
  (let [log [{:date "2026-09-27" :symptoms {"cramps" 2}}                       ; day 1 of cycle 2
             {:date "2026-10-25" :symptoms {"cramps" 3 "nausea" 1}}            ; day 1 of cycle 3
             {:date "2026-10-26" :symptoms {"cramps" 1}}
             {:date "2026-11-08" :symptoms {"mood" 2}}
             {:date "2026-08-01" :symptoms {"mood" 1}}                         ; before the cycles looked at
             {:date "2026-11-09" :flow "light"}]                               ; no symptom: ignored
        m   (cycle/symptom-map {:starts starts :log (map #(update % :symptoms cycle/canonical-symptoms) log)} 2)
        by  (into {} (map (juxt :symptom identity)) (:rows m))]
    (is (= 2 (:cycles m)) "two cycles with a symptom")
    (is (= ["cramps" "mood" "gi"] (map :symptom (:rows m))) "most logged first, then the usual order")
    (is (= {1 {:cycles 2 :max 3} 2 {:cycles 1 :max 1}} (:days (by "cramps"))) "day 1 in both cycles, the worst is 3")
    (is (= {15 {:cycles 1 :max 2}} (:days (by "mood"))) "8 Nov is day 15 of the cycle from 25 Oct")
    (is (= 3 (:total (by "cramps"))))
    (is (= {:cycles 0 :rows []} (cycle/symptom-map {:starts [] :log log} 6)))
    (is (= {:cycles 0 :rows []}
           (cycle/symptom-map {:starts ["2026-01-01"] :log [{:date "2026-03-01" :symptoms {"gi" 1}}]} 6))
        "a missed period (day 60) is no pattern")))

(deftest symptoms-canonical-test
  (is (= {"gi" 3 "cramps" 2} (cycle/canonical-symptoms {:nausea 1 "gi" 3 :cramps 2 :bogus 2 "mood" 9}))
      "the old nausea folds into gi (the higher one wins), unknown names and severities are dropped"))

(deftest context-test
  (let [c (cycle/context {:starts starts :contraception "natural" :today "2026-11-10"
                          :log [{:date "2026-11-09" :symptoms {"fatigue" 2}}
                                {:date "2026-10-01" :symptoms {"cramps" 3}}]}
                         "2026-11-09" "2026-11-15")]
    (is (= "luteal" (get-in c [:today :phase])))
    (is (= 7 (count (:target_week c))))
    (is (= [{:date "2026-11-09" :symptoms {"fatigue" 2}}] (:symptoms_last_7_days c)) "only the last week")
    (is (= "2026-11-22" (:next_period_expected c)))))

(deftest garmin-cycle-rows-test
  (is (= [] (gm/cycle-rows {:other 1})))
  (let [rows (gm/cycle-rows {:daySummaries [{:calendarDate "2026-11-01" :dayInCycle 1 :phase "Menstruation"
                                             :isPredictedCycle false}
                                            {:calendarDate "2026-11-10" :dayInCycle 10 :phase "Follicular"
                                             :isPredictedCycle true}]})]
    (is (= [["2026-11-01" 1 "menstrual" 1 0] ["2026-11-10" 10 "follicular" 0 1]]
           (map (juxt :date :cycle_day :phase :period :predicted) rows)))))

(deftest profile-test
  (let [ds (temp-ds)]
    (db/set-state! ds :sex "FEMALE")
    (is (= {:sex "FEMALE" :sex_source "garmin" :garmin_sex "FEMALE" :cycle_tracking false
            :contraception "unspecified"}
           (cd/profile ds)))
    (is (true? (:cycle_tracking (cd/save-profile! ds {:cycle_tracking true :contraception "hormonal"}))))
    (is (cd/tracking? ds))
    (testing "choosing male switches tracking off, Garmin's value stays"
      (let [p (cd/save-profile! ds {:sex "MALE"})]
        (is (= ["MALE" "athlete" "FEMALE" false] ((juxt :sex :sex_source :garmin_sex :cycle_tracking) p)))))
    (is (false? (:cycle_tracking (cd/save-profile! ds {:cycle_tracking true}))) "not for a man")
    (is (= "garmin" (:sex_source (cd/save-profile! ds {:sex ""}))))
    (is (thrown? clojure.lang.ExceptionInfo (cd/save-profile! ds {:sex "X"})))
    (is (thrown? clojure.lang.ExceptionInfo (cd/save-profile! ds {:contraception "pill"})))))

(deftest log-and-context-test
  (let [ds (temp-ds)]
    (db/set-state! ds :sex "FEMALE")
    (is (nil? (cd/planner-context ds "2026-11-10" "2026-11-09" "2026-11-15")) "off: nothing for the planner")
    (is (nil? (cd/overview ds "2026-11-10")))
    (cd/save-profile! ds {:cycle_tracking true})
    (cd/save-log! ds "2026-11-01" {:period_start true :flow "heavy" :symptoms {"cramps" 3 "bogus" 2 "mood" 9}})
    (is (= {:date "2026-11-01" :period_start 1 :flow "heavy" :symptoms {"cramps" 3} :tags [] :period_end 0 :note nil}
           (first (cd/log-rows ds "2026-11-01" "2026-11-01"))) "unknown symptoms and severities are dropped")
    (let [ov (cd/overview ds "2026-11-10")]
      (is (= 10 (get-in ov [:today :cycle_day])))
      (is (= "2026-11-29" (:next_period ov))))
    (is (= 10 (get-in (cd/planner-context ds "2026-11-10" "2026-11-09" "2026-11-15") [:today :cycle_day])))
    (is (some? (cd/save-log! ds "2026-11-02" {:note "tired"})))
    (is (thrown? Exception (cd/save-log! ds "2999-01-01" {:note "future"})) "a day that has not come is refused")
    (is (= 1 (:period_end (cd/save-log! ds "2026-11-05" {:period_end true}))))
    (cd/save-log! ds "2026-11-05" {})
    (cd/save-log! ds "2026-11-02" {})
    (is (= 1 (count (cd/log-rows ds "2026-11-01" "2026-11-30"))) "an empty day is removed")
    (cd/delete-data! ds)
    (is (false? (cd/tracking? ds)))
    (is (empty? (cd/log-rows ds "2026-01-01" "2026-12-31")))))

(def ^:private run {:id 1 :date "2026-11-10" :status "planned" :session_id "TEMPO" :title "Tempo" :duration_min 60})

(deftest adapt-cycle-signals-test
  (let [ctx {:today "2026-11-10" :acts [] :wellness [] :workouts [run] :settings {}}
        ids #(set (map :id (adapt/signals %)))]
    (is (not-any? #{:cycle-symptoms :cycle-irregular} (ids ctx)) "without tracking: no cycle signals")
    (is (contains? (ids (assoc ctx :cycle {:log [{:date "2026-11-10" :symptoms {"cramps" 2}}] :flags []}))
                   :cycle-symptoms))
    (is (not (contains? (ids (assoc ctx :cycle {:log [{:date "2026-11-10" :symptoms {"cramps" 1}}] :flags []}))
                        :cycle-symptoms)) "mild symptoms: no change")
    (is (contains? (ids (assoc ctx :cycle {:log [] :flags [:missed-period]})) :cycle-irregular))
    (is (not (:trigger? (first (filter #(= :cycle-irregular (:id %))
                                       (adapt/signals (assoc ctx :cycle {:log [] :flags [:missed-period]}))))))
        "a flag, not a reason to change the plan")))

(deftest readiness-luteal-test
  (let [days [{:date "2026-11-10" :hrv_avg 40 :hrv_status "BALANCED" :rhr 50 :sleep_s 28000 :sleep_score 80}]
        s    {:hrv_low 50 :rhr_high 60}]
    (is (= :adjust (:level (readiness/check days [] s))) "cycle-blind: a low HRV is a signal")
    (let [r (readiness/check days [] s :luteal)]
      (is (= :ok (:level r)) "luteal phase, HRV alone: the usual shift")
      (is (= 1 (count (filter #(re-find #"Luteal" %) (:signals r))))))
    (is (= :adjust (:level (readiness/check days [] s :follicular))))
    (is (= :rest (:level (readiness/check [(assoc (first days) :rhr 65)] [] s :luteal))) "both low is still a signal")))

(deftest patterns-test
  (let [;; two complete cycles of 28 days from 1 Sep: luteal days have a lower HRV and a higher resting HR
        st    ["2026-09-01" "2026-09-29" "2026-10-27"]
        ctx   {:starts st :contraception "natural"}
        dates (take 56 (iterate #(coach.season/add-days % 1) "2026-09-01"))
        luteal? (fn [d] (= :luteal (:phase (cycle/day-info (assoc ctx :typical 28) d))))
        well  (for [d dates] {:date d :hrv_avg (if (luteal? d) 40 50) :rhr (if (luteal? d) 54 50)})
        p     (cycle/patterns ctx well [{:date "2026-09-25" :symptoms {"cramps" 2}}
                                        {:date "2026-09-26" :symptoms {"mood" 1}}]
                              [{:date "2026-09-20" :rpe 7} {:date "2026-09-04" :rpe 3}])
        by    (into {} (map (juxt :phase identity)) (:phases p))]
    (is (= 2 (:cycles p)))
    (is (neg? (:hrv_delta (by "luteal"))) "HRV lower in her luteal phase")
    (is (pos? (:rhr_delta (by "luteal"))))
    (is (> (:hrv_delta (by "follicular")) 0))
    (is (= 7.0 (:rpe_avg (by "luteal"))))
    (is (= 1 (:sessions (by "menstrual"))))
    (is (= [2 1] ((juxt :logged_days :symptom_days) (by "late-luteal"))) "only the moderate one counts")
    (is (= {:cycles 0 :phases []} (cycle/patterns {:starts ["2026-09-01"]} well [] [])) "one start: no cycle yet")
    (is (= [{:start "2026-09-01" :days 28} {:start "2026-09-29" :days 28}] (cycle/cycle-list st 12)))
    (is (= 10 (get-in (cycle/context (assoc ctx :today "2026-11-10" :log [] :patterns p) "2026-11-09" "2026-11-15")
                      [:personal_patterns :phases 0 :days])) "the planner gets them once there is a cycle")))

(deftest readiness-own-pattern-test
  (let [days [{:date "2026-11-10" :hrv_avg 40 :hrv_status "BALANCED" :rhr 50 :sleep_s 28000 :sleep_score 80}]
        s    {:hrv_low 50 :rhr_high 60}]
    (is (= :ok (:level (readiness/check days [] s {:phase :luteal :cycles 4 :hrv_delta -5 :rhr_delta 2}))))
    (is (= :adjust (:level (readiness/check days [] s {:phase :luteal :cycles 4 :hrv_delta 0.2 :rhr_delta 0})))
        "three cycles show her luteal phase doesn't shift her HRV: not explained away")))
