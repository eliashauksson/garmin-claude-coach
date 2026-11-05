(ns coach.translate
  "Claude's own texts (season plan, proposals, planned sessions, the athlete
  overview) in every dashboard language.

  When a Claude result comes back, its texts are translated once into the
  other languages (task :translation, Claude Sonnet 5.5 at low effort by
  default) and stored in the translations table, keyed by a hash of the
  original. The original is stored too, under the language it was written in.
  Pages then show the stored version for the current language; nothing is
  translated while a page loads. At dashboard start, anything still missing
  (older texts, a translation that failed) is filled in the same way."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [coach.ai-settings :as ai]
            [coach.athlete :as athlete]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.plan :as plan]
            [next.jdbc :as jdbc])
  (:import (java.time LocalDate)))

(def languages (mapv :id i18n/languages))

(defn text-hash [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- translatable? [s]
  ;; real words, not IDs, numbers or codes
  (and (string? s) (re-find #"\p{L}{3,}" s) (re-find #"\s" s)))

;; ---------------------------------------------------------------------------
;; Which texts are Claude's

(defn overview-texts [{:keys [race_detail athlete focus stats]}]
  (concat [race_detail athlete focus] (mapcat (juxt :label :value) stats)))

(defn response-texts [{:keys [summary targets warnings weeks]}]
  (concat [summary] (vals (select-keys targets [:longest_run :quality])) warnings (map :focus weeks)))

(defn- workout-texts [w]
  (let [blocks (:blocks (plan/parse-blocks w))]
    (concat ((juxt :title :description :reason :intensity :goal) w)
            (map :label blocks)
            (map :note (mapcat :steps blocks)))))

(defn proposal-texts
  "The texts of one stored proposal: its summary and targets, and its sessions."
  [ds id]
  (when-let [p (plan/proposal ds id)]
    (concat (response-texts (:response p)) (mapcat workout-texts (:items p)))))

(defn current-texts
  "Claude-written texts in use now: the current season plan, the 20 newest
  proposals, sessions from two weeks ago on, the athlete overview."
  [ds]
  (let [today (str (LocalDate/now))
        sp    (plan/latest-season-plan ds "ready")
        props (keep #(plan/proposal ds (:id %))
                    (db/query ds ["SELECT id FROM plan_proposals WHERE status <> 'failed' ORDER BY id DESC LIMIT 20"]))]
    (->> (concat (response-texts (:response sp))
                 (mapcat #(concat (response-texts (:response %)) (mapcat workout-texts (:items %))) props)
                 (mapcat workout-texts (db/query ds ["SELECT * FROM planned_workouts WHERE date >= ?"
                                                     (str (.minusDays (LocalDate/parse today) 14))]))
                 (overview-texts (athlete/summary)))
         (filter translatable?)
         distinct
         vec)))

;; ---------------------------------------------------------------------------
;; Storage

(defn stored-langs
  "text -> the set of languages it is stored in."
  [ds texts]
  (let [by-hash (group-by text-hash texts)
        rows    (when (seq texts)
                  (db/query ds (into [(str "SELECT hash, lang FROM translations WHERE hash IN ("
                                           (str/join "," (repeat (count by-hash) "?")) ")")]
                                     (keys by-hash))))
        langs   (reduce (fn [m {:keys [hash lang]}] (update m hash (fnil conj #{}) lang)) {} rows)]
    (into {} (for [t texts] [t (get langs (text-hash t) #{})]))))

(defn store! [ds lang pairs]
  (doseq [[original text] pairs]
    (jdbc/execute! ds ["INSERT INTO translations (hash, lang, text, created_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT(hash, lang) DO UPDATE SET text = excluded.text, created_at = excluded.created_at"
                       (text-hash original) lang text (str (java.time.Instant/now))])))

;; ---------------------------------------------------------------------------
;; Translating (one request per chunk of texts)

(def system-text
  "You translate short texts from a personal running-coach app: training plans, session titles,
descriptions, reasons and athlete overviews.

Rules:
- Translate every text into each requested language. A text already in that language comes back unchanged.
- German: Swiss Standard German spelling with \"ss\", never \"ß\"; address the athlete as \"du\".
- French: French as written in French-speaking Switzerland; address the athlete as \"tu\".
- English: address the athlete as \"you\".
- Keep unchanged: numbers, units, times and paces, session IDs in capitals (such as EASY_ST or B2B),
  zone names (Z1 to Z5), abbreviations (TSS, RPE, HRV, LTHR, AeT, LT, ME, ADS, CTL, ATL, TSB),
  names of people, places and races, and section headings of the training documents
  (such as \"Load & recovery\" or \"Running: Progression\").
- Keep the tone and length of the original. Don't add or drop information.
- Return exactly one entry per input text, in the same order.")

(defn- schema [targets]
  {"type" "object"
   "properties" {"translations"
                 {"type" "array"
                  "items" {"type" "object"
                           "properties" (into {} (for [l targets] [l {"type" "string"}]))
                           "required" (vec targets)
                           "additionalProperties" false}}}
   "required" ["translations"]
   "additionalProperties" false})

(defn- chunks
  "Groups of texts of at most ~6000 characters, so answers stay short."
  [texts]
  (->> texts
       (reduce (fn [[cur size acc] t]
                 (if (and (seq cur) (> (+ size (count t)) 6000))
                   [[t] (count t) (conj acc cur)]
                   [(conj cur t) (+ size (count t)) acc]))
               [[] 0 []])
       ((fn [[cur _ acc]] (cond-> acc (seq cur) (conj cur))))))

(defn translate-chunk!
  "Translates `texts` into `targets` with one Claude request and stores the
  results. Throws (storing nothing) when the answer doesn't match the input."
  [ds texts targets request]
  (let [{:keys [response]} (request {:task :translation
                                     :system system-text
                                     :messages [[:user (json/write-str {:target_languages targets :texts texts})]]
                                     :schema (schema targets)})
        out (:translations response)]
    (when-not (and (= (count out) (count texts))
                   (every? (fn [o] (every? #(not (str/blank? (get o (keyword %)))) targets)) out))
      (throw (ex-info "The translation didn't match the texts sent; nothing stored." {:sent (count texts) :got (count out)})))
    (doseq [l targets]
      (store! ds l (map vector texts (map #(get % (keyword l)) out))))
    (count texts)))

(def scopes
  "What the athlete chose for the Translations task (coach.ai-settings :scope)."
  #{"all" "selected"})

(defn wanted-languages
  "Pure: the languages texts are kept in. \"all\": every dashboard language,
  so switching is instant. \"selected\": only the one in use; another is
  filled in when the athlete switches to it."
  [scope current]
  (if (= "selected" scope) [current] languages))

(defn remember-source!
  "Stores `texts` (a Claude result just written in `lang`) as their own
  original in that language, so they're never sent back for it. Texts
  already stored in some language are left alone."
  [ds lang texts]
  (let [texts (vec (distinct (filter translatable? texts)))
        have  (stored-langs ds texts)]
    (store! ds lang (for [t texts :when (empty? (have t))] [t t]))))

(defn translate-missing!
  "Translates every text in use that lacks a wanted language (see
  wanted-languages); a text sent for the language it's written in comes back
  unchanged. Returns how many texts were sent. `request` is
  coach.planner/request (passed in to avoid a namespace cycle)."
  [ds request]
  (let [wanted (wanted-languages (:scope (ai/for-task :translation)) @i18n/lang)
        texts  (current-texts ds)
        have   (stored-langs ds texts)
        ;; group by the languages still missing: one request per group and chunk
        groups (group-by #(vec (remove (have %) wanted)) texts)]
    (reduce + 0 (for [[targets ts] groups :when (seq targets)
                      chunk (chunks ts)]
                  (translate-chunk! ds chunk targets request)))))

(defonce ^{:doc "The dashboard's DB; set on start. nil (tests, REPL): nothing is translated."}
  ds-ref (atom nil))

(defn use-db! [ds] (reset! ds-ref ds))

(def ^:private lock (Object.))

(defonce ^{:doc "How many translation runs are under way (the dashboard shows a note)."}
  running (atom 0))

(defn soon!
  "Translates what's missing in the background, one run at a time (results
  that arrive together aren't sent twice). `new-texts`, if given, were just
  written by Claude in the current language and are stored as such first.
  Failures are logged; the originals show until the next run (at the latest
  the next dashboard start)."
  [request & [new-texts]]
  (when-let [ds @ds-ref]
    (let [lang @i18n/lang]
      (swap! running inc)
      (future
        (try
          (locking lock
            (when (seq new-texts) (remember-source! ds lang new-texts))
            (let [n (translate-missing! ds request)]
              (when (pos? n) (println "Translated" n "text(s)"))))
          (catch Throwable e
            (println "Translation failed:" (ex-message e)))
          (finally (swap! running dec)))))))

(defn running? [] (pos? @running))

;; ---------------------------------------------------------------------------
;; Showing them

(defn localize
  "`x` (a response map) with every string that has a stored translation for
  `lang` replaced by it. Only Claude's texts are in the store."
  [ds lang x]
  (let [strings (atom #{})]
    (walk/postwalk #(do (when (translatable? %) (swap! strings conj %)) %) x)
    (if (empty? @strings)
      x
      (let [hashes (into {} (map (juxt text-hash identity)) @strings)
            rows   (db/query ds (into [(str "SELECT hash, text FROM translations WHERE lang = ? AND hash IN ("
                                            (str/join "," (repeat (count hashes) "?")) ")")
                                       lang]
                                      (keys hashes)))
            tr     (into {} (for [{:keys [hash text]} rows] [(hashes hash) text]))]
        (if (empty? tr) x (walk/postwalk #(if (string? %) (get tr % %) %) x))))))
