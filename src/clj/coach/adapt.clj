(ns coach.adapt
  "Signals that the plan should adapt (training-principles.md: Monitoring, Load & recovery),
  computed in code from recent activities, feedback, wellness and the plan.
  A triggering signal leads to an adjustment proposal; the athlete decides."
  (:require [clojure.string :as str]
            [coach.analysis :as analysis]
            [coach.i18n :refer [t]]
            [coach.metrics :as m]
            [coach.plan-rules :as rules]
            [coach.season :as season]))

(def drift-max "Long-run HR drift (decoupling, %) above which the next long run shouldn't grow (Monitoring)." 8)

(defn- run? [a] (re-find #"running" (str (:sport a))))

(defn signals
  "All current signals. Each is {:key :id :severity (:info :warn :high)
  :trigger? :message :principle}. `key` is stable per occurrence, so the same
  signal never triggers twice.
  `ctx`: {:today :acts (last 6 weeks, newest last, with metrics/feedback)
          :wellness (last 3 days, oldest first) :workouts (this + next week,
          with status) :tsb :phase (season plan, or nil)
          :settings (the athlete's thresholds: :steady_hr :rhr_high, athlete.md: Baselines; a
          signal without its threshold is skipped)}"
  [{:keys [today acts wellness workouts tsb settings]}]
  (let [monday    (season/monday today)
        week-acts (filter #(<= (compare monday (:date %)) 0) acts)
        planned-by-act (into {} (keep #(when (:activity_id %) [(:activity_id %) %])) workouts)
        session-of #(:session_id (planned-by-act (:id %)))
        easy-run?  #(and (run? %) (or (some-> (session-of %) rules/sessions :easy?)
                                      (and (nil? (session-of %)) (< (analysis/active-secs %) (* 75 60)))))
        long-run?  #(and (run? %) (or (some-> (session-of %) rules/sessions :long?) (>= (analysis/active-secs %) (* 75 60))))
        recent     (filter #(>= (compare (:date %) (season/add-days today -7)) 0) acts)
        steady-hr  (:steady_hr settings)
        too-hard   (filter #(and steady-hr (easy-run? %) (:avg_hr %) (> (:avg_hr %) steady-hr)) week-acts)
        last-long  (last (filter long-run? recent))
        upcoming-long (filter #(and (= "planned" (:status %)) (rules/long? %)
                                    (>= (compare (:date %) today) 0))
                              workouts)
        high-rpe   (filter #(and (or (easy-run? %) (long-run? %))
                                 (some-> (analysis/effective-rpe %) (>= 8))) recent)
        low-rpe-keys (take-last 2 (filter #(and (run? %) (some-> (session-of %) rules/sessions :hard?)
                                                (some-> (analysis/effective-rpe %) (<= 3)))
                                          (filter #(>= (compare (:date %) (season/add-days today -14)) 0) acts)))
        pain       (filter #(and (pos? (or (:pain %) 0)) (>= (compare (:date %) (season/add-days today -3)) 0)) acts)
        last2      (take-last 2 wellness)
        hrv-bad    (and (= 2 (count last2))
                        (every? #(#{"UNBALANCED" "LOW"} (some-> (:hrv_status %) str/upper-case)) last2))
        rhr-high   (:rhr_high settings)
        rhr-bad    (and rhr-high (= 2 (count last2)) (every? #(some-> (:rhr %) (>= rhr-high)) last2))
        this-week  (filter #(<= (compare monday (:date %)) 0 (compare (season/add-days monday 6) (:date %))) workouts)
        missed     (filter #(= "missed" (:status %)) this-week)
        run-min    (fn [ws] (reduce + 0 (keep #(when (rules/run? %) (:duration_min %)) ws)))
        week-min   (run-min this-week)
        missed-min (run-min missed)
        ef-of      (fn [from to] (m/mean (keep #(when (and (easy-run? %) (<= (compare from (:date %)) 0)
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
          :principle "Monitoring"
          :message (if (= 1 (count too-hard))
                     (t "1 easy run this week averaged above {0} bpm (that's steady, not easy)" steady-hr)
                     (t "{0} easy runs this week averaged above {1} bpm (that's steady, not easy)" (count too-hard) steady-hr))}])
      (when (and last-long (:decoupling last-long) (> (:decoupling last-long) drift-max))
        [{:key (str "drift:" (:id last-long)) :id :long-drift :severity :warn
          :trigger? (boolean (some #(> (or (:distance_km %) 0) (/ (or (:distance_m last-long) 0) 1000.0)) upcoming-long))
          :principle "Monitoring"
          :message (t "Long run on {0} drifted {1}% in the second half: don't lengthen the next long run"
                      (:date last-long) (format "%.1f" (double (:decoupling last-long))))}])
      (for [a high-rpe]
        {:key (str "rpe-high:" (:id a)) :id :rpe-high :severity :warn :trigger? true :principle "Monitoring"
         :message (t "RPE {0} on an easy/long run ({1}): fatigue signal" (Math/round (double (analysis/effective-rpe a))) (:date a))})
      (when (= 2 (count low-rpe-keys))
        [{:key (str "rpe-low:" (clojure.string/join "," (map :id low-rpe-keys))) :id :too-easy :severity :info
          :trigger? true :principle "Load & recovery"
          :message (t "The last two key sessions felt easy (RPE ≤ 3): room to progress within the rules")}])
      (for [a pain]
        {:key (str "pain:" (:id a)) :id :pain :severity :high :trigger? true :principle "Monitoring"
         :message (t "Pain reported after {0} ({1}){2} - stop what provokes it, use a pain-free low-impact substitute; see a professional if it lasts over a week"
                     (:name a) (:date a) (or (some->> (:pain_location a) (str ": ")) ""))})
      (when hrv-bad
        [{:key (str "hrv:" (:date (last last2))) :id :hrv-unbalanced :severity :warn :trigger? true :principle "Monitoring"
          :message (t "HRV unbalanced or low for 2 days: rest of the week as recovery, plan next week lower")}])
      (when rhr-bad
        [{:key (str "rhr:" (:date (last last2))) :id :rhr-high :severity :warn :trigger? true :principle "Monitoring"
          :message (t "Resting HR {0} or higher for 2 days: swap hard sessions for easy ones or rest" rhr-high)}])
      (for [w missed :when (rules/key-session? w)]
        {:key (str "missed:" (:id w)) :id :missed-key :severity :warn :trigger? true :principle "Monitoring"
         :message (t "Missed {0} ({1}) on {2}: move it only if the hard/easy spacing holds, or drop it - never make it up"
                     (:title w) (:session_id w) (:date w))})
      (when (and (pos? week-min) (> missed-min (* 0.3 week-min)))
        [{:key (str "missed-vol:" monday) :id :missed-volume :severity :warn :trigger? true :principle "Monitoring"
          :message (t "{0} of {1} planned running minutes missed this week (> 30%): don't raise volume next week"
                      (long missed-min) (long week-min))}])
      (when (and ef-now ef-before (> ef-now (* 1.05 ef-before)))
        [{:key (str "ef-up:" monday) :id :fitter :severity :info :trigger? false :principle "Zones"
          :message (t "Efficiency on easy runs is up {0}% vs the 3 weeks before: paces may need recalibrating"
                      (format "%.0f" (* 100 (- (/ ef-now ef-before) 1))))}])
      (when (and tsb (< tsb -30))
        [{:key (str "tsb:" today) :id :deep-fatigue :severity :info :trigger? false :principle "trend"
          :message (t "Form (TSB) is {0}: recorded load is high" (Math/round (double tsb)))}])))))
