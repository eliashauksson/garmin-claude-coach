(ns coach.api
  "Local JSON API for the dashboard. Binds to 127.0.0.1 only."
  (:require [clojure.data.json :as json]
            [coach.analysis :as analysis]
            [coach.auth :as auth]
            [coach.db :as db]
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
                         (try (sync/incremental! src ds) {:ok true}
                              (finally ((requiring-resolve 'coach.garmin-mcp/stop!) src))))
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

(defn routes [ds]
  [["/api"
    ["/today"       {:get (fn [_] (ok (assoc (analysis/today-summary ds)
                                             :auth (auth-status ds)
                                             :sync (dissoc @sync-state :started_at))))}]
    ["/auth"        {:get (fn [_] (ok (auth-status ds)))}]
    ["/activities"  {:get (fn [{:keys [query-params]}]
                            (let [{:strs [sport from to limit]} query-params]
                              (ok (analysis/activities ds {:sport sport :from from :to to
                                                           :limit (some-> limit parse-long)}))))}]
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
    ["/trends"      {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 365)]
                              (ok (analysis/trends ds from to))))}]
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

(defn start!
  "Starts the API on 127.0.0.1:`port`. With :sync? true (the default) an
  incremental sync runs in the background on startup."
  ([] (start! {}))
  ([{:keys [port sync?] :or {port 8080 sync? true}}]
   (let [ds (doto (db/datasource) db/migrate!)]
     (reset! server (jetty/run-jetty (app ds) {:port port :host "127.0.0.1" :join? false}))
     (println (str "Dashboard on http://127.0.0.1:" port "/"))
     (println (auth/describe (auth/status ds)))
     (when sync? (run-sync! ds))
     @server)))

(defn stop! []
  (when-let [s @server] (.stop s) (reset! server nil)))

(defn -main
  "clojure -M -m coach.api [port] [--no-sync]"
  [& args]
  (start! {:port  (or (some parse-long args) 8080)
           :sync? (not (some #{"--no-sync"} args))}))
