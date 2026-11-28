# Changelog & Feature → Files Map

All notable Matcher releases, with an explicit **Feature → Files Map** so users and AI agents following [`UPDATE.md`](UPDATE.md) can either update everything cleanly or cherry-pick a single feature without re-running debloat or touching personal themes.

---

## `v1.2.1` — Picsum.photos Web Wallpapers, Sharp Nested Menu Mode, Seamless Tiered Live Reload, Smooth 60 FPS Dock Color Glides & Mirror Control Center Collapse

### Highlights
- **Random Web Wallpapers via `picsum.photos/1920/1080` (`PicsumWallpapers`):** Pick **Random web photo (Picsum)** right at the top of *Launcher Settings → Wallpaper* (plus **Load new web photo now** when active, and a 1-click button in the Phone QR Web Editor). Downloads crisp 1920×1080 CC0/Unsplash photography with strict HTTPS-only redirect validation, JPEG/PNG/WebP magic-byte verification, atomic offline Frame-0 disk cache, and automatic hourly rotation — giving clean APK-only installs instant Liquid Glass vibrancy with zero bundled third-party art.
- **Zero Stale Refraction on "No Wallpaper":** Fixed `WallpaperPlayer` so switching to *No wallpaper* immediately clears `ExpressiveGlass.homeWallpaperRefraction` and resets the Material You seed (`DEFAULT_SEED_RGB`), eliminating lingering ghost reflections on Dock edges and top-right circular pills (while keeping clean ambient tint & neutral glass rims).
- **Silky 60 FPS Dock Color Glides Under Folders & Apps:** Separated Dock ambient color & `HeroArt` wash into a dedicated lightweight `DockTintOverlayView` above the `LAYER_TYPE_HARDWARE` `DockBackdropView` AGSL rim cache, extending folder/app ambient crossfade to a lush `420 ms` cubic S-curve (`PathInterpolator(0.25, 0.10, 0.25, 1.0)`) updated smoothly frame-by-frame with **0 missed vsyncs** and **11 ms GPU p50 / 14 ms p99** on Xiaomi TV Box S 3rd Gen.
- **Symmetrical Control Center Settings Collapse Animation:** Closing the *Settings* drawer in Control Center (`QuickSettingsActivity`) now mirrors the expand animation — smoothly gliding focus back to the *Settings* pill, fading/lifting rows bottom-to-top, and smoothly collapsing panel height & scroll to zero instead of snapping.
- **3-Tier Seamless Launcher Reload & Automatic Post-OTA Recovery:**
  - **Tier 1 (Zero-Flicker In-Place Hot-Reload):** Wallpaper, Picsum, "No wallpaper", QR wallpaper upload, clock format, animations, advanced effects, dock opacity, remote keys, ambient glow, sky, and background color now apply live on `MainActivity.onResume()` with smooth wallpaper crossfade and zero `Activity.recreate()` black flash.
  - **Tier 2 (Seamless Crossfade Rebuild):** Structural layout changes (`columns`, `rowsBelowDock`, `festival`, theme switches) use `smoothRecreate()`, preserving the exact focused tile key/slot, skipping cold-start tile bounce, and crossfading cleanly.
  - **Tier 3 (Graceful `ACTION_RELOAD` + Reliable Post-OTA Recovery):** Added top-level `ReloadReceiver` (`com.gathin.tvhome.ACTION_RELOAD`), an on-TV **Reload launcher** action in Settings, and fixed the Android 14 `onUnbind()` race in `ServiceRepair.forceRebindAfterUpdate()` so `HomeKeyService` + `SleepService` automatically re-bind after `MY_PACKAGE_REPLACED` and relaunch Home with a *"Matcher updated to vX.Y.Z"* confirmation toast.
