# Development

## Requirements
- Clojure CLI and Java 21
- Node 18 or newer, for the ClojureScript build
- `uv`/`uvx`, which runs the Garmin MCP server

The page makes no outside requests except for map tiles: OpenStreetMap, Esri satellite imagery and
Mapterhorn terrain for the 3D map (`npm install` copies the font and Leaflet's CSS into
`resources/public/`). The server talks to Garmin, Claude and, in the installed app only, GitHub
(the update check).

## Setup
```sh
npm install
npm run release                       # builds resources/public/js/main.js
make run                              # http://127.0.0.1:8081/
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

## Run
| Command | What it does |
|---|---|
| `make run` (or `make start`) | Dashboard on http://127.0.0.1:8081/ (8080 is the installed app's); syncs on start and every 2 h (06–22 h) |
| `make offline` | Dashboard on port 8090, no syncing |
| `make watch` | Rebuilds the frontend on every change (reload the page) |
| `make build` | Release build of the frontend |
| `make sync` | Incremental sync, like the Sync now button |
| `make status` | Row counts and sync progress |
| `make test` | Runs the tests |
| `make lint` | clj-kondo over src and test (keep it at 0 warnings); `make hooks` runs it before every commit |
| `make app` | Builds the installable `target/garmin-coach.jar` (frontend included) |
| `make backup` / `make backups` | Makes a backup / lists them |
| `make restore FILE=…` | Restores a backup (dashboard stopped) |

The data is in `data/`; `COACH_DATA_DIR=… make run` uses another folder, e.g. the installed app's
(`~/.local/share/garmin-coach/data`). Only one app runs on a data folder at a time (a lock file
in it): quit the installed app first.

Single sync steps: `clojure -M -m coach.sync <step>`, where the step is `intraday [days]`,
`history` (everything before the first sync), `thresholds` (LTHR, threshold pace, FTP, HR zones),
`overview` (training status, predictions, endurance, sleep need) or `metrics` (recomputes TSS and
the other metrics). `clojure -M -m coach.auth` shows the Garmin login countdown.

## How it works
**Data.** Every sync stores activities, daily health and per-second activity data in
`data/coach.db` (SQLite), with the raw source JSON kept on every row. Garmin keeps intraday detail
only about 4 months, so each sync saves every day in that window not stored yet; saved days are
kept for good. Training status, race predictions and endurance score are only available for today,
so each sync stores that day's, and their trends fill in over time.

**Planning.** The planner reads `training-principles.md` (the general method) and the athlete
document (the athlete, goal race and season outline, edited with Claude on the Athlete page).
Claude drafts the season plan to race day; the coach then keeps the next two weeks planned. Rule
checks in code guard every plan.

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

The namespaces and the data flow are described in [`CLAUDE.md`](../CLAUDE.md).

## Making a release
Push a version tag (`git tag v1.0.0 && git push origin v1.0.0`): the Release workflow runs the
tests, builds `garmin-coach.jar` and publishes it with the installers. To try an installer with a
local build, run `make app` and then `bash installer/install.sh` (or `installer\install-windows.cmd`)
from the repository: it installs `target/garmin-coach.jar` instead of the release.
