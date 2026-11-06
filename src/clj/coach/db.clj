(ns coach.db
  "SQLite storage. Every table keeps the source's raw response as JSON so
  metrics can be recomputed without refetching."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [coach.paths :as paths]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.util.zip GZIPInputStream GZIPOutputStream)))

(defn default-path [] (paths/data-file "coach.db"))

(defn datasource
  ([] (datasource (default-path)))
  ([path]
   (some-> (java.io.File. ^String path) .getAbsoluteFile .getParentFile .mkdirs)
   ;; sync, backfill and the API server may write concurrently
   (jdbc/get-datasource {:dbtype "sqlite" :dbname path :busy_timeout 15000})))

(def ^:private ddl
  ["CREATE TABLE IF NOT EXISTS activities (
      id               INTEGER PRIMARY KEY,
      start_time_local TEXT,
      start_time_gmt   TEXT,
      date             TEXT,
      sport            TEXT,
      name             TEXT,
      distance_m       REAL,
      duration_s       REAL,
      moving_s         REAL,
      elev_gain_m      REAL,
      avg_hr           REAL,
      max_hr           REAL,
      calories         REAL,
      training_load    REAL,
      aerobic_te       REAL,
      raw              TEXT NOT NULL,
      fetched_at       TEXT NOT NULL)"
   "CREATE INDEX IF NOT EXISTS activities_date ON activities(date)"
   "CREATE TABLE IF NOT EXISTS daily_wellness (
      date              TEXT PRIMARY KEY,
      rhr               INTEGER,
      hrv_avg           REAL,
      hrv_weekly_avg    REAL,
      hrv_status        TEXT,
      sleep_score       INTEGER,
      sleep_s           INTEGER,
      deep_sleep_s      INTEGER,
      rem_sleep_s       INTEGER,
      readiness         INTEGER,
      readiness_level   TEXT,
      avg_stress        INTEGER,
      body_battery_high INTEGER,
      body_battery_low  INTEGER,
      steps             INTEGER,
      raw               TEXT NOT NULL,
      fetched_at        TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS activity_streams (
      activity_id INTEGER PRIMARY KEY,
      samples     INTEGER NOT NULL,
      data        BLOB,
      error       TEXT,
      fetched_at  TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS thresholds (
      sport      TEXT NOT NULL,
      kind       TEXT NOT NULL,
      valid_from TEXT NOT NULL,
      value      REAL NOT NULL,
      source     TEXT NOT NULL,
      PRIMARY KEY (sport, kind, valid_from))"
   "CREATE TABLE IF NOT EXISTS vo2max (
      date            TEXT NOT NULL,
      sport           TEXT NOT NULL,
      value           REAL NOT NULL,
      carried_forward INTEGER NOT NULL,
      PRIMARY KEY (date, sport))"
   "CREATE TABLE IF NOT EXISTS activity_metrics (
      activity_id  INTEGER PRIMARY KEY,
      tss          REAL,
      tss_method   TEXT,
      intensity    REAL,
      tss_per_hour REAL,
      np           REAL,
      ngp_mps      REAL,
      vi           REAL,
      ef           REAL,
      decoupling   REAL,
      kj           REAL,
      hr_zones_s   TEXT,
      peaks        TEXT,
      inputs       TEXT,
      computed_at  TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS daily_intraday (
      date       TEXT PRIMARY KEY,
      data       BLOB,
      fetched_at TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS plan_weeks (
      monday     TEXT PRIMARY KEY,
      week_type  TEXT,
      note       TEXT,
      updated_at TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS plan_proposals (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      monday     TEXT NOT NULL,
      status     TEXT NOT NULL,
      model      TEXT,
      context    TEXT,
      response   TEXT,
      warnings   TEXT,
      error      TEXT,
      usage      TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS season_plans (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      race_date    TEXT NOT NULL,
      start_monday TEXT NOT NULL,
      status       TEXT NOT NULL,
      model        TEXT,
      context      TEXT,
      response     TEXT,
      warnings     TEXT,
      error        TEXT,
      usage        TEXT,
      created_at   TEXT NOT NULL,
      updated_at   TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS proposal_items (
      proposal_id  INTEGER NOT NULL,
      idx          INTEGER NOT NULL,
      date         TEXT NOT NULL,
      slot         TEXT NOT NULL,
      session_id   TEXT NOT NULL,
      title        TEXT,
      duration_min INTEGER,
      distance_km  REAL,
      intensity    TEXT,
      description  TEXT,
      reason       TEXT,
      decision     TEXT NOT NULL DEFAULT 'pending',
      workout_id   INTEGER,
      PRIMARY KEY (proposal_id, idx))"
   "CREATE TABLE IF NOT EXISTS planned_workouts (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      date         TEXT NOT NULL,
      slot         TEXT NOT NULL,
      session_id   TEXT NOT NULL,
      title        TEXT,
      duration_min INTEGER,
      distance_km  REAL,
      intensity    TEXT,
      description  TEXT,
      reason       TEXT,
      status       TEXT NOT NULL DEFAULT 'planned',
      proposal_id  INTEGER,
      created_at   TEXT NOT NULL,
      updated_at   TEXT NOT NULL)"
   "CREATE INDEX IF NOT EXISTS planned_workouts_date ON planned_workouts(date)"
   "CREATE TABLE IF NOT EXISTS activity_feedback (
      activity_id   INTEGER PRIMARY KEY,
      rpe           INTEGER,
      pain          INTEGER NOT NULL DEFAULT 0,
      pain_location TEXT,
      note          TEXT,
      updated_at    TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS adapt_signals (
      key         TEXT PRIMARY KEY,
      proposal_id INTEGER,
      first_seen  TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS sync_state (
      key   TEXT PRIMARY KEY,
      value TEXT)"
   ;; every Claude API request (coach.costs)
   "CREATE TABLE IF NOT EXISTS api_calls (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      at              TEXT NOT NULL,
      task            TEXT NOT NULL,
      ref             TEXT,
      model_requested TEXT,
      model_used      TEXT,
      effort          TEXT,
      status          TEXT NOT NULL,
      stop_reason     TEXT,
      error           TEXT,
      input_tokens    INTEGER NOT NULL DEFAULT 0,
      output_tokens   INTEGER NOT NULL DEFAULT 0,
      cache_read      INTEGER NOT NULL DEFAULT 0,
      cache_write_5m  INTEGER NOT NULL DEFAULT 0,
      cache_write_1h  INTEGER NOT NULL DEFAULT 0,
      cost_usd        REAL,
      duration_ms     INTEGER,
      request_id      TEXT,
      lines           TEXT,
      usage_raw       TEXT,
      source          TEXT NOT NULL DEFAULT 'logged')"
   "CREATE INDEX IF NOT EXISTS api_calls_at ON api_calls(at)"
   ;; requests sent through the half-price Message Batches API, until their result is in
   "CREATE TABLE IF NOT EXISTS claude_batches (
      custom_id    TEXT PRIMARY KEY,
      batch_id     TEXT,
      task         TEXT NOT NULL,
      ref          TEXT,
      model        TEXT NOT NULL,
      effort       TEXT NOT NULL,
      data         TEXT,
      status       TEXT NOT NULL,
      submitted_at TEXT NOT NULL,
      ended_at     TEXT,
      error        TEXT)"
   ;; the dashboard's coach overview (coach.overview): one row per day synced
   "CREATE TABLE IF NOT EXISTS training_status (
      date       TEXT PRIMARY KEY,
      raw        TEXT NOT NULL,
      fetched_at TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS race_predictions (
      date       TEXT PRIMARY KEY,
      t5k        REAL,
      t10k       REAL,
      thalf      REAL,
      tmar       REAL,
      raw        TEXT NOT NULL,
      fetched_at TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS endurance_score (
      date       TEXT PRIMARY KEY,
      score      REAL,
      raw        TEXT NOT NULL,
      fetched_at TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS sleep_need (
      date       TEXT PRIMARY KEY,
      need_min   REAL,
      raw        TEXT NOT NULL,
      fetched_at TEXT NOT NULL)"
   ;; Claude's texts in every dashboard language (coach.translate)
   "CREATE TABLE IF NOT EXISTS translations (
      hash       TEXT NOT NULL,
      lang       TEXT NOT NULL,
      text       TEXT NOT NULL,
      created_at TEXT NOT NULL,
      PRIMARY KEY (hash, lang))"
   ;; Garmin's daily acute/chronic load (get_training_load_trend), to check ours against
   "CREATE TABLE IF NOT EXISTS load_trend (
      date       TEXT PRIMARY KEY,
      acute      REAL,
      chronic    REAL,
      raw        TEXT NOT NULL,
      fetched_at TEXT NOT NULL)"
   ;; activities from an activity-only source (Coros): which stored activity
   ;; each one became, merged = 1 when it was the same ride as one from Garmin
   "CREATE TABLE IF NOT EXISTS source_activities (
      source      TEXT NOT NULL,
      source_id   TEXT NOT NULL,
      activity_id INTEGER NOT NULL,
      merged      INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (source, source_id))"])

(def ^:private data-fixes
  ;; Garmin reports -1/-2 stress for "not enough data"; rows stored before
  ;; the normaliser filtered them.
  ["UPDATE daily_wellness SET avg_stress = NULL WHERE avg_stress < 0"
   ;; one current season plan: older finished drafts (made before backups existed) become backups
   "UPDATE season_plans SET status = 'archived', archived_at = updated_at, archived_reason = 'redraft'
    WHERE status = 'ready' AND id < (SELECT max(id) FROM season_plans WHERE status = 'ready')"])

(def ^:private added-columns
  "Columns added after a table was first created: [table column type]."
  [["activity_metrics" "route" "TEXT"]
   ["season_plans" "archived_at" "TEXT"]
   ["season_plans" "archived_reason" "TEXT"]
   ["plan_proposals" "kind" "TEXT NOT NULL DEFAULT 'week'"]
   ["plan_proposals" "scope_end" "TEXT"]
   ["plan_proposals" "signals" "TEXT"]
   ["proposal_items" "op" "TEXT NOT NULL DEFAULT 'add'"]
   ["proposal_items" "target_id" "INTEGER"]
   ["proposal_items" "prev" "TEXT"]
   ["api_calls" "error_type" "TEXT"]
   ["api_calls" "batch" "INTEGER NOT NULL DEFAULT 0"]
   ["plan_proposals" "delivery" "TEXT"]
   ["season_plans" "delivery" "TEXT"]
   ;; "How did it go?" dismissed without an answer (not asked again)
   ["activity_feedback" "skipped" "INTEGER NOT NULL DEFAULT 0"]
   ;; our own Garmin-style training load (coach.epoc), for rides Garmin never saw
   ["activity_metrics" "load_est" "REAL"]
   ["activity_metrics" "load_method" "TEXT"]
   ;; a session's purpose and its structure (coach.workout), JSON blocks
   ["planned_workouts" "goal" "TEXT"]
   ["planned_workouts" "blocks" "TEXT"]
   ["proposal_items" "goal" "TEXT"]
   ["proposal_items" "blocks" "TEXT"]
   ;; the heatmap's track: encoded polyline segments (coach.track), "[]" without GPS
   ["activity_metrics" "track" "TEXT"]])

(defn- ensure-column! [ds [table column type]]
  (let [cols (set (map :name (jdbc/execute! ds [(str "PRAGMA table_info(" table ")")]
                                            {:builder-fn rs/as-unqualified-lower-maps})))]
    (when-not (cols column)
      (jdbc/execute! ds [(str "ALTER TABLE " table " ADD COLUMN " column " " type)]))))

(defn migrate! [ds]
  (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
  (doseq [stmt ddl] (jdbc/execute! ds [stmt]))
  (doseq [c added-columns] (ensure-column! ds c))
  (doseq [stmt data-fixes] (jdbc/execute! ds [stmt])))

(defn- now [] (str (java.time.Instant/now)))

(defn- upsert-sql [table pk cols]
  (let [cs (map name cols)]
    (str "INSERT INTO " table " (" (str/join ", " cs) ") VALUES ("
         (str/join ", " (repeat (count cs) "?")) ") "
         "ON CONFLICT(" pk ") DO UPDATE SET "
         (str/join ", " (for [c cs :when (not= c pk)] (str c " = excluded." c))))))

(defn- upsert! [ds table pk cols rows]
  (when (seq rows)
    (let [sql (upsert-sql table pk cols)
          ts  (now)]
      (jdbc/with-transaction [tx ds]
        (doseq [row rows]
          (jdbc/execute! tx (into [sql]
                                  (map (fn [c]
                                         (case c
                                           :raw        (json/write-str (:raw row))
                                           :fetched_at ts
                                           (get row c)))
                                       cols))))))))

(def activity-cols
  [:id :start_time_local :start_time_gmt :date :sport :name :distance_m :duration_s
   :moving_s :elev_gain_m :avg_hr :max_hr :calories :training_load :aerobic_te
   :raw :fetched_at])

(def wellness-cols
  [:date :rhr :hrv_avg :hrv_weekly_avg :hrv_status :sleep_score :sleep_s :deep_sleep_s
   :rem_sleep_s :readiness :readiness_level :avg_stress :body_battery_high
   :body_battery_low :steps :raw :fetched_at])

(defn upsert-activities! [ds rows] (upsert! ds "activities" "id" activity-cols rows))
(defn upsert-wellness!   [ds rows] (upsert! ds "daily_wellness" "date" wellness-cols rows))

(defn upsert-training-status! [ds rows] (upsert! ds "training_status" "date" [:date :raw :fetched_at] rows))
(defn upsert-race-predictions! [ds rows]
  (upsert! ds "race_predictions" "date" [:date :t5k :t10k :thalf :tmar :raw :fetched_at] rows))
(defn upsert-load-trend! [ds rows] (upsert! ds "load_trend" "date" [:date :acute :chronic :raw :fetched_at] rows))
(defn upsert-endurance! [ds rows] (upsert! ds "endurance_score" "date" [:date :score :raw :fetched_at] rows))
(defn upsert-sleep-need! [ds rows] (upsert! ds "sleep_need" "date" [:date :need_min :raw :fetched_at] rows))

(defn query [ds sqlvec]
  (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))

(defn read-json
  "Parses JSON (a `raw` column, a state value) with keyword keys; nil for
  nil or invalid JSON."
  [s]
  (when s (try (json/read-str s :key-fn keyword) (catch Exception _ nil))))

(defn get-state [ds k]
  (:value (first (query ds ["SELECT value FROM sync_state WHERE key = ?" (name k)]))))

(defn state-json
  "A sync_state value stored as JSON, parsed (nil when absent or invalid)."
  [ds k]
  (read-json (get-state ds k)))

(defn set-state! [ds k v]
  (jdbc/execute! ds ["INSERT INTO sync_state (key, value) VALUES (?, ?)
                      ON CONFLICT(key) DO UPDATE SET value = excluded.value"
                     (name k) (str v)]))

;; ---------------------------------------------------------------------------
;; Streams (gzipped JSON)

(defn- gzip ^bytes [^String s]
  (let [bos (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. bos)]
      (.write gz (.getBytes s "UTF-8")))
    (.toByteArray bos)))

(defn- gunzip ^String [^bytes b]
  (with-open [gz (GZIPInputStream. (ByteArrayInputStream. b))]
    (String. (.readAllBytes gz) "UTF-8")))

(defn save-stream!
  "Stores a stream, or a marker row (0 samples) with `error` so it isn't
  refetched on every sync."
  [ds activity-id stream error]
  (jdbc/execute! ds ["INSERT INTO activity_streams (activity_id, samples, data, error, fetched_at)
                      VALUES (?, ?, ?, ?, ?)
                      ON CONFLICT(activity_id) DO UPDATE SET samples = excluded.samples,
                        data = excluded.data, error = excluded.error, fetched_at = excluded.fetched_at"
                     activity-id (count (:t stream))
                     (when stream (gzip (json/write-str stream)))
                     error (now)]))

(defn load-stream [ds activity-id]
  (when-let [b (:data (first (jdbc/execute! ds ["SELECT data FROM activity_streams WHERE activity_id = ?" activity-id]
                                            {:builder-fn rs/as-unqualified-lower-maps})))]
    (json/read-str (gunzip b) :key-fn keyword)))

(defn save-intraday!
  "Stores one day's intraday series, including :raw so they can be recomputed."
  [ds {:keys [date] :as row}]
  (jdbc/execute! ds ["INSERT INTO daily_intraday (date, data, fetched_at) VALUES (?, ?, ?)
                      ON CONFLICT(date) DO UPDATE SET data = excluded.data, fetched_at = excluded.fetched_at"
                     date (gzip (json/write-str row)) (now)]))

(defn load-intraday
  "Intraday series for `date` without :raw, or nil if not synced."
  [ds date]
  (when-let [b (:data (first (jdbc/execute! ds ["SELECT data FROM daily_intraday WHERE date = ?" date]
                                            {:builder-fn rs/as-unqualified-lower-maps})))]
    (dissoc (json/read-str (gunzip b) :key-fn keyword) :raw)))

;; ---------------------------------------------------------------------------
;; Thresholds, VO2 max, metrics

(defn thresholds [ds]
  (query ds ["SELECT sport, kind, valid_from, value, source FROM thresholds ORDER BY valid_from"]))

(defn add-threshold! [ds {:keys [sport kind valid_from value source]}]
  (jdbc/execute! ds ["INSERT INTO thresholds (sport, kind, valid_from, value, source) VALUES (?, ?, ?, ?, ?)
                      ON CONFLICT(sport, kind, valid_from) DO UPDATE SET value = excluded.value,
                        source = excluded.source"
                     sport kind valid_from value source]))

(defn upsert-vo2max! [ds rows]
  (when (seq rows)
    (jdbc/with-transaction [tx ds]
      (doseq [{:keys [date sport value carried_forward]} rows]
        (jdbc/execute! tx ["INSERT INTO vo2max (date, sport, value, carried_forward) VALUES (?, ?, ?, ?)
                            ON CONFLICT(date, sport) DO UPDATE SET value = excluded.value,
                              carried_forward = excluded.carried_forward"
                           date sport value (if carried_forward 1 0)])))))

(def metric-cols
  [:activity_id :tss :tss_method :intensity :tss_per_hour :np :ngp_mps :vi :ef
   :decoupling :kj :hr_zones_s :peaks :inputs :route :track :load_est :load_method :computed_at])

(defn upsert-metrics! [ds rows]
  (when (seq rows)
    (let [sql (upsert-sql "activity_metrics" "activity_id" metric-cols)
          ts  (now)]
      (jdbc/with-transaction [tx ds]
        (doseq [row rows]
          (jdbc/execute! tx (into [sql]
                                  (map (fn [c]
                                         (case c
                                           (:hr_zones_s :peaks :inputs :route :track)
                                           (some-> (get row c) json/write-str)
                                           :computed_at ts
                                           (get row c)))
                                       metric-cols))))))))
