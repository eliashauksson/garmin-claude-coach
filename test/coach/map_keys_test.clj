(ns coach.map-keys-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.api :as api]
            [coach.paths :as paths]))

(deftest tracestrack-key-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "coach-map-keys"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (java.io.File. dir "config.edn")
        save! @#'api/save-map-keys!
        get-keys @#'api/map-keys]
    (with-redefs [paths/config-file (constantly f)]
      (testing "no key at first"
        (is (nil? (:tracestrack (get-keys)))))
      (testing "a key is trimmed and saved in the athlete's config"
        (is (= "abcDEF123456" (:tracestrack (save! {:tracestrack "  abcDEF123456 \n"}))))
        (is (= "abcDEF123456" (:tracestrack (get-keys)))))
      (testing "something that is no key is refused and the saved one stays"
        (is (thrown? clojure.lang.ExceptionInfo (save! {:tracestrack "not a key!"})))
        (is (= "abcDEF123456" (:tracestrack (get-keys)))))
      (testing "blank removes it"
        (is (nil? (:tracestrack (save! {:tracestrack ""}))))
        (is (nil? (:tracestrack (get-keys))))))))
