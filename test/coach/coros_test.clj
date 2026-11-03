(ns coach.coros-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.coros-auth :as auth]
            [coach.coros-mcp :as c]
            [coach.db :as db]
            [coach.fit :as fit]
            [coach.mcp :as mcp]
            [coach.source :as source]
            [coach.sync :as sync])
  (:import (java.time Instant)))

(def records-text
  "Sport Records — 2025-01-01 to 2026-11-03 (2 records)
========================

1. Cycling — 2026-09-26
   Location: Brugg Road Bike
   Start Coordinates: 47.485001, 8.207000
   Time Window: startTimestamp=1790419085 | endTimestamp=1790423429
   Duration: 1:10:16 | Distance: 28.50 km
   Average Speed: 24.3 km/h | Calories: 748 kcal
   LabelId: 480613187119514001 | SportType: 200

2. Indoor Cycling — 2025-11-22
   Duration: 1:00:00 | Distance: 30.00 km
   LabelId: 473474028053496010 | SportType: 201")

(deftest parse-records
  (let [[a b :as rs] (c/parse-records records-text)]
    (is (= 2 (count rs)))
    (is (= {:id 480613187119514001 :label_id "480613187119514001" :sport_type 200
            :date "2026-09-26" :start_ts 1790419085 :name "Brugg Road Bike"}
           a))
    (is (= [473474028053496010 201 "2025-11-22" nil nil]
           ((juxt :id :sport_type :date :start_ts :name) b))))
  (testing "no rides is an empty list, an unreadable answer an error"
    (is (= [] (c/parse-records "No sport records found from 2026-10-01 to 2026-11-03.")))
    (is (thrown? clojure.lang.ExceptionInfo (c/parse-records "Something else entirely")))))

(deftest newest-first
  (is (= [3 2 4 1]
         (map :id (c/newest-first [{:id 1 :date "2025-03-05" :start_ts 100}
                                   {:id 2 :date "2026-09-26" :start_ts 300}
                                   {:id 3 :date "2026-09-26" :start_ts 400}
                                   {:id 4 :date "2025-11-22" :start_ts nil}])))))

(deftest fit-url-only-from-coros
  (is (= "https://s3eu.coros.com/fit/1/2.fit"
         (c/fit-url "Activity FIT file download URL(s):\n1. 2.fit\n   https://s3eu.coros.com/fit/1/2.fit")))
  (is (nil? (c/fit-url "https://example.com/2.fit")))
  (is (nil? (c/fit-url "http://s3eu.coros.com/fit/1/2.fit")))
  (is (nil? (c/fit-url "https://coros.com.example.com/2.fit"))))

(deftest read-only-allowlist
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing"
                        (c/call nil "createScheduledWorkout" {}))))

(deftest error-kinds
  (is (= :rate-limit (c/error-kind "Daily FIT file download limit reached")))
  (is (= :auth (c/error-kind "MCP server answered HTTP 401")))
  (is (= :coros (c/error-kind "something odd"))))

(def t0 1159353485) ; FIT seconds: 2026-09-26T10:38:05Z

(deftest activity-row-from-fit
  (let [msgs {"session"  [{"start_time" t0 "total_distance" 28503.15 "total_elapsed_time" 4344.09
                           "total_timer_time" 4215.95 "total_ascent" 209 "total_calories" 748
                           "avg_heart_rate" 143 "max_heart_rate" 171}]
              "activity" [{"timestamp" (+ t0 4344) "local_timestamp" (+ t0 4344 7200)}]}
        row  (c/activity-row {:id 42 :sport_type 200 :name "Brugg Road Bike"} msgs)]
    (is (= {:id 42 :sport "road_biking" :name "Brugg Road Bike"
            :start_time_gmt "2026-09-26T10:38:05" :start_time_local "2026-09-26T12:38:05" :date "2026-09-26"
            :distance_m 28503.15 :duration_s 4344.09 :moving_s 4215.95 :elev_gain_m 209
            :avg_hr 143 :max_hr 171 :calories 748}
           (select-keys row [:id :sport :name :start_time_gmt :start_time_local :date :distance_m
                             :duration_s :moving_s :elev_gain_m :avg_hr :max_hr :calories])))
    (is (= "coros" (get-in row [:raw :source]))))
  (testing "a bike sport family, and a name when Coros has no location"
    (is (= "Indoor cycling" (:name (c/activity-row {:id 1 :sport_type 201} {"session" [{"start_time" t0}]}))))
    (is (= "other" (c/sport-name 12345)))))

(deftest records->stream
  (let [s (c/records->stream [{"timestamp" (+ t0 1) "power" 0 "heart_rate" 120 "speed" 6.0
                               "position_lat" 566500000 "position_long" 97900000}
                              {"timestamp" t0 "power" 0 "heart_rate" 118 "speed" 5.0}
                              {"timestamp" (+ t0 2) "power" 0 "cadence" 0 "speed" 6.5}])]
    (testing "sorted by time, t from the start"
      (is (= [0 1 2] (:t s)))
      (is (= [118 120 nil] (:hr s)))
      (is (= [5.0 6.0 6.5] (:speed s))))
    (testing "all-zero power and cadence mean no sensor"
      (is (not (contains? s :power)))
      (is (not (contains? s :cadence))))
    (testing "positions in degrees"
      (is (< 47.48 (second (:lat s)) 47.49))))
  (is (nil? (c/records->stream []))))

