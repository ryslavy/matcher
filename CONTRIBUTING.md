# Contributing to Matcher

Thank you for helping improve **Matcher**! Whether you want to add a translation, contribute a theme or device preset, fix a UI glitch, or build a new feature, this guide covers the complete workflow.

---

## 1. Local Development Setup

1. **Fork and clone** this repository on Windows 10/11 (PowerShell 5.1+ or PowerShell 7+) with **Git** and **Python 3** installed.
2. **Bootstrap the upstream workspace & portable toolchain** (Downloads OpenJDK 17, Android SDK 34, ADB Platform-Tools, clones `Gathin23/cleanyourtv`, and applies all `patches/`):
   - If you have a test TV on your LAN:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP> -SkipDebloat
     ```
   - Or if you only want to compile & run unit tests locally without connecting to a TV yet, clone `Gathin23/cleanyourtv` into `./cleanyourtv/`, copy `./patches/` into it following steps 6–7 of [`apply-setup.ps1`](apply-setup.ps1), and set `JAVA_HOME` + `ANDROID_HOME`.

---

## 2. Editing Code & Syncing `patches/`

Because `./cleanyourtv/` is a gitignored clone of upstream `Gathin23/cleanyourtv`, **all tracked source changes live in `./patches/`**:

1. Make and test your changes inside `./cleanyourtv/launcher/` or `./cleanyourtv/addons/<addon>/`.
2. Run the full JVM unit test suite and release build:
   ```powershell
   Push-Location .\cleanyourtv\launcher
   .\gradlew.bat testReleaseUnitTest assembleRelease --no-daemon
   Pop-Location
   ```
   All 375+ unit tests must pass cleanly without Android device dependencies.
3. If you modified `GlassKit.java` in one add-on, sync it identically (preserving each package's `package ...;` line 1, UTF-8 without BOM) across every patched add-on under `patches/addons/`:
   - Defaults: `bluetooth-connect`, `brightness`, `capacity-stats`, `headphones`, `quiet-hours`, `sleep-timer`, `weather`, `world-clock` (plus any additional upstream add-ons you matched).
4. Copy every modified file from `./cleanyourtv/` back to `./patches/` (or `./patches/addons/...`), and regenerate the unified patch:
   ```powershell
   git -C .\cleanyourtv diff --binary --output="$PWD\patches\appletv-frosted-glass.patch"
   ```
5. Verify `git diff` has no hardcoded LAN IPs, personal locations, API keys, or UTF-8 BOMs.

---

## 3. Adding or Improving Translations

We warmly welcome translations! Here is how localization works in Matcher:

### Current Language Coverage
- **Launcher (`com.gathin.tvhome`):** Complete **31-locale coverage** (`values` default EN + all 30 upstream CleanYourTV locales + Slovak `values-sk`). All Matcher-specific strings live in separate **`patches/locales/values*/strings_matcher.xml`** overlays beside upstream `strings.xml` (zero merge conflicts with upstream CleanYourTV!), validated automatically by [`LocaleIntegrityTest.java`](patches/LocaleIntegrityTest.java) for XML well-formedness, zero missing keys, escaped apostrophes, and 100% printf placeholder parity.
- **8 Modular Add-ons:** Complete **31-locale coverage** across all 8 default GlassKit add-ons (`brightness`, `sleep-timer`, `headphones`, `bluetooth-connect`, `quiet-hours`, `weather`, `world-clock`, `capacity-stats`), preserving complete upstream translations and filling every remaining locale under `patches/addons/<addon>/app/src/main/res/values-<lang>/`. Validated automatically in CI across all `8 × 31 = 248` locale sets by [`tools/validate-addon-locales.py`](tools/validate-addon-locales.py). Translations outside English and Czech were AI-translated on top of upstream CleanYourTV — native-speaker PR refinements are always welcome!

### How to Improve or Add a Language (e.g. Polish `pl`, German `de`, Slovak `sk`)

1. **Launcher strings:**
   - Edit `patches/locales/values-<lang>/strings_matcher.xml` (or copy `patches/locales/values/strings_matcher.xml` for a brand-new locale and register it in `patches/locales_config.xml`).
   - Keep all `name="..."` attributes and format placeholders (`%1$s`, `%1$d`, `%%`) intact, and escape apostrophes as `\'`.
   - Run `.\gradlew.bat testReleaseUnitTest` — `LocaleIntegrityTest` immediately verifies every locale!

2. **Add-on strings (8 add-ons):**
   Edit (or create for a brand-new language) `patches/addons/<addon>/app/src/main/res/values-<lang>/strings.xml`:
   - `sleep-timer` uses **3 XML files**: `strings.xml`, `schedule.xml`, and `remote_picker.xml` (preserve double-quoted trailing spaces on `sched_5`, `sched_11`, `sched_12`; note upstream `values-ru` uses `values.xml`).
   - `weather` and `world-clock` include `<plurals>` (`day_count`, `day_difference` with `%1$+d`) — define the appropriate CLDR plural quantities for your language (`one`, `few`, `many`, `other`). Never override `weather`'s non-translatable `temperature_unit`.
   - Run `python tools/validate-addon-locales.py` to validate all `8 × 31 = 248` locale sets instantly!
   - `apply-setup.ps1` and `.github/workflows/release.yml` automatically copy every file under `patches/addons/` recursively — no script edits needed!

