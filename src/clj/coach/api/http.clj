(ns coach.api.http
  "Response helpers shared by the API's namespaces: JSON answers, the request
  body, and the turning of bad input (ex-info) into a 400 and of anything else
  into a 500."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.i18n :as i18n]
            [coach.translate :as translate])
  (:import (java.io ByteArrayOutputStream)
           (java.util.zip GZIPOutputStream)))

(defn json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-str body)})

(defn ok [body] (json-response 200 body))

(defn ok-tr
  "ok, with Claude's texts in the current dashboard language (coach.translate)."
  [ds body]
  (ok (translate/localize ds @i18n/lang body)))

(defn athlete-tr
  "The athlete status with its overview (Claude's) in the current language;
  athlete.md itself is never translated."
  [ds status]
  (update status :summary #(when % (translate/localize ds @i18n/lang %))))

(defn iso-date!
  "`s` when it is a YYYY-MM-DD date, else an error for the athlete."
  [s]
  (if (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s))
    s
    (throw (ex-info (i18n/t "Not a date: {0}" s) {}))))

(defn body [req]
  (some-> (:body req) slurp not-empty (json/read-str :key-fn keyword)))

(defn guarded
  "Turns bad input (ex-info) into a 400 instead of a 500."
  [handler]
  (fn [req]
    (try (handler req)
         (catch clojure.lang.ExceptionInfo e
           (json-response 400 {:error (ex-message e)})))))

(defn wrap-errors
  "Unexpected exceptions become a JSON 500 with the message (and a log line),
  so the page can say what went wrong instead of \"HTTP 500\"."
  [handler]
  (fn [req]
    (try (handler req)
         (catch Throwable e
           (println "Error on" (:request-method req) (:uri req) "-" (str e))
           (json-response 500 {:error (str "Server error: " (or (ex-message e) (str (class e))))})))))

(def ^:private compressible
  #"(?i)^(application/(json|javascript|manifest\+json)|text/|image/svg\+xml)")

(defn- body-bytes
  "The response body as bytes (a string, file or stream), or nil for other kinds."
  ^bytes [body]
  (cond
    (string? body)                           (.getBytes ^String body "UTF-8")
    (instance? java.io.File body)            (with-open [in (io/input-stream body)] (.readAllBytes in))
    (instance? java.io.InputStream body)     (with-open [in ^java.io.InputStream body] (.readAllBytes in))))

(defn wrap-gzip
  "Gzips JSON, JS, CSS, HTML and SVG answers (over 1 KB) for clients that accept it:
  the JS bundle and the heatmap tracks are the big ones."
  [handler]
  (fn [req]
    (let [r  (handler req)
          ct (get-in r [:headers "Content-Type"] "")]
      (if (and r
               (= 200 (:status r))
               (some-> (get-in req [:headers "accept-encoding"]) (str/includes? "gzip"))
               (re-find compressible ct)
               (not (get-in r [:headers "Content-Encoding"])))
        (if-let [raw (body-bytes (:body r))]
          (if (< (alength raw) 1024)
            (assoc r :body raw)
            (let [out (ByteArrayOutputStream.)]
              (with-open [gz (GZIPOutputStream. out)] (.write gz raw))
              (-> r
                  (assoc :body (.toByteArray out))
                  (update :headers #(-> (dissoc % "Content-Length")
                                        (assoc "Content-Encoding" "gzip" "Vary" "Accept-Encoding"))))))
          r)
        r))))
