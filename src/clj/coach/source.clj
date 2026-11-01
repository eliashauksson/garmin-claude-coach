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
    "Current thresholds {:thresholds [{:sport :kind :value :measured_on}] :sex}.")
  (fetch-intraday [this date]
    "Intraday series for one local day:
    {:date :hr [[minute bpm]] :stress [[minute level]] :body_battery [[minute level]]
     :steps [24 hourly totals] :raw {...}}.")
  (fetch-vo2max [this start-date end-date]
    "VO2 max per day [{:date :sport :value :carried_forward}]. Max 90 days.")
  (fetch-training-status [this date]
    "Training status and load focus on `date`: {:date :raw {:status {...} :balance {...}}},
    nil when Garmin has none.")
  (fetch-race-predictions [this]
    "Today's race predictions {:date :t5k :t10k :thalf :tmar :raw} (seconds), nil when none.")
  (fetch-endurance [this date]
    "Endurance score on `date` {:date :score :raw}, nil when none.")
  (fetch-sleep-need [this date]
    "Sleep need for the night ending `date` {:date :need_min :raw}, nil when none."))
