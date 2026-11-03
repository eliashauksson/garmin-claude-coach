(ns coach.coros-auth
  "The Coros login: OAuth 2.0 against COROS's official MCP server.

  The app registers itself with Coros (dynamic client registration, no
  secret), then the athlete signs in on Coros's own page in the browser
  (authorization code with PKCE) and Coros sends them back to
  /api/coros/callback. The tokens are kept in ~/.config/coach/coros.json,
  readable only by this user, next to the Claude key; the access token is
  renewed with the refresh token as it runs out."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coach.i18n :refer [t]])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Duration Instant)
           (java.util Base64)))

(def mcp-url
  "COROS's official MCP server (https://support.coros.com). It names its
  authorization server itself (see discover!)."
  "https://mcp.coros.com/mcp")

(def scope "openid mcp.tools offline_access")

(def token-file (str (System/getProperty "user.home") "/.config/coach/coros.json"))
(def ^:dynamic *token-file* token-file)

;; ---------------------------------------------------------------------------
;; HTTP

(def ^:private ^HttpClient http
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) (.build)))

(defn- enc [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn form-body
  "Pure: a map as an application/x-www-form-urlencoded string (nil values left out)."
  [m]
  (str/join "&" (for [[k v] m :when (some? v)] (str (enc (name k)) "=" (enc v)))))

(defn- send-req [^HttpRequest req what]
  (let [resp (try (.send http req (HttpResponse$BodyHandlers/ofString))
                  (catch java.io.IOException e
                    (throw (ex-info (t "Coros can't be reached: {0}" (ex-message e))
                                    {:kind :unreachable :step what} e))))
        body (try (json/read-str (.body resp) :key-fn keyword) (catch Exception _ nil))]
    {:status (.statusCode resp) :body body}))

(defn- get-json [url what]
  (let [{:keys [status body]} (send-req (-> (HttpRequest/newBuilder (URI/create url))
                                            (.timeout (Duration/ofSeconds 30)) (.GET) (.build))
                                        what)]
    (when-not (= 200 status)
      (throw (ex-info (t "Coros answered HTTP {0} ({1})." status what) {:kind :coros :status status})))
    body))

(defn- post [url content-type ^String payload what]
  (send-req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 30))
                (.header "Content-Type" content-type)
                (.header "Accept" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString payload))
                (.build))
            what))

;; ---------------------------------------------------------------------------
;; Discovery (RFC 9728 → RFC 8414): which resource and authorization server.

(defonce ^:private metadata (atom nil))

(defn well-known-url
  "Pure: the protected-resource metadata URL for an MCP URL."
  [url]
  (let [u (URI/create url)]
    (str (.getScheme u) "://" (.getAuthority u) "/.well-known/oauth-protected-resource" (.getPath u))))

