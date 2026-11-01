(ns coach.i18n
  "English and German, for the dashboard and the server's own messages.

  Texts are written in English in the code; the English text is the key to
  its translation: German in `coach.i18n-de/de` (Swiss Standard German: \"ss\",
  never \"ß\"; the athlete is \"du\"), French in `coach.i18n-fr/fr` (as written
  in French-speaking Switzerland; the athlete is \"tu\"). A text without a
  translation shows in English. Placeholders are {0}, {1}, ...

  The language is a setting on the server (sync_state :lang), so background
  jobs, Claude's instructions and the dashboard always agree."
  (:require [clojure.string :as str]
            [coach.i18n-de :as de]
            [coach.i18n-fr :as fr]))

(def languages
  "Each language's name is written in that language."
  [{:id "en" :label "English" :short "EN"} {:id "de" :label "Deutsch" :short "DE"}
   {:id "fr" :label "Français" :short "FR"}])
(def language-ids (set (map :id languages)))

(def dictionaries {"de" de/de "fr" fr/fr})

(defonce ^{:doc "The current language: \"en\" or \"de\"."} lang
  (atom #?(:cljs (let [saved (.getItem js/localStorage "lang")] (if (language-ids saved) saved "en"))
           :clj "en")))

(defn fill
  "Pure: replaces {0}, {1}, ... in `template` with `args`."
  [template args]
  (reduce-kv (fn [s i a] (str/replace s (str "{" i "}") (str a))) template (vec args)))

(defn translate
  "Pure: `s` in `language`, with its placeholders filled."
  [language s args]
  (fill (get (dictionaries language) s s) args))

(defn t
  "`s` in the current language. (t \"Week {0} of {1}\" 3 24)"
  [s & args]
  (translate @lang s args))

(defn tk
  "Marks a text for translation where it is defined (in data); it's
  translated where it's shown, with `t`. Returns `s` unchanged."
  [s] s)

(defn de? [] (= "de" @lang))

(defn locale
  "For dates and numbers (Swiss formats in German and French)."
  []
  (case @lang "de" "de-CH" "fr" "fr-CH" "en-GB"))
