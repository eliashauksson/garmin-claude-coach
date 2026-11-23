(ns coach.cycle
  "Menstrual cycle tracking (opt-in, women only; planner/cycle-rules). Pure: period starts, the typical cycle
  length, the phase of a day (counted from the last period start, so ovulation and the luteal phase are
  estimates), the next expected period, flags worth a doctor's attention, and what the planner is told.
  Evidence: no phase-based periodization (McNulty 2020, Colenso-Semple 2023), so the phase is context for
  symptoms and readiness, not a training rule. With hormonal contraception there is no natural cycle:
  only bleeding days, no ovulation or luteal phase."
  (:require [coach.season :as season])
  (:import (java.time LocalDate)
           (java.time.temporal ChronoUnit)))

(def default-length "Cycle length (days) until the athlete has two periods recorded." 28)
(def min-length "Shorter gaps between starts are not a cycle (a bleed logged twice)." 15)
(def max-length "Longer gaps are a missed period, not a cycle length." 60)
(def missed-after "Days without a period start after which a natural cycle is flagged." 45)
(def symptoms
  "The symptoms the athlete can log (severity 1 mild, 2 moderate, 3 severe): the ones that change how training
  feels, in the order they are shown."
  ["cramps" "fatigue" "heavy_legs" "low_motivation" "sleep" "headache" "mood" "back_pain" "bloating"
   "breast_tenderness" "cravings" "gi" "acne" "dizziness"])

(def tag-groups
  "What else she can log, as yes/no tags in groups (the order they are shown). Only `coach-tag-groups` ever reach
  the planner's prompt; sex and discharge stay on her computer."
  [["mood" ["happy" "calm" "energetic" "motivated" "sad" "anxious" "irritable" "mood_swings" "stressed"]]
   ["sex" ["sex_protected" "sex_unprotected" "high_drive" "low_drive"]]
   ["discharge" ["dry" "sticky" "creamy" "egg_white" "watery"]]
   ["other" ["ovulation_test_positive" "ovulation_test_negative" "pill_taken" "illness" "alcohol" "travel"]]])

