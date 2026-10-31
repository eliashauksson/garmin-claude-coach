(ns coach.views.today
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.ui :as ui]
            [coach.views.plan :as plan]))

;; ---------------------------------------------------------------------------
;; Helpers

(def long-days ["Sunday" "Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday"])
(def long-months ["January" "February" "March" "April" "May" "June" "July" "August"
                  "September" "October" "November" "December"])

(defn- header-date [iso]
  (when iso (let [d (ui/parse-date iso)]
              (str (long-days (.getDay d)) ", " (.getDate d) " " (long-months (.getMonth d))))))

(defn- greeting []
  (let [h (.getHours (js/Date.))]
    (cond (< h 12) "Good morning" (< h 18) "Good afternoon" :else "Good evening")))

(defn- clock [minute]
  (let [m (js/Math.round minute)]
    (str (.padStart (str (quot m 60)) 2 "0") ":" (.padStart (str (mod m 60)) 2 "0"))))

(defn- sentence [s] (some-> s str/lower-case str/capitalize))

(def day-axis [:div.ax [:span "00:00"] [:span "06:00"] [:span "12:00"] [:span "18:00"] [:span "24:00"]])

(defn- no-data [] [:div.empty "No data yet — run Sync."])

(defn- day-series-option
  "A whole-day (0–1440 min) chart with hidden axes."
  [{:keys [series y-min y-max tip]}]
  (-> (c/base {:x-type "value"
               :tooltip-fmt (fn [params]
                              (let [p (first params) [m v] (js->clj (.-value p))]
                                (c/tooltip-rows (clock m) [{:label tip :value (str v)}])))})
      (assoc :grid {:left 0 :right 0 :top 4 :bottom 0})
      (assoc-in [:xAxis :min] 0) (assoc-in [:xAxis :max] 1440)
      (assoc-in [:xAxis :show] false)
      (assoc :yAxis {:type "value" :show false :min y-min :max y-max})
      (assoc :series series)))

;; ---------------------------------------------------------------------------
;; Header, auth

(defn- auth-notice
  "Shown only in the final week (urgent) or once expired."
  [{:keys [level days_left expires_on renew_command sync_error]}]
  (when (#{"urgent" "expired"} level)
    [:div.banner {:class "critical"}
     [:span.icon "!"]
     [:div
      [:div [:b (if (= level "expired")
                  "Garmin login has expired"
                  (str "Garmin login expires in about " days_left " days"))]
       (when expires_on [:span.sub (str " · estimated " (ui/date-short expires_on))])]
      (when sync_error [:div.sub (str "Last sync failed: " sync_error)])
      [:div.sub "Renew in a terminal: " [:code renew_command]]]]))

(defn- auth-pill
  "Hidden until the final week of the login."
  [{:keys [level days_left]}]
  (case level
    "urgent"  (ui/status-pill :critical (str "Garmin login · " days_left " days left"))
    "expired" (ui/status-pill :critical "Garmin login expired")
    "unknown" (ui/status-pill :warn "Garmin login not recorded")
    nil))

(defn sync-button [{:keys [running last_result]}]
  [:span {:style {:display "inline-flex" :gap "10px" :align-items "center"}}
   (when (and (not running) (false? (:ok last_result))) [:span.error "Sync failed"])
   [:button.btn-ink {:disabled running :on {:click [[:sync]]}}
    (if running
      (list [:span.spinner {:aria-hidden "true"}] "Syncing…")
      "Sync now")]])

;; ---------------------------------------------------------------------------
;; Row 1

(defn- readiness-card [w]
  (let [score (:readiness w)]
    (ui/card {:tint "green" :flex "1 1 260px"}
             (ui/label-row {:icon :bolt :accent "--green" :label "Training readiness"})
             [:div.big.xl (or score "—")]
             [:div.sub {:style {:color "var(--green-text)" :font-weight 600}}
              (if (:readiness_level w) (str (sentence (:readiness_level w)) " · this morning") "No reading this morning")]
             [:div.track [:div {:style {:width (str (or score 0) "%") :background "var(--green)"}}]])))

(defn- body-battery-card [w day intraday]
  (let [bb (:body_battery intraday)]
    (ui/chart-card
     {:title "Body battery" :icon :battery :accent "--blue" :tint "blue" :flex "1 1 260px"
      :header [:div.baseline [:span.big (or (:bb_current day) "—")]
               [:span.sub (str "high " (or (:body_battery_high w) "—"))]]
      :chart (if (seq bb)
               (c/echart {:key "bb" :class "h90"
                          :option-fn #(day-series-option
                                       {:y-min 0 :y-max 100 :tip "body battery"
                                        :series [(-> (c/line "Body battery" "--blue" bb :lineStyle {:width 2.5})
                                                     (c/area-wash 0.25))]})})
               (no-data))
      :footer (ui/pills (when (:bb_charged day) (ui/pill (str "+" (:bb_charged day) " charged")))
                        (when (:bb_drained day) (ui/pill (str "−" (:bb_drained day) " drained"))))})))

