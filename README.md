# Matcher — Liquid Glass Android TV / Google TV Experience

**Matcher** is an ad-free, Apple tvOS 26–inspired Liquid Glass launcher, modular add-on suite, and automated deployment kit for **Android TV / Google TV** (Android 13/14+, built on top of [CleanYourTV](https://github.com/Gathin23/cleanyourtv)).

Designed to run smoothly even on 2 GB RAM streaming boxes (reference device: **Xiaomi TV Box S 3rd Gen**, `MiTV-AFMU0`, Android 14 / SDK 34), Matcher turns Google TV into a fast, clutter-free living-room interface with optical frosted glass refraction, system-wide overlays, smart-home controls, and zero sponsored home-screen ads.

> **Documentation hub:**
> - **Users (Czech guide):** [`docs/README-cs.md`](docs/README-cs.md)
> - **Contributors & Translators:** [`CONTRIBUTING.md`](CONTRIBUTING.md)
> - **AI Coding Agents:** [`AGENTS.md`](AGENTS.md) and [`SKILL.md`](SKILL.md)

---

## Highlights & Features

### 1. Real-Time 3D Optical Liquid Glass Engine
- **Home & Screensaver Optics:** Custom Android 13/14 AGSL `RuntimeShader` (`ExpressiveGlass`) renders smoothly frosted 3D curved meniscus refraction with RGB prism dispersion and directional Fresnel highlights across the Dock, top circular buttons, Folders, Media Mini-Player, Control Center, App Library, Recents, Discover, Screensaver Now Playing card, and every nested sub-menu/picker.
- **Smart Context Adaptation:** Automatically switches to smooth frosted glass over external video/DRM apps (Netflix, YouTube, Disney+, etc.) so Home wallpaper colors never leak onto live playback.
- **Optional iOS 26 Beveled Clock (`LiquidClockView`):** Toggleable in *Settings → Liquid Glass clock*; defaults to crisp luminous typography.
- **Living Fresnel Focus Ring:** Spring-damped wobbly glass focus highlight that dynamically adapts to tile/app colors.

### 2. Apple tvOS-Style System Overlays (Accessible Over Any App)
- **Control Center** *(Hold Home ~0.55 s or Gear button)*: Media controls, Brightness, Sleep Timer, Quiet Hours, Good Night scene, Bluetooth / Headphones, Aerial Video Screensaver, Home Assistant smart-home grid, and direct jumps to App Library, Discover, Recents, Settings, and Standby.
- **App Library** *(Apps key)*: Full sortable grid of all installed apps, keeping the Home screen clean and minimal.
- **App Switcher / Recents** *(Hold Back ~0.5 s)*: Horizontal card carousel with swipe/OK app switching and force-stop management.
- **Folders** *(Hold OK on any tile → Add to folder)*: Multi-app glass folders with instant *Add apps*, *Rename*, *Reorder*, *Move to another folder*, and *Dissolve*.
- **Media Mini-Player** *(Spinning Vinyl disc in top bar)*: Multi-session switcher, album art, live scrubbable seek bar, and zero-flicker opening.
- **Ambient & 1080p Aerial Video Screensaver:** Live time/date, Open-Meteo local weather + sunrise/sunset, Now Playing card, optional OLED pixel drift, and one-press launch.

### 3. Discover, Smart Home, Photos & Phone QR Setup
- **Discover Overlay** *(Compass icon in Control Center)*:
  - **Continue Watching:** Reads Android TV Watch Next & preview channels (with 1-click local ADB `READ_TV_LISTINGS` grant right from the TV).
  - **Unsponsored Trending Picks:** TMDB + Trakt + Plex filtered to your installed apps, with region selector (`CZ/SK/DE/GB/US`), animation filter, and optional direct `nuvio://tmdb/...` deep-linking.
- **Configure From Your Phone via QR Code:** Spins up an ephemeral local LAN web UI with a scannable QR code on TV so you can paste TMDB, Trakt, Plex, Home Assistant, and Immich credentials from your phone instead of typing on a TV remote. The server shuts down immediately when the dialog closes.
- **Home Assistant Integration:** Fast toggle tiles for lights, switches, and scenes directly inside Control Center.
- **Immich Photo Sync:** Periodically syncs a chosen album from your self-hosted Immich server for wallpapers/screensaver.

### 4. Ergonomics, Safety & Updates
- **Two-Level Remote Remapping (`HomeKeyService`):** Separate Tap and Long-Press (~0.5 s) actions for every shortcut button, plus automatic **Gamepad Passthrough** for Moonlight / game streaming.
- **Soft UI Click Sounds & Air-Mouse / Pointer Support** across every screen, popup, and dialog.
- **Crash-Loop Recovery Watchdog** (`CrashWatchdog`) & **1-Click Stock Launcher Restore** via loopback ADB (`LocalAdbClient`) respecting Android 14 safe restore ordering.
- **GitHub Releases Update Checker** (`SelfUpdater`).

### 5. 8 Modular Glass Add-ons (Localized in English & Czech)
All 8 add-ons share [`GlassKit.java`](patches/addons/brightness/app/src/main/java/io/github/maxhayim/brightness/GlassKit.java) (`Theme.GlassOverlay` + 3D Liquid Glass + spring focus ring):
1. **Brightness** — Dim screen below TV hardware minimum + warm night filter + floating quick bar.
2. **Sleep Timer** — 15–120 min timer, weekly wake/sleep schedule, exclusion dates, HDMI-CEC standby + quick bar.
3. **Headphones** — Battery level + auto-pause & mute TV speakers when Bluetooth headphones disconnect.
4. **Bluetooth Connect** — 1-click connect/disconnect for paired BT audio devices.
5. **Quiet Hours** — Scheduled night-time volume cap.
6. **Weather** — 3-day Open-Meteo forecast & air quality index.
7. **World Clock** — Up to 6 world clocks + optional screensaver.
8. **Capacity Stats** — Live RAM and storage breakdown.

---

## Languages & Translations

| Component | Languages Included | Notes |
|---|---|---|
| **Launcher (`com.gathin.tvhome`)** | **31 locales** from upstream + complete **English (`values`)** and **Czech (`values-cs`)** for all Matcher features | All other 29 locales automatically fall back to English for Matcher-specific strings |
| **8 Modular Add-ons** | **English (`values`)** + **Czech (`values-cs`)** (+ partial upstream IT/DE/FR/ES/PT/RU/ZH-TW/KO/TR) | Adding a new language takes ~5 minutes — see [`CONTRIBUTING.md`](CONTRIBUTING.md#adding-or-improving-translations) |

---

## Quick Start (Windows PowerShell + ADB)

### Prerequisites
- Windows 10/11 with **Git** and **Python 3** installed (`apply-setup.ps1` automatically downloads portable **Microsoft OpenJDK 17**, **Android SDK 34**, **ADB Platform-Tools**, and clones upstream `Gathin23/cleanyourtv`).
- Android TV / Google TV device on the same LAN with **Developer Options → USB / Wireless Debugging** enabled.

### Critical Pre-Flight Check (Android 14 / Google TV!)
> **WARNING — Do NOT skip this if running debloat!**
> On Android 14 Google TV boxes, the initial setup wizard (`com.google.android.tungsten.setupwraith`) owns your Wi-Fi profile. Disabling `setupwraith` while it still owns the Wi-Fi network causes Android 14 to **immediately delete your Wi-Fi credentials and drop ADB**.
>
> **Before running `apply-setup.ps1`:** On your TV, go to **Settings → Network & Internet → your Wi-Fi → Forget**, then reconnect to it once. (`apply-setup.ps1` automatically checks `dumpsys wifi` and aborts safely if `setupwraith` still owns the network.)

### Deploy Everything in One Command

**Option A — Xiaomi TV Box S 3rd Gen (full debloat + launcher + 8 add-ons + theme):**
```powershell
powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP>
```

**Option B — Any other Android TV / Google TV box (skip Xiaomi debloat list):**
```powershell
powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP> -SkipDebloat
```

*(If using Android 14 Wireless Debugging on a dynamic port, pass `-TvHost <TV_IP>:<PORT>`.)*

After deployment finishes, **wait 15 seconds before rebooting** so Android flushes package states to disk.

---

## Everyday Customization

### 1. Edit Dock, Wallpapers, App Names & Remote Buttons
Edit [`my-theme/config.json`](my-theme/config.json) (and optionally drop your own 16:9 JPGs into `my-theme/wallpapers/`), then push changes instantly without rebuilding APKs:
```powershell
.\switch-theme.ps1 my-theme -TvHost <TV_IP>
```

### 2. Try 30+ Upstream Visual Themes (Keeping Your Dock & Remote Keys)
```powershell
.\switch-theme.ps1 -List
.\switch-theme.ps1 aurora -TvHost <TV_IP>
.\switch-theme.ps1 my-theme -TvHost <TV_IP>
```

### 3. Set Your Local Weather City & Sun Coordinates
Before building, update `latitude`, `longitude`, and `weather_city` in [`patches/launcher-config.xml`](patches/launcher-config.xml) (lines 503–505, defaults to `Prague`), then rebuild the launcher.

### 4. Configure TMDB / Trakt / Plex / Home Assistant / Immich
On the TV, open **Control Center → Settings → Configure keys & servers via Phone QR**, scan the QR code with your phone, and hit Save.

### 5. Safe Full Rollback to Stock Google TV
```powershell
powershell -ExecutionPolicy Bypass -File .\restore-all.ps1 -TvHost <TV_IP>
```
Or directly on the TV without a PC: **Control Center → Settings → Restore stock Google TV launcher (ADB)**.

---

## Repository Layout

```text
matcher/
├── README.md                                  # Project overview & quick start (this file)
├── AGENTS.md                                  # Architecture, invariants & commands for AI agents
├── CONTRIBUTING.md                            # Developer workflow, Glass rules & translation guide
├── SKILL.md                                   # Reusable AI Agent Skill playbooks
├── LICENSE                                    # MIT License
├── apply-setup.ps1                            # Full 1-command bootstrap, patch, build & deploy
├── switch-theme.ps1                           # Switch themes while preserving dock & remote keys
├── restore-all.ps1                            # Safe rollback to stock Google TV launcher
├── my-debloat.txt                             # Reference safe debloat list (Xiaomi TV Box S 3rd Gen)
├── my-theme/                                  # Active theme config.json + seasonal/mood wallpapers
├── presets/xiaomi-tv-box-s-3rd-gen/           # Device preset (debloat list & remote keymap)
├── docs/README-cs.md                          # Czech user & remote-control manual
└── patches/                                   # All launcher & add-on source/resource overrides
    ├── *.java / *.xml                         # Launcher Java classes, layouts, drawables, EN+CS strings
    ├── *Test.java                             # 366 JVM unit tests
    ├── appletv-frosted-glass.patch            # Unified git diff against upstream cleanyourtv
    └── addons/                                # GlassKit, manifests, styles & EN+CS strings for 8 add-ons
```

---

## Credits & License
Built on top of [`Gathin23/cleanyourtv`](https://github.com/Gathin23/cleanyourtv) and its modular add-on ecosystem, released under the **MIT License** ([`LICENSE`](LICENSE)).