(deftest fit-epoch
  (is (= 1790419085 (fit/unix-seconds t0))))

;; ---------------------------------------------------------------------------
;; MCP over HTTP, OAuth

(deftest sse-messages
  (is (= [{"jsonrpc" "2.0" "id" 1 "result" {"ok" true}}]
         (mcp/sse-messages "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\ndata: \"result\":{\"ok\":true}}\n\n")))
  (is (= 2 (count (mcp/sse-messages "data: {\"id\":1}\r\n\r\ndata: {\"id\":2}\r\n\r\n")))))

(deftest http-status-kinds
  (is (= :auth (mcp/http-status-kind 401)))
  (is (= :rate-limit (mcp/http-status-kind 429)))
  (is (= :server-error (mcp/http-status-kind 502))))

(deftest pkce
  ;; RFC 7636, appendix B
  (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
         (auth/code-challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))))

(deftest oauth-urls
  (is (= "https://mcp.coros.com/.well-known/oauth-protected-resource/mcp"
         (auth/well-known-url "https://mcp.coros.com/mcp")))
  (let [u (auth/authorize-url {:authorization_endpoint "https://a.example/oauth2/authorize"
                               :resource "https://r.example/mcp"}
                              {:client-id "abc" :redirect-uri "http://127.0.0.1:8081/api/coros/callback"
                               :state "s1" :verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"})]
    (is (re-find #"^https://a\.example/oauth2/authorize\?response_type=code&client_id=abc&" u))
    (is (re-find #"redirect_uri=http%3A%2F%2F127\.0\.0\.1%3A8081%2Fapi%2Fcoros%2Fcallback" u))
    (is (re-find #"scope=openid\+mcp\.tools\+offline_access" u))
    (is (re-find #"code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256" u))))

(deftest token-expiry
  (let [now (Instant/parse "2026-11-03T12:00:00Z")]
    (is (auth/expiring? nil now))
    (is (auth/expiring? "2026-11-03T12:00:30Z" now))
    (is (not (auth/expiring? "2026-11-03T13:00:00Z" now)))))

(deftest connected-from-token-file
  (let [f (java.io.File/createTempFile "coros" ".json")]
    (.delete f)
    (binding [auth/*token-file* (str f)]
      (is (not (auth/connected?)))
      (spit f "{\"refresh_token\":\"r\",\"connected_at\":\"2026-11-03T12:00:00Z\"}")
      (is (auth/connected?))
      (is (= "2026-11-03T12:00:00Z" (:connected_at (auth/status))))
      (auth/disconnect!)
      (is (not (.exists f))))))

;; ---------------------------------------------------------------------------
;; Sync

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-test" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- fake-source [summaries fetched fail-on]
  (reify source/ActivitySource
    (list-activities [_ _ _] summaries)
    (fetch-full-activity [_ {:keys [id date]}]
      (when (= id fail-on) (throw (ex-info "Daily FIT file limit reached" {:kind :rate-limit})))
      (swap! fetched conj id)
      {:activity {:id id :date date :start_time_local (str date "T10:00:00") :sport "road_biking"
                  :name "Ride" :moving_s 3600.0 :duration_s 3700.0 :raw {:source "coros"}}
       :stream   {:t [0 1 2] :hr [120 121 122]}})))

(deftest activities-only-sync
  (let [ds      (temp-ds)
        fetched (atom [])
        rides   [{:id 1 :date "2026-10-01"} {:id 2 :date "2026-10-02"}]]
    (testing "new rides are stored with their stream and scored"
      (is (= [1 2] (sync/activities-only! (fake-source rides fetched nil) ds :coros_synced_through "2026-01-01")))
      (is (= [1 2] (map :id (db/query ds ["SELECT id FROM activities ORDER BY id"]))))
      (is (= [120 121 122] (:hr (db/load-stream ds 2))))
      (is (some? (db/get-state ds :coros_synced_through))))
    (testing "rides already stored aren't fetched again"
      (reset! fetched [])
      (is (= [3] (sync/activities-only! (fake-source (conj rides {:id 3 :date "2026-10-03"}) fetched nil)
                                        ds :coros_synced_through "2026-01-01")))
      (is (= [3] @fetched)))
    (testing "a failure halfway keeps what came before and doesn't move the sync date"
      (db/set-state! ds :coros_synced_through "2026-10-01")
      (is (thrown? clojure.lang.ExceptionInfo
                   (sync/activities-only! (fake-source (into rides [{:id 4 :date "2026-10-04"} {:id 5 :date "2026-10-05"}])
                                                       fetched 5)
                                          ds :coros_synced_through "2026-01-01")))
      (is (= [1 2 3 4] (map :id (db/query ds ["SELECT id FROM activities ORDER BY id"]))))
      (is (= "2026-10-01" (db/get-state ds :coros_synced_through))))))
