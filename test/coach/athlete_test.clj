(ns coach.athlete-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [coach.athlete :as athlete]
            [coach.athlete-chat :as chat]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "athlete" (make-array java.nio.file.attribute.FileAttribute 0))))

(defmacro with-temp [& body]
  `(let [d# (temp-dir)]
     (binding [athlete/*doc-file*     (str d# "/athlete.md")
               athlete/*backup-dir*   (str d# "/backups")
               athlete/*summary-file* (str d# "/summary.json")]
       (spit athlete/*doc-file* "# Athlete\nLTHR 170\n")
       ~@body)))

(deftest save-backup-delete-restore-test
  (with-temp
    (testing "save keeps a backup and the summary for the new text"
      (athlete/save! "# Athlete\nLTHR 172" {:race "City Marathon" :stats [{:label "LTHR" :value "172 bpm"}]})
      (is (= "# Athlete\nLTHR 172" (athlete/content)))
      (is (= {:race "City Marathon" :stats [{:label "LTHR" :value "172 bpm"}]} (athlete/summary)))
      (is (= ["edit"] (map :action (athlete/backups)))))
    (testing "only the newest 3 backups stay"
      (doseq [n [177 178 179]]
        (Thread/sleep 5)
        (athlete/save! (str "# Athlete\nLTHR " n) nil))
      (is (= 3 (count (athlete/backups))))
      (is (= "# Athlete\nLTHR 178" (slurp (io/file athlete/*backup-dir* (:id (first (athlete/backups))))))))
    (testing "a summary for older text isn't shown"
      (is (nil? (athlete/summary))))
    (testing "delete backs up, then restore brings it back"
      (Thread/sleep 5)
      (athlete/delete!)
      (is (nil? (athlete/content)))
      (is (= "delete" (:action (first (athlete/backups)))))
      (athlete/restore! (:id (first (athlete/backups))))
      (is (= "# Athlete\nLTHR 179" (athlete/content))))
    (testing "bad input"
      (is (thrown? clojure.lang.ExceptionInfo (athlete/save! "  " nil)))
      (is (thrown? clojure.lang.ExceptionInfo (athlete/restore! "../athlete.md"))))))

(deftest apply-edits-test
  (let [doc "## A1\nLTHR 170\n## A3\nT 165-172\n"]
    (is (= "## A1\nLTHR 172\n## A3\nT 167-174\n"
           (chat/apply-edits doc [{:old_text "LTHR 170" :new_text "LTHR 172"}
                                  {:old_text "T 165-172" :new_text "T 167-174"}])))
    (testing "an empty old_text writes the whole file"
      (is (= "new" (chat/apply-edits nil [{:old_text "" :new_text "new"}]))))
    (testing "no match or several matches is an error"
      (is (thrown? clojure.lang.ExceptionInfo (chat/apply-edits doc [{:old_text "LTHR 999" :new_text "x"}])))
      (is (thrown? clojure.lang.ExceptionInfo (chat/apply-edits doc [{:old_text "## A" :new_text "x"}]))))))

(deftest upload-test
  (with-temp
    (testing "an upload replaces the document, backs up the old one and asks for a new summary"
      (athlete/save! "# Athlete\nLTHR 170" {:race "Old" :settings {:hrv_low 40}})
      (athlete/save! "# My notes\nGoal: autumn marathon\n" nil "upload")
      (is (= "# My notes\nGoal: autumn marathon" (athlete/content)))
      (is (nil? (athlete/summary)))
      (is (= "upload" (:action (first (athlete/backups))))))
    (testing "binary, empty and very long files are refused"
      (is (thrown? Exception (athlete/save! "PK\u0003\u0004\u0000\u0000" nil "upload")))
      (is (thrown? Exception (athlete/save! "   " nil "upload")))
      (is (thrown? Exception (athlete/save! (apply str (repeat (inc athlete/max-chars) "x")) nil "upload")))
      (is (= "# My notes\nGoal: autumn marathon" (athlete/content))))))