(def coach-tags "Tags the planner may see: how she feels and whether she is ill."
  (into #{"illness"} (second (first tag-groups))))

(def all-tags (vec (mapcat second tag-groups)))

(defn canonical-tags
  "A log row's tags as a vector in the usual order: unknown names and repeats dropped."
  [tags]
  (let [have (set (map name tags))]
    (filterv have all-tags)))
(def flows
  "Spotting (a little, or more) is light bleeding that is no period; the others are bleeding days."
  ["spotting" "spotting_more" "light" "medium" "heavy"])
(def spotting-flows #{"spotting" "spotting_more"})
(def bleeding-flows #{"light" "medium" "heavy"})
(def flag-window-days "How far back the flags look (Apple's deviation checks use six months)." 183)

(defn canonical-symptoms
  "A log row's symptoms as {name severity 1-3}: keys as strings, the old \"nausea\" read as \"gi\", unknown names and
  severities dropped."
  [m]
  (reduce (fn [acc [k v]]
            (let [k (name k) k (if (= "nausea" k) "gi" k)]
              (if (and (some #{k} symptoms) (number? v) (<= 1 v 3))
                (update acc k (fnil max 0) (long v))
                acc)))
          {} m))

(defn- days-between [a b] (.between ChronoUnit/DAYS (LocalDate/parse a) (LocalDate/parse b)))

(defn- runs
  "Groups sorted ISO dates into runs of consecutive days."
  [dates]
  (reduce (fn [acc d]
            (let [prev (some-> acc peek peek)]
              (if (and prev (= 1 (days-between prev d)))
                (update acc (dec (count acc)) conj d)
                (conj acc [d]))))
          [] (sort dates)))

(defn period-starts
  "Sorted ISO dates on which a period started. `log` rows {:date :period_start} are the athlete's own entries and
  win; `days` rows {:date :period :predicted} come from the watch's app (first day of each run of bleeding days)."
  [log days]
  (let [logged (->> log (filter #(= 1 (:period_start %))) (map :date))
        synced (->> days (filter #(and (= 1 (:period %)) (not= 1 (:predicted %)))) (map :date) runs (map first)
                    (remove (fn [g] (some #(<= (Math/abs (days-between % g)) 4) logged))))]
    (vec (sort (distinct (concat logged synced))))))

(def max-period-days "A bleed longer than this is no period: an end logged later belongs to no start." 14)

(defn period-ends
  "Sorted ISO dates she marked as the last bleeding day of a period (`log` rows {:date :period_end})."
  [log]
  (vec (sort (for [r log :when (= 1 (:period_end r))] (:date r)))))

(defn period-length
  "Days the period that started on `start` lasted according to the logged `ends`, nil without one in reach."
  [ends start]
  (some (fn [e] (let [n (days-between start e)] (when (<= 0 n (dec max-period-days)) (inc n)))) ends))

(defn lengths
  "Cycle lengths in days between consecutive starts; gaps that are no cycle length are left out."
  [starts]
  (->> (partition 2 1 starts)
       (map (fn [[a b]] (days-between a b)))
       (filter #(<= min-length % max-length))
       vec))

(defn- median [xs]
  (let [ls (vec (sort xs))
        n  (count ls)]
    (if (odd? n)
      (nth ls (quot n 2))
      (Math/round (/ (+ (nth ls (dec (quot n 2))) (nth ls (quot n 2))) 2.0)))))

(defn typical-length
  "Median of the last six cycle lengths, `default-length` before there are any."
  [starts]
  (let [ls (take-last 6 (lengths starts))]
    (if (empty? ls) default-length (median ls))))

(defn bleed-dates
  "Sorted set of the days she bled: logged period starts and light/medium/heavy flow (spotting is no bleeding day),
  every day from a logged start to its logged end, and the watch's bleeding days that are no prediction."
  [log days]
  (let [ends (period-ends log)]
  (into (sorted-set)
        cat
        [(for [r log :when (or (= 1 (:period_start r)) (bleeding-flows (:flow r)))] (:date r))
         (for [r log :when (= 1 (:period_start r))
               :let [n (period-length ends (:date r))]
               :when n
               i (range n)]
           (season/add-days (:date r) i))
         (for [r days :when (and (= 1 (:period r)) (not= 1 (:predicted r)))] (:date r))])))

(defn- run-length
  "How many consecutive days from `start` are in the `bleed` set."
  [bleed start]
  (loop [d start n 0]
    (if (contains? bleed d) (recur (season/add-days d 1) (inc n)) n)))

(defn cycle-list
  "The last `n` cycles as [{:start :days}], oldest first (gaps that are no cycle length left out)."
  [starts n]
  (->> (partition 2 1 starts)
       (keep (fn [[a b]] (let [d (days-between a b)] (when (<= min-length d max-length) {:start a :days d}))))
       (take-last n)
       vec))

(defn hormonal? [contraception] (= "hormonal" contraception))

(defn day-info
  "The cycle day and phase of `date`, nil before the first start. `ctx` {:starts :typical :contraception :ends
  (the logged last bleeding days: the menstrual phase lasts until one, else 5 days)}.
  Phases: :menstrual :follicular :ovulation :luteal :late-luteal, :overdue when the period is more than a week
  late; with hormonal contraception only :bleed and :no-bleed."
  [{:keys [starts typical contraception ends]} date]
  (when-let [start (last (take-while #(<= (compare % date) 0) starts))]
    (let [day     (inc (days-between start date))
          plen    (or (period-length ends start) 5)
          typical (or typical (typical-length starts))
          ov      (max 10 (- typical 14))
          phase   (cond
                    (hormonal? contraception) (if (<= day plen) :bleed :no-bleed)
                    (> day (+ typical 7))     :overdue
                    (<= day plen)             :menstrual
                    (< day (dec ov))          :follicular
                    (<= day (inc ov))         :ovulation
                    (> day (- typical 5))     :late-luteal
                    :else                     :luteal)]
      {:date date :cycle_day day :phase phase
       :estimated (not (#{:menstrual :bleed :no-bleed :overdue} phase))})))

(defn next-period
  "The ISO date the next period is expected, nil when there is none yet or it is already late."
  [{:keys [starts typical today]}]
  (when-let [start (peek starts)]
    (let [n (season/add-days start (or typical (typical-length starts)))]
      (when (>= (compare n today) 0) n))))

(defn stats
  "The numbers of her cycles for the Today card and the planner: {:typical :cycles (how many lengths it is from)
  :variability (longest minus shortest of the last six, 0 with fewer than two) :period_length (median days of
  bleeding, nil until flow is logged)}. `ctx` {:starts :bleed :today}."
  [{:keys [starts bleed today]}]
  (let [ls   (take-last 6 (lengths starts))
        done (if (and (seq starts) today (< (days-between (peek starts) today) 8)) (butlast starts) starts)
        runs (keep #(let [n (run-length (or bleed #{}) %)] (when (pos? n) n)) (take-last 6 done))]
    {:typical (typical-length starts)
     :cycles (count ls)
     :variability (if (>= (count ls) 2) (- (apply max ls) (apply min ls)) 0)
     :period_length (when (seq runs) (median runs))}))

(defn next-window
  "[from to] around the expected next period: half the variability either side (1 to 5 days), 3 days until there
  are three cycles. Nil when there is no expected period."
  [ctx]
  (when-let [n (next-period ctx)]
    (let [{:keys [cycles variability]} (stats ctx)
          half (if (< cycles 3) 3 (-> (Math/ceil (/ variability 2.0)) long (max 1) (min 5)))]
      [(season/add-days n (- half)) (season/add-days n half)])))

(defn predict
  "`day-info` for every date in [from, to]; days after the expected next period continue the cycle with the
  typical length. `ctx` {:starts :typical :contraception :today}."
  [{:keys [starts typical today] :as ctx} from to]
  (let [typical (or typical (typical-length starts))
        first-n (some-> (peek starts) (season/add-days typical))
        virtual (when (and first-n (pos? (compare first-n today)))
                  (take-while #(<= (compare % to) 0) (iterate #(season/add-days % typical) first-n)))
        ctx     (assoc ctx :typical typical :starts (into (vec starts) virtual))]
    (vec (keep #(day-info ctx %)
               (take-while #(<= (compare % to) 0) (iterate #(season/add-days % 1) from))))))

(defn severity
  "The worst severity (1-3) among a log row's symptoms, 0 without any."
  [row]
  (reduce max 0 (vals (:symptoms row))))

(defn flags
  "What deserves attention, as keywords, over the last six months (a prompt to see a doctor, never a diagnosis;
  Apple's cycle deviation rules, IOC REDs consensus 2023, Bruinvels 2016). Natural cycles only, except
  :heavy-bleeding: :missed-period (no start for 45+ days), :irregular (three cycles, longest minus shortest 17+
  days), :infrequent (one or two periods in six months of tracking), :short-cycles (two under 21 days),
  :prolonged (two periods with 10+ bleeding days), :spotting (spotting in two cycles), :heavy-bleeding (heavy
  flow in two of the last three periods). `ctx` {:starts :log :bleed :contraception :today}."
  [{:keys [starts log bleed contraception today]}]
  (let [natural?  (not (hormonal? contraception))
        since     (season/add-days today (- flag-window-days))
        in-window #(>= (compare % since) 0)
        recent    (filter in-window starts)
        pairs     (->> (partition 2 1 starts)
                       (filter (fn [[_ b]] (in-window b)))
                       (keep (fn [[a b]] (let [d (days-between a b)] (when (<= min-length d max-length) d)))))
        bleed     (or bleed (bleed-dates log nil))
        first-day (first (sort (concat starts (map :date log))))
        tracked   (if first-day (days-between first-day today) 0)
        start     (peek starts)
        heavy?    (fn [s] (some #(and (= "heavy" (:flow %)) (<= 0 (days-between s (:date %)) 7)) log))
        cycle-of  (fn [d] (last (take-while #(<= (compare % d) 0) starts)))
        missed?   (and natural? start (> (days-between start today) missed-after))]
    (cond-> []
      missed?                                                          (conj :missed-period)
      (and natural? (>= (count pairs) 3) (>= (- (apply max pairs) (apply min pairs)) 17)) (conj :irregular)
      (and natural? (not missed?) (>= tracked flag-window-days) (<= 1 (count recent) 2)) (conj :infrequent)
      (and natural? (>= (count (filter #(< % 21) pairs)) 2))           (conj :short-cycles)
      (and natural? (>= (count (filter #(>= (run-length bleed %) 10) recent)) 2)) (conj :prolonged)
      (and natural? (>= (count (distinct (keep #(when (and (spotting-flows (:flow %)) (in-window (:date %)))
                                                  (cycle-of (:date %)))
                                               log)))
                        2))                                            (conj :spotting)
      (>= (count (filter heavy? (take-last 3 starts))) 2)              (conj :heavy-bleeding))))

(defn history
  "The last `n` cycles for the Trends rows, oldest first: [{:start :days :current :bleed [day numbers]
  :spotting [..] :symptoms [..] (moderate or severe) :ovulation (estimated day, natural cycles)}]. The current
  cycle has :days = its typical length, or the days so far when it is longer. `ctx` {:starts :log :bleed
  :contraception :today}."
  [{:keys [starts log bleed contraception today]} n]
  (let [typical (typical-length starts)
        bleed   (or bleed (bleed-dates log nil))
        ends    (concat (rest starts) [nil])]
    (vec (for [[s e] (take-last n (map vector starts ends))
               :let [current? (nil? e)
                     elapsed  (inc (days-between s today))
                     days     (if current? (max typical elapsed) (days-between s e))
                     day-of   #(inc (days-between s %))
                     in-cycle #(and (>= (compare % s) 0) (< (day-of %) (inc days)))]]
           (cond-> {:start s :days days :current current?
                    :bleed    (vec (for [d bleed :when (in-cycle d)] (day-of d)))
                    :spotting (vec (for [r log :when (and (spotting-flows (:flow r)) (in-cycle (:date r)))]
                                     (day-of (:date r))))
                    :symptoms (vec (for [r log :when (and (>= (severity r) 2) (in-cycle (:date r)))]
                                     (day-of (:date r))))}
             (not (hormonal? contraception)) (assoc :ovulation (max 10 (- days 14)))
             current? (assoc :elapsed elapsed))))))

(def symptom-map-days "Cycle days the symptom map covers; later days are a missed period, not a pattern." 45)

(defn symptom-map
  "Her symptoms by day of the cycle over her last `n` cycles (the current one included): {:cycles k (the cycles in
  which she logged a symptom) :rows [{:symptom :total :days {day {:cycles c :max severity}}}]}, most logged first.
  `ctx` {:starts :log}; `day` counts the period start as 1."
  [{:keys [starts log]} n]
  (let [first-start (first (take-last n starts))
        cycle-of    (fn [d] (last (take-while #(<= (compare % d) 0) starts)))
        entries     (for [r log
                          :when (and first-start (seq (:symptoms r)) (>= (compare (:date r) first-start) 0))
                          :let [s (cycle-of (:date r)) day (inc (days-between s (:date r)))]
                          :when (<= day symptom-map-days)
                          [k sev] (:symptoms r)]
                      {:symptom k :start s :day day :severity sev})
        order       (zipmap symptoms (range))]
    {:cycles (count (distinct (map :start entries)))
     :rows (->> (group-by :symptom entries)
                (map (fn [[k es]]
                       {:symptom k :total (count es)
                        :days (into (sorted-map)
                                    (for [[day ds] (group-by :day es)]
                                      [day {:cycles (count (distinct (map :start ds)))
                                            :max (apply max (map :severity ds))}]))}))
                (sort-by (juxt (comp - :total) (comp order :symptom)))
                vec)}))

(defn bar
  "The current cycle as a bar for the Today card: {:days :today :segments [{:phase :from :to :estimated}]
  :window [from-day to-day] (the expected next period)}; nil before the first start. `ctx` as for `predict`."
  [{:keys [starts today] :as ctx}]
  (when-let [start (peek starts)]
    (let [typical (typical-length starts)
          ctx     (assoc ctx :typical typical)
          day     (inc (days-between start today))
          days    (max typical day)
          infos   (for [i (range days)] (day-info ctx (season/add-days start i)))
          segs    (reduce (fn [acc {:keys [phase cycle_day estimated]}]
                            (if (= phase (:phase (peek acc)))
                              (conj (pop acc) (assoc (peek acc) :to cycle_day))
                              (conj acc {:phase phase :from cycle_day :to cycle_day :estimated estimated})))
                          [] infos)]
      {:days days :today day
       :segments (mapv #(update % :phase name) segs)
       :window (some->> (next-window ctx) (mapv #(inc (days-between start %))))})))

(def phase-order [:menstrual :follicular :ovulation :luteal :late-luteal :bleed :no-bleed])
(def pattern-cycles "How many of the latest cycles `patterns` looks at." 6)

(defn- mean [xs] (when (seq xs) (/ (reduce + 0.0 xs) (count xs))))
(defn- round1 [x] (when x (/ (Math/round (* 10.0 x)) 10.0)))

(defn patterns
  "How this athlete's own body behaves across the phases of her last six cycles, so the coach can use her pattern
  instead of a generic one: per phase the mean HRV and resting-HR difference from her mean over the same period,
  the days with a moderate or severe symptom among the logged days, and the mean RPE of her sessions.
  `ctx` {:starts :contraception}; `wellness` rows {:date :hrv_avg :rhr}; `log` rows {:date :symptoms};
  `sessions` rows {:date :rpe}. {:cycles n :phases [{:phase :days :hrv_delta :rhr_delta :logged_days
  :symptom_days :sessions :rpe_avg}]}; each number is from few days, so read it with :cycles."
  [{:keys [starts] :as ctx} wellness log sessions]
  (let [n      (min pattern-cycles (max 0 (dec (count starts))))
        from   (when (seq starts) (nth starts (max 0 (- (count starts) (inc n)))))
        ctx    (assoc ctx :typical (typical-length starts))
        phase  (fn [date] (let [p (when (and from (>= (compare date from) 0)) (:phase (day-info ctx date)))]
                            (when-not (= :overdue p) p)))
        tag    (fn [rows] (keep (fn [r] (when-let [p (phase (:date r))] (assoc r :phase p))) rows))
        w      (tag wellness)
        hrv0   (mean (keep :hrv_avg w))
        rhr0   (mean (keep :rhr w))
        by     (fn [rows] (group-by :phase (tag rows)))
        wb (group-by :phase w) lb (by log) sb (by sessions)]
    {:cycles n
     :phases (vec (for [p phase-order
                        :when (and (pos? n) (or (wb p) (lb p) (sb p)))
                        :let [ws (wb p) ls (lb p) ss (sb p)
                              hrv (mean (keep :hrv_avg ws)) rhr (mean (keep :rhr ws))]]
                    (cond-> {:phase (name p) :days (count ws)
                             :logged_days (count ls) :symptom_days (count (filter #(>= (severity %) 2) ls))
                             :sessions (count (keep :rpe ss))}
                      (and hrv hrv0) (assoc :hrv_delta (round1 (- hrv hrv0)))
                      (and rhr rhr0) (assoc :rhr_delta (round1 (- rhr rhr0)))
                      (seq (keep :rpe ss)) (assoc :rpe_avg (round1 (mean (keep :rpe ss)))))))}))

(defn- shown [info]
  (when info (-> info (update :phase name) (select-keys [:date :cycle_day :phase :estimated]))))

(defn context
  "What the planner is told when tracking is on: today, the target week's expected phases, the recent symptom log
  and the flags. `ctx` {:starts :log :contraception :today}; `from`/`to` the target week."
  [{:keys [starts log contraception today patterns] :as ctx} from to]
  (let [typical (typical-length starts)
        ctx     (assoc ctx :typical typical)]
    (cond-> {:contraception (or contraception "unspecified")
             :periods_recorded (count starts)
             :typical_length_days typical
             :variability_days (:variability (stats ctx))
             :today (shown (day-info ctx today))
             :next_period_expected (next-period ctx)
             :target_week (mapv shown (predict ctx from to))
             :symptoms_last_7_days (vec (for [r log
                                              :when (and (<= (compare (season/add-days today -6) (:date r)) 0)
                                                         (<= (compare (:date r) today) 0)
                                                         (or (seq (:symptoms r)) (:flow r) (:note r)
                                                             (some coach-tags (:tags r))))]
                                          (into {} (remove (comp nil? val))
                                                (assoc (select-keys r [:date :flow :symptoms :note])
                                                       :tags (not-empty (filterv coach-tags (:tags r)))))))
             :flags (mapv name (flags ctx))}
      ;; her own numbers once there is a cycle to compare: HRV, resting HR, symptoms and RPE by phase
      (and patterns (pos? (:cycles patterns))) (assoc :personal_patterns patterns)
      (hormonal? contraception) (assoc :note "hormonal contraception: no natural cycle, bleeding days only"))))
