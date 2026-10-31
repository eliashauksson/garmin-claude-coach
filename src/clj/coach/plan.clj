(ns coach.plan
  "Storage for the training plan: week types, AI proposals and the planned
  workouts the athlete accepted. Plans live only here; nothing goes to Garmin."
  (:require [clojure.data.json :as json]
            [coach.db :as db]
            [coach.plan-rules :as rules]
            [coach.season :as season]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Instant LocalDate)))

(def week-types #{"NORMAL" "LIGHT" "AWAY" "HOLIDAY"})

(defn- now [] (str (Instant/now)))

(defn- exec! [ds sqlvec]
  (jdbc/execute-one! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))

;; ---------------------------------------------------------------------------
;; Weeks

(defn weeks
  "Plan weeks (with season position) for every Monday in [from, to]."
  [ds from to]
  (let [rows (into {} (map (juxt :monday identity))
                   (db/query ds ["SELECT * FROM plan_weeks WHERE monday BETWEEN ? AND ?" (season/monday from) to]))]
    (for [m (take-while #(<= (compare % to) 0) (iterate #(season/add-days % 7) (season/monday from)))]
      (merge (season/week-info m) (select-keys (rows m) [:week_type :note])))))

(defn set-week! [ds monday {:keys [week_type note]}]
  (when (and week_type (not (week-types week_type)))
    (throw (ex-info "Unknown week type" {:week_type week_type})))
  (jdbc/execute! ds ["INSERT INTO plan_weeks (monday, week_type, note, updated_at) VALUES (?, ?, ?, ?)
                      ON CONFLICT(monday) DO UPDATE SET week_type = excluded.week_type,
                        note = excluded.note, updated_at = excluded.updated_at"
                     (season/monday monday) week_type note (now)]))

;; ---------------------------------------------------------------------------
;; Planned workouts

(defn- activity-kind [sport]
  (cond (re-find #"running" (str sport)) :run
        (re-find #"strength" (str sport)) :strength))

(defn with-done
  "Status as seen by the athlete: a planned workout is done when an activity
  of the same kind exists that day (each activity matches one workout), and
  missed when its day has passed without one. A workout marked done by hand
  stays done."
  [workouts activities today]
  (let [pool (atom (group-by (juxt :date #(activity-kind (:sport %))) activities))]
    (mapv (fn [w]
            (let [k [(:date w) (get-in rules/sessions [(:session_id w) :kind])]
                  a (first (@pool k))]
              (cond
                (and a (#{"planned" "done"} (:status w)))
                (do (swap! pool update k rest)
                    (assoc w :status "done" :activity_id (:id a)))

                (and (= "planned" (:status w)) (neg? (compare (:date w) today)))
                (assoc w :status "missed")

                :else w)))
          (sort-by (juxt :date :slot) workouts))))

(defn workouts
  "Planned workouts in [from, to] with done/missed resolved. Cancelled ones
  (removed by an accepted adjustment) are left out."
  [ds from to]
  (with-done (db/query ds ["SELECT * FROM planned_workouts WHERE date BETWEEN ? AND ? AND status <> 'cancelled'
                            ORDER BY date, slot" from to])
    (db/query ds ["SELECT id, date, sport FROM activities WHERE date BETWEEN ? AND ?" from to])
    (str (LocalDate/now))))

(def statuses #{"planned" "skipped" "done" "cancelled"})

(def workout-cols [:date :slot :session_id :title :duration_min :distance_km :intensity :description :reason])

(defn add-workout! [ds w & [proposal-id]]
  (:id (exec! ds (into ["INSERT INTO planned_workouts (date, slot, session_id, title, duration_min, distance_km,
                           intensity, description, reason, proposal_id, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"]
                       (concat (map w workout-cols) [proposal-id (now) (now)])))))

(defn update-workout!
  "Edits a planned workout; :status may be planned/skipped/done/cancelled."
  [ds id changes]
  (when (and (:status changes) (not (statuses (:status changes))))
    (throw (ex-info "Unknown status" {:status (:status changes)})))
  (let [cols (filter #(contains? changes %) (conj workout-cols :status))]
    (when (seq cols)
      (jdbc/execute! ds (into [(str "UPDATE planned_workouts SET "
                                    (clojure.string/join ", " (map #(str (name %) " = ?") cols))
                                    ", updated_at = ? WHERE id = ?")]
                              (concat (map changes cols) [(now) id]))))))

(defn delete-workout! [ds id]
  (jdbc/execute! ds ["DELETE FROM planned_workouts WHERE id = ?" id]))

;; ---------------------------------------------------------------------------
;; Proposals

(defn- parse [row & ks]
  (reduce (fn [r k] (update r k #(some-> % (json/read-str :key-fn keyword)))) row ks))

(defn create-proposal!
  "A week proposal (kind \"week\") or an adjustment of planned sessions up to
  `scope-end` (kind \"adjust\", with the signals that triggered it)."
  ([ds monday model] (create-proposal! ds monday model {}))
  ([ds monday model {:keys [kind scope-end signals] :or {kind "week"}}]
   (:id (exec! ds ["INSERT INTO plan_proposals (monday, status, model, kind, scope_end, signals, created_at, updated_at)
                    VALUES (?, 'running', ?, ?, ?, ?, ?, ?) RETURNING id"
                   monday model kind scope-end (some-> signals json/write-str) (now) (now)]))))

(defn fail-proposal! [ds id error]
  (jdbc/execute! ds ["UPDATE plan_proposals SET status = 'failed', error = ?, updated_at = ? WHERE id = ?"
                     error (now) id]))

(defn finish-proposal!
  "Stores the model's week and its sessions as pending items."
  [ds id {:keys [context response warnings usage]}]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["UPDATE plan_proposals SET status = 'open', context = ?, response = ?, warnings = ?,
                          usage = ?, updated_at = ? WHERE id = ?"
                       (json/write-str context) (json/write-str (dissoc response :workouts))
                       (json/write-str warnings) (json/write-str usage) (now) id])
    (doseq [[i w] (map-indexed vector (or (:changes response) (:workouts response)))]
      (jdbc/execute! tx (into ["INSERT INTO proposal_items (proposal_id, idx, date, slot, session_id, title,
                                  duration_min, distance_km, intensity, description, reason, op, target_id)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"]
                              (concat [id i] (map w workout-cols)
                                      [(or (:op w) "add") (when (pos? (or (:workout_id w) 0)) (:workout_id w))]))))))

(defn proposal [ds id]
  (when-let [p (first (db/query ds ["SELECT id, monday, status, model, kind, scope_end, signals, response, warnings,
                                            error, usage, created_at, updated_at FROM plan_proposals WHERE id = ?" id]))]
    (let [items   (db/query ds ["SELECT * FROM proposal_items WHERE proposal_id = ? ORDER BY date, slot, idx" id])
          targets (into {} (for [t (keep :target_id items)]
                             [t (first (db/query ds ["SELECT * FROM planned_workouts WHERE id = ?" t]))]))]
      (assoc (parse p :response :warnings :usage :signals)
             :items (mapv #(cond-> (parse % :prev) (:target_id %) (assoc :current (targets (:target_id %)))) items)))))

(defn open-proposals
  "Proposals that are generating or still have undecided items."
  [ds]
  (db/query ds ["SELECT p.id, p.kind, p.monday, p.status, p.created_at FROM plan_proposals p
                 WHERE p.status = 'running'
                    OR (p.status = 'open' AND EXISTS (SELECT 1 FROM proposal_items i
                                                      WHERE i.proposal_id = p.id AND i.decision = 'pending'))
                 ORDER BY p.id DESC"]))

(defn latest-proposal [ds monday & [kind]]
  (some->> (db/query ds ["SELECT id FROM plan_proposals WHERE monday = ? AND kind = ? ORDER BY id DESC LIMIT 1"
                         monday (or kind "week")])
           first :id (proposal ds)))

(defn- workout-row [tx id]
  (first (db/query tx ["SELECT * FROM planned_workouts WHERE id = ?" id])))

(defn- undo-item!
  "Reverts an accepted item: an added workout is deleted, a changed or
  removed one gets its previous values back."
  [tx {:keys [op workout_id target_id prev]}]
  (case op
    "add" (when workout_id (delete-workout! tx workout_id))
    (when (and target_id prev)
      (update-workout! tx target_id (select-keys (json/read-str prev :key-fn keyword)
                                                 (conj workout-cols :status))))))

(defn decide-item!
  "Accepts (optionally with edits) or rejects one proposed item.
  op add: creates a planned workout. op change: updates the target workout.
  op remove: cancels the target workout. Previous values are kept so a
  later reject undoes the change."
  [ds proposal-id idx decision edits]
  (jdbc/with-transaction [tx ds]
    (let [item (first (db/query tx ["SELECT * FROM proposal_items WHERE proposal_id = ? AND idx = ?" proposal-id idx]))
          op   (:op item)]
      (when-not item (throw (ex-info "No such proposal item" {:proposal proposal-id :idx idx})))
      (when (#{"accepted" "edited"} (:decision item)) (undo-item! tx item))
      (if (= decision "accepted")
        (let [w   (merge item (select-keys edits workout-cols))
              cur (some->> (:target_id item) (workout-row tx))]
          (when (and (#{"change" "remove"} op) (nil? cur))
            (throw (ex-info "The session to change no longer exists" {:target (:target_id item)})))
          (let [wid (case op
                      "add"    (add-workout! tx w proposal-id)
                      "change" (do (update-workout! tx (:target_id item) (select-keys w workout-cols)) nil)
                      "remove" (do (update-workout! tx (:target_id item) {:status "cancelled"}) nil))]
            (jdbc/execute! tx ["UPDATE proposal_items SET decision = ?, workout_id = ?, prev = ?
                                WHERE proposal_id = ? AND idx = ?"
                               (if (seq edits) "edited" "accepted") wid
                               (some-> cur (select-keys (conj workout-cols :status)) json/write-str)
                               proposal-id idx])))
        (jdbc/execute! tx ["UPDATE proposal_items SET decision = 'rejected', workout_id = NULL
                            WHERE proposal_id = ? AND idx = ?" proposal-id idx])))))

(defn accept-all!
  "Accepts every pending item. With `replace?`, planned (not done or skipped)
  workouts already in that week that didn't come from this proposal are removed."
  [ds proposal-id replace?]
  (let [{:keys [monday items]} (proposal ds proposal-id)]
    (when replace?
      (jdbc/execute! ds ["DELETE FROM planned_workouts WHERE date BETWEEN ? AND ? AND status = 'planned'
                          AND (proposal_id IS NULL OR proposal_id <> ?)"
                         monday (season/add-days monday 6) proposal-id]))
    (doseq [i items :when (= "pending" (:decision i))]
      (decide-item! ds proposal-id (:idx i) "accepted" nil))
    (jdbc/execute! ds ["UPDATE plan_proposals SET status = 'closed', updated_at = ? WHERE id = ?" (now) proposal-id])))
