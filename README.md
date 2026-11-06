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
- **Map**: a heatmap of every activity with GPS, by time range and sport, in five colour schemes on a
  dark, light or satellite map. In 3D the heat lies on the terrain; zoomed out the map becomes a globe
  in front of the real night sky (stars, Milky Way, sun and moon where they are now).
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
A step-by-step guide for people who aren't technical, from installing to costs and backups:
[`docs/user-guide.html`](docs/user-guide.html).

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
or the computer shuts down; the shortcut starts it again, or just opens it if it's running.
Settings › General also has:
- **Start with the computer**: starts the app in the background at login, so syncing never pauses
  (Garmin keeps training status and race predictions only for the day itself).
- **Update now**, when a newer release exists (checked at start and twice a day). It runs the
  installer, which replaces the app and restarts it; the data stays. Running the installer by
  hand does the same.
- **Data folder**: move the data elsewhere (e.g. a folder that is backed up), or point the app
  at a folder that already has coach data. The app restarts.
- **Restore a backup**, from the list or from a file kept elsewhere. The app restarts.

Where things are:

| | Windows | Linux |
|---|---|---|
| App | `%LOCALAPPDATA%\GarminCoach` | `~/.local/share/garmin-coach` |
| Data (unless moved in Settings) | `data\` in the app folder | `data/` in the app folder |
| Log | `app.log` in the data folder | `app.log` in the data folder |
| Uninstall | Start menu › Uninstall Garmin Coach | `~/.local/share/garmin-coach/uninstall.sh` |

The installer downloads Java 21 ([Adoptium](https://adoptium.net)), [uv](https://github.com/astral-sh/uv)
(which runs the Garmin connector and fetches Python for it) and the app itself, `garmin-coach.jar`,
from the latest release.

## Install on a server (phone and always on)
Optional. On a small always-on server the app syncs every 2 h whether the laptop is on or not, and
you open the same dashboard on your phone and laptop. It is never on the open internet: it listens
on the server's 127.0.0.1 only, and [Tailscale](https://tailscale.com) (free for personal use)
passes it on over HTTPS to your own devices, at an address like `https://coach.tail1234.ts.net`.
About half an hour. Full guide: [`docs/server.md`](docs/server.md).

You need a Debian/Ubuntu server with **2 GB of memory or more** (e.g. a Hetzner Cloud VPS for
about €5 a month, with public IPv4 kept on; or a Raspberry Pi 4/5 at home), an SSH key for it, and
Tailscale on your phone and laptop.

1. **Create the server** with Ubuntu 24.04 and your SSH key, and log in: `ssh root@<server-ip>`.
2. **Run the setup script.** It updates the system, opens only SSH in the firewall, sets the time
   zone, creates a user `coach`, installs Tailscale (open the sign-in link it prints) and the app as
   a systemd user service, and turns on `tailscale serve`. It prints the dashboard's address.
   ```sh
   curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/server-setup.sh \
     | sudo bash -s -- --timezone Europe/Zurich
   ```
   Running it again is safe: finished steps are skipped and the app is updated.
3. **Tailscale admin console › Machines:** ⋯ › **Disable key expiry** for the server.
4. **Move your data and logins:** Download backup in the laptop app, then Restore from a file on
   the server's page. Log in to Garmin, paste the Claude key and connect Coros there (or copy
   `~/.garminconnect`, `~/.config/coach/anthropic-key` and `coros.json` over; see the guide).
5. **Laptop app:** turn off Start with the computer and quit it, so only the server syncs.
6. **Phone:** with Tailscale on, open the address and add it to the home screen (on Android in
   Chrome; the DuckDuckGo browser can't open Tailscale addresses). It opens full-screen with the
   app icon.

Updates: **Update now** in Settings, or run the setup script again.

## Making a release
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
font and Leaflet's CSS into `resources/public/`). The server talks to Garmin, Claude and, in the
installed app only, GitHub (the update check).

### Setup
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

### Run
| Command | What it does |
|---|---|
| `make run` (or `make start`) | Dashboard on http://127.0.0.1:8081/ (8080 is the installed app's); syncs on start and every 2 h (06–22 h) |
| `make offline` | Dashboard on port 8090, no syncing |
| `make watch` | Rebuilds the frontend on every change (reload the page) |
| `make build` | Release build of the frontend |
| `make sync` | Incremental sync, like the Sync now button |
| `make status` | Row counts and sync progress |
| `make test` | Runs the tests |
| `make app` | Builds the installable `target/garmin-coach.jar` (frontend included) |
| `make backup` / `make backups` | Makes a backup / lists them |
| `make restore FILE=…` | Restores a backup (dashboard stopped) |

The data is in `data/`; `COACH_DATA_DIR=… make run` uses another folder, e.g. the installed app's
(`~/.local/share/garmin-coach/data`). Only one app runs on a data folder at a time (a lock file
in it): quit the installed app first.

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
  reconnects by itself. If it won't start, `app.log` in the data folder says why.

A failed sync never damages stored data: a day that comes back empty never replaces a stored day.

## Backups
Before the first sync of each day, the dashboard zips the database and the athlete document into
`backups/` in the data folder and keeps the newest 14. **Download backup** in Settings makes a
fresh one to keep elsewhere. **Restore a backup** in Settings restores one from the list or from a
file; the current state is backed up first, and the app restarts. In a terminal (app stopped):
`make restore FILE=data/backups/coach-backup-….zip`.
