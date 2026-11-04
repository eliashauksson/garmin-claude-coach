#!/usr/bin/env bash
# Garmin Coach installer for Linux.
#
# Open a terminal (Ctrl+Alt+T) and paste:
#   curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.sh | bash
#
# It installs everything the app needs into ~/.local/share/garmin-coach (no
# admin rights, nothing system-wide), adds "Garmin Coach" to the app menu and
# the desktop, and starts it. Run it again to update; your data is kept.
#
# On a server (see docs/server.md), with GARMIN_COACH_SERVER=1: no shortcuts,
# but a systemd user service that keeps it running and syncing. The host
# names it may be opened at (a Tailscale address) come from
# GARMIN_COACH_HOSTS, else the service's earlier setting, else Tailscale.
#
# From a clone of the repository it installs the jar you built
# (target/garmin-coach.jar) instead of downloading the latest release.
set -euo pipefail

REPO="eliashauksson/garmin-claude-coach"
RELEASE="https://github.com/$REPO/releases/latest/download"
DIR="${GARMIN_COACH_DIR:-$HOME/.local/share/garmin-coach}"
PORT="${GARMIN_COACH_PORT:-8080}"
UNIT="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/garmin-coach.service"
SERVER="${GARMIN_COACH_SERVER:-}"
[ -f "$UNIT" ] && SERVER=1 # installed as a server before: stays one

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mThe installation stopped: %s\033[0m\n' "$*" >&2; exit 1; }
trap 'fail "an unexpected error (see the lines above)."' ERR

for tool in curl tar; do
  command -v "$tool" >/dev/null || fail "'$tool' is missing. Install it (e.g. sudo apt install $tool) and run this again."
done

case "$(uname -m)" in
  x86_64|amd64)  JAVA_ARCH=x64;     UV_ARCH=x86_64 ;;
  aarch64|arm64) JAVA_ARCH=aarch64; UV_ARCH=aarch64 ;;
  *) fail "this computer's processor ($(uname -m)) isn't supported." ;;
esac

# A local build when run from the repository (not when piped from curl)
SCRIPT_DIR=""
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
LOCAL_JAR=""
if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/../target/garmin-coach.jar" ]; then
  LOCAL_JAR="$SCRIPT_DIR/../target/garmin-coach.jar"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

download() { # url target
  curl -fL --retry 3 --progress-bar -o "$2" "$1" || fail "couldn't download $1. Check the internet connection."
}

bold "Installing Garmin Coach into $DIR"
mkdir -p "$DIR/app" "$DIR/runtime"

# A running copy keeps its files open: stop it first (it saves everything as it goes)
if [ -n "$SERVER" ] && systemctl --user is-active --quiet garmin-coach 2>/dev/null; then
  echo "Stopping the running app…"
  systemctl --user stop garmin-coach
elif curl -fs -m 2 -X POST "http://127.0.0.1:$PORT/api/quit" >/dev/null 2>&1; then
  echo "Stopping the running app…"
  sleep 3
fi

# 1. Java, only for this app
if [ -x "$DIR/runtime/jre/bin/java" ]; then
  echo "Java: already there."
else
  bold "1/4  Downloading Java (about 50 MB)…"
  download "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$JAVA_ARCH/jre/hotspot/normal/eclipse" "$TMP/jre.tar.gz"
  mkdir -p "$TMP/jre"
  tar -xzf "$TMP/jre.tar.gz" -C "$TMP/jre" --strip-components=1
  rm -rf "$DIR/runtime/jre"
  mv "$TMP/jre" "$DIR/runtime/jre"
fi

# 2. uv, which runs the Garmin connector (and fetches Python for it)
if [ -x "$DIR/runtime/uv/uvx" ]; then
  echo "uv: already there."
else
  bold "2/4  Downloading uv (about 20 MB)…"
  download "https://github.com/astral-sh/uv/releases/latest/download/uv-$UV_ARCH-unknown-linux-gnu.tar.gz" "$TMP/uv.tar.gz"
  mkdir -p "$TMP/uv" "$DIR/runtime/uv"
  tar -xzf "$TMP/uv.tar.gz" -C "$TMP/uv" --strip-components=1
  mv -f "$TMP/uv/uv" "$TMP/uv/uvx" "$DIR/runtime/uv/"
