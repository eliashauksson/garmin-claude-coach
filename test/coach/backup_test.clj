(ns coach.backup-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [coach.backup :as backup]
            [coach.db :as db]))

(defn- temp-dir []
  (doto (.toFile (java.nio.file.Files/createTempDirectory "coach-backup" (make-array java.nio.file.attribute.FileAttribute 0)))
    .deleteOnExit))

(deftest backup-restore-test
  (let [data (temp-dir)
        ds   (doto (db/datasource (str (io/file data "coach.db"))) db/migrate!)]
    (binding [backup/*data-dir* (str data)
              backup/*backup-dir* (str (io/file data "backups"))
              *out* (java.io.StringWriter.)]
      (db/set-state! ds :synced_through "2026-10-31")
      (spit (io/file data "athlete.md") "# Athlete v1")
      (io/make-parents (io/file data "athlete-backups" "x"))
      (spit (io/file data "athlete-backups" "athlete-1.md") "old")
      (let [b (backup/create! ds)]
        (is (.isFile (io/file (:path b))))
        (testing "a second daily backup on the same day is skipped"
          (is (nil? (backup/daily! ds))))
        (testing "restore brings back the DB and the athlete files"
          (db/set-state! ds :synced_through "2027-01-01")
          (spit (io/file data "athlete.md") "# Athlete v2")
          (spit (io/file data "athlete-backups" "athlete-2.md") "newer")
          (Thread/sleep 1100) ; backup names have one-second resolution
          (backup/restore! ds (:path b))
          (is (= "2026-10-31" (db/get-state ds :synced_through)))
          (is (= "# Athlete v1" (slurp (io/file data "athlete.md"))))
          (is (= ["athlete-1.md"] (sort (map #(.getName %) (.listFiles (io/file data "athlete-backups"))))))
          (is (= 2 (count (backup/backups))) "the state before the restore was backed up too"))
        (testing "prune keeps the newest"
          (with-redefs [backup/keep-count 1]
            (is (= 1 (count (backup/prune!))))
            (is (= 1 (count (backup/backups))))))))))
