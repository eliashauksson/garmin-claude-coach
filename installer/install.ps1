# Garmin Coach installer for Windows.
#
# Double-click "install-windows.cmd" (from the latest release), or press
# Win+R and paste:
#   powershell -ExecutionPolicy Bypass -c "irm https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.ps1 | iex"
#
# It installs everything the app needs into %LOCALAPPDATA%\GarminCoach (no
# admin rights, nothing system-wide), adds "Garmin Coach" to the Start menu
# and the desktop, and starts it. Run it again to update; your data is kept.
#
# From a clone of the repository it installs the jar you built
# (target\garmin-coach.jar) instead of downloading the latest release.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with its progress bar
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Repo    = 'eliashauksson/garmin-claude-coach'
$Release = "https://github.com/$Repo/releases/latest/download"
$Dir     = if ($env:GARMIN_COACH_DIR) { $env:GARMIN_COACH_DIR } else { Join-Path $env:LOCALAPPDATA 'GarminCoach' }
$Port    = if ($env:GARMIN_COACH_PORT) { $env:GARMIN_COACH_PORT } else { '8080' }

function Say($text) { Write-Host ''; Write-Host $text -ForegroundColor Cyan }

function Download($url, $target) {
  for ($i = 1; $i -le 3; $i++) {
    try { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $target; return }
    catch { if ($i -eq 3) { throw "Couldn't download $url. Check the internet connection. ($($_.Exception.Message))" } Start-Sleep -Seconds 3 }
  }
}

function Expand-Single($zip, $target) {
  # Unzips $zip and moves the content of its single top folder (or the files
  # themselves, if there is none) to $target.
  $tmp = "$target.unpack"   # next to the target: Move-Item can't move folders across drives
  if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  $items = @(Get-ChildItem $tmp)
  $src = if ($items.Count -eq 1 -and $items[0].PSIsContainer) { $items[0].FullName } else { $tmp }
  if (Test-Path $target) { Remove-Item $target -Recurse -Force }
  New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
  Move-Item $src $target
  if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
}

