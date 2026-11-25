(ns coach.source
  "Read-only interface to wherever the training data comes from.
  There are deliberately no write operations.

  Implementations return normalised maps (see coach.db for the columns),
  each carrying the source's original response under :raw.")

(defprotocol DataSource
  (fetch-activities [this start-date end-date]
    "Activities starting in [start-date, end-date] (ISO dates, inclusive),
    with full detail.")
  (fetch-activity [this activity-id]
    "Full detail for a single activity.")
  (fetch-wellness [this start-date end-date]
    "One wellness map per day in [start-date, end-date] (ISO dates, inclusive).")
  (fetch-streams [this activity-id]
    "Per-sample data as a columnar map {:t [...] :hr [...] :speed [...] ...},
    :t in seconds from the start. nil if there is none.")
  (fetch-thresholds [this]
    "Current thresholds {:thresholds [{:sport :kind :value :measured_on}] :sex :weight_kg}.")
  (fetch-intraday [this date]
    "Intraday series for one local day:
    {:date :hr [[minute bpm]] :stress [[minute level]] :body_battery [[minute level]]
     :steps [24 hourly totals] :raw {...}}.")
  (fetch-vo2max [this start-date end-date]
    "VO2 max per day [{:date :sport :value :carried_forward}]. Max 90 days.")
  (fetch-load-trend [this start-date end-date]
    "Garmin's daily acute and chronic load [{:date :acute :chronic :raw}]. Max 90 days.")
  (fetch-training-status [this date]
    "Training status and load focus on `date`: {:date :raw {:status {...} :balance {...}}},
    nil when Garmin has none.")
  (fetch-race-predictions [this]
    "Today's race predictions {:date :t5k :t10k :thalf :tmar :raw} (seconds), nil when none.")
  (fetch-endurance [this date]
    "Endurance score on `date` {:date :score :raw}, nil when none.")
  (fetch-sleep-need [this date]
    "Sleep need for the night ending `date` {:date :need_min :raw}, nil when none."))

(defprotocol ActivitySource
  "A source of activities only, such as a bike computer (no health data).
  Read-only like DataSource."
  (list-activities [this start-date end-date]
    "Activity summaries in [start-date, end-date] (ISO dates, inclusive):
    [{:id :date …}], enough to tell which ones are new.")
  (fetch-full-activity [this summary]
    "One activity in full: {:activity row (coach.db columns, with :raw)
    :stream columnar stream or nil}."))

(defprotocol HealthSource
  "Metrics only some watches and athletes have (read-only). A source without them just doesn't implement this."
  (fetch-sleep-respiration [this start-date end-date]
    "Breaths per minute during sleep in [start-date, end-date]: date -> value, only the nights measured.")
  (fetch-weigh-ins [this start-date end-date]
    "Weigh-ins in [start-date, end-date], the last of each day: [{:date :weight_kg :body_fat :muscle_kg
    :water_pct :bone_kg :bmi :raw}]. Empty when nothing was weighed.")
  (fetch-acclimation [this date]
    "Heat and altitude acclimation as of `date`: {:date :heat_pct :altitude_m :raw}, nil when none.")
  (fetch-exercise-sets [this activity-id]
    "A strength activity's exercises: {:summary [{:exercise :sets :reps :max_kg}] :raw}, the summary empty
    when the watch recorded no recognised exercise."))

(defprotocol CycleSource
  "Menstrual cycle days from the watch's app (read-only, only asked for when the athlete turned cycle tracking on)."
  (fetch-cycle [this start-date end-date]
    "Days in [start-date, end-date] (ISO dates) as [{:date :cycle_day :phase :period :predicted :raw}], where
    :period and :predicted are 0 or 1. Empty when the app has no cycle data."))
