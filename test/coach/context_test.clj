(ns coach.context-test
  "One app serving several athletes: nothing of one athlete shows in another's."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [coach.api :as api]
            [coach.context :as context]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.paths :as paths]
            [coach.token :as token]))

(defn- temp-ds []
  (let [f (java.io.File/createTempFile "coach-ctx" ".db")]
    (.deleteOnExit f)
    (doto (db/datasource (str f)) db/migrate!)))

(defn- get-json [app uri]
  (let [r (app {:request-method :get :uri uri :headers {"host" "127.0.0.1:8080"}})]
    [(:status r) (json/read-str (:body r) :key-fn keyword)]))

(deftest per-athlete-atom-test
  (let [a (context/per-athlete-atom {:n 0})]
    (context/with-athlete nil #(swap! a update :n + 5))
    (context/with-athlete "nadine" #(do (swap! a update :n inc)
                                        (is (= [{:n 1} {:n 2}] (swap-vals! a update :n inc)))))
    (is (= {:n 5} (context/with-athlete nil #(deref a))))
    (is (= {:n 2} (context/with-athlete "nadine" #(deref a))))
    (is (= {:n 0} (context/with-athlete "bjarki" #(deref a))) "a new athlete starts from the initial value")
    (testing "the function carries its athlete to another thread"
      (let [f (context/with-athlete "nadine" #(context/athlete-fn (fn [] (:n @a))))]
        (is (= 2 @(future (f))))
        (is (nil? @(future (context/athlete))) "unbound: the first athlete")))))

(deftest paths-per-athlete-test
  (context/with-athlete "nadine"
    #(do (is (= "nadine" (paths/instance)))
         (is (= "config-nadine.edn" (.getName (paths/config-file))))
         (is (= "config.edn" (.getName (context/with-athlete nil paths/config-file))))
         (is (not= (context/with-athlete nil paths/garmin-token-dir) (paths/garmin-token-dir)))))
  (context/with-athlete nil #(is (nil? (paths/instance)))))

(deftest two-athletes-one-process-test
  (let [ds-a (temp-ds)
        ds-b (temp-ds)
        app-a (context/with-athlete nil #(api/app ds-a))
        app-b (context/with-athlete "nadine" #(api/app ds-b))]
    (db/upsert-activities! ds-a [{:id 1 :date "2026-10-13" :start_time_local "2026-10-13T05:01:57"
                                  :sport "running" :name "Run" :moving_s 1800 :raw {}}])
    (db/upsert-activities! ds-b [{:id 2 :date "2026-10-14" :start_time_local "2026-10-14T05:01:57"
                                  :sport "cycling" :name "Ride" :moving_s 3600 :raw {}}])
    (testing "each handler answers for its own athlete, whoever calls it"
      (is (= [1] (map :id (second (get-json app-a "/api/activities")))))
      (is (= [2] (map :id (second (get-json app-b "/api/activities")))))
      (is (= [1] (map :id (second (context/with-athlete "nadine" #(get-json app-a "/api/activities")))))
          "even called while another athlete is current"))
    (testing "state is kept apart"
      (is (nil? (:instance (second (get-json app-a "/api/app")))))
      (is (= "nadine" (:instance (second (get-json app-b "/api/app")))))
      (is (false? (:admin (second (get-json app-b "/api/app")))))
      (is (true? (:admin (second (get-json app-a "/api/app"))))))
    (testing "each athlete has their own language"
      (context/with-athlete nil #(reset! i18n/lang "de"))
      (context/with-athlete "nadine" #(reset! i18n/lang "fr"))
      (is (= "de" (:lang (second (get-json app-a "/api/lang")))))
      (is (= "fr" (:lang (second (get-json app-b "/api/lang")))))
      (context/with-athlete nil #(reset! i18n/lang "en"))
      (context/with-athlete "nadine" #(reset! i18n/lang "en")))))

(deftest athletes-take-turns-test
  (let [log (atom [])
        in  (promise)
        a   (future (paths/one-at-a-time "sync-test.lock" #(swap! log conj :a-waits)
                                         (fn [] (swap! log conj :a-start) @in (swap! log conj :a-end))))
        _   (Thread/sleep 300)
        b   (future (paths/one-at-a-time "sync-test.lock" #(swap! log conj :b-waits)
                                         (fn [] (swap! log conj :b-start))))]
    (Thread/sleep 300)
    (deliver in true)
    @a @b
    (is (= [:a-start :b-waits :a-end :b-start] @log) "the second athlete waits for the first, in this process too")
    (.delete (paths/app-file "sync-test.lock"))))

(deftest concurrent-athletes-test
  (let [ds-a (temp-ds)
        ds-b (temp-ds)
        app-a (context/with-athlete nil #(api/app ds-a))
        app-b (context/with-athlete "nadine" #(api/app ds-b))]
    (db/upsert-activities! ds-a [{:id 1 :date "2026-10-13" :start_time_local "2026-10-13T05:01:57"
                                  :sport "running" :name "Run" :moving_s 1800 :raw {}}])
    (db/upsert-activities! ds-b [{:id 2 :date "2026-10-14" :start_time_local "2026-10-14T05:01:57"
                                  :sport "cycling" :name "Ride" :moving_s 3600 :raw {}}])
    (context/with-athlete nil #(reset! i18n/lang "de"))
    (context/with-athlete "nadine" #(reset! i18n/lang "fr"))
    (let [bad  (atom [])
          work (fn [app id inst lang]
                 (future
                   (dotimes [_ 300]
                     (let [[_ acts] (get-json app "/api/activities")
                           [_ a]    (get-json app "/api/app")
                           [_ l]    (get-json app "/api/lang")]
                       (when-not (and (= [id] (map :id acts)) (= inst (:instance a)) (= lang (:lang l)))
                         (swap! bad conj [id inst lang (map :id acts) (:instance a) (:lang l)]))))))
          fs   (doall (concat (repeatedly 4 #(work app-a 1 nil "de"))
                              (repeatedly 4 #(work app-b 2 "nadine" "fr"))))]
      (run! deref fs)
      (is (empty? @bad) (str "mixed up: " (take 3 @bad))))
    (context/with-athlete nil #(reset! i18n/lang "en"))
    (context/with-athlete "nadine" #(reset! i18n/lang "en"))))

(deftest tokens-per-athlete-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "coach-ctx-tok" (make-array java.nio.file.attribute.FileAttribute 0)))
        ;; the token file is named per athlete: take them one at a time like a request would
        tok (fn [inst] (context/with-athlete inst
                         #(binding [token/*token-file* (str dir "/" (paths/per-instance "dashboard-token"))]
                            (token/ensure!))))]
    (is (not= (tok nil) (tok "nadine")) "each athlete has a token of their own")
    (is (= (tok nil) (tok nil)) "and keeps it")
    (is (not (context/with-athlete "nadine" #(binding [token/*token-file* (str dir "/dashboard-token")]
                                               (token/valid? (tok nil)))))
        "one athlete's token is not accepted for another")))

(deftest log-lines-say-whose-they-are-test
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. (context/prefixing-writer sw) true)
        say (fn [athlete & msgs] (context/with-athlete athlete #(binding [*out* pw] (apply println msgs))))]
    (say nil "first athlete")
    (say "nadine" "sync done")
    (say "nadine" "two" "words")
    (context/with-athlete "bjarki"
      #(binding [*out* pw] (print "in ") (print "parts") (println)))
    (binding [*out* pw] (.write pw "multi\nline\n"))
    (context/with-athlete "nadine" #(do (.write pw (char-array "chars\n")) (.write pw (int \!)) (.write pw "\n")))
    (is (= (str "first athlete\n"
                "[nadine] sync done\n"
                "[nadine] two words\n"
                "[bjarki] in parts\n"
                "multi\nline\n"
                "[nadine] chars\n"
                "[nadine] !\n")
           (str sw)))))
