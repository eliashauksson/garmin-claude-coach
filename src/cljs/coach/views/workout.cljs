(ns coach.views.workout
  "One planned session as a card: what it is for, what to focus on, and its
  blocks (coach.workout). Opened from the calendar, the plan and the day."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.plan :as plan]
            [coach.views.structure :as st]))

(defn url [id] (str "/api/plan/workouts/" id))

(defn- amount [{:keys [duration_min distance_km]}]
  (str/join " · " (remove nil? [(when (and duration_min (pos? duration_min)) (str duration_min " min"))
                                (when (and distance_km (pos? distance_km)) (str distance_km " km"))])))

(defn- status-pill [{:keys [status activity_id deviation]}]
  (case status
    "done"      (if activity_id
                  [:a.pill {:href (str "#/activities/" activity_id)} (t "✓ done") " · " (t "Open activity")
                   (when deviation (str " · " (t (ui/deviation-labels deviation))))]
                  (ui/pill "--green" (t "✓ done")))
    "missed"    [:span.pill.missed (t "missed")]
    "skipped"   (ui/pill (t "skipped"))
    "cancelled" (ui/pill (t "cancelled"))
    nil))

(defn goal-section [{:keys [goal reason intensity]}]
  (list
   (when-not (str/blank? goal)
     [:div {:replicant/key "goal"} [:div.lab (t "Goal & focus")] [:div.wk-goal goal]])
   (when-not (str/blank? intensity)
     [:div.sub {:replicant/key "int"} intensity])
   (when-not (str/blank? reason)
     [:div.sub {:replicant/key "why"} (t "Why: {0}" reason)])))

(defn structure-section [{:keys [blocks description]}]
  (if (seq blocks)
    (list [:div.lab {:replicant/key "lab"} (t "Structure")]
          [:div {:replicant/key "prof"} (st/profile blocks)]
          [:div {:replicant/key "list"} (st/block-list blocks)])
    (when-not (str/blank? description)
      (list [:div.lab {:replicant/key "lab"} (t "Structure")]
            [:div {:replicant/key "desc"} description]))))

(defn- apply-change
  "PATCH body for one readiness suggestion."
  [{:keys [to slot]}]
  (cond (= "REST" to) {:status "skipped"}
        :else (cond-> {}
                to   (assoc :session_id to :title (plan/session-names to))
                slot (assoc :slot slot))))

(defn planned-today [{:keys [workouts signals changes level]}]
  (let [by-id (into {} (map (juxt :workout_id identity)) changes)]
    [:div.divider
     [:div.lab (t "Planned today")]
     (if (seq workouts)
       (for [w workouts
             :let [c (by-id (:id w))]]
         [:div {:replicant/key (:id w) :style {:display "flex" :flex-direction "column" :gap "4px"}}
          (ui/planned-card w)
          (when c
            [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap"}}
             [:span.sub (t "Readiness suggests: {0}"
                           (cond (= "REST" (:to c)) (t "rest instead")
                                 :else (str/join ", "
                                                 (remove nil? [(some->> (:to c) plan/session-names (t "change to {0}"))
                                                               (when (:slot c) (t "move to the evening"))]))))]
             [:button.pill {:on {:click [[:api "PATCH" (str "/api/plan/workouts/" (:id w)) (apply-change c)]]}}
              (t "Apply")]])])
       [:div.sub (t "Nothing planned today.")])
     (when (seq signals)
       [:div.sub {:style {:color (if (= "rest" (name level)) "var(--red)" "var(--text-2)")}}
        (str/join " · " signals)])]))

(defn- reason-text [reason]
  (case reason
    "not-planned"   (t "Only planned sessions can be sent.")
    "past"          (t "This session's day has passed.")
    "unsupported"   (t "This device can't receive this kind of session.")
    "too-far"       (t "Coros takes workouts up to {0} days ahead." 90)
    "not-connected" (t "Not connected. Connect it in Settings.")
    nil))

(defn- device-row
  "One device in the send panel: what was sent, or why nothing can be, and the button."
  [id service label {:keys [reason sent_at changed]} {:keys [busy error old-left]}]
  [:div.send-row {:replicant/key service}
   [:div
    [:b label]
    [:div.sub
     (cond reason (reason-text reason)
           sent_at (str (t "Sent {0}" (ui/date-long (subs sent_at 0 10)))
                        (when changed (str " · " (t "changed since sent"))))
           :else (t "Not sent yet."))]
    (when (and (= "coros" service) old-left)
      [:div.sub.send-warn
       (t "The earlier copy stays on {0} in the COROS app. Remove it there." (ui/date-long old-left))])
    (when (and error (not busy)) [:div.error error])]
   [:button.pill {:disabled (boolean (or reason busy))
                  :on {:click [[:send-workout id service]]}}
    (cond (= busy service) (t "Sending…")
          sent_at (t "Send again")
          :else (t "Send"))]])

(defn send-panel
  "The \"Send to device\" button and, once opened, a row per device."
  [{:keys [id send]} {:keys [open] :as st}]
  (when (some #(not (#{"past" "not-planned"} (:reason %))) (vals send))
    [:div {:replicant/key "send"}
     [:button.pill {:on {:click [[:set-in-ui :send id :open (not open)]]}}
      (t "Send to device")]
     (when open
       [:div.send-panel
        (device-row id "garmin" (t "Garmin (watch)") (:garmin send) st)
        (device-row id "coros" (t "Coros (bike computer)") (:coros send) st)
        [:div.sub (t "It reaches the device at its next sync with Garmin Connect or the COROS app.")]])]))

(defn view [{:keys [data errors ui]} url]
  (let [w (get data url)]
    [:div {:style {:display "contents"}}
     [:div [:button.link-back {:on {:click [[:back]]}} (t "← Back")]]
     (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
     (when w
       (list
        [:div.workout-head {:replicant/key "head" :class (str "st-" (ui/session-status w))}
         [:div (plan/session-chip (:session_id w))]
         [:h1 (:title w)]
         [:div.sub (str/join " · " (remove str/blank? [(ui/date-long (:date w)) (:slot w) (amount w)]))]
         (let [p (status-pill w) sp (send-panel w (get-in ui [:send (:id w)]))]
           (when (or p sp) [:div.pills {:style {:margin-top "8px"}} p sp]))]
        [:div.row {:replicant/key "body"}
         (into (ui/card {:flex "1 1 320px"}) (goal-section w))
         (into (ui/card {:flex "2 1 420px"}) (or (structure-section w)
                                                 [[:div.empty (t "No structure for this session.")]]))]))]))
