(ns coach.app
  "The installed app around the dashboard: its version and updates, starting
  with the computer, restarting itself, and the actions that need a restart
  because they replace or move the data folder (restoring a backup, moving
  the data). Those are written to config.edn as :pending and done by the
  next start, before anything opens the database."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.i18n :refer [t tk]]
            [coach.paths :as paths])
  (:import (java.io File)
           (java.lang ProcessBuilder$Redirect)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.file Files LinkOption StandardCopyOption)
           (java.time Duration Instant)))

(def repo "eliashauksson/garmin-claude-coach")
(def release-url (str "https://github.com/" repo "/releases/latest"))

;; ---------------------------------------------------------------------------
;; Version and updates

(defn version
  "The release this jar was built from (v1.2.3), else a git description or \"dev\"."
  []
  (or (some-> (io/resource "coach/version.txt") slurp str/trim not-empty) "dev"))

(defn parse-version
  "Pure: [1 2 3] from \"v1.2.3\" (or \"1.2.3\"); nil for anything else,
  including builds between releases (v1.2.3-4-gabc)."
  [s]
  (when-let [[_ a b c] (re-matches #"v?(\d+)\.(\d+)\.(\d+)" (str s))]
    (mapv parse-long [a b c])))

(defn newer?
  "Pure: whether release `latest` is newer than `current`."
  [latest current]
  (let [l (parse-version latest) c (parse-version current)]
    (boolean (and l c (pos? (compare l c))))))

(defonce ^:private latest (atom nil)) ; {:tag "v1.2.0" :checked "…"}

(defn- fetch-latest-tag []
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10))
                   (.followRedirects java.net.http.HttpClient$Redirect/NORMAL) .build)
        req    (-> (HttpRequest/newBuilder (URI. (str "https://api.github.com/repos/" repo "/releases/latest")))
                   (.header "Accept" "application/vnd.github+json")
                   (.header "User-Agent" "garmin-coach")
                   (.timeout (Duration/ofSeconds 15)) .build)
        resp   (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode resp))
      (get (json/read-str (.body resp)) "tag_name"))))

(defn check-for-update!
  "Asks GitHub for the newest release (the installed app with a release
  version only). Never throws."
  []
  (when (and (paths/installed?) (parse-version (version)))
    (try (when-let [tag (fetch-latest-tag)]
           (reset! latest {:tag tag :checked (str (Instant/now))}))
         (catch Exception e (println "Update check failed:" (ex-message e))))))

(defn- stale? [checked]
  (or (nil? checked) (.isBefore (Instant/parse checked) (.minus (Instant/now) (Duration/ofHours 1)))))

(defn update-status
  "The newest release known. Looked up again in the background when the last
  check is over an hour old (e.g. Settings opened), so a new release shows
  up soon."
  []
  (when (stale? (:checked @latest))
    (swap! latest assoc :checked (str (Instant/now))) ; one lookup at a time
    (future (check-for-update!)))
  (let [{:keys [tag checked]} @latest]
    {:latest tag :checked checked :available (newer? tag (version)) :url release-url}))

;; ---------------------------------------------------------------------------
;; Starting the app

(defn- java-bin
  "This Java; javaw on Windows, so no console window opens."
  []
  (str (io/file (System/getProperty "java.home") "bin" (if (paths/windows?) "javaw.exe" "java"))))

(defn launch-command
  "Command line that starts this app on `port`; `mode` is --open or
  --background, `extra` more flags (--no-sync)."
  [port mode & extra]
  (into [(java-bin) "-cp"
   ;; absolute: Windows runs login items from System32
   (str/join File/pathSeparator (map #(.getAbsolutePath (io/file %))
                                     (str/split (System/getProperty "java.class.path")
                                                (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))))
   "clojure.main" "-m" "coach.api" (str port) mode]
        (remove nil? extra)))

(defn spawn!
  "Starts `cmd` detached from this process, in the app folder."
  [cmd & [{:keys [env log]}]]
  (let [pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.directory (paths/app-dir))
             (.redirectInput (ProcessBuilder$Redirect/from (io/file (if (paths/windows?) "NUL" "/dev/null")))))]
    (if log
      (doto pb (.redirectErrorStream true) (.redirectOutput (ProcessBuilder$Redirect/appendTo (io/file log))))
      (doto pb (.redirectOutput ProcessBuilder$Redirect/DISCARD) (.redirectError ProcessBuilder$Redirect/DISCARD)))
    (doseq [[k v] env] (.put (.environment pb) k v))
    (.start pb)))

