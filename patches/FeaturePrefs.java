package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent preferences & private local secrets for opt-in launcher features:
 * Liquid Glass typography, synthesized UI click sounds, OLED screensaver drift,
 * Continue Watching (TvContract), Trending Picks (TMDB / Trakt / Plex / Watchmode / Nuvio),
 * Home Assistant quick controls, Immich photo sync, Crash Watchdog, and GitHub Self-Updater.
 * Stored separately from {@link Config#SETTINGS_NAME} so "Follow theme" never wipes keys or toggles.
 */
final class FeaturePrefs {

    static final String PREFS_NAME = "feature_prefs";

    static final String KEY_LIQUID_CLOCK = "liquid_glass_clock";
    static final String KEY_HOME_LIQUID_OPTICS = "home_liquid_optics";
    static final String KEY_UI_SOUNDS = "ui_click_sounds";
    static final String KEY_OLED_DRIFT = "oled_screensaver_drift";
    static final String KEY_CRASH_WATCHDOG = "crash_watchdog_enabled";
    static final String KEY_WATCH_NEXT = "watch_next_enabled";
    static final String KEY_WATCH_NEXT_OFF_PKGS = "watch_next_off_pkgs";
    static final String KEY_WATCH_NEXT_HIDDEN = "watch_next_hidden";
    static final String KEY_TMDB_KEY = "tmdb_api_key";
    static final String KEY_TMDB_REGION = "tmdb_region";
    static final String KEY_WATCHMODE_KEY = "watchmode_api_key";
    static final String KEY_HIDE_ANIMATION = "hide_animation_picks";
    static final String KEY_OPEN_IN_NUVIO = "open_picks_in_nuvio";
    static final String KEY_WATCH_FROM_SCREENSAVER = "watch_from_screensaver";
    static final String KEY_TRAKT_CLIENT_ID = "trakt_client_id";
    static final String KEY_TRAKT_ACCESS_TOKEN = "trakt_access_token";
    static final String KEY_TRAKT_REFRESH_TOKEN = "trakt_refresh_token";
    static final String KEY_TRAKT_EXPIRES_AT = "trakt_expires_at";
    static final String KEY_PLEX_URL = "plex_url";
    static final String KEY_PLEX_TOKEN = "plex_token";
    static final String KEY_HA_ENABLED = "ha_enabled";
    static final String KEY_HA_URL = "ha_url";
    static final String KEY_HA_TOKEN = "ha_token";
    static final String KEY_HA_ENTITIES = "ha_entities";
    static final String KEY_IMMICH_ENABLED = "immich_enabled";
    static final String KEY_IMMICH_URL = "immich_url";
    static final String KEY_IMMICH_KEY = "immich_api_key";
    static final String KEY_IMMICH_ALBUM_ID = "immich_album_id";
    static final String KEY_IMMICH_ALBUM_NAME = "immich_album_name";
    static final String KEY_UPDATER_AUTO = "updater_auto_check";
    static final String KEY_UPDATER_REPO = "updater_github_repo";
    static final String KEY_REVISION = "feature_prefs_rev";

    static final String DEFAULT_UPDATER_REPO = "ryslavy/matcher";

    private FeaturePrefs() { }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static int revision(Context ctx) {
        return prefs(ctx).getInt(KEY_REVISION, 0);
    }

    static void bumpRevision(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(KEY_REVISION, p.getInt(KEY_REVISION, 0) + 1).apply();
    }

    // ---- Visual & UX toggles -------------------------------------------------------------------

    static boolean isLiquidClockEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LIQUID_CLOCK, false);
    }

    static void setLiquidClockEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_LIQUID_CLOCK, on).apply();
        bumpRevision(ctx);
    }

    static boolean isHomeLiquidOpticsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HOME_LIQUID_OPTICS, true);
    }

    static void setHomeLiquidOpticsEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_HOME_LIQUID_OPTICS, on).apply();
        bumpRevision(ctx);
    }

    static boolean isUiSoundsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_UI_SOUNDS, true);
    }

    static void setUiSoundsEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_UI_SOUNDS, on).apply();
        bumpRevision(ctx);
    }

    static boolean isOledDriftEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_OLED_DRIFT, true);
    }

    static void setOledDriftEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_OLED_DRIFT, on).apply();
        bumpRevision(ctx);
    }

    static boolean isCrashWatchdogEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CRASH_WATCHDOG, true);
    }

    static void setCrashWatchdogEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_CRASH_WATCHDOG, on).apply();
    }

    // ---- Continue Watching (TvContract) --------------------------------------------------------

    static boolean isWatchNextEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WATCH_NEXT, true);
    }

    static void setWatchNextEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_WATCH_NEXT, on).apply();
        bumpRevision(ctx);
    }

    static Set<String> watchNextDisabledPackages(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(KEY_WATCH_NEXT_OFF_PKGS, null);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    static Set<String> watchNextOffPackages(Context ctx) {
        return watchNextDisabledPackages(ctx);
    }

    static void toggleWatchNextPackage(Context ctx, String pkg, boolean enabled) {
        Set<String> set = new HashSet<>(watchNextDisabledPackages(ctx));
        if (enabled) set.remove(pkg);
        else set.add(pkg);
        prefs(ctx).edit().putStringSet(KEY_WATCH_NEXT_OFF_PKGS, set).apply();
        bumpRevision(ctx);
    }

    static Set<String> watchNextHiddenKeys(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(KEY_WATCH_NEXT_HIDDEN, null);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    static Set<String> watchNextHiddenItems(Context ctx) {
        return watchNextHiddenKeys(ctx);
    }

    static void hideWatchNextKey(Context ctx, String key) {
        Set<String> set = new HashSet<>(watchNextHiddenKeys(ctx));
        set.add(key);
        prefs(ctx).edit().putStringSet(KEY_WATCH_NEXT_HIDDEN, set).apply();
        bumpRevision(ctx);
    }

    static void hideWatchNextItem(Context ctx, String key) {
        hideWatchNextKey(ctx, key);
    }

    static void clearHiddenWatchNext(Context ctx) {
        prefs(ctx).edit().remove(KEY_WATCH_NEXT_HIDDEN).apply();
        bumpRevision(ctx);
    }

    // ---- Trending Picks (TMDB / Watchmode / Trakt / Plex / Nuvio) ------------------------------

    static String tmdbKey(Context ctx) {
        return prefs(ctx).getString(KEY_TMDB_KEY, "").trim();
    }

    static void setTmdbKey(Context ctx, @Nullable String key) {
        prefs(ctx).edit().putString(KEY_TMDB_KEY, key == null ? "" : key.trim()).apply();
        bumpRevision(ctx);
    }

    static String tmdbRegion(Context ctx) {
        String saved = prefs(ctx).getString(KEY_TMDB_REGION, "").trim().toUpperCase(Locale.ROOT);
        if (saved.length() == 2) return saved;
        String country = Locale.getDefault().getCountry();
        return (country != null && country.length() == 2) ? country.toUpperCase(Locale.ROOT) : "CZ";
    }

    static void setTmdbRegion(Context ctx, @Nullable String region) {
        String r = region == null ? "" : region.trim().toUpperCase(Locale.ROOT);
        if (r.length() != 2) r = "CZ";
        prefs(ctx).edit().putString(KEY_TMDB_REGION, r).apply();
        bumpRevision(ctx);
    }

    static String watchmodeKey(Context ctx) {
        return prefs(ctx).getString(KEY_WATCHMODE_KEY, "").trim();
    }

    static void setWatchmodeKey(Context ctx, @Nullable String key) {
        prefs(ctx).edit().putString(KEY_WATCHMODE_KEY, key == null ? "" : key.trim()).apply();
        bumpRevision(ctx);
    }

    static boolean hideAnimationPicks(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HIDE_ANIMATION, false);
    }

    static void setHideAnimationPicks(Context ctx, boolean hide) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_ANIMATION, hide).apply();
        bumpRevision(ctx);
    }

    static boolean openPicksInNuvio(Context ctx) {
        return prefs(ctx).getBoolean(KEY_OPEN_IN_NUVIO, true);
    }

    static void setOpenPicksInNuvio(Context ctx, boolean nuvio) {
        prefs(ctx).edit().putBoolean(KEY_OPEN_IN_NUVIO, nuvio).apply();
        bumpRevision(ctx);
    }

    static boolean watchFromScreensaver(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WATCH_FROM_SCREENSAVER, true);
    }

    static void setWatchFromScreensaver(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_WATCH_FROM_SCREENSAVER, on).apply();
    }

    static String traktClientId(Context ctx) {
        return prefs(ctx).getString(KEY_TRAKT_CLIENT_ID, "").trim();
    }

    static void setTraktClientId(Context ctx, @Nullable String clientId) {
        prefs(ctx).edit().putString(KEY_TRAKT_CLIENT_ID, clientId == null ? "" : clientId.trim()).apply();
        bumpRevision(ctx);
    }

    static boolean isTraktSignedIn(Context ctx) {
        return !traktClientId(ctx).isEmpty() && !traktRefreshToken(ctx).isEmpty();
    }

    static String traktAccessToken(Context ctx) {
        return prefs(ctx).getString(KEY_TRAKT_ACCESS_TOKEN, "").trim();
    }

    static String traktRefreshToken(Context ctx) {
        return prefs(ctx).getString(KEY_TRAKT_REFRESH_TOKEN, "").trim();
    }

    static long traktExpiresAt(Context ctx) {
        return prefs(ctx).getLong(KEY_TRAKT_EXPIRES_AT, 0L);
    }

    static void saveTraktTokens(Context ctx, String access, String refresh, long expiresAtMs) {
        prefs(ctx).edit()
                .putString(KEY_TRAKT_ACCESS_TOKEN, access == null ? "" : access.trim())
                .putString(KEY_TRAKT_REFRESH_TOKEN, refresh == null ? "" : refresh.trim())
                .putLong(KEY_TRAKT_EXPIRES_AT, expiresAtMs)
                .apply();
        bumpRevision(ctx);
    }

    static void clearTraktTokens(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_TRAKT_ACCESS_TOKEN)
                .remove(KEY_TRAKT_REFRESH_TOKEN)
                .remove(KEY_TRAKT_EXPIRES_AT)
                .apply();
        bumpRevision(ctx);
    }

    static String plexUrl(Context ctx) {
        return prefs(ctx).getString(KEY_PLEX_URL, "").trim();
    }

    static String plexToken(Context ctx) {
        return prefs(ctx).getString(KEY_PLEX_TOKEN, "").trim();
    }

    static void setPlexConfig(Context ctx, @Nullable String url, @Nullable String token) {
        prefs(ctx).edit()
                .putString(KEY_PLEX_URL, url == null ? "" : url.trim())
                .putString(KEY_PLEX_TOKEN, token == null ? "" : token.trim())
                .apply();
        bumpRevision(ctx);
    }

    static void setPlexUrl(Context ctx, @Nullable String url) {
        prefs(ctx).edit().putString(KEY_PLEX_URL, url == null ? "" : url.trim()).apply();
        bumpRevision(ctx);
    }

    static void setPlexToken(Context ctx, @Nullable String token) {
        prefs(ctx).edit().putString(KEY_PLEX_TOKEN, token == null ? "" : token.trim()).apply();
        bumpRevision(ctx);
    }

    // ---- Home Assistant ------------------------------------------------------------------------

    static boolean isHomeAssistantEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HA_ENABLED, false)
                && !homeAssistantUrl(ctx).isEmpty()
                && !homeAssistantToken(ctx).isEmpty()
                && !homeAssistantEntities(ctx).isEmpty();
    }

    static boolean isHomeAssistantToggleOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HA_ENABLED, false);
    }

    static void setHomeAssistantEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_HA_ENABLED, on).apply();
        bumpRevision(ctx);
    }

    static String homeAssistantUrl(Context ctx) {
        return prefs(ctx).getString(KEY_HA_URL, "").trim();
    }

    static String homeAssistantToken(Context ctx) {
        return prefs(ctx).getString(KEY_HA_TOKEN, "").trim();
    }

    static String homeAssistantEntities(Context ctx) {
        return prefs(ctx).getString(KEY_HA_ENTITIES, "").trim();
    }

    static void setHomeAssistantUrl(Context ctx, @Nullable String url) {
        prefs(ctx).edit().putString(KEY_HA_URL, url == null ? "" : url.trim()).apply();
        bumpRevision(ctx);
    }

    static void setHomeAssistantToken(Context ctx, @Nullable String token) {
        prefs(ctx).edit().putString(KEY_HA_TOKEN, token == null ? "" : token.trim()).apply();
        bumpRevision(ctx);
    }

    static void setHomeAssistantEntities(Context ctx, @Nullable String entities) {
        prefs(ctx).edit().putString(KEY_HA_ENTITIES, entities == null ? "" : entities.trim()).apply();
        bumpRevision(ctx);
    }

    static void setHomeAssistantConfig(Context ctx, @Nullable String url, @Nullable String token,
                                       @Nullable String entities) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (url != null) ed.putString(KEY_HA_URL, url.trim());
        if (token != null) ed.putString(KEY_HA_TOKEN, token.trim());
        if (entities != null) ed.putString(KEY_HA_ENTITIES, entities.trim());
        if ((url != null && !url.trim().isEmpty()) && (token != null && !token.trim().isEmpty())) {
            ed.putBoolean(KEY_HA_ENABLED, true);
        }
        ed.apply();
        bumpRevision(ctx);
    }

    // ---- Immich Photo Sync ---------------------------------------------------------------------

    static boolean isImmichEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_IMMICH_ENABLED, false)
                && !immichUrl(ctx).isEmpty()
                && !immichApiKey(ctx).isEmpty();
    }

    static void setImmichEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_IMMICH_ENABLED, on).apply();
        bumpRevision(ctx);
    }

    static String immichUrl(Context ctx) {
        return prefs(ctx).getString(KEY_IMMICH_URL, "").trim();
    }

    static String immichApiKey(Context ctx) {
        return prefs(ctx).getString(KEY_IMMICH_KEY, "").trim();
    }

    static String immichKey(Context ctx) {
        return immichApiKey(ctx);
    }

    static void setImmichUrl(Context ctx, @Nullable String url) {
        prefs(ctx).edit().putString(KEY_IMMICH_URL, url == null ? "" : url.trim()).apply();
        bumpRevision(ctx);
    }

    static void setImmichKey(Context ctx, @Nullable String key) {
        prefs(ctx).edit().putString(KEY_IMMICH_KEY, key == null ? "" : key.trim()).apply();
        bumpRevision(ctx);
    }

    static String immichAlbumId(Context ctx) {
        return prefs(ctx).getString(KEY_IMMICH_ALBUM_ID, "").trim();
    }

    static String immichAlbumName(Context ctx) {
        return prefs(ctx).getString(KEY_IMMICH_ALBUM_NAME, "").trim();
    }

    static void setImmichConfig(Context ctx, @Nullable String url, @Nullable String key) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (url != null) ed.putString(KEY_IMMICH_URL, url.trim());
        if (key != null) ed.putString(KEY_IMMICH_KEY, key.trim());
        if (url != null && !url.trim().isEmpty() && key != null && !key.trim().isEmpty()) {
            ed.putBoolean(KEY_IMMICH_ENABLED, true);
        }
        ed.apply();
        bumpRevision(ctx);
    }

    static void setImmichAlbum(Context ctx, @Nullable String albumId, @Nullable String albumName) {
        prefs(ctx).edit()
                .putString(KEY_IMMICH_ALBUM_ID, albumId == null ? "" : albumId.trim())
                .putString(KEY_IMMICH_ALBUM_NAME, albumName == null ? "" : albumName.trim())
                .apply();
        bumpRevision(ctx);
    }

    // ---- GitHub Self-Updater -------------------------------------------------------------------

    static boolean isAutoUpdateEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_UPDATER_AUTO, true);
    }

    static void setAutoUpdateEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_UPDATER_AUTO, on).apply();
    }

    static String updaterRepo(Context ctx) {
        String r = prefs(ctx).getString(KEY_UPDATER_REPO, DEFAULT_UPDATER_REPO).trim();
        return r.isEmpty() ? DEFAULT_UPDATER_REPO : r;
    }

    static void setUpdaterRepo(Context ctx, @Nullable String repo) {
        String r = repo == null ? "" : repo.trim();
        prefs(ctx).edit().putString(KEY_UPDATER_REPO, r.isEmpty() ? DEFAULT_UPDATER_REPO : r).apply();
    }

    /** Pure JVM URL validator: allows plain http:// ONLY on private LAN hosts; public hosts require https://. */
    static boolean isAllowedServerUrl(@Nullable String rawUrl) {
        if (rawUrl == null) return false;
        String u = rawUrl.trim();
        if (u.isEmpty()) return false;
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) return u.length() > 8;
        if (!lower.startsWith("http://")) return false;
        String host = extractHost(u);
        return isPrivateLanHost(host);
    }

    static String extractHost(@Nullable String rawUrl) {
        if (rawUrl == null) return "";
        String s = rawUrl.trim();
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close > 0 ? s.substring(1, close).toLowerCase(Locale.ROOT) : "";
        }
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isPrivateLanHost(@Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            return true;
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) return false;
            if (a == 10 || a == 127) return true;
            if (a == 192 && b == 168) return true;
            return a == 172 && b >= 16 && b <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
