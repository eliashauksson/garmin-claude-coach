(ns coach.db
  "SQLite storage. Every table keeps the source's raw response as JSON so
  metrics can be recomputed without refetching."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def default-path "data/coach.db")

(defn datasource
  ([] (datasource default-path))
  ([path]
   (some-> (java.io.File. ^String path) .getAbsoluteFile .getParentFile .mkdirs)
   (jdbc/get-datasource {:dbtype "sqlite" :dbname path})))

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
   "CREATE TABLE IF NOT EXISTS sync_state (
      key   TEXT PRIMARY KEY,
      value TEXT)"])

(defn migrate! [ds]
  (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
  (doseq [stmt ddl] (jdbc/execute! ds [stmt])))

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

(defn query [ds sqlvec]
  (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-state [ds k]
  (:value (first (query ds ["SELECT value FROM sync_state WHERE key = ?" (name k)]))))

(defn set-state! [ds k v]
  (jdbc/execute! ds ["INSERT INTO sync_state (key, value) VALUES (?, ?)
                      ON CONFLICT(key) DO UPDATE SET value = excluded.value"
                     (name k) (str v)]))
