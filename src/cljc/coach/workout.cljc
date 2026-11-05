(ns coach.workout
  "Pure. A planned session's structure (blocks of steps with a target each)
  and how a recorded activity compares with it.

  blocks: [{:label \"3 × threshold\" :repeat 3
            :steps [{:kind \"work\" :duration_s 600 :target \"pace\"
                     :from \"4:20\" :to \"4:30\" :note \"relaxed form\"}]}]
  :kind   warmup, steady, work, recovery, cooldown
  :target hr (bpm), pace (\"m:ss\" min/km), power (W) or open (none).
  :from/:to may come in either order; a single value (or both equal) means
  \"stay at\" that value, compared within a small tolerance."
  (:require [clojure.string :as str]))

(def kinds ["warmup" "steady" "work" "recovery" "cooldown"])
(def target-types ["hr" "pace" "power" "open"])

(def tolerance
  "Half-width of the band around a single-value target."
  {"hr" 2 "pace" 5 "power" 5})

(defn- parse-num [s]
  (let [s (str/trim (str s))]
    (when (re-matches #"\d+(\.\d+)?" s)
      #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))))

(defn parse-value
  "A target value as a number: bpm, W, or seconds per km for pace (\"4:20\")."
  [target v]
  (when (some? v)
    (if (= "pace" target)
      (let [s (str/trim (str v))]
        (if-let [[_ m sec] (re-matches #"(\d+):(\d{1,2})" s)]
          (+ (* 60 (parse-num m)) (parse-num sec))
          (parse-num s)))
      (parse-num v))))

(defn pace-str
  "Seconds per km as \"m:ss\" (min/km)."
  [spk]
  (when (and spk (pos? spk))
    (let [s (Math/round (double spk))]
      (str (quot s 60) ":" (let [r (mod s 60)] (if (< r 10) (str "0" r) (str r)))))))

(defn bounds
  "[low high] of a step's target (numbers; pace in s/km), or nil."
  [{:keys [target from to]}]
  (when (and target (not= "open" target))
    (let [vs (keep #(parse-value target %) [from to])]
      (when (seq vs)
        (let [lo (apply min vs) hi (apply max vs)]
          (if (= lo hi)
            (let [d (get tolerance target 0)] [(- lo d) (+ hi d)])
            [lo hi]))))))

(defn- step-s [s] (max 0 (or (:duration_s s) 0)))

(defn total-s
  "The whole session's planned time in seconds."
  [blocks]
  (reduce + 0 (for [{:keys [repeat steps]} blocks]
                (* (max 1 (or repeat 1)) (reduce + 0 (map step-s steps))))))

(defn expand
  "Flat segments with cumulative times, repeats unrolled:
  [{:start :end :kind :target :from :to :low :high :label :note :block :rep :reps}]."
  [blocks]
  (loop [todo (for [[bi {:keys [label repeat steps]}] (map-indexed vector blocks)
                    :let [n (max 1 (or repeat 1))]
                    rep (range n)
                    s steps]
                (assoc s :label label :block bi :rep (inc rep) :reps n))
         t 0
         out []]
    (if-let [s (first todo)]
      (let [d (step-s s) [lo hi] (bounds s)]
        (recur (rest todo) (+ t d)
               (conj out (cond-> (assoc s :start t :end (+ t d))
                           lo (assoc :low lo :high hi)))))
      out)))

(defn targets
  "Target types present in the blocks (without open), in display order."
  [blocks]
  (let [present (set (keep #(when (bounds %) (:target %)) (mapcat :steps blocks)))]
    (filterv present ["hr" "pace" "power"])))

(defn stream-values
  "The stream's values for a target type: bpm, s/km (from m/s) or W."
  [stream target]
  (case target
    "hr"    (:hr stream)
    "power" (:power stream)
    "pace"  (mapv #(when (and % (> % 1.0)) (/ 1000.0 %)) (:speed stream))
    nil))

(defn- mean [xs] (when (seq xs) (/ (reduce + xs) (count xs))))

(defn compare-segments
  "Each segment with :actual, the average of its own target's metric over
  the stream samples with t in [start, end) (time from the start of the
  activity), and :verdict: :in, :low or :high (numerically: for pace :low
  is faster than planned). Segments without a target or without samples
  have neither."
  [segments stream]
  (let [ts (:t stream)]
    (mapv (fn [{:keys [start end target low] :as seg}]
            (if-not low
              seg
              (let [pace?  (= "pace" target)
                    ;; pace: the mean speed as pace (a mean of paces overweights slow samples)
                    vs     (if pace? (mapv #(when (and % (> % 1.0)) %) (:speed stream)) (stream-values stream target))
                    m      (mean (keep (fn [[t v]] (when (and t v (<= start t) (< t end)) v))
                                   (map vector ts vs)))
                    sample (if (and pace? m) (/ 1000.0 m) m)]
                (if-not sample
                  seg
                  (assoc seg :actual sample
                         :verdict (cond (< sample (:low seg)) :low
                                        (> sample (:high seg)) :high
                                        :else :in))))))
          segments)))
