# garmin-claude-coach

A personal training dashboard that runs locally on my own Garmin data. It covers Today, Activities, Trends and Training load, with TrainingPeaks-style metrics. Today shows intraday heart rate, stress, body battery and steps; every sync keeps the last 7 days of intraday data up to date. AI training planning is coming in Phase 5. The data comes from a local [garmin_mcp](https://github.com/Taxuspt/garmin_mcp) server and is only ever **read**: nothing is written back to Garmin.

See `garmin-coach-plan.md` for the design.

## Requirements
- Clojure CLI and Java 21
- Node 18 or newer, for the ClojureScript build
- `uv`/`uvx`, which runs the Garmin MCP server

`npm install` also copies the self-hosted Plus Jakarta Sans font and Leaflet's CSS into `resources/public/`. The page makes no outside requests except for OpenStreetMap map tiles.

## Setup
```sh
clojure -M -m coach.auth login        # Garmin login (email, password, MFA); starts the expiry countdown
npm install
npm run release                       # builds resources/public/js/main.js
clojure -M -m coach.sync backfill     # one time: full history to 2021 (about 1 h)
clojure -M -m coach.sync streams      # one time: per-second data for every activity (about 1 h)
clojure -M -m coach.sync vo2max
```

## Run
```sh
clojure -M -m coach.api               # http://127.0.0.1:8080/ and syncs new data on start
clojure -M -m coach.api 8090 --no-sync
```
For frontend development, run `npm run watch` and reload the page.

## Other commands
| Command | What it does |
|---|---|
| `clojure -M -m coach.sync sync` | Incremental sync, the same as the dashboard's Sync button |
| `clojure -M -m coach.sync intraday [days]` | Fetches intraday HR, stress, body battery and steps for the last N days (default 7). A normal sync does this too. |
| `clojure -M -m coach.sync status` | Shows row counts and sync progress |
| `clojure -M -m coach.sync thresholds` | Fetches LTHR, threshold pace, FTP and HR zones from Garmin |
| `clojure -M -m coach.sync metrics` | Recomputes TSS and the other metrics for every activity |
| `clojure -M -m coach.auth` | Shows the Garmin login countdown |
| `clojure -M:test` | Runs the tests |

All data lives in `data/coach.db` (SQLite). That folder is not tracked by git.
