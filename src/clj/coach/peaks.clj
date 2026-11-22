(ns coach.peaks
  "Peak bagging: OpenStreetMap peaks (Overpass: a 0.25° tile for each activity track, a 1° square at a
  time for the squares the athletes download, each fetched once), their prominence from open elevation tiles
  (coach.prominence), and which of them an activity's GPS track passed (coach.summits). The athlete's
  tracks never leave the machine: Overpass sees tile and square boxes, the elevation server the tiles
  around a peak.

  The peaks, their prominence and the downloaded squares are the same for everybody, so they live once per
  install in a shared database (shared/peaks.db, `shared`); what an athlete bagged (`summits`) and their
  minimum prominence are in their own database. Functions without a `ds` argument work on the shared
  data; those with one on the athlete's.

  OSM tags every bump as a peak, so a peak is shown only when its prominence reaches the athlete's
  minimum (30 m by default), unless it is on a peak list."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.context :as context]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.paths :as paths]
            [coach.prominence :as prominence]
            [coach.summits :as summits]
            [coach.track :as track]
            [next.jdbc :as jdbc])
  (:import (coach.prominence Elevation)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files StandardCopyOption)
           (java.time Duration LocalDate)
           (javax.imageio ImageIO)))

(def overpass-urls
  "The public Overpass servers, tried in turn when one is busy."
  ["https://overpass-api.de/api/interpreter" "https://overpass.kumi.systems/api/interpreter"])
(def dem-url "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png")
(def dem-zoom 13)
(def window-m
  "Prominence is looked for within this far of the summit; a peak still bounded at the edge only has a lower bound."
  10000)
(def worker-count
  "Threads working out prominence at once: a few, as the machine may be a small server that does other things."
  (max 1 (min 4 (dec (.availableProcessors (Runtime/getRuntime))))))
(def default-min-prominence 30)
(def budget-ms
  "How long an update spends on prominence when no region is loading; the rest follows at the next sync."
  180000)
(def retry-ms "When Overpass was busy, the update tries the failed chunks again after this long …" 300000)
(def max-attempts "… up to this many times." 6)

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
  "The database every athlete of this install shares (peaks, prominence, squares), opened once."
  []
  (or @shared-ds
      (locking shared-ds
        (or @shared-ds (reset! shared-ds (doto (db/peaks-datasource) db/migrate-peaks!))))))

(defn- meta-get [k] (:v (first (db/query (shared) ["SELECT v FROM meta WHERE k = ?" (name k)]))))

