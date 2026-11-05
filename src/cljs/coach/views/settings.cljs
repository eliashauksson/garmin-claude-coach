(ns coach.views.settings
  "Settings: General (language, appearance, backup, Garmin, Coros) and Claude & API
  costs (model, effort and delivery per task, and what the requests cost)."
  (:require [coach.i18n :as i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.costs :as costs]
            [coach.views.status :as status]))

(def tabs [["general" (tk "General")] ["claude" (tk "Claude & API costs")]])

(def key-url "/api/claude/key")
(def app-url "/api/app")

(defn tab [route] (get-in route [:params :tab] "general"))

(defn urls [route]
  (if (= "claude" (tab route))
    [(costs/url route) costs/settings-url]
    [status/backup-url key-url app-url]))

(defn- choice
  "A row of buttons; the current one is highlighted."
  [current options action]
  [:div.choice
   (for [[v label attrs] options]
     [:button (merge {:replicant/key v :class (when (= v current) "on") :aria-pressed (= v current)
                      :on {:click [(conj action v)]}}
                     attrs)
      label])])

(defn- language-card []
  (ui/card {:flex "1 1 320px"}
           [:div.lab (t "Language")]
           (choice @i18n/lang (for [{:keys [id label]} i18n/languages] [id label {:lang id}]) [:set-lang])
           [:div.sub (t "Also the language Claude writes in from now on; its earlier texts are translated (see Claude & API costs: Translations).")]))

(defn- appearance-card [theme-choice]
  (ui/card {:flex "1 1 320px"}
           [:div.lab (t "Appearance")]
           (choice theme-choice [["light" (t "Light theme")] ["dark" (t "Dark theme")] ["system" (t "Like the system")]]
                   [:set-theme])))

(defn- backup-time
  "\"2 Nov, 07:41\" from a backup's created stamp (2026-11-02-074100)."
  [c]
  (str (ui/date-short (subs c 0 10)) ", " (subs c 11 13) ":" (subs c 13 15)))

(defn- backup-card [{:keys [latest count keep backups]} {:keys [busy error]}]
  (ui/card {:flex "1 1 320px"}
           [:div.lab (t "Backup")]
           [:div (if-let [c (:created latest)]
                   (t "Last backup: {0}" (backup-time c))
                   (t "No backup yet"))]
           [:div.sub (t "A backup (database and athlete document) is made before the first sync of each day; the newest {0} are kept ({1} now)."
                        (or keep 14) (or count 0))]
           [:div [:a.btn-ink.inline {:href (str status/backup-url "/download" (ui/token-qs)) :download true
                                     :on {:click [[:backup-started]]}}
                  (t "Download backup")]]
           [:details.connect-replace
            [:summary (t "Restore a backup")]
            [:div.sub (t "Puts the database and athlete document back as they were then. The current state is backed up first, so you can go back. The app restarts.")]
            (when (seq backups)
              [:ul.backup-list
               (for [{:keys [name created size]} backups]
                 [:li {:replicant/key name}
                  [:span (backup-time created)] [:span.sub (str (.toFixed (/ size 1048576) 1) " MB")]
                  [:button.pill {:disabled busy :on {:click [[:restore name (backup-time created)]]}} (t "Restore")]])])
            [:label.pill.file-pick {:class (when busy "disabled")}
             (t "Restore from a file…")
             [:input {:type "file" :accept ".zip,application/zip" :disabled busy
                      :on {:change [[:restore-file]]}}]]
            (when error [:div.error error])]))

(defn- connected-pill [connected?]
  (if connected?
    (ui/status-pill :ok (t "Connected"))
    (ui/status-pill :warn (t "Not connected"))))

(defn- history-section
  "Loading everything before the first sync, and how far it has got."
  [{:keys [history running job setup]}]
  (let [{:keys [complete loaded_from activities streams_missing]} history
        loading? (and running (= "history" job))]
    (when (and (:garmin setup) (:synced setup))
      [:div.connect-part
       [:div.lab (t "History")]
       (cond
         loading?
         [:div.sub [:span.spinner-inline {:aria-hidden "true"}]
          (if (not complete)
            (t "Going back in time: loaded back to {0}…" (ui/date-short loaded_from))
            (t "Fetching per-second data: {0} of {1} activities left…" streams_missing activities))]
         (and complete (zero? (or streams_missing 0)))
         [:div.sub (t "Everything Garmin has is loaded, back to {0}." (ui/date-short loaded_from))]
         :else
         (list
          [:div.sub {:replicant/key "x"}
           (t "So far only the weeks since the first sync. Load full history fetches every activity and health day Garmin has before that, then each activity's per-second data. It can take an hour or two; the dashboard stays usable, and if the app stops it carries on next time.")]
          [:div {:replicant/key "b"}
           [:button.btn-ink {:disabled running :on {:click [[:load-history]]}} (t "Load full history")]]))])))

