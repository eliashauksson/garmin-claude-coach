(ns coach.token-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.token :as token]))

(defn- temp-token-file []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "coach-token" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (str dir "/coach/dashboard-token")))

(deftest token-file-test
  (let [f (temp-token-file)]
    (binding [token/*token-file* f]
      (token/reset-cache!)
      (testing "created once, owner-only, and stable"
        (let [t (token/ensure!)]
          (is (string? t))
          (is (>= (count t) 24))
          (is (= t (token/ensure!)) "same token on the next call")
          (is (= t (slurp f)) "persisted")
          (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString
                              (java.nio.file.Files/getPosixFilePermissions
                               (.toPath (java.io.File. f)) (make-array java.nio.file.LinkOption 0))))
              "readable only by this user")))
      (testing "valid? accepts the token and rejects anything else"
        (is (token/valid? (token/ensure!)))
        (is (not (token/valid? "wrong")))
        (is (not (token/valid? nil)))
        (is (not (token/valid? "")))))
    (testing "a fresh file gets a different token"
      (let [f2 (temp-token-file)]
        (binding [token/*token-file* f2]
          (token/reset-cache!)
          (is (not= (slurp f) (token/ensure!))))))
    (token/reset-cache!)))
