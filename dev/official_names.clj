;; Cuts swisstopo's swissNAMES3D (the official names of Swiss summits and hills) down to the file the app downloads
;; with the prominence blocks.
;;   make official-names ZIP=swissnames3d_2026_2056.csv.zip OUT=some/folder
;; ZIP: https://data.geo.admin.ch/ch.swisstopo.swissnames3d/swissnames3d_2026/swissnames3d_2026_2056.csv.zip
;; Writes OUT/official-ch.csv.gz: lines `lat,lon,ele,name` (WGS84; metres), one per summit (a name that exists in
;; several languages is one row: the German one wins). Only summit-like objects count, no passes or fields.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '(java.util.zip GZIPOutputStream ZipFile))

(def kinds #{"Alpiner Gipfel" "Hauptgipfel" "Gipfel" "Felskopf" "Haupthuegel" "Huegel"})

(defn lv95->wgs84
  "swisstopo's approximate formulas (about 1 m): [lat lon] from E, N."
  [e n]
  (let [y (/ (- e 2600000.0) 1e6) x (/ (- n 1200000.0) 1e6)
        lon (+ 2.6779094 (* 4.728982 y) (* 0.791484 y x) (* 0.1306 y x x) (- (* 0.0436 y y y)))
        lat (+ 16.9023892 (* 3.238272 x) (- (* 0.270978 y y)) (- (* 0.002528 x x)) (- (* 0.0447 y y x))
               (- (* 0.0140 x x x)))]
    [(* lat (/ 100.0 36.0)) (* lon (/ 100.0 36.0))]))

(let [[zip-path out-dir] *command-line-args*
      _ (when-not (and zip-path out-dir) (println "usage: official_names.clj ZIP OUT") (System/exit 1))
      out (doto (io/file out-dir) .mkdirs)
      rows (java.util.LinkedHashMap.)] ; uuid → [german? line]
  (with-open [zf (ZipFile. ^String zip-path)]
    (let [entry (.getEntry zf "swissNAMES3D_PKT.csv")]
      (with-open [r (io/reader (.getInputStream zf entry) :encoding "UTF-8")]
        (let [lines (line-seq r)
              header (str/split (str/replace (first lines) "﻿" "") #";")
              col (into {} (map-indexed (fn [i h] [h i])) header)]
          (doseq [line (rest lines)
                  :let [f (.split ^String line ";" -1)
                        at #(aget f (col %))]
                  :when (and (> (alength f) (col "Z")) (kinds (at "OBJEKTART")) (seq (at "NAME")))]
            (let [german? (str/starts-with? (at "SPRACHCODE") "Hochdeutsch")
                  [lat lon] (lv95->wgs84 (parse-double (at "E")) (parse-double (at "N")))
                  z (parse-double (at "Z"))
                  hoehe (parse-double (at "HOEHE"))
                  line (format "%.5f,%.5f,%s,%s" lat lon
                               (if-let [h (or hoehe z)] (format "%.1f" h) "")
                               (str/replace (at "NAME") "," " "))
                  old (.get rows (at "UUID"))]
              (when (or (nil? old) (and german? (not (first old))))
                (.put rows (at "UUID") [german? line])))))))
    (with-open [w (io/writer (GZIPOutputStream. (io/output-stream (io/file out "official-ch.csv.gz"))))]
      (doseq [[_ line] (.values rows)] (.write w ^String line) (.write w "\n")))
    (println (count rows) "summits written to" (str (io/file out "official-ch.csv.gz")))))
