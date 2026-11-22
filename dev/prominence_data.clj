;; Cuts Andrew Kirmse's and Jonathan de Ferranti's world prominence results (all-peaks-p100-2023.zip, every summit
;; with at least 100 ft of prominence) into 10° × 10° blocks the app downloads as it needs them.
;;   make prominence-data ZIP=all-peaks-p100-2023.zip OUT=some/folder
;; Writes OUT/prominence-<south>_<west>.csv.gz (lines: lat,lon,elevation,prominence; metres) and OUT/blocks.edn
;; ({"40_0" rows, …}), which goes to resources/peaks/ so the app knows which blocks exist. The blocks are
;; uploaded to the `peaks-data-2023` release of the repo (see CLAUDE.md).
(require '[clojure.java.io :as io]
         '[clojure.pprint :as pp])
(import '(java.util.zip GZIPOutputStream ZipFile))

(defn- block-of [lat lon]
  (str (* 10 (Math/floorDiv (long (Math/floor lat)) 10)) "_" (* 10 (Math/floorDiv (long (Math/floor lon)) 10))))

(let [[zip-path out-dir] *command-line-args*
      _ (when-not (and zip-path out-dir) (println "usage: prominence_data.clj ZIP OUT") (System/exit 1))
      out (doto (io/file out-dir) .mkdirs)
      writers (java.util.HashMap.)
      counts (java.util.HashMap.)
      writer (fn [block]
               (or (.get writers block)
                   (let [w (java.io.PrintWriter.
                            (io/writer (GZIPOutputStream. (io/output-stream (io/file out (str "prominence-" block ".csv.gz"))))))]
                     (.put writers block w) w)))]
  (with-open [zf (ZipFile. ^String zip-path)]
    (let [entry (first (enumeration-seq (.entries zf)))]
      (with-open [r (io/reader (.getInputStream zf entry))]
        (doseq [^String line (line-seq r)
                :let [f (.split line "," -1)]
                :when (= 6 (alength f))]
          (let [lat (Double/parseDouble (aget f 0)) lon (Double/parseDouble (aget f 1))
                block (block-of lat lon)
                w ^java.io.PrintWriter (writer block)]
            (.print w (aget f 0)) (.print w ",") (.print w (aget f 1)) (.print w ",")
            (.print w (format "%.1f,%.1f\n" (Double/parseDouble (aget f 2)) (Double/parseDouble (aget f 5))))
            (.put counts block (inc (long (.getOrDefault counts block 0)))))))))
  (doseq [^java.io.Writer w (.values writers)] (.close w))
  (spit (io/file out "blocks.edn") (with-out-str (pp/pprint (into (sorted-map) counts))))
  (let [files (filter #(.endsWith (.getName ^java.io.File %) ".csv.gz") (.listFiles out))
        total (reduce + (map #(.length ^java.io.File %) files))]
    (println (format "%d blocks, %d summits, %.1f MB gzip; biggest block %.1f MB"
                     (count files) (reduce + (vals counts)) (/ total 1e6)
                     (/ (apply max (map #(.length ^java.io.File %) files)) 1e6)))
    (doseq [b ["40_0" "40_10" "50_0" "40_-10"]]
      (when-let [f (io/file out (str "prominence-" b ".csv.gz"))]
        (when (.exists f) (println "  block" b ":" (get counts b) "summits," (format "%.2f MB" (/ (.length f) 1e6))))))))
