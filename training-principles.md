# AI Coach – Training Principles

> General method for planning endurance running: how to build, adjust and reschedule training from Garmin data. It holds no athlete-specific values. Those live in `athlete.md` (sections §A1–§A9): the athlete, their circumstances, personal zones and baselines, the goal race and the season plan.
>
> Every proposal is reviewed by the athlete before anything enters the plan. The plan lives in the dashboard; nothing is sent to the watch.
>
> Version: 2026-10-31

---

## 0. How to use this document

1. On every run, read: **the athlete (§A1–§A3)**, **the current phase (§3, §A5)**, **recent Garmin data (§8)**, **recorded training vs. the plan (§6)**.
2. Decide the **day status** (§7.1 readiness), then the **week status** (§6: what was done and missed), then build or adjust sessions.
3. Follow the **hard rules (§9)** strictly. Where neither document says anything, apply the **philosophy (§1)**.
4. Give a short reason for every change.

`athlete.md` fills in the numbers this document leaves open (zones in bpm, baselines, volume peak, dates) and adds goal-specific sessions and rules. Where it is more specific, it wins, except over the safety rules (§9).

**Priority if rules conflict:** safety / injury rules > readiness > protected sessions > phase targets > preferences.

---

## 1. Training philosophy

1. **Train for the goal's demands.** The goal race decides what matters most (§A4); build the plan around that ability.
2. **Mostly easy.** 85–90% of running time is easy (Zone E). Quality is a small, precise addition.
3. **Consistency over heroics.** Never "make up" missed sessions. Pick up where it makes sense (§6).
4. **Progress gradually.** Volume rises at most ~10% per week, with a cutback every 3rd–4th week.
5. **Protect the key elements:** long runs, race simulations, taper (§9.3).
6. **Strength is part of running training,** not an extra.
7. **Fuelling is trained** like a physical ability.
8. **When in doubt, do less.** A missed easy day costs nothing. An injury costs weeks.

---

## 2. Zones & paces

Zones are set from the running lactate threshold heart rate (LTHR). HR is the primary measure; pace is a guide only (terrain, heat, fatigue). The athlete's zones in bpm and pace guides are in §A3.

| Zone | Name | HR (% of LTHR) | Use |
|---|---|---|---|
| R | Recovery | < 78% | Day after big efforts, return after time away |
| E | Easy | 78–85% | Most runs, long runs |
| S/MP | Steady / marathon pace | 86–91% | MP blocks inside long runs, steady blocks |
| T | Threshold | 95–99% | One key session per week max, mainly in Build |
| ST | Strides | n/a (short) | 15–20 s fast and relaxed, full walk-back recovery |

### Recalibration
- **5 km time trial** (`TT5`) in the test week at the end of Build 1. New T pace ≈ 5 km pace + 15–20 s/km. New MP ≈ 5 km pace + 50–60 s/km. Sanity-check MP against long runs in Zone S.
- If Garmin updates LTHR by more than ±3 bpm, recalculate all HR zones proportionally and flag the change to the athlete.
- **Easy-run HR drift:** if HR rises above the top of Zone E on an easy run, the athlete should slow down or walk briefly. In analysis, an easy run whose average HR is clearly above Zone E counts as "steady", not "easy" (limit in §A3).

---

## 3. Periodization

A season runs through these phases toward the goal race. The athlete's dates, weekly volumes and long-run targets per phase are in §A5.

| Phase | Goal | Quality |
|---|---|---|
| Reset | Rebuild running frequency and tissue tolerance. All easy. Start strength training | Strides only |
| Base | Aerobic volume, long run up to ~2 h, hill strength. Gut training starts on runs > 90 min | Short hills, 1× steady block |
| Build 1 | Add threshold, extend the long run, introduce MP at the end of long runs. Ends with a cutback + test week (§2) | Threshold |
| Build 2 | Running on tired legs (double days, back-to-backs), long MP blocks | Threshold + MP blocks |
| Specific | Race simulations with race fuelling, kit and start times | MP, little threshold |
| Taper | Cut volume (−30%, −50%, then race week), keep some intensity, sleep | Short MP / threshold touches |

