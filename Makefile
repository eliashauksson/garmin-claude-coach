.PHONY: run start offline watch build sync status test backup backups restore

# Dashboard on http://127.0.0.1:8080/, syncs on start and every 2 h
run:
	clojure -M -m coach.api

start: run

# Dashboard on port 8090 without syncing
offline:
	clojure -M -m coach.api 8090 --no-sync

# Rebuilds the frontend on every change (reload the page)
watch:
	npm run watch

build:
	npm run release

sync:
	clojure -M -m coach.sync sync

status:
	clojure -M -m coach.sync status

test:
	clojure -M:test

# Snapshot of the database and athlete document into data/backups/
backup:
	clojure -M -m coach.backup create

backups:
	clojure -M -m coach.backup list

# make restore FILE=data/backups/coach-backup-….zip (dashboard stopped)
restore:
	clojure -M -m coach.backup restore $(FILE)
