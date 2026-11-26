---
name: matcher-tv-customization
description: Build, customize, translate, deploy, debug, and safely roll back the Matcher Liquid Glass Android TV / Google TV launcher and its 8 modular add-ons. Use whenever working in this repository to modify themes, remote keymaps, Liquid Glass shaders, launcher features, add-on locales, or TV box deployments via ADB.
---

# Matcher TV Customization Skill

Use this skill when assisting a user with deploying, customizing, extending, translating, debugging, or restoring **Matcher** on an Android TV / Google TV box.

Always read [`AGENTS.md`](AGENTS.md) first for architecture invariants and safety constraints.

---

## Playbook 1 — Safe Initial Deployment to Any TV / Box

1. **Confirm the TV's ADB target (`<TV_IP>` or `<TV_IP>:<PORT>` for Android 14 Wireless Debugging).**
   Verify connectivity (if using Wireless Debugging for the first time, run `adb pair <TV_IP>:<PAIR_PORT>` first):
   ```powershell
   .\platform-tools\adb.exe connect <TV_IP>
   .\platform-tools\adb.exe -s <TV_IP> get-state
   ```
2. **MANDATORY — Identify the exact TV / box model & consult upstream CleanYourTV device docs:**
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release; getprop ro.build.version.sdk"
   ```
   - Search `./cleanyourtv/docs/devices/` (or `https://github.com/Gathin23/cleanyourtv/tree/main/docs/devices`) plus `docs/DOS-AND-DONTS.md` for this exact model.
   - Check whether the model requires a custom `-PlauncherApplicationId=<pkg>` (HOME allowlist), has protected vendor packages (HDMI/tuner/picture settings), or needs `"reduced_motion": true` / `"glow_snap": true` (weak GPU / `SDK < 33` fallback).
   - **Never run `my-debloat.txt` on any device other than `MiTV-AFMU0`.** Always pass `-SkipDebloat` for other devices unless using a device-verified list.
3. **Save a local pre-flight backup into gitignored `./backup/` before changing packages or settings:**
   ```powershell
   New-Item -ItemType Directory -Force .\backup | Out-Null
   .\platform-tools\adb.exe -s <TV_IP> shell "pm list packages -e" | Out-File -Encoding utf8 .\backup\enabled-packages.txt
   .\platform-tools\adb.exe -s <TV_IP> shell "pm list packages -d" | Out-File -Encoding utf8 .\backup\disabled-packages.txt
   .\platform-tools\adb.exe -s <TV_IP> shell "settings get secure enabled_accessibility_services" | Out-File -Encoding utf8 .\backup\a11y.txt
   .\platform-tools\adb.exe -s <TV_IP> shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | Out-File -Encoding utf8 .\backup\home.txt
   ```
   *(Never use `pm uninstall` on system packages — always use reversible `pm disable-user --user 0`.)*
4. **CRITICAL Wi-Fi ownership check (Android 14 / Google TV) before any debloat:**
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "dumpsys wifi | grep -E 'cname=|creatorName'"
   ```
   - If output mentions `setupwraith`, **STOP** and ask the user to **Forget** and **Reconnect** Wi-Fi in TV Settings → Network & Internet first.
5. **Run the automated installer** (on macOS/Linux, run the equivalent `./gradlew` + `adb` + `cleanyourtv/tools/` commands in bash/zsh):
   ```powershell
   # Any non-Xiaomi TV / box (safe default):
   powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP> -SkipDebloat
   # Xiaomi TV Box S 3rd Gen (MiTV-AFMU0) with full debloat:
   powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP>
   ```
6. **Verify Accessibility services & disk flush:**
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "dumpsys accessibility | grep -E 'Bound services|Service\[label=|Crashed'"
   .\platform-tools\adb.exe -s <TV_IP> shell "sync"
   ```
   Confirm both `HomeKeyService` and `SleepService` are bound and `Crashed services:{}` is empty. Remind the user to wait 15 seconds before rebooting.

---

## Playbook 2 — Adapting Apps, Remote Keys, Location & Visual Elements