(defn- garmin-login
  "Email and password, then the two-step code if Garmin sends one. Nothing is
  stored but the login Garmin hands back."
  [{:keys [email password code step busy error done]}]
  (if done
    [:div.ok-line {:replicant/key "gl"} (t "Logged in. The first sync is running; it takes a few minutes.")]
    [:div.garmin-login {:replicant/key "gl"}
     (if (= step :mfa)
       (list
        [:div {:replicant/key "m"} (t "Garmin sent you a code by email or in the Garmin Connect app. Enter it here:")]
        [:div.connect-row {:replicant/key "c"}
         [:input.note {:type "text" :inputmode "numeric" :autocomplete "one-time-code" :value (or code "")
                       :aria-label (t "Code") :placeholder "123456"
                       :on {:input   [[:set-in-ui :garmin-login :code :event/target-value]]
                            :keydown [[:garmin-login-key :code]]}}]
         [:button.btn-ink {:disabled (or busy (empty? code)) :on {:click [[:garmin-login :code]]}}
          (if busy (t "Checking…") (t "Confirm"))]
         [:button.pill {:disabled busy :on {:click [[:garmin-login :cancel]]}} (t "Cancel")]])
       (list
        [:div.connect-row {:replicant/key "e"}
         [:input.note {:type "email" :autocomplete "username" :value (or email "")
                       :aria-label (t "Garmin email") :placeholder (t "Garmin email")
                       :on {:input [[:set-in-ui :garmin-login :email :event/target-value]]}}]]
        [:div.connect-row {:replicant/key "p"}
         [:input.note {:type "password" :autocomplete "current-password" :value (or password "")
                       :aria-label (t "Garmin password") :placeholder (t "Garmin password")
                       :on {:input   [[:set-in-ui :garmin-login :password :event/target-value]]
                            :keydown [[:garmin-login-key :login]]}}]
         [:button.btn-ink {:disabled (or busy (empty? email) (empty? password)) :on {:click [[:garmin-login :login]]}}
          (if busy (t "Logging in…") (t "Log in"))]]
        (when busy
          [:div.sub {:replicant/key "w"} [:span.spinner-inline {:aria-hidden "true"}]
           (t "This can take a minute; the very first time a bit longer.")])))
     (when error [:div.error {:replicant/key "x"} error])]))

