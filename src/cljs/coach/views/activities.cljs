(ns coach.views.activities
  (:require [coach.ui :as ui]))

(def ranges [["30" "30 days"] ["90" "90 days"] ["365" "1 year"] ["730" "2 years"] ["all" "All"]])

(defn- speed-cell [{:keys [sport distance_m moving_s duration_s]}]
  (let [secs (or moving_s duration_s)]
    (when (and distance_m secs (pos? secs) (pos? distance_m))
      (let [mps (/ distance_m secs)]
        (if (= :run (:id (ui/family sport)))
          (str (ui/pace mps) " /km")
          (str (ui/kmh mps) " km/h"))))))

(def columns
  [{:label "Date"     :value #(ui/date-short (:date %))}
   {:label "Name"     :value :name}
   {:label "Sport"    :value #(ui/sport-label (:sport %))}
   {:label "Time"     :value #(ui/hm (or (:moving_s %) (:duration_s %))) :align :r}
   {:label "km"       :value #(ui/km (:distance_m %)) :align :r}
   {:label "Pace/speed" :value speed-cell :align :r}
   {:label "Avg HR"   :value #(some-> (:avg_hr %) js/Math.round) :align :r}
   {:label "TSS"      :value #(some-> (:tss %) js/Math.round) :align :r}
   {:label "Method"   :value :tss_method}
   {:label "IF"       :value #(ui/fixed (:intensity %) 2) :align :r}])

(defn view [{:keys [data errors route]} url]
  (let [rows   (get data url)
        range  (get-in route [:params :range] "365")
        sport  (get-in route [:params :sport] "")
        sports (sort (distinct (keep :sport rows)))
        rows   (if (seq sport) (filter #(= sport (:sport %)) rows) rows)
        sum    #(reduce + 0 (keep % rows))]
    [:div
     [:div.page-head [:h1 "Activities"]]
     [:div.filters
      (ui/seg range ranges [:set-param :range])
      [:select {:on {:change [[:set-param :sport :event/target-value]]}}
       [:option {:value "" :selected (= "" sport)} "All sports"]
       (for [s sports]
         [:option {:replicant/key s :value s :selected (= s sport)} (ui/sport-label s)])]]
     (when-let [e (get errors url)] [:div.error (str "Could not load: " e)])
     [:div.stack
      [:div.grid.tiles
       (ui/tile "Activities" (count rows))
       (ui/tile "Moving time" (ui/hm (sum #(or (:moving_s %) (:duration_s %)))))
       (ui/tile "Distance" (ui/thousands (/ (sum :distance_m) 1000)) "km" nil)
       (ui/tile "Training stress" (ui/thousands (sum :tss)) "TSS" nil)]
      [:div.card
       (if (seq rows)
         (ui/table columns rows {:key-fn :id
                                 :on-row (fn [a] [[:navigate (str "#/activities/" (:id a))]])})
         [:div.empty "No activities in this range."])]]]))
