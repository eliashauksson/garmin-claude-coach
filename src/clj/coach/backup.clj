(ns coach.backup
  "Backups of everything personal in data/: a consistent snapshot of the
  SQLite database (VACUUM INTO, safe while the dashboard is writing) plus the
  athlete document, its backups and summary, zipped into one file.

  - daily!   : at most one automatic backup a day (the dashboard calls it
               before syncing); the newest `keep-count` are kept.
  - create!  : a backup now.
  - restore! : CLI only, with the dashboard stopped. The current state is
               backed up first, so a restore can itself be undone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.db :as db]
            [next.jdbc :as jdbc])
  (:import (java.io File)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util.zip ZipEntry ZipInputStream ZipOutputStream)))

(def ^:dynamic *data-dir* "data")
(def ^:dynamic *backup-dir* "data/backups")
(def keep-count "Automatic and manual backups kept; older ones are deleted." 14)

(def prefix "coach-backup-")
(def ^:private stamp-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss"))

(defn- extra-files
  "Personal files besides the DB, as [zip-entry-name File]."
  []
  (let [d (io/file *data-dir*)]
    (concat
     (for [n ["athlete.md" "athlete-summary.json"]
           :let [f (io/file d n)] :when (.isFile f)]
       [n f])
     (for [^File f (sort (or (.listFiles (io/file d "athlete-backups")) []))
           :when (.isFile f)]
       [(str "athlete-backups/" (.getName f)) f]))))

(defn backups
  "Existing backups, newest first: [{:name :path :size :created}]."
  []
  (->> (.listFiles (io/file *backup-dir*))
       (filter #(and (.isFile ^File %) (str/starts-with? (.getName ^File %) prefix)
                     (str/ends-with? (.getName ^File %) ".zip")))
       (sort-by #(.getName ^File %) #(compare %2 %1))
       (mapv (fn [^File f]
               {:name    (.getName f)
                :path    (.getPath f)
                :size    (.length f)
                :created (-> (.getName f) (subs (count prefix)) (str/replace #"\.zip$" ""))}))))

(defn- snapshot-db!
  "Consistent copy of the live DB into `target` (must not exist)."
  [ds ^File target]
  (jdbc/execute! ds [(str "VACUUM INTO '" (str/replace (.getAbsolutePath target) "'" "''") "'")]))

(defn- copy-into-zip! [^ZipOutputStream zip ^String entry ^File f]
  (.putNextEntry zip (ZipEntry. entry))
  (io/copy f zip)
  (.closeEntry zip))

(defn write-zip!
  "Writes a backup zip to `out` (a File). Returns `out`."
  [ds ^File out]
  (let [tmp-db (File/createTempFile "coach-snapshot" ".db")]
    (.delete tmp-db) ; VACUUM INTO needs a fresh path
    (try
      (snapshot-db! ds tmp-db)
      (with-open [zip (ZipOutputStream. (io/output-stream out))]
        (copy-into-zip! zip "coach.db" tmp-db)
        (doseq [[entry f] (extra-files)]
          (copy-into-zip! zip entry f)))
      out
      (finally (.delete tmp-db)))))

(defn prune!
  "Deletes all but the newest `keep-count` backups. Returns the deleted names."
  []
  (let [old (drop keep-count (backups))]
    (doseq [{:keys [path]} old] (io/delete-file path true))
    (mapv :name old)))

(defn create!
  "Writes a new backup into *backup-dir* and (unless :prune? false) prunes
  old ones. Returns its entry."
  [ds & [{:keys [prune?] :or {prune? true}}]]
  (let [dir  (doto (io/file *backup-dir*) .mkdirs)
        name (str prefix (.format (LocalDateTime/now) stamp-fmt) ".zip")
        part (io/file dir (str name ".part"))]
    ;; written under a temporary name so a crash never leaves a half backup
    (write-zip! ds part)
    (.renameTo part (io/file dir name))
    (when prune? (prune!))
    (first (filter #(= name (:name %)) (backups)))))

(defn daily!
  "Creates a backup unless one was already made today. Returns it, or nil."
  [ds]
  (let [today (str (java.time.LocalDate/now))]
    (when-not (some #(str/starts-with? (:created %) today) (backups))
      (create! ds))))

(defn status []
  (let [bs (backups)]
    {:dir *backup-dir* :count (count bs) :latest (first bs) :keep keep-count}))

;; ---------------------------------------------------------------------------
;; Restore (CLI only)

(defn- safe-entry?
  "Only the files a backup writes; nothing that could escape data/."
  [^String n]
  (and (not (str/includes? n ".."))
       (not (str/starts-with? n "/"))
       (or (#{"coach.db" "athlete.md" "athlete-summary.json"} n)
           (re-matches #"athlete-backups/[^/]+" n))))

(defn restore!
  "Replaces data/ files with those in backup `file`. Backs up the current
  state first. The dashboard must not be running."
  [ds file]
  (let [f (io/file file)]
    (when-not (.isFile f) (throw (ex-info (str "No such backup: " file) {:file file})))
    (with-open [zin (ZipInputStream. (io/input-stream f))]
      (let [names (loop [acc []] (if-let [e (.getNextEntry zin)] (recur (conj acc (.getName e))) acc))]
        (when-not (some #{"coach.db"} names)
          (throw (ex-info (str file " has no coach.db; not a coach backup.") {:file file})))))
    ;; not pruned: that could delete the very backup being restored
    (let [before (create! ds {:prune? false})]
      (println "Current state backed up to" (:path before)))
    (let [data (io/file *data-dir*)]
      ;; a leftover write-ahead log belongs to the old database: replayed onto
      ;; the restored file it would corrupt it (its content is in the backup above)
      (doseq [ext ["-wal" "-shm"]] (io/delete-file (io/file data (str "coach.db" ext)) true))
      ;; the athlete backups are replaced as a set, like the document itself
      (doseq [^File old (or (.listFiles (io/file data "athlete-backups")) [])] (.delete old))
      (with-open [zin (ZipInputStream. (io/input-stream f))]
        (loop []
          (when-let [e (.getNextEntry zin)]
            (let [n (.getName e)]
              (when (safe-entry? n)
                (let [target (io/file data n)
                      tmp    (io/file (str (.getPath target) ".restoring"))]
                  (io/make-parents target)
                  (io/copy zin tmp)
                  (.renameTo tmp target)
                  (println "  restored" n))))
            (recur))))
      ;; a restored backup without these files means they didn't exist then
      (doseq [n ["athlete.md" "athlete-summary.json"]]
        (with-open [zin (ZipInputStream. (io/input-stream f))]
          (when-not (some #{n} (take-while some? (repeatedly #(some-> (.getNextEntry zin) .getName))))
            (io/delete-file (io/file data n) true)))))))

(defn- dashboard-running? []
  (some (fn [port]
          (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
               (catch java.io.IOException _ false)))
        [8080 8090]))

(defn- human-size [n]
  (format "%.1f MB" (/ n 1048576.0)))

(defn -main
  "clojure -M -m coach.backup [create | list | restore FILE]"
  [& [cmd file]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (case (or cmd "create")
      "create"  (let [{:keys [path size]} (create! ds)]
                  (println "Backup written:" path (str "(" (human-size size) ")")))
      "list"    (doseq [{:keys [path size]} (backups)]
                  (println path (str "(" (human-size size) ")")))
      "restore" (do (when-not file
                      (println "Usage: clojure -M -m coach.backup restore data/backups/coach-backup-….zip")
                      (System/exit 2))
                    (when (dashboard-running?)
                      (println "The dashboard is running (port 8080 or 8090). Stop it, then restore.")
                      (System/exit 1))
                    (restore! ds file)
                    (println "Restored from" file)))
    (shutdown-agents)))
