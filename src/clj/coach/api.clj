(ns coach.api
  "Local JSON API for the dashboard. Binds to 127.0.0.1 only."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.adapt :as adapt]
            [coach.ai-settings :as ai]
            [coach.analysis :as analysis]
            [coach.api.http :as http :refer [athlete-tr body guarded iso-date! json-response ok ok-tr wrap-errors
                                                     wrap-gzip]]
            [coach.api.jobs :as jobs]
            [coach.api.security :as security
             :refer [admin-only coros-callback-url same-origin-fetch? token-guard wrap-local-only]]
            [coach.app :as coach-app]
            [coach.athlete :as athlete]
            [coach.athlete-chat :as athlete-chat]
            [coach.auth :as auth]
            [coach.backup :as backup]
            [coach.claude :as claude]
            [coach.coach-chat :as coach-chat]
            [coach.context :as context]
            [coach.coros-auth :as coros-auth]
            [coach.costs :as costs]
            [coach.cycle-data :as cycle-data]
            [coach.db :as db]
            [coach.debrief :as debrief]
            [coach.i18n :as i18n]
            [coach.overview :as overview]
            [coach.paths :as paths]
            [coach.peaks :as peaks]
            [coach.plan :as plan]
            [coach.planner :as planner]
            [coach.push :as push]
            [coach.readiness :as readiness]
            [coach.readiness-estimate :as readiness-estimate]
            [coach.season :as season]
            [coach.season-plan :as season-plan]
            [coach.sync :as sync]
            [coach.token :as token]
            [coach.translate :as translate]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]]))

(defn- setup
  "What a new install still needs, for the Dashboard's checklist and the
  banners: Garmin and Claude connected, the history loaded, the athlete
  document and a season plan."
  [ds]
  {:garmin  (auth/connected?)
   :claude  (claude/api-key?)
   :synced  (some? (db/get-state ds :synced_through))
   :history (sync/history-complete? ds)
   :athlete (some? (athlete/content))
   :season  (some? (plan/latest-season-plan ds "ready"))})

(declare claude-check!)

(defn- map-keys [] {:tracestrack (:tracestrack-key (paths/read-config))})

