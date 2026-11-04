# Running Garmin Coach on a server

This is optional. The app on your computer keeps working as before. A server install runs the
app all the time, so:
- it syncs Garmin (and Coros) every 2 hours, whether your laptop is on or not;
- you open the same dashboard on your phone and your laptop, wherever you are.

**How it stays private.** The app has no login of its own, so it is never put on the open
internet. It listens only on the server itself (127.0.0.1). [Tailscale](https://tailscale.com)
connects your server, phone and laptop in a private network (free for personal use).
`tailscale serve` passes the dashboard on to these devices over HTTPS, at an address like
`https://coach.tail1234.ts.net`. Nobody outside that network can reach it.

```
phone / laptop ──(Tailscale, HTTPS)──▶ server: tailscale serve ──▶ 127.0.0.1:8080 Garmin Coach
                                                                         │ every 2 h
                                                                         ▼
                                                                 Garmin, Coros, Claude
```

Time needed: about 30–45 minutes.

---

## Contents
0. [What you need](#0-what-you-need)
1. [Get a server](#1-get-a-server)
2. [Prepare the server](#2-prepare-the-server)
3. [Tailscale](#3-tailscale)
4. [Install the app](#4-install-the-app)
5. [Move your data and logins](#5-move-your-data-and-logins)
6. [Stop syncing on the laptop](#6-stop-syncing-on-the-laptop)
7. [On the phone](#7-on-the-phone)
8. [Everyday use, updates, backups](#8-everyday-use-updates-backups)
9. [Troubleshooting](#9-troubleshooting)
10. [Removing it](#10-removing-it)

---

## 0. What you need
- An always-on Linux machine: x86_64 or arm64, **2 GB RAM or more**, about 2 GB of free disk.
  Ubuntu 24.04 or Debian 12 are the easiest; the steps below use their commands.
- A terminal on your laptop with `ssh` (Linux and macOS have it; Windows 10/11 too).
- Your phone and laptop, to install Tailscale on.
- Your Garmin account and your Claude API key, as for the desktop app.

## 1. Get a server
Pick one:

| | Small VPS | Machine at home |
|---|---|---|
| Examples | Hetzner CX22 / CAX11, DigitalOcean, Infomaniak | Raspberry Pi 4/5 (4 GB+), NAS, old laptop |
| Cost | about 4–6 € a month | power only |
| Always on | yes | while it's plugged in and online |
| Garmin login | from a data-centre IP: Garmin may ask for the MFA code more often | from your home IP, like your laptop |

**VPS:** create a server with Ubuntu 24.04 and add your SSH key when you create it (the provider
explains how). Note its public IP address.

**At home:** install Ubuntu Server or Raspberry Pi OS (64-bit) and turn on SSH. You'll need the
machine's local IP address (from your router, or `hostname -I` on the machine).

## 2. Prepare the server
Log in from your laptop:

```sh
ssh root@<server-ip>          # a Raspberry Pi: ssh <your-user>@<pi-ip>
```

**A normal user** (skip this if you already log in as a non-root user). The app runs as this
user, never as root:

```sh
adduser coach                 # choose a password
usermod -aG sudo coach
rsync -a ~/.ssh /home/coach/ && chown -R coach:coach /home/coach/.ssh
exit
ssh coach@<server-ip>
```

**Updates, a firewall and the time zone:**

```sh
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl tar
sudo ufw allow OpenSSH && sudo ufw --force enable    # only SSH is open; the app needs no port
sudo timedatectl set-timezone Europe/Zurich         # your own time zone
```

The time zone matters. It decides what "today" is for the dashboard and the plan, and when the
syncs run (between 6 and 22 h). A VPS usually starts in UTC.

## 3. Tailscale
1. **Create an account** at <https://login.tailscale.com> (sign in with Google, Microsoft,
   GitHub or Apple).
2. **On the server:**
   ```sh
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up
   ```
   Open the link it prints, and sign in.
3. **On your laptop and phone:** install Tailscale (<https://tailscale.com/download>; App Store or
   Google Play on the phone), and sign in with **the same account**.
4. **Turn on HTTPS:** in the admin console, under [DNS](https://login.tailscale.com/admin/dns),
   make sure **MagicDNS** is on, then turn on **HTTPS Certificates**.
5. **Optional:** rename the server under
   [Machines](https://login.tailscale.com/admin/machines) (⋯ › Edit machine name), e.g. to
   `coach`. The address then becomes `coach.<your-tailnet>.ts.net`. Do this before the next step.
   For a VPS, also choose ⋯ › **Disable key expiry**, so the server doesn't drop out of the network
   after 180 days.
6. **Pass the dashboard on (on the server):**
   ```sh
   sudo tailscale serve --bg 8080
   ```
   It prints the address, e.g. `https://coach.tail1234.ts.net`. Write it down. This setting
   survives reboots; `tailscale serve status` shows it again.

## 4. Install the app
On the server, as your normal user (not with sudo):

```sh
curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.sh \
  | GARMIN_COACH_SERVER=1 bash
```

> **Before the first release with server mode** the one-liner still installs an older app. In
> that case, build the app on your laptop and install that build:
> ```sh
> # on the laptop, in the project folder
> make app
> ssh coach@<server-ip> 'mkdir -p ~/coach/installer ~/coach/target'
> scp installer/install.sh installer/icon.png coach@<server-ip>:coach/installer/
> scp target/garmin-coach.jar coach@<server-ip>:coach/target/
> # on the server
> GARMIN_COACH_SERVER=1 bash ~/coach/installer/install.sh
> ```

The installer:
- downloads Java and uv (the Garmin connector) into `~/.local/share/garmin-coach`, as on a
  desktop. The first time this takes a few minutes;
- adds no shortcuts. Instead it sets up a **systemd user service**, `garmin-coach`. The service
  starts at boot, starts the app again if it stops, and keeps it running with nobody logged in;
- reads your Tailscale address and allows the dashboard to be opened there. If it says
  *"No host name set"*, run it again with the address you wrote down:
  ```sh
  curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.sh \
    | GARMIN_COACH_SERVER=1 GARMIN_COACH_HOSTS=coach.tail1234.ts.net bash
  ```

**Check that it runs:**

```sh
systemctl --user status garmin-coach      # "active (running)"
curl -s http://127.0.0.1:8080/api/lang    # answers with {"lang":…}
```

Then open the Tailscale address **on your laptop**. You should see the dashboard, still empty.
Settings › General shows *"Runs on a server"*.

## 5. Move your data and logins
Do all of this in the browser, on the server's address (not 127.0.0.1), unless it says otherwise.

### Training data and athlete document
1. In the **app on your laptop**: Settings › General › Backup › **Download backup**. You get a
   `coach-backup-….zip`.
2. On the **server's page**: Settings › General › Backup › Restore a backup ›
   **Restore from a file…**, and choose that zip. The app restarts; reload the page after a few
   seconds.

Skip this to start fresh on the server. The first sync then loads your Garmin history.

### Garmin
**Option A, log in again:** Settings › General › Garmin, with your email, password and the MFA
code. Simple, but a VPS gets more MFA requests (see step 1).

**Option B, copy the login from your laptop** (no new login; it lasts about 6 months):
```sh
# on the laptop
scp -r ~/.garminconnect coach@<server-ip>:
# on the server
chmod -R go= ~/.garminconnect && systemctl --user restart garmin-coach
```

### Claude
Settings › General › Claude: paste your API key and press **Save and test**. You could also copy
`~/.config/coach/anthropic-key` over, like the Garmin login. The key is then kept on the server,
readable by your user only.

### Coros (if you use it)
Settings › General › Coros › connect. Coros's sign-in page sends you back to the server's address.

If that fails (Coros refuses the address, or the page doesn't come back), connect once through
an SSH tunnel from your laptop instead:
1. Quit the laptop app (it also uses port 8080).
2. On the laptop: `ssh -L 8080:127.0.0.1:8080 coach@<server-ip>`, and leave it open.
3. Open <http://127.0.0.1:8080>. This is now the server's dashboard. Connect Coros there.
4. Close the tunnel (`exit`). The login stays on the server.

### First sync
Press **Sync now** (or wait: a sync starts within 15 minutes). The status line in the dashboard
shows when it's done.

## 6. Stop syncing on the laptop
Otherwise both copies keep syncing and planning, and your plans and feedback end up in two
places. In the **laptop app**: Settings › General › turn off **Start with the computer**, then
**Quit the app**. From now on, use the server's address.

You can still start the laptop app any time, e.g. offline with `make offline` or the shortcut.
To bring its data up to date, restore a backup from the server (the reverse of step 5).

## 7. On the phone
1. Make sure the Tailscale app is connected (the toggle in the app).
2. Open the server's address in the browser.
3. Add it to the home screen:
   - **iPhone (Safari):** Share › **Add to Home Screen**.
   - **Android (Chrome):** ⋮ › **Add to Home screen**.

If the page doesn't load, check Tailscale first: the phone must be connected to it, also on
Wi-Fi at home.

## 8. Everyday use, updates, backups
- **Syncing:** every 2 hours between 6 and 22 h (server time), plus **Sync now** whenever you like.
- **Updates:** when a new version exists, Settings › General shows **Update now**. Or run the
  install command from step 4 again. Your data, logins and allowed addresses are kept.
- **Backups:** the app makes one automatically each day before syncing, and keeps the newest 14
  in `~/.local/share/garmin-coach/data/backups/` on the server. These sit on the same disk. For
  a copy elsewhere, now and then use **Download backup** on your laptop, or turn on your VPS
  provider's backups.
- **Garmin login:** it lasts about 6 months. The dashboard warns before it runs out; log in
  again in Settings.
- **Commands on the server:**

  | | |
  |---|---|
  | Status | `systemctl --user status garmin-coach` |
  | Restart | `systemctl --user restart garmin-coach` |
  | Log | `tail -f ~/.local/share/garmin-coach/data/app.log` |
  | Startup errors | `journalctl --user -u garmin-coach -n 50` |
  | Tailscale address | `tailscale serve status` |

- **Another address** (e.g. a second Tailscale name): edit
  `~/.config/systemd/user/garmin-coach.service`. Comma-separate the names in the
  `Environment=COACH_ALLOWED_HOSTS=` line. Then run
  `systemctl --user daemon-reload && systemctl --user restart garmin-coach`.

## 9. Troubleshooting

| What you see | What to do |
|---|---|
| The page doesn't load at all | Is Tailscale connected on this device? On the server: `tailscale status`, `tailscale serve status` (should show port 8080), `systemctl --user status garmin-coach`. |
| *"Requests from other websites are not allowed."* | The address you opened isn't allowed. Check the `COACH_ALLOWED_HOSTS` line in the service file (step 8). It must be exactly the name in your address bar, without `https://`. Each start writes the allowed names to `app.log` ("Server mode; allowed hosts: …"). |
| The service stops when you log out | Linger is off. Run `sudo loginctl enable-linger $USER`. |
| *"active (running)"*, but `curl` gets no answer | It's still starting (up to a minute). Otherwise look in `app.log`. |
| Garmin login fails or asks for MFA every time | On a VPS, copy the login from the laptop instead (step 5, option B). |
| Days are off by one, syncs at odd hours | Set the time zone (step 2), then `systemctl --user restart garmin-coach`. |
| The server is slow or the app gets killed | Too little memory. Use 2 GB or more, or add swap. |
| *"The data folder … is in use by another Garmin Coach"* | A second copy is running, e.g. one started by hand. `pkill -f coach.api`, then `systemctl --user restart garmin-coach`. |

## 10. Removing it
```sh
~/.local/share/garmin-coach/uninstall.sh       # asks before deleting your data
sudo tailscale serve reset                     # stops passing on port 8080
```
To also remove Tailscale: `sudo apt remove tailscale`. Then delete the machine in the admin
console.

---

### Why only allowed addresses?
The app checks which address every request was sent to. It answers only for 127.0.0.1 and the
addresses you allowed. This stops other websites that are open in your browser from reaching
the dashboard through tricks like DNS rebinding. Your secrets stay on the server, readable by
your user only: the Garmin and Coros logins and the Claude key. Tailscale decides which devices
get to the server at all.
