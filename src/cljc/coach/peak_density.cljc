(ns coach.peak-density
  "Pure. How many peaks the map shows at a zoom: the most prominent first, like a printed map.")

(def steps
  "[zoom prominence], by zoom: from this zoom on, unbagged peaks of at least this prominence show."
  [[0 500] [6 300] [7 150] [8 100] [9 50] [10 0]])

(defn min-prominence
  "The smallest prominence an unbagged peak needs to be drawn at `zoom`."
  [zoom]
  (or (some (fn [[z m]] (when (>= zoom z) m)) (reverse steps)) 500))

(def listed-zoom "Peaks on a list show from this zoom on, whatever their prominence." 8)

(defn shown?
  "Whether a peak is drawn at `zoom`: bagged ones always, listed ones from `listed-zoom`, the rest by
  prominence. The athlete's own minimum has been applied by the server."
  [bagged? listed? prominence zoom]
  (boolean (or bagged?
               (and listed? (>= zoom listed-zoom))
               (>= (or prominence 0) (min-prominence zoom)))))

(defn tiers
  "The steps as bands for layers that can't compute with the zoom: [{:zoom z :min m :max M}] where a
  peak with `min` <= prominence < `max` (nil: no limit) is drawn from `zoom` on. The bands don't overlap."
  []
  (mapv (fn [[z m] above] {:zoom z :min m :max above})
        steps
        (cons nil (map second steps))))
