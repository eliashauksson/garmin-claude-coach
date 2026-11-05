(ns coach.season-plan
  "The long-term plan: Claude lays out every week from the current one to
  race week, each with its phase (training-principles.md: Periodization; athlete.md: Proposed macro)
  and a target weekly TSS. The newest finished draft is the season plan;
  weekly proposals aim at its target for their week (coach.planner/context).
  Once it exists, the coach keeps the next two weeks proposed (coach.planner/plan-ahead!)."
  (:require [clojure.data.json :as json]
            [coach.ai-settings :as ai]
            [coach.analysis :as analysis]
            [coach.athlete :as athlete]
            [coach.i18n :as i18n]
            [coach.plan :as plan]
            [coach.claude :as claude]
            [coach.planner :as planner]
            [coach.translate :as translate]
            [coach.season :as season]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.time LocalDate)))

(def phases
  "Periodization in training-principles.md, plus the race week itself."
  ["Transition" "Base" "Specific" "Taper" "Race week"])

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

(defn race-date!
  "The goal race date from the athlete information, or an error saying it's missing."
  []
  (or (:date (athlete/race))
      (throw (ex-info (i18n/t "The athlete information has no goal race date yet. Add it on the Plan page first.")
                      {:type :no-race-date}))))

(defn context [ds today race-date]
  (let [start (season/monday today)
        from  (season/add-days start -84)
        acts  (analysis/activities ds {:from from :to today})
        by-wk (group-by #(season/monday (:date %)) acts)
        pmc   (last (analysis/pmc ds today today))
        r     #(some-> % Math/round)]
    {:today          today
     :race_date      race-date
     :days_to_race   (season/days-between today race-date)
     :weeks_to_plan  (mondays start race-date)
     :load_today     {:ctl (r (:ctl pmc)) :atl (r (:atl pmc)) :tsb (r (:tsb pmc))}
     :last_12_weeks  (vec (for [i (range 12 0 -1) :let [mo (season/add-days start (* -7 i))]]
                            (planner/week-summary mo (by-wk mo))))
     :this_week_so_far (planner/week-summary start (by-wk start))}))

(def system-intro
  "You are the running coach for the athlete described in the two documents above: the general
training principles and athlete.md (the athlete's profile, goal and season plan). Refer to
sections by their heading. Rules in athlete.md override the principles' (d) defaults.

Draft the long-term plan: every week from the current week to race week, with its phase and a
target weekly TSS. Weekly sessions are planned later, one week at a time, aiming at these targets.

- Follow the Periodization and the athlete's season plan (athlete.md: Proposed macro): its phases,
  dates, peak, cutback and test weeks. If today's date or the athlete's current fitness no longer fits it,
  adapt it and say what you changed in `warnings`.
- TSS is all recorded training as this dashboard computes it: hrTSS for runs and marches, plus gym
  sessions. Remember that Garmin-recorded load underestimates the athlete's real load (athlete.md: Constraints).
- Start from what the athlete has actually done (`last_12_weeks`, `load_today`), not from the
  macro's numbers alone. Progress within Load & recovery: no week more than about +10% over the last
  normal week, a recovery week per the build:recovery rhythm, a taper per Periodization and
  Running › By period.
- The last week is `Race week`; its target includes the race itself.
- Return exactly one entry per Monday in `weeks_to_plan`, in order.
- `focus`: a few words on the week's key work (e.g. \"Long run 18 km, hill repeats\").
- `cutback`: true for cutback, absorb and test weeks with reduced load.
- `summary`: 2-3 sentences on the season's shape.
- `warnings`: only what the athlete must know (changes to the macro, risks, missing information), one line each.

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
    (throw (ex-info (i18n/t "Invalid season plan from the model: {0}" (pr-str (me/humanize err))) {:response response})))
  (when (not= (:weeks_to_plan ctx) (mapv :monday (:weeks response)))
    (throw (ex-info (i18n/t "The model's season plan does not cover every week to race day exactly once.")
                    {:expected (:weeks_to_plan ctx) :got (mapv :monday (:weeks response))})))
  response)

;; ---------------------------------------------------------------------------

(defn- finish! [ds id ctx response usage]
  (plan/finish-season-plan! ds id {:context ctx :response (validate response ctx)
                                   :warnings [] :usage usage})
  (claude/translate-soon! (translate/response-texts response))
  (planner/plan-ahead! ds))

(claude/register-batch-handler!
 :season_plan
 {:done (fn [ds {:keys [id ctx]} response usage] (finish! ds id ctx response usage))
  :fail (fn [ds {:keys [id]} msg] (plan/fail-season-plan! ds id msg))})

(defn draft!
  "Starts drafting a season plan in the background and returns its id. A draft
  already running is reused."
  [ds & [trigger]]
  (let [running (plan/latest-season-plan ds "running")]
    (if running
      (:id running)
      (let [today  (str (LocalDate/now))
            race   (race-date!)
            batch? (ai/batch? :season_plan (or trigger :manual))
            id     (plan/create-season-plan! ds race (season/monday today) (claude/model-for :season_plan)
                                             (when batch? "batch"))]
        (future
          (try
            (let [ctx  (context ds today race)
                  opts {:knowledge (claude/principles)
                        :system    system-intro
                        :messages  [[:user (str "Draft the season plan. The athlete's data as JSON:\n\n"
                                                (json/write-str ctx))]]
                        :schema    response-schema
                        :task      :season_plan
                        :ref       (str "season plan " id)}]
              (if batch?
                (claude/submit-batch! ds (assoc opts :data {:id id :ctx ctx}))
                (let [{:keys [response usage]} (claude/request opts)]
                  (finish! ds id ctx response usage))))
            (catch Throwable e
              (plan/fail-season-plan! ds id (or (ex-message e) (str (class e)))))))
        id))))

(defn delete!
  "Removes the current plan (kept as a backup) and, with it, the upcoming
  planned sessions and open proposals (coach.plan/clear-upcoming!). The coach
  stops planning weeks ahead until there is a plan again."
  [ds today]
  (plan/delete-season-plan! ds)
  (plan/clear-upcoming! ds today))

(defn restore!
  "Brings back backup `id` as the current plan, then fills the weeks ahead."
  [ds id]
  (plan/restore-season-plan! ds id)
  (planner/plan-ahead! ds))

(defn overview
  "What the Plan page shows: whether a draft is running or the last one
  failed, the current plan with the TSS recorded in its weeks so far, and
  the backups."
  [ds today]
  (let [running (plan/latest-season-plan ds "running")
        current (plan/latest-season-plan ds "ready")
        newest  (plan/latest-season-plan ds)
        failed  (when (and (= "failed" (:status newest)) (> (:id newest) (or (:id current) 0))) newest)
        weeks   (get-in current [:response :weeks])
        race    (or (:race_date current) (:date (athlete/race)))
        actual  (when (seq weeks)
                  (weekly-tss (analysis/activities ds {:from (:monday (first weeks)) :to today})))]
    {:status         (cond running "running" failed "failed" current "ready" :else "none")
     :delivery       (:delivery running)
     :error          (:error failed)
     :plan           (when current
                       (assoc-in current [:response :weeks]
                                 (mapv #(cond-> % (actual (:monday %)) (assoc :actual_tss (actual (:monday %))))
                                       weeks)))
     :backups        (plan/season-backups ds)
     :current_monday (season/monday today)
     :race           (:name (athlete/race))
     :race_date      race
     :days_to_race   (some->> race (season/days-between today))}))
