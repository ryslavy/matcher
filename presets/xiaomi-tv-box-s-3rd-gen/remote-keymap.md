# Xiaomi TV Box S 3rd Gen (`MiTV-AFMU0`) — Remote Keymap Reference

Reference mapping for `/vendor/usr/keylayout/Vendor_2717_Product_32b9.kl` configured in [`my-theme/config.json`](../../my-theme/config.json) via `HomeKeyService` (`app_keys`).

| Physical Remote Key | Linux Scancode(s) | Android Keycode | Default Short Press (Tap) | Default Long Press (~0.5 s `hold:`) |
|---|---|---|---|---|
| **Netflix** | `240`, `416` | `KEYCODE_BUTTON_6` | Nuvio (`com.nuvio.tv`) | Netflix (`com.netflix.ninja`) |
| **YouTube** | `425`, `393` | `KEYCODE_BUTTON_3` | TizenTube Cobalt (`io.gh.reisxd.tizentube.cobalt`) | Spotify (`com.spotify.tv.android`) |
| **Prime Video** | `207` | `KEYCODE_BUTTON_7` | Oneplay (`net.cme.voyo.cz.tvapp`) | Brightness Quick Bar (`io.github.maxhayim.brightness/.QuickActivity`) |
| **Xiaomi TV+** | `150`, `400` | `KEYCODE_BUTTON_5` | Magenta TV (`cz.tmobile.tvgo`) | Sleep Timer Quick Bar (`io.github.luyzfernando08.sleeptimer/.QuickActivity`) |
| **Apps** | `216` | `KEYCODE_ALL_APPS` | App Library overlay (`library`) | Ambient Screensaver (`screensaver`) |
| **Back** | `158` | `KEYCODE_BACK` | Standard Back | Recents App Switcher overlay (0.5 s) |
| **Home** | `172`, `102` | `KEYCODE_HOME` | Matcher Home | Control Center overlay (0.55 s) |
| **Microphone** | `582`, `144` | `KEYCODE_ASSIST` | Google Assistant | — |

## Finding Scancodes on Another TV Box

To map custom remote buttons on a different Android TV / Google TV device:

```bash
adb shell getevent -lt
```

Press each shortcut key on your remote, note its `KEY_*` or hex/decimal scancode, and check `dumpsys input` or `/vendor/usr/keylayout/*.kl` for the matching Android `KEYCODE_*`. Then update `app_keys` in [`my-theme/config.json`](../../my-theme/config.json) and push the theme:

```powershell
.\switch-theme.ps1 my-theme -TvHost <TV_IP>
```
