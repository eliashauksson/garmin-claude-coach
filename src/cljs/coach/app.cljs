(ns coach.app
  (:require [clojure.string :as str]
            [coach.ui :as ui]
            [coach.views.athlete :as athlete]
            [coach.views.activities :as activities]
            [coach.views.activity :as activity]
            [coach.views.calendar :as calendar]
            [coach.views.costs :as costs]
            [coach.views.plan :as plan]
            [coach.views.load :as load]
            [coach.views.status :as status]
            [coach.views.day :as day]
            [replicant.dom :as r]))

(defonce state (atom {:route {:page :calendar} :data {} :loading #{} :errors {} :ui {}}))

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
      "activities" (if-let [id (second segs)]
                     {:page :activity :id id}
                     {:page :activities :params params})
      "load"       {:page :load :params params}
      "calendar"   {:page :calendar :params params}
      "plan"       {:page :plan :params params}
      "day"        {:page :day :date (second segs)}
      "costs"      {:page :costs :params params}
      ;; the calendar is the start page (the old Today page is now a day page)
      {:page :calendar :params params})))

(defn route-hash [{:keys [page id params]}]
  (let [qs (str/join "&" (for [[k v] params :when (some? v)]
                           (str (name k) "=" (js/encodeURIComponent v))))]
    (str "#/" (case page :calendar "calendar" :activity (str "activities/" id) (name page))
         (when (seq qs) (str "?" qs)))))

(def default-range {:activities "365" :load "180"})

(defn range-from [route]
  (let [r (get-in route [:params :range] (default-range (:page route)))]
    (if (= r "all") "2021-03-26" (ui/days-ago (js/parseInt r)))))

(defn urls [{:keys [page id] :as route}]
  (case page
    :day        [(day/url (:date route))]
    :activities [(str "/api/activities?from=" (range-from route))]
    :activity   [(str "/api/activities/" id "?streams=true")]
    :calendar   (calendar/urls route)
    :plan       (plan/urls)
    :costs      [(costs/url route) costs/settings-url]
    :load       [(str "/api/load?from=" (range-from route))
                 (str "/api/volume?from=" (range-from route))]
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
                     (.then #(swap! state assoc :flash (str "Error: " (.-error %))))))))
      (.catch #(swap! state assoc :flash (str "Error: " (.-message %))))))

(defonce sync-timer (atom nil))

(defn- sync-state! [s]
  (let [{was :running done :finished_at} (:sync @state)]
    (swap! state assoc :sync s)
    ;; a sync ended since the last poll (also one shorter than the interval)
    (when (and (not (:running s))
               (or was (and done (not= done (:finished_at s)))))
      ;; new data: drop the cache and reload this page
      (swap! state assoc :data {})
      (load-route!))))

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
      (.finally #(reset! sync-timer (js/setTimeout poll-sync! (if (get-in @state [:sync :running]) 3000 30000))))))

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
                             :error (str "Error: " (.-message e))))))))))

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
                           {:error (str (.-name f) " doesn't look like a text file. Upload Markdown (.md) or plain text (.txt).")}
                           (str/blank? text)  {:error (str (.-name f) " is empty.")}
                           (> (count text) max-upload-chars) {:error (str (.-name f) " is too long (over 200,000 characters).")}
                           :else {:name (.-name f) :content text}))))
          (.catch #(put! {:error (str "Could not read the file: " (.-message %))}))))))

(defn summarise-athlete! []
  (swap! state assoc-in [:ui :athlete :summarising?] true)
  (-> (post-json (str athlete/url "/summary") {})
      (.then #(swap! state (fn [s] (-> s (assoc-in [:data athlete/url] %)
                                       (assoc-in [:ui :athlete :summarising?] false)))))
      (.catch #(swap! state (fn [s] (-> s (assoc :flash (str "Error: " (.-message %)))
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

(defn toggle-theme! []
  (.setItem js/localStorage "theme" (if (= "dark" (theme)) "light" "dark"))
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
                            (assoc (select-keys current [:model :effort :delivery]) field value)))
      :claude-check (do (swap! state assoc-in [:ui :claude-check] {:checking true})
                        (-> (js/fetch "/api/claude/check" #js {:method "POST"})
                            (.then #(.json %))
                            (.then (fn [body]
                                     (let [{:keys [ok error]} (js->clj body :keywordize-keys true)]
                                       (swap! state assoc-in [:ui :claude-check]
                                              (if ok nil {:checking false :result error}))
                                       (poll-sync!))))
                            (.catch #(swap! state assoc-in [:ui :claude-check] {:checking false :result (.-message %)}))))
      :backup-started (doseq [ms [3000 10000 30000]] ; the download starts once the zip is written
                        (js/setTimeout #(fetch! status/backup-url {:force true}) ms))
      :toggle-theme (toggle-theme!)
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

(def nav-items [[:calendar "#/calendar" "Calendar"] [:plan "#/plan" "Plan"] [:activities "#/activities" "Activities"]
                [:load "#/load" "Training load"] [:costs "#/costs" "API costs"]])

(defn view [{:keys [route loading] :as s}]
  (let [page (:page route)
        active (case page :activity :activities :day :calendar page)]
    [:div.shell
     [:nav.side {:aria-label "Main"}
      [:div.brand [:span.logo] "Coach"]
      (for [[k href label] nav-items]
        [:a {:replicant/key k :href href :class (when (= k active) "active")} label])
      (let [dark? (= "dark" (theme))]
        [:button.theme-toggle {:on {:click [[:toggle-theme]]}
                               :title (if dark? "Switch to light theme" "Switch to dark theme")}
         (into [:svg {:aria-hidden "true" :width 16 :height 16 :viewBox "0 0 24 24" :fill "none"
                      :stroke "currentColor" :stroke-width 2.2 :stroke-linecap "round" :stroke-linejoin "round"}]
               (ui/icon-paths (if dark? :sun :moon)))
         (if dark? "Light theme" "Dark theme")])
      (status/backup-link s)]
     [:main {:class (remove nil? [(when (seq loading) "loading") (when (= page :calendar) "wide")])}
      (status/view s)
      (case page
        :day        (day/view s (first (urls route)) (:date route))
        :activities (activities/view s (first (urls route)))
        :activity   (activity/view s (first (urls route)))
        :load       (let [[l v] (urls route)] (load/view s l v))
        :calendar   (calendar/view s (urls route))
        :plan       (plan/view s (urls route))
        :costs      (costs/view s (first (urls route))))]]))

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
  (.addEventListener system-dark "change" #(do (apply-theme!) (render!)))
  (on-hash!)
  (fetch! status/backup-url)
  (poll-sync!)
  (render!))
