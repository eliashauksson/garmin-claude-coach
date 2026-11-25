(ns snap-check
  "Dev: what snapping a square's summits to Mapterhorn's terrain does to an athlete's bags and to the names.
  Reads both databases and writes nothing.
  clojure -M:dev -m snap-check <athlete's coach.db> <square> [radius-m …]"
  (:require [coach.db :as db]
            [coach.peaks :as peaks]
            [coach.summits :as summits]
            [coach.terrain :as terrain]))

(def ^:private near-limit-m 250.0)

(defn- square-summits [k margin]
  (let [[s w n e] (summits/square-bbox k)]
    (db/query (peaks/shared)
              ["SELECT id, lat, lon, ele_dem, coalesce(ele, ele_dem) AS ele, prominence, name, near_name
                FROM peaks WHERE lat >= ? AND lat < ? AND lon >= ? AND lon < ?"
               (- s margin) (+ n margin) (- w margin) (+ e margin)])))

(defn- snapped [snaps summit]
  (if-let [{:keys [lat lon]} (:snap (snaps (:id summit)))]
    (assoc summit :lat lat :lon lon)
    summit))

(defn- activities [ds k]
  (for [{:keys [id date sport name]} (db/query ds ["SELECT id, date, sport, name FROM activities ORDER BY date"])
        :when (summits/eligible? sport)
        :let [stream (try (db/load-stream ds id) (catch Exception _ nil))]
        :when (some (fn [[la lo]] (and la lo (= k (summits/square-key la lo))))
                    (map vector (:lat stream) (:lon stream)))]
    {:id id :date date :sport sport :name name :stream stream}))

(defn- around
  "The summits a stream can reach."
  [{:keys [lat lon]} by-row]
  (let [las (filter number? lat) los (filter number? lon)
        [a b] [(- (apply min las) 0.01) (+ (apply max las) 0.01)]
        [c d] [(- (apply min los) 0.01) (+ (apply max los) 0.01)]]
    (filter #(and (<= a (:lat %) b) (<= c (:lon %) d))
            (mapcat by-row (range (long (Math/floor (* a 100))) (inc (long (Math/floor (* b 100)))))))))

