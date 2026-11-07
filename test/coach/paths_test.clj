(ns coach.paths-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.paths :as paths]))

(deftest instance-names
  (is (nil? (paths/parse-instance nil)))
  (is (nil? (paths/parse-instance "  ")))
  (is (= "anna" (paths/parse-instance " Anna ")))
  (is (= "team-2" (paths/parse-instance "team-2")))
  (testing "nothing that could leave the folder or break a file name"
    (doseq [bad ["../x" "a b" "-a" "a/b" (apply str (repeat 33 "a"))]]
      (is (thrown? clojure.lang.ExceptionInfo (paths/parse-instance bad)) bad))))

(deftest per-instance-files
  (is (= "config.edn" (paths/per-instance "config.edn" nil)))
  (is (= "config-anna.edn" (paths/per-instance "config.edn" "anna")))
  (is (= "coros-anna.json" (paths/per-instance "coros.json" "anna")))
  (is (= "token-anna" (paths/per-instance "token" "anna"))))

(deftest one-at-a-time-runs-and-releases
  (let [waited (atom 0)
        lock   "sync-test.lock"]
    (try
      (is (= 1 (paths/one-at-a-time lock #(swap! waited inc) (constantly 1))))
      (is (= 2 (paths/one-at-a-time lock #(swap! waited inc) (constantly 2))) "released after the first")
      (is (zero? @waited))
      (finally (.delete (paths/app-file lock))))))

(deftest per-instance-dotfiles
  (is (= ".garminconnect-anna" (paths/per-instance ".garminconnect" "anna")))
  (is (= "dashboard-token-anna" (paths/per-instance "dashboard-token" "anna"))))

(deftest garmin-token-env-pairs-the-export-with-the-folder
  (let [{dir "GARMINTOKENS" b64 "GARMINTOKENS_BASE64"} (paths/garmin-token-env)]
    (is (= dir (paths/garmin-token-dir)))
    (when-not (System/getenv "GARMINTOKENS_BASE64")
      (is (= (str dir "_base64") b64)))))
