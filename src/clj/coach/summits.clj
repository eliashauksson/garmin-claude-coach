(ns coach.summits
  "Pure. Peak bagging: which OSM tiles an activity needs, which peaks a GPS stream bagged, which peaks
  to show, and the progress of a peak list."
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
  radius, nil when the track never comes that close. `alt` is the highest altitude of those points."
  [{:keys [lat lon alt t]} {plat :lat plon :lon}]
  (let [n (count lat)
        dlon (/ pad (max 0.2 (Math/cos (Math/toRadians plat))))]
    (loop [i 0 best nil]
      (if (>= i n)
        best
        (let [la (nth lat i) lo (nth lon i)]
          (if (and la lo (< (Math/abs (- la plat)) pad) (< (Math/abs (- lo plon)) dlon))
            (let [a (project plat plon la lo)
                  nxt (when (< (inc i) n)
                        (let [la2 (nth lat (inc i)) lo2 (nth lon (inc i))]
                          (when (and la2 lo2) (project plat plon la2 lo2))))
                  [d u] (if (and nxt (<= (Math/hypot (- (nxt 0) (a 0)) (- (nxt 1) (a 1))) gap-m))
                          (seg-dist a nxt)
                          (seg-dist a a))]
              (if (<= d radius-m)
                (let [j (if (and nxt (> u 0.5)) (inc i) i)
                      z (some-> alt (nth j nil))
                      ti (some-> t (nth j nil))
                      best (if (and best (<= (:dist_m best) d))
                             best
                             {:dist_m d :time_s ti})
                      best (cond-> best z (update :alt #(if % (max % z) z)))]
                  (recur (inc i) best))
                (recur (inc i) best)))
            (recur (inc i) best)))))))

(defn detect
  "Peaks a stream bagged. `stream`: {:lat :lon :alt :t} columns; `peaks`: [{:osm_id :lat :lon :ele}].
  A peak counts when the track passes within `radius-m` and, if both altitudes are known, was within
  `below-m` of its height there. Returns [{:osm_id :dist_m :time_s}], nearest first for each peak once."
  [{:keys [lat lon] :as stream} peaks]
  (let [pts (filter number? lat)
        lons (filter number? lon)]
    (if (or (empty? pts) (empty? lons))
      []
      (let [[la0 la1] [(apply min pts) (apply max pts)]
            [lo0 lo1] [(apply min lons) (apply max lons)]]
        (vec (for [{:keys [osm_id ele] :as p} peaks
                   :when (and (<= (- la0 pad) (:lat p) (+ la1 pad)) (<= (- lo0 pad) (:lon p) (+ lo1 pad)))
                   :let [hit (near-peak stream p)]
                   :when (and hit (or (nil? ele) (nil? (:alt hit)) (>= (:alt hit) (- ele below-m))))]
               {:osm_id osm_id :dist_m (:dist_m hit) :time_s (:time_s hit)}))))))

;; ---------------------------------------------------------------------------
;; What to show

(defn visible?
  "Whether a peak is shown: it is on a list, or its prominence (OSM tag or the elevation model) reaches
  `min-prominence`. A peak without a prominence yet is hidden."
  [{:keys [prominence listed]} min-prominence]
  (boolean (or listed
               (and prominence (>= prominence (or min-prominence 0))))))

(defn list-progress
  "A peak list against the bagged ids: {:total :bagged :peaks [{… :bagged? bool}]}."
  [{:keys [peaks] :as lst} bagged-ids]
  (let [bagged (set bagged-ids)
        ps (mapv #(assoc % :bagged? (contains? bagged (:osm_id %))) peaks)]
    (assoc (dissoc lst :peaks)
           :total (count ps)
           :bagged (count (filter :bagged? ps))
           :peaks ps)))
