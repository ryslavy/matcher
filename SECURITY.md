# Security Policy

## Supported Versions

Security fixes and signed APK updates are provided for the latest minor release line:

| Version | Supported | Notes |
|---|---|---|
| **`v1.2.x`** (`v1.2.1`+) | ✅ Yes | Active release line with universal signed APKs, OTA updates & SHA-256 verification |
| `< v1.2.0` | ❌ No | Please upgrade to the latest `v1.2.x` release |

---

## Verifying Official Release APKs

All official APKs published on [GitHub Releases](https://github.com/ryslavy/matcher/releases) (`matcher-launcher-v*.apk` and `matcher-addon-*-v*.apk`) are:

1. **Signed with Matcher's stable release key:**
   - **Certificate SHA-256 fingerprint:**
     ```text
     F2:0C:79:FD:4B:22:67:CB:3D:6A:6F:0A:A4:20:75:97:73:C7:7E:0D:BB:FC:78:40:C5:C8:DB:17:74:34:E6:8E
     ```
2. **Accompanied by `SHA256SUMS.txt` and `manifest.json`** for independent integrity verification before installation (`apksigner verify --print-certs <file>.apk` and `sha256sum -c SHA256SUMS.txt`).

---

## Security & Privacy Design

- **Zero Telemetry / Zero Ads:** Matcher collects no analytics, crash telemetry, usage tracking, or sponsored home-screen content.
- **Verified In-App OTA Updates (`SelfUpdater`):** Enforces GitHub Release HTTPS domains, validates SHA-256 checksums, verifies strictly monotonic `versionCode`, and checks signing certificate match before passing APKs to Android's `PackageInstaller`.
- **Safe Web Wallpapers (`PicsumWallpapers`):** Enforces HTTPS-only redirect validation and JPEG/PNG/WebP magic-byte verification before caching downloaded images.
- **Ephemeral LAN Phone QR Server (`QrConfigServer`):** Listens on your local network **only** while the Phone QR setup dialog is open on your TV and shuts down automatically when the dialog closes or after a 5-minute timeout.
- **Elevated Permissions Are Strictly Local & Opt-In:** Permissions such as `WRITE_SECURE_SETTINGS`, `HomeKeyService` (Accessibility), `GET_USAGE_STATS`, `READ_TV_LISTINGS`, and loopback `LocalAdbClient` operate exclusively on your TV to handle remote shortcuts, Watch Next, Recents, and safe launcher recovery.
- **ADB Hygiene:** After finishing ADB setup or updates, we strongly recommend disabling **Network / Wireless ADB debugging** in your TV's *Developer options*.

---

## Reporting a Vulnerability

Please **do not** report security vulnerabilities through public GitHub issues.

Instead, report vulnerabilities privately via **[GitHub Private Vulnerability Reporting](https://github.com/ryslavy/matcher/security/advisories/new)** on `ryslavy/matcher`.

When reporting, please include as much of the following as possible:

- Affected Matcher version (e.g., `v1.2.1`), Android TV / Google TV OS version, and device model
- Component involved (e.g., `SelfUpdater`, `QrConfigServer`, `PicsumWallpapers`, `LocalAdbClient`, `HomeKeyService`, `apply-setup.ps1`, or a specific add-on)
- Clear reproduction steps or a minimal proof of concept
- Potential impact and any suggested remediation

### Response Expectations

- **Initial acknowledgment:** Within **5 business days**
- **Triage & remediation plan:** Once reproduced, we will work on a fix, coordinate disclosure, and ship a signed patch release via GitHub Releases.
- **Upstream vulnerabilities:** If a security issue originates in upstream [`Gathin23/cleanyourtv`](https://github.com/Gathin23/cleanyourtv), please also consider reporting it according to upstream's [`SECURITY.md`](https://github.com/Gathin23/cleanyourtv/blob/main/SECURITY.md).
