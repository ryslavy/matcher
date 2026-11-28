# Accessibility Statement

## Our Commitment

**Matcher** aims to provide a clear, calm, and comfortable living-room interface for **Android TV / Google TV** users with diverse visual, motor, cognitive, and language needs.

---

## Built-In Accessibility Features

### 1. Visual Clarity, Contrast & Motion Comfort
- **6 Glass Styles (`GlassStyle`):** Switch live in *Launcher Settings → Glass style* between **Pure Black (OLED)** (maximum dark-mode contrast), **Frost White** (light-mode inversion with dark graphite typography, dark icons, and a dark focus ring), **Frosted Tonal**, **Clear Crystal**, **Chromatic Tint**, and **Auto (Day / Night)** — applied consistently across Home, Dock, overlays, and all 8 modular add-ons.
- **Adjustable Frost & Blur Controls:** Independently toggle **Home backdrop blur**, **Nested menu blur**, **Dock ambient glow**, **Liquid Glass optics** (disables lens refraction globally across the launcher, all 8 add-ons, and floating quick bars), and **Dock bar opacity (`30%–100%`)** to maximize legibility or reduce visual complexity.
- **Reduced Motion & Calm Transitions:** Disable *Animations* or *Advanced effects* in *Launcher Settings*, or set `"reduced_motion": true` and `"glow_snap": true` in `my-theme/config.json` to minimize tile tilt, sway, and animated color sweeps.
- **Night-Time Comfort Add-ons:** The optional **Brightness** add-on dims screens below the TV's hardware minimum with a warm blue-light filter, and **Quiet Hours** caps maximum night-time speaker volume to prevent startling audio spikes.

### 2. Flexible Navigation & Input Ergonomics
- **100% Remote / D-Pad Navigable:** Every screen, overlay, modal, folder, context menu, and all 8 modular add-ons can be operated completely with a standard 5-way TV remote D-pad and Back button, accompanied by a clear animated focus highlight (`FocusRingController`) and optional subtle **UI click sounds**.
- **Pointer / Air-Mouse & Gamepad Support:** Full hover and click support (`PointerSupport`) for air-mouse remotes and USB/Bluetooth mice across all screens and popups, plus automatic **Gamepad Passthrough** for game controllers.
- **Customizable Shortcut Mapping:** Map any physical shortcut key to **Tap**, **Double-Tap**, or **Hold** right on the TV (*Launcher Settings → Remote buttons → Learn remote button*) to reduce repetitive navigation steps.
- **Phone QR Web Editor:** Configure settings, remote keys, custom app labels, weather location, and wallpapers from a phone browser or assistive keyboard rather than typing with an on-screen TV keyboard.

### 3. Multilingual & Right-to-Left (RTL) Support
- **31 Languages Across Launcher & All 8 Add-ons:** Complete localized UI across 31 locales, including right-to-left (RTL) layouts (`layout-iw/`) for **Arabic (`ar`)** and **Hebrew (`iw`)**.

### 4. Note on Android Accessibility Services
- Matcher's `HomeKeyService` and the optional Sleep Timer's `SleepService` use Android's `AccessibilityService` API solely to intercept remote shortcut buttons and manage scheduled standby. They do not collect screen content and are designed to coexist alongside system accessibility tools.

---

## Supported Environments

- **Primary tested environment:** Android TV / Google TV on **Android 13 / 14+** (`SDK 33+`), 1080p and 4K displays, 5-way D-pad remotes and air-mouse pointers.
- **Broader compatibility:** Android **8.0 – 12** (`SDK 26–32`) with automatic frosted fallback (no AGSL shader requirement) across all CPU architectures (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).

---

## Known Limitations

- **Screen Readers (TalkBack):** Standard Android focus navigation works across lists and controls, but custom Canvas/AGSL views (such as the optional beveled `LiquidClockView`, media seek bar, and custom focusable tiles) have not yet undergone a full end-to-end TalkBack audit and may have incomplete spoken `contentDescription` labels.
- **System Font Scaling:** TV layouts are tuned primarily for living-room 1080p/4K density; extreme Android system font-scale overrides may clip some compact labels or multi-row tile titles.

---

## Feedback & Reporting Accessibility Barriers

If you encounter any accessibility barrier, unreadable contrast combination, missing focus indicator, or missing screen-reader label:

1. Open an issue at **[github.com/ryslavy/matcher/issues/new/choose](https://github.com/ryslavy/matcher/issues/new/choose)** (Bug Report or Feature Request).
2. Mention your TV/box model, Android version, active **Glass style** / blur settings, and the screen or assistive tool you were using.

Community contributions improving contrast, TalkBack `contentDescription` attributes, RTL layouts, or translations are always warmly welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md).
