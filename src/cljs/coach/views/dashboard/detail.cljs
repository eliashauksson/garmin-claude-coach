(ns coach.views.dashboard.detail
  "The card that opens over the start page when a morning card is clicked: the
  metric's history with its 7-day average and normal range (/api/trend)."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.views.dashboard.common :refer [empty-line padded-range]]))

(def ranges [["30" (tk "30 days")] ["90" (tk "90 days")] ["365" (tk "1 year")] ["all" (tk "All")]])

;; One entry per clickable card (its :key in views/dashboard/morning.cljs). Each chart is one wellness metric
;; (coach.analysis/vital-keys); :scale turns the stored value into the shown unit.
(def ^:private vitals
  {"readiness" {:label (tk "Training readiness") :icon :bolt :accent "--green" :tint "--tint-green"
                :info "readiness"
                :charts [{:k :readiness :color "--green"}]}
   "sleep"     {:label (tk "Sleep score") :icon :moon :accent "--purple" :tint "--tint-purple" :info "sleep"
                :charts [{:k :sleep_score :color "--purple"}
                         {:k :sleep_s :color "--purple-2" :title (tk "Time asleep") :unit "h" :dec 1
                          :scale (/ 1 3600)}]}
   "hrv"       {:label (tk "Overnight HRV") :icon :pulse :accent "--fam-swim" :tint "--chip-swim-bg" :info "hrv"
                :charts [{:k :hrv_avg :color "--fam-swim" :unit "ms"}]}
   "rhr"       {:label (tk "Resting heart rate") :icon :heart :accent "--red" :tint "--tint-red" :info "rhr"
                :lower-better? true
                :charts [{:k :rhr :color "--red" :unit "bpm"}]}
   "bb"        {:label (tk "Body Battery now") :icon :battery :accent "--blue" :tint "--tint-blue"
                :info "body-battery"
                :charts [{:k :body_battery_high :color "--blue" :title (tk "Highest Body Battery of the day")}]}})

(defn range-of [route] (get-in route [:params :vrange] "90"))

(defn url
  "The history to fetch for the card `vital` over `range`, nil for no card."
  [vital range]
  (when-let [cfg (vitals vital)]
    (str "/api/trend?metrics=" (str/join "," (map (comp name :k) (:charts cfg)))
         "&from=" (if (= "all" range) "all" (ui/days-ago (js/parseInt range))))))

;; ---------------------------------------------------------------------------

(defn- scaled [rows scale]
  (if (or (nil? scale) (= 1 scale))
    rows
    (mapv (fn [r] (reduce (fn [r k] (update r k #(when (ui/num? %) (* % scale))))
                          r [:value :avg7 :avg28 :base_low :base_high]))
          rows)))

(defn- fmt [v {:keys [dec unit] :or {dec 0}}]
  (when (ui/num? v) (str (ui/fixed v dec) (when (seq unit) (str " " unit)))))

(defn- row-ago
  "The row `n` days before the last one, or the nearest earlier one."
  [rows n]
  (when-let [last-date (:date (peek rows))]
    (let [want (ui/add-days last-date (- n))]
      (last (filter #(and (<= (compare (:date %) want) 0) (ui/num? (:avg28 %))) rows)))))

(defn- kpis [rows opts]
  (let [latest (last (filter #(ui/num? (:value %)) rows))
        now    (peek rows)
        ago    (row-ago rows 28)
        diff   (when (and (ui/num? (:avg28 now)) ago) (- (:avg28 now) (:avg28 ago)))
        {:keys [dec] :or {dec 0}} opts]
    [:div.row {:replicant/key "kpis"}
     (ui/tile {:label (t "Latest") :value (fmt (:value latest) (dissoc opts :unit)) :unit (:unit opts)
               :sub (some-> (:date latest) ui/date-short)})
     (ui/tile {:label (t "7-day average") :value (fmt (:avg7 now) (dissoc opts :unit)) :unit (:unit opts)})
     (ui/tile {:label (t "28-day average") :value (fmt (:avg28 now) (dissoc opts :unit)) :unit (:unit opts)})
     (ui/tile {:label (t "Normal range") :info "vital-history"
               :value (when (and (ui/num? (:base_low now)) (ui/num? (:base_high now)))
                        (str (ui/fixed (:base_low now) dec) "–" (ui/fixed (:base_high now) dec)))
               :unit (:unit opts)})
     (ui/tile {:label (t "vs 4 weeks ago")
               :value (when diff (ui/signed (js/parseFloat (ui/fixed diff dec)))) :unit (:unit opts)})]))

(defn- reading
  "One sentence: where the 7-day average stands against the normal range."
  [rows opts]
  (let [{:keys [avg7 base_low base_high]} (peek rows)
        {:keys [dec] :or {dec 0}} opts]
    (when (and (ui/num? avg7) (ui/num? base_low) (ui/num? base_high))
      (let [a (fmt avg7 opts) lo (ui/fixed base_low dec) hi (ui/fixed base_high dec)]
        (cond (< avg7 base_low)  (t "7-day average {0}, below your normal range ({1} to {2})." a lo hi)
              (> avg7 base_high) (t "7-day average {0}, above your normal range ({1} to {2})." a lo hi)
              :else              (t "7-day average {0}, inside your normal range ({1} to {2})." a lo hi))))))

(defn- open-day! [^js p]
  (let [v (.-value p)
        d (when (array? v) (aget v 0))]
    (when (ui/iso-date? d) (set! (.-hash js/location) (str "#/day/" d)))))

(defn- history-chart [rows {:keys [k color title] :as opts} label]
  (let [by-date (into {} (map (juxt :date identity)) rows)
        vs      (concat (keep :value rows) (keep :base_low rows) (keep :base_high rows))
        banded  (filter #(and (ui/num? (:base_low %)) (ui/num? (:base_high %))) rows)
        [lo hi] (padded-range vs)
        shown   (t (or title label))]
    (c/echart
     {:key (str "vital-" (name k)) :class "h280"
      :on-click open-day!
      :option-fn
      (fn []
        (merge (c/base {:y-min lo :y-max hi
                        :tooltip-fmt
                        (fn [params]
                          (let [r (by-date (aget (.-value (first params)) 0))]
                            (c/tooltip-rows (ui/date-long (:date r))
                                            [{:label shown :value (fmt (:value r) opts) :color (c/css-var color)}
                                             {:label (t "7-day average") :value (fmt (:avg7 r) opts)}
                                             {:label (t "Normal range")
                                              :value (when (and (ui/num? (:base_low r)) (ui/num? (:base_high r)))
                                                       (str (ui/fixed (:base_low r) (or (:dec opts) 0)) "–"
                                                            (fmt (:base_high r) opts)))}])))})
               {:series [{:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0}
                          :data (mapv (juxt :date :base_low) banded)}
                         {:type "line" :stack "band" :symbol "none" :silent true :lineStyle {:opacity 0}
                          :areaStyle {:color (c/css-var color) :opacity 0.14}
                          :data (mapv (fn [r] [(:date r) (- (:base_high r) (:base_low r))]) banded)}
                         (c/line shown color (mapv (juxt :date :value) rows)
                                 :lineStyle {:width 1} :showSymbol true :symbolSize 4 :connectNulls true
                                 :sampling nil)
                         (c/line (t "7-day average") color (mapv (juxt :date :avg7) rows)
                                 :lineStyle {:width 3} :connectNulls true :sampling nil)]}))})))

;; ---------------------------------------------------------------------------

(defn modal
  "The open card for `vital` (a key of `vitals`), nil for an unknown one."
  [{:keys [data errors route]} vital]
  (when-let [{:keys [label icon accent tint info lower-better? charts]} (vitals vital)]
    (let [range (range-of route)
          u     (url vital range)
          d     (get data u)]
      [:div.modal-backdrop {:replicant/key "vital-modal" :on {:click [[:backdrop-close]]}}
       [:div.modal {:role "dialog" :aria-modal "true" :aria-label (t label)}
        [:div.modal-head
         (ui/label-row {:icon icon :accent accent :tint tint :soft? true :label (t label) :info info})
         (ui/seg range ranges [:set-param :vrange])
         [:button.modal-close {:aria-label (t "Close") :on {:click [[:set-param :vital nil]]}} "×"]]
        (when-let [e (get errors u)] [:div.error (t "Could not load: {0}" e)])
        (cond
          (nil? d) (when-not (get errors u) [:div.skel {:style {:height "320px"}}])
          :else
          (for [[i {:keys [k scale] :as ch}] (map-indexed vector charts)
                :let [rows (scaled (get d k) scale)
                      has? (some #(ui/num? (:value %)) rows)]]
            [:div.modal-block {:replicant/key (name k)}
             (when (pos? i) [:h3.modal-sub (t (:title ch))])
             (if-not has?
               (empty-line)
               (list (kpis rows ch)
                     (history-chart rows ch label)
                     (when-let [s (reading rows ch)] [:p.takeaway {:replicant/key "reading"} s])))]))
        [:div.sub {:style {:margin-top "8px"}}
         (t "The band is your normal range: the last 60 days, ±1 standard deviation. Click a day to open it.")
         (when lower-better?
           (str " " (t "Lower is better: a falling trend means you are fitter or better recovered.")))]]])))
