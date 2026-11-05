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
            [coach.season :as season]
            [coach.source :as source]
            [next.jdbc])
  (:import (java.time LocalDate LocalDateTime ZoneOffset)))

(def epoch
  "The earliest date a backfill goes back to (Garmin Connect started in 2006).
  It usually stops sooner: see empty-chunks-to-stop."
  "2006-01-01")

(def empty-chunks-to-stop
  "A backfill stops after this many 28-day chunks in a row with no activity
  and no health data (about half a year): the start of the athlete's history."
  6)

(def legacy-history-start
  "Where backfills used to stop (a fixed date) before they found the start by
  themselves: a backfill that got there counts as complete."
  "2021-03-26")
(def chunk-days 28)
(def refresh-days
  "How many days before the last sync to re-fetch, for late-arriving data."
  2)

(defn- d ^LocalDate [s] (LocalDate/parse s))

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

(declare keep-stored empty-wellness? dates-with-wellness garmin-duplicate?)

(defn sync-range!
  "Fetches and stores everything in one window. Returns counts. Nothing is
  written unless the whole window was fetched, and empty days never replace
  stored ones (see keep-stored)."
  [src ds start end]
  (let [t0   (System/currentTimeMillis)
        acts (source/fetch-activities src start end)
        well (source/fetch-wellness src start end)
        kept (keep-stored well empty-wellness? (dates-with-wellness ds start end))
        acts (remove #(garmin-duplicate? ds %) acts)]
    (db/upsert-activities! ds acts)
    (db/upsert-wellness! ds kept)
    (println (format "  %s → %s  %3d activities  %3d days  (%.1fs)%s"
                     start end (count acts) (count kept)
                     (/ (- (System/currentTimeMillis) t0) 1000.0)
                     (let [n (- (count well) (count kept))]
                       (if (pos? n) (str "  kept " n " stored day(s) over empty ones") ""))))
    {:activities (count acts) :days (count kept)
     ;; anything at all in this window (for finding the start of the history)
     :with_data (+ (count acts) (count (remove empty-wellness? well)))}))

(def vo2max-chunk-days "get_vo2max_trend accepts at most 90 days." 90)

(defn sync-vo2max! [src ds start end]
  (doseq [[s e] (chunks start end vo2max-chunk-days)]
    (db/upsert-vo2max! ds (source/fetch-vo2max src s e))))

(defn sync-load-trend!
  "Garmin's daily acute and chronic load (coach.load-model checks ours
  against it). Not needed for anything else, so a failure only prints."
  [src ds start end]
  (try
    (doseq [[s e] (chunks start end vo2max-chunk-days)]
      (db/upsert-load-trend! ds (source/fetch-load-trend src s e)))
    (catch Throwable e (println "Load trend failed:" (ex-message e)))))

(defn sync-thresholds!
  "Stores Garmin's current thresholds. The first time, they apply to all
  history (valid from `epoch`); afterwards a changed value starts a new
  period from the day it was measured. Returns true if anything changed."
  [src ds]
  (let [{:keys [thresholds sex weight_kg]} (source/fetch-thresholds src)
        ;; only Garmin's own rows: an estimated FTP (coach.analysis/sync-eftp!)
        ;; mustn't make Garmin's unchanged value look new
        existing (filter #(= "garmin" (:source %)) (db/thresholds ds))
        current  (fn [sport kind]
                   (:value (last (filter #(and (= sport (:sport %)) (= kind (:kind %))) existing))))
        changed  (doall
                  (for [{:keys [sport kind value measured_on]} thresholds
                        :let [cur (current sport kind)]
                        :when (not= cur value)]
                    (db/add-threshold! ds {:sport sport :kind kind :value value :source "garmin"
                                           :valid_from (if (nil? cur) epoch (or measured_on (season/today)))})))]
    (when sex (db/set-state! ds :sex sex))
    (when weight_kg (db/set-state! ds :weight_kg weight_kg))
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

(def intraday-days "Today plus this many days back are re-fetched on every sync." 6)

(def garmin-intraday-months
  "Garmin serves intraday detail (heart rate, stress, body battery, steps
  through the day) only for about this many months back; older days come
  back empty. Measured on 2026-11-01: the first day with detail was
  2026-07-01. So every day is saved while it's still in the window."
  4)

(defn intraday-window-start [today] (str (.minusMonths (d today) garmin-intraday-months)))

(defn intraday-dates
  "Pure: the days a sync fetches, oldest first: the last week (late and
  partial data) plus every day in Garmin's window that was never stored.
  A day Garmin had nothing for is stored empty, so it isn't asked for again."
  [today stored?]
  (let [recent (set (map first (chunks (str (.minusDays (d today) intraday-days)) today 1)))
        window (map first (chunks (intraday-window-start today) today 1))]
    (sort (distinct (concat recent (remove stored? window))))))

(defn stored-intraday-dates [ds from]
  (into #{} (map :date) (db/query ds ["SELECT date FROM daily_intraday WHERE date >= ?" from])))

(defn sync-intraday-dates!
  "Fetches and stores the intraday series of `dates`. A fatal error (login,
  rate limit, offline) stops the sync; any other failure is logged and that
  day skipped (so it's tried again next time). An empty day never replaces
  a stored one with data."
  [src ds dates]
  (doseq [[i date] (map-indexed vector dates)]
    (when (and (pos? i) (zero? (mod i 25)))
      (println (format "  intraday %d/%d" i (count dates))))
    (try
      (let [row (source/fetch-intraday src date)]
        (if (and (empty-intraday? row) (some-> (db/load-intraday ds date) empty-intraday? not))
          (println "  intraday" date "came back empty; kept the stored day")
          (db/save-intraday! ds row)))
      (catch Exception e
        (if (fatal? e)
          (throw e)
          (println "  intraday" date "skipped:" (ex-message e)))))))

(defn sync-intraday!
  "Fetches intraday series for each date in [start, end]."
  [src ds start end]
  (sync-intraday-dates! src ds (reverse (map first (chunks start end 1)))))

;; ---------------------------------------------------------------------------
;; Coach overview: today's training status, race predictions and endurance
;; score (Garmin only serves their current value, so each sync keeps that
;; day's), and the sleep need of the last 30 nights.

(def sleep-need-days "Nights of sleep need the overview shows." 30)

(defn sleep-need-dates
  "Pure: the nights to fetch, oldest first: the last `refresh-days` + 1
  (late data) plus every night of the last `sleep-need-days` not stored."
  [today stored?]
  (let [recent (set (map first (chunks (str (.minusDays (d today) refresh-days)) today 1)))
        window (map first (chunks (str (.minusDays (d today) (dec sleep-need-days))) today 1))]
    (sort (distinct (concat recent (remove stored? window))))))

(defn- overview-step!
  "Runs one fetch-and-store step. A fatal error stops the sync; any other is
  logged and skipped (tried again next sync). nil answers store nothing, so
  an empty answer never replaces a stored day."
  [label f]
  (try (f)
       (catch Exception e
         (if (fatal? e) (throw e) (println " " label "skipped:" (ex-message e))))))

(defn sync-overview!
  "Training status, race predictions and endurance score for `today`, and
  the sleep need of recent nights."
  [src ds today]
  (overview-step! "training status"
                  #(some->> (source/fetch-training-status src today) vector (db/upsert-training-status! ds)))
  (overview-step! "race predictions"
                  #(some->> (source/fetch-race-predictions src) vector (db/upsert-race-predictions! ds)))
  (overview-step! "endurance score"
                  #(some->> (source/fetch-endurance src today) vector (db/upsert-endurance! ds)))
  (let [stored (into #{} (map :date) (db/query ds ["SELECT date FROM sleep_need"]))]
    (doseq [date (sleep-need-dates today stored)]
      (overview-step! (str "sleep need " date)
                      #(some->> (source/fetch-sleep-need src date) vector (db/upsert-sleep-need! ds))))))

(defn- sync-chunks! [src ds start end]
  (doseq [[s e] (chunks start end chunk-days)]
    (sync-range! src ds s e)))

(defn incremental!
  "Syncs from shortly before the last synced date up to today."
  [src ds]
  (let [t    (season/today)
        last (db/get-state ds :synced_through)
        from (if last
               (str (.minusDays (d last) refresh-days))
               (str (.minusDays (d t) (dec chunk-days))))]
    (println "Sync" from "→" t)
    (sync-chunks! src ds from t)
    (db/set-state! ds :synced_through t)
    (when-not (db/get-state ds :backfill_before)
      (db/set-state! ds :backfill_before from))
    ;; the last week, plus any day in Garmin's 4-month window not saved yet
    (let [dates (intraday-dates t (stored-intraday-dates ds (intraday-window-start t)))]
      (when (> (count dates) (inc intraday-days))
        (println "Intraday:" (count dates) "days, saving the ones not stored yet before Garmin drops them"))
      (sync-intraday-dates! src ds dates))
    (sync-vo2max! src ds from t)
    (sync-load-trend! src ds from t)
    (sync-overview! src ds t)
    (let [changed? (sync-thresholds! src ds)
          new-ids  (sync-streams! src ds)
          missing  (map :id (db/query ds ["SELECT a.id FROM activities a
                                           LEFT JOIN activity_metrics m ON m.activity_id = a.id
                                           WHERE m.activity_id IS NULL"]))
          ;; activities without their own heart rate borrow the watch's all-day
          ;; heart rate, which keeps coming in for recent days: score them again
          no-hr    (map :id (db/query ds ["SELECT a.id FROM activities a
                                           JOIN activity_metrics m ON m.activity_id = a.id
                                           WHERE a.avg_hr IS NULL AND (m.tss IS NULL OR a.date >= ?)"
                                          (str (.minusDays (d t) (inc intraday-days)))]))]
      ;; new thresholds change every score; otherwise only score what's new
      (if changed?
        (analysis/compute-metrics! ds)
        (analysis/compute-metrics! ds (distinct (concat new-ids missing no-hr))))
      (analysis/update-eftp! ds))
    :ok))

(defn history-complete?
  "Whether a backfill has reached the start of the athlete's history. A
  backfill records its progress in :history_complete; an install from before
  that counts as complete if it reached the old fixed start."
  [ds]
  (case (db/get-state ds :history_complete)
    "true"  true
    "false" false
    (boolean (some-> (db/get-state ds :backfill_before) (compare legacy-history-start) pos? not))))

(defn backfill!
  "Walks backwards one chunk at a time until `empty-chunks-to-stop` chunks in
  a row had nothing, or `epoch`. Resumable. Pass :max-chunks to stop early
  (e.g. to try one chunk)."
  ([src ds] (backfill! src ds {}))
  ([src ds {:keys [max-chunks]}]
   (when-not (db/get-state ds :synced_through)
     (incremental! src ds))
   (loop [n 0 empty-run 0]
     (let [before (db/get-state ds :backfill_before)
           done!  #(do (db/set-state! ds :history_complete "true") (println "Backfill complete.") :complete)]
       (cond
         (or (history-complete? ds) (not (.isAfter (d before) (d epoch))))
         (done!)

         (>= empty-run empty-chunks-to-stop)
         (do (println "Nothing older than" before "- that's the start of the history.") (done!))

         (and max-chunks (>= n max-chunks))
         (do (println "Backfill paused before" before) :paused)

         :else
         (let [_     (when-not (db/get-state ds :history_complete)
                         (db/set-state! ds :history_complete "false"))
               end   (.minusDays (d before) 1)
               start (let [s (.minusDays end (dec chunk-days))]
                       (if (.isBefore s (d epoch)) (d epoch) s))
               {:keys [with_data]} (sync-range! src ds (str start) (str end))]
           (db/set-state! ds :backfill_before (str start))
           (recur (inc n) (if (pos? with_data) 0 (inc empty-run)))))))))

;; ---------------------------------------------------------------------------
;; Activity-only sources (a Coros bike computer). Each new activity is
;; fetched once, in full, and stored right away, so a failure halfway (a
;; daily download limit, the connection) keeps what came before and the next
;; sync picks up the rest.
;;
;; A ride can also be on Garmin (imported into Garmin Connect): an activity
;; starting within `same-ride-s` of a Garmin one is the same ride and counts
;; once. The Garmin entry stays (feedback and plan links point to it) and
;; gets the per-second data if it has none.

(def local-id-base
  "Ids for activities from activity-only sources. Their own ids can be too
  big for the browser (JavaScript counts exactly only up to 2^53; Coros ids
  are ~4.7e17), so they get this plus their start in Unix seconds: far above
  Garmin's ids (~2.4e10), below 2^53."
  4000000000000000)

(def ^:private js-max-int 9007199254740991)

(def same-ride-s "Starts this close (seconds) are the same ride." 300)

(defn local-id
  "Pure: the id for an activity starting at `start-gmt` (ISO, UTC)."
  [start-gmt]
  (+ local-id-base (.toEpochSecond (LocalDateTime/parse start-gmt) ZoneOffset/UTC)))

(defn- same-ride
  "The id of a stored activity starting within same-ride-s of `activity`,
  among Garmin's (`garmin?` true) or the activity-only sources' ones."
  [ds {:keys [start_time_gmt start_time_local]} garmin?]
  (when (or start_time_gmt start_time_local)
    (:id (first (db/query ds [(str "SELECT id FROM activities WHERE id " (if garmin? "<" ">=") " ?
                                    AND ((? IS NOT NULL AND start_time_gmt IS NOT NULL
                                          AND abs(strftime('%s', start_time_gmt) - strftime('%s', ?)) <= ?)
                                      OR ((? IS NULL OR start_time_gmt IS NULL)
                                          AND abs(strftime('%s', start_time_local) - strftime('%s', ?)) <= ?))
                                    ORDER BY id LIMIT 1")
                              local-id-base
                              start_time_gmt start_time_gmt same-ride-s
                              start_time_gmt start_time_local same-ride-s])))))

(defn- samples [ds id]
  (or (:samples (first (db/query ds ["SELECT samples FROM activity_streams WHERE activity_id = ?" id]))) 0))

(defn- link! [ds source source-id activity-id merged?]
  (next.jdbc/execute! ds ["INSERT INTO source_activities (source, source_id, activity_id, merged) VALUES (?, ?, ?, ?)
                           ON CONFLICT(source, source_id) DO UPDATE SET activity_id = excluded.activity_id,
                             merged = excluded.merged"
                          source (str source-id) activity-id (if merged? 1 0)]))

(defn store-source-activity!
  "Stores one activity from source `source` (its own id `source-id`):
  merged into the same ride from Garmin, else as an activity of its own.
  Returns the stored activity's id."
  [ds source source-id {:keys [activity stream]}]
  (if-let [gid (same-ride ds activity true)]
    (do (when (and stream (zero? (samples ds gid)))
          (db/save-stream! ds gid stream nil))
        (link! ds source source-id gid true)
        gid)
    (let [id (local-id (:start_time_gmt activity))]
      (db/upsert-activities! ds [(-> activity
                                     (assoc :id id)
                                     (update :raw assoc :source source :source_id (str source-id)))])
      (db/save-stream! ds id stream (when-not stream "no per-second data"))
      (link! ds source source-id id false)
      id)))

(defn- drop-activity! [ds id]
  (doseq [t ["activity_streams" "activity_metrics"]]
    (next.jdbc/execute! ds [(str "DELETE FROM " t " WHERE activity_id = ?") id]))
  (next.jdbc/execute! ds ["DELETE FROM activities WHERE id = ?" id]))

(defn relink-stored!
  "Activities stored under their source's own id (before local-id-base, too
  big for the browser) are stored again through store-source-activity!,
  without fetching anything. Feedback moves along. Returns the new ids."
  [ds]
  (let [rows (db/query ds ["SELECT * FROM activities WHERE id > ?" js-max-int])]
    (vec
     (for [row rows
           :let [old (:id row)
                 raw (db/read-json (:raw row))
                 new (store-source-activity! ds (or (:source raw) "coros") old
                                             {:activity (-> (select-keys row db/activity-cols)
                                                            (assoc :raw raw))
                                              :stream   (db/load-stream ds old)})]]
       (do (next.jdbc/execute! ds ["UPDATE OR IGNORE activity_feedback SET activity_id = ? WHERE activity_id = ?" new old])
           (next.jdbc/execute! ds ["DELETE FROM activity_feedback WHERE activity_id = ?" old])
           (drop-activity! ds old)
           new)))))

(defn garmin-duplicate?
  "Whether a Garmin activity is a ride already stored from an activity-only
  source (so it isn't stored twice)."
  [ds activity]
  (some? (same-ride ds activity false)))

(defn activities-only!
  "Stores the activities of `src` (a coach.source/ActivitySource, called
  `source`) since a little before `state-key`'s date (from `epoch` the first
  time), skipping ones already stored. Scores the new ones. Returns the ids
  of the activities stored or updated. A failed fetch throws with
  :remaining, the number of new activities not stored."
  [src ds source state-key epoch]
  (let [relinked (relink-stored! ds)
        t      (season/today)
        last   (db/get-state ds state-key)
        from   (if last (str (.minusDays (d last) refresh-days)) epoch)
        listed (source/list-activities src from t)
        known  (into #{} (map :source_id)
                     (db/query ds ["SELECT source_id FROM source_activities WHERE source = ?" source]))
        fresh  (remove #(known (str (:id %))) listed)
        stored (atom (vec relinked))]
    (println (format "  %s → %s  %d listed, %d new" from t (count listed) (count fresh)))
    (try
      (doseq [[i summary] (map-indexed vector fresh)]
        (swap! stored conj (store-source-activity!
                            ds source (:id summary)
                            (try (source/fetch-full-activity src summary)
                                 ;; how many are still to come, for the message
                                 (catch Throwable e
                                   (throw (ex-info (ex-message e)
                                                   (assoc (ex-data e) :remaining (- (count fresh) i))
                                                   e)))))))
      (db/set-state! ds state-key t)
      (finally
        (when (seq @stored)
          (analysis/compute-metrics! ds (distinct @stored))
          (analysis/update-eftp! ds))))
    (vec (distinct @stored))))

(defn coros!
  "Syncs the rides on Coros (coach.coros-mcp). Returns {:new n}."
  [ds]
  (println "Coros")
  (let [src ((requiring-resolve 'coach.coros-mcp/start!))]
    (try {:new (count (activities-only! src ds "coros" :coros_synced_through
                                        @(requiring-resolve 'coach.coros-mcp/epoch)))}
         (finally ((requiring-resolve 'coach.coros-mcp/stop!) src)))))

(defn earliest-date [ds]
  (:d (first (db/query ds [(str "SELECT min(d) AS d FROM (SELECT min(date) AS d FROM activities
                                 UNION SELECT min(date) FROM daily_wellness WHERE NOT ("
                                (str/join " AND " (map #(str (name %) " IS NULL") wellness-values)) "))")]))))

(defn load-history!
  "Everything before the first sync: the backfill, VO2 max history,
  per-second data for every activity, then the metrics. Resumable."
  [src ds]
  (backfill! src ds)
  (when-let [from (earliest-date ds)]
    (sync-vo2max! src ds from (season/today))
    (sync-load-trend! src ds from (season/today)))
  (sync-streams! src ds)
  (analysis/compute-metrics! ds)
  (analysis/update-eftp! ds)
  :complete)

(defn history-status
  "For the dashboard: how far the history goes back and what's left."
  [ds]
  {:complete        (history-complete? ds)
   :synced_through  (db/get-state ds :synced_through)
   :loaded_from     (db/get-state ds :backfill_before)
   :activities      (:n (first (db/query ds ["SELECT count(*) AS n FROM activities"])))
   :streams_missing (:n (first (db/query ds ["SELECT count(*) AS n FROM activities a
                                              LEFT JOIN activity_streams s ON s.activity_id = a.id
                                              WHERE s.activity_id IS NULL"])))})

(defn record-result!
  "Keeps the last sync's outcome in the DB, so a failure is still shown after
  the dashboard restarts. `result`: {:ok … :finished_at … [:kind :message]}."
  [ds result]
  (db/set-state! ds :last_sync (json/write-str result))
  (when (:ok result) (db/set-state! ds :last_ok_sync (:finished_at result))))

(defn last-result [ds]
  (some-> (db/state-json ds :last_sync)
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
  "clojure -M -m coach.sync [sync | backfill [max-chunks] | history | streams [max] | intraday [days] |
                            vo2max | loadtrend [from] | thresholds | overview | metrics | status]"
  [& [cmd n]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (case cmd
      "status"  (prn (status ds))
      "metrics" (do (println "Scored" (analysis/compute-metrics! ds) "activities")
                    (analysis/update-eftp! ds))
      (let [_ (when-not (#{"sync" "backfill" "history" "streams" "intraday" "vo2max" "loadtrend" "thresholds" "overview"} (or cmd "sync"))
                (println "Unknown command:" cmd) (System/exit 2))
            {:keys [ok]}
            (run-cli-sync!
             ds (fn [src]
                  (case (or cmd "sync")
                    "sync"       (incremental! src ds)
                    "backfill"   (backfill! src ds (when n {:max-chunks (parse-long n)}))
                    "history"    (load-history! src ds)
                    "streams"    (do (sync-streams! src ds (when n {:max-activities (parse-long n)}))
                                     (analysis/compute-metrics! ds)
                                     (analysis/update-eftp! ds))
                    "intraday"   (let [days (if n (parse-long n) 7)]
                                   (sync-intraday! src ds (season/add-days (season/today) (- (dec days))) (season/today)))
                    "vo2max"     (sync-vo2max! src ds epoch (season/today))
                    "loadtrend"  (sync-load-trend! src ds (or n epoch) (season/today))
                    "overview"   (sync-overview! src ds (season/today))
                    "thresholds" (do (println "Changed:" (sync-thresholds! src ds))
                                     (run! prn (db/thresholds ds))))))]
        (prn (status ds))
        (when-not ok (shutdown-agents) (System/exit 1))))
    (shutdown-agents)))
