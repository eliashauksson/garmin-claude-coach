(ns coach.app
  (:require [clojure.string :as str]
            [coach.i18n :as i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.athlete :as athlete]
            [coach.views.activity :as activity]
            [coach.views.calendar :as calendar]
            [coach.views.costs :as costs]
            [coach.views.dashboard :as dashboard]
            [coach.views.settings :as settings]
            [coach.views.plan :as plan]
            [coach.views.status :as status]
            [coach.views.day :as day]
            [replicant.dom :as r]))

(defonce state (atom {:route {:page :dashboard} :data {} :loading #{} :errors {} :ui {}}))

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
                     {:page :dashboard :params {}})
      "calendar"   {:page :calendar :params params}
      "plan"       {:page :plan :params params}
      "day"        {:page :day :date (second segs)}
      "settings"   {:page :settings :params params}
      ;; old links: the costs now live in Settings
      "costs"      {:page :settings :params (assoc params :tab "claude")}
      ;; the dashboard is the start page
      {:page :dashboard :params params})))

(defn route-hash [{:keys [page id params]}]
  (let [qs (str/join "&" (for [[k v] params :when (some? v)]
                           (str (name k) "=" (js/encodeURIComponent v))))]
    (str "#/" (case page :dashboard "" :activity (str "activities/" id) (name page))
         (when (seq qs) (str "?" qs)))))

(defn urls [{:keys [page id] :as route}]
  (case page
    :day        [(day/url (:date route))]
    :activity   [(str "/api/activities/" id "?streams=true")]
    :calendar   (calendar/urls route)
    :plan       (plan/urls)
    :dashboard  (dashboard/urls route)
    :settings   (settings/urls route)
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
               (when (:server-down @state)
                 ;; back after a restart: reload what failed while it was down
                 (swap! state assoc :server-down false :errors {} :data {})
                 (load-route!)
                 (fetch! status/backup-url))
               (sync-state! (js->clj body :keywordize-keys true))))
      (.catch (fn [_] (swap! state assoc :server-down true)))
      (.finally #(reset! sync-timer (js/setTimeout poll-sync! (if (or (get-in @state [:sync :running])
                                                                      (get-in @state [:sync :translating]))
                                                                3000 30000))))))

(defn start-sync! []
  (-> (js/fetch "/api/sync" #js {:method "POST"})
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
                           {:error (t "{0} doesn't look like a text file. Upload Markdown (.md) or plain text (.txt)." (.-name f))}
                           (str/blank? text)  {:error (t "{0} is empty." (.-name f))}
                           (> (count text) max-upload-chars) {:error (t "{0} is too long (over 200,000 characters)." (.-name f))}
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

(defn theme [] (.. js/document -documentElement -dataset -theme))

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

(defn handle [action]
  (let [[kind & args] action]
    (case kind
      :navigate     (set! (.-hash js/location) (first args))
      :set-param    (let [[k v] args]
                      (set! (.-hash js/location)
                            (route-hash (assoc-in (:route @state) [:params k] v))))
      :set-ui       (swap! state assoc-in [:ui (first args)] (second args))
      :set-in-ui    (swap! state assoc-in (into [:ui] (butlast args)) (last args))
      :api          (let [[method url body] args]
                      (swap! state dissoc :flash)
                      (api! method url body))
      :sync         (start-sync!)
      :ai-setting   (let [[task field value] args
                          current (some #(when (= task (:id %)) %)
                                        (get-in @state [:data costs/settings-url :tasks]))]
                      (swap! state dissoc :flash)
                      (api! "PUT" (str costs/settings-url "/" task)
                            (assoc (select-keys current [:model :effort :delivery :scope]) field value)))
      :claude-check (do (swap! state assoc-in [:ui :claude-check] {:checking true})
                        (-> (js/fetch "/api/claude/check" #js {:method "POST"})
                            (.then #(.json %))
                            (.then (fn [body]
                                     (let [{:keys [ok error claude]} (js->clj body :keywordize-keys true)]
                                       (swap! state assoc-in [:ui :claude-check]
                                              (if ok nil {:checking false :result error
                                                          :still-out (boolean (:credits_out claude))}))
                                       (poll-sync!))))
                            (.catch #(swap! state assoc-in [:ui :claude-check] {:checking false :result (.-message %)}))))
      :claude-key   (let [op   (first args)
                          path [:ui :claude-key]
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
                          (.catch #(done {:ok false :error (.-message %)}))))
      :load-history (-> (js/fetch "/api/history" #js {:method "POST"})
                        (.then (fn [_] (poll-sync!))))
      :backup-started (doseq [ms [3000 10000 30000]] ; the download starts once the zip is written
                        (js/setTimeout #(fetch! status/backup-url {:force true}) ms))
      :set-theme    (set-theme! (first args))
      :back         (if (> (.-length js/history) 1) (.back js/history) (set! (.-hash js/location) "#/"))
      :set-lang     (set-lang! (first args))
      :athlete-chat-send (send-athlete-chat!)
      :athlete-chat-key  (when (= "Enter" (.-key (first args))) (send-athlete-chat!))
      :athlete-summary   (summarise-athlete!)
      :athlete-upload    (read-athlete-file! (first args))
      (js/console.warn "unknown action" (pr-str action)))))

(defn dispatch [event-data actions]
  (let [ev (:replicant/dom-event event-data)]
    (doseq [action actions]
      (handle (cond
                (= :event/target-value (last action)) (conj (vec (butlast action)) (.. ev -target -value))
                (= :athlete-chat-key (first action))  [:athlete-chat-key ev]
                (= :athlete-upload (first action))    [:athlete-upload ev]
                :else action)))))

;; ---------------------------------------------------------------------------
;; Shell

(def nav-items [[:dashboard "#/" (i18n/tk "Dashboard")] [:calendar "#/calendar" (i18n/tk "Calendar")]
                [:plan "#/plan" (i18n/tk "Plan")]
                [:settings "#/settings" (i18n/tk "Settings")]])

(defn view [{:keys [route loading] :as s}]
  (let [page (:page route)
        active (case page :activity :dashboard :day :calendar page)]
    [:div.shell
     [:nav.side {:aria-label (t "Main")}
      [:div.brand [:span.logo] "Coach"]
      (for [[k href label] nav-items]
        [:a {:replicant/key k :href href :class (when (= k active) "active")} (t label)])]
     [:main {:class (remove nil? [(when (seq loading) "loading") (when (= page :calendar) "wide")])}
      (status/view s)
      (case page
        :day        (day/view s (first (urls route)) (:date route))
        :activity   (activity/view s (first (urls route)))
        :calendar   (calendar/view s (urls route))
        :plan       (plan/view s (urls route))
        :dashboard  (dashboard/view s)
        :settings   (settings/view s (theme-choice)))]]))

(defn render! []
  (r/render (js/document.getElementById "app") (view @state)))

(defn on-hash! []
  (swap! state assoc :route (parse-route (.-hash js/location)))
  (load-route!))

(defn ^:dev/after-load reload! [] (render!))

(defn init []
  (r/set-dispatch! dispatch)
  (add-watch state ::render (fn [_ _ _ _] (render!)))
  (.addEventListener js/window "hashchange" on-hash!)
  (apply-theme!)
  (set! (.. js/document -documentElement -lang) @i18n/lang)
  (load-lang!)
  (.addEventListener system-dark "change" #(do (apply-theme!) (render!)))
  (on-hash!)
  (fetch! status/backup-url)
  (poll-sync!)
  (render!))
