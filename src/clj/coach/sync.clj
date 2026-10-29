(ns coach.sync
  "Pulls data from a DataSource into the local DB.

  - incremental: from a little before the last synced date up to today, so
    partial days (today, last night's sleep) get refreshed.
  - backfill: walks backwards from the oldest synced date to `epoch` in
    28-day chunks, saving progress after each chunk so it can resume.

  Writes are upserts, so re-running any range is harmless."
  (:require [coach.db :as db]
            [coach.source :as source])
  (:import (java.time LocalDate)))

(def epoch "Earliest date with Garmin data." "2021-03-26")
(def chunk-days 28)
(def refresh-days
  "How many days before the last sync to re-fetch, for late-arriving data."
  2)

(defn- d ^LocalDate [s] (LocalDate/parse s))
(defn- today [] (str (LocalDate/now)))

(defn chunks
  "Splits [start, end] (ISO dates, inclusive) into windows of at most `n` days,
  newest first."
  [start end n]
  (let [s (d start)]
    (loop [e (d end) acc []]
      (if (.isBefore e s)
        acc
        (let [cs (let [c (.minusDays e (dec n))] (if (.isBefore c s) s c))]
          (recur (.minusDays cs 1) (conj acc [(str cs) (str e)])))))))

(defn sync-range!
  "Fetches and stores everything in one window. Returns counts."
  [src ds start end]
  (let [t0   (System/currentTimeMillis)
        acts (source/fetch-activities src start end)
        well (source/fetch-wellness src start end)]
    (db/upsert-activities! ds acts)
    (db/upsert-wellness! ds well)
    (println (format "  %s → %s  %3d activities  %3d days  (%.1fs)"
                     start end (count acts) (count well)
                     (/ (- (System/currentTimeMillis) t0) 1000.0)))
    {:activities (count acts) :days (count well)}))

(defn- sync-chunks! [src ds start end]
  (doseq [[s e] (chunks start end chunk-days)]
    (sync-range! src ds s e)))

(defn incremental!
  "Syncs from shortly before the last synced date up to today."
  [src ds]
  (let [t    (today)
        last (db/get-state ds :synced_through)
        from (if last
               (str (.minusDays (d last) refresh-days))
               (str (.minusDays (d t) (dec chunk-days))))]
    (println "Sync" from "→" t)
    (sync-chunks! src ds from t)
    (db/set-state! ds :synced_through t)
    (when-not (db/get-state ds :backfill_before)
      (db/set-state! ds :backfill_before from))
    :ok))

(defn backfill!
  "Walks backwards to `epoch`, one chunk at a time. Resumable.
  Pass :max-chunks to stop early (e.g. to try one chunk)."
  ([src ds] (backfill! src ds {}))
  ([src ds {:keys [max-chunks]}]
   (when-not (db/get-state ds :synced_through)
     (incremental! src ds))
   (loop [n 0]
     (let [before (db/get-state ds :backfill_before)]
       (cond
         (not (.isAfter (d before) (d epoch)))
         (do (println "Backfill complete.") :complete)

         (and max-chunks (>= n max-chunks))
         (do (println "Backfill paused before" before) :paused)

         :else
         (let [end   (.minusDays (d before) 1)
               start (let [s (.minusDays end (dec chunk-days))]
                       (if (.isBefore s (d epoch)) (d epoch) s))]
           (sync-range! src ds (str start) (str end))
           (db/set-state! ds :backfill_before (str start))
           (recur (inc n))))))))

(defn status [ds]
  (merge
   (first (db/query ds ["SELECT count(*) AS activities, min(date) AS first_activity,
                                max(date) AS last_activity FROM activities"]))
   (first (db/query ds ["SELECT count(*) AS wellness_days, min(date) AS first_day,
                                max(date) AS last_day FROM daily_wellness"]))
   {:synced_through  (db/get-state ds :synced_through)
    :backfill_before (db/get-state ds :backfill_before)}))

(defn -main
  "clojure -M -m coach.sync [sync | backfill [max-chunks] | status]"
  [& [cmd n]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (if (= cmd "status")
      (prn (status ds))
      (let [src ((requiring-resolve 'coach.garmin-mcp/start!))]
        (try
          (case (or cmd "sync")
            "sync"     (incremental! src ds)
            "backfill" (backfill! src ds (when n {:max-chunks (parse-long n)})))
          (prn (status ds))
          (finally
            ((requiring-resolve 'coach.garmin-mcp/stop!) src)))))
    (shutdown-agents)))
