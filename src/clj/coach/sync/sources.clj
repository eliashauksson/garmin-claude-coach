(ns coach.sync.sources
  "Activity-only sources (a Coros bike computer) and how their rides meet Garmin's.
  Each new activity is fetched once, in full, and stored right away, so a
  failure halfway (a daily download limit, the connection) keeps what came
  before and the next sync picks up the rest.

  A ride can also be on Garmin (imported into Garmin Connect): an activity
  starting within `same-ride-s` of a Garmin one is the same ride and counts
  once. The Garmin entry stays (feedback and plan links point to it) and
  gets the per-second data if it has none."
  (:require [coach.analysis :as analysis]
            [coach.db :as db]
            [coach.season :as season]
            [coach.source :as source]
            [next.jdbc])
  (:import (java.time LocalDate LocalDateTime ZoneOffset)))

(def refresh-days
  "How many days before the last sync to re-fetch, for late-arriving data."
  2)

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

(defn same-ride
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

(defn samples [ds id]
  (or (:samples (first (db/query ds ["SELECT samples FROM activity_streams WHERE activity_id = ?" id]))) 0))

(defn link! [ds source source-id activity-id merged?]
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

(defn drop-activity! [ds id]
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
       (do
         (next.jdbc/execute! ds
                             ["UPDATE OR IGNORE activity_feedback SET activity_id = ? WHERE activity_id = ?" new old])
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
        from   (if last (str (.minusDays (LocalDate/parse last) refresh-days)) epoch)
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