(defn discover!
  "{:resource :issuer :authorization_endpoint :token_endpoint :registration_endpoint …}"
  []
  (or @metadata
      (let [pr  (get-json (well-known-url mcp-url) "resource metadata")
            as  (first (:authorization_servers pr))
            asm (get-json (str (str/replace as #"/$" "") "/.well-known/oauth-authorization-server")
                          "authorization server metadata")]
        (reset! metadata (assoc asm :resource (:resource pr))))))

;; ---------------------------------------------------------------------------
;; Token file

(defn- posix? []
  (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix"))

(defn read-tokens []
  (let [f (io/file *token-file*)]
    (when (.isFile f)
      (try (json/read-str (slurp f) :key-fn keyword) (catch Exception _ nil)))))

(defn- write-tokens!
  "Writes the token file, readable only by this user."
  [m]
  (let [f     (io/file *token-file*)
        dir   (.toPath (.getParentFile (.getAbsoluteFile f)))
        path  (.toPath f)
        perms #(java.nio.file.attribute.PosixFilePermissions/fromString %)]
    (java.nio.file.Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
    (when (and (posix?) (not (.exists f)))
      (java.nio.file.Files/createFile path (into-array [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute (perms "rw-------"))])))
    (spit f (json/write-str m))
    (when (posix?) (java.nio.file.Files/setPosixFilePermissions path (perms "rw-------")))
    m))

(defn connected?
  "Whether Coros is connected: a login is stored. (Whether it still works
  shows when a sync runs.)"
  []
  (boolean (:refresh_token (read-tokens))))

;; ---------------------------------------------------------------------------
;; Login

(defn- random-b64 [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn code-challenge
  "Pure: the PKCE S256 challenge for `verifier`."
  [^String verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier StandardCharsets/US_ASCII))))

(defn- register!
  "Registers the app with Coros for `redirect-uri`. Returns the client id."
  [redirect-uri]
  (let [{:keys [registration_endpoint]} (discover!)
        {:keys [status body]} (post registration_endpoint "application/json"
                                    (json/write-str {:client_name "Garmin Coach"
                                                     :redirect_uris [redirect-uri]
                                                     :grant_types ["authorization_code" "refresh_token"]
                                                     :response_types ["code"]
                                                     :token_endpoint_auth_method "none"
                                                     :scope scope})
                                    "registration")]
    (or (when (< status 300) (:client_id body))
        (throw (ex-info (t "Coros didn't accept the app: {0}" (or (:error_description body) (str "HTTP " status)))
                        {:kind :coros :status status})))))

(defn- client-id!
  "The client id registered for `redirect-uri` (a new registration when the
  app runs on another port than last time)."
  [redirect-uri]
  (let [{:keys [client_id redirect_uri]} (read-tokens)]
    (if (and client_id (= redirect_uri redirect-uri))
      client_id
      (register! redirect-uri))))

(defonce ^:private pending (atom nil))
(defonce ^:private last-error (atom nil))

(defn authorize-url
  "Pure: Coros's sign-in page URL for one login attempt."
  [{:keys [authorization_endpoint resource]} {:keys [client-id redirect-uri state verifier]}]
  (str authorization_endpoint "?"
       (form-body {:response_type "code" :client_id client-id :redirect_uri redirect-uri
                   :scope scope :state state :resource resource
                   :code_challenge (code-challenge verifier) :code_challenge_method "S256"})))

(defn start-login!
  "Begins a login: returns {:url …}, Coros's sign-in page. Coros sends the
  browser back to `redirect-uri` (finish-login!)."
  [redirect-uri]
  (let [md  (discover!)
        cid (client-id! redirect-uri)
        p   {:client-id cid :redirect-uri redirect-uri :state (random-b64 24) :verifier (random-b64 48)}]
    (reset! pending p)
    (reset! last-error nil)
    {:url (authorize-url md p)}))

(defn- expires-at [{:keys [expires_in]}]
  (str (.plusSeconds (Instant/now) (long (or expires_in 3600)))))

(defn- token-request! [params what]
  (let [{:keys [token_endpoint]} (discover!)
        {:keys [status body]} (post token_endpoint "application/x-www-form-urlencoded" (form-body params) what)]
    (if (and (= 200 status) (:access_token body))
      body
      (throw (ex-info (t "Coros login failed: {0}" (or (:error_description body) (:error body) (str "HTTP " status)))
                      {:kind (if (#{400 401} status) :auth :coros) :status status :error (:error body)})))))

(defn finish-login!
  "Exchanges the code Coros sent back for tokens and stores them. Throws
  (and remembers the reason for Settings) if anything is off."
  [{:keys [state code error error_description]}]
  (try
    (let [p @pending]
      (cond
        error (throw (ex-info (t "Coros login failed: {0}" (or error_description error)) {:kind :auth}))
        (or (nil? p) (not= state (:state p)))
        (throw (ex-info (t "The Coros login was interrupted. Please start again.") {:kind :auth})))
      (let [{:keys [resource]} (discover!)
            tok (token-request! {:grant_type "authorization_code" :code code :client_id (:client-id p)
                                 :redirect_uri (:redirect-uri p) :code_verifier (:verifier p)
                                 :resource resource}
                                "login")]
        (reset! pending nil)
        (write-tokens! {:client_id     (:client-id p)
                        :redirect_uri  (:redirect-uri p)
                        :access_token  (:access_token tok)
                        :refresh_token (:refresh_token tok)
                        :expires_at    (expires-at tok)
                        :connected_at  (str (Instant/now))})
        {:ok true}))
    (catch clojure.lang.ExceptionInfo e
      (reset! last-error (ex-message e))
      (throw e))))

(defn expiring?
  "Pure: whether a token that expires at `expires-at` (ISO instant) needs
  renewing at `now`, a minute ahead."
  [expires-at ^Instant now]
  (or (nil? expires-at) (.isAfter (.plusSeconds now 60) (Instant/parse expires-at))))

(defn access-token!
  "A valid access token, renewed first when it's about to run out."
  []
  (let [{:keys [access_token refresh_token expires_at client_id] :as toks} (read-tokens)]
    (cond
      (nil? refresh_token)
      (throw (ex-info (t "Coros isn't connected. Connect it in Settings.") {:kind :auth}))

      (not (expiring? expires_at (Instant/now)))
      access_token

      :else
      (let [tok (token-request! {:grant_type "refresh_token" :refresh_token refresh_token :client_id client_id
                                 :resource (:resource (discover!))}
                                "renewal")]
        (:access_token
         (write-tokens! (assoc toks
                               :access_token  (:access_token tok)
                               :refresh_token (or (:refresh_token tok) refresh_token)
                               :expires_at    (expires-at tok))))))))

(defn disconnect!
  "Forgets the login (the synced rides stay)."
  []
  (io/delete-file (io/file *token-file*) true)
  (reset! pending nil)
  {:connected false})

(defn status
  "For Settings: {:connected :connected_at :login_error}."
  []
  (let [toks (read-tokens)]
    {:connected    (boolean (:refresh_token toks))
     :connected_at (:connected_at toks)
     :login_error  @last-error}))
