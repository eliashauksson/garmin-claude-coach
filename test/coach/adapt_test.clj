(ns coach.adapt-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.adapt :as adapt]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.planner :as planner]))

(def today "2027-01-14") ; a Thursday

(defn- run [id date mins & {:as m}]
  (merge {:id id :date date :sport "running" :moving_s (* 60 mins) :distance_m (* 160 mins)} m))

(defn- ids [sigs] (set (map :id sigs)))
(defn- by [sigs id] (first (filter #(= id (:id %)) sigs)))

(def calm {:today today :acts [] :wellness [] :workouts [] :tsb 0 :phase "Base"
           :settings {:steady_hr 150 :rhr_high 57}})

(deftest signals-test
  (is (= [] (adapt/signals calm)))
  (testing "one easy run too hard is noted, three trigger"
    (let [one   (adapt/signals (assoc calm :acts [(run 1 "2027-01-11" 45 :avg_hr 158)]))
          three (adapt/signals (assoc calm :acts (for [[i d] (map-indexed vector ["2027-01-11" "2027-01-12" "2027-01-13"])]
                                                   (run i d 45 :avg_hr 155))))]
      (is (= [:info false] ((juxt :severity :trigger?) (by one :easy-too-hard))))
      (is (= [:warn true] ((juxt :severity :trigger?) (by three :easy-too-hard))))
      (is (not= (:key (by one :easy-too-hard)) (:key (by three :easy-too-hard))))))
  (testing "long-run drift triggers only if a longer long run is planned"
    (let [base (assoc calm :acts [(run 7 "2027-01-09" 120 :decoupling 11.2 :distance_m 22000)])]
      (is (false? (:trigger? (by (adapt/signals base) :long-drift))))
      (is (true? (:trigger? (by (adapt/signals (assoc base :workouts [{:id 1 :date "2027-01-16" :session_id "LONG"
                                                                         :status "planned" :distance_km 24}]))
                                :long-drift))))))
  (testing "RPE: watch value or in-app; pain"
    (is (contains? (ids (adapt/signals (assoc calm :acts [(run 3 "2027-01-13" 40 :garmin_rpe 8.0)]))) :rpe-high))
    (is (contains? (ids (adapt/signals (assoc calm :acts [(run 3 "2027-01-13" 40 :rpe 9)]))) :rpe-high))
    (is (= :high (:severity (by (adapt/signals (assoc calm :acts [(run 3 "2027-01-13" 40 :pain 1 :pain_location "Achilles")]))
                                :pain)))))
  (testing "wellness two days in a row"
    (is (contains? (ids (adapt/signals (assoc calm :wellness [{:date "2027-01-13" :hrv_status "UNBALANCED"}
                                                              {:date "2027-01-14" :hrv_status "LOW"}])))
                   :hrv-unbalanced))
    (is (not (contains? (ids (adapt/signals (assoc calm :wellness [{:date "2027-01-13" :rhr 52} {:date "2027-01-14" :rhr 58}])))
                        :rhr-high))))
  (testing "missed long run and missed volume"
    (let [ws [{:id 1 :date "2027-01-11" :session_id "EASY" :status "missed" :duration_min 45}
              {:id 2 :date "2027-01-13" :session_id "THR" :status "missed" :duration_min 60}
              {:id 3 :date "2027-01-16" :session_id "LONG" :status "planned" :duration_min 120}]
          s  (adapt/signals (assoc calm :workouts ws))]
      (is (= ["missed:2"] (map :key (filter #(= :missed-key (:id %)) s))))
      (is (contains? (ids s) :missed-volume))))
  (testing "fitter: easy-run efficiency up, never triggers a proposal"
    (let [s (adapt/signals (assoc calm :acts (concat (for [i (range 3)] (run i (str "2026-12-0" (+ 3 i)) 40 :ef 1.20))
                                                     (for [i (range 3)] (run (+ 10 i) (str "2027-01-0" (+ 5 i)) 40 :ef 1.30)))))]
      (is (= [:info false] ((juxt :severity :trigger?) (by s :fitter)))))))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-adapt" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(def base-w {:slot "AM" :title "t" :duration_min 40 :distance_km 8 :intensity "E" :description "d" :reason "r"
             :goal "g" :blocks []})

(deftest adjustment-flow-test
  (let [ds  (temp-ds)
        w1  (plan/add-workout! ds (assoc base-w :date "2027-01-15" :session_id "THR"))
        w2  (plan/add-workout! ds (assoc base-w :date "2027-01-16" :session_id "LONG" :distance_km 24))
        pid (plan/create-proposal! ds "2027-01-11" "test" {:kind "adjust" :scope-end "2027-01-24" :signals [{:id "x"}]})]
    (plan/finish-proposal! ds pid {:context {} :warnings [] :usage {}
                                   :response {:summary "s" :warnings []
                                              :changes [(assoc base-w :op "change" :workout_id w1 :date "2027-01-15" :session_id "EASY")
                                                        (assoc base-w :op "remove" :workout_id w2 :date "2027-01-16" :session_id "LONG")
                                                        (assoc base-w :op "add" :workout_id 0 :date "2027-01-17" :session_id "REC")]}})
    (is (= ["change" "remove" "add"] (map :op (:items (plan/proposal ds pid)))))
    (is (= "THR" (get-in (plan/proposal ds pid) [:items 0 :current :session_id])) "shows what it changes")
    (doseq [i [0 1 2]] (plan/decide-item! ds pid i "accepted" nil))
    (let [ws (plan/workouts ds "2027-01-11" "2027-01-24")]
      (is (= [["2027-01-15" "EASY"] ["2027-01-17" "REC"]] (map (juxt :date :session_id) ws)) "LONG cancelled"))
    (testing "rejecting accepted items undoes them"
      (doseq [i [0 1 2]] (plan/decide-item! ds pid i "rejected" nil))
      (is (= [["2027-01-15" "THR"] ["2027-01-16" "LONG"]]
             (map (juxt :date :session_id) (plan/workouts ds "2027-01-11" "2027-01-24")))))))

(deftest accept-all-adjustment-test
  (let [ds  (temp-ds)
        w1  (plan/add-workout! ds (assoc base-w :date "2027-01-15" :session_id "THR"))
        w2  (plan/add-workout! ds (assoc base-w :date "2027-01-16" :session_id "LONG" :distance_km 24))
        _   (plan/add-workout! ds (assoc base-w :date "2027-01-12" :session_id "EASY"))
        pid (plan/create-proposal! ds "2027-01-11" "test" {:kind "adjust" :scope-end "2027-01-24" :signals [{:id "x"}]})]
    (plan/finish-proposal! ds pid {:context {} :warnings [] :usage {}
                                   :response {:summary "s" :warnings []
                                              :changes [(assoc base-w :op "change" :workout_id w1 :date "2027-01-15" :session_id "EASY")
                                                        (assoc base-w :op "remove" :workout_id w2 :date "2027-01-16" :session_id "LONG")
                                                        (assoc base-w :op "add" :workout_id 0 :date "2027-01-17" :session_id "REC")]}})
    (plan/accept-all! ds pid true)
    (is (= [["2027-01-12" "EASY"] ["2027-01-15" "EASY"] ["2027-01-17" "REC"]]
           (map (juxt :date :session_id) (plan/workouts ds "2027-01-11" "2027-01-24")))
        "every change applied; replace is ignored, so untouched sessions stay")
    (is (every? #{"accepted"} (map :decision (:items (plan/proposal ds pid)))))))

(deftest missed-and-done-test
  (let [past [{:id 1 :date "2020-01-01" :slot "AM" :session_id "EASY" :status "planned"}
              {:id 2 :date "2020-01-01" :slot "PM" :session_id "GYM_UPPER" :status "done"}
              {:id 3 :date "2020-01-02" :slot "AM" :session_id "EASY" :status "planned"}]
        res  (plan/with-done past [{:id 9 :date "2020-01-02" :sport "running"}] "2020-01-05")]
    (is (= ["missed" "done" "done"] (map :status res)))))

(deftest validate-adjustment-test
  (let [in-scope [{:id 1 :date "2027-01-15" :slot "AM" :session_id "THR" :status "planned" :distance_km 10}
                  {:id 2 :date "2027-01-13" :slot "AM" :session_id "EASY" :status "done" :distance_km 8}]
        ch       (fn [op id date s] (assoc base-w :op op :workout_id id :date date :session_id s))
        {:keys [response]} (planner/validate-adjustment
                            today "2027-01-24"
                            {:summary "s" :warnings []
                             :changes [(ch "change" 1 "2027-01-15" "EASY")   ; ok
                                       (ch "change" 2 "2027-01-13" "REC")    ; done session: dropped
                                       (ch "remove" 99 "2027-01-15" "EASY")  ; unknown id: dropped
                                       (ch "add" 0 "2027-01-12" "EASY")      ; before today: dropped
                                       (ch "add" 0 "2027-01-18" "EASY")]}    ; ok
                            in-scope (constantly {}))]
    (is (= [["change" 1] ["add" 0]] (map (juxt :op :workout_id) (:changes response))))))
