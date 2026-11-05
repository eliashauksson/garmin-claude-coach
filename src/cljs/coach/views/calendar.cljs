(ns coach.views.calendar
  "Week-per-row calendar, the start page: a summary of the week on the left,
  then Monday to Sunday with each day's health data, activities and planned
  sessions (dashed). A day's health block or date opens the day page."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.status :as status]))

(def weeks-before 4)
(def weeks-after 1)

(defn window
  "[first-day last-day mondays] shown for `route`; ?week=YYYY-MM-DD anchors it."
  [route]
  (let [anchor  (ui/monday (get-in route [:params :week] (ui/iso-today)))
        mondays (mapv #(ui/add-days anchor (* 7 %)) (range (- weeks-before) (inc weeks-after)))]
    [(first mondays) (ui/add-days (peek mondays) 6) mondays]))

(defn- min-date [a b] (if (neg? (compare a b)) a b))

(defn urls [route]
  (let [[from to] (window route)]
    [(str "/api/activities?from=" from "&to=" to)
     (str "/api/load?from=" from "&to=" (min-date to (ui/iso-today)))
     (str "/api/plan?from=" from "&to=" to)
     (str "/api/wellness?from=" from "&to=" (min-date to (ui/iso-today)))]))

;; ---------------------------------------------------------------------------

(defn- day-names [] (mapv ui/weekdays [1 2 3 4 5 6 0]))

(defn- day-label [date]
  (let [d (ui/parse-date date)]
    (if (= 1 (.getDate d))
      (ui/day-month d)
      (str (.getDate d)))))

(defn- activity-item [{:keys [id sport name distance_m tss tss_method] :as a}]
  (let [fam (ui/family sport)]
    [:a.cal-item {:replicant/key id
                  :href (str "#/activities/" id)
                  :title (str name (when tss_method (str " · " tss_method)))
                  :style {:border-left-color (str "var(" (:color fam) ")")}}
     [:div.cal-item-name name]
     [:div.cal-item-meta
      (str/join " · " (remove nil? [(ui/hm (ui/active-secs a))
                                    (when (and distance_m (pos? distance_m)) (str (ui/km distance_m) " km"))]))]
     (when tss [:div.cal-item-tss (str (js/Math.round tss) " TSS")])]))

(def health-metrics
  "Shown in a day's health block, with the day page's icons and colours."
  [[:readiness :bolt "--green" (tk "Readiness") str]
   [:sleep_score :moon "--purple" (tk "Sleep score") str]
   [:rhr :heart "--red" (tk "Resting heart rate") #(str % " bpm")]
   [:hrv_avg :pulse "--blue" (tk "HRV") #(str (js/Math.round %) " ms")]])

(defn- mini-icon [icon color]
  (into [:svg {:aria-hidden "true" :width 11 :height 11 :viewBox "0 0 24 24" :fill "none"
               :stroke (str "var(" color ")") :stroke-width 2.6 :stroke-linecap "round" :stroke-linejoin "round"}]
        (ui/icon-paths icon)))

(defn- health-item
  "A small block with the day's key health numbers; opens the day page."
  [date w]
  (let [shown (for [[k icon color label fmt] health-metrics :let [v (get w k)] :when (some? v)]
                [k icon color label (fmt v)])]
    (when (or (seq shown) (:steps w))
      [:a.cal-health {:replicant/key (str "h" date) :href (str "#/day/" date)
                      :title (str/join "\n" (concat (for [[_ _ _ label v] shown] (str (t label) ": " v))
                                                    (when (:steps w) [(t "Steps: {0}" (ui/thousands (:steps w)))])
                                                    [(t "Open the day")]))}
       (if (seq shown)
         (for [[k icon color _ v] shown]
           [:span {:replicant/key (name k)} (mini-icon icon color) v])
         [:span (mini-icon :footprints "--green") (ui/thousands (:steps w))])])))

(defn- planned-item [{:keys [id session_id title slot duration_min distance_km status]}]
  ;; missed sessions keep the dashed card but get a red outline
  (let [gym? (re-find #"^GYM" session_id)]
    [:a.cal-item.planned {:replicant/key (str "p" id) :href (str "#/workouts/" id) :class status
                          :title (str slot " · " title)
                          :style {:border-left-color (if gym? "var(--fam-other)" "var(--fam-run)")}}
     [:div.cal-item-name (ui/session-names session_id session_id)]
     [:div.cal-item-meta (str/join " · " (remove nil? [slot
                                                     (when (pos? duration_min) (str duration_min " min"))
                                                     (when (pos? distance_km) (str distance_km " km"))]))]]))

(defn- week-summary [monday acts load-by-date today]
  (let [sunday   (ui/add-days monday 6)
        end      (min-date sunday today)
        future?  (pos? (compare monday today))
        sum      #(reduce + 0 (keep % acts))
        by-fam   (->> acts
                      (group-by #(:id (ui/family (:sport %))))
                      (map (fn [[fid as]] [fid (reduce + 0 (keep ui/active-secs as))])))
        fams     (keep (fn [f] (when-let [s (some #(when (= (:id f) (first %)) (second %)) by-fam)] [f s]))
                       ui/families)
        l        (load-by-date end)
        d1 (ui/parse-date monday) d2 (ui/parse-date sunday)]
    [:div.cal-summary
     [:div.cal-range (str (ui/day-month d1) " – " (ui/day-month d2))]
     (if (seq acts)
       (list
        [:div.cal-total {:replicant/key "t"} (ui/hm (sum ui/active-secs))]
        [:div.sub {:replicant/key "s"}
         (str/join " · " (remove nil? [(if (= 1 (count acts)) (t "1 activity") (t "{0} activities" (count acts)))
                                       (let [km (/ (sum :distance_m) 1000)] (when (pos? km) (str (js/Math.round km) " km")))
                                       (let [tss (sum :tss)] (when (pos? tss) (str (js/Math.round tss) " TSS")))]))]
        [:div.cal-fams {:replicant/key "f"}
         (for [[f s] fams]
           [:span {:replicant/key (name (:id f))}
            [:span.dot {:style {:background (str "var(" (:color f) ")")}}]
            (str (t (:label f)) " " (ui/hm s))])])
       [:div.sub (if future? (t "Nothing planned yet") (t "Rest week"))])
     (when (and l (not future?))
       [:div.cal-load (t "Fitness {0} · Form {1}" (js/Math.round (:ctl l)) (js/Math.round (:tsb l)))])]))

(defn- week-row [monday acts-by-date plan-by-date load-by-date well-by-date today]
  (let [days (mapv #(ui/add-days monday %) (range 7))]
    [:div.cal-week {:replicant/key monday}
     (week-summary monday (mapcat acts-by-date days) load-by-date today)
     (for [[i d] (map-indexed vector days)]
       [:div.cal-day {:replicant/key d
                      :class (remove nil? [(when (= d today) "today") (when (pos? (compare d today)) "future")])}
        (if (pos? (compare d today))
          [:div.cal-date [:span.cal-dow ((day-names) i)] (day-label d)]
          [:a.cal-date {:href (str "#/day/" d) :title (t "Open the day")} [:span.cal-dow ((day-names) i)] (day-label d)])
        (health-item d (well-by-date d))
        (map activity-item (acts-by-date d))
        (map planned-item (remove #(= "done" (:status %)) (plan-by-date d)))])]))

(defn view [{:keys [data errors route sync]} [acts-url load-url plan-url well-url]]
  (let [[from to mondays] (window route)
        acts    (get data acts-url)
        today   (ui/iso-today)
        by-date (group-by :date (sort-by :start_time_local acts))
        load-by (into {} (map (juxt :date identity)) (get data load-url))
        plan-by (group-by :date (:workouts (get data plan-url)))
        well-by (into {} (map (juxt :date identity)) (get data well-url))
        this-monday (ui/monday today)
        anchor  (ui/monday (get-in route [:params :week] today))
        d1 (ui/parse-date from) d2 (ui/parse-date to)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title
       [:div.sub (str (ui/date-short from) " – " (ui/date-short to))]
       [:h1 (t "Calendar")]]
      (status/auth-pill (:auth sync))
      (status/sync-button sync)
      [:div.seg
       [:button {:on {:click [[:set-param :week (ui/add-days anchor -28)]]}} (t "← Earlier")]
       [:button {:class (when (= anchor this-monday) "on")
                 :on {:click [[:set-param :week nil]]}} (t "This week")]
       [:button {:on {:click [[:set-param :week (ui/add-days anchor 28)]]}} (t "Later →")]]]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (t "Could not load: {0}" e)])) [acts-url load-url well-url])
     [:div.card.cal
      [:div.cal-head [:div (t "Week")] (for [d (day-names)] [:div {:replicant/key d} d])]
      (for [m mondays] (week-row m by-date plan-by load-by well-by today))]
     [:div.sub (t "The small block at the top of a day is its health data (readiness, sleep, resting heart rate, HRV): click it, or the date, for the whole day. Planned sessions have a dashed outline; once done they're replaced by the recorded activity.")]]))
