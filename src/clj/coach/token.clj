(ns coach.token
  "A per-install secret the dashboard page proves it holds, so other local
  processes that can reach 127.0.0.1 but can't read this user's files (and
  don't carry a browser's same-origin headers) can't export the data or
  trigger the self-update. Stored owner-only next to the other secrets.
  Pure except for reading/writing the token file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.paths :as paths])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(def token-file
  "dashboard-token; dashboard-token-<instance> for an extra athlete on a server."
  (str (System/getProperty "user.home") "/.config/coach/" (paths/per-instance "dashboard-token")))
(def ^:dynamic *token-file* token-file)

(defonce ^:private cached (atom nil))

(defn- generate []
  (let [b (byte-array 24)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- read-token []
  (let [f (io/file *token-file*)]
    (when (.isFile f) (not-empty (str/trim (slurp f))))))

(defn ensure!
  "Returns the token, creating and storing it (owner-only) the first time."
  []
  (or @cached
      (reset! cached (or (read-token)
                         (let [t (generate)]
                           (paths/secure-spit! *token-file* t)
                           t)))))

(defn valid?
  "Constant-time comparison of `t` against the current token."
  [t]
  (boolean
   (and (string? t)
        (MessageDigest/isEqual (.getBytes (ensure!) "UTF-8") (.getBytes ^String t "UTF-8")))))

(defn reset-cache!
  "Forgets the cached token (tests rebind *token-file*)."
  []
  (reset! cached nil))
