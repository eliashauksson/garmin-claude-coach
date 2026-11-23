# CLAUDE.md

Personal Garmin dashboard + AI training planner: syncs Garmin data (plus rides from a Coros bike computer) into local SQLite, computes TrainingPeaks-style metrics, and asks Claude to propose training the athlete accepts or rejects. Sync never writes to Garmin or Coros; the only write is a planned workout the athlete sends with the "Send to device" button (coach.push).
Stack: Clojure 1.12 backend (reitit/ring/jetty, next.jdbc + SQLite, malli, anthropic-java), ClojureScript via shadow-cljs + Replicant (ECharts, Leaflet), shared `.cljc` for i18n.

## Commands
- `make run`: dashboard on http://127.0.0.1:8081/, syncs on start and every 2 h (8080 is the installed app's port)
- `make offline`: port 8090, no sync · `make watch`: shadow-cljs watch (reload the page) · `make build`: `npm run release`
- `make sky`: regenerates the 3D globe's stars + Milky Way (dev/sky_data.clj → resources/public/sky/)
- `make test`: `clojure -M:test` (cognitect test-runner; one ns: `clojure -M:test -n coach.plan-test`)
- `make lint`: clj-kondo over src + test (`.clj-kondo/config.edn`); keep it at 0 warnings · `make hooks`: runs it before every commit (.githooks/pre-commit)
- `make app`: frontend release + `clojure -T:build uber` → target/garmin-coach.jar (installer/)
- `make sync` / `make status` (coach.sync CLI) · `make backup` / `backups` / `restore FILE=…` (coach.backup CLI)
- Data folder: `$COACH_DATA_DIR`, else config.edn, else data/ (coach.paths)

## Namespaces (src/clj)
- api: JSON API under /api + static files, 127.0.0.1 only: the route table, server start/stop, `-main` entry point
  - api.jobs: the background sync/Coros/history jobs and their state (`sync-state`) · api.security: which requests may
    call the API (other websites, dashboard token, main athlete only) · api.http: JSON responses, request body, 400/500 handling
- app: installed app: version/updates, autostart, restart, :pending actions in config.edn
- paths: app/data folder resolution, lock file, server mode + allowed hosts (docs/server.md); `instance`: an extra
  athlete on a server (installer/add-athlete.sh) gets its own config-<name>.edn, data (athletes/<name>/data),
  Garmin/Coros logins and dashboard token (`per-instance`); only the Claude key and sync.lock (syncs take turns) are shared.
  One app serves all athletes: athletes.edn in the app folder lists name + port, `server-athletes`, started by `api/-main --server`
  (without it, $COACH_INSTANCE runs one app per athlete as before)
- context: the current athlete (`*athlete*`, bound per request/job: `with-athlete`, `athlete-fn` for executors; futures carry it) and
  `per-athlete-atom`, an atom with one value per athlete. State that is per athlete (sync state, DB handles, language, tokens) uses it;
  a new global `atom` that holds an athlete's data must too
- push: sends one planned workout to Garmin Connect (upload + schedule) or the COROS schedule, on the athlete's button press only; Garmin on its own
  write-only server (`garmin-mcp/write-tools`, refused while a sync runs), COROS via `coros-mcp/call-write`; remembers what was sent so resending replaces it
