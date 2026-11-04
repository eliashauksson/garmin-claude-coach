#!/usr/bin/env bash
# Garmin Coach on a fresh server, in one go (see docs/server.md).
#
# On a new Debian or Ubuntu server, logged in as root or as a user with sudo:
#   curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/server-setup.sh | sudo bash
#
# It sets up:
#   1. system updates, curl, and a firewall that lets only SSH in (ufw)
#   2. the time zone (it asks)
#   3. a normal user for the app ("coach" when run as root, else you)
#   4. Tailscale: it prints a link to sign in, and turns on HTTPS
#   5. the app, as a systemd service of that user (installer/install.sh)
#   6. `tailscale serve`, which passes the dashboard to your devices over HTTPS
# Run it again any time: every step skips what is already done.
#
# Options (environment variables, or flags after `bash -s --`):
#   --user NAME        COACH_USER         the user the app runs as
#   --timezone ZONE    COACH_TIMEZONE     e.g. Europe/Zurich (no question asked)
#   --authkey KEY      TS_AUTHKEY         a Tailscale auth key (no sign-in link)
#   --jar FILE         GARMIN_COACH_JAR   install this build instead of the latest release
#   --no-firewall      COACH_NO_FIREWALL=1
#   --port PORT        GARMIN_COACH_PORT  default 8080
set -euo pipefail

REPO="eliashauksson/garmin-claude-coach"
INSTALLER_URL="https://raw.githubusercontent.com/$REPO/main/installer/install.sh"

APP_USER="${COACH_USER:-}"
TIMEZONE="${COACH_TIMEZONE:-}"
AUTHKEY="${TS_AUTHKEY:-}"
JAR="${GARMIN_COACH_JAR:-}"
NO_FIREWALL="${COACH_NO_FIREWALL:-}"
PORT="${GARMIN_COACH_PORT:-8080}"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31mThe setup stopped: %s\033[0m\n' "$*" >&2; exit 1; }
trap 'fail "an unexpected error (see the lines above). Fix it and run this again: finished steps are skipped."' ERR

while [ $# -gt 0 ]; do
  case "$1" in
    --user)        APP_USER="$2"; shift 2 ;;
    --timezone)    TIMEZONE="$2"; shift 2 ;;
    --authkey)     AUTHKEY="$2"; shift 2 ;;
    --jar)         JAR="$2"; shift 2 ;;
    --no-firewall) NO_FIREWALL=1; shift ;;
    --port)        PORT="$2"; shift 2 ;;
    -h|--help)     sed -n '2,24p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "unknown option $1 (see --help)." ;;
  esac
done

# Questions come from the terminal, also when this script is piped from curl
ask() { # prompt default -> answer
  local answer=""
  if (exec </dev/tty) 2>/dev/null; then read -r -p "$1 " answer </dev/tty || true; fi
  printf '%s' "${answer:-$2}"
}

[ "$(id -u)" -eq 0 ] || fail "run it with sudo: curl -fsSL … | sudo bash"
command -v apt-get >/dev/null || fail "this script is for Debian or Ubuntu (apt). On other systems, follow docs/server.md by hand."
command -v systemctl >/dev/null || fail "systemd is missing. Use a regular VM or computer, not a container."
case "$(uname -m)" in
  x86_64|amd64|aarch64|arm64) ;;
  *) fail "this processor ($(uname -m)) isn't supported." ;;
esac
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
[ "$MEM_MB" -ge 1800 ] || note "Only ${MEM_MB} MB of memory: 2 GB or more is recommended (or add swap)."

# A build to install: copied where the app's user can read it
if [ -n "$JAR" ]; then
  [ -f "$JAR" ] || fail "there is no file $JAR."
  JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
fi

# ---------------------------------------------------------------------------
bold "1/6  System updates, curl and the firewall"
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
apt-get update -q
apt-get upgrade -y -q
apt-get install -y -q curl tar ca-certificates ufw
if [ -n "$NO_FIREWALL" ]; then
  note "Firewall skipped (--no-firewall)."
else
  ufw allow OpenSSH >/dev/null
  ufw --force enable >/dev/null
  note "Firewall on: only SSH comes in. The dashboard goes through Tailscale, not an open port."
fi

# ---------------------------------------------------------------------------
bold "2/6  Time zone"
CURRENT_TZ="$(timedatectl show -p Timezone --value 2>/dev/null || echo UTC)"
if [ -z "$TIMEZONE" ]; then
  note "Days and the sync hours (6-22 h) follow the server's time zone. It is now: $CURRENT_TZ"
  TIMEZONE="$(ask "Your time zone, e.g. Europe/Zurich (Enter keeps $CURRENT_TZ):" "$CURRENT_TZ")"
fi
if [ "$TIMEZONE" != "$CURRENT_TZ" ]; then
  timedatectl list-timezones | grep -x "$TIMEZONE" >/dev/null || fail "'$TIMEZONE' isn't a time zone (see: timedatectl list-timezones)."
  timedatectl set-timezone "$TIMEZONE"
fi
note "Time zone: $TIMEZONE"

# ---------------------------------------------------------------------------
bold "3/6  The app's user"
if [ -z "$APP_USER" ]; then
  if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then APP_USER="$SUDO_USER"; else APP_USER="coach"; fi
fi
if id "$APP_USER" >/dev/null 2>&1; then
  note "Using the user $APP_USER."
