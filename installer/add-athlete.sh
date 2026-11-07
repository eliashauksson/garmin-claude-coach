#!/usr/bin/env bash
# Another athlete on a Garmin Coach server (optional; see docs/server.md, "More athletes").
#
# The usual install is one athlete. This adds another one to a server set up
# with server-setup.sh: a second copy of the same installed app, run as the same
# user, with its own data, Garmin and Coros logins, and its own address:
#   https://<server>.<tailnet>.ts.net:8443/
# The Claude key is shared (it belongs to the user, not to the athlete).
# On a small server it adds swap, and the athletes' syncs take turns, so more
# athletes make it slower, never crash it.
#
# On the server:
#   curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/add-athlete.sh \
#     | sudo bash -s -- anna
#
# Options, after the name:
#   --port PORT        the app's local port (default: the next free one from 8081)
#   --https-port PORT  the HTTPS port on the Tailscale address (default: the next free one from 8443)
#   --user NAME        the user the app runs as (default: coach, or the one you sudo from)
#   --remove           stops and removes this athlete; the data is kept
#
# Run it again with the same name to start or repair that athlete; updating the
# app (Settings › Update now, or server-setup.sh) updates all athletes.
set -euo pipefail

NAME=""
PORT=""
HTTPS_PORT=""
APP_USER="${COACH_USER:-}"
REMOVE=""

note() { printf '   %s\n' "$*"; }
bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mStopped: %s\033[0m\n' "$*" >&2; exit 1; }

# Swap, so that running short of memory makes things slower instead of
# getting the app killed. Only on small servers (under 4 GB) and only a
# swap file of our own: one the system already has is left alone.
SWAPFILE=/swapfile-garmin-coach
ensure_swap() { # wanted MB of swap in all
  local want="$1" mem total ours free_mb
  mem=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
  [ "$mem" -lt 3900 ] || return 0
  total=$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)
  ours=0
  if swapon --show=NAME --noheadings 2>/dev/null | grep -qx "$SWAPFILE"; then
    ours=$(( $(stat -c %s "$SWAPFILE") / 1048576 ))
  fi
  [ "$total" -lt $(( want - 64 )) ] || return 0
  local size=$(( want - (total - ours) ))
  [ "$size" -gt "$ours" ] || return 0
  free_mb=$(df -Pm / | awk 'NR==2 {print $4}')
  if [ "$free_mb" -lt $(( size - ours + 2048 )) ]; then
    note "Not enough disk space for ${size} MB of swap; skipped. Add memory or swap yourself."
    return 0
  fi
  if [ "$ours" -gt 0 ]; then swapoff "$SWAPFILE"; fi
  rm -f "$SWAPFILE"
  if ! fallocate -l "${size}M" "$SWAPFILE" 2>/dev/null; then
    dd if=/dev/zero of="$SWAPFILE" bs=1M count="$size" status=none
  fi
  chmod 600 "$SWAPFILE"
  mkswap "$SWAPFILE" >/dev/null
  if ! swapon "$SWAPFILE" 2>/dev/null; then
    rm -f "$SWAPFILE"
    note "This system doesn't allow a swap file (a container?); skipped."
    return 0
  fi
  grep -q "^$SWAPFILE " /etc/fstab || echo "$SWAPFILE none swap sw 0 0" >> /etc/fstab
  # use it only when memory is really short, not for files the app reads
  echo "vm.swappiness=10" > /etc/sysctl.d/90-garmin-coach.conf
  sysctl -q -p /etc/sysctl.d/90-garmin-coach.conf || true
  note "Swap: ${size} MB ($SWAPFILE). Short of memory, the app gets slower instead of stopping."
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port)       PORT="$2"; shift 2 ;;
    --https-port) HTTPS_PORT="$2"; shift 2 ;;
    --user)       APP_USER="$2"; shift 2 ;;
    --remove)     REMOVE=1; shift ;;
    -h|--help)    sed -n '2,23p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)           fail "unknown option $1 (see --help)." ;;
    *)            [ -z "$NAME" ] || fail "one name only (got $NAME and $1)."; NAME="$1"; shift ;;
  esac
done

