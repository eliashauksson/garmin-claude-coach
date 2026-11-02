# Smoke test of an installed Garmin Coach, on Windows (PowerShell 5.1 or 7)
# and Linux (pwsh). Run it right after the installer, which starts the app.
# The release workflow runs it on fresh Windows and Linux machines before it
# publishes anything. Exits 1 when a check fails.

$ErrorActionPreference = 'Stop'
$base   = 'http://127.0.0.1:8080'
$onWin  = ($env:OS -eq 'Windows_NT')
$dir    = if ($onWin) { Join-Path $env:LOCALAPPDATA 'GarminCoach' } else { Join-Path $HOME '.local/share/garmin-coach' }
$script:failed = 0

function Check($ok, $what) {
  if ($ok) { Write-Host "ok    $what" } else { Write-Host "FAIL  $what" -ForegroundColor Red; $script:failed++ }
}

function Api($method, $path, $body) {
  $p = @{ Method = $method; Uri = "$base$path"; TimeoutSec = 400; UseBasicParsing = $true }
  if ($null -ne $body) { $p.ContentType = 'application/json'; $p.Body = ($body | ConvertTo-Json -Compress) }
  Invoke-RestMethod @p
}

function Started { try { (Invoke-RestMethod -Uri "$base/api/sync" -TimeoutSec 3 -UseBasicParsing).started_at } catch { $null } }

function Wait-Up($old, $what) {
  # until the app answers (as a new process, when $old is given)
  for ($i = 0; $i -lt 90; $i++) {
    $s = Started
    if ($s -and $s -ne $old) { Write-Host "      ($what after $(2 * $i) s)"; return $s }
    Start-Sleep -Seconds 2
  }
  throw "The app didn't come up: $what"
}

function Show-Log {
  $log = Join-Path $dir 'data/app.log'
  if (Test-Path $log) { Write-Host '--- app.log ---'; Get-Content $log -Tail 40 }
}

try {
  $started = Wait-Up $null 'started by the installer'

  # --- the installed app
  $app = Api GET '/api/app'
  Check $app.installed "installed app (version $($app.version))"
  Check ($app.data_dir -like "$dir*") "data folder in the app folder: $($app.data_dir)"
  $page = Invoke-WebRequest -Uri "$base/" -UseBasicParsing
  Check ($page.Content -match '<html') 'the page itself'
  $js = Invoke-WebRequest -Uri "$base/js/main.js" -UseBasicParsing
  Check ($js.StatusCode -eq 200) 'the page script'
  $uvx = if ($onWin) { 'runtime/uv/uvx.exe' } else { 'runtime/uv/uvx' }
  Check (Test-Path (Join-Path $dir $uvx)) 'uv installed'

  if ($onWin) {
    $programs = [Environment]::GetFolderPath('Programs')
    Check (Test-Path (Join-Path $programs 'Garmin Coach.lnk')) 'Start menu shortcut'
    Check (Test-Path (Join-Path $programs 'Uninstall Garmin Coach.lnk')) 'Start menu uninstaller'
    $lnk = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $programs 'Garmin Coach.lnk'))
    Check ($lnk.TargetPath -like '*javaw.exe' -and $lnk.WorkingDirectory -eq $dir) "shortcut runs javaw in the app folder"
  } else {
    Check (Test-Path (Join-Path $HOME '.local/share/applications/garmin-coach.desktop')) 'app menu entry'
  }

  # --- other websites are refused
  try {
    Invoke-WebRequest -Method Post -Uri "$base/api/quit" -Headers @{ Origin = 'https://evil.example' } -UseBasicParsing | Out-Null
    Check $false 'a foreign website is refused'
  } catch { Check ($_.Exception.Response.StatusCode.value__ -eq 403) 'a foreign website is refused (403)' }

  # --- start with the computer
  $app = Api PUT '/api/app/autostart' @{ on = $true }
  Check $app.autostart 'start with the computer: on'
  if ($onWin) {
    $run = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -ErrorAction SilentlyContinue).'Garmin Coach'
    Check ($run -match 'javaw\.exe" -cp ".*garmin-coach\.jar" clojure\.main -m coach\.api 8080 --background') "Run entry: $run"
  } else {
    Check (Test-Path (Join-Path $HOME '.config/autostart/garmin-coach.desktop')) 'autostart entry'
  }
  $app = Api PUT '/api/app/autostart' @{ on = $false }
  Check (-not $app.autostart) 'start with the computer: off'

  # --- Garmin login: a made-up account must come back as a clean error
  # (runs the Garmin connector through uv and Python, with its output piped)
  $login = Api POST '/api/garmin/login' @{ email = 'nobody-coach-smoke-test@example.invalid'; password = 'not-a-password' }
  Check ($login.status -eq 'error' -and $login.error -notmatch 'Server error') "Garmin login with a wrong password: $($login.error)"

  # --- backup and restore (restarts the app)
  Api POST '/api/backup' $null | Out-Null
  $backups = (Api GET '/api/backup').backups
  Check ($backups.Count -ge 1) "backup made ($($backups[0].name))"
  Api PUT '/api/lang' @{ lang = 'de' } | Out-Null
  Api POST '/api/backup/restore' @{ name = $backups[0].name } | Out-Null
  $started = Wait-Up $started 'restarted after restore'
  $app = Api GET '/api/app'
  Check ($app.notice.kind -eq 'restore' -and $app.notice.ok) "restored: $($app.notice.message)"
  Check ((Api GET '/api/lang').lang -eq 'en') 'restore brought back the earlier language'

  # --- moving the data folder (restarts the app), then back
  $moved = Join-Path ([IO.Path]::GetTempPath()) ("coach data " + [Guid]::NewGuid())
  Api POST '/api/app/data-dir' @{ path = $moved } | Out-Null
  $started = Wait-Up $started 'restarted after moving the data'
  $app = Api GET '/api/app'
  Check ($app.data_dir -eq $moved -and (Test-Path (Join-Path $moved 'coach.db'))) "data moved to $moved"
  Check (-not (Test-Path (Join-Path $dir 'data/coach.db'))) 'the old data folder is gone'
  Api POST '/api/app/data-dir' @{ path = (Join-Path $dir 'data') } | Out-Null
  $started = Wait-Up $started 'restarted after moving the data back'
  $app = Api GET '/api/app'
  Check ($app.data_dir -like "$dir*" -and -not (Test-Path $moved)) 'data moved back'

  # --- quit
  Api POST '/api/quit' $null | Out-Null
  Start-Sleep -Seconds 3
  Check (-not (Started)) 'quit'

  # --- uninstall, keeping the data
  $env:GARMIN_COACH_KEEP_DATA = '1'
  if ($onWin) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dir 'uninstall.ps1') | Out-Host
    Check (-not (Test-Path (Join-Path ([Environment]::GetFolderPath('Programs')) 'Garmin Coach.lnk'))) 'uninstalled: shortcut gone'
  } else {
    & bash (Join-Path $dir 'uninstall.sh') | Out-Host
    Check (-not (Test-Path (Join-Path $HOME '.local/share/applications/garmin-coach.desktop'))) 'uninstalled: menu entry gone'
  }
  Check (-not (Test-Path (Join-Path $dir 'runtime'))) 'uninstalled: app removed'
  Check (Test-Path (Join-Path $dir 'data/coach.db')) 'uninstalled: data kept'
} catch {
  Check $false "unexpected: $($_.Exception.Message)"
  Show-Log
}

if ($script:failed) { Show-Log; Write-Host "$($script:failed) check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed.'
