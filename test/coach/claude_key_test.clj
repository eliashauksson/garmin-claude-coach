(ns coach.claude-key-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.claude :as claude]))

(def good-key "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789-a1b2")

(deftest key-file-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "coach-key" (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (str dir "/coach/anthropic-key")]
    (binding [claude/*key-file* f]
      (testing "a malformed key is refused and nothing is written"
        (is (thrown? clojure.lang.ExceptionInfo (claude/save-key! "hello")))
        (is (not (.exists (java.io.File. f)))))
      (testing "a key is saved readable only by this user, and never shown in full"
        (let [st (claude/save-key! (str "  " good-key "\n"))]
          (is (= {:connected true :source "file" :hint "sk-ant-…a1b2"} (select-keys st [:connected :source :hint])))
          (is (= good-key (slurp f)))
          (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString
                              (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. f)) (make-array java.nio.file.LinkOption 0)))))
          (is (= "rwx------" (java.nio.file.attribute.PosixFilePermissions/toString
                              (java.nio.file.Files/getPosixFilePermissions (.toPath (.getParentFile (java.io.File. f))) (make-array java.nio.file.LinkOption 0)))))))
      (testing "removing it"
        (claude/remove-key!)
        (is (not (.exists (java.io.File. f))))
        (when-not (System/getenv "ANTHROPIC_API_KEY")
          (is (false? (:connected (claude/key-status))))
          (is (thrown? clojure.lang.ExceptionInfo (claude/api-key)))))))
  (testing "which failures mean the key itself is wrong"
    (is (claude/key-rejected? "401 Unauthorized: authentication_error, invalid x-api-key"))
    (is (not (claude/key-rejected? "Your credit balance is too low")))
    (is (not (claude/key-rejected? "Connection refused")))))
