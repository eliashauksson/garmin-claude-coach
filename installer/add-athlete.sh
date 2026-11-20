#!/usr/bin/env bash
# Another athlete on a Garmin Coach server (optional; see docs/server.md, "More athletes").
#
# The usual install is one athlete. This adds another one to a server set up
# with server-setup.sh. All athletes are served by the one installed app (one
# Java process): each has their own data, Garmin and Coros logins, and their
# own address:
#   https://<server>.<tailnet>.ts.net:8443/
# The athletes are listed in athletes.edn in the app folder, which this script
# keeps. The Claude key is shared (it belongs to the user, not to the athlete).
# On a small server it adds swap, and the athletes' syncs take turns, so more
# athletes make it slower, never crash it.
#
# On the server:
#   curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/add-athlete.sh \
#     | sudo bash -s -- anna
#
# Options, after the name:
#   --port PORT        the athlete's local port (default: the next free one from 8081)
#   --https-port PORT  the HTTPS port on the Tailscale address (default: the next free one from 8443)
#   --label "Name"     the name the dashboard shows (default: the name as typed, e.g. Nadine)
#   --user NAME        the user the app runs as (default: coach, or the one you sudo from)
#   --remove           removes this athlete; the data is kept
#   --migrate          (no name) moves a server that still runs one app per athlete
#                      (garmin-coach-athlete-*.service) to the one app for all
#
# Run it again with the same name to start or repair that athlete; updating the
# app (Settings > Update now, or server-setup.sh) updates all athletes.
set -euo pipefail

NAME=""
PORT=""
HTTPS_PORT=""
APP_USER="${COACH_USER:-}"
REMOVE=""
MIGRATE=""
LABEL=""

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
    --label)      LABEL="$2"; shift 2 ;;
    --remove)     REMOVE=1; shift ;;
    --migrate)    MIGRATE=1; shift ;;
    -h|--help)    sed -n '2,29p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)           fail "unknown option $1 (see --help)." ;;
    *)            [ -z "$NAME" ] || fail "one name only (got $NAME and $1)."; NAME="$1"; shift ;;
  esac
done

