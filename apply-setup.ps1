# Complete automated build & deployment script for Matcher (Android TV / Google TV Liquid Glass Launcher + 8 Add-ons)
# Usage:
#   .\apply-setup.ps1 -TvHost 192.168.1.50
#   .\apply-setup.ps1 -TvHost 192.168.1.50:36977 -SkipDebloat
#   .\apply-setup.ps1 -TvHost 192.168.1.50 -DebloatFile .\presets\xiaomi-tv-box-s-3rd-gen\debloat.txt
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$DebloatFile = (Join-Path $PSScriptRoot 'my-debloat.txt'),
    [string]$UpstreamRef = 'be6087056a1c7d4f08f3f1cc778d92374d86ef27',
    [switch]$SkipDebloat,
    [switch]$SkipAddons,
    [switch]$SkipTheme
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 1. Download Google Android Platform-Tools (ADB) if missing
$adb = Join-Path $root 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Host 'Downloading Google Android Platform-Tools...'
    $zip = Join-Path $root 'platform-tools-latest-windows.zip'
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $root -Force
    Remove-Item $zip -Force
}

# 2. Clone verified upstream Gathin23/cleanyourtv base and initialize Python venv
$cyt = Join-Path $root 'cleanyourtv'
if (-not (Test-Path (Join-Path $cyt '.git'))) {
    Write-Host "Cloning Gathin23/cleanyourtv ($UpstreamRef)..."
    git init $cyt | Out-Null
    git -C $cyt remote add origin https://github.com/Gathin23/cleanyourtv.git
    git -C $cyt fetch --depth 1 origin $UpstreamRef
    git -C $cyt checkout -B main FETCH_HEAD
}
$py = Join-Path $cyt 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) {
    python -m venv (Join-Path $cyt 'tools\venv')
    & (Join-Path $cyt 'tools\venv\Scripts\pip.exe') install -r (Join-Path $cyt 'tools\requirements.txt')
}

# 3. Verify ADB connection & Wi-Fi ownership (Android 14 setupwraith safety check)
$dev = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }
& $adb connect $dev
$state = & $adb -s $dev get-state
if ($state -ne 'device') { throw "ADB is not authorized ($state). Accept the debugging prompt on your TV and re-run." }

if (Test-Path "$env:USERPROFILE\.android\adbkey") {
    New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.cleanyourtv" | Out-Null
    Copy-Item "$env:USERPROFILE\.android\adbkey*" "$env:USERPROFILE\.cleanyourtv\" -Force
}

if (-not $SkipDebloat) {
    $wifiCheck = (& $adb -s $dev shell dumpsys wifi) | Select-String -Pattern 'cname=' | Select-Object -Unique
    if ($wifiCheck -match 'setupwraith') {
        throw "STOP: Your Wi-Fi profile is owned by com.google.android.tungsten.setupwraith! Forget and reconnect your Wi-Fi network in TV Settings -> Network & Internet first, otherwise disabling setupwraith on Android 14 will delete your Wi-Fi connection."
    }
}

# 4. Portable Microsoft OpenJDK 17 and Android SDK 34 licenses
$tc = Join-Path $root 'toolchain'
$jdkRoot = Join-Path $tc 'jdk'
$sdkDir = Join-Path $tc 'android-sdk'
New-Item -ItemType Directory -Force -Path $jdkRoot, (Join-Path $sdkDir 'licenses') | Out-Null
$licenses = "8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78af6dfcb131a6481e`n24333f8a63b6825ea9c5514f83c2829b004d1fee`n"
[System.IO.File]::WriteAllText((Join-Path $sdkDir 'licenses\android-sdk-license'), $licenses, [System.Text.Encoding]::ASCII)