NAME="$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]')"
[ -n "$NAME" ] || fail "give the athlete a name, e.g.: sudo bash add-athlete.sh anna"
[[ "$NAME" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail "the name may only have letters, digits and dashes: $NAME"
[ "$(id -u)" -eq 0 ] || fail "run it with sudo."
command -v tailscale >/dev/null || fail "Tailscale is missing. Set up the server with server-setup.sh first."

if [ -z "$APP_USER" ]; then
  if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ] && [ -d "$(getent passwd "$SUDO_USER" | cut -d: -f6)/.local/share/garmin-coach/runtime" ]; then
    APP_USER="$SUDO_USER"
  else
    APP_USER="coach"
  fi
fi
id "$APP_USER" >/dev/null 2>&1 || fail "there is no user $APP_USER (use --user)."
APP_HOME="$(getent passwd "$APP_USER" | cut -d: -f6)"
APP_UID="$(id -u "$APP_USER")"
DIR="$APP_HOME/.local/share/garmin-coach"
UNIT_DIR="$APP_HOME/.config/systemd/user"
MAIN_UNIT="$UNIT_DIR/garmin-coach.service"
SERVICE="garmin-coach-athlete-$NAME"
UNIT="$UNIT_DIR/$SERVICE.service"

[ -f "$MAIN_UNIT" ] && [ -x "$DIR/runtime/jre/bin/java" ] \
  || fail "Garmin Coach isn't installed as a server for $APP_USER. Run server-setup.sh first."

as_app() { # runs a command as the app's user, with its systemd
  sudo -u "$APP_USER" -H XDG_RUNTIME_DIR="/run/user/$APP_UID" \
    DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$APP_UID/bus" "$@"
}
unit_value() { # file key -> value of a "# key=value" line
  sed -n "s/^# $2=//p" "$1" 2>/dev/null | head -n 1
}
exec_port() { # unit file -> the app's port
  sed -n 's/^ExecStart=.* coach\.api \([0-9]*\) .*/\1/p' "$1" | head -n 1
}

HOST="$(tailscale status --json --peers=false 2>/dev/null | grep -o -m 1 '"DNSName": *"[^"]*"' \
        | sed 's/.*"\([^"]*\)"$/\1/; s/\.$//')"

# ---------------------------------------------------------------------------
if [ -n "$REMOVE" ]; then
  [ -f "$UNIT" ] || fail "there is no athlete $NAME."
  OLD_HTTPS="$(unit_value "$UNIT" https-port)"
  as_app systemctl --user disable --now "$SERVICE" 2>/dev/null || true
  rm -f "$UNIT"
  as_app systemctl --user daemon-reload
  [ -z "$OLD_HTTPS" ] || tailscale serve --https="$OLD_HTTPS" off >/dev/null 2>&1 || true
  bold "The athlete $NAME is removed."
  note "The data is kept in $DIR/athletes/$NAME (delete that folder to remove it too)."
  note "Their Garmin login: $APP_HOME/.garminconnect-$NAME; Coros: $APP_HOME/.config/coach/coros-$NAME.json"
  exit 0
fi

# Ports: this athlete's earlier ones, else the next free ones
USED_PORTS="$(exec_port "$MAIN_UNIT")"
USED_HTTPS=""
for f in "$UNIT_DIR"/garmin-coach-athlete-*.service; do
  if [ -f "$f" ] && [ "$f" != "$UNIT" ]; then
    USED_PORTS="$USED_PORTS $(exec_port "$f")"
    USED_HTTPS="$USED_HTTPS $(unit_value "$f" https-port)"
  fi
done
if [ -f "$UNIT" ]; then
  PORT="${PORT:-$(exec_port "$UNIT")}"
  HTTPS_PORT="${HTTPS_PORT:-$(unit_value "$UNIT" https-port)}"
fi
in_use() { # port -> whether something listens on it
  command -v ss >/dev/null && ss -Hltn "sport = :$1" 2>/dev/null | grep -q .
}
if [ -z "$PORT" ]; then
  PORT=8081
  while [[ " $USED_PORTS " == *" $PORT "* ]] || in_use "$PORT"; do PORT=$((PORT + 1)); done
fi
if [ -z "$HTTPS_PORT" ]; then
  HTTPS_PORT=8443
  while [[ " $USED_HTTPS " == *" $HTTPS_PORT "* ]]; do HTTPS_PORT=$((HTTPS_PORT + 1)); done