TYPED="$NAME"
NAME="$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]')"
# shown name: --label, else the name as typed when it has capitals (Nadine)
[ -n "$LABEL" ] || [ "$TYPED" = "$NAME" ] || LABEL="$TYPED"
if [ -z "$MIGRATE" ]; then
  [ -n "$NAME" ] || fail "give the athlete a name, e.g.: sudo bash add-athlete.sh anna"
  [[ "$NAME" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail "the name may only have letters, digits and dashes: $NAME"
fi
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
REGISTRY="$DIR/athletes.edn"

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
# athletes.edn: one map per line, e.g.  {:name "anna" :port 8081 :https-port 8443}
# (the first athlete has no :name). The app reads :name and :port; :https-port
# is only for this script (the address on the Tailscale network).

registry_rows() { # -> lines "name port https-port"; the first athlete's name is "-"
  [ -f "$REGISTRY" ] || return 0
  grep -o '{[^{}]*}' "$REGISTRY" | while IFS= read -r m; do
    n="$(sed -n 's/.*:name "\([^"]*\)".*/\1/p' <<< "$m")"
    p="$(sed -n 's/.*:port \([0-9]*\).*/\1/p' <<< "$m")"
    h="$(sed -n 's/.*:https-port \([0-9]*\).*/\1/p' <<< "$m")"
    echo "${n:--} ${p:-0} ${h:-0}"
  done
}
registry_write() { # rows on stdin
  local first=1 n p h m
  {
    echo "{:athletes"
    while read -r n p h; do
      m="{"; [ "$n" = "-" ] || m="$m:name \"$n\" "; m="$m:port $p"; [ "$h" = 0 ] || m="$m :https-port $h"; m="$m}"
      if [ "$first" = 1 ]; then echo " [$m"; first=0; else echo "  $m"; fi
    done
    if [ "$first" = 1 ]; then echo " []}"; else echo " ]}"; fi
  } > "$REGISTRY.new"
  chown "$APP_USER:$APP_USER" "$REGISTRY.new"
  mv -f "$REGISTRY.new" "$REGISTRY"
}
row_of() { registry_rows | awk -v n="$1" '$1 == n'; } # name -> its row

# Whether the installed app can serve all athletes (coach.context is in its jar)
app_serves_all() { grep -aq "coach/context" "$DIR/app/garmin-coach.jar"; }
need_new_app() {
  app_serves_all || fail "the installed app is too old to serve all athletes in one process. Update it first (server-setup.sh, or Settings > Update now in the dashboard), then run this again."
}

# A server that runs one app per athlete (garmin-coach-athlete-*.service): list
# them in athletes.edn and stop the extra apps. The ports, Tailscale addresses
# and data stay as they are; the main app serves them from now on.
migrate() {
  [ ! -f "$REGISTRY" ] || return 0
  local units=("$UNIT_DIR"/garmin-coach-athlete-*.service) f n
  [ -f "${units[0]}" ] || return 0
  need_new_app
  bold "Moving to one app for all athletes"
  {
    echo "- $(exec_port "$MAIN_UNIT") 443"
    for f in "${units[@]}"; do
      n="${f##*garmin-coach-athlete-}"; n="${n%.service}"
      echo "$n $(exec_port "$f") $(unit_value "$f" https-port)"
    done
  } | registry_write
  for f in "${units[@]}"; do
    n="$(basename "$f" .service)"
    as_app systemctl --user disable --now "$n" 2>/dev/null || true
    rm -f "$f"
  done
  as_app systemctl --user daemon-reload
  note "Athletes now listed in $REGISTRY; the extra apps are removed."
}

# ---------------------------------------------------------------------------
migrate
if [ -n "$MIGRATE" ]; then
  if [ -f "$REGISTRY" ]; then
    as_app systemctl --user restart garmin-coach
    bold "Done. One app serves: $(registry_rows | awk '{print ($1 == "-" ? "(first athlete)" : $1)}' | tr '\n' ' ')"
  else
    bold "Nothing to move: this server has no extra athletes yet."
  fi
  exit 0
fi

if [ ! -f "$REGISTRY" ]; then # no extra athlete yet: the first athlete is the one main unit
  [ -z "$REMOVE" ] || fail "there is no athlete $NAME."
  need_new_app
  echo "- $(exec_port "$MAIN_UNIT") 443" | registry_write
fi

if [ -n "$REMOVE" ]; then
  [ -n "$(row_of "$NAME")" ] || fail "there is no athlete $NAME."
  OLD_HTTPS="$(row_of "$NAME" | awk '{print $3}')"
  registry_rows | awk -v n="$NAME" '$1 != n' | registry_write
  as_app systemctl --user restart garmin-coach
  [ "$OLD_HTTPS" = 0 ] || tailscale serve --https="$OLD_HTTPS" off >/dev/null 2>&1 || true
  bold "The athlete $NAME is removed."
  note "The data is kept in $DIR/athletes/$NAME (delete that folder to remove it too)."
  note "Their Garmin login: $APP_HOME/.garminconnect-$NAME; Coros: $APP_HOME/.config/coach/coros-$NAME.json"
  exit 0
fi

# Ports: this athlete's earlier ones, else the next free ones
USED_PORTS="$(registry_rows | awk -v n="$NAME" '$1 != n {print $2}' | tr '\n' ' ')"
USED_HTTPS="$(registry_rows | awk -v n="$NAME" '$1 != n {print $3}' | tr '\n' ' ')"
if [ -n "$(row_of "$NAME")" ]; then
  [ -n "$PORT" ] || PORT="$(row_of "$NAME" | awk '{print $2}')"
  [ -n "$HTTPS_PORT" ] || HTTPS_PORT="$(row_of "$NAME" | awk '{print $3}')"
fi
in_use() { # port -> whether something other than this app listens on it
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

COUNT=$(( $(registry_rows | awk -v n="$NAME" '$1 != n' | wc -l) + 1 ))

bold "Adding the athlete $NAME${LABEL:+ ($LABEL)}"
# about 1 GB of swap per athlete (at least 2 GB): with the syncs taking turns,
# a small server then gets slower, but nothing is killed
SWAP_WANT=$(( COUNT * 1024 )); [ "$SWAP_WANT" -ge 2048 ] || SWAP_WANT=2048
ensure_swap "$SWAP_WANT"
install -d -m 700 -o "$APP_USER" -g "$APP_USER" "$DIR/athletes" "$DIR/athletes/$NAME"
if [ -n "$LABEL" ]; then # the dashboards read it (coach.paths/instance-label)
  printf '%s\n' "$LABEL" > "$DIR/athletes/$NAME/label"
  chown "$APP_USER:$APP_USER" "$DIR/athletes/$NAME/label"
fi
SHOWN="$(cat "$DIR/athletes/$NAME/label" 2>/dev/null || echo "$NAME")"

{ registry_rows | awk -v n="$NAME" '$1 != n'; echo "$NAME $PORT $HTTPS_PORT"; } | registry_write
# the one app starts the new athlete (the others are back within seconds)
as_app systemctl --user restart garmin-coach
tailscale serve --bg --https="$HTTPS_PORT" "$PORT" >/dev/null

note "Waiting for the app to start..."
STARTED=""
for _ in $(seq 90); do
  if curl -fs -o /dev/null "http://127.0.0.1:$PORT/api/lang"; then STARTED=1; break; fi
  sleep 2
done

bold "Done!"
if [ -n "$STARTED" ]; then
  echo "The athlete $SHOWN runs at:  https://${HOST:-<server>}:$HTTPS_PORT/"
else
  echo "It should soon run at https://${HOST:-<server>}:$HTTPS_PORT/. It hasn't answered yet; see"
  echo "  sudo -u $APP_USER XDG_RUNTIME_DIR=/run/user/$APP_UID systemctl --user status garmin-coach"
  echo "  $DIR/data/app.log"
fi
MEM_ALL=$(awk '/MemTotal|SwapTotal/ {s += $2} END {print int(s/1024)}' /proc/meminfo)
if [ "$MEM_ALL" -lt $(( 600 * COUNT )) ]; then
  echo
  echo "Note: $COUNT athletes on ${MEM_ALL} MB of memory and swap. One app serves them all (about"
  echo "150 MB per athlete plus the Garmin connector during a sync). Add memory or swap (free -h)."
fi
cat <<EOF

Next, on that address (details in docs/server.md, "More athletes"):
  1. Settings > General: log in to Garmin and connect Coros with this athlete's accounts.
     The Claude key is already there (shared with the other athletes).
  2. Write this athlete's profile in the Athlete tab.
  3. Whoever uses it needs access to your Tailscale network (invite them, or share the machine).

Restart:  sudo -u $APP_USER XDG_RUNTIME_DIR=/run/user/$APP_UID systemctl --user restart garmin-coach
Remove:   sudo bash add-athlete.sh $NAME --remove   (keeps the data)
EOF