- device-workout: pure, a planned session's blocks as a Garmin workout / COROS course, and `sendable`
- source: `DataSource` protocol (read-only), `ActivitySource` for activity-only sources · garmin-mcp: DataSource over a local garmin_mcp server · mcp: minimal MCP client (stdio, Streamable HTTP)
- coros-mcp: ActivitySource over COROS's official remote MCP server (rides from FIT files) · coros-auth: Coros OAuth (PKCE, token in ~/.config/coach/coros.json) · fit: FIT file decoding (Garmin FIT SDK)
- sync: incremental + backfill from a DataSource into the DB (upserts) · sync.sources: rides from activity-only sources (`activities-only!`, `coros!`), merged with the same ride on Garmin
- db: SQLite schema/queries; raw source JSON kept on every row
- metrics: pure TSS/NP/IF/CTL/ATL/TSB, power curve/eFTP/cycling VO2 max etc. · analysis: glue between db and metrics, API queries, eFTP stored as bike FTP (source "estimate")
- overview: everything behind the dashboard start page (/api/overview)
- readiness: pure morning readiness check · adapt: signals that the plan should adapt
- readiness-score: pure, Garmin's training readiness rebuilt from raw data (six factors + combination, `fit`) · readiness-estimate: its DB glue, shown when Garmin has no score, refit after each sync, CLI `compare`
- epoc: pure, Garmin-style training load (peak EPOC) from HR or power, acute load · load-model: its DB glue, fitted to Garmin's loads (refit every 20 new ones), power calibration, CLI `compare`; gives rides Garmin never saw (Coros) `activity_metrics.load_est`
- plan-rules: pure checks of a week against training-principles.md (warnings only)
- season: date helpers + week position in the season · season-plan: Claude drafts every week to race day
- plan: storage for weeks, proposals and accepted workouts · planner: Claude week proposals, adjustments, session structure
- claude: every Claude call (`request`, half-price batches), API key, shared system prompt (`knowledge`/`principles`)
- ai-settings: model/effort/delivery per task (`tasks`, `defaults`) · costs: per-request Claude cost log, `prices`
- translate: translates Claude's texts once into all languages (translations table)
- athlete: athlete.md read/save/backup · athlete-chat: editing athlete.md as a chat with Claude
- auth: Garmin login expiry countdown · backup: zip snapshots of DB + athlete docs
- peaks: peak bagging, komoot-style. **The peaks are the summits of the Kirmse/de Ferranti world prominence dataset (2023, every
  summit ≥ 30 m prominence); we calculate no heights ourselves.** The dataset is cut into 10° blocks (`make prominence-data`,
  dev/prominence_data.clj) and, with `official-ch.csv.gz` (swisstopo's swissNAMES3D summits, `make official-names`), uploaded as
  assets of the GitHub release `peaks-data-2023` of this repo (`peaks/data-url`; `$COACH_PEAKS_DATA_URL` may be a URL or a
  folder, for dev/tests). resources/peaks/blocks.edn lists the blocks that exist (rest of the world is sea).
  Flow per requested 1° square (`run-once!`): `load-datasets!` (the square's block into `peaks`, Switzerland's official names
  once, then `rematch-square!`) → detection for activities whose blocks are loaded → `load-names!` (OSM `natural=peak|volcano|hill` nodes with a `name` or `name:xx` per tile through Overpass, then rematch; `peak_tiles.query_version` vs
  `peaks/osm-query-version`: bump it when the query asks for more and tiles of an older version are fetched again, still shown as ready) → `relink-summits!`. Rematching is from scratch and idempotent.
  A dataset summit's id comes from its position (`summits/dataset-peak-id`, ≥ 10^13). Names: swisstopo (Switzerland only) > OSM, one donor per summit
  (`summits/match-donors`, ≤ 250 m, donor height −60…+250 m over the dataset's, which runs low on sharp summits); a last pass (`summits/loose-pairs`) lets a leftover named donor within 600 m and ±20 m of a summit name it if mutual nearest; a donor's
  height replaces the dataset's `ele` (`ele_dem` keeps it). OSM/official points with no summit nearby are not shown; a summit nobody named shows "Near X" (`peaks.near_name`/`near_m`: the nearest named summit, OSM or official point within 3 km,
  `summits/nearest-names`, set by `rename-nearby!` at the end of every `rematch-square!`; one-time `backfill-near-names!`) or, with nothing near, unnamed with its height. `peak_links` (OSM id → summit) moves athletes' old bags
  (athlete `summits.osm_id` was renamed `peak_id`). The dataset's licence is not stated: ask the author before publishing a release.
  Squares come from three places: the default (Switzerland), the athlete's picks on the Peaks page map (≤ 100 at once, name
  search through Nominatim), and every athlete's tracks: the first round of each athlete queues all squares their activities
  touch, then each round those of the activities not checked yet (`queue-track-squares!`; virtual/indoor sports touch none).
  The whole world's dataset can be asked for in one go (main athlete, Peaks page; `request-world!`, `/api/peaks/world`, meta `world`): `world-work!`
  (its own worker and lock `peaks-world`, started by `jobs/peaks-soon!`, so it never waits behind the names) loads every block of
  blocks.edn, one per step, resuming after a stop or failure (~1.5 GB in shared/peaks.db). No Overpass names for it
  (weeks for ~200k tiles): those still come per requested square, other summits show their height or "Near X".
  One peak worker runs at a time for the whole install (`peaks-work` lock) and waits between steps while the machine's
  CPU is above `peaks/cpu-limit` (60%); detection indexes the track in a grid, so a peak is only compared with nearby fixes.
  Square states: queued (block missing) → names (peaks loaded, OSM names to come) → ready; failed keeps the error and retries.
  `request!`/`work!` run one background worker per athlete (after each sync, at start, when squares are added). Shown: a peak
  with prominence ≥ the athlete's minimum (30 m, `sync_state`). The map gets only what its view draws (`/api/peaks?bbox&zoom&cell`:
  `coach.peak-density` prominence floors per zoom, then the most prominent √n peaks per ~80 px cell; there are 11.9 M peaks in the
  world, 1 M on the server): never load the whole `peaks` table. The `listed` columns are unused (peak lists were removed).
  Shared by all athletes: `peaks`, `dataset_blocks`, `official_peaks`, `peak_links`, `osm_peaks`, `peak_tiles`, `peak_squares`,
  `meta` live in shared/peaks.db (`$COACH_SHARED_DIR`, else shared/ in the app folder; `paths/shared-file`), so a block one
  athlete loads is there for everybody; downloads take turns (`paths/with-lock "peaks-fetch"`). Per athlete: `summits`
  (gps | manual | dismissed), `summits_checked`, the minimum prominence. No SQL joins across the two databases (merged in Clojure).
  `activity_metrics.peaks` is something else (best HR/power), hence "summits"
  - summits: pure, detection (track within 75 m of the top and, with altitude, within 80 m below it), tiles/squares,
    dataset ids and `match-donors`
  - privacy: GitHub sees which 10° blocks are loaded, Overpass the tile/square boxes, Nominatim the search text; no elevation server any more; tracks never leave the machine

