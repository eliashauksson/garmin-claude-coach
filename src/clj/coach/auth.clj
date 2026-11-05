(ns coach.auth
  "Countdown to the Garmin login expiring.

  The server renews its short-lived access token by itself; what eventually
  runs out is the refresh token. Garmin doesn't expose its expiry, and the
  garmin_mcp docs give ~6 months, so we record the login date ourselves and
  count down from that. It's an estimate: a failing sync is the real signal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.db :as db]
            [coach.garmin-mcp :as gm]
            [coach.i18n :refer [t]])
  (:import (java.time LocalDate)
           (java.time.temporal ChronoUnit)))

(def lifetime-days "Approximate refresh-token lifetime." 180)
(def warn-days 30)
(def urgent-days 7)

(def renew-command "clojure -M -m coach.auth login")

(defn countdown
  "Pure: login date + lifetime -> days left and an alert level
  (:ok, :warn, :urgent, :expired)."
  [login-date today]
  (let [expires (.plusDays (LocalDate/parse login-date) lifetime-days)
        left    (.between ChronoUnit/DAYS (LocalDate/parse today) expires)]
    {:logged_in_on  login-date
     :expires_on    (str expires)
     :days_left     left
     :level         (cond (<= left 0)           :expired
                          (<= left urgent-days) :urgent
                          (<= left warn-days)   :warn
                          :else                 :ok)
     :estimate      true
     :renew_command renew-command}))

(defn with-sync-result
  "Pure: a sync that failed on the login since the last renewal is the real
  signal, whatever the countdown says. `last-sync` is coach.sync/last-result."
  [st last-sync login-at]
  (if (and (= :auth (:kind last-sync))
           (or (nil? login-at) (pos? (compare (:finished_at last-sync) login-at))))
    (assoc st :level :expired :sync_error (:message last-sync) :failed_at (:finished_at last-sync))
    st))

(defn token-dir
  "Where garmin-mcp-auth stores the login (garmin_mcp reads it from there)."
  []
  (or (not-empty (System/getenv "GARMINTOKENS"))
      (str (System/getProperty "user.home") "/.garminconnect")))

(defn connected?
  "Whether Garmin has ever been connected: a login token is stored. (Whether
  it still works shows when a sync runs.)"
  ([] (connected? (token-dir)))
  ([dir] (boolean (some #(and (.isFile ^java.io.File %) (pos? (.length ^java.io.File %)))
                        (.listFiles (io/file dir))))))

