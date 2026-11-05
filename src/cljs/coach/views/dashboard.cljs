(ns coach.views.dashboard
  "The start page (coach-dashboard-spec.md): how ready am I today, and how is
  training going toward the goal race? From today to the long term: this
  morning's readiness and vitals, the goal race, recovery over 30 days,
  training (with the CTL/ATL/TSB training load), and fitness over 6 months.
  Everything is worked out on the server (/api/overview); this only formats
  and draws. Each section is its own namespace under coach.views.dashboard."
  (:require [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [lower tone]]
            [coach.views.dashboard.fitness :as fitness]
            [coach.views.dashboard.morning :as morning]
            [coach.views.dashboard.race :as race]
            [coach.views.dashboard.recovery :as recovery]
            [coach.views.dashboard.training :as training]
            [coach.views.feedback :as feedback]
            [coach.views.load :as load]
            [coach.views.status :as status]))

(def url "/api/overview")

(defn urls
  "The overview, the training load's (load/urls), the activities
  waiting for \"How did it go?\", then the cycling power."
  [route]
  (conj (into [url] (load/urls route)) feedback/due-url load/power-url))

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
              [:span (t "With your own API key.") " " [:a {:href "#/settings"} (t "How to connect")]])
        (step "history" history (t "Load your full history")
              (cond
                (and running (= "history" job)) (t "Loading… you can use the dashboard meanwhile.")
                synced [:span (t "Everything before the first sync, for the long-term charts and the coach.") " "
                        [:button.pill {:disabled running :on {:click [[:load-history]]}} (t "Load full history")]]
                :else (t "After the first sync.")))
        (step "athlete" athlete (t "Tell the coach about yourself")
              [:span (t "Your goal race, availability and history, in a chat with Claude.") " "
               [:a {:href "#/plan"} (t "Open the Plan page")]])
        (step "season" season (t "Create the season plan")
              [:span (t "From then on the coach keeps the next two weeks planned.") " "
               [:a {:href "#/plan"} (t "Open the Plan page")]])]])))

;; ---------------------------------------------------------------------------

(defn- skeleton []
  [:div.skeleton {:aria-hidden "true"}
   [:div.row [:div.skel {:style {:flex "1 1 360px" :height "300px"}}]
    [:div.skel {:style {:flex "1 1 360px" :height "300px"}}]]
   [:div.skel {:style {:height "260px"}}]
   [:div.skel {:style {:height "320px"}}]])

(defn view [{:keys [data errors sync route] ui-state :ui :as s}]
  (let [[ov-url load-url volume-url due-url power-url] (urls route)
        d     (get data ov-url)
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
     (if-not d
       (when-not (get errors ov-url) (skeleton))
       (list
        (takeaway d)
        (feedback/due-card ui-state (get data due-url))
        [:div.morning {:replicant/key "morning"}
         (morning/readiness-card (:readiness d))
         (morning/vitals-grid d)]
        [:div.section-head {:replicant/key "race-head"} [:h2.section-title (t "Goal race")]]
        [:div.race {:replicant/key "race"}
         (race/race-hero (:race d))
         (race/prediction-cards (:race d))]
        (recovery/recovery d)
        (training/training d)))
     (load/section s [load-url volume-url])
     (load/power-section s power-url)
     (when d (fitness/fitness d))]))