(defn- sleep-card [w day]
  (let [stages (filter (fn [[_ s _]] (and s (pos? s)))
                       [["Deep" (:deep_sleep_s w) "--deep"] ["Light" (:sleep_light_s day) "--purple-2"]
                        ["REM" (:rem_sleep_s w) "--pink"] ["Awake" (:sleep_awake_s day) "--amber"]])]
    (ui/card {:tint "purple" :flex "1 1 260px"}
             (ui/label-row {:icon :moon :accent "--purple" :label "Sleep"})
             [:div.baseline [:span.big (or (:sleep_score w) "—")]
              (when (:sleep_s w) [:span.sub (str (ui/hm (:sleep_s w)) " asleep")])]
             (if (seq stages)
               [:div.stages {:role "img"
                             :aria-label (str/join ", " (for [[l s] stages] (str l " " (ui/hm s))))}
                (for [[l s color] stages]
                  [:div {:replicant/key l :style {:flex s :background (str "var(" color ")")}}])]
               (no-data))
             (into [:div.pills] (for [[l s] stages] (ui/pill (str l " " (ui/hm s))))))))

;; ---------------------------------------------------------------------------
;; Row 2: heart rate

(defn- heart-rate-card [w day intraday]
  (let [hr (:hr intraday)]
    (ui/chart-card
     {:title "Heart rate" :icon :heart :accent "--red" :tint "red"
      :header [:div.baseline
               [:span.big (or (:rhr w) "—") [:small "bpm resting"]]
               (ui/pills (when (:hr_min day) (ui/pill (str "Low " (:hr_min day))))
                         (when (:hr_max day) (ui/pill (str "High " (:hr_max day))))
                         (when (:rhr_7d day) (ui/pill (str "7-day resting " (:rhr_7d day)))))]
      :chart (if (seq hr)
               [:div
                (c/echart {:key "hr" :class "h170"
                           :option-fn #(day-series-option
                                        {:tip "bpm"
                                         :series [(c/area-wash (c/line "Heart rate" "--red" hr) 0.18)]})})
                day-axis]
               (no-data))})))

;; ---------------------------------------------------------------------------
;; Row 3: stress, steps

