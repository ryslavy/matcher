# AGENTS.md — Technical Context & Invariants for AI Coding Agents

This repository (**Matcher**) packages a heavily upgraded Apple tvOS 26–inspired **Liquid Glass** launcher, 8 matching frosted-glass modular add-ons, seasonal/mood wallpapers, two-level remote keymaps, and automated Windows PowerShell deployment scripts built on top of [`Gathin23/cleanyourtv`](https://github.com/Gathin23/cleanyourtv).

> **Operational Playbooks:** For step-by-step execution recipes (deploy, customize, edit code, add translations, debug overlays, rollback), see [`SKILL.md`](SKILL.md).

---

## 1. Reference Platform & Architecture

- **Primary Tested Target:** Xiaomi TV Box S 3rd Gen (`MiTV-AFMU0`, Amlogic `twilight`, Mali-G31 MP2, 2 GB RAM, 1920×1080 @ 60 Hz, Google TV on **Android 14 / SDK 34**). Compatible with other Android TV / Google TV devices (Android 13+ required for AGSL `RuntimeShader` Liquid Glass; graceful fallback on Android 12 and below).
- **Build Pattern (Overlay Patches):**
  - Upstream `Gathin23/cleanyourtv` (pinned by default to verified CleanYourTV 3.6+ commit `be6087056a1c7d4f08f3f1cc778d92374d86ef27`, overridable via `-UpstreamRef`) is cloned into `./cleanyourtv/` (gitignored) by `apply-setup.ps1`.
  - All custom code, resources, translations, and unit tests are tracked in `./patches/` (launcher files at `patches/*`, add-on overrides under `patches/addons/<addon>/...`, plus unified diff `patches/appletv-frosted-glass.patch`).
  - Whenever you edit files inside `./cleanyourtv/`, **always mirror every modified file back into `./patches/`** and regenerate `patches/appletv-frosted-glass.patch` before committing.
- **Portable Toolchain (Windows) & macOS/Linux Parity:**
  - Windows (`apply-setup.ps1`): `JAVA_HOME = ./toolchain/jdk/jdk-17*` (Microsoft OpenJDK 17), `ANDROID_HOME = ./toolchain/android-sdk` (Android SDK 34), `adb = ./platform-tools/adb.exe` (or `./toolchain/android-sdk/platform-tools/adb.exe`).
  - macOS / Linux: Use system JDK 17, Android SDK 34, `./gradlew`, `adb`, and upstream `./cleanyourtv/tools/*.sh` equivalents following the same patch/build/install sequence from `apply-setup.ps1`.

---

## 2. Mandatory Device Discovery, Safety & Android 14 Invariants (READ BEFORE TOUCHING A TV!)

1. **Always Identify the User's Exact TV / Box Model First:**
   - Before running **any** debloat, keymap, or launcher switch, query the connected device over ADB:
     ```bash
     adb shell getprop ro.product.manufacturer
     adb shell getprop ro.product.model
     adb shell getprop ro.product.device
     adb shell getprop ro.build.version.release
     adb shell getprop ro.build.version.sdk
     ```
   - **Look up the exact model in upstream CleanYourTV's device docs:**
     - Local clone: `./cleanyourtv/docs/devices/` + `./cleanyourtv/docs/DOS-AND-DONTS.md` + `./cleanyourtv/docs/switches.md` + `./cleanyourtv/docs/REMOTE.md`
     - Online: `https://github.com/Gathin23/cleanyourtv/tree/main/docs/devices`
   - Check specifically for:
     - **HOME package allowlists** (certain Xiaomi / Oppo ColorOS TV firmwares require building with `-PlauncherApplicationId=<allowed.pkg>`).
     - **Vendor packages that must NEVER be disabled** (HDMI inputs, picture/HDR engine, TV tuner, Bluetooth, Wi-Fi, Cast, Play Services — especially critical on integrated Sony, TCL, Philips, Hisense, and Panasonic TVs).
     - **GPU/RAM limits** (`SDK < 33` falls back cleanly without AGSL `RuntimeShader`; weak GPUs like MediaTek MT5895 or 1–1.5 GB RAM TVs should enable `"reduced_motion": true` and `"glow_snap": true`).
   - **NEVER run `my-debloat.txt` on any device other than `MiTV-AFMU0`.** For all other devices, default to `-SkipDebloat` or assemble a model-verified list from CleanYourTV's device guide.

2. **Backup Before Touching Anything & Reversible-Only Changes:**
   - Before disabling any package or modifying settings on a new TV, save a local recovery snapshot inside gitignored `./backup/` (`pm list packages -e`, `pm list packages -d`, `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`, and `settings get secure enabled_accessibility_services`).
   - **NEVER run `pm uninstall` on system packages** — always use reversible `pm disable-user --user 0 <pkg>` so `pm enable <pkg>` can restore every change cleanly.

3. **Wi-Fi Ownership Trap (`setupwraith` on Android 14 / Google TV):**
   - After factory setup, `com.google.android.tungsten.setupwraith` owns the saved Wi-Fi network.
   - **NEVER disable `setupwraith`** until `adb shell dumpsys wifi | grep -E "cname=|creatorName"` reports `android.uid.system:1000` (not `setupwraith`). Otherwise Android 14 deletes the Wi-Fi credentials immediately and drops ADB.
   - Fix: Instruct the user to **Forget** and **Reconnect** the Wi-Fi network on the TV first.

4. **Launcher Disable & Restore Order:**
   - HOME priority order: `com.google.android.apps.tv.launcherx` (2) > `setupwraith.RecoveryActivity` (1) > `com.gathin.tvhome` (0).
   - **Disable:** Always disable `setupwraith` **BEFORE** `com.google.android.apps.tv.launcherx` (otherwise `RecoveryActivity` takes over Home and causes a black screen / crash loop).
   - **Restore:** Always re-enable `com.google.android.apps.tv.launcherx` **BEFORE** `setupwraith`. (`LocalAdbClient.restoreStockLauncher` and `restore-all.ps1` enforce this.)

5. **Package-State Disk Flush (`WRITE_SETTINGS_DELAY`):**
   - After any `pm disable-user`, `pm enable`, or `settings put secure` change, run `adb shell sync` and advise waiting **15 seconds** before rebooting the TV box.

6. **Accessibility Services (`enabled_accessibility_services`):**
   - Must keep **both** services bound:
     ```text
     com.gathin.tvhome/.HomeKeyService:io.github.luyzfernando08.sleeptimer/.SleepService
     ```
   - Verify with `adb shell dumpsys accessibility | grep -E "Bound services|Service\[label=|Crashed"` — `Crashed services:{}` must be empty.
   - Launcher permissions required: `WRITE_SECURE_SETTINGS`, `ACCESS_FINE_LOCATION` (SSID on Android 14), `GET_USAGE_STATS`, `READ_TV_LISTINGS` (Watch Next), and notification listener `com.gathin.tvhome/.GuardRevival`.
   - Do not run third-party key mappers (e.g. Button Mapper) alongside `HomeKeyService` — they conflict with `FLAG_REQUEST_FILTER_KEY_EVENTS` and `gamepad_passthrough`.
   - For Android 14 Wireless Debugging, pair once (`adb pair <IP>:<PAIR_PORT>`) and pass `-TvHost <IP>:<CONNECT_PORT>`.

7. **Personalizing Visuals, Apps, Remote & Location for Any User:**
   - **No-rebuild toggles (in TV Settings / `FeaturePrefs`):** Liquid Glass clock, Home Liquid Glass optics, UI click sounds, OLED screensaver drift, Aerial videos, Watch from Screensaver, Discover region (`CZ/SK/DE/GB/US`).
   - **No-rebuild theme config (`my-theme/config.json` + `switch-theme.ps1`):** `dock_glass`, `dock_opacity`, `cinematic_home`, `ambient_glow`, `app_glow`, `glass_style`, `fade_rows`, `advanced_effects`, `reduced_motion`, `glow_snap`, `columns`, `dock_apps`, `app_labels`, `app_keys`, `wallpaper_moods`.
   - **Build-time config (`patches/launcher-config.xml`):** `latitude`, `longitude`, `weather_city` (lines 503–505, default `Prague`).
   - **Shader tuning (rebuild required):** Rim refraction/tint/opacity live in `LIQUID_GLASS_PANEL_AGSL` inside `ExpressiveGlass.java` (launcher) and all patched `GlassKit.java` copies (must stay synchronized and preserve `0 <= premul.rgb <= outA`).
   - **Modular Add-on Selection & Visual Matching (`cleanyourtv/addons/`):** The 8 bundled add-ons are only a customizable default — users may skip them (`-SkipAddons`), uninstall any subset, or add any of the **40+ upstream CleanYourTV add-ons** (`cleanyourtv/addons/README.md`). Before installing an upstream add-on, check its `README.md` for required permissions and OS/hardware compatibility (e.g., `bluetooth-toggle` is Android ≤12 only; `sources` targets integrated multi-HDMI TVs). To visually match a newly added add-on to Matcher's Liquid Glass 2.0 UI, copy `GlassKit.java` (changing only line 1 `package ...;`, UTF-8 without BOM), apply `Theme.GlassOverlay` + `GlassKit.installModal(...)` to its Activity/settings UI (leave full-screen Canvas screensavers/DreamServices untouched), mirror all changes under `patches/addons/<name>/`, and add its build/install/permissions to `apply-setup.ps1`. If removing `sleep-timer`, also remove `io.github.luyzfernando08.sleeptimer/.SleepService` from `enabled_accessibility_services` so `HomeKeyService` remains cleanly bound.

---

## 3. Liquid Glass & Rendering Invariants (DO NOT REGRESS!)

1. **Strict Porter-Duff Valid Premultiplied Alpha & Dark-TV Color Science in AGSL Shaders:**
   - In `LIQUID_GLASS_PANEL_AGSL` (both [`patches/ExpressiveGlass.java`](patches/ExpressiveGlass.java) and all 8 [`GlassKit.java`](patches/addons/brightness/app/src/main/java/io/github/maxhayim/brightness/GlassKit.java) copies), output RGB **MUST NEVER exceed output alpha** (`0 <= premul.rgb <= outA <= 1`).
   - Always combine specular & refracted glass using proper Porter-Duff `SrcOver`:
     ```glsl
     float outA = clamp(specAlpha + refractAlpha * (1.0 - specAlpha), 0.0, 0.96);
     half3 premul = clamp(specCol * half(specAlpha) + refracted * half(refractAlpha * (1.0 - specAlpha)), half3(0.0), half3(outA));
     ```
   - Violating `premul <= outA` causes hardware layer (`LAYER_TYPE_HARDWARE`) FBO compositing to look brighter than direct rendering and flash when animations end.
   - **No light-mode white lift on dark TV pixels:** `applyVibrancyAndAdapt` protects highlights above `luma > 0.68` without injecting white haze into dark pixels, keeping dark glass rims deep crystal.

2. **4-Layer Optical Backdrop Hierarchy & Frame-0 Invalidation:**
   - **Layer 0 (Base):** Wallpaper / HeroArt (`homeWallpaperRefraction`) or External App (`overlayRefraction`).
   - **Layer 1 (Home Chrome):** Dock (with interactive ripple), Top Pills, Liquid Clock → strictly sample Layer 0.
   - **Layer 2 (Primary Modals / Overlays / Add-ons):** Sample `homeLiveRefraction` over Home or `overlayRefraction` over external apps.
   - **Layer 3 (Nested Context Menus & Sub-Dialogs):** Sample `nestedRefraction` (composited Layer 0 + dimmed parent panel + softly blurred parent children). `Motion.glidePanel` and `GlassKit.glidePanel` **must always** call `invalidateCached(liquidTargets)` when `entering || !useHwLayer` (even with `LAYER_TYPE_HARDWARE`) so a popup shown before `setPanelContentBlur(true)` never freezes a stale Frame-0 RenderNode sampling Home icons.
   - `ExpressiveGlass.captureHomeWindowForRefraction` (`480×270`) and `WallpaperPlayer.makeSharpScaled` prefilter `uSharp` so curved meniscus rims never refract harsh text/logos.

3. **Hardware-Cached Material 3 Expressive Nested Blur (`ExpressiveBlurVeilDrawable`):**
   - Both `ExpressiveGlass` and `GlassKit` render nested parent-panel defocus on API 31+ using a 2-`RenderNode` pipeline mounted on `panel.getOverlay()`: inner `effectNode` applies native Skia GPU Gaussian blur (`NESTED_BLUR_RADIUS_DP = 28dp`) chained with `BACKDROP_AGSL`, and outer `cacheNode` caches that output **once** via `setUseCompositingLayer(true, null)` with native `setOutline`/`setClipToOutline(true)`.
   - Never animate nested blur by mutating per-child `View.setRenderEffect(...)` radii frame-by-frame (causes Mali GPU rebuild stutter and visible stepping).

4. **Zero Home Wallpaper Leakage Over DRM / External Apps:**
   - In `ExpressiveGlass.PanelDrawable.drawLiquidGlass`:
     - When `smoked == true`, use `overlayRefraction` if available; fall back to Home's `activeRefractionBackdrop()` **ONLY when `MainActivity.isHomeVisible()` is `true`**. When an external/DRM app is in foreground (`!isHomeVisible()`), return `null` so smoked overlays never leak Home wallpaper colors.
   - All overlay Activities (launcher + all 8 add-ons) use `Theme.GlassOverlay` (`windowIsTranslucent=true`, `windowBackground=@android:color/transparent`, `windowAnimationStyle=@null`) and handle `onNewIntent` on `singleTask` launch modes.

5. **Zero Frame-0 Snap & Zero Scroll Jump on Floating Windows (`computeScreenOrigin`):**
   - On Android 14, `View.transformMatrixToGlobal` on floating `Dialog`/`PopupWindow` views already includes window origin `(winOffX, winOffY)` — adding `winOff` a second time shifts refraction during scroll/re-layout. Always compute screen origin via `(hostTrans - rootTrans) + winOff` (`ExpressiveGlass.computeScreenOrigin`), with `WindowManager.LayoutParams` gravity fallback when `scrLoc == (0, 0)` on Frame 0.
   - `MediaMiniPlayerDialog` uses a full-screen `MATCH_PARENT × MATCH_PARENT` transparent `backdrop` `PopupWindow` plus direct GPU drawing (`allowHwLayer = false` in `Motion.revealTopCornerCard`/`dismissTopCornerCard`).

6. **Seamless Backdrop Blur Handoffs, Input Parity & Shared `GlassKit.java`:**
   - Chained Home popups/modals (`Folder ↔ Add Apps / Rename`, `Tile Menu → Folder Picker → New Folder`, `Top Menu → Inputs / Settings`) must call `MainActivity.holdBackdropBlurForHandoff()` (and `OverlayActivity.finishForHandoff()` when leaving `OverlayActivity`).
   - Every standalone `PopupWindow` and `Dialog` must explicitly attach `PointerSupport.install(host)` and `UiClickSound.attachFocusSound(host)`.
   - All patched add-ons (the 8 defaults plus any added upstream add-ons) must contain identical `GlassKit.java` implementations differing **only in line 1 (`package ...;`)**. Sync across every patched add-on (UTF-8 without BOM) and mirror into `patches/addons/<name>/`.

---

## 4. Key Source Map (`patches/`)

| File(s) | Responsibility |
|---|---|
| `ExpressiveGlass.java`, `Motion.java`, `FocusRingController.java`, `GlassView.java`, `DockDecoration.java`, `FrostedPillDrawable.java`, `LiquidClockView.java` | 3D Liquid Glass AGSL shader engine, backdrop capture/blur veil, vsync glide curves, spring Fresnel focus ring, beveled clock |
| `MainActivity.java`, `AppAdapter.java`, `TileSheenDrawable.java`, `WallpaperPlayer.java`, `WallpaperMood.java`, `HeroArt.java` | Home screen, dock, 3D tile spring tilt/sway/sheen, seasonal/mood wallpapers, tile/folder context menus, Add-Apps & Rename dialogs |
| `OverlayActivity.java`, `QuickSettingsActivity.java`, `LauncherSettingsActivity.java`, `LauncherFeatureSettings.java` | Translucent overlay hosts for Recents, App Library, Folders, Discover, Control Center, and Settings |
| `FolderDialog.java`, `AppLibraryDialog.java`, `RecentsDialog.java`, `MediaMiniPlayerDialog.java`, `DiscoverDialog.java`, `QrSetupDialog.java` | All major glass modals, carousels, mini-player, and nested popovers |
| `HomeKeyService.java`, `InputKeyService.java`, `RemoteKeys.java`, `GamepadPassthrough.java`, `PointerSupport.java`, `UiClickSound.java` | Three-level (`tap`/`double:`/`hold:`) remote key interception, game controller passthrough, air-mouse hover, click sounds |
| `ClockScene.java`, `ClockActivity.java`, `ClockDream.java`, `AerialCatalog.java`, `AmbientWeather.java`, `ImmichSyncManager.java` | 4K HEVC / 1080p Aerial video & photo screensaver, favorite loops, weather/sunrise, OLED drift, Immich sync |
| `WatchNextRepository.java`, `TrendingPicksRepository.java`, `TraktClient.java`, `PlexClient.java`, `HomeAssistantClient.java`, `HaCameraOverlay.java`, `QrConfigServer.java`, `QrBitmap.java` | Discover providers, Home Assistant API client & live `camera.*` PiP overlay, ephemeral LAN QR setup web server |
| `LocalAdbClient.java`, `CrashWatchdog.java`, `TvHomeApp.java`, `SelfUpdater.java`, `FeaturePrefs.java` | Loopback ADB permission/restore client, crash-loop protection, preferences (`SelfUpdater` disabled by default) |

---

## 5. Build, Test & Verify Commands

```powershell
# 1. Run all 375 JVM unit tests + build release launcher APK
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
- **Release-pinned visual gallery:** Screenshots live in `docs/gallery/v<release>/` and are indexed in `docs/GALLERY.md` (plus 6 hero previews in `README.md` / `docs/README-cs.md`) explicitly tagged with the release version (`v1.1.1`). When shipping a future UI-changing release, re-capture into `docs/gallery/v<new-release>/`, anonymize any personal city/IP/SSID, and update the gallery release tag so screenshots never go silently stale.
