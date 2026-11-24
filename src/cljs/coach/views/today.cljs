(ns coach.views.today
  "The start page: what do I do today, and am I ready? The takeaway, today's
  session, this morning's readiness and vitals, \"How did it go?\", the week
  and the last activity. The long-term sections are on the Trends page
  (coach.views.trends). Everything is worked out on the server
  (/api/overview); this only formats and draws. The cards are namespaces under
  coach.views.dashboard."
  (:require [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [lower tone]]
            [coach.views.dashboard.cycle :as cycle]
            [coach.views.dashboard.detail :as detail]
            [coach.views.dashboard.morning :as morning]
            [coach.views.dashboard.week :as week]
            [coach.views.debrief :as debrief]
            [coach.views.feedback :as feedback]
            [coach.views.load :as load]
            [coach.views.status :as status]))

(def url "/api/overview")

(defn urls
  "The overview, the training load's (the week card needs its daily TSS), the
  activities waiting for \"How did it go?\", today's readiness check, this
  week's plan, the coach's newest note, then the open card's history."
  [route]
  (let [vital (get-in route [:params :vital])
        trend (detail/url vital (detail/range-of route))]
    (cond-> [url (first (load/urls route)) feedback/due-url week/readiness-url (week/plan-url)
             debrief/latest-url]
      trend (conj trend))))

;; Garmin's enums as shown (ui/enum); listed so each has a translation.
(def enum-texts
  [;; readiness levels, HRV status, sleep quality
   (tk "Poor") (tk "Low") (tk "Moderate") (tk "High") (tk "Prime") (tk "Balanced") (tk "Unbalanced")
   (tk "Fair") (tk "Good") (tk "Excellent")
   ;; readiness feedback
   (tk "Listen to your body") (tk "Ready for the day") (tk "Ready to train") (tk "Prime to train")
   (tk "Well recovered") (tk "Recovering") (tk "Rest") (tk "Take it easy") (tk "Recovered")
   (tk "Good sleep") (tk "Poor sleep") (tk "High stress") (tk "Low hrv") (tk "Elevated stress")
   ;; training status
   (tk "Productive") (tk "Maintaining") (tk "Recovery") (tk "Unproductive") (tk "Detraining")
   (tk "Peaking") (tk "Overreaching") (tk "Strained") (tk "No status") (tk "Paused")
   ;; load ratio (ACWR)
   (tk "Optimal") (tk "Very high") (tk "None")
   ;; load focus
   (tk "Aerobic low shortage") (tk "Aerobic high shortage") (tk "Anaerobic shortage")
   (tk "Aerobic low focus") (tk "Aerobic high focus") (tk "Anaerobic focus") (tk "No data")
   ;; endurance classes
   (tk "Recreational") (tk "Intermediate") (tk "Trained") (tk "Expert")
   (tk "Superior") (tk "Elite")])

;; ---------------------------------------------------------------------------
;; Header

(defn- eyebrow-date [iso]
  (let [d (ui/parse-date iso)]
    (str (ui/long-weekdays (.getDay d)) " " (t "{0} {1}" (.getDate d) (ui/long-months (.getMonth d)))
         " " (.getFullYear d) " · Garmin")))

(defn- takeaway [{:keys [readiness vitals]}]
  (let [{:keys [score level estimate]} readiness
        lvl (lower (ui/enum level))
        hrv (or (lower (ui/enum (:hrv_status vitals))) "—")]
    [:p.takeaway {:replicant/key "take"}
     (when estimate (str (t "Garmin has no readiness this morning; this is the app's estimate.") " "))
     (cond
       (nil? score)
       (t "Your watch hasn't worked out today's readiness yet. It appears after you wake up and sync.")
       (= :bad (tone level))
       (ui/rich (t "Readiness is {0} at {1} and HRV is {2}. Your body is asking for a rest day, or something very easy.") lvl score hrv)
       (= :warn (tone level))
       (ui/rich (t "Readiness is {0} at {1} and HRV is {2}. Keep today aerobic and easy, and save the hard session for a better morning.") lvl score hrv)
       :else
       (ui/rich (t "Readiness is {0} at {1} and HRV is {2}. You are ready for quality work today.") lvl score hrv))]))

