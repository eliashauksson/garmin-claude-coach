(ns coach.garmin-mcp
  "DataSource backed by a local Taxuspt/garmin_mcp server over stdio.
  Read-only is enforced twice: the server only registers the tools in
  `read-tools` (GARMIN_ENABLED_TOOLS), and `call` refuses anything else."
  (:require [clojure.string :as str]
            [coach.mcp :as mcp]
            [coach.source :as source])
  (:import (java.time LocalDate)))

(def server-ref
  "Pinned so a server update can't silently change tool names or shapes."
  "git+https://github.com/Taxuspt/garmin_mcp@a2ca5fcac34524b83814fe2fcf3d479f8a75983f")

(def read-tools
  #{"count_activities" "get_activities" "get_activities_by_date" "get_activity"
    "get_activity_splits" "get_activity_hr_in_timezones" "get_activity_fit_messages"
    "get_user_profile"
    "get_stats" "get_rhr_day" "get_hrv_data" "get_sleep_data" "get_sleep_summary_range"
    "get_training_readiness" "get_morning_training_readiness" "get_body_battery"
    "get_stress_data" "get_training_status" "get_training_load_trend" "get_vo2max_trend"})

(defn- uvx-path []
  (let [local (str (System/getProperty "user.home") "/.local/bin/uvx")]
    (if (.exists (java.io.File. local)) local "uvx")))

