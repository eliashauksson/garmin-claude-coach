(ns coach.views.today
  (:require [clojure.string :as str]
            [coach.ui :as ui]))

(defn- auth-notice
  "Small chip normally; a banner with the renew command from 30 days out."
  [{:keys [level days_left expires_on renew_command sync_error]}]
  (when (#{"warn" "urgent" "expired"} level)
    [:div.banner {:class (if (= level "warn") "warn" "critical")}
     [:span.icon "!"]
     [:div
      [:div [:b (case level
                  "warn"    (str "Garmin login expires in about " days_left " days")
                  "urgent"  (str "Garmin login expires in about " days_left " days")
                  "Garmin login has expired")]
       (when expires_on [:span.secondary (str " · estimated " (ui/date-short expires_on))])]
      (when sync_error [:div.secondary (str "Last sync failed: " sync_error)])
      [:div.secondary "Renew in a terminal: " [:code renew_command]]]]))

(defn- auth-chip [{:keys [level days_left]}]
  (case level
    "ok"      (ui/status-chip :ok (str "Garmin login · " days_left " days left"))
    "warn"    (ui/status-chip :warn (str "Garmin login · " days_left " days left"))
    "urgent"  (ui/status-chip :critical (str "Garmin login · " days_left " days left"))
    "expired" (ui/status-chip :critical "Garmin login expired")
    nil))

(defn sync-button [{:keys [running last_result finished_at]}]
  [:span {:style {:display "inline-flex" :gap "8px" :align-items "center"}}
   (cond
     running                        [:span.muted "Syncing…"]
     (false? (:ok last_result))     [:span.error "Sync failed"]
     finished_at                    [:span.muted "Synced"])
   [:button.btn {:disabled running :on {:click [[:sync]]}} "Sync"]])

(defn view [{:keys [data errors sync]}]
  (let [url "/api/today"
        d   (get data url)
        w   (:wellness d)
        b   (:baselines d)
        l   (:load d)
        a   (:last_activity d)
        sync-state (or sync (:sync d))]
    [:div
     [:div.page-head
      [:h1 "Today"]
      [:span.secondary (ui/date-long (:date d))]
      [:span.spacer]
      (auth-chip (:auth d))
      (sync-button sync-state)]
     (when-let [e (get errors url)] [:div.error (str "Could not load: " e)])
     (auth-notice (:auth d))
     (when d
       [:div.stack
        [:div.grid.tiles
         (ui/tile "Training readiness" (:readiness w) nil
                  (some-> (:readiness_level w) str/lower-case (str " · this morning"))
                  {:hero true})
         (ui/tile "Sleep score" (:sleep_score w) nil
                  (str (ui/hm (:sleep_s w)) " asleep · 7-day avg " (or (get-in b [:sleep_score :avg7]) "—")))
         (ui/tile "HRV last night" (:hrv_avg w) "ms"
                  (str "normal " (or (get-in b [:hrv_avg :base_low]) "—") "–" (or (get-in b [:hrv_avg :base_high]) "—")
                       (when (:hrv_status w) (str " · " (str/lower-case (:hrv_status w))))))
         (ui/tile "Resting HR" (:rhr w) "bpm"
                  (str "28-day avg " (or (get-in b [:rhr :avg28]) "—")))
         (ui/tile "Form (TSB)" (:tsb l) nil
                  (str "Fitness " (or (:ctl l) "—") " · Fatigue " (or (:atl l) "—")))
         (ui/tile "Body battery" (:body_battery_high w) nil
                  (str "low " (or (:body_battery_low w) "—") " today"))
         (ui/tile "Stress" (:avg_stress w) nil "average today")
         (ui/tile "Steps" (ui/thousands (:steps w)) nil nil)]
        [:div.grid.two
         [:div.card
          [:h2 "Planned today"]
          [:div.empty "No plan yet. Planning arrives in Phase 5."]]
         [:div.card
          [:h2 "Last activity"]
          (if a
            [:div
             [:div [:a {:href (str "#/activities/" (:id a))} [:b (:name a)]]]
             [:div.secondary (str (ui/sport-label (:sport a)) " · " (ui/date-long (:date a)))]
             [:div {:style {:margin-top "6px"}}
              (str/join " · " (remove nil? [(some-> (ui/km (:distance_m a)) (str " km"))
                                            (ui/hm (or (:moving_s a) (:duration_s a)))
                                            (some-> (:tss a) js/Math.round (str " " (:tss_method a)))
                                            (some-> (:avg_hr a) js/Math.round (str " bpm avg"))]))]]
            [:div.empty "No activities yet."])]]])]))
