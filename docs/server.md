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

The server serves one athlete, like the desktop app. Coaching a partner or a friend too? Each
can get their own dashboard on the same server, with the same Claude key: see
[More athletes](#11-more-athletes-optional), after the setup.

Time needed: about half an hour with the setup script (including creating the server), 45–60 by hand.

## Quick setup: one script
On a **fresh Debian or Ubuntu server** ([step 1](#1-get-a-server) helps you get one), logged in
as root or as a user with sudo:

```sh
curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/server-setup.sh | sudo bash
```

It does steps 2–4 below for you:
- system updates;
- a firewall that lets only SSH in;
- swap on servers under 4 GB, so running short of memory makes the app slower, never stops it;
- your time zone (it asks);
- a user `coach` for the app (or you, when run with sudo);
- Tailscale (it prints a link to sign in);
- the app as a service;
- `tailscale serve`.

At the end it prints the dashboard's address. While it runs, you only need to:
1. **Answer the time zone question**, e.g. `Europe/Zurich`.
2. **Open the Tailscale link** it prints and sign in. Use the same account you'll use on your
   phone and laptop.
3. **If Tailscale asks to turn on HTTPS certificates,** open that link too and allow it.

Afterwards, in the [Tailscale admin console](https://login.tailscale.com/admin/machines), choose
⋯ › **Disable key expiry** for the server ([step 3](#3-tailscale), point 5). Then continue with
[step 5](#5-move-your-data-and-logins) (data and logins), 6 and 7. Running the
script again is safe: finished steps are skipped, and the app is updated.

Options, after `sudo bash -s --`:
- `--timezone Europe/Zurich`: don't ask for the time zone.
- `--authkey tskey-…`: a [Tailscale auth key](https://login.tailscale.com/admin/settings/keys)
  instead of the sign-in link.
- `--user NAME`: run the app as another user.
- `--no-firewall`: leave the firewall alone.
- `--port 8080`: a different port.
- `--jar FILE`: install your own build. To use this before a release includes server mode: on the
  laptop run `make app`, then
  `scp target/garmin-coach.jar installer/server-setup.sh installer/install.sh root@<server-ip>:`.
  On the server run `sudo bash server-setup.sh --jar garmin-coach.jar`.

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
11. [More athletes (optional)](#11-more-athletes-optional)

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

**An SSH key** lets your laptop log in to the server. Give it its own name, so it stays apart
from other keys you have:

```sh
ssh-keygen -t ed25519 -f ~/.ssh/coach -C "coach-server"   # a passphrase is optional
cat ~/.ssh/coach.pub                                     # copy this whole line
```

**VPS, e.g. Hetzner:** in the [Cloud Console](https://console.hetzner.cloud), open a project and
click **Add Server**:
- **Location:** one near you (Falkenstein or Nuremberg from Switzerland).
- **Image:** Ubuntu 24.04.
- **Type:** the cheapest with **2 GB of memory or more**. Check the memory column: the smallest
  types have 1 GB, and the app then gets killed during syncs. Already picked one? Switch the
  server off, then **Rescale** it ("CPU and RAM only" keeps the disk, so you can scale back).
- **Networking:** keep **public IPv4** on (IPv6 too). The setup downloads from GitHub, which has
  no IPv6.
- **SSH keys:** **Add SSH key**, paste the line from above.
- **Name:** e.g. `coach`. Tailscale takes it over, so the dashboard's address becomes
  `https://coach.<your-tailnet>.ts.net`. Left at the default, it's something like
  `ubuntu-2gb-fsn1-1`; you can still [rename it later](#8-everyday-use-updates-backups).

Click **Create & Buy now** and note the server's IPv4 address. Other providers ask the same.

So you don't have to name the key every time, add the server to `~/.ssh/config` on the laptop:

```
Host coach
    HostName <server-ip>
    User root
    IdentityFile ~/.ssh/coach
    IdentitiesOnly yes
```

`ssh coach` then logs you in. (Without this, use `ssh -i ~/.ssh/coach root@<server-ip>`.)
`IdentitiesOnly` keeps ssh from trying your other keys first, which a server may refuse as
"too many authentication failures".

**At home:** install Ubuntu Server or Raspberry Pi OS (64-bit) and turn on SSH. You'll need the
machine's local IP address (from your router, or `hostname -I` on the machine).

## 2. Prepare the server
Log in from your laptop:

```sh
ssh coach                     # or ssh root@<server-ip>; a Raspberry Pi: ssh <your-user>@<pi-ip>
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

Opened from the home screen, the dashboard gets its own icon and runs full-screen, like an app.
On Android, Chrome may also offer **Install app**, which does the same.

**On Android, use Chrome.** The DuckDuckGo browser can't open Tailscale addresses (*Connection
refused*), although Tailscale is connected. You can keep DuckDuckGo for everything else.

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

- **Renaming the server later**, e.g. to `coach`: on the server, as root,
  ```sh
  tailscale set --hostname=coach
  sed -i 's|^Environment=COACH_ALLOWED_HOSTS=.*|Environment=COACH_ALLOWED_HOSTS=coach.<your-tailnet>.ts.net|' \
    /home/coach/.config/systemd/user/garmin-coach.service
  systemctl --user -M coach@ daemon-reload && systemctl --user -M coach@ restart garmin-coach
  tailscale serve reset && tailscale serve --bg 8080      # HTTPS for the new name
  ```
  `tailscale serve status` then shows the new address. The old one stops working: change
  bookmarks, and add the phone's home-screen icon again.
- **Another address** (e.g. a second Tailscale name): edit
  `~/.config/systemd/user/garmin-coach.service`. Comma-separate the names in the
  `Environment=COACH_ALLOWED_HOSTS=` line. Then run
  `systemctl --user daemon-reload && systemctl --user restart garmin-coach`.

## 9. Troubleshooting

| What you see | What to do |
|---|---|
| *Connection refused* on the phone, Tailscale connected | The browser. DuckDuckGo on Android can't open Tailscale addresses; use Chrome. Also check Tailscale's **Use Tailscale DNS** setting and Android's **Private DNS** (set it to Automatic). |
| The page doesn't load at all | Is Tailscale connected on this device? On the server: `tailscale status`, `tailscale serve status` (should show port 8080), `systemctl --user status garmin-coach`. |
| *"Requests from other websites are not allowed."* | The address you opened isn't allowed. Check the `COACH_ALLOWED_HOSTS` line in the service file (step 8). It must be exactly the name in your address bar, without `https://`. Each start writes the allowed names to `app.log` ("Server mode; allowed hosts: …"). |
| The service stops when you log out | Linger is off. Run `sudo loginctl enable-linger $USER`. |
| *"active (running)"*, but `curl` gets no answer | It's still starting (up to a minute). Otherwise look in `app.log`. |
| Garmin login fails or asks for MFA every time | On a VPS, copy the login from the laptop instead (step 5, option B). |
| Days are off by one, syncs at odd hours | Set the time zone (step 2), then `systemctl --user restart garmin-coach`. |
| The server is slow or the app gets killed | Too little memory: `free -h` shows it. Use 2 GB or more (on Hetzner: power off, **Rescale**), or add swap. |
| *"The data folder … is in use by another Garmin Coach"* | A second copy is running, e.g. one started by hand. `pkill -f coach.api`, then `systemctl --user restart garmin-coach`. |

## 10. Removing it
```sh
~/.local/share/garmin-coach/uninstall.sh       # asks before deleting your data
sudo tailscale serve reset                     # stops passing on port 8080
```
To also remove Tailscale: `sudo apt remove tailscale`. Then delete the machine in the admin
console.

## 11. More athletes (optional)
One install is one athlete: their data, athlete profile, plan and logins. To coach someone else
on the same server, add them as another athlete. They get a dashboard of their own, at the same
address with another port:

```
https://coach.tail1234.ts.net/        you (the first athlete, as before)
https://coach.tail1234.ts.net:8443/   anna
https://coach.tail1234.ts.net:8444/   the next one
```

Each athlete has their own data, backups, Garmin login and Coros login. Only the **Claude key is
shared**: all athletes' requests go to your Anthropic account. Settings › Claude & API costs then
has a **By athlete** table: what each athlete's requests cost in the chosen range and this
month, and all of them together (what Anthropic bills). The rest of the page (tasks, models,
requests) is about the dashboard's own athlete.

**Add one** (on the server, after the setup above):

```sh
curl -fsSL https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/add-athlete.sh \
  | sudo bash -s -- anna
```

The name may have letters, digits and dashes; as typed (`Nadine`) it is the name the dashboards
show, while services and folders use it in lower case (`nadine`). To show another name later, run
it again with `--label "Nadine M."`. The script runs a second copy of the installed app
as a service (`garmin-coach-athlete-anna`), passes it on with `tailscale serve`, and prints the
address. Then, on that address:
1. Settings › General: log in to Garmin and connect Coros with **anna's** accounts. The Claude
   key is already there.
2. The Athlete tab: write anna's profile.
3. Settings › General shows *"This is the athlete profile "anna"…"*, and the browser tab says
   *Coach · anna* (the shown name), so you can tell the dashboards apart.

**Access.** Whoever opens the address needs to be in your Tailscale network. For someone with
their own Tailscale account, share just the server: in the
[admin console](https://login.tailscale.com/admin/machines), ⋯ › **Share…**. Note that every
dashboard is open to everyone who reaches the server: anna could also open yours at the address
without a port. The app has no login of its own.

**Memory.** Each athlete runs their own app (about 400 MB, plus the Garmin connector during a
sync). A 2 GB server is enough; more athletes make it slower, not unstable:
- **The syncs take turns.** While one athlete syncs, the others wait. Their **Sync now** button
  says *"Waiting for another athlete…"* and their sync starts as soon as the other one is done.
- **Swap.** On a server under 4 GB, the script adds a swap file (about 1 GB per athlete, at least
  2 GB; `/swapfile-garmin-coach`). Short of memory, the system moves what isn't used right now
  to the disk, so things get slower instead of a process being killed.
- **Memory is handed back.** After a sync the app returns what it no longer needs, and an update
  restarts the athletes one at a time.
- If a dashboard feels slow, `free -h` shows memory and swap use. More memory (Hetzner: power
  off, **Rescale**) makes it fast again.

**Claude spending limits.** In the main athlete's Settings › Claude & API costs, each athlete can
get a daily and a monthly limit in US dollars, counted against their own requests only. Once one
is reached, that athlete's new Claude requests stop until the next day or month (local server
time). A request already running still finishes, so a limit can be passed by that last request.
The other athletes see their own limits there but can't change them.

**Coros** sends you back to the address you connected from, with its port. If that fails, use
the SSH tunnel from [step 5](#coros-if-you-use-it) with this athlete's local port (8081 for the
first extra athlete; `systemctl --user cat garmin-coach-athlete-anna` shows it):
`ssh -L 8081:127.0.0.1:8081 coach@<server-ip>`, then open <http://127.0.0.1:8081>.

**Updates** update all athletes at once: **Update now** in any dashboard, or the install command.

| | |
|---|---|
| Status | `systemctl --user status garmin-coach-athlete-anna` |
| Restart | `systemctl --user restart garmin-coach-athlete-anna` |
| Log | `~/.local/share/garmin-coach/athletes/anna/data/app.log` |
| Data and backups | `~/.local/share/garmin-coach/athletes/anna/data/` |
| Garmin login | `~/.garminconnect-anna` (copy a laptop login there, like step 5, option B) |
| Coros login | `~/.config/coach/coros-anna.json` |
| Remove (keeps the data) | `sudo bash add-athlete.sh anna --remove` |

Options, after the name: `--port 8085` (the app's local port), `--https-port 8450` (the port in
the address), `--user NAME` (when the app runs as another user than `coach`).

---

### Why only allowed addresses?
The app checks which address every request was sent to. It answers only for 127.0.0.1 and the
addresses you allowed. This stops other websites that are open in your browser from reaching
the dashboard through tricks like DNS rebinding. Your secrets stay on the server, readable by
your user only: the Garmin and Coros logins and the Claude key. Tailscale decides which devices
get to the server at all.
