# garmin-claude-coach

A personal training dashboard that runs locally on my own Garmin data. It covers Today, Activities and Training load, with TrainingPeaks-style metrics. Today shows intraday heart rate, stress, body battery and steps; every sync keeps the last 7 days of intraday data up to date. The Plan page proposes training weeks with Claude and suggests adjustments when recent training, feedback or wellness call for it. The data comes from a local [garmin_mcp](https://github.com/Taxuspt/garmin_mcp) server and is only ever **read**: nothing is written back to Garmin.

The planner reads two documents: `training-principles.md`, the general method for building a running plan, and the athlete document: the athlete, goal race and season outline. Both are referred to by section heading. You add and edit the athlete document on the Plan page, with Claude. On the same page Claude drafts the season plan to race day; the coach then keeps the next two weeks planned.

**Personal data stays local.** Only the general training principles are in git. The athlete document (`data/athlete.md`), its backups, the season plans, planned sessions and all Garmin data live in `data/`, which git ignores. Personal values the rule checks need (AeT and LT heart rates, readiness thresholds, hard-session and progression limits, long-run cap, race date) are read from the athlete document, never hard-coded.

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
make run                              # http://127.0.0.1:8080/, syncs on start and every 2 h (06-22 h)
make offline                          # port 8090, no syncing
```
`make start` is the same as `make run`. For frontend development, run `make watch` and reload the page.

| Make target | Runs |
|---|---|
| `make run` / `make start` | `clojure -M -m coach.api` |
| `make offline` | `clojure -M -m coach.api 8090 --no-sync` |
| `make watch` | `npm run watch` |
| `make build` | `npm run release` |
| `make sync` | `clojure -M -m coach.sync sync` |
| `make status` | `clojure -M -m coach.sync status` |
| `make test` | `clojure -M:test` |

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