(def stress-buckets
  [["Rest" #(<= 0 % 25)] ["Low" #(<= 26 % 50)] ["Medium" #(<= 51 % 75)] ["High" #(<= 76 % 100)]
   ["Unmeasured" neg?]])

(defn- stress-card [w day intraday]
  (let [st    (:stress intraday)
        n     (count st)
        rest  (count (filter #(<= 0 (second %) 25) st))]
    (ui/chart-card
     {:title "Stress" :icon :pulse :accent "--amber" :tint "amber" :flex "2 1 460px"
      :header [:div.baseline
               [:span.big (or (:avg_stress w) "—") [:small "average"]]
               (ui/pills (when (:stress_max day) (ui/pill (str "Peak " (:stress_max day))))
                         (when (pos? n) (ui/pill (str (ui/hm (* rest 180)) " at rest"))))]
      :chart (if (seq st)
               [:div
                (c/echart {:key "stress" :class "h120"
                           :option-fn #(day-series-option
                                        {:y-min 0 :y-max 100 :tip "stress"
                                         :series [(c/bars "Rest" "--blue" (filterv (fn [[_ v]] (<= 0 v 25)) st)
                                                          :barWidth 2 :barMaxWidth nil :itemStyle {:color (c/css-var "--blue") :borderRadius 1})
                                                  (c/bars "Stress" "--amber" (filterv (fn [[_ v]] (> v 25)) st)
                                                          :barWidth 2 :barMaxWidth nil :barGap "-100%"
                                                          :itemStyle {:color (c/css-var "--amber") :borderRadius 1})]})})
                day-axis]
               (no-data))
      :footer (when (pos? n)
                (into [:div.pills]
                      (for [[label pred] stress-buckets]
                        (ui/pill (when (= label "Rest") "--blue")
                                 (str label " " (js/Math.round (* 100 (/ (count (filter #(pred (second %)) st)) n))) "%")))))})))

(defn- steps-card [w day intraday]
  (let [steps (:steps intraday)
        goal  (:step_goal day)
        total (:steps w)]
    (ui/chart-card
     {:title "Steps" :icon :footprints :accent "--green" :tint "green-2" :flex "1 1 260px"
      :header [:div
               [:div.big (or (ui/thousands total) "—")]
               [:div.sub {:style {:margin-top "8px"}}
                (str/join " · " (remove nil? [(when (and total goal (pos? goal))
                                                (str (js/Math.round (* 100 (/ total goal))) "% of " (ui/thousands goal)))
                                              (some-> (:distance_m day) ui/km (str " km"))
                                              (some-> (:floors day) js/Math.round (str " floors"))]))]]
      :chart (if (seq steps)
               [:div
                (c/echart {:key "steps" :class "h90"
                           :option-fn (fn []
                                        (-> (c/base {:x-type "category"
                                                     :tooltip-fmt (fn [params]
                                                                    (let [p (first params)]
                                                                      (c/tooltip-rows (str (.padStart (str (.-dataIndex p)) 2 "0") ":00")
                                                                                      [{:label "steps" :value (ui/thousands (or (.-value p) 0))}])))})
                                            (assoc :grid {:left 0 :right 0 :top 4 :bottom 0})
                                            (assoc :xAxis {:type "category" :show false :data (vec (range 24))})
                                            (assoc :yAxis {:type "value" :show false :min 0})
                                            (assoc :series [(c/bars "Steps" "--green" (mapv #(when (pos? %) %) steps)
                                                                    :barMaxWidth nil :barCategoryGap "15%" :barMinHeight 4)])))})
                [:div.ax [:span "00"] [:span "06"] [:span "12"] [:span "18"] [:span "24"]]]
               (no-data))})))

;; ---------------------------------------------------------------------------
;; Row 4: 7 nights, last activity

(defn- weekday [iso] (ui/weekdays (.getDay (ui/parse-date iso))))

(defn- seven-night-option
  "Bars per night with the value and weekday as two-line category labels."
  [{:keys [nights value-fn color-fn y-max bar-width radius mark-area]}]
  (-> (c/base {:x-type "category"
               :tooltip-fmt (fn [params]
                              (let [n (nth nights (.-dataIndex (first params)))]
                                (c/tooltip-rows (ui/date-long (:date n)) [{:label "" :value (str (value-fn n))}])))})
      (assoc :grid {:left 0 :right 0 :top 4 :bottom 40})
      (assoc :xAxis {:type "category" :axisLine {:show false} :axisTick {:show false}
                     :data (mapv #(str "{s|" (or (value-fn %) "—") "}\n{d|" (weekday (:date %)) "}") nights)
                     :axisLabel {:interval 0 :fontFamily c/font
                                 :rich {:s {:fontSize 12 :fontWeight 700 :lineHeight 18
                                            :color (c/css-var "--text") :fontFamily c/font}
                                        :d {:fontSize 11 :fontWeight 600 :lineHeight 16
                                            :color (c/css-var "--text-3") :fontFamily c/font}}}})
      (assoc :yAxis {:type "value" :show false :min 0 :max y-max})
      (assoc :series [(cond-> {:type "bar" :barMaxWidth 40 :barWidth bar-width
                               :data (mapv (fn [n] {:value (value-fn n)
                                                    :itemStyle {:color (c/css-var (color-fn n)) :borderRadius radius}})
                                           nights)}
                        mark-area (assoc :markArea mark-area))])))

(defn- sleep-nights-card [nights]
  (let [scored (filter :sleep_score nights)
        avg    #(when (seq %) (/ (reduce + %) (count %)))]
    (ui/chart-card
     {:title "Sleep · 7 nights" :icon :moon :accent "--purple" :tint "purple" :soft? true
      :flex "1 1 300px"
      :header [:div.baseline [:span.big.sm (or (some-> (avg (map :sleep_score scored)) js/Math.round) "—")]
               [:span.sub (str "avg score · " (or (ui/hm (avg (keep :sleep_s nights))) "—") " avg")]]
      :chart (if (seq scored)
               (c/echart {:key "nights" :class "h150"
                          :option-fn #(seven-night-option
                                       {:nights nights :value-fn :sleep_score :y-max 100 :radius 10
                                        :color-fn (fn [{:keys [sleep_score]}]
                                                    (cond (nil? sleep_score) "--surface-2"
                                                          (>= sleep_score 80) "--green"
                                                          (>= sleep_score 60) "--purple-2"
                                                          :else "--amber"))})})
               (no-data))})))

(defn- hrv-nights-card [w nights]
  (let [low  (some :hrv_low (reverse nights))
        high (some :hrv_high (reverse nights))
        in?  #(and low high (<= low % high))
        vals (keep :hrv_avg nights)]
    (ui/chart-card
     {:title "HRV · 7 nights" :icon :pulse :accent "--blue" :tint "blue" :soft? true
      :flex "1 1 300px"
      :header [:div.baseline [:span.big.sm (or (some-> (:hrv_weekly_avg w) js/Math.round) "—") [:small "ms"]]
               [:span.sub (str/join " · " (remove nil? [(some-> (:hrv_status w) str/lower-case)
                                                        (when low (str "normal " low "–" high))]))]]
      :chart (if (seq vals)
               (c/echart {:key "hrv" :class "h150"
                          :option-fn #(seven-night-option
                                       {:nights nights :value-fn (fn [n] (some-> (:hrv_avg n) js/Math.round))
                                        :y-max (+ 10 (apply max (or high 0) vals)) :bar-width 14 :radius 7
                                        :color-fn (fn [n] (if (in? (:hrv_avg n)) "--blue" "--amber"))
                                        :mark-area (when low
                                                     {:silent true
                                                      :itemStyle {:color (c/css-var "--tint-green") :borderRadius 8}
                                                      :data [[{:yAxis low} {:yAxis high}]]})})})
               (no-data))})))

(defn- apply-change
  "PATCH body for one readiness suggestion."
  [{:keys [to slot]}]
  (cond (= "REST" to) {:status "skipped"}
        :else (cond-> {}
                to   (assoc :session_id to :title (plan/session-names to))
                slot (assoc :slot slot))))

(defn- planned-today [{:keys [workouts signals changes level]}]
  (let [by-id (into {} (map (juxt :workout_id identity)) changes)]
    [:div.divider
     [:div.lab "Planned today"]
     (if (seq workouts)
       (for [w workouts
             :let [c (by-id (:id w))]]
         [:div {:replicant/key (:id w) :style {:display "flex" :flex-direction "column" :gap "4px"}}
          [:div (plan/session-chip (:session_id w)) " " [:b (:title w)]
           [:span.sub (str " · " (:slot w) (when (pos? (:duration_min w)) (str " · " (:duration_min w) " min")))]]
          (when c
            [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap"}}
             [:span.sub (str "Readiness suggests: "
                             (cond (= "REST" (:to c)) "rest instead"
                                   :else (str/join ", " (remove nil? [(some->> (:to c) plan/session-names (str "change to "))
                                                                      (when (:slot c) "move to the evening")]))))]
             [:button.pill {:on {:click [[:api "PATCH" (str "/api/plan/workouts/" (:id w)) (apply-change c)]]}} "Apply"]])])
       [:div.sub "Nothing planned today."])
     (when (seq signals)
       [:div.sub {:style {:color (if (= "rest" (name level)) "var(--red)" "var(--text-2)")}}
        (str/join " · " signals)])]))

(defn- last-activity-card [a check]
  (ui/card {:flex "1 1 300px"}
           (ui/label-row {:icon :mountain :accent "--text" :tint "--surface-2" :soft? true :label "Last activity"})
           (if a
             [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
              [:a {:href (str "#/activities/" (:id a)) :style {:font-size "20px" :font-weight 800 :text-decoration "none"}}
               (:name a)]
              [:div.sub (ui/date-short (:date a))]
              (ui/pills (some-> (ui/km (:distance_m a)) (str " km") ui/pill)
                        (some-> (ui/hm (or (:moving_s a) (:duration_s a))) ui/pill)
                        (some-> (:avg_hr a) js/Math.round (str " bpm") ui/pill))]
             [:div.empty "No activities yet."])
           (planned-today check)))

;; ---------------------------------------------------------------------------

(defn view [{:keys [data errors sync]}]
  (let [url "/api/today"
        d   (get data url)
        w   (:wellness d)
        day (:day d)
        i   (:intraday d)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title [:div.sub (header-date (:date d))] [:h1 (greeting)]]
      (auth-pill (:auth d))
      (sync-button (or sync (:sync d)))]
     (when-let [e (get errors url)] [:div.error (str "Could not load: " e)])
     (auth-notice (:auth d))
     (when d
       (list
        [:div.row {:replicant/key "r1"}
         (readiness-card w) (body-battery-card w day i) (sleep-card w day)]
        [:div {:replicant/key "r2"} (heart-rate-card w day i)]
        [:div.row {:replicant/key "r3"}
         (stress-card w day i) (steps-card w day i)]
        [:div.row {:replicant/key "r4"}
         (sleep-nights-card (:nights d)) (hrv-nights-card w (:nights d))
         (last-activity-card (:last_activity d) (:readiness_check d))]))]))
