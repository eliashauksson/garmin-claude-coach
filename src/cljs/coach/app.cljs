(ns coach.app
  (:require [clojure.string :as str]
            [coach.ui :as ui]
            [coach.views.activities :as activities]
            [coach.views.activity :as activity]
            [coach.views.calendar :as calendar]
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
    :load       [(str "/api/load?from=" (range-from route))
                 (str "/api/volume?from=" (range-from route))]
    []))

;; ---------------------------------------------------------------------------
;; Data

(defn fetch! [url & [{:keys [force]}]]
  (when (or force (not (contains? (:data @state) url)))
    (swap! state update :loading conj url)
    (-> (js/fetch url)
        (.then (fn [res]
                 (if (.-ok res) (.json res) (throw (js/Error. (str "HTTP " (.-status res)))))))
        (.then (fn [body]
                 (swap! state #(-> % (assoc-in [:data url] (js->clj body :keywordize-keys true))
                                   (update :loading disj url) (update :errors dissoc url)))))
        (.catch (fn [e]
                  (swap! state #(-> % (update :loading disj url)
                                    (assoc-in [:errors url] (.-message e)))))))))

(defn load-route! [& [opts]]
  (run! #(fetch! % opts) (urls (:route @state))))

(defonce sync-timer (atom nil))

(defn poll-sync! []
  (-> (js/fetch "/api/sync")
      (.then #(.json %))
      (.then (fn [body]
               (let [s (js->clj body :keywordize-keys true)]
                 (swap! state assoc :sync s)
                 (when-not (:running s)
                   (js/clearInterval @sync-timer)
                   (reset! sync-timer nil)
                   ;; new data: drop the cache and reload this page
                   (swap! state assoc :data {})
                   (load-route!)))))))

(defn start-sync! []
  (-> (js/fetch "/api/sync" #js {:method "POST"})
      (.then #(.json %))
      (.then (fn [body]
               (swap! state assoc :sync (js->clj body :keywordize-keys true))
               (when-not @sync-timer
                 (reset! sync-timer (js/setInterval poll-sync! 3000)))))))

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
      :sync         (start-sync!)
      (js/console.warn "unknown action" (pr-str action)))))

(defn dispatch [event-data actions]
  (let [ev (:replicant/dom-event event-data)]
    (doseq [action actions]
      (handle (if (= :event/target-value (last action))
                (conj (vec (butlast action)) (.. ev -target -value))
                action)))))

;; ---------------------------------------------------------------------------
;; Shell

(def nav-items [[:today "#/" "Today"] [:calendar "#/calendar" "Calendar"] [:activities "#/activities" "Activities"]
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
        :calendar   (calendar/view s (urls route)))]]))

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
  (render!))
