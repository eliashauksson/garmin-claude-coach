(ns coach.summits-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.summits :as summits]))

(def peak {:id 1 :lat 46.5 :lon 8.0 :ele 3000.0})

(defn- walk
  "A stream passing `off` metres east of the peak, going north, at altitude `alt`."
  [off alt]
  (let [dlon (/ off (* 111320.0 (Math/cos (Math/toRadians 46.5))))
        pts (range -20 21)]
    {:lat (mapv #(+ 46.5 (* % 0.0001)) pts)
     :lon (mapv (constantly (+ 8.0 dlon)) pts)
     :alt (mapv (constantly alt) pts)
     :t (vec (range (count pts)))}))

(deftest detect-passes
  (testing "passing 40 m away at the summit's height bags it"
    (let [[hit :as hits] (summits/detect (walk 40 2995.0) [peak])]
      (is (= 1 (count hits)))
      (is (= 1 (:id hit)))
      (is (< (Math/abs (- 40.0 (:dist_m hit))) 2.0))))
  (testing "passing 150 m away does not"
    (is (empty? (summits/detect (walk 150 2995.0) [peak]))))
  (testing "a road 400 m below the summit (or a tunnel) does not, a track just below it does"
    (is (empty? (summits/detect (walk 10 2600.0) [peak])))
    (is (= 1 (count (summits/detect (walk 10 2940.0) [peak])))))
  (testing "without altitude only the distance counts"
    (is (= 1 (count (summits/detect (dissoc (walk 10 0) :alt) [peak])))))
  (testing "a gap in the fixes is not a line that was walked"
    (let [s {:lat [46.49 46.51] :lon [8.0 8.0]}]
      (is (empty? (summits/detect s [peak])))))
  (testing "no GPS"
    (is (empty? (summits/detect {:lat [nil nil] :lon [nil nil]} [peak])))
    (is (empty? (summits/detect {} [peak])))))

(deftest tiles
  (testing "keys and boxes round trip"
    (is (= "186:32" (summits/tile-key 46.5 8.0))))
  (testing "a long straight piece covers every tile it crosses"
    (is (= #{"184:32" "184:33" "184:34" "184:35"}
           (summits/tiles-for-track [[[46.1 8.1] [46.1 8.9]]]))))
  (testing "the border pulls in the tile next door"
    (is (= #{"183:32" "184:32"} (summits/tiles-for-track [[[46.0001 8.1] [46.0002 8.1]]])))))

(deftest eligibility
  (is (summits/eligible? "hiking"))
  (is (summits/eligible? "road_biking"))
  (is (not (summits/eligible? "virtual_ride")))
  (is (not (summits/eligible? "indoor_cycling")))
  (is (not (summits/eligible? "treadmill_running"))))

(deftest visibility
  (is (summits/visible? {:prominence 45.0} 30))
  (is (not (summits/visible? {:prominence 12.0} 30)))
  (is (not (summits/visible? {:prominence nil} 30)) "unknown prominence stays hidden")
  (is (summits/visible? {:prominence 5.0 :listed true} 30) "list peaks skip the filter"))

(deftest progress
  (let [p (summits/list-progress {:id "x" :name "X" :peaks [{:peak_id 1} {:peak_id 2} {:peak_id 3}]} [2 9])]
    (is (= 3 (:total p)))
    (is (= 1 (:bagged p)))
    (is (= [false true false] (mapv :bagged? (:peaks p))))))

(deftest squares
  (testing "keys are whole degrees rounded down"
    (is (= "46,7" (summits/square-key 46.5 7.9)))
    (is (= "-1,-1" (summits/square-key -0.5 -0.1)) "negative: down, not towards zero")
    (is (= [46 7 47 8] (summits/square-bbox "46,7")))
    (is (= [45 6] (summits/parse-square "45,6")))
    (is (not-any? summits/valid-square? ["46" "a,b" "90,0" "46,180" "-86,0" nil "46,7,1"])))
  (testing "squares covering a box"
    (is (= 18 (count (summits/squares-in [45.75 5.75 48.0 10.5]))) "Switzerland: 3 rows x 6 columns")
    (is (= ["46,7"] (summits/squares-in [46.1 7.1 46.9 7.9])))
    (is (= #{"10,179" "10,-180"} (set (summits/squares-in [10.5 179.5 10.9 -179.5]))) "across the date line")
    (is (= (* 170 360) (count (summits/squares-in [-90 -181 90 181]))) "kept on the globe"))
  (testing "tiles of a box and of a square"
    (is (= #{"184:32" "185:32" "186:32" "187:32" "184:33" "185:33" "186:33" "187:33"}
           (summits/tiles-in [46.0 8.0 47.0 8.5])))
    (is (= 16 (count (summits/tiles-of-square "46,7"))))
    (is (contains? (summits/tiles-in [63.25 -24.5 66.5 -13.25]) "253:-98"))))

(deftest search-result
  (let [hit {:name "Nepal" :display_name "Nepal" :type "administrative" :boundingbox ["26.3" "30.5" "80.0" "88.2"]
             :geojson {:type "Polygon"}}
        r (summits/search-result hit)]
    (is (= [26.3 80.0 30.5 88.2] (:bbox r)) "Nominatim gives south, north, west, east")
    (is (= "Nepal" (:name r)))
    (is (false? (:too_big r)))
    (is (= (* 5 9) (count (:squares r))) "rows 26 to 30, columns 80 to 88")
    (is (= {:type "Polygon"} (:outline r))))
  (let [r (summits/search-result {:display_name "Russia" :boundingbox ["41.2" "81.9" "19.6" "180"] :type "country"})]
    (is (:too_big r))
    (is (empty? (:squares r)) "nothing is selected for a place that is too big")))

(deftest dataset-ids-from-positions
  (let [a (summits/dataset-peak-id 46.5 8.0) b (summits/dataset-peak-id 46.5001 8.0)]
    (is (summits/dataset-peak-id? a))
    (is (not (summits/dataset-peak-id? 13800000000)) "OSM ids are below")
    (is (not= a b))
    (is (= a (summits/dataset-peak-id 46.50000001 7.99999999)) "the same 4 decimals, the same id")
    (is (< (summits/dataset-peak-id 84.9999 179.9999) 9007199254740992) "safe in JSON")
    (is (summits/dataset-peak-id? (summits/dataset-peak-id -84.9 -179.9)))))

(defn- summit [id lat lon ele] {:id id :lat lat :lon lon :ele ele})
(defn- donor [id lat lon ele rank & [claim?]]
  {:id id :lat lat :lon lon :ele ele :rank rank :claim? (if (nil? claim?) true claim?)})

(deftest matching-names-to-summits
  (let [top (summit 1 46.5 8.0 4430.0)    ; dataset height, cut by the terrain model
        other (summit 2 46.51 8.0 3000.0)
        m #(summits/match-donors [top other] %)]
    (testing "a donor 50 m away with the surveyed height (75 m higher) names the summit"
      (is (= {1 505} (update-vals (:named (m [(donor 505 46.50045 8.0 4505.0 2)])) :id))))
    (testing "too far, or a height that can't be the same peak"
      (is (empty? (:named (m [(donor 5 46.504 8.0 4430.0 2)]))) "450 m away")
      (is (empty? (:named (m [(donor 6 46.5 8.0 4800.0 2)]))) "370 m above the summit")
      (is (empty? (:named (m [(donor 7 46.5 8.0 4300.0 2)]))) "130 m below: the dataset is never that much higher"))
    (testing "without a height only very close counts"
      (is (= [1] (keys (:named (m [(donor 8 46.5005 8.0 nil 2)])))))
      (is (empty? (:named (m [(donor 9 46.5015 8.0 nil 2)])))))
    (testing "one name per summit, the nearest wins; the other point is still the same peak"
      (let [r (m [(donor 10 46.5003 8.0 4430.0 2) (donor 11 46.5001 8.0 4430.0 2)])]
        (is (= 11 (:id (get (:named r) 1))))
        (is (= {10 1, 11 1} (:links r)))))
    (testing "a lower rank goes first even when farther"
      (let [r (m [(donor 12 46.5001 8.0 4430.0 2) (donor 13 46.5008 8.0 4430.0 0)])]
        (is (= 13 (:id (get (:named r) 1))))
        (is (= 1 (get (:links r) 12)))))
    (testing "a point without a name never takes a summit but is linked"
      (let [r (m [(donor 14 46.5001 8.0 4430.0 2 false)])]
        (is (empty? (:named r)))
        (is (= {14 1} (:links r)))))
    (testing "two summits, two donors: each gets its own"
      (let [r (m [(donor 15 46.5 8.0 4430.0 2) (donor 16 46.51 8.0 3000.0 2)])]
        (is (= {1 15, 2 16} (update-vals (:named r) :id)))))))
