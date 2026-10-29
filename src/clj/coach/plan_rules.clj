(ns coach.plan-rules
  "Checks a proposed or planned week against the hard rules and sequencing
  rules in training-principles.md (§6.2, §10). Pure; returns warnings, it
  never rejects - the athlete decides."
  (:require [clojure.string :as str]
            [coach.season :as season]))

(def sessions
  "Session library (§5): id -> {:kind :run/:strength, :key? bool, :quality? bool}."
  {"EASY"        {:kind :run}
   "EASY_ST"     {:kind :run}
   "REC"         {:kind :run}
   "HILL_ST"     {:kind :run}
   "HILL_2"      {:kind :run :key? true :quality? true}
   "STEADY"      {:kind :run :key? true :quality? true}
   "THR"         {:kind :run :key? true :quality? true :threshold? true}
   "MP_BLOCK"    {:kind :run :key? true :quality? true}
   "LONG"        {:kind :run :long? true}
   "LONG_MP"     {:kind :run :long? true :key? true :quality? true}
   "DOUBLE"      {:kind :run :long? true :key? true}
   "B2B"         {:kind :run :long? true :key? true}
   "TRIPLE_MINI" {:kind :run :long? true :key? true}
   "TRIPLE_SIM"  {:kind :run :long? true :key? true}
   "TT5"         {:kind :run :key? true :quality? true}
   "GYM_LOWER"   {:kind :strength :heavy-legs? true}
   "GYM_UPPER"   {:kind :strength}
   "GYM_BACK_SL" {:kind :strength :legs? true}
   "CALF_MIN"    {:kind :strength}})

(def session-ids (vec (keys sessions)))

(defn run? [w] (= :run (get-in sessions [(:session_id w) :kind])))

(defn key-session?
  "Key sessions; LONG counts as key from Build 1 (week 9) on (§6.2)."
  [w week-no]
  (let [s (sessions (:session_id w))]
    (or (:key? s) (and (:long? s) week-no (>= week-no 9)))))

(defn- run-km [ws] (reduce + 0 (keep #(when (run? %) (:distance_km %)) ws)))

(defn check-week
  "Warnings for `workouts` planned in the week starting `monday`.
  `prev` is {:run_km :longest_km :week_type} for the week before, from what
  was actually done (or planned, if it hasn't happened yet)."
  [monday workouts {:keys [prev week-type]}]
  (let [info    (season/week-info monday)
        wk      (:week info)
        sunday  (season/add-days monday 6)
        km      (run-km workouts)
        by-date (group-by :date workouts)
        dates   (map #(season/add-days monday %) (range 7))
        longest (apply max 0 (keep #(when (and (run? %) (get-in sessions [(:session_id %) :long?]))
                                      (:distance_km %))
                                   workouts))
        cap     (if (= "AWAY" (:week_type prev)) 1.2 1.1)
        key-days (set (keep #(when (key-session? % wk) (:date %)) workouts))]
    (vec
     (remove nil?
             (concat
              [(when (some #(or (neg? (compare (:date %) monday)) (pos? (compare (:date %) sunday))) workouts)
                     "Some sessions fall outside this week.")
               (when (and (:run_km prev) (pos? (:run_km prev)) (> km (* cap (:run_km prev))))
                     (format "Running volume %.0f km is more than +%d%% over last week's %.0f km (§10.1)."
                             (double km) (Math/round (* 100 (- cap 1))) (double (:run_km prev))))
               (when (> km 80) (format "Running volume %.0f km exceeds the 80 km peak (§10.1)." (double km)))
               (when (and (:longest_km prev) (pos? (:longest_km prev)) (pos? longest)
                          (> longest (+ 3 (:longest_km prev)))
                          (not (some #(str/starts-with? (:session_id %) "TRIPLE") workouts)))
                     (format "Longest run %.0f km is more than +3 km over the previous longest %.0f km (§10.1)."
                             (double longest) (double (:longest_km prev))))
               (when (> (count (filter #(get-in sessions [(:session_id %) :threshold?]) workouts)) 1)
                     "More than one threshold session (§10.1).")
               (when (> (count (filter #(get-in sessions [(:session_id %) :quality?]) workouts)) 2)
                     "More than two quality elements (§10.1).")
               (when (some #(and (key-days %) (key-days (season/add-days % 1))) key-days)
                     "Key sessions on consecutive days (§10.4).")
               (when (not-any? (fn [d] (not-any? run? (by-date d))) dates)
                     "No day without running (§6.2: at least one full rest day).")
               (when (and (= "AWAY" week-type) (some #(not= "CALF_MIN" (:session_id %)) workouts))
                     "Time away week: plan nothing except optional CALF_MIN (§7.1).")
               (when (some #(and (= :strength (get-in sessions [(:session_id %) :kind]))
                                 (<= 0 (season/days-between (:date %) season/race-date) 10))
                           workouts)
                     "No gym in the final 10 days before the race (§10.4).")]
              (for [long-w (filter #(get-in sessions [(:session_id %) :long?]) workouts)
                    gym    workouts
                    :let [s    (sessions (:session_id gym))
                          gap  (season/days-between (:date gym) (:date long-w))
                          hrs  (- (* 24 gap) (if (= "PM" (:slot gym)) 10 0))]
                    :when (and (or (:heavy-legs? s) (:legs? s)) (<= 0 gap)
                               (< hrs (if (:heavy-legs? s) 48 36)))]
                (format "%s on %s is less than %d h before %s on %s (§6.2)."
                        (:session_id gym) (:date gym) (if (:heavy-legs? s) 48 36)
                        (:session_id long-w) (:date long-w))))))))
