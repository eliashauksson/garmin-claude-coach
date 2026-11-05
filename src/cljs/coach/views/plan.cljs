(ns coach.views.plan
  "Plan page: the planned sessions, and the AI proposal review.
  Nothing enters the plan without an explicit accept."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.athlete :as athlete]
            [coach.views.season :as season]
            [coach.views.structure :as st]))

(def weeks-shown "The current week and the two the coach keeps planned ahead." 3)

(def session-names ui/session-names)

(defn strength? [session-id] (boolean (re-find #"^GYM" (str session-id))))
(defn- cross? [session-id] (contains? #{"HIKE" "RIDE"} session-id))

(defn mondays []
  (let [m (ui/monday (ui/iso-today))] (mapv #(ui/add-days m (* 7 %)) (range weeks-shown))))

(defn urls []
  (let [ms (mondays)]
    (into [(str "/api/plan?from=" (first ms) "&to=" (ui/add-days (peek ms) 6))
           "/api/plan/signals"
           (str "/api/plan/proposals?kind=adjust&monday=" (first ms))
           athlete/url
           season/url]
          (map #(str "/api/plan/proposals?monday=" %) ms))))

(defn- short-date [iso]
  (let [d (ui/parse-date iso)] (str (ui/weekdays (.getDay d)) " " (ui/day-month d))))

(defn session-chip [session-id]
  [:span.chip {:class (if (or (strength? session-id) (cross? session-id)) "other" "run")} [:span.dot]
   (session-names session-id session-id)])

(defn- amount [{:keys [duration_min distance_km]}]
  (str/join " · " (remove nil? [(when (and duration_min (pos? duration_min)) (str duration_min " min"))
                                (when (and distance_km (pos? distance_km)) (str distance_km " km"))])))

;; ---------------------------------------------------------------------------
;; Planned sessions

(defn- workout-row [{:keys [id date slot session_id title intensity status] :as w}]
  [:div.plan-row {:replicant/key id :class status}
   [:div.plan-when (short-date date) [:span.sub (str " " slot)]]
   [:div.plan-what
    [:div (session-chip session_id) " " [:a.plain {:href (str "#/workouts/" id)} [:b title]]]
    [:div.sub (str/join " · " (remove str/blank? [(amount w) intensity]))]]
   (let [patch (fn [k label body] [:button.pill
                                   {:replicant/key k :on {:click [[:api "PATCH" (str "/api/plan/workouts/" id) body]]}}
                                   label])]
     [:div.plan-actions
      (case status
        "done"    (if (:activity_id w)
                    [:a.pill {:href (str "#/activities/" (:activity_id w))} (t "✓ done")]
                    (list [:span.pill {:replicant/key "s"} (t "✓ done")] (patch "u" (t "Undo") {:status "planned"})))
        "skipped" (list [:span.pill {:replicant/key "s"} (t "skipped")] (patch "u" (t "Undo") {:status "planned"}))
        "missed"  (list [:span.pill.missed {:replicant/key "s"} (t "missed")]
                        (patch "m" (t "Mark done") {:status "done"}) (patch "k" (t "Skip") {:status "skipped"}))
        (list (patch "m" (t "Mark done") {:status "done"}) (patch "k" (t "Skip") {:status "skipped"})
              [:button.pill {:replicant/key "d" :on {:click [[:api "DELETE" (str "/api/plan/workouts/" id) nil]]}}
               (t "Remove")]))])])

;; ---------------------------------------------------------------------------
;; Proposal review

(defn- diff-badge [item existing]
  (let [same-slot (first (filter #(and (= (:date %) (:date item)) (= (:slot %) (:slot item))
                                       (nil? (:proposal_id %))) existing))]
    (cond
      (nil? same-slot) [:span.pill (t "new")]
      (= (:session_id same-slot) (:session_id item)) [:span.pill (t "same as planned")]
      :else [:span.pill (t "replaces {0}" (session-names (:session_id same-slot)))])))

(defn- edit-form [pid {:keys [idx] :as item} edits monday]
  (let [v   #(get edits % (get item %))
        set #(vector :set-in-ui :edits pid idx % :event/target-value)]
    [:div.plan-edit
     [:label (t "Day") " "
      [:select {:on {:change [(set :date)]}}
       (for [d (map #(ui/add-days monday %) (range 7))]
         [:option {:replicant/key d :value d :selected (= d (v :date))} (short-date d)])]]
     [:label (t "Slot") " "
      [:select {:on {:change [(set :slot)]}}
       (for [s ["AM" "PM"]] [:option {:replicant/key s :value s :selected (= s (v :slot))} s])]]
     [:label (t "Minutes") " "
      [:input {:type "number" :min 0 :value (v :duration_min) :on {:input [(set :duration_min)]}}]]
     [:label "km "
      [:input {:type "number" :min 0 :step 0.5 :value (v :distance_km) :on {:input [(set :distance_km)]}}]]]))

(defn- clean-edits [edits]
  (cond-> edits
    (:duration_min edits) (update :duration_min #(js/parseInt %))
    (:distance_km edits)  (update :distance_km #(js/parseFloat %))))

(defn- item-row [{:keys [id]} item existing ui-state monday]
  (let [{:keys [idx date slot session_id title intensity description reason decision]} item
        editing (get-in ui-state [:editing id idx])
        edits   (get-in ui-state [:edits id idx])
        url     (str "/api/plan/proposals/" id "/items/" idx)]
    [:div.plan-row {:replicant/key idx :class [decision (when (= "remove" (:op item)) "remove")]}
     [:div.plan-when (short-date date) [:span.sub (str " " slot)]]
     [:div.plan-what
      [:div (session-chip session_id) " " [:b title] " "
       (case (:op item)
         "change" (let [c (:current item)]
                    [:span.pill
                     (t "changes {0}" (str (session-names (:session_id c)) " " (short-date (:date c)) " " (:slot c)
                                           (some->> (amount c) not-empty (str ", "))))])
         "remove" [:span.pill.missed (t "remove")]
         (diff-badge item existing))]
      [:div.sub (str/join " · " (remove str/blank? [(amount item) intensity]))]
      [:div description]
      (when (seq (:blocks item)) [:div {:style {:margin "4px 0"}} (st/profile (:blocks item) {:height "28px"})])
      (when-not (str/blank? (:goal item)) [:div.sub (:goal item)])
      [:div.sub [:i reason]]
      (when editing (edit-form id item edits monday))]
     [:div.plan-actions
      (case decision
        "pending"
        (list
         [:button.btn-ink {:replicant/key "a"
                           :on {:click [[:api "POST" url {:decision "accepted" :edits (clean-edits edits)}]
                                        [:set-in-ui :editing id idx false]]}}
          (if editing (t "Accept edited") (t "Accept"))]
         [:button.pill {:replicant/key "e" :on {:click [[:set-in-ui :editing id idx (not editing)]]}}
          (if editing (t "Cancel edit") (t "Edit"))]
         [:button.pill {:replicant/key "r" :on {:click [[:api "POST" url {:decision "rejected"}]]}} (t "Reject")])
        (list
         [:span.pill {:replicant/key "d"}
          (case decision "accepted" (t "✓ accepted") "edited" (t "✓ accepted (edited)") "rejected" (t "✗ rejected")
            decision)]
         [:button.pill {:replicant/key "u"
                        :on {:click [[:api "POST" url {:decision (if (= decision "rejected") "accepted" "rejected")}]]}}
          (if (= decision "rejected") (t "Accept instead") (t "Undo"))]))]]))

(defn waiting-text
  "What a running request is doing, by how it was sent (coach.plan/set-delivery!)."
  [delivery]
  (case delivery
    "batch"  (t "Waiting for Claude in the half-price batch. Usually within an hour (at most 24 h); you can close the page meanwhile.")
    "queued" (t "Queued for the half-price batch: it goes out once the week before it is back, because it builds on that week.")
    (t "The coach is working on it… this usually takes a minute or two.")))

(defn- proposal-panel [p existing ui-state monday]
  (let [{:keys [id status response warnings error items usage delivery]} p
        pending? (some #(= "pending" (:decision %)) items)]
    (case status
      "running" [:div.proposal [:div.sub (waiting-text delivery)]]
      "failed"  [:div.proposal [:div.error (t "Planning failed: {0}" error)]]
      [:div.proposal
       [:div.lab (if (= "adjust" (:kind p)) (t "Suggested adjustments") (t "Proposal")) [:span.sp]
        [:span.sub (str "#" id)]]
       (when (seq (:signals p))
         [:div.sub (t "Triggered by: {0}" (str/join " · " (keep :message (:signals p))))])
       [:div (:summary response)]
       (when-let [tg (:targets response)]
         (ui/pills (ui/pill (if (:run_min tg) (t "Target {0} min running" (:run_min tg))
                              (t "Target {0} km running" (:run_km tg))))
                   (ui/pill (t "Longest: {0}" (:longest_run tg)))
                   (ui/pill (t "Quality: {0}" (:quality tg)))))
       (let [all (concat (:warnings response) warnings)]
         (when (seq all)
           [:div.banner.warn [:span.icon "!"]
            [:div (for [[i w] (map-indexed vector all)] [:div {:replicant/key i} w])]]))
       [:div.plan-list (for [item items] (item-row p item existing ui-state monday))]
       (when pending?
         (if (= "adjust" (:kind p))
           [:div.plan-footer
            [:button.btn-ink
             {:on {:click [[:api "POST" (str "/api/plan/proposals/" id "/accept-all") {:replace false}]]}}
             (t "Accept all remaining")]]
           [:div.plan-footer
            [:button.btn-ink {:on {:click [[:api "POST" (str "/api/plan/proposals/" id "/accept-all")
                                            {:replace (get-in ui-state [:replace id] true)}]]}}
             (t "Accept all remaining")]
            [:label.sub
             [:input {:type "checkbox" :checked (get-in ui-state [:replace id] true)
                      :on {:change [[:set-in-ui :replace id (not (get-in ui-state [:replace id] true))]]}}]
             " " (t "replace sessions already planned this week")]]))
       (when usage
         [:div.sub (str/join " · " (remove nil? [(t "Model {0}" (:model p))
                                                 (when (:cost_chf usage)
                                                   (t "cost ≈ CHF {0}" (.toFixed (:cost_chf usage) 2)))
                                                 (t "{0} in / {1} out tokens (incl. thinking)"
                                                    (ui/thousands (:input_tokens usage))
                                                    (ui/thousands (:output_tokens usage)))
                                                 (when (pos? (:cache_read usage))
                                                   (t "{0} read from cache"
                                                      (ui/thousands (:cache_read usage))))]))])])))

;; ---------------------------------------------------------------------------

(defn- week-card [{:keys [monday sunday week phase]} workouts proposal ui-state season?]
  (let [mine     (filter #(<= (compare monday (:date %)) 0 (compare sunday (:date %))) workouts)
        running? (= "running" (:status proposal))]
    (ui/card {}
             [:div.page-head
              [:div.title
               [:div.sub (str (short-date monday) " – " (short-date sunday))]
               [:h2.week-title (if week (t "Week {0} · {1}" week (t phase)) (t phase))]]]
             (if (seq mine)
               [:div.plan-list (map workout-row mine)]
               [:div.empty (t "Nothing planned yet.")])
             (if season?
               [:div.plan-footer
                [:button.btn-ink {:disabled running?
                                  :on {:click [[:api "POST" "/api/plan/proposals" {:monday monday}]]}}
                 (cond (and running? (:delivery proposal)) (t "Waiting for batch…")
                       running? (t "Planning…") (seq mine) (t "Re-plan week") :else (t "Propose week"))]]
               [:div.sub (t "Create the season plan first. The coach then plans the next two weeks for you.")])
             (when (and proposal (not (#{"closed" "withdrawn"} (:status proposal))))
               (proposal-panel proposal mine ui-state monday)))))

(defn- season-line
  "Header line: the week's place in the season plan and the days to the race."
  [{:keys [week weeks_total phase days_to_race race_date]} race-name]
  (str/join " · " (remove nil? [(cond week  (t "Week {0} of {1} · {2}" week weeks_total (t phase))
                                      phase (t phase)
                                      :else (t "No season plan yet"))
                                (when days_to_race
                                  (t "{0} days to {1} ({2})" days_to_race (or race-name (t "race day"))
                                     (short-date race_date)))])))

(def severity-color {"high" "var(--red)" "warn" "var(--amber)" "info" "var(--blue)"})

(defn- coach-card [signals adjust workouts ui-state]
  (let [running? (= "running" (:status adjust))
        open?    (and adjust (not (#{"closed" "withdrawn"} (:status adjust)))
                      (or running? (= "failed" (:status adjust))
                          (some #(= "pending" (:decision %)) (:items adjust))))]
    (ui/card {:tint (if (some #(#{"high" "warn"} (name (:severity %))) signals) "amber" nil)}
             (ui/label-row {:icon :pulse :accent "--amber" :tint "--tint-amber" :soft? true :label (t "Coach signals")})
             (if (seq signals)
               [:div.plan-list
                (for [s signals]
                  [:div.signal {:replicant/key (:key s)}
                   [:span.dot {:style {:background (severity-color (name (:severity s)))}}]
                   [:span (:message s)
                    [:span.sub (str " · " (:principle s) ; a section heading of training-principles.md, as written there
                                    (when (:trigger? s) (str " · " (t "asks for a change"))))]]])]
               [:div.sub (t "No signals - training is going as planned.")])
             [:div.plan-footer
              [:input.note {:type "text" :placeholder (t "Anything the coach should know? (e.g. sick since yesterday)")
                            :value (get ui-state :adjust-note "")
                            :on {:input [[:set-ui :adjust-note :event/target-value]]}}]
              [:button.btn-ink {:disabled running?
                                :on {:click [[:api "POST" "/api/plan/adjust" {:note (get ui-state :adjust-note "")}]
                                             [:set-ui :adjust-note ""]]}}
               (cond (and running? (:delivery adjust)) (t "Waiting for batch…")
                     running? (t "Adjusting…") :else (t "Re-plan rest of week"))]]
             (when open? (proposal-panel adjust workouts ui-state (:monday adjust))))))

(defn view [{:keys [data errors flash] ui-state :ui}
            [plan-url signals-url adjust-url athlete-url season-url & proposal-urls]]
  (let [p     (get data plan-url)
        props (map #(get data %) proposal-urls)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title [:div.sub (when p (season-line (:season p) (:race (get data season-url))))] [:h1 (t "Plan")]]]
     (when flash [:div.error flash])
     (when-let [e (get errors plan-url)] [:div.error (t "Could not load: {0}" e)])
     (when-let [a (get data athlete-url)] (athlete/card a (:athlete ui-state)))
     (when-let [s (get data season-url)] (season/card s (:season ui-state)))
     (when p (coach-card (get data signals-url) (get data adjust-url) (:workouts p) ui-state))
     (when p
       (for [[wk prop] (map vector (:weeks p) props)]
         [:div {:replicant/key (:monday wk)}
          (week-card wk (:workouts p) prop ui-state (some? (:plan (get data season-url))))]))]))
