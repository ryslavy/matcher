package com.gathin.tvhome;

import androidx.annotation.Nullable;

import java.io.File;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * wallpaper_moods: picks the active subset of a rotating wallpaper collection by:
 *  1. Custom calendar date keys ("MM-DD" or "MM-DD..MM-DD", supporting year-wrap ranges)
 *  2. Built-in holidays (christmas, new_year, easter, halloween, valentines) + optional "_morning/_evening/_night"
 *  3. Meteorological seasons (spring, summer, autumn, winter) + optional "_morning/_evening/_night"
 *  4. Time of day (morning from sunrise to noon, evening until 30 min after sunset, night the rest)
 *  5. Full collection fallback when no matching group has pictures on disk.
 */
final class WallpaperMood {
    static final String MORNING = "morning", EVENING = "evening", NIGHT = "night";
    static final String[] ALL = {MORNING, EVENING, NIGHT};

    static final String SPRING = "spring", SUMMER = "summer", AUTUMN = "autumn", WINTER = "winter";
    static final String[] SEASONS = {SPRING, SUMMER, AUTUMN, WINTER};

    static final String CHRISTMAS = "christmas", NEW_YEAR = "new_year", EASTER = "easter",
            HALLOWEEN = "halloween", VALENTINES = "valentines";
    static final String[] HOLIDAYS = {CHRISTMAS, NEW_YEAR, EASTER, HALLOWEEN, VALENTINES};

    private static final Set<String> VALID_NAMED_KEYS = buildValidNamedKeys();

    /** Sunrise and sunset in minutes of the day, without a place (or where the sun does not rise or set that day). */
    static final int DEFAULT_SUNRISE = 7 * 60, DEFAULT_SUNSET = 18 * 60 + 30;
    private static final int NOON = 12 * 60;
    /** Evening lasts this long after sunset, while the sky still glows. */
    private static final int DUSK_MINUTES = 30;

    private WallpaperMood() {
    }

    private static Set<String> buildValidNamedKeys() {
        Set<String> keys = new HashSet<>();
        Collections.addAll(keys, ALL);
        for (String s : SEASONS) {
            keys.add(s);
            for (String tod : ALL) keys.add(s + "_" + tod);
        }
        for (String h : HOLIDAYS) {
            keys.add(h);
            for (String tod : ALL) keys.add(h + "_" + tod);
        }
        return Collections.unmodifiableSet(keys);
    }

    /** True if {@code key} is a recognized time-of-day, season, holiday, composite, or MM-DD[..MM-DD] calendar key. */
    static boolean isValidKey(@Nullable String key) {
        if (key == null) return false;
        String norm = key.trim().toLowerCase(Locale.ROOT);
        if (VALID_NAMED_KEYS.contains(norm)) return true;
        return parseDateRange(norm) != null;
    }

    /** The mood at this minute of the day, for a sunrise and sunset in minutes of the day. */
    static String timeOfDay(int minute, int sunrise, int sunset) {
        if (minute >= sunrise && minute < NOON) return MORNING;
        if (minute >= NOON && minute < sunset + DUSK_MINUTES) return EVENING;
        return NIGHT;
    }

    /** Meteorological season for the northern hemisphere: Mar-May spring, Jun-Aug summer, Sep-Nov autumn, Dec-Feb winter. */
    static String season(LocalDate day) {
        int m = day.getMonthValue();
        if (m >= 3 && m <= 5) return SPRING;
        if (m >= 6 && m <= 8) return SUMMER;
        if (m >= 9 && m <= 11) return AUTUMN;
        return WINTER;
    }

    /** Gregorian Easter Sunday via the Anonymous (Meeus/Jones/Butcher) algorithm. */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /**
     * Built-in holiday active on {@code day}, or {@code null} on ordinary days:
     *  - new_year: Dec 31 .. Jan 2
     *  - christmas: Dec 1 .. Dec 30 (full Advent + Christmas week)
     *  - valentines: Feb 13 .. Feb 14
     *  - easter: Maundy Thursday (Easter - 3d) .. Easter Monday (Easter + 1d)
     *  - halloween: Oct 28 .. Nov 2 (Halloween + All Souls)
     */
    @Nullable
    static String holiday(LocalDate day) {
        int m = day.getMonthValue();
        int d = day.getDayOfMonth();
        if ((m == 12 && d == 31) || (m == 1 && d <= 2)) return NEW_YEAR;
        if (m == 12) return CHRISTMAS;
        if (m == 2 && (d == 13 || d == 14)) return VALENTINES;
        if ((m == 10 && d >= 28) || (m == 11 && d <= 2)) return HALLOWEEN;
        LocalDate easter = easterSunday(day.getYear());
        if (!day.isBefore(easter.minusDays(3)) && !day.isAfter(easter.plusDays(1))) return EASTER;
        return null;
    }

    /** Matches a custom "MM-DD" or "MM-DD..MM-DD" calendar key against {@code day} (supports year wrap like "12-28..01-03"). */
    static boolean matchesDateKey(@Nullable String key, LocalDate day) {
        if (key == null) return false;
        MonthDay[] range = parseDateRange(key.trim().toLowerCase(Locale.ROOT));
        if (range == null) return false;
        MonthDay today = MonthDay.of(day.getMonthValue(), day.getDayOfMonth());
        MonthDay start = range[0], end = range[1];
        if (!start.isAfter(end)) {
            return !today.isBefore(start) && !today.isAfter(end);
        }
        return !today.isBefore(start) || !today.isAfter(end);
    }

