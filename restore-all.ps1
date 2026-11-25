# Safely restore stock Google TV Launcher and re-enable any debloated packages in safe order
# (re-enables launcherx BEFORE setupwraith, then syncs disk).
#
# Usage:
#   .\restore-all.ps1 -TvHost 192.168.1.50
#   .\restore-all.ps1 -TvHost 192.168.1.50 -KeepLauncherInstalled
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$DebloatFile = (Join-Path $PSScriptRoot 'my-debloat.txt'),
    [switch]$KeepLauncherInstalled
)
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$restoreScript = Join-Path $root 'cleanyourtv\tools\restore.ps1'
$py = Join-Path $root 'cleanyourtv\tools\venv\Scripts\python.exe'
$tvPy = Join-Path $root 'cleanyourtv\tools\tv.py'

Write-Host "Restoring stock Google TV launcher and re-enabling packages on $TvHost..."
powershell -ExecutionPolicy Bypass -File $restoreScript -TvHost $TvHost $DebloatFile -Stock 'com.google.android.apps.tv.launcherx'
if ($LASTEXITCODE -ne 0) { throw "Restore via restore.ps1 failed ($LASTEXITCODE)." }

if (-not $KeepLauncherInstalled) {
    $env:TV_HOST = $TvHost
    Write-Host "Uninstalling Matcher / CleanYourTV Launcher (com.gathin.tvhome)..."
    & $py -I $tvPy 'pm uninstall com.gathin.tvhome'
}

$env:TV_HOST = $TvHost
& $py -I $tvPy 'sync'
Write-Host "Done. Wait 15 seconds before rebooting your TV box or disabling ADB debugging."
