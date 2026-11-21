(ns coach.api.security
  "Who may call the API. The server listens on 127.0.0.1 only, but a website open in
  the browser can still send requests there; this keeps those out (`wrap-local-only`),
  and guards the routes a mere local process must not reach (the dashboard token,
  the main athlete only)."
  (:require [clojure.string :as str]
            [coach.api.http :refer [json-response]]
            [coach.i18n :as i18n]
            [coach.paths :as paths]
            [coach.token :as token]))

;; A loopback token for the two routes a mere local process must not reach:
;; the bulk data export and the self-update. wrap-local-only already stops
;; other *websites*; the token additionally stops header-less local clients
;; (curl, a sandboxed process) that can't read this user's files. The page
;; gets the token from GET /api/token, which answers only a same-origin fetch.

(defn request-token [req]
  (or (get-in req [:headers "x-coach-token"])
      (get (:query-params req) "token")))

(defn same-origin-fetch?
  "A fetch the dashboard page itself made. Browsers set Sec-Fetch-Site on
  every request; curl and other scripts don't, so they can't read the token."
  [req]
  (= "same-origin" (get-in req [:headers "sec-fetch-site"])))

(defn token-guard
  "Wraps a handler so it answers only when the request carries the token."
  [handler]
  (fn [req]
    (if (token/valid? (request-token req))
      (handler req)
      (json-response 403 {:error (i18n/t "This action needs the dashboard token. Reload the page and try again.")}))))

(defn admin-only
  "Wraps a handler so it answers only the main athlete; the extra athletes on a
  server get a 403."
  [handler]
  (fn [req]
    (if (paths/instance)
      (json-response 403 {:error (i18n/t "Only {0} can do this." (paths/main-name))})
      (handler req))))

(def ^:private loopback-hosts #{"127.0.0.1" "localhost" "[::1]"})

(defn local-hosts
  "This computer's names, plus the host names allowed to open the dashboard
  on a server (coach.paths/allowed-hosts, e.g. a Tailscale address)."
  []
  (into loopback-hosts (paths/allowed-hosts)))

(defn host-name
  "\"127.0.0.1\" from a Host header like \"127.0.0.1:8080\"; also for [::1]:8080."
  [h]
  (when h (second (re-matches #"(?i)(\[[^\]]*\]|[^:]*)(?::\d+)?" (str/trim h)))))

(defn local-origin? [origin]
  (boolean (some->> origin (re-matches #"(?i)https?://([^/]+)") second host-name str/lower-case ((local-hosts)))))

(defn foreign-request?
  "Pure: whether a request comes from another website rather than this page.
  The server listens on 127.0.0.1 only (on a server, a proxy such as
  `tailscale serve` passes the allowed host names on), but any website open in the browser
  can still send requests there, or rename itself to 127.0.0.1 (DNS
  rebinding) to read the answers. So:
  - the Host must be this computer or an allowed host (a rebound site still
    sends its own name);
  - the browser's Sec-Fetch-Site must not say another site sent it;
  - a changing request with an Origin must come from this computer.
  Requests without those headers (curl, the installer) are fine.
  Two exceptions to the second rule:
  - opening the page itself (a top-level page load, not /api): a phone's
    home-screen shortcut or a link elsewhere sends \"cross-site\". The other
    site can't read the page or frame it, and the page's own requests are
    same-origin again;
  - Coros's sign-in page sending the browser back to /api/coros/callback.
    That GET only completes a login this app started, with the one-time state
    it made up (coach.coros-auth), and answers with nothing but a redirect."
  [{:keys [headers request-method uri]}]
  (let [host   (get headers "host")
        site   (get headers "sec-fetch-site")
        origin (get headers "origin")
        get?   (#{:get :head} request-method)
        page?  (and get?
                    (= "navigate" (get headers "sec-fetch-mode"))
                    (= "document" (get headers "sec-fetch-dest"))
                    (not (str/starts-with? (or uri "") "/api/")))]
    (boolean
     (or (and host (not ((local-hosts) (some-> (host-name host) str/lower-case))))
         (and (#{"cross-site" "same-site"} site)
              (not page?)
              (not (and (= :get request-method) (= "/api/coros/callback" uri))))
         (and origin (not (#{:get :head} request-method)) (not (local-origin? origin)))))))

(defn wrap-local-only
  "Refuses requests from other websites (see foreign-request?)."
  [handler]
  (fn [req]
    (if (foreign-request? req)
      (do (println "Refused a request from another website:" (:request-method req) (:uri req)
                   (get-in req [:headers "origin"]) (get-in req [:headers "host"])
                   (get-in req [:headers "sec-fetch-site"]))
          (json-response 403 {:error "Requests from other websites are not allowed."}))
      (handler req))))

(defn coros-callback-url
  "Where Coros sends the browser back to after its sign-in: the address this
  page was opened at when that is an allowed host (a phone on a server's
  Tailscale address), else 127.0.0.1. Behind `tailscale serve` the Host has
  no port and the page is HTTPS; a proxy's X-Forwarded-Proto wins."
  [{:keys [headers server-port]}]
  (let [host (some-> (get headers "host") str/trim str/lower-case)
        name (host-name host)]
    (if (and name ((paths/allowed-hosts) name))
      (let [proto (or (some-> (get headers "x-forwarded-proto") (str/split #",") first str/trim not-empty)
                      (if (= name host) "https" "http"))]
        (str proto "://" host "/api/coros/callback"))
      (str "http://127.0.0.1:" server-port "/api/coros/callback"))))
