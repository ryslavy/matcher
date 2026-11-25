# Matcher — Uživatelský návod pro Android TV / Google TV (CZ)

Tato příručka shrnuje ovládání, nové funkce, úpravy vzhledu i bezpečný návrat do původního stavu pro launcher **Matcher** (postavený na [CleanYourTV](https://github.com/Gathin23/cleanyourtv) s 3D optickým Liquid Glass a 8 lokalizovanými modulárními doplňky).

---

## 1. Ovládání dálkovým ovladačem (dvouúrovňové mapování)

Výchozí mapa pro **Xiaomi TV Box S 3rd Gen** (upravíš v [`my-theme/config.json`](../my-theme/config.json) pod `app_keys`):

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

### Vzhled a Liquid Glass
- **3D rozostřený Liquid Glass** na celé domovské obrazovce (dock, horní kulaté ikony, složky, Media Mini-Player, Ovládací centrum, Knihovna aplikací, Nedávné aplikace, Objevovat, Spořič i všechna vnořená podokna a kontextová menu). Nad streamovacími aplikacemi (Netflix, YouTube…) se automaticky přepíná na plynulý frosted režim bez úniku tapety plochy.
- **Dynamické tapety podle denní doby, ročních období a svátků** (`wallpaper_moods` v [`my-theme/config.json`](../my-theme/config.json)).
- **Volitelné 3D skleněné hodiny (iOS 26)** a **ochrana OLED proti vypálení** na spořiči — přepínače najdeš v *Ovládací centrum → Nastavení*.

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
- **Chytrá domácnost (Home Assistant):** Po zadání adresy, tokenu a entit přes QR se přímo v Ovládacím centru objeví přepínače světel, zásuvek a scén.
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

## 4. Rychlé příkazy z PC (PowerShell)

> **Důležité před prvním nasazením na Android 14:** Na TV v **Nastavení → Síť a internet** nejdříve svou Wi-Fi **Zapomeň** a znovu se k ní připoj, aby síť nevlastnil `setupwraith`.

```powershell
# Kompletní nasazení (Xiaomi TV Box S 3rd Gen):
powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP>

# Nasazení na jiný Android TV box (bez debloatu):
powershell -ExecutionPolicy Bypass -File .\apply-setup.ps1 -TvHost <TV_IP> -SkipDebloat

# Nahrání úprav v my-theme\config.json:
.\switch-theme.ps1 my-theme -TvHost <TV_IP>

# Vyzkoušení jiného z 30+ témat (zachová dock, názvy i tlačítka):
.\switch-theme.ps1 -List
.\switch-theme.ps1 aurora -TvHost <TV_IP>

# Kompletní návrat do původního stavu:
powershell -ExecutionPolicy Bypass -File .\restore-all.ps1 -TvHost <TV_IP>
```

Po dokončení úprav počkej **15 sekund** před restartem TV boxu a vypni síťové ADB ladění v *Možnostech pro vývojáře*, pokud ho už nepotřebuješ.
