(ns coach.ai-settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.ai-settings :as ai]
            [coach.db :as db]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-ai" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(deftest settings-test
  (let [ds (temp-ds)]
    (testing "the recommended defaults"
      (is (= {:model "claude-opus-5-5" :effort "high" :delivery "auto"} (get (ai/all ds) "season_plan")))
      (is (= {:model "claude-opus-5-5" :effort "medium" :delivery "auto"} (get (ai/all ds) "week_plan")))
      (is (= {:model "claude-sonnet-5-5" :effort "low"} (get (ai/all ds) "athlete_chat")))
      (is (= {:model "claude-haiku-5-5" :effort "low"} (get (ai/all ds) "athlete_summary"))))
    (testing "a choice is stored per task"
      (ai/save! ds "athlete_chat" {:model "claude-haiku-5-5" :effort "medium"})
      (is (= {:model "claude-haiku-5-5" :effort "medium"} (get (ai/all ds) "athlete_chat")))
      (is (= {:model "claude-opus-5-5" :effort "medium" :delivery "auto"} (get (ai/all ds) "week_plan")) "others unchanged"))
    (testing "bad input is refused"
      (is (thrown? clojure.lang.ExceptionInfo (ai/save! ds "nope" {:model "claude-opus-5-5" :effort "low"})))
      (is (thrown? clojure.lang.ExceptionInfo (ai/save! ds "week_plan" {:model "gpt-9" :effort "low"})))
      (is (thrown? clojure.lang.ExceptionInfo (ai/save! ds "week_plan" {:model "claude-opus-5-5" :effort "huge"}))))
    (testing "for-task reads the dashboard's DB; without one, the defaults"
      (try
        (ai/use-db! ds)
        (is (= "claude-haiku-5-5" (:model (ai/for-task :athlete_chat))))
        (finally (ai/use-db! nil)))
      (is (= "claude-sonnet-5-5" (:model (ai/for-task :athlete_chat)))))
    (testing "reset"
      (ai/reset-all! ds)
      (is (= ai/defaults (ai/all ds))))
    (testing "Claude Haiku 5.5 has no server-side fallback"
      (is (not (ai/fallback? "claude-haiku-5-5")))
      (is (ai/fallback? "claude-opus-5-5")))))

(deftest delivery-test
  (let [ds (temp-ds)]
    (try
      (ai/use-db! ds)
      (testing "default: batch when the app starts the request, instant when the athlete clicks"
        (is (ai/batch? :week_plan :auto))
        (is (not (ai/batch? :week_plan :manual)))
        (is (not (ai/batch? :athlete_chat :auto)) "chat is never batched"))
      (testing "always batch / always instant"
        (ai/save! ds "adjust" {:model "claude-opus-5-5" :effort "high" :delivery "batch"})
        (is (ai/batch? :adjust :manual))
        (ai/save! ds "adjust" {:model "claude-opus-5-5" :effort "high" :delivery "instant"})
        (is (not (ai/batch? :adjust :auto))))
      (testing "chat can't be set to batch"
        (is (thrown? clojure.lang.ExceptionInfo
                     (ai/save! ds "athlete_chat" {:model "claude-sonnet-5-5" :effort "low" :delivery "batch"}))))
      (finally (ai/use-db! nil)))))

(deftest translation-scope-test
  (let [ds (temp-ds)]
    (is (= "all" (get-in (ai/all ds) ["translation" :scope])) "default: every language")
    (ai/save! ds "translation" {:model "claude-sonnet-5-5" :effort "low" :scope "selected"})
    (is (= "selected" (get-in (ai/all ds) ["translation" :scope])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ai/save! ds "week_plan" {:model "claude-opus-5-5" :effort "medium" :scope "all"}))
        "only the Translations task has a scope")))
