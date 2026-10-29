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