## Namespaces (src/cljs, src/cljc)
- app: state, routing, `fetch!` of /api URLs · ui: formatters + shared components · charts: ECharts/Leaflet mount
  Tabs: Today `#/`, Plan `#/plan?view=…`, Trends `#/trends`, Map `#/map`, More `#/more` (opens `#/athlete`, `#/metrics`,
  `#/settings`; their active tab is More). Activity, Day and Session pages light up Plan. Old links: `#/calendar` →
  Plan calendar view, `#/costs` → Settings' Claude tab, `#/activities` and unknown routes → Today
- heat: the Map tab's heatmap (canvas; grounds: OSM dark/light, Esri satellite, Tracestrack Topo (athlete's key in config.edn `:tracestrack-key`, Settings → Maps, `/api/map-keys`), OpenTopoMap, CyclOSM, swisstopo, Humanitarian: `extra-grounds`; their tile servers see the viewed area) and activity pins (start points merged by `track/cluster`, a click opens a popup
  linking to the activity), 2D on Leaflet, 3D terrain on MapLibre GL 6 (not bundled: resources/public/vendor/maplibre/, copied by `npm install`, loaded on first 3D use)
- views/: today (start page; its cards in views/dashboard/), trends (long-term sections, jump bar), plan (`?view=week|calendar|season`),
  calendar (the Plan page's calendar view), heatmap (Map tab), more (the More tab's list), athlete, settings, costs,
  metrics (explains every metric), day, activity, feedback, load, season, status (error banner), workout (a planned
  session's card), structure (block profile/list, planned vs actual bits)
- cljc i18n: `t`/`tk`, language from server · i18n-de / i18n-fr: dictionaries keyed by English text
- cljc sky: pure, the night sky behind the 3D globe (sidereal time, precession, camera frame, galactic coords); data in resources/public/sky/ (`make sky`)
- cljc peak-density: pure, which peaks the map draws at a zoom (prominence bands: 1500 m at the world view, 1000 m from zoom 3, 500 m at 5 … all at zoom 10); the 3D
  map's layers are made from its `tiers`
- views/peaks: the Peaks page (`#/peaks`, under More): stats, the download map (`square_map.cljs`: 1° squares, search), bagged peaks; the Map tab
  draws peaks like komoot: rounded triangles with a white outline (grey, bagged crimson), under the activity pins
- cljc track: pure, GPS tracks for the heatmap (split, simplify, encoded polyline); stored in `activity_metrics.track`
- cljc workout: pure, a planned session's blocks (steps with hr/pace/power targets): expand, total time, compare with an activity stream

## Data flow
garmin-mcp (via mcp, implements source/DataSource) → sync → db → analysis/metrics → overview, readiness, adapt, plan-rules
→ planner / season-plan → claude (costs, ai-settings, translate) → plan → api → frontend (`js/fetch` in coach.app).
coros-mcp (HTTP, coros-auth) → sync/coros! → db: new rides only, each fetched once; runs after the Garmin sync in `run-job!`, failures kept apart.
api starts garmin-mcp via `requiring-resolve` in `run-job!`; the frontend requires coach.i18n and coach.workout from cljc.

## Where to look
- Plan logic: planner.clj (prompt/context), claude.clj (requests, batches), plan_rules.clj, adapt.clj, readiness.clj, season_plan.clj, plan.clj; routes `/api/plan…` in api.clj
- UI: src/cljs/coach/views/*.cljs; shared bits in ui.cljs, charts.cljs
- i18n: wrap text in `t` (or `tk` in data), then add the de/fr entries; i18n_test checks they exist
- AI/cost settings: claude.clj (requests, key), ai_settings.clj (`tasks`), costs.clj (`prices`), views/costs.cljs, views/settings.cljs
- Auth: auth.clj (Garmin login), `/api/garmin/login|code`, coros_auth.clj + `/api/coros/login|callback`, `/api/claude/key`
- Metrics: metrics.clj (pure) + analysis.clj
- Metrics docs: views/metrics.cljs explains every shown metric (entries by :id); cards and tiles link there with `:info "<id>"` / `ui/info`. A new metric shown in the UI gets an entry and a link

## Don't read unless needed
i18n_de.cljc, i18n_fr.cljc (~1000 lines each), resources/public/css/app.css, docs/style.md, package-lock.json.
Specs by path: coach-dashboard-spec.md (dashboard), training-principles.md (coaching rules the planner/rules cite), garmin-coach-plan.md (original project plan).

## Conventions
- `!` suffix for side effects (`start!`, `save!`, `daily!`, `incremental!`); pure namespaces say "Pure" in the ns docstring and are listed in the `pure` ns-group of .clj-kondo/config.edn (lint rejects db/files/network/Claude requires there)
- Lines up to 120 characters; a long string (a `t` text is one literal, the dictionary key) may run over on its own line. Requires sorted; no local named `t` where `t` (i18n) is referred
- Errors: `(throw (ex-info (i18n/t "User-facing message {0}" …) {:data …}))`; background jobs catch Throwable, `println` and record the result
- Claude never changes anything by itself: proposals/edits are stored and applied only when the athlete accepts
- UI text: English is the key; German is Swiss ("ss", never "ß", "du"), French is Swiss ("tu")
- Tests: test/coach/<ns>_test.clj with clojure.test; some are named by feature (batch, claude_key, credits)