function Install-GarminCoach {
  switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { $javaArch = 'x64';     $uvArch = 'x86_64' }
    'ARM64' { $javaArch = 'aarch64'; $uvArch = 'aarch64' }
    default { throw "This computer's processor ($($env:PROCESSOR_ARCHITECTURE)) isn't supported." }
  }

  # A local build when run from the repository (not when piped from the web)
  $localJar = $null
  if ($PSScriptRoot -and (Test-Path (Join-Path $PSScriptRoot '..\target\garmin-coach.jar'))) {
    $localJar = (Resolve-Path (Join-Path $PSScriptRoot '..\target\garmin-coach.jar')).Path
  }

  $tmp = Join-Path ([IO.Path]::GetTempPath()) ("garmin-coach-" + [Guid]::NewGuid())
  New-Item -ItemType Directory -Force -Path $tmp, "$Dir\app", "$Dir\runtime" | Out-Null

  Say "Installing Garmin Coach into $Dir"

  # A running copy keeps its files open: stop it first (it saves everything as it goes)
  try {
    Invoke-WebRequest -UseBasicParsing -Method Post -TimeoutSec 2 -Uri "http://127.0.0.1:$Port/api/quit" | Out-Null
    Write-Host 'Stopping the running app...'
    Start-Sleep -Seconds 3
  } catch { }

  # 1. Java, only for this app
  if (Test-Path "$Dir\runtime\jre\bin\javaw.exe") {
    Write-Host 'Java: already there.'
  } else {
    Say '1/4  Downloading Java (about 50 MB)...'
    Download "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$javaArch/jre/hotspot/normal/eclipse" "$tmp\jre.zip"
    Expand-Single "$tmp\jre.zip" "$Dir\runtime\jre"
  }

  # 2. uv, which runs the Garmin connector (and fetches Python for it)
  if (Test-Path "$Dir\runtime\uv\uvx.exe") {
    Write-Host 'uv: already there.'
  } else {
    Say '2/4  Downloading uv (about 20 MB)...'
    Download "https://github.com/astral-sh/uv/releases/latest/download/uv-$uvArch-pc-windows-msvc.zip" "$tmp\uv.zip"
    Expand-Single "$tmp\uv.zip" "$Dir\runtime\uv"
  }

  # 3. The app itself
  Say '3/4  Installing the app...'
  if ($localJar) {
    Write-Host "Using the local build $localJar"
    Copy-Item $localJar "$Dir\app\garmin-coach.jar.new" -Force
    Copy-Item (Join-Path $PSScriptRoot 'icon.ico') "$Dir\app\icon.ico" -Force
  } else {
    Download "$Release/garmin-coach.jar" "$Dir\app\garmin-coach.jar.new"
    Download "$Release/icon.ico" "$Dir\app\icon.ico"
  }
  Move-Item "$Dir\app\garmin-coach.jar.new" "$Dir\app\garmin-coach.jar" -Force

  # 4. Python and the Garmin connector, so the first login doesn't wait for them
  Say '4/4  Preparing the Garmin connector (downloads Python the first time)...'
  Push-Location $Dir
  $ErrorActionPreference = 'Continue'   # uv reports its progress on stderr
  try {
    & "$Dir\runtime\jre\bin\java.exe" -cp 'app\garmin-coach.jar' clojure.main -m coach.garmin-mcp prepare
    $prepared = ($LASTEXITCODE -eq 0)
  } finally { Pop-Location; $ErrorActionPreference = 'Stop' }
  if (-not $prepared) { throw "The Garmin connector couldn't be prepared. Check the internet connection and run this again." }

  # Shortcuts: Start menu and desktop. javaw.exe runs without a console window.
  $shell = New-Object -ComObject WScript.Shell
  $javaw = "$Dir\runtime\jre\bin\javaw.exe"
  $appArgs = "-cp app\garmin-coach.jar clojure.main -m coach.api $Port --open"
  $startMenu = [Environment]::GetFolderPath('Programs')
  $desktop   = [Environment]::GetFolderPath('Desktop')
  foreach ($folder in @($startMenu, $desktop)) {
    if (-not $folder) { continue }
    $lnk = $shell.CreateShortcut((Join-Path $folder 'Garmin Coach.lnk'))
    $lnk.TargetPath       = $javaw
    $lnk.Arguments        = $appArgs
    $lnk.WorkingDirectory = $Dir
    $lnk.IconLocation     = "$Dir\app\icon.ico"
    $lnk.Description      = 'Your training dashboard, coached by Claude'
    $lnk.Save()
  }

  # Uninstaller, with its own Start menu entry. Paths go into single quotes
  # there, so a ' (as in a user name like O'Brien) is doubled.
  $q = { param($path) $path -replace "'", "''" }
  $dirQ = & $q $Dir; $startQ = & $q $startMenu; $deskQ = & $q $desktop
  $uninstall = @"
# Removes Garmin Coach. Asks before deleting your training data.
try { Invoke-WebRequest -UseBasicParsing -Method Post -TimeoutSec 2 -Uri 'http://127.0.0.1:$Port/api/quit' | Out-Null; Start-Sleep -Seconds 3 } catch { }
Remove-Item -Force -ErrorAction SilentlyContinue '$startQ\Garmin Coach.lnk', '$deskQ\Garmin Coach.lnk', '$startQ\Uninstall Garmin Coach.lnk'
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue '$dirQ\app', '$dirQ\runtime'
`$answer = Read-Host 'Also delete your training data in $dirQ\data? (y/N)'
if (`$answer -match '^[yY]') { Set-Location `$env:TEMP; Remove-Item -Recurse -Force '$dirQ' }
else { Remove-Item -Force '$dirQ\uninstall.ps1'; Write-Host 'Your data is kept in $dirQ\data.' }
Write-Host 'Garmin Coach is removed.'
Read-Host 'Press Enter to close'
"@
  Set-Content -Path "$Dir\uninstall.ps1" -Value $uninstall -Encoding UTF8
  $lnk = $shell.CreateShortcut((Join-Path $startMenu 'Uninstall Garmin Coach.lnk'))
  $lnk.TargetPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
  $lnk.Arguments  = "-NoProfile -ExecutionPolicy Bypass -File `"$Dir\uninstall.ps1`""
  $lnk.Save()

  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

  Say 'Done! Garmin Coach is installed.'
  Write-Host 'Find it in the Start menu or on the desktop. Starting it now...'
  Start-Process -FilePath $javaw -ArgumentList $appArgs -WorkingDirectory $Dir
  Write-Host 'It opens in your browser in a few seconds.'
  Write-Host 'To uninstall later: Start menu > Uninstall Garmin Coach.'
}

try {
  Install-GarminCoach
} catch {
  Write-Host ''
  Write-Host "The installation stopped: $($_.Exception.Message)" -ForegroundColor Red
}