### 2A — Personalize Installed Apps, Own Remote & Weather City
1. **List the user's installed TV apps:**
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "pm list packages -3"
   ```
   Replace `dock_apps` and `app_labels` in [`my-theme/config.json`](my-theme/config.json) with the user's own streaming apps.
2. **Capture the user's remote control scancodes & keycodes** (see also `cleanyourtv/docs/REMOTE.md`):
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "getevent -lt"
   ```
   Have the user press each shortcut button once, note its Linux scancode (`scan:<hex-to-dec>`) and Android keycode (`KEYCODE_...`), and update `app_keys` (tap, optional `double:`, and `hold:`) in [`my-theme/config.json`](my-theme/config.json).
3. **Update local weather & sunrise/sunset location:**
   Edit `latitude`, `longitude`, and `weather_city` in [`patches/launcher-config.xml`](patches/launcher-config.xml) (lines 503–505), sync to `cleanyourtv/launcher/app/src/main/res/values/config.xml`, and rebuild the launcher APK.

### 2B — Turn Off or Tone Down Visual Elements (No Code vs. Shader Tuning)
- **Instant in-app toggles (TV Control Center → Settings):** Liquid Glass clock, Home Liquid Glass optics, UI click sounds, OLED screensaver drift, Aerial videos, Watch from Screensaver.
- **Instant theme toggles (edit [`my-theme/config.json`](my-theme/config.json) → `.\switch-theme.ps1 my-theme -TvHost <TV_IP>`):**
  - Disable/soften Dock glass: `"dock_glass": false` or lower `"dock_opacity"` (`0`–`100`).
  - Disable colorful top shelf / glow: `"cinematic_home": false`, `"ambient_glow": false`, `"app_glow": false`.
  - Calm animations for older/weak GPUs: `"reduced_motion": true`, `"glow_snap": true`, `"advanced_effects": false`.
  - Change grid density or swap wallpapers: `"columns"`, `"rows_below_dock"`, `"wallpaper_moods"` + JPGs in `my-theme/wallpapers/`.
  - Try 30+ upstream themes while keeping dock & remote keys:
    ```powershell
    .\switch-theme.ps1 -List
    .\switch-theme.ps1 <theme_name> -TvHost <TV_IP>
    ```
- **Code-level Glass toning (rebuild required):** Adjust refraction strength, rim brightness, or smoked tint in `LIQUID_GLASS_PANEL_AGSL` inside `ExpressiveGlass.java` and all 8 synchronized `GlassKit.java` copies while strictly keeping `0 <= premul.rgb <= outA`.

---

## Playbook 3 — Modifying Launcher or Add-on Code

1. Edit sources inside `./cleanyourtv/launcher/` (and/or `./cleanyourtv/addons/<addon>/`).
2. **If editing `GlassKit.java`:**
   - Maintain valid Porter-Duff premultiplied alpha (`0 <= premul.rgb <= outA`).
   - Synchronize the updated `GlassKit.java` across all 8 add-ons preserving each file's `package ...;` line 1, encoded as **UTF-8 without BOM**.
3. **Run unit tests and release builds:**
   ```powershell
   $env:JAVA_HOME = (Get-ChildItem .\toolchain\jdk\jdk-17* -Directory | Select-Object -First 1).FullName
   $env:ANDROID_HOME = "$PWD\toolchain\android-sdk"
   Push-Location .\cleanyourtv\launcher
   .\gradlew.bat testReleaseUnitTest assembleRelease --no-daemon
   Pop-Location
   ```
   (Build all 8 add-ons if `GlassKit.java` or add-on resources changed.)
4. **Mirror changes back to `patches/` and regenerate the unified diff:**
   - Copy modified launcher Java/XML/test files into `./patches/`.
   - Copy modified add-on files into `./patches/addons/<addon>/...`.
   - Run:
     ```powershell
     git -C .\cleanyourtv diff --binary --output="$PWD\patches\appletv-frosted-glass.patch"
     ```
