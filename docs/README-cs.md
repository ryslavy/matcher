# Matcher — Uživatelský návod pro Android TV / Google TV (CZ)

Tato příručka shrnuje ovládání, nové funkce, úpravy vzhledu i bezpečný návrat do původního stavu pro launcher **Matcher** (postavený na [CleanYourTV](https://github.com/Gathin23/cleanyourtv) s 3D optickým Liquid Glass a 8 lokalizovanými modulárními doplňky).

---

## 1. Ovládání dálkovým ovladačem (tříúrovňové mapování)

Výchozí mapa pro **Xiaomi TV Box S 3rd Gen** (upravíš v [`my-theme/config.json`](../my-theme/config.json) pod `app_keys`; kromě krátkého stisku a podržení `hold:` lze k libovolnému tlačítku přidat i dvojklik `double:` — tlačítka bez `double:` reagují okamžitě bez zpoždění):

| Tlačítko na ovladači | Krátký stisk (Tap) | Podržení ~0,5 s (`hold:`) |
|---|---|---|
| **Domů** (`HOME`) | Domovská obrazovka Matcher | **Ovládací centrum** (funguje i přes spuštěné aplikace!) |
| **Zpět** (`BACK`) | Zpět (na ploše otevře Spořič) | **Nedávné aplikace** (přepínač aplikací přes cokoliv) |
| **Aplikace** (`ALL_APPS`) | **Knihovna aplikací** (přes cokoliv) | **Ambient spořič / Aerial video** |
| **Netflix** | **Nuvio** | **Netflix** |
| **YouTube** | **TizenTube Cobalt** | **Spotify** |
| **Prime Video** | **Oneplay** | **Rychlá lišta Jasu / nočního filtru** |
| **Xiaomi TV+** | **Magenta TV** | **Rychlá lišta Časovače spánku** |
| **Mikrofon** | **Google Assistant** (nativně) | — |

> **Hraní her (Moonlight apod.):** Funkce `gamepad_passthrough` automaticky propouští vstupy z připojeného herního ovladače přímo do hry bez zásahu launcheru.

---

## 2. Hlavní funkce na TV

### Vzhled, Liquid Glass 2.0 a Material 3 Expressive blur
- **4vrstvý optický Liquid Glass 2.0** na celé domovské obrazovce (dock s živým vlněním při pohybu fokusu, horní kulaté ikony, složky, Media Mini-Player, Ovládací centrum, Knihovna aplikací, Nedávné aplikace, Objevovat, Spořič i všechna vnořená podokna a kontextová menu). Každá vrstva opticky láme přesně to, co je pod ní (kontextová menu v *All Apps* / složkách / nastavení lámou obsah rodičovského okna přes `nestedRefraction`, nad cizími streamovacími aplikacemi se přepíná čistý frosted režim bez úniku tapety).
- **Hardwarově cachovaný Material 3 Expressive blur vnořených menu:** Při otevření kontextové nabídky se podkladový panel plynule (60/120 FPS) rozostří nativním GPU Skia Gaussian + `BACKDROP_AGSL` závojem tónovaným podle aktivní tapety, zatímco vnější skleněný okraj panelu zůstává ostrý.
- **3D naklánění dlaždic a živý odlesk:** Dlaždice na ploše i ve složkách reagují 3D pružinovým náklonem, jemným pohybem a světelným odleskem (`TileSheenDrawable`).
- **4K HEVC Aerial spořič a oblíbené smyčky (`★`):** Plynulá Apple TV 4K HEVC videa s automatickým fallbackem na 1080p SDR, připínáním oblíbených scén, počasím, kartou *Právě hraje* a ochranou OLED proti vypálení.
- **Dynamické tapety podle denní doby, ročních období a svátků** (`wallpaper_moods` v [`my-theme/config.json`](../my-theme/config.json)) a volitelné **3D skleněné hodiny (iOS 26)** v *Ovládací centrum → Nastavení*.

### Složky, Knihovna aplikací a Media Mini-Player
- **Složky:** Podrž **OK** na libovolné aplikaci → *Přidat do složky* → *Nová složka*. Uvnitř složky lze kdykoliv přidávat další aplikace (*+ Přidat*), přejmenovat ji, přesouvat dlaždice nebo ji rozpustit.
- **Přesun a skrytí dlaždic:** Podrž **OK** na libovolné dlaždici → *Přesunout* nebo *Odebrat z domovské obrazovky* (aplikace zůstane dostupná v Knihovně aplikací).
- **Media Mini-Player:** Při přehrávání hudby/videa se v horní liště objeví otáčející se vinylová deska — kliknutím otevřeš kompaktní přehrávač s přepínáním zdrojů a posuvníkem času.

### Objevovat (*Discover*), Chytrá domácnost & Nastavení přes QR z mobilu
- **Objevovat** *(ikona kompasu v horní části Ovládacího centra)*:
  - **Pokračovat ve sledování (*Watch Next*)** s možností povolení oprávnění `READ_TV_LISTINGS` jedním kliknutím přes lokální ADB.
  - **Nesponzorované tipy** (TMDB / Trakt / Plex) filtrované na tvé nainstalované aplikace, včetně přímého otevírání v Nuvio.
- **Nastavení z mobilu přes QR kód** *(Nastavení → Nastavit z mobilu (QR) nebo tlačítko QR v Objevovat)*:
  - Zobrazí QR kód na dočasný lokální web na TV — z telefonu pohodlně zadáš API klíče a adresy pro **TMDB, Trakt, Plex, Home Assistant i Immich**. Po zavření dialogu se server okamžitě vypne.
- **Chytrá domácnost a živé kamery PiP (Home Assistant):** Po zadání adresy, tokenu a entit přes QR se přímo v Ovládacím centru objeví přepínače světel, zásuvek, scén i **živý plovoucí náhled kamer (`camera.*`)** přes jakoukoliv aplikaci.
- **Fotky z Immich:** Periodická synchronizace vybraného alba z vlastního Immich serveru.

### Zvuky, Air-Mouse a Ochrana proti pádu
- **Jemné UI zvuky kliknutí** a **podpora myši / Air-Mouse** na všech obrazovkách i vyskakovacích oknech.
- **Crash Watchdog** hlídá opakované pády launcheru a umí bezpečně přepnout zpět na výchozí systémový launcher.
- **Bezpečný návrat na původní Google TV** přímo z TV (*Nastavení → Obnovit původní Google TV launcher (ADB)*).

---

## 3. 8 modulárních doplňků (všechny v češtině i angličtině)

1. **Jas (`Brightness`)** — ztlumení pod minimum TV + teplý noční filtr + rychlá lišta.
2. **Časovač spánku (`Sleep Timer`)** — uspání za 15–120 min, týdenní rozvrh a výjimky, HDMI-CEC + rychlá lišta.
3. **Sluchátka (`Headphones`)** — stav baterie a automatická pauza + ztlumení TV při odpojení sluchátek.
4. **Bluetooth připojení (`Bluetooth Connect`)** — rychlé připojení/odpojení spárovaných BT zařízení.
5. **Noční zvuk (`Quiet Hours`)** — noční strop hlasitosti.
6. **Počasí (`Weather`)** — 3denní předpověď a kvalita ovzduší (Open-Meteo).
7. **Světový čas (`World Clock`)** — až 6 světových měst + volitelný spořič.
8. **Kapacita a paměť (`Capacity Stats`)** — živý přehled RAM a úložiště.

---

## 4. Kde najít zbytek zdrojového kódu a jak začít na vlastní TV / boxu

Matcher funguje jako **overlay sada patchů** nad upstream projektem [**`Gathin23/cleanyourtv`**](https://github.com/Gathin23/cleanyourtv) (ověřeno na CleanYourTV 3.6+, commit [`be60870`](https://github.com/Gathin23/cleanyourtv/commit/be6087056a1c7d4f08f3f1cc778d92374d86ef27)). Proto jsou ve složce `patches/` jen naše nové a upravené soubory — samotný Gradle projekt, základ launcheru, zdrojáky add-onů, 33+ témat a profily pro 504+ TV jsou v upstreamu a [`apply-setup.ps1`](../apply-setup.ps1) si ověřený commit stáhne automaticky.

| Co hledáš | Kde to najdeš |
|---|---|
| **Základní projekt launcheru + Gradle** | [`Gathin23/cleanyourtv/tree/main/launcher`](https://github.com/Gathin23/cleanyourtv/tree/main/launcher) |
| **Zdrojové kódy všech add-onů** | [`Gathin23/cleanyourtv/tree/main/addons`](https://github.com/Gathin23/cleanyourtv/tree/main/addons) |
| **33+ hotových témat** | [`Gathin23/cleanyourtv/tree/main/themes`](https://github.com/Gathin23/cleanyourtv/tree/main/themes) |
| **Databáze 504+ modelů TV / boxů** | [`Gathin23/cleanyourtv/tree/main/docs/devices`](https://github.com/Gathin23/cleanyourtv/tree/main/docs/devices) a [`examples/`](https://github.com/Gathin23/cleanyourtv/tree/main/examples) |
| **Návod na mapování tlačítek ovladače** | [`Gathin23/cleanyourtv/blob/main/docs/REMOTE.md`](https://github.com/Gathin23/cleanyourtv/blob/main/docs/REMOTE.md) |
| **Bezpečnostní pravidla & přepínače** | [`DOS-AND-DONTS.md`](https://github.com/Gathin23/cleanyourtv/blob/main/docs/DOS-AND-DONTS.md) a [`switches.md`](https://github.com/Gathin23/cleanyourtv/blob/main/docs/switches.md) |

### Postup pro novou TV / box
Matcher je otestovaný primárně na **Xiaomi TV Box S 3rd Gen** (`MiTV-AFMU0`, Android 14). **Každý výrobce TV má vlastní specifika** (u integrovaných TV jako Sony, TCL, Philips nebo Hisense bývají na systémové balíčky navázané HDMI vstupy, obrazové režimy nebo tuner; některé Xiaomi/Oppo firmware navíc hlídají povolené `applicationId` pro HOME).

1. **Zapni ADB ladění na TV a naklonuj Matcher na PC:**
   - Na TV: **Nastavení → Systém → O zařízení → Číslo sestavení Android TV OS** (klikni **7× OK**) → **Možnosti pro vývojáře** → zapni **Ladění USB** (případně **Bezdrátové ladění** na Androidu 14).
   - Na PC:
     ```powershell
     git clone https://github.com/ryslavy/matcher.git
     cd matcher
     ```
2. **Zjisti přesný model své TV / boxu a verzi Androidu:**
   ```powershell
   adb shell getprop ro.product.manufacturer
   adb shell getprop ro.product.model
   adb shell getprop ro.build.version.sdk
   ```
3. **Zkontroluj profil svého modelu v upstream databázi CleanYourTV** (`docs/devices/` a `DOS-AND-DONTS.md`, po spuštění skriptu i lokálně v `./cleanyourtv/docs/devices/`).
4. **Nikdy nespouštěj `my-debloat.txt` na jiném než `MiTV-AFMU0` zařízení** — pro všechny ostatní TV/boxy použij parametr `-SkipDebloat` a před zásahem si ulož seznam zapnutých balíčků (`adb shell pm list packages -e > backup\enabled.txt`).
5. **Kompatibilita podle verze Androidu a GPU:**
   * **Android 13/14+ (`SDK 33+`):** plný 3D AGSL Liquid Glass 2.0 + hardwarový Material 3 Expressive blur.
   * **Android 8–12 (`SDK 26–32`):** automatický čistý fallback bez AGSL shaderu, vše ostatní funguje stejně.
   * **Slabší GPU / 1–1,5 GB RAM TV (např. MediaTek MT5895):** v `my-theme/config.json` zapni `"reduced_motion": true` a `"glow_snap": true` a v Nastavení vypni *Liquid Glass* optiku.

---

## 5. Vypínání vizuálních prvků a rychlé příkazy z PC

Většinu efektů lze snadno vypnout nebo upravit i bez znalosti kódu (nebo o to požádat svého AI agenta — repozitář obsahuje `AGENTS.md` a `SKILL.md`):
* **Přímo v TV (*Ovládací centrum → Nastavení*):** 3D Liquid Glass hodiny, Liquid Glass optika na ploše, zvuky kliknutí, OLED ochrana spořiče, Aerial videa, region Objevovat.
* **V [`my-theme/config.json`](../my-theme/config.json) (bez kompilace):** `dock_glass`, `dock_opacity`, `cinematic_home`, `ambient_glow`, `app_glow`, `reduced_motion`, `glow_snap`, `columns`, `dock_apps`, `app_labels`, `app_keys`, vlastní tapety v `my-theme/wallpapers/`.
* **Vlastní město pro počasí/slunce:** uprav `latitude`, `longitude`, `weather_city` v [`patches/launcher-config.xml`](../patches/launcher-config.xml) (výchozí `Prague`) před buildem.

> **Důležité před prvním nasazením s debloatem na Android 14:** Na TV v **Nastavení → Síť a internet** nejdříve svou Wi-Fi **Zapomeň** a znovu se k ní připoj, aby síť nevlastnil `setupwraith`.
>
> **Bezdrátové ladění (Android 14) a macOS/Linux:** Pro Bezdrátové ladění nejprve spáruj TV přes `adb pair <IP>:<PAIR_PORT>` a pak předej aktivní port jako `-TvHost <IP>:<PORT>`. Na macOS/Linuxu fungují stejné kroky z `apply-setup.ps1` nativně přes JDK 17, `./gradlew` a `adb`.

```powershell
# Bezpečné nasazení na jakýkoliv Android TV / Google TV box (bez Xiaomi debloatu):
powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP> -SkipDebloat

# Kompletní nasazení včetně debloatu (jen Xiaomi TV Box S 3rd Gen / MiTV-AFMU0):
powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP>

# Nahrání úprav v my-theme\config.json (vlastní appky, ovladač, vizuální přepínače):
.\switch-theme.ps1 my-theme -TvHost <TV_IP>

# Vyzkoušení jiného z 30+ témat (zachová dock, názvy i tlačítka):
.\switch-theme.ps1 -List
.\switch-theme.ps1 aurora -TvHost <TV_IP>

# Kompletní návrat do původního stavu:
powershell -ExecutionPolicy Bypass -File .\restore-all.ps1 -TvHost <TV_IP>
```

Po dokončení úprav počkej **15 sekund** před restartem TV boxu a vypni síťové ADB ladění v *Možnostech pro vývojáře*, pokud ho už nepotřebuješ.