(defn- save-map-keys!
  "Saves the Tracestrack key (blank removes it)."
  [{:keys [tracestrack]}]
  (let [k (str/trim (str tracestrack))]
    (when-not (or (empty? k) (re-matches #"[A-Za-z0-9_\-]{8,200}" k))
      (throw (ex-info (i18n/t "That doesn't look like a Tracestrack key. Copy it from console.tracestrack.com.") {})))
    (paths/update-config! assoc :tracestrack-key (not-empty k))
    (map-keys)))

(defn- claude-key-saved!
  "Saves `k`, then checks it with one tiny request. A key the API rejects is
  taken out again (the previous one, if any, comes back)."
  [ds k]
  (let [prev (try (slurp claude/*key-file*) (catch Exception _ nil))]
    (claude/save-key! k)
    (let [{:keys [ok error] :as r} (claude-check! ds)]
      (if (and (not ok) (claude/key-rejected? error))
        (do (if prev (spit claude/*key-file* prev) (claude/remove-key!))
            {:ok false :error (i18n/t "Claude didn't accept that key. Check that you copied all of it, and that it isn't deleted in the Claude Console.")
             :key (claude/key-status)})
        (assoc r :key (claude/key-status))))))

(defn- days-ago [n] (season/add-days (season/today) (- n)))

(defn- date-range
  "from/to query params with a default window ending today."
  [params default-days]
  [(get params "from" (days-ago default-days)) (get params "to" (season/today))])

(defn- auth-status [ds] (auth/status ds))

(defn- claude-status
  "For the banner on every page: are the Claude credits out, or this athlete's spending limit reached?"
  [ds]
  (if-let [out (costs/credits-out ds)]
    (assoc out :credits_out true :billing_url claude/billing-url :message (claude/no-credits-message))
    (when-let [hit (costs/limit-reached ds)]
      {:limit_hit hit :message (claude/limit-message hit)})))

(defn- claude-check!
  "After the athlete adds funds: one tiny request on the cheapest model
  (about $0.00003) to see whether Claude answers again."
  [ds]
  (try
    (claude/request {:system "You test an API connection. Answer briefly."
                      :messages [[:user "Reply with the word ok."]]
                      :schema {"type" "object" "properties" {"reply" {"type" "string"}}
                               "required" ["reply"] "additionalProperties" false}
                      :model "claude-haiku-5-5" :effort "low"
                      :task :test :ref "credits check"})
    {:ok true :claude (claude-status ds)}
    (catch Throwable e
      {:ok false :error (ex-message e) :claude (claude-status ds)})))

(defn readiness-today
  "Readiness check (principles: Monitoring) for today's planned workouts."
  [ds]
  (let [t    (season/today)
        days (analysis/wellness ds (season/add-days t -1) t)
        ;; no score from Garmin this morning: the app's estimate stands in
        est  (when (and (seq days) (nil? (:readiness (last days)))) (readiness-estimate/for-date ds t))
        days (cond-> (vec days)
               est (update (dec (count days)) assoc :readiness (:score est) :readiness_estimated true))
        ws   (filter #(= "planned" (:status %)) (plan/workouts ds t t))]
    (assoc (readiness/check days ws (athlete/settings) (cycle-data/readiness-input ds t))
           :date t :workouts ws)))

(defn plan-routes [ds]
  ["/plan"
   ["" {:get (fn [{:keys [query-params]}]
               (let [[from to] (date-range query-params 0)]
                 (ok-tr ds {:weeks (plan/weeks ds from to)
                      :workouts (plan/workouts ds from to)
                      :season (plan/week-info ds (season/today))})))}]
   ["/readiness" {:get (fn [_] (ok-tr ds (readiness-today ds)))}]
   ["/season"
    ["" {:get    (fn [_] (ok-tr ds (season-plan/overview ds (season/today))))
         :post   (guarded (fn [_]
                            (claude/api-key) ; fail fast with a clear message
                            (claude/principles)
                            (season-plan/race-date!)
                            (json-response 202 {:id (season-plan/draft! ds)})))
         :delete (guarded (fn [_]
                            (season-plan/delete! ds (season/today))
                            (ok-tr ds (season-plan/overview ds (season/today)))))}]
    ["/restore" {:post (guarded (fn [req]
                                  (season-plan/restore! ds (:id (body req)))
                                  (ok-tr ds (season-plan/overview ds (season/today)))))}]]
   ["/signals"   {:get (fn [_] (ok (adapt/signals (planner/adapt-inputs ds (season/today)))))}]
   ["/adjust"    {:post (guarded (fn [req]
                                   (claude/api-key)
                                   (claude/principles) ; fails without athlete info
                                   (let [note (not-empty (:note (body req)))
                                         sigs (filter :trigger?
                                                      (adapt/signals (planner/adapt-inputs ds (season/today))))]
                                     (json-response 202 {:id (planner/propose-adjustment! ds sigs note)}))))}]
   ["/open"      {:get (fn [_] (ok (plan/open-proposals ds)))}]
   ["/proposals"
    ["" {:get  (fn [{:keys [query-params]}]
                 (ok-tr ds (plan/latest-proposal ds (season/monday (get query-params "monday" (season/today)))
                                           (get query-params "kind" "week"))))
         :post (guarded (fn [req]
                          (claude/api-key) ; fail fast with a clear message
                          (claude/principles)
                          (json-response 202 {:id (planner/propose! ds (:monday (body req)))})))}]
    ["/:id" {:get (fn [{:keys [path-params]}]
                    (if-let [p (plan/proposal ds (parse-long (:id path-params)))]
                      (ok p)
                      (json-response 404 {:error "not found"})))}]
    ["/:id/items/:idx" {:post (guarded (fn [{:keys [path-params] :as req}]
                                         (let [{:keys [decision edits]} (body req)
                                               id (parse-long (:id path-params))]
                                           (plan/decide-item! ds id (parse-long (:idx path-params)) decision edits)
                                           (ok-tr ds (plan/proposal ds id)))))}]
    ["/:id/accept-all" {:post (guarded (fn [{:keys [path-params] :as req}]
                                         (let [id (parse-long (:id path-params))]
                                           (plan/accept-all! ds id (boolean (:replace (body req))))
                                           (ok-tr ds (plan/proposal ds id)))))}]]
   ["/workouts/:id/send/garmin" {:post (guarded (fn [{:keys [path-params]}]
                                                  (let [w (push/garmin! ds (parse-long (:id path-params)))]
                                                    (ok-tr ds (assoc w :send (push/info w))))))}]
   ["/workouts/:id/send/coros" {:post (guarded (fn [{:keys [path-params]}]
                                                 (let [w (push/coros! ds (parse-long (:id path-params)))]
                                                   (ok-tr ds (assoc w :send (push/info w))))))}]
   ["/workouts/:id" {:get    (fn [{:keys [path-params]}]
                               (if-let [w (plan/workout ds (parse-long (:id path-params)))]
                                 (ok-tr ds (assoc w :send (push/info w)))
                                 (json-response 404 {:error "not found"})))
                     :patch  (guarded (fn [{:keys [path-params] :as req}]
                                        (plan/update-workout! ds (parse-long (:id path-params)) (body req))
                                        (ok {:ok true})))
                     :delete (fn [{:keys [path-params]}]
                               (plan/delete-workout! ds (parse-long (:id path-params)))
                               (ok {:ok true}))}]])

(defn athlete-routes [ds]
  ["/athlete"
   ["" {:get    (fn [_] (ok (athlete-tr ds (merge (athlete/status)
                                       (when (claude/api-key?) (athlete-chat/ensure-summary!))))))
        :put    (guarded (fn [req]
                           (let [{:keys [content summary source]} (body req)]
                             (athlete/save! content summary source)
                             (when summary
                               (claude/translate-soon! (translate/overview-texts summary))) ; the chat's new overview
                             (ok (athlete-tr ds (athlete/status))))))
        :delete (guarded (fn [_] (athlete/delete!) (ok (athlete/status))))}]
   ["/chat"    {:post (guarded (fn [req]
                                 (claude/api-key)
                                 (try (ok (athlete-chat/chat (:messages (body req))))
                                      (catch clojure.lang.ExceptionInfo e (throw e))
                                      (catch Exception e
                                        (json-response 502
                                                       {:error (str "Claude request failed: " (ex-message e))})))))}]
   ["/summary" {:post (guarded (fn [_]
                                 (claude/api-key)
                                 (swap! athlete-chat/summary-job assoc :failed nil) ; manual retry
                                 (athlete-chat/summarise!)
                                 (ok (athlete/status))))}]
   ["/restore" {:post (guarded (fn [req]
                                 (athlete/restore! (:id (body req)))
                                 (ok (athlete/status))))}]])

(declare stop!)
(defonce started-at (str (java.time.Instant/now)))
(defonce current-port (context/per-athlete-atom 8080))
(defonce syncing? (context/per-athlete-atom true)) ; false with --no-sync; kept on a restart

(defn- busy-response
  "Restoring or moving the data waits for a running sync or history load."
  []
  (when (:running @jobs/sync-state)
    (json-response 409 {:error (i18n/t "Wait until the sync has finished, then try again.")})))

(defn- restart-soon!
  "Answers first, then restarts (the page shows \"Restarting…\" and reconnects)."
  []
  (future (Thread/sleep 500)
          (coach-app/restart! @current-port (when-not @syncing? ["--no-sync"]) stop!)))

(defn- app-status []
  {:version          (coach-app/version)
   :installed        (paths/installed?)
   :windows          (paths/windows?)
   :data_dir         (str (paths/data-dir))
   :default_data_dir (str (paths/default-data-dir))
   :data_dir_env     (boolean (paths/env-data-dir))
   :autostart        (coach-app/autostart?)
   :server           (paths/server?)
   :instance         (paths/instance)
   :instance_label   (paths/instance-label)
   :admin            (nil? (paths/instance))
   :admin_name       (paths/main-name)
   :update           (coach-app/update-status)
   :notice           (coach-app/notice-message)})

(defn app-routes []
  ["/app"
   ["" {:get (fn [_] (ok (app-status)))}]
   ["/notice" {:delete (fn [_] (reset! coach-app/notice nil) (ok (app-status)))}]
   ["/autostart" {:put (guarded (fn [req]
                                  (coach-app/set-autostart! (boolean (:on (body req))) @current-port)
                                  (ok (app-status))))}]
   ["/update" {:post (guarded (token-guard (fn [_] (ok (coach-app/start-update! @current-port)))))}]
   ;; {:path "…"} moves the data there; {:path "…" :use_existing true} switches
   ;; to the coach data already in that folder. Both restart the app.
   ["/data-dir" {:post (admin-only
                        (fn [req]
                         (or (busy-response)
                             (let [{:keys [path use_existing]} (body req)
                                   {:keys [existing to error] :as r} (coach-app/check-data-dir path)]
                               (cond
                                 error (json-response 400 {:error error})
                                 (and existing (not use_existing)) (ok r)
                                 :else (do (coach-app/set-pending! (if existing {:use-data to} {:move-data to}))
                                           (restart-soon!)
                                           (ok {:restarting true})))))))}]])

(def activities-default-limit "How many activities /api/activities lists without a date range or limit." 200)
(def activities-max-limit "The most /api/activities ever lists in one answer." 1000)

(defn activities-limit
  "Pure: how many activities /api/activities lists for these query params
  (the newest ones). The whole history in one answer grows with every
  activity, so without a date range it is the newest `activities-default-limit`,
  and never more than `activities-max-limit`."
  [{:strs [limit from to]}]
  (let [n (some-> limit parse-long)]
    (min activities-max-limit
         (cond (and n (pos? n)) n
               (or from to) activities-max-limit
               :else activities-default-limit))))

(defn routes [ds]
  [["/api"
    ["/day/:date"   {:get (guarded
                           (fn [{:keys [path-params]}]
                             (let [date (iso-date! (:date path-params))]
                               (ok-tr ds (cond-> (assoc (analysis/day-summary ds date)
                                                  :season (plan/week-info ds date)
                                                  :planned (plan/workouts ds date date)
                                                  ;; the app's own score when Garmin has none
                                                  :readiness_estimate (readiness-estimate/for-date ds date))
                                     ;; suggestions for today's sessions, from this morning's readiness
                                     (= date (season/today)) (assoc :readiness_check (readiness-today ds))
                                     (cycle-data/tracking? ds)
                                     (assoc :cycle (cycle-data/overview ds (season/today) date)))))))}]
    ;; the dashboard's coach overview (coach-dashboard-spec.md)
    ["/overview"    {:get (guarded
                           (fn [{:keys [query-params]}]
                             ;; the goal time and race legs come from the athlete summary:
                             ;; an older summary without them is rewritten in the background
                             (let [date (iso-date! (get query-params "date" (season/today)))
                                   job  (when (claude/api-key?) (athlete-chat/ensure-summary!))]
                               (ok (cond-> (overview/overview ds date)
                                     (:summarising job) (assoc :summarising true)
                                     (cycle-data/tracking? ds) (assoc :cycle (cycle-data/overview ds date)))))))}]
    (plan-routes ds)
    (athlete-routes ds)
    (app-routes)
    ["/auth"        {:get (fn [_] (ok (auth-status ds)))}]
    ;; the loopback token, handed only to a same-origin fetch from the page
    ["/token"       {:get (fn [req] (if (same-origin-fetch? req)
                                      (ok {:token (token/ensure!)})
                                      (json-response 403 {:error "forbidden"})))}]
    ;; logging in to Garmin from the page: email and password, then the
    ;; two-step code if Garmin sends one. A new login starts a sync.
    ["/garmin/login" {:post   (fn [req]
                                (let [{:keys [email password]} (body req)
                                      r (auth/start-login! ds email password)]
                                  (when (= "ok" (:status r)) (jobs/run-sync! ds))
                                  (ok r)))
                      :delete (fn [_] (auth/cancel-login!) (ok {:status "cancelled"}))}]
    ["/garmin/code"  {:post (fn [req]
                              (let [r (auth/submit-code! ds (:code (body req)))]
                                (when (= "ok" (:status r)) (jobs/run-sync! ds))
                                (ok r)))}]
    ;; Coros: sign in on Coros's own page, which sends the browser back to
    ;; /coros/callback; a new login starts a sync
    ["/coros/login"    {:post   (fn [req]
                                  (ok (coros-auth/start-login! (coros-callback-url req))))
                        :delete (fn [_] (ok (coros-auth/disconnect!)))}]
    ["/coros/sync"     {:post (fn [_] (json-response 202 (jobs/run-coros-sync! ds true)))}]
    ["/coros/callback" {:get (fn [{:keys [query-params]}]
                               (try (coros-auth/finish-login! (update-keys query-params keyword))
                                    (when @syncing? (jobs/run-sync! ds))
                                    (catch clojure.lang.ExceptionInfo e
                                      (println "Coros login failed:" (ex-message e))))
                               ;; not a redirect: the browser would still count the next page as
                               ;; coming from Coros (wrap-local-only); a page of our own moving on is ours
                               {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                                :body (str "<!DOCTYPE html><meta charset=\"utf-8\">"
                                           "<meta http-equiv=\"refresh\" content=\"0;url=/#/settings\">"
                                           "<a href=\"/#/settings\">Settings</a>")})}]
    ;; the app runs in the background; Settings can stop it
    ["/quit"         {:post (fn [_]
                              (future (Thread/sleep 300)
                                      (println "Stopped from the dashboard.")
                                      (shutdown-agents)
                                      (System/exit 0))
                              (ok {:ok true}))}]
    ["/activities"  {:get (fn [{:keys [query-params]}]
                            (let [{:strs [sport from to]} query-params]
                              (ok (analysis/activities ds {:sport sport :from from :to to
                                                           :limit (activities-limit query-params)}))))}]
    ;; every GPS track, for the Map tab's heatmap
    ["/heatmap"     {:get (fn [_] (ok (analysis/heatmap ds)))}]
    ;; peak bagging: the peaks to draw, the Peaks page, manual bags
    ;; the world view, or with ?bbox=south,west,north,east&zoom=z what that part of the map draws
    ["/peaks"       {:get (fn [{:keys [query-params]}]
                            (let [bbox (some->> (get query-params "bbox") (re-seq #"-?[\d.]+") (mapv parse-double))
                                  zoom (some-> (get query-params "zoom") parse-long)
                                  cell (some-> (get query-params "cell") parse-double)]
                              (ok (peaks/map-peaks ds (if (and zoom (= 4 (count bbox)))
                                                        {:zoom zoom :bbox bbox :cell cell}
                                                        {:zoom 0 :cell 2.0})))))}]
    ["/peaks/summary" {:get (fn [_] (ok (peaks/summary ds)))}]
    ["/peaks/squares" {:get (fn [_] (ok (peaks/squares-status ds)))
                       :post (guarded (fn [req]
                                        (peaks/add-squares! (:squares (body req)))
                                        (jobs/peaks-soon! ds)
                                        (ok (peaks/squares-status ds))))}]
    ;; the whole world's dataset (about 1.5 GB in the shared peaks database): the main athlete's choice
    ["/peaks/world" {:post   (admin-only (guarded (fn [_]
                                                    (peaks/request-world!)
                                                    (jobs/peaks-soon! ds)
                                                    (ok (peaks/squares-status ds)))))
                     :delete (admin-only (guarded (fn [_]
                                                    (peaks/stop-world!)
                                                    (ok (peaks/squares-status ds)))))}]
    ["/peaks/squares/:square" {:delete (guarded (fn [{:keys [path-params]}]
                                                  (peaks/cancel-square! (:square path-params))
                                                  (ok (peaks/squares-status ds))))}]
    ["/peaks/search" {:get (fn [{:keys [query-params]}]
                             (let [q (some-> (get query-params "q") str/trim)]
                               (when (str/blank? q) (throw (ex-info (i18n/t "Type a place to search for.") {})))
                               (ok (peaks/search-areas q @i18n/lang))))}]
    ["/peak-bags" {:post (guarded (fn [req]
                                      (let [{:keys [peak date]} (body req)]
                                        (peaks/bag! ds peak date)
                                        (ok (peaks/summary ds)))))}]
    ["/peak-bags/:id" {:delete (guarded (fn [{:keys [path-params]}]
                                           (peaks/unbag! ds (parse-long (:id path-params)))
                                           (ok (peaks/summary ds))))}]
    ;; Ask the coach: answers from the athlete's data; a plan change is only offered (adjust_note)
    ["/coach/chat"
     {:post (guarded (fn [req]
                       (claude/api-key)
                       (try (ok (coach-chat/chat ds (:messages (body req))))
                            (catch clojure.lang.ExceptionInfo e (throw e))
                            (catch Exception e
                              (json-response 502 {:error (str "Claude request failed: " (ex-message e))})))))}]
    ;; the newest coach's note from the last two days, for Today
    ["/debrief/latest"
     {:get (fn [_] (ok (debrief/latest ds (season/today) 2)))}]
    ;; recent activities waiting for "How did it go?"
    ["/feedback/due"
     {:get (fn [{:keys [query-params]}] (ok (analysis/feedback-due ds (get query-params "date" (season/today)))))}]
    ["/activities/:id/feedback"
     {:put (guarded (fn [{:keys [path-params] :as req}]
                      (analysis/save-feedback! ds (parse-long (:id path-params)) (body req))
                      (debrief/after-feedback! ds (parse-long (:id path-params))) ; its coach's note, if due
                      (ok (analysis/activity ds (parse-long (:id path-params))))))}]
    ;; the coach's note on request (also the retry of a failed one); the page polls the activity
    ["/activities/:id/debrief"
     {:post (guarded (fn [{:keys [path-params]}]
                       (claude/api-key)
                       (let [id (parse-long (:id path-params))]
                         (if-let [a (analysis/activity ds id)]
                           (do (debrief/write-soon! ds a)
                               (ok {:debrief (debrief/shown ds id)}))
                           (json-response 404 {:error "not found"})))))}]
    ["/activities/:id"
     {:get (fn [{:keys [path-params query-params]}]
             (let [id (parse-long (:id path-params))]
               (if-let [a (analysis/activity ds id)]
                 (ok (cond-> a
                       ;; with the watch's all-day heart rate where the activity has none
                       (= "true" (get query-params "streams")) (assoc :streams (first (analysis/stream-with-hr ds a)))
                       ;; the peaks its track passed
                       true (assoc :summits (peaks/activity-summits ds id))
                       ;; the coach's note after it (coach.debrief)
                       true (assoc :debrief (debrief/shown ds id))
                       ;; the planned session it fulfilled, in the current language
                       true (assoc :planned (some->> (plan/planned-for-activity ds a)
                                                     (translate/localize ds @i18n/lang)))))
                 (json-response 404 {:error "not found"}))))}]
    ;; sex and cycle tracking (Settings): the choices, the phases per day, the athlete's own log
    ["/profile"     {:get (fn [_] (ok (cycle-data/profile ds)))
                     :put (guarded (fn [req] (ok (cycle-data/save-profile! ds (body req)))))}]
    ["/cycle"
     ["" {:get    (fn [{:keys [query-params]}]
                    (let [[from to] (date-range query-params 90)]
                      (ok {:enabled (cycle-data/tracking? ds)
                           :days    (cycle-data/days ds (iso-date! from) (iso-date! to))})))
          :delete (guarded (fn [_] (ok (cycle-data/delete-data! ds))))}]
     ["/summary" {:get (fn [_] (ok (cycle-data/summary ds (season/today))))}]
     ["/log" {:get (fn [{:keys [query-params]}]
                     (let [[from to] (date-range query-params 90)]
                       (ok (if (cycle-data/tracking? ds)
                             (cycle-data/log-rows ds (iso-date! from) (iso-date! to))
                             []))))}]
     ["/log/:date" {:put (guarded (fn [{:keys [path-params] :as req}]
                                    (when-not (cycle-data/tracking? ds)
                                      (throw (ex-info (i18n/t "Cycle tracking is off. Turn it on in Settings.") {})))
                                    (ok {:log (cycle-data/save-log! ds (:date path-params) (body req))
                                         :cycle (cycle-data/overview ds (season/today))})))}]]
    ["/wellness"    {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 90)]
                              (ok (analysis/wellness ds from to))))}]
    ["/trend"       {:get (guarded
                           (fn [{:keys [query-params]}]
                             (let [ks   (->> (str/split (get query-params "metrics" "") #",")
                                             (remove str/blank?) (mapv keyword))
                                   [from to] (date-range query-params 90)]
                               (ok (analysis/vital-trend ds ks from to)))))}]
    ["/load"        {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 180)]
                              (ok (analysis/pmc ds from to))))}]
    ["/forecast"    {:get (fn [_]
                            (let [today (season/today)
                                  season (plan/current-season ds)]
                              (ok {:race_date (:race_date season)
                                   :state     (some-> (analysis/form-state ds today) name)
                                   :forecast  (analysis/forecast ds today season)})))}]
    ["/volume"      {:get (fn [{:keys [query-params]}]
                            (let [[from to] (date-range query-params 180)]
                              (ok (analysis/volume ds from to))))}]
    ;; wrapped, so "no ride with power" (nil) isn't mistaken for "not loaded yet"
    ["/cycling-power" {:get (fn [_] (ok {:power (analysis/cycling-power ds (season/today))}))}]
    ["/thresholds"  {:get (fn [_] (ok (db/thresholds ds)))}]
    ["/ai-settings"
     ["" {:get    (fn [_] (ok (ai/overview ds)))
          :delete (admin-only (fn [_] (ai/reset-all! ds) (ok (ai/overview ds))))}]
     ["/:task" {:put (admin-only
                      (guarded (fn [{:keys [path-params] :as req}]
                                 (ai/save! ds (:task path-params) (body req))
                                 (ok (ai/overview ds)))))}]]
    ["/costs"       {:get (admin-only (fn [{:keys [query-params]}]
                                        (ok (costs/overview ds (get query-params "from")))))}]
    ["/claude/limits" {:put (admin-only
                             (guarded
                              (fn [req]
                                (costs/save-limits! (:limits (body req)))
                                (ok (costs/limits-overview ds)))))}]
    ["/claude/check" {:post (fn [_] (ok (claude-check! ds)))}]
    ;; the Claude API key: saved in ~/.config/coach/anthropic-key, never sent back
    ["/claude/key"   {:get    (fn [_] (ok (claude/key-status)))
                      :put    (admin-only (guarded (fn [req] (ok (claude-key-saved! ds (:key (body req)))))))
                      :delete (admin-only (fn [_] (ok (claude/remove-key!))))}]
    ;; the Tracestrack key of the Map tab's topo layer: in this athlete's config.edn, sent to their browser
    ["/map-keys"     {:get (fn [_] (ok (map-keys)))
                      :put (guarded (fn [req] (ok (save-map-keys! (body req)))))}]
    ["/claude/test"  {:post (fn [_] (ok (assoc (claude-check! ds) :key (claude/key-status))))}]
    ;; everything before the first sync, in the background
    ["/history"      {:post (fn [_] (json-response 202 (jobs/run-history! ds)))}]
    ;; the language of the dashboard, the server's messages and what Claude writes
    ["/lang"        {:get (fn [_] (ok {:lang @i18n/lang :languages i18n/languages
                                          :instance (paths/instance-label)}))
                     :put (guarded (fn [req]
                                     (let [lang (:lang (body req))]
                                       (when-not (i18n/language-ids lang)
                                         (throw (ex-info (str "Unknown language: " lang) {})))
                                       (db/set-state! ds :lang lang)
                                       (reset! i18n/lang lang)
                                       ;; with "selected language only", fill in the new language
                                       (claude/translate-soon!)
                                       (ok {:lang lang}))))}]
    ["/sync"        {:get  (fn [_] (ok (assoc @jobs/sync-state :auth (auth-status ds) :claude (claude-status ds)
                                          :coros (jobs/coros-status ds)
                                          :admin (nil? (paths/instance)) :admin_name (paths/main-name)
                                          :started_at started-at ; the page notices a restart by this
                                          :update (coach-app/update-status)
                                          :translating (translate/running?)
                                          :setup (setup ds) :history (sync/history-status ds))))
                     :post (fn [_] (json-response 202 (assoc (jobs/run-sync! ds) :auth (auth-status ds))))}]
    ["/backup"
     ["" {:get  (fn [_] (ok (backup/status)))
          :post (fn [_] (backup/create! ds) (ok (backup/status)))}]
     ;; restoring replaces the data folder's files, so it happens on a restart
     ["/restore" {:post (fn [req]
                          (or (busy-response)
                              (let [n (:name (body req))]
                                (if-let [{:keys [path]} (some #(when (= n (:name %)) %) (backup/backups))]
                                  (do (coach-app/set-pending! {:restore (str (.getAbsoluteFile (io/file path)))})
                                      (restart-soon!)
                                      (ok {:restarting true}))
                                  (json-response 404 {:error (i18n/t "That backup doesn't exist any more.")})))))}]
     ;; a backup kept elsewhere, sent as the request body
     ["/restore-upload" {:post (fn [req]
                                 (or (busy-response)
                                     (let [f (io/file (paths/data-file "restore-upload.zip"))]
                                       (with-open [in ^java.io.InputStream (:body req)] (io/copy in f))
                                       (if (backup/backup-file? f)
                                         (do (coach-app/set-pending! {:restore (str f) :delete-after true})
                                             (restart-soon!)
                                             (ok {:restarting true}))
                                         (let [msg (i18n/t "That file isn't a Garmin Coach backup.")]
                                           (.delete f)
                                           (json-response 400 {:error msg}))))))}]
     ["/download" {:get (token-guard
                         (fn [_]
                          ;; a fresh backup, kept in data/backups like the others
                          (let [{:keys [name path]} (backup/create! ds)]
                            {:status  200
                             :headers {"Content-Type" "application/zip"
                                       "Content-Disposition" (str "attachment; filename=\"" name "\"")}
                             :body    (io/file path)})))}]]]])

(defn- index
  "The page, with the app version on its script and stylesheet addresses: a
  browser that kept an older version's files fetches the new ones after an
  update."
  [_]
  (if-let [r (io/resource "public/index.html")]
    (let [v (java.net.URLEncoder/encode (coach-app/version) "UTF-8")]
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (-> (slurp r)
                 (str/replace "\"/js/main.js\"" (str "\"/js/main.js?v=" v "\""))
                 (str/replace "\"/css/app.css\"" (str "\"/css/app.css?v=" v "\"")))})
    {:status 404 :body "index.html missing"}))

(defn- wrap-revalidate
  "The page and its files are checked with the server on every load (a 304
  when unchanged), so an updated app is never shown with yesterday's JS."
  [handler]
  (fn [req]
    (let [r (handler req)]
      (cond-> r
        (and r (not (str/starts-with? (:uri req "") "/api/")))
        (assoc-in [:headers "Cache-Control"] "no-cache")))))

(defn- wrap-athlete
  "Everything a request does is done for the athlete this server belongs to
  (one app can serve several athletes, each on its own port)."
  [handler]
  (let [athlete (context/athlete)]
    (fn [req] (context/with-athlete athlete #(handler req)))))

(defn app [ds]
  (-> (ring/ring-handler
       (ring/router (conj (routes ds) ["/" {:get index}]))
       (ring/routes
        (ring/create-resource-handler {:path "/" :root "public"})
        (ring/create-default-handler
         {:not-found (constantly (json-response 404 {:error "not found"}))})))
      wrap-not-modified
      wrap-revalidate
      wrap-gzip
      wrap-errors
      wrap-params
      wrap-local-only
      wrap-athlete))

(defonce server (context/per-athlete-atom nil))
(defonce scheduler (context/per-athlete-atom nil))
(defonce batch-poller (context/per-athlete-atom nil))
(defonce ^{:doc "The athletes this app started (nil: the first), for stop!."} started-athletes (atom []))

(defn- start-batch-poller!
  "Collects half-price batch results every minute (and right away, for
  results that came in while the dashboard was off)."
  [ds]
  (let [ex (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay ex (context/athlete-fn
                                 #(try (let [n (claude/poll-batches! ds)]
                                         (when (pos? n) (println "Collected" n "batch result(s)")))
                                       (catch Throwable e (println "Batch check failed:" (ex-message e)))))
                             0 60 java.util.concurrent.TimeUnit/SECONDS)
    (reset! batch-poller ex)))

(defonce update-checker (atom nil))

(declare log-stream log-to-file!)

(defn- start-update-checker!
  "Twice a day: the installed app looks for a newer release, and a log file
  is trimmed."
  []
  (let [ex (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate ex #(coach-app/check-for-update!) 0 12
                          java.util.concurrent.TimeUnit/HOURS) ; installed app only
    ;; app.log stays open while the app runs: reopen it, trimmed
    (.scheduleAtFixedRate ex #(when @log-stream (try (log-to-file!) (catch Throwable _)))
                          12 12 java.util.concurrent.TimeUnit/HOURS)
    (reset! update-checker ex)))

(def sync-every-hours 2)

(defn- due-for-sync? []
  (let [hour (.getHour (java.time.LocalTime/now))
        last (some-> (:finished_at @jobs/sync-state) java.time.Instant/parse)]
    (and (<= 6 hour 21)
         (not (:running @jobs/sync-state))
         (or (nil? last)
             (.isBefore last (.minus (java.time.Instant/now) (java.time.Duration/ofHours sync-every-hours)))))))

(defn- start-background-sync!
  "Checks every 15 minutes; syncs when the last sync is over 2 h old (06-22 h)."
  [ds]
  (let [ex (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate ex (context/athlete-fn #(try (when (due-for-sync?) (jobs/run-sync! ds)) (catch Throwable _)))
                          15 15 java.util.concurrent.TimeUnit/MINUTES)
    (reset! scheduler ex)))

(defn start!
  "Starts the API on 127.0.0.1:`port`. With :sync? true (the default) an
  incremental sync runs in the background on startup."
  ([] (start! {}))
  ([{:keys [port sync?] :or {port 8080 sync? true}}]
   (reset! current-port port)
   (reset! syncing? sync?)
   (let [ds (doto (db/datasource) db/migrate!)]
     (token/ensure!) ; the loopback token exists before the first page load
     (reset! server (jetty/run-jetty (app ds) {:port port :host "127.0.0.1" :join? false :max-threads 16}))
     (println (str "Dashboard on http://127.0.0.1:" port "/"))
     (println (auth/describe (auth/status ds)))
     (jobs/load-sync-state! ds)
     (costs/use-db! ds)
     (ai/use-db! ds)
     (translate/use-db! ds)
     (let [lang (db/get-state ds :lang)]
       (reset! i18n/lang (if (i18n/language-ids lang) lang "en")))
     ;; Claude's older texts, and any translation that failed, in every language
     (when (claude/api-key?)
       (claude/translate-soon!)
       ;; blocks for sessions planned before sessions had them (once)
       (planner/structure-once! ds))
     (let [n (costs/import-earlier! ds)]
       (when (pos? n) (println "Imported" n "earlier Claude requests into the cost log")))
     ;; heatmap tracks for activities synced before the Map tab existed (once)
     (future (try (let [n (analysis/ensure-tracks! ds)]
                    (when (pos? n) (println "Heatmap tracks computed for" n "activities")))
                  (catch Throwable e (println "Heatmap tracks failed:" (ex-message e)))))
     ;; peaks for activities from before peak bagging existed (and any tiles still missing)
     (jobs/peaks-soon! ds)
     (start-batch-poller! ds)
     (when-not @update-checker (start-update-checker!)) ; once, however many athletes
     (swap! started-athletes #(vec (distinct (conj % (context/athlete)))))
     (when sync?
       (jobs/run-sync! ds)
       (start-background-sync! ds))
     @server)))

(defn- stop-athlete! []
  (when-let [ex @scheduler] (.shutdownNow ex) (reset! scheduler nil))
  (when-let [ex @batch-poller] (.shutdownNow ex) (reset! batch-poller nil))
  (when-let [s @server] (.stop s) (reset! server nil)))

(defn stop!
  "Stops the dashboard of every athlete this app started."
  []
  (when-let [ex @update-checker] (.shutdownNow ex) (reset! update-checker nil))
  (doseq [a @started-athletes] (context/with-athlete a stop-athlete!))
  (stop-athlete!) ; started without start! (tests)
  (reset! started-athletes []))

(defn- open-browser!
  "Opens `url` in the default browser."
  [url]
  (try
    (.start (ProcessBuilder. ^java.util.List
                             (cond (paths/windows?) ["rundll32" "url.dll,FileProtocolHandler" url]
                                   (str/includes? (str/lower-case (System/getProperty "os.name")) "mac") ["open" url]
                                   :else ["xdg-open" url])))
    (catch Exception e (println "Couldn't open the browser:" (ex-message e) "- open" url))))

(defn- running?
  "Whether the dashboard already answers on `port`."
  [port]
  (try
    (let [url (java.net.URL. (str "http://127.0.0.1:" port "/api/lang"))
          c   (doto ^java.net.HttpURLConnection (.openConnection url)
                (.setConnectTimeout 1000) (.setReadTimeout 3000))]
      (= 200 (.getResponseCode c)))
    (catch Exception _ false)))

(defonce ^:private log-stream (atom nil))

(defn- log-to-file!
  "Started from a shortcut there is no terminal: output goes to app.log in
  the data folder. Called again when the data folder changes."
  []
  (when-let [old @log-stream] (.close ^java.io.PrintStream old)) ; closed first: Windows can't trim an open file
  (let [f  (io/file (paths/data-file "app.log"))
        _  (io/make-parents f)
        _  (paths/trim-log! f)
        ps (java.io.PrintStream. (java.io.FileOutputStream. f true) true "UTF-8")
        ;; a server with several athletes logs in one file: their lines say whose they are
        w  (java.io.PrintWriter.
            (context/prefixing-writer (java.io.OutputStreamWriter. ps java.nio.charset.StandardCharsets/UTF_8))
            true)]
    (System/setOut ps)
    (System/setErr ps)
    (alter-var-root #'*out* (constantly w))
    (alter-var-root #'*err* (constantly w))
    (reset! log-stream ps)
    (println (str "\n--- " (java.time.LocalDateTime/now) " " (coach-app/version) " ---"))))

(defn- start-app!
  "Takes the data folder (one app per folder), does what the last run left
  pending (restore, move the data), then starts the dashboard."
  [opts log?]
  (when-not (paths/lock-data-dir! 30000)
    (throw (ex-info (str "The data folder " (paths/data-dir) " is in use by another Garmin Coach. Quit that one first.")
                    {})))
  (coach-app/apply-pending! (fn [f] (backup/restore! (doto (db/datasource) db/migrate!) f))
                            {:after-switch (when log? log-to-file!)})
  (start! opts))

(defn- start-athletes!
  "One app for all the athletes of athletes.edn (coach.paths/server-athletes):
  each gets its own port, data folder, database and jobs. An athlete that
  can't start is reported and the others go on. Returns how many started."
  [athletes opts]
  (count (filter true?
                 (for [{:keys [name port]} athletes]
                   (context/with-athlete
                     name
                     #(try (start-app! (assoc opts :port port) (nil? name))
                           true
                           (catch Throwable e
                             (println "Couldn't start" (or name "the first athlete") "-" (ex-message e))
                             false)))))))

(defn -main
  "clojure -M -m coach.api [port] [--no-sync] [--open | --background | --server]

  --open is what the app shortcut runs: it starts the dashboard unless it is
  already running, logs to app.log in the data folder and opens it in the
  browser. --background does the same without the browser (starting with
  the computer, restarts). --server (or COACH_SERVER=1) is the server
  install under systemd (docs/server.md): logs to app.log, and exits on a
  failed start or a restart, for systemd to start it again. With an
  athletes.edn in the app folder (and no $COACH_INSTANCE) it serves all
  those athletes, each on their own port, from this one process."
  [& args]
  (when (some #{"--server"} args) (paths/use-server-mode! true))
  (let [port (or (some parse-long args) 8080)
        url  (str "http://127.0.0.1:" port "/")
        bg?  (some #{"--background"} args)
        shortcut? (or bg? (some #{"--open"} args))
        open! #(when-not bg? (open-browser! url))
        opts {:port port :sync? (not (some #{"--no-sync"} args))}]
    (cond
      (paths/server?)
      (do (log-to-file!)
          (println "Server mode; allowed hosts:" (str/join ", " (sort (paths/allowed-hosts))))
          (try (if-let [athletes (and (nil? (paths/instance)) (paths/server-athletes))]
                 (when (zero? (start-athletes! athletes opts))
                   (throw (ex-info "No athlete could start." {})))
                 (start-app! opts true))
               (catch Throwable e
                 (println "Couldn't start:" (ex-message e))
                 (System/exit 1))))

      (not shortcut?)
      (start-app! opts false)

      :else
      (if (running? port)
        (do (open!) (System/exit 0))
        (do (log-to-file!)
            (try (start-app! opts true)
                 (catch Throwable e
                   ;; e.g. a second click while the first is still starting
                   (println "Couldn't start:" (ex-message e))
                   (if (some (fn [_] (Thread/sleep 1000) (running? port)) (range 60))
                     (do (open!) (System/exit 0))
                     (System/exit 1))))
            (open!))))))
