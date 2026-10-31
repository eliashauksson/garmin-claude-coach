(ns coach.athlete-chat
  "Editing athlete.md as a conversation with Claude. Claude answers and,
  once the change is clear, proposes edits; nothing is saved until the
  athlete applies them (coach.athlete/save!)."
  (:require [clojure.string :as str]
            [coach.athlete :as athlete]
            [coach.planner :as planner])
  (:import (com.anthropic.models.beta.messages BetaOutputConfig$Effort)))

(def system-intro
  "You help the athlete keep their coaching file, athlete.md, up to date. The coach plans their
training from two documents: the general training principles (fixed, never edited here) and
athlete.md (the athlete, their circumstances, personal zones and baselines, goal race, season
plan). Both are below.

How to work:
- Talk with the athlete in short, plain messages. Ask when something is unclear or missing;
  don't guess numbers, dates or goals.
- Propose a change only once you know what the athlete wants. Keep everything else as it is.
- Keep the section structure (§A1-§A9 headings) and the session IDs: the general principles
  and the app refer to them. If a section no longer applies, say so in it rather than removing it.
- Keep the file consistent: if a change affects other parts (e.g. a new LTHR changes the zones
  in §A3), update those too and say so.
- Edits proposed earlier in this chat are applied only if the file below shows them; a new
  proposal replaces the previous one, so always edit from the file as it is now.
- If there is no athlete.md yet, help the athlete create one: ask for their profile, goal race
  and date, circumstances and availability, then propose the whole file in the same structure.

Output fields:
- `reply`: your message to the athlete. When you propose edits, say briefly what changes.
- `edits`: the proposed changes, or [] when you are only talking. Each edit replaces `old_text`
  (copied exactly from the current file and unique in it) with `new_text`. To write the whole
  file (new file or full rewrite) use a single edit with `old_text` \"\".
- `summary`: when you propose edits, the overview of the whole athlete file as it will be after
  the edits (rules below); otherwise leave every field empty (\"\" and []).

The overview is a small card on the Plan page, read at a glance. Keep only what matters most,
in short fragments, never sentences:
- `race`: the goal race name only (e.g. \"City Marathon\").
- `race_date`: its date as YYYY-MM-DD, or \"\" if there is none.
- `race_detail`: what the race is, a few words (e.g. \"Marathon + half + 10k · ~73 km\").
- `athlete`: name and a few words (e.g. \"Alex, 34 · teacher\").
- `stats`: the 3-4 numbers that shape training most, each a short `label` and a `value` with
  its unit (e.g. LTHR / 174 bpm, Easy zone / 135–148 bpm). No more than 4.
- `focus`: the single most important point for the plan, at most about 8 words.
")

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
               "required" ["race" "race_date" "race_detail" "athlete" "stats" "focus"]
               "properties" {"race" {"type" "string"}
                             "race_date" {"type" "string"}
                             "race_detail" {"type" "string"}
                             "athlete" {"type" "string"}
                             "stats" {"type" "array"
                                      "items" {"type" "object" "additionalProperties" false
                                               "required" ["label" "value"]
                                               "properties" {"label" {"type" "string"}
                                                             "value" {"type" "string"}}}}
                             "focus" {"type" "string"}}}}})

(defn apply-edits
  "The document after `edits`, or an ex-info naming the edit that didn't match."
  [doc edits]
  (reduce (fn [d {:keys [old_text new_text]}]
            (cond
              (= "" old_text) new_text
              (not= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote old_text)) d)))
              (throw (ex-info (str "An edit didn't match the file exactly once: "
                                   (subs old_text 0 (min 60 (count old_text))) "…")
                              {:old_text old_text}))
              :else (str/replace d old_text new_text)))
          (or doc "")
          edits))

(defn system-text [doc]
  (str system-intro
       "\n# General training principles (read-only)\n\n" (slurp planner/general-file)
       "\n\n# Current athlete.md\n\n" (or doc "(no athlete.md yet)")))

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
        (planner/request {:system   (system-text doc)
                          :messages (for [{:keys [role text]} messages] [(keyword role) text])
                          :schema   schema
                          :effort   BetaOutputConfig$Effort/LOW})
        {:keys [reply edits summary]} response]
    {:reply    reply
     :proposal (when (seq edits)
                 {:content (apply-edits doc edits) :edits edits :summary (tidy summary)})
     :usage    usage}))

(def summary-request
  "Write the overview of the current athlete.md (rules above) in `summary`. `reply` can be
empty and `edits` must be [].")


(defn summarise!
  "Asks Claude for a summary of the current file and stores it."
  []
  (let [doc (or (athlete/content) (throw (ex-info "There is no athlete document." {})))
        {:keys [response usage]}
        (planner/request {:system (system-text doc) :messages [[:user summary-request]]
                          :schema schema :effort BetaOutputConfig$Effort/LOW})]
    (athlete/save-summary-only! (tidy (:summary response)))
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
        need (and doc (nil? (athlete/summary)))
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
