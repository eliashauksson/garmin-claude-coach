(ns coach.peaks
  "Peak bagging. The peaks are the summits of Andrew Kirmse's and Jonathan de Ferranti's world prominence
  dataset (2023: every summit that stands at least 30 m above its saddle), loaded in 10° blocks from a
  release of this project's repository. We work out no heights ourselves. Names come from the peak lists
  (resources/peaks/lists.edn), from swisstopo's swissNAMES3D in Switzerland and from OpenStreetMap
  elsewhere (Overpass: the named peaks of a 0.25° tile, a 1° square at a time, each fetched once); each name
  is given to the dataset summit it matches (coach.summits/match-donors). The athlete's tracks never leave
  the machine: GitHub sees which blocks are loaded, Overpass the tile and square boxes.

  The peaks, the loaded blocks and the downloaded squares are the same for everybody, so they live once per
  install in a shared database (shared/peaks.db, `shared`); what an athlete bagged (`summits`) and their
  minimum prominence are in their own database. Functions without a `ds` argument work on the shared
  data; those with one on the athlete's.

  A peak is shown when its prominence reaches the athlete's minimum (30 m, the dataset's own limit, by
  default) or it is on a peak list."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.context :as context]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.paths :as paths]
            [coach.peak-density :as density]
            [coach.summits :as summits]
            [coach.track :as track]
            [next.jdbc :as jdbc])
  (:import (java.io ByteArrayInputStream)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.time Duration LocalDate)
           (java.util.zip GZIPInputStream)))

(def overpass-urls
  "The public Overpass servers, tried in turn when one is busy."
  ["https://overpass-api.de/api/interpreter" "https://overpass.kumi.systems/api/interpreter"])
(def data-url
  "Where the dataset's blocks are (assets of a release of this repository, made by `make prominence-data` and
  `make official-names`). $COACH_PEAKS_DATA_URL may point elsewhere: a URL or a folder."
  "https://github.com/eliashauksson/garmin-claude-coach/releases/download/peaks-data-2023")
(def default-min-prominence 30)
(def retry-ms "When a server was busy, the update tries the failed chunks again after this long …" 300000)
(def max-attempts "… up to this many times." 6)

(def cpu-limit
  "The machine's CPU use (0–1) above which peak bagging waits between its steps, so the dashboard stays quick."
  0.6)
(def ^:private pause-ms 250)
(def ^:private max-pause-ms "One wait is never longer than this, so the update always goes on." 10000)

(defn pause?
  "Pure: whether to wait, given the CPU load (0–1, negative when unknown) and the limit."
  [load limit]
  (and (number? load) (>= load 0) (> load limit)))

(defn- cpu-load []
  (.getCpuLoad ^com.sun.management.OperatingSystemMXBean
   (java.lang.management.ManagementFactory/getOperatingSystemMXBean)))

(defn- breathe!
  "Between two steps of work: while the machine is busier than `cpu-limit`, waits (at most `max-pause-ms`).
  Returns at once when it isn't, or when the load is unknown."
  []
  (loop [waited 0]
    (when (and (< waited max-pause-ms) (pause? (cpu-load) cpu-limit))
      (Thread/sleep (long pause-ms))
      (recur (+ waited pause-ms)))))

(def ^:private ^HttpClient http
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20))
      (.followRedirects java.net.http.HttpClient$Redirect/NORMAL) (.build)))

(def ^:private user-agent "garmin-claude-coach")

;; ---------------------------------------------------------------------------
;; The shared database

(defonce ^:private shared-ds (atom nil))

(defn use-shared!
  "Makes `ds` the shared database (for tests)."
  [ds]
  (reset! shared-ds ds))

(defn shared
  "The database every athlete of this install shares (peaks, loaded blocks, squares), opened once."
  []
  (or @shared-ds
      (locking shared-ds
        (or @shared-ds (reset! shared-ds (doto (db/peaks-datasource) db/migrate-peaks!))))))

(defn- meta-get [k] (:v (first (db/query (shared) ["SELECT v FROM meta WHERE k = ?" (name k)]))))

