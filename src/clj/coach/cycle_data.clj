(ns coach.cycle-data
  "Cycle tracking's database side (the pure part is coach.cycle): the athlete's profile choices (sex, tracking,
  contraception), the cycle log, the days synced from the watch's app, and what the planner and the dashboard are
  shown. Everything here is off until the athlete is female and turned tracking on in Settings."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [coach.analysis :as analysis]
            [coach.cycle :as cycle]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.season :as season]
            [coach.source :as source]
            [next.jdbc :as jdbc]))

(def contraceptions ["natural" "hormonal" "unspecified"])

;; ---------------------------------------------------------------------------
;; Profile

(defn- garmin-sex [ds] (some-> (db/get-state ds :sex) str/upper-case not-empty))

(defn sex
  "The athlete's sex for cycle tracking: their own choice in Settings, else what Garmin's profile says
  (\"MALE\", \"FEMALE\" or nil). It is not used for the training load, which keeps Garmin's value."
  [ds]
  (or (#{"MALE" "FEMALE"} (db/get-state ds :sex_override)) (garmin-sex ds)))

(defn tracking?
  "True when the athlete is female and turned cycle tracking on."
  [ds]
  (and (= "FEMALE" (sex ds)) (= "true" (db/get-state ds :cycle_tracking))))

(defn contraception [ds]
  (or (some #{(db/get-state ds :contraception)} contraceptions) "unspecified"))

(defn profile
  "{:sex :sex_source (\"athlete\" or \"garmin\") :garmin_sex :cycle_tracking :contraception} for Settings."
  [ds]
  (let [own (#{"MALE" "FEMALE"} (db/get-state ds :sex_override))]
    {:sex (sex ds) :sex_source (if own "athlete" "garmin") :garmin_sex (garmin-sex ds)
     :cycle_tracking (tracking? ds) :contraception (contraception ds)}))

(defn save-profile!
  "Saves the choices that are given: :sex (\"MALE\", \"FEMALE\", or nil/\"\" to follow Garmin), :cycle_tracking
  (only possible for female) and :contraception. Returns the profile."
  [ds {new-sex :sex :keys [cycle_tracking contraception] :as body}]
  (when (contains? body :sex)
    (when-not (contains? #{nil "" "MALE" "FEMALE"} new-sex)
      (throw (ex-info (i18n/t "Unknown sex: {0}" new-sex) {})))
    (db/set-state! ds :sex_override (or new-sex "")))
  (when (some? contraception)
    (when-not (some #{contraception} contraceptions)
      (throw (ex-info (i18n/t "Unknown contraception: {0}" contraception) {})))
    (db/set-state! ds :contraception contraception))
  (when (some? cycle_tracking)
    (db/set-state! ds :cycle_tracking (boolean cycle_tracking)))
  ;; tracking needs a female athlete: choosing male (or Garmin saying so) switches it off
  (when (and (not= "FEMALE" (sex ds)) (= "true" (db/get-state ds :cycle_tracking)))
    (db/set-state! ds :cycle_tracking false))
  (profile ds))

;; ---------------------------------------------------------------------------
;; Log and synced days

(defn- parse-row [r]
  (-> r
      (update :symptoms #(cycle/canonical-symptoms (or (db/read-json %) {})))
      (update :tags #(cycle/canonical-tags (or (db/read-json %) [])))))

(defn log-rows
  "The athlete's log between two ISO dates, oldest first: {:date :period_start :period_end :flow :symptoms {name severity} :tags [..] :note}."
  [ds from to]
  (mapv parse-row (db/query ds ["SELECT date, period_start, period_end, flow, symptoms, tags, note FROM cycle_log
                                 WHERE date BETWEEN ? AND ? ORDER BY date" from to])))

(defn save-log!
  "Saves the log of one day (replacing it); a day with nothing in it is removed. `body` {:period_start :flow
  :symptoms {name 1-3} :tags [yes/no tags] :note}; a date after today is refused."
  [ds date {:keys [period_start period_end flow symptoms tags note]}]
  (when-not (and (string? date) (re-matches #"\d{4}-\d{2}-\d{2}" date))
    (throw (ex-info (i18n/t "Not a date: {0}" date) {})))
  (when (pos? (compare date (season/today)))
    (throw (ex-info (i18n/t "You can't log a day in the future.") {})))
  (when-not (or (str/blank? flow) (some #{flow} cycle/flows))
    (throw (ex-info (i18n/t "Unknown flow: {0}" flow) {})))
  (let [flow     (not-empty flow)
        symptoms (cycle/canonical-symptoms symptoms)
        tags     (cycle/canonical-tags (or tags []))
        note     (some-> note str/trim not-empty)
        start?   (boolean period_start)
        end?     (boolean period_end)]
    (if (or start? end? flow (seq symptoms) (seq tags) note)
      (jdbc/execute! ds ["INSERT INTO cycle_log
                                 (date, period_start, period_end, flow, symptoms, tags, note, updated_at)
                               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                               ON CONFLICT(date) DO UPDATE SET period_start = excluded.period_start,
                                 period_end = excluded.period_end,
                                 flow = excluded.flow, symptoms = excluded.symptoms, tags = excluded.tags,
                                 note = excluded.note,
                                 updated_at = excluded.updated_at"
                              date (if start? 1 0) (if end? 1 0) flow (json/write-str symptoms)
                              (json/write-str tags) note (str (java.time.Instant/now))])
      (jdbc/execute! ds ["DELETE FROM cycle_log WHERE date = ?" date]))
    (first (log-rows ds date date))))

(defn delete-data!
  "Removes everything cycle tracking stored (synced days and the log) and the stored choices of tracking."
  [ds]
  (jdbc/execute! ds ["DELETE FROM cycle_days"])
  (jdbc/execute! ds ["DELETE FROM cycle_log"])
  (db/set-state! ds :cycle_tracking false)
  (db/set-state! ds :cycle_synced "")
  (profile ds))

(defn- synced-days [ds from to]
  (db/query ds ["SELECT date, cycle_day, phase, period, predicted FROM cycle_days
                 WHERE date BETWEEN ? AND ? ORDER BY date" from to]))

(def ^:private history-days "How far back the cycle is read: enough for six cycles." 400)

(defn inputs
  "What coach.cycle works from: {:starts :bleed (the days she bled) :log :contraception :today}."
  [ds today]
  (let [from   (season/add-days today (- history-days))
        log    (log-rows ds from today)
        synced (synced-days ds from today)]
    {:starts        (cycle/period-starts log synced)
     :bleed         (cycle/bleed-dates log synced)
     :log           log
     :ends          (cycle/period-ends log)
     :contraception (contraception ds)
     :today         today}))

(defn days
  "Phase of every day in [from, to] for the calendar: [{:date :cycle_day :phase :estimated :period :predicted
  :logged}] (past days from the recorded starts, later days predicted). Empty when tracking is off."
  [ds from to]
  (if-not (tracking? ds)
    []
    (let [today   (season/today)
          inp     (inputs ds today)
          typical (cycle/typical-length (:starts inp))
          ctx     (assoc inp :typical typical)
          bleed   (into #{} (comp (filter #(= 1 (:period %))) (map :date)) (synced-days ds from to))
          logged  (into {} (map (juxt :date identity)) (log-rows ds from to))]
      (vec (for [i (cycle/predict ctx from to)
                 :let [l (logged (:date i))]]
             (-> i
                 (update :phase name)
                 (assoc :period (boolean (or (bleed (:date i)) (some-> l :period_start (= 1))))
                        :predicted (pos? (compare (:date i) today))
                        :logged (boolean l))))))))

;; ---------------------------------------------------------------------------
;; What others are shown

(defn patterns
  "coach.cycle/patterns from the database: wellness, the log and the RPE of the athlete's activities."
  [ds inp today]
  (let [from (season/add-days today (- history-days))]
    (cycle/patterns inp
                    (analysis/wellness ds from today)
                    (:log inp)
                    (keep #(when-let [r (analysis/effective-rpe %)] {:date (:date %) :rpe r})
                          (analysis/activities ds {:from from :to today})))))

(defn planner-context
  "The planner's :menstrual_cycle block for the week [from, to]; nil when tracking is off."
  [ds today from to]
  (when (tracking? ds)
    (let [inp (inputs ds today)]
      (cycle/context (assoc inp :patterns (patterns ds inp today)) from to))))

(defn summary
  "For the Trends page: {:enabled :contraception :cycles [{:start :days}] :typical_length :stats :history
  (coach.cycle/history rows) :symptom_map :phase_days (the current cycle's phases, as on the Today bar) :patterns}."
  [ds today]
  (if-not (tracking? ds)
    {:enabled false}
    (let [inp (inputs ds today)]
      {:enabled true :contraception (:contraception inp)
       :typical_length (cycle/typical-length (:starts inp))
       :stats (cycle/stats inp)
       :cycles (cycle/cycle-list (:starts inp) 12)
       :history (cycle/history inp 12)
       :symptom_map (cycle/symptom-map inp cycle/pattern-cycles)
       :phase_days (:segments (cycle/bar inp))
       :patterns (patterns ds inp today)})))

(defn overview
  "The Today card, and the log of another `date` on the Day page: {:contraception :today (the date's phase)
  :next_period :next_window :stats :bar :typical_length :flags :log (that date's entry) :symptoms :flows}; nil when
  tracking is off."
  ([ds today] (overview ds today today))
  ([ds today date]
  (when (tracking? ds)
    (let [inp     (inputs ds today)
          typical (cycle/typical-length (:starts inp))
          ctx     (assoc inp :typical typical)
          info    (cycle/day-info ctx date)]
      {:contraception (:contraception inp)
       :today         (some-> info (update :phase name))
       :periods       (count (:starts inp))
       :typical_length typical
       :next_period   (cycle/next-period ctx)
       :next_window   (cycle/next-window ctx)
       :stats         (cycle/stats ctx)
       :bar           (cycle/bar ctx)
       :flags         (mapv name (cycle/flags ctx))
       :log           (first (filter #(= date (:date %)) (:log inp)))
       :symptoms      (remove #{"mood"} cycle/symptoms) ; moods have their own group now
       :tag_groups    cycle/tag-groups
       :flows         cycle/flows}))))

(defn adapt-input
  "What coach.adapt needs ({:log yesterday's and today's rows :flags}); nil when tracking is off."
  [ds today]
  (when (tracking? ds)
    (let [inp (inputs ds today)]
      {:log   (filterv #(>= (compare (:date %) (season/add-days today -1)) 0) (:log inp))
       :flags (cycle/flags (assoc inp :typical (cycle/typical-length (:starts inp))))})))

(defn readiness-input
  "What coach.readiness needs: {:phase (keyword) :cycles :hrv_delta :rhr_delta}, the last two being this
  athlete's own usual shift in today's phase; nil when tracking is off or there is no period recorded."
  [ds today]
  (when (tracking? ds)
    (let [inp   (inputs ds today)
          phase (:phase (cycle/day-info (assoc inp :typical (cycle/typical-length (:starts inp))) today))]
      (when phase
        (let [p (patterns ds inp today)]
          (merge {:phase phase :cycles (:cycles p)}
                 (select-keys (first (filter #(= (name phase) (:phase %)) (:phases p))) [:hrv_delta :rhr_delta])))))))

;; ---------------------------------------------------------------------------
;; Sync

(def ^:private first-sync-days 120)
(def ^:private ahead-days "Predicted days the app already knows." 45)

(defn sync!
  "Reads the cycle days from the watch's app (the first time 120 days back, later the last 14) when tracking is on
  and the source can give them. Stores nothing for an empty answer."
  [src ds today]
  (when (and (tracking? ds) (satisfies? source/CycleSource src))
    (let [first? (str/blank? (db/get-state ds :cycle_synced))
          from   (season/add-days today (if first? (- first-sync-days) -14))
          rows   (source/fetch-cycle src from (season/add-days today ahead-days))]
      (db/upsert-cycle-days! ds rows)
      (db/set-state! ds :cycle_synced today)
      (count rows))))
