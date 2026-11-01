(ns coach.sync
  "Pulls data from a DataSource into the local DB.

  - incremental: from a little before the last synced date up to today, so
    partial days (today, last night's sleep) get refreshed.
  - backfill: walks backwards from the oldest synced date to `epoch` in
    28-day chunks, saving progress after each chunk so it can resume.

  Writes are upserts, so re-running any range is harmless."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [coach.analysis :as analysis]
            [coach.db :as db]
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

(declare keep-stored empty-wellness? dates-with-wellness)

(defn sync-range!
  "Fetches and stores everything in one window. Returns counts. Nothing is
  written unless the whole window was fetched, and empty days never replace
  stored ones (see keep-stored)."
  [src ds start end]
  (let [t0   (System/currentTimeMillis)
        acts (source/fetch-activities src start end)
        well (source/fetch-wellness src start end)
        kept (keep-stored well empty-wellness? (dates-with-wellness ds start end))]
    (db/upsert-activities! ds acts)
    (db/upsert-wellness! ds kept)
    (println (format "  %s → %s  %3d activities  %3d days  (%.1fs)%s"
                     start end (count acts) (count kept)
                     (/ (- (System/currentTimeMillis) t0) 1000.0)
                     (let [n (- (count well) (count kept))]
                       (if (pos? n) (str "  kept " n " stored day(s) over empty ones") ""))))
    {:activities (count acts) :days (count kept)}))

(def vo2max-chunk-days "get_vo2max_trend accepts at most 90 days." 90)

(defn sync-vo2max! [src ds start end]
  (doseq [[s e] (chunks start end vo2max-chunk-days)]
    (db/upsert-vo2max! ds (source/fetch-vo2max src s e))))

(defn sync-thresholds!
  "Stores Garmin's current thresholds. The first time, they apply to all
  history (valid from `epoch`); afterwards a changed value starts a new
  period from the day it was measured. Returns true if anything changed."
  [src ds]
  (let [{:keys [thresholds sex]} (source/fetch-thresholds src)
        existing (db/thresholds ds)
        current  (fn [sport kind]
                   (:value (last (filter #(and (= sport (:sport %)) (= kind (:kind %))) existing))))
        changed  (doall
                  (for [{:keys [sport kind value measured_on]} thresholds
                        :let [cur (current sport kind)]
                        :when (not= cur value)]
                    (db/add-threshold! ds {:sport sport :kind kind :value value :source "garmin"
                                           :valid_from (if (nil? cur) epoch (or measured_on (today)))})))]
    (when sex (db/set-state! ds :sex sex))
    (boolean (seq changed))))

;; ---------------------------------------------------------------------------
;; Failures

(defn error-kind
  "The :kind of a sync exception (see coach.garmin-mcp/error-kind)."
  [^Throwable e]
  (or (:kind (ex-data e))
      ((requiring-resolve 'coach.garmin-mcp/error-kind)
       (str (ex-message e) " " (some-> e .getCause ex-message)))))

(def fatal-kinds
  "Retrying the next item can't help: stop the whole sync."
  #{:auth :rate-limit :unreachable :server})

(def transient-kinds
  "Worth retrying on the next sync, so never recorded as permanently missing."
  #{:timeout :garmin})

(defn- fatal? [e] (contains? fatal-kinds (error-kind e)))

(defn- clean-message
  "Drops garmin_mcp's own fix-it hints (\"Run 'garmin-mcp-auth' … restart the
  server.\"): they don't apply here, the dashboard gives its own."
  [msg]
  (-> msg
      (str/replace #"\s*(Run|Re-run) 'garmin-mcp-auth'[^.]*\." "")
      str/trim))

(defn failure
  "Pure-ish: what the dashboard shows for a failed sync."
  [^Throwable e]
  {:kind (error-kind e) :message (clean-message (or (ex-message e) (str (class e))))})

;; ---------------------------------------------------------------------------
;; Do no harm: an empty fetched day never replaces a stored day with data.
;; Garmin (or a changed API) answering "nothing" for a day we already have is
;; far more likely a fault than the data really being gone.

(def ^:private wellness-values
  [:rhr :hrv_avg :sleep_score :sleep_s :readiness :avg_stress :body_battery_high :steps])

(defn empty-wellness? [row] (every? #(nil? (get row %)) wellness-values))
(defn empty-intraday? [row] (every? #(empty? (get row %)) [:hr :stress :body_battery :steps]))

(defn keep-stored
  "Pure: drops fetched rows that are empty when the stored row for that date
  is not. `stored-has-data?` is a set of dates."
  [rows empty? stored-has-data?]
  (remove #(and (empty? %) (stored-has-data? (:date %))) rows))

(defn- dates-with-wellness [ds start end]
  (into #{} (map :date)
        (db/query ds [(str "SELECT date FROM daily_wellness WHERE date BETWEEN ? AND ? AND NOT ("
                           (str/join " AND " (map #(str (name %) " IS NULL") wellness-values)) ")")
                      start end])))

(defn sync-streams!
  "Fetches per-sample data for activities that don't have it yet.
  Activities without a FIT file get a marker row so they aren't retried."
  ([src ds] (sync-streams! src ds {}))
  ([src ds {:keys [max-activities]}]
   (let [ids (map :id (db/query ds ["SELECT a.id FROM activities a
                                     LEFT JOIN activity_streams s ON s.activity_id = a.id
                                     WHERE s.activity_id IS NULL
                                     ORDER BY a.start_time_local DESC"]))
         ids (if max-activities (take max-activities ids) ids)
         n   (count ids)]
     (when (pos? n) (println "Fetching streams for" n "activities"))
     (doseq [[i id] (map-indexed vector ids)]
       (try
         (db/save-stream! ds id (source/fetch-streams src id) nil)
         (catch clojure.lang.ExceptionInfo e
           (cond
             (or (fatal? e) (not (:tool (ex-data e)))) (throw e)
             ;; try again next sync rather than marking it missing for good
             (transient-kinds (error-kind e)) (println "  streams" id "skipped:" (ex-message e))
             :else (db/save-stream! ds id nil (ex-message e)))))
       (when (zero? (mod (inc i) 25)) (println (format "  streams %d/%d" (inc i) n))))
     ids)))

(def intraday-days "Today plus this many days back are kept fresh." 6)

(defn sync-intraday!
  "Fetches intraday series for each date in [start, end]. A fatal error
  (login, rate limit, offline) stops the sync; any other failure is logged
  and that day skipped. An empty day never replaces a stored one with data."
  [src ds start end]
  (doseq [date (reverse (map first (chunks start end 1)))]
    (try
      (let [row (source/fetch-intraday src date)]
        (if (and (empty-intraday? row) (some-> (db/load-intraday ds date) empty-intraday? not))
          (println "  intraday" date "came back empty; kept the stored day")
          (db/save-intraday! ds row)))
      (catch Exception e
        (if (fatal? e)
          (throw e)
          (println "  intraday" date "skipped:" (ex-message e)))))))

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
    (let [week-ago (str (.minusDays (d t) intraday-days))]
      (sync-intraday! src ds (if (pos? (compare from week-ago)) from week-ago) t))
    (sync-vo2max! src ds from t)
    (let [changed? (sync-thresholds! src ds)
          new-ids  (sync-streams! src ds)
          missing  (map :id (db/query ds ["SELECT a.id FROM activities a
                                           LEFT JOIN activity_metrics m ON m.activity_id = a.id
                                           WHERE m.activity_id IS NULL"]))]
      ;; new thresholds change every score; otherwise only score what's new
      (if changed?
        (analysis/compute-metrics! ds)
        (analysis/compute-metrics! ds (distinct (concat new-ids missing)))))
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

(defn record-result!
  "Keeps the last sync's outcome in the DB, so a failure is still shown after
  the dashboard restarts. `result`: {:ok … :finished_at … [:kind :message]}."
  [ds result]
  (db/set-state! ds :last_sync (json/write-str result))
  (when (:ok result) (db/set-state! ds :last_ok_sync (:finished_at result))))

(defn last-result [ds]
  (some-> (db/get-state ds :last_sync) (json/read-str :key-fn keyword)
          (as-> r (cond-> r (:kind r) (update :kind keyword)))))

(defn- run-cli-sync!
  "Runs `f` against a fresh Garmin source and records the result, like the
  dashboard does, so a CLI failure shows up there too."
  [ds f]
  (let [result (try
                 (let [src ((requiring-resolve 'coach.garmin-mcp/start!))]
                   (try (f src) (finally ((requiring-resolve 'coach.garmin-mcp/stop!) src))))
                 {:ok true}
                 (catch Throwable e (merge {:ok false} (failure e))))
        result (assoc result :finished_at (str (java.time.Instant/now)))]
    (record-result! ds result)
    (when-not (:ok result)
      (println (str "Sync failed (" (name (:kind result)) "): " (:message result))))
    result))

(defn status [ds]
  (merge
   (first (db/query ds ["SELECT count(*) AS streams, sum(samples = 0) AS streams_missing FROM activity_streams"]))
   (first (db/query ds ["SELECT count(*) AS scored, round(sum(tss)) AS total_tss FROM activity_metrics WHERE tss IS NOT NULL"]))
   (first (db/query ds ["SELECT count(*) AS activities, min(date) AS first_activity,
                                max(date) AS last_activity FROM activities"]))
   (first (db/query ds ["SELECT count(*) AS wellness_days, min(date) AS first_day,
                                max(date) AS last_day FROM daily_wellness"]))
   {:synced_through  (db/get-state ds :synced_through)
    :backfill_before (db/get-state ds :backfill_before)
    :last_ok_sync    (db/get-state ds :last_ok_sync)
    :last_sync       (last-result ds)}))

(defn -main
  "clojure -M -m coach.sync [sync | backfill [max-chunks] | streams [max] | intraday [days] |
                            vo2max | thresholds | metrics | status]"
  [& [cmd n]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (case cmd
      "status"  (prn (status ds))
      "metrics" (println "Scored" (analysis/compute-metrics! ds) "activities")
      (let [_ (when-not (#{"sync" "backfill" "streams" "intraday" "vo2max" "thresholds"} (or cmd "sync"))
                (println "Unknown command:" cmd) (System/exit 2))
            {:keys [ok]}
            (run-cli-sync!
             ds (fn [src]
                  (case (or cmd "sync")
                    "sync"       (incremental! src ds)
                    "backfill"   (backfill! src ds (when n {:max-chunks (parse-long n)}))
                    "streams"    (do (sync-streams! src ds (when n {:max-activities (parse-long n)}))
                                     (analysis/compute-metrics! ds))
                    "intraday"   (let [days (if n (parse-long n) 7)]
                                   (sync-intraday! src ds (str (.minusDays (d (today)) (dec days))) (today)))
                    "vo2max"     (sync-vo2max! src ds epoch (today))
                    "thresholds" (do (println "Changed:" (sync-thresholds! src ds))
                                     (run! prn (db/thresholds ds))))))]
        (prn (status ds))
        (when-not ok (shutdown-agents) (System/exit 1))))
    (shutdown-agents)))
