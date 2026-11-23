(ns coach.views.dashboard.cycle-trends
  "Trends' cycle section, only for an athlete who tracks their cycle. Three questions, each with its own chart:
  how long are my cycles (a bar per cycle, the period in red, the usual 21 to 35 days shaded), how do I feel
  through the cycle (symptoms by day of the cycle) and how does my body change (HRV and resting heart rate by
  phase, against her own average). Few days per phase and logged days only: a hint, not a measurement."
  (:require [coach.charts :as c]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [chart-card dash]]
            [coach.views.dashboard.cycle :as cycle]))

(def url "/api/cycle/summary")

(defn days-url
  "The cycle days under the recovery charts (their phase backgrounds)."
  []
  (str "/api/cycle?from=" (ui/days-ago 32) "&to=" (ui/iso-today)))

(def ^:private usual-from "Shortest cycle of the usual range (days)." 21)
(def ^:private usual-to "Longest cycle of the usual range (days)." 35)

(defn- cols
  "The style of a row of `scale` equal day columns."
  [scale]
  {:grid-template-columns (str "repeat(" scale ", minmax(0, 1fr))")})

(defn- finished [history] (remove :current history))

(defn- spread-word
  "How steady the cycle length is: the same 17 days as the irregular flag."
  [variability]
  (cond (<= variability 7) (t "steady") (<= variability 16) (t "varies") :else (t "varies a lot")))

;; ---------------------------------------------------------------------------
;; Summary

(defn- tile [key label value unit sub]
  [:section.card.kpi {:replicant/key key}
   [:div.lab label]
   [:div.big.md (dash value) (when (and value unit) [:small unit])]
   [:div.delta.flat sub]])

(defn- tiles [{:keys [history stats]}]
  (let [{:keys [cycles typical variability period_length]} stats
        current (last (filter :current history))
        ago     (some-> current :elapsed dec)]
    [:div.kpis {:replicant/key "cyc-tiles"}
     (tile "usual" (t "Usual cycle") (when (pos? cycles) typical) (t "days")
           (if (pos? cycles) (t "from your last {0} cycles" cycles) (t "needs two periods")))
     (tile "varies" (t "Varies by") (when (>= cycles 2) variability) (t "days")
           (if (>= cycles 2) (spread-word variability) "—"))
     (tile "period" (t "Period lasts") period_length (t "days")
           (if period_length (t "typically") (t "log the flow days")))
     (tile "last" (t "Last period") (some-> current :start ui/date-short) nil
           (cond (nil? ago) "—" (zero? ago) (t "started today") :else (t "{0} days ago" ago)))]))

(defn- verdict
  "One sentence on the finished cycles: how long they were and whether that is steady."
  [history]
  (let [ls (->> (finished history) (take-last 6) (map :days))
        n  (count ls)]
    (cond
      (zero? n) (t "Log the first day of each period and your cycles show up here.")
      (= 1 n)   (t "Your last cycle lasted {0} days." (first ls))
      :else     (let [lo (apply min ls) hi (apply max ls)]
                  (t "Your last {0} cycles lasted {1} to {2} days: {3}." n lo hi (spread-word (- hi lo)))))))

;; ---------------------------------------------------------------------------
;; Shared day axis

(defn- scale-of [history symptom-map]
  (apply max 35 (concat (map :days history)
                        (mapcat (comp keys :days) (:rows symptom-map)))))

(defn- axis
  "The day numbers above a grid of `scale` day columns."
  [scale]
  [:div.cyc-axis {:replicant/key "axis"}
   (for [d (range 7 (inc scale) 7)]
     [:span {:replicant/key d :style {:left (str (* 100.0 (/ (- d 0.5) scale)) "%")}} d])])

(defn- grid
  "A label column, `scale` day columns and a last column; `rows` are the grid's children."
  [scale class & children]
  [:div.cyc-grid {:class class}
   [:span.cyc-label {:replicant/key "axis-label"} (t "Day of the cycle")]
   (axis scale)
   [:span {:replicant/key "axis-tail"}]
   children])

(defn- key-row [items]
  [:div.cyc-key
   (for [[cls label] items]
     [:span {:replicant/key label} [:i {:class cls}] label])])

;; ---------------------------------------------------------------------------
;; 1. How long are your cycles

