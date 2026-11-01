(ns coach.sync-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [coach.db :as db]
            [coach.garmin-mcp :as gm]
            [coach.source :as source]
            [coach.sync :as sync]))

(deftest chunks-test
  (is (= [["2026-10-02" "2026-10-29"] ["2026-09-04" "2026-10-01"]]
         (sync/chunks "2026-09-04" "2026-10-29" 28)))
  (is (= [["2026-10-20" "2026-10-29"] ["2026-10-10" "2026-10-19"] ["2026-10-08" "2026-10-09"]]
         (sync/chunks "2026-10-08" "2026-10-29" 10)))
  (is (= [["2026-10-29" "2026-10-29"]] (sync/chunks "2026-10-29" "2026-10-29" 28)))
  (is (= [] (sync/chunks "2026-10-29" "2026-10-28" 28))))

;; Fake source: one activity on every 10th day, a wellness row every day.
(defrecord FakeSource [calls]
  source/DataSource
  (fetch-activities [_ start end]
    (swap! calls conj [:activities start end])
    (for [date (gm/dates start end)
          :when (zero? (mod (.getDayOfYear (java.time.LocalDate/parse date)) 10))]
      {:id (parse-long (str/replace date "-" "")) :date date :sport "running"
       :raw {:date date}}))
  (fetch-activity [_ id] {:id id})
  (fetch-wellness [_ start end]
    (swap! calls conj [:wellness start end])
    (for [date (gm/dates start end)] {:date date :rhr 50 :raw {}})))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-test" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(deftest backfill-test
  (let [ds    (temp-ds)
        calls (atom [])
        src   (->FakeSource calls)]
    (with-redefs [sync/epoch "2026-07-01"]
      (binding [*out* (java.io.StringWriter.)]
        (db/set-state! ds :synced_through "2026-10-29")
        (db/set-state! ds :backfill_before "2026-10-02")
        (testing "max-chunks pauses and saves progress"
          (is (= :paused (sync/backfill! src ds {:max-chunks 1})))
          (is (= "2026-09-04" (db/get-state ds :backfill_before))))
        (testing "resumes to the epoch and stops there"
          (is (= :complete (sync/backfill! src ds)))
          (is (= "2026-07-01" (db/get-state ds :backfill_before)))
          (is (= ["2026-07-01" "2026-07-09"] (drop 1 (last @calls)))))
        (let [{:keys [wellness_days first_day last_day]} (sync/status ds)]
          (is (= 93 wellness_days))
          (is (= ["2026-07-01" "2026-10-01"] [first_day last_day])))
        (testing "re-running a range adds nothing"
          (let [before (sync/status ds)]
            (sync/sync-range! src ds "2026-07-01" "2026-10-01")
            (is (= before (sync/status ds)))))))))

;; ---------------------------------------------------------------------------
;; Failures

(defn- garmin-error [kind] (ex-info (str "Error: " (name kind)) {:tool "t" :kind kind}))

(defrecord FailingSource [kind]
  source/DataSource
  (fetch-activities [_ _ _] [])
  (fetch-activity [_ id] {:id id})
  (fetch-wellness [_ start end] (for [date (gm/dates start end)] {:date date :raw {}}))
  (fetch-streams [_ _] (throw (garmin-error kind)))
  (fetch-intraday [_ _] (throw (garmin-error kind))))

