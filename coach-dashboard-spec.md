# Spec: the new Coach dashboard page

This document describes a dashboard page for **garmin-claude-coach** precisely enough to rebuild it from scratch: what it shows, where every number comes from in the Garmin data, how each number is worked out, and how each card looks and behaves. A working prototype was built and checked against real data on 1 Nov 2026; the example values below come from that run, so you can check your output against them.

Read this together with `docs/style.md`, which stays the source of truth for tokens, type, cards, pills and charts. This spec only adds what is new. Where they disagree, follow `docs/style.md` for styling and this file for content.

---

## 0. Ground rules

- **Stack:** follow the existing app. The backend is Clojure (`coach.api`, `coach.analysis`, `coach.sync`, SQLite in `data/coach.db`), the frontend is ClojureScript with Replicant (`coach.views.*`, `coach.ui`, `coach.charts` on ECharts 6 with the SVG renderer). Do not add a framework, a CSS library or an icon font.
- **Read only.** Nothing is written to Garmin. All data comes through the existing local `garmin_mcp` server and the sync into SQLite. The page reads from the coach's own API, never from Garmin directly.
- **Every visible string goes through `coach.i18n/t`**, with EN, DE (Swiss Standard German) and FR (as written in French-speaking Switzerland) entries.
- **Personal values never go in code.** The goal race (Züri Triple, 11 Apr 2027, 7 h goal) is read from the athlete document (`data/athlete.md`) the same way the rule checks read the race date. Section 5.3 says which values it needs.
- **Missing values show as an em dash "—"**, never `NaN`, `0` or an empty string.
- **Both themes.** Every colour comes from a token in `docs/style.md`; nothing is hard-coded except white text on the race-leg bars.

---

## 1. What the page is for

One question: **"How ready am I today, and how is my training going toward the goal race?"** The page reads top to bottom from today to the long term:

1. **Header**: date, title and a one-sentence takeaway built from today's readiness and HRV.
2. **This morning**: a readiness ring with its six factors, then four vitals tiles (sleep, HRV, resting HR, Body Battery).
3. **Goal race**: a countdown to the Züri Triple, the summed race predictions against the 7-hour goal, and the 10K, half and marathon predictions with 6-month trends.
4. **Recovery, last 30 days**: HRV against the baseline band, sleep stages, Body Battery range, resting HR and stress.
5. **Training**: 28-day KPIs with change, training status, a 26-week activity heatmap, weekly load by sport, load focus against targets, acute vs chronic load, time in HR zones, and the latest activities.
6. **Fitness, last 6 months**: the VO₂ max trend and the endurance score on its class gauge.

Put it on the existing **Dashboard** route (replacing or extending `coach.views.dashboard`) or as a new route `#/overview` with a sidebar link. Ask the owner which before starting if it isn't clear.

---

## 2. Data sources

### 2.1 Garmin calls

The prototype used these Garmin MCP tools. The local `garmin_mcp` server may name some of them differently (it already exposes `get_morning_training_readiness`, `get_training_status`, `get_hrv_data`, `get_sleep_summary_range`, `get_stats`, `get_vo2max_trend`, `get_activities_by_date` and others: see `src/clj/coach/garmin_mcp.clj`). **List the server's tools first** (`tools/list`) and map each row below to the closest one. The JSON underneath is Garmin Connect's in both cases, so the field paths stay the same.

| # | Data | Tool (prototype) | Arguments | Window |
|---|---|---|---|---|
| D1 | Morning readiness | `get_morning_training_readiness` | `cdate` = today | today |
| D2 | Sleep per night | `get_sleep_daily` (≈ `get_sleep_summary_range`) | `start`, `end` | last 30 nights |
| D3 | HRV per night | `get_hrv_data_range` (≈ `get_hrv_data` per day) | `start`, `end` | last 30 nights |
| D4 | Daily health summary | `get_daily_summaries` (≈ `get_stats` per day) | `start`, `end` | last 30 days |
| D5 | Activities | `get_activities_by_date` | `startdate`, `enddate` | last 182 days |
| D6 | Training status and load balance | `get_training_status` | `cdate` = today | today |
| D7 | VO₂ max history | `get_max_metrics_range` (≈ `get_vo2max_trend`, ≤ 90 days a call) | `start`, `end` | last 182 days |
| D8 | Race predictions now | `get_race_predictions` | none | latest |
| D9 | Race predictions by month | `get_race_predictions` | `startdate`, `enddate`, `type: "monthly"` | last 182 days |
| D10 | Endurance score | `get_endurance_score` | `startdate` = today | today |