    @Nullable
    private static MonthDay[] parseDateRange(String key) {
        int dots = key.indexOf("..");
        if (dots < 0) {
            MonthDay single = parseMonthDay(key);
            return single == null ? null : new MonthDay[]{single, single};
        }
        if (key.indexOf("..", dots + 2) >= 0) return null;
        MonthDay start = parseMonthDay(key.substring(0, dots));
        MonthDay end = parseMonthDay(key.substring(dots + 2));
        return (start != null && end != null) ? new MonthDay[]{start, end} : null;
    }

    @Nullable
    private static MonthDay parseMonthDay(String s) {
        if (s.length() != 5 || s.charAt(2) != '-') return null;
        try {
            int m = Integer.parseInt(s.substring(0, 2));
            int d = Integer.parseInt(s.substring(3, 5));
            return MonthDay.of(m, d);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** That day's {sunrise, sunset} at config.xml's latitude and longitude; the defaults when either is unusable. */
    static int[] sun(LocalDate day, String latitude, String longitude, ZoneId zone) {
        int[] times = null;
        try {
            if (!latitude.trim().isEmpty() && !longitude.trim().isEmpty()) {
                times = Sun.riseAndSet(day, Double.parseDouble(latitude.trim()), Double.parseDouble(longitude.trim()), zone);
            }
        } catch (NumberFormatException ignored) {
            // not a number: the defaults
        }
        return times != null ? times : new int[]{DEFAULT_SUNRISE, DEFAULT_SUNSET};
    }

    /** The pictures in the mood's group, in the collection's order; all of them when the group has none. */
    static List<File> select(List<File> all, Map<String, Set<String>> groups, String mood) {
        List<File> picked = filterByNames(all, groups.get(mood));
        return picked.isEmpty() ? all : picked;
    }

    /**
     * Calendar + time-of-day aware selection in strict priority order:
     *  1. Matching custom date range ("MM-DD" / "MM-DD..MM-DD"), intersecting with {@code timeOfDay} if non-empty
     *  2. Active holiday: {@code <holiday>_<timeOfDay>} -> {@code <holiday> ∩ <timeOfDay>} -> {@code <holiday>}
     *  3. Active season:  {@code <season>_<timeOfDay>}  -> {@code <season> ∩ <timeOfDay>}  -> {@code <season>}
     *  4. Time of day:    {@code <timeOfDay>}
     *  5. Full collection fallback.
     */
    static List<File> select(List<File> all, Map<String, Set<String>> groups, LocalDate day, String timeOfDay) {
        if (groups == null || groups.isEmpty()) return all;
        Set<String> todNames = groups.get(timeOfDay);

        // 1. Custom calendar date keys
        List<String> customMatches = new ArrayList<>();
        for (String key : groups.keySet()) {
            if (matchesDateKey(key, day)) customMatches.add(key);
        }
        if (!customMatches.isEmpty()) {
            Collections.sort(customMatches);
            Set<String> union = new HashSet<>();
            for (String key : customMatches) {
                Set<String> names = groups.get(key);
                if (names != null) union.addAll(names);
            }
            List<File> custom = filterWithTimeOfDay(all, union, todNames);
            if (!custom.isEmpty()) return custom;
        }

        // 2. Built-in holiday
        String hol = holiday(day);
        if (hol != null) {
            List<File> picked = selectBucket(all, groups, hol, timeOfDay, todNames);
            if (!picked.isEmpty()) return picked;
        }

        // 3. Meteorological season
        List<File> seasonal = selectBucket(all, groups, season(day), timeOfDay, todNames);
        if (!seasonal.isEmpty()) return seasonal;

        // 4. Time of day fallback
        return select(all, groups, timeOfDay);
    }

    private static List<File> selectBucket(List<File> all, Map<String, Set<String>> groups,
                                           String bucket, String timeOfDay, @Nullable Set<String> todNames) {
        List<File> exact = filterByNames(all, groups.get(bucket + "_" + timeOfDay));
        if (!exact.isEmpty()) return exact;
        return filterWithTimeOfDay(all, groups.get(bucket), todNames);
    }

    private static List<File> filterWithTimeOfDay(List<File> all, @Nullable Set<String> bucketNames,
                                                  @Nullable Set<String> todNames) {
        if (bucketNames == null || bucketNames.isEmpty()) return Collections.emptyList();
        if (todNames != null && !todNames.isEmpty()) {
            List<File> intersection = new ArrayList<>();
            for (File file : all) {
                String n = file.getName();
                if (bucketNames.contains(n) && todNames.contains(n)) intersection.add(file);
            }
            if (!intersection.isEmpty()) return intersection;
        }
        return filterByNames(all, bucketNames);
    }

    private static List<File> filterByNames(List<File> all, @Nullable Set<String> names) {
        if (names == null || names.isEmpty()) return Collections.emptyList();
        List<File> group = new ArrayList<>();
        for (File file : all) if (names.contains(file.getName())) group.add(file);
        return group;
    }
}
