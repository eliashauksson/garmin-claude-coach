;; Times the prominence calculation on real peaks and dumps the results, to compare before/after a change.
;;   COACH_SHARED_DIR=<dir with peaks.db> clojure -M dev/peaks_bench.clj OUT.edn [N=200] [START=1000]
;; Takes N peaks (without a prominence from OSM) that follow each other in the order the app works through
;; them (so they share elevation tiles), works them out twice: the first pass downloads the tiles, the second
;; reads them from the disk cache. Nothing is written to the database. OUT.edn: [[osm_id prominence src] …].
(require '[clojure.pprint :as pp]
         '[coach.db :as db]
         '[coach.peaks :as peaks])

(let [[out n start] *command-line-args*
      n (parse-long (or n "200"))
      start (parse-long (or start "1000"))
      peaks-in-order (->> (db/query (peaks/shared) ["SELECT osm_id, lat, lon, ele FROM osm_peaks
                                                     WHERE ele IS NOT NULL AND ele >= 0 AND prominence_src IS NOT 'osm'"])
                          (sort-by (deref #'peaks/dem-tile-of)))
      sample (vec (take n (drop start peaks-in-order)))
      run (fn [label]
            (let [t (System/nanoTime)
                  rs (mapv (fn [p] (let [r (peaks/peak-prominence p)] [(:osm_id p) (:prominence r) (:src r)])) sample)
                  ms (/ (- (System/nanoTime) t) 1e6)]
              (println (format "%s: %d peaks in %.0f ms (%.1f ms/peak), %d bounded at the biggest window"
                               label (count rs) ms (/ ms (count rs)) (count (filter #(= "dem-min" (nth % 2)) rs))))
              rs))]
  (run "pass 1 (downloads tiles)")
  (let [rs (run "pass 2 (tiles on disk)")
        rs2 (run "pass 3 (tiles on disk, JIT warm)")]
    (println "passes agree:" (= rs rs2))
    (spit out (with-out-str (pp/pprint rs))))
  (shutdown-agents))