"Today" is the athlete's local date (Europe/Zurich), not UTC. Every window ends today, inclusive. "Last 30 days" runs from today − 29 to today.

**Answer size.** Garmin's activity objects are about 5 KB each (most of it is a `userRoles` list), and one daily summary is about 3.5 KB. In the prototype, MCP answers over 100,000 characters came back **cut off** and failed to parse. Fetch activities in windows of at most 30 days, and daily summaries in windows of at most 15 days, then merge them and remove duplicates (activities by `activityId`, days by `calendarDate`). Reuse `coach.sync/chunks`. Treat an answer that doesn't parse as a sync failure (`coach.sync/failure`). Never store a partial result.

### 2.2 What to store

Most of this data is already synced. Add only what's missing, and keep the `raw` JSON as the existing tables do:

| Data | Where now | To add |
|---|---|---|
| D1 readiness | `daily_wellness.readiness`, `readiness_level`, `raw` | the six factor percentages, `feedbackShort` and `recoveryTime`: parse them from `raw` in `analysis` if they're there, otherwise store them |
| D2 sleep | `daily_wellness.sleep_score`, `sleep_s`, `deep_sleep_s`, `rem_sleep_s` | `lightTime`, `awakeTime`, `sleepNeed`, `sleepScoreQuality` |
| D3 HRV | `daily_wellness.hrv_avg`, `hrv_weekly_avg`, `hrv_status` | baseline `balancedLow`, `balancedUpper`, `lowUpper` per night |
| D4 daily | `daily_wellness.rhr`, `avg_stress`, `body_battery_high/low`, `steps` | Body Battery `latestValue`, `chargedValue`, `drainedValue` |
| D5 activities | `activities` (+ `raw`), `activity_metrics.hr_zones_s` | nothing: `hrTimeInZone_1..5` are in `raw` |
| D6 status | not stored | new table `training_status(date PK, raw, fetched_at)`, synced once a day |
| D7 VO₂ max | `vo2max` | nothing |
| D8/D9 predictions | not stored | new table `race_predictions(date PK, t5k, t10k, thalf, tmar, raw)`, today's row plus month-end rows |
| D10 endurance | not stored | new table `endurance_score(date PK, score, raw)` |

Add the new syncs to `coach.sync/incremental!`, follow its error handling, and cover them in `test/coach/sync_test.clj`. Keep "a day that comes back empty never replaces a stored day that has data" (`keep-stored`).

### 2.3 Field paths

Paths are into Garmin's JSON. Seconds stay seconds until the UI formats them.

**D1 readiness** (one object; `null` before the watch has computed it after waking):

| Field | Path | Example |
|---|---|---|
| score | `score` | 52 |
| level | `level`: `POOR` · `LOW` · `MODERATE` · `HIGH` · `PRIME` | MODERATE |
| feedback | `feedbackShort` | LISTEN_TO_YOUR_BODY |
| recovery time (h) | `recoveryTime` | 1 |
| sleep factor % | `sleepScoreFactorPercent` | 62 |
| recovery-time factor % | `recoveryTimeFactorPercent` | 99 |
| load balance (ACWR) factor % | `acwrFactorPercent` | 100 |
| stress history factor % | `stressHistoryFactorPercent` | 74 |
| HRV factor % | `hrvFactorPercent` | 28 |
| sleep history factor % | `sleepHistoryFactorPercent` | 38 |

**D2 sleep**: a list of `{calendarDate, values: {…}}`: `sleepScore`, `sleepScoreQuality` (`POOR`/`FAIR`/`GOOD`/`EXCELLENT`), `totalSleepTimeInSeconds`, `deepTime`, `lightTime`, `remTime`, `awakeTime` (all in seconds), `sleepNeed` (**minutes**).

**D3 HRV**: `hrvSummaries[]`: `calendarDate`, `lastNightAvg`, `weeklyAvg`, `lastNight5MinHigh`, `status` (`BALANCED`/`UNBALANCED`/`LOW`/`POOR`), `baseline.balancedLow`, `baseline.balancedUpper`, `baseline.lowUpper`.

