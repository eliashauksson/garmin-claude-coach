(ns coach.views.status
  "What's wrong right now, shown above every page: the coach server is down,
  the Garmin login has expired (or is about to), or the last sync failed.
  Plus the backup link in the sidebar."
  (:require [clojure.string :as str]
            [coach.ui :as ui]))

(def backup-url "/api/backup")

(defn when-str
  "ISO instant -> \"1 Nov, 14:30\" in local time."
  [iso]
  (when (string? iso)
    (let [d (js/Date. iso)]
      (when-not (js/isNaN (.getTime d))
        (str (.toLocaleDateString d "en-GB" #js {:day "numeric" :month "short"}) ", "
             (.toLocaleTimeString d "en-GB" #js {:hour "2-digit" :minute "2-digit"}))))))

(defn- banner [level title & lines]
  [:div.banner {:class level :role "status"}
   [:span.icon "!"]
   (into [:div [:div [:b title]]]
         (for [l lines :when l] [:div.sub l]))])

(defn- data-up-to [last-ok]
  (when-let [t (when-str last-ok)]
    (str "Data shown is from the last successful sync, " t ".")))

(def ^:private kind-text
  {"rate-limit"  ["warn" "Garmin is limiting requests"
                  "Nothing to do: the next automatic sync (within 2 h) tries again."]
   "unreachable" ["warn" "Couldn't reach Garmin Connect"
                  "Check the network connection, then press Sync now."]
   "timeout"     ["warn" "Garmin didn't answer in time"
                  "Usually temporary: press Sync now, or wait for the next automatic sync."]
   "garmin"      ["warn" "Garmin returned an error"
                  "Usually temporary. If it keeps failing, Garmin may have changed its API: check data/mcp-server.log."]
   "server"      ["critical" "The Garmin connector didn't start"
                  "It runs through uvx; check that uv is installed and see data/mcp-server.log."]})

(defn- sync-banner
  "The last sync failed for a reason other than the login (that one has its own banner)."
  [{:keys [running last_result last_ok]}]
  (let [{:keys [ok kind message]} last_result]
    (when (and (false? ok) (not running) (not= "auth" kind))
      (let [[level title hint] (get kind-text kind
                                    ["warn" "The last sync failed"
                                     "If it keeps failing, Garmin may have changed its API: check data/mcp-server.log."])]
        (banner level title (str "Details: " message) hint (data-up-to last_ok))))))

(defn- auth-banner
  "Shown only in the final week of the login, once a sync failed on it, or past
  the estimated expiry."
  [{:keys [level days_left expires_on renew_command sync_error]} last-ok]
  (when (#{"urgent" "expired"} level)
    (banner "critical"
            (cond sync_error         "Garmin login has expired: syncing has stopped"
                  (= level "expired") "Garmin login has probably expired"
                  :else              (str "Garmin login expires in about " days_left " days"))
            (when (and expires_on (not sync_error)) (str "Estimated expiry " (ui/date-short expires_on) "."))
            (when sync_error (str "Details: " sync_error))
            [:span "Renew in a terminal: " [:code renew_command] ", then press Sync now."]
            (when sync_error (data-up-to last-ok)))))

(defn- credits-banner
  "The Claude API credits have run out: planning and the athlete chat stop
  until funds are added."
  [{:keys [credits_out billing_url since]} {:keys [checking result]}]
  (when credits_out
    (banner "critical" "Your Claude API credits have run out"
            (str "Plans, adjustments and the athlete chat need credits. Since " (or (when-str since) "the last request")
                 ", requests to Claude are refused, and the automatic planning after each sync is paused.")
            [:span "Add funds on the "
             [:a {:href billing_url :target "_blank" :rel "noopener noreferrer"} "billing page of the Claude Console"]
             ", then check again."]
            [:span {:style {:display "inline-flex" :gap "10px" :align-items "center" :margin-top "6px"}}
             [:button.pill {:disabled checking :on {:click [[:claude-check]]}}
              (if checking "Checking…" "I've added funds, check again")]
             (when (and result (not checking))
               [:span (if (str/includes? (str result) "credits have run out")
                        "Still no credits. If you just paid, wait a minute and try again."
                        (str "The check failed: " result))])])))

(defn view
  "Banners for every page. `sync` is the polled /api/sync state."
  [{:keys [sync server-down] ui-state :ui}]
  (if server-down
    (banner "critical" "Can't reach the coach server"
            [:span "It may have stopped. Start it again with " [:code "make run"] "; this page reconnects by itself."])
    (list (credits-banner (:claude sync) (:claude-check ui-state))
          (auth-banner (:auth sync) (:last_ok sync))
          (sync-banner sync))))

(defn auth-pill
  "Garmin login state for a page header; hidden until the final week."
  [{:keys [level days_left]}]
  (case level
    "urgent"  (ui/status-pill :critical (str "Garmin login · " days_left " days left"))
    "expired" (ui/status-pill :critical "Garmin login expired")
    "unknown" (ui/status-pill :warn "Garmin login not recorded")
    nil))

(defn sync-button [{:keys [running last_result]}]
  [:span {:style {:display "inline-flex" :gap "10px" :align-items "center"}}
   (when (and (not running) (false? (:ok last_result))) [:span.error "Sync failed"])
   [:button.btn-ink {:disabled running :on {:click [[:sync]]}}
    (if running
      (list [:span.spinner {:aria-hidden "true"}] "Syncing…")
      "Sync now")]])

(defn backup-link
  "Sidebar: download a fresh backup; shows when the last one was made."
  [{:keys [data]}]
  (let [{:keys [latest]} (get data backup-url)]
    [:div.backup
     [:a.backup-link {:href (str backup-url "/download") :download true
                      :title "Makes a backup now (database and athlete document) and downloads it"
                      :on {:click [[:backup-started]]}}
      "Download backup"]
     [:div.backup-sub
      (if-let [c (:created latest)]
        (str "Last: " (ui/date-short (subs c 0 10)) ", " (subs c 11 13) ":" (subs c 13 15))
        "No backup yet")]]))
