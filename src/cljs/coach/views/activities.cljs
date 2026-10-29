(ns coach.views.activities
  (:require [clojure.string :as str]
            [coach.ui :as ui]))

(def ranges [["30" "30 days"] ["90" "90 days"] ["365" "1 year"] ["730" "2 years"] ["all" "All"]])

(defn- speed-cell [{:keys [sport distance_m moving_s duration_s]}]
  (let [secs (or moving_s duration_s)]
    (when (and distance_m secs (pos? secs) (pos? distance_m))
      (let [mps (/ distance_m secs)]
        (if (= :run (:id (ui/family sport)))
          (str (ui/pace mps) " /km")
          (str (ui/kmh mps) " km/h"))))))

(defn- route-svg
  "The simplified track as an SVG line, projected with cos(latitude) so the
  shape isn't stretched, fitted into a 160x110 box."
  [route family-id]
  (let [w 160 h 110 pad 10
        lats (map first route) lons (map second route)
        k    (js/Math.cos (* (/ (apply + lats) (count lats)) (/ js/Math.PI 180)))
        xs   (map #(* % k) lons)
        [x0 x1 y0 y1] [(apply min xs) (apply max xs) (apply min lats) (apply max lats)]
        span (max (- x1 x0) (- y1 y0) 1e-9)
        scale (/ (min (- w (* 2 pad)) (- h (* 2 pad))) span)
        ox   (/ (- w (* scale (- x1 x0))) 2)
        oy   (/ (- h (* scale (- y1 y0))) 2)
        pt   (fn [x lat] [(+ ox (* scale (- x x0))) (- h (+ oy (* scale (- lat y0))))])
        pts  (map pt xs lats)
        color (str "var(--fam-" (name family-id) ")")]
    [:svg.route {:viewBox (str "0 0 " w " " h) :role "img" :aria-label "Route"}
     [:polyline {:points (apply str (interpose " " (map (fn [[x y]] (str (.toFixed x 1) "," (.toFixed y 1))) pts)))
                 :fill "none" :stroke color :stroke-width 2.5 :stroke-linejoin "round" :stroke-linecap "round"}]
     (let [[x y] (first pts)] [:circle {:cx x :cy y :r 4 :fill "var(--green)" :stroke "var(--surface)" :stroke-width 2}])
     (let [[x y] (last pts)] [:circle {:cx x :cy y :r 4 :fill "var(--red)" :stroke "var(--surface)" :stroke-width 2}])]))

(defn- stat [label value unit]
  [:div.stat [:div.stat-label label] [:div.stat-value (or value "—") (when (and value unit) [:small unit])]])

(defn- activity-card [{:keys [id sport name date start_time_local distance_m moving_s duration_s
                              elev_gain_m avg_hr tss tss_method intensity route] :as a}]
  (let [fam  (ui/family sport)
        secs (or moving_s duration_s)
        mps  (when (and distance_m secs (pos? secs) (pos? distance_m)) (/ distance_m secs))
        run? (= :run (:id fam))]
    [:section.card.activity {:replicant/key id
                             :on {:click [[:navigate (str "#/activities/" id)]]}}
     [:div.activity-head
      (ui/sport-chip sport)
      [:span.sub (str (ui/date-long date) (some->> (ui/time-of start_time_local) (str " · ")))]]
     [:a.activity-title {:href (str "#/activities/" id)} name]
     [:div.activity-body
      [:div.stats
       (stat "Distance" (when (and distance_m (pos? distance_m)) (ui/km distance_m)) "km")
       (stat "Time" (ui/hm secs) nil)
       (if run?
         (stat "Pace" (ui/pace mps) "/km")
         (stat "Speed" (ui/kmh mps) "km/h"))
       (stat "Elevation" (some-> elev_gain_m js/Math.round) "m")
       (stat "Avg HR" (some-> avg_hr js/Math.round) "bpm")
       [:div.stat {:title (str/join " · " (remove nil? [tss_method (some->> (ui/fixed intensity 2) (str "IF "))]))}
        [:div.stat-label "Training stress"]
        [:div.stat-value (or (some-> tss js/Math.round) "—") (when tss [:small "TSS"])]]]
      (when (seq route)
        [:div.route-box {:class (clojure.core/name (:id fam))} (route-svg route (:id fam))])]]))

(defn- month-label [iso]
  (let [d (ui/parse-date iso)]
    (str (["January" "February" "March" "April" "May" "June" "July" "August" "September"
           "October" "November" "December"] (.getMonth d)) " " (.getFullYear d))))

(defn- feed [rows]
  (for [acts (partition-by #(subs (:date %) 0 7) rows)]
    [:div.month {:replicant/key (subs (:date (first acts)) 0 7)}
     [:h2.month-title (month-label (:date (first acts)))
      [:span.sub (str (count acts) (if (= 1 (count acts)) " activity" " activities"))]]
     [:div.feed (map activity-card acts)]]))

(defn- breakdown
  "Breakdown such as 5 runs · 6 hikes · 1 ride: families in fixed order, zeros left out."
  [rows]
  (let [counts (frequencies (map #(:id (ui/family (:sport %))) rows))]
    (str/join " · " (for [{:keys [id plural]} ui/families
                          :let [n (get counts id 0)] :when (pos? n)]
                      (str n " " (if (= 1 n) (ui/singular id) plural))))))

(defn view [{:keys [data errors route]} url]
  (let [rows   (get data url)
        range  (get-in route [:params :range] "365")
        sport  (get-in route [:params :sport] "")
        sports (sort (distinct (keep :sport rows)))
        rows   (if (seq sport) (filter #(= sport (:sport %)) rows) rows)
        sum    #(reduce + 0 (keep % rows))
        secs   (sum #(or (:moving_s %) (:duration_s %)))
        mins   (js/Math.round (/ secs 60))]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:h1.title "Activities"]
      (ui/seg range ranges [:set-param :range])
      [:label.visually-hidden {:for "sport"} "Sport"]
      [:select#sport {:on {:change [[:set-param :sport :event/target-value]]}}
       [:option {:value "" :selected (= "" sport)} "All sports"]
       (for [s sports]
         [:option {:replicant/key s :value s :selected (= s sport)} (ui/sport-label s)])]]
     (when-let [e (get errors url)] [:div.error (str "Could not load: " e)])
     [:div.row
      (ui/card {:tint "blue" :flex "1 1 200px"}
               [:div.lab "Activities"] [:div.big.md (count rows)]
               (when (seq rows) [:div.sub (breakdown rows)]))
      (ui/card {:tint "purple" :flex "1 1 200px"}
               [:div.lab "Moving time"]
               [:div.big.md (quot mins 60) [:small "h"] " " (mod mins 60) [:small "m"]])
      (ui/tile {:label "Distance" :tint "green" :size "md" :unit "km"
                :value (ui/thousands (/ (sum :distance_m) 1000))})
      (ui/tile {:label "Training stress" :tint "amber" :size "md" :unit "TSS"
                :value (ui/thousands (sum :tss))})]
     (if (seq rows)
       (feed rows)
       (ui/card {} [:div.empty "No activities in this range."]))]))
