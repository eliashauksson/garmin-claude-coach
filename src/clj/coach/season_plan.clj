(ns coach.season-plan
  "The long-term plan: Claude lays out every week from the current one to
  race week, each with its phase (training-principles.md §3, athlete.md §A5)
  and a target weekly TSS. The newest finished draft is the season plan;
  weekly proposals aim at its target for their week (coach.planner/context)."
  (:require [clojure.data.json :as json]
            [coach.analysis :as analysis]
            [coach.plan :as plan]
            [coach.planner :as planner]
            [coach.season :as season]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.time LocalDate)))

(def phases ["Reset" "Base" "Build 1" "Build 2" "Specific" "Taper" "Race week"])

(defn mondays
  "Every Monday from `start` (a Monday) to the week of `race-date`, inclusive."
  [start race-date]
  (let [last-monday (season/monday race-date)]
    (vec (take-while #(<= (compare % last-monday) 0) (iterate #(season/add-days % 7) start)))))

(defn weekly-tss
  "Monday -> recorded TSS of that week."
  [acts]
  (update-vals (group-by #(season/monday (:date %)) acts)
               #(Math/round (double (reduce + 0 (keep :tss %))))))

;; ---------------------------------------------------------------------------
;; Context and prompt

(defn context [ds today]
  (let [start (season/monday today)
        from  (season/add-days start -84)
        acts  (analysis/activities ds {:from from :to today})
        by-wk (group-by #(season/monday (:date %)) acts)
        pmc   (last (analysis/pmc ds today today))
        r     #(some-> % Math/round)]
    {:today          today
     :race_date      season/race-date
     :days_to_race   (season/days-between today season/race-date)
     :weeks_to_plan  (mondays start season/race-date)
     :load_today     {:ctl (r (:ctl pmc)) :atl (r (:atl pmc)) :tsb (r (:tsb pmc))}
     :last_12_weeks  (vec (for [i (range 12 0 -1) :let [mo (season/add-days start (* -7 i))]]
                            (planner/week-summary mo (by-wk mo))))
     :this_week_so_far (planner/week-summary start (by-wk start))}))

(def system-intro
  "You are the running coach for the athlete described below. Two documents follow: the general
training principles (§0-§9) and the athlete's profile, goal and season plan (§A1-§A9).

Draft the long-term plan: every week from the current week to race week, with its phase and a
target weekly TSS. Weekly sessions are planned later, one week at a time, aiming at these targets.

- Follow the periodization (§3) and the athlete's season plan (§A5): its phases, dates, peak,
  cutback and test weeks. If today's date or the athlete's current fitness no longer fits §A5,
  adapt it and say what you changed in `warnings`.
- TSS is all recorded training as this dashboard computes it: hrTSS for runs and marches, plus gym
  sessions. Remember that Garmin-recorded load underestimates the athlete's real load (§A2).
- Start from what the athlete has actually done (`last_12_weeks`, `load_today`), not from §A5's
  numbers alone. Progress within the hard rules (§9.1): no week more than about +10% over the last
  normal week, a cutback every 3rd-4th week at about -25% (§9.2), a 3-week taper (§9.3).
- The last week is `Race week`; its target includes the race itself.
- Return exactly one entry per Monday in `weeks_to_plan`, in order.
- `focus`: a few words on the week's key work (e.g. \"Long run 18 km, hill repeats\").
- `cutback`: true for cutback, absorb and test weeks with reduced load.
- `summary`: 2-3 sentences on the season's shape.
- `warnings`: only what the athlete must know (changes to §A5, risks, missing information), one line each.

# Coaching documents

")

(def response-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["summary" "weeks" "warnings"]
   "properties"
   {"summary"  {"type" "string"}
    "warnings" {"type" "array" "items" {"type" "string"}}
    "weeks"    {"type" "array"
                "items" {"type" "object" "additionalProperties" false
                         "required" ["monday" "phase" "tss" "cutback" "focus"]
                         "properties" {"monday"  {"type" "string" "format" "date"}
                                       "phase"   {"type" "string" "enum" phases}
                                       "tss"     {"type" "integer"}
                                       "cutback" {"type" "boolean"}
                                       "focus"   {"type" "string"}}}}}})

(def Response
  [:map
   [:summary :string]
   [:warnings [:vector :string]]
   [:weeks [:vector [:map
                     [:monday [:re #"^\d{4}-\d{2}-\d{2}$"]]
                     [:phase (into [:enum] phases)]
                     [:tss [:int {:min 0}]]
                     [:cutback :boolean]
                     [:focus :string]]]]])

(defn validate
  "Schema check, and one entry per week to plan, in order."
  [response ctx]
  (when-let [err (m/explain Response response)]
    (throw (ex-info (str "Invalid season plan from the model: " (pr-str (me/humanize err))) {:response response})))
  (when (not= (:weeks_to_plan ctx) (mapv :monday (:weeks response)))
    (throw (ex-info "The model's season plan does not cover every week to race day exactly once."
                    {:expected (:weeks_to_plan ctx) :got (mapv :monday (:weeks response))})))
  response)

;; ---------------------------------------------------------------------------

(defn draft!
  "Starts drafting a season plan in the background and returns its id. A draft
  already running is reused."
  [ds]
  (let [latest (plan/latest-season-plan ds)]
    (if (= "running" (:status latest))
      (:id latest)
      (let [today (str (LocalDate/now))
            id    (plan/create-season-plan! ds season/race-date (season/monday today) planner/model)]
        (future
          (try
            (let [ctx (context ds today)
                  {:keys [response usage]}
                  (planner/request {:system   (str system-intro (planner/principles))
                                    :messages [[:user (str "Draft the season plan. The athlete's data as JSON:\n\n"
                                                           (json/write-str ctx))]]
                                    :schema   response-schema})]
              (plan/finish-season-plan! ds id {:context ctx :response (validate response ctx)
                                               :warnings [] :usage usage}))
            (catch Throwable e
              (plan/fail-season-plan! ds id (or (ex-message e) (str (class e)))))))
        id))))

(defn overview
  "What the Plan page shows: the status of the newest draft, the newest
  finished plan with the TSS actually recorded in its weeks so far."
  [ds today]
  (let [latest (plan/latest-season-plan ds)
        shown  (if (= "ready" (:status latest)) latest (plan/latest-season-plan ds "ready"))
        weeks  (get-in shown [:response :weeks])
        actual (when (seq weeks)
                 (weekly-tss (analysis/activities ds {:from (:monday (first weeks)) :to today})))]
    {:status         (or (:status latest) "none")
     :error          (when (= "failed" (:status latest)) (:error latest))
     :plan           (when shown
                       (assoc-in shown [:response :weeks]
                                 (mapv #(cond-> % (actual (:monday %)) (assoc :actual_tss (actual (:monday %))))
                                       weeks)))
     :current_monday (season/monday today)
     :race_date      season/race-date
     :days_to_race   (season/days-between today season/race-date)}))