fi

# 3. The app itself
bold "3/4  Installing the app…"
if [ -n "$LOCAL_JAR" ]; then
  echo "Using the local build $LOCAL_JAR"
  cp "$LOCAL_JAR" "$DIR/app/garmin-coach.jar.new"
  cp "$SCRIPT_DIR/icon.png" "$DIR/app/icon.png"
else
  download "$RELEASE/garmin-coach.jar" "$DIR/app/garmin-coach.jar.new"
  download "$RELEASE/icon.png" "$DIR/app/icon.png"
fi
mv -f "$DIR/app/garmin-coach.jar.new" "$DIR/app/garmin-coach.jar"

cat > "$DIR/garmin-coach" <<EOF
#!/bin/sh
# Starts Garmin Coach (or, if it's already running, just opens it).
cd "\$(dirname "\$0")" || exit 1
exec ./runtime/jre/bin/java -cp app/garmin-coach.jar clojure.main -m coach.api $PORT --open "\$@"
EOF
chmod +x "$DIR/garmin-coach"

# 4. Python and the Garmin connector, so the first login doesn't wait for them
bold "4/4  Preparing the Garmin connector (downloads Python the first time)…"
(cd "$DIR" && ./runtime/jre/bin/java -cp app/garmin-coach.jar clojure.main -m coach.garmin-mcp prepare) \
  || fail "the Garmin connector couldn't be prepared. Check the internet connection and run this again."

if [ -n "$SERVER" ]; then
  # The host names the dashboard may be opened at
  HOSTS="${GARMIN_COACH_HOSTS:-}"
  if [ -z "$HOSTS" ] && [ -f "$UNIT" ]; then
    HOSTS="$(sed -n 's/^Environment=COACH_ALLOWED_HOSTS=//p' "$UNIT" | head -n 1)"
  fi
  if [ -z "$HOSTS" ] && command -v tailscale >/dev/null; then
    HOSTS="$(tailscale status --json --peers=false 2>/dev/null | grep -o '"DNSName": *"[^"]*"' | head -n 1 \
             | sed 's/.*"\([^"]*\)"$/\1/; s/\.$//')" || HOSTS=""
  fi

  mkdir -p "$(dirname "$UNIT")"
  {
    echo "[Unit]"
    echo "Description=Garmin Coach (dashboard, syncing every 2 hours)"
    echo "After=network-online.target"
    echo
    echo "[Service]"
    echo "WorkingDirectory=$DIR"
    if [ -n "$HOSTS" ]; then echo "Environment=COACH_ALLOWED_HOSTS=$HOSTS"; fi
    echo "ExecStart=$DIR/runtime/jre/bin/java -cp app/garmin-coach.jar clojure.main -m coach.api $PORT --server"
    echo "Restart=always"
    echo "RestartSec=5"
    echo
    echo "[Install]"
    echo "WantedBy=default.target"
  } > "$UNIT"

  cat > "$DIR/uninstall.sh" <<EOF
#!/usr/bin/env bash
# Removes Garmin Coach (the server install). Asks before deleting your training data.
systemctl --user disable --now garmin-coach 2>/dev/null
rm -f "$UNIT"; systemctl --user daemon-reload
rm -rf "$DIR/app" "$DIR/runtime" "$DIR/garmin-coach"
answer=n
if (exec </dev/tty) 2>/dev/null; then
  read -r -p "Also delete your training data in $DIR/data? [y/N] " answer </dev/tty || answer=n
fi
case "\$answer" in
  [yY]*) rm -rf "$DIR" ;;
  *) rm -f "$DIR/uninstall.sh"; echo "Your data is kept in $DIR/data." ;;