if (-not (Get-ChildItem "$jdkRoot\jdk-17*\bin\java.exe" -ErrorAction SilentlyContinue)) {
    Write-Host 'Downloading portable Microsoft OpenJDK 17...'
    $jdkZip = Join-Path $tc 'jdk.zip'
    Invoke-WebRequest -Uri 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' -OutFile $jdkZip
    Expand-Archive -Path $jdkZip -DestinationPath $jdkRoot -Force
    Remove-Item $jdkZip -Force
}
$env:JAVA_HOME = (Get-ChildItem "$jdkRoot\jdk-17*" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir

# 5. Optional Debloat (skip with -SkipDebloat on non-Xiaomi devices or if already debloated)
if (-not $SkipDebloat) {
    if (-not (Test-Path $DebloatFile)) { throw "Debloat list not found: $DebloatFile" }
    & $adb kill-server 2>$null
    powershell -ExecutionPolicy Bypass -File (Join-Path $cyt 'tools\disable.ps1') -TvHost $TvHost $DebloatFile
    if ($LASTEXITCODE -ne 0) { throw 'Debloat step failed.' }
}

# 6. Apply Matcher launcher patches (Liquid Glass 2.0 + 6 Glass Styles, Control Center, Folders, Discover, Remote Mapper, QR Setup, Home Assistant, Immich, Watchdog, SelfUpdater, 31 locales)
Write-Host 'Applying Matcher launcher patches...'
Copy-Item (Join-Path $root 'patches\launcher-app-build.gradle') (Join-Path $cyt 'launcher\app\build.gradle') -Force
Copy-Item (Join-Path $root 'patches\launcher-config.xml') (Join-Path $cyt 'launcher\app\src\main\res\values\config.xml') -Force
Copy-Item (Join-Path $root 'patches\AndroidManifest.xml') (Join-Path $cyt 'launcher\app\src\main\AndroidManifest.xml') -Force
New-Item -ItemType Directory -Force -Path (Join-Path $cyt 'launcher\app\src\main\res\xml'), (Join-Path $cyt 'launcher\app\src\main\res\color'), (Join-Path $cyt 'launcher\app\src\main\res\anim') | Out-Null
Copy-Item (Join-Path $root 'patches\network_security_config.xml') (Join-Path $cyt 'launcher\app\src\main\res\xml\network_security_config.xml') -Force
Copy-Item (Join-Path $root 'patches\home_key_service.xml') (Join-Path $cyt 'launcher\app\src\main\res\xml\home_key_service.xml') -Force
Copy-Item (Join-Path $root 'patches\locales_config.xml') (Join-Path $cyt 'launcher\app\src\main\res\xml\locales_config.xml') -Force
foreach ($colorFile in @('pill_text.xml', 'pill_icon_online.xml', 'pill_icon_offline.xml', 'pill_icon_no_internet.xml')) {
    Copy-Item (Join-Path $root "patches\$colorFile") (Join-Path $cyt "launcher\app\src\main\res\color\$colorFile") -Force
}
Copy-Item (Join-Path $root 'patches\pill_text.xml') (Join-Path $cyt 'launcher\app\src\main\res\drawable\pill_text.xml') -Force
Copy-Item (Join-Path $root 'patches\ids.xml') (Join-Path $cyt 'launcher\app\src\main\res\values\ids.xml') -Force
Copy-Item (Join-Path $root 'patches\styles.xml') (Join-Path $cyt 'launcher\app\src\main\res\values\styles.xml') -Force
foreach ($javaFile in @('Config.java', 'Motion.java', 'ExpressiveGlass.java', 'FocusRingController.java', 'Folders.java', 'FolderDialog.java', 'FrostedPillDrawable.java', 'HeroArt.java', 'TopFade.java', 'VinylDiscDrawable.java', 'MediaSessionTracker.java', 'MediaMiniPlayerDialog.java', 'AppForceStopper.java', 'RecentApps.java', 'RecentsDialog.java', 'AmbientWeather.java', 'AerialCatalog.java', 'NightAudioController.java', 'TileSheenDrawable.java', 'ClockScene.java', 'ClockActivity.java', 'ClockDream.java', 'QuickSettingsActivity.java', 'OverlayActivity.java', 'LauncherSettingsActivity.java', 'RemoteKeys.java', 'RemoteMapperDialog.java', 'GamepadPassthrough.java', 'HomeKeyService.java', 'InputKeyService.java', 'ServiceRepair.java', 'ReloadReceiver.java', 'AppAdapter.java', 'LibraryApps.java', 'WallpaperMood.java', 'PicsumWallpapers.java', 'WallpaperPlayer.java', 'DockDecoration.java', 'GlassView.java', 'AppLibraryDialog.java', 'MainActivity.java', 'FeaturePrefs.java', 'LiquidClockView.java', 'UiClickSound.java', 'PointerSupport.java', 'LocalAdbClient.java', 'CrashWatchdog.java', 'TvHomeApp.java', 'QrBitmap.java', 'QrConfigServer.java', 'QrSetupDialog.java', 'HomeAssistantClient.java', 'HaCameraOverlay.java', 'ImmichSyncManager.java', 'WatchNextRepository.java', 'TraktClient.java', 'PlexClient.java', 'TrendingPicksRepository.java', 'DiscoverDialog.java', 'SelfUpdater.java', 'LauncherFeatureSettings.java')) {
    Copy-Item (Join-Path $root "patches\$javaFile") (Join-Path $cyt "launcher\app\src\main\java\com\gathin\tvhome\$javaFile") -Force
}
foreach ($testFile in @('ExpressiveGlassTest.java', 'FoldersTest.java', 'MediaSessionsTest.java', 'RecentAppsTest.java', 'AmbientScreensaverTest.java', 'TvOsFeaturesTest.java', 'WallpaperMoodTest.java', 'NewFeaturesTest.java', 'RemoteKeysTest.java', 'LocaleIntegrityTest.java', 'TileFitTest.java')) {
    Copy-Item (Join-Path $root "patches\$testFile") (Join-Path $cyt "launcher\app\src\test\java\com\gathin\tvhome\$testFile") -Force
}
foreach ($animFile in @('app_open_enter.xml', 'app_open_exit.xml', 'tile_enter.xml', 'dropdown_exit.xml', 'modal_enter.xml', 'modal_exit.xml', 'settings_enter.xml', 'settings_exit.xml')) {
    Copy-Item (Join-Path $root "patches\$animFile") (Join-Path $cyt "launcher\app\src\main\res\anim\$animFile") -Force
}
foreach ($resFile in @('glass_panel.xml', 'input_row_bg.xml', 'pill_bg.xml', 'pill_text_mid.xml', 'settings_choice_bg.xml', 'ic_media_play.xml', 'ic_media_pause.xml', 'ic_media_prev.xml', 'ic_media_next.xml', 'ic_media_open.xml', 'ic_recents.xml', 'ic_discover.xml', 'ic_ha_home.xml', 'ic_ha_camera.xml', 'ic_weather_sun.xml', 'ic_weather_moon.xml', 'ic_weather_partly.xml', 'ic_weather_cloud.xml', 'ic_weather_rain.xml', 'ic_weather_snow.xml', 'ic_weather_storm.xml', 'ic_weather_fog.xml', 'ic_ambient_sun_up.xml', 'ic_ambient_sun_down.xml', 'ic_ambient_headphones.xml', 'ic_cc_bedtime.xml', 'ic_cc_night_audio.xml', 'ic_cc_aerial.xml', 'ic_cc_sleep_timer.xml', 'ic_cc_brightness.xml', 'ic_cc_power.xml', 'ic_cc_screensaver.xml', 'ic_cc_location.xml')) {
    Copy-Item (Join-Path $root "patches\$resFile") (Join-Path $cyt "launcher\app\src\main\res\drawable\$resFile") -Force
}
foreach ($layoutFile in @('activity_main.xml', 'activity_launcher_settings.xml', 'activity_quick_settings.xml', 'dialog_card_prompt.xml', 'item_app.xml', 'popup_cards.xml', 'popup_inputs.xml', 'dream_clock.xml')) {
    Copy-Item (Join-Path $root "patches\$layoutFile") (Join-Path $cyt "launcher\app\src\main\res\layout\$layoutFile") -Force
}
Copy-Item (Join-Path $root 'patches\activity_main-iw.xml') (Join-Path $cyt 'launcher\app\src\main\res\layout-iw\activity_main.xml') -Force
Copy-Item (Join-Path $root 'patches\dream_clock.xml') (Join-Path $cyt 'launcher\app\src\main\res\layout-iw\dream_clock.xml') -Force
$localesRoot = Join-Path $root 'patches\locales'
if (Test-Path $localesRoot) {
    Get-ChildItem -Path $localesRoot -Directory | ForEach-Object {
        $targetResDir = Join-Path $cyt ("launcher\app\src\main\res\" + $_.Name)
        New-Item -ItemType Directory -Force -Path $targetResDir | Out-Null
        Get-ChildItem -Path $_.FullName -Filter '*.xml' -File | ForEach-Object {
            Copy-Item $_.FullName (Join-Path $targetResDir $_.Name) -Force
        }
    }
}
"sdk.dir=$($sdkDir -replace '\\', '\\')" | Out-File -Encoding ascii (Join-Path $cyt 'launcher\local.properties')

powershell -ExecutionPolicy Bypass -File (Join-Path $cyt 'launcher\build-and-install.ps1') -TvHost $TvHost -HomeKeyService -RestoreKeyServices
if ($LASTEXITCODE -ne 0) { throw 'Launcher build/install failed.' }

# 7. Patch, build, and install all 8 Liquid Glass add-ons (including EN + CS locales)
if (-not $SkipAddons) {
    $addonsRoot = Join-Path $cyt 'addons'
    $addonPatches = Join-Path $root 'patches\addons'
    if (Test-Path $addonPatches) {
        Get-ChildItem -Path $addonPatches -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($addonPatches.Length + 1)
            $dest = Join-Path $addonsRoot $rel
            New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
            Copy-Item $_.FullName $dest -Force
        }
    }
    $allAddons = @('headphones', 'bluetooth-connect', 'quiet-hours', 'brightness', 'weather', 'world-clock', 'capacity-stats', 'sleep-timer')
    foreach ($name in $allAddons) {
        "sdk.dir=$($sdkDir -replace '\\', '/')" | Out-File -Encoding ascii (Join-Path $addonsRoot "$name\local.properties")
    }
    $stdAddons = @('headphones', 'bluetooth-connect', 'quiet-hours', 'brightness', 'weather', 'world-clock', 'capacity-stats')
    foreach ($name in $stdAddons) {
        Write-Host "Building add-on: $name..."
        Push-Location (Join-Path $addonsRoot $name)
        try {
            $ErrorActionPreference = 'Continue'
            & .\gradlew.bat assembleRelease --no-daemon -q --console=plain
            $rc = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            if ($rc -ne 0) { throw "Build failed for add-on: $name" }
        } finally { Pop-Location }
    }
    & $adb connect $dev
    foreach ($name in $stdAddons) {
        $apk = Join-Path $addonsRoot "$name\app\build\outputs\apk\release\app-release.apk"
        & $adb -s $dev push $apk /data/local/tmp/addon.apk
        & $adb -s $dev shell "pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk"
    }
    & $adb -s $dev shell "pm grant io.github.maxhayim.headphones android.permission.BLUETOOTH_CONNECT"
    & $adb -s $dev shell "cmd appops set io.github.maxhayim.headphones APP_AUTO_START allow 2>/dev/null; true"
    & $adb -s $dev shell "pm grant com.gathin.btconnect android.permission.BLUETOOTH_CONNECT"
    & $adb -s $dev shell "cmd appops set io.github.maxhayim.quiethours APP_AUTO_START allow 2>/dev/null; true"
    & $adb -s $dev shell "appops set io.github.maxhayim.brightness SYSTEM_ALERT_WINDOW allow"
    & $adb -s $dev shell "cmd appops set io.github.maxhayim.brightness APP_AUTO_START allow 2>/dev/null; true"
    & $adb -s $dev shell "cmd notification allow_listener com.gathin.tvhome/.GuardRevival"
    & $adb -s $dev shell "cmd appops set com.gathin.tvhome GET_USAGE_STATS allow"
    & $adb -s $dev shell "pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS 2>/dev/null; true"
    & $adb kill-server 2>$null

    powershell -ExecutionPolicy Bypass -File (Join-Path $addonsRoot 'sleep-timer\build-and-install.ps1') -TvHost $TvHost
    if ($LASTEXITCODE -ne 0) { throw 'Sleep Timer build/install failed.' }
}

# 8. Push theme (wallpapers, dock, app labels, remote keymap) and sync disk
if (-not $SkipTheme) {
    powershell -ExecutionPolicy Bypass -File (Join-Path $cyt 'tools\theme.ps1') -TvHost $TvHost (Join-Path $root 'my-theme')
    if ($LASTEXITCODE -ne 0) { throw 'Theme deployment failed.' }
}

Write-Host 'Done! Wait 15 seconds before rebooting your TV box so Android flushes package & settings state to disk.'
