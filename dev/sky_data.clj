;; The 3D map's night sky (coach.heat): writes resources/public/sky/stars.json and milkyway.jpg.
;; make sky   (clojure -M dev/sky_data.clj)
;;
;; Stars: the Yale Bright Star Catalogue, 5th ed. (Hoffleit & Warren 1991; every star to about 6.5 mag),
;; http://tdc-www.harvard.edu/catalogs/bsc5.html. J2000 positions, V magnitude, B-V colour.
;; Written as one flat array [ra-deg dec-deg vmag b-v ...], brightest first; b-v 0.6 (sun-like) when missing.
;;
;; Milky Way: "The Milky Way panorama", ESO/S. Brunier (CC BY 4.0), https://www.eso.org/public/images/eso0932a/
;; 360x180 degrees in galactic coordinates, galactic centre in the middle. Only the glow is kept: scaled down
;; to 1024x512 (averaging the photo's countless faint stars away), blurred, and the dark-sky level taken off,
;; so the sharp stars come from the catalogue.
(require '[clojure.data.json :as json] '[clojure.java.io :as io] '[clojure.string :as str])
(import '(java.awt RenderingHints) '(java.awt.image BufferedImage ConvolveOp Kernel) '(java.net URI)
        '(java.util.zip GZIPInputStream) '(javax.imageio IIOImage ImageIO ImageWriteParam))

(def bsc5-url "http://tdc-www.harvard.edu/catalogs/bsc5.dat.gz")
(def eso-url "https://cdn.eso.org/images/large/eso0932a.jpg")
(def out-dir "resources/public/sky")

(defn- col
  "Columns `from`..`to` (1-based, as in the catalogue's ReadMe), trimmed; nil when blank."
  [line from to]
  (when (>= (count line) to)
    (not-empty (str/trim (subs line (dec from) to)))))

(defn- star [line]
  (let [rah (col line 76 77) ram (col line 78 79) ras (col line 80 83)
        sgn (col line 84 84) ded (col line 85 86) dem (col line 87 88) des (col line 89 90)
        v   (col line 103 107) bv (col line 110 114)]
    (when (and rah ded v)
      (let [ra  (* 15 (+ (parse-double rah) (/ (parse-double ram) 60) (/ (parse-double ras) 3600)))
            dec (* (if (= "-" sgn) -1 1)
                   (+ (parse-double ded) (/ (parse-double dem) 60) (/ (parse-double des) 3600)))]
        [ra dec (parse-double v) (if bv (parse-double bv) 0.6)]))))

(defn- r2 [x] (/ (Math/round (* x 100.0)) 100.0))

(defn stars! []
  (let [lines (with-open [in (GZIPInputStream. (.openStream (.toURL (URI. bsc5-url))))]
                (doall (line-seq (io/reader in))))
        stars (->> lines (keep star) (sort-by #(nth % 2)))]
    (spit (str out-dir "/stars.json") (json/write-str (mapv r2 (apply concat stars))))
    (println (count stars) "stars of" (count lines) "catalogue entries")))

(def ^:private pano-w 1024)
(def ^:private pano-h 512)
(def ^:private black-level "Dark-sky brightness of the scaled photo, taken off every channel." 24)
(def ^:private gain "Brightness after the dark sky is taken off." 1.6)

(defn- scaled
  "`src` scaled to w x h in halving steps (each one averages 2x2 pixels: the faint stars become glow)."
  [^BufferedImage src w h]
  (loop [^BufferedImage img src]
    (let [nw (max w (quot (.getWidth img) 2)) nh (max h (quot (.getHeight img) 2))
          out (BufferedImage. nw nh BufferedImage/TYPE_INT_RGB)
          g   (.createGraphics out)]
      (.setRenderingHint g RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.drawImage g img 0 0 nw nh nil)
      (.dispose g)
      (if (and (= nw w) (= nh h)) out (recur out)))))

(defn- blurred
  "A 5x5 box blur (edges kept as they are)."
  [^BufferedImage img]
  (let [k (float (/ 1.0 25))]
    (.filter (ConvolveOp. (Kernel. 5 5 (float-array (repeat 25 k))) ConvolveOp/EDGE_NO_OP nil) img nil)))

(defn- glow!
  "Takes the dark-sky level off and brightens what's left: black space, the Milky Way's glow."
  [^BufferedImage img]
  (doseq [y (range (.getHeight img)) x (range (.getWidth img))]
    (let [rgb (.getRGB img x y)
          ch  (fn [shift] (-> (bit-and (bit-shift-right rgb shift) 0xff) (- black-level) (* gain)
                              (max 0) (min 255) long))]
      (.setRGB img x y (unchecked-int (bit-or (bit-shift-left (ch 16) 16) (bit-shift-left (ch 8) 8) (ch 0))))))
  img)

(defn milkyway! []
  (let [src  (ImageIO/read (.toURL (URI. eso-url)))
        img  (glow! (blurred (scaled src pano-w pano-h)))
        jpg  (.next (ImageIO/getImageWritersByFormatName "jpg"))
        prm  (doto (.getDefaultWriteParam jpg)
               (.setCompressionMode ImageWriteParam/MODE_EXPLICIT)
               (.setCompressionQuality (float 0.82)))
        file (io/file out-dir "milkyway.jpg")]
    (io/delete-file file true)
    (with-open [out (ImageIO/createImageOutputStream file)]
      (.setOutput jpg out)
      (.write jpg nil (IIOImage. img nil nil) prm))
    (.dispose jpg)
    (println "Milky Way" (.getWidth src) "x" (.getHeight src) "->" pano-w "x" pano-h "," (.length file) "bytes")))

(.mkdirs (io/file out-dir))
(stars!)
(milkyway!)
