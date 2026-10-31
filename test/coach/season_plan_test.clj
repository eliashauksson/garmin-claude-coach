(ns coach.season-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.db]
            [coach.plan]
            [coach.planner]
            [coach.season]
            [coach.season-plan :as sp]))

(deftest mondays-test
  (testing "from the current week to race week, inclusive"
    (let [ms (sp/mondays "2026-10-26" "2027-03-28")]
      (is (= "2026-10-26" (first ms)))
      (is (= "2027-03-22" (last ms)))
      (is (= 22 (count ms)))))
  (is (= ["2027-03-22"] (sp/mondays "2027-03-22" "2027-03-28"))))

(deftest weekly-tss-test
  (is (= {"2026-10-26" 150 "2026-10-19" 40}
         (sp/weekly-tss [{:date "2026-10-27" :tss 100.4} {:date "2026-11-01" :tss 49.8}
                         {:date "2026-10-19" :tss 40} {:date "2026-10-20" :tss nil}]))))

(defn- wk [monday phase tss] {:monday monday :phase phase :tss tss :cutback false :focus "x"})

(deftest validate-test
  (let [ctx  {:weeks_to_plan ["2027-03-29" "2027-04-05"]}
        good {:summary "s" :warnings [] :weeks [(wk "2027-03-29" "Taper" 200) (wk "2027-04-05" "Race week" 450)]}]
    (is (= good (sp/validate good ctx)))
    (testing "a missing or extra week is rejected"
      (is (thrown? Exception (sp/validate (update good :weeks pop) ctx)))
      (is (thrown? Exception (sp/validate (update good :weeks conj (wk "2027-04-12" "Race week" 1)) ctx))))
    (testing "unknown phases and negative TSS are rejected"
      (is (thrown? Exception (sp/validate (assoc-in good [:weeks 0 :phase] "Peak") ctx)))
      (is (thrown? Exception (sp/validate (assoc-in good [:weeks 0 :tss] -5) ctx))))))

(defn- with-season!
  "A current season plan with race day `race` (default 28 Mar 2027); the weeks don't matter here."
  [ds & [race]]
  (let [id (coach.plan/create-season-plan! ds (or race "2027-03-28") "2026-10-26" "m")]
    (coach.plan/finish-season-plan! ds id {:response {:summary "s" :warnings [] :weeks []}})))

(deftest weeks-to-plan-test
  (let [f  (java.io.File/createTempFile "coach-test" ".db")
        ds (coach.db/datasource (.getPath f))]
    (try
      (coach.db/migrate! ds)
      (testing "nothing without a season plan"
        (is (= [] (coach.planner/weeks-to-plan ds "2026-10-31"))))
      (with-season! ds)
      (testing "the two weeks after the current one"
        (is (= ["2026-11-02" "2026-11-09"] (coach.planner/weeks-to-plan ds "2026-10-31"))))
      (testing "a week with a proposal (running, open or recently failed) is left alone"
        (coach.plan/create-proposal! ds "2026-11-02" "m")
        (let [id (coach.plan/create-proposal! ds "2026-11-09" "m")]
          (coach.plan/fail-proposal! ds id "boom"))
        (is (= [] (coach.planner/weeks-to-plan ds "2026-10-31"))))
      (testing "on Monday the new second week is due"
        (is (= ["2026-11-16"] (coach.planner/weeks-to-plan ds "2026-11-02"))))
      (testing "nothing after race week"
        (is (= ["2027-03-22"] (coach.planner/weeks-to-plan ds "2027-03-17")))
        (is (= [] (coach.planner/weeks-to-plan ds "2027-03-23"))))
      (finally (.delete f)))))

