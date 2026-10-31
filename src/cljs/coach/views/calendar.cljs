(ns coach.views.calendar
  "Week-per-row calendar: a summary of the week on the left, then Monday to
  Sunday with each day's activities. Planned workouts (Phase 5) will sit in
  the same day cells with a dashed outline."
  (:require [clojure.string :as str]
            [coach.ui :as ui]))

(def weeks-before 4)
(def weeks-after 1)

(defn- iso [^js d] (.slice (.toISOString d) 0 10))

(defn add-days [date n]
  (let [d (ui/parse-date date)] (.setDate d (+ (.getDate d) n)) (iso d)))

(defn monday
  "The Monday on or before `date`."
  [date]
  (add-days date (- (mod (+ (.getDay (ui/parse-date date)) 6) 7))))

(defn window
  "[first-day last-day mondays] shown for `route`; ?week=YYYY-MM-DD anchors it."
  [route]
  (let [anchor  (monday (get-in route [:params :week] (ui/iso-today)))
        mondays (mapv #(add-days anchor (* 7 %)) (range (- weeks-before) (inc weeks-after)))]
    [(first mondays) (add-days (peek mondays) 6) mondays]))

(defn- min-date [a b] (if (neg? (compare a b)) a b))

(defn urls [route]
  (let [[from to] (window route)]
    [(str "/api/activities?from=" from "&to=" to)
     (str "/api/load?from=" from "&to=" (min-date to (ui/iso-today)))
     (str "/api/plan?from=" from "&to=" to)]))

;; ---------------------------------------------------------------------------

(def day-names ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"])

(defn- secs [a] (or (:moving_s a) (:duration_s a)))

(defn- day-label [date]
  (let [d (ui/parse-date date)]
    (if (= 1 (.getDate d))
      (str (.getDate d) " " (ui/months (.getMonth d)))
      (str (.getDate d)))))

(defn- activity-item [{:keys [id sport name distance_m tss tss_method] :as a}]
  (let [fam (ui/family sport)]
    [:a.cal-item {:replicant/key id
                  :href (str "#/activities/" id)
                  :title (str name (when tss_method (str " · " tss_method)))
                  :style {:border-left-color (str "var(" (:color fam) ")")}}
     [:div.cal-item-name name]
     [:div.cal-item-meta
      (str/join " · " (remove nil? [(ui/hm (secs a))
                                    (when (and distance_m (pos? distance_m)) (str (ui/km distance_m) " km"))]))]
     (when tss [:div.cal-item-tss (str (js/Math.round tss) " TSS")])]))

(defn- planned-item [{:keys [id session_id title slot duration_min distance_km status]}]
  ;; missed sessions keep the dashed card but get a red outline
  (let [gym? (re-find #"^(GYM|CALF)" session_id)]
    [:a.cal-item.planned {:replicant/key (str "p" id) :href "#/plan" :class status
                          :title (str slot " · " title)
                          :style {:border-left-color (if gym? "var(--fam-other)" "var(--fam-run)")}}
     [:div.cal-item-name (ui/session-names session_id session_id)]
     [:div.cal-item-meta (str/join " · " (remove nil? [slot
                                                     (when (pos? duration_min) (str duration_min " min"))
                                                     (when (pos? distance_km) (str distance_km " km"))]))]]))

(defn- week-summary [monday acts load-by-date today]
  (let [sunday   (add-days monday 6)
        end      (min-date sunday today)
        future?  (pos? (compare monday today))
        sum      #(reduce + 0 (keep % acts))
        by-fam   (->> acts
                      (group-by #(:id (ui/family (:sport %))))
                      (map (fn [[fid as]] [fid (reduce + 0 (keep secs as))])))
        fams     (keep (fn [f] (when-let [s (some #(when (= (:id f) (first %)) (second %)) by-fam)] [f s]))
                       ui/families)
        l        (load-by-date end)
        d1 (ui/parse-date monday) d2 (ui/parse-date sunday)]
    [:div.cal-summary
     [:div.cal-range (str (.getDate d1) " " (ui/months (.getMonth d1)) " – "
                          (.getDate d2) " " (ui/months (.getMonth d2)))]
     (if (seq acts)
       (list
        [:div.cal-total {:replicant/key "t"} (ui/hm (sum secs))]
        [:div.sub {:replicant/key "s"}
         (str/join " · " (remove nil? [(str (count acts) (if (= 1 (count acts)) " activity" " activities"))
                                       (let [km (/ (sum :distance_m) 1000)] (when (pos? km) (str (js/Math.round km) " km")))
                                       (let [t (sum :tss)] (when (pos? t) (str (js/Math.round t) " TSS")))]))]
        [:div.cal-fams {:replicant/key "f"}
         (for [[f s] fams]
           [:span {:replicant/key (name (:id f))}
            [:span.dot {:style {:background (str "var(" (:color f) ")")}}]
            (str (:label f) " " (ui/hm s))])])
       [:div.sub (if future? "Nothing planned yet" "Rest week")])
     (when (and l (not future?))
       [:div.cal-load (str "Fitness " (js/Math.round (:ctl l)) " · Form " (js/Math.round (:tsb l)))])]))

(defn- week-row [monday acts-by-date plan-by-date load-by-date today]
  (let [days (mapv #(add-days monday %) (range 7))]
    [:div.cal-week {:replicant/key monday}
     (week-summary monday (mapcat acts-by-date days) load-by-date today)
     (for [[i d] (map-indexed vector days)]
       [:div.cal-day {:replicant/key d
                      :class (remove nil? [(when (= d today) "today") (when (pos? (compare d today)) "future")])}
        [:div.cal-date [:span.cal-dow (day-names i)] (day-label d)]
        (map activity-item (acts-by-date d))
        (map planned-item (remove #(= "done" (:status %)) (plan-by-date d)))])]))

(defn view [{:keys [data errors route]} [acts-url load-url plan-url]]
  (let [[from to mondays] (window route)
        acts    (get data acts-url)
        today   (ui/iso-today)
        by-date (group-by :date (sort-by :start_time_local acts))
        load-by (into {} (map (juxt :date identity)) (get data load-url))
        plan-by (group-by :date (:workouts (get data plan-url)))
        this-monday (monday today)
        anchor  (monday (get-in route [:params :week] today))
        d1 (ui/parse-date from) d2 (ui/parse-date to)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title
       [:div.sub (str (.getDate d1) " " (ui/months (.getMonth d1)) " " (.getFullYear d1) " – "
                      (.getDate d2) " " (ui/months (.getMonth d2)) " " (.getFullYear d2))]
       [:h1 "Calendar"]]
      [:div.seg
       [:button {:on {:click [[:set-param :week (add-days anchor -28)]]}} "← Earlier"]
       [:button {:class (when (= anchor this-monday) "on")
                 :on {:click [[:set-param :week nil]]}} "This week"]
       [:button {:on {:click [[:set-param :week (add-days anchor 28)]]}} "Later →"]]]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (str "Could not load: " e)])) [acts-url load-url])
     [:div.card.cal
      [:div.cal-head [:div "Week"] (for [d day-names] [:div {:replicant/key d} d])]
      (for [m mondays] (week-row m by-date plan-by load-by today))]
     [:div.sub "Planned sessions have a dashed outline; once done they're replaced by the recorded activity. Plan them on the Plan page."]]))
