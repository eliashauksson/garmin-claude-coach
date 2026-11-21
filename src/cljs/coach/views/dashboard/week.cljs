(ns coach.views.dashboard.week
  "The start page's \"Today\", \"This week\" and \"Last activity\" cards: what to
  train today (with the readiness suggestions), how the week stands, planned
  against done, and the newest activity."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.plan :as plan]
            [coach.views.workout :as workout]))

(def readiness-url "/api/plan/readiness")

(defn plan-url
  "This week's plan, and the day after it (for tomorrow's preview on a Sunday)."
  []
  (let [m (ui/monday (ui/iso-today))]
    (str "/api/plan?from=" m "&to=" (ui/add-days m 7))))

(defn- keyed [k el] [:div {:replicant/key k :style {:display "contents"}} el])

(defn- active? [w] (not (#{"cancelled" "skipped"} (:status w))))

;; ---------------------------------------------------------------------------
;; Today

(defn today-card
  "Today's planned sessions with readiness suggestions; when there are none,
  tomorrow's first session."
  [check {:keys [workouts]} today]
  (when check
    (let [tomorrow (ui/add-days today 1)
          next-w   (first (filter #(and (= tomorrow (:date %)) (active? %)) workouts))]
      (keyed "today"
             (ui/card {:class "today-card"}
               (ui/label-row {:icon :bolt :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Today")})
               (workout/planned-today check)
               (when (and (empty? (:workouts check)) next-w)
                 [:div.divider
                  [:div.lab (t "Tomorrow")]
                  (ui/planned-card next-w)]))))))

;; ---------------------------------------------------------------------------
;; This week

(defn- hours [minutes] (/ (Math/round (/ minutes 6.0)) 10.0))

(defn- bar
  "A planned-against-done progress bar; the fill turns amber past the target."
  [{:keys [label done target unit]}]
  (let [pct (when (and target (pos? target)) (min 100 (Math/round (* 100.0 (/ done target)))))]
    [:div.wk-bar {:replicant/key label}
     [:div.wk-bar-top [:span label] [:span.sub (str done " / " (or target "—") " " unit)]]
     [:div.wk-track
      [:div.wk-fill {:class (when (and target (> done target)) "over")
                     :style {:width (str (or pct 0) "%")}}]]]))

(defn- day-cell [today date ws]
  (let [d     (ui/parse-date date)
        ws    (filter active? ws)
        state (cond (and (seq ws) (every? #(= "done" (:status %)) ws)) "done"
                    (some #(= "missed" (:status %)) ws) "missed"
                    :else nil)]
    [:a.wk-day {:replicant/key date :href (str "#/day/" date)
                :class (str/join " " (remove nil? [(when (= date today) "today") state]))}
     [:div.wk-dow (ui/weekdays (.getDay d))]
     [:div.wk-date (.getDate d)]
     (if (seq ws)
       (for [w ws]
         (let [fam (ui/planned-family (:session_id w))
               nm  (plan/session-names (:session_id w) (:session_id w))]
           [:div {:replicant/key (:id w)}
            [:span.chip {:class (name (:id fam)) :title nm} (ui/sport-icon fam 13)
             [:span.wk-sess nm]]]))
       [:div.sub "—"])
     (case state "done" [:div.wk-mark {:aria-label (t "done")} "✓"]
                 "missed" [:div.wk-mark {:aria-label (t "missed")} "✗"]
                 nil)]))

(defn week-card
  "Seven day cells and two bars: hours and TSS done against what the week plans.
  `load-rows` are the daily PMC rows (:date :tss), `overview` has :weekly."
  [{:keys [workouts season]} overview load-rows today]
  (let [monday  (ui/monday today)
        dates   (mapv #(ui/add-days monday %) (range 7))
        in-week (set dates)
        wk      (filter #(and (in-week (:date %)) (active? %)) workouts)
        by-day  (group-by :date wk)
        planned-min (reduce + 0 (keep :duration_min wk))
        done-h  (->> (:weekly overview) (filter #(= monday (:week %))) (keep :hours) (reduce + 0.0))
        done-tss (->> load-rows (filter #(in-week (:date %))) (keep :tss) (reduce + 0.0))
        {:keys [week weeks_total phase tss_target]} season]
    (when (or (seq wk) tss_target)
      (keyed "week"
             (ui/card {:class "week-card"}
               (ui/label-row {:icon :pulse :accent "--blue" :tint "--tint-blue" :soft? true
                              :label (t "This week") :info "week-progress"
                              :right [:span.sub (cond week (t "Week {0} of {1} · {2}" week weeks_total (t phase))
                                                      phase (t phase))]})
               [:div.wk-days (for [dt dates] (day-cell today dt (by-day dt)))]
               [:div.wk-bars
                (bar {:label (t "Time") :done (/ (Math/round (* 10.0 done-h)) 10.0)
                      :target (when (pos? planned-min) (hours planned-min)) :unit "h"})
                (bar {:label (t "Training load (TSS)") :done (Math/round done-tss)
                      :target tss_target :unit ""})])))))

;; ---------------------------------------------------------------------------
;; Last activity

(defn last-activity-card
  "The newest activity of the overview's :recent list, linking to its page."
  [{:keys [recent]}]
  (when-let [{:keys [id date sport name km duration_s avg_hr load]} (first recent)]
    (let [href (str "#/activities/" id)]
      (keyed "last-activity"
             [:section.card.clickable.last-activity {:on {:click [[:navigate href]]}}
              (ui/label-row {:icon :mountain :accent "--text" :tint "--surface-2" :soft? true
                             :label (t "Last activity") :right [:span.sub (ui/date-short date)]})
              [:div.last-activity-head (ui/sport-chip sport) [:a.last-activity-name {:href href} name]]
              (ui/pills (when km (ui/pill (str (ui/fixed km 1) " km")))
                        (when (ui/num? duration_s) (ui/pill (ui/h-mm duration_s)))
                        (when avg_hr (ui/pill (str avg_hr " bpm")))
                        (when load (ui/pill (t "Load {0}" load))))]))))
