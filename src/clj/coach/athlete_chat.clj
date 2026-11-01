(ns coach.athlete-chat
  "Editing athlete.md as a conversation with Claude. Claude answers and,
  once the change is clear, proposes edits; nothing is saved until the
  athlete applies them (coach.athlete/save!)."
  (:require [clojure.string :as str]
            [coach.athlete :as athlete]
            [coach.i18n :as i18n]
            [coach.planner :as planner]
            [coach.translate :as translate]))

(def system-intro
  "You help the athlete keep their coaching file, athlete.md, up to date. The coach plans their
training from two documents: the general training principles (fixed, never edited here) and
athlete.md (the athlete, their circumstances, personal zones and baselines, goal race, season
plan). Both are above.

How to work:
- Talk with the athlete in short, plain messages. Ask when something is unclear or missing;
  don't guess numbers, dates or goals.
- Propose a change only once you know what the athlete wants. Keep everything else as it is.
- Keep the section headings: the general principles, the coach and the app refer to them
  by name. If a section no longer applies, say so in it rather than removing it.
- Keep the file consistent: if a change affects other parts (e.g. a new LTHR changes the zones
  in Thresholds & zones), update those too and say so.
- Edits proposed earlier in this chat are applied only if the file above shows them; a new
  proposal replaces the previous one, so always edit from the file as it is now.
- If there is no athlete.md yet, help the athlete create one: ask for their profile, goal race
  and date, circumstances and availability, then propose the whole file in the same structure.

Output fields:
- `reply`: your message to the athlete. When you propose edits, say briefly what changes.
- `edits`: the proposed changes, or [] when you are only talking. Each edit replaces `old_text`
  (copied exactly from the current file and unique in it) with `new_text`. To write the whole
  file (new file or full rewrite) use a single edit with `old_text` \"\".
- `summary`: when you propose edits, the overview of the whole athlete file as it will be after
  the edits (rules below); when you are asked for the overview, the overview of the file as it
  is; otherwise leave every field empty (\"\" and []).

The overview is a small card on the Plan page, read at a glance. Keep only what matters most,
in short fragments, never sentences:
- `race`: the goal race name only (e.g. \"City Marathon\").
- `race_date`: its date as YYYY-MM-DD, or \"\" if there is none.
- `race_detail`: what the race is, a few words (e.g. \"Marathon · 42.2 km\").
- `race_goal`: the goal finish time as H:MM:SS (e.g. \"3:30:00\"; for several races on one day,
  their total), or \"\" if the file gives none.
- `race_legs`: the standard distances the goal race is made of, from
  \"5k\", \"10k\", \"half\" and \"marathon\" (e.g. [\"marathon\"]); [] when it is anything else
  (trail, ultra, triathlon...).
- `athlete`: name and a few words (e.g. \"Alex, 34 · teacher\").
- `stats`: the 3-4 numbers that shape training most, each a short `label` and a `value` with
  its unit (e.g. LTHR / 170 bpm, Easy zone / 133–145 bpm). No more than 4.
- `settings`: numbers the dashboard's rule checks use, taken from the file; 0 when the file
  doesn't give one. `easy_hr_max`: the aerobic threshold, top of Z2 (bpm, tested or provisional).
  `steady_hr`: average HR above which an easy run counts as too hard (bpm; the AeT if no separate
  limit is given). `lt_hr`: lactate threshold HR (bpm). `hrv_low`: bottom of the athlete's normal
  overnight HRV range (ms). `rhr_high`: baseline resting HR + 5 (bpm). `quality_max`: hard
  sessions allowed per week if the file overrides the default of 2. `run_increase_pct`: the
  largest weekly running-time increase the file allows (%; the highest value if it gives a
  range or several). `long_run_max_min`: the cap on a single long run (minutes).
- `focus`: the single most important point for the plan, at most about 8 words.
")

(def setting-keys
  ["easy_hr_max" "steady_hr" "lt_hr" "hrv_low" "rhr_high" "quality_max" "run_increase_pct" "long_run_max_min"])

(def schema
  {"type" "object"
   "additionalProperties" false
   "required" ["reply" "edits" "summary"]
   "properties"
   {"reply"   {"type" "string"}
    "edits"   {"type" "array"
               "items" {"type" "object" "additionalProperties" false
                        "required" ["old_text" "new_text"]
                        "properties" {"old_text" {"type" "string"} "new_text" {"type" "string"}}}}
    "summary" {"type" "object" "additionalProperties" false
               "required" ["race" "race_date" "race_detail" "race_goal" "race_legs" "athlete" "stats" "focus" "settings"]
               "properties" {"race" {"type" "string"}
                             "race_date" {"type" "string"}
                             "race_detail" {"type" "string"}
                             "race_goal" {"type" "string"}
                             "race_legs" {"type" "array"
                                          "items" {"type" "string" "enum" ["5k" "10k" "half" "marathon"]}}
                             "athlete" {"type" "string"}
                             "stats" {"type" "array"
                                      "items" {"type" "object" "additionalProperties" false
                                               "required" ["label" "value"]
                                               "properties" {"label" {"type" "string"}
                                                             "value" {"type" "string"}}}}
                             "focus" {"type" "string"}
                             "settings" {"type" "object" "additionalProperties" false
                                         "required" (vec setting-keys)
                                         "properties" (into {} (for [k setting-keys] [k {"type" "integer"}]))}}}}})

(defn apply-edits
  "The document after `edits`, or an ex-info naming the edit that didn't match."
  [doc edits]
  (reduce (fn [d {:keys [old_text new_text]}]
            (cond
              (= "" old_text) new_text
              (not= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote old_text)) d)))
              (throw (ex-info (i18n/t "An edit didn't match the file exactly once: {0}…"
                                      (subs old_text 0 (min 60 (count old_text))))
                              {:old_text old_text}))
              :else (str/replace d old_text new_text)))
          (or doc "")
          edits))


(defn- tidy
  "Keeps the overview within its limits whatever the model returned."
  [overview]
  (some-> overview (update :stats #(vec (take 4 %)))))

(defn chat
  "One turn. `messages` is [{:role \"user\"|\"assistant\" :text ...}], ending
  with the athlete's message. Returns {:reply :proposal {:content :edits :summary} :usage}."
  [messages]
  (let [doc (athlete/content)
        {:keys [response usage]}
        (planner/request {:knowledge (planner/knowledge doc)
                          :system   system-intro
                          ;; the next turn of the chat reads it
                          :cache?   true
                          :messages (for [{:keys [role text]} messages] [(keyword role) text])
                          :schema   schema
                          :task     :athlete_chat})
        {:keys [reply edits summary]} response]
    {:reply    reply
     :proposal (when (seq edits)
                 {:content (apply-edits doc edits) :edits edits :summary (tidy summary)})
     :usage    usage}))

(def summary-request
  "Write the overview of the current athlete.md (rules above) in `summary`, filling in every
field the file gives (race, date, goal, athlete, stats, settings, focus). You are asked for the
overview, so `summary` must not be left empty even though nothing is edited: `reply` can be
empty and `edits` must be [].")


(defn summarise!
  "Asks Claude for a summary of the current file and stores it."
  []
  (let [doc (or (athlete/content) (throw (ex-info (i18n/t "There is no athlete document.") {})))
        {:keys [response usage]}
        (planner/request {:knowledge (planner/knowledge doc) :system system-intro
                          :messages [[:user summary-request]]
                          :schema schema
                          :task :athlete_summary})]
    (athlete/save-summary-only! (tidy (:summary response)))
    (planner/translate-soon! (translate/overview-texts (tidy (:summary response))))
    {:summary (tidy (:summary response)) :usage usage}))

;; Automatic summary: started in the background when the file has none.
;; One at a time; a failure isn't retried for the same text (the card offers a retry).

(defonce summary-job (atom {:running false :failed nil}))

(defn ensure-summary!
  "Starts a background summary when the file has none. Returns the job state
  for the current text: {:summarising bool :summary_error msg-or-nil}."
  []
  (let [doc  (athlete/content)
        hash (athlete/sha doc)
        ;; also an overview from before the goal time and race legs were part of it
        need (and doc (let [s (athlete/summary)] (or (nil? (:settings s)) (not (contains? s :race_legs)))))
        [old _] (swap-vals! summary-job
                            #(if (and need (not (:running %)) (not= hash (get-in % [:failed :hash])))
                               (assoc % :running true)
                               %))]
    (when (and need (not (:running old)) (not= hash (get-in old [:failed :hash])))
      (future
        (try (summarise!)
             (swap! summary-job assoc :running false :failed nil)
             (catch Throwable e
               (swap! summary-job assoc :running false :failed {:hash hash :error (ex-message e)})))))
    (let [{:keys [running failed]} @summary-job]
      {:summarising   (boolean (and need (or running (not= hash (:hash failed)))))
       :summary_error (when (and need (= hash (:hash failed))) (:error failed))})))
