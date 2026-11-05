(ns coach.mcp
  "Minimal MCP client (JSON-RPC 2.0) over stdio (newline-delimited, a local
  server process) or Streamable HTTP (a remote server, with a bearer token).
  Only what we need: initialize, tools/list, tools/call."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader BufferedWriter File)
           (java.lang ProcessBuilder ProcessBuilder$Redirect)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)
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

(defn- stdio-request [{:keys [pending next-id] :as client} method params timeout-ms]
  (let [id (swap! next-id inc)
        p  (promise)]
    (swap! pending assoc id p)
    (send! client {"jsonrpc" "2.0" "id" id "method" method "params" params})
    (let [resp (deref p timeout-ms ::timeout)]
      (if (= resp ::timeout)
        (do (swap! pending dissoc id)
            (throw (ex-info "MCP request timed out" {:method method :params params})))
        resp))))

;; ---------------------------------------------------------------------------
;; Streamable HTTP: every message is a POST; the answer comes back as JSON or
;; as a short server-sent-event stream. The server may hand out a session id
;; on initialize, which every later request repeats.

(defn sse-messages
  "Pure: the JSON-RPC messages in a text/event-stream body (each event's
  data: lines joined)."
  [body]
  (->> (str/split (str/replace (str body) "\r\n" "\n") #"\n\n+")
       (keep (fn [event]
               (let [data (->> (str/split-lines event)
                               (keep #(when (str/starts-with? % "data:") (str/triml (subs % 5)))))]
                 (when (seq data)
                   (try (json/read-str (str/join "\n" data)) (catch Exception _ nil))))))
       vec))

(defn http-status-kind
  "Pure: the error :kind of an HTTP status from a remote MCP server."
  [status]
  (cond (#{401 403} status) :auth
        (= 429 status)      :rate-limit
        (>= status 500)     :server-error
        :else               :other))

(defn- http-post [{:keys [^HttpClient http url token-fn session protocol]} msg timeout-ms]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofMillis timeout-ms))
              (.header "Content-Type" "application/json")
              (.header "Accept" "application/json, text/event-stream")
              (.header "Authorization" (str "Bearer " (token-fn)))
              (.POST (HttpRequest$BodyPublishers/ofString (json/write-str msg))))
        b (cond-> b
            @session  (.header "Mcp-Session-Id" @session)
            @protocol (.header "MCP-Protocol-Version" @protocol))
        resp (try (.send http (.build b) (HttpResponse$BodyHandlers/ofString))
                  (catch java.net.http.HttpTimeoutException _
                    (throw (ex-info "MCP request timed out" {:method (get msg "method") :kind :timeout})))
                  (catch java.io.IOException e
                    (throw (ex-info (str "MCP server unreachable: " (ex-message e))
                                    {:method (get msg "method") :kind :unreachable} e))))
        status (.statusCode resp)]
    (when-let [sid (.orElse (.firstValue (.headers resp) "mcp-session-id") nil)]
      (reset! session sid))
    (when (>= status 300)
      (throw (ex-info (str "MCP server answered HTTP " status)
                      {:method (get msg "method") :status status :kind (http-status-kind status)})))
    {:content-type (.orElse (.firstValue (.headers resp) "content-type") "")
     :body (.body resp)}))

(defn- http-request [{:keys [next-id] :as client} method params timeout-ms]
  (let [id (swap! next-id inc)
        {:keys [content-type body]}
        (http-post client {"jsonrpc" "2.0" "id" id "method" method "params" params} timeout-ms)
        msgs (if (str/includes? content-type "event-stream")
               (sse-messages body)
               [(json/read-str body)])]
    (or (first (filter #(= id (get % "id")) msgs))
        (throw (ex-info "MCP server sent no answer" {:method method :kind :server-error})))))

(defn request
  "Sends a JSON-RPC request and blocks for the result. Throws on error/timeout."
  ([client method params] (request client method params 120000))
  ([client method params timeout-ms]
   (let [resp (if (:url client)
                (http-request client method params timeout-ms)
                (stdio-request client method params timeout-ms))]
     (if (get resp "error")
       (throw (ex-info (str "MCP error: " (get-in resp ["error" "message"]))
                       {:method method :params params :error (get resp "error")}))
       (get resp "result")))))

(defn notify! [client method params]
  (let [msg (cond-> {"jsonrpc" "2.0" "method" method}
              params (assoc "params" params))]
    (if (:url client)
      (http-post client msg 30000)
      (send! client msg))))

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

(defn start-http!
  "Connects to the Streamable HTTP MCP server at `url`, performs the
  initialize handshake and returns a client map. `token-fn` is called for
  each request and returns the bearer token (so it can renew it)."
  [{:keys [url token-fn]}]
  (let [client {:url      url
                :token-fn token-fn
                :http     (-> (HttpClient/newBuilder)
                              (.connectTimeout (Duration/ofSeconds 20))
                              (.build))
                :session  (atom nil)
                :protocol (atom nil)
                :next-id  (atom 0)}
        init (request client "initialize"
                      {"protocolVersion" protocol-version
                       "capabilities"    {}
                       "clientInfo"      {"name" "coach" "version" "0.1.0"}}
                      60000)]
    (reset! (:protocol client) (get init "protocolVersion" protocol-version))
    (notify! client "notifications/initialized" nil)
    (assoc client :server-info init)))

(defn stop! [{:keys [^Process process ^BufferedWriter writer url]}]
  (when-not url
    (try (.close writer) (catch Exception _))
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroy process))))

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
