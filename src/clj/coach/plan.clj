(ns coach.plan
  "Storage for the training plan: weeks, AI proposals and the planned
  workouts the athlete accepted. Plans live only here; nothing goes to Garmin."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [coach.db :as db]
            [coach.i18n :as i18n]
            [coach.plan-rules :as rules]
            [coach.season :as season]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Instant LocalDate)))

(defn- now [] (str (Instant/now)))

(defn- exec! [ds sqlvec]
  (jdbc/execute-one! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))

;; ---------------------------------------------------------------------------
;; Weeks

(declare latest-season-plan)

(defn current-season
  "The current season plan as {:race_date :weeks}, or nil."
  [ds]
  (when-let [p (latest-season-plan ds "ready")]
    {:race_date (:race_date p) :weeks (get-in p [:response :weeks])}))

(defn week-info
  "Season position of the week containing `date` (coach.season/week-info)."
  [ds date]
  (season/week-info date (current-season ds)))

(defn weeks
  "Plan weeks (with season position) for every Monday in [from, to]."
  [ds from to]
  (let [s (current-season ds)]
    (for [m (take-while #(<= (compare % to) 0) (iterate #(season/add-days % 7) (season/monday from)))]
      (season/week-info m s))))

;; ---------------------------------------------------------------------------
;; Planned workouts

(defn- activity-kind [sport]
  (cond (re-find #"running" (str sport)) :run
        (re-find #"strength" (str sport)) :strength
        (re-find #"hiking|walking" (str sport)) :hike
        (re-find #"cycling|biking" (str sport)) :ride))

(defn today
  "Today's date (ISO): what has passed shows as missed. A function so tests
  can pin it."
  []
  (str (LocalDate/now)))

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

(defn parse-blocks
  "A stored row with its JSON blocks (coach.workout) as data."
  [row]
  (cond-> row
    (string? (:blocks row)) (update :blocks #(try (json/read-str % :key-fn keyword) (catch Exception _ nil)))))

(defn- encode-blocks
  "A workout as stored: blocks as JSON."
  [w]
  (cond-> w (coll? (:blocks w)) (update :blocks json/write-str)))

(defn workouts
  "Planned workouts in [from, to] with done/missed resolved. Cancelled ones
  (removed by an accepted adjustment) are left out."
  [ds from to]
  (mapv parse-blocks
        (with-done (db/query ds ["SELECT * FROM planned_workouts WHERE date BETWEEN ? AND ? AND status <> 'cancelled'
                                  ORDER BY date, slot" from to])
          (db/query ds ["SELECT id, date, sport FROM activities WHERE date BETWEEN ? AND ?" from to])
          (today))))

(defn workout
  "One planned workout with done/missed resolved (and the matched
  :activity_id), or nil."
  [ds id]
  (when-let [date (:date (first (db/query ds ["SELECT date FROM planned_workouts WHERE id = ?" id])))]
    (or (some #(when (= id (:id %)) %) (workouts ds date date))
        ;; a cancelled one isn't in the day's list
        (parse-blocks (first (db/query ds ["SELECT * FROM planned_workouts WHERE id = ?" id]))))))

(defn planned-for-activity
  "The planned workout an activity fulfilled (coach.plan/with-done), or nil."
  [ds {:keys [id date]}]
  (when date
    (some #(when (= id (:activity_id %)) %) (workouts ds date date))))

(def statuses #{"planned" "skipped" "done" "cancelled"})

(def workout-cols [:date :slot :session_id :title :duration_min :distance_km :intensity :description :reason
                   :goal :blocks])

(defn add-workout! [ds w & [proposal-id]]
  (let [w (encode-blocks w)]
    (:id (exec! ds (into ["INSERT INTO planned_workouts (date, slot, session_id, title, duration_min, distance_km,
                             intensity, description, reason, goal, blocks, proposal_id, created_at, updated_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"]
                         (concat (map w workout-cols) [proposal-id (now) (now)]))))))

(defn set-structure!
  "Adds goal and blocks to a workout that has no blocks yet (the one-time
  backfill, coach.planner/structure!). Returns whether it was set."
  [ds id goal blocks]
  (pos? (:next.jdbc/update-count
         (jdbc/execute-one! ds ["UPDATE planned_workouts SET goal = ?, blocks = ?, updated_at = ?
                                 WHERE id = ? AND blocks IS NULL"
                                goal (json/write-str (vec blocks)) (now) id]))))

(defn update-workout!
  "Edits a planned workout; :status may be planned/skipped/done/cancelled."
  [ds id changes]
  (when (and (:status changes) (not (statuses (:status changes))))
    (throw (ex-info "Unknown status" {:status (:status changes)})))
  (let [changes (encode-blocks changes)
        cols    (filter #(contains? changes %) (conj workout-cols :status))]
    (when (seq cols)
      (jdbc/execute! ds (into [(str "UPDATE planned_workouts SET "
                                    (str/join ", " (map #(str (name %) " = ?") cols))
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
  ([ds monday model {:keys [kind scope-end signals delivery] :or {kind "week"}}]
   (:id (exec! ds
               ["INSERT INTO plan_proposals
                   (monday, status, model, kind, scope_end, signals, delivery, created_at, updated_at)
                 VALUES (?, 'running', ?, ?, ?, ?, ?, ?, ?) RETURNING id"
                monday model kind scope-end (some-> signals json/write-str) delivery (now) (now)]))))

(defn set-delivery!
  "\"batch\" (sent to the half-price batch), \"queued\" (waits for the previous
  week's batch result) or nil (instant)."
  [ds id delivery]
  (jdbc/execute! ds ["UPDATE plan_proposals SET delivery = ?, updated_at = ? WHERE id = ?" delivery (now) id]))

(defn fail-proposal! [ds id error]
  (jdbc/execute! ds ["UPDATE plan_proposals SET status = 'failed', error = ?, updated_at = ? WHERE id = ? AND status = 'running'"
                     error (now) id]))

(defn finish-proposal!
  "Stores the model's week and its sessions as pending items."
  [ds id {:keys [context response warnings usage]}]
  (jdbc/with-transaction [tx ds]
    ;; only a running proposal: one withdrawn meanwhile (season plan deleted) stays withdrawn
    (when (pos? (:next.jdbc/update-count
                 (jdbc/execute-one! tx
                                    ["UPDATE plan_proposals
                                        SET status = 'open', context = ?, response = ?, warnings = ?, usage = ?,
                                            updated_at = ?
                                      WHERE id = ? AND status = 'running'"
                                     (json/write-str context) (json/write-str (dissoc response :workouts))
                                     (json/write-str warnings) (json/write-str usage) (now) id])))
    (doseq [[i w] (map-indexed vector (or (:changes response) (:workouts response)))]
      (jdbc/execute! tx (into ["INSERT INTO proposal_items
                                  (proposal_id, idx, date, slot, session_id, title, duration_min, distance_km,
                                   intensity, description, reason, goal, blocks, op, target_id)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"]
                              (concat [id i] (map (encode-blocks w) workout-cols)
                                      [(or (:op w) "add") (when (pos? (or (:workout_id w) 0)) (:workout_id w))])))))))

(defn proposal [ds id]
  (when-let [p (first (db/query ds ["SELECT id, monday, status, model, kind, scope_end, signals, response, warnings,
                                            error, usage, delivery, created_at, updated_at
                                       FROM plan_proposals WHERE id = ?" id]))]
    (let [items   (db/query ds ["SELECT * FROM proposal_items WHERE proposal_id = ? ORDER BY date, slot, idx" id])
          targets (into {} (for [t (keep :target_id items)]
                             [t (first (db/query ds ["SELECT * FROM planned_workouts WHERE id = ?" t]))]))]
      (assoc (parse p :response :warnings :usage :signals)
             :items (mapv #(cond-> (parse-blocks (parse % :prev))
                             (:target_id %) (assoc :current (parse-blocks (targets (:target_id %)))))
                          items)))))

(defn open-proposals
  "Proposals that are generating or still have undecided items."
  [ds]
  (db/query ds ["SELECT p.id, p.kind, p.monday, p.status, p.delivery, p.created_at FROM plan_proposals p
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
            (throw (ex-info (i18n/t "The session to change no longer exists") {:target (:target_id item)})))
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
  workouts already in that week that didn't come from this proposal are removed.
  `replace?` is ignored for adjustments: their items change or remove the
  planned sessions themselves, so clearing the week first would lose them."
  [ds proposal-id replace?]
  (let [{:keys [monday items kind]} (proposal ds proposal-id)]
    (when (and replace? (not= "adjust" kind))
      (jdbc/execute! ds ["DELETE FROM planned_workouts WHERE date BETWEEN ? AND ? AND status = 'planned'
                          AND (proposal_id IS NULL OR proposal_id <> ?)"
                         monday (season/add-days monday 6) proposal-id]))
    (doseq [i items :when (= "pending" (:decision i))]
      (decide-item! ds proposal-id (:idx i) "accepted" nil))
    (jdbc/execute! ds ["UPDATE plan_proposals SET status = 'closed', updated_at = ? WHERE id = ?" (now) proposal-id])))

;; ---------------------------------------------------------------------------
;; Season plans (the long-term plan to race day; coach.season-plan drafts them)
;;
;; status: running -> ready (the current plan, at most one) or failed.
;; A ready plan replaced by a redraft, deleted or swapped out by a restore
;; becomes archived: a backup. The newest `keep-season-backups` are kept.

(def keep-season-backups 3)

(def ^:private season-cols
  "id, race_date, start_monday, status, model, response, warnings, error, usage,
   created_at, updated_at, archived_at, archived_reason, delivery")

(defn create-season-plan! [ds race-date start-monday model & [delivery]]
  (:id (exec! ds ["INSERT INTO season_plans (race_date, start_monday, status, model, delivery, created_at, updated_at)
                   VALUES (?, ?, 'running', ?, ?, ?, ?) RETURNING id"
                  race-date start-monday model delivery (now) (now)])))

(defn fail-season-plan! [ds id error]
  (jdbc/execute! ds ["UPDATE season_plans SET status = 'failed', error = ?, updated_at = ? WHERE id = ?"
                     error (now) id]))

(defn- archive-current!
  "Turns the current plan into a backup and drops the oldest backups."
  [tx reason]
  (jdbc/execute! tx ["UPDATE season_plans SET status = 'archived', archived_at = ?, archived_reason = ?
                      WHERE status = 'ready'" (now) reason])
  (jdbc/execute! tx ["DELETE FROM season_plans WHERE status = 'archived' AND id NOT IN
                        (SELECT id FROM season_plans WHERE status = 'archived'
                         ORDER BY archived_at DESC, id DESC LIMIT ?)" keep-season-backups]))

(defn finish-season-plan!
  "Stores a finished draft as the current plan; the previous one becomes a backup."
  [ds id {:keys [context response warnings usage]}]
  (jdbc/with-transaction [tx ds]
    (archive-current! tx "redraft")
    (jdbc/execute! tx ["UPDATE season_plans SET status = 'ready', context = ?, response = ?, warnings = ?,
                          usage = ?, updated_at = ? WHERE id = ?"
                       (json/write-str context) (json/write-str response) (json/write-str warnings)
                       (json/write-str usage) (now) id])))

(defn latest-season-plan
  "The newest season plan, or the newest with `status` (\"ready\" is the current plan)."
  ([ds] (latest-season-plan ds nil))
  ([ds status]
   (some-> (first (db/query ds (cond-> [(str "SELECT " season-cols " FROM season_plans"
                                              (when status " WHERE status = ?") " ORDER BY id DESC LIMIT 1")]
                                 status (conj status))))
           (parse :response :warnings :usage))))

(defn season-backups
  "Backups, newest first, without their full week list."
  [ds]
  (mapv (fn [p] (let [weeks (get-in p [:response :weeks])]
                  (-> (dissoc p :response :warnings :usage :error)
                      (assoc :weeks (count weeks)
                             :peak_tss
                             (some->> (seq (remove #(= "Race week" (:phase %)) weeks)) (map :tss) (apply max))))))
        (map #(parse % :response)
             (db/query ds [(str "SELECT " season-cols " FROM season_plans WHERE status = 'archived'
                                 ORDER BY archived_at DESC, id DESC")]))))

(defn delete-season-plan!
  "Removes the current plan; it is kept as a backup."
  [ds]
  (when-not (latest-season-plan ds "ready")
    (throw (ex-info (i18n/t "There is no season plan to delete.") {})))
  (jdbc/with-transaction [tx ds] (archive-current! tx "delete")))

(defn restore-season-plan!
  "Makes backup `id` the current plan; the current one becomes a backup."
  [ds id]
  (when-not (seq (db/query ds ["SELECT 1 FROM season_plans WHERE id = ? AND status = 'archived'" id]))
    (throw (ex-info "Unknown season plan backup" {:id id})))
  (jdbc/with-transaction [tx ds]
    ;; take the backup out first so pruning can't drop it
    (jdbc/execute! tx ["UPDATE season_plans SET status = 'restoring' WHERE id = ?" id])
    (archive-current! tx "restore")
    (jdbc/execute! tx ["UPDATE season_plans SET status = 'ready', archived_at = NULL, archived_reason = NULL,
                          updated_at = ? WHERE id = ?" (now) id])))

(defn clear-upcoming!
  "What goes with the season plan when it is deleted: planned sessions from
  `today` on that aren't done are cancelled, and week proposals still running
  or awaiting review are withdrawn. Done, skipped and past sessions stay.
  Returns the number of sessions cancelled."
  [ds today]
  (let [ids (keep #(when (= "planned" (:status %)) (:id %)) (workouts ds today "9999-12-31"))]
    (jdbc/with-transaction [tx ds]
      (doseq [id ids]
        (jdbc/execute! tx ["UPDATE planned_workouts SET status = 'cancelled', updated_at = ? WHERE id = ?" (now) id]))
      (jdbc/execute! tx ["UPDATE proposal_items SET decision = 'rejected' WHERE decision = 'pending' AND proposal_id IN
                            (SELECT id FROM plan_proposals WHERE status IN ('running', 'open'))"])
      (jdbc/execute! tx
                     ["UPDATE plan_proposals SET status = 'withdrawn', updated_at = ? WHERE status IN ('running', 'open')"
                      (now)]))
    (count ids)))

(defn season-target
  "The current season plan's entry for the week of `monday`, if there is one."
  [ds monday]
  (some #(when (= monday (:monday %)) %) (get-in (latest-season-plan ds "ready") [:response :weeks])))
