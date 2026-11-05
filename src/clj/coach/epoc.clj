(ns coach.epoc
  "Training load the way Garmin (Firstbeat) defines it: the peak EPOC
  (excess post-exercise oxygen consumption, ml/kg) of a workout. Garmin
  computes it on the watch, so rides recorded elsewhere (Coros) have none;
  this rebuilds it from heart rate, or from power on rides with a power
  meter. Pure; coach.load-model fits it to Garmin's own numbers.

  Science behind it (Firstbeat EPOC white paper, Rusko et al. 2003): EPOC
  hardly builds up below ~30-40 % of VO2 max, builds up continuously above
  ~50 %, the steeper the harder, and falls again when the effort eases. As
  a differential equation, per minute:
    dE/dt = a * max(0, I - i0)^q  -  lam * (1 - I/i1) / (1 + g * A) * E
  solved exactly over each 30 s bin. Load = the peak of E.
  I is heart rate / max heart rate. A is the intensity-hours done so far:
  fatigue slows recovery, so EPOC keeps growing through long efforts even
  at moderate intensity (Borsheim & Bahr 2003: EPOC grows with duration).

  Checked against Garmin (coach.load-model compare; Nov 2026, 714 activities
  2021-26, constants fitted on 4 of 5, checked on the 5th): median error
  ~13 %, rank correlation 0.94; rides 8-13 %. Firstbeat also reads
  beat-to-beat intervals and breathing, which a 1 Hz heart rate doesn't
  have; that is most of what's left. Errors drift by period (2026 runs come
  out low), so the 7-day acute load from our loads alone is ~16 % off;
  with Garmin's loads for its own activities and ours only for the rides
  it never saw, ~4 % on the days such a ride counts.

  Acute load: Garmin's is scale * sum of the daily loads of the last
  `window` days, decayed by e^(-age/tau): rebuilt from Garmin's own
  activity loads it is off by ~6 % (599 days).")

(def defaults
  "Fitted on 714 activities (Nov 2026); coach.load-model refits them."
  {:a 47.18 :i0 0.2905 :q 3.947 :lam 0.01889 :i1 32.2 :g 0.1195})

(def param-keys [:a :i0 :q :lam :i1 :g])

(def acute-defaults
  "Garmin's acute load from its own activity loads (599 days, 2024-26)."
  {:scale 1.343 :tau 6.30 :window 8})

(def bin-s "Streams are averaged into bins of this many seconds." 30)

;; ---------------------------------------------------------------------------
;; Intensity: [dt-minutes intensity] bins

(defn bins
  "{:dt doubles :i doubles} from a stream: samples averaged into `bin-s`
  bins on the stream's own clock. Gaps (pauses, no signal) become bins of
  intensity 0, so EPOC falls through them as it does in the body.
  `intensity-of` maps a sample index to an intensity, or nil. nil without
  any intensity."
  [{:keys [t]} intensity-of]
  (let [t (vec t) n (count t) bin-min (/ bin-s 60.0)]
    (when (>= n 2)
      (loop [i 0 start (double (t 0)) sum 0.0 cnt 0 dts (transient []) is (transient []) any? false]
        (if (= i n)
          (let [dts (cond-> dts (pos? cnt) (conj! bin-min))
                is  (cond-> is (pos? cnt) (conj! (/ sum cnt)))]
            (when (or any? (pos? cnt))
              {:dt (double-array (persistent! dts)) :i (double-array (persistent! is))}))
          (let [ti (double (or (t i) start))]
            (if (>= ti (+ start bin-s))
              (let [skip (long (Math/floor (/ (- ti start bin-s) bin-s)))
                    dts  (conj! dts bin-min)
                    is   (conj! is (if (pos? cnt) (/ sum cnt) 0.0))
                    dts  (if (pos? skip) (conj! dts (* skip bin-min)) dts)
                    is   (if (pos? skip) (conj! is 0.0) is)]
                (recur i (+ start (* bin-s (inc skip))) 0.0 0 dts is (or any? (pos? cnt))))
              (let [v (intensity-of i)]
                (if (number? v)
                  (recur (inc i) start (+ sum (double v)) (inc cnt) dts is any?)
                  (recur (inc i) start sum cnt dts is any?))))))))))

