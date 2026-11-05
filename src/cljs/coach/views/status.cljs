(ns coach.views.status
  "What's wrong right now, shown above every page: the coach server is down,
  the Garmin login has expired (or is about to), or the last sync failed.
  Plus the backup link in the sidebar."
  (:require [coach.i18n :as i18n :refer [t tk]]
            [coach.ui :as ui]))

(def backup-url "/api/backup")

(defn when-str
  "ISO instant -> \"1 Nov, 14:30\" in local time."
  [iso]
  (when (string? iso)
    (let [d (js/Date. iso)]
      (when-not (js/isNaN (.getTime d))
        (str (.toLocaleDateString d (i18n/locale) #js {:day "numeric" :month "short"}) ", "
             (.toLocaleTimeString d (i18n/locale) #js {:hour "2-digit" :minute "2-digit"}))))))

(defn- banner [level title & lines]
  [:div.banner {:class level :role "status"}
   [:span.icon "!"]
   (into [:div [:div [:b title]]]
         (for [l lines :when l] [:div.sub l]))])

(defn- data-up-to [last-ok]
  (when-let [w (when-str last-ok)]
    (t "Data shown is from the last successful sync, {0}." w)))

(def ^:private kind-text
  {"rate-limit"  ["warn" (tk "Garmin is limiting requests")
                  (tk "Nothing to do: the next automatic sync (within 2 h) tries again.")]
   "unreachable" ["warn" (tk "Couldn't reach Garmin Connect")
                  (tk "Check the network connection, then press Sync now.")]
   "timeout"     ["warn" (tk "Garmin didn't answer in time")
                  (tk "Usually temporary: press Sync now, or wait for the next automatic sync.")]
   "garmin"      ["warn" (tk "Garmin returned an error")
                  (tk "Usually temporary. If it keeps failing, Garmin may have changed its API: check mcp-server.log in the data folder (Settings › General).")]
   "server"      ["critical" (tk "The Garmin connector didn't start")
                  (tk "It runs through uvx; check that uv is installed and see mcp-server.log in the data folder (Settings › General).")]})

(defn- sync-banner
  "The last sync failed for a reason other than the login (that one has its own banner)."
  [{:keys [running last_result last_ok]}]
  (let [{:keys [ok kind message]} last_result]
    (when (and (false? ok) (not running) (not= "auth" kind))
      (let [[level title hint] (get kind-text kind
                                    ["warn" (tk "The last sync failed")
                                     (tk "If it keeps failing, Garmin may have changed its API: check mcp-server.log in the data folder (Settings › General).")])]
        (banner level (t title) (t "Details: {0}" message) (t hint) (data-up-to last_ok))))))

(defn- connect-banners
  "Before Garmin or Claude is connected. Not on the Dashboard or Settings,
  whose setup checklist and connect cards say the same."
  [{:keys [setup]} page]
  (when (and setup (not (#{:dashboard :settings} page)))
    (list
     (when (false? (:garmin setup))
       (banner "warn" (t "Garmin isn't connected yet")
               (t "Log in with your Garmin account once.")
               [:a {:href "#/settings"} (t "Connect in Settings › General")]))
     (when (false? (:claude setup))
       (banner "warn" (t "Claude isn't connected yet")
               (t "Planning, adjustments and the athlete chat need a Claude API key.")
               [:a {:href "#/settings"} (t "Add it in Settings › General")])))))

(defn- auth-banner
  "Shown only in the final week of the login, once a sync failed on it, or past
  the estimated expiry."
  [{:keys [level days_left expires_on sync_error]} last-ok]
  (when (#{"urgent" "expired"} level)
    (banner "critical"
            (cond sync_error         (t "Garmin login has expired: syncing has stopped")
                  (= level "expired") (t "Garmin login has probably expired")
                  :else              (t "Garmin login expires in about {0} days" days_left))
            (when (and expires_on (not sync_error)) (t "Estimated expiry {0}." (ui/date-short expires_on)))
            (when sync_error (t "Details: {0}" sync_error))
            [:a {:href "#/settings"} (t "Log in again in Settings › General")]
            (when sync_error (data-up-to last-ok)))))

(defn- credits-banner
  "The Claude API credits have run out: planning and the athlete chat stop
  until funds are added."
  [{:keys [credits_out billing_url since]} {:keys [checking result still-out]}]
  (when credits_out
    (banner "critical" (t "Your Claude API credits have run out")
            (t "Plans, adjustments and the athlete chat need credits. Since {0}, requests to Claude are refused, and the automatic planning after each sync is paused."
               (or (when-str since) (t "the last request")))
            [:span (t "Add funds on the") " "
             [:a {:href billing_url :target "_blank" :rel "noopener noreferrer"} (t "billing page of the Claude Console")]
             (t ", then check again.")]
            [:span {:style {:display "inline-flex" :gap "10px" :align-items "center" :margin-top "6px"}}
             [:button.pill {:disabled checking :on {:click [[:claude-check]]}}
              (if checking (t "Checking…") (t "I've added funds, check again"))]
             (when (and result (not checking))
               [:span (if still-out
                        (t "Still no credits. If you just paid, wait a minute and try again.")
                        (t "The check failed: {0}" result))])])))

(defn view
  "Banners for every page. `sync` is the polled /api/sync state."
  [{:keys [sync server-down quit route restarting] ui-state :ui}]
  (cond
    restarting
    (banner "warn" (case (:kind restarting)
                     :update  (t "Updating Garmin Coach…")
                     :restore (t "Restoring the backup…")
                     :data-dir (t "Moving the data…")
                     (t "Restarting…"))
            (case (:kind restarting)
              :update   (t "The new version is downloaded and the app restarts, in a minute or two. This page reconnects by itself.")
              :data-dir (t "The app restarts with the data in its new folder; with a lot of data this takes a minute. This page reconnects by itself.")
              (t "The app restarts. This page reconnects by itself.")))
    server-down
    (if quit
      (banner "warn" (t "The app has stopped")
              (t "Open it again from its shortcut; this page reconnects by itself."))
      (banner "critical" (t "Can't reach the coach server")
              (t "It may have stopped. Open the app again from its shortcut (or run make run); this page reconnects by itself.")))
    :else
    (list (when (:translating sync)
            [:div.sub.translating {:role "status"} [:span.spinner {:aria-hidden "true"}] " "
             (t "Claude's texts are being translated; they appear here when done.")])
          (connect-banners sync (:page route))
          (when (and (get-in sync [:update :available]) (= :dashboard (:page route)))
            (banner "warn" (t "A new version is available: {0}" (get-in sync [:update :latest]))
                    nil [:a {:href "#/settings"} (t "Update in Settings › General")]))
          (credits-banner (:claude sync) (:claude-check ui-state))
          (auth-banner (:auth sync) (:last_ok sync))
          (sync-banner sync))))

(defn auth-pill
  "Garmin login state for a page header; hidden until the final week."
  [{:keys [level days_left]}]
  (case level
    "urgent"  (ui/status-pill :critical (t "Garmin login · {0} days left" days_left))
    "expired" (ui/status-pill :critical (t "Garmin login expired"))
    "unknown" (ui/status-pill :warn (t "Garmin login not recorded"))
    "not_connected" (ui/status-pill :warn (t "Garmin not connected"))
    nil))

(defn sync-button [{:keys [running job last_result setup]}]
  (let [no-garmin (false? (:garmin setup))]
    [:span {:style {:display "inline-flex" :gap "10px" :align-items "center"}}
     (when (and (not running) (not no-garmin) (false? (:ok last_result))) [:span.error (t "Sync failed")])
     [:button.btn-ink {:disabled (or running no-garmin)
                       :title (when no-garmin (t "Connect Garmin first"))
                       :on {:click [[:sync]]}}
      (cond
        (and running (= "history" job)) (list [:span.spinner {:aria-hidden "true"}] (t "Loading history…"))
        running (list [:span.spinner {:aria-hidden "true"}] (t "Syncing…"))
        :else (t "Sync now"))]]))

(defn coros-sync-button
  "Settings, Coros card: syncs the Coros rides alone, like `sync-button` for
  Garmin. Every Garmin sync fetches them too."
  [{:keys [running job coros]}]
  (let [last-sync (:last_sync coros)]
    [:span {:style {:display "inline-flex" :gap "10px" :align-items "center"}}
     (when (and (not running) last-sync (false? (:ok last-sync)) (not= "rate-limit" (:kind last-sync)))
       [:span.error (t "Sync failed")])
     [:button.btn-ink {:disabled running :on {:click [[:sync-coros]]}}
      (if (and running (#{"sync" "coros"} job))
        (list [:span.spinner {:aria-hidden "true"}] (t "Syncing…"))
        (t "Sync now"))]]))

