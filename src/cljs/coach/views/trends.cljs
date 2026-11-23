(ns coach.views.trends
  "The long term: training load (with the form forecast and weekly volume), the
  goal race against Garmin's predictions, recovery over 30 days, training over
  28 days and 26 weeks, cycling power, and fitness over 6 months. A sticky bar
  jumps between the sections. Everything is worked out on the server
  (/api/overview); each section is its own namespace under coach.views.dashboard."
  (:require [coach.i18n :refer [t tk]]
            [coach.views.dashboard.cycle-trends :as cycle-trends]
            [coach.views.dashboard.fitness :as fitness]
            [coach.views.dashboard.race :as race]
            [coach.views.dashboard.recovery :as recovery]
            [coach.views.dashboard.training :as training]
            [coach.views.load :as load]
            [coach.views.today :as today]))

(defn urls
  "The overview, the training load's (load/urls), the cycling power and the form forecast."
  [route]
  (let [[load-url volume-url] (load/urls route)]
    [today/url load-url volume-url load/power-url load/forecast-url cycle-trends/url (cycle-trends/days-url)]))

(def jumps [["tr-load" (tk "Load")] ["tr-race" (tk "Race")] ["tr-recovery" (tk "Recovery")]
            ["tr-fitness" (tk "Fitness")]])

(defn- jump-bar [cycle?]
  [:nav.jump-bar {:aria-label (t "Sections")}
   (for [[id label] (cond-> jumps cycle? (conj ["tr-cycle" (tk "Cycle")]))]
     [:button.pill {:replicant/key id :on {:click [[:scroll-to id]]}} (t label)])])

(defn view [{:keys [data errors route] :as s}]
  (let [[ov-url load-url volume-url power-url forecast-url cycle-url cycle-days-url] (urls route)
        d (get data ov-url)
        cyc (get data cycle-url)
        cyc-days (when (:enabled cyc) (:days (get data cycle-days-url)))]
    [:div {:style {:display "contents"}}
     [:div.page-head [:div.title [:h1 (t "Trends")]]]
     (jump-bar (:enabled cyc))
     (when-let [e (get errors ov-url)] [:div.error (t "Could not load: {0}" e)])
     [:div {:replicant/key "load" :style {:display "contents"}}
      (load/section s [load-url volume-url forecast-url])]
     (when d
       (list
        [:div.section-head {:replicant/key "race-head" :id "tr-race"} [:h2.section-title (t "Goal race")]]
        [:div.race {:replicant/key "race"}
         (race/race-hero (:race d))
         (race/prediction-cards (:race d))]
        (recovery/recovery d cyc-days)
        (training/training d)))
     [:div {:replicant/key "power" :style {:display "contents"}}
      (load/power-section s power-url)]
     (when d (fitness/fitness d))
     (when (:enabled cyc) (cycle-trends/section cyc))]))
