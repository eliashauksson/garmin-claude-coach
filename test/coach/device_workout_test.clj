(ns coach.device-workout-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.device-workout :as dw]))

(def blocks
  [{:label "Warm-up" :repeat 1
    :steps [{:kind "warmup" :duration_s 900 :target "hr" :from "125" :to "140"}]}
   {:label "3 × threshold" :repeat 3
    :steps [{:kind "work" :duration_s 600 :target "pace" :from "4:30" :to "4:20" :note "relaxed"}
            {:kind "recovery" :duration_s 120 :target "open"}]}
   {:label "Cool-down" :repeat 1
    :steps [{:kind "cooldown" :target "hr" :from "140" :to "140"}]}])

(def run {:title "Threshold" :goal "Hold the pace" :kind :run :blocks blocks})

(defn- steps [w] (get-in w [:workoutSegments 0 :workoutSteps]))

(deftest garmin-test
  (let [w  (dw/garmin-workout run)
        [wu group cd] (steps w)]
    (is (= {:sportTypeId 1 :sportTypeKey "running"} (:sportType w)))
    (is (= "Hold the pace" (:description w)))
    (testing "step numbers run depth-first through the repeat group"
      (is (= [1 2 3 4 5] [(:stepOrder wu) (:stepOrder group)
                          (-> group :workoutSteps first :stepOrder) (-> group :workoutSteps second :stepOrder)
                          (:stepOrder cd)])))
    (testing "repeat group"
      (is (= "RepeatGroupDTO" (:type group)))
      (is (= 3 (:numberOfIterations group)))
      (is (= {:conditionTypeId 7 :conditionTypeKey "iterations"} (:endCondition group))))
    (testing "heart rate is a custom range in bpm"
      (is (= {:workoutTargetTypeId 4 :workoutTargetTypeKey "heart.rate.zone"} (:targetType wu)))
      (is (= [125.0 140.0] ((juxt :targetValueOne :targetValueTwo) wu)))
      (is (not (contains? wu :zoneNumber))))
    (testing "pace is m/s, the faster end is the higher speed"
      (let [work (-> group :workoutSteps first)]
        (is (= "pace.zone" (-> work :targetType :workoutTargetTypeKey)))
        (is (< (:targetValueOne work) (:targetValueTwo work)))
        (is (< 3.7 (:targetValueOne work) 3.8)) ; 4:30/km
        (is (< 3.8 (:targetValueTwo work) 3.9)) ; 4:20/km
        (is (= "relaxed" (:description work)))))
    (testing "no duration: the lap button ends the step; one value gets a band"
      (is (= "lap.button" (-> cd :endCondition :conditionTypeKey)))
      (is (not (contains? cd :endConditionValue)))
      (is (= [138.0 142.0] ((juxt :targetValueOne :targetValueTwo) cd))))
    (testing "a recovery with no target"
      (is (= "no.target" (-> group :workoutSteps second :targetType :workoutTargetTypeKey)))
      (is (= 4 (-> group :workoutSteps second :stepType :stepTypeId))))))

(deftest garmin-ride-test
  (let [w (dw/garmin-workout {:title "Sweet spot" :kind :ride
                              :blocks [{:repeat 1
                                        :steps [{:kind "work" :duration_s 600 :target "power" :from "250" :to "270"}
                                                {:kind "work" :duration_s 600 :target "pace"
                                                 :from "4:00" :to "4:00"}]}]})
        [pw pace] (steps w)]
    (is (= 2 (-> w :sportType :sportTypeId)))
    (is (= [{:workoutTargetTypeId 6 :workoutTargetTypeKey "power.between"} 250.0 270.0]
           [(:targetType pw) (:targetValueOne pw) (:targetValueTwo pw)]))
    (is (= "no.target" (-> pace :targetType :workoutTargetTypeKey)) "a ride has no pace target")))

(deftest garmin-without-blocks-test
  (is (= [{:conditionTypeId 2 :conditionTypeKey "time"} 2700.0]
         ((juxt :endCondition :endConditionValue)
          (first (steps (dw/garmin-workout {:title "Easy" :kind :run :duration_min 45}))))))
  (is (= [{:conditionTypeId 3 :conditionTypeKey "distance"} 10000.0]
         ((juxt :endCondition :endConditionValue)
          (first (steps (dw/garmin-workout {:title "Easy" :kind :run :distance_km 10}))))))
  (is (= "lap.button"
         (-> (steps (dw/garmin-workout {:title "Easy" :kind :run})) first :endCondition :conditionTypeKey))))

