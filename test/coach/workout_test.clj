(ns coach.workout-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.claude :as claude]
            [coach.planner :as planner]
            [coach.translate :as translate]
            [coach.workout :as w]))

(def threshold-blocks
  [{:label "Warm-up" :repeat 1
    :steps [{:kind "warmup" :duration_s 900 :target "hr" :from "125" :to "140" :note ""}]}
   {:label "3 × threshold" :repeat 3
    :steps [{:kind "work" :duration_s 600 :target "pace" :from "4:30" :to "4:20" :note "relaxed shoulders"}
            {:kind "recovery" :duration_s 120 :target "open" :from "" :to "" :note "easy jog"}]}
   {:label "Cool-down" :repeat 1
    :steps [{:kind "cooldown" :duration_s 600 :target "hr" :from "140" :to "140" :note ""}]}])

(deftest values-test
  (is (= 260.0 (w/parse-value "pace" "4:20")))
  (is (= 135.0 (w/parse-value "hr" "135")))
  (is (nil? (w/parse-value "hr" "")))
  (is (= "4:05" (w/pace-str 245)))
  (testing "ranges in either order; one value gets a small band"
    (is (= [260.0 270.0] (w/bounds {:target "pace" :from "4:30" :to "4:20"})))
    (is (= [138.0 142.0] (w/bounds {:target "hr" :from "140" :to "140"})))
    (is (= [195.0 205.0] (w/bounds {:target "power" :from "200" :to ""})))
    (is (nil? (w/bounds {:target "open" :from "" :to ""})))))

(deftest expand-test
  (let [segs (w/expand threshold-blocks)]
    (is (= (+ 900 (* 3 720) 600) (w/total-s threshold-blocks)))
    (is (= 8 (count segs)))
    (is (= [[0 900] [900 1500] [1500 1620]] (map (juxt :start :end) (take 3 segs))))
    (is (= [1 2 3] (map :rep (filter #(= "work" (:kind %)) segs))))
    (is (= (w/total-s threshold-blocks) (:end (last segs))))
    (is (= ["hr" "pace"] (w/targets threshold-blocks)))))

(deftest compare-test
  (let [segs   (w/expand [{:label "x" :repeat 1
                           :steps [{:kind "steady" :duration_s 10 :target "hr" :from "140" :to "150" :note ""}
                                   {:kind "work" :duration_s 10 :target "pace" :from "4:00" :to "4:10" :note ""}
                                   {:kind "work" :duration_s 10 :target "power" :from "250" :to "260" :note ""}
                                   {:kind "recovery" :duration_s 10 :target "open" :from "" :to "" :note ""}
                                   {:kind "cooldown" :duration_s 10 :target "hr" :from "120" :to "130" :note ""}]}])
        stream {:t     (vec (range 40))
                :hr    (vec (concat (repeat 10 145) (repeat 30 nil)))
                :speed (vec (concat (repeat 10 nil) (repeat 10 5.0) (repeat 20 3.0)))   ; 3:20 /km: too fast
                :power (vec (concat (repeat 20 nil) (repeat 10 300) (repeat 10 nil)))}
        res    (w/compare-segments segs stream)]
    (is (= [:in :low :high nil nil] (map :verdict res)))
    (is (= 145 (:actual (first res))))
    (is (= 200.0 (:actual (second res))))
    (testing "a step past the activity's end has no actual"
      (is (nil? (:actual (last res)))))))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-workout" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(def base {:slot "AM" :title "Threshold" :duration_min 50 :distance_km 10 :intensity "Z4"
           :description "15' wu, 3×10' T, 10' cd" :reason "r"})

(deftest storage-test
  (let [ds  (temp-ds)
        pid (plan/create-proposal! ds "2027-01-11" "test")]
    (plan/finish-proposal! ds pid {:context {} :warnings [] :usage {}
                                   :response {:summary "s" :warnings []
                                              :workouts [(assoc base :date "2027-01-13" :session_id "THR"
                                                                :goal "Hold threshold" :blocks threshold-blocks)]}})
    (is (= threshold-blocks (get-in (plan/proposal ds pid) [:items 0 :blocks])))
    (plan/decide-item! ds pid 0 "accepted" nil)
    (let [[wk] (plan/workouts ds "2027-01-11" "2027-01-17")]
      (is (= "Hold threshold" (:goal wk)))
      (is (= threshold-blocks (:blocks wk)))
      (is (= wk (dissoc (plan/workout ds (:id wk)) :activity_id)))
      (testing "the backfill doesn't overwrite blocks"
        (is (false? (plan/set-structure! ds (:id wk) "other" [])))))
    (testing "the activity that fulfilled it"
      (db/upsert-activities! ds [{:id 77 :date "2027-01-13" :sport "running" :name "Run" :raw "{}" :fetched_at "x"}])
      (let [p (plan/planned-for-activity ds {:id 77 :date "2027-01-13"})]
        (is (= "Hold threshold" (:goal p)))
        (is (= 77 (:activity_id (plan/workout ds (:id p))))))
      (is (nil? (plan/planned-for-activity ds {:id 78 :date "2027-01-13"}))))
    (testing "goal, block labels and notes are translated"
      (let [texts (set (translate/proposal-texts ds pid))]
        (is (texts "Hold threshold"))
        (is (texts "3 × threshold"))
        (is (texts "relaxed shoulders"))))))

(deftest block-time-test
  (is (= 61 (:duration_min (planner/with-block-time {:duration_min 45 :blocks threshold-blocks}))))
  (is (= 45 (:duration_min (planner/with-block-time {:duration_min 45 :blocks []})))))

(deftest structure-test
  (let [ds    (temp-ds)
        old   (plan/add-workout! ds (assoc base :date "2099-01-13" :session_id "THR"))
        done  (plan/add-workout! ds (assoc base :date "2099-01-14" :session_id "EASY" :goal "g" :blocks []))
        sent  (atom nil)]
    (with-redefs [claude/principles     (constantly "K")
                  claude/translate-soon! (fn [& _])
                  claude/request        (fn [opts]
                                           (reset! sent opts)
                                           {:response {:workouts [{:workout_id old :goal "Hold threshold" :blocks threshold-blocks}
                                                                  {:workout_id done :goal "x" :blocks []}
                                                                  {:workout_id 999 :goal "x" :blocks []}]}})]
      (is (= [old] (map :id (planner/unstructured ds "2099-01-01"))))
      (is (= 1 (planner/structure! ds "2099-01-01")))
      (is (= :structure (:task @sent)))
      (is (= threshold-blocks (:blocks (plan/workout ds old))))
      (is (= "g" (:goal (plan/workout ds done))) "a structured session is left alone")
      (testing "nothing left: no request"
        (reset! sent nil)
        (is (= 0 (planner/structure! ds "2099-01-01")))
        (is (nil? @sent))))))
