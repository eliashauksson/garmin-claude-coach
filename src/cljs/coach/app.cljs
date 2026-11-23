(ns coach.app
  (:require [clojure.string :as str]
            [coach.heat :as heat]
            [coach.i18n :as i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.activity :as activity]
            [coach.views.athlete :as athlete]
            [coach.views.calendar :as calendar]
            [coach.views.costs :as costs]
            [coach.views.day :as day]
            [coach.views.heatmap :as heatmap]
            [coach.views.metrics :as metrics]
            [coach.views.more :as more]
            [coach.views.peaks :as peaks]
            [coach.views.plan :as plan]
            [coach.views.settings :as settings]
            [coach.views.status :as status]
            [coach.views.today :as today]
            [coach.views.trends :as trends]
            [coach.views.workout :as workout]
            [replicant.dom :as r]))

(defonce state (atom {:route {:page :today} :data {} :loading #{} :errors {} :ui {}}))

;; ---------------------------------------------------------------------------
;; Routing (hash based: #/activities?range=90)

(defn parse-route [hash]
  (let [h          (str/replace (or hash "") #"^#" "")
        [path qs]  (str/split h #"\?" 2)
        segs       (remove str/blank? (str/split (or path "") #"/"))
        params     (into {} (for [kv (str/split (or qs "") #"&")
                                  :when (seq kv)
                                  :let [[k v] (str/split kv #"=" 2)]]
                              [(keyword k) (js/decodeURIComponent (or v ""))]))]
    (case (first segs)
      ;; an activity's page; the activities list and the Data page are gone
      "activities" (if-let [id (second segs)]
                     {:page :activity :id id}
                     {:page :today :params {}})
      ;; old link: the calendar is a view of the Plan page
      "calendar"   {:page :plan :params (assoc params :view "calendar")}
      "map"        {:page :map :params params}
      "peaks"      {:page :peaks :params params}
      ;; one planned session's card
      "workouts"   (if-let [id (second segs)] {:page :workout :id id} {:page :plan :params {}})
      "plan"       {:page :plan :params params}
      "trends"     {:page :trends :params params}
      "more"       {:page :more :params {}}
      "athlete"    {:page :athlete :params {}}
      "day"        {:page :day :date (second segs)}
      "settings"   {:page :settings :params params}
      "metrics"    {:page :metrics :params params}
      ;; old links: the costs now live in Settings
      "costs"      {:page :settings :params (assoc params :tab "claude")}
      ;; Today is the start page
      {:page :today :params params})))

(defn route-hash [{:keys [page id params]}]
  (let [qs (str/join "&" (for [[k v] params :when (some? v)]
                           (str (name k) "=" (js/encodeURIComponent v))))]
    (str "#/" (case page :today "" :activity (str "activities/" id) :workout (str "workouts/" id) (name page))
         (when (seq qs) (str "?" qs)))))

(defn urls [{:keys [page id] :as route}]
  (case page
    :day        [(day/url (:date route))]
    :activity   [(str "/api/activities/" id "?streams=true")]
    :workout    [(workout/url id)]
    :map        [heatmap/url heatmap/peaks-url heatmap/map-keys-url]
    :peaks      [peaks/url peaks/squares-url]
    :plan       (plan/urls route (:ui @state))
    :today      (today/urls route)
    :trends     (trends/urls route)
    :athlete    [athlete/url]
    :settings   (settings/urls route (:sync @state))
    []))

;; ---------------------------------------------------------------------------
;; Data

(declare load-route!)

(defn fetch! [url & [{:keys [force]}]]
  (when (or force (not (contains? (:data @state) url)))
    (when-not (contains? (:data @state) url) ; refetches keep the old render, no fade
      (swap! state update :loading conj url))
    (-> (js/fetch url)
        (.then (fn [res]
                 (if (.-ok res)
                   (.json res)
                   ;; the server sends {"error": "..."}; say that rather than "HTTP 500"
                   (-> (.json res)
                       (.catch (fn [_] nil))
                       (.then #(throw (js/Error. (or (some-> % .-error) (str "HTTP " (.-status res))))))))))
        (.then (fn [body]
                 (let [data   (js->clj body :keywordize-keys true)
                       before (get-in @state [:data url])]
                   (swap! state #(-> % (assoc-in [:data url] data)
                                     (update :loading disj url) (update :errors dissoc url)))
                   ;; a proposal or the athlete summary is being generated: check again shortly
                   (if (or (= "running" (:status data)) (:summarising data))
                     (js/setTimeout #(fetch! url {:force true}) 4000)
                     ;; it just finished: it may have started more work (a finished season
                     ;; plan starts the week proposals), so reload the page's data
                     (when (= "running" (:status before))
                       (load-route! {:force true}))))))
        (.catch (fn [e]
                  (swap! state #(-> % (update :loading disj url)
                                    (assoc-in [:errors url] (.-message e)))))))))

(defn load-route! [& [opts]]
  (run! #(fetch! % opts) (urls (:route @state))))

(defn api!
  "Sends a write request (JSON body), then reloads the current page's data."
  [method url body]
  (-> (js/fetch url #js {:method method
                         :headers #js {"Content-Type" "application/json"}
                         :body (when body (js/JSON.stringify (clj->js body)))})
      (.then (fn [res]
               (if (.-ok res)
                 (load-route! {:force true})
                 (-> (.json res)
                     (.then #(swap! state assoc :flash (t "Error: {0}" (.-error %))))))))
      (.catch #(swap! state assoc :flash (t "Error: {0}" (.-message %))))))

(defonce sync-timer (atom nil))

(defn- sync-state! [s]
  (let [{was :running done :finished_at translating :translating} (:sync @state)]
    (swap! state assoc :sync s)
    ;; Claude's texts were just translated: show them, in place
    (when (and translating (not (:translating s)))
      (load-route! {:force true}))
    ;; a sync ended since the last poll (also one shorter than the interval)
    (when (and (not (:running s))
               (or was (and done (not= done (:finished_at s)))))
      ;; new data: drop the cache and reload this page. The sidebar's backup
      ;; status isn't page data: keep it, and refresh it (a sync can make the
      ;; day's backup)
      (swap! state update :data select-keys [status/backup-url])
      (load-route!)
      (fetch! status/backup-url {:force true}))))

(defn poll-sync!
  "Polls /api/sync every 3 s while a sync runs, else every 30 s, so syncs the
  server starts on its own show up too."
  []
  (js/clearTimeout @sync-timer)
  (-> (js/fetch "/api/sync")
      (.then #(.json %))
      (.then (fn [body]
               (let [b (js->clj body :keywordize-keys true)
                     restarted (when-let [{:keys [from]} (:restarting @state)]
                                 (not= from (:started_at b)))]
                 (when (or (:server-down @state) restarted)
                   ;; back after a restart: reload what failed while it was down
                   (swap! state assoc :server-down false :quit false :restarting nil :errors {} :data {})
                   (load-route!)
                   (fetch! status/backup-url))
                 (sync-state! b))))
      (.catch (fn [_] (swap! state assoc :server-down true)))
      (.finally #(reset! sync-timer (js/setTimeout poll-sync! (cond (:restarting @state) 1500
                                                                     (or (get-in @state [:sync :running])
                                                                         (get-in @state [:sync :translating])) 3000
                                                                     :else 30000))))))

(defn- restarting!
  "The server restarts (restore, data folder) or updates: say so and poll
  until a new server answers."
  [kind]
  (swap! state assoc :restarting {:kind kind :from (get-in @state [:sync :started_at])})
  (poll-sync!))

(defn- app-request!
  "A request that may restart the app. Errors land in [:ui k :error]."
  [k method url body]
  (swap! state update-in [:ui k] assoc :busy true :error nil)
  (-> (js/fetch url (clj->js (cond-> {:method method
                                      :headers (cond-> {} @ui/token (assoc "X-Coach-Token" @ui/token))}
                               (some? body) (-> (assoc-in [:headers "Content-Type"] "application/json")
                                                (assoc :body (js/JSON.stringify (clj->js body)))))))
      (.then (fn [res] (.then (.json res) #(let [r (js->clj % :keywordize-keys true)]
                                             (if (.-ok res) r (throw (js/Error. (:error r))))))))
      (.then (fn [r]
               (swap! state update-in [:ui k] assoc :busy false :existing (when (:existing r) (:to r)))
               (cond (:restarting r) (restarting! k)
                     (:started r)    (restarting! :update))
               r))
      (.catch (fn [e] (swap! state update-in [:ui k] assoc :busy false :error (.-message e))))))

(defn start-sync! [& [url]]
  (-> (js/fetch (or url "/api/sync") #js {:method "POST"})
      (.then #(.json %))
      (.then (fn [body]
               (sync-state! (js->clj body :keywordize-keys true))
               (poll-sync!)))))

;; ---------------------------------------------------------------------------
;; Athlete chat: the conversation lives in [:ui :athlete :chat] until applied or closed

(defn- post-json [url body]
  (-> (js/fetch url #js {:method "POST" :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify (clj->js body))})
      (.then (fn [res] (.then (.json res) #(if (.-ok res) (js->clj % :keywordize-keys true)
                                                (throw (js/Error. (.-error %)))))))))

(defn send-athlete-chat! []
  (let [path  [:ui :athlete :chat]
        {:keys [input messages busy?]} (get-in @state path)
        text  (str/trim (or input ""))]
    (when-not (or busy? (str/blank? text))
      (let [msgs (conj (vec messages) {:role "user" :text text})]
        (swap! state update-in path assoc :messages msgs :input "" :busy? true :error nil)
        (-> (post-json (str athlete/url "/chat") {:messages msgs})
            (.then (fn [{:keys [reply proposal usage]}]
                     (swap! state update-in path
                            #(cond-> (-> % (assoc :busy? false)
                                         (update :messages conj {:role "assistant" :text reply})
                                         (update :cost (fnil + 0) (or (:cost_chf usage) 0)))
                               proposal (assoc :proposal proposal)))))
            (.catch (fn [e]
                      ;; give the message back so it can be sent again
                      (swap! state update-in path assoc :busy? false :messages messages :input text
                             :error (t "Error: {0}" (.-message e))))))))))

(def max-upload-chars "Same limit as the server (coach.athlete/max-chars)." 200000)

(defn read-athlete-file!
  "Reads the file picked for upload into [:ui :athlete :upload] for a preview;
  nothing is saved until the athlete confirms."
  [^js ev]
  (let [input (.-target ev)
        f     (aget (.-files input) 0)
        put!  #(swap! state assoc-in [:ui :athlete :upload] %)]
    (set! (.-value input) "") ; picking the same file again fires a new change
    (when f
      (-> (.text f)
          (.then (fn [text]
                   (put! (cond
                           (str/includes? text "\u0000")
                           {:error (t "{0} doesn't look like a text file. Upload Markdown (.md) or plain text (.txt)."
                                      (.-name f))}
                           (str/blank? text)  {:error (t "{0} is empty." (.-name f))}
                           (> (count text)
                              max-upload-chars) {:error (t "{0} is too long (over 200,000 characters)." (.-name f))}
                           :else {:name (.-name f) :content text}))))
          (.catch #(put! {:error (t "Could not read the file: {0}" (.-message %))}))))))

(defn summarise-athlete! []
  (swap! state assoc-in [:ui :athlete :summarising?] true)
  (-> (post-json (str athlete/url "/summary") {})
      (.then #(swap! state (fn [s] (-> s (assoc-in [:data athlete/url] %)
                                       (assoc-in [:ui :athlete :summarising?] false)))))
      (.catch #(swap! state (fn [s] (-> s (assoc :flash (t "Error: {0}" (.-message %)))
                                        (assoc-in [:ui :athlete :summarising?] false)))))))

;; ---------------------------------------------------------------------------
;; Theme: <html data-theme="light|dark">. A choice made with the toggle is kept in
;; localStorage; without one the system setting decides (also set early in index.html).

(declare render!)

(def system-dark (js/matchMedia "(prefers-color-scheme: dark)"))

(defn apply-theme! []
  (let [saved (.getItem js/localStorage "theme")]
    (set! (.. js/document -documentElement -dataset -theme)
          (if (#{"light" "dark"} saved) saved (if (.-matches system-dark) "dark" "light")))))

;; ---------------------------------------------------------------------------
;; Language: a server setting (it also decides the language Claude writes in);
;; localStorage only avoids a flash of the wrong language on load.

(defn- apply-lang! [lang]
  (when (i18n/language-ids lang)
    (reset! i18n/lang lang)
    (.setItem js/localStorage "lang" lang)
    (set! (.. js/document -documentElement -lang) lang)
    (swap! state assoc :lang lang)))

(defn- reload-in-lang!
  "Refetches the page's data in place: the server's own texts (signals,
  warnings) come back in the new language. Nothing is cleared first, so no
  data goes missing while a request is in flight."
  []
  (load-route! {:force true})
  (fetch! status/backup-url {:force true}))

(defn load-lang!
  "Takes the language the server has (another browser may have changed it)."
  []
  (-> (js/fetch "/api/lang")
      (.then #(.json %))
      (.then (fn [body]
               (let [lang (.-lang body)]
                 ;; an extra athlete on a server: its name in the tab and on the home screen
                 (when-let [inst (.-instance body)]
                   (set! (.-title js/document) (str "Coach · " inst))
                   (swap! state assoc :instance inst))
                 (when (not= lang @i18n/lang)
                   (apply-lang! lang)
                   (reload-in-lang!)))))
      (.catch (fn [_]))))

(defn set-lang! [lang]
  (apply-lang! lang)
  ;; a switch may start a translation run: watch it closely from now
  (js/setTimeout poll-sync! 1500)
  (-> (js/fetch "/api/lang" #js {:method "PUT" :headers #js {"Content-Type" "application/json"}
                                 :body (js/JSON.stringify #js {:lang lang})})
      (.then (fn [_] (reload-in-lang!)))
      (.catch #(swap! state assoc :flash (t "Error: {0}" (.-message %))))))

(defn theme-choice
  "\"light\", \"dark\" or \"system\" (follow the system setting)."
  []
  (let [saved (.getItem js/localStorage "theme")]
    (if (#{"light" "dark"} saved) saved "system")))

(defn set-theme! [choice]
  (if (#{"light" "dark"} choice)
    (.setItem js/localStorage "theme" choice)
    (.removeItem js/localStorage "theme"))
  (apply-theme!)
  (render!))

;; ---------------------------------------------------------------------------
;; Actions (Replicant dispatches data; this interprets it)

;; Each action is a function of its arguments (the rest of the action vector);
;; `actions` maps the action's keyword to it, and `handle` looks it up.

(defn- set-hash! [h] (set! (.-hash js/location) h))

(defn- set-param! [[k v]]
  (set-hash! (route-hash (assoc-in (:route @state) [:params k] v))))

(defn- api-action! [[method url body]]
  (swap! state dissoc :flash)
  (api! method url body))

(defn- send-workout!
  "Sends a planned workout to a device (\"garmin\" or \"coros\"); the panel's state
  (busy, error, a COROS copy left on another day) is kept under [:ui :send id]."
  [[id service]]
  (swap! state update-in [:ui :send id] assoc :busy service :error nil :old-left nil)
  (-> (js/fetch (str "/api/plan/workouts/" id "/send/" service) #js {:method "POST"})
      (.then (fn [res]
               (-> (.json res)
                   (.then (fn [d] (if (.-ok res) d (throw (js/Error. (.-error d)))))))))
      (.then (fn [d]
               (swap! state update-in [:ui :send id] assoc :busy nil :old-left (.-old_left ^js d))
               (load-route! {:force true})))
      (.catch (fn [e] (swap! state update-in [:ui :send id] assoc :busy nil :error (.-message e))))))

(defn- ai-setting! [[task field value]]
  (let [current (some #(when (= task (:id %)) %)
                      (get-in @state [:data costs/settings-url :tasks]))]
    (swap! state dissoc :flash)
    (api! "PUT" (str costs/settings-url "/" task)
          (assoc (select-keys current [:model :effort :delivery :scope]) field value))))

(defn- claude-limits! [_]
  (let [{:keys [ui data route]} @state
        edits (:claude-limits ui)
        value (fn [r field k] (get-in edits [(or (:name r) "") field] (k r)))]
    (swap! state dissoc :flash)
    (api! "PUT" costs/limits-url
          {:limits (vec (for [r (get-in data [(costs/url route) :limits :athletes])]
                          {:name        (:name r)
                           :daily_usd   (value r :daily :daily_usd)
                           :monthly_usd (value r :monthly :monthly_usd)}))})))

(defn- claude-check! [_]
  (swap! state assoc-in [:ui :claude-check] {:checking true})
  (-> (js/fetch "/api/claude/check" #js {:method "POST"})
      (.then #(.json %))
      (.then (fn [body]
               (let [{:keys [ok error claude]} (js->clj body :keywordize-keys true)]
                 (swap! state assoc-in [:ui :claude-check]
                        (if ok nil {:checking false :result error
                                    :still-out (boolean (:credits_out claude))}))
                 (poll-sync!))))
      (.catch #(swap! state assoc-in [:ui :claude-check]
                      {:checking false :result (.-message %)}))))

(defn- claude-key! [[op]]
  (let [path [:ui :claude-key]
        [method url body] (case op
                            :save   ["PUT" settings/key-url {:key (get-in @state (conj path :input))}]
                            :test   ["POST" "/api/claude/test" nil]
                            :remove ["DELETE" settings/key-url nil])
        done (fn [r]
               (swap! state #(-> %
                                 (assoc-in path (cond-> {:result (when (not= op :remove) r)}
                                                  (and (= op :save) (not (:ok r)))
                                                  (assoc :input (get-in % (conj path :input)))))
                                 (cond-> (:key r) (assoc-in [:data settings/key-url] (:key r)))
                                 (cond-> (= op :remove) (assoc-in [:data settings/key-url] r))))
               (poll-sync!))]
    (swap! state update-in path assoc :busy true :result nil)
    (-> (js/fetch url #js {:method method :headers #js {"Content-Type" "application/json"}
                           :body (when body (js/JSON.stringify (clj->js body)))})
        (.then (fn [res] (.then (.json res) #(let [r (js->clj % :keywordize-keys true)]
                                               (if (.-ok res) r {:ok false :error (:error r)})))))
        (.then done)
        (.catch #(done {:ok false :error (.-message %)})))))

;; the Tracestrack map key (Settings): saved per athlete in config.edn
(defn- map-key! [[op]]
  (let [path [:ui :map-key]
        body {:tracestrack (if (= op :save) (get-in @state (conj path :input)) "")}]
    (swap! state update-in path assoc :busy true :error nil)
    (-> (js/fetch settings/map-keys-url #js {:method "PUT" :headers #js {"Content-Type" "application/json"}
                                             :body (js/JSON.stringify (clj->js body))})
        (.then (fn [res] (.then (.json res) #(let [r (js->clj % :keywordize-keys true)]
                                               (if (.-ok res) r {:error (:error r)})))))
        (.then (fn [r]
                 (swap! state #(cond-> (assoc-in % path {:error (:error r)})
                                 (not (:error r)) (assoc-in [:data settings/map-keys-url] r)))))
        (.catch #(swap! state assoc-in path {:error (.-message %)})))))

;; Garmin login from Settings: email + password, then the code if Garmin sends one
(defn- garmin-login! [[op]]
  (let [path [:ui :garmin-login]
        {:keys [email password code]} (get-in @state path)
        done (fn [{:keys [status error]}]
               (swap! state update-in path
                      #(case status
                         "ok"  {:done true}
                         "mfa" (assoc % :busy false :step :mfa :code "" :error error)
                         (assoc % :busy false :step nil :password "" :error error)))
               (poll-sync!))]
    (case op
      :cancel (do (swap! state assoc-in path {})
                  (js/fetch "/api/garmin/login" #js {:method "DELETE"}))
      (do (swap! state update-in path assoc :busy true :error nil :done nil)
          (-> (if (= op :code)
                (post-json "/api/garmin/code" {:code code})
                (post-json "/api/garmin/login" {:email email :password password}))
              (.then done)
              (.catch #(done {:status "error" :error (.-message %)})))))))

;; Coros: off to the Coros sign-in page, which sends the browser back to Settings
(defn- coros-login! [_]
  (swap! state assoc-in [:ui :coros] {:busy true})
  (-> (post-json "/api/coros/login" {})
      (.then #(set! (.-location js/window) (:url %)))
      (.catch #(swap! state assoc-in [:ui :coros] {:error (.-message %)}))))

(defn- coros-disconnect! [_]
  (when (js/confirm (t "Disconnect Coros? The rides already loaded stay."))
    (-> (js/fetch "/api/coros/login" #js {:method "DELETE"})
        (.then #(do (swap! state assoc-in [:ui :coros] {}) (poll-sync!))))))

;; the installed app: update, data folder, restore, start with the computer
(defn- data-dir! [[mode]]
  (app-request! :data-dir "POST" "/api/app/data-dir"
                {:path (get-in @state [:ui :data-dir :input]) :use_existing (= :use mode)}))

(defn- autostart! [[on]]
  (-> (app-request! :autostart "PUT" "/api/app/autostart" {:on on})
      (.then #(when (map? %) (swap! state assoc-in [:data settings/app-url] %)))))

(defn- restore! [[backup-name from]]
  (when (js/confirm (t "Restore the backup from {0}? The current state is backed up first, and the app restarts." from))
    (app-request! :restore "POST" (str status/backup-url "/restore") {:name backup-name})))

(defn- restore-file! [[ev]]
  (let [file (some-> ^js ev .-target .-files (aget 0))]
    (when (and file
               (js/confirm (t "Restore the backup {0}? The current state is backed up first, and the app restarts." (.-name file))))
      (swap! state update-in [:ui :restore] assoc :busy true :error nil)
      (-> (js/fetch (str status/backup-url "/restore-upload")
                    #js {:method "POST" :body file :headers #js {"Content-Type" "application/zip"}})
          (.then (fn [res] (.then (.json res) #(if (.-ok res) % (throw (js/Error. (.-error %)))))))
          (.then (fn [_] (swap! state assoc-in [:ui :restore :busy] false) (restarting! :restore)))
          (.catch (fn [e]
                    (swap! state update-in [:ui :restore] assoc
                           :busy false :error (.-message e))))))))

(defn- quit! [_]
  (-> (js/fetch "/api/quit" #js {:method "POST"})
      (.then #(swap! state assoc :quit true :server-down true))))

(defn- load-history! [_]
  (-> (js/fetch "/api/history" #js {:method "POST"})
      (.then (fn [_] (poll-sync!)))))

(defn- backup-started! [_]
  (doseq [ms [3000 10000 30000]] ; the download starts once the zip is written
    (js/setTimeout #(fetch! status/backup-url {:force true}) ms)))

(defn- heat-setting
  "An action that stores a heatmap setting in localStorage and in the UI state."
  [k]
  (fn [[v]]
    (.setItem js/localStorage (name k) v)
    (swap! state assoc-in [:ui k] v)))

(defn- scroll-to! [[id]]
  (some-> (js/document.getElementById id)
          (.scrollIntoView #js {:behavior "smooth" :block "start"})))

(defn- back! [_]
  (if (> (.-length js/history) 1) (.back js/history) (set-hash! "#/")))

(defn- on-enter
  "An action for a key event: runs `f` on Enter only."
  [f]
  (fn [[ev :as args]] (when (= "Enter" (.-key ev)) (f args))))

(defn- square-ui! [f & args] (apply swap! state update-in [:ui :squares] f args))

(defn- search-places!
  "Looks the place up by name; the answer is listed under the search field."
  []
  (let [q (str/trim (or (get-in @state [:ui :squares :q]) ""))]
    (when-not (str/blank? q)
      (square-ui! assoc :busy true :error nil)
      (-> (js/fetch (str "/api/peaks/search?q=" (js/encodeURIComponent q)))
          (.then (fn [res] (-> (.json res)
                               (.then (fn [body]
                                        (if (.-ok res)
                                          (square-ui! assoc :results (js->clj body :keywordize-keys true) :busy false
                                                      :error (when (zero? (.-length body)) (t "Nothing found")))
                                          (square-ui! assoc :busy false :error (.-error body))))))))
          (.catch #(square-ui! assoc :busy false :error (.-message %)))))))

(defn- pick-place!
  "Flies the map to a search result, outlines it and selects the squares it covers that aren't asked for yet."
  [i]
  (let [{:keys [squares bbox outline too_big]} (nth (get-in @state [:ui :squares :results]) i)
        asked (into #{} (comp (filter :requested) (map :square)) (get-in @state [:data peaks/squares-url :squares]))]
    (square-ui! (fn [m] (assoc m :outline outline :fly bbox :fly-id (inc (or (:fly-id m) 0))
                               :selected (set (remove asked squares))
                               :error (when too_big (t "That place is too big: zoom in and tap squares instead.")))))))

(def actions
  "Action keyword -> function of the action's arguments. Replicant's event
  handlers dispatch these as data (see `dispatch`)."
  {:navigate          (fn [[h]] (set-hash! h))
   :set-param         set-param!
   :set-ui            (fn [[k v]] (swap! state assoc-in [:ui k] v))
   :set-in-ui         (fn [args] (swap! state assoc-in (into [:ui] (butlast args)) (last args)))
   :api               api-action!
   :send-workout      send-workout!
   :sync              (fn [_] (start-sync!))
   :sync-coros        (fn [_] (start-sync! "/api/coros/sync"))
   :ai-setting        ai-setting!
   :claude-limits     claude-limits!
   :claude-check      claude-check!
   :claude-key        claude-key!
   :map-key           map-key!
   :garmin-login      garmin-login!
   :garmin-login-key  (fn [[op ev]] (when (= "Enter" (.-key ev)) (garmin-login! [op])))
   :coros-login       coros-login!
   :coros-disconnect  coros-disconnect!
   :app-update        (fn [_] (app-request! :app-update "POST" "/api/app/update" nil))
   :data-dir          data-dir!
   :data-dir-cancel   (fn [_] (swap! state assoc-in [:ui :data-dir] {}))
   :autostart         autostart!
   :autostart-set     (fn [[v]] (autostart! [(= "on" v)]))
   :dismiss-notice    (fn [_] (-> (js/fetch "/api/app/notice" #js {:method "DELETE"})
                                  (.then #(fetch! settings/app-url {:force true}))))
   :restore           restore!
   :restore-file      restore-file!
   :quit              quit!
   :load-history      load-history!
   :backup-started    backup-started!
   :set-theme         (fn [[choice]] (set-theme! choice))
   :heat-show         (heat-setting :heat-show)
   :heat-pins         (heat-setting :heat-pins)
   :heat-peaks        (heat-setting :heat-peaks)
   :square-key        (on-enter (fn [_] (search-places!)))
   :square-search     (fn [_] (search-places!))
   :square-pick       (fn [[i]] (pick-place! i))
   :square-toggle     (fn [[k]] (swap! state update-in [:ui :squares :selected]
                                       #(let [s (or % #{})] (if (contains? s k) (disj s k) (conj s k)))))
   :square-clear      (fn [_] (swap! state assoc-in [:ui :squares :selected] #{}))
   :square-download   (fn [_] (let [sel (get-in @state [:ui :squares :selected])]
                                (swap! state assoc-in [:ui :squares :selected] #{})
                                (api! "POST" peaks/squares-url {:squares (vec sel)})))
   :show-peak         (fn [[lat lng]] (heat/show-at! lat lng) (set-hash! "#/map"))
   :heat-dim          (heat-setting :heat-dim)
   :heat-base         (heat-setting :heat-base)
   :heat-colors       (heat-setting :heat-colors)
   :cal-extend        (fn [[n]] (calendar/extend! state fetch! n))
   :cal-jump          (fn [[d]] (calendar/jump! state fetch! d))
   :cal-month         (fn [[m]] (calendar/jump! state fetch! (calendar/month-monday m)))
   :scroll-to         scroll-to!
   :scroll-top        (fn [_] (js/window.scrollTo 0 0))
   :back              back!
   :set-lang          (fn [[lang]] (set-lang! lang))
   :athlete-chat-send (fn [_] (send-athlete-chat!))
   :athlete-chat-key  (on-enter (fn [_] (send-athlete-chat!)))
   :athlete-summary   (fn [_] (summarise-athlete!))
   :athlete-upload    (fn [[ev]] (read-athlete-file! ev))})

(defn handle [[kind & args :as action]]
  (if-let [f (actions kind)]
    (f args)
    (js/console.warn "unknown action" (pr-str action))))

(defn- open-vital!
  "A click, or Enter/Space, on a dashboard card opens its history; a link
  inside the card (the \"?\") keeps its own job."
  [ev k]
  (when (and (not (some-> ev .-target (.closest "a")))
             (or (= "click" (.-type ev)) (#{"Enter" " "} (.-key ev))))
    (when (= "keydown" (.-type ev)) (.preventDefault ev))
    (handle [:set-param :vital k])))

(defn dispatch [event-data actions]
  (let [ev (:replicant/dom-event event-data)]
    (doseq [action actions]
      (case (first action)
        :open-vital     (open-vital! ev (second action))
        ;; only a click on the backdrop itself, not one inside the card
        :backdrop-close (when (identical? (.-target ev) (.-currentTarget ev)) (handle [:set-param :vital nil]))
        (handle (cond
                  (= :event/target-value (last action)) (conj (vec (butlast action)) (.. ev -target -value))
                  (= :athlete-chat-key (first action))  [:athlete-chat-key ev]
                  (= :square-key (first action))        [:square-key ev]
                  (= :garmin-login-key (first action))  [:garmin-login-key (second action) ev]
                  (= :restore-file (first action))      [:restore-file ev]
                  (= :athlete-upload (first action))    [:athlete-upload ev]
                  :else action))))))

;; ---------------------------------------------------------------------------
;; Shell

(def nav-items [[:today "#/" (i18n/tk "Today") :nav-home]
                [:plan "#/plan" (i18n/tk "Plan") :nav-plan]
                [:trends "#/trends" (i18n/tk "Trends") :nav-trends]
                [:map "#/map" (i18n/tk "Map") :nav-map]
                [:more "#/more" (i18n/tk "More") :nav-more]])

(defn- calendar-view? [{:keys [route]}]
  (and (= :plan (:page route)) (= "calendar" (plan/view-of route))))

(defn view [{:keys [route loading] :as s}]
  (let [page (:page route)
        active (case page (:activity :day :workout) :plan (:metrics :settings :athlete :peaks) :more page)]
    [:div.shell
     [:nav.side {:aria-label (t "Main")}
      [:div.brand [:span.logo] "Coach"]
      (for [[k href label icon] nav-items]
        [:a {:replicant/key k :href href :class (when (= k active) "active") :title (t label)
             :aria-current (when (= k active) "page")
             :on {:click [[:scroll-top]]}}
         (ui/nav-icon icon) [:span (t label)]])]
     [:main {:class (remove nil? [(when (and (seq loading) (not (and (calendar-view? s) (calendar/loaded? s))))
                                   "loading")
                                 (when (= :map page) "wide")
                                 (when (= :plan page) "plan")
                                 (when (calendar-view? s) "cal")
                                 (when (= :map page) "full")])}
      (status/view s)
      (case page
        :day        (day/view s (first (urls route)) (:date route))
        :activity   (activity/view s (first (urls route)))
        :workout    (workout/view s (first (urls route)))
        :map        (heatmap/view s)
        :plan       (plan/view s (urls route))
        :today      (today/view s)
        :trends     (trends/view s)
        :athlete    (athlete/view s)
        :more       (more/view s)
        :settings   (settings/view s (theme-choice))
        :metrics    (metrics/view s)
        :peaks      (peaks/view s))]]))

(defn render! []
  (r/render (js/document.getElementById "app") (view @state)))

(defn on-hash! []
  (swap! state assoc :route (parse-route (.-hash js/location)))
  (load-route!))

(defn ^:dev/after-load reload! [] (render!))

(defn init []
  (r/set-dispatch! dispatch)
  (reset! heat/action-handler handle) ; the map popups' buttons
  (add-watch state ::render (fn [_ _ _ _] (render!)))
  (.addEventListener js/window "hashchange" on-hash!)
  ;; Esc closes the dashboard's open card
  (.addEventListener js/window "keydown"
                     #(when (and (= "Escape" (.-key %)) (get-in @state [:route :params :vital]))
                        (handle [:set-param :vital nil])))
  (calendar/install! state fetch!)
  (apply-theme!)
  (set! (.. js/document -documentElement -lang) @i18n/lang)
  (load-lang!)
  (.addEventListener system-dark "change" #(do (apply-theme!) (render!)))
  (on-hash!)
  ;; the loopback token, for the download link and the self-update request
  (-> (js/fetch "/api/token")
      (.then (fn [res] (when (.-ok res) (.json res))))
      (.then (fn [body] (when body (reset! ui/token (:token (js->clj body :keywordize-keys true))))))
      (.catch (fn [_] nil)))
  (fetch! status/backup-url)
  (poll-sync!)
  (render!))
