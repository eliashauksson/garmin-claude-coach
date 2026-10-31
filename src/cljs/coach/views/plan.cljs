(ns coach.views.plan
  "Plan page: week types, the planned sessions, and the AI proposal review.
  Nothing enters the plan without an explicit accept."
  (:require [clojure.string :as str]
            [coach.ui :as ui]
            [coach.views.calendar :as cal]))

(def weeks-shown 4)

(def week-types [["NORMAL" "Normal week"] ["LIGHT" "Light week"] ["AWAY" "Away"] ["HOLIDAY" "Holiday"]])

(def session-names ui/session-names)

(defn strength? [session-id] (boolean (re-find #"^(GYM|CALF)" (str session-id))))

(defn mondays []
  (let [m (cal/monday (ui/iso-today))] (mapv #(cal/add-days m (* 7 %)) (range weeks-shown))))

(defn urls []
  (let [ms (mondays)]
    (into [(str "/api/plan?from=" (first ms) "&to=" (cal/add-days (peek ms) 6))
           "/api/plan/signals"
           (str "/api/plan/proposals?kind=adjust&monday=" (first ms))]
          (map #(str "/api/plan/proposals?monday=" %) ms))))

(defn- short-date [iso]
  (let [d (ui/parse-date iso)] (str (ui/weekdays (.getDay d)) " " (.getDate d) " " (ui/months (.getMonth d)))))

(defn session-chip [session-id]
  [:span.chip {:class (if (strength? session-id) "other" "run")} [:span.dot] (session-names session-id session-id)])

(defn- amount [{:keys [duration_min distance_km]}]
  (str/join " · " (remove nil? [(when (and duration_min (pos? duration_min)) (str duration_min " min"))
                                (when (and distance_km (pos? distance_km)) (str distance_km " km"))])))

;; ---------------------------------------------------------------------------
;; Planned sessions

(defn- workout-row [{:keys [id date slot session_id title intensity status] :as w}]
  [:div.plan-row {:replicant/key id :class status}
   [:div.plan-when (short-date date) [:span.sub (str " " slot)]]
   [:div.plan-what
    [:div (session-chip session_id) " " [:b title]]
    [:div.sub (str/join " · " (remove str/blank? [(amount w) intensity]))]]
   (let [patch (fn [k label body] [:button.pill {:replicant/key k :on {:click [[:api "PATCH" (str "/api/plan/workouts/" id) body]]}} label])]
     [:div.plan-actions
      (case status
        "done"    (if (:activity_id w)
                    [:a.pill {:href (str "#/activities/" (:activity_id w))} "✓ done"]
                    (list [:span.pill {:replicant/key "s"} "✓ done"] (patch "u" "Undo" {:status "planned"})))
        "skipped" (list [:span.pill {:replicant/key "s"} "skipped"] (patch "u" "Undo" {:status "planned"}))
        "missed"  (list [:span.pill.missed {:replicant/key "s"} "missed"]
                        (patch "m" "Mark done" {:status "done"}) (patch "k" "Skip" {:status "skipped"}))
        (list (patch "m" "Mark done" {:status "done"}) (patch "k" "Skip" {:status "skipped"})
              [:button.pill {:replicant/key "d" :on {:click [[:api "DELETE" (str "/api/plan/workouts/" id) nil]]}} "Remove"]))])])

;; ---------------------------------------------------------------------------
;; Proposal review

(defn- diff-badge [item existing]
  (let [same-slot (first (filter #(and (= (:date %) (:date item)) (= (:slot %) (:slot item))
                                       (nil? (:proposal_id %))) existing))]
    (cond
      (nil? same-slot) [:span.pill "new"]
      (= (:session_id same-slot) (:session_id item)) [:span.pill "same as planned"]
      :else [:span.pill (str "replaces " (session-names (:session_id same-slot)))])))

(defn- edit-form [pid {:keys [idx] :as item} edits monday]
  (let [v   #(get edits % (get item %))
        set #(vector :set-in-ui :edits pid idx % :event/target-value)]
    [:div.plan-edit
     [:label "Day "
      [:select {:on {:change [(set :date)]}}
       (for [d (map #(cal/add-days monday %) (range 7))]
         [:option {:replicant/key d :value d :selected (= d (v :date))} (short-date d)])]]
     [:label "Slot "
      [:select {:on {:change [(set :slot)]}}
       (for [s ["AM" "PM"]] [:option {:replicant/key s :value s :selected (= s (v :slot))} s])]]
     [:label "Minutes " [:input {:type "number" :min 0 :value (v :duration_min) :on {:input [(set :duration_min)]}}]]
     [:label "km " [:input {:type "number" :min 0 :step 0.5 :value (v :distance_km) :on {:input [(set :distance_km)]}}]]]))

(defn- clean-edits [edits]
  (cond-> edits
    (:duration_min edits) (update :duration_min #(js/parseInt %))
    (:distance_km edits)  (update :distance_km #(js/parseFloat %))))

(defn- item-row [{:keys [id items]} item existing ui-state monday]
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
                    [:span.pill (str "changes " (session-names (:session_id c)) " " (short-date (:date c)) " " (:slot c)
                                     (some->> (amount c) not-empty (str ", ")))])
         "remove" [:span.pill.missed "remove"]
         (diff-badge item existing))]
      [:div.sub (str/join " · " (remove str/blank? [(amount item) intensity]))]
      [:div description]
      [:div.sub [:i reason]]
      (when editing (edit-form id item edits monday))]
     [:div.plan-actions
      (case decision
        "pending"
        (list
         [:button.btn-ink {:replicant/key "a"
                           :on {:click [[:api "POST" url {:decision "accepted" :edits (clean-edits edits)}]
                                        [:set-in-ui :editing id idx false]]}}
          (if editing "Accept edited" "Accept")]
         [:button.pill {:replicant/key "e" :on {:click [[:set-in-ui :editing id idx (not editing)]]}}
          (if editing "Cancel edit" "Edit")]
         [:button.pill {:replicant/key "r" :on {:click [[:api "POST" url {:decision "rejected"}]]}} "Reject"])
        (list
         [:span.pill {:replicant/key "d"} (case decision "accepted" "✓ accepted" "edited" "✓ accepted (edited)" "rejected" "✗ rejected" decision)]
         [:button.pill {:replicant/key "u"
                        :on {:click [[:api "POST" url {:decision (if (= decision "rejected") "accepted" "rejected")}]]}}
          (if (= decision "rejected") "Accept instead" "Undo")]))]]))

(defn- proposal-panel [p existing ui-state monday]
  (let [{:keys [id status response warnings error items usage]} p
        pending? (some #(= "pending" (:decision %)) items)]
    (case status
      "running" [:div.proposal [:div.sub "The coach is working on it… this usually takes a minute or two."]]
      "failed"  [:div.proposal [:div.error (str "Planning failed: " error)]]
      [:div.proposal
       [:div.lab (if (= "adjust" (:kind p)) "Suggested adjustments" "Proposal") [:span.sp] [:span.sub (str "#" id)]]
       (when (seq (:signals p))
         [:div.sub (str "Triggered by: " (str/join " · " (keep :message (:signals p))))])
       [:div (:summary response)]
       (when-let [t (:targets response)]
         (ui/pills (ui/pill (str "Target " (:run_km t) " km running"))
                   (ui/pill (str "Longest: " (:longest_run t)))
                   (ui/pill (str "Quality: " (:quality t)))))
       (let [all (concat (:warnings response) warnings)]
         (when (seq all)
           [:div.banner.warn [:span.icon "!"]
            [:div (for [[i w] (map-indexed vector all)] [:div {:replicant/key i} w])]]))
       [:div.plan-list (for [item items] (item-row p item existing ui-state monday))]
       (when (and pending? (not= "adjust" (:kind p)))
         [:div.plan-footer
          [:button.btn-ink {:on {:click [[:api "POST" (str "/api/plan/proposals/" id "/accept-all")
                                          {:replace (get-in ui-state [:replace id] true)}]]}}
           "Accept all remaining"]
          [:label.sub
           [:input {:type "checkbox" :checked (get-in ui-state [:replace id] true)
                    :on {:change [[:set-in-ui :replace id (not (get-in ui-state [:replace id] true))]]}}]
           " replace sessions already planned this week"]])
       (when usage
         [:div.sub (str/join " · " (remove nil? [(str "Model " (:model p))
                                                 (when (:cost_chf usage) (str "cost ≈ CHF " (.toFixed (:cost_chf usage) 2)))
                                                 (str (ui/thousands (:input_tokens usage)) " in / "
                                                      (ui/thousands (:output_tokens usage)) " out tokens (incl. thinking)")
                                                 (when (pos? (:cache_read usage))
                                                   (str (ui/thousands (:cache_read usage)) " read from cache"))]))])])))

;; ---------------------------------------------------------------------------

(defn- week-card [{:keys [monday sunday week phase week_type] :as wk} workouts proposal ui-state]
  (let [mine     (filter #(<= (compare monday (:date %)) 0 (compare sunday (:date %))) workouts)
        running? (= "running" (:status proposal))]
    (ui/card {}
             [:div.page-head
              [:div.title
               [:div.sub (str (short-date monday) " – " (short-date sunday))]
               [:h2.week-title (if week (str "Week " week " · " phase) phase)]]
              [:div.seg.soft
               (for [[v label] week-types]
                 [:button {:replicant/key v :class (when (= v week_type) "on")
                           :on {:click [[:api "PUT" (str "/api/plan/weeks/" monday) {:week_type v}]]}}
                  label])]]
             (if (seq mine)
               [:div.plan-list (map workout-row mine)]
               [:div.empty "Nothing planned yet."])
             [:div.plan-footer
              [:button.btn-ink {:disabled (or running? (nil? week_type))
                                :title (when (nil? week_type) "Set the week type first (§7.1)")
                                :on {:click [[:api "POST" "/api/plan/proposals" {:monday monday}]]}}
               (cond running? "Planning…" (seq mine) "Re-plan week" :else "Propose week")]
              (when (nil? week_type) [:span.sub "Set the week type first."])]
             (when (and proposal (not= "closed" (:status proposal)))
               (proposal-panel proposal mine ui-state monday)))))

(defn- season-line [{:keys [week phase days_to_race]}]
  (str (if week (str "Week " week " of 23 · " phase) "Season starts Mon 2 Nov")
       " · " days_to_race " days to the the goal race"))

(def severity-color {"high" "var(--red)" "warn" "var(--amber)" "info" "var(--blue)"})

(defn- coach-card [signals adjust workouts ui-state]
  (let [running? (= "running" (:status adjust))
        open?    (and adjust (not= "closed" (:status adjust))
                      (or running? (= "failed" (:status adjust))
                          (some #(= "pending" (:decision %)) (:items adjust))))]
    (ui/card {:tint (if (some #(#{"high" "warn"} (name (:severity %))) signals) "amber" nil)}
             (ui/label-row {:icon :pulse :accent "--amber" :tint "--tint-amber" :soft? true :label "Coach signals"})
             (if (seq signals)
               [:div.plan-list
                (for [s signals]
                  [:div.signal {:replicant/key (:key s)}
                   [:span.dot {:style {:background (severity-color (name (:severity s)))}}]
                   [:span (:message s) [:span.sub (str " · " (:principle s)
                                                       (when (:trigger? s) " · asks for a change"))]]])]
               [:div.sub "No signals - training is going as planned."])
             [:div.plan-footer
              [:input.note {:type "text" :placeholder "Anything the coach should know? (e.g. sick since yesterday)"
                            :value (get ui-state :adjust-note "")
                            :on {:input [[:set-ui :adjust-note :event/target-value]]}}]
              [:button.btn-ink {:disabled running?
                                :on {:click [[:api "POST" "/api/plan/adjust" {:note (get ui-state :adjust-note "")}]
                                             [:set-ui :adjust-note ""]]}}
               (if running? "Adjusting…" "Re-plan rest of week")]]
             (when open? (proposal-panel adjust workouts ui-state (:monday adjust))))))

(defn view [{:keys [data errors flash] ui-state :ui} [plan-url signals-url adjust-url & proposal-urls]]
  (let [p     (get data plan-url)
        props (map #(get data %) proposal-urls)]
    [:div {:style {:display "contents"}}
     [:div.page-head
      [:div.title [:div.sub (when p (season-line (:season p)))] [:h1 "Plan"]]]
     (when flash [:div.error flash])
     (when-let [e (get errors plan-url)] [:div.error (str "Could not load: " e)])
     (when p (coach-card (get data signals-url) (get data adjust-url) (:workouts p) ui-state))
     (when p
       (for [[wk prop] (map vector (:weeks p) props)]
         [:div {:replicant/key (:monday wk)}
          (week-card wk (:workouts p) prop ui-state)]))]))
