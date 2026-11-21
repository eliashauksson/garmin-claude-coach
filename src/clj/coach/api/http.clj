(ns coach.api.http
  "Response helpers shared by the API's namespaces: JSON answers, the request
  body, and the turning of bad input (ex-info) into a 400 and of anything else
  into a 500."
  (:require [clojure.data.json :as json]
            [coach.i18n :as i18n]
            [coach.translate :as translate]))

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
