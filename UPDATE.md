# UPDATE.md — Safe Incremental Update Guide (For Users & AI Coding Agents)

Use this document when **Matcher is already installed and working on your TV / box** and you want to apply new updates (or cherry-pick a single feature such as *Sharp Home Blur*, *Glass Material Styles*, *60 FPS Dock Fix*, or *In-App Remote Remapping*) **without re-running initial setup or debloat**.

---

## 0. Hard Guardrails for AI Coding Agents (READ FIRST!)

When the user asks you to update an existing Matcher installation using this file:

1. **NEVER re-run debloat** (`disable.ps1`, `pm disable-user`, `my-debloat.txt`). The TV is already set up.
2. **NEVER touch `setupwraith` or Wi-Fi settings** (`com.google.android.tungsten.setupwraith` / `com.google.android.apps.tv.launcherx`).
3. **NEVER overwrite the user's personal configuration:**
   - Keep their existing `my-theme/config.json` (dock apps, `app_labels`, `app_keys`, `gamepad_passthrough`, wallpapers, custom theme tweaks).
   - Keep their existing `my-debloat.txt` and `./backup/` files.
   - Keep their custom weather location (`latitude`, `longitude`, `weather_city` in `res/values/config.xml` or `FeaturePrefs`) if they customized it.
   - Never run `adb uninstall` (which wipes folders, launcher settings, HA/Immich/TMDB keys, and learned remote buttons). **Always use `adb install -r` (in-place upgrade).**
4. **ALWAYS re-verify Accessibility services after every APK install** (`HomeKeyService`, and `SleepService` if `sleep-timer` is installed), then run `sync`.

---

## Choose Your Update Path

| Path | Who it is for | Needs JDK / Android SDK? | Preserves theme, folders, keys & debloat? |
|---|---|---|---|
| **Path A — In-App OTA Update (On TV)** | Users running official signed APKs from GitHub Releases (`v1.2.0+`) | **No** (no PC needed) | **Yes** |
| **Path B — 1-Command Binary Update (`update.ps1` / `update.sh`)** | Users who want fast ADB updates from GitHub Releases without compiling | **No** (only `adb`) | **Yes** |
| **Path C — AI Agent Full Source Update** | Users building from source / maintaining custom edits | Yes (existing local toolchain) | **Yes** |
| **Path D — AI Agent Single-Feature Cherry-Pick** | Users who only want **one specific feature** (e.g., blur, glass styles, or 60 FPS dock fix) | Yes (existing local toolchain) | **Yes** |

