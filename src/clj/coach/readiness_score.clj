(ns coach.readiness-score
  "Garmin's training readiness, rebuilt from raw data for the mornings Garmin
  has none. Pure: takes the features of one morning (coach.readiness-estimate
  reads them from the DB) and parameters, returns the score.

  Reverse engineered on the athlete's own mornings, in two steps, against the
  six factor percentages Garmin sends with every score (Garmin's manual names
  the six inputs but no weights):
  A. factors -> score: 100 - sum w_i * (1 - f_i/100)^p_i, kept within 1..100
  B. raw data -> each factor (sleep score, HRV vs Garmin's baseline, training
     load, intraday stress, recovery hours per workout).
  `fit` refits both steps from mornings that have Garmin's score, so the
  parameters follow the athlete's data as it grows."
  (:require [clojure.string :as str]))

(def factor-keys
  "The six factors, named like coach.overview/readiness :factors."
  [:sleep :recovery_time :hrv :acwr :sleep_history :stress_history])

(def defaults
  "Found on 83 mornings (13 Aug - 3 Nov 2026); `fit` replaces them."
  {:combine       {:w {:sleep 86.3 :recovery_time 40.1 :hrv 17.5 :acwr 15.8 :sleep_history 58.7 :stress_history 17.3}
                   :p {:sleep 2.44 :recovery_time 0.51 :hrv 0.76 :acwr 0.09 :sleep_history 2.2 :stress_history 1.6}}
   ;; sleep history = a * sleep%(night before last) + b * sleep%(the night before) + c
   :sleep-history [0.69 0.36 -4.0]
   ;; HRV = intercept + slope * min(cap, 7-day HRV average - bottom of Garmin's balanced range)
   :hrv           [71.5 6.7 4.0]
   ;; acute load = scale * sum training_load * e^(-age/tau); load factor = 100 - k * max(0, acute - a0)
   :acute         [1.425 4.5]
   :load          [0.17 175.0]
   ;; stress history = slope * mean awake stress over the 48 h before waking + intercept
   :stress        [-1.64 138.5]
   ;; each workout sets a * load^q hours (at most 100), counting down from its end
   :recovery      [0.37 0.95]
   ;; recovery factor = 100 - a * hours^p
   :recovery-pct  [2.1 0.9]})

(defn- clamp [lo hi x] (max lo (min hi x)))
(defn- pct [x] (clamp 0.0 100.0 (double x)))

;; ---------------------------------------------------------------------------
;; Step B: one factor each

(defn sleep-pct
  "Garmin's sleep factor from the sleep score: fixed piecewise-linear (exact on every observed morning)."
  [s]
  (when (number? s)
    (let [s (double s)]
      (pct (cond (< s 60) (+ 10 (* 0.5 s))
                 (< s 80) (+ 40 (* 1.5 (- s 60)))
                 (< s 90) (+ 70 (* 2.0 (- s 80)))
                 :else s)))))

(defn sleep-history-pct [{[a b c] :sleep-history} s1 s2]
  (when (and (number? s1) (number? s2))
    (pct (+ (* a (sleep-pct s1)) (* b (sleep-pct s2)) c))))

(defn hrv-pct [{[i k cap] :hrv} delta]
  (when (number? delta) (pct (+ i (* k (min cap (double delta)))))))

(defn acute-load
  "`loads`: [[age-days training-load] …] of the activities before waking."
  [{[scale tau] :acute} loads]
  (* scale (reduce + 0.0 (for [[age l] loads] (* l (Math/exp (- (/ age tau))))))))

(defn load-pct [{[k a0] :load} acute]
  (pct (- 100 (* k (max 0.0 (- acute a0))))))

(defn stress-pct [{[k c] :stress} mean-stress]
  (when (number? mean-stress) (pct (+ c (* k mean-stress)))))

(defn recovery-hours
  "Hours still to go: each workout sets its own and counts down from its end;
  they don't add up, the longest remainder wins. `workouts`: [[hours-since-end load] …]."
  [{[a q] :recovery} workouts]
  (reduce max 0.0 (for [[elapsed l] workouts]
                    (- (min 100.0 (* a (Math/pow (max 0.0 l) q))) elapsed))))

(defn recovery-pct [{[a p] :recovery-pct} hours]
  (pct (- 100 (* a (Math/pow (max 0.0 hours) p)))))

(defn components
  "The six factor percentages for one morning (nil where data is missing).
  `f`: {:sleep [s0 s1 s2] (last night first), :hrv_delta, :loads, :workouts, :stress48}."
  [params {[s0 s1 s2] :sleep :keys [hrv_delta loads workouts stress48]}]
  (let [rh (recovery-hours params workouts)]
    {:sleep          (sleep-pct s0)
     :recovery_time  (recovery-pct params rh)
     :hrv            (hrv-pct params hrv_delta)
     :acwr           (load-pct params (acute-load params loads))
     :sleep_history  (sleep-history-pct params s1 s2)
     :stress_history (stress-pct params stress48)
     :recovery_h     rh}))

;; ---------------------------------------------------------------------------
;; Step A: factors -> score

(defn- penalty [{{:keys [w p]} :combine} k f]
  (* (Math/abs (double (w k))) (Math/pow (- 1.0 (/ f 100.0)) (Math/abs (double (p k))))))

(defn combine
  "Score 1..100 from the factor percentages; a missing factor is left out."
  [params factors]
  (clamp 1.0 100.0 (- 100.0 (reduce + 0.0 (for [k factor-keys :let [f (get factors k)] :when f]
                                            (penalty params k f))))))

(defn level
  "Garmin's levels."
  [score]
  (cond (< score 25) "POOR" (< score 50) "LOW" (< score 75) "MODERATE" (< score 95) "HIGH" :else "PRIME"))

(defn estimate
  "The rebuilt readiness for one morning, nil without last night's sleep score.
  :limiting are the (up to two) factors costing the most points."
  [params f]
  (let [c (components params f)]
    (when (:sleep c)
      (let [score (Math/round (combine params c))
            costs (->> factor-keys
                       (keep #(when-let [v (get c %)] [% (penalty params % v)]))
                       (filter #(>= (second %) 3.0))
                       (sort-by (comp - second)))]
        {:score      score
         :level      (level score)
         :factors    (into {} (for [k factor-keys] [k (some-> (get c k) Math/round)]))
         :recovery_h (Math/round (double (:recovery_h c)))
         :partial    (boolean (some #(nil? (get c %)) factor-keys))
         :limiting   (mapv (comp name first) (take 2 costs))
         :estimate   true}))))

;; ---------------------------------------------------------------------------
;; Fitting

(defn- solve
  "Gauss-Jordan on an augmented matrix; nil if singular."
  [m]
  (let [n (count m)]
    (loop [i 0 m (mapv vec m)]
      (if (= i n)
        (mapv #(/ (peek (m %)) (get-in m [% %])) (range n))
        (let [p (apply max-key #(Math/abs (double (get-in m [% i]))) (range i n))
              m (assoc m i (m p) p (m i))
              piv (get-in m [i i])]
          (if (< (Math/abs (double piv)) 1e-12)
            nil
            (recur (inc i)
                   (vec (for [r (range n)]
                          (if (= r i) (m r)
                              (let [f (/ (get-in m [r i]) piv)]
                                (mapv - (m r) (map #(* f %) (m i))))))))))))))

(defn ols
  "Least squares with an intercept: coefficients for `xs` (rows), intercept last."
  [xs ys]
  (let [a (mapv #(conj (vec %) 1.0) xs)
        n (count (first a))]
    (when (> (count a) n)
      (solve (vec (for [i (range n)]
                    (conj (vec (for [j (range n)] (reduce + (map #(* (nth % i) (nth % j)) a))))
                          (reduce + (map #(* (nth %1 i) %2) a ys)))))))))

(defn nelder-mead
  "Minimises `f` from `x0`."
  [f x0 iters]
  (let [n (count x0)
        init (cons x0 (for [i (range n)] (update (vec x0) i #(+ % (* 0.3 (max 1.0 (Math/abs (double %))))))))]
    (loop [it 0 pts (sort-by first (map (juxt f identity) init))]
      (if (= it iters)
        (second (first pts))
        (let [worst (second (last pts))
              keep* (butlast pts)
              cen   (apply mapv (fn [& xs] (/ (reduce + xs) n)) (map second keep*))
              at    (fn [t] (mapv #(+ %1 (* t (- %1 %2))) cen worst))
              xr (at 1.0) fr (f xr)
              [fb] (first pts) [fsw] (last keep*)
              next-pts
              (cond
                (< fr fb) (let [xe (at 2.0) fe (f xe)] (conj (vec keep*) (if (< fe fr) [fe xe] [fr xr])))
                (< fr fsw) (conj (vec keep*) [fr xr])
                :else (let [xc (at -0.5) fc (f xc)]
                        (if (< fc (first (last pts)))
                          (conj (vec keep*) [fc xc])
                          (let [best (second (first pts))]
                            (cons (first pts)
                                  (for [[_ x] (rest pts) :let [y (mapv #(/ (+ %1 %2) 2) best x)]] [(f y) y]))))))]
          (recur (inc it) (sort-by first next-pts)))))))

(defn- mae [pairs] (when (seq pairs) (/ (reduce + (map (fn [[a b]] (Math/abs (double (- a b)))) pairs)) (count pairs))))

(defn- level-n [s] (cond (< s 25) 0 (< s 50) 1 (< s 75) 2 (< s 95) 3 :else 4))

(defn evaluate
  "How the rebuilt score compares with Garmin's on `rows`
  ({:features f :garmin {:score …}})."
  [params rows]
  (let [pairs (for [{:keys [features garmin]} rows
                    :let [e (estimate params features)] :when e]
                [(:score e) (:score garmin)])
        n (count pairs)]
    (when (pos? n)
      {:n          n
       :mae        (mae pairs)
       :same_level (/ (count (filter (fn [[a b]] (= (level-n a) (level-n b))) pairs)) (double n))
       :within_one (/ (count (filter (fn [[a b]] (<= (Math/abs (- (level-n a) (level-n b))) 1)) pairs)) (double n))})))

(defn- grid-best [f xs ys]
  (apply min-key first (for [x xs y ys] [(f x y) x y])))

(defn- frange [from to step]
  (map #(+ from (* % step)) (range (inc (Math/round (/ (double (- to from)) step))))))

(defn fit
  "Refits every step from `rows`: mornings with Garmin's score, as
  {:features f :garmin {:score :factors {k pct} :recovery_h}}. Starts from
  `params`; keeps a step's old values where its data are too few."
  [params rows]
  (let [g      (fn [k r] (get-in r [:garmin :factors k]))
        with   (fn [k pred] (filter #(and (g k %) (pred %)) rows))
        ;; sleep history: two previous nights
        shr    (with :sleep_history #(let [[_ a b] (get-in % [:features :sleep])] (and a b)))
        sh     (ols (map #(let [[_ a b] (get-in % [:features :sleep])] [(sleep-pct a) (sleep-pct b)]) shr)
                    (map #(g :sleep_history %) shr))
        ;; HRV
        cap    (get-in params [:hrv 2])
        hr     (with :hrv #(number? (get-in % [:features :hrv_delta])))
        hv     (ols (map #(vector (min cap (get-in % [:features :hrv_delta]))) hr) (map #(g :hrv %) hr))
        ;; stress
        sr     (with :stress_history #(number? (get-in % [:features :stress48])))
        sv     (ols (map #(vector (get-in % [:features :stress48])) sr) (map #(g :stress_history %) sr))
        ;; load factor on our acute load
        lr     (with :acwr (constantly true))
        acutes (map #(acute-load params (get-in % [:features :loads])) lr)
        [_ k a0] (grid-best (fn [k a0] (mae (map #(vector (- 100 (* k (max 0.0 (- %1 a0)))) (g :acwr %2)) acutes lr)))
                            (frange 0.05 0.4 0.005) (frange 100 260 5))
        ;; recovery: the curve from Garmin's hours, then hours per workout
        rr     (filter #(number? (get-in % [:garmin :recovery_h])) rows)
        [_ ra rp] (grid-best (fn [a p]
                               (mae (map #(vector (- 100 (* a (Math/pow (get-in % [:garmin :recovery_h]) p)))
                                                  (g :recovery_time %))
                                         rr)))
                             (frange 0.5 6.0 0.05) (frange 0.7 1.4 0.02))
        [_ wa
         wq] (grid-best (fn [a q] (mae (map #(vector (recovery-hours {:recovery [a q]} (get-in % [:features :workouts]))
                                                   (get-in % [:garmin :recovery_h])) rr)))
                        (frange 0.05 2.0 0.01) (frange 0.5 1.2 0.05))
        p1 (cond-> (assoc params :load [k a0] :recovery-pct [ra rp] :recovery [wa wq])
             (and sh (>= (count shr) 20)) (assoc :sleep-history sh)
             (and hv (>= (count hr) 20))  (assoc :hrv [(second hv) (first hv) cap])
             (and sv (>= (count sr) 20))  (assoc :stress [(first sv) (second sv)]))
        ;; step A on Garmin's own factors
        full   (filter #(every? (fn [k] (g k %)) factor-keys) rows)
        x0     (vec (concat (map #(get-in p1 [:combine :w %]) factor-keys)
                            (map #(get-in p1 [:combine :p %]) factor-keys)))
        unpack (fn [x] {:w (zipmap factor-keys (subvec x 0 6)) :p (zipmap factor-keys (subvec x 6 12))})
        loss   (fn [x] (let [pp {:combine (unpack x)}]
                         (reduce +
                                 (for [r full
                                       :let [d
                                             (- (combine pp (get-in r [:garmin :factors]))
                                                (get-in r [:garmin :score]))]] (* d d)))))
        x      (if (>= (count full) 20) (nelder-mead loss (nelder-mead loss x0 1500) 1500) x0)]
    (assoc p1 :combine (unpack (vec x)))))

(defn summary
  "One line for logs and the CLI."
  [{:keys [n mae same_level within_one]}]
  (str/join ", " [(str n " mornings") (format "%.1f points off on average" (double mae))
                  (format "same level %.0f%%" (* 100 same_level))
                  (format "within one level %.0f%%" (* 100 within_one))]))
