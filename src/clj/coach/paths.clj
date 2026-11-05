(ns coach.paths
  "Where the app and its data live.

  The app folder is the installed app's folder (the one holding app/ and
  runtime/), found from the jar's own location, so it doesn't matter which
  folder the app was started from (Windows starts login items in System32).
  In development it is the project folder.

  The data folder is, in order: $COACH_DATA_DIR, the folder chosen in
  Settings (config.edn in the app folder), or data/ in the app folder. It
  is fixed while the app runs; changing it restarts the app.

  A lock file in the data folder makes sure only one running app uses it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File RandomAccessFile)
           (java.nio.channels FileLock)))

(defn windows? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows"))

(defn- jar-app-dir
  "The installed layout: <app folder>/app/garmin-coach.jar."
  []
  (try
    (let [f (some-> (Class/forName "coach.paths__init") .getProtectionDomain .getCodeSource .getLocation .toURI
                    io/file)]
      (when (and f (.isFile f) (= "app" (some-> f .getParentFile .getName)))
        (-> f .getParentFile .getParentFile)))
    (catch Throwable _ nil)))

(def ^:private app-dir* (delay (.getAbsoluteFile ^File (or (jar-app-dir) (io/file (System/getProperty "user.dir"))))))

(defn app-dir ^File [] @app-dir*)

(defn app-file ^File [& parts] (apply io/file (app-dir) parts))

(defn installed?
  "Whether this is the installed app (with its own Java), not a development run."
  []
  (.isDirectory (app-file "runtime" "jre")))

;; ---------------------------------------------------------------------------
;; config.edn: settings that can't live in the database because they say
;; where the database is

(defn config-file ^File [] (app-file "config.edn"))

(defn read-config []
  (let [f (config-file)]
    (or (when (.isFile f) (try (edn/read-string (slurp f)) (catch Exception _ nil))) {})))

(defn write-config! [m]
  (spit (config-file) (pr-str (into (sorted-map) (remove (comp nil? val) m)))))

(defn update-config! [f & args] (write-config! (apply f (read-config) args)))

;; ---------------------------------------------------------------------------
;; Owner-only files, for the secrets in ~/.config/coach (API key, Coros
;; tokens, the dashboard token). POSIX: 0600 file / 0700 dir. Non-POSIX
;; (Windows): an ACL that allows only the file's owner.

(defn- posix-fs? []
  (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix"))

(defn- restrict-acl!
  "Replaces `path`'s ACL with a single entry allowing only its owner. A no-op
  on file systems without ACL support (plain POSIX handles those via perms)."
  [^java.nio.file.Path path]
  (when-let [view (java.nio.file.Files/getFileAttributeView
                   path java.nio.file.attribute.AclFileAttributeView
                   (make-array java.nio.file.LinkOption 0))]
    (let [entry (-> (java.nio.file.attribute.AclEntry/newBuilder)
                    (.setType java.nio.file.attribute.AclEntryType/ALLOW)
                    (.setPrincipal (.getOwner view))
                    (.setPermissions (java.util.EnumSet/allOf java.nio.file.attribute.AclEntryPermission))
                    (.build))]
      (.setAcl view (java.util.Collections/singletonList entry)))))

(defn- lock-owner-only! [^java.nio.file.Path path dir?]
  (if (posix-fs?)
    (java.nio.file.Files/setPosixFilePermissions
     path (java.nio.file.attribute.PosixFilePermissions/fromString (if dir? "rwx------" "rw-------")))
    (restrict-acl! path)))

(defn secure-spit!
  "Writes `content` to `f`, making both the file and its parent folder
  readable only by this user. Perms are set before the write (so the content
  is never briefly world-readable) and again after. Returns the File."
  [f content]
  (let [f    (io/file f)
        dir  (.getAbsoluteFile (.getParentFile (.getAbsoluteFile f)))
        path (.toPath f)]
    (java.nio.file.Files/createDirectories (.toPath dir) (make-array java.nio.file.attribute.FileAttribute 0))
    (lock-owner-only! (.toPath dir) true)
    (when-not (.exists f)
      (if (posix-fs?)
        (java.nio.file.Files/createFile
         path (into-array [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                            (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))]))
        (.createNewFile f)))
    (lock-owner-only! path false)
    (spit f content)
    (lock-owner-only! path false)
    f))

;; ---------------------------------------------------------------------------

(defn default-data-dir ^File [] (app-file "data"))

(defn env-data-dir [] (not-empty (System/getenv "COACH_DATA_DIR")))

(defn configured-data-dir
  "The data folder as configured now (before any restart)."
  ^File []
  (.getAbsoluteFile ^File (io/file (or (env-data-dir) (:data-dir (read-config)) (default-data-dir)))))

(def ^:private data-dir* (atom nil))

(defn data-dir
  "The data folder this run uses."
  ^File []
  (or @data-dir* (reset! data-dir* (configured-data-dir))))

(defn use-data-dir! [dir] (reset! data-dir* (.getAbsoluteFile (io/file dir))))

(defn data-file
  "Path (a string) of a file in the data folder."
  [& parts]
  (str (apply io/file (data-dir) parts)))

;; ---------------------------------------------------------------------------
;; One app per data folder

(defonce ^:private lock (atom nil))

(defn lock-data-dir!
  "Takes the data folder's lock, waiting up to `wait-ms` for another app (or
  the one restarting) to let go. Returns true when held."
  [wait-ms]
  (let [dir (doto (data-dir) .mkdirs)
        raf (RandomAccessFile. (io/file dir "app.lock") "rw")
        ch  (.getChannel raf)
        deadline (+ (System/currentTimeMillis) wait-ms)]
    (loop []
      (let [^FileLock l (try (.tryLock ch) (catch java.nio.channels.OverlappingFileLockException _ nil))]
        (cond l (do (reset! lock {:lock l :raf raf}) true)
              (< (System/currentTimeMillis) deadline) (do (Thread/sleep 500) (recur))
              :else (do (.close raf) false))))))

(def log-max-bytes "A log is trimmed once it is bigger than this…" (* 1024 1024))
(def log-keep-bytes "…to about its newest this much." (* 256 1024))

(defn trim-log!
  "Keeps only the end of log `f` once it has grown past `max-bytes`: whole
  lines, the newest about `keep-bytes`. Call it while the log isn't open
  (Windows can't replace an open file). Returns true when it trimmed."
  ([f] (trim-log! f log-max-bytes log-keep-bytes))
  ([f max-bytes keep-bytes]
   (let [f (io/file f)]
     (when (and (.isFile f) (> (.length f) max-bytes))
       (let [len   (.length f)
             buf   (byte-array keep-bytes)
             _     (with-open [raf (RandomAccessFile. f "r")]
                     (.seek raf (- len keep-bytes))
                     (.readFully raf buf))
             nl    (loop [i 0] (cond (>= i (alength buf)) -1 (= 10 (aget buf i)) i :else (recur (inc i))))
             tail  (if (neg? nl) buf (java.util.Arrays/copyOfRange buf (inc nl) (alength buf)))
             tmp   (io/file (str f ".trim"))]
         (with-open [out (io/output-stream tmp)]
           (.write out (.getBytes (str "[" (java.time.LocalDateTime/now) ": older lines removed]\n") "UTF-8"))
           (.write out ^bytes tail))
         (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                                   (into-array java.nio.file.CopyOption
                                               [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
         true)))))

(defn unlock-data-dir! []
  (when-let [{l :lock ^RandomAccessFile raf :raf} @lock]
    (try (.release ^FileLock l) (.close raf) (catch Exception _))
    (reset! lock nil)))

;; ---------------------------------------------------------------------------
;; Running on a server (docs/server.md): systemd keeps the app running and
;; restarts it, and the dashboard is opened through other host names (a
;; Tailscale address) besides 127.0.0.1.

(defonce ^:private server-mode (atom (= "1" (System/getenv "COACH_SERVER"))))

(defn server?
  "Whether this run is the server install (--server or COACH_SERVER=1)."
  []
  @server-mode)

(defn use-server-mode! [on?] (reset! server-mode (boolean on?)))

(defn parse-hosts
  "Pure: lower-case host names from \"a.ts.net, b\" or [\"a.ts.net\" \"b\"]."
  [v]
  (->> (if (string? v) (str/split v #"[,\s]+") v)
       (map (comp str/lower-case str/trim str))
       (remove str/blank?)
       set))

(def ^:private allowed-hosts*
  (delay (into (parse-hosts (System/getenv "COACH_ALLOWED_HOSTS"))
               (parse-hosts (:allowed-hosts (read-config))))))

(defn allowed-hosts
  "Host names besides this computer's that may open the dashboard: from
  $COACH_ALLOWED_HOSTS (comma-separated) and :allowed-hosts in config.edn.
  Read once per run."
  []
  @allowed-hosts*)
