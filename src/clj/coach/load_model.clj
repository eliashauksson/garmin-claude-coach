(ns coach.load-model
  "DB glue for coach.epoc: fits our training load to Garmin's on the
  athlete's own activities, calibrates power against heart rate on rides
  with both, and compares the result with Garmin (CLI `compare`)."
  (:require [clojure.data.json :as json]
            [coach.analysis :as analysis]
            [coach.db :as db]
            [coach.epoc :as epoc]
            [coach.metrics :as m]
            [coach.readiness-score :as rs])
  (:import (java.time Instant LocalDate)))

(def min-rows "Fit only with this many Garmin activities that have heart rate." 100)
(def refit-after "Refit once this many new Garmin loads came in since the last fit." 20)
(def fit-iters 800)
(def min-power-rides "Calibrate power on this many rides with both power and heart rate." 3)

;; ---------------------------------------------------------------------------
;; Rows

(defn- garmin-rows
  "Garmin activities with a training load and a heart-rate stream:
  [{:id :date :sport :load :bins}], oldest first."
  [ds]
  (let [th (db/thresholds ds)]
    (vec (for [a (db/query ds ["SELECT a.id, a.date, a.sport, a.training_load FROM activities a
                                JOIN activity_streams s ON s.activity_id = a.id
                                WHERE a.training_load > 0 AND s.samples > 0 ORDER BY a.date, a.id"])
               :let [mx (:max_hr (analysis/thresholds-at th (:date a)))
                     b  (some-> (db/load-stream ds (:id a)) (epoc/hr-bins mx))]
               :when b]
           {:id (:id a) :date (:date a) :sport (:sport a) :load (:training_load a) :bins b}))))

(defn split
  "[train held-out]: every 5th activity is held out of the fit."
  [rows]
  [(vec (keep-indexed (fn [i r] (when-not (zero? (mod i 5)) r)) rows))
   (vec (take-nth 5 rows))])

;; ---------------------------------------------------------------------------
;; Refit

(defn fit-stats [ds] (db/state-json ds :load_fit))

(defn- garmin-count [ds]
  (:n (first (db/query ds ["SELECT count(*) AS n FROM activities WHERE training_load > 0"]))))

(defn refit!
  "Fits the constants on 4 of 5 Garmin activities and keeps them if they
  match the 5th at least as well as the current ones. Runs when `force` or
  `refit-after` new Garmin loads came in. Rescores every activity when the
  constants changed. Returns the stats, or nil when it didn't run."
  [ds & [force]]
  (let [n    (garmin-count ds)
        last (:garmin_loads (fit-stats ds))]
    (when (or force (nil? last) (>= (- n last) refit-after))
      (let [rows (garmin-rows ds)]
        (when (>= (count rows) min-rows)
          (let [[train held] (split rows)
                cur   (analysis/load-params ds)
                new   (epoc/fit cur train rs/nelder-mead fit-iters)
                ec    (epoc/evaluate cur held)
                en    (epoc/evaluate new held)
                [p s] (if (<= (:median en) (:median ec)) [new en] [cur ec])
                stats (assoc s :fitted_at (str (Instant/now)) :garmin_loads n)]
            (db/set-state! ds :load_params (json/write-str p))
            (db/set-state! ds :load_fit (json/write-str stats))
            (println (format "Training load model refitted: median %.0f%% off Garmin on %d held-out activities"
                             (* 100 (:median s)) (:n s)))
            (when (not= p cur) (analysis/compute-metrics! ds))
            stats))))))

;; ---------------------------------------------------------------------------
;; Power calibration

(defn calibrate-power!
  "Calibrates power against heart rate on the rides that have both (any
  source) and rescores the rides with power when it changed. Returns [a b]
  or nil while there are too few such rides."
  [ds]
  (let [th    (db/thresholds ds)
        rides (for [a (db/query ds ["SELECT a.id, a.date, a.sport FROM activities a
                                     JOIN activity_metrics m ON m.activity_id = a.id
                                     WHERE json_extract(m.peaks, '$.power') IS NOT NULL"])
                    :when (= :bike (m/sport-family (:sport a)))
                    :let [{:keys [max_hr ftp]} (analysis/thresholds-at th (:date a))
                          s  (db/load-stream ds (:id a))
                          hr (some-> s (epoc/hr-bins max_hr))
                          pw (some-> s (epoc/power-bins ftp [0.0 1.0]))]
                    :when (and hr pw)]
                {:id (:id a) :hr hr :power pw})
        cal   (when (>= (count rides) min-power-rides) (epoc/power-calibration rides))]
    (when (and cal (not= cal (analysis/power-cal ds)))
      (db/set-state! ds :power_cal (json/write-str cal))
      (println (format "Power calibrated on %d rides: intensity = %.2f + %.2f x P/FTP" (count rides) (first cal) (second cal)))
      (analysis/compute-metrics! ds (map :id rides)))
    cal))

(defn update!
  "After a sync: power calibration, then a refit when it's due."
  [ds]
  (calibrate-power! ds)
  (refit! ds))

;; ---------------------------------------------------------------------------
;; Compare with Garmin

(defn- pct [x] (if x (format "%4.1f%%" (* 100.0 x)) "  —  "))

(defn- line [label {:keys [n median p80 bias spearman]}]
  (println (format "%-20s %4d  median %s  p80 %s  bias %+5.1f%%  rank r %s"
                   label n (pct median) (pct p80) (* 100.0 (or bias 0.0))
                   (if spearman (format "%.2f" spearman) "—"))))

(defn daily-loads
  "{date total} of `expr` over the activities, e.g. \"coalesce(a.training_load,
  m.load_est)\": Garmin's load where it has one, else ours."
  [ds expr]
  (reduce (fn [acc {:keys [date l]}] (update acc date (fnil + 0.0) l)) {}
          (db/query ds [(str "SELECT a.date, " expr " AS l
                              FROM activities a LEFT JOIN activity_metrics m ON m.activity_id = a.id
                              WHERE " expr " IS NOT NULL")])))

(defn garmin-acute
  "[[date acute] …] from Garmin's load trend, oldest first."
  [ds]
  ;; acute 0 = days the watch reported no status (chronic frozen, "DETRAINING"
  ;; the day of a 20 h hike): not Garmin's real load, so left out
  (mapv (juxt :date :acute) (db/query ds ["SELECT date, acute FROM load_trend WHERE acute > 0 ORDER BY date"])))

(defn acute-error
  "Mean absolute error of our acute load against Garmin's `trend`, and that
  relative to Garmin's mean."
  [ap daily trend]
  (let [errs (map (fn [[d a]] (Math/abs (- (epoc/acute-load ap daily d) (double a)))) trend)
        mae  (/ (reduce + errs) (count trend))]
    {:mae mae :rel (/ mae (/ (reduce + (map second trend)) (count trend)))}))

(defn fit-acute
  "Garmin's aggregation (scale, tau, window) from its own activity loads
  `daily` and its acute load `trend`."
  [daily trend]
  (apply min-key #(:mae (acute-error % daily trend))
         (for [w (range 6 15)]
           (let [x (rs/nelder-mead (fn [[s t]] (:mae (acute-error {:scale (Math/abs (double s)) :tau (max 0.5 (Math/abs (double t))) :window w}
                                                                  daily trend)))
                                   [(:scale epoc/acute-defaults) (:tau epoc/acute-defaults)] 200)]
             {:scale (Math/abs (double (x 0))) :tau (max 0.5 (Math/abs (double (x 1)))) :window w}))))

(defn- compare! [ds]
  (let [p (analysis/load-params ds)
        rows (garmin-rows ds)
        [_ held] (split rows)]
    (println "Training load (peak EPOC) against Garmin's, held-out activities (not used in the fit):")
    (line "all held out" (epoc/evaluate p held))
    (line "  2025 on" (epoc/evaluate p (filter #(>= (compare (:date %) "2025") 0) held)))
    (println "\nper sport (all activities):")
    (doseq [[s rs] (sort-by (comp - count val) (group-by :sport rows)) :when (>= (count rs) 10)]
      (line (str "  " s) (epoc/evaluate p rs)))
    (println "\nper year (all activities):")
    (doseq [[y rs] (sort (group-by #(subs (:date %) 0 4) rows))]
      (line (str "  " y) (epoc/evaluate p rs)))
    (println "\nActivities without Garmin load (ours):")
    (doseq [r (db/query ds ["SELECT a.date, a.sport, a.name, m.load_est, m.load_method, a.aerobic_te
                             FROM activities a JOIN activity_metrics m ON m.activity_id = a.id
                             WHERE a.training_load IS NULL AND m.load_est IS NOT NULL ORDER BY a.date"])]
      (println (format "  %s %-14s %5.0f  (%s, TE %s)  %s" (:date r) (:sport r) (:load_est r) (:load_method r)
                       (or (:aerobic_te r) "—") (:name r))))
    (let [trend (garmin-acute ds)]
      (when (seq trend)
        (let [garmin (daily-loads ds "a.training_load")
              ;; ours on the same activities Garmin counted: the fair test
              ours   (daily-loads ds "CASE WHEN a.training_load > 0 THEN m.load_est END")
              ap     (fit-acute garmin trend)]
          (println (format "\nAcute load against Garmin's, %d days (%s to %s); Garmin's mean %.0f:"
                           (count trend) (ffirst trend) (first (last trend)) (/ (reduce + (map second trend)) (count trend))))
          (println (format "  aggregation fitted on Garmin's own loads: scale %.3f, tau %.2f d, window %d d"
                           (:scale ap) (:tau ap) (:window ap)))
          (doseq [[label daily] [["from Garmin's activity loads" garmin]
                                 ["from ours, same activities" ours]]]
            (let [{:keys [mae rel]} (acute-error ap daily trend)]
              (println (format "  %-28s MAE %5.1f  (%4.1f%% of Garmin's mean)" label mae (* 100 rel)))))
          ;; ours against the same sum of Garmin's activity loads: our per-activity
          ;; error alone, without the trend's own quirks
          (let [days   (map first trend)
                rebuilt (mapv #(vector % (epoc/acute-load ap garmin %)) days)
                {:keys [mae rel]} (acute-error ap ours (filter #(pos? (second %)) rebuilt))]
            (println (format "  %-28s MAE %5.1f  (%4.1f%% of its mean)" "ours vs Garmin's loads summed" mae (* 100 rel)))))))
    (when-let [f (fit-stats ds)] (println "\nLast fit:" (pr-str f)))
    (when-let [c (analysis/power-cal ds)] (println "Power calibration:" (pr-str c)))))

(defn -main
  "compare: our training load against Garmin's. refit: refit now.
  calibrate: power calibration now."
  [& [cmd]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (case (or cmd "compare")
      "compare"   (compare! ds)
      "refit"     (prn (refit! ds true))
      "calibrate" (prn (calibrate-power! ds)))
    (shutdown-agents)))