(defn- distances
  "{peak-id m}: how close the stream came, whatever the altitude, up to `near-limit-m` and a bit."
  [stream cands]
  (with-redefs [summits/radius-m 400.0]
    (into {} (map (juxt :id :dist_m)) (summits/detect stream (map #(dissoc % :ele) cands)))))

(defn- measure
  "Per summit over all activities: the closest the tracks came to the original and the snapped position, and the
  activities that bag it with each."
  [acts all snaps]
  (let [by-row (group-by #(long (Math/floor (* (:lat %) 100))) all)
        closer (fn [m k d] (if (and d (or (nil? (m k)) (< d (m k)))) (assoc m k d) m))]
    (reduce (fn [r {:keys [stream id]}]
              (let [cands (vec (around stream by-row))
                    moved (mapv #(snapped snaps %) cands)
                    d0 (distances stream cands)
                    d1 (distances stream moved)
                    r (reduce (fn [r {:keys [id]}]
                                (-> r (update :d0 closer id (d0 id)) (update :d1 closer id (d1 id))))
                              r cands)
                    act id
                    add (fn [r key hits] (reduce #(update-in %1 [key (:id %2)] (fnil conj []) act) r hits))]
                (-> r
                    (add :bag0 (summits/detect stream cands))
                    (add :bag1 (summits/detect stream moved)))))
            {:d0 {} :d1 {} :bag0 {} :bag1 {}}
            acts)))

(defn- label [{:keys [name near_name ele prominence]}]
  (format "%-34s %5.0f m  prom %4.0f"
          (subs (str (or name (when near_name (str "near " near_name)) "(unnamed)") (apply str (repeat 34 " "))) 0 34)
          (double (or ele 0)) (double (or prominence 0))))

(defn- m [x] (if x (format "%4.0f" (double x)) "   –"))

(defn- print-peaks [title ids by-id snaps {:keys [d0 d1 bag0 bag1]} limit]
  (println (format "\n%s: %d" title (count ids)))
  (when (seq ids)
    (println (format "  %-52s  track→orig  track→snapped  moved  gain  visits old→new" "summit")))
  (doseq [id (take limit ids)
          :let [s (:snap (snaps id))]]
    (println (format "  %s       %s          %s   %s  %s   %d→%d"
                     (label (by-id id)) (m (d0 id)) (m (d1 id)) (m (:dist_m s)) (m (:gain s))
                     (count (bag0 id)) (count (bag1 id)))))
  (when (> (count ids) limit) (println "  … and" (- (count ids) limit) "more")))

(defn- percentiles [xs]
  (let [v (vec (sort xs))]
    (when (seq v)
      (into {} (for [p [50 75 90 99]] [p (Math/round (double (v (min (dec (count v)) (quot (* p (count v)) 100)))))])))))

(defn- name-changes [k sums snaps]
  (let [ds (#'peaks/donors (summits/square-bbox k))
        match (fn [ss] (summits/match-donors (map #(assoc % :ele (:ele_dem %)) ss) ds))
        before (match sums)
        after (match (map #(snapped snaps %) sums))
        ids (distinct (concat (keys (:named before)) (keys (:named after))))
        diff (for [id ids
                   :let [a (get-in before [:named id :name]) b (get-in after [:named id :name])]
                   :when (not= a b)]
               [a b])]
    (println (format "\nNames (matching on snapped positions instead): %d → %d named summits; %d gain a name, %d lose one, %d change"
                     (count (:named before)) (count (:named after))
                     (count (remove first diff)) (count (remove second diff)) (count (filter (fn [[a b]] (and a b)) diff))))
    (doseq [[a b] (take 25 diff)] (println "  " (or a "–") "→" (or b "–")))
    (println "  OSM links that change:"
             (count (remove (fn [[d s]] (= s (get (:links after) d))) (:links before))))))

(defn- truth
  "How far the summits swisstopo names are from its surveyed point, before and after: independent of tracks."
  [k sums snaps]
  (let [points (into {} (map (juxt :id identity))
                     (filter #(= 1 (:rank %)) (#'peaks/donors (summits/square-bbox k))))
        {:keys [named]} (summits/match-donors (map #(assoc % :ele (:ele_dem %)) sums) (vals points))
        by-id (into {} (map (juxt :id identity)) sums)
        pairs (for [[id d] named
                    :let [s (by-id id) moved (snapped snaps s)]
                    :when (:snap (snaps id))]
                [(terrain/distance-m (:lat d) (:lon d) (:lat s) (:lon s))
                 (terrain/distance-m (:lat d) (:lon d) (:lat moved) (:lon moved))
                 (when (:ele d) (Math/abs (- (:ele d) (:ele (:snap (snaps id))))))])]
    (println (format "Against swisstopo's surveyed points (%d moved summits that have one):" (count pairs)))
    (println "   distance m before" (percentiles (map first pairs)) " after" (percentiles (map second pairs)))
    (println (format "   closer %d, farther %d; within 25 m: %d → %d; |terrain height − surveyed| m %s"
                     (count (filter (fn [[a b]] (< b a)) pairs)) (count (filter (fn [[a b]] (> b a)) pairs))
                     (count (filter #(<= (first %) 25) pairs)) (count (filter #(<= (second %) 25) pairs))
                     (percentiles (keep #(nth % 2) pairs))))))

(defn check [db-path k radii]
  (let [ds (db/read-only-datasource db-path)
        sums (vec (square-summits k 0))
        others (square-summits k 0.003)
        fetched (atom {}) ; the bytes, for this run only: every radius reads the same download
        real @#'peaks/fetch-tile
        fine (peaks/fetch-coverage)
        acts (vec (activities ds k))
        by-id (into {} (map (juxt :id identity)) sums)
        visits (fn [bags] (reduce + 0 (map count (vals bags))))]
    (println "Square" k "·" (count sums) "summits ·" (count acts) "activities in it")
    ;; the widest first: its tiles hold the others'
    (doseq [radius (sort > radii)]
      (let [started (System/currentTimeMillis)
            snaps (with-redefs-fn {#'terrain/snap-radius-m radius
                                   #'peaks/fetch-tile (fn [z x y]
                                                        (if (contains? @fetched [z x y])
                                                          (@fetched [z x y])
                                                          (let [b (real z x y)]
                                                            (swap! fetched assoc [z x y] b)
                                                            b)))}
                    #(peaks/snap-summits fine sums others))
            moved (keep :snap (vals snaps))
            {:keys [d0 d1 bag0 bag1] :as r} (measure acts sums snaps)
            ever0 (set (keys bag0)) ever1 (set (keys bag1))
            near (->> (keys d0)
                      (filter #(and (not (ever0 %)) (< summits/radius-m (d0 %)) (<= (d0 %) near-limit-m)))
                      (sort-by #(get d1 % 1e9)))]
      (println (format "\n======== radius %.0f m (min gain %.0f, max dip %.0f) · %d tiles so far, %.0f MB, %.0f s"
                       (double radius) terrain/snap-min-gain-m terrain/snap-max-dip-m (count @fetched)
                       (/ (reduce + 0 (map count (vals @fetched))) 1e6)
                       (/ (- (System/currentTimeMillis) started) 1000.0)))
      (println "Zooms:" (frequencies (map :zoom (vals snaps))))
      (println (format "Moved: %d (%.0f%%); distance m %s; gain m %s"
                       (count moved) (/ (* 100.0 (count moved)) (max 1 (count sums)))
                       (percentiles (map :dist_m moved)) (percentiles (map :gain moved))))
      (truth k sums snaps)
      (println (format "Peaks bagged: %d → %d; visits: %d → %d" (count ever0) (count ever1) (visits bag0) (visits bag1)))
      (let [either (merge-with (comp distinct concat) bag0 bag1)]
        (println (format "Either position counts: %d peaks, %d visits (visits gained %d, lost with snapped only %d)"
                         (count either) (visits either) (- (visits either) (visits bag0)) (- (visits either) (visits bag1)))))
      (print-peaks "Near misses today (75–250 m from a track, never bagged), closest after snapping first"
                   near by-id snaps r 12)
      (println "  of these: bagged after snapping" (count (filter ever1 near))
               "· moved closer" (count (filter #(< (get d1 % 1e9) (d0 %)) near))
               "· moved away" (count (filter #(> (get d1 % 1e9) (d0 %)) near)))
      (print-peaks "Gained" (sort-by #(get d1 %) (remove ever0 ever1)) by-id snaps r 40)
      (print-peaks "LOST" (sort-by #(get d1 % 1e9) (remove ever1 ever0)) by-id snaps r 40)
      (when (= radius terrain/snap-radius-m) (name-changes k sums snaps))))))

(defn -main [db-path k & radii]
  (check db-path k (if (seq radii) (map parse-double radii) [terrain/snap-radius-m]))
  (shutdown-agents))
