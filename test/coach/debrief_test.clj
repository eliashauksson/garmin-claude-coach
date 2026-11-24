(ns coach.debrief-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.ai-settings :as ai]
            [coach.athlete :as athlete]
            [coach.claude :as claude]
            [coach.db :as db]
            [coach.debrief :as debrief]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-debrief" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(def today "2027-01-14")

(defn- act [id date & {:as m}]
  (merge {:id id :date date :sport "running" :moving_s 2700 :start_time_local (str date "T07:00:00")} m))

(deftest due-test
  (let [acts [(act 1 "2027-01-14")                      ; today, not rated: no waiting for the rating
              (act 3 "2027-01-13")                      ; yesterday
              (act 4 "2027-01-13" :moving_s 120)        ; too short
              (act 5 "2027-01-12" :feedback_at "x")     ; already has a row
              (act 6 "2027-01-10" :feedback_at "x")]    ; out of the window
        due  (debrief/due-from acts #{5} today)]
    (is (= [3 1] (map :id due)) "oldest first")
    (testing "a note exists for a failed one too: no retry on every sync"
      (is (empty? (debrief/due-from [(act 7 "2027-01-13")] #{7} today))))))

(deftest steps-vs-actual-test
  (let [blocks [{:label "Warm-up" :repeat 1 :steps [{:kind "work" :duration_s 60 :target "hr" :from 120 :to 140}]}
                {:label "Main" :repeat 2 :steps [{:kind "work" :duration_s 60 :target "hr" :from 150 :to 160}]}]
        stream {:t [0 30 60 90 120 150 180] :hr [130 130 170 170 155 155 100]}
        out    (debrief/steps-vs-actual blocks stream)]
    (is (= ["in" "high" "in"] (map :verdict out)))
    (is (= "1/2" (:repeat (second out))))
    (is (nil? (:repeat (first out))))
    (is (nil? (debrief/steps-vs-actual blocks {:t []})) "no stream, no comparison")
    (is (nil? (debrief/steps-vs-actual nil stream)))))

(def a (act 11 "2027-01-13" :name "Easy run" :distance_m 8000 :avg_hr 140 :tss 40))

(defn- with-claude [f]
  (let [seen (atom nil)]
    (with-redefs [claude/request (fn [opts] (reset! seen opts)
                                   {:response {:text "Easy and controlled." :takeaway "Good easy day"}
                                    :usage {:model "claude-sonnet-5-5"}})
                  claude/translate-soon! (fn [& _])
                  athlete/content (fn [] "# Athlete")]
      (f seen))))

(deftest write-test
  (let [ds (temp-ds)]
    (with-claude
      (fn [seen]
        (let [r (debrief/write! ds a)]
          (is (= "done" (:status r)))
          (is (= "Easy and controlled." (:text r)))
          (is (= "Good easy day" (:takeaway r)))
          (is (= :debrief (:task @seen)))
          (is (= "activity 11" (:ref @seen)))
          (is (re-find #"Easy run" (second (first (:messages @seen)))) "the activity is in the message"))))
    (testing "a failed request is kept on the row, not thrown"
      (with-redefs [claude/request (fn [_] (throw (ex-info "out of credits" {})))
                    athlete/content (fn [] nil)]
        (let [r (debrief/write! ds (act 12 "2027-01-13"))]
          (is (= "failed" (:status r)))
          (is (= "out of credits" (:error r))))))
    (testing "what the API shows, and the newest note for Today"
      (is (= {:status "done" :text "Easy and controlled." :takeaway "Good easy day" :error nil}
             (debrief/shown ds 11)))
      (is (nil? (debrief/shown ds 99))))))

(deftest batch-handler-test
  (let [ds (temp-ds)
        {:keys [done fail]} (get @claude/batch-handlers "debrief")]
    (with-redefs [claude/translate-soon! (fn [& _])]
      (done ds {:id 5} {:text "Solid." :takeaway "Solid"} {:model "claude-sonnet-5-5"})
      (is (= "done" (:status (debrief/row ds 5))))
      (is (= "claude-sonnet-5-5" (:model (debrief/row ds 5))))
      (fail ds {:id 6} "expired")
      (is (= ["failed" "expired"] ((juxt :status :error) (debrief/row ds 6)))))))

(deftest settings-test
  (is (= "claude-sonnet-5-5" (:model (ai/for-task :debrief))))
  (is (ai/batch? :debrief :auto) "after a sync it goes through the half-price batch")
  (is (not (ai/batch? :debrief :manual)) "a click is instant")
  (is (not (ai/batch? :coach_chat :auto)) "the chat is never batched"))
