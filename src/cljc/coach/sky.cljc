(ns coach.sky
  "Pure. The night sky behind the 3D globe (coach.heat): where a star is seen from a camera looking at
  the Earth. Stars are at infinity, so only the camera's orientation matters. Frames: equatorial (of
  date, x to the vernal equinox, z to the north celestial pole), Earth-fixed (x to Greenwich, as MapLibre's
  globe) and galactic (for the Milky Way image). Vectors are [x y z]; angles in degrees unless named -rad.
  Formulas: Meeus, Astronomical Algorithms (2nd ed.), ch. 12 (sidereal time), 21 (precession),
  25 (sun, ~0.01°) and 47 (moon, its largest terms: ~0.02°).")

(def ^:private rad (/ Math/PI 180))

(defn julian-day
  "Julian day of a time in milliseconds since 1970 (UTC)."
  [ms]
  (+ (/ ms 86400000.0) 2440587.5))

(defn gmst
  "Greenwich mean sidereal time in degrees [0, 360) at Julian day `jd` (Meeus 12.4)."
  [jd]
  (let [d (- jd 2451545.0)
        t (/ d 36525)]
    (mod (+ 280.46061837 (* 360.98564736629 d) (* 0.000387933 t t) (- (/ (* t t t) 38710000))) 360)))

(defn precess
  "J2000 right ascension/declination [ra dec] precessed to Julian day `jd` (Meeus 21.2–21.4)."
  [[ra dec] jd]
  (let [t   (/ (- jd 2451545.0) 36525)
        sec (fn [a b] (* (/ (+ (* a t) (* b t t)) 3600) rad))
        zeta  (sec 2306.2181 0.30188)
        z     (sec 2306.2181 1.09468)
        theta (sec 2004.3109 -0.42665)
        a0  (* ra rad)
        d0  (* dec rad)
        a   (* (Math/cos d0) (Math/sin (+ a0 zeta)))
        b   (- (* (Math/cos theta) (Math/cos d0) (Math/cos (+ a0 zeta))) (* (Math/sin theta) (Math/sin d0)))
        c   (+ (* (Math/sin theta) (Math/cos d0) (Math/cos (+ a0 zeta))) (* (Math/cos theta) (Math/sin d0)))]
    [(mod (/ (+ (Math/atan2 a b) z) rad) 360) (/ (Math/asin (max -1.0 (min 1.0 c))) rad)]))

(defn unit
  "Unit vector of a direction given as [longitude latitude] (or [ra dec])."
  [[lon lat]]
  (let [l (* lon rad) b (* lat rad)]
    [(* (Math/cos b) (Math/cos l)) (* (Math/cos b) (Math/sin l)) (Math/sin b)]))

(defn- add [[a b c] [x y z]] [(+ a x) (+ b y) (+ c z)])
(defn- scale [[x y z] k] [(* k x) (* k y) (* k z)])
(defn dot [[a b c] [x y z]] (+ (* a x) (* b y) (* c z)))

