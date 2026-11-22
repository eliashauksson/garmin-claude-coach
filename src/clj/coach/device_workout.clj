(ns coach.device-workout
  "Pure. A planned session (coach.workout blocks) as the structured workout
  Garmin Connect and COROS accept, and whether a session may be sent at all.

  Garmin: the DTO of garmin_mcp's upload_workout (heart rate and watts as
  ranges, pace as m/s). COROS: the course of createScheduledWorkout (time or
  free sections, intensity as an absolute range)."
  (:require [clojure.string :as str]
            [coach.season :as season]
            [coach.workout :as workout]))

(def coros-max-days
  "COROS only takes workouts from today to this many days ahead."
  90)

(defn- n [x] (when x (Math/round (double x))))

(defn- repeats [{:keys [repeat]}] (max 1 (or repeat 1)))

(defn- minutes-s [{:keys [duration_min]}] (when (and duration_min (pos? duration_min)) (* 60 duration_min)))

(defn- meters [{:keys [distance_km]}] (when (and distance_km (pos? distance_km)) (n (* 1000 distance_km))))

(defn- blocks-or-default
  "The session's blocks, or one open step with its total time (else distance)."
  [{:keys [blocks] :as w}]
  (if (seq blocks)
    blocks
    [{:label (:title w) :repeat 1
      :steps [(cond-> {:kind "steady" :target "open"}
                (minutes-s w) (assoc :duration_s (minutes-s w))
                (and (not (minutes-s w)) (meters w)) (assoc :distance_m (meters w)))]}]))

;; ---------------------------------------------------------------------------
;; Who may be sent where

(defn sendable
  "nil when `w` ({:status :date :kind}; kind :run :ride :hike :strength from
  coach.plan-rules) can go to `service` (:garmin or :coros) on `today`, else
  why not: :not-planned, :past, :unsupported, :too-far."
  [{:keys [status date kind]} service today]
  (cond
    (not= "planned" status)                                   :not-planned
    (neg? (compare date today))                               :past
    (nil? kind)                                               :unsupported
    (and (= :coros service) (= :strength kind))               :unsupported
    (and (= :coros service) (pos? (compare date (season/add-days today coros-max-days)))) :too-far))

;; ---------------------------------------------------------------------------
;; Garmin

(def ^:private garmin-sports
  {:run      {:sportTypeId 1 :sportTypeKey "running"}
   :ride     {:sportTypeId 2 :sportTypeKey "cycling"}
   :hike     {:sportTypeId 12 :sportTypeKey "walking"}
   :strength {:sportTypeId 5 :sportTypeKey "strength_training"}})

(def ^:private garmin-step-types
  {"warmup"   {:stepTypeId 1 :stepTypeKey "warmup"}
   "cooldown" {:stepTypeId 2 :stepTypeKey "cooldown"}
   "recovery" {:stepTypeId 4 :stepTypeKey "recovery"}})

(def ^:private garmin-interval {:stepTypeId 3 :stepTypeKey "interval"})

(defn- garmin-target
  "The target fields of a step, or the no-target ones. Pace is m/s (a faster
  pace is the higher number), and a ride has no pace."
  [kind {:keys [target] :as step}]
  (let [[lo hi] (workout/bounds step)
        none    {:targetType {:workoutTargetTypeId 1 :workoutTargetTypeKey "no.target"}}]
    (cond
      (nil? lo)                                none
      (= "hr" target)                          {:targetType {:workoutTargetTypeId 4
                                                             :workoutTargetTypeKey "heart.rate.zone"}
                                                :targetValueOne (double lo) :targetValueTwo (double hi)}
      (= "power" target)                       {:targetType {:workoutTargetTypeId 6
                                                             :workoutTargetTypeKey "power.between"}
                                                :targetValueOne (double lo) :targetValueTwo (double hi)}
      (and (= "pace" target) (not= :ride kind)) {:targetType {:workoutTargetTypeId 6
                                                              :workoutTargetTypeKey "pace.zone"}
                                                 :targetValueOne (/ 1000.0 hi) :targetValueTwo (/ 1000.0 lo)}
      :else                                    none)))

(defn- garmin-end [{:keys [duration_s distance_m]}]
  (cond
    (and duration_s (pos? duration_s)) [{:conditionTypeId 2 :conditionTypeKey "time"} (double duration_s)]
    (and distance_m (pos? distance_m)) [{:conditionTypeId 3 :conditionTypeKey "distance"} (double distance_m)]
    :else                              [{:conditionTypeId 1 :conditionTypeKey "lap.button"} nil]))

(defn- garmin-step [kind order step]
  (let [[cond-map value] (garmin-end step)]
    (merge {:type "ExecutableStepDTO" :stepOrder order
            :stepType (get garmin-step-types (:kind step) garmin-interval)
            :endCondition cond-map}
           (when value {:endConditionValue value})
           (garmin-target kind step)
           (when-not (str/blank? (:note step)) {:description (:note step)}))))

