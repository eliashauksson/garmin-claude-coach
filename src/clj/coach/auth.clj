(ns coach.auth
  "Countdown to the Garmin login expiring.

  The server renews its short-lived access token by itself; what eventually
  runs out is the refresh token. Garmin doesn't expose its expiry, and the
  garmin_mcp docs give ~6 months, so we record the login date ourselves and
  count down from that. It's an estimate: a failing sync is the real signal."
  (:require [coach.db :as db]
            [coach.garmin-mcp :as gm])
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

(defn status
  "Countdown for the stored login date, or {:level :unknown} if none recorded."
  [ds]
  (if-let [d (db/get-state ds :garmin_login_on)]
    (countdown d (str (LocalDate/now)))
    {:level :unknown :renew_command renew-command}))

(defn record-login! [ds date]
  (db/set-state! ds :garmin_login_on date))

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
          (println "Login renewed. Countdown restarted.")
          true)
      (do (println "Login failed (exit" exit "); countdown unchanged.")
          false))))

(defn describe [{:keys [level days_left expires_on renew_command]}]
  (case level
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
