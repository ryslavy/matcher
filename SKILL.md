---
name: matcher-tv-customization
description: Build, customize, translate, deploy, debug, and safely roll back the Matcher Liquid Glass Android TV / Google TV launcher and its 8 modular add-ons. Use whenever working in this repository to modify themes, remote keymaps, Liquid Glass shaders, launcher features, add-on locales, or TV box deployments via ADB.
---

# Matcher TV Customization Skill

Use this skill when assisting a user with deploying, customizing, extending, translating, debugging, or restoring **Matcher** on an Android TV / Google TV box.

Always read [`AGENTS.md`](AGENTS.md) first for architecture invariants and safety constraints.

---

## Playbook 1 — Safe Initial Deployment to a TV Box

1. **Confirm the TV's ADB target (`<TV_IP>` or `<TV_IP>:<PORT>`).**
   Verify connectivity:
   ```powershell
   .\platform-tools\adb.exe connect <TV_IP>
   .\platform-tools\adb.exe -s <TV_IP> get-state
   ```
2. **CRITICAL Wi-Fi ownership check (Android 14 / Google TV) before any debloat:**
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "dumpsys wifi | grep -E 'cname=|creatorName'"
   ```
   - If output mentions `setupwraith`, **STOP** and ask the user to **Forget** and **Reconnect** Wi-Fi in TV Settings → Network & Internet first.
   - If deploying to a non-Xiaomi Android TV box (or user prefers no debloat), pass `-SkipDebloat`.
3. **Run the automated installer:**
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP>
   ```
4. **Verify Accessibility services & disk flush:**
   ```powershell
   .\platform-tools\adb.exe -s <TV_IP> shell "dumpsys accessibility | grep -E 'Bound services|Service\[label=|Crashed'"
   .\platform-tools\adb.exe -s <TV_IP> shell "sync"
   ```
   Confirm both `HomeKeyService` and `SleepService` are bound and `Crashed services:{}` is empty. Remind the user to wait 15 seconds before rebooting.

---

## Playbook 2 — Customizing Theme, Dock, Wallpapers & Remote Keymap

1. Edit [`my-theme/config.json`](my-theme/config.json):
   - `dock_apps`: ordered array of package names pinned to the main glass dock.
   - `app_labels`: `"package.name=Custom Label"` overrides.
   - `app_keys`: tap and `hold:` actions by Android keycode (`KEYCODE_BUTTON_6:...`) and/or Linux scancode (`scan:240:...`). See [`presets/xiaomi-tv-box-s-3rd-gen/remote-keymap.md`](presets/xiaomi-tv-box-s-3rd-gen/remote-keymap.md).
   - `wallpaper_moods`: map of time-of-day / seasonal / holiday moods (`morning`, `evening`, `night`, `winter_night`, `christmas_evening`, etc.) to filenames in `my-theme/wallpapers/`.
2. Push instantly without recompiling:
   ```powershell
   .\switch-theme.ps1 my-theme -TvHost <TV_IP>
   ```
3. To try any upstream visual theme while keeping the user's dock & keymap:
   ```powershell
   .\switch-theme.ps1 -List
   .\switch-theme.ps1 <theme_name> -TvHost <TV_IP>
   ```

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
