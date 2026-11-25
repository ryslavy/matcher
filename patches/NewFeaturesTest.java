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
        boolean[][] m = QrBitmap.encode("http://192.168.1.50:49152/AbCdEfGhIjKl");
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

        String repo = "ryslavy/matcher";
        assertTrue(SelfUpdater.isValidReleaseDownloadUrl(repo,
                "https://github.com/ryslavy/matcher/releases/download/v2.0/tvhome.apk"));
        assertFalse(SelfUpdater.isValidReleaseDownloadUrl(repo,
                "https://evil.example.com/ryslavy/matcher/releases/download/v2.0/tvhome.apk"));
        assertFalse(SelfUpdater.isValidReleaseDownloadUrl(repo,
                "https://github.com/ryslavy/matcher/releases/download/v2.0/readme.txt"));

        // SHA-256 of empty byte array
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SelfUpdater.sha256Hex(new byte[0]));
    }
}
