(ns coach.adapt
  "Signals that the plan should adapt (training-principles.md §6.4, §7, §9),
  computed in code from recent activities, feedback, wellness and the plan.
  A triggering signal leads to an adjustment proposal; the athlete decides."
  (:require [coach.analysis :as analysis]
            [coach.plan-rules :as rules]
            [coach.season :as season]))

(def easy-sessions #{"EASY" "EASY_ST" "REC" "LONG"})
(def drift-max "Long-run HR drift (decoupling, %) above which the next long run shouldn't grow (§7.2)." 8)

(defn- run? [a] (re-find #"running" (str (:sport a))))
(defn- secs [a] (or (:moving_s a) (:duration_s a) 0))
(defn- mean [xs] (when (seq xs) (/ (reduce + xs) (count xs))))

(defn signals
  "All current signals. Each is {:key :id :severity (:info :warn :high)
  :trigger? :message :principle}. `key` is stable per occurrence, so the same
  signal never triggers twice.
  `ctx`: {:today :acts (last 6 weeks, newest last, with metrics/feedback)
          :wellness (last 3 days, oldest first) :workouts (this + next week,
          with status) :tsb :phase (season plan, or nil)
          :settings (the athlete's thresholds: :steady_hr :rhr_high, §A3; a
          signal without its threshold is skipped)}"
  [{:keys [today acts wellness workouts tsb phase settings]}]
  (let [monday    (season/monday today)
        week-acts (filter #(<= (compare monday (:date %)) 0) acts)
        planned-by-act (into {} (keep #(when (:activity_id %) [(:activity_id %) %])) workouts)
        session-of #(:session_id (planned-by-act (:id %)))
        easy-run?  #(and (run? %) (or (easy-sessions (session-of %))
                                      (and (nil? (session-of %)) (< (secs %) (* 75 60)))))
        long-run?  #(and (run? %) (or (= "LONG" (session-of %)) (>= (secs %) (* 75 60))))
        recent     (filter #(>= (compare (:date %) (season/add-days today -7)) 0) acts)
        steady-hr  (:steady_hr settings)
        too-hard   (filter #(and steady-hr (easy-run? %) (:avg_hr %) (> (:avg_hr %) steady-hr)) week-acts)
        last-long  (last (filter long-run? recent))
        upcoming-long (filter #(and (= "planned" (:status %)) (get-in rules/sessions [(:session_id %) :long?])
                                    (>= (compare (:date %) today) 0))
                              workouts)
        high-rpe   (filter #(and (or (easy-run? %) (long-run? %))
                                 (some-> (analysis/effective-rpe %) (>= 8))) recent)
        low-rpe-keys (take-last 2 (filter #(and (run? %) (some-> (session-of %) rules/sessions :key?)
                                                (some-> (analysis/effective-rpe %) (<= 3)))
                                          (filter #(>= (compare (:date %) (season/add-days today -14)) 0) acts)))
        pain       (filter #(and (pos? (or (:pain %) 0)) (>= (compare (:date %) (season/add-days today -3)) 0)) acts)
        last2      (take-last 2 wellness)
        hrv-bad    (and (= 2 (count last2))
                        (every? #(#{"UNBALANCED" "LOW"} (some-> (:hrv_status %) clojure.string/upper-case)) last2))
        rhr-high   (:rhr_high settings)
        rhr-bad    (and rhr-high (= 2 (count last2)) (every? #(some-> (:rhr %) (>= rhr-high)) last2))
        this-week  (filter #(<= (compare monday (:date %)) 0 (compare (season/add-days monday 6) (:date %))) workouts)
        missed     (filter #(= "missed" (:status %)) this-week)
        km         (fn [ws] (reduce + 0 (keep #(when (rules/run? %) (:distance_km %)) ws)))
        week-km    (km this-week)
        missed-km  (km missed)
        ef-of      (fn [from to] (mean (keep #(when (and (easy-run? %) (<= (compare from (:date %)) 0)
                                                    (neg? (compare (:date %) to)))
                                                 (:ef %))
                                             acts)))
        ef-now     (ef-of (season/add-days today -21) (season/add-days today 1))
        ef-before  (ef-of (season/add-days today -42) (season/add-days today -21))]
    (vec
     (concat
      (when (seq too-hard)
        [{:key (str "easy-hard:" monday ":" (min 3 (count too-hard))) :id :easy-too-hard
          :severity (if (>= (count too-hard) 3) :warn :info) :trigger? (>= (count too-hard) 3)
          :principle "§7.2"
          :message (str (count too-hard) " easy run" (when (> (count too-hard) 1) "s")
                        " this week averaged above " steady-hr " bpm (that's steady, not easy)")}])
      (when (and last-long (:decoupling last-long) (> (:decoupling last-long) drift-max))
        [{:key (str "drift:" (:id last-long)) :id :long-drift :severity :warn
          :trigger? (boolean (some #(> (or (:distance_km %) 0) (/ (or (:distance_m last-long) 0) 1000.0)) upcoming-long))
          :principle "§7.2"
          :message (format "Long run on %s drifted %.1f%% in the second half: don't lengthen the next long run"
                           (:date last-long) (double (:decoupling last-long)))}])
      (for [a high-rpe]
        {:key (str "rpe-high:" (:id a)) :id :rpe-high :severity :warn :trigger? true :principle "§7.2"
         :message (str "RPE " (Math/round (double (analysis/effective-rpe a))) " on an easy/long run (" (:date a) "): fatigue signal")})
      (when (= 2 (count low-rpe-keys))
        [{:key (str "rpe-low:" (clojure.string/join "," (map :id low-rpe-keys))) :id :too-easy :severity :info
          :trigger? true :principle "§1.4, §9.1"
          :message "The last two key sessions felt easy (RPE ≤ 3): room to progress within the rules"}])
      (for [a pain]
        {:key (str "pain:" (:id a)) :id :pain :severity :high :trigger? true :principle "§9.1"
         :message (str "Pain reported after " (:name a) " (" (:date a) ")"
                       (some->> (:pain_location a) (str ": ")) " - stop progression; see a doctor if > 3/10 or limping")})
      (when hrv-bad
        [{:key (str "hrv:" (:date (last last2))) :id :hrv-unbalanced :severity :warn :trigger? true :principle "§7.1"
          :message "HRV unbalanced or low for 2 days: reduce the week by 20%, no key session"}])
      (when rhr-bad
        [{:key (str "rhr:" (:date (last last2))) :id :rhr-high :severity :warn :trigger? true :principle "§7.1"
          :message (str "Resting HR " rhr-high " or higher for 2 days: no key sessions until it settles")}])
      (for [w missed :when (or (rules/key-session? w phase)
                               (get-in rules/sessions [(:session_id w) :long?]))]
        {:key (str "missed:" (:id w)) :id :missed-key :severity :warn :trigger? true :principle "§6.4"
         :message (str "Missed " (:title w) " (" (:session_id w) ") on " (:date w)
                       ": move it within the rules or drop it - never make it up")})
      (when (and (pos? week-km) (> missed-km (* 0.3 week-km)))
        [{:key (str "missed-vol:" monday) :id :missed-volume :severity :warn :trigger? true :principle "§6.4"
          :message (format "%.0f of %.0f planned km missed this week (> 30%%): don't raise volume next week"
                           (double missed-km) (double week-km))}])
      (when (and ef-now ef-before (> ef-now (* 1.05 ef-before)))
        [{:key (str "ef-up:" monday) :id :fitter :severity :info :trigger? false :principle "§2"
          :message (format "Efficiency on easy runs is up %.0f%% vs the 3 weeks before: paces may need recalibrating"
                           (* 100 (- (/ ef-now ef-before) 1)))}])
      (when (and tsb (< tsb -30))
        [{:key (str "tsb:" today) :id :deep-fatigue :severity :info :trigger? false :principle "trend"
          :message (str "Form (TSB) is " (Math/round (double tsb)) ": recorded load is high")}])))))
