(ns coach.views.heatmap
  "Map tab: a heatmap of every activity with GPS, filtered by time range and sport family."
  (:require [coach.heat :as heat]
            [coach.i18n :as i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.status :as status]))

(def url "/api/heatmap")

(def ranges
  [["all" (i18n/tk "All time")] ["year" (i18n/tk "This year")]
   ["365" (i18n/tk "12 months")] ["90" (i18n/tk "90 days")]])

(defn- from-date
  "First date included by `span`, nil for all time."
  [span]
  (let [today (ui/iso-today)]
    (case span
      "year" (str (subs today 0 4) "-01-01")
      "365"  (ui/add-days today -365)
      "90"   (ui/add-days today -90)
      nil)))

(defn palette
  "The chosen heatmap colours (remembered in this browser)."
  [ui]
  (or (:heat-colors ui) (.getItem js/localStorage "heat-colors") "blue"))

(def grounds
  [["dark" (i18n/tk "Dark map")] ["light" (i18n/tk "Light map")] ["satellite" (i18n/tk "Satellite")]])

(defn ground
  "What's under the heat: \"dark\", \"light\" or \"satellite\" (remembered in this browser)."
  [ui]
  (let [g (or (:heat-base ui) (.getItem js/localStorage "heat-base"))]
    (if (some #(= g (first %)) grounds) g "dark")))

(defn heat?
  "Whether the heat is shown (remembered in this browser)."
  [ui]
  (not= "off" (or (:heat-show ui) (.getItem js/localStorage "heat-show"))))

(defn dim
  "\"2d\" or \"3d\" (remembered in this browser)."
  [ui]
  (if (= "3d" (or (:heat-dim ui) (.getItem js/localStorage "heat-dim"))) "3d" "2d"))

(defn- palette-picker [current]
  [:div.seg.heat-colors
   (for [{:keys [id label]} heat/palettes]
     [:button {:replicant/key id :class (when (= id current) "on") :title (t label)
               :on {:click [[:heat-colors id]]}}
      [:span.swatch {:style {:background-image (heat/gradient id)}}] (t label)])])

(defn view [{:keys [data errors route sync ui]}]
  (let [items   (get data url)
        span    (get-in route [:params :range] "all")
        sport   (get-in route [:params :sport] "")
        from    (from-date span)
        in-time (cond->> items from (filter #(>= (:date %) from)))
        fam-of  #(name (:id (ui/family (:sport %))))
        present (set (map fam-of items))
        shown   (vec (cond->> in-time (seq sport) (filter #(= sport (fam-of %)))))]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title
       [:div.sub (if (= 1 (count shown)) (t "1 activity") (t "{0} activities" (count shown)))]
       [:h1 (t "Map")]]
      (status/sync-button sync)]
     [:div.map-filters
      (ui/seg span ranges [:set-param :range])
      (ui/seg sport (into [["" (i18n/tk "All sports")]]
                          (for [f ui/families :when (present (name (:id f)))]
                            [(name (:id f)) (:label f)]))
              [:set-param :sport])
      (ui/seg (if (heat? ui) "on" "off") [["on" (i18n/tk "Heat on")] ["off" (i18n/tk "Heat off")]] [:heat-show])
      (palette-picker (palette ui))
      (ui/seg (ground ui) grounds [:heat-base])
      (ui/seg (dim ui) [["2d" (i18n/tk "2D")] ["3d" (i18n/tk "3D")]] [:heat-dim])]
     (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
     (cond
       (nil? items)   nil
       (empty? items) [:div.card [:div.sub (t "No activities with GPS yet: they appear here after the next sync.")]]
       :else          (let [props {:items shown :version (str span "|" sport "|" (count shown))
                                   :palette (palette ui) :ground (ground ui) :heat? (heat? ui)}]
                        [:div.heat-base {:class (ground ui)}
                         (if (= "3d" (dim ui)) (heat/map-3d props) (heat/map-2d props))]))]))
