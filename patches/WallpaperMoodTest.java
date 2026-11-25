package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** Plain JVM tests for wallpaper_moods: the time of day's group and the pictures it picks from. */
public class WallpaperMoodTest {
    private static final int RISE = 7 * 60 + 20, SET = 18 * 60 + 20;

    private static String at(int hour, int minute) {
        return WallpaperMood.timeOfDay(hour * 60 + minute, RISE, SET);
    }

    @Test
    public void timeOfDayFollowsSunriseNoonAndDusk() {
        assertEquals(WallpaperMood.NIGHT, at(0, 30));
        assertEquals(WallpaperMood.NIGHT, at(7, 19));
        assertEquals(WallpaperMood.MORNING, at(7, 20));
        assertEquals(WallpaperMood.MORNING, at(11, 59));
        assertEquals(WallpaperMood.EVENING, at(12, 0));
        assertEquals(WallpaperMood.EVENING, at(18, 49));   // half an hour after sunset is still evening
        assertEquals(WallpaperMood.NIGHT, at(18, 50));
        assertEquals(WallpaperMood.NIGHT, at(23, 59));
    }

    @Test
    public void sunComesFromTheConfiguredPlaceOrTheDefaults() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        ZoneId zone = ZoneId.of("America/Los_Angeles");
        assertArrayEquals(Sun.riseAndSet(day, 37.35, -121.98, zone), WallpaperMood.sun(day, "37.35", " -121.98 ", zone));
        int[] defaults = {WallpaperMood.DEFAULT_SUNRISE, WallpaperMood.DEFAULT_SUNSET};
        assertArrayEquals(defaults, WallpaperMood.sun(day, "", "", zone));
        assertArrayEquals(defaults, WallpaperMood.sun(day, "37.35", "", zone));
        assertArrayEquals(defaults, WallpaperMood.sun(day, "north", "-121.98", zone));
        // the sun does not set at the pole in June: the defaults
        assertArrayEquals(defaults, WallpaperMood.sun(LocalDate.of(2026, 6, 21), "89.9", "0", ZoneId.of("UTC")));
    }

    private static List<File> files(String... names) {
        List<File> out = new ArrayList<>();
        for (String name : names) out.add(new File("/walls", name));
        return out;
    }

    private static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }

    @Test
    public void selectKeepsTheCollectionsOrderAndFallsBackToAll() {
        List<File> all = files("a.jpg", "b.jpg", "c.jpg", "d.jpg");
        Map<String, Set<String>> groups = new HashMap<>();
        groups.put(WallpaperMood.NIGHT, new HashSet<>(Arrays.asList("d.jpg", "a.jpg")));
        groups.put(WallpaperMood.MORNING, new HashSet<>(Collections.singletonList("gone.jpg")));
        assertEquals(Arrays.asList("a.jpg", "d.jpg"), names(WallpaperMood.select(all, groups, WallpaperMood.NIGHT)));
        assertEquals(all, WallpaperMood.select(all, groups, WallpaperMood.MORNING));   // none of its pictures here
        assertEquals(all, WallpaperMood.select(all, groups, WallpaperMood.EVENING));   // no group at all
        assertEquals(all, WallpaperMood.select(all, Collections.emptyMap(), WallpaperMood.NIGHT));
    }

    @Test
    public void seasonsFollowMeteorologicalMonths() {
        assertEquals(WallpaperMood.WINTER, WallpaperMood.season(LocalDate.of(2026, 1, 15)));
        assertEquals(WallpaperMood.WINTER, WallpaperMood.season(LocalDate.of(2026, 2, 28)));
        assertEquals(WallpaperMood.SPRING, WallpaperMood.season(LocalDate.of(2026, 3, 1)));
        assertEquals(WallpaperMood.SPRING, WallpaperMood.season(LocalDate.of(2026, 5, 31)));
        assertEquals(WallpaperMood.SUMMER, WallpaperMood.season(LocalDate.of(2026, 6, 1)));
        assertEquals(WallpaperMood.SUMMER, WallpaperMood.season(LocalDate.of(2026, 8, 31)));
        assertEquals(WallpaperMood.AUTUMN, WallpaperMood.season(LocalDate.of(2026, 9, 1)));
        assertEquals(WallpaperMood.AUTUMN, WallpaperMood.season(LocalDate.of(2026, 11, 30)));
        assertEquals(WallpaperMood.WINTER, WallpaperMood.season(LocalDate.of(2026, 12, 1)));
    }

    @Test
    public void easterSundayMatchesKnownYears() {
        assertEquals(LocalDate.of(2025, 4, 20), WallpaperMood.easterSunday(2025));
        assertEquals(LocalDate.of(2026, 4, 5), WallpaperMood.easterSunday(2026));
        assertEquals(LocalDate.of(2027, 3, 28), WallpaperMood.easterSunday(2027));
        assertEquals(LocalDate.of(2028, 4, 16), WallpaperMood.easterSunday(2028));
        assertEquals(LocalDate.of(2029, 4, 1), WallpaperMood.easterSunday(2029));
        assertEquals(LocalDate.of(2030, 4, 21), WallpaperMood.easterSunday(2030));
    }

    @Test
    public void holidaysCoverFixedAndMoveableWindows() {
        assertEquals(WallpaperMood.NEW_YEAR, WallpaperMood.holiday(LocalDate.of(2026, 12, 31)));
        assertEquals(WallpaperMood.NEW_YEAR, WallpaperMood.holiday(LocalDate.of(2027, 1, 1)));
        assertEquals(WallpaperMood.NEW_YEAR, WallpaperMood.holiday(LocalDate.of(2027, 1, 2)));
        assertNull(WallpaperMood.holiday(LocalDate.of(2027, 1, 3)));

        assertEquals(WallpaperMood.CHRISTMAS, WallpaperMood.holiday(LocalDate.of(2026, 12, 1)));
        assertEquals(WallpaperMood.CHRISTMAS, WallpaperMood.holiday(LocalDate.of(2026, 12, 24)));
        assertEquals(WallpaperMood.CHRISTMAS, WallpaperMood.holiday(LocalDate.of(2026, 12, 30)));

        assertEquals(WallpaperMood.VALENTINES, WallpaperMood.holiday(LocalDate.of(2026, 2, 14)));
        assertEquals(WallpaperMood.HALLOWEEN, WallpaperMood.holiday(LocalDate.of(2026, 10, 31)));
        assertEquals(WallpaperMood.HALLOWEEN, WallpaperMood.holiday(LocalDate.of(2026, 11, 2)));
        assertNull(WallpaperMood.holiday(LocalDate.of(2026, 11, 3)));

        // 2026 Easter Sunday is April 5 -> active Apr 2 (Maundy Thursday) .. Apr 6 (Easter Monday)
        assertNull(WallpaperMood.holiday(LocalDate.of(2026, 4, 1)));
        assertEquals(WallpaperMood.EASTER, WallpaperMood.holiday(LocalDate.of(2026, 4, 2)));
        assertEquals(WallpaperMood.EASTER, WallpaperMood.holiday(LocalDate.of(2026, 4, 5)));
        assertEquals(WallpaperMood.EASTER, WallpaperMood.holiday(LocalDate.of(2026, 4, 6)));
        assertNull(WallpaperMood.holiday(LocalDate.of(2026, 4, 7)));
    }

    @Test
    public void customDateKeysAndPriorityOrderWork() {
        assertTrue(WallpaperMood.isValidKey("winter_night"));
        assertTrue(WallpaperMood.isValidKey("christmas_evening"));
        assertTrue(WallpaperMood.isValidKey("05-15"));
        assertTrue(WallpaperMood.isValidKey("12-28..01-03"));
        assertFalse(WallpaperMood.isValidKey("13-01"));
        assertFalse(WallpaperMood.isValidKey("random_key"));

        assertTrue(WallpaperMood.matchesDateKey("12-28..01-03", LocalDate.of(2026, 12, 30)));
        assertTrue(WallpaperMood.matchesDateKey("12-28..01-03", LocalDate.of(2027, 1, 2)));
        assertFalse(WallpaperMood.matchesDateKey("12-28..01-03", LocalDate.of(2027, 1, 4)));

        List<File> all = files("tod-n.jpg", "aut-n.jpg", "aut-all.jpg", "xmas-n.jpg", "xmas-m.jpg", "bday.jpg");
        Map<String, Set<String>> g = new HashMap<>();
        g.put("night", new HashSet<>(Arrays.asList("tod-n.jpg", "aut-n.jpg", "xmas-n.jpg")));
        g.put("autumn", new HashSet<>(Arrays.asList("aut-n.jpg", "aut-all.jpg")));
        g.put("christmas", new HashSet<>(Arrays.asList("xmas-n.jpg", "xmas-m.jpg")));
        g.put("12-24", new HashSet<>(Collections.singletonList("bday.jpg")));

        // Custom date overrides even Christmas
        assertEquals(Collections.singletonList("bday.jpg"),
                names(WallpaperMood.select(all, g, LocalDate.of(2026, 12, 24), WallpaperMood.NIGHT)));
        // Christmas intersects with night -> xmas-n.jpg; Christmas morning falls back to all christmas
        assertEquals(Collections.singletonList("xmas-n.jpg"),
                names(WallpaperMood.select(all, g, LocalDate.of(2026, 12, 20), WallpaperMood.NIGHT)));
        assertEquals(Arrays.asList("xmas-n.jpg", "xmas-m.jpg"),
                names(WallpaperMood.select(all, g, LocalDate.of(2026, 12, 20), WallpaperMood.MORNING)));
        // Autumn night intersects autumn ∩ night -> aut-n.jpg; Autumn morning -> all autumn
        assertEquals(Collections.singletonList("aut-n.jpg"),
                names(WallpaperMood.select(all, g, LocalDate.of(2026, 11, 21), WallpaperMood.NIGHT)));
        assertEquals(Arrays.asList("aut-n.jpg", "aut-all.jpg"),
                names(WallpaperMood.select(all, g, LocalDate.of(2026, 11, 21), WallpaperMood.MORNING)));
        // Summer has no group -> falls back cleanly to time of day ("night")
        assertEquals(Arrays.asList("tod-n.jpg", "aut-n.jpg", "xmas-n.jpg"),
                names(WallpaperMood.select(all, g, LocalDate.of(2026, 7, 15), WallpaperMood.NIGHT)));
    }
}
