(ns coach.push-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.api.jobs :as jobs]
            [coach.auth :as auth]
            [coach.coros-mcp :as coros]
            [coach.db :as db]
            [coach.garmin-mcp :as garmin]
            [coach.plan :as plan]
            [coach.push :as push]
            [coach.season :as season]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-push" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- add! [ds date session]
  (plan/add-workout! ds {:date date :slot "AM" :session_id session :title "Easy" :duration_min 45
                         :distance_km 8 :intensity "Z2" :description "easy" :reason "r"}))

(defn- soon [n] (season/add-days (season/today) n))

(deftest garmin-resend-test
  (let [ds (temp-ds) id (add! ds (soon 3) "EASY") calls (atom []) ids (atom 100)]
    (with-redefs [auth/connected?       (constantly true)
                  garmin/start-writer!  (constantly :client)
                  garmin/stop!          (constantly nil)
                  garmin/call-write     (fn [_ tool args]
                                          (swap! calls conj [tool args])
                                          (if (= "upload_workout" tool)
                                            {:status "success" :workout_id (swap! ids inc)}
                                            {:status "success"}))]
      (let [w (push/garmin! ds id)]
        (is (= ["upload_workout" "schedule_workout"] (map first @calls)))
        (is (= {"workout_id" 101 "calendar_date" (soon 3)} (second (second @calls))))
        (is (= 101 (:garmin_workout_id w)))
        (is (nil? (:reason (:garmin (push/info w))))))
      (reset! calls [])
      (testing "sending again deletes the earlier library workout first"
        (let [w (push/garmin! ds id)]
          (is (= ["delete_workout" "upload_workout" "schedule_workout"] (map first @calls)))
          (is (= {"workout_id" 101 "confirm" true} (second (first @calls))))
          (is (= 102 (:garmin_workout_id w))))))))

(deftest garmin-refused-during-sync-test
  (let [ds (temp-ds) id (add! ds (soon 3) "EASY")]
    (with-redefs [auth/connected? (constantly true)
                  garmin/start-writer! (fn [] (throw (ex-info "must not start" {})))]
      (swap! jobs/sync-state assoc :running true)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sync is running" (push/garmin! ds id)))
        (finally (swap! jobs/sync-state assoc :running false))))))

(deftest coros-test
  (let [ds (temp-ds) id (add! ds (soon 3) "RIDE") calls (atom [])]
    (with-redefs [coros/connect!    (constantly :client)
                  coros/stop!       (constantly nil)
                  coros/call-write  (fn [_ tool args]
                                      (swap! calls conj [tool args])
                                      {:idInPlan (str "9" (count @calls))})]
      (let [w (push/coros! ds id)]
        (is (= ["createScheduledWorkout"] (map first @calls)))
        (is (= "91" (:coros_id_in_plan w)))
        (is (nil? (:old_left w))))
      (testing "the same day: the scheduled workout is updated, the id it returns is kept"
        (let [w (push/coros! ds id)]
          (is (= "updateScheduledWorkout" (first (second @calls))))
          (is (= "91" (get-in (second @calls) [1 "idInPlan"])))
          (is (= "92" (:coros_id_in_plan w)))))
      (testing "another day: a new one is made, and the old one is reported as left"
        (plan/update-workout! ds id {:date (soon 4)})
        (let [w (push/coros! ds id)]
          (is (= "createScheduledWorkout" (first (nth @calls 2))))
          (is (= (soon 3) (:old_left w)))
          (is (= (soon 4) (:coros_date w))))))))

(deftest coros-refuses-test
  (let [ds (temp-ds)]
    (with-redefs [coros/connect! (fn [] (throw (ex-info "must not connect" {})))]
      (testing "strength can't go to Coros"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"can't receive"
                              (push/coros! ds (add! ds (soon 3) "GYM_LOWER")))))
      (testing "past days and days beyond 90 can't be sent"
        (is (thrown? clojure.lang.ExceptionInfo (push/coros! ds (add! ds (soon -1) "EASY"))))
        (is (thrown? clojure.lang.ExceptionInfo (push/coros! ds (add! ds (soon 120) "EASY"))))))))

(deftest changed-since-sent-test
  (let [ds (temp-ds) id (add! ds (soon 3) "EASY")]
    (plan/mark-sent! ds id {:garmin_workout_id 5 :garmin_sent_at "2999-01-01T00:00:00Z"})
    (is (false? (:changed (:garmin (push/info (plan/workout ds id))))))
    (plan/mark-sent! ds id {:garmin_sent_at "2000-01-01T00:00:00Z"})
    (is (true? (:changed (:garmin (push/info (plan/workout ds id))))))))
