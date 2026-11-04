(ns coach.api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.api :as api]
            [coach.auth :as auth]
            [coach.backup :as backup]
            [coach.coros-auth :as coros-auth]
            [coach.db :as db]
            [coach.paths :as paths]
            [coach.token :as token]
            [coach.plan]
            [coach.sync :as sync]))

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
  ;; the week of 2026-11-02, seen from the day before it starts: nothing missed yet
  (with-redefs [coach.plan/today (constantly "2026-11-01")]
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
      (is (= ["done"] (map :status (:workouts (second (get-json app "/api/plan" "from=2026-11-02&to=2026-11-08"))))))))))

(deftest token-guard-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "coach-tok" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [token/*token-file* (str dir "/coach/dashboard-token")]
      (token/reset-cache!)
      (let [ds   (temp-ds)
            app  (api/app ds)
            send (fn [uri headers & [qs]]
                   (app {:request-method :get :uri uri :headers headers :query-string qs}))]
        (testing "the token is handed only to a same-origin fetch from the page"
          (let [r (send "/api/token" {"host" "127.0.0.1:8080" "sec-fetch-site" "same-origin"})]
            (is (= 200 (:status r)))
            (is (= (token/ensure!) (:token (json/read-str (:body r) :key-fn keyword)))))
          (is (= 403 (:status (send "/api/token" {"host" "127.0.0.1:8080"})))
              "a header-less local client (curl) can't read the token"))
        (testing "the backup download needs the token"
          (is (= 403 (:status (send "/api/backup/download" {"host" "127.0.0.1:8080"})))
              "no token: refused before any backup is made")
          (with-redefs [backup/create! (fn [_] {:name "b.zip" :path (str dir "/b.zip")})]
            (spit (str dir "/b.zip") "zip")
            (let [r (send "/api/backup/download"
                          {"host" "127.0.0.1:8080"} (str "token=" (token/ensure!)))]
              (is (= 200 (:status r)))
              (is (= "application/zip" (get-in r [:headers "Content-Type"]))))
            (is (= 403 (:status (send "/api/backup/download"
                                      {"host" "127.0.0.1:8080"} "token=wrong")))))))
      (token/reset-cache!))))

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
      (is (foreign? :get {"host" "127.0.0.1.evil.example"})))
    (testing "Coros's sign-in page sending the browser back, and only that"
      (let [cb (fn [method headers] (api/foreign-request? {:request-method method :uri "/api/coros/callback" :headers headers}))]
        (is (not (cb :get {"host" "127.0.0.1:8090" "sec-fetch-site" "cross-site"})))
        (is (cb :post {"host" "127.0.0.1:8090" "sec-fetch-site" "cross-site"}))
        (is (cb :get {"host" "evil.example:8090" "sec-fetch-site" "cross-site"}) "still only on this computer")))))

(deftest page-navigation-test
  (with-redefs [paths/allowed-hosts (constantly #{"coach.tail1.ts.net"})]
    (let [nav {"host" "coach.tail1.ts.net" "sec-fetch-site" "cross-site"
               "sec-fetch-mode" "navigate" "sec-fetch-dest" "document"}
          foreign? (fn [method uri headers] (api/foreign-request? {:request-method method :uri uri :headers headers}))]
      (testing "opening the page from a home-screen shortcut or a link elsewhere"
        (is (not (foreign? :get "/" nav)))
        (is (not (foreign? :get "/" (assoc nav "sec-fetch-site" "same-site")))))
      (testing "but nothing else from another site"
        (is (foreign? :get "/api/backup/download" nav) "the API stays same-origin only")
        (is (foreign? :post "/" nav))
        (is (foreign? :get "/" (assoc nav "sec-fetch-dest" "iframe")) "no framing")
        (is (foreign? :get "/" (dissoc nav "sec-fetch-mode")) "e.g. an <img>")
        (is (foreign? :get "/" (assoc nav "host" "evil.example")) "the Host check still applies")))))

(deftest allowed-hosts-test
  (testing "no allowed hosts (the local app): only this computer"
    (with-redefs [paths/allowed-hosts (constantly #{})]
      (is (api/foreign-request? {:request-method :get :uri "/" :headers {"host" "coach.tail1.ts.net"}}))))
  (with-redefs [paths/allowed-hosts (constantly #{"coach.tail1.ts.net"})]
    (let [foreign? (fn [method headers] (api/foreign-request? {:request-method method :uri "/api/quit" :headers headers}))]
      (testing "the page opened through an allowed host (tailscale serve)"
        (is (not (foreign? :get {"host" "coach.tail1.ts.net" "sec-fetch-site" "same-origin"})))
        (is (not (foreign? :post {"host" "COACH.tail1.ts.net" "origin" "https://coach.tail1.ts.net" "sec-fetch-site" "same-origin"})))
        (is (not (foreign? :post {"host" "127.0.0.1:8080"})) "this computer still works"))
      (testing "everything else is still refused"
        (is (foreign? :get {"host" "evil.example"}))
        (is (foreign? :get {"host" "coach.tail1.ts.net.evil.example"}))
        (is (foreign? :get {"host" "coach.tail1.ts.net" "sec-fetch-site" "cross-site"}))
        (is (foreign? :post {"host" "coach.tail1.ts.net" "origin" "https://evil.example"}))))))

(deftest coros-callback-url-test
  (with-redefs [paths/allowed-hosts (constantly #{"coach.tail1.ts.net"})]
    (let [url (fn [headers] (api/coros-callback-url {:server-port 8080 :headers headers}))]
      (is (= "http://127.0.0.1:8080/api/coros/callback" (url {"host" "127.0.0.1:8080"})))
      (is (= "http://127.0.0.1:8080/api/coros/callback" (url {"host" "evil.example"})) "not an allowed host")
      (is (= "http://127.0.0.1:8080/api/coros/callback" (url {})))
      (is (= "https://coach.tail1.ts.net/api/coros/callback" (url {"host" "coach.tail1.ts.net"})) "tailscale serve: HTTPS, no port")
      (is (= "http://coach.tail1.ts.net:8080/api/coros/callback" (url {"host" "coach.tail1.ts.net:8080"})))
      (is (= "https://coach.tail1.ts.net:8443/api/coros/callback"
             (url {"host" "coach.tail1.ts.net:8443" "x-forwarded-proto" "https"}))))))

(deftest parse-hosts-test
  (is (= #{"a.ts.net" "b"} (paths/parse-hosts " A.ts.net, b ,")))
  (is (= #{"a.ts.net"} (paths/parse-hosts ["a.ts.net" ""])))
  (is (= #{} (paths/parse-hosts nil))))

(deftest coros-sync-test
  (let [ds  (temp-ds)
        run (fn [] (#'api/run-coros-sync! ds))]
    (testing "without a Coros login nothing starts"
      (with-redefs [coros-auth/connected? (constantly false)]
        (reset! api/sync-state {:running false})
        (is (not (:running (run))))))
    (testing "a running sync isn't joined by another"
      (with-redefs [coros-auth/connected? (constantly true)]
        (reset! api/sync-state {:running true :job "sync"})
        (is (= "sync" (:job (run))))))
    (testing "without a Garmin login, the regular sync syncs Coros alone"
      (let [started (atom nil)]
        (with-redefs [auth/connected?       (constantly false)
                      coros-auth/connected? (constantly true)
                      api/run-coros-sync!   (fn [_] (reset! started :coros) {})]
          (#'api/run-sync! ds)
          (is (= :coros @started)))))
    (reset! api/sync-state {:running false})))

(deftest revalidate-test
  (let [app (api/app (temp-ds))]
    (testing "the page is checked with the server on every load, the API isn't marked"
      (is (= "no-cache" (get-in (app {:request-method :get :uri "/"}) [:headers "Cache-Control"])))
      (is (nil? (get-in (app {:request-method :get :uri "/api/nothing-here"}) [:headers "Cache-Control"]))))
    (testing "the script's address carries the version, so an update fetches the new one"
      (is (re-find #"/js/main\.js\?v=" (:body (app {:request-method :get :uri "/"})))))))

(deftest coros-daily-limit-test
  (let [ds    (temp-ds)
        calls (atom 0)
        run   (fn [& [opts]] (#'api/run-coros! ds opts))]
    (binding [*out* (java.io.StringWriter.)]
      (with-redefs [sync/coros! (fn [_] (swap! calls inc)
                                  (throw (ex-info "You have reached the daily limit of 50 activity FIT files."
                                                  {:kind :rate-limit :remaining 4})))]
        (testing "a daily-limit refusal says how many rides are missing and pauses until tomorrow"
          (let [r (run)]
            (is (= [false :rate-limit 4] [(:ok r) (:kind r) (:remaining r)]))
            (is (= (str (.plusDays (java.time.LocalDate/now) 1)) (:paused_until r)))))
        (testing "automatic syncs don't ask Coros again until then"
          (is (= "rate-limit" (:kind (run))))
          (is (= 1 @calls)))
        (testing "the Coros card's Sync now does"
          (run {:force true})
          (is (= 2 @calls)))))))