(defn restart!
  "Stops this app with `stop!` and starts it again in the background (the
  open page reconnects by itself), with the same `flags`."
  [port flags stop!]
  (println "Restarting…")
  (stop!)
  (paths/unlock-data-dir!)
  (spawn! (apply launch-command port "--background" flags))
  (shutdown-agents)
  (System/exit 0))

;; ---------------------------------------------------------------------------
;; Starting with the computer: an autostart entry (Linux) or a Run entry in
;; the registry (Windows), only for the installed app.

(def ^:private run-key "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run")
(def ^:private run-name "Garmin Coach")

(defn- autostart-file ^File []
  (io/file (or (not-empty (System/getenv "XDG_CONFIG_HOME")) (str (System/getProperty "user.home") "/.config"))
           "autostart" "garmin-coach.desktop"))

(defn- run! [& cmd]
  (let [p (-> (ProcessBuilder. ^java.util.List (vec cmd)) (.redirectErrorStream true) .start)]
    (slurp (.getInputStream p))
    (.waitFor p)))

(defn autostart? []
  (when (paths/installed?)
    (if (paths/windows?)
      (zero? (run! "reg" "query" run-key "/v" run-name))
      (.isFile (autostart-file)))))

(defn- reg-escape [s] (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))

(defn set-autostart!
  "Turns starting with the computer on or off. Returns the new state."
  [on? port]
  (when-not (paths/installed?)
    (throw (ex-info (t "Only the installed app can start with the computer.") {})))
  (if (paths/windows?)
    (if on?
      ;; via a .reg file: reg add can't take a value with quotes in it from Java
      (let [cmd (launch-command port "--background")
            v   (str "\"" (first cmd) "\" -cp \"" (nth cmd 2) "\" " (str/join " " (drop 3 cmd)))
            f   (File/createTempFile "garmin-coach" ".reg")]
        ;; regedit's format: UTF-16 little-endian with a byte-order mark
        (spit f (str "\uFEFFWindows Registry Editor Version 5.00\r\n\r\n[" run-key "]\r\n\""
                     run-name "\"=\"" (reg-escape v) "\"\r\n")
              :encoding "UTF-16LE")
        (try (run! "reg" "import" (str f)) (finally (.delete f))))
      (run! "reg" "delete" run-key "/v" run-name "/f"))
    (let [f (autostart-file)]
      (if on?
        (do (io/make-parents f)
            (spit f (str "[Desktop Entry]\nType=Application\nName=Garmin Coach\n"
                         "Comment=Starts Garmin Coach in the background, so it keeps syncing\n"
                         "Exec=\"" (paths/app-file "garmin-coach") "\" --background\n"
                         "Icon=" (paths/app-file "app" "icon.png") "\n"
                         "Terminal=false\nX-GNOME-Autostart-enabled=true\n")))
        (.delete f))))
  (autostart?))

;; ---------------------------------------------------------------------------
;; Updating: runs the installer, which stops this app, replaces it and starts
;; it again (in the background: the open page reconnects).

(def installer-urls
  {:linux   (str "https://raw.githubusercontent.com/" repo "/main/installer/install.sh")
   :windows (str "https://raw.githubusercontent.com/" repo "/main/installer/install.ps1")})

(defn start-update! [port]
  (when-not (paths/installed?) (throw (ex-info (t "Only the installed app updates itself.") {})))
  (let [env {"GARMIN_COACH_DIR" (str (paths/app-dir)) "GARMIN_COACH_PORT" (str port)
             "GARMIN_COACH_UPDATING" "1"}
        log (paths/data-file "update.log")]
    (paths/trim-log! log)
    (spit log (str "\n--- " (Instant/now) " ---\n") :append true)
    (spawn! (if (paths/windows?)
              ["powershell" "-NoProfile" "-ExecutionPolicy" "Bypass" "-Command"
               (str "irm " (:windows installer-urls) " | iex")]
              ["setsid" "bash" "-c" (str "curl -fsSL " (:linux installer-urls) " | bash")])
            {:env env :log log})
    {:started true}))

;; ---------------------------------------------------------------------------
;; Moving the data folder

