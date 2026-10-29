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
