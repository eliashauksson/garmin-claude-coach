(ns coach.peaks-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.db :as db]
            [coach.paths :as paths]
            [coach.peaks :as peaks]
            [coach.summits :as summits]
            [coach.track :as track]
            [next.jdbc :as jdbc])
  (:import (java.io ByteArrayOutputStream)
           (java.util.zip GZIPOutputStream)))

(defn- temp-db [migrate!]
  (let [f (java.io.File/createTempFile "coach-peaks" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) migrate!)))

(defn- temp-ds
  "A fresh athlete database, on a fresh shared peaks database."
  []
  (peaks/use-shared! (temp-db db/migrate-peaks!))
  (temp-db db/migrate!))

(defn- gz [lines]
  (let [o (ByteArrayOutputStream.)]
    (with-open [w (java.io.OutputStreamWriter. (GZIPOutputStream. o) "UTF-8")]
      (doseq [l lines] (.write w ^String l) (.write w "\n")))
    (.toByteArray o)))

(def ^:private id-big (summits/dataset-peak-id 46.5 8.0))
(def ^:private id-bump (summits/dataset-peak-id 46.6 8.0))

;; block 40_0 holds 46.x N, 8.x E: a big summit (the model cuts it: 2900 m, the survey says 3000)
;; and a bump of 35 m of prominence
(defn- fake-release
  "A stand-in for the release: files by name; counts how often each was asked for."
  [asked]
  {"prominence-40_0.csv.gz" (gz ["46.5,8.0,2900.0,400.0" "46.6,8.0,1200.0,35.0" "broken line"])
   "official-ch.csv.gz" (gz ["46.5002,8.0002,3000.0,Gross Horn" "46.9,8.9,2000.0,Elsewhere"])
   :asked asked})

(defn- with-release [release f]
  (with-redefs [peaks/fetch-data (fn [file]
                                   (swap! (:asked release) conj file)
                                   (or (get release file) (throw (ex-info (str "no " file) {}))))]
    (f)))

(defn- osm-peak [id name ele lat lon]
  {:osm_id id :name name :ele ele :lat lat :lon lon :tags "{}"})

