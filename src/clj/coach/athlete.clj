(ns coach.athlete
  "The athlete-specific document (athlete.md): read, save, delete and
  restore. Every save, delete and restore first backs up the current text;
  the newest `keep-backups` backups are kept in data/athlete-backups/."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time Instant)))

(def ^:dynamic *doc-file*
  "Personal, so it lives in data/ (not in git)."
  "data/athlete.md")
(def old-doc-file "Where the document used to be (project root); moved on first read." "athlete.md")
(def ^:dynamic *backup-dir* "data/athlete-backups")
(def ^:dynamic *summary-file* "data/athlete-summary.json")
(def keep-backups 3)

(defn- move-old-doc! []
  (let [old (io/file old-doc-file) f (io/file *doc-file*)]
    (when (and (= *doc-file* "data/athlete.md") (.exists old) (not (.exists f)))
      (io/make-parents f)
      (.renameTo old f))))

(defn content
  "The document text, or nil when there is none."
  []
  (move-old-doc!)
  (let [f (io/file *doc-file*)]
    (when (.exists f) (not-empty (str/trim (slurp f))))))

(defn sha [^String s]
  (when s
    (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
      (apply str (map #(format "%02x" %) (take 8 d))))))

(defn summary
  "The stored overview map ({:race :race_date :race_detail :athlete :stats
  :focus}), if it was made for the current text."
  []
  (let [f (io/file *summary-file*)
        c (content)]
    (when (and c (.exists f))
      (let [{:keys [hash overview]} (json/read-str (slurp f) :key-fn keyword)]
        (when (= hash (sha c)) overview)))))

(defn- save-summary! [text overview]
  (let [f (io/file *summary-file*)]
    (io/make-parents f)
    (if (and text (map? overview) (seq overview))
      (spit f (json/write-str {:hash (sha text) :overview overview}))
      (.delete f))))

;; ---------------------------------------------------------------------------
;; Backups: <timestamp>__<action>.md, newest first

(defn- backup-files []
  (let [d (io/file *backup-dir*)]
    (->> (.listFiles d)
         (filter #(str/ends-with? (.getName %) ".md"))
         (sort-by #(.getName %) #(compare %2 %1)))))

(defn backups []
  (vec (for [f (backup-files)
             :let [[at action] (str/split (str/replace (.getName f) #"\.md$" "") #"__")]]
         {:id (.getName f) :at (str/replace at #"_" ":") :action action
          :chars (count (slurp f))})))

(defn- backup! [action]
  (when-let [c (content)]
    (let [at (str/replace (str (Instant/now)) #":" "_")
          f  (io/file *backup-dir* (str at "__" action ".md"))]
      (io/make-parents f)
      (spit f c)
      (doseq [old (drop keep-backups (backup-files))] (.delete old)))))

;; ---------------------------------------------------------------------------

(def max-chars "Longest document accepted (an upload can be any file)." 200000)

(defn save!
  "Replaces the document with `text` (backing up the old one) and stores
  the `overview` map for it (nil: Claude writes one in the background).
  `action` names the backup: \"edit\" (default) or \"upload\"."
  [text overview & [action]]
  (when (str/blank? text) (throw (ex-info "The athlete document can't be empty; delete it instead." {})))
  (when (> (count text) max-chars)
    (throw (ex-info (str "The athlete document is too long (over " max-chars " characters).") {})))
  (when (str/includes? text "\u0000")
    (throw (ex-info "That doesn't look like a text file. Upload Markdown (.md) or plain text (.txt)." {})))
  (backup! (if (= "upload" action) "upload" "edit"))
  (spit *doc-file* (str (str/trim text) "\n"))
  (save-summary! (content) overview))

(defn delete! []
  (when-not (content) (throw (ex-info "There is no athlete document to delete." {})))
  (backup! "delete")
  (.delete (io/file *doc-file*))
  (save-summary! nil nil))

(defn restore!
  "Puts backup `id` back as the document; the current one is backed up first."
  [id]
  (let [f (io/file *backup-dir* id)]
    (when-not (and (re-matches #"[\w.:-]+__\w+\.md" id) (.exists f))
      (throw (ex-info "Unknown backup" {:id id})))
    (let [text (slurp f)]
      (backup! "restore")
      (spit *doc-file* (str (str/trim text) "\n"))
      (save-summary! nil nil))))

(defn save-summary-only! [overview] (save-summary! (content) overview))

(defn status []
  {:exists  (boolean (content))
   :content (content)
   :summary (summary)
   :backups (backups)})

;; ---------------------------------------------------------------------------
;; What the code needs from the document, via its summary (Claude reads the
;; file and fills these in). Nothing personal is hard-coded.

(defn settings
  "Personal thresholds for the rule checks, from the summary. Keys:
  :easy_hr_max (top of the Easy zone), :steady_hr (avg HR above which an easy
  run counts as steady), :hrv_low, :rhr_high (readiness, principles: Monitoring),
  :peak_week_km. Unknown values are left out, and their checks skipped."
  []
  (into {} (filter #(and (number? (val %)) (pos? (val %)))) (:settings (summary))))

(defn race
  "{:name :date} of the goal race, from the summary; :date nil when not set."
  []
  (let [{:keys [race race_date]} (summary)]
    {:name (not-empty race)
     :date (when (and race_date (re-matches #"\d{4}-\d{2}-\d{2}" race_date)) race_date)}))
