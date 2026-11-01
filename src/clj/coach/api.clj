(ns coach.api
  "Local JSON API for the dashboard. Binds to 127.0.0.1 only."
  (:require [clojure.data.json :as json]
            [coach.analysis :as analysis]
            [coach.auth :as auth]
            [coach.adapt :as adapt]
            [coach.ai-settings :as ai]
            [coach.athlete :as athlete]
            [coach.athlete-chat :as athlete-chat]
            [coach.backup :as backup]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.planner :as planner]
            [coach.readiness :as readiness]
            [coach.season :as season]
            [coach.season-plan :as season-plan]
            [coach.sync :as sync]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]])
  (:import (java.time LocalDate)))

;; ---------------------------------------------------------------------------
;; Sync runs in the background; one at a time.

(defonce sync-state (atom {:running false}))

(defn- run-sync!
  "Starts an incremental sync unless one is running. Returns the state."
  [ds]
  (let [[old _] (swap-vals! sync-state #(if (:running %) % (assoc % :running true :started_at (str (java.time.Instant/now)))))]
    (when-not (:running old)
      (future
        ;; first backup of the day, before anything is written
        (try (backup/daily! ds)
             (catch Throwable e (println "Daily backup failed:" (ex-message e))))
        (let [result (try
                       (let [src ((requiring-resolve 'coach.garmin-mcp/start!))]
                         (try (sync/incremental! src ds)
                              (finally ((requiring-resolve 'coach.garmin-mcp/stop!) src))))
                       {:ok true}
                       (catch Throwable e
                         (println "Sync failed:" (ex-message e))
                         (merge {:ok false} (sync/failure e))))
              ;; new data may call for adapting the plan; a failure here isn't a sync failure.
              ;; Without credits every Claude request would fail, so the check waits.
              result (cond
                       (not (:ok result)) result
                       (costs/credits-out ds) (assoc result :plan_check_skipped "no Claude credits")
                       :else (try (assoc result :proposals (:proposed (planner/check! ds)))
                                  (catch Throwable e (assoc result :plan_check_error (ex-message e)))))
              done   (str (java.time.Instant/now))
              result (assoc result :finished_at done)]
          (try (sync/record-result! ds result) (catch Throwable _))
          (swap! sync-state assoc :running false :finished_at done :last_result result
                 :last_ok (if (:ok result) done (:last_ok @sync-state))))))
    @sync-state))

(defn- load-sync-state!
  "After a restart: the last recorded result, so a failure is still shown."
  [ds]
  (let [r (sync/last-result ds)]
    (swap! sync-state merge
           {:last_ok (db/get-state ds :last_ok_sync)}
           (when r {:last_result r :finished_at (:finished_at r)}))))

;; ---------------------------------------------------------------------------

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-str body)})

(defn- ok [body] (json-response 200 body))

(defn- today [] (str (LocalDate/now)))
(defn- days-ago [n] (str (.minusDays (LocalDate/now) n)))

(defn- date-range
  "from/to query params with a default window ending today."
  [params default-days]
  [(get params "from" (days-ago default-days)) (get params "to" (today))])

(defn- auth-status [ds] (auth/status ds))

(defn- claude-status
  "For the banner on every page: are the Claude credits out?"
  [ds]
  (when-let [out (costs/credits-out ds)]
    (assoc out :credits_out true :billing_url planner/billing-url :message planner/no-credits-message)))

(defn- claude-check!
  "After the athlete adds funds: one tiny request on the cheapest model
  (about $0.00003) to see whether Claude answers again."
  [ds]
  (try
    (planner/request {:system "You test an API connection. Answer briefly."
                      :messages [[:user "Reply with the word ok."]]
                      :schema {"type" "object" "properties" {"reply" {"type" "string"}}
                               "required" ["reply"] "additionalProperties" false}
                      :model "claude-haiku-5-5" :effort "low"
                      :task :test :ref "credits check"})
    {:ok true :claude (claude-status ds)}
    (catch Throwable e
      {:ok false :error (ex-message e) :claude (claude-status ds)})))

(defn- body [req]
  (some-> (:body req) slurp not-empty (json/read-str :key-fn keyword)))

(defn- guarded
  "Turns bad input (ex-info) into a 400 instead of a 500."
  [handler]
  (fn [req]
    (try (handler req)
         (catch clojure.lang.ExceptionInfo e
           (json-response 400 {:error (ex-message e)})))))

(defn readiness-today
  "Readiness check (principles: Monitoring) for today's planned workouts."
  [ds]
  (let [t    (today)
        days (analysis/wellness ds (season/add-days t -1) t)
        ws   (filter #(= "planned" (:status %)) (plan/workouts ds t t))]
    (assoc (readiness/check days ws (athlete/settings))
           :date t :workouts ws)))

(defn plan-routes [ds]
  ["/plan"
   ["" {:get (fn [{:keys [query-params]}]
               (let [[from to] (date-range query-params 0)]
                 (ok {:weeks (plan/weeks ds from to)
                      :workouts (plan/workouts ds from to)
                      :season (plan/week-info ds (today))})))}]
   ["/readiness" {:get (fn [_] (ok (readiness-today ds)))}]
   ["/season"
    ["" {:get    (fn [_] (ok (season-plan/overview ds (today))))
         :post   (guarded (fn [_]
                            (planner/api-key) ; fail fast with a clear message
                            (planner/principles)
                            (season-plan/race-date!)
                            (json-response 202 {:id (season-plan/draft! ds)})))
         :delete (guarded (fn [_] (season-plan/delete! ds (today)) (ok (season-plan/overview ds (today)))))}]
    ["/restore" {:post (guarded (fn [req]
                                  (season-plan/restore! ds (:id (body req)))
                                  (ok (season-plan/overview ds (today)))))}]]
   ["/signals"   {:get (fn [_] (ok (adapt/signals (planner/adapt-inputs ds (today)))))}]
   ["/adjust"    {:post (guarded (fn [req]
                                   (planner/api-key)
                                   (planner/principles) ; fails without athlete info
                                   (let [note (not-empty (:note (body req)))
                                         sigs (filter :trigger? (adapt/signals (planner/adapt-inputs ds (today))))]
                                     (json-response 202 {:id (planner/propose-adjustment! ds sigs note)}))))}]
   ["/open"      {:get (fn [_] (ok (plan/open-proposals ds)))}]
   ["/proposals"
    ["" {:get  (fn [{:keys [query-params]}]
                 (ok (plan/latest-proposal ds (season/monday (get query-params "monday" (today)))
                                           (get query-params "kind" "week"))))
         :post (guarded (fn [req]
                          (planner/api-key) ; fail fast with a clear message
                          (planner/principles)
                          (json-response 202 {:id (planner/propose! ds (:monday (body req)))})))}]
    ["/:id" {:get (fn [{:keys [path-params]}]
                    (if-let [p (plan/proposal ds (parse-long (:id path-params)))]
                      (ok p)
                      (json-response 404 {:error "not found"})))}]
    ["/:id/items/:idx" {:post (guarded (fn [{:keys [path-params] :as req}]
                                         (let [{:keys [decision edits]} (body req)
                                               id (parse-long (:id path-params))]
                                           (plan/decide-item! ds id (parse-long (:idx path-params)) decision edits)
                                           (ok (plan/proposal ds id)))))}]
    ["/:id/accept-all" {:post (guarded (fn [{:keys [path-params] :as req}]
                                         (let [id (parse-long (:id path-params))]
                                           (plan/accept-all! ds id (boolean (:replace (body req))))
                                           (ok (plan/proposal ds id)))))}]]
   ["/workouts/:id" {:patch  (guarded (fn [{:keys [path-params] :as req}]
                                        (plan/update-workout! ds (parse-long (:id path-params)) (body req))
                                        (ok {:ok true})))
                     :delete (fn [{:keys [path-params]}]
                               (plan/delete-workout! ds (parse-long (:id path-params)))
                               (ok {:ok true}))}]])

(defn athlete-routes []
  ["/athlete"
   ["" {:get    (fn [_] (ok (merge (athlete/status)
                                       (when (planner/api-key?) (athlete-chat/ensure-summary!)))))
        :put    (guarded (fn [req]
                           (let [{:keys [content summary source]} (body req)]
                             (athlete/save! content summary source)
                             (ok (athlete/status)))))
        :delete (guarded (fn [_] (athlete/delete!) (ok (athlete/status))))}]
   ["/chat"    {:post (guarded (fn [req]
                                 (planner/api-key)
                                 (try (ok (athlete-chat/chat (:messages (body req))))
                                      (catch clojure.lang.ExceptionInfo e (throw e))
                                      (catch Exception e
                                        (json-response 502 {:error (str "Claude request failed: " (ex-message e))})))))}]
   ["/summary" {:post (guarded (fn [_]
                                 (planner/api-key)
                                 (swap! athlete-chat/summary-job assoc :failed nil) ; manual retry
                                 (athlete-chat/summarise!)
                                 (ok (athlete/status))))}]
   ["/restore" {:post (guarded (fn [req]
                                 (athlete/restore! (:id (body req)))
                                 (ok (athlete/status))))}]])

(defn routes [ds]
  [["/api"
    ["/day/:date"   {:get (guarded
                           (fn [{:keys [path-params]}]
                             (let [date (:date path-params)]
                               (when-not (re-matches #"\d{4}-\d{2}-\d{2}" date)
                                 (throw (ex-info (str "Not a date: " date) {})))
                               (ok (cond-> (assoc (analysis/day-summary ds date)
                                                  :season (plan/week-info ds date)
                                                  :planned (plan/workouts ds date date))
                                     ;; suggestions for today's sessions, from this morning's readiness
                                     (= date (today)) (assoc :readiness_check (readiness-today ds)))))))}]
    (plan-routes ds)
    (athlete-routes)
    ["/auth"        {:get (fn [_] (ok (auth-status ds)))}]
    ["/activities"  {:get (fn [{:keys [query-params]}]
                            (let [{:strs [sport from to limit]} query-params]
                              (ok (analysis/activities ds {:sport sport :from from :to to
                                                           :limit (some-> limit parse-long)}))))}]
    ["/activities/:id/feedback"
     {:put (guarded (fn [{:keys [path-params] :as req}]
                      (analysis/save-feedback! ds (parse-long (:id path-params)) (body req))
                      (ok (analysis/activity ds (parse-long (:id path-params))))))}]
    ["/activities/:id"
     {:get (fn [{:keys [path-params query-params]}]
             (let [id (parse-long (:id path-params))]
               (if-let [a (analysis/activity ds id)]
                 (ok (cond-> a
                       (= "true" (get query-params "streams")) (assoc :streams (db/load-stream ds id))))
                 (json-response 404 {:error "not found"}))))}]
    ["/wellness"    {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 90)]
                              (ok (analysis/wellness ds from to))))}]
    ["/load"        {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 180)]
                              (ok (analysis/pmc ds from to))))}]
    ["/volume"      {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 180)]
                              (ok (analysis/volume ds from to))))}]
    ["/thresholds"  {:get (fn [_] (ok (db/thresholds ds)))}]
    ["/ai-settings"
     ["" {:get    (fn [_] (ok (ai/overview ds)))
          :delete (fn [_] (ai/reset-all! ds) (ok (ai/overview ds)))}]
     ["/:task" {:put (guarded (fn [{:keys [path-params] :as req}]
                                (ai/save! ds (:task path-params) (body req))
                                (ok (ai/overview ds))))}]]
    ["/costs"       {:get (fn [{:keys [query-params]}]
                            (ok (costs/overview ds (get query-params "from"))))}]
    ["/claude/check" {:post (fn [_] (ok (claude-check! ds)))}]
    ["/sync"        {:get  (fn [_] (ok (assoc @sync-state :auth (auth-status ds) :claude (claude-status ds))))
                     :post (fn [_] (json-response 202 (assoc (run-sync! ds) :auth (auth-status ds))))}]
    ["/backup"
     ["" {:get  (fn [_] (ok (backup/status)))
          :post (fn [_] (backup/create! ds) (ok (backup/status)))}]
     ["/download" {:get (fn [_]
                          ;; a fresh backup, kept in data/backups like the others
                          (let [{:keys [name path]} (backup/create! ds)]
                            {:status  200
                             :headers {"Content-Type" "application/zip"
                                       "Content-Disposition" (str "attachment; filename=\"" name "\"")}
                             :body    (io/file path)}))}]]]])

