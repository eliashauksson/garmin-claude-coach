(ns coach.views.metrics
  "The Metrics page: what every number in the app means, where it comes
  from and how to read it. Static text, grouped like the pages show it."
  (:require [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

;; Each entry: :id (anchor), :name, :src (:garmin = Garmin's own value, shown
;; as it comes; :app = calculated here from your data), :what, :how, :read.
(def groups
  [{:id "morning" :title (tk "This morning")
    :intro (tk "The cards at the top of the Dashboard. All of them come from your Garmin watch.")
    :items
    [{:id "readiness" :name (tk "Training readiness") :src :garmin
      :what (tk "A score from 0 to 100 for how ready you are for hard training today.")
      :how (tk "Garmin combines last night's sleep, your sleep over recent nights, recovery time, acute load, HRV and stress history. Each factor is shown as a bar.")
      :read (tk "High: a good day for quality work. Low: keep it easy. The lowest factor bar tells you why.")}
     {:id "readiness-estimate" :name (tk "Training readiness (estimate)") :src :app
      :what (tk "The app's own readiness score, shown only on mornings Garmin has none.")
      :how (tk "Built like Garmin's from the same six factors: last night's sleep score, the two nights before, HRV against your normal range, recovery hours left from recent workouts, acute load, and awake stress over the last two days. How much each counts was worked out from your own mornings with Garmin's score, and is refitted after every sync.")
      :read (tk "Read it like Garmin's score, with the same levels. On your past mornings it was about 8 points from Garmin's on average and never more than one level off. After a hard workout it can come out too high.")}
     {:id "recovery-time" :name (tk "Recovery time") :src :garmin
      :what (tk "Hours until Garmin thinks you're recovered enough for another hard session.")
      :how (tk "Set after each activity from how hard it was, then adjusted by sleep and stress.")
      :read (tk "A guide, not a rule: easy training during recovery time is fine.")}
     {:id "sleep" :name (tk "Sleep score and stages") :src :garmin
      :what (tk "A score from 0 to 100 for last night, and how long you spent in deep, light and REM sleep and awake.")
      :how (tk "Garmin estimates the stages from heart rate, HRV and movement during the night.")
      :read (tk "The trend over several nights matters more than one night. The stage split is an estimate.")}
     {:id "sleep-need" :name (tk "Sleep need") :src :garmin
      :what (tk "How much sleep Garmin thinks you needed last night (the dark tick on the sleep chart).")
      :how (tk "Your baseline need, raised after hard training, poor sleep or high stress.")
      :read (tk "Sleeping well below your need several nights in a row adds up as fatigue.")}
     {:id "hrv" :name (tk "Overnight HRV") :src :garmin
      :what (tk "Heart rate variability: how much the time between heartbeats varies, averaged over the night, in milliseconds.")
      :how (tk "Measured while you sleep. The 7-day average is compared with your balanced range, which Garmin learns from about three weeks of your nights.")
      :read (tk "Only compare it with your own range, never with other people. A 7-day average below the range points to fatigue, illness or stress.")}
     {:id "rhr" :name (tk "Resting heart rate") :src :garmin
      :what (tk "Your lowest heart rate of the day while at rest, in beats per minute.")
      :how (tk "Measured by Garmin. The tile compares today with your average of the 7 days before.")
      :read (tk "A few beats above your usual for several days can mean fatigue, illness, heat or alcohol.")}
     {:id "body-battery" :name (tk "Body Battery") :src :garmin
      :what (tk "Garmin's estimate of your energy reserve, from 0 to 100.")
      :how (tk "It charges during rest and sleep and drains with activity and stress, based on HRV and heart rate.")
      :read (tk "The chart shows each day's low to high. Waking up with a low value means a poor recovery night.")}
     {:id "stress" :name (tk "Stress") :src :garmin
      :what (tk "Physiological stress from 0 to 100, averaged over the day.")
      :how (tk "From HRV and heart rate while you're not moving. 0–25 is rest, 26–50 low, 51–75 medium, 76–100 high.")
      :read (tk "It counts all stress, not only mental: illness, heat, alcohol and hard training raise it too.")}]}

   {:id "fitness" :title (tk "Fitness and race")
    :intro (tk "Garmin's long-term fitness values and the goal race card.")
    :items
    [{:id "vo2max" :name (tk "VO₂ max (running)") :src :garmin
      :what (tk "The most oxygen your body can use, in ml per kg of body weight per minute.")
      :how (tk "Garmin estimates it from your pace and heart rate on outdoor runs. The chart shows each day it changed.")
      :read (tk "Changes slowly: a trend over weeks matters, not a single step. Heat, trails and hills can lower it for a while.")}
     {:id "race-predictions" :name (tk "Race predictions") :src :garmin
      :what (tk "The times Garmin predicts for 5 km, 10 km, half marathon and marathon.")
      :how (tk "Mostly from your VO₂ max and recent training. The goal comparison uses the race and goal time from your athlete profile.")
      :read (tk "Optimistic for long races unless you've trained for the distance.")}
     {:id "endurance-score" :name (tk "Endurance score") :src :garmin
      :what (tk "How well you can keep up long efforts, as a single number with levels from Intermediate to Elite.")
      :how (tk "Garmin combines your VO₂ max with the load of all your activities over recent weeks and months. The level limits are Garmin's, for your age and sex.")
      :read (tk "Rises with consistent training of any kind, falls during long breaks.")}
     {:id "training-status" :name (tk "Training status") :src :garmin
      :what (tk "Garmin's one-word summary of your training, such as Productive, Maintaining, Recovery or Detraining.")
      :how (tk "From your VO₂ max trend and your training load.")
      :read (tk "Depends on activities recorded with Garmin, so rides recorded only on another device are missing from it.")}]}

   {:id "training" :title (tk "Training on the Dashboard")
    :intro (tk "The Training cards use Garmin's training load per activity, or the app's estimate for rides Garmin never saw. The Training load section below them uses the app's own TSS.")
    :items
    [{:id "garmin-load" :name (tk "Garmin training load") :src :garmin
      :what (tk "Garmin's load for one activity. The heatmap, the weekly bars and the 28-day totals add these up.")
      :how (tk "From EPOC: the extra oxygen your body uses after exercise, estimated from heart rate.")
      :read (tk "Comparable between sports, but on a different scale from TSS.")}
     {:id "load-estimate" :name (tk "Training load (estimate)") :src :app
      :what (tk "The app's own version of Garmin's training load, for activities Garmin has none for, such as rides recorded on a Coros bike computer. They count in the Training cards and the readiness estimate like Garmin's.")
      :how (tk "Like Garmin, from EPOC: it builds up while you train above about a third of your maximum heart rate, much faster the harder you go, eases off when you go easy, and keeps building through long efforts. On a ride with a power meter, power is used as well, so short surges count that heart rate is too slow for. The constants are fitted to Garmin's own load on your activities and refitted as new ones come in.")
      :read (tk "Read it like Garmin's load. On your past activities it was about 12% from Garmin's in the middle case and 9% for rides; single workouts can be further off.")}
     {:id "acwr" :name (tk "Acute vs chronic load") :src :garmin
      :what (tk "Your load of the last 7 days divided by your usual load of the last 4 weeks.")
      :how (tk "Garmin's acute and chronic load, and its ratio and range.")
      :read (tk "Around 1 means steady training. Far above the marked range means a sudden jump, when injuries and illness become more likely.")}
     {:id "load-focus" :name (tk "Load focus") :src :garmin
      :what (tk "How your load of the last 4 weeks splits into low aerobic, high aerobic and anaerobic.")
      :how (tk "Garmin sorts each activity's load by intensity and shows its target range for each part.")
      :read (tk "A part below its range shows what your training is missing.")}
     {:id "zones-28d" :name (tk "Time in heart-rate zones") :src :app
      :what (tk "Minutes in each heart-rate zone, over the last 28 days or for one activity.")
      :how (tk "Counted second by second from each activity's heart rate, with the zone limits from Garmin.")
      :read (tk "Most endurance training should be in the low zones; hard sessions add a little time in the high ones.")}
     {:id "period" :name (tk "28-day totals") :src :app
      :what (tk "Hours, distance, Garmin load and sessions over the last 28 days, with the change from the 28 days before.")
      :how (tk "Added up from your activities.")
      :read (tk "Large jumps from one period to the next are worth a second look.")}]}

   {:id "load" :title (tk "Training load (TSS)")
    :intro (tk "The app's own load, in the style of TrainingPeaks, from every activity including rides from Coros.")
    :items
    [{:id "tss" :name (tk "Training stress score (TSS)") :src :app
      :what (tk "How hard one activity was: 100 is one hour as hard as you can sustain for an hour (your threshold).")
      :how (tk "Duration × intensity². The method depends on the data: TSS from power and FTP on the bike, rTSS from grade-adjusted pace on runs, sTSS from swim pace, else hrTSS from heart rate. hrTSS-scaled: heart rate was missing for part of it. hrTSS-est: only the average heart rate was there.")
      :read (tk "An easy hour is about 40–60, a hard hour 80–100. Power and pace scores are more precise than heart rate.")}
     {:id "ctl" :name (tk "Fitness (CTL)") :src :app
      :what (tk "Chronic training load: your average daily TSS over about the last 6 weeks.")
      :how (tk "An exponentially weighted average with a 42-day time constant, from your first activity on.")
      :read (tk "Higher means more training absorbed. It rises slowly with consistent training.")}
     {:id "atl" :name (tk "Fatigue (ATL)") :src :app
      :what (tk "Acute training load: your average daily TSS over about the last week.")
      :how (tk "The same as CTL, with a 7-day time constant.")
      :read (tk "Reacts fast: it jumps after hard days and drops after rest.")}
     {:id "tsb" :name (tk "Form (TSB)") :src :app
      :what (tk "Training stress balance: fitness minus fatigue, going into the day.")
      :how (tk "Yesterday's CTL minus yesterday's ATL.")
      :read (tk "Above 0: fresh. −10 to −30: building fitness. Below −30: high strain. For a race, aim slightly positive.")}
     {:id "ramp" :name (tk "Ramp rate") :src :app
      :what (tk "How much your fitness (CTL) changed over the last 7 days.")
      :how (tk "Today's CTL minus the CTL 7 days ago.")
      :read (tk "Up to about 5 per week is a sustainable build. Much more raises the risk of injury and illness.")}
     {:id "volume" :name (tk "Weekly volume") :src :app
      :what (tk "Hours, distance or TSS per week, stacked by sport.")
      :how (tk "Added up from your activities, Monday to Sunday.")
      :read (tk "Shows how steady your training is and how it builds towards a race.")}]}

   {:id "activity" :title (tk "One activity")
    :intro (tk "The values on an activity's page, calculated from its second-by-second data.")
    :items
    [{:id "moving-time" :name (tk "Moving time") :src :app
      :what (tk "Time spent moving. The elapsed time below it includes stops.")
      :how (tk "From the activity's data; gaps longer than 10 seconds count as pauses.")
      :read (tk "Pace, speed and TSS use moving time.")}
     {:id "ngp" :name (tk "Grade-adjusted pace (NGP)") :src :app
      :what (tk "Your pace as if the run had been on flat ground, weighted towards the hard parts.")
      :how (tk "Each second's speed is adjusted for the slope (Minetti's energy cost of running), then normalised like NP.")
      :read (tk "Makes hilly and flat runs comparable. Used for rTSS.")}
     {:id "np" :name (tk "Normalized power (NP)") :src :app
      :what (tk "The power you could have held steadily for the same effort, in watts.")
      :how (tk "30-second rolling average of power, raised to the 4th power, averaged, then the 4th root (Coggan).")
      :read (tk "Higher than average power when the ride was uneven. Used for TSS and IF.")}
     {:id "if" :name (tk "Intensity factor (IF)") :src :app
      :what (tk "How hard the activity was compared with your threshold.")
      :how (tk "NP ÷ FTP on the bike, NGP ÷ threshold pace on runs.")
      :read (tk "Below 0.75: easy. 0.75–0.85: steady. 0.85–0.95: tempo or threshold. Above 1: a short race or intervals.")}
     {:id "vi" :name (tk "Variability index (VI)") :src :app
      :what (tk "How even your power was.")
      :how (tk "NP ÷ average power.")
      :read (tk "1.00–1.05: very steady, like a time trial. Above 1.1: lots of surges, like a group ride or hilly terrain.")}
     {:id "kj" :name (tk "Work (kJ)") :src :app
      :what (tk "The mechanical work done on the pedals, in kilojoules.")
      :how (tk "The sum of power over every second.")
      :read (tk "Roughly the calories you burned: 1 kJ of work is about 1 kcal of energy used.")}
     {:id "ef" :name (tk "Efficiency factor (EF)") :src :app
      :what (tk "Output per heartbeat.")
      :how (tk "NP ÷ average heart rate on the bike, NGP in metres per minute ÷ average heart rate on runs.")
      :read (tk "Compare similar easy sessions over weeks: a rising EF means better aerobic fitness.")}
     {:id "decoupling" :name (tk "Aerobic decoupling") :src :app
      :what (tk "How much your output per heartbeat dropped from the first half of the activity to the second.")
      :how (tk "EF of the first half compared with EF of the second, in percent. Needs at least 20 minutes.")
      :read (tk "Below 5 %: good aerobic endurance at that effort. The coach flags long runs above 8 %. Heat and hills raise it too.")}
     {:id "peaks" :name (tk "Peak values") :src :app
      :what (tk "Your best average heart rate, speed and power over 5 seconds, 1, 5, 20 and 60 minutes.")
      :how (tk "The best rolling average for each duration in the activity.")
      :read (tk "Shows what you did in the hardest parts.")}
     {:id "aerobic-te" :name (tk "Aerobic training effect") :src :garmin
      :what (tk "Garmin's rating from 0 to 5 of how much an activity improved your aerobic fitness.")
      :how (tk "From the activity's heart rate and your fitness level.")
      :read (tk "Below 2: recovery. 2–3: maintaining. 3–4: improving. Above 4: highly improving or overreaching.")}]}

   {:id "power" :title (tk "Cycling power")
    :intro (tk "Shown once a ride has power from a power meter, from Coros or Garmin.")
    :items
    [{:id "ftp" :name (tk "FTP") :src :app
      :what (tk "Functional threshold power: the highest power you can hold for about an hour, in watts.")
      :how (tk "Estimated as the higher of 95 % of your best 20 minutes and your best hour in the last 90 days. If Garmin has an FTP too, the newer of the two is used.")
      :read (tk "It only rises when you ride hard for 20 minutes or more, so it can lag behind your real fitness.")}
     {:id "wkg" :name (tk "Watts per kg") :src :app
      :what (tk "FTP divided by your body weight.")
      :how (tk "Your weight comes from your Garmin profile.")
      :read (tk "What counts on climbs. About 3 W/kg is a fit amateur, 4 strong, 5 and above elite.")}
     {:id "vo2max-cycling" :name (tk "VO2 max · cycling") :src :app
      :what (tk "An estimate of your VO₂ max from cycling power.")
      :how (tk "10.8 × your best 5-minute W/kg + 7 (ACSM cycling equation), from the last 90 days.")
      :read (tk "Only as good as your hardest 5 minutes: without an all-out effort it underestimates. Not comparable with Garmin's running VO₂ max.")}
     {:id "power-curve" :name (tk "Power curve") :src :app
      :what (tk "Your best average power for each duration from 1 second to 1 hour.")
      :how (tk "The best of all rides in the last 90 days, the 90 days before and all time.")
      :read (tk "The left end shows sprint power, the right end endurance. Compare the lines to see where you got stronger.")}]}

   {:id "thresholds" :title (tk "Thresholds")
    :intro (tk "The personal values the app scores your activities with. The line under an activity shows the ones it used.")
    :items
    [{:id "lthr" :name (tk "Lactate threshold heart rate (LTHR)") :src :garmin
      :what (tk "The heart rate you can hold for about an hour.")
      :how (tk "From Garmin's lactate threshold detection. A new value applies from the day it was measured.")
      :read (tk "hrTSS counts one hour at this heart rate as 100.")}
     {:id "hr-zones" :name (tk "Heart-rate zones and max HR") :src :garmin
      :what (tk "Your five heart-rate zones and your maximum heart rate.")
      :how (tk "As set in Garmin Connect.")
      :read (tk "Used for the time in zones and, with resting heart rate, for hrTSS.")}
     {:id "threshold-pace" :name (tk "Threshold pace") :src :garmin
      :what (tk "The running pace you can hold for about an hour.")
      :how (tk "From Garmin's lactate threshold detection.")
      :read (tk "rTSS counts one hour at this pace (grade-adjusted) as 100.")}
     {:id "rest-hr-scoring" :name (tk "Resting heart rate for scoring") :src :garmin
      :what (tk "The resting heart rate of the activity's day.")
      :how (tk "From Garmin's daily data; on days without it, your usual resting heart rate.")
      :read (tk "hrTSS is based on heart rate reserve: the range between resting and maximum heart rate.")}]}])

(defn- source-pill [src]
  (if (= src :garmin)
    (ui/pill "--blue" (t "From Garmin"))
    (ui/pill "--green" (t "Calculated by the app"))))

(defn- entry
  "One metric. The one a \"?\" link pointed to (`target`) is highlighted and
  scrolled to when the page opens."
  [target {:keys [id name src what how read]}]
  [:div.metric (cond-> {:id (str "m-" id) :replicant/key id}
                 (= id target) (assoc :class "target"
                                      :replicant/on-mount
                                      (fn [{:replicant/keys [node]}]
                                        (js/setTimeout #(.scrollIntoView node #js {:block "center"}) 0))))
   [:div.metric-head [:h3 (t name)] (source-pill src)]
   [:p (t what)]
   [:dl
    [:dt (t "How it's worked out")] [:dd (t how)]
    [:dt (t "How to read it")] [:dd (t read)]]])

(defn view [{:keys [route]}]
  [:div {:style {:display "contents"}}
   [:div.page-head [:div [:h1 (t "Metrics")]]]
   (ui/card {}
            [:p (t "What each number in the app means, where it comes from and how to read it. Values marked \"From Garmin\" are Garmin's own, shown as they come. Values marked \"Calculated by the app\" are worked out here from your activities, including rides from Coros.")]
            (into [:div.pills]
                  (for [g groups]
                    [:button.pill {:replicant/key (:id g) :on {:click [[:scroll-to (str "g-" (:id g))]]}}
                     (t (:title g))])))
   (for [g groups]
     [:section.card.metric-group {:replicant/key (:id g) :id (str "g-" (:id g))}
      [:h2.section-title (t (:title g))]
      [:p.sub (t (:intro g))]
      (map (partial entry (get-in route [:params :m])) (:items g))])])
