(ns coach.auth-test
  (:require [clojure.test :refer [deftest is]]
            [coach.auth :as auth]))

(deftest countdown-test
  (let [c (auth/countdown "2026-10-29" "2026-10-29")]
    (is (= "2027-04-27" (:expires_on c)))
    (is (= 180 (:days_left c)))
    (is (= :ok (:level c))))
  (is (= :ok      (:level (auth/countdown "2026-10-29" "2027-03-27"))))  ; 31 left
  (is (= :warn    (:level (auth/countdown "2026-10-29" "2027-03-28"))))  ; 30 left
  (is (= :urgent  (:level (auth/countdown "2026-10-29" "2027-04-20"))))  ; 7 left
  (is (= :expired (:level (auth/countdown "2026-10-29" "2027-04-27"))))
  (is (= -3       (:days_left (auth/countdown "2026-10-29" "2027-04-30")))))

(deftest with-sync-result-test
  (let [st   {:level :ok :days_left 170}
        fail {:ok false :kind :auth :message "Garmin login failed" :finished_at "2026-11-01T10:00:00Z"}]
    (is (= :expired (:level (auth/with-sync-result st fail nil))))
    (is (= "Garmin login failed" (:sync_error (auth/with-sync-result st fail "2026-10-29T08:00:00Z"))))
    (is (= st (auth/with-sync-result st fail "2026-11-01T11:00:00Z")) "renewed after the failure")
    (is (= st (auth/with-sync-result st (assoc fail :kind :unreachable) nil)) "not a login problem")
    (is (= st (auth/with-sync-result st {:ok true} nil)))))
