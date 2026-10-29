;; Phase 3 check: do our CTL/ATL follow the shape of Garmin's chronic/acute load?
;; clojure -M:dev -i dev/compare_load.clj
(require '[coach.analysis :as analysis] '[coach.db :as db] '[coach.garmin-mcp :as gm])
(import '(java.time LocalDate))

(defn pearson [xs ys]
  (let [n (count xs) mx (/ (reduce + xs) n) my (/ (reduce + ys) n)
        cov (reduce + (map #(* (- %1 mx) (- %2 my)) xs ys))
        sx (Math/sqrt (reduce + (map #(Math/pow (- % mx) 2) xs)))
        sy (Math/sqrt (reduce + (map #(Math/pow (- % my) 2) ys)))]
    (/ cov (* sx sy))))

(let [to   (str (LocalDate/now))
      from (str (.minusDays (LocalDate/now) 89))
      ds   (db/datasource)
      ours (into {} (map (juxt :date identity)) (analysis/pmc ds from to))
      src  (gm/start! {:stderr-file "data/mcp-probe.log"})
      garmin (try (:trend (gm/call (:client src) "get_training_load_trend" {"start_date" from "end_date" to}))
                  (finally (gm/stop! src)))
      pairs (keep #(when-let [o (ours (:date %))] [o %]) garmin)]
  (println (count pairs) "days," from "→" to)
  (println (format "CTL vs Garmin chronic: r = %.2f" (pearson (map (comp :ctl first) pairs) (map (comp double :ctl second) pairs))))
  (println (format "ATL vs Garmin acute:   r = %.2f" (pearson (map (comp :atl first) pairs) (map (comp double :atl second) pairs))))
  (println "\nweek ending   our CTL  ATL  TSB | Garmin chronic acute")
  (doseq [[o g] (take-nth 7 (reverse pairs))]
    (println (format "%s  %6.1f %5.1f %5.1f | %6d %6d" (:date o) (:ctl o) (:atl o) (:tsb o) (:ctl g) (:atl g)))))
(shutdown-agents)
