(ns coach.views.dashboard
  "The start page (coach-dashboard-spec.md): how ready am I today, and how is
  training going toward the goal race? From today to the long term: this
  morning's readiness and vitals, the goal race, recovery over 30 days,
  training (with the CTL/ATL/TSB training load), and fitness over 6 months.
  Everything is worked out on the server (/api/overview); this only formats
  and draws."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :as i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.feedback :as feedback]
            [coach.views.load :as load]
            [coach.views.status :as status]))

(def url "/api/overview")

(defn urls
  "The overview, the training load's (load/urls), the activities
  waiting for \"How did it go?\", then the cycling power."
  [route]
  (conj (into [url] (load/urls route)) feedback/due-url load/power-url))

;; Garmin's enums as shown (ui/enum); listed so each has a translation.
(def enum-texts
  [;; readiness levels, HRV status, sleep quality
   (tk "Poor") (tk "Low") (tk "Moderate") (tk "High") (tk "Prime") (tk "Balanced") (tk "Unbalanced")
   (tk "Fair") (tk "Good") (tk "Excellent")
   ;; readiness feedback
   (tk "Listen to your body") (tk "Ready for the day") (tk "Ready to train") (tk "Prime to train")
   (tk "Well recovered") (tk "Recovering") (tk "Rest") (tk "Take it easy") (tk "Recovered")
   (tk "Good sleep") (tk "Poor sleep") (tk "High stress") (tk "Low hrv") (tk "Elevated stress")
   ;; training status
   (tk "Productive") (tk "Maintaining") (tk "Recovery") (tk "Unproductive") (tk "Detraining")
   (tk "Peaking") (tk "Overreaching") (tk "Strained") (tk "No status") (tk "Paused")
   ;; load ratio (ACWR)
   (tk "Optimal") (tk "Very high") (tk "None")
   ;; load focus
   (tk "Aerobic low shortage") (tk "Aerobic high shortage") (tk "Anaerobic shortage")
   (tk "Aerobic low focus") (tk "Aerobic high focus") (tk "Anaerobic focus") (tk "No data")
   ;; endurance classes
   (tk "Recreational") (tk "Intermediate") (tk "Trained") (tk "Expert")
   (tk "Superior") (tk "Elite")])

(declare fit-labels)

(defn- dash [x] (if (or (nil? x) (and (number? x) (js/isNaN x))) "—" x))

(defn- tone
  "Readiness level -> :good / :warn / :bad."
  [level]
  (case level ("HIGH" "PRIME") :good "MODERATE" :warn ("LOW" "POOR") :bad nil))

(def tone-var {:good "--green" :warn "--amber" :bad "--red"})

(defn- pct-tone [p] (cond (nil? p) nil (< p 34) :bad (< p 67) :warn :else :good))

(defn- day-label
  "\"Sat 31 Oct\" (localised)."
  [iso]
  (when iso
    (let [d (ui/parse-date iso)] (str (ui/weekdays (.getDay d)) " " (ui/day-month d)))))

(defn- axis-date [iso] (when iso (ui/day-month (ui/parse-date iso))))

(defn- lower [s] (some-> s str/lower-case))

(defn- note [text] [:div.note text])

(defn- empty-line [] [:div.no-data (t "No data yet")])

(defn- chart-card
  "A recovery/training chart card: title, one-line note, then the body."
  [{:keys [key title text wide? body]}]
  [:section.card.chart-card {:replicant/key key :class (when wide? "wide")}
   [:div.card-title title]
   (when text (note text))
   body])

;; ---------------------------------------------------------------------------
;; Header

(defn- eyebrow-date [iso]
  (let [d (ui/parse-date iso)]
    (str (ui/long-weekdays (.getDay d)) " " (t "{0} {1}" (.getDate d) (ui/long-months (.getMonth d)))
         " " (.getFullYear d) " · Garmin")))

(defn- takeaway [{:keys [readiness vitals]}]
  (let [{:keys [score level]} readiness
        lvl (lower (ui/enum level))
        hrv (or (lower (ui/enum (:hrv_status vitals))) "—")]
    [:p.takeaway {:replicant/key "take"}
     (cond
       (nil? score)
       (t "Your watch hasn't worked out today's readiness yet. It appears after you wake up and sync.")
       (= :bad (tone level))
       (ui/rich (t "Readiness is {0} at {1} and HRV is {2}. Your body is asking for a rest day, or something very easy.") lvl score hrv)
       (= :warn (tone level))
       (ui/rich (t "Readiness is {0} at {1} and HRV is {2}. Keep today aerobic and easy, and save the hard session for a better morning.") lvl score hrv)
       :else
       (ui/rich (t "Readiness is {0} at {1} and HRV is {2}. You are ready for quality work today.") lvl score hrv))]))

;; ---------------------------------------------------------------------------
;; This morning

(def ^:private cx 100)
(def ^:private cy 96)

(defn- polar [r deg]
  (let [a (* deg (/ js/Math.PI 180))]
    [(+ cx (* r (js/Math.cos a))) (+ cy (* r (js/Math.sin a)))]))

(def ^:private arc-d
  (let [[x1 y1] (polar 74 135) [x2 y2] (polar 74 405)]
    (str "M" x1 " " y1 " A74 74 0 1 1 " x2 " " y2)))

(defn- ring [{:keys [score level recovery_h]}]
  (let [color (str "var(" (tone-var (tone level) "--text-3") ")")]
    [:svg.ring {:viewBox "0 0 200 180" :width 190 :height 170 :role "img"
                :aria-label (t "Training readiness {0} of 100" (dash score))}
     [:defs [:filter {:id "ring-glow" :x "-30%" :y "-30%" :width "160%" :height "160%"}
             [:feGaussianBlur {:stdDeviation 6}]]]
     (for [i (range 21)
           :let [deg (+ 135 (* i 13.5)) major? (zero? (mod i 5))
                 [x1 y1] (polar 88 deg) [x2 y2] (polar (if major? 95 92) deg)]]
       [:line {:replicant/key i :x1 x1 :y1 y1 :x2 x2 :y2 y2
               :stroke "var(--text-3)" :stroke-width (if major? 1.6 1) :stroke-linecap "round"}])
     [:path {:d arc-d :fill "none" :stroke "var(--line)" :stroke-width 14 :stroke-linecap "round"}]
     (when score
       (list
        [:path.ring-val {:replicant/key "glow" :d arc-d :fill "none" :stroke color :stroke-width 14
                         :stroke-linecap "round" :pathLength 100 :stroke-dasharray (str score " 200")
                         :filter "url(#ring-glow)" :opacity 0.45}]
        [:path.ring-val {:replicant/key "val" :d arc-d :fill "none" :stroke color :stroke-width 14
                         :stroke-linecap "round" :pathLength 100 :stroke-dasharray (str score " 200")}]))
     [:text.ring-score {:x cx :y 112 :text-anchor "middle"} (dash score)]
     [:text.ring-level {:x cx :y 136 :text-anchor "middle" :fill color}
      (or (some-> (ui/enum level) str/upper-case) "")]
     [:text.ring-foot {:x cx :y 170 :text-anchor "middle"}
      (if recovery_h (t "Recovery {0} h" recovery_h) "")]]))

