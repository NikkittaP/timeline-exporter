<#
.SYNOPSIS
  Capture the store screenshots on all three emulators, unattended.

.DESCRIPTION
  For each slot in turn: shut down any running emulator, cold-boot the slot's
  AVD, wait for it to finish booting, `adb root` it, keep its screen awake,
  run the matching `fastlane screens_*` lane, then shut the emulator down.
  A lane that fails is run once more on a freshly booted emulator. When the
  slots are done it runs `decorate_screens`, and `upload_screens` only when
  -Upload is given and every slot went through.

  Everything is logged to build\screens_logs\screens_<date>.log. The PC is
  kept from sleeping while the script runs.

  Usually started through run_all_screens.bat in the project root.

.PARAMETER Slots
  Which slots to capture, in order, comma-separated: phone, tablet7,
  tablet10. Default: all three.

.PARAMETER Locales
  Passed to the lanes as locales:<value>, e.g. "ru-RU,ar". Default: all 18.

.PARAMETER Upload
  After a clean run, push the screenshots to Play with upload_screens.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\run_all_screens.ps1
  powershell -ExecutionPolicy Bypass -File tools\run_all_screens.ps1 -Slots phone -Locales ru-RU,ar
#>
param(
    # A string, not string[]: through `powershell -File` (the .bat) a list
    # arrives as the single argument "phone,tablet7".
    [string]$Slots = "phone,tablet7,tablet10",
    [string]$Locales = "",
    [switch]$Upload
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# fastlane warns on every lane without these.
$env:LANG = "en_US.UTF-8"
$env:LC_ALL = "en_US.UTF-8"

$ProjectDir = Split-Path -Parent $PSScriptRoot

# Slot -> AVD and lane. The AVDs are the ones the store shots have always
# been taken on; see SCREENSHOTS_SETUP.md.
$SlotConfig = @{
    phone    = @{ Avd = "Pixel_7";       Lane = "screens_phone" }
    tablet7  = @{ Avd = "7_inch_Tablet"; Lane = "screens_tablet7" }
    tablet10 = @{ Avd = "Pixel_Tablet";  Lane = "screens_tablet10" }
}

$BootTimeoutSec = 600
$ShutdownTimeoutSec = 90

# A new variable: assigned back to the [string] parameter, the list would be
# joined into "phone tablet7 tablet10".
$SlotList = @($Slots -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($slot in $SlotList) {
    if (-not $SlotConfig.ContainsKey($slot)) {
        Write-Host "Unknown slot '$slot'. Use phone, tablet7, tablet10." -ForegroundColor Red
        exit 1
    }
}

# ---- SDK paths -------------------------------------------------------------

# local.properties has sdk.dir=G\:\\Android\\sdk (escaped as a .properties value).
$SdkDir = "G:\Android\sdk"
$localProps = Join-Path $ProjectDir "local.properties"
if (Test-Path $localProps) {
    $line = Select-String -Path $localProps -Pattern '^sdk\.dir=(.*)$' | Select-Object -First 1
    if ($line) { $SdkDir = $line.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\' }
}
$Adb = Join-Path $SdkDir "platform-tools\adb.exe"
$Emulator = Join-Path $SdkDir "emulator\emulator.exe"
foreach ($tool in @($Adb, $Emulator)) {
    if (-not (Test-Path $tool)) { Write-Host "Not found: $tool" -ForegroundColor Red; exit 1 }
}

# ---- logging ---------------------------------------------------------------

$LogDir = Join-Path $ProjectDir "build\screens_logs"
New-Item -ItemType Directory -Force $LogDir | Out-Null
$LogFile = Join-Path $LogDir ("screens_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmm"))

function Log([string]$Message, [string]$Color = "Cyan") {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message
    Write-Host $line -ForegroundColor $Color
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# ---- keep the PC awake -----------------------------------------------------

Add-Type -Namespace Win32 -Name Power -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
# ES_CONTINUOUS | ES_SYSTEM_REQUIRED: no sleep while this process runs; the
# monitor may still turn off.
[Win32.Power]::SetThreadExecutionState([uint32]"0x80000001") | Out-Null

# ---- adb / emulator helpers ------------------------------------------------

function Invoke-Adb([string[]]$AdbArgs) {
    $out = & $Adb @AdbArgs 2>$null | Out-String
    return $out.Trim()
}

function Get-RunningEmulators {
    (Invoke-Adb @("devices")) -split "`r?`n" |
        Where-Object { $_ -match '^(emulator-\d+)\s+(device|offline|unauthorized)' } |
        ForEach-Object { $Matches[1] }
}

function Stop-AllEmulators {
    $serials = @(Get-RunningEmulators)
    foreach ($s in $serials) {
        Log "Shutting down $s"
        Invoke-Adb @("-s", $s, "emu", "kill") | Out-Null
    }
    $deadline = (Get-Date).AddSeconds($ShutdownTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $alive = @(Get-Process -Name "qemu-system-*", "emulator" -ErrorAction SilentlyContinue)
        if ($alive.Count -eq 0) { return }
        Start-Sleep -Seconds 2
    }
    Log "Emulator did not exit in ${ShutdownTimeoutSec}s, killing the process" "Yellow"
    Get-Process -Name "qemu-system-*", "emulator" -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}

function Wait-BootCompleted([string]$Serial, [datetime]$Deadline) {
    while ((Get-Date) -lt $Deadline) {
        if ((Invoke-Adb @("-s", $Serial, "shell", "getprop", "sys.boot_completed")) -eq "1") { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Start-Avd([string]$Avd) {
    Stop-AllEmulators
    Log "Booting $Avd (cold boot)"
    # Not -no-window / -qt-hide-window: with nothing consuming its frames the
    # AVD stalls in eglSwapBuffers and SystemUI starts to ANR.
    Start-Process -FilePath $Emulator `
        -ArgumentList @("-avd", $Avd, "-no-snapshot", "-no-boot-anim") `
        -WindowStyle Minimized | Out-Null

    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    $serial = $null
    while (-not $serial -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $serial = @(Get-RunningEmulators) | Select-Object -First 1
    }
    if (-not $serial) { Log "$Avd never showed up in adb" "Red"; return $null }
    if (-not (Wait-BootCompleted $serial $deadline)) { Log "$Avd did not finish booting" "Red"; return $null }

    # screengrab pulls the captures from the app's private storage, which
    # needs a root adbd. Restarting adbd drops the connection for a moment.
    Log "adb root on $serial"
    Invoke-Adb @("-s", $serial, "root") | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb @("-s", $serial, "wait-for-device") | Out-Null
    if (-not (Wait-BootCompleted $serial $deadline)) { Log "$Avd lost after adb root" "Red"; return $null }

    # A full pass is close to an hour. Keep the screen on for all of it;
    # ScreenshotTest wakes the device too, but a dark screen between tests
    # is what killed the de-DE pass.
    Invoke-Adb @("-s", $serial, "shell", "svc", "power", "stayon", "true") | Out-Null
    Invoke-Adb @("-s", $serial, "shell", "settings", "put", "system", "screen_off_timeout", "1800000") | Out-Null
    Invoke-Adb @("-s", $serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    Invoke-Adb @("-s", $serial, "shell", "wm", "dismiss-keyguard") | Out-Null

    # The launcher and SystemUI keep settling for a while after boot_completed.
    Log "$Avd is up as $serial, letting it settle"
    Start-Sleep -Seconds 30
    return $serial
}

# ---- fastlane --------------------------------------------------------------

function Invoke-Lane([string]$Lane, [string]$LaneArgs = "") {
    $cmd = "bundle exec fastlane $Lane $LaneArgs".Trim()
    Log "> $cmd"
    Push-Location $ProjectDir
    try {
        # Through cmd so stderr is merged as plain text; PowerShell 5.1 would
        # wrap each stderr line in an error record.
        cmd /c "$cmd 2>&1" | ForEach-Object {
            Write-Host $_
            Add-Content -Path $LogFile -Value $_ -Encoding UTF8
        }
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
}

# ---- main ------------------------------------------------------------------

$started = Get-Date
$localeArg = if ($Locales) { "locales:$Locales" } else { "" }
$results = [ordered]@{}

Log ("Slots: {0}; locales: {1}; upload: {2}" -f ($SlotList -join ", "), $(if ($Locales) { $Locales } else { "all" }), [bool]$Upload)
Log "Log file: $LogFile"

foreach ($slot in $SlotList) {
    $cfg = $SlotConfig[$slot]
    $ok = $false
    foreach ($attempt in 1..2) {
        Log ("=== {0} on {1}, attempt {2} of 2 ===" -f $slot, $cfg.Avd, $attempt) "Green"
        $serial = Start-Avd $cfg.Avd
        if (-not $serial) { continue }
        $ok = Invoke-Lane $cfg.Lane $localeArg
        if ($ok) { break }
        Log "$($cfg.Lane) failed" "Red"
    }
    $results[$slot] = $ok
    Stop-AllEmulators
}

$allOk = -not ($results.Values -contains $false)

Log "=== decorate_screens ===" "Green"
$decorated = Invoke-Lane "decorate_screens"
if (-not $decorated) { $allOk = $false }

if ($Upload) {
    if ($allOk) {
        Log "=== upload_screens ===" "Green"
        $results["upload"] = Invoke-Lane "upload_screens"
        if (-not $results["upload"]) { $allOk = $false }
    } else {
        Log "Skipping upload_screens: not every step went through" "Yellow"
    }
}

[Win32.Power]::SetThreadExecutionState([uint32]"0x80000000") | Out-Null

Log ("=== Done in {0:hh\:mm\:ss} ===" -f ((Get-Date) - $started)) "Green"
foreach ($k in $results.Keys) {
    Log ("  {0,-9} {1}" -f $k, $(if ($results[$k]) { "OK" } else { "FAILED" })) $(if ($results[$k]) { "Green" } else { "Red" })
}
Log ("  {0,-9} {1}" -f "decorate", $(if ($decorated) { "OK" } else { "FAILED" })) $(if ($decorated) { "Green" } else { "Red" })
Log "Log file: $LogFile"

if ($allOk) { exit 0 } else { exit 1 }
