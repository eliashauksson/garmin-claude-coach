(ns coach.coros-mcp
  "ActivitySource backed by COROS's official MCP server (https://mcp.coros.com),
  for rides recorded on a Coros bike computer. Health data stays Garmin's.

  Each activity comes from its original FIT file: totals from the session
  message, per-second data from the records. Read-only twice over: `call`
  refuses anything outside `read-tools`, and the only other request is the
  download of a FIT file from Coros's own storage."
  (:require [clojure.string :as str]
            [coach.coros-auth :as coros-auth]
            [coach.fit :as fit]
            [coach.i18n :refer [t]]
            [coach.mcp :as mcp]
            [coach.source :as source])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration Instant LocalDate LocalDateTime ZoneId ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def read-tools #{"querySportRecords" "queryActivityFitFileDownloadUrls"})

(def epoch
  "The earliest date the first sync looks back to (Coros watches came out in 2016)."
  "2015-01-01")

;; ---------------------------------------------------------------------------
;; Errors

(def ^:private error-kinds
  [[:auth        #"(?i)HTTP 40[13]|unauthori[sz]ed|not connected|login failed|invalid_grant"]
   [:rate-limit  #"(?i)limit|HTTP 429|too many"]
   [:unreachable #"(?i)can't be reached|unreachable|connection|network"]
   [:timeout     #"(?i)timed out|timeout"]])

(defn error-kind
  "Pure: classifies a Coros error message. :coros when nothing matches."
  [msg]
  (or (some (fn [[k re]] (when (re-find re (str msg)) k)) error-kinds) :coros))

(defn call
  "Calls a Coros MCP tool, refusing anything outside the read-only allowlist."
  [client tool args]
  (when-not (read-tools tool)
    (throw (ex-info (str "Refusing non-read-only Coros tool: " tool) {:tool tool})))
  (try (mcp/call-tool client tool args)
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info (t "Coros: {0}" (ex-message e))
                         (assoc (ex-data e) :tool tool
                                :kind (or (#{:auth :rate-limit :unreachable :timeout} (:kind (ex-data e)))
                                          (error-kind (ex-message e))))
                         e)))))

;; ---------------------------------------------------------------------------
;; The activity list (pure): querySportRecords answers in text blocks like
;;
;;   1. Cycling — 2026-09-26
;;      Location: Brugg Road Bike
;;      Time Window: startTimestamp=1790419085 | endTimestamp=1790423429
;;      LabelId: 480613187119514001 | SportType: 200

(defn- record-block [block]
  (let [grab (fn [re] (second (re-find re block)))
        label (grab #"LabelId:\s*(\d+)")
        sport (grab #"SportType:\s*(\d+)")]
    (when (and label sport)
      {:id         (parse-long label)
       :label_id   label
       :sport_type (parse-long sport)
       :date       (grab #"^\s*\d+\.\s.*?(\d{4}-\d{2}-\d{2})")
       :start_ts   (some-> (grab #"startTimestamp=(\d+)") parse-long)
       :name       (some-> (grab #"(?m)^\s*Location:\s*(.+?)\s*$") not-empty)})))

(defn parse-records
  "Pure: activity summaries from a querySportRecords answer. Throws when the
  answer has records but none could be read (the format changed), so that
  doesn't pass for \"no rides\"."
  [text]
  (let [text   (str text)
        blocks (str/split text #"(?m)^(?=[ \t]*\d+\.\s)")
        recs   (vec (keep record-block blocks))]
    (when (and (empty? recs) (not (re-find #"(?i)^\s*no sport records" text)))
      (throw (ex-info (t "Coros's activity list couldn't be read.") {:kind :coros :answer (subs text 0 (min 300 (count text)))})))
    recs))

(defn fit-url
  "Pure: the FIT file URL in a queryActivityFitFileDownloadUrls answer. Only
  https on a coros.com host is accepted."
  [text]
  (let [u (re-find #"https://\S+" (str text))
        host (some-> u URI/create .getHost)]
    (when (and host (or (= host "coros.com") (str/ends-with? host ".coros.com")))
      u)))

;; ---------------------------------------------------------------------------
;; FIT -> activity row and stream (pure)

(def sports
  "Coros sport type code -> the app's sport names (Garmin's type keys, so
  sport families and labels work the same)."
  {100 "running" 101 "treadmill_running" 102 "trail_running" 103 "track_running"
   104 "hiking" 105 "mountaineering"
   200 "road_biking" 201 "indoor_cycling" 202 "e_biking" 203 "gravel_cycling"
   204 "mountain_biking" 205 "e_mountain_biking" 299 "cycling"
   300 "lap_swimming" 301 "open_water_swimming"
   400 "indoor_cardio" 401 "cardio" 402 "strength_training"
   500 "resort_skiing" 501 "resort_snowboarding" 502 "cross_country_skiing" 503 "backcountry_skiing"
   700 "rowing" 701 "indoor_rowing" 900 "walking" 904 "yoga" 1200 "hiit"})

(defn sport-name [code] (get sports code "other"))

(def ^:private iso-local (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn- fmt [^long unix ^long offset-s]
  (.format (LocalDateTime/ofEpochSecond unix 0 (ZoneOffset/ofTotalSeconds (int offset-s))) iso-local))

(defn- pos-num [v] (when (and (number? v) (pos? v)) v))

(defn utc-offset
  "Seconds local time is ahead of UTC, from the FIT activity message;
  else the computer's zone at `unix`."
  [fit-msgs unix]
  (let [{lt "local_timestamp" ts "timestamp"} (first (get fit-msgs "activity"))]
    (if (and (number? lt) (number? ts) (<= (Math/abs (long (- lt ts))) (* 18 3600)))
      (long (- lt ts))
      (.getTotalSeconds (.getOffset (.getRules (ZoneId/systemDefault)) (Instant/ofEpochSecond unix))))))

(defn activity-row
  "Normalised activity (coach.db columns) from a list `summary` and the
  decoded FIT file."
  [summary fit-msgs]
  (let [s      (or (first (get fit-msgs "session")) {})
        start  (or (fit/unix-seconds (get s "start_time")) (:start_ts summary))
        offset (utc-offset fit-msgs start)
        local  (fmt start offset)
        sport  (sport-name (:sport_type summary))]
    {:id               (:id summary)
     :start_time_local local
     :start_time_gmt   (fmt start 0)
     :date             (subs local 0 10)
     :sport            sport
     :name             (or (:name summary) (str/capitalize (str/replace sport "_" " ")))
     :distance_m       (pos-num (get s "total_distance"))
     :duration_s       (pos-num (get s "total_elapsed_time"))
     :moving_s         (pos-num (or (get s "total_moving_time") (get s "total_timer_time")))
     :elev_gain_m      (get s "total_ascent")
     :avg_hr           (pos-num (get s "avg_heart_rate"))
     :max_hr           (pos-num (get s "max_heart_rate"))
     :calories         (pos-num (get s "total_calories"))
     :training_load    nil
     :aerobic_te       (pos-num (get s "total_training_effect"))
     :raw              {:source "coros" :summary summary :session s}}))

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

(def ^:private zero-means-none
  "Channels a Coros writes as 0 when there's no sensor (no power meter, no
  HR strap): all zeros means no data, not a ride at 0 W."
  #{:hr :power :cadence})

(defn- semicircles->deg [v] (when (number? v) (* v (/ 180.0 2147483648.0))))

(defn records->stream
  "Columnar stream {:t [s from start] :hr […] …} from FIT record messages.
  Channels without any value are left out. nil if there are no records."
  [records]
  (let [recs (->> records (filter #(number? (get % "timestamp"))) (sort-by #(get % "timestamp")))]
    (when (seq recs)
      (let [t0    (get (first recs) "timestamp")
            field (fn [rec names] (some #(let [v (get rec %)] (when (number? v) v)) names))
            chans (into {}
                        (for [[k names] record-fields
                              :let [xs (mapv #(field % names) recs)
                                    xs (if (#{:lat :lon} k) (mapv semicircles->deg xs) xs)]
                              :when (if (zero-means-none k)
                                      (some #(and % (pos? %)) xs)
                                      (some some? xs))]
                          [k xs]))]
        (assoc chans :t (mapv #(- (get % "timestamp") t0) recs))))))

;; ---------------------------------------------------------------------------

(defn- yyyymmdd [iso] (str/replace iso "-" ""))

(def list-chunk-days 90)
(def list-limit
  "Records asked for per list request. A chunk that comes back with at
  least `split-at-count` is split in two, so a server-side cap above that
  can't hide rides."
  100)
(def split-at-count 20)

(def ^:private ^HttpClient http
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 20))
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.build)))

(defn- download [^String url]
  (let [resp (try (.send http (-> (HttpRequest/newBuilder (URI/create url))
                                  (.timeout (Duration/ofSeconds 120)) (.GET) (.build))
                         (HttpResponse$BodyHandlers/ofByteArray))
                  (catch java.io.IOException e
                    (throw (ex-info (t "Coros can't be reached: {0}" (ex-message e)) {:kind :unreachable} e))))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info (t "Coros answered HTTP {0} ({1})." (.statusCode resp) "FIT file") {:kind :coros})))
    (.body resp)))

(defn newest-first
  "Pure: activity summaries, newest first (by date, then start time), so that
  with a long backlog and Coros's daily FIT limit the recent rides come first."
  [summaries]
  (vec (sort-by (juxt :date :start_ts) #(compare %2 %1) summaries)))

(defn- dates-between [start end]
  (let [s (LocalDate/parse start) e (LocalDate/parse end)]
    (loop [from s acc []]
      (if (.isAfter from e)
        acc
        (let [to (let [x (.plusDays from (dec list-chunk-days))] (if (.isAfter x e) e x))]
          (recur (.plusDays to 1) (conj acc [(str from) (str to)])))))))

(defrecord CorosMcpSource [client delay-ms]
  source/ActivitySource
  (list-activities [_ start-date end-date]
    (letfn [(fetch [s e]
              (Thread/sleep (long delay-ms))
              (let [recs (parse-records (call client "querySportRecords"
                                              {"startDate" (yyyymmdd s) "endDate" (yyyymmdd e)
                                               "limit" list-limit}))]
                (if (and (>= (count recs) split-at-count) (not= s e))
                  (let [sd  (LocalDate/parse s)
                        mid (.plusDays sd (quot (.between java.time.temporal.ChronoUnit/DAYS sd (LocalDate/parse e)) 2))]
                    (into (fetch s (str mid)) (fetch (str (.plusDays mid 1)) e)))
                  recs)))]
      (->> (dates-between start-date end-date)
           (mapcat (fn [[s e]] (fetch s e)))
           (reduce (fn [acc r] (assoc acc (:id r) r)) {})
           vals
           newest-first)))

  (fetch-full-activity [_ summary]
    (Thread/sleep (long delay-ms))
    (let [answer (call client "queryActivityFitFileDownloadUrls"
                       {"labelId" (:label_id summary) "sportType" (:sport_type summary)})
          url    (or (fit-url answer)
                     (throw (ex-info (t "Coros sent no FIT file for the activity on {0}." (:date summary))
                                     {:kind :coros :answer (str answer)})))
          msgs   (fit/decode (download url))]
      {:activity (activity-row summary msgs)
       :stream   (records->stream (get msgs "record"))})))

(defn start!
  "Connects to Coros's MCP server with the stored login. `delay-ms`
  spaces out the calls."
  ([] (start! {}))
  ([{:keys [delay-ms] :or {delay-ms 300}}]
   (when-not (coros-auth/connected?)
     (throw (ex-info (t "Coros isn't connected. Connect it in Settings.") {:kind :auth})))
   (->CorosMcpSource
    (try (mcp/start-http! {:url coros-auth/mcp-url :token-fn coros-auth/access-token!})
         (catch clojure.lang.ExceptionInfo e
           (throw (ex-info (t "Coros: {0}" (ex-message e))
                           (update (ex-data e) :kind #(or (#{:auth :rate-limit :unreachable :timeout} %)
                                                          (error-kind (ex-message e))))
                           e))))
    delay-ms)))

(defn stop! [{:keys [client]}] (mcp/stop! client))
