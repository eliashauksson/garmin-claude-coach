(ns coach.plan-rules
  "Checks a proposed or planned week against training-principles.md (Load &
  recovery, Intensity distribution, ADS, Strength / ME, Running: Progression,
  Plan procedure step 7) and the athlete's overrides in athlete.md. Pure;
  returns warnings, it never rejects - the athlete decides."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.season :as season]))

(def sessions
  "Session library (principles: Running › Sessions, Strength / ME, Testing,
  Cross-training). id -> {:label :kind (:run :strength :hike :ride) and flags}:
  :hard?   a quality session; counts toward the weekly hard-session limit
  :z3?     Z3-Z5 work, not allowed with ADS
  :long?   a long session (next day easy, spacing and long-run checks)
  :key?    needs fresh legs although not hard (tests)
  :b2b?    the second day of a back-to-back; may follow a long day
  :race-sim? exempt from the long-run growth checks
  :test?   a test; exempt from the ADS check
  :heavy-legs? max strength / ME for the legs: not within 48 h before a key
           session, not in the final 10 days
  :easy?   easy effort (Rec-Z2)"
  {"REC"         {:label "Recovery run" :kind :run :easy? true}
   "EASY"        {:label "Easy run" :kind :run :easy? true}
   "EASY_ST"     {:label "Easy run + strides" :kind :run :easy? true}
   "HILL_SPRINT" {:label "Easy run + hill sprints" :kind :run :easy? true}
   "DOWNHILL"    {:label "Downhill conditioning" :kind :run :easy? true}
   "LONG"        {:label "Long run" :kind :run :long? true :easy? true}
   "B2B"         {:label "Back-to-back run (day after a long run)" :kind :run :long? true :b2b? true :easy? true}
   "LONG_RP"     {:label "Long run with race-pace blocks" :kind :run :long? true :hard? true}
   "RACE_PACE"   {:label "Race-pace intervals" :kind :run :hard? true}
   "TEMPO"       {:label "Tempo (Z3)" :kind :run :hard? true :z3? true}
   "THR"         {:label "Threshold (Z4)" :kind :run :hard? true :z3? true}
   "VO2"         {:label "VO2max intervals (Z5)" :kind :run :hard? true :z3? true}
   "HILL_REP"    {:label "Hill repeats / uphill ME" :kind :run :hard? true :z3? true}
   "RACE_SIM"    {:label "Race simulation (several runs with breaks)" :kind :run :long? true :race-sim? true}
   "AET_TEST"    {:label "AeT drift test" :kind :run :key? true :test? true}
   "LT_TEST"     {:label "LT test (30 min time trial)" :kind :run :hard? true :z3? true :test? true}
   "HIKE"        {:label "Hike / time on feet" :kind :hike :easy? true}
   "RIDE"        {:label "Easy ride" :kind :ride :easy? true}
   "GYM_LOWER"   {:label "Gym: lower body (max strength / ME)" :kind :strength :heavy-legs? true}
   "GYM_UPPER"   {:label "Gym: upper body + core" :kind :strength}
   "GYM_MAINT"   {:label "Gym: short maintenance" :kind :strength}})

(def session-ids (vec (keys sessions)))

(defn- flag [w k] (get-in sessions [(:session_id w) k]))

(defn run? [w] (= :run (flag w :kind)))
(defn hard? [w] (boolean (flag w :hard?)))
(defn long? [w] (boolean (flag w :long?)))

(defn key-session?
  "Hard, long or test sessions: they need fresh legs and are followed by an
  easy day (Load & recovery)."
  [w]
  (boolean (or (hard? w) (long? w) (flag w :key?))))

(def session-guide
  "The session IDs, for Claude's prompts."
  (str/join "\n" (for [[id {:keys [label]}] (sort-by key sessions)] (str "- " id ": " label))))

(defn- minutes [ws] (reduce + 0 (keep :duration_min ws)))

