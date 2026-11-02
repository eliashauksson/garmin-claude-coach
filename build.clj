(ns build
  "The installable app: one jar with the server, the built frontend and
  training-principles.md. Build the frontend first (npm run release); then
  clojure -T:build uber  ->  target/garmin-coach.jar
  Run with: java -cp target/garmin-coach.jar clojure.main -m coach.api --open"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/garmin-coach.jar")

(defn clean [_] (b/delete {:path "target"}))

(defn- version
  "COACH_VERSION (the release workflow sets the tag), else git's description."
  []
  (or (not-empty (System/getenv "COACH_VERSION"))
      (try (b/git-process {:git-args "describe --tags --always --dirty"}) (catch Exception _ nil))
      "dev"))

(defn uber [_]
  (when-not (.exists (java.io.File. "resources/public/js/main.js"))
    (throw (ex-info "Build the frontend first: npm install && npm run release" {})))
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "resources"] :target-dir class-dir})
    (b/copy-file {:src "training-principles.md" :target (str class-dir "/training-principles.md")})
    (b/write-file {:path (str class-dir "/coach/version.txt") :string (version)})
    ;; compiled ahead of time, so the app starts in seconds
    (b/compile-clj {:basis basis :ns-compile '[coach.api coach.sync coach.backup coach.auth coach.garmin-mcp]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file jar-file :basis basis})
    (println "Built" jar-file (version))))
