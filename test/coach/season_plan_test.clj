(ns coach.season-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.season-plan :as sp]))

(deftest mondays-test
  (testing "from the current week to race week, inclusive"
    (let [ms (sp/mondays "2026-10-26" "2030-04-07")]
      (is (= "2026-10-26" (first ms)))
      (is (= "2027-04-05" (last ms)))
      (is (= 24 (count ms)))))
  (is (= ["2027-04-05"] (sp/mondays "2027-04-05" "2030-04-07"))))

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
