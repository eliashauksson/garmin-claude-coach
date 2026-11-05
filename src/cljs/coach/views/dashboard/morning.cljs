(ns coach.views.dashboard.morning
  "This morning: the readiness ring with its factors, and the vitals tiles."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [dash note pct-tone tone tone-var]]))

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

(defn readiness-card [{:keys [feedback estimate] :as r}]
  [:section.card.readiness {:replicant/key "readiness"}
   (ui/label-row {:icon :bolt :accent (tone-var (tone (:level r)) "--text-3")
                  :tint (case (tone (:level r)) :good "--tint-green" :warn "--tint-amber" :bad "--tint-red"
                          "--surface-2")
                  :soft? true :label (t "Training readiness") :info (if estimate "readiness-estimate" "readiness")
                  :right (cond estimate (ui/pill (t "Estimate"))
                               feedback (ui/pill (ui/enum feedback)))})
   [:div.readiness-body
    (ring r)
    (factors (:factors r))]
   (note (if estimate
           (t "Garmin has no score this morning, so this is the app's estimate, built like Garmin's from the same six factors. It is usually within about 8 points of Garmin's.")
           (t "Garmin's score from 0 to 100, built from last night's sleep, recovery time, load balance, stress and HRV. The bars show how much each one helps today.")))])

(defn- vital [{:keys [key tint tint-var icon accent label value unit sub visual info]}]
  [:section.card.vital {:replicant/key key :class (str "tint-" tint)}
   (ui/label-row {:icon icon :accent accent :tint tint-var :label label :info info})
   [:div.big (dash value) (when (and value unit) [:small unit])]
   [:div.sub sub]
   [:div.vital-visual {:aria-hidden "true"} visual]])

(defn- stage-bar [{:keys [deep_s light_s rem_s awake_s]}]
  (when (some ui/num? [deep_s light_s rem_s awake_s])
    [:div.stages
     (for [[k v color] [[:deep deep_s "--deep"] [:light light_s "--purple-2"] [:rem rem_s "--pink"]
                        [:awake awake_s "--amber"]]
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

(defn vitals-grid [{:keys [vitals nights days]}]
  (let [{:keys [sleep_score sleep_s sleep_quality hrv_night hrv_weekly hrv_status rhr rhr_7d rhr_delta
                bb_now bb_low bb_high]} vitals
        n14 (take-last 14 nights)
        d14 (take-last 14 days)]
    [:div.vitals
     (vital {:key "sleep" :info "sleep" :tint "purple" :tint-var "--tint-purple" :icon :moon :accent "--purple"
             :label (t "Sleep score") :value sleep_score
             :sub (str/join " · " (remove nil? [(ui/hm sleep_s) (ui/enum sleep_quality)]))
             :visual (stage-bar vitals)})
     (vital {:key "hrv" :info "hrv" :tint "teal" :tint-var "--chip-swim-bg" :icon :pulse :accent "--fam-swim"
             :label (t "Overnight HRV") :value hrv_night :unit "ms"
             :sub (str/join " · " (remove nil? [(ui/enum hrv_status) (some->> hrv_weekly (t "7-day {0} ms"))]))
             :visual (when (some :hrv_night n14)
                       (c/spark "spark-hrv" "h44" {:color "--fam-swim" :values (mapv :hrv_night n14)
                                                   :band (mapv (juxt :hrv_balanced_low :hrv_balanced_up) n14)}))})
     (vital {:key "rhr" :info "rhr" :tint "red" :tint-var "--tint-red" :icon :heart :accent "--red"
             :label (t "Resting heart rate") :value rhr :unit "bpm"
             :sub (when rhr_delta
                    [:span
                     [:b {:style {:color (cond (pos? rhr_delta) "var(--red)" (neg? rhr_delta) "var(--green-ink)")}}
                      (ui/signed rhr_delta)]
                     " " (t "vs the 7 days before ({0})" rhr_7d)])
             :visual (when (some :rhr d14)
                       (c/spark "spark-rhr" "h44" {:color "--red" :values (mapv :rhr d14)}))})
     (vital {:key "bb" :info "body-battery" :tint "blue" :tint-var "--tint-blue" :icon :battery :accent "--blue"
             :label (t "Body Battery now") :value bb_now
             :sub (when (and bb_low bb_high) (t "today {0} to {1}" bb_low bb_high))
             :visual (bb-bars days)})]))
