# Changelog & Feature → Files Map

All notable Matcher releases, with an explicit **Feature → Files Map** so users and AI agents following [`UPDATE.md`](UPDATE.md) can either update everything cleanly or cherry-pick a single feature without re-running debloat or touching personal themes.

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
