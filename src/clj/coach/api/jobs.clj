(ns coach.api.jobs
  "The sync, Coros and history jobs the API runs in the background: one at a
  time per athlete, taking turns with the other athletes on a server. Their
  state (`sync-state`) is what /api/sync shows."
  (:require [clojure.data.json :as json]
            [coach.auth :as auth]
            [coach.backup :as backup]
            [coach.context :as context]
            [coach.coros-auth :as coros-auth]
            [coach.costs :as costs]
            [coach.db :as db]
            [coach.load-model :as load-model]
            [coach.paths :as paths]
            [coach.peaks :as peaks]
            [coach.planner :as planner]
            [coach.readiness-estimate :as readiness-estimate]
            [coach.season :as season]
            [coach.sync :as sync]
            [coach.sync.sources :as sources]))

(defonce sync-state (context/per-athlete-atom {:running false}))

(defn coros-last-sync [ds]
  (db/state-json ds :coros_last_sync))

(defn coros-paused?
  "Coros refused for its daily FIT file limit and the next day hasn't come:
  asking again would only be refused (and may count against the limit)."
  [{:keys [kind paused_until]}]
  (and (= "rate-limit" (some-> kind name)) (string? paused_until)
       (neg? (compare (season/today) paused_until))))

(defn run-coros!
  "Syncs the Coros rides. {:ok true :new n} or {:ok false :kind :message}; a
  daily-limit refusal adds :remaining and :paused_until (tomorrow), and until
  then only `force` (the Coros card's Sync now) asks Coros again."
  [ds & [{:keys [force]}]]
  (let [prev (coros-last-sync ds)]
    (if (and (not force) (coros-paused? prev))
      (do (println "Coros: daily file limit reached, waiting until" (:paused_until prev)) prev)
      (let [r    (try (merge {:ok true} (sources/coros! ds))
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

(defn coros-status
  "For Settings: the login and the last Coros sync."
  [ds]
  (assoc (coros-auth/status)
         :last_sync (coros-last-sync ds)))

(defn taking-turns
  "Runs `f` when no other athlete on this server is syncing (coach.paths/
  one-at-a-time); meanwhile the state says :waiting. A lock that can't be
  taken (a read-only app folder) doesn't stop the sync."
  [f]
  (let [ran (atom false)
        run #(do (swap! sync-state dissoc :waiting) (reset! ran true) (f))]
    (try (paths/one-at-a-time "sync.lock" #(swap! sync-state assoc :waiting true) run)
         (catch java.io.IOException e
           (if @ran (throw e) (do (println "Sync lock unavailable:" (ex-message e)) (run)))))))

(defn in-background!
  "Runs the sync job `f` in the background, taking turns with the other
  athletes. Should it fail unexpectedly, the sync isn't left \"running\"."
  [f]
  (future
    (try (taking-turns f)
         (catch Throwable e
           (println "Sync job failed:" (ex-message e))
           (swap! sync-state assoc :running false :job nil :waiting false))
         ;; a sync is the app's peak: hand that memory back to the others on the server now, not eventually
         (finally (System/gc)))))

(defn peaks-soon!
  "Peak bagging for the new activities (OSM peaks per tile, prominence, GPS matching) in the
  background: it can take minutes, and the sync is done without it."
  [ds]
  (when (peaks/request!) ; marked at once, so the page sees it running; a running update goes round again
    (future (try (peaks/work! ds)
                 (catch Throwable e (println "Peak bagging failed:" (ex-message e)))))))

(defn run-job!
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
        (in-background!
         (fn []
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
                    :last_ok (if (:ok result) done (:last_ok @sync-state)))))))
      @sync-state)))

(defn run-coros-sync!
  "Starts a sync of the Coros rides alone (job \"coros\"), unless a sync is
  running or Coros isn't connected. Needs no Garmin login. `force`: ask Coros
  even while paused for its daily limit. Returns the state."
  [ds & [force]]
  (if-not (coros-auth/connected?)
    @sync-state
    (let [[old _] (swap-vals! sync-state #(if (:running %) % (assoc % :running true :job "coros"
                                                                      :started_at (str (java.time.Instant/now)))))]
      (when-not (:running old)
        (in-background!
         (fn []
           (try (backup/daily! ds)
                (catch Throwable e (println "Daily backup failed:" (ex-message e))))
           (let [r (try (run-coros! ds {:force force})
                        (catch Throwable e {:ok false :message (ex-message e)}))]
             (swap! sync-state assoc :running false :job nil
                    :finished_at (or (:finished_at r) (str (java.time.Instant/now))))
             (peaks-soon! ds)))))
      @sync-state)))

(defn run-sync!
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
                (peaks-soon! ds)
                (cond
                  (costs/credits-out ds) (assoc result :plan_check_skipped "no Claude credits")
                  (costs/limit-reached ds) (assoc result :plan_check_skipped "Claude spending limit reached")
                  :else (try (assoc result :proposals (:proposed (planner/check! ds)))
                             (catch Throwable e (assoc result :plan_check_error (ex-message e)))))))))

(defn run-history!
  "Loads everything before the first sync (coach.sync/load-history!)."
  [ds]
  (run-job! ds "history" #(sync/load-history! % ds)))

(defn load-sync-state!
  "After a restart: the last recorded result, so a failure is still shown."
  [ds]
  (let [r (sync/last-result ds)]
    (swap! sync-state merge
           {:last_ok (db/get-state ds :last_ok_sync)}
           (when r {:last_result r :finished_at (:finished_at r)}))))
