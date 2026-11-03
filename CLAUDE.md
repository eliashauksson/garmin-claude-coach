# CLAUDE.md

Personal Garmin dashboard + AI training planner: syncs Garmin data (plus rides from a Coros bike computer) into local SQLite, computes TrainingPeaks-style metrics, and asks Claude to propose training the athlete accepts or rejects. Nothing is ever written to Garmin or Coros.
Stack: Clojure 1.12 backend (reitit/ring/jetty, next.jdbc + SQLite, malli, anthropic-java), ClojureScript via shadow-cljs + Replicant (ECharts, Leaflet), shared `.cljc` for i18n.

## Commands
- `make run`: dashboard on http://127.0.0.1:8081/, syncs on start and every 2 h (8080 is the installed app's port)
- `make offline`: port 8090, no sync · `make watch`: shadow-cljs watch (reload the page) · `make build`: `npm run release`
- `make test`: `clojure -M:test` (cognitect test-runner; one ns: `clojure -M:test -n coach.plan-test`)
- `make app`: frontend release + `clojure -T:build uber` → target/garmin-coach.jar (installer/)
- `make sync` / `make status` (coach.sync CLI) · `make backup` / `backups` / `restore FILE=…` (coach.backup CLI)
- Data folder: `$COACH_DATA_DIR`, else config.edn, else data/ (coach.paths)

## Namespaces (src/clj)
- api: JSON API under /api + static files, 127.0.0.1 only; runs sync/backup jobs; `-main` entry point
- app: installed app: version/updates, autostart, restart, :pending actions in config.edn
- paths: app/data folder resolution, lock file
- source: `DataSource` protocol (read-only), `ActivitySource` for activity-only sources · garmin-mcp: DataSource over a local garmin_mcp server · mcp: minimal MCP client (stdio, Streamable HTTP)
- coros-mcp: ActivitySource over COROS's official remote MCP server (rides from FIT files) · coros-auth: Coros OAuth (PKCE, token in ~/.config/coach/coros.json) · fit: FIT file decoding (Garmin FIT SDK)
- sync: incremental + backfill from a DataSource into the DB (upserts); `activities-only!`/`coros!` for Coros
- db: SQLite schema/queries; raw source JSON kept on every row
- metrics: pure TSS/NP/IF/CTL/ATL/TSB, power curve/eFTP/cycling VO2 max etc. · analysis: glue between db and metrics, API queries, eFTP stored as bike FTP (source "estimate")
- overview: everything behind the dashboard start page (/api/overview)
- readiness: pure morning readiness check · adapt: signals that the plan should adapt
- plan-rules: pure checks of a week against training-principles.md (warnings only)
- season: date helpers + week position in the season · season-plan: Claude drafts every week to race day
- plan: storage for weeks, proposals and accepted workouts · planner: Claude week proposals, `request` (all Claude calls)
- ai-settings: model/effort/delivery per task (`tasks`, `defaults`) · costs: per-request Claude cost log, `prices`
- translate: translates Claude's texts once into all languages (translations table)
- athlete: athlete.md read/save/backup · athlete-chat: editing athlete.md as a chat with Claude
- auth: Garmin login expiry countdown · backup: zip snapshots of DB + athlete docs

## Namespaces (src/cljs, src/cljc)
- app: state, routing, `fetch!` of /api URLs · ui: formatters + shared components · charts: ECharts/Leaflet mount
- views/: dashboard (start page), calendar, day, activity, feedback, load, plan, season, athlete, settings, costs, metrics (explains every metric), status (error banner)
- cljc i18n: `t`/`tk`, language from server · i18n-de / i18n-fr: dictionaries keyed by English text

## Data flow
garmin-mcp (via mcp, implements source/DataSource) → sync → db → analysis/metrics → overview, readiness, adapt, plan-rules
→ planner / season-plan (Claude; costs, ai-settings, translate) → plan → api → frontend (`js/fetch` in coach.app).
coros-mcp (HTTP, coros-auth) → sync/coros! → db: new rides only, each fetched once; runs after the Garmin sync in `run-job!`, failures kept apart.
api starts garmin-mcp via `requiring-resolve` in `run-job!`; the frontend requires only coach.i18n from cljc.

## Where to look
- Plan logic: planner.clj (prompt/context/Claude), plan_rules.clj, adapt.clj, readiness.clj, season_plan.clj, plan.clj; routes `/api/plan…` in api.clj
- UI: src/cljs/coach/views/*.cljs; shared bits in ui.cljs, charts.cljs
- i18n: wrap text in `t` (or `tk` in data), then add the de/fr entries; i18n_test checks they exist
- AI/cost settings: ai_settings.clj (`tasks`), costs.clj (`prices`), views/costs.cljs, views/settings.cljs
- Auth: auth.clj (Garmin login), `/api/garmin/login|code`, coros_auth.clj + `/api/coros/login|callback`, `/api/claude/key`
- Metrics: metrics.clj (pure) + analysis.clj
- Metrics docs: views/metrics.cljs explains every shown metric (entries by :id); cards and tiles link there with `:info "<id>"` / `ui/info`. A new metric shown in the UI gets an entry and a link

## Don't read unless needed
i18n_de.cljc, i18n_fr.cljc (~1000 lines each), resources/public/css/app.css, docs/style.md, package-lock.json.
Specs by path: coach-dashboard-spec.md (dashboard), training-principles.md (coaching rules the planner/rules cite), garmin-coach-plan.md (original project plan).

## Conventions
- `!` suffix for side effects (`start!`, `save!`, `daily!`, `incremental!`); pure namespaces say "Pure" in the ns docstring
- Errors: `(throw (ex-info (i18n/t "User-facing message {0}" …) {:data …}))`; background jobs catch Throwable, `println` and record the result
- Claude never changes anything by itself: proposals/edits are stored and applied only when the athlete accepts
- UI text: English is the key; German is Swiss ("ss", never "ß", "du"), French is Swiss ("tu")
- Tests: test/coach/<ns>_test.clj with clojure.test; some are named by feature (batch, claude_key, credits)
