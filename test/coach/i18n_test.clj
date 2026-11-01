(ns coach.i18n-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [coach.i18n :as i18n]
            [coach.i18n-de :as de]
            [coach.i18n-fr :as fr]))

(defn- source-files []
  (->> ["src/clj" "src/cljs" "src/cljc"]
       (mapcat #(file-seq (io/file %)))
       (filter #(re-find #"\.clj[sc]?$" (.getName %)))
       (remove #(#{"i18n_de.cljc" "i18n_fr.cljc"} (.getName %)))))

(defn- unescape [s] (-> s (str/replace "\\\"" "\"") (str/replace "\\\\" "\\")))

(defn marked-texts
  "Every text in the code marked for translation: (t \"...\") or (tk \"...\")."
  []
  (into #{}
        (for [f (source-files)
              [_ s] (re-seq #"\((?:i18n/)?(?:t|tk)\s+\"((?:[^\"\\]|\\.)*)\"" (slurp f))]
          (unescape s))))

(defn- placeholders [s] (set (re-seq #"\{\d+\}" s)))

(deftest every-text-has-a-translation
  (let [texts (marked-texts)]
    (is (> (count texts) 400) "the scan finds the marked texts")
    (doseq [[lang dict] {"German" de/de "French" fr/fr}]
      (let [missing (sort (remove #(contains? dict %) texts))]
        (is (empty? missing) (str "No " lang " translation for:\n" (str/join "\n" missing)))))))

(deftest translations-are-consistent
  (testing "Swiss Standard German: no ß"
    (is (empty? (filter #(str/includes? (val %) "ß") de/de))))
  (testing "both dictionaries cover the same texts (runtime values included)"
    (is (= (set (keys de/de)) (set (keys fr/fr)))))
  (testing "each translation keeps the English text's placeholders"
    (doseq [dict [de/de fr/fr]]
      (is (empty? (for [[en d] dict :when (not= (placeholders en) (placeholders d))] en))))))

(deftest date-patterns
  (is (= "1. Nov" (i18n/translate "de" "{0} {1}" [1 "Nov"])))
  (is (= "Montag, 19. Oktober" (i18n/translate "de" "{0}, {1} {2}" ["Montag" 19 "Oktober"])))
  (is (= "Lundi 19 octobre" (i18n/translate "fr" "{0}, {1} {2}" ["Lundi" 19 "octobre"])))
  (is (= "Monday, 19 October" (i18n/translate "en" "{0}, {1} {2}" ["Monday" 19 "October"]))))

(deftest claude-language-test
  (require 'coach.planner)
  (let [with-language (resolve 'coach.planner/with-language)]
    (is (= "Plan the week." (with-language "Plan the week.")) "English: unchanged")
    (try
      (reset! i18n/lang "de")
      (let [s (with-language "Plan the week.")]
        (is (str/starts-with? s "Plan the week.") "the task instructions come first, unchanged")
        (is (re-find #"Swiss Standard German" s))
        (is (re-find #"\"du\"" s)))
      (reset! i18n/lang "fr")
      (let [s (with-language "Plan the week.")]
        (is (str/starts-with? s "Plan the week."))
        (is (re-find #"French-speaking Switzerland" s))
        (is (re-find #"\"tu\"" s)))
      (is (nil? (with-language nil)) "no instructions, nothing added")
      (finally (reset! i18n/lang "en")))))

(deftest translate-test
  (is (= "Woche 3 von 24 · Base" (i18n/translate "de" "Week {0} of {1} · {2}" [3 24 "Base"])))
  (is (= "Week 3 of 24 · Base" (i18n/translate "en" "Week {0} of {1} · {2}" [3 24 "Base"])))
  (is (= "Not in the dictionary" (i18n/translate "de" "Not in the dictionary" [])) "falls back to English")
  (try
    (reset! i18n/lang "de")
    (is (= "Kalender" (i18n/t "Calendar")))
    (finally (reset! i18n/lang "en"))))