- Each phase ends with, or contains, a cutback week every 3rd–4th week (§9.2).
- Plan a few weeks of slack before the race for time away (§6.3).

---

## 4. Session library

Use these session types. Durations and distances scale with the phase (§3, §A5). Goal-specific sessions are in §A6.

### 4.1 Running
| ID | Session | Structure | Phase |
|---|---|---|---|
| `EASY` | Easy run | 30–75 min Zone E | all |
| `EASY_ST` | Easy + strides | 40–60 min E + 6–8× 20 s ST, walk-back recovery | all |
| `REC` | Recovery run | 20–40 min Zone R | all |
| `HILL_ST` | Hill strides | 15 min warm-up · 8–10× 15 s uphill, hard but smooth · walk down · 10 min cool-down | Reset, Base |
| `HILL_2` | Hill repeats | 15 min warm-up · 6–8× 2 min uphill at T effort · jog down · 10 min cool-down | Base |
| `STEADY` | Steady block | 15 min warm-up · 20–30 min Zone S · 10 min cool-down | Base |
| `THR` | Threshold intervals | 15 min warm-up · 3×10 / 4×8 / 3×12 / 2×15 / 5×6 min T, 2 min jog recovery · 10 min cool-down | Build 1–2, light in Specific |
| `MP_BLOCK` | Marathon pace | 18–20 km total with 10–14 km at MP | Build 2, Specific, Taper |
| `LONG` | Long run | Zone E, distance by phase. Fuel 60–90 g carbs/h from 60 min | all |
| `LONG_MP` | Long run with MP finish | Long run, last 6–10 km at MP | Build 1–2 |
| `DOUBLE` | Double day | Morning long run + evening easy 6–10 km | Build 2, Specific |
| `B2B` | Back-to-back | Sat long run + Sun easy 12–16 km | Build 2, Specific |
| `TT5` | 5 km time trial | 15 min warm-up + strides · 5 km max effort · 10 min cool-down | End of Build 1 (+ optional later) |

### 4.2 Strength (3 sessions/week from Reset to mid Build 2)
| ID | Session | Content | Guidance |
|---|---|---|---|
| `GYM_LOWER` | Lower body, heavy | Back squat or trap-bar DL 4×5 · Romanian DL 3×6–8 (heavy DL every 2nd week instead) · straight-knee calf raise 3×10–12 · bent-knee calf raise 3×15 · hanging leg raise 3×8–12 | Same day as the key run (evening). ≥ 48 h before LONG |
| `GYM_UPPER` | Upper body | Bench/DB press · pull-ups · overhead press · rows · face pulls · optional arms | No leg work. Any day |
| `GYM_BACK_SL` | Back + single-leg (lighter) | Bulgarian split squat 3×8 · single-leg RDL 3×8 · step-ups 3×8 · cable/DB rows · lat pulldown · Copenhagen plank 3×20–30 s · Pallof press 3×10 | ≥ 36 h before LONG |
| `CALF_MIN` | Minimal strength | Calf raises (straight + bent knee) 3×15–20 with backpack + 2× side plank | When no gym is available |

**Strength rules by phase**
- Reset and Base: build technique and load. Start lighter, expect soreness for 2–3 weeks.
- Build 1 to mid Build 2: strongest phase. Leave 1–2 reps in reserve, never train to failure.
- Late Build 2 and Specific: 2 sets per exercise, same load. Drop `GYM_UPPER` or cut it to 30 min.
- Taper: 1 light session/week. **No gym in the last 10 days before the race.**
- Keep reps in the 5–10 range. Don't chase muscle size before the race.

---

## 5. Weekly structure

### 5.1 Standard week (template)
| Day | Morning | Evening |
|---|---|---|
| Mon | `EASY` 45–60' | `GYM_UPPER` |
| Tue | **Key session** (`HILL_*` / `STEADY` / `THR` / `MP_BLOCK`) | **`GYM_LOWER`** |
| Wed | `EASY` / `REC` | optional upper body / core 20' |
| Thu | `EASY_ST` | `GYM_BACK_SL` |
| Fri | – | `EASY` 30–40' or rest |
| Sat | **`LONG`** (or `DOUBLE` / sim) | (2nd run on `DOUBLE` days) |
| Sun | rest | `EASY` 30' from Build 2 / `B2B` Sunday |

