(ns coach.season
  "The season structure from training-principles.md §4: which week of the
  plan a date falls in and which phase that is. Targets inside each phase
  are left to the planner, which reads the principles."
  (:import (java.time DayOfWeek LocalDate)
           (java.time.temporal ChronoUnit TemporalAdjusters)))

(def race-date "2030-04-07")
(def week-1 "First Monday of the plan (Reset week 1)." "2026-11-02")
(def total-weeks 23)

(def phases
  [{:id :reset    :name "Reset"    :from 1  :to 3}
   {:id :base     :name "Base"     :from 4  :to 8}
   {:id :build-1  :name "Build 1"  :from 9  :to 12}
   {:id :build-2  :name "Build 2"  :from 13 :to 16}
   {:id :specific :name "Specific" :from 17 :to 20}
   {:id :taper    :name "Taper"    :from 21 :to 23}])

(defn monday
  "The Monday on or before `date` (ISO string)."
  [date]
  (str (.with (LocalDate/parse date) (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))))

(defn add-days [date n] (str (.plusDays (LocalDate/parse date) n)))

(defn days-between [a b] (.between ChronoUnit/DAYS (LocalDate/parse a) (LocalDate/parse b)))

(defn week-info
  "Plan position of the week containing `date`."
  [date]
  (let [m      (monday date)
        n      (inc (quot (days-between week-1 m) 7))
        phase  (some #(when (<= (:from %) n (:to %)) %) phases)
        base   {:monday m :sunday (add-days m 6)
                :days_to_race (days-between date race-date)}]
    (cond
      (< n 1)            (assoc base :week nil :phase "Pre-season" :phase_id :pre-season)
      (> n total-weeks)  (assoc base :week nil :phase "After race" :phase_id :post-race)
      :else              (assoc base :week n :phase (:name phase) :phase_id (:id phase)
                                :phase_week (inc (- n (:from phase)))
                                :phase_weeks (inc (- (:to phase) (:from phase)))))))