(defn- length-chart
  "One column per cycle (oldest first), as tall as the cycle was long; amber when outside the usual 21 to 35 days,
  the running cycle outlined. The usual range is shaded and your own usual length drawn as a line."
  [{:keys [history stats]}]
  (if (empty? history)
    [:div.no-data (t "Log at least two period starts to see your cycles.")]
    (let [rows (vec history)]
      (c/echart
       {:key "cyc-len-chart" :class "h180"
        :option-fn
        (fn []
          (let [usual? (fn [d] (<= usual-from d usual-to))
                point  (fn [{:keys [days current elapsed]}]
                         (if current
                           {:value elapsed :itemStyle {:color "transparent" :borderColor (c/css-var "--text-3")
                                                       :borderType "dashed" :borderWidth 1.5}}
                           {:value days :itemStyle {:color (c/css-var (if (usual? days) "--red" "--amber"))
                                                    :borderRadius [4 4 0 0]}}))]
            (-> (c/base {:x-type "category" :y-min 0 :y-max (apply max 40 (map :days rows)) :grid-left 30
                         :tooltip-fmt
                         (fn [params]
                           (let [r (nth rows (.-dataIndex (first params)))]
                             (c/tooltip-rows (ui/date-short (:start r))
                                             [{:label (if (:current r) (t "day {0} so far" (:elapsed r)) (t "Cycle"))
                                               :value (t "{0} days" (if (:current r) (:elapsed r) (:days r)))
                                               :color (c/css-var "--red")}])))})
                (update :xAxis merge {:data (mapv #(ui/date-short (:start %)) rows)
                                      :axisLabel {:hideOverlap true :fontSize 11 :color (c/css-var "--text-3")}})
                (assoc :series
                       [{:type "bar" :barMaxWidth 22 :data (mapv point rows)
                         :markArea {:silent true :itemStyle {:color (c/css-var "--blue") :opacity 0.12}
                                    :data [[{:yAxis usual-from} {:yAxis usual-to}]]}
                         :markLine (when (pos? (:cycles stats))
                                     {:silent true :symbol "none" :label {:show false}
                                      :lineStyle {:type "dashed" :color (c/css-var "--text-3") :width 1.5}
                                      :data [{:yAxis (:typical stats)}]})}]))))}))))

;; ---------------------------------------------------------------------------
;; 2. How do you feel through your cycle

(defn- heat-row [scale cycles {:keys [symptom total days]}]
  (let [label (t (get cycle/symptom-labels symptom symptom))]
    (list
     [:span.cyc-label {:replicant/key (str "l" symptom)} label]
     [:div.cyc-cells {:replicant/key (str "c" symptom) :style (cols scale)}
      (for [d (range 1 (inc scale))
            :let [{n :cycles worst :max} (get days d)]]
        [:span.cc {:replicant/key d
                   :style (when n {:background (str "color-mix(in srgb, var(--amber) "
                                                    (+ 22 (* 70 (/ n (max 1 cycles)))) "%, transparent)")})
                   :title (when n (str label " · " (t "day {0}" d) ": "
                                       (t "in {0} of {1} cycles" n cycles) " ("
                                       (t "worst") ": " (cycle/severity-label worst) ")"))}])]
     [:span.cyc-tail {:replicant/key (str "t" symptom)} (t "{0} days" total)])))

(defn- symptom-chart [{:keys [history stats symptom_map]}]
  (let [{:keys [cycles rows]} symptom_map]
    (if (empty? rows)
      [:div.no-data (t "Tap symptoms on the Today card for a few days and your pattern appears here.")]
      (let [scale  (scale-of history symptom_map)
            period (:period_length stats)]
        [:div
         (key-row [["amber" (t "Logged in some of your cycles")] ["amber strong" (t "Logged in all of them")]
                   ["red" (t "Your usual period")]])
         (grid scale "heat"
               (when period
                 (list
                  [:span.cyc-label {:replicant/key "pl"} (t "Your period")]
                  [:div.cyc-cells {:replicant/key "pc" :style (cols scale)}
                   (for [d (range 1 (inc scale))]
                     [:span.cc {:replicant/key d :class (when (<= d period) "b")}])]
                  [:span.cyc-tail {:replicant/key "pt"} (t "{0} days" period)]))
               (for [row (take 6 rows)] (heat-row scale cycles row)))
         [:div.note (t "Only days you logged count: a pale square can also mean you didn't log that day.")]]))))

;; ---------------------------------------------------------------------------
;; 3. How your body changes across the cycle

(defn- diverging
  "A bar left or right of a centre line for `v`, `better?` colours it teal, else amber."
  [v unit scale better?]
  [:div.dv
   (if (nil? v)
     [:span.dv-none "—"]
     (let [w   (str (min 100 (* 100 (/ (js/Math.abs v) scale))) "%")
           cls (if (better? v) "good" "bad")]
       (list
        [:div.dv-half.neg {:replicant/key "n"} (when (neg? v) [:i {:class cls :style {:width w}}])]
        [:div.dv-half.pos {:replicant/key "p"} (when (pos? v) [:i {:class cls :style {:width w}}])]
        [:span.dv-num {:replicant/key "v"} (str (ui/signed v) " " unit)])))])

(defn- sentence [phases]
  (let [with   (fn [k] (filter #(some? (get % k)) phases))
        low    (first (sort-by :hrv_delta (with :hrv_delta)))
        high   (last (sort-by :rhr_delta (with :rhr_delta)))
        low?   (and low (<= (:hrv_delta low) -2))
        high?  (and high (>= (:rhr_delta high) 1))
        name-of #(t (get cycle/phase-plain (:phase %) (:phase %)))]
    (if-not (or low? high?)
      (t "No clear pattern yet.")
      (->> [(when low? (t "Lowest HRV: {0} ({1} ms below your average)."
                          (name-of low) (js/Math.abs (:hrv_delta low))))
            (when high? (t "Highest resting heart rate: {0} ({1} bpm above your average)."
                           (name-of high) (:rhr_delta high)))
            (when (and low? (#{"luteal" "late-luteal"} (:phase low)))
              (t "A dip like this is common, and the coach doesn't mistake it for fatigue."))]
           (remove nil?)
           (interpose " ")
           (apply str)))))

(defn- body-chart [{:keys [patterns phase_days]}]
  (let [{:keys [cycles phases]} patterns
        range-of (fn [p] (some #(when (= (:phase %) p) [(:from %) (:to %)]) phase_days))]
    (if (or (zero? cycles) (empty? phases))
      [:div.no-data (t "Log at least two period starts to see your pattern.")]
      [:div
       [:p.cyc-sentence (sentence phases)]
       (key-row [["good" (t "better recovered")] ["bad" (t "less recovered")]])
       [:div.cyc-body
        [:span.cyc-head {:replicant/key "h0"}]
        [:span.cyc-head {:replicant/key "h1"} (t "HRV") [:small (t "higher is better")]]
        [:span.cyc-head {:replicant/key "h2"} (t "Resting heart rate") [:small (t "lower is better")]]
        (for [{:keys [phase hrv_delta rhr_delta]} phases
              :let [[from to] (range-of phase)]]
          (list
           [:div.cyc-phase {:replicant/key (str "p" phase)}
            [:i {:style {:background (str "var(" (get cycle/phase-colors phase "--line") ")")}}]
            [:span (t (get cycle/phase-plain phase phase))
             (when from [:small (t "days {0} to {1}" from to)])]]
           [:div {:replicant/key (str "a" phase)} (diverging hrv_delta "ms" 10 pos?)]
           [:div {:replicant/key (str "b" phase)} (diverging rhr_delta "bpm" 5 neg?)]))]
       [:div.note (t "Compared with your own average, over your last {0} cycles. Each phase is only a handful of days, so take it as a hint. The coach uses it from three cycles." cycles)]])))

;; ---------------------------------------------------------------------------

(defn section [{:keys [history] :as c}]
  (list
   [:div.section-head {:replicant/key "cyc-head" :id "tr-cycle"}
    [:h2.section-title (t "Cycle")]]
   (tiles c)
   [:p.cyc-sentence {:replicant/key "cyc-verdict"} (verdict history)]
   [:div.chart-grid {:replicant/key "cyc"}
    (chart-card {:key "cycle-len" :info "cycle" :title (t "How long are your cycles?")
                 :text
                 (t "Each column is one cycle. The shaded band is the usual 21 to 35 days, the dashed line your own usual.")
                 :body (length-chart c)})
    (chart-card {:key "cycle-body" :info "cycle" :title (t "How your body changes across your cycle")
                 :text (t "Your HRV and resting heart rate in each phase, from your wellness data.")
                 :body (body-chart c)})
    (chart-card {:key "cycle-feel" :info "cycle" :wide? true :title (t "How do you feel through your cycle?")
                 :text (t "Your logged symptoms by day of the cycle. Day 1 is the first day of a period.")
                 :body (symptom-chart c)})]))
