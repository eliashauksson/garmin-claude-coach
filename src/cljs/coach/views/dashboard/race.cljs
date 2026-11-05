(ns coach.views.dashboard.race
  "The goal race: the predicted time against the goal, and the race predictions."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [dash fit-labels note]]))

(def leg-names {"5k" (tk "5K") "10k" (tk "10K") "half" (tk "half marathon") "marathon" (tk "marathon")})
(def leg-short {"5k" "5K" "10k" "10K" "half" (tk "Half") "marathon" (tk "Marathon")})
(def leg-color {"5k" "--purple" "10k" "--blue" "half" "--fam-swim" "marathon" "--pink"})
(def leg-key {"5k" :t5k "10k" :t10k "half" :thalf "marathon" :tmar})

(defn- minutes-text
  "20 min · 1 h 05 min"
  [s]
  (let [m (js/Math.round (/ (js/Math.abs s) 60))]
    (if (< m 60) (t "{0} min" m) (t "{0} h {1} min" (quot m 60) (.padStart (str (mod m 60)) 2 "0")))))

(defn- join-and
  "\"10K, half marathon and marathon\", capitalised (it starts a sentence)."
  [xs]
  (let [s (if (< (count xs) 2)
            (first xs)
            (t "{0} and {1}" (str/join ", " (butlast xs)) (last xs)))]
    (when s (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn- race-sentence [{:keys [legs total_s margin_s goal_s]}]
  (let [names (map #(t (leg-names %)) legs)
        total (ui/hms total_s)
        goal  (ui/hms goal_s)
        diff  (some-> margin_s minutes-text)
        multi (> (count legs) 1)]
    (when total_s
      [:p.race-text {:replicant/key "sentence"}
       (cond
         (and multi diff (not (neg? margin_s)))
         (ui/rich (t "{0} on one day. Garmin's predictions, each as a fresh race, add up to {1}: {2} inside the {3} goal.")
                  (join-and names) total diff goal)
         (and multi diff)
         (ui/rich (t "{0} on one day. Garmin's predictions, each as a fresh race, add up to {1}: {2} over the {3} goal.")
                  (join-and names) total diff goal)
         multi
         (ui/rich (t "{0} on one day. Garmin's predictions, each as a fresh race, add up to {1}.")
                  (join-and names) total)
         (and diff (not (neg? margin_s)))
         (ui/rich (t "Garmin predicts {1} for the {0}: {2} inside the {3} goal.") (first names) total diff goal)
         diff
         (ui/rich (t "Garmin predicts {1} for the {0}: {2} over the {3} goal.") (first names) total diff goal)
         :else
         (ui/rich (t "Garmin predicts {1} for the {0}.") (first names) total))])))

(defn- goal-bar [{:keys [legs now total_s goal_s]}]
  (when total_s
    (let [scale (* 1.04 (max total_s (or goal_s 0)))
          pct   #(str (* 100 (/ % scale)) "%")
          starts (reductions + 0 (map #(get now (leg-key %)) legs))
          gpos  (when goal_s (/ goal_s scale))]
      [:div.goal-bar {:role "img" :aria-label (t "Predicted total {0}" (ui/hms total_s))}
       (for [[i [leg start]] (map-indexed vector (map vector legs starts))
             :let [v (get now (leg-key leg))]]
         [:div.goal-leg.fade-in {:replicant/key leg
                                 :style {:left (pct start) :width (str "calc(" (pct v) " - 3px)")
                                         :background (str "var(" (leg-color leg) ")")
                                         :animation-delay (str (* i 120) "ms")}}
          [:span.fit (str (t (leg-short leg)) " " (ui/hms v))]])
       (when gpos
         (list
          [:div.goal-line {:replicant/key "line" :style {:left (str (* 100 gpos) "%")}}]
          [:div.goal-lab {:replicant/key "lab"
                          :class (cond (> gpos 0.85) "end" (< gpos 0.15) "start")
                          :style {:left (str (* 100 gpos) "%")}}
           (t "Goal {0}" (ui/hms goal_s))]))])))

(defn race-hero [{:keys [name date days_to_race] :as race}]
  [:section.card.race-hero {:replicant/key "hero"}
   (if-not name
     (list [:div.eyebrow {:replicant/key "e"} (t "Goal race")]
           [:div.empty {:replicant/key "x"} (t "No goal race in the athlete information yet. Add it on the Plan page.")])
     (list
      [:div.eyebrow {:replicant/key "e"}
       (str/join " · " (remove nil? [(t "Goal race") name (some-> date ui/date-short)]))]
      (when days_to_race
        [:div.countdown {:replicant/key "cd"}
         [:span.cd-num days_to_race]
         [:span.cd-unit (if (= 1 days_to_race) (t "day to go") (t "days to go"))]])
      (race-sentence race)
      (fit-labels "goal" (goal-bar race))))])

(defn prediction-cards [{:keys [now trend]}]
  [:div.predictions
   (for [[leg k label] [["10k" :t10k (tk "10K")] ["half" :thalf (tk "Half marathon")] ["marathon" :tmar (tk "Marathon")]]
         :let [values (mapv k trend)]]
     [:section.card.prediction {:replicant/key leg}
      [:div.lab (t label) (ui/info "race-predictions")]
      [:div.pred-time (dash (ui/hms (get now k)))]
      (if (some some? values)
        (c/spark (str "pred-" leg) "h54" {:color "--pink" :values values :inverse? true})
        [:div.h54])])
   (note (t "Lines show the prediction at the end of each month over the last 6 months; up means faster."))])