(defn- index [_]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (slurp r)}
    {:status 404 :body "index.html missing"}))

(defn- wrap-errors
  "Unexpected exceptions become a JSON 500 with the message (and a log line),
  so the page can say what went wrong instead of \"HTTP 500\"."
  [handler]
  (fn [req]
    (try (handler req)
         (catch Throwable e
           (println "Error on" (:request-method req) (:uri req) "-" (str e))
           (json-response 500 {:error (str "Server error: " (or (ex-message e) (str (class e))))})))))

(defn app [ds]
  (-> (ring/ring-handler
       (ring/router (conj (routes ds) ["/" {:get index}]))
       (ring/routes
        (ring/create-resource-handler {:path "/" :root "public"})
        (ring/create-default-handler
         {:not-found (constantly (json-response 404 {:error "not found"}))})))
      wrap-errors
      wrap-params))

(defonce server (atom nil))
(defonce scheduler (atom nil))
(defonce batch-poller (atom nil))

(defn- start-batch-poller!
  "Collects half-price batch results every minute (and right away, for
  results that came in while the dashboard was off)."
  [ds]
  (let [ex (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay ex #(try (let [n (planner/poll-batches! ds)]
                                        (when (pos? n) (println "Collected" n "batch result(s)")))
                                      (catch Throwable e (println "Batch check failed:" (ex-message e))))
                             0 60 java.util.concurrent.TimeUnit/SECONDS)
    (reset! batch-poller ex)))

