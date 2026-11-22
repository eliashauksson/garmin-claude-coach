(ns coach.views.peaks
  "The Peaks page (komoot-style peak bagging): the peaks the GPS tracks passed, and how small a summit may be
  (prominence) to count."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.square-map :as square-map]
            [coach.ui :as ui]))

(def url "/api/peaks/summary")
(def settings-url "/api/peaks/settings")
(def squares-url square-map/squares-url)
(def max-squares 100)

(def prominences
  "How far a summit must stand above its saddle to count (m); the dataset has nothing under 30 m."
  [[30 "30 m"] [50 "50 m"] [100 "100 m"]])

(defn- metres [x] (when (ui/num? x) (str (js/Math.round x) " m")))

(defn- visit-row [{:keys [id date activity_id activity_name source]}]
  [:div.peak-visit {:replicant/key id}
   (if activity_id
     [:a {:href (str "#/activities/" activity_id)}
      (ui/date-short date) (when-not (str/blank? activity_name) (str " · " activity_name))]
     [:span (ui/date-short date) " · " (t "Marked by hand")])
   [:button.link {:title (if (= "manual" source) (t "Remove this visit") (t "This wasn't a summit visit"))
                  :on {:click [[:api "DELETE" (str "/api/peak-bags/" id) nil]]}}
    (t "Remove")]])

(defn- peak-row [open-id {:keys [id name ele prominence visits last visit_list lat lon]}]
  [:details.peak {:replicant/key id :open (= (str id) open-id)}
   [:summary
    [:span.peak-name (if (str/blank? name) (t "Unnamed peak") name)]
    [:span.sub (str/join " · " (remove nil? [(metres ele)
                                            (when prominence (t "{0} m prominence" (js/Math.round prominence)))]))]
    [:span.sp]
    [:span.peak-count (if (= 1 visits) (t "1 visit") (t "{0} visits" visits))]
    [:span.sub (ui/date-short last)]]
   [:div.peak-body
    (for [v visit_list] (visit-row v))
    [:a.link {:href "#/map" :on {:click [[:show-peak lat lon]]}} (t "Show on the map")]]])

(defn- legend []
  [:div.square-legend
   (for [[cls label] [["free" (t "Not downloaded")] ["selected" (t "Selected")] ["queued" (t "Waiting")]
                      ["names" (t "Adding names")] ["ready" (t "Ready")]
                      ["failed" (t "Failed, trying again")]]]
     [:span {:replicant/key cls} [:i {:class ["sq" cls]}] label])])

(defn- result-row [i {:keys [name detail kind squares too_big]}]
  [:button.area-result {:replicant/key i :class (when too_big "too-big")
                        :on {:click [[:square-pick i]]}}
   [:b name] [:span.sub (str/join " · " (remove str/blank? [kind (when-not (= detail name) detail)]))]
   [:span.sp]
   [:span.sub (if too_big (t "too big") (t "{0} squares" (count squares)))]])

(defn- download-card
  [{:keys [squares downloaded loading peaks]} {:keys [q results selected busy error outline fly fly-id]}]
  (let [n (count selected)]
    [:div {:replicant/key "download"}
     (ui/card {}
              [:div.lab (t "Download peaks")]
              [:div.sub (t "The world is cut into squares of one degree. Squares your activities touch (and those of the other athletes here) are downloaded automatically. To get more, tap squares to select them, or search for a place, then download. Everybody on this server gets the peaks of a downloaded square. Searching sends your text to OpenStreetMap's place search.")]
              [:div.area-search
               [:input {:type "search" :value (or q "") :placeholder (t "Find a place: a country, a mountain range…")
                        :on {:input [[:set-in-ui :squares :q :event/target-value]]
                             :keydown [[:square-key]]}}]
               [:button.btn-ghost {:disabled (or busy (str/blank? q)) :on {:click [[:square-search]]}}
                (if busy (t "Searching…") (t "Search"))]]
              (when error [:div.error error])
              (when (seq results) (into [:div.area-results] (map-indexed result-row results)))
              (square-map/view {:squares squares :selected (or selected #{}) :outline outline :fly fly :fly-id fly-id})
              (legend)
              [:div.square-bar
               [:span (cond (pos? n) (t "{0} squares selected" n)
                            :else (t "{0} squares downloaded · {1} peaks · {2} loading" downloaded peaks loading))]
               [:span.sp]
               (when (pos? n) [:button.link {:on {:click [[:square-clear]]}} (t "Clear")])
               [:button.btn-ghost {:disabled (or (zero? n) (> n max-squares))
                                   :title (when (> n max-squares) (t "Too many: at most {0} at once" max-squares))
                                   :on {:click [[:square-download]]}}
                (t "Download")]])]))

(defn view [{:keys [data errors route] ui-state :ui}]
  (let [d       (get data url)
        sq      (get data squares-url)
        open-id (get-in route [:params :peak])]
    [:div {:style {:display "contents"}}
     (ui/back-link)
     [:div.page-head [:div.title [:h1 (t "Peaks")]
                      [:div.sub (t "Summits your GPS tracks passed: within 75 m of the top, on a hike, run or ride.")]]]
     (when-let [e (get errors url)] [:div.error (t "Could not load: {0}" e)])
     (when d
       (list
        [:div.row {:replicant/key "tiles"}
         (ui/tile {:label (t "Peaks bagged") :value (str (:bagged d)) :tint "green" :size "md" :info "peaks-bagged"})
         (ui/tile {:label (t "This year") :value (str (:this_year d)) :size "md"})
         (ui/tile {:label (t "Summit visits") :value (str (:visits d)) :size "md"})
         (ui/tile {:label (t "Highest") :value (some-> (:highest d) :ele js/Math.round str) :unit "m" :size "md"
                   :sub (get-in d [:highest :name])})]
        [:div {:replicant/key "prominence"}
         (ui/card {}
                  [:div.lab (t "Smallest summit that counts") (ui/info "prominence")]
                  (ui/seg (:min_prominence d) prominences [:peak-prominence])
                  [:div.sub (t "Prominence is how far a summit stands above the saddle to higher ground. The dataset lists every summit of at least 30 m, the usual rule for a summit of its own; raise the limit to keep only bigger ones.")]
                  (when (pos? (:loading d))
                    [:div.sub (t "Adding names to {0} more squares in the background; the peaks are already on the map." (:loading d))]))]
        (when sq (download-card sq (:squares ui-state)))
        [:div {:replicant/key "peaks"}
         (ui/card {}
                  [:div.lab (t "Your peaks")]
                  (if (seq (:peaks d))
                    (into [:div.peak-list] (map #(peak-row open-id %) (:peaks d)))
                    [:div.sub (t "No peaks yet. They appear here after a sync, once an activity passes a summit.")]))]))]))
