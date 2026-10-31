(ns coach.season
  "Dates and the season position of a week. The season itself (race date,
  phases, week targets) is the athlete's own and lives in the season plan
  in the database (coach.plan/week-info); nothing about it is in the code."
  (:import (java.time DayOfWeek LocalDate)
           (java.time.temporal ChronoUnit TemporalAdjusters)))

(defn monday
  "The Monday on or before `date` (ISO string)."
  [date]
  (str (.with (LocalDate/parse date) (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))))

(defn add-days [date n] (str (.plusDays (LocalDate/parse date) n)))

(defn days-between [a b] (.between ChronoUnit/DAYS (LocalDate/parse a) (LocalDate/parse b)))

(defn week-info
  "Position of the week containing `date` in a season plan
  {:race_date \"YYYY-MM-DD\" :weeks [{:monday :phase :tss} ...]}, or only its
  dates when there is no plan."
  [date {:keys [race_date weeks]}]
  (let [m     (monday date)
        weeks (vec weeks)
        idx   (first (keep-indexed #(when (= m (:monday %2)) %1) weeks))
        w     (when idx (weeks idx))
        ;; the run of consecutive weeks with this week's phase
        same  #(= (:phase w) (:phase (get weeks %)))
        from  (when idx (loop [i idx] (if (and (pos? i) (same (dec i))) (recur (dec i)) i)))
        to    (when idx (loop [i idx] (if (and (< (inc i) (count weeks)) (same (inc i))) (recur (inc i)) i)))]
    (cond-> {:monday m :sunday (add-days m 6)}
      race_date (assoc :race_date race_date :days_to_race (days-between date race_date))
      w         (assoc :week (inc idx) :weeks_total (count weeks) :phase (:phase w) :tss_target (:tss w)
                       :phase_week (inc (- idx from)) :phase_weeks (inc (- to from)))
      (and (seq weeks) (nil? w))
      (assoc :phase (if (neg? (compare m (:monday (first weeks)))) "Before the plan" "After the race")))))