(defn hr-bins
  "Bins from heart rate (I = HR / max HR), or nil without heart rate."
  [{:keys [hr] :as stream} max-hr]
  (when (and (number? max-hr) (pos? max-hr) (some #(and (number? %) (pos? %)) hr))
    (let [hr (vec hr) mx (double max-hr)]
      (bins stream #(let [v (get hr %)] (when (and (number? v) (pos? v)) (/ (double v) mx)))))))

(defn power-bins
  "Bins from power on a ride: I = a + b * watts/FTP, `cal` = [a b] from
  `power-calibration`. nil without power, FTP or calibration. Power reacts
  at once where heart rate lags, and isn't thrown by heat or caffeine."
  [{:keys [power] :as stream} ftp [a b :as cal]]
  (when (and cal (number? ftp) (pos? ftp) (some number? power))
    (let [p (vec power) ftp (double ftp) a (double a) b (double b)]
      (bins stream #(let [v (get p %)] (when (number? v) (max 0.0 (+ a (* b (/ (double v) ftp))))))))))

;; ---------------------------------------------------------------------------
;; EPOC

(defn peak
  "Peak EPOC (= training load) over `bins`; 0.0 without bins."
  ^double [{:keys [a i0 q lam i1 g]} {:keys [dt i]}]
  (if-not dt
    0.0
    (let [^doubles dts dt ^doubles is i n (alength dts)
          a (double a) i0 (double i0) q (double q) lam (double lam) i1 (double i1)
          g (double (or g 0.0))]
      (loop [j 0 e 0.0 top 0.0 acc 0.0]
        (if (= j n)
          top
          (let [dt (aget dts j)
                x  (aget is j)
                up (* a (Math/pow (max 0.0 (- x i0)) q))
                d  (/ (* lam (max 0.0 (- 1.0 (/ x i1)))) (+ 1.0 (* g acc)))
                e  (if (> d 1e-9)
                     (let [ss (/ up d)] (+ ss (* (- e ss) (Math/exp (- (* d dt))))))
                     (+ e (* up dt)))]
            (recur (inc j) e (max top e) (+ acc (/ (* x dt) 60.0)))))))))

;; ---------------------------------------------------------------------------
;; Power calibration

(def hr-lag-bins "Heart rate trails power by about a minute." 2)

(defn power-calibration
  "[a b] such that a + b * watts/FTP matches the heart-rate intensity, by
  least squares over `rides` that have both: [{:hr hr-bins :power power-bins
  made with cal [0 1], i.e. plain watts/FTP}]. Skips each ride's first five
  minutes (heart rate still rising) and coasting bins. nil with too little."
  [rides]
  (let [pts (for [{:keys [hr power]} rides
                  :when (and hr power)
                  :let [^doubles h (:i hr) ^doubles p (:i power)
                        n (min (alength h) (alength p))]
                  j (range 10 (- n hr-lag-bins))
                  :let [x (aget p j) y (aget h (+ j hr-lag-bins))]
                  :when (and (> x 0.2) (> y 0.3))]
              [x y])
        n (count pts)]
    (when (>= n 60)
      (let [mx (/ (reduce + (map first pts)) n)
            my (/ (reduce + (map second pts)) n)
            sxy (reduce + (map (fn [[x y]] (* (- x mx) (- y my))) pts))
            sxx (reduce + (map (fn [[x _]] (let [d (- x mx)] (* d d))) pts))]
        (when (pos? sxx)
          (let [b (/ sxy sxx)] [(- my (* b mx)) b]))))))

(defn activity-load
  "{:load_est :load_method} for one activity, nil without heart rate or
  usable power. On a ride with power and a calibration, each bin takes the
  larger of the heart-rate and the power intensity (\"hr+power\"): power
  catches short surges heart rate is too slow for, heart rate catches drift
  and heat. `th`: {:max_hr :ftp}; `cal`: from `power-calibration`."
  [params cal {:keys [max_hr ftp]} bike? {:keys [hr power] :as stream}]
  (let [hr  (vec hr) pw (vec power)
        hr? (and (number? max_hr) (pos? max_hr) (some #(and (number? %) (pos? %)) hr))
        pw? (and bike? cal (number? ftp) (pos? ftp) (some number? pw))
        hr-i (fn [j] (let [v (get hr j)] (when (and hr? (number? v) (pos? v)) (/ (double v) (double max_hr)))))
        pw-i (fn [j] (let [v (get pw j)]
                       (when (and pw? (number? v))
                         (max 0.0 (+ (double (first cal)) (* (double (second cal)) (/ (double v) (double ftp))))))))
        method (cond (and hr? pw?) "hr+power" pw? "power" hr? "hr")]
    (when method
      (when-let [b (bins stream (fn [j] (let [h (hr-i j) p (pw-i j)]
                                          (cond (and h p) (max h p) :else (or h p)))))]
        {:load_est (peak params b) :load_method method}))))

;; ---------------------------------------------------------------------------
;; Acute load

(defn acute-load
  "Garmin-style acute load on `date` (ISO) from `daily` {date load}."
  [{:keys [scale tau window]} daily date]
  (let [d (java.time.LocalDate/parse date)]
    (* (double scale)
       (reduce + 0.0 (for [age (range 0 (inc (long window)))
                           :let [l (get daily (str (.minusDays d age)))]
                           :when l]
                       (* (double l) (Math/exp (- (/ age (double tau))))))))))

;; ---------------------------------------------------------------------------
;; Fitting and checking against Garmin

(defn- loss
  "Absolute error summed: the big sessions, which make up most of the
  weekly load, count the most (a log error favoured short easy ones)."
  [params rows]
  (reduce + 0.0 (for [{:keys [bins load]} rows] (Math/abs (- (peak params bins) (double load))))))

(defn- ->params [x]
  (let [[a i0 q lam i1 g] (map #(Math/abs (double %)) x)]
    {:a a :i0 (min i0 0.9) :q (max q 0.2) :lam lam :i1 (max i1 0.05) :g g}))

(defn fit
  "Constants that best match Garmin's loads on `rows` [{:bins :load}],
  starting from `params`. `minimise` is a (f x0 iters) -> x minimiser."
  [params rows minimise iters]
  (let [f  #(loss (->params %) rows)
        x0 (mapv params param-keys)]
    (->params (minimise f (minimise f x0 iters) iters))))

(defn- ranks [xs]
  (let [order (sort-by second (map-indexed vector xs))]
    (reduce (fn [m [r [i _]]] (assoc m i (double r))) {} (map-indexed vector order))))

(defn spearman [xs ys]
  (let [n (count xs) rx (ranks xs) ry (ranks ys)
        d2 (reduce + (for [i (range n)] (Math/pow (- (rx i) (ry i)) 2)))]
    (when (> n 2) (- 1.0 (/ (* 6.0 d2) (* n (- (* n n) 1.0)))))))

(defn evaluate
  "How our loads compare with Garmin's on `rows` [{:bins :load}]:
  :median and :p80 of the relative error, :spearman, :bias (median signed)."
  [params rows]
  (let [ours (mapv #(peak params (:bins %)) rows)
        theirs (mapv #(double (:load %)) rows)
        rel  (mapv #(/ (- %1 %2) %2) ours theirs)
        abs  (vec (sort (map #(Math/abs (double %)) rel)))
        n    (count abs)
        at   #(abs (min (dec n) (int (* % n))))]
    (when (pos? n)
      {:n n :median (at 0.5) :p80 (at 0.8)
       :bias ((vec (sort rel)) (quot n 2))
       :spearman (spearman ours theirs)})))