esac
echo "Garmin Coach is removed."
EOF
  chmod +x "$DIR/uninstall.sh"

  # Keeps the service running without anyone logged in, and starts it at boot
  if ! loginctl enable-linger "$USER" 2>/dev/null; then
    echo "Couldn't keep it running after logout. Run: sudo loginctl enable-linger $USER"
  fi
  systemctl --user daemon-reload
  systemctl --user enable garmin-coach >/dev/null 2>&1
  systemctl --user restart garmin-coach

  trap - ERR
  bold "Done! Garmin Coach runs as a service on this server."
  echo "Status: systemctl --user status garmin-coach · log: $DIR/data/app.log"
  if [ -n "$HOSTS" ]; then
    echo "Opens at https://${HOSTS%%,*}/ once 'sudo tailscale serve --bg $PORT' has run."
  else
    echo "No host name set: run this again with GARMIN_COACH_HOSTS=<server>.<tailnet>.ts.net"
  fi
  TZ_NOW="$(timedatectl show -p Timezone --value 2>/dev/null || true)"
  case "$TZ_NOW" in
    UTC|Etc/UTC|"") echo "The time zone is '${TZ_NOW:-unknown}'. Days and sync hours (6-22 h) follow it; set yours, e.g.: sudo timedatectl set-timezone Europe/Zurich" ;;
  esac
  echo "Update: run the same command again, or Update now in Settings. Uninstall: $DIR/uninstall.sh"
  exit 0
fi

# Shortcuts: the app menu and the desktop
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
DESKTOP_ENTRY="[Desktop Entry]
Type=Application
Name=Garmin Coach
Comment=Your training dashboard, coached by Claude
Exec=\"$DIR/garmin-coach\"
Icon=$DIR/app/icon.png
Terminal=false
Categories=Utility;Sports;
StartupNotify=false"
printf '%s\n' "$DESKTOP_ENTRY" > "$APPS/garmin-coach.desktop"
chmod +x "$APPS/garmin-coach.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" >/dev/null 2>&1 || true

DESKTOP_DIR="$(command -v xdg-user-dir >/dev/null && xdg-user-dir DESKTOP || echo "$HOME/Desktop")"
if [ -d "$DESKTOP_DIR" ] && [ "$DESKTOP_DIR" != "$HOME" ]; then
  printf '%s\n' "$DESKTOP_ENTRY" > "$DESKTOP_DIR/garmin-coach.desktop"
  chmod +x "$DESKTOP_DIR/garmin-coach.desktop"
  # GNOME asks before running desktop launchers unless they are marked trusted
  command -v gio >/dev/null && gio set "$DESKTOP_DIR/garmin-coach.desktop" metadata::trusted true 2>/dev/null || true
fi

# Uninstaller
cat > "$DIR/uninstall.sh" <<EOF
#!/usr/bin/env bash
# Removes Garmin Coach. Asks before deleting your training data.
curl -fs -m 2 -X POST "http://127.0.0.1:$PORT/api/quit" >/dev/null 2>&1 && sleep 3
rm -f "$APPS/garmin-coach.desktop" "$DESKTOP_DIR/garmin-coach.desktop" "\${XDG_CONFIG_HOME:-\$HOME/.config}/autostart/garmin-coach.desktop"
rm -rf "$DIR/app" "$DIR/runtime" "$DIR/garmin-coach"
grep -q ':data-dir' "$DIR/config.edn" 2>/dev/null && echo "A data folder you chose in Settings stays where it is: \$(grep -o ':data-dir "[^"]*"' "$DIR/config.edn")"
answer=n
if (exec </dev/tty) 2>/dev/null; then
  read -r -p "Also delete your training data in $DIR/data? [y/N] " answer </dev/tty || answer=n
fi
case "\$answer" in
  [yY]*) rm -rf "$DIR" ;;
  *) rm -f "$DIR/uninstall.sh"; echo "Your data is kept in $DIR/data." ;;
esac
echo "Garmin Coach is removed."
EOF
chmod +x "$DIR/uninstall.sh"

trap - ERR
bold "Done! Garmin Coach is installed."
if [ -n "${GARMIN_COACH_UPDATING:-}" ]; then
  # updated from the app's Settings: the open page reconnects by itself
  nohup setsid "$DIR/garmin-coach" --background >/dev/null 2>&1 &
else
  echo "Find it in your app menu or on the desktop. Starting it now…"
  nohup setsid "$DIR/garmin-coach" >/dev/null 2>&1 &
  echo "It opens in your browser in a few seconds."
fi
echo "To uninstall later: $DIR/uninstall.sh"