(defn- meta-set!
  ([k v] (meta-set! (shared) k v))
  ([ds k v]
   (jdbc/execute! ds ["INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                      (name k) (str v)])))

;; ---------------------------------------------------------------------------
;; Settings

(defn min-prominence [ds]
  (or (some-> (db/get-state ds :peak_min_prominence) parse-double) default-min-prominence))

(defn set-min-prominence! [ds m]
  (when-not (and (number? m) (<= 0 m 2000))
    (throw (ex-info "The minimum prominence must be between 0 and 2000 m." {:value m})))
  (db/set-state! ds :peak_min_prominence (double m)))

;; ---------------------------------------------------------------------------
;; Overpass: the named peaks of a tile

(defn parse-ele
  "OSM's `ele` tag as metres: \"3,000\", \"1234 m\", \"4478.4\"; nil when there is no number."
  [s]
  (some-> s str
          (str/replace #"(\d),(\d{3})(?!\d)" "$1$2") ; 3,000 is a thousand separator …
          (str/replace "," ".")                         ; … 12,5 a decimal comma
          (->> (re-find #"-?\d+(?:\.\d+)?"))
          parse-double))

(defn- form [m]
  (str/join "&" (for [[k v] m] (str (name k) "=" (URLEncoder/encode (str v) StandardCharsets/UTF_8)))))

(defn- overpass-once [url ^String query]
  (let [resp (.send http (-> (HttpRequest/newBuilder (URI/create url))
                             (.timeout (Duration/ofSeconds 150))
                             (.header "Content-Type" "application/x-www-form-urlencoded")
                             (.header "User-Agent" user-agent)
                             (.POST (HttpRequest$BodyPublishers/ofString (form {:data query})))
                             (.build))
                    (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (if (= 200 status)
      (json/read-str (.body resp) :key-fn keyword)
      (throw (ex-info (str "Overpass answered HTTP " status) {:status status})))))

(defn- overpass
  "The answer to an Overpass query: each server in turn, a few rounds with a pause when all are busy."
  [query]
  (loop [round 1]
    (let [r (some (fn [url] (try (overpass-once url query) (catch java.io.IOException _ nil)
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (#{429 502 503 504} (:status (ex-data e))) (throw e))
                                   nil)))
                  overpass-urls)]
      (cond
        r r
        (< round 4) (do (Thread/sleep (* 10000 round)) (recur (inc round)))
        :else (throw (ex-info "The Overpass servers are busy." {}))))))

(defn peaks-query
  "Overpass QL for every named natural=peak node in a [south west north east] box (the heights and the
  unnamed bumps come from the dataset)."
  [[s w n e]]
  (format "[out:json][timeout:120];node[\"natural\"=\"peak\"][\"name\"](%s,%s,%s,%s);out;" s w n e))

(defn- element->row [{:keys [id lat lon tags]}]
  {:osm_id id :name (:name tags) :ele (parse-ele (:ele tags)) :lat lat :lon lon :tags (json/write-str tags)})

(defn save-peaks!
  "Upserts OSM peak rows (the names to give) into the shared database; a peak on a list stays listed."
  [rows]
  (let [now (str (java.time.Instant/now))]
    (jdbc/with-transaction [tx (shared)]
      (doseq [{:keys [osm_id name ele lat lon tags]} rows]
        (jdbc/execute! tx ["INSERT INTO osm_peaks (osm_id, name, ele, lat, lon, tags, fetched_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(osm_id) DO UPDATE SET name = excluded.name, ele = excluded.ele,
                              lat = excluded.lat, lon = excluded.lon, tags = excluded.tags"
                           osm_id name ele lat lon tags now])))))

(defn fetch-box!
  "Downloads the named peaks of a tile-aligned [south west north east] box and records each of its tiles as
  fetched. Returns how many peaks."
  [bbox]
  (let [rows (->> (:elements (overpass (peaks-query bbox)))
                  (filter #(and (= "node" (:type %)) (:lat %)))
                  (mapv element->row))
        per-tile (frequencies (map #(summits/tile-key (:lat %) (:lon %)) rows))
        now (str (java.time.Instant/now))]
    (save-peaks! rows)
    (jdbc/with-transaction [tx (shared)]
      (doseq [tile (summits/tiles-in bbox)]
        (jdbc/execute! tx ["INSERT OR REPLACE INTO peak_tiles (tile, peaks, fetched_at) VALUES (?, ?, ?)"
                           tile (get per-tile tile 0) now])))
    (count rows)))

(defn fetched-tiles []
  (into #{} (map :tile) (db/query (shared) ["SELECT tile FROM peak_tiles"])))

(defn- fetch-once!
  "Fetches the peaks of a box, one request at a time for the whole install (public servers are shared,
  and two athletes starting together would ask for the same chunk): nil when somebody else has fetched
  all of it by the time this one has the turn."
  [bbox]
  (paths/with-lock "peaks-fetch"
    (fn []
      (when-not (every? (fetched-tiles) (summits/tiles-in bbox))
        (Thread/sleep 1000)
        (fetch-box! bbox)
        true))))

;; ---------------------------------------------------------------------------
;; The dataset: 10° blocks and the Swiss names, from the release

(defn- fetch-data
  "The bytes of a file of the release (or of the folder $COACH_PEAKS_DATA_URL names)."
  ^bytes [file]
  (let [base (or (System/getenv "COACH_PEAKS_DATA_URL") data-url)]
    (if (re-find #"^https?://" base)
      (let [resp (.send http (-> (HttpRequest/newBuilder (URI/create (str base "/" file)))
                                 (.timeout (Duration/ofSeconds 180))
                                 (.header "User-Agent" user-agent) (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofByteArray))]
        (when-not (= 200 (.statusCode resp))
          (throw (ex-info (i18n/t "The peak data server answered HTTP {0} for {1}." (.statusCode resp) file) {})))
        (.body resp))
      (Files/readAllBytes (.toPath (io/file base file))))))

(defn- blocks
  "{block rows}: the blocks that exist (resources/peaks/blocks.edn); the rest of the world is sea."
  []
  (if-let [r (io/resource "peaks/blocks.edn")] (edn/read-string (slurp r)) {}))

(defn- gz-lines
  "Calls `f` with the lines of a gzipped text file."
  [^bytes data f]
  (with-open [r (io/reader (GZIPInputStream. (ByteArrayInputStream. data)) :encoding "UTF-8")]
    (f (line-seq r))))

(defn- loaded-blocks []
  (into #{} (map :block) (db/query (shared) ["SELECT block FROM dataset_blocks"])))

(defn- insert-batched! [tx sql param-rows]
  (with-open [ps (jdbc/prepare tx [sql])]
    (doseq [group (partition-all 5000 param-rows)]
      (jdbc/execute-batch! ps (vec group))
      (breathe!))))

(defn- load-block!
  "Puts the summits of a 10° block into `peaks` (unnamed). Once per block, one download at a time for the
  install. True when this call loaded it."
  [block]
  (when-not ((loaded-blocks) block)
    (paths/with-lock "peaks-fetch"
      (fn []
        (when-not ((loaded-blocks) block)
          (let [data (when (contains? (blocks) block) (fetch-data (str "prominence-" block ".csv.gz")))
                n (atom 0)]
            (jdbc/with-transaction [tx (shared)]
              (when data
                (gz-lines data
                          (fn [lines]
                            (insert-batched!
                             tx "INSERT OR IGNORE INTO peaks (id, lat, lon, ele_dem, ele, prominence) VALUES (?, ?, ?, ?, ?, ?)"
                             (for [^String line lines
                                   :let [f (.split line "," -1)]
                                   :when (= 4 (alength f))]
                               (let [lat (parse-double (aget f 0)) lon (parse-double (aget f 1))
                                     ele (parse-double (aget f 2))]
                                 (swap! n inc)
                                 [(summits/dataset-peak-id lat lon) lat lon ele ele (parse-double (aget f 3))]))))))
              (jdbc/execute! tx ["INSERT OR REPLACE INTO dataset_blocks (block, rows, fetched_at) VALUES (?, ?, ?)"
                                 block @n (str (java.time.Instant/now))]))
            true))))))

(defn- load-official!
  "Switzerland's official names (swissNAMES3D), once for the install."
  []
  (when-not (meta-get :official_ch)
    (paths/with-lock "peaks-fetch"
      (fn []
        (when-not (meta-get :official_ch)
          (let [data (fetch-data "official-ch.csv.gz")]
            (jdbc/with-transaction [tx (shared)]
              (jdbc/execute! tx ["DELETE FROM official_peaks"])
              (gz-lines data
                        (fn [lines]
                          (insert-batched!
                           tx "INSERT INTO official_peaks (lat, lon, ele, name) VALUES (?, ?, ?, ?)"
                           (for [^String line lines
                                 :let [f (.split line "," 4)]
                                 :when (= 4 (alength f))]
                             [(parse-double (aget f 0)) (parse-double (aget f 1))
                              (parse-double (aget f 2)) (aget f 3)]))))
              (meta-set! tx :official_ch (str (java.time.Instant/now))))))))))

;; ---------------------------------------------------------------------------
;; Names for the summits of a square

(def ^:private source-of-rank {0 "listed" 1 "swisstopo" 2 "osm"})

(defn- donors
  "The points that can name the summits of a square (with a margin around it): the peaks on a list (rank 0),
  swisstopo's (1) and OpenStreetMap's (2). A point without a name takes no summit but still says which one it is."
  [[s w n e]]
  (let [m 0.01
        box [(- s m) (+ n m) (- w m) (+ e m)]
        osm (db/query (shared) (into ["SELECT osm_id, name, ele, lat, lon, listed FROM osm_peaks
                                       WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"] box))
        off (db/query (shared) (into ["SELECT id, name, ele, lat, lon FROM official_peaks
                                       WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"] box))]
    (concat
     (for [{:keys [osm_id name ele lat lon listed]} osm]
       {:id osm_id :name name :ele ele :lat lat :lon lon :rank (if (= 1 listed) 0 2)
        :claim? (not (str/blank? name))})
     (for [{:keys [id name ele lat lon]} off]
       {:id (- id) :name name :ele ele :lat lat :lon lon :rank 1 :claim? true}))))

(defn- rematch-square!
  "Names the dataset summits of a square from the points around it, from scratch (so it can run again
  whenever a source has arrived), and records which summit each OSM point is. A listed peak the dataset
  doesn't have stays a peak of its own."
  [k]
  (let [[s w n e :as box] (summits/square-bbox k)
        sums (db/query (shared) ["SELECT id, lat, lon, ele_dem AS ele FROM peaks
                                  WHERE id >= ? AND lat >= ? AND lat < ? AND lon >= ? AND lon < ?"
                                 summits/dataset-id-min s n w e])
        ds (donors box)
        {:keys [named links]} (summits/match-donors sums ds)
        by-id (into {} (map (juxt :id identity)) ds)
        listed-summits (into #{} (keep (fn [[donor-id summit-id]]
                                         (when (zero? (:rank (by-id donor-id))) summit-id)))
                             links)]
    (jdbc/with-transaction [tx (shared)]
      (jdbc/execute! tx ["UPDATE peaks SET name = NULL, name_src = NULL, listed = 0, ele = ele_dem
                          WHERE id >= ? AND lat >= ? AND lat < ? AND lon >= ? AND lon < ?"
                         summits/dataset-id-min s n w e])
      (doseq [[summit-id d] named]
        (jdbc/execute! tx ["UPDATE peaks SET name = ?, ele = coalesce(?, ele_dem), name_src = ? WHERE id = ?"
                           (:name d) (:ele d) (source-of-rank (:rank d)) summit-id]))
      (doseq [summit-id listed-summits]
        (jdbc/execute! tx ["UPDATE peaks SET listed = 1 WHERE id = ?" summit-id]))
      (doseq [[donor-id summit-id] links :when (pos? donor-id)]
        (jdbc/execute! tx ["INSERT OR REPLACE INTO peak_links (donor_id, peak_id) VALUES (?, ?)" donor-id summit-id])
        ;; a listed peak that turned out to be a dataset summit is no peak of its own any more
        (jdbc/execute! tx ["DELETE FROM peaks WHERE id = ?" donor-id]))
      (doseq [{:keys [id name ele lat lon rank]} ds :when (and (zero? rank) (not (contains? links id)))]
        (jdbc/execute! tx ["INSERT OR IGNORE INTO peaks (id, lat, lon, ele, name, name_src, listed)
                            VALUES (?, ?, ?, ?, ?, 'listed', 1)" id lat lon ele name])))
    {:summits (count sums) :named (count named)}))

;; ---------------------------------------------------------------------------
;; Peak lists (resources/peaks/lists.edn, made by dev/peak_lists.clj)

(defn lists []
  (if-let [r (io/resource "peaks/lists.edn")]
    (edn/read-string (slurp r))
    []))

(defn seed-lists!
  "Puts the listed peaks into the shared osm_peaks (the names to give) and, until a dataset summit takes
  their place, into `peaks`: they are shown whatever their prominence."
  []
  (let [now (str (java.time.Instant/now))]
    (jdbc/with-transaction [tx (shared)]
      (doseq [{:keys [osm_id name ele lat lon]} (mapcat :peaks (lists))]
        (jdbc/execute! tx ["INSERT INTO osm_peaks (osm_id, name, ele, lat, lon, listed, fetched_at)
                            VALUES (?, ?, ?, ?, ?, 1, ?)
                            ON CONFLICT(osm_id) DO UPDATE SET listed = 1"
                           osm_id name ele lat lon now])
        (when-not (seq (db/query tx ["SELECT 1 FROM peak_links WHERE donor_id = ?" osm_id]))
          (jdbc/execute! tx ["INSERT OR IGNORE INTO peaks (id, lat, lon, ele, name, name_src, listed)
                              VALUES (?, ?, ?, ?, ?, 'listed', 1)" osm_id lat lon ele name]))))))

;; the athletes whose update is running (:running), or running and asked again meanwhile (:again)
(defonce ^:private running? (atom {}))

(defn working?
  "Whether the athlete's own update is going on (also while it waits to retry a busy server)."
  []
  (contains? @running? (context/athlete)))

(defn- visit-counts
  "The athlete's visits per peak: {peak_id {:visits n :first date :last date}}."
  [ds]
  (into {} (map (juxt :peak_id identity))
        (db/query ds ["SELECT peak_id, count(*) AS visits, min(date) AS first, max(date) AS last
                       FROM summits WHERE source <> 'dismissed' GROUP BY peak_id"])))

(defn- relink-summits!
  "Moves what the athlete bagged under an OSM id (before the dataset) to the summit that OSM peak is."
  [ds]
  (let [old (map :peak_id (db/query ds ["SELECT DISTINCT peak_id FROM summits WHERE peak_id < ?"
                                        summits/dataset-id-min]))
        links (when (seq old)
                (db/query (shared) (into [(str "SELECT donor_id, peak_id FROM peak_links WHERE donor_id IN ("
                                               (str/join "," (repeat (count old) "?")) ")")]
                                         old)))]
    (when (seq links)
      (jdbc/with-transaction [tx ds]
        (doseq [{:keys [donor_id peak_id]} links]
          ;; OR IGNORE: two OSM points on one summit, both bagged on one activity, stay as they are
          (jdbc/execute! tx ["UPDATE OR IGNORE summits SET peak_id = ? WHERE peak_id = ?" peak_id donor_id]))))
    (count links)))

(defn- remove-dem-cache!
  "Once: the elevation tiles older versions downloaded (hundreds of MB) are of no use any more."
  []
  (when-not (meta-get :dem_cache_removed)
    (let [d (io/file (paths/shared-file "cache" "dem"))]
      (when (.exists d) (doseq [^java.io.File f (reverse (file-seq d))] (.delete f))))
    (meta-set! :dem_cache_removed "1")))

;; ---------------------------------------------------------------------------
;; Squares: the world in 1° squares, each downloaded on its own, like an offline map region. Shared:
;; a square one athlete downloads is there for everybody.

(defn- default-squares
  "The squares of the default region (resources/peaks/regions.edn, Switzerland)."
  []
  (when-let [r (io/resource "peaks/regions.edn")]
    (summits/squares-in (:bbox (first (edn/read-string (slurp r)))))))

(defn- swiss-square? [k] (contains? (set (default-squares)) k))

(defn- requested-squares
  "[{:square :message}] in the order they were asked for."
  []
  (db/query (shared) ["SELECT square, message FROM peak_squares ORDER BY added_at, rowid"]))

(defn- square-tiles-done [k have]
  (count (filter have (summits/tiles-of-square k))))

(defn- queue-squares!
  "Puts squares (valid keys) on the download list, oldest first; the ones already there are skipped, also
  when another athlete's worker queues the same square at the same moment. Returns how many were new."
  [keys]
  (let [now (str (java.time.Instant/now))]
    (jdbc/with-transaction [tx (shared)]
      (reduce + 0 (for [k (distinct keys)]
                    (:next.jdbc/update-count
                     (jdbc/execute-one! tx ["INSERT OR IGNORE INTO peak_squares (square, added_at) VALUES (?, ?)"
                                            k now])))))))

(defn add-squares!
  "Asks for these 1° squares (keys like \"46,7\") to be downloaded; the ones already asked for are
  skipped. At most `summits/max-squares` at once. Returns how many were new."
  [keys]
  (let [keys (vec (distinct keys))]
    (when-not (and (seq keys) (every? summits/valid-square? keys))
      (throw (ex-info "That isn't a square on the map." {:squares keys})))
    (when (> (count keys) summits/max-squares)
      (throw (ex-info (i18n/t "That is too much at once: {0} squares, at most {1}. Pick fewer."
                              (count keys) summits/max-squares)
                      {:squares (count keys)})))
    (queue-squares! keys)))

(defn- square-ready?
  "Whether a square's peaks are loaded and its names fetched."
  [k blocks-have tiles-have]
  (and (blocks-have (summits/square-block k))
       (= (count (summits/tiles-of-square k)) (square-tiles-done k tiles-have))))

(defn cancel-square!
  "Takes a square that is still waiting off the list. A downloaded one stays: its peaks are shared and small."
  [k]
  (when (square-ready? k (loaded-blocks) (fetched-tiles))
    (throw (ex-info (i18n/t "A downloaded square stays: its peaks are shared with the other athletes.") {:square k})))
  (jdbc/execute! (shared) ["DELETE FROM peak_squares WHERE square = ?" k])
  {:ok true})

(defn- seed-default-squares!
  "Switzerland, once for the whole install."
  []
  (when-not (meta-get :squares_seeded)
    (add-squares! (default-squares))
    (meta-set! :squares_seeded "1")))

(def ^:private square-sql
  "SQLite has no floor(): the whole degree below a coordinate is the integer part, one lower when negative."
  "(CAST(lat AS INTEGER) - (lat < CAST(lat AS INTEGER))) || ',' || (CAST(lon AS INTEGER) - (lon < CAST(lon AS INTEGER)))")

(defn- tile-square [tile]
  (let [[la lo] (map #(Long/parseLong %) (str/split tile #":"))]
    (str (Math/floorDiv la 4) "," (Math/floorDiv lo 4))))

(defn- bagged-per-square
  "{square n}: the athlete's bagged peaks in each."
  [ds]
  (let [ids (keys (visit-counts ds))]
    (if (empty? ids)
      {}
      (frequencies (map #(summits/square-key (:lat %) (:lon %))
                        (db/query (shared) (into [(str "SELECT lat, lon FROM peaks WHERE id IN ("
                                                       (str/join "," (repeat (count ids) "?")) ")")]
                                                 ids)))))))

(defn squares-status
  "The Peaks page's download map: every square with something to show. `:state` is
  queued (waiting) | failed (will retry) | names (peaks loaded, names being added) | ready."
  [ds]
  (let [have (fetched-tiles)
        blocks-have (loaded-blocks)
        done (frequencies (map tile-square have))
        requested (into {} (map (juxt :square :message)) (requested-squares))
        m (min-prominence ds)
        counts (into {} (map (juxt :sq identity))
                     (db/query (shared) [(str "SELECT " square-sql " AS sq,
                                                      coalesce(sum(listed = 1 OR prominence >= ?), 0) AS shown
                                               FROM peaks GROUP BY sq") m]))
        bagged (bagged-per-square ds)
        squares (for [k (keys requested)
                      :let [loaded? (blocks-have (summits/square-block k))
                            full? (= 16 (get done k 0))
                            msg (get requested k)
                            state (cond (and loaded? full?) "ready"
                                        msg "failed"
                                        loaded? "names"
                                        :else "queued")]]
                  {:square k :state state :requested true :message msg
                   :peaks (if loaded? (long (:shown (get counts k) 0)) 0) :bagged (get bagged k 0)})]
    {:status (if (working?) "running" "idle") ; "running" makes the page ask again shortly
     :squares (vec squares)
     :downloaded (count (filter #(#{"names" "ready"} (:state %)) squares))
     :loading (count (remove #(= "ready" (:state %)) squares))
     :peaks (reduce + 0 (map :peaks squares))}))

(defn- note-failure! [square e]
  (println "Peaks: square" square "failed:" (ex-message e))
  (jdbc/execute! (shared) ["UPDATE peak_squares SET message = ? WHERE square = ?" (ex-message e) square]))

(defn- unfinished-squares []
  (let [have (fetched-tiles) blocks-have (loaded-blocks)]
    (remove #(square-ready? (:square %) blocks-have have) (requested-squares))))

(defn- load-datasets!
  "The peaks of the requested squares that aren't there yet: their 10° block of the dataset (and Switzerland's
  official names), each named from what is known so far. Fast: no public server is asked for names here.
  A square that fails is noted and the others go on. Returns {:blocks n :failed? bool}."
  []
  (reduce (fn [r {:keys [square]}]
            (try (let [new? (load-block! (summits/square-block square))]
                   (when (swiss-square? square) (load-official!))
                   (breathe!)
                   (rematch-square! square)
                   (jdbc/execute! (shared) ["UPDATE peak_squares SET message = NULL WHERE square = ?" square])
                   (cond-> r new? (update :blocks inc)))
                 (catch Throwable e (note-failure! square e) (assoc r :failed? true))))
          {:blocks 0 :failed? false} (unfinished-squares)))

(defn- load-names!
  "OpenStreetMap's names for the requested squares that haven't got them yet, oldest first, one request at a
  time (the public server is shared). Stops at the first one that fails: the server is busy, and the update
  tries again later. Returns {:chunks n :failed? bool}."
  []
  (let [have (fetched-tiles)
        todo (remove #(= 16 (square-tiles-done (:square %) have)) (requested-squares))]
    (reduce (fn [r {:keys [square]}]
              (try (let [fetched? (fetch-once! (summits/square-bbox square))]
                     (breathe!)
                     (rematch-square! square)
                     (jdbc/execute! (shared) ["UPDATE peak_squares SET message = NULL WHERE square = ?" square])
                     (cond-> r fetched? (update :chunks inc)))
                   (catch Throwable e (note-failure! square e) (reduced (assoc r :failed? true)))))
            {:chunks 0 :failed? false} todo)))

;; ---------------------------------------------------------------------------
;; Searching for a place by name (OpenStreetMap's Nominatim; only the search text leaves the machine)

(defonce ^:private search-cache (atom {}))

(defn- search-http
  "Nominatim's answer for `q`: the places as maps. One request at a time, a second apart."
  [q lang]
  (paths/with-lock
    "nominatim"
    (fn []
      (Thread/sleep 1000)
      (let [resp (.send http (-> (HttpRequest/newBuilder
                                  (URI/create (str "https://nominatim.openstreetmap.org/search?"
                                                   (form {:q q :format "jsonv2" :limit 8
                                                          :polygon_geojson 1 :polygon_threshold 0.01
                                                          "accept-language" lang}))))
                                 (.timeout (Duration/ofSeconds 20))
                                 (.header "User-Agent" user-agent) (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
        (when-not (= 200 (.statusCode resp))
          (throw (ex-info (i18n/t "Couldn't search for places: {0}" (.statusCode resp)) {})))
        (json/read-str (.body resp) :key-fn keyword)))))

(defn search-areas
  "Places matching `q`, each with the squares it covers (see `summits/search-result`). Nominatim allows one
  request a second and no search-as-you-type, so one at a time, spaced, and the last 50 answers are kept."
  [q lang]
  (let [k [(str/lower-case (str/trim q)) lang]]
    (or (get @search-cache k)
        (let [rows (search-http q lang)
              r (mapv summits/search-result (filter :boundingbox rows))]
          (swap! search-cache #(let [m (assoc % k r)] (if (> (count m) 50) (dissoc m (first (keys m))) m)))
          r))))

;; ---------------------------------------------------------------------------
;; Detection (the athlete's activities against the shared peaks; the result is the athlete's)

(defn- pending-activities [ds]
  (db/query ds ["SELECT a.id, a.date, a.sport, m.track FROM activities a
                 JOIN activity_metrics m ON m.activity_id = a.id
                 WHERE m.track IS NOT NULL AND m.track <> '[]'
                   AND (m.summits_checked IS NULL OR m.summits_checked < ?)
                 ORDER BY a.date" summits/version]))

(defn- mark-checked! [ds id]
  (jdbc/execute! ds ["UPDATE activity_metrics SET summits_checked = ? WHERE activity_id = ?" summits/version id]))

(defn- candidates [{:keys [lat lon]}]
  (let [la (filter number? lat) lo (filter number? lon)]
    (when (seq la)
      (db/query (shared) ["SELECT id, lat, lon, coalesce(ele, ele_dem) AS ele FROM peaks
                           WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"
                          (- (apply min la) 0.01) (+ (apply max la) 0.01)
                          (- (apply min lo) 0.01) (+ (apply max lo) 0.01)]))))

(defn- save-detected! [ds {:keys [id date]} hits]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["DELETE FROM summits WHERE activity_id = ? AND source = 'gps'" id])
    (doseq [{peak :id :keys [dist_m time_s]} hits]
      ;; a dismissed one keeps its row (unique per peak and activity), so it stays gone
      (jdbc/execute! tx ["INSERT OR IGNORE INTO summits (peak_id, activity_id, date, time_s, dist_m, source, created_at)
                          VALUES (?, ?, ?, ?, ?, 'gps', ?)"
                         peak id date time_s dist_m (str (java.time.Instant/now))]))))

(defn detect-activity!
  "Looks for the peaks one activity passed and stores them. Returns how many."
  [ds {:keys [id sport] :as activity}]
  (let [stream (when (summits/eligible? sport) (db/load-stream ds id))
        hits (when stream (summits/detect stream (candidates stream)))]
    (save-detected! ds activity (or hits []))
    (mark-checked! ds id)
    (count hits)))

(defn- activity-tiles [{:keys [track]}]
  (summits/tiles-for-track (map track/decode (json/read-str track))))

(def ^:private track-squares-flag "Set once an athlete's whole history has been queued." :peak_track_squares)

(defn- track-squares
  "The 1° squares the tracks of these activities ({:sport :track}) touch; virtual and indoor ones touch none."
  [activities]
  (into #{}
        (comp (filter #(summits/eligible? (:sport %)))
              (mapcat activity-tiles)
              (map tile-square)
              (filter summits/valid-square?))
        activities))

(defn- queue-track-squares!
  "Queues the squares the athlete's activities touch: all of them the first time (the whole history, for every
  athlete of the server), then those of the activities not checked yet, which is every new one. A square that
  somebody queued already is skipped. Returns how many were new."
  [ds pending]
  (let [first-time? (not (db/get-state ds track-squares-flag))
        all (when first-time?
              (db/query ds ["SELECT a.id, a.sport, m.track FROM activities a
                             JOIN activity_metrics m ON m.activity_id = a.id
                             WHERE m.track IS NOT NULL AND m.track <> '[]'"]))
        n (queue-squares! (sort (track-squares (or all pending))))]
    (when first-time? (db/set-state! ds track-squares-flag "1"))
    n))

;; ---------------------------------------------------------------------------
;; Update

(defn- blocks-loaded?
  "Whether the dataset is there for every square an activity touches."
  [blocks-have activity]
  (every? #(blocks-have (summits/square-block (tile-square %))) (activity-tiles activity)))

(defn- detect-ready!
  "Detects the peaks of the pending activities whose blocks are loaded. Returns [activities found]."
  [ds pending]
  (let [blocks-have (loaded-blocks)
        ready (filter #(blocks-loaded? blocks-have %) pending)]
    [(count ready)
     (reduce + 0 (map #(try (breathe!)
                            (detect-activity! ds %)
                            (catch Throwable e
                              (println "Peaks: activity" (:id %) "failed:" (ex-message e)) 0))
                      ready))]))

(defn- run-once! [ds]
  (seed-lists!)
  (remove-dem-cache!)
  (seed-default-squares!)
  (let [pending (pending-activities ds)
        queued (queue-track-squares! ds pending)
        {:keys [blocks] failed-data? :failed?} (load-datasets!)
        [activities found] (detect-ready! ds pending)
        {:keys [chunks] failed-names? :failed?} (load-names!)
        relinked (relink-summits! ds)
        r {:queued queued :blocks blocks :chunks chunks :activities activities :summits found :relinked relinked}]
    (when (some pos? (vals r)) (println "Peaks:" r))
    (assoc r :failed? (boolean (or failed-data? failed-names?)))))

(defn request!
  "Asks for an update. True when the caller should run `work!` (none is running for this athlete); a
  running one is told to go round once more instead (a region may have been added meanwhile)."
  []
  (let [k (context/athlete)
        [old _] (swap-vals! running? update k #(if % :again :running))]
    (nil? (get old k))))

(defn work!
  "Peak bagging for this athlete, after `request!` said true: the peaks of the squares and tiles for the
  activities not checked yet (shared with the other athletes), which peaks each activity passed (theirs),
  and the names. With a server busy it retries later. Returns the last round's counts."
  [ds]
  (let [k (context/athlete)]
    (try
      (loop [attempt 1]
        (let [r (paths/with-lock "peaks-work" #(run-once! ds))]
          (cond
            (= :again (get @running? k)) (do (swap! running? assoc k :running) (recur 1))
            (and (:failed? r) (< attempt max-attempts)) (do (Thread/sleep retry-ms) (recur (inc attempt)))
            :else r)))
      (finally (swap! running? dissoc k)))))

(defn update!
  "`request!` and `work!` in one, in the caller's thread; nil when an update is running already."
  [ds]
  (when (request!) (work! ds)))

;; ---------------------------------------------------------------------------
;; Queries

(def ^:private visits-sql
  "SELECT s.id, s.peak_id, s.activity_id, s.date, s.source, s.dist_m, a.name AS activity_name, a.sport
   FROM summits s LEFT JOIN activities a ON a.id = s.activity_id
   WHERE s.source <> 'dismissed'")

(defn- peak-row [r] (assoc r :listed (= 1 (:listed r))))

(def ^:private peak-columns "id, name, coalesce(ele, ele_dem) AS ele, lat, lon, prominence, listed")
(def ^:private max-view-peaks "The most unbagged peaks one view gets (there are about a million in the dataset)." 5000)

(defn- peaks-by-ids
  "The visible rows (a list's, or at least the athlete's minimum prominence) of these peak ids."
  [ids m]
  (into [] (mapcat (fn [part]
                     (db/query (shared) (into [(str "SELECT " peak-columns " FROM peaks WHERE id IN ("
                                                    (str/join "," (repeat (count part) "?"))
                                                    ") AND (listed = 1 OR prominence >= ?)")]
                                              (conj (vec part) m)))))
        (partition-all 500 ids)))

(defn- with-visits [visits rows]
  (mapv (fn [p]
          (let [v (visits (:id p))]
            (assoc (peak-row p) :visits (or (:visits v) 0) :first (:first v) :last (:last v))))
        rows))

(def ^:private thin-zoom-max "From this zoom on every peak the zoom draws is sent, not one per screen cell." 13)

(defn- view-peaks
  "The unbagged peaks of a view: listed ones from `density/listed-zoom`, the rest from prominence `floor`. With a
  `cell` (degrees of a screen patch) a patch keeps its most prominent peaks, as many as the square root of those
  it has (1 of 1, 2 of 4, 5 of 25): the map thins out but still shows where the mountains are. A list's
  peaks are not thinned."
  [{:keys [bbox zoom cell floor]}]
  (let [[s w n e] bbox
        box      (if bbox " AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" "")
        box-args (if bbox [s n w e] [])
        listed?  (>= zoom density/listed-zoom)
        q        (fn [where args tail]
                   (db/query (shared) (into [(str "SELECT " peak-columns " FROM peaks WHERE " where box tail)]
                                            (concat args box-args))))]
    (if (and cell (pos? cell) (< zoom thin-zoom-max))
      (concat (when listed? (q "listed = 1" [] ""))
              (db/query (shared)
                        (into [(str "SELECT id, name, ele, lat, lon, prominence, listed FROM ("
                                    " SELECT *, ROW_NUMBER() OVER (PARTITION BY cx, cy ORDER BY prominence DESC) AS rn,"
                                    "          COUNT(*) OVER (PARTITION BY cx, cy) AS n FROM ("
                                    "  SELECT " peak-columns ", CAST((lat + 90) / ? AS INTEGER) AS cx,"
                                    "         CAST((lon + 360) / ? AS INTEGER) AS cy"
                                    "  FROM peaks WHERE prominence >= ?" box "))"
                                    " WHERE rn * rn <= n")]
                              (concat [cell cell floor] box-args))))
      (q "((listed = 1 AND ?) OR prominence >= ?)" [(if listed? 1 0) floor]
         (str " ORDER BY listed DESC, prominence DESC LIMIT " max-view-peaks)))))

(defn visible-peaks
  "Peaks to show, as maps with :listed, :visits (count, the athlete's), :first and :last visit dates. A peak
  nobody named has no :name and shows with its height. All of them, which is a million rows in the world
  dataset: `opts` narrow it. `:bagged-only?` gives just the athlete's. `:zoom` (and `:bbox` [south west north
  east], `:cell` the degrees of longitude of a screen patch: one peak per patch) gives what the map draws at that
  zoom (`coach.peak-density`), at most `max-view-peaks` of it, plus every bagged peak."
  ([ds] (visible-peaks ds {}))
  ([ds {:keys [bbox zoom cell bagged-only?]}]
   (let [m (min-prominence ds)
         visits (visit-counts ds)
         bagged (peaks-by-ids (keys visits) m)
         rows (cond
                bagged-only? bagged
                (nil? zoom) (db/query (shared) [(str "SELECT " peak-columns
                                                     " FROM peaks WHERE listed = 1 OR prominence >= ?") m])
                :else (into bagged
                            (remove #(contains? visits (:id %)))
                            (view-peaks {:bbox bbox :zoom zoom :cell cell
                                         :floor (max m (density/min-prominence zoom))})))]
     (with-visits visits rows))))

(defn map-peaks
  "What the Map tab draws, one short row per peak (there can be a million in the world, so only what a zoom
  draws, in the `bbox` if given; without a zoom the world view):
  [id lat lon ele prominence visits listed? name last-visit]."
  ([ds] (map-peaks ds {:zoom 0 :cell 2.0}))
  ([ds opts]
   (mapv (fn [{:keys [id lat lon ele prominence visits listed name last]}]
           [id lat lon ele (some-> prominence Math/round) visits listed name last])
         (visible-peaks ds opts))))

(defn- visits-by-peak [ds]
  (group-by :peak_id (db/query ds [(str visits-sql " ORDER BY s.date DESC")])))

(defn- list-peaks
  "A list's peaks as {:peak_id :name :ele}: the id of the dataset summit an OSM peak of the list is, else its own."
  [l]
  (let [ps (:peaks l)
        links (if (seq ps)
                (into {} (map (juxt :donor_id :peak_id))
                      (db/query (shared) (into [(str "SELECT donor_id, peak_id FROM peak_links WHERE donor_id IN ("
                                                     (str/join "," (repeat (count ps) "?")) ")")]
                                               (map :osm_id ps))))
                {})]
    (mapv (fn [{:keys [osm_id name ele]}] {:peak_id (get links osm_id osm_id) :name name :ele ele}) ps)))

(defn summary
  "The Peaks page: counts, the bagged peaks with their visits, the lists with progress."
  [ds]
  (let [peaks (visible-peaks ds {:bagged-only? true})
        visits (visits-by-peak ds)
        year (str (.getYear (LocalDate/now)))
        bagged (->> peaks (filter #(pos? (:visits %)))
                    (mapv #(assoc % :visit_list (mapv (fn [v] (dissoc v :peak_id)) (visits (:id %))))))
        bagged-ids (set (map :id bagged))
        highest (first (sort-by (comp - :ele) (filter :ele bagged)))]
    {:bagged (count bagged)
     :this_year (count (filter #(= year (some-> (:first %) (subs 0 4))) bagged))
     :visits (reduce + 0 (map :visits bagged))
     :highest (some-> highest (select-keys [:id :name :ele]))
     :min_prominence (min-prominence ds)
     :loading (:loading (squares-status ds))
     :peaks (vec (sort-by :last #(compare %2 %1) bagged))
     :lists (mapv #(summits/list-progress (assoc % :peaks (list-peaks %)) bagged-ids) (lists))}))

(defn activity-summits
  "The peaks an activity passed (visible ones): [{:id :name :ele :lat :lon :prominence :visit_id :source}]."
  [ds activity-id]
  (let [m (min-prominence ds)
        visits (db/query ds ["SELECT id AS visit_id, peak_id, source, dist_m, time_s FROM summits
                              WHERE activity_id = ? AND source <> 'dismissed' ORDER BY time_s" activity-id])
        peaks (when (seq visits)
                (into {} (map (juxt :id identity))
                      (db/query (shared) (into [(str "SELECT id, name, coalesce(ele, ele_dem) AS ele, lat, lon,
                                                             prominence, listed
                                                      FROM peaks WHERE id IN ("
                                                     (str/join "," (repeat (count visits) "?")) ")")]
                                               (map :peak_id visits)))))]
    (vec (for [v visits
               :let [p (peaks (:peak_id v))]
               :when (and p (or (= 1 (:listed p)) (and (:prominence p) (>= (:prominence p) m))))]
           (merge (peak-row p) (dissoc v :peak_id))))))

;; ---------------------------------------------------------------------------
;; Manual bags

(defn bag!
  "Marks a peak as reached on `date` without GPS. Returns the new visit's id."
  [ds peak-id date]
  (when-not (seq (db/query (shared) ["SELECT 1 FROM peaks WHERE id = ?" peak-id]))
    (throw (ex-info "Unknown peak." {:peak_id peak-id})))
  (when-not (and (string? date) (re-matches #"\d{4}-\d{2}-\d{2}" date))
    (throw (ex-info "The date must look like 2026-07-14." {:date date})))
  (jdbc/execute! ds ["INSERT INTO summits (peak_id, activity_id, date, source, created_at) VALUES (?, NULL, ?, 'manual', ?)"
                     peak-id date (str (java.time.Instant/now))])
  (:id (first (db/query ds ["SELECT max(id) AS id FROM summits"]))))

(defn unbag!
  "Removes a visit: a manual one is deleted, a GPS one is dismissed so detection doesn't bring it back."
  [ds visit-id]
  (jdbc/execute! ds ["DELETE FROM summits WHERE id = ? AND source = 'manual'" visit-id])
  (jdbc/execute! ds ["UPDATE summits SET source = 'dismissed' WHERE id = ? AND source = 'gps'" visit-id])
  {:ok true})
