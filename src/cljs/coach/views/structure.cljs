(ns coach.views.structure
  "A planned session's structure (coach.workout): the block profile, the
  list of steps with their targets, and how a recorded activity compares."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]
            [coach.workout :as w]))

(def kind-labels {"warmup" (tk "Warm-up") "steady" (tk "Steady") "work" (tk "Work")
                  "recovery" (tk "Recovery") "cooldown" (tk "Cool-down")})

(defn kind-label [k] (t (get kind-labels k k)))

(def target-colors {"hr" "--red" "pace" "--blue" "power" "--amber"})

(defn dur
  "A step's time: \"15 min\", \"45 s\", \"1:30 min\"."
  [s]
  (cond (nil? s)        nil
        (< s 60)        (str s " s")
        (zero? (mod s 60)) (str (quot s 60) " min")
        :else           (str (quot s 60) ":" (.padStart (str (mod s 60)) 2 "0") " min")))

(defn value-str
  "A target value as shown: bpm, W or min/km."
  [target v]
  (when (some? v)
    (case target
      "pace" (str (w/pace-str v) " /km")
      "hr"   (str (js/Math.round v) " bpm")
      "power" (str (js/Math.round v) " W")
      (str v))))

(defn target-str
  "A step's target as written by the coach: \"125–140 bpm\", \"4:20–4:30 /km\"."
  [{:keys [target from to]}]
  (when (and target (not= "open" target))
    (let [vs (->> [from to] (remove str/blank?) distinct
                  (sort-by #(w/parse-value target %)))]
      (when (seq vs)
        (str (str/join "–" vs) (case target "pace" " /km" "hr" " bpm" "power" " W" ""))))))

(defn profile
  "The session as touching bars: width by time, height by kind."
  [blocks & [{:keys [height]}]]
  (let [segs  (w/expand blocks)
        total (max 1 (w/total-s blocks))]
    (when (seq segs)
      [:div.wk-profile {:role "img" :style (when height {:height height})
                        :aria-label (str/join ", "
                                              (for [s segs]
                                                (str (kind-label (:kind s)) " " (dur (- (:end s) (:start s))))))}
       (for [[i s] (map-indexed vector segs)]
         [:div {:replicant/key i :class (:kind s)
                :title (str/join " · "
                                 (remove str/blank?
                                         [(kind-label (:kind s)) (dur (- (:end s) (:start s))) (target-str s)]))
                :style {:flex (str (/ (- (:end s) (:start s)) total) " 1 0")}}])])))

(defn- step-line [s]
  (str/join " · " (remove str/blank? [(dur (:duration_s s)) (target-str s)])))

(defn block-list
  "Each block with its steps; repeated blocks as \"3 × label\"."
  [blocks]
  [:div.wk-blocks
   (for [[i {:keys [label repeat steps]}] (map-indexed vector blocks)
         :let [n (max 1 (or repeat 1))]]
     [:div.wk-block {:replicant/key i}
      (if (and (= 1 n) (= 1 (count steps)))
        (let [s (first steps)]
          [:div [:span.wk-dot {:class (:kind s)}] [:b label] [:span.sub (str " · " (step-line s))]
           (when-not (str/blank? (:note s)) [:div.sub.wk-note (:note s)])])
        (list
         [:div {:replicant/key "h"} [:span.wk-dot {:class (:kind (first (filter #(= "work" (:kind %)) steps)) "work")}]
          [:b (if (> n 1) (str n " × " label) label)]]
         (for [[j s] (map-indexed vector steps)]
           [:div.wk-step {:replicant/key j}
            [:span.wk-dot {:class (:kind s)}] (kind-label (:kind s)) [:span.sub (str " · " (step-line s))]
            (when-not (str/blank? (:note s)) [:div.sub.wk-note (:note s)])])))])])

(defn verdict-text
  "In words, how the actual compares: on target, faster/slower, above/below."
  [{:keys [target verdict]}]
  (case verdict
    :in   (t "on target")
    :low  (if (= "pace" target) (t "faster") (t "below"))
    :high (if (= "pace" target) (t "slower") (t "above"))
    nil))

(defn verdict-pill [seg]
  (when-let [txt (verdict-text seg)]
    (ui/pill (if (= :in (:verdict seg)) "--green" "--amber") txt)))

(defn clock
  "Seconds from the start as h:mm or m:ss."
  [s]
  (let [s (js/Math.round s)]
    (if (>= s 3600)
      (str (quot s 3600) ":" (.padStart (str (quot (mod s 3600) 60)) 2 "0") ":" (.padStart (str (mod s 60)) 2 "0"))
      (str (quot s 60) ":" (.padStart (str (mod s 60)) 2 "0")))))