(deftest backups-test
  (let [f  (java.io.File/createTempFile "coach-test" ".db")
        ds (coach.db/datasource (.getPath f))
        draft! (fn [tss]
                 (let [id (coach.plan/create-season-plan! ds "2027-03-28" "2026-10-26" "m")]
                   (coach.plan/finish-season-plan! ds id {:response {:summary "s" :warnings []
                                                                     :weeks [(wk "2026-10-26" "Base" tss)]}})
                   id))
        current #(:id (coach.plan/latest-season-plan ds "ready"))
        backup-ids #(mapv :id (coach.plan/season-backups ds))]
    (try
      (coach.db/migrate! ds)
      (let [a (draft! 100) b (draft! 200)]
        (testing "a redraft keeps the previous plan as a backup"
          (is (= b (current)))
          (is (= [a] (backup-ids)))
          (is (= 100 (:peak_tss (first (coach.plan/season-backups ds))))))
        (testing "delete leaves no current plan and keeps it as a backup"
          (coach.plan/delete-season-plan! ds)
          (is (nil? (current)))
          (is (= [b a] (backup-ids)))
          (is (thrown? Exception (coach.plan/delete-season-plan! ds))))
        (testing "restore brings a backup back"
          (coach.plan/restore-season-plan! ds a)
          (is (= a (current)))
          (is (= [b] (backup-ids)))
          (is (thrown? Exception (coach.plan/restore-season-plan! ds 999))))
        (testing "restore swaps out the current plan"
          (coach.plan/restore-season-plan! ds b)
          (is (= b (current)))
          (is (= [a] (backup-ids))))
        (testing "only the newest 3 backups are kept"
          (let [c (draft! 300) d (draft! 400) e (draft! 500)]
            (is (= e (current)))
            (is (= [d c b] (backup-ids))))))
      (finally (.delete f)))))

(deftest clear-upcoming-test
  (let [f  (java.io.File/createTempFile "coach-test" ".db")
        ds (coach.db/datasource (.getPath f))
        today (str (java.time.LocalDate/now))
        day   #(str (.plusDays (java.time.LocalDate/now) %))
        add!  (fn [d] (coach.plan/add-workout! ds {:date d :slot "AM" :session_id "EASY" :title "t"
                                                    :duration_min 30 :distance_km 5}))
        status #(:status (first (coach.db/query ds ["SELECT status FROM planned_workouts WHERE id = ?" %])))]
    (try
      (coach.db/migrate! ds)
      (with-season! ds (day 365))
      (let [m1 (coach.season/add-days (coach.season/monday today) 7)
            m2 (coach.season/add-days m1 7)
            past (add! (day -2)) now (add! today) later (add! today) skipped (add! today)
            open (coach.plan/create-proposal! ds m1 "m")
            running (coach.plan/create-proposal! ds m2 "m")]
        (coach.plan/update-workout! ds skipped {:status "skipped"})
        (coach.plan/finish-proposal! ds open {:response {:workouts [{:date m1 :slot "AM" :session_id "EASY"
                                                                      :title "t" :duration_min 30 :distance_km 5}]}})
        (is (= 2 (coach.plan/clear-upcoming! ds today)))
        (testing "upcoming planned sessions are cancelled; past and skipped ones stay"
          (is (= ["planned" "cancelled" "cancelled" "skipped"] (mapv status [past now later skipped]))))
        (testing "open and running proposals are withdrawn, their pending items rejected"
          (is (= "withdrawn" (:status (coach.plan/proposal ds open))))
          (is (= ["rejected"] (mapv :decision (:items (coach.plan/proposal ds open)))))
          (is (= "withdrawn" (:status (coach.plan/proposal ds running)))))
        (testing "a proposal finishing after it was withdrawn stays withdrawn"
          (coach.plan/finish-proposal! ds running {:response {:workouts []}})
          (coach.plan/fail-proposal! ds running "late")
          (is (= "withdrawn" (:status (coach.plan/proposal ds running)))))
        (testing "weeks whose proposals were withdrawn can be planned again"
          (is (= [m1 m2] (coach.planner/weeks-to-plan ds today)))))
      (finally (.delete f)))))