else
  useradd --create-home --shell /bin/bash "$APP_USER"
  # the same SSH keys as root, so you can log in as this user (and copy files to it)
  if [ -f /root/.ssh/authorized_keys ]; then
    install -d -m 700 -o "$APP_USER" -g "$APP_USER" "/home/$APP_USER/.ssh"
    install -m 600 -o "$APP_USER" -g "$APP_USER" /root/.ssh/authorized_keys "/home/$APP_USER/.ssh/authorized_keys"
  fi
  note "Created the user $APP_USER (log in with: ssh $APP_USER@<server>)."
fi
APP_HOME="$(getent passwd "$APP_USER" | cut -d: -f6)"
APP_UID="$(id -u "$APP_USER")"

# The user's systemd keeps running without anyone logged in, and starts at boot
loginctl enable-linger "$APP_USER"
for _ in $(seq 30); do [ -S "/run/user/$APP_UID/bus" ] && break; sleep 1; done
[ -S "/run/user/$APP_UID/bus" ] || fail "the user service manager of $APP_USER didn't start."

# ---------------------------------------------------------------------------
bold "4/6  Tailscale"
if ! command -v tailscale >/dev/null; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi
systemctl enable --now tailscaled >/dev/null 2>&1 || true

ts_state() { tailscale status --json 2>/dev/null | grep -o -m 1 '"BackendState": *"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/'; }
if [ "$(ts_state)" = "Running" ]; then
  note "Already connected."
else
  if [ -n "$AUTHKEY" ]; then
    tailscale up --auth-key "$AUTHKEY"
  else
    note "Open the link below in a browser and sign in. Use the same Tailscale account"
    note "as on your phone and laptop (create one there if you have none yet)."
    tailscale up
  fi
fi
[ "$(ts_state)" = "Running" ] || fail "Tailscale isn't connected. Run this again."

HOST="$(tailscale status --json --peers=false 2>/dev/null | grep -o -m 1 '"DNSName": *"[^"]*"' \
        | sed 's/.*"\([^"]*\)"$/\1/; s/\.$//')"
[ -n "$HOST" ] || fail "Tailscale gave this server no name. Turn on MagicDNS (https://login.tailscale.com/admin/dns) and run this again."
note "This server is $HOST in your Tailscale network."

# ---------------------------------------------------------------------------
bold "5/6  Garmin Coach"
INSTALL_SH="$(mktemp)"
trap 'rm -f "$INSTALL_SH"' EXIT
SCRIPT_DIR=""
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/install.sh" ]; then
  cp "$SCRIPT_DIR/install.sh" "$INSTALL_SH"   # the installer next to this script
else
  curl -fsSL "$INSTALLER_URL" -o "$INSTALL_SH" || fail "couldn't download the installer."
fi
chmod 755 "$INSTALL_SH"

JAR_COPY=""
if [ -n "$JAR" ]; then
  JAR_COPY="$APP_HOME/garmin-coach.jar.install"
  install -m 644 -o "$APP_USER" -g "$APP_USER" "$JAR" "$JAR_COPY"
  note "Installing the build $JAR"
fi

# As the app's user, with its systemd (for `systemctl --user`)
sudo -u "$APP_USER" -H \
  XDG_RUNTIME_DIR="/run/user/$APP_UID" \
  DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$APP_UID/bus" \
  GARMIN_COACH_SERVER=1 \
  GARMIN_COACH_HOSTS="$HOST" \
  GARMIN_COACH_PORT="$PORT" \
  GARMIN_COACH_JAR="$JAR_COPY" \
  bash -c "cd ~ && bash '$INSTALL_SH'"
[ -z "$JAR_COPY" ] || rm -f "$JAR_COPY"

# ---------------------------------------------------------------------------
bold "6/6  Passing the dashboard on to your devices (HTTPS)"
note "If Tailscale asks to turn on HTTPS certificates, open its link, allow it, and"
note "this continues by itself."
tailscale serve --bg "$PORT"

note "Waiting for the app to start…"
for _ in $(seq 90); do curl -fs -o /dev/null "http://127.0.0.1:$PORT/api/lang" && break; sleep 2; done
if curl -fs -o /dev/null "http://127.0.0.1:$PORT/api/lang"; then
  STARTED=1
else
  STARTED=""
fi

trap - ERR
bold "Done!"
if [ -n "$STARTED" ]; then
  echo "Garmin Coach runs at:  https://$HOST/"
else
  echo "Garmin Coach should soon run at https://$HOST/. It hasn't answered yet; see"
  echo "  sudo -u $APP_USER XDG_RUNTIME_DIR=/run/user/$APP_UID systemctl --user status garmin-coach"
  echo "  $APP_HOME/.local/share/garmin-coach/data/app.log"
fi
cat <<EOF

Next (details in docs/server.md, step 5):
  1. Install Tailscale on your phone and laptop, with the same account, and open the address.
  2. Settings › General: log in to Garmin, paste your Claude key, connect Coros.
     Your data: in the laptop app, Settings › General › Backup › Download backup,
     then here: Restore a backup › Restore from a file…
     To keep your Garmin login instead of logging in again, from the laptop:
       scp -r ~/.garminconnect $APP_USER@<server>:   (then restart: see below)
  3. In the laptop app: turn off "Start with the computer" and quit it.
  4. Phone: open the address and add it to the home screen.

Restart:  sudo -u $APP_USER XDG_RUNTIME_DIR=/run/user/$APP_UID systemctl --user restart garmin-coach
          (or, logged in as $APP_USER: systemctl --user restart garmin-coach)
Update:   Settings › General › Update now, or run this script again.
EOF