(def ^:private factor-rows
  [[:sleep (tk "Sleep")] [:recovery_time (tk "Recovery time")] [:acwr (tk "Load balance")]
   [:stress_history (tk "Stress history")] [:hrv (tk "HRV status")] [:sleep_history (tk "Sleep history")]])

(defn- factors [fs]
  [:div.factors
   (for [[i [k label]] (map-indexed vector factor-rows)
         :let [p (get fs k)]]
     [:div.factor {:replicant/key k}
      [:span.factor-lab (t label)]
      [:span.factor-track
       (when p [:span.factor-fill.grow-x {:style {:width (str p "%")
                                                  :background (str "var(" (tone-var (pct-tone p)) ")")
                                                  :animation-delay (str (* i 60) "ms")}}])]
      [:span.factor-val (if p (str p "%") "—")]])])

(defn- readiness-card [{:keys [feedback] :as r}]
  [:section.card.readiness {:replicant/key "readiness"}
   (ui/label-row {:icon :bolt :accent (tone-var (tone (:level r)) "--text-3")
                  :tint (case (tone (:level r)) :good "--tint-green" :warn "--tint-amber" :bad "--tint-red" "--surface-2")
                  :soft? true :label (t "Training readiness")
                  :right (when feedback (ui/pill (ui/enum feedback)))})
   [:div.readiness-body
    (ring r)
    (factors (:factors r))]
   (note (t "Garmin's score from 0 to 100, built from last night's sleep, recovery time, load balance, stress and HRV. The bars show how much each one helps today."))])

(defn- vital [{:keys [key tint tint-var icon accent label value unit sub visual]}]
  [:section.card.vital {:replicant/key key :class (str "tint-" tint)}
   (ui/label-row {:icon icon :accent accent :tint tint-var :label label})
   [:div.big (dash value) (when (and value unit) [:small unit])]
   [:div.sub sub]
   [:div.vital-visual {:aria-hidden "true"} visual]])

(defn- stage-bar [{:keys [deep_s light_s rem_s awake_s]}]
  (when (some ui/num? [deep_s light_s rem_s awake_s])
    [:div.stages
     (for [[k v color] [[:deep deep_s "--deep"] [:light light_s "--purple-2"] [:rem rem_s "--pink"] [:awake awake_s "--amber"]]
           :when (and (ui/num? v) (pos? v))]
       [:div {:replicant/key k :style {:flex v :background (str "var(" color ")")}}])]))

(defn- bb-bars [days]
  (let [ds (take-last 14 days) n (count ds)]
    [:div.bb-bars
     (for [[i {:keys [date bb_min bb_max]}] (map-indexed vector ds)
           :when (and (ui/num? bb_min) (ui/num? bb_max))]
       [:div.bb-col {:replicant/key date}
        [:div.bb-bar {:style {:bottom (str bb_min "%") :height (str (max 2 (- bb_max bb_min)) "%")
                              :opacity (if (= i (dec n)) 1 0.55)}}]])]))

(defn- vitals-grid [{:keys [vitals nights days]}]
  (let [{:keys [sleep_score sleep_s sleep_quality hrv_night hrv_weekly hrv_status rhr rhr_7d rhr_delta
                bb_now bb_low bb_high]} vitals
        n14 (take-last 14 nights)
        d14 (take-last 14 days)]
    [:div.vitals
     (vital {:key "sleep" :tint "purple" :tint-var "--tint-purple" :icon :moon :accent "--purple"
             :label (t "Sleep score") :value sleep_score
             :sub (str/join " · " (remove nil? [(ui/hm sleep_s) (ui/enum sleep_quality)]))
             :visual (stage-bar vitals)})
     (vital {:key "hrv" :tint "teal" :tint-var "--chip-swim-bg" :icon :pulse :accent "--fam-swim"
             :label (t "Overnight HRV") :value hrv_night :unit "ms"
             :sub (str/join " · " (remove nil? [(ui/enum hrv_status) (some->> hrv_weekly (t "7-day {0} ms"))]))
             :visual (when (some :hrv_night n14)
                       (c/spark "spark-hrv" "h44" {:color "--fam-swim" :values (mapv :hrv_night n14)
                                                   :band (mapv (juxt :hrv_balanced_low :hrv_balanced_up) n14)}))})
     (vital {:key "rhr" :tint "red" :tint-var "--tint-red" :icon :heart :accent "--red"
             :label (t "Resting heart rate") :value rhr :unit "bpm"
             :sub (when rhr_delta
                    [:span [:b {:style {:color (cond (pos? rhr_delta) "var(--red)" (neg? rhr_delta) "var(--green-ink)")}}
                            (ui/signed rhr_delta)]
                     " " (t "vs the 7 days before ({0})" rhr_7d)])
             :visual (when (some :rhr d14)
                       (c/spark "spark-rhr" "h44" {:color "--red" :values (mapv :rhr d14)}))})
     (vital {:key "bb" :tint "blue" :tint-var "--tint-blue" :icon :battery :accent "--blue"
             :label (t "Body Battery now") :value bb_now
             :sub (when (and bb_low bb_high) (t "today {0} to {1}" bb_low bb_high))
             :visual (bb-bars days)})]))

;; ---------------------------------------------------------------------------
;; Goal race

(def leg-names {"5k" (tk "5K") "10k" (tk "10K") "half" (tk "half marathon") "marathon" (tk "marathon")})
(def leg-short {"5k" "5K" "10k" "10K" "half" (tk "Half") "marathon" (tk "Marathon")})
(def leg-color {"5k" "--purple" "10k" "--blue" "half" "--fam-swim" "marathon" "--pink"})
(def leg-key {"5k" :t5k "10k" :t10k "half" :thalf "marathon" :tmar})

