(ns coach.coach-chat
  "Ask the coach: a chat about the athlete's own training data. Claude answers from the same data the
  planner sees. It never changes the plan: when the athlete wants a change it only offers a note for
  the usual adjustment (coach.planner/propose-adjustment!), which the athlete starts and reviews."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [coach.adapt :as adapt]
            [coach.claude :as claude]
            [coach.debrief :as debrief]
            [coach.plan :as plan]
            [coach.planner :as planner]
            [coach.season :as season]))

(def system-intro
  "You are the athlete's coach, answering their questions in a chat. The general training principles
and athlete.md are above. The athlete's current data is at the start of their first message: the
last six weeks, recent activities with how they went, readiness, load (CTL/ATL/TSB), the plan for
this week and next with what is done or missed, the signals the app raised, the coach's notes after
recent workouts and, for an athlete who tracks her cycle, the cycle.

How to answer:
- Short and plain, in the athlete's language: a few sentences, a short list only when it helps.
  Use numbers from the data and name the principle you rely on; never invent a number.
- If the data can't answer the question, say what is missing instead of guessing.
- Pain, illness or a worrying pattern: say what to watch, and when to see a doctor or physio. Don't diagnose.
- You can't change anything and must never say you did. The plan is changed only by an
  adjustment the athlete starts and accepts.
- When the athlete wants the plan changed (move, shorten, swap or drop a session, ease off after a
  bad week), or the answer leads to a change, put in `adjust_note` what the athlete would tell
  the coach in their own words (\"I'd like to move Thursday's intervals to Friday because ...\"), and
  in `reply` say you can propose it and they review it. Otherwise `adjust_note` is \"\".

Output fields: `reply` (your answer) and `adjust_note`.")

(def schema
  {"type" "object"
   "additionalProperties" false
   "required" ["reply" "adjust_note"]
   "properties" {"reply" {"type" "string"} "adjust_note" {"type" "string"}}})

(def max-messages "The last this many messages of the chat are sent." 20)

(def max-chars "A message is cut to this length." 4000)

(defn data
  "What the coach sees in a chat, as of today: the planner's context (6 weeks, recent activities with
  feedback, readiness, load, thresholds, plan, cycle when tracked) plus the plan for the next two weeks,
  the app's signals and the newest coach's notes."
  [ds]
  (let [today  (season/today)
        monday (season/monday today)]
    (-> (planner/context ds monday)
        (dissoc :proposed_previous_week)
        (assoc :plan_next_two_weeks
               (mapv #(select-keys % [:date :slot :session_id :title :duration_min :distance_km :intensity :status])
                     (plan/workouts ds today (season/add-days monday 13)))
               :signals (mapv #(select-keys % [:severity :message :principle])
                              (adapt/signals (planner/adapt-inputs ds today)))
               :coach_notes (debrief/recent ds today 14 6)))))

(defn request-messages
  "[[role text] ...] for the API: the last `max-messages` of the chat, the first one
  carrying `data-json`, so later turns of the chat reuse the same framing."
  [messages data-json today]
  (let [msgs (->> messages
                  (map (fn [{:keys [role text]}]
                         [(if (= "assistant" role) :assistant :user)
                          (subs (str text) 0 (min max-chars (count (str text))))]))
                  (remove (comp str/blank? second))
                  (take-last max-messages)
                  (drop-while #(not= :user (first %)))) ; a conversation starts with the athlete
        [[_ first-text] & more] msgs]
    (when first-text
      (into [[:user (str "My data (today is " today "):\n" data-json "\n\nMy message: " first-text)]]
            more))))

(defn chat
  "One turn. `messages` is [{:role \"user\"|\"assistant\" :text ...}], ending with the athlete's message.
  Returns {:reply :adjust_note (nil when none) :usage}."
  [ds messages]
  (let [today     (season/today)
        data-json (json/write-str (data ds))
        msgs      (request-messages messages data-json today)]
    (when-not msgs
      (throw (ex-info "No message to answer" {:messages messages})))
    (let [{:keys [response usage]}
          (claude/request {:knowledge (claude/principles)
                           :system    (cond-> system-intro
                                        (str/includes? data-json "\"menstrual_cycle\"") (str (planner/cycle-system)))
                           ;; the next turn of the chat reads the knowledge from the cache
                           :cache?    true
                           :messages  msgs
                           :schema    schema
                           :task      :coach_chat})]
      {:reply       (:reply response)
       :adjust_note (not-empty (str/trim (str (:adjust_note response))))
       :usage       usage})))
