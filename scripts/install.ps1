# Applies (or reverts) all optimizations to a Polarline 50PU52TC-SM / 58PU55STC-SM over adb.
#   powershell -ExecutionPolicy Bypass -File scripts\install.ps1 -Tv 192.168.1.50
#   ... -Revert                      undo everything
#   ... -NoRoot                      skip root CAs and boot-time component tweaks
#   ... -NoLauncher -NoKeyboard      do not install TV Home / LeanKey
#   ... -Adb C:\path\adb.exe         use this adb (otherwise PATH, then a download of Google platform-tools)
# Use at your own risk. See README.md.
param(
    [Parameter(Mandatory)] [string]$Tv,
    [switch]$Revert,
    [switch]$NoRoot,
    [switch]$NoLauncher,
    [switch]$NoKeyboard,
    [string]$Adb
)
# Not 'Stop': adb writes progress to stderr, which PowerShell 5.1 would turn into a fatal error.
$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $repo '.tools'
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }
$remote = '/data/local/tmp/polarline'

$LeanKeyUrl = 'https://github.com/yuliskov/LeanKeyboard/releases/download/6.1.31/LeanKeyboard_v6.1.31_playstore_r.apk'
$LeanKeySha = '5a90529fcae55c664128fb36e752f90e84d158266eced85084594c1d336a1468'

function Fail([string]$m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
function Step([string]$m) { Write-Host "== $m" -ForegroundColor Cyan }
function A { & $script:Adb -s $Tv @args }
function Sh([string]$cmd) { & $script:Adb -s $Tv shell $cmd }

function Find-Adb {
    if ($Adb) { return $Adb }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $local = Join-Path $tools 'platform-tools\adb.exe'
    if (-not (Test-Path $local)) {
        Step 'downloading Android platform-tools (adb) from dl.google.com'
        New-Item -ItemType Directory -Force $tools | Out-Null
        $zip = Join-Path $tools 'platform-tools.zip'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
        Expand-Archive $zip $tools -Force
        Remove-Item $zip
    }
    return $local
}

function Wait-Device([int]$seconds = 180) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $warned = $false
    while ((Get-Date) -lt $deadline) {
        & $script:Adb connect $Tv 2>&1 | Out-Null
        $state = "$(& $script:Adb -s $Tv get-state 2>&1)".Trim()
        if ($state -eq 'device') {
            if ("$(Sh 'getprop sys.boot_completed')".Trim() -eq '1') { return }
        } elseif ($state -match 'unauthorized' -and -not $warned) {
            Write-Host 'Confirm "Allow USB debugging" on the TV screen (tick "Always allow").' -ForegroundColor Yellow
            $warned = $true
        }
        Start-Sleep 3
    }
    Fail "no adb connection to $Tv. Check the IP address, that the TV and PC are on the same network (and no VPN routes the LAN), and that USB debugging is on."
}

function Test-Muted { "$(Sh 'dumpsys audio | grep -m1 Muted')" -match 'true' }

function Restore-Mute([bool]$wasMuted) {
    # The TV unmutes itself on every boot.
    if (-not $wasMuted) { return }
    for ($i = 0; $i -lt 10 -and -not (Test-Muted); $i++) { Sh 'input keyevent 164' | Out-Null; Start-Sleep 2 }
}

function Get-LeanKey {
    $apk = Join-Path $tools 'LeanKeyboard_v6.1.31.apk'
    if (-not (Test-Path $apk)) {
        Step 'downloading LeanKey keyboard from its author''s GitHub releases'
        New-Item -ItemType Directory -Force $tools | Out-Null
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest $LeanKeyUrl -OutFile $apk -UseBasicParsing
    }
    if ((Get-FileHash $apk -Algorithm SHA256).Hash.ToLower() -ne $LeanKeySha) {
        Remove-Item $apk
        Fail 'LeanKey APK checksum mismatch - deleted, run again'
    }
    return $apk
}

$script:Adb = Find-Adb
Step "connecting to $Tv"
Wait-Device
$product = "$(Sh 'getprop ro.product.name')".Trim()
if ($product -ne 'cvt_mt5886_eu_1g') { Fail "unexpected firmware '$product' (expected cvt_mt5886_eu_1g)" }
$wasMuted = Test-Muted
$mode = if ($Revert) { 'revert' } else { 'apply' }

if (-not $Revert) {
    if (-not $NoKeyboard) {
        $lk = Get-LeanKey
        Step 'installing LeanKey'
        A install -r $lk | Out-Host
    }
    if (-not $NoLauncher) {
        Step 'installing TV Home launcher'
        A install -r (Join-Path $repo 'apk\TvHome.apk') | Out-Host
        if ($LASTEXITCODE -ne 0) { Write-Host 'If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE: adb uninstall com.andvin.tvhome, then run again.' -ForegroundColor Yellow }
    }
}

Step 'copying scripts to the TV'
Sh "rm -rf $remote; mkdir -p $remote/certs" | Out-Null
A push (Join-Path $repo 'scripts\device\optimize.sh') "$remote/optimize.sh" | Out-Null
A push (Join-Path $repo 'scripts\device\root.sh') "$remote/root.sh" | Out-Null
Get-ChildItem (Join-Path $repo 'certs') -Filter '*.0' | ForEach-Object { A push $_.FullName "$remote/certs/$($_.Name)" | Out-Null }

Step "optimize.sh $mode"
Sh "sh $remote/optimize.sh $mode" | Out-Host

if (-not $NoRoot) {
    if ("$(Sh 'getprop ro.debuggable')".Trim() -ne '1') { Fail 'ro.debuggable is not 1 - adb root is not available; rerun with -NoRoot' }
    Step 'adb root'
    A root | Out-Host
    Start-Sleep 5
    Wait-Device
    Step "root.sh $mode"
    Sh "sh $remote/root.sh $mode $remote/certs" | Out-Host
    A unroot 2>&1 | Out-Null
    Start-Sleep 5
    Wait-Device
}

Sh "rm -rf $remote" | Out-Null
if (-not $NoRoot) {
    Step 'rebooting (about a minute)'
    A reboot | Out-Null
    Start-Sleep 20
    Wait-Device 300
    Restore-Mute $wasMuted
}
Step 'done'