3. **XML Rules & Tips:**
   - Escape apostrophes (`\'`) and ampersands (`&amp;`).
   - Keep TV button & tile labels concise so they fit cleanly on 1080p/720p living-room screens without truncation.
   - Save all XML files as **UTF-8 without BOM**.

---

## 4. Contributing Themes, Add-on Glass Patches, Wallpapers & Device Presets

- **Matching Additional Upstream Add-ons (`patches/addons/<name>/`):** CleanYourTV ships 40+ modular add-ons in `cleanyourtv/addons/`. To visually match another upstream add-on (e.g. `ad-blocking`, `tv-hub`, `game-day`, `remote-access`) to Matcher's Liquid Glass 2.0 aesthetic, follow Playbook 4 in [`SKILL.md`](SKILL.md#playbook-4--adding-visually-matching-glasskit-or-removing-modular-add-ons): drop in the shared `GlassKit.java` (only line 1 `package ...;` changed, UTF-8 without BOM), apply `Theme.GlassOverlay` + `GlassKit.installModal(...)` (leaving full-screen Canvas screensavers untouched), mirror your overrides into `patches/addons/<name>/`, and test on a real TV.
- **Custom Themes:** Create a folder with a `config.json` and 16:9 `wallpapers/*.jpg` images (1920×1080 JPEG ~85% quality keeps TV RAM usage low). Test with `.\switch-theme.ps1` or `cleanyourtv\tools\theme.ps1`.
- **Device Presets:** To add support for another Android TV / Google TV box or integrated TV (e.g. Chromecast with Google TV, Onn 4K Pro, Shield TV, Sony, TCL, Philips, Hisense):
  - First cross-check upstream [`Gathin23/cleanyourtv/tree/main/docs/devices`](https://github.com/Gathin23/cleanyourtv/tree/main/docs/devices) and `docs/DOS-AND-DONTS.md` for known vendor pitfalls, protected HDMI/tuner packages, GPU flags (`reduced_motion` / `glow_snap`), or HOME allowlists (`-PlauncherApplicationId=...`).
  - Create `presets/<device-slug>/debloat.txt` (only reversible `pm disable-user --user 0` packages — never `pm uninstall`, and never disable system Wi-Fi, Bluetooth, HDMI/CEC, TV tuner, Play Services, or Cast).
  - Optionally add `presets/<device-slug>/remote-keymap.md` with Linux scancodes (`adb shell getevent -lt`) and Android keycodes.

---

## 5. UI, Liquid Glass & Code Quality Guidelines

Before opening a Pull Request, check these project standards:
- **Valid Premultiplied Alpha & Dark-TV Optics:** Any AGSL `RuntimeShader` must output `0 <= rgb <= a <= 1` so hardware layers and direct draws are bit-identical, and must avoid light-mode white lift on dark pixels.
- **4-Layer Backdrop & Hardware-Cached Nested Blur:** Nested menus over modals must refract `nestedRefraction` (with Frame-0 invalidation in `Motion.glidePanel`/`GlassKit.glidePanel`), compute floating window origins via `computeScreenOrigin` (never double-counting `winOff`), and use the 2-`RenderNode` hardware-cached `ExpressiveBlurVeilDrawable` instead of per-frame child `RenderEffect` mutations.
- **No Wallpaper Leakage Over External Apps:** Never fall back to Home's `RefractionBackdrop` when `!MainActivity.isHomeVisible()`.
- **Translucent Overlays:** Every floating Activity must inherit `Theme.GlassOverlay` and handle `onNewIntent` if `singleTask`.
- **Input & Audio Parity:** Every new `PopupWindow` or `Dialog` must call `PointerSupport.install(root)` and `UiClickSound.attachFocusSound(root)`, wrap its panel in `FocusRingController.wrapPopup(...)`, clear default window elevation via `ExpressiveGlass.clearDialogShadow(w)`, and support smooth Back-button dismissal.
- **Zero Flashes:** Chained popups/modals on Home must hold backdrop blur across handoffs (`holdBackdropBlurForHandoff()`).
- **Unit Tests:** Add pure-JVM unit tests in `patches/*Test.java` for any new parser, client, color math, or state-machine logic.

---

## 6. Pull Request Checklist

- [ ] `./gradlew.bat testReleaseUnitTest assembleRelease --no-daemon` passes cleanly.
- [ ] All modified files inside `cleanyourtv/` are synced to `patches/` (and `GlassKit.java` synced across all 8 add-ons if touched).
- [ ] `patches/appletv-frosted-glass.patch` is regenerated.
- [ ] No personal IP addresses, hometown coordinates, device dumps, or API keys are committed (including inside screenshots).
- [ ] Changes affecting user-facing features or setup are reflected in `README.md` (and `AGENTS.md` if architecture/invariants changed); UI-changing releases update `docs/gallery/v<release>/` and the release tag in `docs/GALLERY.md`.
