(ns coach.ai-settings
  "Which Claude model and effort each task uses, chosen by the athlete on the
  API costs page. Stored in sync_state (so backups carry it); tasks without a
  choice use `defaults`."
  (:require [clojure.data.json :as json]
            [coach.db :as db]
            [coach.i18n :as i18n :refer [tk]]))

(def models
  "Offered models, most capable first. :fallback? = the server-side refusal
  fallback is available (Claude Haiku 5.5 has none)."
  [{:id "claude-fable-5-1"  :label "Claude Fable 5.1"  :fallback? true}
   {:id "claude-opus-5-5"   :label "Claude Opus 5.5"   :fallback? true}
   {:id "claude-sonnet-5-5" :label "Claude Sonnet 5.5" :fallback? true}
   {:id "claude-haiku-5-5"  :label "Claude Haiku 5.5"  :fallback? false}])

(def model-ids (set (map :id models)))
(defn fallback? [model] (boolean (:fallback? (some #(when (= model (:id %)) %) models))))

(def efforts ["low" "medium" "high" "xhigh" "max"])

(def deliveries
  "How a task's requests are sent. Batch requests (the Message Batches API)
  cost half and take minutes, at most 24 hours."
  [{:id "auto"    :label (tk "Batch when automatic")
    :about (tk "Half price for requests nobody waits for (after a sync); instant when you click.")}
   {:id "batch"   :label (tk "Always batch")
    :about (tk "Always half price; also your own clicks wait for the batch (usually under an hour).")}
   {:id "instant" :label (tk "Always instant")
    :about (tk "Full price, the answer in a minute or two.")}])

(def delivery-ids (set (map :id deliveries)))

(def batchable
  "Tasks that can go through the batch. Chat and the summary are always
  instant: someone is waiting for them."
  #{"season_plan" "week_plan" "adjust"})

(def tasks
  "Every task that calls Claude, with the recommended model and effort (the
  defaults) and why. Judgement, not measured: care goes where a mistake is
  costly or rare work sets up everything after it; simple, frequent work
  goes to cheaper models."
  [{:id "season_plan"     :label (tk "Season plans")     :model "claude-opus-5-5" :effort "high"
    :about (tk "The whole season to race day. Rare, long answer.")
    :why   (tk "The hardest reasoning, and every week builds on it; it runs a few times a season.")}
   {:id "week_plan"       :label (tk "Week plans")       :model "claude-opus-5-5" :effort "medium"
    :about (tk "One week of sessions, checked against the rules.")
    :why   (tk "Medium is Opus 5.5's own default; the rule checks and your review catch slips.")}
   {:id "adjust"          :label (tk "Plan adjustments") :model "claude-opus-5-5" :effort "high"
    :about (tk "Changes to the next two weeks after signals or a note.")
    :why   (tk "Reacts to pain, illness and fatigue - the riskiest calls. Short answers, so high costs little.")}
   {:id "athlete_chat"    :label (tk "Athlete chat")     :model "claude-sonnet-5-5" :effort "low"
    :about (tk "Editing the athlete document by chat.")
    :why   (tk "Careful text edits you review before saving; Sonnet does this well at half Opus's price.")}
   {:id "athlete_summary" :label (tk "Athlete summary")  :model "claude-haiku-5-5" :effort "low"
    :about (tk "The short overview on the athlete card.")
    :why   (tk "Summarising a document is simple work: Haiku costs about a fortieth of Opus.")}
   {:id "translation"     :label (tk "Translations")    :model "claude-sonnet-5-5" :effort "low"
    :about (tk "Claude's texts in the other dashboard languages, once per result.")
    :why   (tk "Translating short texts needs good wording, not deep thought; Sonnet writes natural German and French.")}])

(def scopes
  "For the Translations task: which languages Claude's texts are kept in."
  [{:id "selected" :label (tk "Selected language only")
    :about (tk "Translated only into the language in use; switching fills in the rest (about a minute). Cheaper.")}
   {:id "all"      :label (tk "All languages")
    :about (tk "Every result is translated into every language right away; switching is instant.")}])

(def scope-ids (set (map :id scopes)))

(def defaults
  (into {} (for [t tasks]
             [(:id t) (cond-> (select-keys t [:model :effort])
                        (batchable (:id t)) (assoc :delivery "auto")
                        (= "translation" (:id t)) (assoc :scope "selected"))])))

(defonce ^{:doc "The DB the choices live in; set by the dashboard on start.
  nil (tests, REPL) means the defaults apply."}
  settings-ds (atom nil))

(defn use-db! [ds] (reset! settings-ds ds))

(defn- stored [ds]
  (or (some-> (db/get-state ds :ai_settings) (json/read-str)) {}))

(defn all
  "task id -> {:model :effort}, defaults filled in."
  ([] (if-let [ds @settings-ds] (all ds) defaults))
  ([ds]
   (merge-with merge defaults
               (into {} (for [[t m] (stored ds) :when (defaults t)]
                          [t (into {} (for [[k v] m] [(keyword k) v]))])))))

(defn for-task
  "{:model :effort} for a task (keyword or string). Unknown tasks get the
  week-plan setting's defaults."
  [task]
  (get (all) (some-> task name) {:model "claude-opus-5-5" :effort "high"}))

(defn batch?
  "Should this request go through the half-price batch? `trigger` is :auto
  (started by the app, e.g. after a sync) or :manual (the athlete clicked)."
  [task trigger]
  (case (:delivery (for-task task))
    "batch" true
    "auto"  (= :auto trigger)
    false))

(defn save!
  "Stores a task's model, effort and (for batchable tasks) delivery."
  [ds task {:keys [model effort delivery scope]}]
  (when-not (defaults task) (throw (ex-info (str "Unknown task: " task) {:task task})))
  (when-not (model-ids model) (throw (ex-info (str "Unknown model: " model) {:model model})))
  (when-not (some #{effort} efforts) (throw (ex-info (str "Unknown effort: " effort) {:effort effort})))
  (when (and delivery (not (and (batchable task) (delivery-ids delivery))))
    (throw (ex-info (str "Unknown delivery for " task ": " delivery) {:delivery delivery})))
  (when (and scope (not (and (= "translation" task) (scope-ids scope))))
    (throw (ex-info (str "Unknown scope for " task ": " scope) {:scope scope})))
  (db/set-state! ds :ai_settings
                 (json/write-str (assoc (stored ds) task (cond-> {:model model :effort effort}
                                                           delivery (assoc :delivery delivery)
                                                           scope    (assoc :scope scope)))))
  (all ds))

(defn reset-all! [ds]
  (db/set-state! ds :ai_settings (json/write-str {}))
  (all ds))

(defn overview [ds]
  {:tasks (for [task tasks]
           (-> (merge task (get (all ds) (:id task)))
               (update :label i18n/t) (update :about i18n/t) (update :why i18n/t)))
   :defaults defaults
   :deliveries (for [d deliveries] (-> d (update :label i18n/t) (update :about i18n/t)))
   :scopes (for [d scopes] (-> d (update :label i18n/t) (update :about i18n/t)))
   :models models
   :efforts efforts})