;; ---------------------------------------------------------------------------
;; Getting started (a new install)

(defn- setup-card
  "The steps a new install still needs; gone once all are done."
  [{:keys [setup running job] :as sync}]
  (let [{:keys [garmin synced claude history athlete season]} setup
        step (fn [k done? title body]
               [:li.setup-step {:replicant/key k :class (when done? "done")}
                [:span.setup-check {:aria-hidden "true"} (if done? "✓" "")]
                [:div [:div.setup-title title (when done? [:span.visually-hidden (str " " (t "done"))])]
                 (when-not done? [:div.sub body])]])]
    (when (and setup (not (and garmin synced claude history athlete season)))
      [:section.card.setup {:replicant/key "setup"}
       (ui/label-row {:icon :bolt :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Getting started")})
       [:ol.setup-steps
        (step "garmin" garmin (t "Connect Garmin")
              [:span (t "Log in with your Garmin account once.") " " [:a {:href "#/settings"} (t "Connect")]])
        (step "sync" synced (t "First sync")
              (if garmin
                [:span (t "Loads the last 4 weeks (a few minutes).") " " (status/sync-button sync)]
                (t "After Garmin is connected.")))
        (step "claude" claude (t "Connect Claude")
              (if (false? (:admin sync))
                (t "Ask {0} to add the Claude key." (or (:admin_name sync) (t "the main athlete")))
                [:span (t "With your own API key.") " " [:a {:href "#/settings"} (t "How to connect")]]))
        (step "history" history (t "Load your full history")
              (cond
                (and running (= "history" job)) (t "Loading… you can use the dashboard meanwhile.")
                synced [:span (t "Everything before the first sync, for the long-term charts and the coach.") " "
                        [:button.pill {:disabled running :on {:click [[:load-history]]}} (t "Load full history")]]
                :else (t "After the first sync.")))
        (step "athlete" athlete (t "Tell the coach about yourself")
              [:span (t "Your goal race, availability and history, in a chat with Claude.") " "
               [:a {:href "#/athlete"} (t "Open your athlete profile")]])
        (step "season" season (t "Create the season plan")
              [:span (t "From then on the coach keeps the next two weeks planned.") " "
               [:a {:href "#/plan?view=season"} (t "Open the season plan")]])]])))

;; ---------------------------------------------------------------------------

(defn- skeleton []
  [:div.skeleton {:aria-hidden "true"}
   [:div.skel {:style {:height "140px"}}]
   [:div.row [:div.skel {:style {:flex "1 1 360px" :height "300px"}}]
    [:div.skel {:style {:flex "1 1 360px" :height "300px"}}]]])

(defn view [{:keys [data errors sync route] ui-state :ui :as s}]
  (let [[ov-url load-url due-url check-url plan-url note-url] (urls route)
        d     (get data ov-url)
        plan  (get data plan-url)
        today (or (:date d) (ui/iso-today))]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title
       [:div.eyebrow (eyebrow-date today)]
       [:h1 "Coach"]]
      (status/auth-pill (:auth sync))
      (status/sync-button sync)]
     (setup-card sync)
     (when-let [e (get errors ov-url)] [:div.error (t "Could not load: {0}" e)])
     (when-not (or d (get errors ov-url)) (skeleton))
     (when d
       (list
        (takeaway d)
        (week/today-card (get data check-url) plan today)
        (when-let [c (:cycle d)] (cycle/card c today))
        [:div.morning {:replicant/key "morning"}
         (morning/readiness-card (:readiness d))
         (morning/vitals-grid d)]
        (debrief/today-card (get data note-url))
        (feedback/due-card ui-state (get data due-url))
        (when plan (week/week-card plan d (get data load-url) today))
        (week/last-activity-card d)))
     (when-let [v (get-in route [:params :vital])] (detail/modal s v))]))
