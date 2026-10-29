(ns coach.mcp
  "Minimal MCP client over stdio (JSON-RPC 2.0, newline-delimited).
  Only what we need: initialize, tools/list, tools/call."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader BufferedWriter File)
           (java.lang ProcessBuilder ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def protocol-version "2025-06-18")

(defn- read-loop
  "Reads JSON-RPC messages from the server's stdout and delivers responses
  to the matching pending promise. Notifications are ignored."
  [^BufferedReader rdr pending]
  (try
    (loop []
      (when-let [line (.readLine rdr)]
        (when-not (str/blank? line)
          (let [msg (try (json/read-str line) (catch Exception _ nil))]
            (when-let [id (get msg "id")]
              (when-let [p (get @pending id)]
                (swap! pending dissoc id)
                (deliver p msg)))))
        (recur)))
    (catch java.io.IOException _))
  ;; stdout closed: fail anything still waiting
  (doseq [[id p] @pending]
    (deliver p {"error" {"code" -32000 "message" "MCP server exited"}})
    (swap! pending dissoc id)))

(defn- send! [{:keys [^BufferedWriter writer]} msg]
  (locking writer
    (.write writer (json/write-str msg))
    (.write writer "\n")
    (.flush writer)))

(defn request
  "Sends a JSON-RPC request and blocks for the result. Throws on error/timeout."
  ([client method params] (request client method params 120000))
  ([{:keys [pending next-id] :as client} method params timeout-ms]
   (let [id (swap! next-id inc)
         p  (promise)]
     (swap! pending assoc id p)
     (send! client {"jsonrpc" "2.0" "id" id "method" method "params" params})
     (let [resp (deref p timeout-ms ::timeout)]
       (cond
         (= resp ::timeout)
         (do (swap! pending dissoc id)
             (throw (ex-info "MCP request timed out" {:method method :params params})))

         (get resp "error")
         (throw (ex-info (str "MCP error: " (get-in resp ["error" "message"]))
                         {:method method :params params :error (get resp "error")}))

         :else (get resp "result"))))))

(defn notify! [client method params]
  (send! client (cond-> {"jsonrpc" "2.0" "method" method}
                  params (assoc "params" params))))

(defn start!
  "Spawns the MCP server `cmd` (vector of strings) with extra `env` (map),
  performs the initialize handshake and returns a client map.
  Server stderr is appended to `stderr-file`."
  [{:keys [cmd env stderr-file] :or {stderr-file "mcp-server.log"}}]
  (let [pb   (doto (ProcessBuilder. ^java.util.List cmd)
               (.redirectError (ProcessBuilder$Redirect/appendTo (File. ^String stderr-file))))
        _    (let [penv (.environment pb)]
               (doseq [[k v] env] (.put penv (name k) (str v))))
        proc (.start pb)
        pending (atom {})
        client {:process proc
                :writer  (BufferedWriter. (io/writer (.getOutputStream proc)))
                :pending pending
                :next-id (atom 0)}
        rdr (BufferedReader. (io/reader (.getInputStream proc)))]
    (doto (Thread. ^Runnable #(read-loop rdr pending) "mcp-reader")
      (.setDaemon true)
      (.start))
    (let [init (request client "initialize"
                        {"protocolVersion" protocol-version
                         "capabilities"    {}
                         "clientInfo"      {"name" "coach" "version" "0.1.0"}})]
      (notify! client "notifications/initialized" nil)
      (assoc client :server-info init))))

(defn stop! [{:keys [^Process process ^BufferedWriter writer]}]
  (try (.close writer) (catch Exception _))
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroy process)))

(defn list-tools [client]
  (loop [cursor nil acc []]
    (let [res   (request client "tools/list" (cond-> {} cursor (assoc "cursor" cursor)))
          acc   (into acc (get res "tools"))]
      (if-let [c (get res "nextCursor")] (recur c acc) acc))))

(defn- parse-content
  "Tool results come back as text content; most Garmin tools return JSON text."
  [result]
  (let [texts (keep #(when (= "text" (get % "type")) (get % "text")) (get result "content"))
        parse (fn [t] (try (json/read-str t :key-fn keyword) (catch Exception _ t)))]
    (if-let [sc (get result "structuredContent")]
      (json/read-str (json/write-str sc) :key-fn keyword)
      (case (count texts)
        0 nil
        1 (parse (first texts))
        (mapv parse texts)))))

(defn call-tool
  "Calls tool `tool-name` with `args` (map). Returns parsed content.
  Throws ex-info if the tool reports an error."
  ([client tool-name] (call-tool client tool-name {}))
  ([client tool-name args]
   (let [res (request client "tools/call" {"name" tool-name "arguments" args})]
     (if (get res "isError")
       (throw (ex-info (str "Tool error from " tool-name ": " (parse-content res))
                       {:tool tool-name :args args :result res}))
       (parse-content res)))))