(def sync-every-hours 2)

(defn- due-for-sync? []
  (let [hour (.getHour (java.time.LocalTime/now))
        last (some-> (:finished_at @sync-state) java.time.Instant/parse)]
    (and (<= 6 hour 21)
         (not (:running @sync-state))
         (or (nil? last)
             (.isBefore last (.minus (java.time.Instant/now) (java.time.Duration/ofHours sync-every-hours)))))))

(defn- start-background-sync!
  "Checks every 15 minutes; syncs when the last sync is over 2 h old (06-22 h)."
  [ds]
  (let [ex (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate ex #(try (when (due-for-sync?) (run-sync! ds)) (catch Throwable _))
                          15 15 java.util.concurrent.TimeUnit/MINUTES)
    (reset! scheduler ex)))

(defn start!
  "Starts the API on 127.0.0.1:`port`. With :sync? true (the default) an
  incremental sync runs in the background on startup."
  ([] (start! {}))
  ([{:keys [port sync?] :or {port 8080 sync? true}}]
   (let [ds (doto (db/datasource) db/migrate!)]
     (reset! server (jetty/run-jetty (app ds) {:port port :host "127.0.0.1" :join? false}))
     (println (str "Dashboard on http://127.0.0.1:" port "/"))
     (println (auth/describe (auth/status ds)))
     (load-sync-state! ds)
     (costs/use-db! ds)
     (ai/use-db! ds)
     (let [n (costs/import-earlier! ds)]
       (when (pos? n) (println "Imported" n "earlier Claude requests into the cost log")))
     (start-batch-poller! ds)
     (when sync?
       (run-sync! ds)
       (start-background-sync! ds))
     @server)))

(defn stop! []
  (when-let [ex @scheduler] (.shutdownNow ex) (reset! scheduler nil))
  (when-let [ex @batch-poller] (.shutdownNow ex) (reset! batch-poller nil))
  (when-let [s @server] (.stop s) (reset! server nil)))

(defn -main
  "clojure -M -m coach.api [port] [--no-sync]"
  [& args]
  (start! {:port  (or (some parse-long args) 8080)
           :sync? (not (some #{"--no-sync"} args))}))
