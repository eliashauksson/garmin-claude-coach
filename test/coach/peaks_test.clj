(ns coach.peaks-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [coach.db :as db]
            [coach.paths :as paths]
            [coach.peaks :as peaks]
            [coach.summits :as summits]
            [coach.track :as track]
            [next.jdbc :as jdbc])
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (javax.imageio ImageIO)))

(defn- temp-db [migrate!]
  (let [f (java.io.File/createTempFile "coach-peaks" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) migrate!)))

(defn- temp-ds
  "A fresh athlete database, on a fresh shared peaks database."
  []
  (peaks/use-shared! (temp-db db/migrate-peaks!))
  (temp-db db/migrate!))

(defn- peak [id name ele lat prominence]
  {:osm_id id :name name :ele ele :lat lat :lon 8.0 :prominence prominence :tags "{}"})

(defn- activity! [ds id sport]
  (db/upsert-activities! ds [{:id id :date "2026-07-14" :sport sport :name "Hike" :raw {} :fetched_at "x"}])
  (db/upsert-metrics! ds [{:activity_id id :track ["abc"]}])
  ;; walking north past 46.5 N / 8.0 E, 30 m to the east
  (db/save-stream! ds id {:lat (mapv #(+ 46.49 (* % 0.0001)) (range 200))
                          :lon (vec (repeat 200 8.0004))
                          :alt (vec (repeat 200 2990.0))
                          :t (vec (range 200))}
                   nil))

(deftest parse-ele
  (is (= 3000.0 (peaks/parse-ele "3,000")))
  (is (= 1234.0 (peaks/parse-ele "1234 m")))
  (is (= 4478.4 (peaks/parse-ele "4478.4")))
  (is (nil? (peaks/parse-ele "many")))
  (is (nil? (peaks/parse-ele nil))))

(deftest filter-by-prominence
  (let [ds (temp-ds)]
    (peaks/save-peaks! [(peak 1 "Big" 3000.0 46.5 400.0) (peak 2 "Bump" 40.0 46.6 12.0)
                           (peak 3 "Unknown" 900.0 46.7 nil)])
    (is (= ["Big"] (map :name (peaks/visible-peaks ds))) "minor and not-yet-computed peaks are hidden")
    (peaks/set-min-prominence! ds 10)
    (is (= #{"Big" "Bump"} (set (map :name (peaks/visible-peaks ds)))))
    (testing "saving a peak again keeps the prominence worked out for it"
      (db/query (peaks/shared) ["UPDATE osm_peaks SET prominence = 55, prominence_src = 'dem' WHERE osm_id = 3"])
      (peaks/save-peaks! [(peak 3 "Unknown" 901.0 46.7 nil)])
      (is (= 55.0 (:prominence (first (filter #(= 3 (:id %)) (peaks/visible-peaks ds)))))))))

(deftest detection-and-dismissing
  (let [ds (temp-ds)]
    (peaks/save-peaks! [(peak 1 "Big" 3000.0 46.5 400.0)])
    (activity! ds 10 "hiking")
    (is (= 1 (peaks/detect-activity! ds {:id 10 :date "2026-07-14" :sport "hiking"})))
    (let [[s :as summits] (peaks/activity-summits ds 10)]
      (is (= 1 (count summits)))
      (is (= "Big" (:name s)))
      (testing "a GPS bag is dismissed, not deleted, so detection doesn't bring it back"
        (peaks/unbag! ds (:visit_id s))
        (is (empty? (peaks/activity-summits ds 10)))
        (peaks/detect-activity! ds {:id 10 :date "2026-07-14" :sport "hiking"})
        (is (empty? (peaks/activity-summits ds 10)))))
    (testing "manual bags"
      (let [id (peaks/bag! ds 1 "2026-08-01")]
        (is (= 1 (:bagged (peaks/summary ds))))
        (is (= 1 (:visits (peaks/summary ds))))
        (peaks/unbag! ds id)
        (is (zero? (:bagged (peaks/summary ds))))))
    (testing "bad input"
      (is (thrown? Exception (peaks/bag! ds 99 "2026-08-01")))
      (is (thrown? Exception (peaks/bag! ds 1 "yesterday"))))))

(deftest virtual-rides-bag-nothing
  (let [ds (temp-ds)]
    (peaks/save-peaks! [(peak 1 "Big" 3000.0 46.5 400.0)])
    (activity! ds 11 "virtual_ride")
    (is (zero? (peaks/detect-activity! ds {:id 11 :date "2026-07-14" :sport "virtual_ride"})))
    (is (empty? (peaks/activity-summits ds 11)))))

(defn- fetched! [k]
  (let [now "2026-01-01T00:00:00Z"]
    (doseq [t (summits/tiles-of-square k)]
      (jdbc/execute! (peaks/shared) ["INSERT OR REPLACE INTO peak_tiles (tile, peaks, fetched_at) VALUES (?, 0, ?)" t now]))))

(deftest squares
  (let [ds (temp-ds)
        state #(:state (first (filter (fn [s] (= %2 (:square s))) (:squares (peaks/squares-status %1)))))]
    (testing "asking for squares: new ones counted, repeats and bad keys refused"
      (is (= 2 (peaks/add-squares! ["46,7" "46,8"])))
      (is (= 1 (peaks/add-squares! ["46,8" "47,8"])))
      (is (thrown? Exception (peaks/add-squares! ["99,99"])))
      (is (thrown? Exception (peaks/add-squares! [])))
      (is (thrown? Exception (peaks/add-squares! (for [la (range -85 15) lo [0 1]] (str la "," lo)))) "over the cap"))
    (testing "states: queued, failed, heights, ready"
      (is (= "queued" (state ds "46,8")))
      (jdbc/execute! (peaks/shared) ["UPDATE peak_squares SET message = 'busy' WHERE square = '46,8'"])
      (is (= "failed" (state ds "46,8")))
      (fetched! "46,8")
      (peaks/save-peaks! [(peak 1 "A" 3000.0 46.5 nil)])
      (is (= "heights" (state ds "46,8")) "all tiles fetched, a height still to work out")
      (db/query (peaks/shared) ["UPDATE osm_peaks SET prominence = 300 WHERE osm_id = 1"])
      (is (= "ready" (state ds "46,8")))
      (is (= 1 (:downloaded (peaks/squares-status ds)))))
    (testing "only a square still waiting can be cancelled"
      (is (thrown? Exception (peaks/cancel-square! "46,8")))
      (peaks/cancel-square! "47,8")
      (is (nil? (state ds "47,8"))))
    (testing "the athlete's bags are counted per square"
      (peaks/bag! ds 1 "2026-08-01")
      (is (= 1 (:bagged (first (filter #(= "46,8" (:square %)) (:squares (peaks/squares-status ds))))))))))

(defn- queued [] (set (map :square (#'peaks/requested-squares))))

(defn- track-activity! [ds id sport lat lon]
  (db/upsert-activities! ds [{:id id :date "2026-07-14" :sport sport :name "Ride" :raw {} :fetched_at "x"}])
  (db/upsert-metrics! ds [{:activity_id id :track [(track/encode [[lat lon] [(+ lat 0.01) (+ lon 0.01)]])]}]))

(deftest squares-under-tracks-are-queued
  (let [a (temp-ds)
        b (temp-db db/migrate!)]
    (track-activity! a 1 "hiking" 46.5 8.1)
    (track-activity! a 2 "virtual_ride" 10.5 20.5)
    (testing "the first time: the whole history, virtual rides left out, with Switzerland's default untouched"
      (is (= 1 (#'peaks/queue-track-squares! a [])))
      (is (= #{"46,8"} (queued))))
    (testing "afterwards only what is pending: nothing, then a new activity somewhere else"
      (is (zero? (#'peaks/queue-track-squares! a [])))
      (track-activity! a 3 "cycling" 47.5 9.2)
      (let [row (first (db/query a ["SELECT track FROM activity_metrics WHERE activity_id = 3"]))]
        (is (= 1 (#'peaks/queue-track-squares! a [{:id 3 :sport "cycling" :track (:track row)}]))))
      (is (= #{"46,8" "47,9"} (queued))))
    (testing "another athlete over the same square adds nothing"
      (track-activity! b 1 "hiking" 46.6 8.2)
      (is (zero? (#'peaks/queue-track-squares! b []))))
    (testing "tracks are not limited to the manual cap"
      (is (= 120 (#'peaks/queue-squares! (for [la (range -60 60)] (str la ",0"))))))))

(deftest load-squares-fetches-in-order-and-stops-at-a-failure
  (temp-ds)
  (peaks/add-squares! ["46,7" "46,8" "46,9"])
  (let [calls (atom [])]
    (with-redefs [peaks/fetch-box! (fn [bbox]
                                     (swap! calls conj bbox)
                                     (when (= [46 8 47 9] bbox) (throw (Exception. "Overpass busy")))
                                     (fetched! (summits/square-key (first bbox) (second bbox)))
                                     0)]
      (is (= {:chunks 1 :failed? true} (#'peaks/load-squares!)))
      (is (= [[46 7 47 8] [46 8 47 9]] @calls) "stopped at the first failure, the third was not tried")
      (is (= "Overpass busy" (:message (second (#'peaks/requested-squares))))))))

(deftest searching
  (temp-ds)
  (let [calls (atom 0)
        hit [{:name "Nepal" :display_name "Nepal" :type "country" :boundingbox ["26.3" "30.5" "80.0" "88.2"]}]]
    (with-redefs [peaks/search-http (fn [_ _] (swap! calls inc) hit)]
      (is (= "Nepal" (:name (first (peaks/search-areas "Nepal" "en")))))
      (peaks/search-areas " nepal " "en")
      (is (= 1 @calls) "the same question twice asks once"))))

(deftest compact-map-rows
  (let [ds (temp-ds)]
    (peaks/save-peaks! [(peak 1 "Big" 3000.0 46.5 412.6) (peak 2 "Bump" 40.0 46.6 12.0)])
    (is (= [[1 46.5 8.0 3000.0 413 0 false "Big" nil]] (peaks/map-peaks ds)))))

(defn- png
  "A Terrarium tile of one height: metres + 32768 = R·256 + G."
  [metres]
  (let [v (+ metres 32768) img (BufferedImage. 256 256 BufferedImage/TYPE_INT_RGB)
        rgb (bit-or (bit-shift-left (quot v 256) 16) (bit-shift-left (rem v 256) 8))]
    (dotimes [y 256] (dotimes [x 256] (.setRGB img x y rgb)))
    (let [o (ByteArrayOutputStream.)] (ImageIO/write img "png" o) (.toByteArray o))))

(deftest elevation-tiles-are-downloaded-once
  (let [dir (doto (java.io.File/createTempFile "coach-dem" "") .delete .mkdirs)
        downloads (atom 0)]
    (paths/use-shared-dir! dir)
    (with-redefs [peaks/download-dem-png (fn [_ _ _] (swap! downloads inc) (png 1234))]
      (let [dem-tile #'peaks/dem-tile]
        (is (= 1234.0 (double (aget ^floats (dem-tile 13 4270 2905) 0))))
        (is (= 1 @downloads))
        (testing "a second look (also after a restart: the memory cache is cleared) reads the disk"
          (.clear ^java.util.Map @#'peaks/dem-cache)
          (is (= 1234.0 (double (aget ^floats (dem-tile 13 4270 2905) 100))))
          (is (= 1 @downloads)))
        (testing "no data (open sea) is flat at 0 m"
          (with-redefs [peaks/download-dem-png (fn [_ _ _] nil)]
            (is (= 0.0 (double (aget ^floats (dem-tile 13 1 1) 5))))))
        (peaks/clean-dem-cache!)
        (is (not (.exists (io/file (paths/shared-file "cache" "dem")))) "cleaned up")))))

(deftest peaks-are-shared-bags-are-not
  (let [a (temp-ds)
        b (temp-db db/migrate!)]
    (peaks/save-peaks! [(peak 1 "Big" 3000.0 46.5 400.0) (peak 2 "Other" 2500.0 46.6 300.0)])
    (peaks/bag! a 1 "2026-08-01")
    (peaks/bag! b 2 "2026-08-02")
    (is (= #{"Big" "Other"} (set (map :name (peaks/visible-peaks a)))) "the peaks are the same ...")
    (is (= (set (map :name (peaks/visible-peaks a))) (set (map :name (peaks/visible-peaks b)))))
    (is (= ["Big"] (map :name (:peaks (peaks/summary a)))) "... the bags are not")
    (is (= ["Other"] (map :name (:peaks (peaks/summary b)))))
    (testing "a square asked for by one is there for the other"
      (peaks/add-squares! ["46,7"])
      (is (= ["46,7"] (map :square (:squares (peaks/squares-status b))))))))

(deftest locks
  (testing "a lock held by someone else is busy for try-lock, and free again afterwards"
    (let [dir (doto (java.io.File/createTempFile "coach-shared" "") .delete .mkdirs)]
      (paths/use-shared-dir! dir)
      (is (= :done (paths/try-lock "t" (fn [] :done))))
      (let [inner (atom nil)]
        (paths/with-lock "t" (fn [] (reset! inner @(future (paths/try-lock "t" (fn [] :done))))))
        (is (= paths/busy @inner)))
      (is (thrown? Exception (paths/with-lock "t" (fn [] (throw (Exception. "x"))))))
      (is (= :done (paths/try-lock "t" (fn [] :done))) "released after an exception"))))
