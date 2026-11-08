(ns coach.spending-limit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [coach.claude :as claude]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.paths :as paths])
  (:import (java.io File)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def ^:dynamic *limits* nil)

(use-fixtures :each
  (fn [f]
    (let [file (File/createTempFile "coach-limits" ".edn")]
      (.delete file)
      (with-redefs [costs/limits-file (fn [] file)
                    paths/athletes    (fn [] [{:name nil :current true} {:name "anna" :current false}])
                    paths/instance    (constantly nil)]
        (binding [*limits* file]
          (try (f) (finally (.delete file) (costs/use-db! nil))))))))

(defn- temp-ds []
  (let [f (File/createTempFile "coach-limit" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- spend! [ds days-ago usd]
  (costs/insert! ds {:at (str (.minus (Instant/now) (long days-ago) ChronoUnit/DAYS)) :task "week_plan"
                     :status "ok" :cost_usd usd}))

(defn- calls [ds] (:n (first (db/query ds ["SELECT count(*) AS n FROM api_calls"]))))

(deftest limit-hit-test
  (is (nil? (costs/limit-hit nil {:today 5.0 :month 50.0})) "no limits")
  (is (nil? (costs/limit-hit {:daily_usd 2.0 :monthly_usd 20.0} {:today 1.99 :month 19.0})))
  (is (= "day" (:period (costs/limit-hit {:daily_usd 2.0 :monthly_usd 20.0} {:today 2.0 :month 19.0}))))
  (is (= "month" (:period (costs/limit-hit {:daily_usd 2.0 :monthly_usd 20.0} {:today 5.0 :month 20.0})))
      "the month goes first: it lasts longer")
  (is (= "month" (:period (costs/limit-hit {:monthly_usd 20.0} {:today 0.0 :month 21.0})))))

(deftest save-and-read-test
  (is (= {} (costs/read-limits)) "no file: no limits")
  (costs/save-limits! [{:name nil :daily_usd 2.5 :monthly_usd "20,5"}
                       {:name "anna" :daily_usd "" :monthly_usd "10"}
                       {:name "gone" :daily_usd 1 :monthly_usd 1}])
  (is (= {nil {:daily_usd 2.5 :monthly_usd 20.5} "anna" {:monthly_usd 10.0}} (costs/read-limits))
      "per athlete; unknown athletes ignored")
  (testing "empty fields remove a limit"
    (costs/save-limits! [{:name nil :daily_usd nil :monthly_usd ""} {:name "anna" :monthly_usd 10}])
    (is (= {"anna" {:monthly_usd 10.0}} (costs/read-limits))))
  (testing "bad amounts are refused and change nothing"
    (doseq [bad ["abc" 0 -1 "NaN"]]
      (is (thrown? clojure.lang.ExceptionInfo (costs/save-limits! [{:name nil :daily_usd bad}]))))
    (is (= {"anna" {:monthly_usd 10.0}} (costs/read-limits))))
  (testing "a damaged file means no limits, not a crash"
    (spit *limits* "{not edn")
    (is (= {} (costs/read-limits)))))

(deftest spent-test
  (let [ds (temp-ds)]
    (spend! ds 0 1.5)
    (spend! ds 0 0.5)
    (spend! ds 40 9.0)
    (is (= 2.0 (:today (costs/spent ds))) "only today's")
    (is (= 2.0 (:month (costs/spent ds))) "a request 40 days ago is last month's")))

(deftest request-stops-at-the-limit-test
  (let [ds   (temp-ds)
        sent (atom 0)]
    (costs/use-db! ds)
    (spend! ds 0 3.0)
    (with-redefs [claude/request* (fn [_] (swap! sent inc) {:response {} :usage-raw {} :stop "end_turn"})]
      (let [opts {:system "x" :messages [[:user "y"]] :schema {} :task :week_plan}]
        (testing "under the limit requests go through"
          (costs/save-limits! [{:name nil :daily_usd 5}])
          (claude/request opts)
          (is (= 1 @sent)))
        (testing "at the limit: stopped before anything is sent or logged"
          (costs/save-limits! [{:name nil :daily_usd 3}])
          (let [before (calls ds)
                e      (is (thrown? clojure.lang.ExceptionInfo (claude/request opts)))]
            (is (= :cost-limit (:type (ex-data e))))
            (is (re-find #"3\.00 USD" (ex-message e)))
            (is (= 1 @sent))
            (is (= before (calls ds)))
            (is (= "day" (:period (costs/limit-reached ds))) "the banner sees it too")))
        (testing "testing the API key stays possible"
          (claude/request (assoc opts :task :test))
          (is (= 2 @sent)))
        (testing "a batch is stopped as well"
          (is (thrown? clojure.lang.ExceptionInfo (claude/submit-batch! ds (assoc opts :data {})))))))))

(deftest limits-are-per-athlete-test
  (let [ds (temp-ds)]
    (spend! ds 0 3.0)
    (costs/save-limits! [{:name "anna" :daily_usd 1}])
    (testing "another athlete's limit doesn't apply here"
      (is (nil? (costs/limit-reached ds))))
    (testing "this athlete's own limit does, on its own spending only"
      (with-redefs [paths/instance (constantly "anna")]
        (is (= "day" (:period (costs/limit-reached ds))))))))

(deftest overview-test
  (let [ds (temp-ds)]
    (spend! ds 0 1.0)
    (with-redefs [paths/athletes        (fn [] [{:name nil :current true}])
                  paths/instance-label  (constantly nil)]
      (costs/save-limits! [{:name nil :daily_usd 1 :monthly_usd 9}])
      (let [{:keys [can_edit athletes]} (costs/limits-overview ds)
            row (first athletes)]
        (is can_edit)
        (is (= [1.0 1.0 1.0 9.0] (map row [:spent_today :spent_month :daily_usd :monthly_usd])))
        (is (= "day" (get-in row [:hit :period]))))
      (with-redefs [paths/instance (constantly "anna")]
        (is (false? (:can_edit (costs/limits-overview ds))) "other athletes only look")))))

(deftest client-retries-test
  (with-redefs [claude/api-key (constantly "test-key")]
    (is (some? (#'claude/client)) "the client builds with the longer retries")))