- **Optional Nested Menu Blur Toggle (`nested_menu_blur`):** Added a live **Nested menu blur** toggle right under *Home backdrop blur* in *Launcher Settings* (`FeaturePrefs.KEY_NESTED_MENU_BLUR`, default `true`) and forwarded `EXTRA_NESTED_MENU_BLUR` across all 8 `GlassKit` add-ons. When disabled, parent modals stay 100% razor-sharp at full opacity behind Layer-3 context menus and sub-dialogs, while the floating context menu/sub-dialog itself automatically engages GPU `RenderNode`-cached opaque frosted interior blur (`uInteriorFill = 1.0f`) + rich style tint + crisp `640×360` optical edge refraction across all 6 Glass Styles — ensuring zero transparent bleed-through.
- **Optional Dock Ambient Glow Toggle (`dock_ambient_glow`):** Added a live **Dock ambient glow** toggle in *Launcher Settings* (`FeaturePrefs.KEY_DOCK_AMBIENT_GLOW`, inheriting theme `ambient_glow` by default) to turn the app-colored radial glow behind the Dock (and its matching Dock/pill ambient tint wash) on or off immediately without reload.
- **Global Liquid Glass Optics Across All 8 Add-ons & Quick Overlays:** Forwarded `EXTRA_LIQUID_OPTICS` (and `EXTRA_LOCALE`) from `MainActivity.attachAddonBackdropExtras` to `GlassKit` across all 8 add-ons (including *Brightness* and *Sleep Timer* `QuickActivity` overlays) and renamed *Home liquid glass optics* → **Liquid Glass optics** across all 32 launcher locales so disabling optical lens refraction applies uniformly everywhere.
- **100% Localized Add-on UIs (`brightness`, `quiet-hours`, `bluetooth-connect`):** Extracted all 81 remaining upstream hardcoded English strings in `brightness`, `quiet-hours`, and `bluetooth-connect` into `R.string.*` and translated them across all 31 locales (`248/248` add-on/locale sets verified), eliminating English screens on localized TVs.
- **Rows-Below-Dock Layout (`4–7 × 0–3`) & Scroll-Clipped Dock Glass:** Scaled hero title spacing proportionally (`44/30/22 sp`), switched `2–3` rows below dock to compact `32 dp` row spacing with collapsed (`View.GONE`) hidden dock labels and an `80 dp` tile width floor, capped `DockDecoration` bottom clearance (`18 dp` / `14 dp`) so the Dock pill never overlaps Row 1, initialized `rowsBelowDock` before hero/shelf layout with shared `syncGridPadding` (`grid` + `hero`), and synced `DockBackdropView` / `DockTintOverlayView` on every scroll frame with strict `setClipBounds` and `TopFade`-matched alpha fade — ensuring all **16** (`4–7 cols × 0–3 rows`) combinations fit 1080p cleanly (`0` rows unchanged) and the Dock glass never overflows above the grid or lingers after its icons fade out.
- **Liquid Glass Dock Opacity (`30%–100%`) Across All 6 Styles:** Wired the existing *Dock bar opacity* setting (`30% / 50% / 70% / 100%`) directly into Liquid Glass docks (`ExpressiveGlass.dockGlassColors(..., opacity)` + `dockBackdropAlpha(opacity)`) with live real-time preview so users can smoothly tune frosted density from airy crystal glass to rich frost across all 6 Glass Styles.
- **Module Cards Hide Icons Cleanly Across Home & App Library:** Fixed *Module Cards* (`Cards.State.HIDDEN`) so toggling a module add-on to *Hidden* hides its launcher icon on **both** Home (grid/dock/folders) and App Library while keeping Control Center, remote shortcuts, and re-enabling via *Module Cards* intact.
- **Scrollable OTA Updates Dialog:** Wrapped update rows in a bounded `ScrollView` with fading edges, `FocusRingController.clipScrollContainers`, and per-APK file size labels so all launcher + 8 add-on updates remain smoothly D-pad scrollable on 1080p/4K TVs.
- **31-Locale Parity & Unit Tests:** Added all 8 new UI strings across all 32 launcher locale files (`values` + 31 `values-<lang>`), guarded by `382/382` passing JVM unit tests (`LocaleIntegrityTest` + `TileFitTest` across all 16 column×row combinations + `PicsumWallpapers`, `nested_menu_blur`, `dock_ambient_glow`, and `Config.requiresStructuralRebuild` tests).

