(ns coach.views.settings
  "Settings: General (language, appearance, backup, Garmin) and Claude & API
  costs (model, effort and delivery per task, and what the requests cost)."
  (:require [coach.i18n :as i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.costs :as costs]
            [coach.views.status :as status]))

(def tabs [["general" (tk "General")] ["claude" (tk "Claude & API costs")]])

(defn tab [route] (get-in route [:params :tab] "general"))

(defn urls [route]
  (if (= "claude" (tab route))
    [(costs/url route) costs/settings-url]
    [status/backup-url]))

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

(defn- garmin-card [{:keys [auth last_ok] :as sync}]
  (ui/card {:flex "1 1 320px"}
           [:div.lab "Garmin"]
           [:div (case (:level auth)
                   "expired" (t "Garmin login expired")
                   "unknown" (t "Garmin login not recorded")
                   (if (:days_left auth)
                     (t "Garmin login: about {0} days left (until about {1})" (:days_left auth) (ui/date-short (:expires_on auth)))
                     "—"))]
           (when-let [w (status/when-str last_ok)]
             [:div.sub (t "Last successful sync: {0}" w)])
           [:div.sub (t "Renew in a terminal:") " " [:code (or (:renew_command auth) "clojure -M -m coach.auth login")]]
           [:div (status/sync-button sync)]))

(defn view [{:keys [data route sync] :as s} theme-choice]
  (let [tb (tab route)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:h1.title (t "Settings")]
      (ui/seg tb tabs [:set-param :tab])]
     (if (= "claude" tb)
       (costs/view s (first (urls route)))
       (list
        [:div.row {:replicant/key "r1"} (language-card) (appearance-card theme-choice)]
        [:div.row {:replicant/key "r2"} (backup-card (get data status/backup-url)) (garmin-card sync)]))]))