5. **Deploy & bounce-rebind Accessibility services** (installing an APK update can unbind Accessibility services on Android 14):
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> install -r .\cleanyourtv\launcher\app\build\outputs\apk\release\app-release.apk
   .\platform-tools\adb.exe -s <TV_IP> shell "settings put secure enabled_accessibility_services ''"
   Start-Sleep -Seconds 1
   .\platform-tools\adb.exe -s <TV_IP> shell "settings put secure enabled_accessibility_services 'com.gathin.tvhome/.HomeKeyService:io.github.luyzfernando08.sleeptimer/.SleepService'"
   .\platform-tools\adb.exe -s <TV_IP> shell "settings put secure accessibility_enabled 1; sync"
   ```

---

## Playbook 4 — Adding a New Translation Locale (`<lang>`)

1. **Launcher:**
   - Copy `patches/strings-en.xml` to `patches/strings-<lang>.xml` and translate all values (preserving `%1$s`, `%1$d`, `%%`, `\'`, `&amp;`).
   - Mirror to `cleanyourtv/launcher/app/src/main/res/values-<lang>/strings.xml` and add the `Copy-Item` line in `apply-setup.ps1`.
2. **All 8 Add-ons:**
   - Create `patches/addons/<addon>/app/src/main/res/values-<lang>/strings.xml` (and `schedule.xml` + `remote_picker.xml` for `sleep-timer`) using `values-cs/` or upstream `values/` as reference.
   - Include proper CLDR `<plurals>` quantities in `weather` and `world-clock`.
   - Copy into `cleanyourtv/addons/<addon>/app/src/main/res/values-<lang>/`.
3. Rebuild launcher + all 8 add-ons to verify AAPT2 resource compilation succeeds with zero format-string warnings.

---

## Playbook 5 — Debugging Glass Overlays, Edge Flicker, or Permissions

- **Edges flash/dim after opening a popup/dialog:**
  1. Verify `LIQUID_GLASS_PANEL_AGSL` satisfies `premul <= outA` everywhere.
  2. Verify the `PopupWindow` either uses a full-screen `MATCH_PARENT × MATCH_PARENT` transparent backdrop (guaranteed `winOffX == 0, winOffY == 0`) or relies on `ExpressiveGlass` / `GlassKit`'s `WindowManager.LayoutParams` Frame-0 origin resolver.
  3. For small floating popups (`MediaMiniPlayerDialog`), keep `allowHwLayer = false` in `Motion.glidePanel` so there is no `LAYER_TYPE_HARDWARE -> LAYER_TYPE_NONE` FBO swap on animation end.
- **Nested context menu refracting Home icons or shifting color while scrolling:**
  1. Ensure `Motion.glidePanel` / `GlassKit.glidePanel` invalidates `liquidTargets` on `entering || !useHwLayer` so Frame-0 RenderNodes created before `setPanelContentBlur(true)` are always re-recorded with `nestedRefraction`.
  2. Verify floating `Dialog`/`PopupWindow` coordinates use `(hostTrans - rootTrans) + winOff` (`computeScreenOrigin`) so Android 14's `transformMatrixToGlobal` window offset is never double-counted.
- **Smoked sub-menu missing Liquid Glass on Home:** Ensure `ExpressiveGlass.PanelDrawable.drawLiquidGlass` checks `MainActivity.isHomeVisible()` before returning `null` for `smoked == true`.
- **Home wallpaper leaking over Netflix/DRM video:** Confirm `MainActivity.isHomeVisible()` is `false` when external apps are foregrounded and `overlayRefraction` is cleared when overlays close.
- **Remote buttons or Sleep Timer stopped responding:** Re-run the Accessibility bounce-rebind from Playbook 3 and confirm `Crashed services:{}` in `dumpsys accessibility`.

---

## Playbook 6 — Safe Rollback to Stock Google TV

1. From PC:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\restore-all.ps1 -TvHost <TV_IP>
   ```
2. Or directly on the TV (requires local ADB enabled): **Control Center → Settings → Restore stock Google TV launcher (ADB)**.
3. Verify `com.google.android.apps.tv.launcherx` is enabled BEFORE `setupwraith`, run `sync`, and wait 15 seconds before rebooting.
