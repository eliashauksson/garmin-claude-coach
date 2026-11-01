(ns coach.batch-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.ai-settings :as ai]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.planner :as planner])
  (:import (com.anthropic.core ObjectMappers)
           (com.anthropic.models.beta.messages BetaMessage)))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-batch" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- message
  "A BetaMessage as the API sends it."
  [text stop]
  (.readValue (ObjectMappers/jsonMapper)
              (json/write-str {"id" "msg_1" "type" "message" "role" "assistant" "model" "claude-opus-5-5"
                               "content" [{"type" "text" "text" text}]
                               "stop_reason" stop "stop_sequence" nil
                               "usage" {"input_tokens" 1000 "output_tokens" 2000
                                        "cache_read_input_tokens" 0 "cache_creation_input_tokens" 0}})
              BetaMessage))

(defn- pending-row [ds cid]
  (next.jdbc/execute! ds ["INSERT INTO claude_batches (custom_id, batch_id, task, ref, model, effort, data, status, submitted_at)
                           VALUES (?, 'msgbatch_1', 'test_task', 'ref 1', 'claude-opus-5-5', 'high', ?, 'pending', ?)"
                          cid (json/write-str {:id 7}) (str (java.time.Instant/now))])
  (first (planner/pending-batches ds)))

(deftest batch-params-test
  (let [p    (planner/batch-request-params {:knowledge "K" :system "S" :messages [[:user "hi"]]
                                            :schema {"type" "object"} :model "claude-opus-5-5" :effort "medium"})
        body (json/read-str (.writeValueAsString (ObjectMappers/jsonMapper) p))]
    (is (= "claude-opus-5-5" (get body "model")))
    (is (= "medium" (get-in body ["output_config" "effort"])))
    (is (= ["K" "S"] (map #(get % "text") (get body "system"))))
    (is (not (contains? body "fallbacks")) "the Batches API rejects fallbacks")))

(deftest handle-result-test
  (let [ds    (temp-ds)
        calls (atom [])]
    (planner/register-batch-handler! :test_task {:done (fn [_ data response usage] (swap! calls conj [:done data response usage]))
                                                 :fail (fn [_ data msg] (swap! calls conj [:fail data msg]))})
    (try
      (costs/use-db! ds)
      (testing "a result goes to the task's handler, at half price"
        (planner/handle-batch-result! ds (pending-row ds "a") {:type "succeeded" :message (message "{\"ok\":true}" "end_turn")})
        (let [[kind data response usage] (first @calls)]
          (is (= [:done {:id 7} {:ok true}] [kind data response]))
          (is (< (Math/abs (- 0.022 (:cost_usd usage))) 1e-9) "1k in x $4 + 2k out x $20, halved"))
        (is (= {:batch 1 :cost_usd 0.022} (update (first (db/query ds ["SELECT batch, cost_usd FROM api_calls"]))
                                                  :cost_usd #(/ (Math/round (* 1000 %)) 1000.0))))
        (is (empty? (planner/pending-batches ds))))
      (testing "a refusal fails the job with a way out (no fallback in batches)"
        (reset! calls [])
        (planner/handle-batch-result! ds (pending-row ds "b") {:type "succeeded" :message (message "" "refusal")})
        (is (= :fail (ffirst @calls)))
        (is (re-find #"no automatic fallback" (last (first @calls)))))
      (testing "out of credits shows the credits message and the banner"
        (reset! calls [])
        (planner/handle-batch-result! ds (pending-row ds "c") {:type "errored" :error "Billing" :error-type "billing_error"})
        (is (re-find #"credits have run out" (last (first @calls))))
        (is (costs/credits-out ds)))
      (testing "expired: not billed, try again"
        (reset! calls [])
        (planner/handle-batch-result! ds (pending-row ds "d") {:type "expired"})
        (is (re-find #"24 hours" (last (first @calls)))))
      (finally (costs/use-db! nil)))))

(deftest weeks-chain-test
  (let [ds   (temp-ds)
        sent (atom [])]
    (with-redefs [planner/principles   (constantly "K")
                  planner/submit-batch! (fn [_ opts] (swap! sent conj (:data opts)) "cid")]
      (testing "automatic: the first week goes to the batch, the second waits for it"
        (let [[a b] (planner/propose-weeks! ds ["2026-11-09" "2026-11-16"] :auto)]
          (Thread/sleep 1500)
          (is (= [[a "2026-11-09"]] (map (juxt :id :monday) @sent)))
          (is (= [[b "2026-11-16"]] (:later (first @sent))))
          (is (= ["batch" "queued"] (map #(:delivery (plan/proposal ds %)) [a b])))
          (testing "when week 1 is back (here: failed), week 2 is sent"
            ((:fail (get @planner/batch-handlers "week_plan")) ds (json/read-str (json/write-str (first @sent)) :key-fn keyword) "x")
            (is (= "failed" (:status (plan/proposal ds a))))
            (is (= b (:id (second @sent))))
            (is (= "batch" (:delivery (plan/proposal ds b)))))))
      (testing "a click runs instantly with the default delivery"
        (is (not (ai/batch? :week_plan :manual)))))))
