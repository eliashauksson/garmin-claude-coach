(ns coach.fit
  "Reads FIT activity files (Garmin's FIT SDK) into plain maps: message name ->
  [{field-name value}], values scaled to their units (m, m/s, s, bpm, W).
  Timestamps stay FIT seconds (since 1989-12-31 UTC): see unix-seconds."
  (:import (com.garmin.fit Decode Field Mesg MesgListener)
           (java.io ByteArrayInputStream)))

(def epoch-offset
  "FIT time 0 in Unix seconds (1989-12-31T00:00:00Z)."
  631065600)

(defn unix-seconds [fit-ts] (when (number? fit-ts) (+ (long fit-ts) epoch-offset)))

(defn decode
  "FIT bytes -> {\"record\" [{\"timestamp\" … \"heart_rate\" …} …] \"session\" […] …}.
  Throws ex-info when the file isn't a readable FIT file."
  [^bytes bs]
  (let [acc (volatile! (transient {}))]
    (try
      (with-open [in (ByteArrayInputStream. bs)]
        (.read (Decode.) in
               (reify MesgListener
                 (onMesg [_ m]
                   (let [row (persistent!
                              (reduce (fn [r ^Field f]
                                        (let [v (.getValue f)]
                                          (if (some? v) (assoc! r (.getName f) v) r)))
                                      (transient {})
                                      (.getFields ^Mesg m)))
                         k   (.getName ^Mesg m)]
                     (vswap! acc assoc! k (conj (get @acc k []) row)))))))
      (persistent! @acc)
      (catch com.garmin.fit.FitRuntimeException e
        (throw (ex-info (str "Unreadable FIT file: " (ex-message e)) {:kind :fit} e))))))

;; ---------------------------------------------------------------------------
;; Record messages -> stream

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

(defn- number-ts [rec] (let [v (get rec "timestamp")] (when (number? v) v)))

(defn records->stream
  "Columnar stream {:t [s from start] :hr […] …} from FIT record maps
  ({\"timestamp\" … \"heart_rate\" …}). Options: `:ts` reads a record's time
  in seconds (default: a numeric \"timestamp\"); `:zero-means-none` channels
  count as missing when never above 0 (no sensor, not 0 W). Channels without
  any value are left out. nil if there are no records."
  ([records] (records->stream records {}))
  ([records {:keys [ts zero-means-none] :or {ts number-ts zero-means-none #{}}}]
   (let [recs (->> records (filter ts) (sort-by ts))]
     (when (seq recs)
       (let [t0    (ts (first recs))
             field (fn [rec names] (some #(let [v (get rec %)] (when (number? v) v)) names))
             chans (into {}
                         (for [[k names] record-fields
                               :let [xs (mapv #(field % names) recs)
                                     xs (if (#{:lat :lon} k) (mapv semicircles->deg xs) xs)]
                               :when (if (zero-means-none k)
                                       (some #(and % (pos? %)) xs)
                                       (some some? xs))]
                           [k xs]))]
         (assoc chans :t (mapv #(- (ts %) t0) recs)))))))
