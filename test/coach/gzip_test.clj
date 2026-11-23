(ns coach.gzip-test
  (:require [clojure.test :refer [deftest is]]
            [coach.api.http :as http])
  (:import (java.io ByteArrayInputStream)
           (java.util.zip GZIPInputStream)))

(defn- gunzip [^bytes b] (String. (.readAllBytes (GZIPInputStream. (ByteArrayInputStream. b))) "UTF-8"))

(def ^:private big (apply str (repeat 500 "hello world ")))

(defn- call [resp accept]
  ((http/wrap-gzip (constantly resp)) {:headers (if accept {"accept-encoding" accept} {})}))

(deftest gzips-big-json-for-clients-that-accept-it
  (let [r (call {:status 200 :headers {"Content-Type" "application/json"} :body big} "gzip, br")]
    (is (= "gzip" (get-in r [:headers "Content-Encoding"])))
    (is (= big (gunzip (:body r))))))

(deftest leaves-others-alone
  (is (string? (:body (call {:status 200 :headers {"Content-Type" "application/json"} :body big} nil))))
  (is (string? (:body (call {:status 200 :headers {"Content-Type" "application/zip"} :body big} "gzip"))))
  (is (nil? (get-in (call {:status 200 :headers {"Content-Type" "application/json"} :body "{}"} "gzip")
                    [:headers "Content-Encoding"])))
  (is (= 304 (:status (call {:status 304 :headers {"Content-Type" "text/css"}} "gzip")))))
