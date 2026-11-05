(ns coach.views.dashboard.common
  "Small helpers the start page's sections share: value tones, dates, chart cards
  and chart bases."
  (:require [clojure.string :as str]
            [coach.charts :as c]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]))

(defn dash [x] (if (or (nil? x) (and (number? x) (js/isNaN x))) "—" x))

(defn tone
  "Readiness level -> :good / :warn / :bad."
  [level]
  (case level ("HIGH" "PRIME") :good "MODERATE" :warn ("LOW" "POOR") :bad nil))

(def tone-var {:good "--green" :warn "--amber" :bad "--red"})

(defn pct-tone [p] (cond (nil? p) nil (< p 34) :bad (< p 67) :warn :else :good))

(defn day-label
  "\"Sat 31 Oct\" (localised)."
  [iso]
  (when iso
    (let [d (ui/parse-date iso)] (str (ui/weekdays (.getDay d)) " " (ui/day-month d)))))

(defn axis-date [iso] (when iso (ui/day-month (ui/parse-date iso))))

(defn lower [s] (some-> s str/lower-case))

(defn note [text] [:div.note text])

(defn empty-line [] [:div.no-data (t "No data yet")])

(defn chart-card
  "A recovery/training chart card: title, one-line note, then the body."
  [{:keys [key title text wide? body info]}]
  [:section.card.chart-card {:replicant/key key :class (when wide? "wide")}
   [:div.card-title title (ui/info info)]
   (when text (note text))
   body])

(defn off-grid-ends
  "Hides the y axis's min/max labels: padded ranges end between ticks."
  [option]
  (update-in option [:yAxis :axisLabel] assoc :showMinLabel false :showMaxLabel false))

(defn cat-base
  "c/base on a category x axis of `dates` (labelled \"31 Oct\")."
  [dates opts]
  (-> (c/base (merge {:x-type "category"} opts))
      (update :xAxis merge {:data (vec dates)
                            :axisLabel (merge (get-in (c/base {}) [:xAxis :axisLabel])
                                              {:formatter (fn [v] (axis-date v)) :hideOverlap true})})))

(defn rows-tip
  "Axis tooltip from `rows` by data index: (f row) -> [heading [{:label :value :color}]]."
  [rows f]
  (fn [params]
    (when-let [r (nth rows (.-dataIndex (first params)) nil)]
      (let [[heading lines] (f r)] (c/tooltip-rows heading lines)))))

(defn padded-range [vs]
  (let [lo (apply min vs) hi (apply max vs) pad (max 1 (* 0.2 (- hi lo)))]
    [(js/Math.floor (- lo pad)) (js/Math.ceil (+ hi pad))]))

(defn fit-labels
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
