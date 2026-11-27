package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Pure-JVM unit tests covering optical Liquid Glass clock height/slope map synthesis,
 * private-LAN URL guardrails, QR matrix encoding & form parsing, UI WAV synthesis,
 * Home Assistant safety allowlists, Watch Next formatting, TMDB/Oneplay/Nuvio deep-links,
 * CrashWatchdog 3-in-5-minute recovery threshold, and GitHub SelfUpdater verification.
 */
public class NewFeaturesTest {

    @Test
    public void liquidClockSlopeMapProducesValidPackedNormalsAndShadow() {
        int w = 48, h = 24;
        int[] argb = new int[w * h];
        // Filled rounded glyph block in the middle
        for (int y = 5; y < 19; y++) {
            for (int x = 10; x < 38; x++) {
                argb[y * w + x] = 0xFFFFFFFF;
            }
        }
        int[] slopePx = new int[w * h];
        int[] shadowPx = new int[w * h];
        LiquidClockView.computeSlopeAndShadow(argb, w, h, 3, 5, slopePx, shadowPx);

        // Interior center should have high height (B channel > 180) and near-flat slope (R,G ~ 128)
        int center = slopePx[12 * w + 24];
        int centerR = (center >>> 16) & 0xFF;
        int centerG = (center >>> 8) & 0xFF;
        int centerB = center & 0xFF;
        assertTrue("Interior height should be high: " + centerB, centerB > 180);
        assertTrue("Interior X slope should be near neutral: " + centerR, Math.abs(centerR - 128) < 25);
        assertTrue("Interior Y slope should be near neutral: " + centerG, Math.abs(centerG - 128) < 25);

        // Left bevel edge should have non-neutral X normal, and outside drop-shadow below glyph > 0
        int leftEdge = slopePx[12 * w + 10];
        int leftR = (leftEdge >>> 16) & 0xFF;
        assertNotEquals("Left bevel edge must have non-neutral X normal", 128, leftR);
        int belowShadow = (shadowPx[20 * w + 24] >>> 24) & 0xFF;
        assertTrue("Drop shadow below glyph should be visible: " + belowShadow, belowShadow > 0);
    }

    @Test
    public void featurePrefsUrlGuardAllowsHttpsAndPrivateLanHttpOnly() {
        assertTrue(FeaturePrefs.isAllowedServerUrl("https://ha.example.com"));
        assertTrue(FeaturePrefs.isAllowedServerUrl("http://192.168.1.50:8123"));
        assertTrue(FeaturePrefs.isAllowedServerUrl("http://10.0.0.5:2283"));
        assertTrue(FeaturePrefs.isAllowedServerUrl("http://172.16.4.12:32400"));
        assertTrue(FeaturePrefs.isAllowedServerUrl("http://homeassistant.local:8123"));

        // Plain HTTP to public domains or public IPs MUST be rejected
        assertFalse(FeaturePrefs.isAllowedServerUrl("http://example.com:8123"));
        assertFalse(FeaturePrefs.isAllowedServerUrl("http://8.8.8.8:8123"));
        assertFalse(FeaturePrefs.isAllowedServerUrl("http://172.32.0.1:8123"));
        assertFalse(FeaturePrefs.isAllowedServerUrl("ftp://192.168.1.50"));
        assertFalse(FeaturePrefs.isAllowedServerUrl("javascript:alert(1)"));
        assertFalse(FeaturePrefs.isAllowedServerUrl(""));
    }

    @Test
    public void qrBitmapEncodesValidSquareMatrixWithFinderPatterns() {
        boolean[][] m = QrBitmap.encode("http://192.0.2.10:49152/AbCdEfGhIjKl");
        assertTrue("QR size must be >= 21", m.length >= 21);
        assertEquals("QR matrix must be square", m.length, m[0].length);
        // Top-left 7x7 finder corners & center must be dark
        assertTrue(m[0][0]);
        assertTrue(m[0][6]);
        assertTrue(m[6][0]);
        assertTrue(m[3][3]);
        assertFalse(m[1][1]);
        // Top-right and bottom-left finder centers must be dark
        int s = m.length;
        assertTrue(m[3][s - 4]);
        assertTrue(m[s - 4][3]);
    }

    @Test
    public void qrConfigFormParserDecodesUtf8UrlEncodedValues() {
        Map<String, String> parsed = QrConfigServer.parseForm(
                "ha_url=http%3A%2F%2F192.168.1.5%3A8123&ha_entities=light.obyvak%2C+switch.tv&tmdb_region=CZ");
        assertEquals("http://192.168.1.5:8123", parsed.get("ha_url"));
        assertEquals("light.obyvak, switch.tv", parsed.get("ha_entities"));
        assertEquals("CZ", parsed.get("tmdb_region"));
    }

