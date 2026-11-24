(ns coach.coach-chat-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.claude :as claude]
            [coach.coach-chat :as chat]
            [coach.planner :as planner]))

(deftest request-messages-test
  (testing "the data goes in front of the first message only"
    (is (= [[:user "My data (today is 2027-01-14):\n{\"a\":1}\n\nMy message: how am I?"]
            [:assistant "Fine."]
            [:user "and tomorrow?"]]
           (chat/request-messages [{:role "user" :text "how am I?"}
                                   {:role "assistant" :text "Fine."}
                                   {:role "user" :text "and tomorrow?"}]
                                  "{\"a\":1}" "2027-01-14"))))
  (testing "a conversation starts with the athlete, blank messages are dropped"
    (is (= :user (ffirst (chat/request-messages [{:role "assistant" :text "Hello"}
                                                 {:role "user" :text "  "}
                                                 {:role "user" :text "hi"}]
                                                "{}" "2027-01-14"))))
    (is (nil? (chat/request-messages [{:role "assistant" :text "Hello"}] "{}" "2027-01-14"))))
  (testing "only the last messages are sent, long ones cut"
    (let [msgs (for [i (range 30)] {:role (if (even? i) "user" "assistant") :text (str "m" i)})
          out  (chat/request-messages msgs "{}" "2027-01-14")]
      (is (<= (count out) chat/max-messages))
      (is (= :user (ffirst out))))
    (is (= chat/max-chars (count (re-find #"x+" (second (first (chat/request-messages
                                                                 [{:role "user" :text (apply str (repeat 9000 "x"))}]
                                                                 "{}" "2027-01-14")))))))))

(defn- ask [data messages response]
  (let [seen (atom nil)]
    (with-redefs [chat/data (fn [_] data)
                  claude/principles (fn [] "PRINCIPLES")
                  claude/request (fn [opts] (reset! seen opts) {:response response :usage {:cost_chf 0.01}})]
      [(chat/chat nil messages) @seen])))

(deftest chat-test
  (testing "the request: task, cache, data in the first turn; no plan change offered"
    (let [[r seen] (ask {:load_today {:tsb -12}} [{:role "user" :text "Why am I tired?"}]
                        {:reply "TSB is -12." :adjust_note " "})]
      (is (= "TSB is -12." (:reply r)))
      (is (nil? (:adjust_note r)) "blank means no change offered")
      (is (= :coach_chat (:task seen)))
      (is (true? (:cache? seen)))
      (is (= "PRINCIPLES" (:knowledge seen)))
      (is (re-find #"\"tsb\":-12" (second (first (:messages seen)))))
      (is (= chat/system-intro (:system seen)) "no cycle rules without cycle data")))
  (testing "an adjustment note is passed on, for the athlete to start"
    (let [[r _] (ask {} [{:role "user" :text "Move Thursday"}]
                     {:reply "I can propose it." :adjust_note "Move Thursday's intervals to Friday."})]
      (is (= "Move Thursday's intervals to Friday." (:adjust_note r)))))
  (testing "cycle rules only when the data has a cycle block"
    (with-redefs [planner/cycle-system (fn [] "\nCYCLE RULES")]
      (let [[_ seen] (ask {:menstrual_cycle {:phase "luteal"}} [{:role "user" :text "hi"}]
                          {:reply "Hi" :adjust_note ""})]
        (is (re-find #"CYCLE RULES$" (:system seen))))))
  (testing "nothing to answer"
    (is (thrown? clojure.lang.ExceptionInfo (ask {} [{:role "assistant" :text "x"}] {})))))
