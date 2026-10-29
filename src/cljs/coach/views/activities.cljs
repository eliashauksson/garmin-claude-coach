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

(def columns
  [{:label "Date"       :value #(ui/date-short (:date %)) :class "date"}
   {:label "Name"       :value :name :class "name"}
   {:label "Sport"      :render #(ui/sport-chip (:sport %))}
   {:label "Time"       :value #(ui/hm (or (:moving_s %) (:duration_s %))) :align :r}
   {:label "km"         :value #(ui/km (:distance_m %)) :align :r}
   {:label "Pace/speed" :value speed-cell :align :r}
   {:label "Avg HR"     :value #(some-> (:avg_hr %) js/Math.round) :align :r}
   {:label "TSS"        :value #(some-> (:tss %) js/Math.round) :align :r :title :tss_method}
   {:label "IF"         :value #(ui/fixed (:intensity %) 2) :align :r}])

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
     (ui/card {:class "flush"}
              (if (seq rows)
                (ui/table columns rows {:key-fn :id
                                        :on-row (fn [a] [[:navigate (str "#/activities/" (:id a))]])})
                [:div.empty "No activities in this range."]))]))