(defn- garmin-card [{:keys [auth last_ok setup] :as sync} login]
  (ui/card {:flex "1 1 360px" :class "connect"}
           [:div.lab "Garmin" [:span.sp] (connected-pill (not (false? (:garmin setup))))]
           (if (false? (:garmin setup))
             (list
              [:div {:replicant/key "t"}
               (t "The app reads your Garmin data through Garmin Connect. Log in once with your Garmin account:")]
              (garmin-login login)
              [:div.sub {:replicant/key "n"} (t "Your password is passed to Garmin and not stored. The login stays on this computer and lasts about 6 months; the app reminds you before it runs out. Nothing is ever written to Garmin.")])
             (list
              [:div {:replicant/key "t"}
               (case (:level auth)
                 "expired" (t "Garmin login expired")
                 "unknown" (t "Garmin login not recorded")
                 (if (:days_left auth)
                   (t "Garmin login: about {0} days left (until about {1})" (:days_left auth)
                      (ui/date-short (:expires_on auth)))
                   "—"))]
              (when-let [w (status/when-str last_ok)]
                [:div.sub {:replicant/key "l"} (t "Last successful sync: {0}" w)])
              [:div {:replicant/key "b"} (status/sync-button sync)]
              (cond
                (:done login) (garmin-login login)
                (or (#{"expired" "urgent" "unknown"} (:level auth)) (seq login))
                (list [:div.lab {:replicant/key "rl"} (t "Log in again")] (garmin-login login))
                :else
                [:details.connect-replace {:replicant/key "r"}
                 [:summary (t "Log in again")]
                 (garmin-login login)])
              (history-section sync)))))

(defn- coros-connect-button [busy label]
  [:button.btn-ink {:disabled busy :on {:click [[:coros-login]]}}
   (if busy (t "Opening Coros…") label)])

(defn- coros-card
  "Rides from a Coros bike computer, laid out like the Garmin card: signing in
  happens on Coros's own page, which sends the browser back here."
  [{:keys [coros running] :as sync} {:keys [busy error]}]
  (let [{:keys [connected connected_at login_error last_sync]} coros
        auth-failed (= "auth" (some-> (:kind last_sync) name))]
    (ui/card {:flex "1 1 360px" :class "connect"}
             [:div.lab "Coros" [:span.sp] (connected-pill connected)]
             (if-not connected
               (list
                [:div {:replicant/key "t"}
                 (t "For rides recorded on a Coros bike computer. Sign in once on the Coros page:")]
                [:div.connect-row {:replicant/key "b"} (coros-connect-button busy (t "Connect Coros"))]
                [:div.sub {:replicant/key "n"} (t "Every sync then fetches your new rides, with their per-second data. The login stays on this computer. Nothing is ever written to Coros.")])
               (list
                [:div {:replicant/key "t"}
                 (if auth-failed
                   (t "Coros login expired")
                   (if-let [w (status/when-str connected_at)] (t "Connected since {0}" w) "—"))]
                (if (:ok last_sync)
                  [:div.sub {:replicant/key "l"}
                   (t "Last Coros sync: {0}, {1} new activities" (status/when-str (:finished_at last_sync))
                      (:new last_sync))]
                  (when-let [w (status/when-str (:last_ok last_sync))]
                    [:div.sub {:replicant/key "l"} (t "Last successful sync: {0}" w)]))
                (cond
                  (or running (not last_sync) (:ok last_sync) auth-failed) nil
                  ;; Coros's daily FIT file limit: nothing wrong, the rest comes later
                  (= "rate-limit" (:kind last_sync))
                  [:div.sub {:replicant/key "s"}
                   (if-let [n (:remaining last_sync)]
                     (t "Coros allows 50 ride files a day and today's are used up. {0} more will load after the limit resets." n)
                     (t "Coros allows 50 ride files a day and today's are used up. The rest will load after the limit resets."))
                   (when-let [d (:paused_until last_sync)]
                     (when (neg? (compare (ui/iso-today) d))
                       (str " " (t "Automatic syncs wait until {0}; Sync now tries right away." (ui/date-short d)))))]
                  :else
                  [:div.error {:replicant/key "s"} (t "The last Coros sync failed: {0}" (:message last_sync))])
                [:div {:replicant/key "b"} (status/coros-sync-button sync)]
                (if auth-failed
                  (list [:div.lab {:replicant/key "rl"} (t "Connect again")]
                        [:div.connect-row {:replicant/key "rb"}
                         (coros-connect-button busy (t "Connect Coros"))
                         [:button.pill {:on {:click [[:coros-disconnect]]}} (t "Disconnect")]])
                  [:details.connect-replace {:replicant/key "r"}
                   [:summary (t "Connect again")]
                   [:div.connect-row
                    (coros-connect-button busy (t "Connect Coros"))
                    [:button.pill {:on {:click [[:coros-disconnect]]}} (t "Disconnect")]]])))
             (when-let [e (or error login_error)]
               [:div.error {:replicant/key "x"} e]))))

(defn- notice-line [{:keys [ok message]}]
  (when message
    [:div {:replicant/key "notice" :class (if ok "ok-line" "error")}
     message " " [:button.pill {:on {:click [[:dismiss-notice]]}} (t "OK")]]))

(defn- app-card [{:keys [version installed update autostart notice server]} ui-state]
  (let [{:keys [latest available url]} update
        updating (get-in ui-state [:app-update :busy])]
    (ui/card {:flex "1 1 320px"}
             [:div.lab (t "App")]
             (notice-line (when (= "restore" (:kind notice)) notice))
             [:div (t "Version {0}" version)]
             (cond
               available
               [:div.connect-row {:replicant/key "u"}
                [:span (t "Version {0} is available." latest)]
                [:button.btn-ink {:disabled updating :on {:click [[:app-update]]}} (t "Update now")]
                [:a {:href url :target "_blank" :rel "noopener noreferrer"} (t "What's new")]]
               (and installed latest)
               [:div.sub {:replicant/key "u"} (t "This is the newest version.")])
             (when-let [e (get-in ui-state [:app-update :error])] [:div.error e])
             (if server
               [:div.sub {:replicant/key "srv"}
                (t "Runs on a server: it is always on and syncs every 2 hours by itself.")]
               (list
                [:div.lab {:replicant/key "asl" :style {:margin-top "6px"}} (t "Start with the computer")]
                (if installed
                  (list
                   (choice (if autostart "on" "off") [["on" (t "On")] ["off" (t "Off")]] [:autostart-set])
                   [:div.sub {:replicant/key "as"} (t "Starts the app in the background when you log in, so it keeps syncing: Garmin keeps some data (training status, race predictions) only for the day itself.")])
                  [:div.sub {:replicant/key "asn"} (t "Only in the installed app.")])
                (when-let [e (get-in ui-state [:autostart :error])] [:div.error {:replicant/key "ase"} e])
                [:div.sub {:replicant/key "bg"} (t "The app keeps running in the background, syncing every 2 hours, until you quit it or shut down the computer. Its shortcut opens it again.")]
                [:div {:replicant/key "q"} [:button.pill {:on {:click [[:quit]]}} (t "Quit the app")]])))))

(defn- data-card [{:keys [data_dir default_data_dir data_dir_env notice]} {:keys [input busy error existing]}]
  (ui/card {:flex "1 1 320px"}
           [:div.lab (t "Data folder")]
           (notice-line (when (= "data-dir" (:kind notice)) notice))
           [:div.sub (t "Your training data, the athlete document, backups and logs are all here:")]
           [:div [:code.path data_dir]]
           (if data_dir_env
             [:div.sub (t "Set by COACH_DATA_DIR.")]
             [:details.connect-replace {:open (boolean (or existing error))}
              [:summary (t "Change the data folder")]
              [:div.sub (t "For example a folder that is backed up, or synced to another computer. The data moves there and the app restarts; with a lot of data this takes a minute.")]
              [:div.connect-row
               [:input.note {:type "text" :spellcheck "false" :value (or input "")
                             :placeholder default_data_dir :aria-label (t "New data folder")
                             :on {:input [[:set-in-ui :data-dir :input :event/target-value]]}}]
               [:button.btn-ink {:disabled (or busy (empty? input)) :on {:click [[:data-dir :move]]}}
                (t "Move the data here")]]
              (when existing
                [:div {:replicant/key "ex"}
                 [:div
                  (t "That folder already has Garmin Coach data. Use it instead? The current data stays where it is.")]
                 [:div.connect-row
                  [:button.btn-ink {:disabled busy :on {:click [[:data-dir :use]]}} (t "Use that data")]
                  [:button.pill {:on {:click [[:data-dir-cancel]]}} (t "Cancel")]]])
              (when error [:div.error error])])))

(defn- claude-card [{:keys [connected source hint file]} {:keys [input busy result]}]
  (let [save [:button.btn-ink {:disabled (or busy (empty? input)) :on {:click [[:claude-key :save]]}}
              (if busy (t "Checking…") (t "Save and test"))]
        field [:input.note {:type "password" :autocomplete "off" :spellcheck "false"
                            :placeholder "sk-ant-…" :value (or input "")
                            :aria-label (t "Claude API key")
                            :on {:input [[:set-in-ui :claude-key :input :event/target-value]]}}]]
    (ui/card {:flex "1 1 360px" :class "connect"}
             [:div.lab "Claude" [:span.sp] (connected-pill connected)]
             (if-not connected
               (list
                [:div {:replicant/key "t"} (t "The coach plans with Claude, through your own API key (you pay Anthropic per request; see Claude & API costs).")]
                [:ol.steps {:replicant/key "s"}
                 [:li [:span (t "Create a key in the Claude Console, and add some credit under Billing:") " "
                       [:a
                        {:href "https://platform.claude.com/settings/keys" :target "_blank" :rel "noopener noreferrer"}
                        "platform.claude.com/settings/keys"]]]
                 [:li (t "Paste it here:")]]
                [:div.connect-row {:replicant/key "f"} field save])
               (list
                [:div {:replicant/key "t"} (t "Key {0}" hint)
                 [:span.sub " · " (if (= "env" source)
                                    (t "from ANTHROPIC_API_KEY")
                                    (t "saved on this computer only, in {0}" file))]]
                [:div.connect-row {:replicant/key "b"}
                 [:button.pill {:disabled busy :on {:click [[:claude-key :test]]}} (if busy (t "Checking…") (t "Test"))]
                 (when (= "file" source)
                   [:button.pill {:disabled busy :on {:click [[:claude-key :remove]]}} (t "Remove key")])]
                (when (= "file" source)
                  [:details.connect-replace {:replicant/key "r"}
                   [:summary (t "Replace key")]
                   [:div.connect-row field save]])))
             (when result
               [:div {:replicant/key "res" :class (if (:ok result) "ok-line" "error")}
                (if (:ok result) (t "Claude answered: the key works.") (:error result))]))))

(defn view [{:keys [data route sync] :as s} theme-choice]
  (let [tb (tab route)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:h1.title (t "Settings")]
      (ui/seg tb tabs [:set-param :tab])]
     (if (= "claude" tb)
       (costs/view s (first (urls route)))
       (list
        [:div.row {:replicant/key "r0"} (garmin-card sync (get-in s [:ui :garmin-login]))
         (claude-card (get data key-url) (get-in s [:ui :claude-key]))]
        [:div.row {:replicant/key "rc"} (coros-card sync (get-in s [:ui :coros]))]
        [:div.row {:replicant/key "r1"} (language-card) (appearance-card theme-choice)]
        [:div.row {:replicant/key "r2"} (app-card (get data app-url) (:ui s))
         (data-card (get data app-url) (get-in s [:ui :data-dir]))]
        [:div.row {:replicant/key "r3"} (backup-card (get data status/backup-url) (get-in s [:ui :restore]))]))]))