fi
[[ "$PORT" =~ ^[0-9]+$ && "$HTTPS_PORT" =~ ^[0-9]+$ ]] || fail "ports are numbers."
[[ " $USED_PORTS " != *" $PORT "* ]] || fail "port $PORT is another athlete's (use --port)."
[ "$HTTPS_PORT" != 443 ] || fail "443 is the first athlete's address (use another --https-port)."

# The host names the dashboard may be opened at: the same as the first athlete's
HOSTS="$(sed -n 's/^Environment=COACH_ALLOWED_HOSTS=//p' "$MAIN_UNIT" | head -n 1)"
HOSTS="${HOSTS:-$HOST}"

COUNT=$(( $(wc -w <<< "$USED_PORTS") + 1 ))

bold "Adding the athlete $NAME"
# about 1 GB of swap per athlete (at least 2 GB): with the syncs taking turns,
# a small server then gets slower, but nothing is killed
SWAP_WANT=$(( COUNT * 1024 )); [ "$SWAP_WANT" -ge 2048 ] || SWAP_WANT=2048
ensure_swap "$SWAP_WANT"
install -d -m 755 -o "$APP_USER" -g "$APP_USER" "$UNIT_DIR"
{
  echo "# Garmin Coach: the athlete $NAME (installer/add-athlete.sh)"
  echo "# https-port=$HTTPS_PORT"
  echo "[Unit]"
  echo "Description=Garmin Coach, athlete $NAME"
  echo "After=network-online.target"
  echo
  echo "[Service]"
  echo "WorkingDirectory=$DIR"
  echo "Environment=COACH_INSTANCE=$NAME"
  echo "Environment=COACH_DATA_DIR=$DIR/athletes/$NAME/data"
  if [ -n "$HOSTS" ]; then echo "Environment=COACH_ALLOWED_HOSTS=$HOSTS"; fi
  echo "ExecStart=$DIR/runtime/jre/bin/java -cp app/garmin-coach.jar clojure.main -m coach.api $PORT --server"
  echo "Restart=always"
  echo "RestartSec=5"
  echo
  echo "[Install]"
  echo "WantedBy=default.target"
} > "$UNIT"
chown "$APP_USER:$APP_USER" "$UNIT"
install -d -m 700 -o "$APP_USER" -g "$APP_USER" "$DIR/athletes" "$DIR/athletes/$NAME"

as_app systemctl --user daemon-reload
as_app systemctl --user enable "$SERVICE" >/dev/null 2>&1
as_app systemctl --user restart "$SERVICE"
tailscale serve --bg --https="$HTTPS_PORT" "$PORT" >/dev/null

note "Waiting for the app to start…"
STARTED=""
for _ in $(seq 90); do
  if curl -fs -o /dev/null "http://127.0.0.1:$PORT/api/lang"; then STARTED=1; break; fi
  sleep 2
done

bold "Done!"
if [ -n "$STARTED" ]; then
  echo "The athlete $NAME runs at:  https://${HOST:-<server>}:$HTTPS_PORT/"
else
  echo "It should soon run at https://${HOST:-<server>}:$HTTPS_PORT/. It hasn't answered yet; see"
  echo "  sudo -u $APP_USER XDG_RUNTIME_DIR=/run/user/$APP_UID systemctl --user status $SERVICE"
  echo "  $DIR/athletes/$NAME/data/app.log"
fi
MEM_ALL=$(awk '/MemTotal|SwapTotal/ {s += $2} END {print int(s/1024)}' /proc/meminfo)
if [ "$MEM_ALL" -lt $(( 1000 * COUNT )) ]; then
  echo
  echo "Note: $COUNT athletes on ${MEM_ALL} MB of memory and swap. Each one runs its own app; about"
  echo "1 GB each is safe. Add memory or swap (free -h shows them)."
fi
cat <<EOF

Next, on that address (details in docs/server.md, "More athletes"):
  1. Settings › General: log in to Garmin and connect Coros with this athlete's accounts.
     The Claude key is already there (shared with the other athletes).
  2. Write this athlete's profile in the Athlete tab.
  3. Whoever uses it needs access to your Tailscale network (invite them, or share the machine).

Restart:  sudo -u $APP_USER XDG_RUNTIME_DIR=/run/user/$APP_UID systemctl --user restart $SERVICE
Remove:   sudo bash add-athlete.sh $NAME --remove   (keeps the data)
EOF
