(ns coach.views.dashboard.cycle
  "The Today page's cycle card, only for an athlete who turned cycle tracking on (Settings): the phase of today,
  the next period, flags worth a doctor's attention, and a quick log of a period start, flow and symptoms.
  The phase is context for the coach (it adapts to symptoms, not to the phase), counted from the last period
  start, so ovulation and the luteal phase are estimates."
  (:require [coach.charts :as c]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

(def phases
  {"menstrual"   (tk "Menstrual phase")
   "follicular"  (tk "Follicular phase")
   "ovulation"   (tk "Around ovulation")
   "luteal"      (tk "Luteal phase")
   "late-luteal" (tk "Late luteal phase")
   "overdue"     (tk "Period is late")
   "bleed"       (tk "Bleeding days")
   "no-bleed"    (tk "No bleeding")})

(def phase-plain
  "The phases in everyday words, for the Trends charts."
  {"menstrual"   (tk "Your period")
   "follicular"  (tk "After your period")
   "ovulation"   (tk "Around ovulation")
   "luteal"      (tk "After ovulation")
   "late-luteal" (tk "Just before your period")
   "bleed"       (tk "Bleeding days")
   "no-bleed"    (tk "No bleeding")})

(def phase-colors
  "The colour of each phase (the calendar's tags, the cycle bar, the chart backgrounds)."
  {"menstrual" "--red" "bleed" "--red" "follicular" "--blue" "ovulation" "--green" "luteal" "--purple"
   "late-luteal" "--amber" "no-bleed" "--blue" "overdue" "--text-3"})

(def symptom-labels
  {"cramps"            (tk "Cramps")
   "fatigue"           (tk "Fatigue")
   "heavy_legs"        (tk "Heavy legs")
   "low_motivation"    (tk "Low motivation")
   "sleep"             (tk "Poor sleep")
   "headache"          (tk "Headache")
   "mood"              (tk "Mood")
   "back_pain"         (tk "Back pain")
   "bloating"          (tk "Bloating")
   "breast_tenderness" (tk "Breast tenderness")
   "cravings"          (tk "Cravings")
   "gi"                (tk "Stomach or gut")
   "acne"              (tk "Acne")
   "dizziness"         (tk "Dizziness")})

(def symptom-icons
  {"cramps" "⚡" "fatigue" "😴" "heavy_legs" "🦵" "low_motivation" "🔋" "sleep" "🌙" "headache" "🤕"
   "mood" "🎭" "back_pain" "🔙" "bloating" "🎈" "breast_tenderness" "💗" "cravings" "🍫" "gi" "🤢"
   "acne" "🔴" "dizziness" "💫"})

(def tag-labels
  {"happy" (tk "Happy") "calm" (tk "Calm") "energetic" (tk "Energetic") "motivated" (tk "Motivated")
   "sad" (tk "Sad") "anxious" (tk "Anxious") "irritable" (tk "Irritable") "mood_swings" (tk "Mood swings")
   "stressed" (tk "Stressed")
   "sex_protected" (tk "Sex, protected") "sex_unprotected" (tk "Sex, unprotected")
   "high_drive" (tk "High sex drive") "low_drive" (tk "Low sex drive")
   "dry" (tk "Dry") "sticky" (tk "Sticky") "creamy" (tk "Creamy") "egg_white" (tk "Egg white") "watery" (tk "Watery")
   "ovulation_test_positive" (tk "Ovulation test positive") "ovulation_test_negative" (tk "Ovulation test negative")
   "pill_taken" (tk "Took the pill") "illness" (tk "Ill") "alcohol" (tk "Alcohol") "travel" (tk "Travel")})

(def tag-icons
  {"happy" "😊" "calm" "😌" "energetic" "⚡" "motivated" "💪" "sad" "😢" "anxious" "😟" "irritable" "😠"
   "mood_swings" "🎢" "stressed" "😫"
   "sex_protected" "🛡️" "sex_unprotected" "❤️" "high_drive" "🔥" "low_drive" "🧊"
   "dry" "🏜️" "sticky" "🍯" "creamy" "🥛" "egg_white" "🥚" "watery" "💧"
   "ovulation_test_positive" "➕" "ovulation_test_negative" "➖" "pill_taken" "💊" "illness" "🤒"
   "alcohol" "🍷" "travel" "✈️"})

(def group-titles
  {"mood" (tk "Mood") "sex" (tk "Sex and desire") "discharge" (tk "Discharge") "other" (tk "Other")})

(def flow-labels
  {"spotting" (tk "Spotting, a little") "spotting_more" (tk "Spotting, more")
   "light" (tk "Light") "medium" (tk "Medium") "heavy" (tk "Heavy")})

(def flag-texts
  {"missed-period"  (tk "No period recorded for over 45 days. Low energy availability can be a cause (REDs): worth talking to a doctor.")
   "irregular"      (tk "Your cycle length has varied by 17 days or more over the last six months. Worth mentioning to a doctor.")
   "infrequent"     (tk "Only one or two periods in the last six months. Low energy availability can be a cause (REDs): worth talking to a doctor.")
   "short-cycles"   (tk "Two of your recent cycles were shorter than 21 days. Worth mentioning to a doctor.")
   "prolonged"      (tk "Two of your recent periods lasted ten days or more. Worth mentioning to a doctor.")
   "spotting"       (tk "You logged spotting between periods in two cycles. Worth mentioning to a doctor.")
   "heavy-bleeding" (tk "Heavy bleeding in your recent periods. Iron deficiency is common then: consider a ferritin check.")})

(defn phase-areas
  "ECharts markArea data shading each run of days of one phase, for the days (of the cycle calendar) that
  are among the chart's `dates`; predicted days are left out."
  [days dates]
  (let [on (set dates)]
    (->> days
         (filter #(and (on (:date %)) (not (:predicted %)) (:phase %)))
         (partition-by :phase)
         (mapv (fn [run]
                 (let [p (:phase (first run))]
                   [{:xAxis (:date (first run))
                     :itemStyle {:color (c/css-var (phase-colors p))
                                 :opacity (if (#{"menstrual" "bleed"} p) 0.16 0.08)}}
                    {:xAxis (:date (last run))}]))))))

(defn phase-bar
  "The current cycle as one bar: a segment per phase (faded when estimated), the expected next period shaded
  at the end and a marker at today. `bar` is {:days :today :segments [{:phase :from :to :estimated}] :window}."
  [{:keys [days today segments window]}]
  (let [pct (fn [d] (str (* 100.0 (/ d days)) "%"))]
    [:div.cycle-bar
     [:div.cycle-bar-track
      (for [{:keys [phase from to estimated]} segments]
        [:span.cycle-seg {:replicant/key (str phase from) :class (when estimated "est")
                          :title (t (get phases phase phase))
                          :style {:width (pct (inc (- to from)))
                                  :background (str "var(" (phase-colors phase) ")")}}])
      (when window
        [:span.cycle-window {:style {:left (pct (dec (first window)))
                                     :width (pct (inc (- (second window) (first window))))}}])
      [:span.cycle-today {:style {:left (pct (- today 0.5))}}]]]))

(defn bar-key
  "Says what the bar's colours mean: each phase it shows (with its days), the expected next period and today."
  [{:keys [segments window]}]
  [:div.cycle-key
   (for [{:keys [phase from to estimated]} segments]
     [:span {:replicant/key (str phase from)}
      [:i {:class (when estimated "est") :style {:background (str "var(" (phase-colors phase) ")")}}]
      (t (get phase-plain phase phase)) [:small (if (= from to) from (str from "–" to))]])
   (when window [:span {:replicant/key "next"} [:i.next] (t "Next period")])
   [:span {:replicant/key "today"} [:i.now] (t "Today")]])

(defn- log-url [date] (str "/api/cycle/log/" date))

(defn- body
  "What the day's log is saved as, with `change` applied."
  [log change]
  (merge {:period_start (= 1 (:period_start log)) :period_end (= 1 (:period_end log))
          :flow (:flow log) :symptoms (or (:symptoms log) {})
          :tags (vec (:tags log)) :note (:note log)}
         change))

(defn severity-label [n]
  (case n 1 (t "mild") 2 (t "moderate") (t "severe")))

(def ^:private amounts {"spotting" ["spotting" "spotting_more"] "period" ["light" "medium" "heavy"]})

(def ^:private amount-labels
  {"spotting" (tk "A little") "spotting_more" (tk "More") "light" (tk "Light") "medium" (tk "Medium")
   "heavy" (tk "Heavy")})

(defn- bleed-kind
  "\"spotting\" or \"period\" for a logged flow, nil for none."
  [flow]
  (cond (#{"spotting" "spotting_more"} flow) "spotting" flow "period"))

(defn- group [key title & children]
  [:div.log-group {:replicant/key key}
   [:div.log-title title]
   (into [:div.fb-row] children)])

(defn log-controls
  "The log of one `date`, in groups (period, symptoms, mood, sex, discharge, other), each saved as it is tapped.
  `c` is the cycle overview (:symptoms, :tag_groups, :today phase), `log` the day's saved entry."
  [{:keys [symptoms tag_groups]} log date]
  (let [have      (into {} (map (fn [[k v]] [(name k) v])) (:symptoms log))
        tags      (set (map name (:tags log)))
        started?  (= 1 (:period_start log))
        ended?    (= 1 (:period_end log))
        save      (fn [change] [:api "PUT" (log-url date) (body log change)])]
    (list
     (group "period" (t "Period")
            [:button.pill {:type "button" :class (when started? "on")
                           :on {:click [(save {:period_start (not started?)})]}}
             (t "Period started this day")]
            [:button.pill {:replicant/key "end" :type "button" :class (when ended? "on")
                           :on {:click [(save {:period_end (not ended?)})]}}
             (t "Period ended this day")])
     (group "bleeding" (t "Bleeding")
            (for [[kind label default] [["spotting" (t "Spotting") "spotting"] ["period" (t "Period flow") "medium"]]
                  :let [on? (= kind (bleed-kind (:flow log)))]]
              [:button.pill {:replicant/key kind :type "button" :class (when on? "on")
                             :on {:click [(save {:flow (when-not on? default)})]}}
               label]))
     (when-let [kind (bleed-kind (:flow log))]
       (group "amount" (t "How much?")
              (for [f (amounts kind)]
                [:button.pill {:replicant/key f :type "button" :class (when (= f (:flow log)) "on")
                               :on {:click [(save {:flow f})]}}
                 (t (amount-labels f))])))
     (group "symptoms" (t "Symptoms")
            (for [k symptoms
                  :let [n (get have k 0)]]
              [:button.pill {:replicant/key k :type "button" :class (when (pos? n) "pain-on")
                             :title (t "Tap to change: mild, moderate, severe, none")
                             :on {:click [(save {:symptoms (if (= n 3) (dissoc have k) (assoc have k (inc n)))})]}}
               [:span.ico (get symptom-icons k)] (t (get symptom-labels k k))
               (when (pos? n) (str " · " (severity-label n)))]))
     (for [[g ids] tag_groups]
       (apply group g (t (get group-titles g g))
              (for [id ids
                    :let [on? (tags id)]]
                [:button.pill {:replicant/key id :type "button" :class (when on? "on")
                               :on {:click [(save {:tags (if on?
                                                           (remove #{id} (:tags log))
                                                           (conj (vec (:tags log)) id))})]}}
                 [:span.ico (get tag-icons id)] (t (get tag-labels id id))])))
     [:div.sub {:replicant/key "private"} (t "Sex and discharge stay on this computer: the coach never sees them.")])))

(defn- logged-summary
  "What a day's log holds, in a sentence, nil when it is empty."
  [{:keys [period_start period_end flow symptoms tags]}]
  (let [parts (concat (when (= 1 period_start) [(t "Period started this day")])
                      (when (= 1 period_end) [(t "Period ended this day")])
                      (when flow [(t (get flow-labels flow flow))])
                      (for [[k n] symptoms] (str (t (get symptom-labels (name k) (name k))) " · " (severity-label n)))
                      (for [id tags] (t (get tag-labels id id))))]
    (when (seq parts) (apply str (interpose ", " parts)))))

(defn day-card
  "The Day page's cycle card: that day's phase, what is logged and the whole log, to fill in or change."
  [{:keys [today log] :as c} date today-date]
  (let [{:keys [phase cycle_day estimated]} today
        future? (pos? (compare date today-date))]
    [:section.card.cycle-card {:replicant/key "cycle"}
     (ui/label-row {:label (t "Cycle") :info "cycle"})
     (if phase
       [:div.big (t "Day {0}" cycle_day)
        [:small " · " (t (get phases phase phase)) (when estimated (str " (" (t "estimated") ")"))]]
       [:div.sub (t "No period recorded before this day.")])
     (if future?
       [:div.sub (t "You can log a day once it has come.")]
       (list
        [:div.sub {:replicant/key "logged"}
         (if-let [x (logged-summary log)] (t "Logged: {0}" x) (t "Nothing logged for this day yet."))]
        (log-controls c log date)))]))

(defn- stats-line
  "How her cycles usually are, once there is a cycle to tell it from."
  [{:keys [cycles typical variability period_length]}]
  (when (pos? (or cycles 0))
    (->> [(t "Usually {0} days" typical)
          (when (pos? variability) (t "varies by {0} days" variability))
          (when period_length (t "periods last {0} days" period_length))]
         (remove nil?)
         (interpose " · ")
         (apply str))))

(defn card [{:keys [today next_period next_window stats bar flags log contraception periods] :as c} date]
  (let [{:keys [phase cycle_day estimated]} today]
    [:section.card.cycle-card {:replicant/key "cycle"}
     (ui/label-row {:label (t "Cycle") :info "cycle"})
     (if phase
       [:div
        [:div.big (t "Day {0}" cycle_day)
         [:small " · " (t (get phases phase phase))
          (when estimated (str " (" (t "estimated") ")"))]]
        (when bar (list (phase-bar bar) (bar-key bar)))
        [:div.sub
         (cond next_window (t "Next period expected {0} to {1}" (ui/date-short (first next_window))
                              (ui/date-short (second next_window)))
               next_period (t "Next period expected {0}" (ui/date-short next_period))
               (= "overdue" phase) (t "Your period is late. Log its start when it comes.")
               :else nil)
         (when (= "hormonal" contraception) (str " · " (t "Hormonal contraception: bleeding days only")))]
        (when-let [line (stats-line stats)] [:div.sub line])]
       [:div.sub (t "Log the first day of your period and the card follows your cycle from there.")])
     (when (and (= "menstrual" phase) (not= 1 (:period_end log)))
       [:div.sub {:replicant/key "ended?"} (t "Has your period stopped? Mark the last day in Log today.")])
     (for [f flags]
       [:div.error {:replicant/key (str "flag-" f)} (t (get flag-texts f f))])
     [:details.cycle-log {:replicant/key "log"}
      [:summary (t "Log today")]
      (log-controls c log date)
      [:div.sub (t "The coach reads your symptoms when it plans and adapts. {0} periods recorded." (or periods 0))]
      [:a.sub {:href "#/plan?view=calendar"} (t "Forgot a day? Open it in the calendar")]]]))