(defn- minutes-text
  "20 min · 1 h 05 min"
  [s]
  (let [m (js/Math.round (/ (js/Math.abs s) 60))]
    (if (< m 60) (t "{0} min" m) (t "{0} h {1} min" (quot m 60) (.padStart (str (mod m 60)) 2 "0")))))

(defn- join-and
  "\"10K, half marathon and marathon\", capitalised (it starts a sentence)."
  [xs]
  (let [s (if (< (count xs) 2)
            (first xs)
            (t "{0} and {1}" (str/join ", " (butlast xs)) (last xs)))]
    (when s (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn- race-sentence [{:keys [legs total_s margin_s goal_s]}]
  (let [names (map #(t (leg-names %)) legs)
        total (ui/hms total_s)
        goal  (ui/hms goal_s)
        diff  (some-> margin_s minutes-text)
        multi (> (count legs) 1)]
    (when total_s
      [:p.race-text {:replicant/key "sentence"}
       (cond
         (and multi diff (not (neg? margin_s)))
         (ui/rich (t "{0} on one day. Garmin's predictions, each as a fresh race, add up to {1}: {2} inside the {3} goal.")
                  (join-and names) total diff goal)
         (and multi diff)
         (ui/rich (t "{0} on one day. Garmin's predictions, each as a fresh race, add up to {1}: {2} over the {3} goal.")
                  (join-and names) total diff goal)
         multi
         (ui/rich (t "{0} on one day. Garmin's predictions, each as a fresh race, add up to {1}.")
                  (join-and names) total)
         (and diff (not (neg? margin_s)))
         (ui/rich (t "Garmin predicts {1} for the {0}: {2} inside the {3} goal.") (first names) total diff goal)
         diff
         (ui/rich (t "Garmin predicts {1} for the {0}: {2} over the {3} goal.") (first names) total diff goal)
         :else
         (ui/rich (t "Garmin predicts {1} for the {0}.") (first names) total))])))

(defn- goal-bar [{:keys [legs now total_s goal_s]}]
  (when total_s
    (let [scale (* 1.04 (max total_s (or goal_s 0)))
          pct   #(str (* 100 (/ % scale)) "%")
          starts (reductions + 0 (map #(get now (leg-key %)) legs))
          gpos  (when goal_s (/ goal_s scale))]
      [:div.goal-bar {:role "img" :aria-label (t "Predicted total {0}" (ui/hms total_s))}
       (for [[i [leg start]] (map-indexed vector (map vector legs starts))
             :let [v (get now (leg-key leg))]]
         [:div.goal-leg.fade-in {:replicant/key leg
                                 :style {:left (pct start) :width (str "calc(" (pct v) " - 3px)")
                                         :background (str "var(" (leg-color leg) ")")
                                         :animation-delay (str (* i 120) "ms")}}
          [:span.fit (str (t (leg-short leg)) " " (ui/hms v))]])
       (when gpos
         (list
          [:div.goal-line {:replicant/key "line" :style {:left (str (* 100 gpos) "%")}}]
          [:div.goal-lab {:replicant/key "lab"
                          :class (cond (> gpos 0.85) "end" (< gpos 0.15) "start")
                          :style {:left (str (* 100 gpos) "%")}}
           (t "Goal {0}" (ui/hms goal_s))]))])))

(defn- race-hero [{:keys [name date days_to_race] :as race}]
  [:section.card.race-hero {:replicant/key "hero"}
   (if-not name
     (list [:div.eyebrow {:replicant/key "e"} (t "Goal race")]
           [:div.empty {:replicant/key "x"} (t "No goal race in the athlete information yet. Add it on the Plan page.")])
     (list
      [:div.eyebrow {:replicant/key "e"}
       (str/join " · " (remove nil? [(t "Goal race") name (some-> date ui/date-short)]))]
      (when days_to_race
        [:div.countdown {:replicant/key "cd"}
         [:span.cd-num days_to_race]
         [:span.cd-unit (if (= 1 days_to_race) (t "day to go") (t "days to go"))]])
      (race-sentence race)
      (fit-labels "goal" (goal-bar race))))])

(defn- fit-labels
  "Hides each .fit label that doesn't fit its box (on render and resize)."
  [key content]
  (when content
    [:div.fit-host
     {:replicant/key key
      :replicant/on-render
      (fn [{:replicant/keys [life-cycle] :as hook}]
        (let [^js node (:replicant/node hook)
              check #(doseq [^js l (array-seq (.querySelectorAll node ".fit"))]
                       (set! (.. l -style -visibility) "visible")
                       (when (> (.-scrollWidth l) (+ 1 (.-clientWidth l)))
                         (set! (.. l -style -visibility) "hidden")))]
          (if (= life-cycle :replicant.life-cycle/unmount)
            (some-> (.-__ro node) .disconnect)
            (do (when-not (.-__ro node)
                  (let [ro (js/ResizeObserver. (fn [_] ((.-__check node))))]
                    (.observe ro node)
                    (set! (.-__ro node) ro)))
                (set! (.-__check node) check)
                (check)))))}
     content]))

(defn- prediction-cards [{:keys [now trend]}]
  [:div.predictions
   (for [[leg k label] [["10k" :t10k (tk "10K")] ["half" :thalf (tk "Half marathon")] ["marathon" :tmar (tk "Marathon")]]
         :let [values (mapv k trend)]]
     [:section.card.prediction {:replicant/key leg}
      [:div.lab (t label)]
      [:div.pred-time (dash (ui/hms (get now k)))]
      (if (some some? values)
        (c/spark (str "pred-" leg) "h54" {:color "--pink" :values values :inverse? true})
        [:div.h54])])
   (note (t "Lines show the prediction at the end of each month over the last 6 months; up means faster."))])

;; ---------------------------------------------------------------------------
;; Recovery, last 30 days

(defn- off-grid-ends
  "Hides the y axis's min/max labels: padded ranges end between ticks."
  [option]
  (update-in option [:yAxis :axisLabel] assoc :showMinLabel false :showMaxLabel false))

(defn- cat-base
  "c/base on a category x axis of `dates` (labelled \"31 Oct\")."
  [dates opts]
  (-> (c/base (merge {:x-type "category"} opts))
      (update :xAxis merge {:data (vec dates)
                            :axisLabel (merge (get-in (c/base {}) [:xAxis :axisLabel])
                                              {:formatter (fn [v] (axis-date v)) :hideOverlap true})})))

(defn- rows-tip
  "Axis tooltip from `rows` by data index: (f row) -> [heading [{:label :value :color}]]."
  [rows f]
  (fn [params]
    (when-let [r (nth rows (.-dataIndex (first params)) nil)]
      (let [[heading lines] (f r)] (c/tooltip-rows heading lines)))))

(defn- hrv-chart [nights]
  (let [dates (mapv :date nights)
        vals  (concat (keep :hrv_night nights) (keep :hrv_balanced_low nights) (keep :hrv_balanced_up nights)
                      (keep :hrv_weekly nights))
        teal  (c/css-var "--fam-swim")]
    (if (empty? (keep :hrv_night nights))
      (empty-line)
      (c/echart
       {:key "hrv" :class "h240"
        :option-fn
        (fn []
          (merge (off-grid-ends (cat-base dates {:y-min (- (apply min vals) 4) :y-max (+ (apply max vals) 4)
                                  :y-fmt (fn [v] (str v " ms"))
                                  :tooltip-fmt (rows-tip nights
                                                         (fn [n] [(day-label (:date n))
                                                                  [{:label (t "overnight") :value (some-> (:hrv_night n) (str " ms")) :color teal}
                                                                   {:label (t "7-day average") :value (some-> (:hrv_weekly n) (str " ms"))}
                                                                   {:label (t "balanced range")
                                                                    :value (when (:hrv_balanced_low n) (str (:hrv_balanced_low n) "–" (:hrv_balanced_up n) " ms"))}
                                                                   {:label (t "status") :value (ui/enum (:hrv_status n))}]]))}))
                 {:series
                  [{:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0} :tooltip {:show false}
                    :data (mapv :hrv_balanced_low nights)}
                   {:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0} :tooltip {:show false}
                    :areaStyle {:color teal :opacity 0.13}
                    :data (mapv #(when (and (:hrv_balanced_low %) (:hrv_balanced_up %)) (- (:hrv_balanced_up %) (:hrv_balanced_low %))) nights)}
                   {:type "line" :name (t "7-day average") :data (mapv :hrv_weekly nights) :showSymbol false
                    :lineStyle {:width 1.5 :type "dashed" :color (c/css-var "--text-3")} :emphasis {:disabled true}}
                   {:type "line" :name (t "Overnight") :showSymbol true :symbolSize 7 :connectNulls false
                    :lineStyle {:width 2.5 :color teal} :emphasis {:disabled true}
                    :data (mapv (fn [{:keys [hrv_night hrv_low_up hrv_balanced_low]}]
                                  {:value hrv_night
                                   :itemStyle {:borderColor (c/css-var "--surface") :borderWidth 2
                                               :color (cond (and hrv_night hrv_low_up (< hrv_night hrv_low_up)) (c/css-var "--red")
                                                            (and hrv_night hrv_balanced_low (< hrv_night hrv_balanced_low)) (c/css-var "--amber")
                                                            :else teal)}})
                                nights)}]}))}))))

(def stage-series
  [[:deep_s (tk "Deep") "--deep"] [:light_s (tk "Light") "--purple-2"] [:rem_s (tk "REM") "--pink"] [:awake_s (tk "Awake") "--amber"]])

(defn- sleep-chart [nights]
  (let [dates (mapv :date nights)
        hours #(some-> % (/ 3600))
        top   (apply max 9 (concat (keep #(some-> (:total_s %) (+ (or (:awake_s %) 0)) hours) nights)
                                   (keep #(some-> (:need_min %) (/ 60)) nights)))]
    (if (empty? (keep :total_s nights))
      (empty-line)
      (list
       (ui/legend (concat (for [[_ label color] stage-series] {:label (t label) :color color})
                          [{:label (t "Sleep need") :color "--text"}]))
       (c/echart
        {:key "sleep" :class "h230"
         :option-fn
         (fn []
           (merge (cat-base dates {:y-min 0 :y-max (* 3 (js/Math.ceil (/ top 3))) :y-fmt (fn [v] (str v " h"))
                                   :tooltip-fmt (rows-tip nights
                                                          (fn [n] [(day-label (:date n))
                                                                   (concat
                                                                    [{:label (t "sleep score") :value (some-> (:sleep_score n) (str (some->> (:quality n) ui/enum (str " · "))))}
                                                                     {:label (t "asleep") :value (ui/h-mm (:total_s n))}]
                                                                    (for [[k label color] stage-series]
                                                                      {:label (t label) :value (ui/h-mm (get n k)) :color (c/css-var color)})
                                                                    [{:label (t "sleep need") :value (some-> (:need_min n) (* 60) ui/h-mm)}])]))})
                  {:series
                   (concat
                    (c/stacked 3
                               (for [[k label color] stage-series]
                                 (c/bars (t label) color (mapv #(hours (get % k)) nights)
                                         :stack "sleep" :barMaxWidth 18
                                         :itemStyle {:color (c/css-var color)})))
                    [{:type "custom" :name (t "Sleep need") :z 10 :silent true :tooltip {:show false}
                      :data (vec (map-indexed (fn [i n] [i (or (some-> (:need_min n) (/ 60)) "-")]) nights))
                      :renderItem (fn [_ ^js api]
                                    (let [v (.value api 1)]
                                      (when (and (number? v) (not (js/isNaN v)))
                                        (let [p (.coord api #js [(.value api 0) v])
                                              w (min 24 (* 0.8 (aget (.size api #js [1 0]) 0)))]
                                          (clj->js {:type "line"
                                                    :shape {:x1 (- (aget p 0) (/ w 2)) :y1 (aget p 1)
                                                            :x2 (+ (aget p 0) (/ w 2)) :y2 (aget p 1)}
                                                    :style {:stroke (c/css-var "--text") :lineWidth 2}})))))}])}))})))))

(defn- bb-chart [days]
  (let [dates (mapv :date days)
        blue  (c/css-var "--blue")]
    (if (empty? (keep :bb_max days))
      (empty-line)
      (c/echart
       {:key "bb" :class "h230"
        :option-fn
        (fn []
          (merge (cat-base dates {:y-min 0 :y-max 100
                                  :tooltip-fmt (rows-tip days
                                                         (fn [d] [(day-label (:date d))
                                                                  [{:label (t "range") :value (when (:bb_min d) (str (:bb_min d) "–" (:bb_max d))) :color blue}
                                                                   {:label (t "end of day") :value (:bb_latest d)}
                                                                   {:label (t "charged") :value (some->> (:bb_charged d) (str "+"))}
                                                                   {:label (t "drained") :value (some->> (:bb_drained d) (str "−"))}]]))})
                 {:series
                  [{:type "bar" :stack "bb" :silent true :itemStyle {:color "transparent"} :tooltip {:show false}
                    :data (mapv :bb_min days) :barMaxWidth 14}
                   {:type "bar" :stack "bb" :name (t "Body Battery") :barMaxWidth 14
                    :itemStyle {:color blue :opacity 0.7 :borderRadius 5}
                    :emphasis {:focus "self" :itemStyle {:opacity 1}}
                    :blur {:itemStyle {:opacity 0.45}}
                    :data (mapv #(when (and (:bb_min %) (:bb_max %)) (- (:bb_max %) (:bb_min %))) days)}
                   {:type "scatter" :name (t "end of day") :symbolSize 8 :z 5
                    :itemStyle {:color (c/css-var "--surface") :borderColor blue :borderWidth 2}
                    :data (mapv :bb_latest days)}]}))}))))

(defn- padded-range [vs]
  (let [lo (apply min vs) hi (apply max vs) pad (max 1 (* 0.2 (- hi lo)))]
    [(js/Math.floor (- lo pad)) (js/Math.ceil (+ hi pad))]))

(defn- end-line
  "A line on a time axis with an area fade and a labelled end point (VO2 max)."
  [{:keys [key class color rows k fmt]}]
  (let [points (vec (keep (fn [r] (when (get r k) [(:date r) (get r k)])) rows))]
    (if (empty? points)
      (empty-line)
      (let [[lo hi] (padded-range (map second points))
            [_ lastv :as end] (peek points)
            {:keys [label pad]} (c/end-label (fmt lastv) color)]
        (c/echart
         {:key key :class class
          :option-fn
          (fn []
            (-> (off-grid-ends
                 (c/base {:y-min lo :y-max hi
                         :x-fmt (fn [v] (axis-date (.toISOString (js/Date. v))))
                         :tooltip-fmt (fn [params]
                                        (let [[d v] (js->clj (.-value (first params)))]
                                          (c/tooltip-rows (day-label d) [{:label "VO₂ max" :value (fmt v) :color (c/css-var color)}])))}))
                (assoc-in [:grid :right] pad)
                (merge {:series [(c/line "VO₂ max" color points :lineStyle {:width 2.2} :sampling nil
                                         :areaStyle {:color (c/fade color 0.26)})
                                 {:type "scatter" :silent true :symbolSize 8 :tooltip {:show false}
                                  :itemStyle {:color (c/css-var color) :borderColor (c/css-var "--surface") :borderWidth 2}
                                  :label (assoc label :position "right")
                                  :data [end]}]})))})))))

(defn- rhr-chart [days]
  (let [rows (vec days)
        i    (last (keep-indexed #(when (:rhr %2) %1) rows))]
    (if-not i
      (empty-line)
      (let [lastv (:rhr (rows i))
            text  (str lastv " bpm")
            {:keys [label pad]} (c/end-label text "--red")
            [lo hi] (padded-range (keep :rhr rows))]
        (c/echart
         {:key "rhr" :class "h180"
          :option-fn
          (fn []
            (-> (off-grid-ends
                 (cat-base (mapv :date rows)
                          {:y-min lo :y-max hi
                           :tooltip-fmt (rows-tip rows (fn [r] [(day-label (:date r))
                                                                [{:label (t "resting HR") :value (some-> (:rhr r) (str " bpm"))
                                                                  :color (c/css-var "--red")}]]))}))
                (assoc-in [:grid :right] pad)
                (merge {:series [(c/line (t "Resting heart rate") "--red" (mapv :rhr rows)
                                         :lineStyle {:width 2.2} :connectNulls true :sampling nil
                                         :areaStyle {:color (c/fade "--red" 0.26)})
                                 {:type "scatter" :silent true :symbolSize 8 :tooltip {:show false}
                                  :itemStyle {:color (c/css-var "--red") :borderColor (c/css-var "--surface") :borderWidth 2}
                                  :label (assoc label :position "right")
                                  :data [[i lastv]]}]})))})))))

(defn- stress-chart [days]
  (let [vs (keep :stress_avg days)]
    (if (empty? vs)
      (empty-line)
      (c/echart
       {:key "stress" :class "h180"
        :option-fn
        (fn []
          (merge (cat-base (mapv :date days)
                           {:y-min 0 :y-max (max 60 (apply max vs))
                            :tooltip-fmt (rows-tip days (fn [d] [(day-label (:date d))
                                                                 [{:label (t "average stress") :value (:stress_avg d)}
                                                                  {:label (t "steps") :value (ui/thousands (:steps d))}]]))})
                 {:series [(c/bars (t "Average stress") "--blue"
                                   (mapv (fn [{:keys [stress_avg]}]
                                           {:value stress_avg
                                            :itemStyle {:borderRadius 4
                                                        :color (c/css-var (cond (nil? stress_avg) "--line"
                                                                                (<= stress_avg 25) "--blue"
                                                                                (<= stress_avg 50) "--amber"
                                                                                :else "--red"))}})
                                         days)
                                   :barMaxWidth 14
                                   :markLine {:silent true :symbol "none" :label {:show false}
                                              :lineStyle {:color (c/css-var "--text-3") :type "dashed" :width 1}
                                              :data [{:yAxis 25}]})]}))}))))

(defn- recovery [{:keys [nights days]}]
  (list
   [:div.section-head {:replicant/key "rec-head"}
    [:h2.section-title (t "Recovery") [:span.section-note (t "Last 30 days")]]]
   [:div.chart-grid {:replicant/key "rec"}
    (chart-card {:key "hrv" :wide? true :title (t "Overnight HRV against your baseline")
                 :text (t "Nightly average in ms. The shaded band is Garmin's balanced range for you; the dashed line is the 7-day average.")
                 :body (hrv-chart nights)})
    (chart-card {:key "sleep" :title (t "Sleep stages per night")
                 :text (t "Hours asleep in each stage; the dark tick is the sleep Garmin says you needed.")
                 :body (sleep-chart nights)})
    (chart-card {:key "bb" :title (t "Body Battery range per day")
                 :text (t "From the day's low to its high; the ring is where the day ended.")
                 :body (bb-chart days)})
    (chart-card {:key "rhr" :title (t "Resting heart rate")
                 :text (t "Beats per minute at rest, each day.")
                 :body (rhr-chart days)})
    (chart-card {:key "stress" :title (t "Average stress per day")
                 :text (t "Garmin's stress score from 0 to 100; below the dashed line at 25 is rest.")
                 :body (stress-chart days)})]))

;; ---------------------------------------------------------------------------
;; Training

(defn- delta-el [d unit]
  [:div.delta {:class (cond (nil? d) nil (pos? d) "up" (neg? d) "down" :else "flat")}
   (if (nil? d) "—" (str (ui/signed d) (when unit (str " " unit))))])

(defn- kpi [key label value unit delta]
  [:section.card.kpi {:replicant/key key}
   [:div.lab label]
   [:div.big.md (dash value) (when (and value unit) [:small unit])]
   (delta-el delta unit)])

(defn- kpis [{:keys [period status]}]
  (let [{:keys [hours km load sessions hours_delta km_delta load_delta sessions_delta]} period]
    [:div.kpis {:replicant/key "kpis"}
     (kpi "hours" (t "Time") hours "h" hours_delta)
     (kpi "km" (t "Distance") km "km" km_delta)
     (kpi "load" (t "Training load") load nil load_delta)
     (kpi "sessions" (t "Sessions") sessions nil sessions_delta)
     [:section.card.kpi {:replicant/key "status"}
      [:div.lab (t "Training status")]
      [:div.status-phrase (dash (ui/enum (:status status)))]
      (when (:device status) [:div.sub (t "on your {0}" (:device status))])]]))

(def fam-order ["run" "bike" "swim" "hike" "ski" "strength" "other"])

(defn- fam [id] (some #(when (= (name (:id %)) id) %) ui/families))

(defn- mondays
  "The 26 Mondays ending with this week's."
  [today]
  (let [d (ui/parse-date today)
        dow (mod (+ 6 (.getDay d)) 7)]
    (.setDate d (- (.getDate d) dow (* 7 25)))
    (vec (for [i (range 26)]
           (let [x (js/Date. (.getTime d))]
             (.setDate x (+ (.getDate x) (* 7 i)))
             (.slice (.toISOString x) 0 10))))))

(defn- add-days [iso n]
  (let [d (ui/parse-date iso)] (.setDate d (+ (.getDate d) n)) (.slice (.toISOString d) 0 10)))

(defn- draw-heatmap!
  "Every day of the last 26 weeks as a square, Monday at the top."
  [cells today ^js node width first?]
  (let [by     (into {} (map (juxt :date identity)) cells)
        maxl   (max 1 (apply max 1 (keep :load cells)))
        cell   (max 7 (min 26 (- (js/Math.floor (/ (- width 30) 26)) 4)))
        gap    4
        top    18
        left   30
        weeks  (mondays today)
        h      (+ top (* 7 (+ cell gap)))
        rects  (for [[col monday] (map-indexed vector weeks)
                     row (range 7)
                     :let [date (add-days monday row)]
                     :when (<= (compare date today) 0)
                     :let [{:keys [load family] :as day} (get by date)
                           active (and day (pos? (or load 0)))]]
                 (c/el :svg/rect
                       {:x (+ left (* col (+ cell gap))) :y (+ top (* row (+ cell gap)))
                        :width cell :height cell :rx (/ cell 4)
                        :class (str "hm-cell" (when first? " anim") (when (= date today) " today"))
                        :style (str (if active
                                      (str "fill: var(" (:color (fam family)) "); fill-opacity: "
                                           (+ 0.3 (* 0.7 (js/Math.sqrt (/ load maxl)))))
                                      "fill: var(--line)")
                                    (when first? (str "; animation-delay: " (* col 18) "ms")))
                        :data-date date}))
        month-labels (loop [[[col monday] & more] (map-indexed vector weeks) last-x -100 acc []]
                       (if-not monday
                         acc
                         (let [d (ui/parse-date monday) x (+ left (* col (+ cell gap)))]
                           (if (and (<= (.getDate d) 7) (>= (- x last-x) 30))
                             (recur more x (conj acc (c/el :svg/text {:x x :y 11 :class "hm-lab"} (ui/months (.getMonth d)))))
                             (recur more last-x acc)))))
        day-labels (for [[row i] [[0 1] [2 3] [4 5] [6 0]]]
                     (c/el :svg/text {:x 0 :y (+ top (* row (+ cell gap)) (* 0.75 cell)) :class "hm-lab"}
                           (ui/weekdays i)))
        svg (c/el :svg/svg {:width (+ left (* 26 (+ cell gap))) :height h :role "img"
                            :aria-label (t "Every day of the last 26 weeks")}
                  month-labels day-labels rects)]
    (.replaceChildren node svg)
    (set! (.-__tip node) nil)
    (set! (.-onmousemove svg)
          (fn [^js ev]
            (let [date (some-> ev .-target (.getAttribute "data-date"))
                  d    (get by date)]
              (c/tooltip! node ev
                          (when date
                            (if d
                              [(day-label date) (:names d)
                               (str (t "load {0}" (:load d)) " · " (ui/hm (* 60 (:minutes d))))]
                              [(day-label date) (t "Rest day")]))))))
    (set! (.-onmouseleave svg) #(c/tooltip! node % nil))))

(defn- heatmap-card [{:keys [heatmap date]}]
  (let [fams (filter (set (map :family heatmap)) fam-order)]
    (chart-card {:key "heat" :wide? true :title (t "Every day of the last 26 weeks")
                 :text (t "One square a day, Monday at the top. Colour is the sport with the most load that day, and the stronger the colour, the bigger the load.")
                 :body (list
                        (ui/legend (for [f fams :let [x (fam f)]] {:label (t (:label x)) :color (:color x)}))
                        (c/dom-chart {:key "heatmap" :class "heatmap"
                                      :draw! (fn [node w first?] (draw-heatmap! heatmap date node w first?))}))})))

(defn- weekly-card [{:keys [weekly date]}]
  (let [weeks (mondays date)
        by    (group-by :week weekly)
        fams  (filter (set (map :family weekly)) fam-order)]
    (chart-card
     {:key "weekly" :wide? true :title (t "Weekly training load by sport")
      :text (t "Garmin's training load per week, stacked by sport.")
      :body (if (empty? weekly)
              (empty-line)
              (list
               (ui/legend (for [f fams :let [x (fam f)]] {:label (t (:label x)) :color (:color x)}))
               (c/echart
                {:key "weekly-load" :class "h230"
                 :option-fn
                 (fn []
                   (-> (cat-base weeks
                                 {:y-min 0
                                  :tooltip-fmt (fn [params]
                                                 (let [wk (nth weeks (.-dataIndex (first params)))
                                                       rows (get by wk)]
                                                   (c/tooltip-rows
                                                    (t "Week of {0}" (axis-date wk))
                                                    (concat
                                                     [{:label (t "total load") :value (reduce + 0 (keep :load rows))}]
                                                     (for [f fams :let [r (some #(when (= f (:family %)) %) rows)] :when r]
                                                       {:label (t (:label (fam f))) :value (str (:load r) " · " (:hours r) " h")
                                                        :color (c/css-var (:color (fam f)))})))))})
                       (assoc-in [:tooltip :axisPointer] {:type "shadow" :shadowStyle {:color (c/alpha "--text-3" 0.12)}})
                       (merge {:series (c/stacked 5
                                                  (for [f fams :let [x (fam f)]]
                                                    (c/bars (t (:label x)) (:color x)
                                                            (vec (for [wk weeks] (:load (some #(when (= f (:family %)) %) (get by wk)))))
                                                            :stack "load" :barMaxWidth 22
                                                            :itemStyle {:color (c/css-var (:color x))})))})))})))})))

(defn- focus-card [{:keys [status]}]
  (let [{:keys [aer_low aer_low_min aer_low_max aer_high aer_high_min aer_high_max anaer anaer_min anaer_max balance]} status
        rows  [[(t "Low aerobic") "--blue" aer_low aer_low_min aer_low_max]
               [(t "High aerobic") "--amber" aer_high aer_high_min aer_high_max]
               [(t "Anaerobic") "--pink" anaer anaer_min anaer_max]]
        scale (* 1.08 (apply max 1 (keep identity [aer_low aer_low_max aer_high aer_high_max anaer anaer_max])))
        pct   #(str (* 100 (/ % scale)) "%")]
    (chart-card
     {:key "focus" :title (t "Load focus, last 4 weeks")
      :text (t "Your load in each intensity band against the range Garmin targets for you (shaded).")
      :body (if (every? nil? [aer_low aer_high anaer])
              (empty-line)
              (list
               [:div.focus {:replicant/key "rows"}
                (for [[label color v lo hi] rows]
                  [:div.focus-row {:replicant/key label}
                   [:div.focus-lab label]
                   [:div.focus-line
                    [:div.focus-track
                     (when (and lo hi)
                       [:div.focus-target {:style {:left (pct lo) :width (pct (- hi lo))
                                                   :background (c/alpha color 0.16)
                                                   :border-color (c/alpha color 0.5)}}])
                     (when v [:div.focus-val.grow-x {:style {:width (pct v) :background (str "var(" color ")")}}])]
                    [:span.focus-num {:class (when (and v lo (< v lo)) "below")} (dash v)]]])]
               (when balance [:div.sub {:replicant/key "bal"} (ui/enum balance)])))})))

(defn- acwr-card [{:keys [status]}]
  (let [{:keys [acute chronic chronic_min chronic_max acwr acwr_status]} status
        scale (* 1.15 (apply max 1 (keep identity [acute chronic chronic_max])))
        pct   #(str (* 100 (/ % scale)) "%")
        edge  #(let [f (/ % scale)] (cond (< f 0.1) "start" (> f 0.9) "end"))]
    (chart-card
     {:key "acwr" :title (t "Acute vs chronic load")
      :text (t "The last 7 days' load (acute) against the 4-week load you are used to (chronic). The shaded band is the optimal range.")
      :body (if (nil? chronic)
              (empty-line)
              (list
               [:div.acwr {:replicant/key "bar"}
                (when acute
                  [:div.acwr-lab.above {:class (edge acute)
                                        :style {:left (pct acute)
                                                :color (when (= "HIGH" acwr_status) "var(--red)")}}
                   (t "Acute {0}" acute)])
                [:div.acwr-track
                 (when (and chronic_min chronic_max)
                   [:div.acwr-band {:style {:left (pct chronic_min) :width (pct (- chronic_max chronic_min))}}])
                 [:div.acwr-mark.chronic {:style {:left (pct chronic)}}]
                 (when acute
                   [:div.acwr-mark.acute {:style {:left (pct acute)
                                                  :background (if (= "HIGH" acwr_status) "var(--red)" "var(--text)")}}])]
                [:div.acwr-lab.below {:class (edge chronic) :style {:left (pct chronic)}} (t "Chronic {0}" chronic)]]
               [:div.sub {:replicant/key "foot"}
                (str/join " · " (remove nil? [(when acwr (t "Ratio {0}" (.toFixed acwr 1))) (ui/enum acwr_status)]))]))})))

(def zone-colors {"Z1" "--fam-other" "Z2" "--blue" "Z3" "--green" "Z4" "--amber" "Z5" "--red"})

(defn- zones-card [{:keys [zones]}]
  (let [total (reduce + 0 (keep :minutes zones))]
    (chart-card
     {:key "zones" :title (t "Time in heart-rate zones")
      :text (t "Minutes in each zone over all activities of the last 28 days.")
      :body (if (zero? total)
              (empty-line)
              (list
               [:div.zone-bar {:replicant/key "bar" :role "img" :aria-label (t "Time in heart-rate zones")}
                (for [{:keys [zone minutes]} zones :when (pos? minutes)]
                  [:div {:replicant/key zone :style {:flex minutes :background (str "var(" (zone-colors zone) ")")}}])]
               [:div.zone-legend {:replicant/key "legend"}
                (for [{:keys [zone minutes]} zones]
                  [:div {:replicant/key zone}
                   [:div.zl [:span.dot {:style {:background (str "var(" (zone-colors zone) ")")}}] zone]
                   [:div.zv (ui/h-mm (* 60 minutes))]
                   [:div.sub (str (js/Math.round (* 100 (/ minutes total))) "%")]])]))})))

(defn- recent-card [{:keys [recent]}]
  (chart-card
   {:key "recent" :wide? true :title (t "Latest activities")
    :body (if (empty? recent)
            (empty-line)
            (ui/table [{:label (t "Date") :class "date" :value #(ui/date-short (:date %))}
                       {:label (t "Sport") :render #(ui/sport-chip (:sport %))}
                       {:label (t "Name") :class "name-wrap" :value :name}
                       {:label "km" :align :r :value #(some-> (:km %) (.toFixed 1))}
                       {:label (t "Time") :align :r :value #(ui/h-mm (:duration_s %))}
                       {:label (t "Avg HR") :align :r :value :avg_hr}
                       {:label (t "Load") :align :r :value :load}]
                      recent
                      {:key-fn :id
                       :on-row (fn [a] [[:navigate (str "#/activities/" (:id a))]])}))}))

(defn- training [d]
  (list
   [:div.section-head {:replicant/key "tr-head"}
    [:h2.section-title (t "Training") [:span.section-note (t "Last 28 days, change from the 28 days before")]]]
   (kpis d)
   [:div.chart-grid {:replicant/key "tr"}
    (heatmap-card d)
    (weekly-card d)
    (focus-card d)
    (acwr-card d)
    (zones-card d)
    (recent-card d)]))

;; ---------------------------------------------------------------------------
;; Fitness, last 6 months

(def classes
  [[:low (tk "Recreational")] [:intermediate (tk "Intermediate")] [:trained (tk "Trained")]
   [:well_trained (tk "Well-trained")] [:expert (tk "Expert")] [:superior (tk "Superior")] [:elite (tk "Elite")]])

(defn- endurance-gauge [{:keys [score limits]}]
  (let [bounds (conj (mapv #(get limits (first %)) classes) (:high limits))
        lo (first bounds) hi (last bounds)
        pct #(* 100 (/ (- % lo) (- hi lo)))
        cur (last (keep-indexed (fn [i b] (when (>= score b) i)) (butlast bounds)))
        next-b (get bounds (inc cur))]
    (list
     (fit-labels "gauge"
      [:div.endurance
       [:div.end-marker {:style {:left (str (pct score) "%")}}]
       [:div.end-segs
        (for [[i [k label]] (map-indexed vector classes)
              :let [a (get bounds i) b (get bounds (inc i))]]
          [:div.end-seg {:replicant/key k :style {:flex (- b a)}}
           [:div.end-bar {:class (when (= i cur) "current")
                          :style {:opacity (+ 0.15 (* 0.75 (/ i 6)))}}]
           [:div.end-lab {:class (when (= i cur) "current")} [:span.fit (t label)]]])]])
     [:div.sub {:replicant/key "foot"}
      (if (and next-b (< (inc cur) (count classes)))
        (t "{0} points to {1}" (js/Math.round (- next-b score)) (t (second (classes (inc cur)))))
        (t "Top class"))])))

(defn- fitness [{:keys [vo2 endurance]}]
  (list
   [:div.section-head {:replicant/key "fit-head"}
    [:h2.section-title (t "Fitness") [:span.section-note (t "Last 6 months")]]]
   [:div.chart-grid {:replicant/key "fit"}
    (chart-card {:key "vo2" :title (t "VO₂ max (running)")
                 :text (t "Garmin's estimate in ml/kg/min, each day it changed.")
                 :body (end-line {:key "vo2" :class "h220" :color "--green" :rows vo2 :k :vo2
                                  :fmt #(.toFixed % 1)})})
    (chart-card {:key "endurance" :title (t "Endurance score")
                 :text (t "How long you can keep going, from all your training. The marker is your score on Garmin's classes.")
                 :body (if-not (:score endurance)
                         (empty-line)
                         (list [:div.big.md {:replicant/key "n"} (:score endurance)]
                               (when (:limits endurance) (endurance-gauge endurance))))})]))

;; ---------------------------------------------------------------------------
;; Getting started (a new install)

(defn- setup-card
  "The steps a new install still needs; gone once all are done."
  [{:keys [setup running job] :as sync}]
  (let [{:keys [garmin synced claude history athlete season]} setup
        step (fn [k done? title body]
               [:li.setup-step {:replicant/key k :class (when done? "done")}
                [:span.setup-check {:aria-hidden "true"} (if done? "✓" "")]
                [:div [:div.setup-title title (when done? [:span.visually-hidden (str " " (t "done"))])]
                 (when-not done? [:div.sub body])]])]
    (when (and setup (not (and garmin synced claude history athlete season)))
      [:section.card.setup {:replicant/key "setup"}
       (ui/label-row {:icon :bolt :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Getting started")})
       [:ol.setup-steps
        (step "garmin" garmin (t "Connect Garmin")
              [:span (t "Log in with your Garmin account once.") " " [:a {:href "#/settings"} (t "Connect")]])
        (step "sync" synced (t "First sync")
              (if garmin
                [:span (t "Loads the last 4 weeks (a few minutes).") " " (status/sync-button sync)]
                (t "After Garmin is connected.")))
        (step "claude" claude (t "Connect Claude")
              [:span (t "With your own API key.") " " [:a {:href "#/settings"} (t "How to connect")]])
        (step "history" history (t "Load your full history")
              (cond
                (and running (= "history" job)) (t "Loading… you can use the dashboard meanwhile.")
                synced [:span (t "Everything before the first sync, for the long-term charts and the coach.") " "
                        [:button.pill {:disabled running :on {:click [[:load-history]]}} (t "Load full history")]]
                :else (t "After the first sync.")))
        (step "athlete" athlete (t "Tell the coach about yourself")
              [:span (t "Your goal race, availability and history, in a chat with Claude.") " "
               [:a {:href "#/plan"} (t "Open the Plan page")]])
        (step "season" season (t "Create the season plan")
              [:span (t "From then on the coach keeps the next two weeks planned.") " "
               [:a {:href "#/plan"} (t "Open the Plan page")]])]])))

;; ---------------------------------------------------------------------------

(defn- skeleton []
  [:div.skeleton {:aria-hidden "true"}
   [:div.row [:div.skel {:style {:flex "1 1 360px" :height "300px"}}]
    [:div.skel {:style {:flex "1 1 360px" :height "300px"}}]]
   [:div.skel {:style {:height "260px"}}]
   [:div.skel {:style {:height "320px"}}]])

(defn view [{:keys [data errors sync route] ui-state :ui :as s}]
  (let [[ov-url load-url volume-url due-url power-url] (urls route)
        d     (get data ov-url)
        today (or (:date d) (ui/iso-today))]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title
       [:div.eyebrow (eyebrow-date today)]
       [:h1 "Coach"]]
      (status/auth-pill (:auth sync))
      (status/sync-button sync)]
     (setup-card sync)
     (when-let [e (get errors ov-url)] [:div.error (t "Could not load: {0}" e)])
     (if-not d
       (when-not (get errors ov-url) (skeleton))
       (list
        (takeaway d)
        (feedback/due-card ui-state (get data due-url))
        [:div.morning {:replicant/key "morning"}
         (readiness-card (:readiness d))
         (vitals-grid d)]
        [:div.section-head {:replicant/key "race-head"} [:h2.section-title (t "Goal race")]]
        [:div.race {:replicant/key "race"}
         (race-hero (:race d))
         (prediction-cards (:race d))]
        (recovery d)
        (training d)))
     (load/section s [load-url volume-url])
     (load/power-section s power-url)
     (when d (fitness d))]))