### Feature → Files Map (`v1.2.1`)
| Feature (for Path D cherry-picking) | Files in `patches/` | Add-ons affected? |
|---|---|---|
| **Picsum.photos Random Web Wallpapers** | `PicsumWallpapers.java`, `Config.java`, `WallpaperPlayer.java`, `LauncherSettingsActivity.java`, `QrConfigServer.java`, `NewFeaturesTest.java`, `patches/locales/values*/strings_matcher.xml` | No |
| **Nested Menu Blur Toggle (Sharp Parent + Frosted Popup)** | `ExpressiveGlass.java`, `FeaturePrefs.java`, `LauncherFeatureSettings.java`, `MainActivity.java`, `OverlayActivity.java`, `NewFeaturesTest.java`, `patches/locales/values*/strings_matcher.xml` | Yes (`patches/addons/*/…/GlassKit.java`) |
| **Dock Ambient Glow Toggle** | `FeaturePrefs.java`, `LauncherFeatureSettings.java`, `MainActivity.java`, `NewFeaturesTest.java`, `patches/locales/values*/strings_matcher.xml` | No |
| **Global Liquid Glass Optics & Locale Forwarding** | `ExpressiveGlass.java`, `MainActivity.java`, `patches/locales/values*/strings_matcher.xml` | Yes (`patches/addons/*/…/GlassKit.java`) |
| **Full 31-Locale Add-on UI Extraction (`brightness`, `quiet-hours`, `bluetooth-connect`)** | — | Yes (`patches/addons/{brightness,quiet-hours,bluetooth-connect}/…/{MainActivity.java,res/values*/strings.xml}`) |
| **Rows-Below-Dock Layout & Dock Scroll Clip/Fade Fix** | `AppAdapter.java`, `DockDecoration.java`, `MainActivity.java`, `TileFitTest.java` | No |
| **Liquid Glass Dock Opacity (`30%–100%`)** | `ExpressiveGlass.java`, `DockDecoration.java`, `LauncherSettingsActivity.java`, `MainActivity.java`, `ExpressiveGlassTest.java` | No |
| **Module Cards Hide Across Home & App Library** | `AppAdapter.java`, `MainActivity.java`, `OverlayActivity.java` | No |
| **Scrollable OTA Updates Dialog** | `LauncherFeatureSettings.java`, `SelfUpdater.java` | No |
| **No-Wallpaper Stale Refraction Fix** | `WallpaperPlayer.java`, `DockDecoration.java`, `FrostedPillDrawable.java`, `MainActivity.java` | No |
| **Smooth 60 FPS Dock Color Glides** | `DockDecoration.java`, `MainActivity.java` | No |
| **Mirror Control Center Settings Collapse** | `QuickSettingsActivity.java` | No |
| **3-Tier Seamless Reload & Post-OTA Auto-Recovery** | `MainActivity.java`, `Config.java`, `ReloadReceiver.java`, `ServiceRepair.java`, `SelfUpdater.java`, `FeaturePrefs.java`, `LauncherFeatureSettings.java`, `AndroidManifest.xml`, `update.ps1`, `update.sh` | No |

---

## `v1.2.0` — Universal Release APKs, 31 Locales, In-App Remote Remapper, 6 Glass Styles & 60 FPS Dock

