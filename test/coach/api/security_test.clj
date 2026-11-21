(ns coach.api.security-test
  (:require [clojure.test :refer [deftest is testing]]
            [coach.api.security :as security]
            [coach.paths :as paths]))

(deftest foreign-request-test
  (let [req (fn [method headers] {:request-method method :uri "/api/quit" :headers headers})
        foreign? #(security/foreign-request? (req %1 %2))]
    (testing "this page, the installer and curl"
      (is (not (foreign? :post
                         {"host" "127.0.0.1:8080" "origin" "http://127.0.0.1:8080" "sec-fetch-site" "same-origin"})))
      (is (not (foreign? :post {"host" "localhost:8080" "origin" "http://localhost:8080"})))
      (is (not (foreign? :get {"host" "[::1]:8080"})))
      (is (not (foreign? :post {"host" "127.0.0.1:8080"})) "curl: no Origin")
      (is (not (foreign? :get {"host" "127.0.0.1:8080" "sec-fetch-site" "none"})) "typed in the address bar")
      (is (not (foreign? :get {})) "no headers at all"))
    (testing "another website"
      (is (foreign? :post {"host" "127.0.0.1:8080" "origin" "https://evil.example"}) "older browser, no Sec-Fetch-Site")
      (is (foreign? :get {"host" "127.0.0.1:8080" "sec-fetch-site" "cross-site"})
          "e.g. an <img> of /api/backup/download")
      (is (foreign? :post {"host" "127.0.0.1:8080" "origin" "http://127.0.0.1.evil.example"}))
      (is (foreign? :get {"host" "evil.example:8080"}) "DNS rebinding: the Host gives it away")
      (is (foreign? :get {"host" "127.0.0.1.evil.example"})))
    (testing "Coros's sign-in page sending the browser back, and only that"
      (let [cb (fn [method headers]
                 (security/foreign-request? {:request-method method :uri "/api/coros/callback" :headers headers}))]
        (is (not (cb :get {"host" "127.0.0.1:8090" "sec-fetch-site" "cross-site"})))
        (is (cb :post {"host" "127.0.0.1:8090" "sec-fetch-site" "cross-site"}))
        (is (cb :get {"host" "evil.example:8090" "sec-fetch-site" "cross-site"}) "still only on this computer")))))

(deftest page-navigation-test
  (with-redefs [paths/allowed-hosts (constantly #{"coach.tail1.ts.net"})]
    (let [nav {"host" "coach.tail1.ts.net" "sec-fetch-site" "cross-site"
               "sec-fetch-mode" "navigate" "sec-fetch-dest" "document"}
          foreign? (fn [method uri headers]
                     (security/foreign-request? {:request-method method :uri uri :headers headers}))]
      (testing "opening the page from a home-screen shortcut or a link elsewhere"
        (is (not (foreign? :get "/" nav)))
        (is (not (foreign? :get "/" (assoc nav "sec-fetch-site" "same-site")))))
      (testing "but nothing else from another site"
        (is (foreign? :get "/api/backup/download" nav) "the API stays same-origin only")
        (is (foreign? :post "/" nav))
        (is (foreign? :get "/" (assoc nav "sec-fetch-dest" "iframe")) "no framing")
        (is (foreign? :get "/" (dissoc nav "sec-fetch-mode")) "e.g. an <img>")
        (is (foreign? :get "/" (assoc nav "host" "evil.example")) "the Host check still applies")))))

(deftest allowed-hosts-test
  (testing "no allowed hosts (the local app): only this computer"
    (with-redefs [paths/allowed-hosts (constantly #{})]
      (is (security/foreign-request? {:request-method :get :uri "/" :headers {"host" "coach.tail1.ts.net"}}))))
  (with-redefs [paths/allowed-hosts (constantly #{"coach.tail1.ts.net"})]
    (let [foreign? (fn [method headers]
                     (security/foreign-request? {:request-method method :uri "/api/quit" :headers headers}))]
      (testing "the page opened through an allowed host (tailscale serve)"
        (is (not (foreign? :get {"host" "coach.tail1.ts.net" "sec-fetch-site" "same-origin"})))
        (is (not (foreign? :post
                           {"host" "COACH.tail1.ts.net" "origin" "https://coach.tail1.ts.net"
                            "sec-fetch-site" "same-origin"})))
        (is (not (foreign? :post {"host" "127.0.0.1:8080"})) "this computer still works"))
      (testing "everything else is still refused"
        (is (foreign? :get {"host" "evil.example"}))
        (is (foreign? :get {"host" "coach.tail1.ts.net.evil.example"}))
        (is (foreign? :get {"host" "coach.tail1.ts.net" "sec-fetch-site" "cross-site"}))
        (is (foreign? :post {"host" "coach.tail1.ts.net" "origin" "https://evil.example"}))))))

(deftest coros-callback-url-test
  (with-redefs [paths/allowed-hosts (constantly #{"coach.tail1.ts.net"})]
    (let [url (fn [headers] (security/coros-callback-url {:server-port 8080 :headers headers}))]
      (is (= "http://127.0.0.1:8080/api/coros/callback" (url {"host" "127.0.0.1:8080"})))
      (is (= "http://127.0.0.1:8080/api/coros/callback" (url {"host" "evil.example"})) "not an allowed host")
      (is (= "http://127.0.0.1:8080/api/coros/callback" (url {})))
      (is (= "https://coach.tail1.ts.net/api/coros/callback" (url {"host" "coach.tail1.ts.net"}))
          "tailscale serve: HTTPS, no port")
      (is (= "http://coach.tail1.ts.net:8080/api/coros/callback" (url {"host" "coach.tail1.ts.net:8080"})))
      (is (= "https://coach.tail1.ts.net:8443/api/coros/callback"
             (url {"host" "coach.tail1.ts.net:8443" "x-forwarded-proto" "https"}))))))