The athlete's time-of-day preferences are in §A7.

### 5.2 Sequencing rules
- **Hard days hard, easy days easy:** key run (AM) and `GYM_LOWER` (PM) on the same day.
- ≥ 48 h between `GYM_LOWER` and `LONG` or the next key session.
- ≥ 36 h between `GYM_BACK_SL` and `LONG`.
- At least 1 easy or rest day between the key session and `LONG`.
- Never two key sessions on consecutive days (`LONG` counts as key from Build 1 on).
- At least 1 full rest day per week (no running; upper-body gym allowed).
- Before early-morning key sessions: a small snack + 15' warm-up. Fasted is only OK for `EASY` ≤ 60'.
- **Sleep has priority.** If the athlete reports < 6 h sleep, move morning runs to the evening or skip them.

### 5.3 Priority list (when time is short)
Keep sessions in this order; drop from the bottom:
1. `LONG` (or the phase-specific long format: `DOUBLE`, `B2B`, sims)
2. Key session
3. `GYM_LOWER`
4. `GYM_BACK_SL`
5. `EASY` runs
6. `GYM_UPPER`

**Minimum viable week:** `LONG` + 1× `EASY` + 1× gym (`GYM_LOWER`, or `CALF_MIN` without a gym).

---

## 6. Adapting the plan

### 6.1 Planning without a schedule
The athlete doesn't enter availability in advance. Every week gets the full plan for the current phase (§5.1); a free weekend is a good place for `LONG`, `B2B` or sims.

What happened shows up in the recorded training: sessions that were done, missed (§6.4) or replaced by other activities (§A2). The plan adapts from there. A stretch with no recorded training counts as time away of that length (§6.2), unless the athlete says otherwise.

### 6.2 Return after time away
Time away is not automatically rest: if it involved hard physical work or poor sleep, treat it as load (§A2).

| Length | Return |
|---|---|
| ≤ 3 days | Continue the plan. First day back `EASY` instead of a key session |
| 4–7 days | 2 days `REC`/`EASY`, then continue the plan where it was interrupted |
| 8–14 days | Repeat the last fully completed week at ~80% volume, without `THR`. Then continue |
| 15–21 days | Go back 2 weeks in the plan. First week at ~70% volume, all easy + strides |
| > 21 days | Go back 3–4 weeks. Rebuild via a Reset-style week. Re-check whether the race goals are realistic and tell the athlete |

**Additional return rules**
- **No key session until** HRV and RHR are back in the athlete's normal range on 2 consecutive mornings (thresholds in §A3).
- The first `LONG` after more than 7 days away is at most 80% of the last completed long run.
- `GYM_LOWER` after more than 10 days off: the first session at ~70% of the previous load.

### 6.3 Compressing the plan (when time away eats weeks)
When the remaining weeks no longer fit the structure, cut in this order:
1. Cutback weeks that fall right after time away (the time away already was one).
2. Build 1 weeks (keep at least 2).
3. Build 2 weeks (keep at least 2).
4. **Never cut:** the race simulations or the taper (§9.3).

Keep the full race simulation **3–5 weeks** before the race. If that isn't possible, do a shorter simulation no later than **2 weeks** before the race and skip the full one (sessions in §A6).

### 6.4 Missed sessions
- **Never make up missed sessions** by doubling up later.
- Missed `LONG`: move it within the next 3 days if the sequencing rules (§5.2) allow. Otherwise drop it.
- Missed key session: move it once within the week. Otherwise drop it.
- Missed `EASY`: drop it.
- If more than 30% of the planned running volume is missed in a week: don't raise volume next week; repeat the week.

---

## 7. Daily adjustments (readiness)

### 7.1 Readiness from Garmin data
Check every morning before the planned session. The athlete's baselines and the resulting thresholds are in §A3.

