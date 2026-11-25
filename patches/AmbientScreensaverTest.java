package com.gathin.tvhome;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AmbientScreensaverTest {

    @Test
    public void parsesOpenMeteoJsonAndMapsWeatherIcons() {
        String json = "{\"current\":{\"temperature_2m\":14.6,\"apparent_temperature\":13.2,\"weather_code\":2,\"is_day\":1}}";
        AmbientWeather.Snapshot s = AmbientWeather.parseJson(json, 123456L);
        assertNotNull(s);
        assertEquals(14.6, s.tempC, 0.001);
        assertEquals(13.2, s.feelsC, 0.001);
        assertEquals(2, s.wmoCode);
        assertTrue(s.isDay);
        assertEquals("15 \u00B0C", AmbientWeather.formatTemperature(s.tempC));

        assertEquals(R.drawable.ic_weather_sun, AmbientWeather.iconResForWmo(0, true));
        assertEquals(R.drawable.ic_weather_moon, AmbientWeather.iconResForWmo(0, false));
        assertEquals(R.drawable.ic_weather_partly, AmbientWeather.iconResForWmo(2, true));
        assertEquals(R.drawable.ic_weather_rain, AmbientWeather.iconResForWmo(61, true));
        assertEquals(R.drawable.ic_weather_snow, AmbientWeather.iconResForWmo(73, false));
        assertEquals(R.drawable.ic_weather_storm, AmbientWeather.iconResForWmo(95, true));
        assertNull(AmbientWeather.parseJson("{}", 0L));
    }

    @Test
    public void selectsUpcomingSolarEventAndFormatsTime() {
        int[] morning = AmbientWeather.nextSolarEvent(6 * 60, 7 * 60 + 21, 16 * 60 + 18);
        assertEquals(1, morning[0]);
        assertEquals("07:21", AmbientWeather.formatHourMin(morning[1]));

        int[] afternoon = AmbientWeather.nextSolarEvent(11 * 60, 7 * 60 + 21, 16 * 60 + 18);
        assertEquals(0, afternoon[0]);
        assertEquals("16:18", AmbientWeather.formatHourMin(afternoon[1]));

        int[] night = AmbientWeather.nextSolarEvent(22 * 60, 7 * 60 + 21, 16 * 60 + 18);
        assertEquals(1, night[0]);
        assertEquals("07:21", AmbientWeather.formatHourMin(night[1]));
    }

    @Test
    public void editorialSlotsNeverOverlapOrLeaveSafeBounds() {
        int parentW = 1920, parentH = 1080;
        int clockW = 700, clockH = 380;
        int mediaW = 980, mediaH = 300;
        int marginX = 112, marginY = 96;

        for (int slot = 0; slot < 4; slot++) {
            float[] p = ClockScene.computePlacement(slot, parentW, parentH,
                    clockW, clockH, mediaW, mediaH, true, marginX, marginY, 18, -14, 0.5f, 0.5f);
            // Both views stay strictly inside screen safe margins
            assertTrue(p[0] >= marginX && p[0] + clockW <= parentW - marginX);
            assertTrue(p[1] >= marginY && p[1] + clockH <= parentH - marginY);
            assertTrue(p[2] >= marginX && p[2] + mediaW <= parentW - marginX);
            assertTrue(p[3] >= marginY && p[3] + mediaH <= parentH - marginY);
            // No vertical overlap between Clock block and Now Playing card
            boolean separatedVertically = (p[1] + clockH <= p[3]) || (p[3] + mediaH <= p[1]);
            assertTrue("Slot " + slot + " must not overlap vertically", separatedVertically);
        }
    }

    @Test
    public void oledDriftStaysAnchoredAroundPlacedSlot() {
        int parentW = 1920, parentH = 1080;
        int clockW = 620, clockH = 320;
        int safeMargin = 48;
        float baseX = 1188f; // Top-Right quadrant anchor
        float baseY = 96f;

        // With OLED drift OFF, clock stays strictly at its placed anchor (never snaps to 0,0)
        float[] disabled = ClockScene.computeOledDriftedPosition(
                baseX, baseY, parentW, parentH, clockW, clockH, safeMargin, false, 120_000L, 2.0f);
        assertEquals(baseX, disabled[0], 0.01f);
        assertEquals(baseY, disabled[1], 0.01f);

        // With OLED drift ON, clock orbits smoothly within +-18dp X / +-12dp Y of anchor and stays inside safe bounds
        for (long t = 0; t <= 600_000L; t += 30_000L) {
            float[] drifted = ClockScene.computeOledDriftedPosition(
                    baseX, baseY, parentW, parentH, clockW, clockH, safeMargin, true, t, 2.0f);
            assertTrue("Drift X too far from anchor", Math.abs(drifted[0] - baseX) <= 36.1f);
            assertTrue("Drift Y too far from anchor", Math.abs(drifted[1] - baseY) <= 24.1f);
            assertTrue(drifted[0] >= safeMargin && drifted[0] + clockW <= parentW - safeMargin);
            assertTrue(drifted[1] >= safeMargin && drifted[1] + clockH <= parentH - safeMargin);
        }
    }

    @Test
    public void distinguishesMediaControlKeysFromWakeKeys() {
        assertTrue(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertTrue(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertFalse(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_BACK));
        assertFalse(ClockActivity.isMediaControlKey(KeyEvent.KEYCODE_HOME));
    }
}
