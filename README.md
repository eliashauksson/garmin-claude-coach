# garmin-claude-coach

A personal training dashboard that runs locally on my own Garmin data, with Claude as the coach.
Garmin data comes from a local [garmin_mcp](https://github.com/Taxuspt/garmin_mcp) server and is
only ever **read**: nothing is written back to Garmin.

## Pages
- **Dashboard** (start page): this morning's readiness and vitals, the goal race against Garmin's
  race predictions, recovery over 30 days, training over 28 days and 26 weeks, the training load
  (CTL/ATL/TSB), and VO₂ max and endurance score. See `coach-dashboard-spec.md`.
- **Calendar**: activities and planned sessions per week, with a small health block per day. It
  opens the **day page**: that day's health data, including heart rate, stress, Body Battery and
  steps through the day.
- **Plan**: the athlete document, the season plan to race day, and week plans drafted by Claude,
  with adjustments when training, feedback or wellness call for it.
- **Settings**: language, appearance, backup and Garmin login (**General**), and Claude's models
  and costs (**Claude & API costs**).

Each activity has its own page, opened from the dashboard, the calendar or a day.

## How it works

**Data.** Every sync stores activities, daily health and per-second activity data in
`data/coach.db` (SQLite). Garmin keeps intraday detail only about 4 months, so each sync saves
every day in that window not stored yet; saved days are kept for good. Training status, race
predictions and endurance score are only available for today, so each sync stores that day's, and
their trends fill in over time.

**Planning.** The planner reads `training-principles.md` (the general method) and the athlete
document (the athlete, goal race and season outline, edited with Claude on the Plan page). Claude
drafts the season plan to race day; the coach then keeps the next two weeks planned. Rule checks
in code guard every plan.

**Personal data stays local.** Only the general principles are in git. The athlete document, plans
and all Garmin data live in `data/`, which git ignores. Personal values (heart-rate thresholds,
limits, race date and goal time) are read from the athlete document, never hard-coded.

**Languages.** English, German (Swiss Standard German) and French (as written in Switzerland). The
language is a server setting, so the coach's signals and warnings follow it. Texts Claude writes are
translated once when they come back and stored, so switching is instant. To save cost, the
Translations task can be set to translate into the selected language only; switching then takes
about a minute.

**Claude costs.** Settings › Claude & API costs lists every request with its tokens and cost
(at list prices), per day, task and model. Per task you choose the model, the effort and, for plans
and adjustments, the delivery:
- **Batch when automatic** (default): requests nobody waits for, like proposals after a sync, go
  through the half-price Batches API (answers usually within an hour); your own clicks stay instant.
- **Always batch** / **Always instant**.

## Install (Windows and Linux)
No programming tools needed: the installer fetches everything the app needs into one folder,
adds a **Garmin Coach** shortcut to the desktop and the app menu, and starts it. No admin rights.

- **Windows:** download `install-windows.cmd` from the
  [latest release](https://github.com/eliashauksson/garmin-claude-coach/releases/latest) and
  double-click it. If Windows says it protected your PC, click **More info › Run anyway**.
  Or press Win+R and paste:
  `powershell -ExecutionPolicy Bypass -c "irm https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.ps1 | iex"`
- **Linux:** open a terminal (Ctrl+Alt+T) and paste:
  `curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.sh | bash`

The browser opens on the Dashboard, whose **Getting started** checklist walks through the rest:
log in to Garmin and paste a Claude API key (both in Settings › General), then the first sync.

The app then runs in the background (syncing every 2 h) until **Settings › General › Quit the app**
or the computer shuts down; the shortcut starts it again, or just opens it if it's running. To
update, run the installer again: your data stays. Where things are:

| | Windows | Linux |
|---|---|---|
| App and data | `%LOCALAPPDATA%\GarminCoach` (data in `data\`) | `~/.local/share/garmin-coach` (data in `data/`) |
| Log | `data\app.log` | `data/app.log` |
| Uninstall | Start menu › Uninstall Garmin Coach | `~/.local/share/garmin-coach/uninstall.sh` |

The installer downloads Java 21 ([Adoptium](https://adoptium.net)), [uv](https://github.com/astral-sh/uv)
(which runs the Garmin connector and fetches Python for it) and the app itself, `garmin-coach.jar`,
from the latest release.

### Making a release
Push a version tag (`git tag v1.0.0 && git push origin v1.0.0`): the Release workflow runs the
tests, builds `garmin-coach.jar` and publishes it with the installers. To try an installer with a
local build, run `make app` and then `bash installer/install.sh` (or `installer\install-windows.cmd`)
from the repository: it installs `target/garmin-coach.jar` instead of the release.

## Development
### Requirements
- Clojure CLI and Java 21
- Node 18 or newer, for the ClojureScript build
- `uv`/`uvx`, which runs the Garmin MCP server

The page makes no outside requests except for OpenStreetMap map tiles (`npm install` copies the
font and Leaflet's CSS into `resources/public/`).

### Setup
```sh
npm install
npm run release                       # builds resources/public/js/main.js
make run                              # http://127.0.0.1:8080/
```
The Dashboard then shows a **Getting started** checklist until everything is set up:
1. **Connect Garmin**: log in with your Garmin email and password (and the code, with two-step
   verification) in Settings › General. In a terminal: `clojure -M -m coach.auth login`.
2. **First sync**: loads the last 4 weeks (starts by itself after logging in).
3. **Claude**: paste your API key in Settings › General. It's saved in
   `~/.config/coach/anthropic-key`, readable only by you; `ANTHROPIC_API_KEY` works too.
4. **Load full history**: everything before the first sync, then the per-second data of each
   activity (an hour or two, in the background; resumes after a restart). It stops where Garmin
   has nothing older. In a terminal: `clojure -M -m coach.sync history`.
5. **Athlete information and season plan**, on the Plan page.

### Run
| Command | What it does |
|---|---|
| `make run` (or `make start`) | Dashboard on http://127.0.0.1:8080/; syncs on start and every 2 h (06–22 h) |
| `make offline` | Dashboard on port 8090, no syncing |
| `make watch` | Rebuilds the frontend on every change (reload the page) |
| `make build` | Release build of the frontend |
| `make sync` | Incremental sync, like the Sync now button |
| `make status` | Row counts and sync progress |
| `make test` | Runs the tests |
| `make app` | Builds the installable `target/garmin-coach.jar` (frontend included) |
| `make backup` / `make backups` | Makes a backup / lists them |
| `make restore FILE=…` | Restores a backup (dashboard stopped) |

Single sync steps: `clojure -M -m coach.sync <step>`, where the step is `intraday [days]`,
`history` (everything before the first sync), `thresholds` (LTHR, threshold pace, FTP, HR zones), `overview` (training status, predictions,
endurance, sleep need) or `metrics` (recomputes TSS and the other metrics).
`clojure -M -m coach.auth` shows the Garmin login countdown.

## When something goes wrong
A banner above every page says what failed and what to do:
- **Garmin login expired.** Log in again in Settings › General (or run `clojure -M -m coach.auth login`
  and press Sync now). No restart needed.
- **Rate limit, network or Garmin error.** Usually temporary; the next automatic sync tries again.
  If a Garmin error keeps coming back, check `data/mcp-server.log`.
- **Claude credits ran out.** Planning pauses. Add funds on the
  [Claude Console billing page](https://platform.claude.com/settings/billing), then press
  **I've added funds, check again**.
- **The server isn't running.** Open the app from its shortcut (or run `make run`); the page
  reconnects by itself. If it won't start, `data/app.log` says why.

A failed sync never damages stored data: a day that comes back empty never replaces a stored day.

## Backups
Before the first sync of each day, the dashboard zips the database and the athlete document into
`data/backups/` and keeps the newest 14. **Download backup** in Settings makes a fresh one to keep
outside `data/`. To restore, stop the dashboard and run
`make restore FILE=data/backups/coach-backup-….zip`; the current state is backed up first.
In the installed app, quit it and run, in its folder:
`runtime/jre/bin/java -cp app/garmin-coach.jar clojure.main -m coach.backup restore data/backups/coach-backup-….zip`
(on Windows `runtime\jre\bin\java` and backslashes).