(defn camera
  "The camera's frame in Earth-fixed coordinates, for a map centred on [lat lng] seen with `bearing`
  (clockwise from north) and `pitch` (0 = straight down): {:right :up :view} unit vectors."
  [lat lng bearing pitch]
  (let [[f l b p] (map #(* % rad) [lat lng bearing pitch])
        up    [(* (Math/cos f) (Math/cos l)) (* (Math/cos f) (Math/sin l)) (Math/sin f)]
        east  [(- (Math/sin l)) (Math/cos l) 0.0]
        north [(- (* (Math/sin f) (Math/cos l))) (- (* (Math/sin f) (Math/sin l))) (Math/cos f)]
        fwd   (add (scale north (Math/cos b)) (scale east (Math/sin b)))]
    {:right (add (scale east (Math/cos b)) (scale north (- (Math/sin b))))
     :up    (add (scale up (Math/sin p)) (scale fwd (Math/cos p)))
     :view  (add (scale up (- (Math/cos p))) (scale fwd (Math/sin p)))}))

(defn to-equatorial
  "An Earth-fixed vector in the equatorial frame of date, at sidereal time `gmst-deg`."
  [[x y z] gmst-deg]
  (let [g (* gmst-deg rad)]
    [(- (* x (Math/cos g)) (* y (Math/sin g))) (+ (* x (Math/sin g)) (* y (Math/cos g))) z]))

(def ^:private galactic-matrix
  "J2000 equatorial -> galactic (IAU, Hipparcos), rows."
  [[-0.0548755604 -0.8734370902 -0.4838350155]
   [0.4941094279 -0.4448296300 0.7469822445]
   [-0.8676661490 -0.1980763734 0.4559837762]])

(defn to-galactic
  "An equatorial vector in galactic coordinates (precession since J2000 ignored: well under a degree)."
  [v]
  (mapv #(dot % v) galactic-matrix))

(defn panorama-xy
  "Where a galactic direction lies in the Milky Way panorama: [x y] in 0..1, galactic centre in the
  middle, longitude increasing to the left (as seen from Earth)."
  [[x y z]]
  (let [l (Math/atan2 y x)
        b (Math/asin (max -1.0 (min 1.0 (/ z (Math/sqrt (+ (* x x) (* y y) (* z z)))))))]
    [(mod (- 0.5 (/ l (* 2 Math/PI))) 1) (- 0.5 (/ b Math/PI))]))

(defn project
  "Screen position [x y] (px from the centre, y down) of direction `v` for a camera frame (`camera`,
  in the same coordinates as `v`) and focal length `f` px; nil when behind the camera."
  [v {:keys [right up view]} f]
  (let [d (dot v view)]
    (when (pos? d)
      [(* f (/ (dot v right) d)) (- (* f (/ (dot v up) d)))])))

(defn focal
  "Focal length in px for a vertical field of view (degrees) and a screen height (px)."
  [fov-deg height]
  (/ (/ height 2) (Math/tan (/ (* fov-deg rad) 2))))

(def ^:private bv-colours
  [[-0.4 [155 176 255]] [0.0 [202 215 255]] [0.4 [248 247 255]] [0.6 [255 244 234]]
   [1.0 [255 214 168]] [1.5 [255 186 126]] [2.0 [255 166 102]]])

(defn bv->rgb
  "A star's colour from its B-V colour index (hot blue-white to cool orange-red)."
  [bv]
  (let [[[b0 c0] [b1 c1]] (or (first (filter (fn [[[a] [b]]] (<= a bv b)) (partition 2 1 bv-colours)))
                              (if (< bv 0) (take 2 bv-colours) (take-last 2 bv-colours)))
        k (max 0.0 (min 1.0 (/ (- bv b0) (- b1 b0))))]
    (mapv (fn [x y] (Math/round (double (+ x (* k (- y x)))))) c0 c1)))

;; ---------------------------------------------------------------------------
;; Sun and moon

(def delta-t
  "Terrestrial minus universal time, in days (~69 s in the 2020s): the sun and moon formulas run on TT."
  (/ 69.0 86400))

(def earth-radius-km 6371.0)
(def moon-radius-km 1737.4)
(def sun-radius-deg "The sun's apparent radius." 0.2666)

(defn- sin-d [x] (Math/sin (* x rad)))
(defn- cos-d [x] (Math/cos (* x rad)))

(defn- obliquity
  "[true obliquity of the ecliptic, nutation in longitude] in degrees (Meeus 22, low precision)."
  [t]
  (let [omega (- 125.04452 (* 1934.136261 t))
        l     (+ 280.4665 (* 36000.7698 t))
        l'    (+ 218.3165 (* 481267.8813 t))]
    [(+ 23.439291 (* -0.0130042 t) (* 0.00256 (cos-d omega)))
     (/ (+ (* -17.20 (sin-d omega)) (* -1.32 (sin-d (* 2 l))) (* -0.23 (sin-d (* 2 l'))) (* 0.21 (sin-d (* 2 omega))))
        3600)]))

(defn- equatorial
  "Ecliptic longitude/latitude -> [ra dec] for obliquity `eps` (Meeus 13.3, 13.4)."
  [lambda beta eps]
  [(mod (/ (Math/atan2 (- (* (sin-d lambda) (cos-d eps)) (* (Math/tan (* beta rad)) (sin-d eps))) (cos-d lambda))
           rad)
        360)
   (/ (Math/asin (+ (* (sin-d beta) (cos-d eps)) (* (cos-d beta) (sin-d eps) (sin-d lambda)))) rad)])

(defn sun
  "The sun's apparent place at Julian day `jd` (TT): {:ra :dec} in degrees (Meeus 25, ~0.01°)."
  [jd]
  (let [t  (/ (- jd 2451545.0) 36525)
        l0 (+ 280.46646 (* 36000.76983 t) (* 0.0003032 t t))
        m  (+ 357.52911 (* 35999.05029 t) (* -0.0001537 t t))
        c  (+ (* (+ 1.914602 (* -0.004817 t) (* -0.000014 t t)) (sin-d m))
              (* (- 0.019993 (* 0.000101 t)) (sin-d (* 2 m)))
              (* 0.000289 (sin-d (* 3 m))))
        omega (- 125.04 (* 1934.136 t))
        lambda (- (+ l0 c) 0.00569 (* 0.00478 (sin-d omega)))
        eps (+ 23.439291 (* -0.0130042 t) (* 0.00256 (cos-d omega)))
        [ra dec] (equatorial lambda 0 eps)]
    {:ra ra :dec dec}))

(def ^:private moon-lr
  "Meeus table 47.A, largest terms: [D M M' F, longitude (1e-6 deg), distance (1e-3 km)]."
  [[0 0 1 0 6288774 -20905355] [2 0 -1 0 1274027 -3699111] [2 0 0 0 658314 -2955968]
   [0 0 2 0 213618 -569925] [0 1 0 0 -185116 48888] [0 0 0 2 -114332 -3149] [2 0 -2 0 58793 246158]
   [2 -1 -1 0 57066 -152138] [2 0 1 0 53322 -170733] [2 -1 0 0 45758 -204586] [0 1 -1 0 -40923 -129620]
   [1 0 0 0 -34720 108743] [0 1 1 0 -30383 104755] [2 0 0 -2 15327 10321] [0 0 1 2 -12528 0]
   [0 0 1 -2 10980 79661] [4 0 -1 0 10675 -34782] [0 0 3 0 10034 -23210] [4 0 -2 0 8548 -21636]
   [2 1 -1 0 -7888 24208] [2 1 0 0 -6766 30824] [1 0 -1 0 -5163 -8379] [1 1 0 0 4987 -16675]
   [2 -1 1 0 4036 -12831] [2 0 2 0 3994 -10445] [4 0 0 0 3861 -11650] [2 0 -3 0 3665 14403]
   [0 1 -2 0 -2689 -7003] [2 0 -1 2 -2602 0] [2 -1 -2 0 2390 10056] [1 0 1 0 -2348 6322]
   [2 -2 0 0 2236 -9884] [0 2 0 0 -2120 5751] [0 0 2 -2 -2069 -4950] [2 -2 -1 0 1916 0]])

(def ^:private moon-b
  "Meeus table 47.B, largest terms: [D M M' F, latitude (1e-6 deg)]."
  [[0 0 0 1 5128122] [0 0 1 1 280602] [0 0 1 -1 277693] [2 0 0 -1 173237] [2 0 -1 1 55413]
   [2 0 -1 -1 46271] [2 0 0 1 32573] [0 0 2 1 17198] [2 0 1 -1 9266] [0 0 2 -1 8822]
   [2 -1 0 -1 8216] [2 0 -2 -1 4324] [2 0 1 1 4200] [2 1 0 -1 -3359] [2 -1 -1 1 2463]
   [2 -1 0 1 2211] [2 -1 -1 -1 2065] [0 1 -1 -1 -1870] [4 0 -1 -1 1828] [0 1 0 1 -1794]])

(defn moon
  "The moon's apparent geocentric place at Julian day `jd` (TT): {:ra :dec :km} (Meeus 47, largest terms)."
  [jd]
  (let [t  (/ (- jd 2451545.0) 36525)
        t2 (* t t) t3 (* t2 t) t4 (* t3 t)
        l' (+ 218.3164477 (* 481267.88123421 t) (* -0.0015786 t2) (/ t3 538841) (- (/ t4 65194000)))
        d  (+ 297.8501921 (* 445267.1114034 t) (* -0.0018819 t2) (/ t3 545868) (- (/ t4 113065000)))
        m  (+ 357.5291092 (* 35999.0502909 t) (* -0.0001536 t2) (/ t3 24490000))
        m' (+ 134.9633964 (* 477198.8675055 t) (* 0.0087414 t2) (/ t3 69699) (- (/ t4 14712000)))
        f  (+ 93.2720950 (* 483202.0175233 t) (* -0.0036539 t2) (- (/ t3 3526000)) (/ t4 863310000))
        a1 (+ 119.75 (* 131.849 t))
        a2 (+ 53.09 (* 479264.290 t))
        a3 (+ 313.45 (* 481266.484 t))
        e  (- 1 (* 0.002516 t) (* 0.0000074 t2))
        ef (fn [mm] (case (abs mm) 1 e 2 (* e e) 1))
        arg (fn [dd mm mm' ff] (+ (* dd d) (* mm m) (* mm' m') (* ff f)))
        sl (reduce + (* 3958 (sin-d a1)) (concat [(* 1962 (sin-d (- l' f))) (* 318 (sin-d a2))]
                                                 (for [[dd mm mm' ff l] moon-lr]
                                                   (* l (ef mm) (sin-d (arg dd mm mm' ff))))))
        sr (reduce + (for [[dd mm mm' ff _ r] moon-lr] (* r (ef mm) (cos-d (arg dd mm mm' ff)))))
        sb (reduce + (+ (* -2235 (sin-d l')) (* 382 (sin-d a3)) (* 175 (sin-d (- a1 f))) (* 175 (sin-d (+ a1 f)))
                        (* 127 (sin-d (- l' m'))) (* -115 (sin-d (+ l' m'))))
                   (for [[dd mm mm' ff b] moon-b] (* b (ef mm) (sin-d (arg dd mm mm' ff)))))
        [eps dpsi] (obliquity t)
        [ra dec] (equatorial (+ l' (/ sl 1e6) dpsi) (/ sb 1e6) eps)]
    {:ra ra :dec dec :km (+ 385000.56 (/ sr 1000))}))

(defn to-earth-fixed
  "An equatorial (of date) vector in Earth-fixed coordinates, at sidereal time `gmst-deg`."
  [v gmst-deg]
  (to-equatorial v (- gmst-deg)))

(defn camera-position
  "Where MapLibre's globe camera is, in Earth radii (Earth-fixed): it looks at the map's centre from
  `f` px away (its focal length), the globe being worldSize / (2π cos lat) px in radius."
  [{:keys [view]} lat lng zoom f]
  (let [radius-px (/ (* 512 (Math/pow 2 zoom)) (* 2 Math/PI (Math/cos (* lat rad))))
        d         (/ f radius-px)]
    (mapv - (unit [lng lat]) (scale view d))))

(defn moon-phase
  "The moon's phase angle in degrees (0 full, 180 new) seen from `observer` (vectors in km, Earth-fixed,
  `sun-dir` a unit vector: the sun is far enough to be a direction)."
  [moon-km observer-km sun-dir]
  (let [to-obs (mapv - observer-km moon-km)
        n      (Math/sqrt (dot to-obs to-obs))]
    (/ (Math/acos (max -1.0 (min 1.0 (/ (dot to-obs sun-dir) n)))) rad)))

