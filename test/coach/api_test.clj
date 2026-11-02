(ns coach.api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.api :as api]
            [coach.auth :as auth]
            [coach.db :as db]
            [coach.plan]))

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
    (with-redefs [auth/connected? (constantly true)] ; a token file, wherever the tests run
      (is (= "2027-04-27" (:expires_on (second (get-json app "/api/auth")))) "180 days from login"))
    (testing "the day page: any date, with that day's activities"
      (db/upsert-wellness! ds [{:date "2026-10-13" :rhr 52 :sleep_score 75 :raw {}}])
      (let [[s d] (get-json app "/api/day/2026-10-13")]
        (is (= 200 s))
        (is (= "2026-10-13" (:date d)))
        (is (= 52 (get-in d [:wellness :rhr])))
        (is (= [1] (map :id (:activities d))))
        (is (not (contains? d :readiness_check)) "suggestions only for today"))
      (is (= 400 (first (get-json app "/api/day/yesterday")))))
    (is (= 404 (first (get-json app "/api/nope"))))))

(defn- send-json [app method uri body]
  (let [r (app {:request-method method :uri uri
                :body (java.io.ByteArrayInputStream. (.getBytes (json/write-str body) "UTF-8"))})]
    [(:status r) (json/read-str (:body r) :key-fn keyword)]))

(deftest plan-flow-test
  (let [ds  (temp-ds)
        app (api/app ds)
        pid (coach.plan/create-proposal! ds "2026-11-02" "test")]
    (coach.plan/finish-proposal!
     ds pid {:context {} :warnings ["w"] :usage {}
             :response {:summary "s" :targets {:run_min 180 :longest_run "60 min" :quality "strides"}
                        :warnings []
                        :workouts [{:date "2026-11-02" :slot "AM" :session_id "EASY" :title "Easy" :duration_min 45
                                    :distance_km 7 :intensity "E" :description "d" :reason "r"}
                                   {:date "2026-11-03" :slot "PM" :session_id "GYM_UPPER" :title "Upper" :duration_min 50
                                    :distance_km 0 :intensity "-" :description "d" :reason "r"}]}})
    (let [[_ p] (get-json app (str "/api/plan/proposals/" pid))]
      (is (= "open" (:status p)))
      (is (= ["pending" "pending"] (map :decision (:items p)))))
    (testing "accept one with an edit, reject the other"
      (send-json app :post (str "/api/plan/proposals/" pid "/items/0") {:decision "accepted" :edits {:duration_min 40}})
      (let [[_ p] (send-json app :post (str "/api/plan/proposals/" pid "/items/1") {:decision "rejected"})]
        (is (= ["edited" "rejected"] (map :decision (:items p))))))
    (let [[_ plan] (get-json app "/api/plan" "from=2026-11-02&to=2026-11-08")]
      (is (= [["EASY" 40 "planned"]] (map (juxt :session_id :duration_min :status) (:workouts plan))))
      (is (= "2026-11-02" (:monday (first (:weeks plan)))))
      (is (nil? (:week (first (:weeks plan))))) ; no season plan
      (let [sid (coach.plan/create-season-plan! ds "2026-12-20" "2026-11-02" "test")]
        (coach.plan/finish-season-plan! ds sid {:response {:summary "s" :warnings []
                                                            :weeks [{:monday "2026-11-02" :phase "Base" :tss 100
                                                                     :cutback false :focus "f"}]}})
        (is (= [1 "Base"] ((juxt :week :phase) (first (:weeks (second (get-json app "/api/plan" "from=2026-11-02&to=2026-11-08")))))))))
    (testing "a run on that day marks the planned run done"
      (db/upsert-activities! ds [{:id 9 :date "2026-11-02" :start_time_local "2026-11-02T06:00:00"
                                  :sport "running" :name "Run" :raw {}}])
      (is (= ["done"] (map :status (:workouts (second (get-json app "/api/plan" "from=2026-11-02&to=2026-11-08")))))))))

(deftest foreign-request-test
  (let [req (fn [method headers] {:request-method method :uri "/api/quit" :headers headers})
        foreign? #(api/foreign-request? (req %1 %2))]
    (testing "this page, the installer and curl"
      (is (not (foreign? :post {"host" "127.0.0.1:8080" "origin" "http://127.0.0.1:8080" "sec-fetch-site" "same-origin"})))
      (is (not (foreign? :post {"host" "localhost:8080" "origin" "http://localhost:8080"})))
      (is (not (foreign? :get {"host" "[::1]:8080"})))
      (is (not (foreign? :post {"host" "127.0.0.1:8080"})) "curl: no Origin")
      (is (not (foreign? :get {"host" "127.0.0.1:8080" "sec-fetch-site" "none"})) "typed in the address bar")
      (is (not (foreign? :get {})) "no headers at all"))
    (testing "another website"
      (is (foreign? :post {"host" "127.0.0.1:8080" "origin" "https://evil.example"}) "older browser, no Sec-Fetch-Site")
      (is (foreign? :get {"host" "127.0.0.1:8080" "sec-fetch-site" "cross-site"}) "e.g. an <img> of /api/backup/download")
      (is (foreign? :post {"host" "127.0.0.1:8080" "origin" "http://127.0.0.1.evil.example"}))
      (is (foreign? :get {"host" "evil.example:8080"}) "DNS rebinding: the Host gives it away")
      (is (foreign? :get {"host" "127.0.0.1.evil.example"})))))