(deftest coros-test
  (let [c (dw/coros-course run "Threshold (Z4)")
        [wu group cd] (:sections c)]
    (is (= 1 (:sportType c)))
    (is (= "Hold the pace" (:courseDescription c)))
    (is (= {:sectionType 1 :targetType 2 :targetValue 900 :intensityType 1
            :intensityValueStart 125 :intensityValueEnd 140} wu))
    (testing "an interval group; warm-up and cool-down inside would be training"
      (is (= {:intervalGroup true :repeats 3} (select-keys group [:intervalGroup :repeats])))
      (is (= [2 3] (map :sectionType (:sets group))))
      (is (= {:intensityType 2 :intensityValueStart 260 :intensityValueEnd 270}
             (select-keys (first (:sets group)) [:intensityType :intensityValueStart :intensityValueEnd])))
      (is (not (contains? (second (:sets group)) :intensityType))))
    (testing "no duration: free mode, and its intensity stays"
      (is (= {:sectionType 4 :targetType 4 :intensityType 1 :intensityValueStart 138 :intensityValueEnd 142} cd)))))

(deftest coros-edge-cases-test
  (testing "more than 20 repeats are split into groups"
    (let [c (dw/coros-course {:title "Reps" :kind :run
                              :blocks [{:repeat 45 :steps [{:kind "work" :duration_s 30}]}]} "x")]
      (is (= [20 20 5] (map :repeats (:sections c))))))
  (testing "a repeated warm-up step becomes a training section inside the group"
    (let [c (dw/coros-course {:title "x" :kind :run
                              :blocks [{:repeat 2 :steps [{:kind "warmup" :duration_s 60}]}]} "x")]
      (is (= [2] (map :sectionType (-> c :sections first :sets))))))
  (testing "pace on a ride is dropped"
    (let [c (dw/coros-course {:title "x" :kind :ride
                              :blocks [{:repeat 1
                                        :steps [{:kind "work" :duration_s 60 :target "pace" :from "4:00"}]}]} "x")]
      (is (not (contains? (-> c :sections first) :intensityType)))))
  (testing "a hike is trail running in free mode, its minutes in the description"
    (let [c (dw/coros-course {:title "Hike" :kind :hike :duration_min 90} "Hike / time on feet")]
      (is (= 5 (:sportType c)))
      (is (= [4] (map :targetType (:sections c))))
      (is (= "Hike / time on feet (90 min)" (:courseDescription c)))))
  (testing "strength can't go to Coros"
    (is (nil? (dw/coros-course {:title "Gym" :kind :strength :duration_min 50} "Gym"))))
  (testing "name is cut to 100 characters, description is never empty"
    (let [c (dw/coros-course {:title (apply str (repeat 150 "a")) :kind :run :duration_min 30} "")]
      (is (= 100 (count (:courseName c))))
      (is (seq (:courseDescription c)))))
  (is (= "20261125" (dw/coros-date "2026-11-25"))))

(deftest sendable-test
  (let [w {:status "planned" :date "2026-11-25" :kind :run}]
    (is (nil? (dw/sendable w :garmin "2026-11-22")))
    (is (nil? (dw/sendable w :coros "2026-11-22")))
    (is (nil? (dw/sendable w :garmin "2026-11-25")) "today counts")
    (is (= :past (dw/sendable w :garmin "2026-11-26")))
    (is (= :not-planned (dw/sendable (assoc w :status "done") :garmin "2026-11-22")))
    (is (= :unsupported (dw/sendable (assoc w :kind nil) :garmin "2026-11-22")))
    (is (= :unsupported (dw/sendable (assoc w :kind :strength) :coros "2026-11-22")))
    (is (nil? (dw/sendable (assoc w :kind :strength) :garmin "2026-11-22")))
    (testing "Coros takes 90 days ahead, Garmin any"
      (is (= :too-far (dw/sendable (assoc w :date "2027-03-01") :coros "2026-11-22")))
      (is (nil? (dw/sendable (assoc w :date "2027-03-01") :garmin "2026-11-22"))))))
