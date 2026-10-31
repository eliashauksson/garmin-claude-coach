(ns coach.api
  "Local JSON API for the dashboard. Binds to 127.0.0.1 only."
  (:require [clojure.data.json :as json]
            [coach.analysis :as analysis]
            [coach.auth :as auth]
            [coach.adapt :as adapt]
            [coach.db :as db]
            [coach.plan :as plan]
            [coach.planner :as planner]
            [coach.readiness :as readiness]
            [coach.season :as season]
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
        (let [result (try
                       (let [src ((requiring-resolve 'coach.garmin-mcp/start!))]
                         (try (sync/incremental! src ds)
                              (finally ((requiring-resolve 'coach.garmin-mcp/stop!) src)))
                         ;; new data may call for adapting the plan
                         (let [{:keys [proposed]} (planner/check! ds)]
                           {:ok true :proposals proposed}))
                       (catch Throwable e
                         {:ok false :error (ex-message e)}))]
          (swap! sync-state assoc :running false
                 :finished_at (str (java.time.Instant/now))
                 :last_result result))))
    @sync-state))

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

(defn- auth-status [ds]
  (let [st (auth/status ds)
        err (get-in @sync-state [:last_result :error])]
    (cond-> st
      (and err (re-find #"(?i)auth|login|token|401|403" err))
      (assoc :level :expired :sync_error err))))

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
  "§7.1 check for today's planned workouts."
  [ds]
  (let [t    (today)
        days (analysis/wellness ds (season/add-days t -1) t)
        ws   (filter #(= "planned" (:status %)) (plan/workouts ds t t))]
    (assoc (readiness/check days ws (:week (season/week-info t)))
           :date t :workouts ws)))

(defn plan-routes [ds]
  ["/plan"
   ["" {:get (fn [{:keys [query-params]}]
               (let [[from to] (date-range query-params 0)]
                 (ok {:weeks (plan/weeks ds from to)
                      :workouts (plan/workouts ds from to)
                      :season (season/week-info (today))})))}]
   ["/readiness" {:get (fn [_] (ok (readiness-today ds)))}]
   ["/signals"   {:get (fn [_] (ok (adapt/signals (planner/adapt-inputs ds (today)))))}]
   ["/adjust"    {:post (guarded (fn [req]
                                   (planner/api-key)
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

(defn routes [ds]
  [["/api"
    ["/today"       {:get (fn [_] (ok (assoc (analysis/today-summary ds)
                                             :auth (auth-status ds)
                                             :sync (dissoc @sync-state :started_at)
                                             :season (season/week-info (today))
                                             :readiness_check (readiness-today ds)
                                             :open_proposals (plan/open-proposals ds))))}]
    (plan-routes ds)
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
    ["/sync"        {:get  (fn [_] (ok @sync-state))
                     :post (fn [_] (json-response 202 (run-sync! ds)))}]]])

(defn- index [_]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (slurp r)}
    {:status 404 :body "index.html missing"}))

(defn app [ds]
  (-> (ring/ring-handler
       (ring/router (conj (routes ds) ["/" {:get index}]))
       (ring/routes
        (ring/create-resource-handler {:path "/" :root "public"})
        (ring/create-default-handler
         {:not-found (constantly (json-response 404 {:error "not found"}))})))
      wrap-params))

(defonce server (atom nil))
(defonce scheduler (atom nil))

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
     (when sync?
       (run-sync! ds)
       (start-background-sync! ds))
     @server)))

(defn stop! []
  (when-let [ex @scheduler] (.shutdownNow ex) (reset! scheduler nil))
  (when-let [s @server] (.stop s) (reset! server nil)))

(defn -main
  "clojure -M -m coach.api [port] [--no-sync]"
  [& args]
  (start! {:port  (or (some parse-long args) 8080)
           :sync? (not (some #{"--no-sync"} args))}))
