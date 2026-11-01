(ns coach.credits-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.planner :as planner])
  (:import (com.anthropic.errors BadRequestException UnexpectedStatusCodeException)
           (com.anthropic.core JsonValue)
           (com.anthropic.core.http Headers)))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-credits" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- body [t m] (JsonValue/from (json/read-str (json/write-str {"type" "error" "error" {"type" t "message" m}}))))
(defn- headers [] (.build (Headers/builder)))

(def billing-402
  (-> (UnexpectedStatusCodeException/builder) (.statusCode 402) (.headers (headers))
      (.body (body "billing_error" "Billing problem")) (.build)))

(def low-balance-400
  (-> (BadRequestException/builder) (.headers (headers))
      (.body (body "invalid_request_error" "Your credit balance is too low to access the Anthropic API.")) (.build)))

(def other-400
  (-> (BadRequestException/builder) (.headers (headers))
      (.body (body "invalid_request_error" "max_tokens: too large")) (.build)))

(deftest no-credits-test
  (is (planner/no-credits? billing-402) "the typed billing_error")
  (is (planner/no-credits? low-balance-400) "the older 400 form")
  (is (not (planner/no-credits? other-400)))
  (is (not (planner/no-credits? (ex-info "boom" {})))))

(deftest request-without-credits-test
  (let [ds (temp-ds)]
    (try
      (costs/use-db! ds)
      (with-redefs [planner/request* (fn [_] (throw billing-402))]
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (planner/request {:system "x" :messages [[:user "y"]] :schema {} :task :week_plan})))]
          (testing "a clear message with the link to add funds"
            (is (= :no-credits (:type (ex-data e))))
            (is (re-find #"credits have run out" (ex-message e)))
            (is (re-find #"platform\.claude\.com/settings/billing" (ex-message e))))))
      (testing "logged, so every page can show the banner"
        (is (= {:status "error" :error_type "no_credits"}
               (first (db/query ds ["SELECT status, error_type FROM api_calls"]))))
        (is (costs/credits-out ds)))
      (testing "an unrelated failure afterwards doesn't hide it"
        (with-redefs [planner/request* (fn [_] (throw other-400))]
          (is (thrown? Exception (planner/request {:system "x" :messages [[:user "y"]] :schema {} :task :test}))))
        (is (costs/credits-out ds)))
      (testing "a successful request clears it"
        (costs/log! {:task :test :model "claude-haiku-5-5" :usage {:input_tokens 10 :output_tokens 2}
                     :served_model "claude-haiku-5-5" :stop_reason "end_turn"})
        (is (nil? (costs/credits-out ds))))
      (finally (costs/use-db! nil)))))
