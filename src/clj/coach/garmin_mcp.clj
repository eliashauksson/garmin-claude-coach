(ns coach.garmin-mcp
  "DataSource backed by a local Taxuspt/garmin_mcp server over stdio.
  Read-only is enforced twice: the server only registers the tools in
  `read-tools` (GARMIN_ENABLED_TOOLS), and `call` refuses anything else."
  (:require [clojure.string :as str]
            [coach.mcp :as mcp]
            [coach.source :as source])
  (:import (java.time LocalDate LocalDateTime ZoneOffset)))

(def server-ref
  "Pinned so a server update can't silently change tool names or shapes."
  "git+https://github.com/Taxuspt/garmin_mcp@a2ca5fcac34524b83814fe2fcf3d479f8a75983f")

(def read-tools
  #{"count_activities" "get_activities" "get_activities_by_date" "get_activity"
    "get_activity_splits" "get_activity_hr_in_timezones" "get_activity_fit_messages"
    "get_user_profile"
    "get_stats" "get_rhr_day" "get_hrv_data" "get_sleep_data" "get_sleep_summary_range"
    "get_training_readiness" "get_morning_training_readiness" "get_body_battery"
    "get_stress_data" "get_training_status" "get_training_load_trend" "get_vo2max_trend"
    "get_lactate_threshold" "get_heart_rate_zones" "get_cycling_ftp"
    "get_heart_rates" "get_steps_data"})

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
     :avg_stress        (let [v (:avg_stress_level st)] (when (and (number? v) (>= v 0)) v))
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

;; Streams: FIT "record" messages -> columnar map

(def ^:private record-fields
  "stream key -> FIT field names, first present wins."
  {:hr      ["heart_rate"]
   :speed   ["enhanced_speed" "speed"]
   :alt     ["enhanced_altitude" "altitude"]
   :dist    ["distance"]
   :power   ["power"]
   :cadence ["cadence"]
   :lat     ["position_lat"]
   :lon     ["position_long"]})

(defn- semicircles->deg [v] (when (number? v) (* v (/ 180.0 2147483648.0))))

(defn- epoch-s [ts]
  (when (string? ts)
    (.toEpochSecond (LocalDateTime/parse ts) ZoneOffset/UTC)))

