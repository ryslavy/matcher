# AGENTS.md — Technical Context & Invariants for AI Coding Agents

This repository (**Matcher**) packages a heavily upgraded Apple tvOS 26–inspired **Liquid Glass** launcher, 8 matching frosted-glass modular add-ons, seasonal/mood wallpapers, two-level remote keymaps, and automated Windows PowerShell deployment scripts built on top of [`Gathin23/cleanyourtv`](https://github.com/Gathin23/cleanyourtv).

> **Operational Playbooks:** For step-by-step execution recipes (deploy, customize, edit code, add translations, debug overlays, rollback), see [`SKILL.md`](SKILL.md).

---

## 1. Reference Platform & Architecture

- **Primary Tested Target:** Xiaomi TV Box S 3rd Gen (`MiTV-AFMU0`, Amlogic `twilight`, Mali-G31 MP2, 2 GB RAM, 1920×1080 @ 60 Hz, Google TV on **Android 14 / SDK 34**). Compatible with other Android TV / Google TV devices (Android 13+ required for AGSL `RuntimeShader` Liquid Glass; graceful fallback on Android 12 and below).
- **Build Pattern (Overlay Patches):**
  - Upstream `Gathin23/cleanyourtv` is cloned into `./cleanyourtv/` (gitignored) by `apply-setup.ps1`.
  - All custom code, resources, translations, and unit tests are tracked in `./patches/` (launcher files at `patches/*`, add-on overrides under `patches/addons/<addon>/...`, plus unified diff `patches/appletv-frosted-glass.patch`).
  - Whenever you edit files inside `./cleanyourtv/`, **always mirror every modified file back into `./patches/`** and regenerate `patches/appletv-frosted-glass.patch` before committing.
- **Portable Toolchain:**
  - `JAVA_HOME = ./toolchain/jdk/jdk-17*` (Microsoft OpenJDK 17)
  - `ANDROID_HOME = ./toolchain/android-sdk` (Android SDK 34)
  - `adb = ./platform-tools/adb.exe` (or `./toolchain/android-sdk/platform-tools/adb.exe`)

---

## 2. Critical Android 14 / Google TV Safety Invariants (READ BEFORE TOUCHING A TV!)

1. **Wi-Fi Ownership Trap (`setupwraith`):**
   - After factory setup, `com.google.android.tungsten.setupwraith` owns the saved Wi-Fi network.
   - **NEVER disable `setupwraith`** until `adb shell dumpsys wifi | grep -E "cname=|creatorName"` reports `android.uid.system:1000` (not `setupwraith`). Otherwise Android 14 deletes the Wi-Fi credentials immediately and drops ADB.
   - Fix: Instruct the user to **Forget** and **Reconnect** the Wi-Fi network on the TV first.

2. **Launcher Disable & Restore Order:**
   - HOME priority order: `com.google.android.apps.tv.launcherx` (2) > `setupwraith.RecoveryActivity` (1) > `com.gathin.tvhome` (0).
   - **Disable:** Always disable `setupwraith` **BEFORE** `com.google.android.apps.tv.launcherx` (otherwise `RecoveryActivity` takes over Home and causes a black screen / crash loop).
   - **Restore:** Always re-enable `com.google.android.apps.tv.launcherx` **BEFORE** `setupwraith`. (`LocalAdbClient.restoreStockLauncher` and `restore-all.ps1` enforce this.)

3. **Package-State Disk Flush (`WRITE_SETTINGS_DELAY`):**
   - After any `pm disable-user`, `pm enable`, or `settings put secure` change, run `adb shell sync` and advise waiting **15 seconds** before rebooting the TV box.

4. **Accessibility Services (`enabled_accessibility_services`):**
   - Must keep **both** services bound:
     ```text
     com.gathin.tvhome/.HomeKeyService:io.github.luyzfernando08.sleeptimer/.SleepService
     ```
   - Verify with `adb shell dumpsys accessibility | grep -E "Bound services|Service\[label=|Crashed"` — `Crashed services:{}` must be empty.
   - Launcher permissions required: `WRITE_SECURE_SETTINGS`, `ACCESS_FINE_LOCATION` (SSID on Android 14), `GET_USAGE_STATS`, `READ_TV_LISTINGS` (Watch Next), and notification listener `com.gathin.tvhome/.GuardRevival`.
   - Do not run third-party key mappers (e.g. Button Mapper) alongside `HomeKeyService` — they conflict with `FLAG_REQUEST_FILTER_KEY_EVENTS` and `gamepad_passthrough`.

---

## 3. Liquid Glass & Rendering Invariants (DO NOT REGRESS!)

1. **Strict Porter-Duff Valid Premultiplied Alpha in AGSL Shaders:**
   - In `LIQUID_GLASS_PANEL_AGSL` (both [`patches/ExpressiveGlass.java`](patches/ExpressiveGlass.java) and all 8 [`GlassKit.java`](patches/addons/brightness/app/src/main/java/io/github/maxhayim/brightness/GlassKit.java) copies), output RGB **MUST NEVER exceed output alpha** (`0 <= premul.rgb <= outA <= 1`).
   - Always combine specular & refracted glass using proper Porter-Duff `SrcOver`:
     ```glsl
     float outA = clamp(specAlpha + refractAlpha * (1.0 - specAlpha), 0.0, 0.98);
     half3 premul = clamp(specCol * half(specAlpha) + refracted * half(refractAlpha * (1.0 - specAlpha)), half3(0.0), half3(outA));
     ```
   - Violating `premul <= outA` causes hardware layer (`LAYER_TYPE_HARDWARE`) FBO compositing to look brighter than direct rendering and flash when animations end.

2. **Optical Frost Prefilter (Zero Sharp Text in Curved Bezels):**
   - `ExpressiveGlass.captureHomeWindowForRefraction` captures `480×270` (`REFRACT_SHARP_W/H`) and applies a 2-pass Gaussian-approximating box blur (`sharpRadius ≈ 5`, ~13 px frost on 1080p) before binding `uSharp`, and `WallpaperPlayer.makeSharpScaled` does the same for static wallpapers. Keep this prefilter so curved meniscus rims never refract crisp text/logos.

3. **Zero Home Wallpaper Leakage Over DRM / External Apps:**
   - In `ExpressiveGlass.PanelDrawable.drawLiquidGlass`:
     - When `smoked == true`, use `overlayRefraction` if available; fall back to Home's `activeRefractionBackdrop()` **ONLY when `MainActivity.isHomeVisible()` is `true`**. When an external/DRM app is in foreground (`!isHomeVisible()`), return `null` so smoked overlays never leak Home wallpaper colors.
   - All overlay Activities (launcher + all 8 add-ons) use `Theme.GlassOverlay` (`windowIsTranslucent=true`, `windowBackground=@android:color/transparent`, `windowAnimationStyle=@null`) and handle `onNewIntent` on `singleTask` launch modes.

4. **Zero Frame-0 Coordinate Snap on Floating Windows:**
   - On Android 14, non-fullscreen `PopupWindow` and `Dialog` windows can report `(0, 0)` from `getLocationOnScreen()` on Frame 1 before WindowManager delivers `MSG_RESIZED`.
   - `ExpressiveGlass` and `GlassKit` both compute exact Frame-0 screen origins from `WindowManager.LayoutParams` gravity/offsets when `scrLoc == (0, 0)`.
   - `MediaMiniPlayerDialog` uses a full-screen `MATCH_PARENT × MATCH_PARENT` transparent `backdrop` `PopupWindow` (just like `FolderDialog`) plus direct GPU drawing (`allowHwLayer = false` in `Motion.revealTopCornerCard`/`dismissTopCornerCard`) so there is zero `LAYER_TYPE_HARDWARE` FBO swap or edge brightness blink on open.

5. **Seamless Backdrop Blur Handoffs & Input Parity:**
   - Chained Home popups/modals (`Folder ↔ Add Apps / Rename`, `Tile Menu → Folder Picker → New Folder`, `Top Menu → Inputs / Settings`) must call `MainActivity.holdBackdropBlurForHandoff()` (and `OverlayActivity.finishForHandoff()` when leaving `OverlayActivity`) so Home's blurred veil never blinks off between windows.
   - Every standalone `PopupWindow` and `Dialog` creates its own window tree and **must** explicitly attach `PointerSupport.install(host)` and `UiClickSound.attachFocusSound(host)`.

6. **Shared `GlassKit.java` Synchronization:**
   - All 8 add-ons contain identical `GlassKit.java` implementations differing **only in line 1 (`package ...;`)**. Whenever you modify one `GlassKit.java`, synchronize it across all 7 other add-ons (UTF-8 without BOM) and mirror all 8 into `patches/addons/`.

---

## 4. Key Source Map (`patches/`)

| File(s) | Responsibility |
|---|---|
| `ExpressiveGlass.java`, `Motion.java`, `FocusRingController.java`, `GlassView.java`, `DockDecoration.java`, `FrostedPillDrawable.java`, `LiquidClockView.java` | 3D Liquid Glass AGSL shader engine, backdrop capture/blur veil, vsync glide curves, spring Fresnel focus ring, beveled clock |
| `MainActivity.java`, `AppAdapter.java`, `WallpaperPlayer.java`, `WallpaperMood.java`, `HeroArt.java` | Home screen, dock, seasonal/mood wallpapers, tile/folder context menus, Add-Apps & Rename dialogs |
| `OverlayActivity.java`, `QuickSettingsActivity.java`, `LauncherSettingsActivity.java`, `LauncherFeatureSettings.java` | Translucent overlay hosts for Recents, App Library, Folders, Discover, Control Center, and Settings |
| `FolderDialog.java`, `AppLibraryDialog.java`, `RecentsDialog.java`, `MediaMiniPlayerDialog.java`, `DiscoverDialog.java`, `QrSetupDialog.java` | All major glass modals, carousels, mini-player, and nested popovers |
| `HomeKeyService.java`, `RemoteKeys.java`, `GamepadPassthrough.java`, `PointerSupport.java`, `UiClickSound.java` | Two-level remote key interception, game controller passthrough, air-mouse hover, click sounds |
| `ClockScene.java`, `ClockActivity.java`, `ClockDream.java`, `AerialCatalog.java`, `AmbientWeather.java`, `ImmichSyncManager.java` | 1080p Aerial video & photo screensaver, weather/sunrise, OLED drift, Immich sync |
| `WatchNextRepository.java`, `TrendingPicksRepository.java`, `TraktClient.java`, `PlexClient.java`, `HomeAssistantClient.java`, `QrConfigServer.java`, `QrBitmap.java` | Discover providers, Home Assistant API client, ephemeral LAN QR setup web server |
| `LocalAdbClient.java`, `CrashWatchdog.java`, `TvHomeApp.java`, `SelfUpdater.java`, `FeaturePrefs.java` | Loopback ADB permission/restore client, crash-loop protection, GitHub Releases updater, preferences |

---

## 5. Build, Test & Verify Commands

```powershell
# 1. Run all 366 JVM unit tests + build release launcher APK
$env:JAVA_HOME = (Get-ChildItem .\toolchain\jdk\jdk-17* -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$PWD\toolchain\android-sdk"
Push-Location .\cleanyourtv\launcher
.\gradlew.bat testReleaseUnitTest assembleRelease --no-daemon
Pop-Location

# 2. Build all 8 add-on release APKs
foreach ($a in @('brightness','sleep-timer','headphones','bluetooth-connect','quiet-hours','weather','world-clock','capacity-stats')) {
    Push-Location ".\cleanyourtv\addons\$a"
    .\gradlew.bat assembleRelease --no-daemon
    Pop-Location
}

# 3. Regenerate unified git diff after syncing modified files into patches/
git -C .\cleanyourtv diff --binary --output="$PWD\patches\appletv-frosted-glass.patch"
```

- Always write PowerShell-generated files as **UTF-8 without BOM** (`New-Object System.Text.UTF8Encoding($false)`).
- Never commit device dumps, serial numbers, personal LAN IPs, or personal API tokens.
