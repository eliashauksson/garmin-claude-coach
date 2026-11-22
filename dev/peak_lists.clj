;; Generates resources/peaks/lists.edn: the peak lists the Peaks page shows progress for (`make peak-lists`).
;; Four-thousanders of the Swiss Alps: the named OSM peaks of 4000 m and over in the Valais, Bernese
;; Oberland, Engadine and Monte Rosa area, minus the subsidiary points of a mountain (a "Vorgipfel", a
;; shoulder, one of the Breithorn's lesser tops). The elevation model can't tell those apart reliably on
;; snow and ice (it is smoothed: Monte Rosa comes out 100 m low), so they are left out by name.
;; The result is a starting point: check it by hand against the list the athlete follows.
(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[coach.peaks :as peaks])

(def box [45.8 7.0 46.7 10.0])

(def subsidiary
  #"(?i)vorgipfel|nordgipfel|grenzgipfel|westlicher|épaule|naso |gemello|roccia nera|burnaby|perrucchetti|spedla|croissant|piramide|pointe |aiguille|gendarme|sattelkuppe|tyndall|balmenhorn|entdeckungsfelsen|muzio|giordani|scoperta|corno nero|epaule")

(def four-thousanders
  (let [q (format "[out:json][timeout:90];node[\"natural\"=\"peak\"][\"ele\"~\"^4[0-9]{3}\"](%s,%s,%s,%s);out;"
                  (box 0) (box 1) (box 2) (box 3))]
    (->> (:elements (#'peaks/overpass q))
         (filter #(and (:name (:tags %)) (peaks/parse-ele (:ele (:tags %)))
                       (not (re-find subsidiary (:name (:tags %))))))
         (map (fn [{:keys [id lat lon tags]}]
                {:osm_id id :name (:name tags) :ele (peaks/parse-ele (:ele tags)) :lat lat :lon lon}))
         (sort-by (comp - :ele))
         (reduce (fn [acc p] (if (some #(= (:name %) (:name p)) acc) acc (conj acc p))) [])))) ; one per name

(spit "resources/peaks/lists.edn"
      (with-out-str
        (pp/pprint [{:id "alps-4000" :name "Four-thousanders of the Swiss Alps" :peaks four-thousanders}])))
(println (count four-thousanders) "peaks written:" (str/join ", " (map :name four-thousanders)))
(shutdown-agents)