(defn records->stream
  "Columnar stream {:t [s from start] :hr [...] ...} from FIT record messages.
  Channels with no values at all are left out. nil if there are no records."
  [messages]
  (let [recs (->> messages
                  (map (fn [msg] (into {} (map (juxt :name :value)) (:fields msg))))
                  (filter #(epoch-s (get % "timestamp")))
                  (sort-by #(epoch-s (get % "timestamp"))))]
    (when (seq recs)
      (let [t0  (epoch-s (get (first recs) "timestamp"))
            field (fn [rec names] (some #(let [v (get rec %)] (when (number? v) v)) names))
            chans (into {}
                        (for [[k names] record-fields
                              :let [xs (mapv #(field % names) recs)
                                    xs (if (#{:lat :lon} k) (mapv semicircles->deg xs) xs)]
                              :when (some some? xs)]
                          [k xs]))]
        (assoc chans :t (mapv #(- (epoch-s (get % "timestamp")) t0) recs))))))

(defn thresholds-from
  "Threshold rows from get_lactate_threshold and get_heart_rate_zones."
  [lt zones ftp]
  (let [lt    (or (m lt) {})
        z     (or (some #(when (= "DEFAULT" (:sport %)) %) (filter map? (if (sequential? zones) zones [zones])))
                  (first (filter map? (if (sequential? zones) zones [zones]))))
        on    (fn [ts] (some-> ts (subs 0 10)))
        row   (fn [sport kind value measured]
                (when (number? value)
                  {:sport sport :kind kind :value (double value) :measured_on measured}))]
    (vec
     (keep identity
           (concat
            [(row "all" "lthr" (or (:lactate_threshold_heart_rate_bpm lt) (:lactateThresholdHeartRateUsed z))
                  (on (:speed_hr_date lt)))
             (row "all" "max_hr" (:maxHeartRateUsed z) nil)
             (row "run" "threshold_speed" (:lactate_threshold_speed_mps lt) (on (:speed_hr_date lt)))
             (row "run" "ftp" (:functional_threshold_power_watts lt) (on (:power_date lt)))
             (row "bike" "ftp" (or (:functional_threshold_power_watts (m ftp)) (:ftp (m ftp))) nil)]
            (map-indexed (fn [i k] (row "all" (str "hr_z" (inc i)) (get z k) nil))
                         [:zone1Floor :zone2Floor :zone3Floor :zone4Floor :zone5Floor]))))))

;; Intraday series (heart rate, stress, body battery, steps per hour)

(defn- ts-ms
  "\"2026-10-28T23:00:00.0\" (no zone) -> epoch millis, read as UTC."
  [s]
  (when (string? s)
    (* 1000 (.toEpochSecond (LocalDateTime/parse s) ZoneOffset/UTC))))

(defn- local-midnight-gmt-ms
  "Epoch millis of local midnight on `date`, using the UTC offset that the
  payload itself reports (local start − GMT start), so DST days and travel
  stay right regardless of the JVM's zone."
  [date payload]
  (let [local (ts-ms (:startTimestampLocal (m payload)))
        gmt   (ts-ms (:startTimestampGMT (m payload)))]
    (when (and local gmt)
      (- (ts-ms (str date "T00:00:00")) (- local gmt)))))

(defn- minute-of-day [midnight ms] (quot (- ms midnight) 60000))

(defn intraday-row
  "Normalised intraday series for one local day from the raw tool responses.
  Days without data (message strings, nil arrays) give empty vectors."
  [{:keys [date hr stress steps] :as raw}]
  (let [hr-mid     (local-midnight-gmt-ms date hr)
        stress-mid (local-midnight-gmt-ms date stress)
        series     (fn [mid rows f]
                     (if mid (into [] (keep #(f mid %)) rows) []))]
    {:date date
     :hr (series hr-mid (:heartRateValues (m hr))
                 (fn [mid [ms bpm]] (when (and ms bpm) [(minute-of-day mid ms) bpm])))
     :stress (series stress-mid (:stressValuesArray (m stress))
                     (fn [mid [ms lvl]] (when (and ms lvl) [(minute-of-day mid ms) (if (neg? lvl) -1 lvl)])))
     :body_battery (series stress-mid (:bodyBatteryValuesArray (m stress))
                           (fn [mid [ms _ lvl]] (when (and ms (number? lvl)) [(minute-of-day mid ms) lvl])))
     :steps (let [mid (or hr-mid stress-mid)]
              (if (and mid (sequential? steps))
                (reduce (fn [acc {:keys [startGMT] :as iv}]
                          (let [ms (ts-ms startGMT)
                                h  (when ms (quot (minute-of-day mid ms) 60))]
                            (if (and h (<= 0 h 23)) (update acc h + (or (:steps iv) 0)) acc)))
                        (vec (repeat 24 0))
                        steps)
                []))
     :raw (dissoc raw :date)}))

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
            (dates start-date end-date))))

  (fetch-streams [_ activity-id]
    (let [limit 2000]
      (loop [offset 0 msgs []]
        (Thread/sleep (long delay-ms))
        (let [r     (call client "get_activity_fit_messages"
                          {"activity_id" activity-id "include_records" true
                           "message_types" ["record"]
                           "message_offset" offset "message_limit" limit})
              page  (:messages r)
              msgs  (into msgs page)
              total (get-in r [:pagination :total_eligible_count] 0)]
          (if (and (seq page) (< (+ offset (count page)) total))
            (recur (+ offset (count page)) msgs)
            (records->stream msgs))))))

  (fetch-thresholds [_]
    (let [lt      (call client "get_lactate_threshold" {})
          zones   (call client "get_heart_rate_zones" {})
          ftp     (call client "get_cycling_ftp" {})
          profile (call client "get_user_profile" {})]
      {:thresholds (thresholds-from lt zones ftp)
       :sex        (get-in profile [:userData :gender])}))

  (fetch-intraday [_ date]
    (let [day (fn [tool]
                (Thread/sleep (long delay-ms))
                (call client tool {"date" date}))]
      (intraday-row {:date   date
                     :hr     (day "get_heart_rates")
                     :stress (day "get_stress_data")
                     :steps  (day "get_steps_data")})))

  (fetch-vo2max [_ start-date end-date]
    (Thread/sleep (long delay-ms))
    (let [r (call client "get_vo2max_trend" {"start_date" start-date "end_date" end-date})]
      (vec (for [p (:trend (m r)) :when (:vo2_max p)]
             {:date (:date p) :sport (:sport r) :value (:vo2_max p)
              :carried_forward (boolean (:carried_forward p))})))))

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