    @Test
    public void uiClickSoundSynthesizesValidRiffWaveHeader() {
        byte[] wav = UiClickSound.synthesizeWavBytes(1420f, 0.0085f, 0.028f);
        assertTrue("WAV must contain header + PCM data", wav.length > 100);
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);
    }

    @Test
    public void homeAssistantBlocksDangerousDomainsAndGarageCovers() {
        String raw = "light.living_room, lock.front_door, alarm_control_panel.home, "
                + "switch.tv_led, cover.bedroom_blinds, scene.movie_night, climate.heater, "
                + "fan.ceiling, script.goodnight, automation.lights, button.reboot, "
                + "input_boolean.guest, light.extra1, light.extra2";
        List<HomeAssistantClient.Control> parsed = HomeAssistantClient.parseEntities(raw);
        assertEquals("Should cap at MAX_CONTROLS (8)", HomeAssistantClient.MAX_CONTROLS, parsed.size());
        for (HomeAssistantClient.Control c : parsed) {
            assertFalse("Locks must never be allowed", c.entityId.startsWith("lock."));
            assertFalse("Alarms must never be allowed", c.entityId.startsWith("alarm_control_panel."));
            assertFalse("Climate must not be allowed", c.entityId.startsWith("climate."));
        }
        assertTrue(HomeAssistantClient.isCoverClassAllowed("blind"));
        assertTrue(HomeAssistantClient.isCoverClassAllowed("curtain"));
        assertTrue(HomeAssistantClient.isCoverClassAllowed("shade"));
        assertTrue(HomeAssistantClient.isCoverClassAllowed("shutter"));
        assertFalse("Garage doors must be blocked", HomeAssistantClient.isCoverClassAllowed("garage"));
        assertFalse("Gates must be blocked", HomeAssistantClient.isCoverClassAllowed("gate"));
        assertFalse("Unclassified covers must be blocked", HomeAssistantClient.isCoverClassAllowed(""));
        assertFalse("Null cover class must be blocked", HomeAssistantClient.isCoverClassAllowed(null));

        // Read-only camera.* entities are allowed for live PiP preview
        List<HomeAssistantClient.Control> camParsed = HomeAssistantClient.parseEntities(
                "camera.front_door, lock.front_door, camera.garden");
        assertEquals(2, camParsed.size());
        assertTrue(camParsed.get(0).isCamera());
        assertTrue(camParsed.get(1).isCamera());
        assertEquals("camera.front_door", camParsed.get(0).entityId);
    }

    @Test
    public void watchNextFormatsProgressAndEpisodeCleanly() {
        assertEquals(-1f, WatchNextRepository.computeProgress(0, 3600_000L), 0.001f);
        assertEquals(-1f, WatchNextRepository.computeProgress(500, 3600_000L), 0.001f);
        assertEquals(0.5f, WatchNextRepository.computeProgress(1800_000L, 3600_000L), 0.001f);
        assertEquals("S2 E5", WatchNextRepository.formatEpisodeLabel("2", "5", "Pilot", "Severance"));
        assertEquals("Pilot", WatchNextRepository.formatEpisodeLabel(null, null, "Pilot", "Severance"));
        assertNull(WatchNextRepository.formatEpisodeLabel(null, null, "Severance", "Severance"));
        assertEquals("com.netflix.ninja|Dark", WatchNextRepository.buildHideKey("com.netflix.ninja", "Dark"));
    }

    @Test
    public void trendingPicksMatchesCzechOneplayAndBuildsNuvioDeepLink() {
        assertEquals("nuvio://tmdb/movie/603", TrendingPicksRepository.buildNuvioDeepLink("movie", 603));
        assertEquals("nuvio://tmdb/tv/1396", TrendingPicksRepository.buildNuvioDeepLink("tv", 1396));
        assertNull(TrendingPicksRepository.buildNuvioDeepLink("movie", 0));

        Set<String> installed = new HashSet<>(Arrays.asList(
                "net.cme.voyo.cz.tvapp", "com.netflix.ninja", "com.wbd.stream"));
        String[] oneplay = TrendingPicksRepository.matchInstalledService("Oneplay", installed);
        assertNotNull(oneplay);
        assertEquals("Oneplay", oneplay[0]);
        assertEquals("net.cme.voyo.cz.tvapp", oneplay[1]);

        String[] voyo = TrendingPicksRepository.matchInstalledService("Voyo CZ", installed);
        assertNotNull(voyo);
        assertEquals("net.cme.voyo.cz.tvapp", voyo[1]);

        assertNull("Uninstalled Disney+ must not match",
                TrendingPicksRepository.matchInstalledService("Disney Plus", installed));
    }

    @Test
    public void crashWatchdogTriggersOnlyOnThreeCrashesWithinFiveMinutes() {
        List<Long> stamps = new ArrayList<>();
        long t0 = 1_000_000L;
        assertFalse(CrashWatchdog.recordAndShouldRecover(stamps, t0));
        assertFalse(CrashWatchdog.recordAndShouldRecover(stamps, t0 + 120_000L));
        // 3rd crash 6 minutes after first -> t0 pruned, only [t0+120s, t0+360s] remain -> must NOT trigger
        assertFalse(CrashWatchdog.recordAndShouldRecover(stamps, t0 + 360_000L));
        assertEquals(2, stamps.size());
        // 4th crash 10s later -> all 3 [t0+120s, t0+360s, t0+370s] are within 5 minutes -> MUST trigger
        assertTrue(CrashWatchdog.recordAndShouldRecover(stamps, t0 + 370_000L));
        assertEquals(3, stamps.size());
    }

    @Test
    public void selfUpdaterValidatesSemverSha256AndReleaseDomain() {
        assertTrue(SelfUpdater.compareVersions("v2.1.0", "2.0.9") > 0);
        assertTrue(SelfUpdater.compareVersions("2.0.10", "2.0.9") > 0);
        assertEquals(0, SelfUpdater.compareVersions("v1.4.2", "1.4.2"));
        assertTrue(SelfUpdater.compareVersions("1.3.9", "1.4.0") < 0);

        String repo = "ryslavy/mitv-box-s-3rd-gen-setup";
        assertTrue(SelfUpdater.isValidReleaseDownloadUrl(repo,
                "https://github.com/ryslavy/mitv-box-s-3rd-gen-setup/releases/download/v2.0/tvhome.apk"));
        assertFalse(SelfUpdater.isValidReleaseDownloadUrl(repo,
                "https://evil.example.com/ryslavy/mitv-box-s-3rd-gen-setup/releases/download/v2.0/tvhome.apk"));
        assertFalse(SelfUpdater.isValidReleaseDownloadUrl(repo,
                "https://github.com/ryslavy/mitv-box-s-3rd-gen-setup/releases/download/v2.0/readme.txt"));

        // SHA-256 of empty byte array
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SelfUpdater.sha256Hex(new byte[0]));
    }

    @Test
    public void featurePrefsDefinesDistinctHomeBackdropBlurKey() {
        assertEquals("home_backdrop_blur", FeaturePrefs.KEY_HOME_BACKDROP_BLUR);
        assertNotEquals(FeaturePrefs.KEY_HOME_LIQUID_OPTICS, FeaturePrefs.KEY_HOME_BACKDROP_BLUR);
        assertNotEquals(FeaturePrefs.KEY_LIQUID_CLOCK, FeaturePrefs.KEY_HOME_BACKDROP_BLUR);
        assertEquals("glass_material", FeaturePrefs.KEY_GLASS_MATERIAL);
        Set<String> styles = new HashSet<>(Arrays.asList(
                FeaturePrefs.GLASS_TONAL, FeaturePrefs.GLASS_OBSIDIAN, FeaturePrefs.GLASS_FROST,
                FeaturePrefs.GLASS_CLEAR, FeaturePrefs.GLASS_CHROMATIC, FeaturePrefs.GLASS_AUTO));
        assertEquals("All 6 glass material keys must be distinct", 6, styles.size());
    }

    @Test
    public void remoteMappingsRoundTripAndExpandWithPrecedence() {
        List<FeaturePrefs.RemoteMapping> list = Arrays.asList(
                new FeaturePrefs.RemoteMapping("scan:240", "Netflix\tBtn", "com.nuvio.tv", "discover", "com.netflix.ninja"),
                new FeaturePrefs.RemoteMapping("KEYCODE_BUTTON_3", "YouTube", "io.gh.reisxd.tizentube.cobalt", "", "com.spotify.tv.android"),
                new FeaturePrefs.RemoteMapping("scan:999", "Empty", "", "", "")
        );
        String raw = FeaturePrefs.serializeRemoteMappings(list);
        List<FeaturePrefs.RemoteMapping> parsed = FeaturePrefs.parseRemoteMappings(raw);
        assertEquals(2, parsed.size());
        assertEquals("scan:240", parsed.get(0).keyToken);
        assertEquals("Netflix\tBtn", parsed.get(0).label);
        assertEquals("com.nuvio.tv", parsed.get(0).tap);
        assertEquals("discover", parsed.get(0).dbl);
        assertEquals("com.netflix.ninja", parsed.get(0).hold);

        List<String> expanded = FeaturePrefs.expandRemoteMappings(parsed);
        assertTrue(expanded.contains("scan:240:com.nuvio.tv"));
        assertTrue(expanded.contains("double:scan:240:discover"));
        assertTrue(expanded.contains("hold:scan:240:com.netflix.ninja"));
        assertTrue(expanded.contains("KEYCODE_BUTTON_3:io.gh.reisxd.tizentube.cobalt"));
        assertTrue(expanded.contains("hold:KEYCODE_BUTTON_3:com.spotify.tv.android"));
        assertEquals(5, expanded.size());

        // Verify RemoteKeys accepts new overlay action words
        assertTrue(RemoteKeys.isAction("discover"));
        assertTrue(RemoteKeys.isAction("recents_overlay"));
        assertTrue(RemoteKeys.isAction("control_center"));
    }

    @Test
    public void qrPhoneEditorParsesLabelsHiddenAppsCoordinatesAndImageMagic() {
        Map<String, String> labels = QrConfigServer.parseAppLabelsText(
                "# comment\ncom.netflix.ninja=Netflix 4K\nnet.cme.voyo.cz.tvapp:Oneplay\ninvalidline\n");
        assertEquals(2, labels.size());
        assertEquals("Netflix 4K", labels.get("com.netflix.ninja"));
        assertEquals("Oneplay", labels.get("net.cme.voyo.cz.tvapp"));

        String serMap = FeaturePrefs.serializeStringMap(labels);
        assertEquals(labels, FeaturePrefs.parseStringMap(serMap));

        List<String> hidden = QrConfigServer.parseHiddenAppsText(
                "com.foo.bar, com.baz.qux\ncom.foo.bar\nnodots\n");
        assertEquals(Arrays.asList("com.foo.bar", "com.baz.qux"), hidden);
        assertEquals(hidden, FeaturePrefs.parseStringList(FeaturePrefs.serializeStringList(hidden)));

        assertTrue(FeaturePrefs.isValidCoordinate("50.0755", -90.0, 90.0));
        assertTrue(FeaturePrefs.isValidCoordinate("-122.4194", -180.0, 180.0));
        assertFalse(FeaturePrefs.isValidCoordinate("95.0", -90.0, 90.0));
        assertFalse(FeaturePrefs.isValidCoordinate("abc", -90.0, 90.0));

        byte[] jpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        byte[] junk = new byte[]{'H', 'E', 'L', 'L', 'O', 0, 0, 0, 0, 0, 0, 0};
        assertTrue(QrConfigServer.isValidImageMagic(jpg));
        assertTrue(QrConfigServer.isValidImageMagic(png));
        assertTrue(QrConfigServer.isValidImageMagic(webp));
        assertFalse(QrConfigServer.isValidImageMagic(junk));
        assertFalse(QrConfigServer.isValidImageMagic(new byte[]{1, 2}));

        assertTrue(QrConfigServer.isValidJsonObjectSyntax(" {\"dock_glass\": true} "));
        assertFalse(QrConfigServer.isValidJsonObjectSyntax("[1, 2, 3]"));
        assertFalse(QrConfigServer.isValidJsonObjectSyntax(""));
    }

    @Test
    public void selfUpdaterManifestUrlAndLauncherAssetSelection() {
        String repo = "ryslavy/matcher";
        assertTrue(SelfUpdater.isValidReleaseAssetUrl(repo,
                "https://github.com/ryslavy/matcher/releases/download/v1.2.0/manifest.json", ".json"));
        assertFalse(SelfUpdater.isValidReleaseAssetUrl(repo,
                "https://evil.example/ryslavy/matcher/releases/download/v1.2.0/manifest.json", ".json"));

        assertTrue(SelfUpdater.isPreferredLauncherAsset("matcher-launcher-v1.2.0.apk"));
        assertTrue(SelfUpdater.isPreferredLauncherAsset("tvhome-release.apk"));
        assertFalse(SelfUpdater.isPreferredLauncherAsset("matcher-addon-brightness-v1.2.0.apk"));
        assertFalse(SelfUpdater.isPreferredLauncherAsset("manifest.json"));

        assertEquals("hello", SelfUpdater.truncateNotes("  hello  ", 20));
        assertTrue(SelfUpdater.truncateNotes("0123456789abcdef", 10).endsWith("\u2026"));
    }
}
