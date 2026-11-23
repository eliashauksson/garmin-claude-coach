(ns coach.summits
  "Pure. Peak bagging: which tiles an activity needs, which peaks a GPS stream bagged, which peaks to show,
  how the names of other sources (swisstopo, OpenStreetMap) are matched to the summits of the prominence dataset."
  (:require [clojure.string :as str]))

(def radius-m "A track bags a peak when it passes within this distance of it." 75.0)
(def below-m
  "…and, when the stream has altitude, got within this far below the summit (not a tunnel or road beneath it)."
  80.0)
(def ^:private pad
  "Degrees around a peak where a fix can belong to a line passing it: a segment is at most `gap-m` long."
  0.006)
(def tile-deg "Peaks are fetched per tile of this many degrees." 0.25)
(def gap-m "A jump longer than this between two fixes is not a line the athlete followed." 500.0)
(def version "Bump when detection changes: activities checked with an older one are checked again." 1)

(def ^:private skipped-sports #"(?i)virtual|indoor|treadmill|trainer")

(defn eligible?
  "False for virtual and indoor sports: Zwift and Rouvy record real coordinates, but nobody climbed."
  [sport]
  (not (and sport (re-find skipped-sports (name sport)))))

;; ---------------------------------------------------------------------------
;; Tiles

(defn- tile-index [deg] (long (Math/floor (/ (double deg) tile-deg))))

(defn tile-key [lat lon] (str (tile-index lat) ":" (tile-index lon)))

(defn tiles-for-track
  "The tile keys a track (decoded segments of [lat lon]) passes through, 100 m border included.
  Long straight pieces are sampled so a tile between two distant vertices is not missed."
  [segments]
  (let [border 0.001
        step 0.02
        spots (fn [[la lo]] (for [dla [(- border) border] dlo [(- border) border]] [(+ la dla) (+ lo dlo)]))]
    (into #{}
          (comp (mapcat (fn [seg]
                          (concat seg
                                  (mapcat (fn [[[a b] [c d]]]
                                            (let [far (max (Math/abs (- c a)) (Math/abs (- d b)))
                                                  n (long (Math/ceil (/ far step)))]
                                              (for [i (range 1 n)
                                                    :let [f (/ (double i) n)]]
                                                [(+ a (* f (- c a))) (+ b (* f (- d b)))])))
                                          (partition 2 1 seg)))))
                (mapcat spots)
                (map (fn [[la lo]] (tile-key la lo))))
          segments)))

;; ---------------------------------------------------------------------------
;; Squares: the world cut into 1° squares, each downloaded on its own (like an offline map region)

(def max-squares "One download, or one search result, may cover this many squares (about 10° × 10°)." 100)

(defn square-key
  "\"46,7\": the square whose south-west corner is 46 N, 7 E (whole degrees, rounded down)."
  [lat lon]
  (str (long (Math/floor (double lat))) "," (long (Math/floor (double lon)))))

(defn parse-square
  "[south west] of a square key, nil when it isn't one on the globe (latitude -85…84, longitude -180…179)."
  [k]
  (when-let [[_ la lo] (and (string? k) (re-matches #"(-?\d{1,2}),(-?\d{1,3})" k))]
    (let [la (Long/parseLong la) lo (Long/parseLong lo)]
      (when (and (<= -85 la 84) (<= -180 lo 179)) [la lo]))))

(defn valid-square? [k] (boolean (parse-square k)))

(defn square-bbox
  "[south west north east] of a square key."
  [k]
  (let [[la lo] (parse-square k)] [la lo (inc la) (inc lo)]))

(defn squares-in
  "The square keys covering [south west north east]. A box whose west is right of its east crosses the
  date line. Kept on the globe."
  [[s w n e]]
  (let [rows (range (max -85 (long (Math/floor (double s)))) (min 85 (long (Math/ceil (double n)))))
        cols (fn [from to]
               (range (max -180 (long (Math/floor (double from)))) (min 180 (long (Math/ceil (double to))))))
        lons (if (> w e) (concat (cols w 180) (cols -180 e)) (cols w e))]
    (vec (for [la rows lo lons] (str la "," lo)))))

(defn search-result
  "One Nominatim hit (jsonv2, with polygon_geojson) as {:name :kind :bbox :outline :squares :too_big}.
  Nominatim gives `boundingbox` as [south north west east] strings."
  [{:keys [name display_name type category boundingbox geojson]}]
  (let [[s n w e] (map #(Double/parseDouble (str %)) boundingbox)
        squares (squares-in [s w n e])]
    {:name (or (not-empty name) display_name)
     :detail display_name
     :kind (str/replace (str (or type category)) "_" " ")
     :bbox [s w n e]
     :outline geojson
     :too_big (> (count squares) max-squares)
     :squares (if (> (count squares) max-squares) [] squares)}))

(defn tiles-in
  "The tile keys of a tile-aligned bbox."
  [[s w n e]]
  (let [idx tile-index
        end #(long (Math/ceil (/ (double %) tile-deg)))]
    (set (for [la (range (idx s) (end n)) lo (range (idx w) (end e))] (str la ":" lo)))))

(defn tiles-of-square
  "The 16 tile keys of a square."
  [k]
  (tiles-in (square-bbox k)))

;; ---------------------------------------------------------------------------
;; Detection

(defn- project
  "Metres east/north of [lat0 lon0]."
  [lat0 lon0 lat lon]
  (let [k (/ Math/PI 180.0) r 6371000.0]
    [(* r k (- lon lon0) (Math/cos (* k lat0))) (* r k (- lat lat0))]))

(defn- seg-dist
  "Distance from the origin to the segment a-b, and the fraction along it."
  [[ax ay] [bx by]]
  (let [dx (- bx ax) dy (- by ay)
        l2 (+ (* dx dx) (* dy dy))
        u (if (zero? l2) 0.0 (max 0.0 (min 1.0 (/ (- (+ (* ax dx) (* ay dy))) l2))))
        px (+ ax (* u dx)) py (+ ay (* u dy))]
    [(Math/sqrt (+ (* px px) (* py py))) u]))

(defn- near-peak
  "Closest approach of the stream to one peak: {:dist_m :time_s :alt} over the points within the
  radius, nil when the track never comes that close. `alt` is the highest altitude of those points.
  `idxs`: the ascending indices of the fixes that can be near the peak (a superset of those within `pad`)."
  [{:keys [lat lon alt t]} idxs {plat :lat plon :lon}]
  (let [n (count lat)
        plat (double plat) plon (double plon)
        dlon (/ pad (max 0.2 (Math/cos (Math/toRadians plat))))]
    (loop [is (seq idxs) best nil]
      (if-not is
        best
        (let [i (long (first is))
              la (double (nth lat i)) lo (double (nth lon i))]
          (if (and (< (Math/abs (- la plat)) pad) (< (Math/abs (- lo plon)) dlon))
            (let [a (project plat plon la lo)
                  nxt (when (< (inc i) n)
                        (let [la2 (nth lat (inc i)) lo2 (nth lon (inc i) nil)]
                          (when (and la2 lo2) (project plat plon (double la2) (double lo2)))))
                  [d u] (if (and nxt (<= (Math/hypot (- (double (nxt 0)) (double (a 0)))
                                                     (- (double (nxt 1)) (double (a 1)))) gap-m))
                          (seg-dist a nxt)
                          (seg-dist a a))]
              (if (<= (double d) radius-m)
                (let [j (if (and nxt (> (double u) 0.5)) (inc i) i)
                      z (some-> alt (nth j nil))
                      ti (some-> t (nth j nil))
                      best (if (and best (<= (:dist_m best) d))
                             best
                             {:dist_m d :time_s ti})
                      best (cond-> best z (update :alt #(if % (max % z) z)))]
                  (recur (next is) best))
                (recur (next is) best)))
            (recur (next is) best)))))))

(defn- grid-index
  "{[row col] [fix index …]} of the fixes with a position, in `pad`-degree rows and `col-deg`-degree columns;
  the indices of a cell ascend."
  [lat lon col-deg]
  (group-by (fn [i] [(long (Math/floor (/ (double (nth lat i)) pad)))
                     (long (Math/floor (/ (double (nth lon i)) col-deg)))])
            (filter #(and (number? (nth lat %)) (number? (nth lon % nil))) (range (count lat)))))

(defn detect
  "Peaks a stream bagged. `stream`: {:lat :lon :alt :t} columns; `peaks`: [{:id :lat :lon :ele}].
  A peak counts when the track passes within `radius-m` and, if both altitudes are known, was within
  `below-m` of its height there. Returns [{:id :dist_m :time_s}], nearest first for each peak once."
  [{:keys [lat lon] :as stream} peaks]
  (let [pts (filter number? lat)
        lons (filter number? lon)]
    (if (or (empty? pts) (empty? lons))
      []
      (let [[la0 la1] [(apply min pts) (apply max pts)]
            [lo0 lo1] [(apply min lons) (apply max lons)]
            ;; wide enough for every peak in the box: its own column width is at most this
            min-cos (min (Math/cos (Math/toRadians (double (- la0 pad))))
                         (Math/cos (Math/toRadians (double (+ la1 pad)))))
            col-deg (/ pad (max 0.2 min-cos))
            index (grid-index lat lon col-deg)]
        (vec (for [{:keys [id ele] :as p} peaks
                   :when (and (<= (- la0 pad) (:lat p) (+ la1 pad)) (<= (- lo0 pad) (:lon p) (+ lo1 pad)))
                   :let [r (long (Math/floor (/ (double (:lat p)) pad)))
                         c (long (Math/floor (/ (double (:lon p)) col-deg)))
                         idxs (sort (mapcat #(get index [(+ r (long (first %))) (+ c (long (second %)))])
                                            [[-1 -1] [-1 0] [-1 1] [0 -1] [0 0] [0 1] [1 -1] [1 0] [1 1]]))
                         hit (when (seq idxs) (near-peak stream idxs p))]
                   :when (and hit (or (nil? ele) (nil? (:alt hit)) (>= (:alt hit) (- ele below-m))))]
               {:id id :dist_m (:dist_m hit) :time_s (:time_s hit)}))))))

;; ---------------------------------------------------------------------------
;; What to show

(defn visible?
  "Whether a peak is shown: its prominence (from the prominence dataset) reaches `min-prominence`. A peak
  without a prominence is hidden."
  [{:keys [prominence]} min-prominence]
  (boolean (and prominence (>= prominence (or min-prominence 0)))))

;; ---------------------------------------------------------------------------
;; The prominence dataset's summits (coach.peaks): ids, and which name belongs to which summit

(defn dataset-peak-id
  "A summit's id, made from its position (the dataset has 4 decimals): always above 10^13, so never an OSM
  id (those stay below 1.5·10^10), and below 2^53, so safe in JSON."
  [lat lon]
  (+ 10000000000000
     (* (+ (Math/round (* 1e4 (double lat))) 900000) 3600000)
     (Math/round (* 1e4 (double lon)))
     1800000))

(def dataset-id-min "The smallest id of a dataset summit." 10000000000000)

(defn square-block
  "The 10° block of the dataset a 1° square is in (\"46,8\" → \"40_0\": south-west corner)."
  [k]
  (let [[la lo] (map #(Long/parseLong %) (str/split k #","))]
    (str (* 10 (Math/floorDiv la 10)) "_" (* 10 (Math/floorDiv lo 10)))))

(defn dataset-peak-id?
  "Whether an id is a dataset summit's (not an OSM one: those are older athletes' bags)."
  [id]
  (>= id 10000000000000))

(def match-m "A name is given to a summit this near (m) …" 250.0)
(def match-no-ele-m "… or this near when one of the two has no height …" 100.0)
(def link-m "… and a point this near a summit that took another name only counts as the same peak (m)." 150.0)
(def loose-m "A last pass names a summit this near (m) when nothing nearer took the name …" 600.0)
(def loose-ele-m "… and the heights (both known) differ by at most this much (m)." 20.0)
(def ele-window
  "The donor's height minus the summit's must lie in this range (m): the dataset's terrain model cuts sharp
  summits by up to 190 m (Finsteraarhorn), and hardly ever sits above the surveyed height."
  [-60.0 250.0])

(defn- dist-m [lat1 lon1 lat2 lon2]
  (let [k (/ Math/PI 180.0)]
    (Math/hypot (* 6371000.0 k (- lon2 lon1) (Math/cos (* k (/ (+ lat1 lat2) 2.0))))
                (* 6371000.0 k (- lat2 lat1)))))

(def ^:private cell-deg 0.005)

(defn- cell [lat lon] [(long (Math/floor (/ (double lat) cell-deg))) (long (Math/floor (/ (double lon) cell-deg)))])

(defn- in-radius
  "The points of a `cell` grid (`by-cell`) within `radius-m` of lat/lon, as [distance point]."
  [by-cell lat lon radius-m]
  (let [[ci cj] (cell lat lon)
        m-per-cell (* 111320.0 cell-deg)
        lat-cells (long (Math/ceil (/ radius-m m-per-cell)))
        lon-m (* m-per-cell (max 0.05 (Math/cos (Math/toRadians lat))))
        lon-cells (long (Math/ceil (/ radius-m lon-m)))]
    (for [i (range (- ci lat-cells) (+ ci lat-cells 1))
          j (range (- cj lon-cells) (+ cj lon-cells 1))
          p (get by-cell [i j])
          :let [d (dist-m lat lon (:lat p) (:lon p))]
          :when (<= d radius-m)]
      [d p])))

(defn- fits?
  "Whether a donor can be this summit: near enough, and of about the same height."
  [{dele :ele :as d} {sele :ele :as s} limit-m]
  (let [dist (dist-m (:lat d) (:lon d) (:lat s) (:lon s))]
    (if (and dele sele)
      (and (<= dist limit-m)
           (<= (first ele-window) (- dele sele) (second ele-window))
           dist)
      (and (<= dist match-no-ele-m) (<= dist limit-m) dist))))

(defn- loose-pairs
  "The last pass: a named donor nothing took, and not the same peak as a summit (`links`), names the summit
  nearest to it within `loose-m` whose height is within `loose-ele-m` of its own, when that donor is also the
  nearest such donor of that summit. Returns [[summit donor] …]."
  [summits donors named links]
  (let [taken (into #{} (map :id) (vals named))
        free (remove #(or (not (:claim? %)) (taken (:id %)) (contains? links (:id %)) (nil? (:ele %)))
                     donors)
        open (remove #(or (nil? (:ele %)) (contains? named (:id %))) summits)
        by-cell (group-by (fn [s] (cell (:lat s) (:lon s))) open)
        fits (fn [d] (for [[dist s] (in-radius by-cell (:lat d) (:lon d) loose-m)
                           :when (<= (Math/abs (- (:ele d) (:ele s))) loose-ele-m)]
                       [dist s]))
        nearest-summit (into {} (keep (fn [d]
                                        (when-let [[dist s] (first (sort-by first (fits d)))]
                                          [(:id d) [dist s d]])))
                             free)
        nearest-donor (reduce (fn [m [_ [dist s d]]]
                                (if (< dist (or (first (get m (:id s))) Double/MAX_VALUE))
                                  (assoc m (:id s) [dist d])
                                  m))
                              {} nearest-summit)]
    (for [[_ [_ s d]] nearest-summit
          :when (= (:id d) (:id (second (get nearest-donor (:id s)))))]
      [s d])))

(defn match-donors
  "Which donor (a point with a name and a height: a peak list, swisstopo, OpenStreetMap) names which summit
  of the prominence dataset. `summits`: [{:id :lat :lon :ele}]; `donors`: [{:id :lat :lon :ele :rank :claim?}]
  where a lower `:rank` goes first and a donor with `:claim?` false (no name) never takes a summit.
  One donor per summit and one summit per donor, the nearest pair first within a rank. A donor left over is
  still the same peak as the nearest summit within `link-m` (a second OSM point on one top), so what an
  athlete bagged under its id is not lost. A last pass (`loose-pairs`) gives a name that was too far for the first
  ones (the dataset's tops can lie a few hundred metres off) to the summit it nearly matches.
  Returns {:named {summit-id donor} :links {donor-id summit-id}}."
  [summits donors]
  (let [by-cell (group-by (fn [s] (cell (:lat s) (:lon s))) summits)
        near (fn [d limit]
               (let [[ci cj] (cell (:lat d) (:lon d))]
                 (for [i (range (- ci 2) (+ ci 3)) j (range (- cj 2) (+ cj 3))
                       s (get by-cell [i j])
                       :let [dist (fits? d s limit)]
                       :when dist]
                   [dist s])))
        ranks (sort (distinct (map :rank (filter :claim? donors))))
        [named links]
        (reduce (fn [[named links] rank]
                  (let [pairs (sort-by first (for [d donors :when (and (:claim? d) (= rank (:rank d)))
                                                   [dist s] (near d match-m)
                                                   :when (not (contains? named (:id s)))]
                                               [dist s d]))]
                    (reduce (fn [[named links] [_ s d]]
                              (if (or (contains? named (:id s)) (contains? links (:id d)))
                                [named links]
                                [(assoc named (:id s) d) (assoc links (:id d) (:id s))]))
                            [named links] pairs)))
                [{} {}] ranks)
        links (reduce (fn [links d]
                        (if (contains? links (:id d))
                          links
                          (if-let [[_ s] (first (sort-by first (near d link-m)))]
                            (assoc links (:id d) (:id s))
                            links)))
                      links donors)
        loose (loose-pairs summits donors named links)]
    {:named (reduce (fn [named [s d]] (assoc named (:id s) d)) named loose)
     :links (reduce (fn [links [s d]] (assoc links (:id d) (:id s))) links loose)}))

(def near-m "A summit nobody named is described by the nearest name within this distance (m)." 3000.0)

(defn nearest-names
  "For each of `summits` ([{:id :lat :lon}]) the nearest of `points` ([{:name :lat :lon}], blank names ignored)
  within `radius-m`: {summit-id {:name :dist}}. A summit with no point that near is left out."
  [summits points radius-m]
  (let [by-cell (group-by (fn [p] (cell (:lat p) (:lon p))) (remove #(str/blank? (:name %)) points))]
    (into {}
          (keep (fn [{:keys [id lat lon]}]
                  (when-let [[dist p] (first (sort-by first (in-radius by-cell lat lon radius-m)))]
                    [id {:name (:name p) :dist dist}])))
          summits)))
