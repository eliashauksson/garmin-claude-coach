(ns coach.app
  (:require [clojure.string :as str]
            [coach.ui :as ui]
            [coach.views.athlete :as athlete]
            [coach.views.activities :as activities]
            [coach.views.activity :as activity]
            [coach.views.calendar :as calendar]
            [coach.views.plan :as plan]
            [coach.views.load :as load]
            [coach.views.today :as today]
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
      "activities" (if-let [id (second segs)]
                     {:page :activity :id id}
                     {:page :activities :params params})
      "load"       {:page :load :params params}
      "calendar"   {:page :calendar :params params}
      "plan"       {:page :plan :params params}
      {:page :today})))

(defn route-hash [{:keys [page id params]}]
  (let [qs (str/join "&" (for [[k v] params :when (some? v)]
                           (str (name k) "=" (js/encodeURIComponent v))))]
    (str "#/" (case page :today "" :activity (str "activities/" id) (name page))
         (when (seq qs) (str "?" qs)))))

(def default-range {:activities "365" :load "180"})

(defn range-from [route]
  (let [r (get-in route [:params :range] (default-range (:page route)))]
    (if (= r "all") "2021-03-26" (ui/days-ago (js/parseInt r)))))

(defn urls [{:keys [page id] :as route}]
  (case page
    :today      ["/api/today"]
    :activities [(str "/api/activities?from=" (range-from route))]
    :activity   [(str "/api/activities/" id "?streams=true")]
    :calendar   (calendar/urls route)
    :plan       (plan/urls)
    :load       [(str "/api/load?from=" (range-from route))
                 (str "/api/volume?from=" (range-from route))]
    []))

;; ---------------------------------------------------------------------------
;; Data

(defn fetch! [url & [{:keys [force]}]]
  (when (or force (not (contains? (:data @state) url)))
    (when-not (contains? (:data @state) url) ; refetches keep the old render, no fade
      (swap! state update :loading conj url))
    (-> (js/fetch url)
        (.then (fn [res]
                 (if (.-ok res) (.json res) (throw (js/Error. (str "HTTP " (.-status res)))))))
        (.then (fn [body]
                 (let [data (js->clj body :keywordize-keys true)]
                   (swap! state #(-> % (assoc-in [:data url] data)
                                     (update :loading disj url) (update :errors dissoc url)))
                   ;; a proposal or the athlete summary is being generated: check again shortly
                   (when (or (= "running" (:status data)) (:summarising data))
                     (js/setTimeout #(fetch! url {:force true}) 4000)))))
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
      (.then #(sync-state! (js->clj % :keywordize-keys true)))
      (.catch (fn [_]))
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

(defn summarise-athlete! []
  (swap! state assoc-in [:ui :athlete :summarising?] true)
  (-> (post-json (str athlete/url "/summary") {})
      (.then #(swap! state (fn [s] (-> s (assoc-in [:data athlete/url] %)
                                       (assoc-in [:ui :athlete :summarising?] false)))))
      (.catch #(swap! state (fn [s] (-> s (assoc :flash (str "Error: " (.-message %)))
                                        (assoc-in [:ui :athlete :summarising?] false)))))))

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
      :athlete-chat-send (send-athlete-chat!)
      :athlete-chat-key  (when (= "Enter" (.-key (first args))) (send-athlete-chat!))
      :athlete-summary   (summarise-athlete!)
      (js/console.warn "unknown action" (pr-str action)))))

(defn dispatch [event-data actions]
  (let [ev (:replicant/dom-event event-data)]
    (doseq [action actions]
      (handle (cond
                (= :event/target-value (last action)) (conj (vec (butlast action)) (.. ev -target -value))
                (= :athlete-chat-key (first action))  [:athlete-chat-key ev]
                :else action)))))

;; ---------------------------------------------------------------------------
;; Shell

(def nav-items [[:today "#/" "Today"] [:plan "#/plan" "Plan"] [:calendar "#/calendar" "Calendar"] [:activities "#/activities" "Activities"]
                [:load "#/load" "Training load"]])

(defn view [{:keys [route loading] :as s}]
  (let [page (:page route)
        active (if (= page :activity) :activities page)]
    [:div.shell
     [:nav.side {:aria-label "Main"}
      [:div.brand [:span.logo] "Coach"]
      (for [[k href label] nav-items]
        [:a {:replicant/key k :href href :class (when (= k active) "active")} label])]
     [:main {:class (remove nil? [(when (seq loading) "loading") (when (= page :calendar) "wide")])}
      (case page
        :today      (today/view s)
        :activities (activities/view s (first (urls route)))
        :activity   (activity/view s (first (urls route)))
        :load       (let [[l v] (urls route)] (load/view s l v))
        :calendar   (calendar/view s (urls route))
        :plan       (plan/view s (urls route)))]]))

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
  (.addEventListener (js/matchMedia "(prefers-color-scheme: dark)") "change" render!)
  (on-hash!)
  (poll-sync!)
  (render!))