| Signal | Threshold | Action |
|---|---|---|
| HRV last night | below the bottom of the athlete's normal range | Key session → `EASY`; `EASY` → `REC` |
| HRV status | "Unbalanced" or "Low" for ≥ 2 days | Reduce the week by 20%, no key session |
| RHR | ≥ 5 bpm over baseline | Key session → `EASY` or rest |
| RHR + HRV both off | both thresholds above | Rest day |
| Sleep | < 6 h or sleep score < 50 | Move morning runs to the evening, or rest |
| Garmin Training Readiness | < 30 | Key session → `EASY` |
| Body Battery in the morning | < 30 | No key session |
| Illness (athlete-reported) | symptoms below the neck / fever | No training; return as after time away of the same length (§6.2) |

**Single bad nights** (e.g., one isolated HRV dip) only change that day. Don't adjust the whole week.

### 7.2 Session feedback (after each session)
- **Easy run too hard** (average HR above the steady limit in §A3): flag it. Repeated 3× in a week: remind the athlete about pace, and suggest walk breaks or a slower route.
- **Threshold reps clearly faster than target:** after 2 sessions, update the T pace upward by 5 s/km at most.
- **Long run HR drift > 8% in the second half** (at the same pace): don't increase the next long run.
- **RPE ≥ 8 on easy runs or long runs** (athlete-reported): treat it as a fatigue signal.

---

## 8. Garmin data – what to use

| Data | Source (Garmin MCP) | Use |
|---|---|---|
| Activities | `get_activities_by_date` | Volume, type (running / hiking / strength), HR, pace, TE |
| HRV | `get_hrv_data_range` | Readiness (§7.1) |
| RHR | `get_rhr_daily` | Readiness |
| Sleep | `get_sleep_daily` | Readiness, morning vs evening runs |
| Training readiness | `get_training_readiness` | Readiness (secondary) |
| Training status / load | `get_training_status` | Trend only; it misses unrecorded activity (§A2) |
| LTHR / zones | `get_lactate_threshold`, `get_heart_rate_zones` | Zone recalculation (§2) |
| VO2max / race predictions | `get_max_metrics`, `get_race_predictions` | Trend only, not for planning |
| Planned workouts | the dashboard plan (not Garmin) | Plan vs actual |

**Weekly metrics to compute:**
- Running km and time; % easy time (target ≥ 85%)
- Longest run; time on feet including hikes and walks
- Number of completed vs planned sessions (by priority)
- Number of gym sessions
- HRV 7-day average and RHR 7-day average vs baseline

**Activity classification**
- `running` / `trail_running` → running volume.
- `hiking` / `walking` > 90 min → time on feet, not running volume (how it counts: §A2).
- `strength_training` → gym session.
- Anything else → ignore for running volume and mention it in the summary.

---

## 9. Hard rules (never break)

### 9.1 Load
- Weekly running volume: max **+10%** per week (exception: back up to a level already completed, e.g. after time away, max +20%).
- `LONG`: max **+3 km** per week over the previous longest run (race simulations excepted).
- Max **1** threshold session per week. Max **2** quality elements per week (key session + `LONG_MP`/`MP_BLOCK` count).
- Peak weekly volume: never above the athlete's peak (§A5).
- Ramp guard: **pain in the Achilles, shin, knee, hip or foot** that stays during running or is worse the next morning → stop progression and repeat the week. Pain > 3/10 or limping → no running, tell the athlete to get it checked by a doctor or physio.

### 9.2 Recovery
- After a full race simulation: 3–4 days `REC`/`EASY`, no gym legs for 4 days.
- After `B2B` or `DOUBLE`: next day rest, day after `EASY`; no `THR` in the same week before the `B2B`.
- Cutback week every 3rd–4th week (−25% volume), unless time away in the last 2 weeks already provided one.

### 9.3 Protected sessions
- The long runs, race simulations and taper the goal needs (list in §A6).
- **3-week taper.** Compress it only to 2 weeks if absolutely necessary; never less.
- During the taper: no new exercises, no new shoes, no new fuel products.

### 9.4 Never do
- No running on two key days in a row.
- No heavy leg training < 48 h before `LONG` or the key session.
- No "catching up" on missed sessions.
- No training during illness with fever.
- No gym in the final 10 days.
- Never add anything to the plan without the athlete's approval.