**D4 daily**: a list of day objects: `calendarDate`, `movement.steps.value`, `heartRate.restingValue`, `stress.avgLevel`, `bodyBattery.minValue`, `bodyBattery.maxValue`, `bodyBattery.latestValue`, `bodyBattery.chargedValue`, `bodyBattery.drainedValue`. Days with no data are missing from the list, not null.

**D5 activities**: `activityId` (keep as text: it's longer than JS can hold exactly), `activityName`, `startTimeLocal` (date = first 10 characters), `activityType.typeKey`, `distance` (m), `duration` (s), `averageHR`, `activityTrainingLoad`, `hrTimeInZone_1` … `hrTimeInZone_5` (s).

**D6 training status**: values are keyed by device id. Use the entry whose `primaryTrainingDevice` is true, else the first one.

- `mostRecentTrainingStatus.latestTrainingStatusData[dev]`: `trainingStatusFeedbackPhrase` (e.g. `DETRAINING`), `acuteTrainingLoadDTO.{dailyTrainingLoadAcute, dailyTrainingLoadChronic, minTrainingLoadChronic, maxTrainingLoadChronic, dailyAcuteChronicWorkloadRatio, acwrStatus}`
- `mostRecentTrainingLoadBalance.metricsTrainingLoadBalanceDTOMap[dev]`: `monthlyLoadAerobicLow`, `monthlyLoadAerobicHigh`, `monthlyLoadAnaerobic`, each with `…TargetMin` / `…TargetMax`, and `trainingBalanceFeedbackPhrase`
- `mostRecentTrainingStatus.recordedDevices[0].deviceName` (e.g. "Enduro 3")

**D7 VO₂ max**: a list of `{generic: {calendarDate, vo2MaxPreciseValue}}`. Skip entries where `generic` is null.

**D8/D9 predictions**: `calendarDate`, `time5K`, `time10K`, `timeHalfMarathon`, `timeMarathon` (seconds). D8 is one object; D9 is a list with one row per month end plus today.

**D10 endurance**: `overallScore`, `classification`, `gaugeLowerLimit`, `classificationLowerLimitIntermediate`, `…Trained`, `…WellTrained`, `…Expert`, `…Superior`, `…Elite`, `gaugeUpperLimit`.

Example values from 1 Nov 2026: endurance 6093, Trained 5800 to Well-trained 6600, gauge 3570 to 10560. Predictions: 5K 22:31, 10K 48:33, half 1:47:47, marathon 4:03:15. VO₂ max 52.9. Acute load 0, chronic 219, optimal 175 to 329. Load focus: low aerobic 56 (target 204–467), high aerobic 135 (276–539), anaerobic 27 (0–262).

---

## 3. API

Add one endpoint so the page needs a single request plus the existing ones:

```
GET /api/overview?date=YYYY-MM-DD      (date defaults to today)
```

The response is JSON in snake_case like the rest of the API. Every time is in seconds and every date is `YYYY-MM-DD`. Unknown values are `null`.

```jsonc
{
  "date": "2026-11-01",
  "readiness": { "score": 52, "level": "MODERATE", "feedback": "LISTEN_TO_YOUR_BODY", "recovery_h": 1,
                 "factors": { "sleep": 62, "recovery_time": 99, "acwr": 100, "stress_history": 74, "hrv": 28, "sleep_history": 38 } },
  "vitals": { "sleep_score": 75, "sleep_s": 40020, "sleep_quality": "FAIR",
              "deep_s": 4020, "light_s": 27900, "rem_s": 8100, "awake_s": 2520,
              "hrv_night": 44, "hrv_weekly": 44, "hrv_status": "LOW",
              "rhr": 55, "rhr_7d": 53, "rhr_delta": 2,
              "bb_now": 32, "bb_low": 5, "bb_high": 55 },
  "nights":  [ { "date", "sleep_score", "quality", "total_s", "deep_s", "light_s", "rem_s", "awake_s", "need_min",
                 "hrv_night", "hrv_weekly", "hrv_low_up", "hrv_balanced_low", "hrv_balanced_up", "hrv_status" } ],   // 30 rows
  "days":    [ { "date", "rhr", "stress_avg", "steps", "bb_min", "bb_max", "bb_latest", "bb_charged", "bb_drained" } ],    // 30 rows
  "period":  { "hours", "km", "load", "sessions", "hours_delta", "km_delta", "load_delta", "sessions_delta" },
  "heatmap": [ { "date", "load", "minutes", "sessions", "family", "names" } ],   // only days with activity, last 182 days
  "weekly":  [ { "week", "family", "load", "hours", "km", "sessions" } ],        // week = Monday's date
  "zones":   [ { "zone": "Z1", "minutes" } ],                                    // 5 rows, last 28 days
  "status":  { "status", "device", "acute", "chronic", "chronic_min", "chronic_max", "acwr", "acwr_status",
               "aer_low", "aer_low_min", "aer_low_max", "aer_high", "aer_high_min", "aer_high_max",
               "anaer", "anaer_min", "anaer_max", "balance" },
  "recent":  [ { "id", "date", "sport", "name", "km", "duration_s", "avg_hr", "load" } ],   // newest 12
  "vo2":     [ { "date", "vo2" } ],
  "race":    { "name": "Züri Triple", "date": "2027-04-11", "goal_s": 25200,
               "legs": ["10k", "half", "marathon"],
               "now":   { "t5k", "t10k", "thalf", "tmar" },
               "trend": [ { "date", "t10k", "thalf", "tmar" } ],
               "total_s": 23975, "margin_s": 1225, "days_to_race": 161 },
  "endurance": { "score", "limits": { "low", "intermediate", "trained", "well_trained", "expert", "superior", "elite", "high" } }
}
```

Compute everything in Clojure (`coach.analysis`, or a new `coach.overview` namespace). The frontend only formats and draws. Add `test/coach/overview_test.clj` with fixed fixtures covering every formula in §4.

---

## 4. Derived values

"Today" means the request's `date`. Day arithmetic uses calendar dates, not timestamps.

| Value | Rule |
|---|---|
| `vitals.*` | sleep and HRV from the **latest** night in `nights`; RHR and Body Battery from the latest row in `days` (today, which may be partial) |
| `rhr_7d` | mean of `rhr` over the 7 days **before** the latest day (not counting it), rounded to an integer; ignore nulls; null if there are none |
| `rhr_delta` | `rhr − rhr_7d` (positive means higher than usual, which is worse) |
| sport family | from `activityType.typeKey`: contains `run` → run; `cycl`, `bik` or `ride` → bike; `swim` → swim; `hik`, `walk` or `mountaineer` → hike; `ski` or `snowboard` → ski; `strength`, `fitness`, `cardio`, `hiit` or `yoga` → strength; anything else → other. Extend the chip table in `docs/style.md` §5 with a **strength** family (purple: chip bg `--tint-purple`, fg `--purple`, series `--purple`) |
| `period` | sums over activities with `0 ≤ today − date < 28` (the last 28 days, today included), compared with `28 ≤ … < 56`. Hours = Σ duration / 3600, rounded to 0.1. km = Σ distance / 1000, rounded. Load = Σ `activityTrainingLoad`, rounded. Sessions = count. Each `_delta` = now − before, with the same rounding |
| `heatmap` | one row per date with activity: summed load (rounded), summed minutes, number of sessions, names joined with " · ", and `family` = the family of that day's **highest-load** activity |
| `weekly` | group by (Monday of the date, family): Σ load (rounded), Σ hours (0.1), Σ km (0.1), count. The page shows 26 weeks ending with the current week, including empty weeks |
| `zones` | for activities in the last 28 days, Σ `hrTimeInZone_n` / 60, rounded, for n = 1…5 |
| `recent` | the 12 newest activities by start time, newest first |
| `race.total_s` | `t10k + thalf + tmar` from today's predictions (example: 2913 + 6467 + 14595 = 23 975 s = 6:39:35; margin 1225 s ≈ 20 min) |
| `race.margin_s` | `goal_s − total_s` (positive means the prediction beats the goal) |
| `race.days_to_race` | race date − today, in days (11 Apr 2027 − 1 Nov 2026 = 161) |

Remember that the triple total adds up **fresh, separate-race** predictions. The page says so in words; it is not a same-day projection.

---

## 5. Layout and visuals

The overall look is the app's bento style (`docs/style.md` §1): rounded cards on the pale page, with a tinted card for each metric. The prototype ran in a host with its own tokens; use the app's tokens as mapped here.

### 5.1 Colours

| Prototype slot | App token | Used for |
|---|---|---|
| c0 | `--blue` | Body Battery, run family, low-aerobic load, Z2 |
| c1 | `--amber` | bike family, high-aerobic load, awake sleep, Z4, stress 26–50 |
| c2 | `--fam-swim` (`#0e9aa7` / dark `#2bb8c4`) | HRV, swim family, half-marathon leg |
| c3 | `--green` | hike family, VO₂ max, endurance gauge, Z3 |
| c4 | `--pink` | REM sleep, ski family, anaerobic load, marathon leg, race accent, prediction sparklines |
| c5 | `--purple` / `--deep` | deep sleep (`--deep`), light sleep (`--purple-2`), strength family |
| c6 | `--red` | resting HR, Z5 |
| c7 | `--fam-other` | other family, Z1 |
| good / warn / bad | `--green` / `--amber` / `--red` | readiness level, factor bars, deltas, HRV dots |

Status colours always come with a label or a value next to them (`docs/style.md`: "Status is never colour alone").

### 5.2 Header

- Eyebrow (11px / 800 uppercase, `--text-2`): the full date, e.g. "SUNDAY 1 NOVEMBER 2026 · GARMIN".
- `h1`: "Coach" (30px / 800).
- Takeaway, one sentence (16px, `--text-2`, values in 600 `--text`, max 60ch). Pick it by readiness level:
  - `POOR` / `LOW`: "Readiness is **low** at **38** and HRV is **low**. Your body is asking for a rest day, or something very easy."
  - `MODERATE`: "Readiness is **moderate** at **52** and HRV is **low**. Keep today aerobic and easy, and save the hard session for a better morning."
  - `HIGH` / `PRIME`: "Readiness is **high** at **81** and HRV is **balanced**. You are ready for quality work today."
  - no readiness yet: "Your watch hasn't worked out today's readiness yet. It appears after you wake up and sync."
  - Level and HRV status are shown lowercase in the sentence. Translate the whole sentence per language rather than gluing pieces together.
- Keep the existing auth pill and Sync button on the right of the header.

### 5.3 This morning (row 1)

A grid of two columns that wraps under ~720px: on the left the readiness card, on the right a 2×2 grid of tiles (tiles wrap to one column under ~400px).

**Readiness card** (white card; its accent follows the level: green, amber or red):

- Label row: icon chip `bolt` in the soft variant, "Training readiness", spacer, and a pill with `feedback` as a sentence ("Listen to your body").
- **Ring gauge**, 190×170 SVG (viewBox 0 0 200 180, centre 100,96):
  - Arc radius 74 over 270°, from the bottom-left (135°) clockwise to the bottom-right (405°). Track: 14px stroke `--line`, round caps.
  - Value: the same arc with `pathLength=100` and `stroke-dasharray="<score> 200"`, in the level colour.
  - Glow: a copy of the value arc with `feGaussianBlur stdDeviation=6` at 0.45 opacity, behind it.
  - 21 tick marks just outside the arc (r 88 → 92, every 5th → 95), `--text-3`, the major ones 1.6px.
  - Centre: the score at 54px / 800 tabular (`--text`); below it the level in uppercase, 13px / 700, letter-spacing .08em, in the level colour; at the bottom "Recovery 1 h" (11px `--text-3`).
  - First draw: the arc grows from 0 over 1.1s ease-out (`stroke-dashoffset` 100 → 0). Skip this under `prefers-reduced-motion`, and don't replay it on redraws.
- **Factor bars** beside the ring (stacked under it when narrow): six rows on a grid `minmax(6.5rem, 8.5rem) | 1fr | 2.6rem`, gap 10px, 12px text.
  - Order: Sleep, Recovery time, Load balance, Stress history, HRV status, Sleep history.
  - Each row: the label (`--text-2`), an 8px track (`--line`, radius 4), a fill of width = %, and the value "62%" (700, right-aligned).
  - Fill colour: < 34 `--red`, 34–66 `--amber`, ≥ 67 `--green`.
  - First draw: the fills grow from the left with a 60ms stagger.
- Note under it (12px `--text-2`): "Garmin's score from 0 to 100, built from last night's sleep, recovery time, load balance, stress and HRV. The bars show how much each one helps today."

**Four vitals tiles** (tinted cards, min-height 170, label row with an icon chip in the solid variant). Each has a big number (44px `.big`) with a unit in `<small>`, a sub line, and a mini visual pushed to the bottom:

| Tile | Tint / icon | Big | Sub line | Mini visual (44px tall) |
|---|---|---|---|---|
| Sleep score | purple / moon | `75` | "11h 07m · Fair" | stage bar (`.stages`): deep `--deep`, light `--purple-2`, REM `--pink`, awake `--amber`, `flex` = seconds |
| Overnight HRV | swim teal (use `--tint-blue` if no teal tint) / pulse | `44 ms` | "Low · 7-day 44 ms" | sparkline of the last 14 nights, plus the baseline band (balanced low → up) at 0.14 opacity |
| Resting heart rate | red / heart | `55 bpm` | "**+2** vs the 7 days before (53)": the delta is red when positive, green when negative | sparkline of the last 14 days of RHR, with an area fade |
| Body Battery now | blue / battery | `32` | "today 5 to 55" | 14 floating bars from the day's min to max (0–100 scale); today's at full opacity, the rest at 0.55 |

Sparklines: 2px line, round caps, no axes, a 3.5px end dot with a 2px `--surface` outline, and an area that fades from 0.3 to 0 under it. They are `aria-hidden`.

### 5.4 Goal race (row 2)

Two columns that wrap: a hero card, then a column of three prediction cards.

**Hero card**: a white card with two soft radial glows: pink at 22% from the top-left and purple at 20% from the bottom-right, both fading to transparent.

- Eyebrow: "GOAL RACE · ZÜRI TRIPLE · 11 APR 2027". The name and date come from the athlete document.
- Countdown: `161` at clamp(56px, 8vw, 88px) / 800, tracking −0.03em, followed by "days to go" (16px `--text-2`).
- Sentence: "10K, half marathon and marathon on one day. Garmin's predictions, each as a fresh race, add up to **6:39:35**: **20 min inside** the 7-hour goal." When the margin is negative, say "… **12 min over** the 7-hour goal." Format: `m min`, or `h h mm min` from 60 minutes up.
- **Goal bar**, 58px SVG:
  - The three legs as consecutive rounded bars (radius 8, 26px tall, 3px gap) in `--blue` (10K), teal (half) and `--pink` (marathon).
  - The scale runs from 0 to max(goal, total) × 1.04.
  - Inside each leg, "10K 48:33" in white 11px / 700, only when it fits.
  - A dashed 2px `--text` line at the goal, labelled "Goal 7:00:00" under it, clamped inside the bar.
  - The legs fade in with a 120ms stagger.

**Prediction cards** ×3 (10K, half marathon, marathon): a label, the time at 32px / 800 tabular (`48:33`, `1:47:47`, `4:03:15`), and a 54px sparkline of the monthly trend in `--pink` with the **y axis inverted** (lower time = higher on the chart). Below all three, a note spanning their width: "Lines show the prediction at the end of each month over the last 6 months; up means faster."

### 5.5 Recovery, last 30 days

Section heading: `h2` "Recovery" and a muted "Last 30 days" beside it. Below it a card grid: `repeat(auto-fit, minmax(min(100%, 22rem), 1fr))`, gap 14px. Every chart card has a title (14px / 600), a one-line note (12px `--text-2`) and the chart. All charts use the base option from `docs/style.md` §6 (ECharts, SVG renderer, no animation, hairline grid, no axis lines, axis tooltip with the value first in bold).

1. **Overnight HRV against your baseline** (full width, 240px tall)
   - Note: "Nightly average in ms. The shaded band is Garmin's balanced range for you; the dashed line is the 7-day average."
   - The balanced band (`balancedLow` → `balancedUpper`) as a normal-range band in teal at 0.13.
   - 7-day average: 1.5px dashed line in `--text-3`.
   - Nightly line: 2.5px teal, with 3.5px dots outlined in `--surface`. Dot colour: below `lowUpper` `--red`, below `balancedLow` `--amber`, otherwise teal.
   - Y labels like "50 ms"; y domain = data min − 4 to max + 4.
   - Tooltip: the date ("Sat 31 Oct"), overnight, 7-day average, balanced range "51–71 ms", status.

2. **Sleep stages per night** (half width, 230px)
   - A legend pill row: Deep, Light, REM, Awake, Sleep need.
   - Stacked bars in hours (deep, light, REM, awake from the bottom up), radius 3, 1.5px gaps between segments.
   - A 2px `--text` tick across each bar at `sleepNeed / 60` hours.
   - Y axis from 0 to max(9, data); labels like "8 h".
   - Tooltip: sleep score and quality, time asleep, each stage as `h:mm`, and the need.

3. **Body Battery range per day** (half, 230px)
   - A floating bar from `bb_min` to `bb_max` per day, `--blue` at 0.7, radius 5, on a fixed 0–100 axis.
   - A hollow dot (`--surface` fill, 2px `--blue` ring) at `bb_latest`.
   - On hover, the hovered bar goes to full opacity and the rest drop to 0.45.
   - Tooltip: range, end of day, "+charged" and "−drained".

4. **Resting heart rate** (half, 180px)
   - A 2.2px `--red` line with an area fade (opacity 0.26 → 0).
   - The last point gets a 4px dot and an end label "55 bpm" (12px / 700) to the right; reserve right padding by measuring that label.
   - Y = data min − pad to max + pad, where pad = max(1, 20% of the range).

5. **Average stress per day** (half, 180px)
   - Bars coloured by level: ≤ 25 `--blue` (rest), 26–50 `--amber`, > 50 `--red`.
   - A dashed reference line at 25. Y from 0 to max(60, data).
   - Tooltip: average stress and steps.

### 5.6 Training

Section heading: "Training", with the muted note "Last 28 days, change from the 28 days before".

**KPI row**: tiles at `minmax(11rem, 1fr)`.

- Time `8.3 h`, Distance `42 km`, Training load `219`, Sessions `2`.
- Each has the value at 38px (`.big.md`) and a signed delta under it ("+1.2 h", "−3"): green when up, red when down, `--text-2` when 0. (Treat more training as "good" here. That's a choice; flag it if the owner wants it neutral.)
- A fifth tile, **Training status**: the phrase as a sentence ("Detraining", 28px / 800) and "on your Enduro 3" under it.

**Activity heatmap** (full width card)

- Title: "Every day of the last 26 weeks". Note: "One square a day, Monday at the top. Colour is the sport with the most load that day, and the stronger the colour, the bigger the load."
- A legend pill row with only the families that appear.
- Hand-built SVG (not ECharts):
  - 26 columns = weeks, starting on the Monday 25 weeks before this week's Monday; 7 rows, Monday to Sunday.
  - Cell size = clamp(7, floor((width − 30) / 26) − 4, 26), with a 4px gap and radius cell/4.
  - Day labels Mon, Wed, Fri and Sun on the left (11px `--text-3`). Month labels ("Jun") above the first Monday of each month, at least 30px apart.
  - An active day is filled with its family's series colour at opacity 0.3 + 0.7 × √(load / max load); a rest day is filled with `--line`.
  - Days after today are not drawn. Today gets a 1.5px `--ink` outline.
  - Tooltip: the date, the activity names, the load and the time.
  - The cells fade in column by column (18ms stagger).

**Weekly training load by sport** (full width, 230px)

- 26 stacked bars, one per week (Monday dates as categories, labelled "24 Aug"), stacked in family order run, bike, swim, hike, ski, strength, other. Radius 5, 2px gaps.
- On hover, the hovered week stays at full opacity and the others drop to 0.45.
- Tooltip: "Week of 19 Oct", the total load, then each family with its load and hours.

**Load focus, last 4 weeks** (half width)

- Note: "Your load in each intensity band against the range Garmin targets for you (shaded)."
- Three rows: Low aerobic (`--blue`), High aerobic (`--amber`), Anaerobic (`--pink`). Each has:
  - the label (12px / 600) on top;
  - under it a 14px track (`--line`, radius 7);
  - the target range as a 20px box in the band's colour at 0.16 with a dashed 0.5-opacity border;
  - the value as a 10px bar (radius 5);
  - the value on the right (12px / 700), in `--amber` when it is below the target minimum.
- Scale: 0 to max(values, target maxima) × 1.08.

**Acute vs chronic load** (half)

- Note: "The last 7 days' load (acute) against the 4-week load you are used to (chronic). The shaded band is the optimal range."
- A horizontal 12px track. The optimal band (`chronic_min` → `chronic_max`) is `--green` at 0.35.
- A 3px vertical marker for chronic (`--text-2`, labelled under: "Chronic 219") and one for acute (`--text`, labelled above: "Acute 0"; `--red` when `acwr_status` is `HIGH`).
- A foot line: "Ratio 0.0 · Low".

**Time in heart-rate zones** (half)

- Note: "Minutes in each zone over all activities of the last 28 days."
- One 26px stacked bar (radius 8, 3px gaps) in zone colours Z1 `--fam-other`, Z2 `--blue`, Z3 `--green`, Z4 `--amber`, Z5 `--red`.
- Under it a 5-column legend: dot and "Z2", the time as `h:mm`, and the share "13%".

**Latest activities** (full width)

- The table style from `docs/style.md`, sticky header.
- Columns: Date, Sport (a family chip), Name (700, wraps), km (0.1), Time (`h:mm`), Avg HR, Load.
- Numeric columns right-aligned. A row opens `#/activities/<id>`.

### 5.7 Fitness, last 6 months

- **VO₂ max (running)**: the same line style as resting HR, in `--green`, 220px tall, with the end label "52.9". Note: "Garmin's estimate in ml/kg/min, each day it changed."
- **Endurance score**:
  - The big number `6093`.
  - A gauge (92px SVG): seven segments from `gaugeLowerLimit` to `gaugeUpperLimit`, cut at each class limit (Recreational, Intermediate, Trained, Well-trained, Expert, Superior, Elite).
  - Segments are `--green` at opacity rising from 0.15 to 0.9, radius 5, 3px gaps.
  - The current class gets a 1.5px `--text` outline and its label in bold. Other labels show under their segment only when they fit.
  - A down-pointing triangle and a 2px line mark the score.
  - A foot line: "507 points to Well-trained".

---

## 6. Behaviour

- **Loading:** each card keeps its final height (charts have a `min-height`) and shows a skeleton until its data arrives. No spinners inside cards.
- **Failure:** if `/api/overview` fails, show the existing error line and banner (see `coach.views.status`). Inside a card whose data is null, show one centred muted line ("No data yet") instead of an empty chart.
- **Tooltips** stay inside the card, start 14px right of the pointer and flip left near the edge. They are built from text nodes, never `innerHTML` with data.
- **Resize:** every chart redraws from its container's width (`observe-resize!` in `coach.charts`). Nothing may scroll sideways down to 360px wide.
- **Theme switch:** redraw the charts so they pick up the new token values (as the existing charts do).
- **Animations:** only on the first draw that has data, only under `prefers-reduced-motion: no-preference`, and 0.6s or less except the ring.
- **Sync:** after a sync finishes, refetch `/api/overview`.

---

## 7. Formats

| Kind | Format | Example |
|---|---|---|
| Race time | `h:mm:ss`, or `m:ss` under an hour | 4:03:15 · 48:33 |
| Duration | `11h 07m` (tiles, tooltips) · `2:05` (table) | |
| Day in a tooltip | `Sat 31 Oct` (localise the weekday and month) | |
| Axis date | `31 Oct` | |
| Deltas | sign always shown, ASCII minus is fine | +1.2 · -3 |
| Garmin enums | sentence case, underscores → spaces, then translated | LISTEN_TO_YOUR_BODY → "Listen to your body" |
| Big numbers | tabular numerals, no thousands separator under 10 000 | 6093 |

Give every Garmin enum the page shows a translation in `i18n_de.cljc` and `i18n_fr.cljc`: readiness levels and feedback, HRV status, sleep quality, training status phrases, ACWR status and endurance classes.

---

## 8. Acceptance checklist

- [ ] With the 1 Nov 2026 data, the page shows: 28-day time 8.3 h, 42 km, load 219, 2 sessions; readiness 52, Moderate; factors 62 / 99 / 100 / 74 / 28 / 38; sleep 75 (11h 07m, Fair); HRV 44 ms, Low; RHR 55; Body Battery 32; 161 days to the race; predictions 48:33 / 1:47:47 / 4:03:15; VO₂ max 52.9; endurance 6093, Trained; training status Detraining.
- [ ] The HRV chart shows six red dots (14, 20, 27, 30 and 31 Oct and 1 Nov sit below that night's `lowUpper`) and two amber ones (19 and 26 Oct, below `balancedLow` but not below `lowUpper`).
- [ ] The heatmap's longest-hike day (19 Oct, 37.1 km, load 56) shows in the hike colour. The 13 Oct run (load 162) is the strongest cell in its week.
- [ ] Every number on the page traces to a field in §2.3 or a rule in §4.
- [ ] The page works in light and dark, in EN, DE and FR, and at 360px, 760px and 1400px wide, with no horizontal scroll and no overlapping labels.
- [ ] `make test` passes, with new tests for the sync of D6, D8, D9 and D10 and for every formula in §4.
- [ ] A missing value anywhere shows "—", and a day without readiness shows the "not yet" sentence.
- [ ] Nothing is written to Garmin, and no personal value is hard-coded.