(defn server-cmd
  "Command line for one of the pinned server's executables
  (\"garmin-mcp\" or \"garmin-mcp-auth\")."
  [exe & args]
  (into [(uvx-path) "--python" "3.12" "--from" server-ref exe] args))

(defn call
  "Calls a Garmin MCP tool, refusing anything outside the read-only allowlist."
  ([client tool] (call client tool {}))
  ([client tool args]
   (when-not (read-tools tool)
     (throw (ex-info (str "Refusing non-read-only Garmin tool: " tool) {:tool tool})))
   (mcp/call-tool client tool args)))

;; ---------------------------------------------------------------------------
;; Normalisation (pure)
;;
;; Days without data come back as a plain message string ("No HRV data found
;; for ..."), not an error, so every accessor tolerates non-map input.

(defn- m [x] (when (map? x) x))

(defn- trim-ts
  "\"2026-10-19T04:37:08.0\" -> \"2026-10-19T04:37:08\""
  [s]
  (when (string? s) (str/replace s #"\.\d+$" "")))

(defn activity-row
  "Normalised activity from the list `summary` and the `get_activity` `detail`."
  [summary detail]
  (let [d     (or (m detail) {})
        s     (or (m summary) {})
        local (or (trim-ts (:start_time_local d))
                  (some-> (:start_time s) (str/replace " " "T")))]
    {:id               (or (:id d) (:id s))
     :start_time_local local
     :start_time_gmt   (trim-ts (:start_time_gmt d))
     :date             (some-> local (subs 0 10))
     :sport            (or (:type d) (:type s))
     :name             (or (:name d) (:name s))
     :distance_m       (or (:distance_meters d) (:distance_meters s))
     :duration_s       (or (:duration_seconds d) (:duration_seconds s))
     :moving_s         (:moving_duration_seconds d)
     :elev_gain_m      (or (:elevation_gain_meters d) (:elevation_gain_meters s))
     :avg_hr           (or (:avg_hr_bpm d) (:avg_hr_bpm s))
     :max_hr           (or (:max_hr_bpm d) (:max_hr_bpm s))
     :calories         (or (:calories d) (:calories s))
     :training_load    (:training_load d)
     :aerobic_te       (:training_effect d)
     :raw              {:summary summary :detail detail}}))

(defn morning-readiness
  "Garmin returns several readiness snapshots a day; the morning value is the
  one taken after waking. Falls back to the earliest snapshot."
  [readiness]
  (let [snaps (filter map? (if (sequential? readiness) readiness [readiness]))]
    (or (first (filter #(= "AFTER_WAKEUP_RESET" (:context %)) snaps))
        (first (sort-by :timestamp snaps)))))

(defn wellness-row
  "Normalised wellness for one day from the raw tool responses."
  [{:keys [date stats hrv readiness sleep] :as raw}]
  (let [st (or (m stats) {})
        hv (or (m hrv) {})
        sl (or (m sleep) {})
        rd (morning-readiness readiness)]
    {:date              date
     :rhr               (or (:resting_heart_rate_bpm st) (:resting_heart_rate_bpm sl))
     :hrv_avg           (or (:last_night_avg_hrv_ms hv) (:avg_overnight_hrv sl))
     :hrv_weekly_avg    (:weekly_avg_hrv_ms hv)
     :hrv_status        (:status hv)
     :sleep_score       (:sleep_score sl)
     :sleep_s           (:sleep_seconds sl)
     :deep_sleep_s      (:deep_sleep_seconds sl)
     :rem_sleep_s       (:rem_sleep_seconds sl)
     :readiness         (:score rd)
     :readiness_level   (:level rd)
     :avg_stress        (:avg_stress_level st)
     :body_battery_high (:body_battery_highest st)
     :body_battery_low  (:body_battery_lowest st)
     :steps             (:total_steps st)
     :raw               (dissoc raw :date)}))

(defn dates
  "ISO dates from start to end, inclusive."
  [start end]
  (let [e (LocalDate/parse end)]
    (->> (iterate #(.plusDays ^LocalDate % 1) (LocalDate/parse start))
         (take-while #(not (.isAfter ^LocalDate % e)))
         (map str))))

;; ---------------------------------------------------------------------------

(defn- activity-page
  "Tool results may be a bare list or wrapped in a map; normalise to a seq."
  [res]
  (cond
    (sequential? res) res
    (map? res) (or (:activities res) (:result res) [])
    :else []))

(defrecord GarminMcpSource [client delay-ms]
  source/DataSource
  (fetch-activities [this start-date end-date]
    (let [page-size 100
          summaries (loop [page 0 acc []]
                      (Thread/sleep (long delay-ms))
                      (let [items (activity-page
                                   (call client "get_activities_by_date"
                                         {"start_date" start-date "end_date" end-date
                                          "page" page "page_size" page-size}))
                            acc   (into acc items)]
                        (if (< (count items) page-size) acc (recur (inc page) acc))))]
      (mapv (fn [s] (activity-row s (source/fetch-activity this (:id s))))
            summaries)))

  (fetch-activity [_ activity-id]
    (Thread/sleep (long delay-ms))
    (call client "get_activity" {"activity_id" activity-id}))

  (fetch-wellness [_ start-date end-date]
    (let [nights (->> (call client "get_sleep_summary_range"
                            {"start_date" start-date "end_date" end-date})
                      :nights
                      (into {} (map (juxt :date identity))))
          day    (fn [tool date]
                   (Thread/sleep (long delay-ms))
                   (call client tool {"date" date}))]
      (mapv (fn [date]
              (wellness-row {:date      date
                             :stats     (day "get_stats" date)
                             :hrv       (day "get_hrv_data" date)
                             :readiness (day "get_training_readiness" date)
                             :sleep     (get nights date)}))
            (dates start-date end-date)))))

(defn start!
  "Starts the MCP server and returns a GarminMcpSource. Tokens are read from
  ~/.garminconnect (or GARMINTOKENS), written there by garmin-mcp-auth.
  `delay-ms` throttles calls to Garmin's (unofficial) API."
  ([] (start! {}))
  ([{:keys [stderr-file delay-ms] :or {stderr-file "data/mcp-server.log" delay-ms 250}}]
   (.mkdirs (.getParentFile (.getAbsoluteFile (java.io.File. ^String stderr-file))))
   (->GarminMcpSource
    (mcp/start! {:cmd (server-cmd "garmin-mcp")
                 :env {"GARMIN_ENABLED_TOOLS" (str/join "," (sort read-tools))}
                 :stderr-file stderr-file})
    delay-ms)))

(defn stop! [{:keys [client]}]
  (mcp/stop! client))