(defn- meta-set! [k v]
  (jdbc/execute! (shared) ["INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (name k) (str v)]))

;; ---------------------------------------------------------------------------
;; Settings

(defn min-prominence [ds]
  (or (some-> (db/get-state ds :peak_min_prominence) parse-double) default-min-prominence))

(defn set-min-prominence! [ds m]
  (when-not (and (number? m) (<= 0 m 2000))
    (throw (ex-info "The minimum prominence must be between 0 and 2000 m." {:value m})))
  (db/set-state! ds :peak_min_prominence (double m)))

;; ---------------------------------------------------------------------------
;; Overpass: the peaks of a tile

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
  "Overpass QL for every natural=peak node in a [south west north east] box."
  [[s w n e]]
  (format "[out:json][timeout:120];node[\"natural\"=\"peak\"](%s,%s,%s,%s);out;" s w n e))

(defn- element->row [{:keys [id lat lon tags]}]
  {:osm_id id :name (:name tags) :ele (parse-ele (:ele tags)) :lat lat :lon lon
   :prominence (parse-ele (:prominence tags)) :tags (json/write-str tags)})

(defn save-peaks!
  "Upserts peak rows into the shared database. The prominence is kept when a peak is saved again, unless OSM gives one."
  [rows]
  (let [now (str (java.time.Instant/now))]
    (jdbc/with-transaction [tx (shared)]
      (doseq [{:keys [osm_id name ele lat lon prominence tags]} rows]
        (jdbc/execute! tx ["INSERT INTO osm_peaks
                              (osm_id, name, ele, lat, lon, prominence, prominence_src, tags, fetched_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(osm_id) DO UPDATE SET name = excluded.name, ele = excluded.ele,
                              lat = excluded.lat, lon = excluded.lon, tags = excluded.tags,
                              prominence = coalesce(excluded.prominence, osm_peaks.prominence),
                              prominence_src = CASE WHEN excluded.prominence IS NOT NULL THEN 'osm'
                                                    ELSE osm_peaks.prominence_src END"
                           osm_id name ele lat lon prominence (when prominence "osm") tags now])))))

(defn fetch-box!
  "Downloads the peaks of a tile-aligned [south west north east] box and records each of its tiles as
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
;; Elevation tiles (Terrarium PNG: metres = R·256 + G + B/256 − 32768)

(def ^:private dem-memory-tiles
  "Decoded tiles kept in memory (each 256 kB): room for the windows of every worker."
  256)

(defonce ^:private dem-cache
  (java.util.Collections/synchronizedMap
   (proxy [java.util.LinkedHashMap] [64 (float 0.75) true]
     (removeEldestEntry [_] (> (.size ^java.util.Map this) dem-memory-tiles)))))

(defonce ^:private dem-loading (java.util.concurrent.ConcurrentHashMap.)) ; tile → delay of its load

(defn- dem-dir ^java.io.File [] (io/file (paths/shared-file "cache" "dem")))

(defn clean-dem-cache!
  "Deletes the downloaded elevation tiles (a region's worth is hundreds of MB)."
  []
  (let [d (dem-dir)]
    (when (.exists d)
      (doseq [^java.io.File f (reverse (file-seq d))] (.delete f)))))

(defn- download-dem-png
  "The PNG bytes of an elevation tile; nil where there is no data (open sea)."
  ^bytes [z x y]
  (let [resp (.send http (-> (HttpRequest/newBuilder (URI/create (format dem-url z x y)))
                             (.timeout (Duration/ofSeconds 30))
                             (.header "User-Agent" user-agent) (.GET) (.build))
                    (HttpResponse$BodyHandlers/ofByteArray))
        status (.statusCode resp)]
    (cond
      (= 404 status) nil
      (not= 200 status) (throw (ex-info (str "Elevation tiles answered HTTP " status) {:status status}))
      :else (.body resp))))

(defn- dem-png
  "The tile's PNG: from the disk cache, else downloaded once and kept there."
  [z x y]
  (let [f (io/file (dem-dir) (str z) (str x) (str y ".png"))]
    (if (.exists f)
      (Files/readAllBytes (.toPath f))
      (when-let [bytes (download-dem-png z x y)]
        (try (.mkdirs (.getParentFile f))
             (let [part (io/file (str f ".part"))]
               (with-open [o (io/output-stream part)] (.write o ^bytes bytes))
               (Files/move (.toPath part) (.toPath f)
                         (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING])))
             (catch java.io.IOException _ nil)) ; no disk cache is not a reason to fail
        bytes))))

(defn- decode-dem ^floats [^bytes png]
  (let [out (float-array (* 256 256))]
    (when png
      (let [^BufferedImage img (ImageIO/read (ByteArrayInputStream. png))
            ^ints px (.getRGB img 0 0 256 256 nil 0 256)]
        (dotimes [i (* 256 256)]
          (let [rgb (aget px i)
                r (bit-and (bit-shift-right rgb 16) 255) g (bit-and (bit-shift-right rgb 8) 255) b (bit-and rgb 255)]
            (aset out i (float (- (+ (* r 256) g (/ b 256.0)) 32768)))))))
    out))

(defn- dem-tile
  "A decoded tile (no data: flat at 0 m). Workers asking for the same tile at once wait for one download."
  [z x y]
  (let [k [z x y]]
    (or (.get ^java.util.Map dem-cache k)
        (let [mine (delay (or (.get ^java.util.Map dem-cache k) (decode-dem (dem-png z x y))))
              d (or (.putIfAbsent dem-loading k mine) mine)]
          (try (let [a @d] (.put ^java.util.Map dem-cache k a) a)
               (finally (.remove dem-loading k d)))))))

(defn- world-px
  "Pixel position of a coordinate in the whole Web Mercator world at zoom `z`."
  [z lat lon]
  (let [n (* 256.0 (Math/pow 2 z))
        la (Math/toRadians lat)]
    [(* n (/ (+ lon 180.0) 360.0))
     (* n (/ (- 1.0 (/ (Math/log (+ (Math/tan la) (/ 1.0 (Math/cos la)))) Math/PI)) 2.0))]))

(defn- window-elevation
  "Reads elevations of a w×w window whose top-left pixel is (x0, y0) of the world at zoom `z`: a tile is
  fetched the first time somebody asks for a pixel of it, so a flood that finds higher ground nearby
  never touches (or downloads) the rest of the window."
  ^Elevation [z ^long x0 ^long y0 ^long w]
  (let [tx0 (bit-shift-right x0 8) ty0 (bit-shift-right y0 8)
        nx (inc (- (bit-shift-right (+ x0 w -1) 8) tx0))
        ny (inc (- (bit-shift-right (+ y0 w -1) 8) ty0))
        ^objects tiles (object-array (* nx ny))]
    (reify Elevation
      (at [_ x y]
        (let [wx (+ x0 x) wy (+ y0 y)
              col (- (bit-shift-right wx 8) tx0) row (- (bit-shift-right wy 8) ty0)
              i (+ col (* row nx))
              ^floats tile (or (aget tiles i)
                               (let [t (dem-tile z (+ tx0 col) (+ ty0 row))] (aset tiles i t) t))]
          (double (aget tile (+ (bit-and wx 255) (* (bit-and wy 255) 256)))))))))

(defn dem-grid
  "The elevation grid within `radius-m` of a point, for coach.prominence (tiles are read as the flood asks)."
  [lat lon radius-m]
  (let [z dem-zoom
        res (/ (* 156543.03392 (Math/cos (Math/toRadians lat))) (Math/pow 2 z))
        half (long (Math/ceil (/ radius-m res)))
        [gx gy] (world-px z lat lon)
        w (inc (* 2 half))]
    {:w w :h w :at (window-elevation z (- (long gx) half) (- (long gy) half) w) :cx half :cy half :res res}))

(defn peak-prominence
  "{:prominence m :src \"dem\"|\"dem-min\"} from the elevation tiles: the flood over a window of `window-m`
  around the peak; \"dem-min\" when it reached the edge before higher ground, so the value is a lower bound."
  [{:keys [lat lon]} & [{:keys [noise]}]]
  (let [p (prominence/prominence (cond-> (dem-grid lat lon window-m) noise (assoc :noise noise)))]
    {:prominence (:prominence p) :src (if (:bound? p) "dem-min" "dem")}))

;; ---------------------------------------------------------------------------
;; Peak lists (resources/peaks/lists.edn, made by dev/peak_lists.clj)

(defn lists []
  (if-let [r (io/resource "peaks/lists.edn")]
    (edn/read-string (slurp r))
    []))

(defn seed-lists!
  "Puts the listed peaks into the shared osm_peaks and marks them: they are shown whatever their prominence."
  []
  (let [now (str (java.time.Instant/now))]
    (doseq [{:keys [osm_id name ele lat lon]} (mapcat :peaks (lists))]
      (jdbc/execute! (shared) ["INSERT INTO osm_peaks (osm_id, name, ele, lat, lon, listed, fetched_at)
                                VALUES (?, ?, ?, ?, ?, 1, ?)
                                ON CONFLICT(osm_id) DO UPDATE SET listed = 1"
                               osm_id name ele lat lon now]))))

;; the athletes whose update is running (:running), or running and asked again meanwhile (:again)
(defonce ^:private running? (atom {}))

(defonce ^:private computing? (atom false)) ; this app is working out prominence (for all athletes)

(defn working?
  "Whether peak work is going on that the athlete should wait for: their own update (also while it
  waits to retry a busy Overpass), or the prominence calculation, which serves every athlete."
  []
  (boolean (or (contains? @running? (context/athlete)) @computing?)))

(defn- visit-counts
  "The athlete's visits per peak: {osm_id {:visits n :first date :last date}}."
  [ds]
  (into {} (map (juxt :osm_id identity))
        (db/query ds ["SELECT osm_id, count(*) AS visits, min(date) AS first, max(date) AS last
                       FROM summits WHERE source <> 'dismissed' GROUP BY osm_id"])))

;; ---------------------------------------------------------------------------
;; Squares: the world in 1° squares, each downloaded on its own, like an offline map region. Shared:
;; a square one athlete downloads is there for everybody.

(defn- default-squares
  "The squares of the default region (resources/peaks/regions.edn, Switzerland)."
  []
  (when-let [r (io/resource "peaks/regions.edn")]
    (summits/squares-in (:bbox (first (edn/read-string (slurp r)))))))

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

(defn cancel-square!
  "Takes a square that is still waiting off the list. A downloaded one stays: its peaks are shared and small."
  [k]
  (when (= (count (summits/tiles-of-square k)) (square-tiles-done k (fetched-tiles)))
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
                        (db/query (shared) (into [(str "SELECT lat, lon FROM osm_peaks WHERE osm_id IN ("
                                                       (str/join "," (repeat (count ids) "?")) ")")]
                                                 ids)))))))

(defn squares-status
  "The Peaks page's download map: every square with something to show. `:state` is
  queued (waiting) | failed (will retry) | heights (peaks loaded, heights being worked out) | ready."
  [ds]
  (let [have (fetched-tiles)
        done (frequencies (map tile-square have))
        requested (into {} (map (juxt :square :message)) (requested-squares))
        m (min-prominence ds)
        counts (into {} (map (juxt :sq identity))
                     (db/query (shared) [(str "SELECT " square-sql " AS sq, count(*) AS peaks,
                                                     coalesce(sum(listed = 1 OR prominence >= ?), 0) AS shown,
                                                     coalesce(sum(prominence IS NULL AND ele IS NOT NULL
                                                                  AND ele >= 0), 0) AS pending
                                              FROM osm_peaks GROUP BY sq") m]))
        bagged (bagged-per-square ds)
        squares (for [k (keys requested)
                      :let [full? (= 16 (get done k 0)) c (get counts k)
                            msg (get requested k)
                            state (cond (not full?) (if msg "failed" "queued")
                                        (pos? (long (:pending c 0))) "heights"
                                        :else "ready")]]
                  {:square k :state state :requested true :message msg
                   :peaks (long (:shown c 0)) :bagged (get bagged k 0)})]
    {:status (if (working?) "running" "idle") ; "running" makes the page ask again shortly
     :squares (vec squares)
     :downloaded (count (filter #(#{"heights" "ready"} (:state %)) squares))
     :loading (count (remove #(= "ready" (:state %)) squares))
     :peaks (reduce + 0 (map :peaks squares))}))

(defn- loading?
  "Whether squares asked for are not ready yet."
  [ds]
  (pos? (:loading (squares-status ds))))

(defn- load-squares!
  "Fetches the requested squares that aren't there yet, oldest first, one request at a time (the public
  server is shared). Stops at the first one that fails: the server is busy, and the update tries again
  later. `on-fetched` is called after each square that was downloaded. Returns {:chunks n :failed? bool}."
  ([] (load-squares! (fn [])))
  ([on-fetched]
   (let [have (fetched-tiles)
         todo (remove #(= 16 (square-tiles-done (:square %) have)) (requested-squares))
         [n failed?] (reduce (fn [[n _] {:keys [square]}]
                               (try (let [fetched? (fetch-once! (summits/square-bbox square))]
                                      (jdbc/execute! (shared)
                                                     ["UPDATE peak_squares SET message = NULL WHERE square = ?" square])
                                      (when fetched? (on-fetched))
                                      [(if fetched? (inc n) n) false])
                                    (catch Throwable e
                                      (println "Peaks: square" square "failed:" (ex-message e))
                                      (jdbc/execute! (shared) ["UPDATE peak_squares SET message = ? WHERE square = ?"
                                                               (ex-message e) square])
                                      (reduced [n true]))))
                             [0 false] todo)]
     {:chunks n :failed? failed?})))

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
      (db/query (shared) ["SELECT osm_id, lat, lon, ele FROM osm_peaks
                           WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"
                          (- (apply min la) 0.01) (+ (apply max la) 0.01)
                          (- (apply min lo) 0.01) (+ (apply max lo) 0.01)]))))

(defn- save-detected! [ds {:keys [id date]} hits]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["DELETE FROM summits WHERE activity_id = ? AND source = 'gps'" id])
    (doseq [{:keys [osm_id dist_m time_s]} hits]
      ;; a dismissed one keeps its row (unique per peak and activity), so it stays gone
      (jdbc/execute! tx ["INSERT OR IGNORE INTO summits (osm_id, activity_id, date, time_s, dist_m, source, created_at)
                          VALUES (?, ?, ?, ?, ?, 'gps', ?)"
                         osm_id id date time_s dist_m (str (java.time.Instant/now))]))))

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

(defn- dem-tile-of
  "Where a peak's elevation tile is: sorting by it keeps the peaks of one area together, so they share tiles."
  [{:keys [lat lon]}]
  (let [[x y] (world-px dem-zoom lat lon)
        row (long (quot y 256)) col (long (quot x 256))]
    [(quot row 8) (quot col 8) row col]))

(defn- prominence-todo
  "Peaks still without a prominence that could be real summits (they have a height): the listed ones
  first, then the rest area by area."
  []
  (->> (db/query (shared) ["SELECT osm_id, lat, lon, ele, listed AS first FROM osm_peaks
                            WHERE prominence IS NULL AND ele IS NOT NULL AND ele >= 0"])
       (sort-by (fn [p] [(if (= 1 (:first p)) 0 1) (dem-tile-of p)]))))

(defn pending-prominence []
  (:n (first (db/query (shared) ["SELECT count(*) n FROM osm_peaks
                                  WHERE prominence IS NULL AND ele IS NOT NULL AND ele >= 0"]))))

(defn- past? [deadline] (and deadline (> (System/currentTimeMillis) deadline)))

(def ^:private chunk-size "Peaks a worker takes at a time: neighbours, so they share elevation tiles." 32)
(def ^:private flush-every "Results are saved after this many …" 50)
(def ^:private flush-ms "… or this long, so the map still fills in as it goes." 2000)

(defn- save-prominence!
  "Saves finished [osm_id prominence src] results in one transaction."
  [rows]
  (when (seq rows)
    (jdbc/with-transaction [tx (shared)]
      (doseq [[id prominence src] rows]
        (jdbc/execute! tx ["UPDATE osm_peaks SET prominence = ?, prominence_src = ? WHERE osm_id = ?"
                           prominence src id])))))

(defn- compute-prominence!
  "Works out the prominence of the peaks that lack one, on `worker-count` threads that each take a chunk of
  neighbouring peaks, saving the results in batches so the map fills in progressively. Until `deadline`
  (millis; nil: until all are done). Returns how many."
  [deadline]
  (let [chunks (vec (partition-all chunk-size (prominence-todo)))
        next-chunk (java.util.concurrent.atomic.AtomicInteger. 0)
        failures (java.util.concurrent.atomic.AtomicInteger. 0) ; in a row, over all workers
        done (java.util.concurrent.atomic.AtomicInteger. 0)
        results (java.util.concurrent.ConcurrentLinkedQueue.)
        last-save (atom (System/currentTimeMillis))
        save! (fn [force?]
                (locking results
                  (when (or force? (>= (.size results) flush-every)
                            (> (- (System/currentTimeMillis) @last-save) flush-ms))
                    (let [rows (loop [acc []] (if-let [r (.poll results)] (recur (conj acc r)) acc))]
                      (save-prominence! rows)
                      (reset! last-save (System/currentTimeMillis))))))
        stop? (fn [] (or (past? deadline) (>= (.get failures) 3)))
        work (fn []
               (loop []
                 (let [i (.getAndIncrement next-chunk)]
                   (when (and (< i (count chunks)) (not (stop?)))
                     (doseq [p (nth chunks i) :while (not (stop?))]
                       (if-let [r (try (peak-prominence p)
                                       (catch Throwable e
                                         (println "Peaks: prominence of" (:osm_id p) "failed:" (ex-message e))
                                         nil))]
                         (do (.add results [(:osm_id p) (:prominence r) (:src r)])
                             (.incrementAndGet done)
                             (.set failures 0)
                             (save! false))
                         (.incrementAndGet failures)))
                     (recur)))))]
    (try (->> (repeatedly (min worker-count (count chunks)) #(future (work))) doall (run! deref))
         (finally (save! true)))
    (when (>= (.get failures) 3) (println "Peaks: elevation tiles unreachable, prominence resumes next time"))
    (.get done)))

(defn- prominence-step!
  "Prominence for all pending peaks, whoever's region they are in. One worker at a time for the install:
  the first to come does it all and the others go on (their peaks are covered, being in the same
  database). The holder re-checks what arrived while it worked, before and after letting go, so a peak
  added at the last moment is not forgotten. Returns how many were done here."
  [deadline]
  (loop [total 0]
    (let [r (paths/try-lock "peaks-prominence"
                            (fn []
                              (reset! computing? true)
                              (try
                                (let [n (loop [n 0]
                                          (let [d (compute-prominence! deadline)]
                                            (if (and (pos? d) (pos? (pending-prominence)) (not (past? deadline)))
                                              (recur (+ n d))
                                              (+ n d))))]
                                  (when (zero? (pending-prominence)) (clean-dem-cache!))
                                  n)
                                (finally (reset! computing? false)))))]
      (cond
        (= r paths/busy) total
        (and (pos? r) (pos? (pending-prominence)) (not (past? deadline))) (recur (+ total r))
        :else (+ total r)))))

(defn- run-once! [ds]
  (seed-lists!)
  (seed-default-squares!)
  (let [pending (pending-activities ds)
        queued (queue-track-squares! ds pending)
        ;; the heights of a square are worked out while the next ones download
        early (atom nil)
        heights-now! (fn [] (let [f @early]
                              (when (or (nil? f) (realized? f))
                                (reset! early (future (try (prominence-step! nil)
                                                           (catch Throwable e
                                                             (println "Peaks: prominence failed:" (ex-message e))
                                                             0)))))))
        {:keys [chunks failed?]} (load-squares! heights-now!)
        early-prom (if-let [f @early] @f 0)
        have (fetched-tiles)
        ready (filter #(every? have (activity-tiles %)) pending)
        found (reduce + 0 (map #(try (detect-activity! ds %)
                                     (catch Throwable e
                                       (println "Peaks: activity" (:id %) "failed:" (ex-message e)) 0))
                               ready))
        prom (+ early-prom (prominence-step! (when-not (loading? ds) (+ (System/currentTimeMillis) budget-ms))))
        r {:queued queued :chunks chunks :activities (count ready) :summits found :prominence prom}]
    (when (some pos? (vals r)) (println "Peaks:" r))
    (assoc r :failed? failed?)))

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
  and the prominence of the peaks that need it (shared: one worker for the install). While a region is
  loading it runs until all heights are done (it can take many minutes); otherwise within a time
  budget. With Overpass busy it retries later. Returns the last round's counts."
  [ds]
  (let [k (context/athlete)]
    (try
      (loop [attempt 1]
        (let [r (run-once! ds)]
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
  "SELECT s.id, s.osm_id, s.activity_id, s.date, s.source, s.dist_m, a.name AS activity_name, a.sport
   FROM summits s LEFT JOIN activities a ON a.id = s.activity_id
   WHERE s.source <> 'dismissed'")

(defn- peak-row [r] (-> r (assoc :listed (= 1 (:listed r)))))

(defn visible-peaks
  "Peaks to show, as maps with :listed, :visits (count, the athlete's), :first and :last visit dates."
  [ds]
  (let [m (min-prominence ds)
        visits (visit-counts ds)]
    (mapv (fn [p]
            (let [v (visits (:id p))]
              (assoc (peak-row p) :visits (or (:visits v) 0) :first (:first v) :last (:last v))))
          (db/query (shared) ["SELECT osm_id AS id, name, ele, lat, lon, prominence, listed
                               FROM osm_peaks WHERE listed = 1 OR prominence >= ?" m]))))

(defn map-peaks
  "What the Map tab draws, one short row per peak (there can be tens of thousands):
  [id lat lon ele prominence visits listed? name last-visit]."
  [ds]
  (mapv (fn [{:keys [id lat lon ele prominence visits listed name last]}]
          [id lat lon ele (some-> prominence Math/round) visits listed name last])
        (visible-peaks ds)))

(defn- visits-by-peak [ds]
  (group-by :osm_id (db/query ds [(str visits-sql " ORDER BY s.date DESC")])))

(defn summary
  "The Peaks page: counts, the bagged peaks with their visits, the lists with progress."
  [ds]
  (let [peaks (visible-peaks ds)
        visits (visits-by-peak ds)
        year (str (.getYear (LocalDate/now)))
        bagged (->> peaks (filter #(pos? (:visits %)))
                    (mapv #(assoc % :visit_list (mapv (fn [v] (dissoc v :osm_id)) (visits (:id %))))))
        bagged-ids (set (map :id bagged))
        highest (first (sort-by (comp - :ele) (filter :ele bagged)))]
    {:bagged (count bagged)
     :this_year (count (filter #(= year (some-> (:first %) (subs 0 4))) bagged))
     :visits (reduce + 0 (map :visits bagged))
     :highest (some-> highest (select-keys [:id :name :ele]))
     :min_prominence (min-prominence ds)
     :pending (pending-prominence)
     :peaks (vec (sort-by :last #(compare %2 %1) bagged))
     :lists (mapv (fn [l]
                    (summits/list-progress (update l :peaks (fn [ps] (mapv #(select-keys % [:osm_id :name :ele]) ps)))
                                           bagged-ids))
                  (lists))}))

(defn activity-summits
  "The peaks an activity passed (visible ones): [{:id :name :ele :lat :lon :prominence :visit_id :source}]."
  [ds activity-id]
  (let [m (min-prominence ds)
        visits (db/query ds ["SELECT id AS visit_id, osm_id, source, dist_m, time_s FROM summits
                              WHERE activity_id = ? AND source <> 'dismissed' ORDER BY time_s" activity-id])
        peaks (when (seq visits)
                (into {} (map (juxt :id identity))
                      (db/query (shared) (into [(str "SELECT osm_id AS id, name, ele, lat, lon, prominence, listed
                                                      FROM osm_peaks WHERE osm_id IN ("
                                                     (str/join "," (repeat (count visits) "?")) ")")]
                                               (map :osm_id visits)))))]
    (vec (for [v visits
               :let [p (peaks (:osm_id v))]
               :when (and p (or (= 1 (:listed p)) (and (:prominence p) (>= (:prominence p) m))))]
           (merge (peak-row p) (dissoc v :osm_id))))))

;; ---------------------------------------------------------------------------
;; Manual bags

(defn bag!
  "Marks a peak as reached on `date` without GPS. Returns the new visit's id."
  [ds osm-id date]
  (when-not (seq (db/query (shared) ["SELECT 1 FROM osm_peaks WHERE osm_id = ?" osm-id]))
    (throw (ex-info "Unknown peak." {:osm_id osm-id})))
  (when-not (and (string? date) (re-matches #"\d{4}-\d{2}-\d{2}" date))
    (throw (ex-info "The date must look like 2026-07-14." {:date date})))
  (jdbc/execute! ds ["INSERT INTO summits (osm_id, activity_id, date, source, created_at) VALUES (?, NULL, ?, 'manual', ?)"
                     osm-id date (str (java.time.Instant/now))])
  (:id (first (db/query ds ["SELECT max(id) AS id FROM summits"]))))

(defn unbag!
  "Removes a visit: a manual one is deleted, a GPS one is dismissed so detection doesn't bring it back."
  [ds visit-id]
  (jdbc/execute! ds ["DELETE FROM summits WHERE id = ? AND source = 'manual'" visit-id])
  (jdbc/execute! ds ["UPDATE summits SET source = 'dismissed' WHERE id = ? AND source = 'gps'" visit-id])
  {:ok true})