(defn check-week
  "Warnings for `workouts` planned in the week starting `monday`.
  `prev` is {:run_min :longest_min :peak_min} from what was actually done:
  last week's running time, the longest run and the highest weekly running
  time of the recent weeks. `phase` and `race-date` come from the season
  plan, `settings` from athlete.md (coach.athlete/settings). `full-week?`
  says the workouts are the whole week (not just the days from today).
  Checks without their values are skipped."
  [monday workouts {:keys [prev phase race-date settings full-week?]}]
  (let [{:keys [lt_hr easy_hr_max quality_max run_increase_pct long_run_max_min]} settings
        sunday    (season/add-days monday 6)
        run-min   (minutes (filter run? workouts))
        endurance (minutes (filter #(#{:run :hike :ride} (flag % :kind)) workouts))
        longs     (filter #(and (run? %) (long? %) (not (flag % :race-sim?))) workouts)
        longest   (apply max 0 (keep :duration_min longs))
        race-sim? (some #(flag % :race-sim?) workouts)
        by-date   (group-by :date workouts)
        dates     (map #(season/add-days monday %) (range 7))
        ;; back up to a level already held: +20%; above it the athlete's limit, else 10% (d)
        cap       (if (<= run-min (or (:peak_min prev) 0)) 20 (or run_increase_pct 10))
        hard      (filter hard? workouts)
        q-max     (or quality_max 2)
        gap       (when (and lt_hr easy_hr_max) (/ (- lt_hr easy_hr_max) (double lt_hr)))
        key-day?  (fn [d] (some key-session? (by-date d)))]
    (vec
     (remove nil?
             (concat
              [(when (some #(or (neg? (compare (:date %) monday)) (pos? (compare (:date %) sunday))) workouts)
                 (t "Some sessions fall outside this week."))
               (when (and (:run_min prev) (pos? (:run_min prev))
                          (> run-min (* (+ 1 (/ cap 100.0)) (:run_min prev))))
                 (t "Running time {0} min is more than +{1}% over last week's {2} min (Running: Progression)."
                    run-min cap (long (:run_min prev))))
               (when (and long_run_max_min (> longest long_run_max_min))
                 (t "Long run {0} min is over the {1} min cap (Running: Progression)." longest (long long_run_max_min)))
               (when (and (:longest_min prev) (pos? (:longest_min prev)) (pos? longest) (not race-sim?)
                          (> longest (+ 30 (:longest_min prev))))
                 (t "Long run {0} min is more than +30 min over the previous longest {1} min (Running: Progression)."
                    longest (long (:longest_min prev))))
               (when (and (pos? longest) (> longest (* 0.35 endurance)))
                 (t "Long run {0} min is more than 35% of the week's {1} min endurance time (Load & recovery)."
                    longest endurance))
               (when (> (count hard) q-max)
                 (t "{0} hard sessions; the limit is {1} a week (Intensity distribution)." (count hard) (long q-max)))
               (when (and gap (> gap 0.10) (some #(and (flag % :z3?) (not (flag % :test?))) workouts))
                 (t "AeT-LT gap is {0}% (ADS): no Z3-Z5 sessions, only strides, hill sprints and strength (ADS)."
                    (format "%.0f" (* 100 gap))))
               (when (and (= "Transition" phase) (some #(and (hard? %) (not (flag % :test?))) workouts))
                 (t "No hard sessions in Transition: easy, unstructured training (Periodization)."))
               (when (some #(and (key-day? %)
                                 (some (fn [w] (and (key-session? w) (not (flag w :b2b?))))
                                       (by-date (season/add-days % 1))))
                           dates)
                 (t "Hard or long sessions on consecutive days: the day after is easy (Load & recovery)."))
               (when (and full-week?
                          (not-any? (fn [d] (every? #(= "REC" (:session_id %)) (by-date d))) dates))
                 (t "No rest day (Load & recovery: at least one a week; a recovery run alone counts)."))
               (when (and full-week? (not= "Race week" phase)
                          (not-any? #(= :strength (flag % :kind)) workouts))
                 (t "No strength session: never below maintenance, 1 a week (Core)."))
               (when (and race-date (some #(and (flag % :heavy-legs?)
                                                (<= 0 (season/days-between (:date %) race-date) 10))
                                          workouts))
                 (t "No lower-body max strength or ME in the final 10 days before the race (Strength / ME)."))]
              (for [k   (filter key-session? workouts)
                    gym (filter #(flag % :heavy-legs?) workouts)
                    :let [gap-d (season/days-between (:date gym) (:date k))
                          hrs   (- (* 24 gap-d) (if (= "PM" (:slot gym)) 10 0))]
                    :when (<= 0 hrs 47)]
                (t "{0} on {1} is less than 48 h before {2} on {3} (Strength / ME)."
                   (:session_id gym) (:date gym) (:session_id k) (:date k))))))))
