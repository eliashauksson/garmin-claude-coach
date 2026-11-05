(ns coach.translate-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.ai-settings :as ai]
            [coach.athlete :as athlete]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.translate :as tr]
            [next.jdbc]))

(defn- no-athlete-files
  "Keeps the tests away from the real data/athlete*.md files."
  [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory "coach-tr" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [athlete/*doc-file* (str dir "/athlete.md")
              athlete/*summary-file* (str dir "/athlete-summary.json")
              athlete/*backup-dir* (str dir "/backups")]
      (f))))

(clojure.test/use-fixtures :each no-athlete-files)

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-translate" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- fake-claude
  "Stands in for coach.claude/request: \"[de] text\" etc. Records the calls."
  [calls]
  (fn [{:keys [task messages]}]
    (let [{:keys [target_languages texts]} (json/read-str (second (first messages)) :key-fn keyword)]
      (swap! calls conj {:task task :targets target_languages :texts texts})
      {:response {:translations (vec (for [t texts]
                                       (into {} (for [l target_languages] [(keyword l) (str "[" l "] " t)]))))}})))

(defn- add-workout! [ds date title description]
  (next.jdbc/execute! ds ["INSERT INTO planned_workouts (date, slot, session_id, title, duration_min, distance_km,
                             intensity, description, reason, status, created_at, updated_at)
                           VALUES (?, 'AM', 'EASY', ?, 30, 5, 'Z1-Z2', ?, NULL, 'planned', 'x', 'x')"
                          date title description]))

(defn- all-languages
  "Runs `f` with the Translations scope set to all languages (the default is the selected one)."
  [f]
  (with-redefs [ai/for-task (constantly {:scope "all"})] (f)))

(deftest translate-once-and-store
  (all-languages
   #(let [ds    (temp-ds)
        calls (atom [])
        today (str (java.time.LocalDate/now))]
    (add-workout! ds today "Easy run" "Relaxed 30 minutes on flat ground.")
    (testing "texts in use are sent once, for every language"
      (is (= 2 (tr/translate-missing! ds (fake-claude calls))))
      (is (= [:translation] (distinct (map :task @calls))))
      (is (= #{"en" "de" "fr"} (set (:targets (first @calls))))))
    (testing "stored: a second run sends nothing"
      (reset! calls [])
      (is (zero? (tr/translate-missing! ds (fake-claude calls))))
      (is (empty? @calls)))
    (testing "a page shows the stored text for its language; the original stays in the DB"
      (let [w {:title "Easy run" :description "Relaxed 30 minutes on flat ground." :session_id "EASY"}]
        (is (= {:title "[fr] Easy run" :description "[fr] Relaxed 30 minutes on flat ground." :session_id "EASY"}
               (tr/localize ds "fr" w)))
        (is (= "Easy run" (:title (first (db/query ds ["SELECT title FROM planned_workouts"])))))))
    (testing "a changed text is new, so it's translated again"
      (add-workout! ds today "Easy run" "Relaxed 40 minutes, a few strides.")
      (reset! calls [])
      (is (= 1 (tr/translate-missing! ds (fake-claude calls))))
      (is (= ["Relaxed 40 minutes, a few strides."] (:texts (first @calls))))))))

(deftest a-wrong-answer-stores-nothing
  (let [ds (temp-ds)
        bad (fn [_] {:response {:translations [{:en "x" :de "y" :fr "z"}]}})] ; one answer for two texts
    (is (thrown? clojure.lang.ExceptionInfo
                 (tr/translate-chunk! ds ["First text here." "Second text here."] ["en" "de" "fr"] bad)))
    (is (empty? (db/query ds ["SELECT * FROM translations"])))
    (testing "the page then shows the original"
      (is (= {:title "First text here."} (tr/localize ds "de" {:title "First text here."}))))))

(deftest only-real-text-is-translated
  (let [ds (temp-ds)]
    (tr/store! ds "de" [["Easy run" "Lockerer Lauf"]])
    (is (= {:id "EASY" :title "Lockerer Lauf" :n 5 :zone "Z1"}
           (tr/localize ds "de" {:id "EASY" :title "Easy run" :n 5 :zone "Z1"}))
        "IDs, numbers and codes are left alone")))

(deftest languages-by-scope
  (is (= ["en" "de" "fr"] (tr/wanted-languages "all" "de")))
  (is (= ["de"] (tr/wanted-languages "selected" "de"))))

(deftest a-new-result-is-not-sent-back-for-its-own-language
  (all-languages
   #(let [ds    (temp-ds)
        calls (atom [])
        today (str (java.time.LocalDate/now))]
    (add-workout! ds today "Lockerer Lauf" "Locker 30 Minuten auf flachem Gelände.")
    ;; Claude just wrote these in German
    (tr/remember-source! ds "de" ["Lockerer Lauf" "Locker 30 Minuten auf flachem Gelände."])
    (tr/translate-missing! ds (fake-claude calls))
    (is (= ["en" "fr"] (:targets (first @calls))) "all languages: only the other two are requested")
    (is (= "Lockerer Lauf" (tr/localize ds "de" "Lockerer Lauf"))))))

(deftest selected-language-only
  (let [ds    (temp-ds)
        calls (atom [])
        today (str (java.time.LocalDate/now))]
    (add-workout! ds today "Easy run" "Relaxed 30 minutes on flat ground.")
    (tr/remember-source! ds "en" ["Easy run" "Relaxed 30 minutes on flat ground."])
    (with-redefs [ai/for-task (constantly {:scope "selected"})]
      (testing "in the language the text is in: nothing to do"
        (is (zero? (tr/translate-missing! ds (fake-claude calls))))
        (is (empty? @calls)))
      (testing "after switching to French: only French is requested"
        (try
          (reset! i18n/lang "fr")
          (is (= 2 (tr/translate-missing! ds (fake-claude calls))))
          (is (= [["fr"]] (map :targets @calls)))
          (is (= "[fr] Easy run" (tr/localize ds "fr" "Easy run")))
          (is (= "Easy run" (tr/localize ds "de" "Easy run")) "German wasn't made")
          (finally (reset! i18n/lang "en")))))))