### Highlights
- **6-Preset Glass Material Design System (`GlassStyle`):** Switch live between *Frosted Tonal*, *Pure Black (OLED)*, *Frost White* (full light-glass inversion with dark graphite text/icons & dark Fresnel focus ring), *Clear Crystal*, *Chromatic Tint (Material You)*, and *Auto (Day / Night)* across Home, Dock, top pills, all modals/popups, and all 8 add-ons (`GlassKit`).
- **Locked 60 FPS Home & Dock Performance Fix:** Eliminated a 30 ms/frame CPU bitmap rebuild in `DockDecoration.invalidateBackdrop()`, fixed the `DockBackdropView` `View.GONE` layout deadlock so the Dock stays 100% GPU-cached in `LAYER_TYPE_HARDWARE` VRAM, removed per-frame `ambientAnimator` / `triggerRipple` FBO thrashing, and moved `HeroArt` blur off the UI thread with an `O(W·H)` running-sum box blur (`39.87%` jank → `0.00%` jank on Xiaomi TV Box S 3rd Gen 4K60).
- **In-App "Learn Button" Remote Remapper (No PC / No ADB Needed):** Map any physical button on any Android TV remote right from *Launcher Settings → Remote buttons* with independent **Tap**, **Double-tap**, and **Hold** actions across installed apps and built-in overlays (Control Center, App Library, Recents, Screensaver, Brightness, Sleep Timer, Inputs, etc.).
- **Expanded Phone QR Config Editor (`QrConfigServer`):** Configure remote buttons (with live dropdown of the TV's installed apps), custom app labels (`app_labels`), hidden apps (`hidden_apps`), weather city/coordinates, upload a custom wallpaper directly from your phone gallery, and export/import `config.json`.
- **All 31 Locales Across BOTH Launcher & All 8 Add-ons:** Full translations for all 30 upstream CleanYourTV locales + Slovak (`sk`) — including Polish (`pl`), Czech (`cs`), German (`de`), Spanish (`es`, `es-419`), French (`fr`), Italian (`it`), Portuguese (`pt`, `pt-PT`), Dutch (`nl`), Russian (`ru`), Ukrainian (`uk`), Turkish (`tr`), Arabic (`ar`), Hebrew (`iw`), Korean (`ko`), Chinese (`zh-CN`, `zh-TW`), and more — stored in clean `strings_matcher.xml` overlays beside upstream `strings.xml` in the launcher (`LocaleIntegrityTest`) and across all 8 GlassKit add-ons (`tools/validate-addon-locales.py`, `248/248` checks in CI).
- **Universal Signed Release APKs, Synchronized Add-on Versioning, OTA `SelfUpdater` & No-SDK Update Scripts:**
  - GitHub Actions release pipeline (`.github/workflows/release.yml`) validating all 31 launcher + 8×31 add-on locales, automatically syncing release `versionCode`/`versionName` across all 8 add-on APKs (so OTA & scripts reliably detect GlassKit/translation updates), signing everything with a stable release key, and publishing `manifest.json` + `SHA256SUMS.txt`.
  - Re-enabled in-app OTA **Updates** in *Launcher Settings* with `BuildConfig.MATCHER_VERSION` comparison, `manifest.json` support (launcher + individual changed add-ons), and automatic `MY_PACKAGE_REPLACED` `ServiceRepair` to keep `HomeKeyService` bound after OTA updates.
  - Lightweight `update.ps1` (Windows) & `update.sh` (macOS/Linux) for 1-command binary updates via ADB without JDK/SDK.
  - Added [`UPDATE.md`](UPDATE.md) for safe AI-agent incremental updates & cherry-picking.
- **UI Polish:** Control Center *Settings → Reorder tiles* now uses opposed swap arrows (`ic_swap_vert`) instead of duplicating the Apps grid icon.

### Feature → Files Map (`v1.2.0`)
| Feature (for Path D cherry-picking) | Files in `patches/` | Add-ons affected? |
|---|---|---|
| **60 FPS Dock & HeroArt Performance Fix** | `DockDecoration.java`, `MainActivity.java`, `HeroArt.java`, `GlassView.java` | No |
| **6-Preset Glass Material System (`GlassStyle`)** | `ExpressiveGlass.java`, `FeaturePrefs.java`, `LauncherFeatureSettings.java`, `LauncherSettingsActivity.java`, `MainActivity.java`, `DockDecoration.java`, `FrostedPillDrawable.java`, `GlassView.java`, `FocusRingController.java`, `AppAdapter.java`, `AppLibraryDialog.java`, `FolderDialog.java`, `QuickSettingsActivity.java`, `RecentsDialog.java`, `MediaMiniPlayerDialog.java`, `DiscoverDialog.java`, `QrSetupDialog.java`, `ClockScene.java`, `ExpressiveGlassTest.java` | Yes (`patches/addons/*/…/GlassKit.java` + add-on activities) |
| **In-App "Learn Button" Remote Remapper** | `RemoteKeys.java`, `FeaturePrefs.java`, `HomeKeyService.java`, `InputKeyService.java`, `Config.java`, `RemoteMapperDialog.java`, `LauncherFeatureSettings.java`, `RemoteKeysTest.java` | No |
| **Expanded Phone QR Web Editor** | `QrConfigServer.java`, `FeaturePrefs.java`, `Config.java`, `AmbientWeather.java`, `MainActivity.java`, `ClockScene.java`, `NewFeaturesTest.java` | No |
| **31-Locale System (Launcher + 8 Add-ons)** | `patches/locales/values*/*.xml`, `patches/locales_config.xml`, `LocaleIntegrityTest.java`, `tools/validate-addon-locales.py`, `apply-setup.ps1` | Yes (`patches/addons/*/app/src/main/res/values-*/`) |
| **OTA `SelfUpdater` + `MY_PACKAGE_REPLACED` Repair** | `SelfUpdater.java`, `TvHomeApp.java`, `AndroidManifest.xml`, `LauncherFeatureSettings.java`, `FeaturePrefs.java`, `NewFeaturesTest.java`, `.github/workflows/release.yml`, `update.ps1`, `update.sh` | Yes (CI syncs add-on `versionCode`/`versionName` & signs all 8 APKs) |
| **Control Center Reorder Tiles Icon (`ic_swap_vert`)** | `QuickSettingsActivity.java` (line ~973) | No |

---

## `v1.1.2` — Sharp Home Mode, GPU `RenderNode` Panel Cache & Crisp `960×540` Rims

### Highlights
- Added live **Home backdrop blur** toggle in *Launcher Settings* (`FeaturePrefs.KEY_HOME_BACKDROP_BLUR`) so users can disable full-screen Home dimming/blur behind modals.
- Added GPU `RenderNode`-cached per-panel frosted interior blur (`uInteriorFill = 1.0f`) paired with untouched `960×540` `uSharp` optical rim refraction across modals, context menus, Dock, top circular pills, beveled clock, and all 8 add-ons (`EXTRA_OVER_SHARP_BACKDROP`).
- Added 4-screenshot `v1.1.2` visual showcase in [`docs/GALLERY.md`](docs/GALLERY.md).

### Feature → Files Map (`v1.1.2`)
| Feature | Files in `patches/` | Add-ons affected? |
|---|---|---|
| **Sharp Home Mode & `960×540` Crisp Rims** | `ExpressiveGlass.java`, `FeaturePrefs.java`, `LauncherFeatureSettings.java`, `MainActivity.java`, `DockDecoration.java`, `FrostedPillDrawable.java`, `LiquidClockView.java`, `QuickSettingsActivity.java`, `OverlayActivity.java`, `LauncherSettingsActivity.java`, `AppLibraryDialog.java`, `FolderDialog.java`, `RecentsDialog.java`, `MediaMiniPlayerDialog.java`, `DiscoverDialog.java`, `QrSetupDialog.java` | Yes (`patches/addons/*/…/GlassKit.java`) |

---

## `v1.1.1` — Full 43-Shot Gallery, Add-on Modularity Docs & Pinned Upstream CleanYourTV 3.6+

### Highlights
- Pinned upstream `Gathin23/cleanyourtv` to commit `be6087056a1c7d4f08f3f1cc778d92374d86ef27` (`-UpstreamRef` flag in `apply-setup.ps1`).
- Documented adding/removing any of 40+ upstream add-ons and visually matching them with `GlassKit.java`.
- Added 43-screenshot full-UI visual gallery (`docs/gallery/v1.1.1/`).

---

## `v1.1.0` — Liquid Glass 2.0, Material 3 Expressive Nested Blur, 4K Aerials, Double-Tap Keys & HA Camera PiP

### Highlights
- 4-layer AGSL Liquid Glass 2.0 optical refraction with 2-`RenderNode` Material 3 Expressive GPU nested blur.
- 3D tactile tile tilt physics + `TileSheenDrawable`.
- 4K HEVC Apple TV Aerial streams + favorite loop pinning (`★`).
- Three-level remote key mapping (`tap` / `double:` / `hold:`).
- Home Assistant live `camera.*` PiP overlay (`HaCameraOverlay`) + Discover & Phone QR setup.

---

## `v1.0.0` — Initial Public Release

- Initial Liquid Glass Android TV / Google TV launcher overlay on CleanYourTV + 8 GlassKit-styled modular add-ons + automated ADB setup and safe rollback scripts.