(defn- garmin-steps
  "All blocks as steps, numbered depth-first in one counter (a repeat group
  takes the next number, its steps continue after it)."
  [kind blocks]
  (let [order (atom 0)
        next! #(swap! order inc)]
    (vec (mapcat (fn [{:keys [steps] :as b}]
                   (if (> (repeats b) 1)
                     (let [o (next!)]
                       [{:type "RepeatGroupDTO" :stepOrder o
                         :stepType {:stepTypeId 6 :stepTypeKey "repeat"}
                         :numberOfIterations (repeats b)
                         :endCondition {:conditionTypeId 7 :conditionTypeKey "iterations"}
                         :endConditionValue (double (repeats b))
                         :workoutSteps (mapv #(garmin-step kind (next!) %) steps)}])
                     (mapv #(garmin-step kind (next!) %) steps)))
                 blocks))))

(defn garmin-workout
  "The upload_workout argument for planned workout `w` ({:title :goal :kind :blocks :duration_min :distance_km})."
  [{:keys [title goal kind] :as w}]
  (let [sport (get garmin-sports kind (:run garmin-sports))]
    (cond-> {:workoutName (or (not-empty title) "Workout")
             :sportType   sport
             :workoutSegments [{:segmentOrder 1 :sportType sport
                                :workoutSteps (garmin-steps kind (blocks-or-default w))}]}
      (not (str/blank? goal)) (assoc :description goal))))

;; ---------------------------------------------------------------------------
;; COROS

(def ^:private coros-sports {:run 1 :ride 2 :hike 5})

(def ^:private coros-section-types
  {"warmup" 1 "cooldown" 4 "recovery" 3})

(defn- clamp [lo hi x] (max lo (min hi x)))

(defn- coros-intensity
  "Intensity fields (an absolute range) for a step, or nothing. No pace on a
  ride, no zones/percentages ever."
  [sport {:keys [target] :as step}]
  (let [[lo hi] (workout/bounds step)
        [a b]   (sort [(n lo) (n hi)])]
    (cond
      (nil? lo)                                {}
      (= "hr" target)                          {:intensityType 1 :intensityValueStart (clamp 30 240 a)
                                                :intensityValueEnd (clamp 30 240 b)}
      (= "power" target)                       {:intensityType 4 :intensityValueStart (clamp 10 2000 a)
                                                :intensityValueEnd (clamp 10 2000 b)}
      (and (= "pace" target) (not= 2 sport))   {:intensityType 2 :intensityValueStart (clamp 120 1499 a)
                                                :intensityValueEnd (clamp 120 1499 b)}
      :else                                    {})))

(defn- coros-target
  "Time when the step has a duration, distance when it has one, else free
  mode. Trail running (hike) takes no time target: free."
  [sport {:keys [duration_s distance_m]}]
  (cond
    (and (not= 5 sport) duration_s (pos? duration_s)) {:targetType 2 :targetValue (n duration_s)}
    (and distance_m (pos? distance_m))                {:targetType 1 :targetValue (n distance_m)}
    :else                                             {:targetType 4}))

(defn- coros-section [sport in-set? step]
  (let [t (get coros-section-types (:kind step) 2)]
    (merge {:sectionType (if (and in-set? (#{1 4} t)) 2 t)}
           (coros-target sport step)
           (coros-intensity sport step))))

(defn- coros-sections [sport blocks]
  (vec (mapcat (fn [{:keys [steps] :as b}]
                 (let [k (repeats b)]
                   (if (> k 1)
                     (let [sets (mapv #(coros-section sport true %) steps)]
                       (for [r (concat (repeat (quot k 20) 20) (when (pos? (rem k 20)) [(rem k 20)]))]
                         {:intervalGroup true :repeats r :sets sets}))
                     (mapv #(coros-section sport false %) steps))))
               blocks)))

(defn- clip [s k] (if (> (count s) k) (subs s 0 k) s))

(defn coros-course
  "The `course` of createScheduledWorkout for planned workout `w`, or nil
  when COROS can't take that kind. `fallback` names the session when it has
  no goal or description."
  [{:keys [title goal description kind] :as w} fallback]
  (when-let [sport (coros-sports kind)]
    (let [text (or (some #(when-not (str/blank? %) %) [goal description fallback title]) "Workout")
          text (if (and (= 5 sport) (minutes-s w))
                 (str text " (" (quot (minutes-s w) 60) " min)")
                 text)]
      {:sportType sport
       :courseName (clip (or (not-empty title) "Workout") 100)
       :courseDescription text
       :sections (coros-sections sport (blocks-or-default w))})))

(defn coros-date
  "yyyyMMdd of an ISO date."
  [iso]
  (str/replace iso "-" ""))
