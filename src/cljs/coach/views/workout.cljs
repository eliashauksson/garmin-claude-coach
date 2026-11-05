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

(defn- status-pill [{:keys [status activity_id]}]
  (case status
    "done"      (if activity_id
                  [:a.pill {:href (str "#/activities/" activity_id)} (t "✓ done") " · " (t "Open activity")]
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

(defn view [{:keys [data errors]} url]
  (let [w (get data url)]
    [:div {:style {:display "contents"}}
     [:div [:button.link-back {:on {:click [[:back]]}} (t "← Back")]]
     (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
     (when w
       (list
        [:div {:replicant/key "head"}
         [:div (plan/session-chip (:session_id w))]
         [:h1 (:title w)]
         [:div.sub (str/join " · " (remove str/blank? [(ui/date-long (:date w)) (:slot w) (amount w)]))]
         (when-let [p (status-pill w)] [:div.pills {:style {:margin-top "8px"}} p])]
        [:div.row {:replicant/key "body"}
         (into (ui/card {:flex "1 1 320px"}) (goal-section w))
         (into (ui/card {:flex "2 1 420px"}) (or (structure-section w)
                                                 [[:div.empty (t "No structure for this session.")]]))]))]))
