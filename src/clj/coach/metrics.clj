(ns coach.metrics
  "Training metrics in the TrainingPeaks style, as pure functions.

  Per activity (from per-second streams + thresholds):
    NP, NGP, IF, VI, TSS (power) / rTSS (run pace) / sTSS (swim) / hrTSS,
    EF, aerobic decoupling, kJ, time in HR zones, peak curves.
  Cycling power: power curve, eFTP, VO2 max estimate.
  Over time:
    CTL (fitness), ATL (fatigue), TSB (form), ramp rate, weekly volume,
    wellness trends.

  Streams are columnar maps {:t [...] :hr [...] :speed [...] ...} with :t in
  seconds from the start; any channel may be missing or contain nils."
  (:import (java.time LocalDate)
           (java.time.temporal IsoFields)))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- mean [xs] (when (seq xs) (/ (reduce + 0.0 xs) (count xs))))

(defn- sd [xs]
  (when (> (count xs) 1)
    (let [m (mean xs)]
      (Math/sqrt (/ (reduce + (map #(let [d (- % m)] (* d d)) xs)) (dec (count xs)))))))

(defn- clamp [lo hi x] (max lo (min hi x)))

(defn- round [x digits]
  (when x
    (let [f (Math/pow 10 digits)] (/ (Math/round (* (double x) f)) f))))

;; ---------------------------------------------------------------------------
;; Resampling

(def max-gap-s
  "Gaps longer than this are treated as a pause (auto-pause / timer stopped)
  and not filled; shorter gaps (smart recording) are forward-filled."
  10)

(defn resample
  "Expands a stream to 1 Hz over moving time. Returns a map of channel ->
  vector, all the same length (one entry per moving second)."
  [{:keys [t] :as stream}]
  (let [chans (vec (remove #{:t} (keys stream)))
        n     (count t)]
    (if (< n 2)
      (into {} (for [c chans] [c (vec (get stream c))]))
      (let [reps (mapv (fn [i]
                         (if (= i (dec n))
                           1
                           (let [dt (- (t (inc i)) (t i))]
                             (cond (<= dt 0) 0
                                   (<= dt max-gap-s) dt
                                   :else 1))))
                       (range n))]
        (into {}
              (for [c chans
                    :let [xs (get stream c)]]
                [c (into [] (mapcat (fn [i] (repeat (reps i) (get xs i))) (range n)))]))))))

;; ---------------------------------------------------------------------------
;; Normalisation (NP / NGP)

(defn rolling-mean
  "Means of every full `w`-sample window. Shorter series give one overall mean."
  [xs w]
  (let [v (vec xs) n (count v)]
    (cond
      (zero? n) []
      (< n w)   [(mean v)]
      :else
      (loop [i w sum (reduce + 0.0 (subvec v 0 w)) acc [(/ (reduce + 0.0 (subvec v 0 w)) w)]]
        (if (= i n)
          acc
          (let [sum (+ sum (- (v i) (v (- i w))))]
            (recur (inc i) sum (conj acc (/ sum w)))))))))

(defn normalized
  "Coggan normalisation: 4th root of the mean of the 4th power of the
  30-second rolling average. Used for NP (watts) and NGP (m/s)."
  [xs]
  (let [xs (map #(or % 0.0) xs)]
    (when (seq xs)
      (Math/pow (mean (map #(Math/pow % 4) (rolling-mean xs 30))) 0.25))))

(defn minetti-factor
  "Energy cost of running at `grade` relative to flat (Minetti et al. 2002).
  Grade is clamped to ±45%."
  [grade]
  (let [i (clamp -0.45 0.45 grade)
        c (+ (* 155.4 (Math/pow i 5)) (* -30.4 (Math/pow i 4)) (* -43.3 (Math/pow i 3))
             (* 46.3 i i) (* 19.5 i) 3.6)]
    (/ c 3.6)))

(def grade-lag-s 10)

(defn grades
  "Per-second grade from altitude and distance over a 10 s lag."
  [alt dist]
  (let [alt (vec alt) dist (vec dist) n (count alt)]
    (mapv (fn [i]
            (let [j (max 0 (- i grade-lag-s))
                  a1 (get alt j) a2 (get alt i) d1 (get dist j) d2 (get dist i)]
              (if (and a1 a2 d1 d2 (> (- d2 d1) 5.0))
                (/ (- a2 a1) (- d2 d1))
                0.0)))
          (range n))))

(defn grade-adjusted-speed [speed alt dist]
  (if (and (seq alt) (seq dist) (some some? alt))
    (mapv (fn [s g] (when s (* s (minetti-factor g)))) speed (grades alt dist))
    (vec speed)))

;; ---------------------------------------------------------------------------
;; Stress scores

(defn tss
  "TSS from duration and intensity factor: hours × IF² × 100."
  [seconds intensity]
  (* (/ seconds 3600.0) intensity intensity 100.0))

(defn stss
  "Swim TSS uses IF³."
  [seconds intensity]
  (* (/ seconds 3600.0) intensity intensity intensity 100.0))

(defn- trimp-rate
  "Banister TRIMP per minute at heart-rate reserve fraction `hrr`."
  [hrr k]
  (* hrr 0.64 (Math/exp (* k hrr))))

(defn sex-factor [sex] (if (= "FEMALE" sex) 1.67 1.92))

(defn hrtss
  "Heart-rate TSS: Banister TRIMP normalised so one hour at LTHR = 100.
  `hr` is a 1 Hz series."
  [hr {:keys [lthr max_hr rest_hr sex]}]
  (when (and lthr max_hr rest_hr (> max_hr rest_hr) (seq (keep identity hr)))
    (let [k     (sex-factor sex)
          frac  #(clamp 0.0 1.0 (/ (- % rest_hr) (- max_hr rest_hr)))
          trimp (reduce + 0.0 (map #(/ (trimp-rate (frac %) k) 60.0) (keep identity hr)))
          hour  (* 60.0 (trimp-rate (frac lthr) k))]
      (* 100.0 (/ trimp hour)))))

(defn hrtss-estimate
  "hrTSS when there is no stream: assume average HR for the whole duration."
  [avg-hr seconds thresholds]
  (when (and avg-hr seconds (pos? seconds))
    (hrtss (repeat (long seconds) avg-hr) thresholds)))

;; ---------------------------------------------------------------------------
;; Activity metrics

(def min-hr-coverage
  "Below this share of recorded seconds with a heart-rate value, hrTSS from
  the samples is scaled up to the full duration (HR dropouts, watch HR off)."
  0.8)

(def min-hr-seconds
  "With less heart-rate data than this, use the average-HR estimate instead."
  600)

(defn sport-family [sport]
  (let [s (str sport)]
    (cond
      (re-find #"running" s)                 :run
      (re-find #"cycling|biking|ride" s)     :bike
      (re-find #"swimming" s)                :swim
      :else                                  :other)))

(defn time-in-zones
  "Seconds per HR zone. `floors` are the zone 1..5 lower bounds; time below
  zone 1 counts as zone 0."
  [hr floors]
  (let [floors (vec floors)
        zone   (fn [h] (count (filter #(>= h %) floors)))]
    (reduce (fn [acc h] (update acc (zone h) inc))
            (vec (repeat (inc (count floors)) 0))
            (keep identity hr))))

(def peak-durations [5 60 300 1200 3600])

(def power-curve-durations
  "Durations (seconds) of a ride's power curve: denser than peak-durations,
  for the eFTP, the VO2 max estimate and the power-curve chart."
  [1 5 15 30 60 120 300 600 1200 1800 3600])

(defn peaks
  "Best rolling average for each duration (seconds) that fits in the series."
  ([xs] (peaks xs peak-durations))
  ([xs durations]
   (let [xs (vec (keep identity xs))]
     (into {}
           (for [d durations :when (<= d (count xs))]
             [d (apply max (rolling-mean xs d))])))))

(defn- ef [output hr] (when (and output hr (pos? hr)) (/ output hr)))

(defn decoupling
  "Aerobic decoupling (Pa:HR or Pw:HR) in %: how much output per heartbeat
  drops from the first half to the second. Needs at least 20 minutes."
  [output hr]
  (let [pairs (filter (fn [[o h]] (and o h (pos? h))) (map vector output hr))
        n     (count pairs)]
    (when (>= n 1200)
      (let [[a b] (split-at (quot n 2) pairs)
            ef1   (ef (mean (map first a)) (mean (map second a)))
            ef2   (ef (mean (map first b)) (mean (map second b)))]
        (when (and ef1 ef2 (pos? ef1))
          (* 100.0 (/ (- ef1 ef2) ef1)))))))

(defn has-hr? [stream] (boolean (some #(and (number? %) (pos? %)) (:hr stream))))

(def watch-hr-max-gap-min
  "All-day heart rate comes every ~2 minutes; a gap longer than this is
  left empty (watch off the wrist)."
  5)

(defn watch-hr
  "A stream without heart rate, filled from the watch's all-day heart rate:
  `start-local` the activity's local start (\"2026-09-26T12:38:05\"),
  `days` [[minute-of-day bpm] …] of its date and the day after. Values are
  interpolated between the watch's samples. nil when it covers less than
  half the activity."
  [{:keys [t] :as stream} start-local [day0 day1]]
  (let [pts (->> (concat (for [[m v] day0] [(double m) v]) (for [[m v] day1] [(+ 1440.0 m) v]))
                 (filter (fn [[_ v]] (and (number? v) (pos? v))))
                 (sort-by first)
                 vec)
        t0  (/ (+ (* 3600 (parse-long (subs start-local 11 13)))
                  (* 60 (parse-long (subs start-local 14 16)))
                  (parse-long (subs start-local 17 19)))
               60.0)
        n   (count pts)
        at  (fn [m]
              ;; index of the first point at or after minute m
              (let [i (loop [lo 0 hi n]
                        (if (< lo hi)
                          (let [mid (quot (+ lo hi) 2)]
                            (if (< (first (pts mid)) m) (recur (inc mid) hi) (recur lo mid)))
                          lo))
                    [ma va] (when (pos? i) (pts (dec i)))
                    [mb vb] (when (< i n) (pts i))]
                (cond
                  (and ma mb (<= (- mb ma) watch-hr-max-gap-min))
                  (if (= ma mb) vb (+ va (* (- vb va) (/ (- m ma) (- mb ma)))))
                  (and mb (<= (- mb m) 1.0)) vb
                  (and ma (<= (- m ma) 1.0)) va)))
        hr  (when (pos? n) (mapv #(some-> % (/ 60.0) (+ t0) at double Math/round) t))]
    (when (and hr (>= (count (keep identity hr)) (* 0.5 (count t))))
      (assoc stream :hr hr))))

(defn activity-metrics
  "Metrics for one activity.
  `activity`   row from the activities table (:sport, :moving_s, :duration_s, :avg_hr)
  `stream`     columnar stream map or nil
  `thresholds` {:lthr :max_hr :rest_hr :sex :hr_zones [floors]
                :threshold_speed :ftp :css} (any may be nil)"
  [{:keys [sport moving_s duration_s avg_hr]} stream thresholds]
  (let [s       (when (seq (:t stream)) (resample stream))
        secs    (if s (count (some #(when (seq %) %) (vals s))) 0)
        hr      (:hr s)
        power   (when (some some? (:power s)) (:power s))
        speed   (when (some some? (:speed s)) (:speed s))
        family  (sport-family sport)
        np      (when power (normalized power))
        gap     (when (and speed (= family :run))
                  (grade-adjusted-speed speed (:alt s) (:dist s)))
        ngp     (when gap (normalized gap))
        avg-p   (when power (mean (map #(or % 0.0) power)))
        avg-hr  (or (mean (keep identity hr)) avg_hr)
        hr-secs (count (keep identity hr))
        {:keys [threshold_speed ftp css hr_zones]} thresholds
        [method intensity score]
        (cond
          (and (= family :run) ngp threshold_speed)
          (let [i (/ ngp threshold_speed)] ["rTSS" i (tss secs i)])

          (and (= family :bike) np ftp)
          (let [i (/ np ftp)] ["TSS" i (tss secs i)])

          (and (= family :swim) speed css)
          (let [nss (mean (keep identity speed)) i (/ nss css)] ["sTSS" i (stss secs i)])

          (>= hr-secs min-hr-seconds)
          (when-let [t (hrtss hr thresholds)]
            (if (< hr-secs (* min-hr-coverage secs))
              ;; scale to moving time, so a watch left running isn't counted
              (let [target (if moving_s (min secs moving_s) secs)]
                ["hrTSS-scaled" nil (* t (/ (max target hr-secs) hr-secs))])
              ["hrTSS" nil t]))

          avg_hr
          (when-let [t (hrtss-estimate avg_hr (or moving_s duration_s) thresholds)]
            ["hrTSS-est" nil t]))
        output  (or gap power)]
    {:tss          (round score 1)
     :tss_method   method
     :intensity    (round intensity 3)
     :tss_per_hour (when (and score (pos? secs)) (round (/ score (/ secs 3600.0)) 1))
     :np           (round np 1)
     :ngp_mps      (round ngp 3)
     :vi           (when (and np avg-p (pos? avg-p)) (round (/ np avg-p) 3))
     :ef           (round (cond ngp (ef (* 60 ngp) avg-hr)   ; m/min per beat
                                np  (ef np avg-hr)) 3)
     :decoupling   (round (decoupling output hr) 1)
     :kj           (when power (round (/ (reduce + 0.0 (map #(or % 0.0) power)) 1000.0) 0))
     :hr_zones_s   (when (and (seq hr) (seq hr_zones)) (time-in-zones hr hr_zones))
     :peaks        (when s
                     (cond-> {}
                       (seq (keep identity hr)) (assoc :hr (update-vals (peaks hr) #(round % 1)))
                       speed                    (assoc :speed (update-vals (peaks speed) #(round % 3)))
                       power                    (assoc :power (update-vals (peaks power power-curve-durations)
                                                                           #(round % 1)))))}))

;; ---------------------------------------------------------------------------
;; Cycling power: power curve, eFTP, VO2 max estimate

(defn- peak-at
  "A curve's value for duration `d` (keys may be numbers or keywords, as
  they come back from JSON)."
  [curve d]
  (or (get curve d) (get curve (keyword (str d)))))

(defn best-curve
  "The best value per duration over `rides` [{:date :power {duration watts}}],
  as {duration {:watts :date}} (the date the best was set; the later one on a tie)."
  [rides]
  (reduce (fn [acc {:keys [date power]}]
            (reduce (fn [acc d]
                      (let [w (peak-at power d)]
                        (if (and (number? w) (pos? w)
                                 (or (nil? (get acc d)) (>= w (:watts (get acc d)))))
                          (assoc acc d {:watts w :date date})
                          acc)))
                    acc power-curve-durations))
          (sorted-map) (sort-by :date rides)))

(def eftp-20min-factor "FTP ≈ 95 % of the best 20 minutes (Coggan)." 0.95)

(defn eftp
  "Estimated FTP from a power curve {duration watts}: the higher of 95 % of
  the best 20 minutes and the best hour. nil without a 20-minute effort."
  [curve]
  (let [p20 (peak-at curve 1200) p60 (peak-at curve 3600)]
    (when (and (number? p20) (pos? p20))
      (round (max (* eftp-20min-factor p20) (or p60 0)) 0))))

(def eftp-window-days "The eFTP looks at the best efforts of this many days." 90)
(def eftp-min-change "A new eFTP is kept when it differs by at least this share." 0.02)

(defn eftp-history
  "The eFTP over time from `rides` [{:date :power {duration watts}}]: for each
  ride date, the eFTP from the best efforts of the `window-days` up to it.
  Returns [{:date :value}], one entry each time it moves by at least
  `eftp-min-change` (the first one always). An eFTP doesn't drop to nothing
  when the window has no 20-minute effort: it stays until a new one is set."
  ([rides] (eftp-history rides eftp-window-days))
  ([rides window-days]
   (let [rides (vec (sort-by :date (filter #(seq (:power %)) rides)))]
     (reduce (fn [acc date]
               (let [from (str (.minusDays (LocalDate/parse date) (dec window-days)))
                     in   (filter #(<= (compare from (:date %)) 0 (compare date (:date %))) rides)
                     v    (eftp (update-vals (best-curve in) :watts))
                     cur  (:value (peek acc))]
                 (if (and v (or (nil? cur) (>= (Math/abs (- v cur)) (* eftp-min-change cur))))
                   (conj acc {:date date :value v})
                   acc)))
             [] (distinct (map :date rides))))))

(defn cycling-vo2max
  "VO2 max (ml/kg/min) estimated from the best 5 minutes of power and body
  weight: 10.8 × W/kg + 7 (ACSM cycling equation, Storer et al. 1990)."
  [p5min weight-kg]
  (when (and (number? p5min) (pos? p5min) (number? weight-kg) (pos? weight-kg))
    (round (+ 7.0 (* 10.8 (/ p5min weight-kg))) 1)))

;; ---------------------------------------------------------------------------
;; Performance management (CTL / ATL / TSB)

(def ctl-days 42)
(def atl-days 7)

(defn- dates [start end]
  (let [e (LocalDate/parse end)]
    (->> (iterate #(.plusDays ^LocalDate % 1) (LocalDate/parse start))
         (take-while #(not (.isAfter ^LocalDate % e)))
         (map str))))

(defn pmc
  "Daily fitness/fatigue/form from a map of date -> total TSS.
  TSB is yesterday's CTL − ATL (form going into the day, as TrainingPeaks
  shows it). Ramp is the CTL change over the last 7 days."
  [daily-tss start end]
  (loop [[d & more] (dates start end) ctl 0.0 atl 0.0 hist [] acc []]
    (if-not d
      acc
      (let [t     (get daily-tss d 0.0)
            ctl'  (+ ctl (/ (- t ctl) ctl-days))
            atl'  (+ atl (/ (- t atl) atl-days))
            hist' (conj hist ctl')
            ramp  (when (> (count hist') 7) (- ctl' (hist' (- (count hist') 8))))]
        (recur more ctl' atl' hist'
               (conj acc {:date d :tss (round t 1)
                          :ctl (round ctl' 1) :atl (round atl' 1)
                          :tsb (round (- ctl atl) 1) :ramp (round ramp 1)}))))))

(defn daily-tss
  "Sum TSS per date from activity rows with :date and :tss."
  [activities]
  (reduce (fn [m {:keys [date tss]}] (if (and date tss) (update m date (fnil + 0.0) tss) m))
          {} activities))

;; ---------------------------------------------------------------------------
;; Volume

(defn iso-week [date]
  (let [d (LocalDate/parse date)]
    (format "%d-W%02d" (.get d IsoFields/WEEK_BASED_YEAR) (.get d IsoFields/WEEK_OF_WEEK_BASED_YEAR))))

(defn weekly-volume
  "Per ISO week and sport: count, moving time, distance, elevation, TSS."
  [activities]
  (->> activities
       (group-by (juxt (comp iso-week :date) :sport))
       (map (fn [[[week sport] as]]
              {:week        week
               :week_start  (str (.with (LocalDate/parse (:date (first as)))
                                        java.time.DayOfWeek/MONDAY))
               :sport       sport
               :count       (count as)
               :duration_s  (round (reduce + 0.0 (keep #(or (:moving_s %) (:duration_s %)) as)) 0)
               :distance_m  (round (reduce + 0.0 (keep :distance_m as)) 0)
               :elev_gain_m (round (reduce + 0.0 (keep :elev_gain_m as)) 0)
               :tss         (round (reduce + 0.0 (keep :tss as)) 1)}))
       (sort-by (juxt :week :sport))))

;; ---------------------------------------------------------------------------
;; Wellness trends

(def baseline-days 60)

(defn trend
  "For each day: the value, 7- and 28-day rolling means (ignoring missing
  days), and a 60-day baseline mean ± 1 SD (the normal range, e.g. for HRV)."
  [rows k]
  (let [v (vec rows)]
    (vec
     (for [i (range (count v))]
       (let [window (fn [n] (keep k (subvec v (max 0 (- i (dec n))) (inc i))))
             base   (window baseline-days)
             m      (mean base)
             s      (sd base)]
         {:date      (:date (v i))
          :value     (get (v i) k)
          :avg7      (round (mean (window 7)) 1)
          :avg28     (round (mean (window 28)) 1)
          :base_low  (when (and m s) (round (- m s) 1))
          :base_high (when (and m s) (round (+ m s) 1))})))))
