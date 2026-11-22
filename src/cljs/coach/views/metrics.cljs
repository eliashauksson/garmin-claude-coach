(ns coach.views.metrics
  "The Metrics page: what every number in the app means, where it comes from
  and what a given value tells you, written for someone with no sports
  background. Searchable. Static text, grouped like the pages show it."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

;; Each entry: :id (anchor), :name, :src (:garmin = Garmin's own value, shown as it comes; :app = calculated
;; here from your data; nil = an idea, not a number), :what (plain words), :how (where it comes from),
;; :read (what a reading means for you). Optional: :levels [[range meaning]…] (a table), :example (a worked
;; number) and :see (ids of entries that explain a term used here).
(def groups
  [{:id "basics" :title (tk "The basics")
    :intro (tk "Start here if training terms are new to you. The other entries build on these ideas.")
    :items
    [{:id "basics-sources" :name (tk "Garmin's numbers and the app's numbers")
      :what (tk "Some numbers come straight from your Garmin watch and Garmin Connect. Others are worked out by this app from your activities, including rides recorded on a Coros bike computer.")
      :how (tk "Every entry has a label. \"From Garmin\" means the value is Garmin's own, shown as it comes. \"Calculated by the app\" means the app computed it, in the style of the TrainingPeaks coaching software.")
      :read (tk "The two can differ even when they describe the same thing, for example Garmin's training load and the app's TSS. They are on different scales: use each one against itself over time, not against the other.")}
     {:id "basics-trends" :name (tk "Trends matter more than single days")
      :what (tk "Almost every body measurement moves up and down from one day to the next, for reasons that have nothing to do with fitness: a late dinner, a warm room, a bad night, a cold coming.")
      :how (tk "That is why the app shows averages (over 7 days, for example) and a normal range next to a day's value.")
      :read (tk "One odd number is no reason to worry. Several days in a row in the same direction is a signal worth acting on.")}
     {:id "basics-zones" :name (tk "Heart rate, zones and threshold")
      :what (tk "Your heart rate is how hard your body is working. Zones split it into five bands, from Z1 (very easy) to Z5 (all-out). Your threshold is the hardest effort you could hold for about an hour.")
      :how (tk "The zones are set in Garmin Connect from your maximum heart rate. Threshold is found by Garmin from your hard efforts, or estimated by the app.")
      :read (tk "Most of your training should feel easy.")
      :levels [["Z1" (tk "Very easy: warming up, recovery. You could chat in full sentences all day.")]
               ["Z2" (tk "Easy: the base of endurance. You can talk in full sentences, but you notice the effort.")]
               ["Z3" (tk "Moderate: steady and a little uncomfortable. You can say short sentences.")]
               ["Z4" (tk "Hard: around your threshold. A few words at a time. Sustainable for 20–60 minutes.")]
               ["Z5" (tk "Very hard: close to your maximum. Only for short intervals of a few minutes.")]]
      :see ["lthr" "hr-zones"]}
     {:id "basics-load" :name (tk "Training load, fitness and fatigue")
      :what (tk "Training load is how much a workout asks of your body. Fitness is what your body has gradually got used to. Fatigue is the tiredness left over from recent training.")
      :how (tk "Hard training makes you tired first (fatigue rises within days) and fitter later (fitness rises over weeks). The charts follow both.")
      :read (tk "Being tired after hard training is normal and not a sign of being unfit. You get fitter during the rest that follows it. Fitness is built by many steady weeks, not by one big one.")
      :see ["tss" "ctl" "atl" "tsb"]}
     {:id "basics-recovery" :name (tk "Recovery")
      :what (tk "You don't get fitter while you train but afterwards, while you rest and sleep. Recovery is when your body repairs itself and adapts to the effort.")
      :how (tk "The morning numbers (sleep, HRV, resting heart rate, Body Battery, stress) show how well that is going.")
      :read (tk "Good sleep and easy days are part of training, not time off from it.")
      :see ["readiness" "sleep" "hrv" "rhr"]}]}

   {:id "morning" :title (tk "Today")
    :intro (tk "The readiness ring, the four vitals under it (tap one to see its history) and the week strip on the Today page.")
    :items
    [{:id "readiness" :name (tk "Training readiness") :src :garmin
      :what (tk "A score from 1 to 100 that says how ready your body is for hard training today.")
      :how (tk "Garmin combines six things: last night's sleep, your sleep over the recent nights, how long it thinks you still need to recover, your recent training load against what you are used to, your stress over the last days, and your HRV. Each one is shown as a bar.")
      :read (tk "Use the level as a guide for the day. The lowest bar tells you why the score is not higher: it is usually sleep or too much recent training.")
      :levels [["95–100" (tk "Prime: you are at your best. A good day for a race or your hardest session.")]
               ["75–94" (tk "High: ready for hard training.")]
               ["50–74" (tk "Moderate: fine for normal training, perhaps not your hardest effort.")]
               ["25–49" (tk "Low: keep it easy or rest.")]
               ["1–24" (tk "Poor: rest. Training hard today would cost more than it gives.")]]
      :see ["recovery-time" "sleep" "hrv"]}
     {:id "readiness-estimate" :name (tk "Training readiness (estimate)") :src :app
      :what (tk "The app's own version of the readiness score. It appears only on mornings when Garmin has none, and is marked \"Estimate\".")
      :how (tk "Built like Garmin's from the same six things: last night's sleep, the two nights before, HRV against your normal range, recovery hours left from recent workouts, recent load and your stress over the last two days. How much each one counts was worked out from your own mornings that have a Garmin score, and is refitted after every sync.")
      :read (tk "Read it like Garmin's score, with the same levels. On your past mornings it was about 8 points away from Garmin's on average and never more than one level off. It tends to come out too high after a very hard workout.")}
     {:id "recovery-time" :name (tk "Recovery time") :src :garmin
      :what (tk "The number of hours Garmin thinks your body needs before another hard session.")
      :how (tk "Set after each activity from how hard it was, then adjusted by your sleep and stress.")
      :read (tk "A guide, not a rule. Easy training during recovery time is fine. It only says: don't go hard yet. A long recovery time after an ordinary day means you are carrying a lot of tiredness.")}
     {:id "sleep" :name (tk "Sleep score and stages") :src :garmin
      :what (tk "A score from 0 to 100 for last night, and how long you spent in deep sleep, light sleep, REM sleep (when you dream) and awake.")
      :how (tk "Your watch estimates the stages from your heart rate, HRV and movement during the night.")
      :read (tk "The score looks at how long you slept, the stages, and how restful the night was. The stage split is an estimate, so don't rely on the exact minutes. The trend over several nights matters more than one night.")
      :levels [["90–100" (tk "Excellent")]
               ["80–89" (tk "Good")]
               ["60–79" (tk "Fair: you could do with a bit more or better sleep.")]
               ["0–59" (tk "Poor")]]
      :example (tk "Adults need about 7 to 9 hours. Deep sleep is when the body repairs itself; REM sleep helps memory and mood.")
      :see ["sleep-need"]}
     {:id "hrv" :name (tk "Overnight HRV") :src :garmin
      :what (tk "Heart rate variability: how much the time between two heartbeats varies, in milliseconds (ms). A healthy, rested heart does not tick like a metronome; it varies a little.")
      :how (tk "Measured while you sleep and averaged over the night. The 7-day average is compared with your own balanced range, which Garmin learns from about three weeks of your nights.")
      :read (tk "Higher usually means better recovered, lower means stressed or tired. Only compare it with your own range, never with other people: a normal value for one person is low for another. A 7-day average below your range points to fatigue, illness or stress.")
      :see ["vital-history"]}
     {:id "rhr" :name (tk "Resting heart rate") :src :garmin
      :what (tk "Your lowest heart rate of the day while at rest, in beats per minute (bpm).")
      :how (tk "Measured by your watch. The tile compares today with your average of the 7 days before.")
      :read (tk "A fitter heart beats more slowly at rest, so over months of training it slowly falls. A few beats above your usual for several days in a row can mean fatigue, illness, heat, alcohol or stress.")
      :example (tk "Most adults are between 60 and 100 bpm, people who train a lot often between 40 and 60. What matters is your own usual value.")
      :see ["vital-history"]}
     {:id "body-battery" :name (tk "Body Battery") :src :garmin
      :what (tk "Garmin's estimate of your energy reserve, from 0 (empty) to 100 (full), like a phone battery.")
      :how (tk "It charges while you rest and sleep and drains with activity and stress. Garmin works it out from your HRV and heart rate.")
      :read (tk "The chart shows each day's lowest to highest value. Waking up with a low value means a poor night of recovery. As a rough guide, waking up above 75 is well rested, and below 50 means you started the day tired.")
      :see ["vital-history"]}
     {:id "vital-history" :name (tk "History, 7-day average and normal range") :src :app
      :what (tk "Tap a card under the readiness ring to see how that value has moved over 30 days, 90 days, a year or all time.")
      :how (tk "The line is the value of each day. The 7-day average smooths out the ups and downs. The shaded band is your normal range: the average of your last 60 days, give or take one typical swing (one standard deviation). It is worked out here from your own data, and is not Garmin's balanced range for HRV.")
      :read (tk "A day inside the band is an ordinary day for you. A day outside it is unusual, and several in a row are worth noticing. For resting heart rate, above the band is the worrying side. For HRV and sleep, below it is.")
      :see ["basics-trends"]}
     {:id "week-progress" :name (tk "This week") :src :app
      :what (tk "The week strip: your planned sessions for each day, and how much of the week's training time and load you have done so far.")
      :how (tk "Time and training stress score (TSS) of this week's activities, against the planned sessions' time and the season plan's TSS for the week.")
      :read (tk "A bar close to full by Sunday means the week went as planned. Amber means you went past it, which is not always a good thing: more is not better if it leaves you too tired for the next week.")
      :see ["tss"]}]}

   {:id "load" :title (tk "Training load (TSS)")
    :intro (tk "The Load section at the top of the Trends page. The app's own measure of how much you train, in the style of TrainingPeaks, from every activity including rides from Coros.")
    :items
    [{:id "tss" :name (tk "Training stress score (TSS)") :src :app
      :what (tk "A single number for how hard one activity was, taking its length and its intensity into account. 100 means one hour at your threshold, the hardest effort you could hold for an hour.")
      :how (tk "Length times intensity squared, so a hard hour counts for much more than an easy one. The method depends on the data: power and FTP on the bike (TSS), pace on runs (rTSS), swim pace (sTSS), otherwise heart rate (hrTSS). \"hrTSS-scaled\" means heart rate was missing for part of the activity, \"hrTSS-est\" that only the average heart rate was there.")
      :read (tk "Power and pace scores are more precise than heart-rate scores. Compare TSS with TSS, not with Garmin's training load.")
      :levels [["< 150" (tk "Low: you will probably feel fine the next day.")]
               ["150–300" (tk "Medium: some tiredness the next day.")]
               ["300–450" (tk "High: tired for a day or two.")]
               ["450+" (tk "Very high: it can take several days to recover.")]]
      :example (tk "An easy hour is about 40–60 TSS, a hard hour 80–100, and a 4-hour steady ride about 200.")
      :see ["basics-zones"]}
     {:id "ctl" :name (tk "Fitness (CTL)") :src :app
      :what (tk "How much training your body has got used to: your average daily TSS over about the last 6 weeks (CTL stands for chronic training load).")
      :how (tk "A weighted average where recent days count more than old ones (time constant of 42 days), calculated from your first activity on.")
      :read (tk "Higher means more training absorbed. It rises slowly with steady training, and falls slowly in a break. It is yours alone: compare it with your own past, not with other people.")
      :example (tk "A CTL of 50 means you have been training about as much as 50 TSS every day, which is roughly an easy hour a day on average.")
      :see ["basics-load"]}
     {:id "atl" :name (tk "Fatigue (ATL)") :src :app
      :what (tk "How tired recent training has made you: your average daily TSS over about the last week (ATL stands for acute training load).")
      :how (tk "The same calculation as fitness, but with a 7-day time constant, so it reacts fast.")
      :read (tk "It jumps after hard days and drops after rest days. A high value only means you trained a lot lately.")
      :see ["basics-load"]}
     {:id "tsb" :name (tk "Form (TSB)") :src :app
      :what (tk "The balance of fitness and fatigue: how fresh or tired you are going into the day (TSB stands for training stress balance).")
      :how (tk "Yesterday's fitness (CTL) minus yesterday's fatigue (ATL).")
      :read (tk "Below zero you are carrying tiredness, which is the normal price of building fitness. Above zero you are rested. For a race you want to be slightly above zero.")
      :levels [["> 0" (tk "Fresh: rested. Good for a race or a test.")]
               ["−10 – 0" (tk "Balanced: normal training.")]
               ["−30 – −10" (tk "Building: fitness is growing and so is tiredness. Keep recovery days easy.")]
               ["< −30" (tk "High strain: ease off before you get ill or injured.")]]
      :see ["ctl" "atl"]}
     {:id "ramp" :name (tk "Ramp rate") :src :app
      :what (tk "How quickly your fitness is growing: the change of CTL over the last 7 days.")
      :how (tk "Today's CTL minus the CTL 7 days ago.")
      :read (tk "Fast progress feels good, but the body needs time to adapt. Rising too fast is when injuries and illness happen.")
      :levels [["< 0" (tk "Falling: you are training less than you are used to.")]
               ["0–5" (tk "A sustainable build.")]
               ["5–8" (tk "Fast: fine for a short time, not for weeks.")]
               ["> 8" (tk "Too fast: the app calls it overloaded.")]]
      :see ["ctl"]}
     {:id "form-state" :name (tk "Training state") :src :app
      :what (tk "Your form in one word, with a forecast of fitness and form up to race day.")
      :how (tk "From today's form (TSB) and ramp rate. The forecast spreads each season-plan week's TSS over its remaining days. In race week, half of it counts as the race itself.")
      :read (tk "Building is normal in a training block, and Fresh is what you want on race day. The dashed lines show where the plan takes you.")
      :levels [[(tk "Overloaded") (tk "Form below −30 or fitness rising by more than 8 a week. Ease off.")]
               [(tk "Building") (tk "Form between −30 and −10. Fitness is growing; fatigue is the price.")]
               [(tk "Maintaining") (tk "Form between −10 and 0. Load and recovery are balanced.")]
               [(tk "Recovering") (tk "Rested, but fitness is slipping. Fine for a short break.")]
               [(tk "Fresh") (tk "Rested and ready for a race or a hard session.")]]
      :see ["tsb" "ramp"]}
     {:id "volume" :name (tk "Weekly volume") :src :app
      :what (tk "How much you trained each week, as hours, distance or TSS, with the sports stacked on each other.")
      :how (tk "Added up from your activities, Monday to Sunday.")
      :read (tk "It shows how steady your training is and how it builds towards a race. Steady weeks beat a few huge ones.")}]}

   {:id "race" :title (tk "Goal race")
    :intro (tk "The Goal race section on the Trends page: your race, your goal time and what Garmin predicts.")
    :items
    [{:id "race-predictions" :name (tk "Race predictions") :src :garmin
      :what (tk "The finishing times Garmin predicts for a 5 km, 10 km, half marathon and marathon run, if you ran them today.")
      :how (tk "Mostly from your VO₂ max and your recent training. The goal comparison uses the race and goal time from your athlete profile.")
      :read (tk "A prediction is a fitness score in the shape of a time, not a promise. It tends to be optimistic for long races unless you have trained for the distance.")
      :see ["vo2max"]}]}

   {:id "recovery" :title (tk "Recovery")
    :intro (tk "The Recovery section on the Trends page: the last 30 days of sleep, HRV, Body Battery, resting heart rate and stress. The first four are explained under Today.")
    :items
    [{:id "sleep-need" :name (tk "Sleep need") :src :garmin
      :what (tk "How much sleep Garmin thinks you needed last night, shown as the dark tick on the sleep chart.")
      :how (tk "Your baseline need, raised after hard training, poor sleep or high stress.")
      :read (tk "Sleeping well below your need several nights in a row builds up as fatigue, even if you feel fine.")
      :see ["sleep"]}
     {:id "stress" :name (tk "Stress") :src :garmin
      :what (tk "How strained your body is, from 0 to 100, averaged over the day. It is not a feeling: your watch infers it from your heart rate and HRV while you are not moving.")
      :how (tk "Low HRV and a high heart rate at rest mean high stress.")
      :read (tk "It counts every kind of stress, not only mental: illness, heat, alcohol and hard training raise it too. Days with low stress are good recovery days.")
      :levels [["0–25" (tk "Rest")]
               ["26–50" (tk "Low")]
               ["51–75" (tk "Medium")]
               ["76–100" (tk "High")]]
      :see ["hrv"]}]}

   {:id "training" :title (tk "Training")
    :intro (tk "The Training section on the Trends page. It uses Garmin's training load per activity, or the app's estimate for rides Garmin never saw. The Training load (TSS) section above it uses the app's own TSS.")
    :items
    [{:id "garmin-load" :name (tk "Garmin training load") :src :garmin
      :what (tk "How much one activity asked of your body, on Garmin's scale. The heatmap, the weekly bars and the 28-day totals add these up.")
      :how (tk "From EPOC, the extra oxygen your body keeps using after exercise, which Garmin estimates from your heart rate.")
      :read (tk "It lets you compare a run, a ride and a swim fairly. It is on a different scale from TSS, so don't mix them. A hard hour is often around 100–150 on this scale.")
      :see ["tss"]}
     {:id "load-estimate" :name (tk "Training load (estimate)") :src :app
      :what (tk "The app's own version of Garmin's training load, for activities Garmin has none for, such as rides recorded on a Coros bike computer. They count in the Training cards and the readiness estimate like Garmin's.")
      :how (tk "Like Garmin, from EPOC: the load builds up while you train above about a third of your maximum heart rate, much faster the harder you go, eases off when you go easy, and keeps building through long efforts. On a ride with a power meter, power is used as well, so short surges count that a slow heart rate misses. The constants are fitted to Garmin's own load on your activities and refitted as new ones come in.")
      :read (tk "Read it like Garmin's load. On your past activities it was about 12% from Garmin's in the middle case and 9% for rides. Single workouts can be further off.")}
     {:id "acwr" :name (tk "Acute vs chronic load") :src :garmin
      :what (tk "Your training of the last 7 days (acute) compared with what you are used to over the last 4 weeks (chronic).")
      :how (tk "Garmin's acute and chronic load, with their ratio and its optimal range, shown as a band.")
      :read (tk "Around 1 means you train as usual. A sudden jump well above the band is when injuries and illness become more likely. A ratio far below it means you have been resting or detraining.")
      :levels [["< 0.8" (tk "Low: less than you are used to.")]
               ["0.8–1.3" (tk "Optimal: a steady load.")]
               ["1.3–1.5" (tk "High: a quick increase.")]
               ["> 1.5" (tk "Very high: a big jump. Take care.")]]
      :see ["basics-load"]}
     {:id "load-focus" :name (tk "Load focus") :src :garmin
      :what (tk "How your last 4 weeks of training split into three kinds: low aerobic (long easy work), high aerobic (hard steady work) and anaerobic (short, very hard efforts).")
      :how (tk "Garmin sorts each activity's load by intensity and shows a target range for each kind.")
      :read (tk "A kind that sits below its range shows what your training is missing. A runner who only jogs easy has no high aerobic or anaerobic work.")}
     {:id "zones-28d" :name (tk "Time in heart-rate zones") :src :app
      :what (tk "How many minutes you spent in each heart-rate zone, over the last 28 days or in one activity.")
      :how (tk "Counted second by second from each activity's heart rate, with the zone limits from Garmin.")
      :read (tk "Most endurance training should be in the low zones. Hard sessions add a little time in the high ones. If most of your time is in Z3 and Z4, you train a bit too hard for the easy days and too easy for the hard ones.")
      :see ["basics-zones"]}
     {:id "period" :name (tk "28-day totals") :src :app
      :what (tk "Hours, distance, Garmin load and number of sessions over the last 28 days, with the change from the 28 days before.")
      :how (tk "Added up from your activities.")
      :read (tk "Large jumps from one period to the next are worth a second look, in either direction.")}
     {:id "training-status" :name (tk "Training status") :src :garmin
      :what (tk "Garmin's one-word summary of how your training is going.")
      :how (tk "From your VO₂ max trend and your training load. It depends on activities recorded with Garmin, so rides recorded only on another device are missing from it.")
      :read (tk "Productive and Maintaining are good. Unproductive, Strained and Overreaching say your training is not paying off or is too much.")
      :levels [[(tk "Productive") (tk "Your fitness is rising with your training.")]
               [(tk "Maintaining") (tk "Your fitness is steady.")]
               [(tk "Peaking") (tk "You are in top shape, ready to race.")]
               [(tk "Recovery") (tk "A light period to recover.")]
               [(tk "Unproductive") (tk "You train, but your fitness is not rising.")]
               [(tk "Strained") (tk "You train more than your body copes with.")]
               [(tk "Overreaching") (tk "Far too much for too long: rest.")]
               [(tk "Detraining") (tk "Too little training: you are losing fitness.")]]}]}

   {:id "power" :title (tk "Cycling power")
    :intro (tk "Shown once a ride has power from a power meter, from Coros or Garmin. Power is how hard you push the pedals, measured in watts (W).")
    :items
    [{:id "ftp" :name (tk "FTP") :src :app
      :what (tk "Functional threshold power: the most power you could hold for about an hour, in watts. It is the cycling version of your threshold.")
      :how (tk "Estimated as the higher of 95 % of your best 20 minutes and your best hour in the last 90 days. If Garmin has an FTP too, the newer of the two is used.")
      :read (tk "It only goes up when you ride hard for 20 minutes or more, so it can lag behind your real fitness. Training zones and TSS are based on it.")
      :see ["basics-zones"]}
     {:id "wkg" :name (tk "Watts per kg") :src :app
      :what (tk "Your FTP divided by your body weight: how much power you have for each kilogram you carry.")
      :how (tk "Your weight comes from your Garmin profile.")
      :read (tk "It decides how fast you climb. A heavier rider needs more watts for the same speed uphill.")
      :levels [["< 2" (tk "A beginner or an occasional rider.")]
               ["2–3" (tk "A regular rider.")]
               ["3–4" (tk "A fit amateur.")]
               ["4–5" (tk "A strong club racer.")]
               ["5+" (tk "Elite.")]]
      :see ["ftp"]}
     {:id "vo2max-cycling" :name (tk "VO2 max · cycling") :src :app
      :what (tk "An estimate of your VO₂ max (the most oxygen your body can use, a measure of aerobic fitness) from your cycling power.")
      :how (tk "10.8 × your best 5-minute W/kg + 7 (ACSM cycling equation), from the last 90 days.")
      :read (tk "Only as good as your hardest 5 minutes: without an all-out effort it underestimates. It is not comparable with Garmin's running VO₂ max.")
      :see ["vo2max"]}
     {:id "power-curve" :name (tk "Power curve") :src :app
      :what (tk "Your best average power for each length of effort, from 1 second to 1 hour.")
      :how (tk "The best of all rides in the last 90 days, in the 90 days before, and of all time.")
      :read (tk "The left end shows your sprint power, the right end your endurance. Compare the lines to see where you got stronger.")}]}

   {:id "fitness" :title (tk "Fitness")
    :intro (tk "The Fitness section on the Trends page: your fitness over the last 6 months, on Garmin's scale.")
    :items
    [{:id "vo2max" :name (tk "VO₂ max (running)") :src :garmin
      :what (tk "The most oxygen your body can use while exercising, in millilitres per kilogram of body weight per minute. The best single measure of aerobic fitness.")
      :how (tk "Garmin estimates it from your pace and heart rate on outdoor runs. The chart shows each day it changed.")
      :read (tk "A higher value means a fitter body, but it changes slowly: look at weeks, not a single step. Heat, trails and hills can lower it for a while. It falls with age.")
      :example (tk "A healthy untrained adult is typically in the 30s or low 40s. A well-trained recreational athlete is around 50–60, and elite endurance athletes reach 70 or more.")}
     {:id "endurance-score" :name (tk "Endurance score") :src :garmin
      :what (tk "How well you can keep up long efforts, as a single number placed on a scale from Recreational to Elite.")
      :how (tk "Garmin combines your VO₂ max with the load of all your activities over recent weeks and months. The limits between the levels are Garmin's, for your age and sex.")
      :read (tk "It rises with consistent training of any kind and falls during long breaks. The line under the gauge tells you how many points are left to the next level.")}]}

   {:id "activity" :title (tk "One activity")
    :intro (tk "The values on an activity's page, calculated from its second-by-second data.")
    :items
    [{:id "moving-time" :name (tk "Moving time") :src :app
      :what (tk "The time you actually spent moving. The elapsed time next to it includes stops, such as waiting at a traffic light.")
      :how (tk "From the activity's data. Gaps longer than 10 seconds count as pauses.")
      :read (tk "Pace, speed and TSS use moving time, so a coffee stop doesn't make you look slow.")}
     {:id "ngp" :name (tk "Grade-adjusted pace (NGP)") :src :app
      :what (tk "Your running pace as if the run had been on flat ground. Running uphill is slower but harder, and this corrects for that.")
      :how (tk "Each second's speed is adjusted for the slope (Minetti's energy cost of running), then averaged so that the hard parts count more, like NP.")
      :read (tk "It makes hilly and flat runs comparable. It is used for the running TSS (rTSS).")
      :see ["np"]}
     {:id "np" :name (tk "Normalized power (NP)") :src :app
      :what (tk "The steady power that would have cost your body the same effort as your uneven ride, in watts. Surges are harder on the body than steady riding at the same average.")
      :how (tk "A 30-second rolling average of power, raised to the 4th power, averaged, then the 4th root (Coggan).")
      :read (tk "It is higher than the average power when the ride was uneven, and about equal when it was steady. It is used for TSS and IF.")}
     {:id "if" :name (tk "Intensity factor (IF)") :src :app
      :what (tk "How hard the activity was compared with your threshold. 1.00 is the hardest effort you could hold for an hour.")
      :how (tk "NP ÷ FTP on the bike, NGP ÷ threshold pace on runs.")
      :read (tk "A long ride has a low IF and a short race a high one, because nobody can keep a high intensity for hours.")
      :levels [["< 0.75" (tk "Easy: recovery or a long, relaxed ride.")]
               ["0.75–0.85" (tk "Steady: a solid endurance ride.")]
               ["0.85–0.95" (tk "Tempo: comfortably hard.")]
               ["0.95–1.05" (tk "Threshold: close to the most you could do for an hour.")]
               ["> 1.05" (tk "Very hard: a short race or intervals.")]]
      :see ["np" "basics-zones"]}
     {:id "vi" :name (tk "Variability index (VI)") :src :app
      :what (tk "How even your power was over the ride.")
      :how (tk "NP ÷ average power.")
      :read (tk "A high value means lots of stopping, starting and surging, which is more tiring than steady riding at the same average.")
      :levels [["1.00–1.05" (tk "Very steady, like a time trial.")]
               ["1.05–1.10" (tk "Mostly steady.")]
               ["> 1.10" (tk "Lots of surges, like a group ride or hilly terrain.")]]
      :see ["np"]}
     {:id "kj" :name (tk "Work (kJ)") :src :app
      :what (tk "The mechanical work you did on the pedals, in kilojoules.")
      :how (tk "The sum of power over every second.")
      :read (tk "Roughly the number of food calories you burned, because the body turns about 4 kcal of food into 1 kcal of pedalling work: so 1 kJ of work is about 1 kcal used.")}
     {:id "ef" :name (tk "Efficiency factor (EF)") :src :app
      :what (tk "How much output you get for each heartbeat: the faster or harder you go for the same heart rate, the better.")
      :how (tk "NP ÷ average heart rate on the bike, NGP in metres per minute ÷ average heart rate on runs.")
      :read (tk "Compare similar easy sessions over weeks: a rising EF means better aerobic fitness. Don't compare different kinds of session or different days with heat or hills.")}
     {:id "decoupling" :name (tk "Aerobic decoupling") :src :app
      :what (tk "How much your output per heartbeat dropped from the first half of the activity to the second. A sign that your body is tiring or overheating.")
      :how (tk "EF of the first half compared with EF of the second, in percent. Needs at least 20 minutes.")
      :read (tk "A small drop means your endurance holds up at that effort. Heat and hills raise it too.")
      :levels [["< 5 %" (tk "Good: your aerobic endurance holds up at that effort.")]
               ["5–8 %" (tk "Acceptable.")]
               ["> 8 %" (tk "High: you tired or overheated. The coach flags long runs above this.")]]
      :see ["ef"]}
     {:id "peaks" :name (tk "Peak values") :src :app
      :what (tk "Your best average heart rate, speed and power over 5 seconds, 1, 5, 20 and 60 minutes within the activity.")
      :how (tk "The best rolling average for each length of time in the activity.")
      :read (tk "Shows what you did in the hardest parts. The short ones show your sprint, the long ones your endurance.")}
     {:id "planned-vs-actual" :name (tk "Planned vs actual") :src :app
      :what (tk "For an activity that fulfilled a planned session: each block's target next to what you did. The coloured bars in the charts are the targets, one per block.")
      :how (tk "The coach gives every step a target: heart rate when the point is staying in a zone, pace (min/km) when it is about a specific speed, power on rides. The blocks are laid over the activity by time from its start. Each step's actual value is its average over that time (pace from the average speed). A single target value gets a small band (±2 bpm, ±5 s/km, ±5 W).")
      :read (tk "Look at the work blocks first. Pauses and a late start shift the activity against the plan, so later blocks can be off by a few minutes.")}
     {:id "aerobic-te" :name (tk "Aerobic training effect") :src :garmin
      :what (tk "Garmin's rating from 0 to 5 of how much an activity improved your aerobic fitness.")
      :how (tk "From the activity's heart rate and your fitness level.")
      :read (tk "Most sessions should be between 2 and 4. A 5 means more than your body can absorb: expect to need rest.")
      :levels [["0–0.9" (tk "No effect.")]
               ["1.0–1.9" (tk "Minor: recovery.")]
               ["2.0–2.9" (tk "Maintaining your fitness.")]
               ["3.0–3.9" (tk "Improving your fitness.")]
               ["4.0–4.9" (tk "Highly improving.")]
               ["5.0" (tk "Overreaching: more than you can absorb.")]]}]}

   {:id "thresholds" :title (tk "Thresholds")
    :intro (tk "The personal values the app scores your activities with. The line under an activity shows the ones it used.")
    :items
    [{:id "lthr" :name (tk "Lactate threshold heart rate (LTHR)") :src :garmin
      :what (tk "The heart rate you can hold for about an hour. Above it, the body tires quickly; below it, it can go on for a long time.")
      :how (tk "From Garmin's lactate threshold detection. A new value applies from the day it was measured. If Garmin has none, the app estimates it from your hardest efforts of the last year, and marks it as an estimate. The coach then plans a test to find the real one.")
      :read (tk "The heart-rate TSS (hrTSS) counts one hour at this heart rate as 100. A wrong threshold makes all your scores too high or too low, so a measured value is better than an estimate.")
      :see ["basics-zones"]}
     {:id "hr-zones" :name (tk "Heart-rate zones and max HR") :src :garmin
      :what (tk "Your five heart-rate zones and your maximum heart rate (the highest you can reach).")
      :how (tk "As set in Garmin Connect.")
      :read (tk "Used for the time in zones and, with resting heart rate, for hrTSS. Your real maximum is higher than any formula guesses for some people and lower for others, so check it after an all-out effort.")
      :see ["basics-zones"]}
     {:id "threshold-pace" :name (tk "Threshold pace") :src :garmin
      :what (tk "The running pace you can hold for about an hour.")
      :how (tk "From Garmin's lactate threshold detection.")
      :read (tk "The running TSS (rTSS) counts one hour at this pace, adjusted for hills, as 100.")}
     {:id "rest-hr-scoring" :name (tk "Resting heart rate for scoring") :src :garmin
      :what (tk "The resting heart rate of the activity's day, used to work out how hard your heart worked.")
      :how (tk "From Garmin's daily data. On days without it, your usual resting heart rate.")
      :read (tk "hrTSS is based on your heart rate reserve: the range between resting and maximum heart rate. How far up that range you went is what counts.")}]}

   {:id "peaks-page" :title (tk "Peaks")
    :intro (tk "The Peaks page and the peaks on the Map tab: summits your GPS tracks passed.")
    :items
    [{:id "peaks-bagged" :name (tk "Peaks bagged") :src :app
      :what (tk "The number of different summits you have reached. A summit counts as bagged when the GPS track of a hike, run or ride passes within 75 m of its top, at about its height. Virtual and indoor rides never count.")
      :how (tk "After each sync the app fetches the peaks of the map areas your tracks touch from OpenStreetMap, once per area, and checks every new track against them. Only coarse map squares leave your computer, never your tracks. A peak you reached without recording (or one that was wrongly counted) can be added or removed by hand.")
      :read (tk "A peak reached again counts as another visit, not as another peak. Peaks on a list (for example the four-thousanders) always show, even before their height has been worked out.")
      :see ["prominence"]}
     {:id "prominence" :name (tk "Prominence") :src :app
      :what (tk "How far a summit stands above the lowest saddle that leads to higher ground. A 3000 m peak in a big range can have 50 m of prominence if it is only a shoulder of its neighbour; a 170 m hill in Denmark has little more than 10 m.")
      :how (tk "OpenStreetMap marks every bump as a peak, so the app works prominence out from open elevation tiles (about 15 m per pixel), once per peak. For the highest peak around it is only a lower bound, which is plenty to pass the filter.")
      :read (tk "The usual rule for a summit of its own is 30 m, and that is the default. Raise it to 100 m to keep only the real standalone hills and mountains. The elevation tiles are smoothed, so values on sharp alpine summits are a little low.")
      :see ["peaks-bagged"]}]}])

;; ---------------------------------------------------------------------------
;; Search

(defn- norm-char
  "One character without accents, lower case, with sub- and superscripts as normal digits. One in, one out,
  so that positions in a normalised text match the original."
  [c]
  (let [d (str/replace (.normalize c "NFKD") #"[̀-ͯ]" "")]
    (if (seq d) (subs d 0 1) c)))

(defn norm
  "`s` for comparing: lower case, no accents (ü = u, é = e), ₂ = 2. Same length as `s`."
  [s]
  (->> (str/lower-case (str s)) (map norm-char) (apply str)))

(defn words
  "The words of a search text, normalised."
  [q]
  (vec (remove str/blank? (str/split (norm q) #"\s+"))))

(defn- texts
  "Everything a search looks at for an entry, in the current language."
  [group {:keys [name what how read levels example]}]
  (concat (map t [(:title group) name what how read])
          (when example [(t example)])
          (mapcat (fn [[rng meaning]] [(t rng) (t meaning)]) levels)))

(defn matches?
  "True when every word of `ws` is somewhere in the entry (or in its group's title)."
  [ws group entry]
  (or (empty? ws)
      (let [hay (norm (str/join "\n" (texts group entry)))]
        (every? #(str/includes? hay %) ws))))

(defn- occurrences
  "[start end] of each place the normalised word `w` occurs in the normalised text `n`."
  [n w]
  (loop [from 0, acc []]
    (let [i (.indexOf n w from)]
      (if (neg? i) acc (recur (+ i (count w)) (conj acc [i (+ i (count w))]))))))

(defn highlight
  "`s` as a vector of strings and [:mark …] where a word of `ws` occurs, to splice into an element."
  [s ws]
  (if (empty? ws)
    [s]
    (let [n      (norm s)
          merged (reduce (fn [acc [a b]]
                           (if-let [[a0 b0] (peek acc)]
                             (if (<= a b0) (conj (pop acc) [a0 (max b b0)]) (conj acc [a b]))
                             (conj acc [a b])))
                         [] (sort (mapcat #(occurrences n %) ws)))
          [out pos] (reduce (fn [[out pos] [a b]] [(conj out (subs s pos a) [:mark (subs s a b)]) b])
                            [[] 0] merged)]
      (vec (remove #{""} (conj out (subs s pos)))))))

(defn- hl [tag ws s] (into [tag] (highlight s ws)))

;; ---------------------------------------------------------------------------
;; View

(def ^:private names
  "id -> name, for the \"see also\" links."
  (into {} (for [g groups, i (:items g)] [(:id i) (:name i)])))

(defn- source-pill [src]
  (case src
    :garmin (ui/pill "--blue" (t "From Garmin"))
    :app    (ui/pill "--green" (t "Calculated by the app"))
    nil))

(defn- entry
  "One metric. The one a \"?\" link pointed to (`target`) is highlighted and
  scrolled to when the page opens. Words of `ws` (a search) are marked."
  [target ws {:keys [id name src what how read levels example see]}]
  [:div.metric (cond-> {:id (str "m-" id) :replicant/key id}
                 (= id target) (assoc :class "target"
                                      :replicant/on-mount
                                      (fn [{:replicant/keys [node]}]
                                        (js/setTimeout #(.scrollIntoView node #js {:block "center"}) 0))))
   [:div.metric-head (hl :h3 ws (t name)) (source-pill src)]
   (hl :p ws (t what))
   [:dl
    [:dt (t "How it's worked out")] (hl :dd ws (t how))
    [:dt (t "What it means for you")] (hl :dd ws (t read))]
   (when (seq levels)
     (into [:dl.metric-levels]
           (mapcat (fn [[rng meaning]] [(hl :dt ws (t rng)) (hl :dd ws (t meaning))]))
           levels))
   (when example (hl :p.metric-example ws (t example)))
   (when (seq see)
     [:p.metric-see (t "See also") ": "
      (interpose ", "
                 (for [i see]
                   [:a {:replicant/key i :href (str "#/metrics?m=" i)
                      :on {:click [[:scroll-to (str "m-" i)]]}}
                   (t (names i))]))])])

(defn- search-box [q n]
  [:div.metric-search
   [:input.note {:type "search" :value (or q "") :placeholder (t "Search the metrics")
                 :aria-label (t "Search the metrics")
                 :on {:input [[:set-in-ui :metrics :q :event/target-value]]}}]
   (when-not (str/blank? q)
     [:span.sub (if (zero? n) (t "No metric matches \"{0}\"." q) (t "{0} found" n))
      " " [:button.pill {:on {:click [[:set-in-ui :metrics :q ""]]}} (t "Clear")]])])

(defn view [{:keys [route] :as s}]
  (let [q       (get-in s [:ui :metrics :q])
        ws      (words q)
        target  (get-in route [:params :m])
        shown   (for [g groups
                      :let [items (filter #(matches? ws g %) (:items g))]
                      :when (seq items)]
                  (assoc g :items items))
        n       (reduce + (map (comp count :items) shown))]
    [:div {:style {:display "contents"}}
     [:div.page-head [:div.title (ui/back-link) [:h1 (t "Metrics")]]]
     (ui/card {}
              [:p
               (t "What each number in the app means, where it comes from and what a reading tells you. Values marked \"From Garmin\" are Garmin's own, shown as they come. Values marked \"Calculated by the app\" are worked out here from your activities, including rides from Coros.")]
              (search-box q n)
              (when (empty? ws)
                (into [:div.pills]
                      (for [g groups]
                        [:button.pill {:replicant/key (:id g) :on {:click [[:scroll-to (str "g-" (:id g))]]}}
                         (t (:title g))]))))
     (for [g shown]
       [:section.card.metric-group {:replicant/key (:id g) :id (str "g-" (:id g))}
        [:h2.section-title (t (:title g))]
        [:p.sub (t (:intro g))]
        (map (partial entry target ws) (:items g))])]))
