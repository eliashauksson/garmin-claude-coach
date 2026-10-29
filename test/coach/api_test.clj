(ns coach.api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [coach.api :as api]
            [coach.auth :as auth]
            [coach.db :as db]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-api" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- get-json [app uri & [qs]]
  (let [r (app {:request-method :get :uri uri :query-string qs})]
    [(:status r) (json/read-str (:body r) :key-fn keyword)]))

(deftest api-test
  (let [ds  (temp-ds)
        app (api/app ds)]
    (auth/record-login! ds "2026-10-29")
    (db/upsert-activities! ds [{:id 1 :date "2026-10-13" :start_time_local "2026-10-13T05:01:57"
                                :sport "running" :name "Run" :moving_s 1800 :raw {}}])
    (db/upsert-metrics! ds [{:activity_id 1 :tss 50.0 :tss_method "rTSS" :peaks {:hr {:5 180}}}])
    (let [[s body] (get-json app "/api/activities")]
      (is (= 200 s))
      (is (= [1] (map :id body)))
      (is (= 50.0 (:tss (first body))))
      (is (= {:hr {:5 180}} (:peaks (first body)))))
    (is (= "running" (:sport (second (get-json app "/api/activities/1")))))
    (is (= 404 (first (get-json app "/api/activities/2"))))
    (is (= [] (second (get-json app "/api/activities" "sport=cycling"))))
    (let [[_ load] (get-json app "/api/load" "from=2026-10-13&to=2026-10-14")]
      (is (= ["2026-10-13" "2026-10-14"] (map :date load)))
      (is (= 50.0 (:tss (first load)))))
    (is (= "2027-04-27" (:expires_on (second (get-json app "/api/auth")))) "180 days from login")
    (is (= 404 (first (get-json app "/api/nope"))))))
