(ns coach.auth-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest connected-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "garmin-tokens" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (is (false? (auth/connected? (str dir))) "an empty folder: never logged in")
    (is (false? (auth/connected? (str dir "/missing"))) "no folder at all")
    (spit (java.io.File. dir "garmin_tokens.json") "{}")
    (is (true? (auth/connected? (str dir))))))

(deftest not-connected-status-test
  (is (= :not_connected (:level (auth/status nil false))) "before the first login, never \"expired\""))

(deftest failure-message-test
  (testing "the lines from garmin-mcp-auth's ✗ on"
    (is (= "Authentication failed Invalid email or password. Please check your Garmin Connect credentials."
           (auth/failure-message (str "Authenticating with Garmin Connect...\nEmail: a@b.c\n\n"
                                      "✗ Authentication failed\n  Invalid email or password.\n"
                                      "  Please check your Garmin Connect credentials.\n")))))
  (testing "without a ✗, its last line"
    (is (= "error: Failed to fetch" (auth/failure-message "Resolving…\nerror: Failed to fetch\n"))))
  (is (= "" (auth/failure-message ""))))