> **Important note on APK signing:** Android only allows `adb install -r` / OTA updates when the new APK is signed with the **same signing key** as the installed APK. If you previously compiled locally (signed with your machine's `debug.keystore`), keep updating via **Path C / D**, OR switch once to the official signed Release APK (back up your `config.json` first if uninstalling a locally-signed build).

---

## Path A — On-TV OTA Update (No PC Required, `v1.2.0+` Release APKs)

1. On your TV, open **Control Center → Settings → Launcher settings → Updates (GitHub)**.
2. Select **Check for updates now** (or enable **Weekly update check**).
3. If a newer version (or updated modular add-on) is listed, select **Download & install**.
4. Matcher verifies the GitHub Release domain, SHA-256 checksum, `versionCode`, and matching signing certificate before launching Android's `PackageInstaller`.
5. After installation finishes, `UpdateCompletedReceiver` (`MY_PACKAGE_REPLACED`) automatically restores `HomeKeyService` (and `SleepService` if installed) via `ServiceRepair`.

*(Prefer **Obtainium** on Android TV? Add `https://github.com/ryslavy/matcher` and match `matcher-launcher-*.apk`.)*

---

## Path B — Fast Binary Update Script (No JDK / SDK Needed)

Downloads `manifest.json` from the latest GitHub Release, checks which installed APKs (launcher + only the add-ons you actually have installed) have newer versions, verifies SHA-256, installs via `adb install -r`, and restores Accessibility services:

```powershell
# Windows:
powershell -ExecutionPolicy Bypass -File .\update.ps1 -TvHost <TV_IP>
```

```bash
# macOS / Linux:
./update.sh <TV_IP>
```

Pass `-LauncherOnly` / `--launcher-only` to update only the launcher and skip add-ons.

---

## Path C — AI Agent Full Source Update (Skip Debloat & Theme)

Prompt you can paste directly to your AI agent (Claude Code, Antigravity, Cursor, Codex, Gemini):

> *"Read `UPDATE.md` and `CHANGELOG.md` from `ryslavy/matcher`. Update my local launcher (and patched add-ons only if `GlassKit.java` changed) to the latest version using Path C. Do NOT run debloat, do NOT touch `setupwraith` or `my-theme/config.json`, preserve my weather city and installed add-on selection, run unit tests, install with `adb install -r`, and re-bind Accessibility services."*

### Exact Steps for the Agent (Path C)

1. **Fetch latest upstream Matcher changes** (without touching user config):
   - Pull or fetch `https://github.com/ryslavy/matcher` into a temporary staging folder (or `git pull --ff-only` if the workspace is a clean clone of `matcher`).
   - Check [`CHANGELOG.md`](CHANGELOG.md) to see whether only the **launcher** changed, or if **`GlassKit.java` / add-ons** also changed since the user's local version.
   - Check if the pinned upstream CleanYourTV commit changed (only update `./cleanyourtv` if required by `CHANGELOG.md`).
2. **Sync launcher source & locale overlays ONLY:**
   - Copy updated `patches/*.java`, `patches/*Test.java`, `patches/*.xml` (layouts, drawables, anims, styles, ids, colors, manifest, `locales_config.xml`) into `./cleanyourtv/launcher/app/src/...`.
   - Copy all `patches/locales/values*/*.xml` into `./cleanyourtv/launcher/app/src/main/res/values*/` (this merges `strings_matcher.xml` cleanly beside upstream `strings.xml` and supplies `values-sk/strings.xml` with zero conflicts across all 31 locales).
   - **Merge `patches/launcher-config.xml` carefully** into `cleanyourtv/launcher/app/src/main/res/values/config.xml` — preserve the user's `latitude`, `longitude`, `weather_city`, and any custom flags they previously edited.
3. **Sync add-ons ONLY if `patches/addons/` changed** (and ONLY for add-ons the user actually has installed on their TV — check with `adb -s <TV_IP> shell pm list packages -3`):
   - Mirror changed files under `patches/addons/<addon>/` into `./cleanyourtv/addons/<addon>/` (and optionally verify all 31 locales with `python tools/validate-addon-locales.py --merged-only`).
   - Remember: every copy of `GlassKit.java` must be identical except line 1 (`package ...;`) and saved as **UTF-8 without BOM**.
4. **Run unit tests and build release APK(s):**
   ```powershell
   $env:JAVA_HOME = (Get-ChildItem .\toolchain\jdk\jdk-17* -Directory | Select-Object -First 1).FullName
   $env:ANDROID_HOME = "$PWD\toolchain\android-sdk"
   Push-Location .\cleanyourtv\launcher
   .\gradlew.bat testReleaseUnitTest assembleRelease --no-daemon --console=plain
   Pop-Location
   ```
   *(If any add-on changed, build only those installed add-ons with `.\gradlew.bat assembleRelease --no-daemon`.)*
5. **In-place install (`adb install -r`) & bounce-rebind Accessibility services:**
   ```powershell
   $adb = '.\toolchain\android-sdk\platform-tools\adb.exe' # or .\platform-tools\adb.exe / adb
   & $adb connect <TV_IP>
   # Capture currently enabled Accessibility services BEFORE install so we preserve user's exact set
   $prevA11y = (& $adb -s <TV_IP> shell "settings get secure enabled_accessibility_services").Trim()
   & $adb -s <TV_IP> install -r .\cleanyourtv\launcher\app\build\outputs\apk\release\app-release.apk
   # Bounce-rebind Accessibility services (Android 14 can unbind them on APK upgrade)
   & $adb -s <TV_IP> shell "settings put secure enabled_accessibility_services ''"
   Start-Sleep -Seconds 1
   & $adb -s <TV_IP> shell "settings put secure enabled_accessibility_services '$prevA11y'; settings put secure accessibility_enabled 1; sync"
   ```
6. **Verify live on the TV:**
   ```powershell
   & $adb -s <TV_IP> shell "am start -n com.gathin.tvhome/.MainActivity"
   Start-Sleep -Seconds 2
   & $adb -s <TV_IP> shell "dumpsys accessibility | grep -E 'Bound services|Service\[label=|Crashed services'"
   & $adb -s <TV_IP> shell "logcat -d -t 100 | grep -E 'FATAL EXCEPTION|AndroidRuntime'"
   ```
   Confirm `Crashed services:{}` is empty and zero crashes appear in `logcat`. Remind the user not to unplug/reboot for 15 seconds while `sync` settles. If you encounter a reproducible bug, regression, or performance issue during the update, offer to help the user report an anonymized summary upstream as a [GitHub Issue](https://github.com/ryslavy/matcher/issues/new/choose), [Pull Request](https://github.com/ryslavy/matcher/pulls), or [Discussion](https://github.com/ryslavy/matcher/discussions) on `ryslavy/matcher` (only with their consent).

---

## Path D — AI Agent Single-Feature Cherry-Pick

Want **only one specific improvement** (for example, the *Sharp Home Blur* mode, the *60 FPS Dock Fix*, the *6 Glass Material Styles*, or *In-App Remote Button Learning*)?

Prompt you can paste to your AI agent:

> *"Read `UPDATE.md` and the Feature → Files Map in `CHANGELOG.md` from `ryslavy/matcher`. Apply ONLY the `<Feature Name>` change to my local launcher. Do NOT touch debloat, `my-theme/config.json`, or unrelated files. Run `testReleaseUnitTest assembleRelease`, install with `adb install -r`, and re-bind Accessibility services."*

### Agent Rules for Cherry-Picking (Path D)

1. Look up the requested feature in the **Feature → Files Map** in [`CHANGELOG.md`](CHANGELOG.md).
2. Inspect `git diff` between the user's current version and latest `ryslavy/matcher` **scoped strictly to those listed files** (plus any new string keys referenced by those files in `strings_matcher.xml`).
3. Apply only the hunk(s) belonging to that feature cleanly (never blindly overwrite files if the user has local customizations — merge surgically!).
4. Follow Steps 4–6 of **Path C** (unit tests → `assembleRelease` → `adb install -r` → Accessibility re-bind → `dumpsys accessibility` + `logcat` verification).