(deftest keep-stored-test
  (let [rows [{:date "2026-10-30"} {:date "2026-10-31" :rhr 55} {:date "2026-11-01"}]]
    (is (= ["2026-10-31" "2026-11-01"]
           (map :date (sync/keep-stored rows sync/empty-wellness? #{"2026-10-30" "2026-10-31"})))
        "an empty day doesn't replace a stored day; a day with data does; new days go in")))

(deftest empty-days-never-overwrite-test
  (let [ds (temp-ds)]
    (binding [*out* (java.io.StringWriter.)]
      (db/upsert-wellness! ds [{:date "2026-10-30" :rhr 52 :sleep_score 75 :raw {}}])
      (db/save-intraday! ds {:date "2026-10-30" :hr [[0 50]] :stress [] :body_battery [] :steps []})
      (testing "wellness"
        (sync/sync-range! (->FailingSource :other) ds "2026-10-30" "2026-10-31")
        (is (= [52 nil] (map :rhr (db/query ds ["SELECT rhr FROM daily_wellness ORDER BY date"])))))
      (testing "intraday"
        (let [empty-src (reify source/DataSource
                          (fetch-intraday [_ date] {:date date :hr [] :stress [] :body_battery [] :steps []}))]
          (sync/sync-intraday! empty-src ds "2026-10-30" "2026-10-30")
          (is (= [[0 50]] (:hr (db/load-intraday ds "2026-10-30")))))))))

(deftest fatal-and-transient-errors-test
  (let [ds (temp-ds)]
    (binding [*out* (java.io.StringWriter.)]
      (db/upsert-activities! ds [{:id 7 :date "2026-10-30" :sport "running" :raw {}}])
      (testing "an expired login stops the sync"
        (is (= :auth (:kind (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                  (sync/sync-streams! (->FailingSource :auth) ds)))))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (sync/sync-intraday! (->FailingSource :rate-limit) ds "2026-10-30" "2026-10-30")))
        (is (empty? (db/query ds ["SELECT * FROM activity_streams"]))))
      (testing "a transient error skips the item without marking it missing"
        (sync/sync-streams! (->FailingSource :timeout) ds)
        (is (empty? (db/query ds ["SELECT * FROM activity_streams"]))))
      (testing "any other per-activity error is recorded so it isn't retried forever"
        (sync/sync-streams! (->FailingSource :other) ds)
        (is (= [{:samples 0 :error "Error: other"}]
               (db/query ds ["SELECT samples, error FROM activity_streams"])))))))

(deftest record-result-test
  (let [ds (temp-ds)]
    (sync/record-result! ds {:ok false :kind :auth :message "x" :finished_at "2026-11-01T10:00:00Z"})
    (is (= :auth (:kind (sync/last-result ds))))
    (is (nil? (db/get-state ds :last_ok_sync)))
    (sync/record-result! ds {:ok true :finished_at "2026-11-01T12:00:00Z"})
    (is (= "2026-11-01T12:00:00Z" (db/get-state ds :last_ok_sync)))
    (is (= {:kind :unreachable :message "Garmin Connect request failed"}
           (sync/failure (ex-info "Garmin Connect request failed" {}))))
    (is (= {:kind :auth :message "Error retrieving stats: Garmin login failed."}
           (sync/failure (ex-info "Error retrieving stats: Garmin login failed. Run 'garmin-mcp-auth' to authenticate, then restart the server." {}))))))

(deftest intraday-dates-test
  (let [today "2026-11-01"]
    (is (= "2026-07-01" (sync/intraday-window-start today)) "Garmin's window: 4 months")
    (testing "first run: every day in the window"
      (let [ds (sync/intraday-dates today #{})]
        (is (= ["2026-07-01" "2026-11-01"] [(first ds) (last ds)]))
        (is (= 124 (count ds)))))
    (testing "afterwards: the last week plus any gap"
      (let [stored (disj (set (sync/intraday-dates today #{})) "2026-08-15")]
        (is (= ["2026-08-15" "2026-10-26" "2026-10-27" "2026-10-28" "2026-10-29" "2026-10-30" "2026-10-31" "2026-11-01"]
               (sync/intraday-dates today stored)))))))

;; ---------------------------------------------------------------------------
;; Coach overview: training status, race predictions, endurance, sleep need

(defn- overview-source
  "Answers for today; `empty?` makes every answer come back without data."
  [calls empty?]
  (reify source/DataSource
    (fetch-training-status [_ date]
      (swap! calls conj [:status date])
      (when-not empty? {:date date :raw {:status {:training_status_feedback "DETRAINING"}}}))
    (fetch-race-predictions [_]
      (swap! calls conj [:predictions])
      (when-not empty? {:date "2026-11-01" :t5k 1351 :t10k 2913 :thalf 6467 :tmar 14595 :raw {}}))
    (fetch-endurance [_ date]
      (swap! calls conj [:endurance date])
      (when-not empty? {:date date :score 6093 :raw {}}))
    (fetch-sleep-need [_ date]
      (swap! calls conj [:sleep-need date])
      (when-not empty? {:date date :need_min 480 :raw {}}))))

(deftest sleep-need-dates-test
  (testing "first run: the last 30 nights"
    (let [ds (sync/sleep-need-dates "2026-11-01" #{})]
      (is (= ["2026-10-03" "2026-11-01"] [(first ds) (last ds)]))
      (is (= 30 (count ds)))))
  (testing "afterwards: the last 3 nights plus any gap"
    (let [stored (disj (set (sync/sleep-need-dates "2026-11-01" #{})) "2026-10-10")]
      (is (= ["2026-10-10" "2026-10-30" "2026-10-31" "2026-11-01"]
             (sync/sleep-need-dates "2026-11-01" stored))))))

(deftest sync-overview-test
  (let [ds    (temp-ds)
        calls (atom [])]
    (binding [*out* (java.io.StringWriter.)]
      (sync/sync-overview! (overview-source calls false) ds "2026-11-01")
      (testing "today's training status, predictions and endurance are stored"
        (is (= 1 (count (db/query ds ["SELECT * FROM training_status WHERE date = '2026-11-01'"]))))
        (is (= [{:date "2026-11-01" :t10k 2913.0 :tmar 14595.0}]
               (db/query ds ["SELECT date, t10k, tmar FROM race_predictions"])))
        (is (= [6093.0] (map :score (db/query ds ["SELECT score FROM endurance_score"])))))
      (testing "sleep need for the last 30 nights"
        (is (= 30 (count (db/query ds ["SELECT * FROM sleep_need"])))))
      (testing "an answer without data never replaces a stored day"
        (reset! calls [])
        (sync/sync-overview! (overview-source calls true) ds "2026-11-01")
        (is (= 3 (count (filter #(= :sleep-need (first %)) @calls))) "only the recent nights are asked again")
        (is (= [6093.0] (map :score (db/query ds ["SELECT score FROM endurance_score"]))))
        (is (= [480.0] (distinct (map :need_min (db/query ds ["SELECT need_min FROM sleep_need"]))))))))
  (testing "a failed login stops the sync; any other error skips the step"
    (let [ds  (temp-ds)
          src (fn [kind] (reify source/DataSource
                           (fetch-training-status [_ _] (throw (garmin-error kind)))
                           (fetch-race-predictions [_] {:date "2026-11-01" :t10k 2913 :raw {}})
                           (fetch-endurance [_ _] nil)
                           (fetch-sleep-need [_ _] nil)))]
      (binding [*out* (java.io.StringWriter.)]
        (is (thrown? clojure.lang.ExceptionInfo (sync/sync-overview! (src :auth) ds "2026-11-01")))
        (sync/sync-overview! (src :garmin) ds "2026-11-01")
        (is (= 1 (count (db/query ds ["SELECT * FROM race_predictions"]))))))))

;; ---------------------------------------------------------------------------
;; Loading the full history

(defrecord HistorySource [first-day]
  ;; data on every day from `first-day` on, nothing before
  source/DataSource
  (fetch-activities [_ start end]
    (for [date (gm/dates start end) :when (>= (compare date first-day) 0)
          :when (zero? (mod (.getDayOfYear (java.time.LocalDate/parse date)) 7))]
      {:id (parse-long (str/replace date "-" "")) :date date :sport "running" :raw {}}))
  (fetch-activity [_ id] {:id id})
  (fetch-wellness [_ start end]
    (for [date (gm/dates start end)]
      (cond-> {:date date :raw {}} (>= (compare date first-day) 0) (assoc :rhr 50)))))

(deftest backfill-finds-the-start-test
  (let [ds (temp-ds)]
    (binding [*out* (java.io.StringWriter.)]
      (db/set-state! ds :synced_through "2026-10-29")
      (db/set-state! ds :backfill_before "2026-10-02")
      (is (false? (sync/history-complete? ds)))
      (is (= :paused (sync/backfill! (->HistorySource "2025-06-01") ds {:max-chunks 2})))
      (is (= "false" (db/get-state ds :history_complete)) "a running backfill records that it isn't done")
      (is (= :complete (sync/backfill! (->HistorySource "2025-06-01") ds)))
      (testing "it stops after half a year of nothing, not at the floor"
        (let [before (db/get-state ds :backfill_before)]
          (is (neg? (compare before "2025-06-01")))
          (is (pos? (compare before "2024-11-01")))))
      (is (true? (sync/history-complete? ds)))
      (is (= "2025-06-01" (sync/earliest-date ds))))))

(deftest legacy-history-test
  (let [ds (temp-ds)]
    (db/set-state! ds :backfill_before "2021-03-26")
    (is (true? (sync/history-complete? ds)) "an install that reached the old fixed start is complete")
    (db/set-state! ds :history_complete "false")
    (is (false? (sync/history-complete? ds)) "unless a backfill says it is still going")))
