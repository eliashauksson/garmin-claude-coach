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
    "One wellness map per day in [start-date, end-date] (ISO dates, inclusive)."))