(defn- load-all! []
  (with-redefs [peaks/fetch-box! (fn [bbox]
                                   (let [now "2026-01-01T00:00:00Z"]
                                     (doseq [t (summits/tiles-in bbox)]
                                       (jdbc/execute! (peaks/shared)
                                                      ["INSERT OR REPLACE INTO peak_tiles (tile, peaks, fetched_at) VALUES (?, 0, ?)" t now]))
                                     0))]
    [(#'peaks/load-datasets!) (#'peaks/load-names!)]))

(defn- activity! [ds id sport]
  (db/upsert-activities! ds [{:id id :date "2026-07-14" :sport sport :name "Hike" :raw {} :fetched_at "x"}])
  (db/upsert-metrics! ds [{:activity_id id :track ["abc"]}])
  ;; walking north past 46.5 N / 8.0 E, 30 m to the east
  (db/save-stream! ds id {:lat (mapv #(+ 46.49 (* % 0.0001)) (range 200))
                          :lon (vec (repeat 200 8.0004))
                          :alt (vec (repeat 200 2990.0))
                          :t (vec (range 200))}
                   nil))

(defn- peak-named [ds name] (first (filter #(= name (:name %)) (peaks/visible-peaks ds))))

(defn- set-up!
  "An athlete database on a shared one that has loaded the fake release for the square 46,8."
  []
  (let [ds (temp-ds) asked (atom [])]
    (peaks/add-squares! ["46,8"])
    (with-release (fake-release asked) load-all!)
    [ds asked]))

(deftest parse-ele
  (is (= 3000.0 (peaks/parse-ele "3,000")))
  (is (= 1234.0 (peaks/parse-ele "1234 m")))
  (is (= 4478.4 (peaks/parse-ele "4478.4")))
  (is (nil? (peaks/parse-ele "many")))
  (is (nil? (peaks/parse-ele nil))))

(deftest a-block-brings-the-peaks-unnamed
  (let [ds (temp-ds) asked (atom [])]
    (peaks/add-squares! ["46,8"])
    (with-release (fake-release asked)
      (fn []
        (is (= 1 (:blocks (#'peaks/load-datasets!))))
        (testing "the official names of Switzerland arrive with the first Swiss square; the broken line is skipped"
          (is (= #{"prominence-40_0.csv.gz" "official-ch.csv.gz"} (set @asked))))
        (testing "a second run downloads nothing"
          (is (= 0 (:blocks (#'peaks/load-datasets!))))
          (is (= 2 (count @asked))))))
    (is (= #{id-big id-bump} (set (map :id (db/query (peaks/shared) ["SELECT id FROM peaks"])))))
    (is (= #{id-big id-bump} (set (map :id (peaks/visible-peaks ds)))) "the dataset has nothing under 30 m")))

(deftest names-and-heights-from-the-best-source
  (let [[ds _] (set-up!)]
    (testing "swisstopo names the summit and gives its surveyed height (the dataset's model is 100 m low)"
      (let [p (peak-named ds "Gross Horn")]
        (is (= id-big (:id p)))
        (is (= 3000.0 (:ele p)))
        (is (= "swisstopo" (:name_src (first (db/query (peaks/shared)
                                                       ["SELECT name_src FROM peaks WHERE id = ?" id-big])))))))
    (testing "OpenStreetMap names what swisstopo left unnamed; swisstopo keeps what it named"
      (peaks/save-peaks! [(osm-peak 5 "Osm Name" 3002.0 46.5001 8.0001) (osm-peak 6 "Small Bump" 1210.0 46.6003 8.0)])
      (#'peaks/rematch-square! "46,8")
      (is (= "Gross Horn" (:name (peak-named ds "Gross Horn"))))
      (is (nil? (peak-named ds "Osm Name")))
      (is (= id-bump (:id (peak-named ds "Small Bump")))))
    (testing "an unmatched OSM point creates no peak"
      (peaks/save-peaks! [(osm-peak 7 "Far Away" 2000.0 46.55 8.5)])
      (#'peaks/rematch-square! "46,8")
      (is (nil? (peak-named ds "Far Away"))))
    (testing "running it again changes nothing"
      (let [before (db/query (peaks/shared) ["SELECT * FROM peaks ORDER BY id"])]
        (#'peaks/rematch-square! "46,8")
        (is (= before (db/query (peaks/shared) ["SELECT * FROM peaks ORDER BY id"])))))))

(deftest minimum-prominence
  (let [[ds _] (set-up!)]
    (is (= 2 (count (peaks/visible-peaks ds))) "both are at least 30 m")
    (peaks/set-min-prominence! ds 100)
    (is (= [id-big] (map :id (peaks/visible-peaks ds))))))

(deftest listed-peaks
  (let [ds (temp-ds) asked (atom [])]
    (with-redefs [peaks/lists (fn [] [{:id "l" :name "L"
                                       :peaks [{:osm_id 9 :name "Listed Horn" :ele 3001.0 :lat 46.5003 :lon 8.0}
                                               {:osm_id 10 :name "Lonely" :ele 500.0 :lat 10.0 :lon 10.0}]}])]
      (peaks/seed-lists!)
      (testing "a listed peak is shown before any block is there, under its own id"
        (is (= #{9 10} (set (map :id (peaks/visible-peaks ds))))))
      (peaks/add-squares! ["46,8"])
      (with-release (fake-release asked) #(#'peaks/load-datasets!))
      (testing "the dataset's summit takes it over: named and listed; the placeholder is gone; the list follows"
        (is (= #{id-big 10} (set (map :id (filter :listed (peaks/visible-peaks ds))))))
        (is (= "Listed Horn" (:name (peak-named ds "Listed Horn"))) "the list's name outranks swisstopo's")
        (let [l (first (:lists (peaks/summary ds)))]
          (peaks/bag! ds id-big "2026-08-01")
          (is (= 1 (:bagged (first (:lists (peaks/summary ds))))))
          (is (= 2 (:total l))))))))

(deftest detection-and-dismissing
  (let [[ds _] (set-up!)]
    (activity! ds 10 "hiking")
    (is (= 1 (peaks/detect-activity! ds {:id 10 :date "2026-07-14" :sport "hiking"})))
    (let [[s :as visits] (peaks/activity-summits ds 10)]
      (is (= 1 (count visits)))
      (is (= "Gross Horn" (:name s)))
      (testing "a GPS bag is dismissed, not deleted, so detection doesn't bring it back"
        (peaks/unbag! ds (:visit_id s))
        (is (empty? (peaks/activity-summits ds 10)))
        (peaks/detect-activity! ds {:id 10 :date "2026-07-14" :sport "hiking"})
        (is (empty? (peaks/activity-summits ds 10)))))
    (testing "manual bags"
      (let [id (peaks/bag! ds id-big "2026-08-01")]
        (is (= 1 (:bagged (peaks/summary ds))))
        (is (= 1 (:visits (peaks/summary ds))))
        (peaks/unbag! ds id)
        (is (zero? (:bagged (peaks/summary ds))))))
    (testing "bad input"
      (is (thrown? Exception (peaks/bag! ds 99 "2026-08-01")))
      (is (thrown? Exception (peaks/bag! ds id-big "yesterday"))))))

(deftest virtual-rides-bag-nothing
  (let [[ds _] (set-up!)]
    (activity! ds 11 "virtual_ride")
    (is (zero? (peaks/detect-activity! ds {:id 11 :date "2026-07-14" :sport "virtual_ride"})))
    (is (empty? (peaks/activity-summits ds 11)))))

(deftest bags-made-under-osm-ids-follow-their-summit
  (let [[ds _] (set-up!)]
    (peaks/save-peaks! [(osm-peak 5 "Osm Name" 3002.0 46.5001 8.0001)])
    (#'peaks/rematch-square! "46,8")
    (testing "the athlete's old rows hold the OSM id"
      (jdbc/execute! ds ["INSERT INTO summits (peak_id, activity_id, date, source, created_at)
                          VALUES (5, NULL, '2026-05-01', 'manual', 'x'), (77, NULL, '2026-05-02', 'manual', 'x')"])
      (is (= 1 (#'peaks/relink-summits! ds)))
      (is (= [id-big 77] (map :peak_id (db/query ds ["SELECT peak_id FROM summits ORDER BY id"])))
          "the one with a summit is moved, an OSM point that was no summit stays (hidden)"))))

(deftest old-summit-tables-are-renamed
  (let [f (java.io.File/createTempFile "coach-old" ".db") _ (.deleteOnExit f)
        ds (db/datasource (str f))]
    (jdbc/execute! ds ["CREATE TABLE summits (id INTEGER PRIMARY KEY AUTOINCREMENT, osm_id INTEGER NOT NULL,
                         activity_id INTEGER, date TEXT NOT NULL, time_s REAL, dist_m REAL, source TEXT NOT NULL,
                         created_at TEXT NOT NULL)"])
    (jdbc/execute! ds ["INSERT INTO summits (osm_id, date, source, created_at) VALUES (5, '2026-01-01', 'manual', 'x')"])
    (db/migrate! ds)
    (is (= [5] (map :peak_id (db/query ds ["SELECT peak_id FROM summits"]))))))

(defn- fetched! [k]
  (let [now "2026-01-01T00:00:00Z"]
    (doseq [t (summits/tiles-of-square k)]
      (jdbc/execute! (peaks/shared) ["INSERT OR REPLACE INTO peak_tiles (tile, peaks, fetched_at) VALUES (?, 0, ?)" t now]))))

(defn- block! [k]
  (jdbc/execute! (peaks/shared) ["INSERT OR REPLACE INTO dataset_blocks (block, rows, fetched_at) VALUES (?, 0, 'x')"
                                 (summits/square-block k)]))

(deftest squares
  (let [ds (temp-ds)
        state #(:state (first (filter (fn [s] (= %2 (:square s))) (:squares (peaks/squares-status %1)))))]
    (testing "asking for squares: new ones counted, repeats and bad keys refused"
      (is (= 2 (peaks/add-squares! ["46,7" "46,8"])))
      (is (= 1 (peaks/add-squares! ["46,8" "47,8"])))
      (is (thrown? Exception (peaks/add-squares! ["99,99"])))
      (is (thrown? Exception (peaks/add-squares! [])))
      (is (thrown? Exception (peaks/add-squares! (for [la (range -85 15) lo [0 1]] (str la "," lo)))) "over the cap"))
    (testing "states: queued, failed, names, ready"
      (is (= "queued" (state ds "46,8")))
      (jdbc/execute! (peaks/shared) ["UPDATE peak_squares SET message = 'busy' WHERE square = '46,8'"])
      (is (= "failed" (state ds "46,8")))
      (jdbc/execute! (peaks/shared) ["UPDATE peak_squares SET message = NULL WHERE square = '46,8'"])
      (block! "46,8")
      (is (= "names" (state ds "46,8")) "the peaks are loaded, OpenStreetMap's names still to come")
      (fetched! "46,8")
      (is (= "ready" (state ds "46,8")))
      (is (= 3 (:downloaded (peaks/squares-status ds))) "46,7 and 47,8 share the loaded block"))
    (testing "only a square still waiting can be cancelled"
      (is (thrown? Exception (peaks/cancel-square! "46,8")))
      (peaks/cancel-square! "47,8")
      (is (nil? (state ds "47,8"))))))

(deftest a-failed-block-is-noted-and-the-others-go-on
  (let [ds (temp-ds) asked (atom [])]
    (peaks/add-squares! ["10,10" "46,8"])
    (with-release (assoc (fake-release asked) "prominence-10_10.csv.gz" nil)
      (fn []
        (with-redefs [peaks/fetch-data (let [orig @#'peaks/fetch-data]
                                         (fn [file]
                                           (if (= "prominence-10_10.csv.gz" file)
                                             (throw (ex-info "offline" {}))
                                             (orig file))))]
          (let [r (#'peaks/load-datasets!)]
            (is (:failed? r))
            (is (= 1 (:blocks r)))))))
    (is (= "offline" (:message (first (filter #(= "10,10" (:square %)) (#'peaks/requested-squares))))))
    (is (= "failed" (:state (first (filter #(= "10,10" (:square %)) (:squares (peaks/squares-status ds))))))))
    (testing "a square whose block another square loaded meanwhile loses its old error"
      (jdbc/execute! (peaks/shared) ["UPDATE peak_squares SET message = 'old 404' WHERE square = '46,8'"])
      (with-release (fake-release (atom [])) #(#'peaks/load-datasets!))
      (is (nil? (:message (first (filter #(= "46,8" (:square %)) (#'peaks/requested-squares))))))))

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

(deftest queueing-at-the-same-moment
  (temp-ds)
  (let [keys (for [la (range 0 40)] (str la ",5"))
        new (doall (pmap (fn [_] (#'peaks/queue-squares! keys)) (range 4)))]
    (is (= 40 (reduce + new)) "every square is new exactly once, however many workers queue it")
    (is (= 40 (count (queued))))))

(deftest names-are-fetched-in-order-and-stop-at-a-failure
  (temp-ds)
  (peaks/add-squares! ["46,7" "46,8" "46,9"])
  (let [calls (atom [])]
    (with-redefs [peaks/fetch-box! (fn [bbox]
                                     (swap! calls conj bbox)
                                     (when (= [46 8 47 9] bbox) (throw (Exception. "Overpass busy")))
                                     (fetched! (summits/square-key (first bbox) (second bbox)))
                                     0)]
      (is (= {:chunks 1 :failed? true} (#'peaks/load-names!)))
      (is (= [[46 7 47 8] [46 8 47 9]] @calls) "stopped at the first failure, the third was not tried")
      (is (= "Overpass busy" (:message (second (#'peaks/requested-squares))))))))

(deftest the-update-detects-before-the-names-arrive
  (let [ds (temp-ds) asked (atom [])]
    (activity! ds 12 "hiking")
    (db/upsert-metrics! ds [{:activity_id 12 :track [(track/encode [[46.49 8.0] [46.51 8.0]])]}])
    (with-release (fake-release asked)
      (fn []
        (with-redefs [peaks/fetch-box! (fn [_] (throw (Exception. "Overpass busy")))
                      peaks/retry-ms 1 peaks/max-attempts 1]
          (let [r (peaks/update! ds)]
            (is (:failed? r) "the names are still to come")
            (is (= 1 (:summits r)) "the bag is there already")))))
    (is (= 1 (count (peaks/activity-summits ds 12))))))

(deftest searching
  (temp-ds)
  (let [calls (atom 0)
        hit [{:name "Nepal" :display_name "Nepal" :type "country" :boundingbox ["26.3" "30.5" "80.0" "88.2"]}]]
    (with-redefs [peaks/search-http (fn [_ _] (swap! calls inc) hit)]
      (is (= "Nepal" (:name (first (peaks/search-areas "Nepal" "en")))))
      (peaks/search-areas " nepal " "en")
      (is (= 1 @calls) "the same question twice asks once"))))

(deftest compact-map-rows
  (let [[ds _] (set-up!)]
    (is (= [[id-big 46.5 8.0 3000.0 400 0 false "Gross Horn" nil]
            [id-bump 46.6 8.0 1200.0 35 0 false nil nil]]
           (sort-by first (peaks/map-peaks ds))))))

(deftest peaks-are-shared-bags-are-not
  (let [[a _] (set-up!)
        b (temp-db db/migrate!)]
    (peaks/bag! a id-big "2026-08-01")
    (peaks/bag! b id-bump "2026-08-02")
    (is (= (set (map :id (peaks/visible-peaks a))) (set (map :id (peaks/visible-peaks b))))
        "the peaks are the same ...")
    (is (= [id-big] (map :id (:peaks (peaks/summary a)))) "... the bags are not")
    (is (= [id-bump] (map :id (:peaks (peaks/summary b)))))
    (testing "a square asked for by one is there for the other"
      (peaks/add-squares! ["46,7"])
      (is (some #{"46,7"} (map :square (:squares (peaks/squares-status b))))))))

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

(deftest pause-only-when-busy
  (is (peaks/pause? 0.95 0.8))
  (is (not (peaks/pause? 0.5 0.8)))
  (is (not (peaks/pause? 0.8 0.8)))
  (testing "an unknown load never pauses"
    (is (not (peaks/pause? -1.0 0.8)))
    (is (not (peaks/pause? nil 0.8)))))
