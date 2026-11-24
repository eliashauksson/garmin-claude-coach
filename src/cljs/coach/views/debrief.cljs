(ns coach.views.debrief
  "The coach's short note after a workout (coach.debrief on the server): a card on the activity page
  (the note, a spinner while it is being written, a retry, or a button to ask for one) and the newest
  note on Today."
  (:require [coach.i18n :refer [t]]
            [coach.ui :as ui]))

(def latest-url "/api/debrief/latest")

(defn- label []
  (ui/label-row {:icon :chat :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Coach's note")}))

(defn- ask-url [id] (str "/api/activities/" id "/debrief"))

(defn activity-card
  "The note on activity `a`'s page, whatever its state: {:status :text :takeaway :error} or none."
  [{:keys [id debrief]}]
  (let [{:keys [status text error]} debrief]
    [:section.card.debrief-card {:replicant/key "debrief"}
     (label)
     (case status
       "done"    [:p.debrief-text text]
       "pending" [:div.typing [:span.spinner-inline] (t "The coach is writing a note…")]
       "failed"  [:div
                  [:div.error (t "The note could not be written: {0}" error)]
                  [:button.pill {:on {:click [[:api "POST" (ask-url id) nil]]}} (t "Try again")]]
       [:div
        [:div.sub (t "A few sentences on how this workout went against the plan and what it means for the week.")]
        [:button.pill {:on {:click [[:api "POST" (ask-url id) nil]]}} (t "Write a coach's note")]])]))

(defn today-card
  "The newest note from the last two days ({:activity_id :date :name :text :takeaway}), or nothing."
  [{:keys [activity_id date text takeaway] title :name}]
  (when activity_id
    [:section.card.debrief-card {:replicant/key "debrief"}
     (label)
     [:div.debrief-take takeaway]
     [:p.debrief-text text]
     [:div.sub [:a {:href (str "#/activities/" activity_id)} (str title " · " (ui/date-long date))]]]))