(defn status
  "{:level :not_connected} before the first login. Otherwise the countdown
  for the stored login date ({:level :unknown} if none recorded), overridden
  to :expired when the last sync failed on the login."
  ([ds] (status ds (connected?)))
  ([ds connected]
   (if-not connected
     {:level :not_connected :renew_command renew-command}
     (with-sync-result
       (if-let [d (db/get-state ds :garmin_login_on)]
         (countdown d (str (LocalDate/now)))
         {:level :unknown :renew_command renew-command})
       ((requiring-resolve 'coach.sync/last-result) ds)
       (db/get-state ds :garmin_login_at)))))

(defn record-login! [ds date]
  (db/set-state! ds :garmin_login_on date)
  (db/set-state! ds :garmin_login_at (str (java.time.Instant/now))))

(defn login!
  "Runs the interactive garmin-mcp-auth (email, password, MFA) in this
  terminal and, if it succeeds, restarts the countdown."
  [ds]
  (let [proc (-> (ProcessBuilder. ^java.util.List (gm/server-cmd "garmin-mcp-auth" "--force-reauth"))
                 (.inheritIO)
                 (.start))
        exit (.waitFor proc)]
    (if (zero? exit)
      (do (record-login! ds (str (LocalDate/now)))
          (println "Login renewed. Countdown restarted. Press Sync now on the dashboard (or run: make sync).")
          true)
      (do (println "Login failed (exit" exit "); countdown unchanged.")
          false))))


;; ---------------------------------------------------------------------------
;; Login from the dashboard: the same garmin-mcp-auth, with the email and
;; password passed in its environment (never stored) and the two-step
;; verification code written to its input when it asks for one.

(def login-timeout-ms
  "The first login also downloads Python and the Garmin server: give it time."
  (* 5 60 1000))

(defonce ^:private login-job (atom nil))

(defn failure-message
  "Pure: garmin-mcp-auth's reason for a failed login, from its output: the
  lines from its first ✗ on, else its last line."
  [out]
  (let [lines (->> (str/split-lines (str out)) (map str/trim) (remove str/blank?))
        why   (->> lines (drop-while #(not (str/starts-with? % "✗"))) (take 3)
                   (map #(str/trim (str/replace % #"^✗" ""))))]
    (or (not-empty (str/join " " why)) (last lines) "")))

(defn- output [job] (let [out (:out job)] (locking out (str out))))

(defn- wait-for
  "Waits until the login exits or (unless `mfa-asked?`) asks for the code.
  :exited, :mfa or :timeout."
  [{:keys [^Process proc ^Thread reader] :as job} mfa-asked?]
  (let [deadline (+ (System/currentTimeMillis) login-timeout-ms)]
    (loop []
      (cond (not (.isAlive proc))      (do (.join reader 2000) :exited)
            (and (not mfa-asked?) (str/includes? (output job) "Enter MFA code")) :mfa
            (> (System/currentTimeMillis) deadline) :timeout
            :else (do (Thread/sleep 200) (recur))))))

(defn- finish!
  "What the dashboard gets back once the login exited, asks for a code or
  ran out of time."
  [ds job outcome]
  (case outcome
    :mfa     {:status "mfa"}
    :timeout (do (.destroyForcibly ^Process (:proc job)) (reset! login-job nil)
                 {:status "error"
                  :error (t "Garmin didn't answer in time. Check the internet connection and try again.")})
    :exited  (do (reset! login-job nil)
                 (if (zero? (.exitValue ^Process (:proc job)))
                   (do (record-login! ds (str (LocalDate/now))) {:status "ok"})
                   {:status "error"
                    :error  (t "Garmin login failed: {0}" (failure-message (output job)))}))))

(defn cancel-login! []
  (when-let [{:keys [^Process proc]} @login-job] (.destroyForcibly proc))
  (reset! login-job nil))

(defn start-login!
  "Logs in with `email` and `password`. Blocks until it's done ({:status
  \"ok\"}), failed ({:status \"error\" :error …}) or Garmin sent a code
  ({:status \"mfa\"}: finish with submit-code!)."
  [ds email password]
  (let [email (str/trim (str email))]
    (if (or (str/blank? email) (str/blank? (str password)))
      {:status "error" :error (t "Enter your Garmin email and password.")}
      (do (cancel-login!)
          (let [pb   (doto (ProcessBuilder. ^java.util.List (gm/server-cmd "garmin-mcp-auth" "--force-reauth"))
                       (.redirectErrorStream true))
                _    (doto (.environment pb)
                       (.putAll ^java.util.Map gm/python-env)
                       (.put "GARMIN_EMAIL" email)
                       (.put "GARMIN_PASSWORD" (str password)))
                proc (.start pb)
                out  (StringBuilder.)
                rdr  (doto (Thread. ^Runnable
                                    (fn []
                                      (with-open [r (io/reader (.getInputStream proc) :encoding "UTF-8")]
                                        (let [buf (char-array 1024)]
                                          (loop []
                                            (let [n (.read r buf)]
                                              (when (pos? n)
                                                (locking out (.append out buf 0 n))
                                                (recur))))))))
                       (.setDaemon true)
                       (.start))
                job  {:proc proc :out out :reader rdr}]
            (reset! login-job job)
            (finish! ds job (wait-for job false)))))))

(defn submit-code!
  "The two-step verification code Garmin sent, for the login waiting on it."
  [ds code]
  (if-let [{:keys [^Process proc] :as job} @login-job]
    (let [code (str/replace (str code) #"\s" "")]
      (if (str/blank? code)
        {:status "mfa" :error (t "Enter the code Garmin sent you.")}
        (do (doto (io/writer (.getOutputStream proc) :encoding "UTF-8")
              (.write (str code "\n"))
              (.flush))
            (finish! ds job (wait-for job true)))))
    {:status "error" :error (t "The login was interrupted. Please start again.")}))

(defn describe [{:keys [level days_left expires_on renew_command]}]
  (case level
    :not_connected (str "Garmin isn't connected yet. Log in: " renew_command)
    :unknown "No Garmin login date recorded."
    :expired (str "Garmin login has probably expired (est. " expires_on "). Renew: " renew_command)
    (str "Garmin login: " days_left " days left (est. expires " expires_on ")"
         (when (#{:warn :urgent} level) (str ". Renew soon: " renew_command)))))

(defn -main
  "clojure -M -m coach.auth [status | login | set YYYY-MM-DD]"
  [& [cmd date]]
  (let [ds (doto (db/datasource) db/migrate!)]
    (case (or cmd "status")
      "status" (println (describe (status ds)))
      "login"  (login! ds)
      "set"    (do (record-login! ds (str (LocalDate/parse date)))
                   (println (describe (status ds)))))
    (shutdown-agents)))