(defn- dir-size [^File d]
  (reduce + 0 (map #(.length ^File %) (filter #(.isFile ^File %) (file-seq d)))))

(defn- inside? [^File child ^File parent]
  (let [c (.normalize (.toPath (.getAbsoluteFile child)))
        p (.normalize (.toPath (.getAbsoluteFile parent)))]
    (and (not= c p) (.startsWith c p))))

(defn- expand-home [s]
  (let [s (str/trim (str s))]
    (if (or (= s "~") (str/starts-with? s "~/") (str/starts-with? s "~\\"))
      (str (System/getProperty "user.home") (subs s 1))
      s)))

(defn check-data-dir
  "Whether the data can go to folder `path`: {:ok true :to …}, {:existing true
  :to …} when it already holds coach data, or {:error …}."
  [path]
  (let [current (paths/data-dir)
        to      (some-> (not-empty (expand-home path)) io/file)]
    (cond
      (paths/env-data-dir) {:error (t "The data folder is set by COACH_DATA_DIR; change it there.")}
      (nil? to)            {:error (t "Enter a folder.")}
      (not (.isAbsolute to)) {:error (t "Enter the full path of the folder, e.g. /home/you/Documents/Garmin Coach or D:\\Garmin Coach.")}
      :else
      (let [to (.getAbsoluteFile (.toFile (.normalize (.toPath to))))]
        (cond
          (= (.toPath to) (.toPath current)) {:error (t "That is the data folder already.")}
          (inside? to current) {:error (t "The new folder can't be inside the current data folder.")}
          (.isFile to)         {:error (t "That is a file, not a folder.")}
          (.isFile (io/file to "coach.db")) {:existing true :to (str to)}
          (seq (remove #{"app.lock"} (.list to))) {:error (t "That folder isn't empty. Choose an empty or new folder.")}
          :else
          (let [probe (io/file to ".coach-write-test")]
            (try (.mkdirs to) (spit probe "") (.delete probe)
                 (let [free (.getUsableSpace to) need (* 1.1 (dir-size current))]
                   (if (< free need)
                     {:error (t "Not enough space there ({0} MB needed)." (Math/round (/ need 1048576.0)))}
                     {:ok true :to (str to)}))
                 (catch Exception _ {:error (t "Can't write to that folder.")}))))))))

(defn- copy-tree! [^File from ^File to]
  (doseq [^File f (file-seq from)
          :let [rel (.relativize (.toPath from) (.toPath f))
                target (.resolve (.toPath to) rel)]
          :when (not= "app.lock" (.getName f))]
    (if (.isDirectory f)
      (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
      (Files/copy (.toPath f) target
                  ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/COPY_ATTRIBUTES])))))

(defn- delete-tree! [^File d]
  (doseq [^File f (reverse (file-seq d))] (.delete f)))

;; ---------------------------------------------------------------------------
;; Pending actions, done at the next start

(defonce notice (atom nil)) ; what the last pending action did, for Settings: {:ok :kind :text :args}

(defn notice-message
  "The notice with its text in the current language (it's written before
  the language setting is loaded)."
  []
  (when-let [{:keys [text args] :as n} @notice]
    (-> n (dissoc :text :args) (assoc :message (apply t text args)))))

(defn set-pending! [m] (paths/update-config! assoc :pending m))

(defn- set-data-dir-config! [^File to]
  (paths/update-config! #(if (= (.toPath to) (.toPath (.getAbsoluteFile (paths/default-data-dir))))
                           (dissoc % :data-dir)
                           (assoc % :data-dir (str to)))))

(defn apply-pending!
  "Run at start, with the current data folder locked and before the
  database is opened. `restore!` is (fn [zip-file]) restoring a backup;
  `after-switch` runs once the data folder has changed."
  [restore! & [{:keys [after-switch]}]]
  (when-let [{:keys [move-data use-data restore delete-after]} (:pending (paths/read-config))]
    (paths/update-config! dissoc :pending) ; once, even if it fails
    (try
      (cond
        (or move-data use-data)
        (let [from (paths/data-dir)
              to   (.getAbsoluteFile (io/file (or move-data use-data)))]
          (when move-data
            (println "Moving the data from" (str from) "to" (str to))
            (copy-tree! from to))
          (set-data-dir-config! to)
          (paths/unlock-data-dir!)
          (paths/use-data-dir! to)
          (when-not (paths/lock-data-dir! 30000)
            (throw (ex-info (t "The new data folder is in use by another Garmin Coach.") {})))
          (when after-switch (after-switch)) ; e.g. the log moves along, so the old folder can go
          (when move-data (delete-tree! from))
          (reset! notice {:ok true :kind "data-dir" :args [(str to)]
                          :text (if move-data (tk "Data moved to {0}.") (tk "Now using the data in {0}."))}))

        restore
        (do (println "Restoring" restore)
            (restore! (io/file restore))
            (when delete-after (io/delete-file restore true))
            (reset! notice {:ok true :kind "restore" :text (tk "Backup restored.")})))
      (catch Exception e
        (println "Pending action failed:" (ex-message e))
        (reset! notice {:ok false :kind (if restore "restore" "data-dir") :text (ex-message e)})))))
