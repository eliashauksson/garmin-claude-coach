(ns coach.views.feedback
  "\"How did it go?\": the athlete's own words on an activity, plus optional
  effort (RPE 1-10) and pain. The coach reads them when it plans and adjusts
  (coach.planner). Asked on the Dashboard for recent activities without an
  answer; shown and editable on each activity's page."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]))

(def due-url "/api/feedback/due")

(defn- url [id] (str "/api/activities/" id "/feedback"))

(defn- draft
  "The form's values: what's being typed (ui state) over what's saved."
  [ui-state {:keys [id rpe pain pain_location note]}]
  (merge {:rpe rpe :pain (pos? (or pain 0)) :pain_location pain_location :note note}
         (get-in ui-state [:fb (str id)])))

(defn form
  "The feedback form for activity `a`. `saved?` shows that an answer exists."
  [ui-state a & [{:keys [skip? saved?]}]]
  (let [id  (str (:id a))
        {:keys [rpe pain pain_location note] :as d} (draft ui-state a)
        put (fn [k v] [:set-in-ui :fb id k v])
        clear [:set-in-ui :fb id nil]
        empty? (and (str/blank? note) (nil? rpe) (not pain))]
    [:div.fb-form
     [:textarea.fb-note {:rows 3 :value (or note "")
                         :placeholder (t "How did it feel? Legs, breathing, energy, anything that hurt, sleep or stress the day before…")
                         :on {:input [[:set-in-ui :fb id :note :event/target-value]]}}]
     [:div.fb-row
      [:span.fb-lab (t "Effort")]
      [:div.fb-rpe
       (for [n (range 1 11)]
         [:button.fb-n {:replicant/key n :class (when (= n rpe) "on") :type "button"
                        :title (t "RPE {0} of 10" n)
                        :on {:click [(put :rpe (when-not (= n rpe) n))]}}
          n])]
      [:span.sub (case rpe nil (t "optional") (1 2 3) (t "easy") (4 5 6) (t "moderate") (7 8) (t "hard") (t "very hard"))]]
     [:div.fb-row
      [:button.pill {:type "button" :class (when pain "pain-on") :on {:click [(put :pain (not pain))]}}
       (if pain (t "✓ Pain or niggle") (t "Pain or niggle?"))]
      (when pain
        [:input.note.fb-where {:type "text" :value (or pain_location "") :placeholder (t "Where? e.g. left knee")
                               :on {:input [[:set-in-ui :fb id :pain_location :event/target-value]]}}])]
     [:div.fb-row
      [:button.btn-ink {:type "button" :disabled empty?
                        :on {:click [[:api "PUT" (url id) (select-keys d [:rpe :pain :pain_location :note])] clear]}}
       (t "Save")]
      (when skip?
        [:button.pill {:type "button" :on {:click [[:api "PUT" (url id) {:skipped true}] clear]}}
         (t "Skip")])
      (when saved? [:span.sub (t "Saved. The coach reads it when it plans.")])]]))

(defn- head [a]
  [:div.fb-head
   (ui/sport-chip (:sport a))
   [:a.fb-name {:href (str "#/activities/" (:id a))} (:name a)]
   [:span.sub (str/join " · " (remove nil? [(ui/date-long (:date a))
                                            (some-> (ui/km (:distance_m a)) (str " km"))
                                            (ui/hm (or (:moving_s a) (:duration_s a)))
                                            (some-> (:avg_hr a) js/Math.round (str " bpm"))]))]])

(defn due-card
  "The Dashboard's question for each recent activity without an answer."
  [ui-state acts]
  (when (seq acts)
    [:section.card.fb-card {:replicant/key "feedback"}
     (ui/label-row {:icon :footprints :accent "--purple" :tint "--tint-purple" :soft? true
                    :label (t "How did it go?")})
     [:div.note (t "A few words on each new activity help the coach adjust your next sessions.")]
     (for [a (take 3 acts)]
       [:div.fb-item {:replicant/key (str (:id a))}
        (head a)
        (form ui-state a {:skip? true})])
     (when (> (count acts) 3)
       [:div.sub (t "{0} more after these" (- (count acts) 3))])]))

(defn activity-card
  "The activity page's \"How it went\", to read or change the answer."
  [ui-state a]
  [:section.card.fb-card {:replicant/key "feedback"}
   (ui/label-row {:icon :footprints :accent "--purple" :tint "--tint-purple" :soft? true
                  :label (t "How it went")})
   (form ui-state a {:saved? (and (or (:note a) (:rpe a) (pos? (or (:pain a) 0)))
                                  (nil? (get-in ui-state [:fb (str (:id a))])))})])
