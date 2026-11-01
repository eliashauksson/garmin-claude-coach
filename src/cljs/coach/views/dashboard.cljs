(ns coach.views.dashboard
  "The start page: everything important at a glance - this morning's health,
  today's session (with the readiness check), this week's plan against what's
  done, training load, the coach's signals, what waits for review and the last
  activity. Each part links to its detail page."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.day :as day]
            [coach.views.load :as load]
            [coach.views.plan :as plan]
            [coach.views.status :as status]))

(defn urls
  "The glance cards' data, then the training load's (load/urls)."
  [route]
  (let [today (ui/iso-today)]
    [(day/url today)
     ;; a rolling week: what's coming, whatever the weekday
     (str "/api/plan?from=" today "&to=" (day/add-days today 6))
     "/api/plan/signals"
     "/api/plan/open"
     "/api/activities?limit=1"
     (first (load/urls route))
     (second (load/urls route))]))

(defn- greeting []
  (let [h (.getHours (js/Date.))]
    (cond (< h 12) (t "Good morning") (< h 18) (t "Good afternoon") :else (t "Good evening"))))

(defn- link-card
  "A card that opens `href`."
  [href opts & children]
  [:a.card-link {:href href :style {:flex (or (:flex opts) "1 1 200px")}}
   (apply ui/card (dissoc opts :flex) children)])

(defn- health-tile [href label value unit sub tint]
  (link-card href {:tint tint :flex "1 1 150px"}
             [:div.lab label]
             [:div.big.sm (or value "—") (when (and value unit) [:small unit])]
             (when sub [:div.sub sub])))

(defn- health-row [{:keys [wellness day]} today]
  (let [w    wellness
        href (str "#/day/" today)]
    [:div.row {:replicant/key "health"}
     (health-tile href (t "Training readiness") (:readiness w) nil
                  (some-> (:readiness_level w) str/lower-case str/capitalize t) "green")
     (health-tile href (t "Sleep") (:sleep_score w) nil (some-> (:sleep_s w) ui/hm) "purple")
     (health-tile href "HRV" (some-> (:hrv_avg w) js/Math.round) "ms"
                  (some-> (:hrv_status w) str/lower-case t) "blue")
     (health-tile href (t "Resting heart rate") (:rhr w) "bpm"
                  (some->> (:rhr_7d day) (t "7-day resting {0}")) "red")
     (health-tile href (t "Body battery") (:bb_current day) nil
                  (some->> (:body_battery_high w) (t "high {0}")) "amber")]))

(defn- today-card [{:keys [activities readiness_check]}]
  (ui/card {:flex "1 1 360px"}
           (ui/label-row {:icon :bolt :accent "--green" :tint "--tint-green" :soft? true :label (t "Today")})
           (if (seq activities)
             (for [a activities]
               [:a {:replicant/key (:id a) :href (str "#/activities/" (:id a))
                    :style {:font-weight 700 :text-decoration "none"}}
                (str (:name a) " · " (str/join " · " (remove nil? [(some-> (ui/km (:distance_m a)) (str " km"))
                                                                    (ui/hm (or (:moving_s a) (:duration_s a)))])))])
             [:div.sub (t "No activity recorded yet today.")])
           (day/planned-today (or readiness_check {}))))

(defn- week-card
  "The next 7 days (today included): the planned sessions and their state."
  [workouts]
  (let [ws (sort-by (juxt :date :slot) (remove #(= "cancelled" (:status %)) workouts))]
    (link-card "#/plan" {:flex "1 1 360px"}
               (ui/label-row {:icon :mountain :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Next 7 days")})
               (if (seq ws)
                 (list
                  [:div.sub {:replicant/key "sum"}
                   (t "{0} sessions · {1} min planned" (count ws) (reduce + 0 (keep :duration_min ws)))]
                  [:div.dash-week {:replicant/key "list"}
                   (for [w ws]
                     [:div.dash-week-row {:replicant/key (:id w) :class (:status w)}
                      [:span.dash-week-day (ui/weekdays (.getDay (ui/parse-date (:date w))))]
                      (plan/session-chip (:session_id w))
                      [:span.sub (str (when (pos? (:duration_min w)) (str (:duration_min w) " min"))
                                      (case (:status w) "done" " · ✓" "missed" (str " · " (t "missed"))
                                            "skipped" (str " · " (t "skipped")) ""))]])])
                 [:div.sub (t "Nothing planned for the next 7 days.")]))))

(def severity-color {"high" "var(--red)" "warn" "var(--amber)" "info" "var(--blue)"})

(defn- coach-card [signals open-proposals]
  (let [waiting (count (filter #(= "open" (:status %)) open-proposals))
        running (count (filter #(= "running" (:status %)) open-proposals))]
    (link-card "#/plan" {:flex "1 1 360px"}
               (ui/label-row {:icon :heart :accent "--red" :tint "--tint-red" :soft? true :label (t "Coach signals")})
               (if (seq signals)
                 [:div.plan-list
                  (for [s (take 3 signals)]
                    [:div.signal {:replicant/key (:key s)}
                     [:span.dot {:style {:background (severity-color (name (:severity s)))}}]
                     [:span (:message s)]])]
                 [:div.sub (t "No signals - training is going as planned.")])
               (when (> (count signals) 3) [:div.sub (t "{0} more on the Plan page" (- (count signals) 3))])
               (when (pos? waiting)
                 [:div [:b (if (= 1 waiting) (t "1 proposal waits for your review")
                               (t "{0} proposals wait for your review" waiting))]])
               (when (pos? running)
                 [:div.sub (t "The coach is working on {0} proposal(s)." running)]))))

(defn- last-activity-card [a]
  (if a
    (link-card (str "#/activities/" (:id a)) {:flex "1 1 300px"}
               (ui/label-row {:icon :footprints :accent "--text" :tint "--surface-2" :soft? true :label (t "Last activity")})
               [:div {:style {:font-size "20px" :font-weight 800}} (:name a)]
               [:div.sub (ui/date-long (:date a))]
               (ui/pills (some-> (ui/km (:distance_m a)) (str " km") ui/pill)
                         (some-> (ui/hm (or (:moving_s a) (:duration_s a))) ui/pill)
                         (some-> (:avg_hr a) js/Math.round (str " bpm") ui/pill)
                         (some-> (:tss a) js/Math.round (str " TSS") ui/pill)))
    (ui/card {:flex "1 1 300px"} [:div.empty (t "No activities yet.")])))

(defn- season-line [{:keys [week weeks_total phase days_to_race]}]
  (str/join " · " (remove nil? [(when week (t "Week {0} of {1} · {2}" week weeks_total (t phase)))
                                (when days_to_race (t "{0} days to race day" days_to_race))])))

(defn view [{:keys [data errors sync route] :as s}]
  (let [[day-url week-url signals-url open-url last-url load-url volume-url :as us] (urls route)
        d     (get data day-url)
        today (ui/iso-today)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title
       [:div.sub (str/join " · " (remove str/blank? [(ui/date-long today) (some-> (:season d) season-line)]))]
       [:h1 (greeting)]]
      (status/auth-pill (:auth sync))
      (status/sync-button sync)]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (t "Could not load: {0}" e)])) (take 5 us))
     (when d
       (list
        (health-row d today)
        [:div.row {:replicant/key "r2"} (today-card d) (week-card (:workouts (get data week-url)))]
        [:div.row {:replicant/key "r3"}
         (coach-card (get data signals-url) (get data open-url))
         (last-activity-card (first (get data last-url)))]))
     (load/section s [load-url volume-url])]))
