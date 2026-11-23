# Running the app, backups and troubleshooting

## Everyday use
The app runs in the background (syncing every 2 h) until **Settings › General › Quit the app**
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

## Where things are
| | Windows | Linux |
|---|---|---|
| App | `%LOCALAPPDATA%\GarminCoach` | `~/.local/share/garmin-coach` |
| Data (unless moved in Settings) | `data\` in the app folder | `data/` in the app folder |
| Log | `app.log` in the data folder | `app.log` in the data folder |
| Uninstall | Start menu › Uninstall Garmin Coach | `~/.local/share/garmin-coach/uninstall.sh` |

The installer downloads Java 21 ([Adoptium](https://adoptium.net)), [uv](https://github.com/astral-sh/uv)
(which runs the Garmin connector and fetches Python for it) and the app itself, `garmin-coach.jar`,
from the latest release. No admin rights needed.

## Backups
Before the first sync of each day, the dashboard zips the database and the athlete document into
`backups/` in the data folder and keeps the newest 14. **Download backup** in Settings makes a
fresh one to keep elsewhere. **Restore a backup** in Settings restores one from the list or from a
file; the current state is backed up first, and the app restarts. In a terminal (app stopped):
`make restore FILE=data/backups/coach-backup-….zip`.

## When something goes wrong
A banner above every page says what failed and what to do:
- **Garmin login expired.** Log in again in Settings › General (or run `clojure -M -m coach.auth login`
  and press Sync now). No restart needed.
- **Rate limit, network or Garmin error.** Usually temporary; the next automatic sync tries again.
  If a Garmin error keeps coming back, check `mcp-server.log` in the data folder.
- **Claude credits ran out.** Planning pauses. Add funds on the
  [Claude Console billing page](https://platform.claude.com/settings/billing), then press
  **I've added funds, check again**.
- **The server isn't running.** Open the app from its shortcut (or run `make run`); the page
  reconnects by itself. If it won't start, `app.log` in the data folder says why.

A failed sync never damages stored data: a day that comes back empty never replaces a stored day.
