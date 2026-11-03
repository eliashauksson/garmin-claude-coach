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
