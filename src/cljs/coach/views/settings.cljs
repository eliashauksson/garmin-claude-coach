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

(defn- garmin-card [{:keys [auth last_ok setup] :as sync}]
  (let [cmd (or (:renew_command auth) "clojure -M -m coach.auth login")]
    (ui/card {:flex "1 1 360px" :class "connect"}
             [:div.lab "Garmin" [:span.sp] (connected-pill (not (false? (:garmin setup))))]
             (if (false? (:garmin setup))
               (list
                [:div {:replicant/key "t"} (t "The app reads your Garmin data through Garmin Connect. Log in once, in a terminal:")]
                [:ol.steps {:replicant/key "s"}
                 [:li (t "Open a terminal in the app folder.")]
                 [:li (t "Run this command:") " " [:code cmd] " " (t "It asks for your Garmin email, password and, if you use two-step verification, the code.")]
                 [:li (t "Come back here and press Sync now. The first sync loads the last 4 weeks and takes a few minutes.")]]
                [:div.sub {:replicant/key "n"} (t "The login stays on this computer and lasts about 6 months; the app reminds you before it runs out. Nothing is ever written to Garmin.")]
                [:div {:replicant/key "b"} (status/sync-button sync)])
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
                [:div.sub {:replicant/key "r"} (t "Renew in a terminal:") " " [:code cmd]]
                [:div {:replicant/key "b"} (status/sync-button sync)]
                (history-section sync))))))

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
        [:div.row {:replicant/key "r0"} (garmin-card sync) (claude-card (get data key-url) (get-in s [:ui :claude-key]))]
        [:div.row {:replicant/key "r1"} (language-card) (appearance-card theme-choice)]
        [:div.row {:replicant/key "r2"} (backup-card (get data status/backup-url))]))]))
