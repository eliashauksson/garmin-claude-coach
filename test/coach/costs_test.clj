(ns coach.costs-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.costs :as costs]
            [coach.db :as db]
            [next.jdbc :as jdbc]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-costs" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- close? [a b] (< (Math/abs (- a b)) 1e-9))

;; Shaped like the API's usage JSON (as the SDK's mapper writes it).
(def plain
  {:input_tokens 3000 :output_tokens 9000 :cache_read_input_tokens 0
   :cache_creation_input_tokens 8000
   :cache_creation {:ephemeral_5m_input_tokens 8000 :ephemeral_1h_input_tokens 0}})

(def fallback
  {:input_tokens 12 :output_tokens 34 :cache_read_input_tokens 5 :cache_creation_input_tokens 100
   :iterations [{:type "message" :input_tokens 1000 :output_tokens 2000
                 :cache_read_input_tokens 0 :cache_creation_input_tokens 0}
                {:type "fallback_message" :model "claude-opus-4-8" :input_tokens 1000 :output_tokens 2000
                 :cache_read_input_tokens 0 :cache_creation_input_tokens 0}]})

(deftest lines-test
  (testing "one model: the same as the old single-model formula"
    (let [[l] (costs/lines plain "claude-opus-5-5")]
      (is (= "claude-opus-5-5" (:model l)))
      (is (close? 0.232 (:cost_usd l)))))
  (testing "1-hour cache writes cost 2x input"
    (let [[l] (costs/lines {:cache_creation_input_tokens 1000000
                            :cache_creation {:ephemeral_5m_input_tokens 0 :ephemeral_1h_input_tokens 1000000}}
                           "claude-opus-5-5")]
      (is (close? 8.0 (:cost_usd l)))))
  (testing "a fallback is priced per model, from usage.iterations"
    (let [ls (costs/lines fallback "claude-opus-4-8" "claude-opus-5-5")
          by (into {} (map (juxt :model identity) ls))]
      (is (= #{"claude-opus-5-5" "claude-opus-4-8"} (set (keys by))))
      (is (close? 0.044 (:cost_usd (by "claude-opus-5-5"))) "1k in x $4 + 2k out x $20")
      (is (close? 0.055 (:cost_usd (by "claude-opus-4-8"))) "1k in x $5 + 2k out x $25")
      (is (close? 0.099 (:cost_usd (costs/summarise ls))))))
  (testing "an unknown model has no price rather than a wrong one"
    (is (nil? (:cost_usd (costs/summarise (costs/lines plain "claude-new-9")))))))

(deftest log-and-overview-test
  (let [ds (temp-ds)]
    (testing "nothing is logged without a DB (tests, REPL)"
      (costs/use-db! nil)
      (costs/log! {:task :week_plan :model "claude-opus-5-5" :usage plain})
      (is (empty? (db/query ds ["SELECT * FROM api_calls"]))))
    (try
      (costs/use-db! ds)
      (costs/log! {:task :week_plan :ref "proposal 9" :model "claude-opus-5-5" :effort "high"
                   :usage plain :served_model "claude-opus-5-5" :stop_reason "end_turn" :duration_ms 1200})
      (costs/log! {:task :athlete_chat :model "claude-opus-5-5" :effort "low"
                   :usage fallback :served_model "claude-opus-4-8" :stop_reason "end_turn"})
      (costs/log! {:task :athlete_chat :model "claude-opus-5-5" :error "Connection refused"})
      (let [o (costs/overview ds nil)]
        (is (= 3 (get-in o [:totals :requests])))
        (is (= 1 (get-in o [:totals :errors])))
        (is (close? (+ 0.232 0.099) (get-in o [:all_time :cost_usd])))
        (is (= ["week_plan" "athlete_chat"] (map :key (:by_task o))) "most expensive first")
        (is (= {"claude-opus-5-5" 0.276 "claude-opus-4-8" 0.055}
               (into {} (for [m (:by_model o) :when (pos? (:cost_usd m))]
                          [(:key m) (/ (Math/round (* 1000 (:cost_usd m))) 1000.0)])))
            "a fallback request counts toward both models")
        (is (= "claude-opus-5-5, claude-opus-4-8" (:model_used (second (:requests o)))))
        (is (= "error" (:status (first (:requests o))))))
      (finally (costs/use-db! nil)))))

(deftest import-earlier-test
  (let [ds (temp-ds)
        old {:input_tokens 3000 :output_tokens 9000 :cache_read 0 :cache_write 8000 :stop_reason "end_turn"}]
    (db/query ds ["INSERT INTO plan_proposals (monday, status, model, kind, usage, created_at, updated_at)
                   VALUES ('2026-11-02', 'closed', 'claude-opus-5-5', 'week', ?, '2026-10-29T21:51:00Z', 'x')
                   RETURNING id" (json/write-str old)])
    (is (= 1 (costs/import-earlier! ds)))
    (is (zero? (costs/import-earlier! ds)) "only once")
    (let [[r] (db/query ds ["SELECT * FROM api_calls"])]
      (is (= ["week_plan" "imported"] [(:task r) (:source r)]))
      (is (close? 0.232 (:cost_usd r))))))

(deftest athlete-totals-test
  (let [ds    (temp-ds)
        today (str (java.time.LocalDate/now))
        add!  (fn [at cost in out cr]
                (jdbc/execute! ds ["INSERT INTO api_calls (at, task, model_requested, status, input_tokens,
                                          output_tokens, cache_read, cost_usd) VALUES (?,?,?,?,?,?,?,?)"
                                        at "week_plan" "claude-opus-5-5" "ok" in out cr cost]))]
    (is (= 0 (:requests (costs/athlete-totals ds nil))) "no requests: zeros, not nil")
    (add! "2020-01-01T10:00:00Z" 1.0 100 10 0)
    (add! (str today "T00:00:01Z") 0.5 200 20 1000)
    (let [all    (costs/athlete-totals ds nil)
          recent (costs/athlete-totals ds today)]
      (is (= 2 (:requests all)))
      (is (close? 1.5 (:cost_usd all)))
      (is (= 1300 (:input_tokens all)) "input counts cached tokens too, like the rest of the page")
      (is (= 30 (:output_tokens all)))
      (is (= 1 (:requests recent)))
      (is (close? 0.5 (:this_month all))))
    (is (nil? (costs/by-athlete ds nil)) "one athlete: no per-athlete table")))
