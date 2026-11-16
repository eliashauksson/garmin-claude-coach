(ns coach.views.calendar
  "Week-per-row calendar, the start page: a summary of the week on the left,
  then Monday to Sunday with each day's health data, activities and planned
  sessions (dashed). A day's health block or date opens the day page."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.status :as status]))

(def chunk-days
  "Data is fetched in blocks of 8 weeks on a fixed grid, so scrolling back hits the cache."
  56)
(def grid-origin "1970-01-05") ; a Monday

(defn- min-date [a b] (if (neg? (compare a b)) a b))

(defn- day-index [iso]
  (js/Math.round (/ (- (.getTime (ui/parse-date iso)) (.getTime (ui/parse-date grid-origin))) 86400000)))

(defn chunk-start [iso]
  (ui/add-days grid-origin (* chunk-days (js/Math.floor (/ (day-index iso) chunk-days)))))

(defn- chunk-end-monday [iso]
  (ui/add-days (chunk-start iso) (- chunk-days 7)))

(defn- anchor [route] (ui/monday (get-in route [:params :week] (ui/iso-today))))

(defn- around [monday]
  [(chunk-start (ui/add-days monday -28)) (chunk-end-monday (ui/add-days monday 28))])

(defn cal-range
  "[first-monday last-monday] of the weeks shown: what scrolling and the month menu made of it, else
  4 weeks either side of ?week=YYYY-MM-DD (today)."
  [route ui]
  (let [cal (:cal ui)]
    (if (and (:from cal) (= (get-in route [:params :week]) (:seed cal)))
      [(:from cal) (:to cal)]
      (around (anchor route)))))

(defn- chunk-starts [from to]
  (take-while #(not (pos? (compare % to))) (iterate #(ui/add-days % chunk-days) (chunk-start from))))

(defn- chunk-urls [start]
  (let [end    (ui/add-days start (dec chunk-days))
        today  (ui/iso-today)
        past?  (not (pos? (compare start today)))]
    (cond-> {:acts (str "/api/activities?from=" start "&to=" end)
             :plan (str "/api/plan?from=" start "&to=" end)}
      past? (assoc :load (str "/api/load?from=" start "&to=" (min-date end today))
                   :well (str "/api/wellness?from=" start "&to=" (min-date end today))))))

(defn urls [route ui]
  (let [[from to] (cal-range route ui)]
    (mapcat (comp vals chunk-urls) (chunk-starts from (ui/add-days to 6)))))

(defn loaded?
  "Every block of the shown weeks is in (or failed)."
  [{:keys [data errors route ui]}]
  (every? #(or (contains? data %) (contains? errors %)) (urls route ui)))

;; ---------------------------------------------------------------------------
;; Endless scrolling: the weeks shown grow by a block when the page nears its top or bottom

(defonce pending (atom nil)) ; a Monday to scroll to once it's on the page
(defonce checker (atom nil))
(defonce extending (atom false))

(defn- seed-cal [route from to ui] (assoc (:cal ui) :seed (get-in route [:params :week]) :from from :to to))

(defn extend!
  "Adds the block of weeks before (:up) or after (:down) the ones shown, once its data is in. Rows above the
  view keep their place on screen."
  [state fetch! dir]
  (when-not @extending
    (reset! extending true)
    (let [{:keys [route ui]} @state
          [from to] (cal-range route ui)
          [nf nt]   (if (= dir :up)
                      [(chunk-start (ui/add-days from -7)) to]
                      [from (chunk-end-monday (ui/add-days to 7))])]
      (-> (js/Promise.all (clj->js (keep fetch! (urls route {:cal (seed-cal route nf nt ui)}))))
          (.then (fn [_]
                   (let [root (.-documentElement js/document)
                         h0   (.-scrollHeight root)]
                     (swap! state assoc-in [:ui :cal] (seed-cal route nf nt (:ui @state)))
                     (when (= dir :up)
                       (js/window.scrollBy 0 (- (.-scrollHeight root) h0))))))
          (.finally (fn []
                      (reset! extending false)
                      (some-> @checker js/requestAnimationFrame)))))))

(defn month-monday [ym] (ui/monday (str ym "-01")))

(defn jump!
  "Scrolls to the week of `monday`, loading that part of the calendar first when it isn't shown."
  [state fetch! monday]
  (let [{:keys [route ui]} @state
        [from to] (cal-range route ui)
        month     (subs (ui/add-days monday 3) 0 7)]
    (reset! pending monday)
    (if (and (not (neg? (compare monday from))) (not (pos? (compare monday to))))
      (do (swap! state assoc-in [:ui :cal] (assoc (seed-cal route from to ui) :month month))
          (when-let [el (.querySelector js/document (str "[data-monday='" monday "']"))]
            (reset! pending nil)
            (.scrollIntoView el #js {:block "start"})))
      (let [[nf nt] (around monday)]
        (swap! state assoc-in [:ui :cal] (assoc (seed-cal route nf nt ui) :month month))
        (run! fetch! (urls route (:ui @state)))))))

(defn- check!
  "Scroll handler: which month is at the top, and is it time for more weeks?"
  [state fetch!]
  (when-let [head (.querySelector js/document ".cal-sticky")]
    (let [top   (.-bottom (.getBoundingClientRect head))
          week  (some #(when (> (.-bottom (.getBoundingClientRect %)) (+ top 1)) %)
                      (array-seq (.querySelectorAll js/document ".cal-week")))
          month (some-> week (.getAttribute "data-monday") (ui/add-days 3) (subs 0 7))
          root  (.-documentElement js/document)
          y     (.-scrollY js/window)]
      (when (and month (not= month (get-in @state [:ui :cal :month])))
        (swap! state assoc-in [:ui :cal :month] month))
      (cond
        (< y 600) (extend! state fetch! :up)
        (< (- (.-scrollHeight root) y (.-innerHeight js/window)) 600) (extend! state fetch! :down)))))

(defonce ticking (atom false))

(defn install!
  "Once, from the app's init: the calendar reacts to the page scrolling."
  [state fetch!]
  (reset! checker #(check! state fetch!))
  (.addEventListener js/window "scroll"
                     (fn []
                       (when-not @ticking
                         (reset! ticking true)
                         (js/requestAnimationFrame #(do (reset! ticking false) (@checker)))))
                     #js {:passive true}))

;; ---------------------------------------------------------------------------

(defn- day-names [] (mapv ui/weekdays [1 2 3 4 5 6 0]))

(defn- day-label [date]
  (let [d (ui/parse-date date)]
    (if (= 1 (.getDate d))
      (ui/day-month d)
      (str (.getDate d)))))

(defn- activity-item
  "Green when it matched its planned session (or none was planned), yellow when it didn't."
  [by-act a]
  (ui/activity-card a (:deviation (by-act (:id a))) {:class "cal-item"}))

(def health-metrics
  "Shown in a day's metrics card, with the day page's icons and colours."
  [[:readiness :bolt "--green" (tk "Readiness") str]
   [:sleep_score :moon "--purple" (tk "Sleep score") str]
   [:rhr :heart "--red" (tk "Resting heart rate") #(str % " bpm")]
   [:hrv_avg :pulse "--blue" (tk "HRV") #(str (js/Math.round %) " ms")]])

(defn- mini-icon [icon color]
  (into [:svg {:aria-hidden "true" :width 11 :height 11 :viewBox "0 0 24 24" :fill "none"
               :stroke (str "var(" color ")") :stroke-width 2.6 :stroke-linecap "round" :stroke-linejoin "round"}]
        (ui/icon-paths icon)))

(defn- health-item
  "A small card with the day's key health numbers; opens the day page."
  [date w]
  (let [shown (for [[k icon color label fmt] health-metrics :let [v (get w k)] :when (some? v)]
                [k icon color label (fmt v)])]
    (when (or (seq shown) (:steps w))
      [:a.cal-health {:replicant/key (str "h" date) :href (str "#/day/" date)
                      :title (str/join "\n" (concat (for [[_ _ _ label v] shown] (str (t label) ": " v))
                                                    (when (:steps w) [(t "Steps: {0}" (ui/thousands (:steps w)))])
                                                    [(t "Open the day")]))}
       [:div.cal-health-title (t "Metrics")]
       (for [[k icon color _ v] shown]
         [:span {:replicant/key (name k)} (mini-icon icon color) v])
       (when (:steps w)
         [:span {:replicant/key "steps"} (mini-icon :footprints "--green") (ui/thousands (:steps w))])])))

(defn- planned-item [w]
  (ui/planned-card w {:class "cal-item"}))

(defn- week-summary [monday acts planned load-by-date today]
  (let [sunday  (ui/add-days monday 6)
        end     (min-date sunday today)
        future? (pos? (compare monday today))
        sum     #(reduce + 0 (keep % acts))
        todo    (remove #(= "done" (:status %)) planned)
        plan-s  (* 60 (reduce + 0 (keep :duration_min planned)))
        by-fam  (->> acts (group-by #(:id (ui/family (:sport %))))
                     (map (fn [[fid as]] [fid (reduce + 0 (keep ui/active-secs as))])))
        fams    (keep (fn [f] (when-let [s (some #(when (= (:id f) (first %)) (second %)) by-fam)] [f s]))
                      ui/families)
        total   (sum ui/active-secs)
        l       (load-by-date end)
        km      (/ (sum :distance_m) 1000)
        tss     (sum :tss)
        row     (fn [label v & [color pct]]
                  [:div.cal-sum-row {:replicant/key label}
                   [:span label] [:b v]
                   (when color [:i {:style {:background (str "var(" color ")") :width (str (or pct 0) "%")}}])])]
    [:div.cal-summary
     [:div.cal-range (str (ui/day-month (ui/parse-date monday)) " – " (ui/day-month (ui/parse-date sunday)))]
     (when (and l (not future?))
       [:div.cal-fff
        [:div [:b {:style {:color "var(--blue)"}} (js/Math.round (:ctl l))] [:small (t "Fitness")]]
        [:div [:b {:style {:color "var(--pink)"}} (js/Math.round (:atl l))] [:small (t "Fatigue")]]
        [:div [:b {:style {:color "var(--amber)"}} (js/Math.round (:tsb l))] [:small (t "Form")]]])
     (if (and (empty? acts) (empty? todo))
       [:div.sub (if future? (t "Nothing planned yet") (t "Rest week"))]
       [:div.cal-sums
        (row (t "Total duration") (str (ui/hm total) (when (and (pos? plan-s) (not= plan-s total))
                                                       (str " / " (ui/hm plan-s)))))
        (when (pos? tss) (row "TSS" (js/Math.round tss)))
        (for [[f sec] fams]
          (row (t (:label f)) (ui/hm sec) (:color f) (js/Math.round (* 100 (/ sec (max total 1))))))
        (when (pos? km) (row (t "Distance") (str (js/Math.round km) " km")))])]))

(defn- week-row [monday acts-by-date plan-by-date load-by-date well-by-date today by-act]
  (let [days (mapv #(ui/add-days monday %) (range 7))]
    [:div.cal-week {:replicant/key monday :data-monday monday}
     (for [[i d] (map-indexed vector days)]
       [:div.cal-day {:replicant/key d
                      :class (remove nil? [(when (= d today) "today") (when (pos? (compare d today)) "future")
                                    (when (str/ends-with? d "-01") "month-start")])}
        (if (pos? (compare d today))
          [:div.cal-date [:span.cal-dow ((day-names) i)] (day-label d)]
          [:a.cal-date {:href (str "#/day/" d) :title (t "Open the day")} [:span.cal-dow ((day-names) i)]
           (day-label d)])
        (health-item d (well-by-date d))
        (map #(activity-item by-act %) (acts-by-date d))
        (map planned-item (remove #(= "done" (:status %)) (plan-by-date d)))])
     (week-summary monday (mapcat acts-by-date days) (mapcat plan-by-date days) load-by-date today)]))

(defn- scroll-hook
  "Brings the week asked for (a jump, else the one of ?week= / today when the calendar opens) to the top."
  [anchor-monday]
  (fn [{:replicant/keys [life-cycle node]}]
    (let [mount? (= life-cycle :replicant.life-cycle/mount)
          target (or @pending (when mount? anchor-monday))]
      (when (and target (not= life-cycle :replicant.life-cycle/unmount))
        (when-let [el (.querySelector node (str "[data-monday='" target "']"))]
          (reset! pending nil)
          (.scrollIntoView el #js {:block "start"})
          (some-> @checker js/requestAnimationFrame))))))

(defn- month-options
  "[\"2026-11\" \"November 2026\"] for three years back to a year ahead (and the month on screen)."
  [shown]
  (let [d0 (ui/parse-date (ui/iso-today))
        ym (fn [d] (str (.getFullYear d) "-" (when (< (.getMonth d) 9) "0") (inc (.getMonth d))))
        label (fn [y m] (str (ui/long-months m) " " y))
        all (for [k (range -36 13)
                  :let [d (js/Date. (.getFullYear d0) (+ (.getMonth d0) k) 1 12)]]
              [(ym d) (label (.getFullYear d) (.getMonth d))])]
    (if (some #(= shown (first %)) all)
      all
      (let [d (ui/parse-date (str shown "-01"))]
        (sort-by first (conj (vec all) [shown (label (.getFullYear d) (.getMonth d))]))))))

(defn view [{:keys [data errors route ui sync] :as s}]
  (let [[from to] (cal-range route ui)
        chunks  (map chunk-urls (chunk-starts from (ui/add-days to 6)))
        got     (fn [k] (apply concat (map #(get data (k %)) chunks)))
        acts    (got :acts)
        today   (ui/iso-today)
        by-date (group-by :date (sort-by :start_time_local acts))
        load-by (into {} (map (juxt :date identity)) (got :load))
        plan-by (group-by :date (mapcat :workouts (map #(get data (:plan %)) chunks)))
        well-by (into {} (map (juxt :date identity)) (got :well))
        by-act  (into {} (keep #(when (:activity_id %) [(:activity_id %) %])) (mapcat val plan-by))
        mondays (take-while #(not (pos? (compare % to))) (iterate #(ui/add-days % 7) from))
        shown   (or (get-in ui [:cal :month]) (subs (ui/add-days (anchor route) 3) 0 7))]
    [:div {:style {:display "contents"}}
     [:div.cal-sticky
      [:div.page-head
       [:div.title [:h1 (t "Calendar")]]
       (status/auth-pill (:auth sync))
       (status/sync-button sync)
       [:select {:aria-label (t "Month") :on {:change [[:cal-month :event/target-value]]}}
        (for [[ym label] (month-options shown)]
          [:option {:replicant/key ym :value ym :selected (= ym shown)} label])]
       [:div.seg
        [:button {:on {:click [[:cal-jump (ui/monday today)]]}} (t "Today")]]]
      [:div.cal-head (for [d (day-names)] [:div {:replicant/key d} d]) [:div (t "Summary")]]]
     (some (fn [u] (when-let [e (get errors u)] [:div.error (t "Could not load: {0}" e)]))
           (mapcat (juxt :acts :load :well) chunks))
     (when (loaded? s)
       [:div.card.cal {:replicant/on-render (scroll-hook (anchor route))}
        (for [m mondays] (week-row m by-date plan-by load-by well-by today by-act))])
     [:div.sub (t "The small block at the top of a day is its health data (readiness, sleep, resting heart rate, HRV): click it, or the date, for the whole day. Planned sessions have a dashed outline; once done they're replaced by the recorded activity.")]]))
