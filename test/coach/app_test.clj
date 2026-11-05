(ns coach.app-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [coach.app :as app]
            [coach.paths :as paths]))

(deftest versions
  (is (= [1 2 3] (app/parse-version "v1.2.3")))
  (is (= [1 10 0] (app/parse-version "1.10.0")))
  (is (nil? (app/parse-version "v1.2.3-4-gabc1234")) "a build between releases")
  (is (nil? (app/parse-version "e3e5420")))
  (is (app/newer? "v1.10.0" "v1.9.2") "numbers, not text")
  (is (not (app/newer? "v1.0.0" "v1.0.0")))
  (is (not (app/newer? "v0.9.0" "v1.0.0")))
  (is (not (app/newer? "v1.1.0" "dev")) "a development build never asks to update")
  (is (not (app/newer? nil "v1.0.0"))))

(defn- temp-dir []
  (doto (.toFile (java.nio.file.Files/createTempDirectory "coach-app" (make-array java.nio.file.attribute.FileAttribute 0)))
    .deleteOnExit))

(deftest check-data-dir-test
  (let [current (temp-dir)]
    (spit (io/file current "coach.db") "x")
    (paths/use-data-dir! current)
    (try
      (testing "a new or empty folder"
        (let [to (io/file (temp-dir) "Garmin Coach")]
          (is (= {:ok true :to (str to)} (app/check-data-dir (str to))))))
      (testing "a folder with coach data: use it instead?"
        (let [other (temp-dir)]
          (spit (io/file other "coach.db") "y")
          (is (:existing (app/check-data-dir (str other))))))
      (testing "refused"
        (is (:error (app/check-data-dir "")))
        (is (:error (app/check-data-dir "relative/folder")))
        (is (:error (app/check-data-dir (str current))) "the same folder")
        (is (:error (app/check-data-dir (str (io/file current "inside")))))
        (let [full (temp-dir)]
          (spit (io/file full "holiday.jpg") "")
          (is (:error (app/check-data-dir (str full))) "a folder with other files"))
        (let [f (io/file (temp-dir) "a-file")]
          (spit f "")
          (is (:error (app/check-data-dir (str f))))))
      (finally (paths/use-data-dir! (paths/configured-data-dir))))))

(deftest trim-log-test
  (let [f (io/file (temp-dir) "x.log")]
    (spit f (apply str (for [i (range 1000)] (str "line " i "\n"))))
    (is (not (paths/trim-log! f 100000 1000)) "small enough: untouched")
    (is (paths/trim-log! f 5000 1000))
    (let [lines (str/split-lines (slurp f))]
      (is (re-find #"older lines removed" (first lines)))
      (is (= "line 999" (last lines)) "the newest lines stay")
      (is (every? #(re-matches #"line \d+" %) (rest lines)) "whole lines only")
      (is (< (.length f) 1100)))))
