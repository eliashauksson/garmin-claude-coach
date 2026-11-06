(ns coach.api
  "Local JSON API for the dashboard. Binds to 127.0.0.1 only."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.adapt :as adapt]
            [coach.ai-settings :as ai]
            [coach.analysis :as analysis]
            [coach.app :as coach-app]
            [coach.athlete :as athlete]
            [coach.athlete-chat :as athlete-chat]
            [coach.auth :as auth]
            [coach.backup :as backup]
            [coach.claude :as claude]
            [coach.coros-auth :as coros-auth]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.load-model :as load-model]
            [coach.overview :as overview]
            [coach.paths :as paths]
            [coach.plan :as plan]
            [coach.planner :as planner]
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

;; ---------------------------------------------------------------------------
;; Sync runs in the background; one at a time.

(defonce sync-state (atom {:running false}))

(defn- coros-last-sync [ds]
  (db/state-json ds :coros_last_sync))

(defn- coros-paused?
  "Coros refused for its daily FIT file limit and the next day hasn't come:
  asking again would only be refused (and may count against the limit)."
  [{:keys [kind paused_until]}]
  (and (= "rate-limit" (some-> kind name)) (string? paused_until)
       (neg? (compare (season/today) paused_until))))

(defn- run-coros!
  "Syncs the Coros rides. {:ok true :new n} or {:ok false :kind :message}; a
  daily-limit refusal adds :remaining and :paused_until (tomorrow), and until
  then only `force` (the Coros card's Sync now) asks Coros again."
  [ds & [{:keys [force]}]]
  (let [prev (coros-last-sync ds)]
    (if (and (not force) (coros-paused? prev))
      (do (println "Coros: daily file limit reached, waiting until" (:paused_until prev)) prev)
      (let [r    (try (merge {:ok true} (sync/coros! ds))
                      (catch Throwable e
                        (println "Coros sync failed:" (ex-message e))
                        (merge {:ok false} (sync/failure e) (select-keys (ex-data e) [:remaining]))))
            r    (cond-> r
                   (= :rate-limit (:kind r)) (assoc :paused_until (season/add-days (season/today) 1)))
            done (str (java.time.Instant/now))
            r    (assoc r :finished_at done :last_ok (if (:ok r) done (:last_ok prev)))]
        (try (db/set-state! ds :coros_last_sync (json/write-str r)) (catch Throwable _))
        ;; new rides with power refine the power calibration (coach.epoc)
        (when (and (:ok r) (pos? (or (:new r) 0)))
          (try (load-model/calibrate-power! ds)
               (catch Throwable e (println "Power calibration failed:" (ex-message e)))))
        r))))

(defn- coros-status
  "For Settings: the login and the last Coros sync."
  [ds]
  (assoc (coros-auth/status)
         :last_sync (coros-last-sync ds)))

(defn- run-job!
  "Runs `f` with a Garmin source in the background unless a sync or history
  load is already running (one Garmin session at a time), and records the
  result like a sync. `job` names it (\"sync\" or \"history\"); `after` runs
  on success and may add to the result. Nothing starts before Garmin is
  connected. Returns the state."
  [ds job f & [after]]
  (if-not (auth/connected?)
    @sync-state
    (let [[old _] (swap-vals! sync-state #(if (:running %) % (assoc % :running true :job job
                                                                      :started_at (str (java.time.Instant/now)))))]
      (when-not (:running old)
        (future
          ;; first backup of the day, before anything is written
          (try (backup/daily! ds)
               (catch Throwable e (println "Daily backup failed:" (ex-message e))))
          (let [result (try
                         (let [src ((requiring-resolve 'coach.garmin-mcp/start!))]
                           (try (f src)
                                (finally ((requiring-resolve 'coach.garmin-mcp/stop!) src))))
                         {:ok true}
                         (catch Throwable e
                           (println (str job " failed:") (ex-message e))
                           (merge {:ok false} (sync/failure e))))
                ;; rides from a Coros bike computer: on their own, so a Coros
                ;; problem never fails the Garmin sync (and the other way round)
                result (cond-> result (coros-auth/connected?) (assoc :coros (run-coros! ds)))
                result (if (and (:ok result) after) (after result) result)
                done   (str (java.time.Instant/now))
                result (assoc result :finished_at done)]
            (try (sync/record-result! ds result) (catch Throwable _))
            (swap! sync-state assoc :running false :job nil :finished_at done :last_result result
                   :last_ok (if (:ok result) done (:last_ok @sync-state))))))
      @sync-state)))

(defn- run-coros-sync!
  "Starts a sync of the Coros rides alone (job \"coros\"), unless a sync is
  running or Coros isn't connected. Needs no Garmin login. `force`: ask Coros
  even while paused for its daily limit. Returns the state."
  [ds & [force]]
  (if-not (coros-auth/connected?)
    @sync-state
    (let [[old _] (swap-vals! sync-state #(if (:running %) % (assoc % :running true :job "coros"
                                                                      :started_at (str (java.time.Instant/now)))))]
      (when-not (:running old)
        (future
          (try (backup/daily! ds)
               (catch Throwable e (println "Daily backup failed:" (ex-message e))))
          (let [r (try (run-coros! ds {:force force})
                       (catch Throwable e {:ok false :message (ex-message e)}))]
            (swap! sync-state assoc :running false :job nil
                   :finished_at (or (:finished_at r) (str (java.time.Instant/now)))))))
      @sync-state)))

(defn- run-sync!
  "Starts an incremental sync unless one is running. Returns the state.
  Without a Garmin login only the Coros rides sync (when connected)."
  [ds]
  (if (and (not (auth/connected?)) (coros-auth/connected?))
    (run-coros-sync! ds)
    (run-job! ds "sync" #(sync/incremental! % ds)
              ;; new data may call for adapting the plan; a failure here isn't a sync failure.
              ;; Without credits every Claude request would fail, so the check waits.
              (fn [result]
                ;; the readiness estimate learns from Garmin's newest scores
                (try (readiness-estimate/refit! ds (season/today))
                     (catch Throwable e (println "Readiness refit failed:" (ex-message e))))
                ;; our training load (rides Garmin never saw) learns from Garmin's newest loads
                (try (load-model/update! ds)
                     (catch Throwable e (println "Training load refit failed:" (ex-message e))))
                (cond
                  (costs/credits-out ds) (assoc result :plan_check_skipped "no Claude credits")
                  :else (try (assoc result :proposals (:proposed (planner/check! ds)))
                             (catch Throwable e (assoc result :plan_check_error (ex-message e)))))))))

(defn- run-history!
  "Loads everything before the first sync (coach.sync/load-history!)."
  [ds]
  (run-job! ds "history" #(sync/load-history! % ds)))

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

(defn- ok-tr
  "ok, with Claude's texts in the current dashboard language (coach.translate)."
  [ds body]
  (ok (translate/localize ds @i18n/lang body)))

(defn- athlete-tr
  "The athlete status with its overview (Claude's) in the current language;
  athlete.md itself is never translated."
  [ds status]
  (update status :summary #(when % (translate/localize ds @i18n/lang %))))

(defn- days-ago [n] (season/add-days (season/today) (- n)))

(defn- date-range
  "from/to query params with a default window ending today."
  [params default-days]
  [(get params "from" (days-ago default-days)) (get params "to" (season/today))])

(defn- auth-status [ds] (auth/status ds))

(defn- claude-status
  "For the banner on every page: are the Claude credits out?"
  [ds]
  (when-let [out (costs/credits-out ds)]
    (assoc out :credits_out true :billing_url claude/billing-url :message (claude/no-credits-message))))

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

(defn- body [req]
  (some-> (:body req) slurp not-empty (json/read-str :key-fn keyword)))

;; ---------------------------------------------------------------------------
;; A loopback token for the two routes a mere local process must not reach:
;; the bulk data export and the self-update. wrap-local-only already stops
;; other *websites*; the token additionally stops header-less local clients
;; (curl, a sandboxed process) that can't read this user's files. The page
;; gets the token from GET /api/token, which answers only a same-origin fetch.

(defn- request-token [req]
  (or (get-in req [:headers "x-coach-token"])
      (get (:query-params req) "token")))

(defn- same-origin-fetch?
  "A fetch the dashboard page itself made. Browsers set Sec-Fetch-Site on
  every request; curl and other scripts don't, so they can't read the token."
  [req]
  (= "same-origin" (get-in req [:headers "sec-fetch-site"])))

(defn- token-guard
  "Wraps a handler so it answers only when the request carries the token."
  [handler]
  (fn [req]
    (if (token/valid? (request-token req))
      (handler req)
      (json-response 403 {:error (i18n/t "This action needs the dashboard token. Reload the page and try again.")}))))

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
  (let [t    (season/today)
        days (analysis/wellness ds (season/add-days t -1) t)
        ;; no score from Garmin this morning: the app's estimate stands in
        est  (when (and (seq days) (nil? (:readiness (last days)))) (readiness-estimate/for-date ds t))
        days (cond-> (vec days)
               est (update (dec (count days)) assoc :readiness (:score est) :readiness_estimated true))
        ws   (filter #(= "planned" (:status %)) (plan/workouts ds t t))]
    (assoc (readiness/check days ws (athlete/settings))
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
   ["/workouts/:id" {:get    (fn [{:keys [path-params]}]
                               (if-let [w (plan/workout ds (parse-long (:id path-params)))]
                                 (ok-tr ds w)
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
(defonce current-port (atom 8080))
(defonce syncing? (atom true)) ; false with --no-sync; kept on a restart

(defn- busy-response
  "Restoring or moving the data waits for a running sync or history load."
  []
  (when (:running @sync-state)
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
   :update           (coach-app/update-status)
   :notice           (coach-app/notice-message)})

(declare host-name)

(defn coros-callback-url
  "Where Coros sends the browser back to after its sign-in: the address this
  page was opened at when that is an allowed host (a phone on a server's
  Tailscale address), else 127.0.0.1. Behind `tailscale serve` the Host has
  no port and the page is HTTPS; a proxy's X-Forwarded-Proto wins."
  [{:keys [headers server-port]}]
  (let [host (some-> (get headers "host") str/trim str/lower-case)
        name (host-name host)]
    (if (and name ((paths/allowed-hosts) name))
      (let [proto (or (some-> (get headers "x-forwarded-proto") (str/split #",") first str/trim not-empty)
                      (if (= name host) "https" "http"))]
        (str proto "://" host "/api/coros/callback"))
      (str "http://127.0.0.1:" server-port "/api/coros/callback"))))

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
   ["/data-dir" {:post (fn [req]
                         (or (busy-response)
                             (let [{:keys [path use_existing]} (body req)
                                   {:keys [existing to error] :as r} (coach-app/check-data-dir path)]
                               (cond
                                 error (json-response 400 {:error error})
                                 (and existing (not use_existing)) (ok r)
                                 :else (do (coach-app/set-pending! (if existing {:use-data to} {:move-data to}))
                                           (restart-soon!)
                                           (ok {:restarting true}))))))}]])

(defn routes [ds]
  [["/api"
    ["/day/:date"   {:get (guarded
                           (fn [{:keys [path-params]}]
                             (let [date (:date path-params)]
                               (when-not (re-matches #"\d{4}-\d{2}-\d{2}" date)
                                 (throw (ex-info (str "Not a date: " date) {})))
                               (ok-tr ds (cond-> (assoc (analysis/day-summary ds date)
                                                  :season (plan/week-info ds date)
                                                  :planned (plan/workouts ds date date)
                                                  ;; the app's own score when Garmin has none
                                                  :readiness_estimate (readiness-estimate/for-date ds date))
                                     ;; suggestions for today's sessions, from this morning's readiness
                                     (= date (season/today)) (assoc :readiness_check (readiness-today ds)))))))}]
    ;; the dashboard's coach overview (coach-dashboard-spec.md)
    ["/overview"    {:get (guarded
                           (fn [{:keys [query-params]}]
                             (let [date (get query-params "date" (season/today))]
                               (when-not (re-matches #"\d{4}-\d{2}-\d{2}" date)
                                 (throw (ex-info (str "Not a date: " date) {})))
                               ;; the goal time and race legs come from the athlete summary:
                               ;; an older summary without them is rewritten in the background
                               (let [job (when (claude/api-key?) (athlete-chat/ensure-summary!))]
                                 (ok (cond-> (overview/overview ds date)
                                       (:summarising job) (assoc :summarising true)))))))}]
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
                                  (when (= "ok" (:status r)) (run-sync! ds))
                                  (ok r)))
                      :delete (fn [_] (auth/cancel-login!) (ok {:status "cancelled"}))}]
    ["/garmin/code"  {:post (fn [req]
                              (let [r (auth/submit-code! ds (:code (body req)))]
                                (when (= "ok" (:status r)) (run-sync! ds))
                                (ok r)))}]
    ;; Coros: sign in on Coros's own page, which sends the browser back to
    ;; /coros/callback; a new login starts a sync
    ["/coros/login"    {:post   (fn [req]
                                  (ok (coros-auth/start-login! (coros-callback-url req))))
                        :delete (fn [_] (ok (coros-auth/disconnect!)))}]
    ["/coros/sync"     {:post (fn [_] (json-response 202 (run-coros-sync! ds true)))}]
    ["/coros/callback" {:get (fn [{:keys [query-params]}]
                               (try (coros-auth/finish-login! (update-keys query-params keyword))
                                    (when @syncing? (run-sync! ds))
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
                            (let [{:strs [sport from to limit]} query-params]
                              (ok (analysis/activities ds {:sport sport :from from :to to
                                                           :limit (some-> limit parse-long)}))))}]
    ;; every GPS track, for the Map tab's heatmap
    ["/heatmap"     {:get (fn [_] (ok (analysis/heatmap ds)))}]
    ;; recent activities waiting for "How did it go?"
    ["/feedback/due"
     {:get (fn [{:keys [query-params]}] (ok (analysis/feedback-due ds (get query-params "date" (season/today)))))}]
    ["/activities/:id/feedback"
     {:put (guarded (fn [{:keys [path-params] :as req}]
                      (analysis/save-feedback! ds (parse-long (:id path-params)) (body req))
                      (ok (analysis/activity ds (parse-long (:id path-params))))))}]
    ["/activities/:id"
     {:get (fn [{:keys [path-params query-params]}]
             (let [id (parse-long (:id path-params))]
               (if-let [a (analysis/activity ds id)]
                 (ok (cond-> a
                       ;; with the watch's all-day heart rate where the activity has none
                       (= "true" (get query-params "streams")) (assoc :streams (first (analysis/stream-with-hr ds a)))
                       ;; the planned session it fulfilled, in the current language
                       true (assoc :planned (some->> (plan/planned-for-activity ds a)
                                                     (translate/localize ds @i18n/lang)))))
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
    ;; wrapped, so "no ride with power" (nil) isn't mistaken for "not loaded yet"
    ["/cycling-power" {:get (fn [_] (ok {:power (analysis/cycling-power ds (season/today))}))}]
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
    ;; the Claude API key: saved in ~/.config/coach/anthropic-key, never sent back
    ["/claude/key"   {:get    (fn [_] (ok (claude/key-status)))
                      :put    (guarded (fn [req] (ok (claude-key-saved! ds (:key (body req))))))
                      :delete (fn [_] (ok (claude/remove-key!)))}]
    ["/claude/test"  {:post (fn [_] (ok (assoc (claude-check! ds) :key (claude/key-status))))}]
    ;; everything before the first sync, in the background
    ["/history"      {:post (fn [_] (json-response 202 (run-history! ds)))}]
    ;; the language of the dashboard, the server's messages and what Claude writes
    ["/lang"        {:get (fn [_] (ok {:lang @i18n/lang :languages i18n/languages}))
                     :put (guarded (fn [req]
                                     (let [lang (:lang (body req))]
                                       (when-not (i18n/language-ids lang)
                                         (throw (ex-info (str "Unknown language: " lang) {})))
                                       (db/set-state! ds :lang lang)
                                       (reset! i18n/lang lang)
                                       ;; with "selected language only", fill in the new language
                                       (claude/translate-soon!)
                                       (ok {:lang lang}))))}]
    ["/sync"        {:get  (fn [_] (ok (assoc @sync-state :auth (auth-status ds) :claude (claude-status ds)
                                          :coros (coros-status ds)
                                          :started_at started-at ; the page notices a restart by this
                                          :update (coach-app/update-status)
                                          :translating (translate/running?)
                                          :setup (setup ds) :history (sync/history-status ds))))
                     :post (fn [_] (json-response 202 (assoc (run-sync! ds) :auth (auth-status ds))))}]
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

(defn- wrap-errors
  "Unexpected exceptions become a JSON 500 with the message (and a log line),
  so the page can say what went wrong instead of \"HTTP 500\"."
  [handler]
  (fn [req]
    (try (handler req)
         (catch Throwable e
           (println "Error on" (:request-method req) (:uri req) "-" (str e))
           (json-response 500 {:error (str "Server error: " (or (ex-message e) (str (class e))))})))))

(def ^:private loopback-hosts #{"127.0.0.1" "localhost" "[::1]"})

(defn- local-hosts
  "This computer's names, plus the host names allowed to open the dashboard
  on a server (coach.paths/allowed-hosts, e.g. a Tailscale address)."
  []
  (into loopback-hosts (paths/allowed-hosts)))

(defn- host-name
  "\"127.0.0.1\" from a Host header like \"127.0.0.1:8080\"; also for [::1]:8080."
  [h]
  (when h (second (re-matches #"(?i)(\[[^\]]*\]|[^:]*)(?::\d+)?" (str/trim h)))))

(defn- local-origin? [origin]
  (boolean (some->> origin (re-matches #"(?i)https?://([^/]+)") second host-name str/lower-case ((local-hosts)))))

(defn foreign-request?
  "Pure: whether a request comes from another website rather than this page.
  The server listens on 127.0.0.1 only (on a server, a proxy such as
  `tailscale serve` passes the allowed host names on), but any website open in the browser
  can still send requests there, or rename itself to 127.0.0.1 (DNS
  rebinding) to read the answers. So:
  - the Host must be this computer or an allowed host (a rebound site still
    sends its own name);
  - the browser's Sec-Fetch-Site must not say another site sent it;
  - a changing request with an Origin must come from this computer.
  Requests without those headers (curl, the installer) are fine.
  Two exceptions to the second rule:
  - opening the page itself (a top-level page load, not /api): a phone's
    home-screen shortcut or a link elsewhere sends \"cross-site\". The other
    site can't read the page or frame it, and the page's own requests are
    same-origin again;
  - Coros's sign-in page sending the browser back to /api/coros/callback.
    That GET only completes a login this app started, with the one-time state
    it made up (coach.coros-auth), and answers with nothing but a redirect."
  [{:keys [headers request-method uri]}]
  (let [host   (get headers "host")
        site   (get headers "sec-fetch-site")
        origin (get headers "origin")
        get?   (#{:get :head} request-method)
        page?  (and get?
                    (= "navigate" (get headers "sec-fetch-mode"))
                    (= "document" (get headers "sec-fetch-dest"))
                    (not (str/starts-with? (or uri "") "/api/")))]
    (boolean
     (or (and host (not ((local-hosts) (some-> (host-name host) str/lower-case))))
         (and (#{"cross-site" "same-site"} site)
              (not page?)
              (not (and (= :get request-method) (= "/api/coros/callback" uri))))
         (and origin (not (#{:get :head} request-method)) (not (local-origin? origin)))))))

(defn- wrap-local-only
  "Refuses requests from other websites (see foreign-request?)."
  [handler]
  (fn [req]
    (if (foreign-request? req)
      (do (println "Refused a request from another website:" (:request-method req) (:uri req)
                   (get-in req [:headers "origin"]) (get-in req [:headers "host"])
                   (get-in req [:headers "sec-fetch-site"]))
          (json-response 403 {:error "Requests from other websites are not allowed."}))
      (handler req))))

(defn- wrap-revalidate
  "The page and its files are checked with the server on every load (a 304
  when unchanged), so an updated app is never shown with yesterday's JS."
  [handler]
  (fn [req]
    (let [r (handler req)]
      (cond-> r
        (and r (not (str/starts-with? (:uri req "") "/api/")))
        (assoc-in [:headers "Cache-Control"] "no-cache")))))

(defn app [ds]
  (-> (ring/ring-handler
       (ring/router (conj (routes ds) ["/" {:get index}]))
       (ring/routes
        (ring/create-resource-handler {:path "/" :root "public"})
        (ring/create-default-handler
         {:not-found (constantly (json-response 404 {:error "not found"}))})))
      wrap-not-modified
      wrap-revalidate
      wrap-errors
      wrap-params
      wrap-local-only))

(defonce server (atom nil))
(defonce scheduler (atom nil))
(defonce batch-poller (atom nil))

(defn- start-batch-poller!
  "Collects half-price batch results every minute (and right away, for
  results that came in while the dashboard was off)."
  [ds]
  (let [ex (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay ex #(try (let [n (claude/poll-batches! ds)]
                                        (when (pos? n) (println "Collected" n "batch result(s)")))
                                      (catch Throwable e (println "Batch check failed:" (ex-message e))))
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
   (reset! current-port port)
   (reset! syncing? sync?)
   (let [ds (doto (db/datasource) db/migrate!)]
     (token/ensure!) ; the loopback token exists before the first page load
     (reset! server (jetty/run-jetty (app ds) {:port port :host "127.0.0.1" :join? false}))
     (println (str "Dashboard on http://127.0.0.1:" port "/"))
     (println (auth/describe (auth/status ds)))
     (load-sync-state! ds)
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
     (start-batch-poller! ds)
     (start-update-checker!)
     (when sync?
       (run-sync! ds)
       (start-background-sync! ds))
     @server)))

(defn stop! []
  (when-let [ex @update-checker] (.shutdownNow ex) (reset! update-checker nil))
  (when-let [ex @scheduler] (.shutdownNow ex) (reset! scheduler nil))
  (when-let [ex @batch-poller] (.shutdownNow ex) (reset! batch-poller nil))
  (when-let [s @server] (.stop s) (reset! server nil)))

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
        w  (java.io.PrintWriter. (java.io.OutputStreamWriter. ps java.nio.charset.StandardCharsets/UTF_8) true)]
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

(defn -main
  "clojure -M -m coach.api [port] [--no-sync] [--open | --background | --server]

  --open is what the app shortcut runs: it starts the dashboard unless it is
  already running, logs to app.log in the data folder and opens it in the
  browser. --background does the same without the browser (starting with
  the computer, restarts). --server (or COACH_SERVER=1) is the server
  install under systemd (docs/server.md): logs to app.log, and exits on a
  failed start or a restart, for systemd to start it again."
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
          (try (start-app! opts true)
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
