(ns coach.views.heatmap
  "Map tab: a heatmap of every activity with GPS, filtered by time range and sport family."
  (:require [clojure.string :as str]
            [coach.heat :as heat]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.settings :as settings]
            [coach.views.status :as status]))

(def url "/api/heatmap")
(def peaks-url "/api/peaks")

(def ranges
  [["all" (tk "All time")] ["year" (tk "This year")]
   ["365" (tk "12 months")] ["90" (tk "90 days")]])

(defn- from-date
  "First date included by `span`, nil for all time."
  [span]
  (let [today (ui/iso-today)]
    (case span
      "year" (str (subs today 0 4) "-01-01")
      "365"  (ui/add-days today -365)
      "90"   (ui/add-days today -90)
      nil)))

(def defaults
  "The map settings every time the Map tab is opened (time range and sport are URL params: all, all)."
  {:heat-show "on" :heat-pins "on" :heat-peaks "off"
   :heat-colors "fire" :heat-base "satellite" :heat-dim "2d"})

(defn- setting
  "A map setting: what was picked since the Map tab was opened, else its default."
  [ui k]
  (get ui k (defaults k)))

(defn palette
  "The chosen heatmap colours."
  [ui]
  (setting ui :heat-colors))

(def map-keys-url settings/map-keys-url)

(defn- tracestrack-key [data] (not-empty (:tracestrack (get data map-keys-url))))

(defn grounds
  "The map choices: Tracestrack Topo only with its key (Settings)."
  [tracestrack-key]
  (let [available (set (heat/available-grounds tracestrack-key))]
    (into [["dark" (tk "Dark map")] ["light" (tk "Light map")] ["satellite" (tk "Satellite")]]
          (for [[id label] [["topo" (tk "Tracestrack Topo")] ["opentopomap" (tk "OpenTopoMap")]
                            ["cyclosm" (tk "CyclOSM")] ["swisstopo" (tk "swisstopo")]
                            ["hot" (tk "Humanitarian")]]
                :when (available id)]
            [id label]))))

(defn ground
  "What's under the heat: one of `grounds` (the default when the picked one is gone, e.g. the
  Tracestrack key was removed)."
  [ui tracestrack-key]
  (let [g (setting ui :heat-base)]
    (if (some #(= g (first %)) (grounds tracestrack-key)) g (:heat-base defaults))))

(defn- own-colours?
  "Whether the ground keeps its own colours (not OpenStreetMap dark/light or satellite)."
  [g]
  (contains? (set heat/extra-grounds) g))

(defn heat?
  "Whether the heat is shown."
  [ui]
  (= "on" (setting ui :heat-show)))

(defn pins?
  "Whether the activity pins are shown."
  [ui]
  (= "on" (setting ui :heat-pins)))

(defn peaks?
  "Whether the peaks are shown."
  [ui]
  (= "on" (setting ui :heat-peaks)))

(defn dim
  "\"2d\" or \"3d\"."
  [ui]
  (setting ui :heat-dim))

(defn- palette-picker [current]
  [:div.seg.heat-colors
   (for [{:keys [id label]} heat/palettes]
     [:button {:replicant/key id :class (when (= id current) "on") :title (t label)
               :on {:click [[:heat-colors id]]}}
      [:span.swatch {:style {:background-image (heat/gradient id)}}] (t label)])])

(defn- row [label & controls]
  [:div.map-row [:div.map-row-lab (t label)] (into [:div.map-row-ctl] controls)])

(defn- overlay
  "Top-left over the map: one pill with the title, the number of activities and the filter summary;
  it opens the settings."
  [{:keys [empty? n span sport present sync ui tracestrack-key]}]
  (let [summary (str/join " · " (remove nil? [(t (some #(when (= span (first %)) (second %)) ranges))
                                              (when (seq sport)
                                                (t (:label (some #(when (= sport (name (:id %))) %) ui/families))))]))]
    [:div.map-overlay
     (when empty?
       [:div.map-note (t "No activities with GPS yet: they appear here after the next sync.")])
     [:details.map-filters
      [:summary {:title (t "Filters")}
       [:b (t "Map")] [:span.sub (if (= 1 n) (t "1 activity") (t "{0} activities" n))]
       [:span.sub summary]]
      [:div.map-panel
       (row (tk "Time range") (ui/seg span ranges [:set-param :range] {:soft? true}))
       (row (tk "Sport") (ui/seg sport (into [["" (tk "All sports")]]
                                             (for [f ui/families :when (present (name (:id f)))]
                                               [(name (:id f)) (:label f)]))
                                 [:set-param :sport] {:soft? true}))
       (row (tk "Heat") (ui/seg (if (heat? ui) "on" "off") [["on" (tk "Heat on")] ["off" (tk "Heat off")]]
                                [:heat-show] {:soft? true}))
       (row (tk "Pins") (ui/seg (if (pins? ui) "on" "off") [["on" (tk "Pins on")] ["off" (tk "Pins off")]]
                                [:heat-pins] {:soft? true}))
       (row (tk "Peaks") (ui/seg (if (peaks? ui) "on" "off") [["on" (tk "Peaks on")] ["off" (tk "Peaks off")]]
                                 [:heat-peaks] {:soft? true}))
       (row (tk "Colours") (palette-picker (palette ui)))
       (row (tk "Map") (ui/seg (ground ui tracestrack-key) (grounds tracestrack-key) [:heat-base] {:soft? true}))
       (row (tk "View") (ui/seg (dim ui) [["2d" (tk "2D")] ["3d" (tk "3D")]] [:heat-dim] {:soft? true}))
       [:div.map-row (status/sync-button sync)]]]]))

(defn view [{:keys [data errors route sync ui]}]
  (let [items   (get data url)
        span    (get-in route [:params :range] "all")
        sport   (get-in route [:params :sport] "")
        from    (from-date span)
        in-time (cond->> items from (filter #(>= (:date %) from)))
        fam-of  #(name (:id (ui/family (:sport %))))
        present (set (map fam-of items))
        shown   (vec (cond->> in-time (seq sport) (filter #(= sport (fam-of %)))))
        tkey    (tracestrack-key data)
        g       (ground ui tkey)]
    [:div {:style {:display "contents"}}
     (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
     (cond
       (nil? items)   nil
       :else          (let [props {:items shown :version (str span "|" sport "|" (count shown))
                                   :palette (palette ui) :ground g :heat? (heat? ui) :tracestrack-key tkey
                                   :pins? (pins? ui) :peaks (when (peaks? ui) (get data peaks-url))}]
                        [:div.heat-base {:class [g (when (own-colours? g) "own-colours")]}
                         (if (= "3d" (dim ui)) (heat/map-3d props) (heat/map-2d props))
                         (overlay {:empty? (empty? items) :n (count shown) :span span :sport sport :present present
                                  :sync sync :ui ui :tracestrack-key tkey})]))]))
