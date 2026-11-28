# Matcher Fast Binary Updater (Windows) - No JDK, No Android SDK, No Gradle required!
# Downloads manifest.json & only changed signed APKs from GitHub Releases, verifies SHA-256,
# installs in-place via `adb install -r`, preserves theme/debloat/folders/keys, and re-binds Accessibility services.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\update.ps1 -TvHost 192.168.1.50
#   powershell -ExecutionPolicy Bypass -File .\update.ps1 -TvHost 192.168.1.50:37891 -LauncherOnly
#   powershell -ExecutionPolicy Bypass -File .\update.ps1 -TvHost 192.168.1.50 -Force
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Repo = 'ryslavy/matcher',
    [switch]$LauncherOnly,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Find-Adb {
    $candidates = @(
        (Join-Path $root 'toolchain\android-sdk\platform-tools\adb.exe'),
        (Join-Path $root 'platform-tools\adb.exe')
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb not found. Install Android Platform-Tools or place adb.exe in .\platform-tools\."
}

function Get-InstalledVersionCode([string]$adbPath, [string]$target, [string]$pkg) {
    $out = & $adbPath -s $target shell "dumpsys package $pkg 2>/dev/null | grep -m1 'versionCode='"
    if ("$out" -match 'versionCode=(\d+)') { return [int64]$Matches[1] }
    return -1
}

function Verify-Sha256([string]$filePath, [string]$expected) {
    if (-not $expected) { return }
    $actual = (Get-FileHash -Path $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected.ToLowerInvariant()) {
        Remove-Item -Force -ErrorAction SilentlyContinue $filePath
        throw "SHA-256 mismatch for $(Split-Path -Leaf $filePath)! Expected $expected, got $actual."
    }
}

$adb = Find-Adb
Write-Host "Using ADB: $adb"
& $adb connect $TvHost | Out-Null
$state = (& $adb -s $TvHost get-state 2>&1) -join ''
if ($state -notmatch 'device') {
    throw "Cannot connect to $TvHost via ADB (state: $state). Check IP/port and Wireless Debugging."
}

Write-Host "Fetching latest release info from github.com/$Repo ..."
$headers = @{ 'Accept' = 'application/vnd.github+json'; 'User-Agent' = 'Matcher-Updater' }
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers
$manifestAsset = $rel.assets | Where-Object { $_.name -eq 'manifest.json' } | Select-Object -First 1
if (-not $manifestAsset) {
    throw "Latest release $($rel.tag_name) has no manifest.json asset yet."
}
$manifest = Invoke-RestMethod -Uri $manifestAsset.browser_download_url -Headers @{ 'User-Agent' = 'Matcher-Updater' }
Write-Host "Latest release: $($manifest.tag) (Launcher versionCode $($manifest.launcher.versionCode))"

# Save exact existing Accessibility services BEFORE installing anything
$prevA11y = ((& $adb -s $TvHost shell "settings get secure enabled_accessibility_services") -join '').Trim()

$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("matcher-update-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$updatedCount = 0

try {
    # 1. Launcher
    $lPkg = $manifest.launcher.packageName
    $curCode = Get-InstalledVersionCode $adb $TvHost $lPkg
    $remCode = [int64]$manifest.launcher.versionCode
    if ($Force -or $curCode -lt $remCode) {
        Write-Host "Updating Launcher ($curCode -> $remCode): $($manifest.launcher.apk) ..."
        $apkPath = Join-Path $tmpDir $manifest.launcher.apk
        Invoke-WebRequest -Uri $manifest.launcher.url -OutFile $apkPath -UseBasicParsing
        Verify-Sha256 $apkPath $manifest.launcher.sha256
        $res = (& $adb -s $TvHost install -r $apkPath 2>&1) -join "`n"
        Write-Host $res
        if ($LASTEXITCODE -ne 0 -or $res -notmatch 'Success') {
            throw "Launcher install failed. If INSTALL_FAILED_UPDATE_INCOMPATIBLE, the installed build uses a different signing key."
        }
        $updatedCount++
    } else {
        Write-Host "Launcher is already up to date (versionCode $curCode)."
    }

    # 2. Only installed Add-ons
    if (-not $LauncherOnly -and $manifest.addons) {
        foreach ($addon in $manifest.addons) {
            $aCur = Get-InstalledVersionCode $adb $TvHost $addon.packageName
            if ($aCur -lt 0) { continue } # Not installed on this TV - skip!
            $aRem = [int64]$addon.versionCode
            if ($Force -or $aCur -lt $aRem) {
                Write-Host "Updating add-on $($addon.name) ($aCur -> $aRem): $($addon.apk) ..."
                $apkPath = Join-Path $tmpDir $addon.apk
                Invoke-WebRequest -Uri $addon.url -OutFile $apkPath -UseBasicParsing
                Verify-Sha256 $apkPath $addon.sha256
                $res = (& $adb -s $TvHost install -r $apkPath 2>&1) -join "`n"
                Write-Host $res
                if ($LASTEXITCODE -ne 0 -or $res -notmatch 'Success') {
                    Write-Warning "Skipping add-on $($addon.name) due to install error (different signing key?)."
                } else {
                    $updatedCount++
                }
            }
        }
    }

    if ($updatedCount -gt 0) {
        # Re-assert launcher runtime permissions safely (no debloat or theme touched)
        & $adb -s $TvHost shell "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; true" | Out-Null
        if ($prevA11y -and $prevA11y -ne 'null') {
            Write-Host "Re-binding Accessibility services: $prevA11y"
            & $adb -s $TvHost shell "settings put secure enabled_accessibility_services ''"
            Start-Sleep -Seconds 1
            & $adb -s $TvHost shell "settings put secure enabled_accessibility_services '$prevA11y'; settings put secure accessibility_enabled 1; sync"
        } else {
            & $adb -s $TvHost shell "sync"
        }
        Start-Sleep -Seconds 2
        & $adb -s $TvHost shell "am broadcast -a com.gathin.tvhome.ACTION_RELOAD -p com.gathin.tvhome --ez tiles true 2>/dev/null; am start -n com.gathin.tvhome/.MainActivity" | Out-Null
        Write-Host "`n=== Accessibility Check ==="
        & $adb -s $TvHost shell "dumpsys accessibility | grep -E 'Bound services|Service\[label=|Crashed services'"
        Write-Host "`nDone! Updated $updatedCount package(s). Wait 15 seconds before rebooting your TV."
    } else {
        Write-Host "`nEverything installed on $TvHost is already up to date!"
    }
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmpDir
}
