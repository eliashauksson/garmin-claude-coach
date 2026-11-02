(ns coach.views.settings
  "Settings: General (language, appearance, backup, Garmin) and Claude & API
  costs (model, effort and delivery per task, and what the requests cost)."
  (:require [coach.i18n :as i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.costs :as costs]
            [coach.views.status :as status]))

(def tabs [["general" (tk "General")] ["claude" (tk "Claude & API costs")]])

(def key-url "/api/claude/key")

(defn tab [route] (get-in route [:params :tab] "general"))

(defn urls [route]
  (if (= "claude" (tab route))
    [(costs/url route) costs/settings-url]
    [status/backup-url key-url]))

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
           (choice theme-choice [["light" (t "Light theme")] ["dark" (t "Dark theme")] ["system" (t "Like the system")]] [:set-theme])))

(defn- backup-card [{:keys [latest count keep]}]
  (ui/card {:flex "1 1 320px"}
           [:div.lab (t "Backup")]
           [:div (if-let [c (:created latest)]
                   (t "Last backup: {0}" (str (ui/date-short (subs c 0 10)) ", " (subs c 11 13) ":" (subs c 13 15)))
                   (t "No backup yet"))]
           [:div.sub (t "A backup (database and athlete document) is made before the first sync of each day; the newest {0} are kept in data/backups ({1} now)."
                        (or keep 14) (or count 0))]
           [:div [:a.btn-ink.inline {:href (str status/backup-url "/download") :download true
                                     :on {:click [[:backup-started]]}}
                  (t "Download backup")]]
           [:div.sub (t "To restore one, stop the dashboard and run")
            " " [:code "make restore FILE=…"]]))

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
              [:div {:replicant/key "t"} (t "The app reads your Garmin data through Garmin Connect. Log in once with your Garmin account:")]
              (garmin-login login)
              [:div.sub {:replicant/key "n"} (t "Your password is passed to Garmin and not stored. The login stays on this computer and lasts about 6 months; the app reminds you before it runs out. Nothing is ever written to Garmin.")])
             (list
              [:div {:replicant/key "t"}
               (case (:level auth)
                 "expired" (t "Garmin login expired")
                 "unknown" (t "Garmin login not recorded")
                 (if (:days_left auth)
                   (t "Garmin login: about {0} days left (until about {1})" (:days_left auth) (ui/date-short (:expires_on auth)))
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

(defn- app-card []
  (ui/card {:flex "1 1 320px"}
           [:div.lab (t "App")]
           [:div.sub (t "The app keeps running in the background, syncing every 2 hours, until you quit it or shut down the computer. Its shortcut opens it again.")]
           [:div [:button.pill {:on {:click [[:quit]]}} (t "Quit the app")]]))

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
                       [:a {:href "https://platform.claude.com/settings/keys" :target "_blank" :rel "noopener noreferrer"}
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
        [:div.row {:replicant/key "r0"} (garmin-card sync (get-in s [:ui :garmin-login])) (claude-card (get data key-url) (get-in s [:ui :claude-key]))]
        [:div.row {:replicant/key "r1"} (language-card) (appearance-card theme-choice)]
        [:div.row {:replicant/key "r2"} (backup-card (get data status/backup-url)) (app-card)]))]))
