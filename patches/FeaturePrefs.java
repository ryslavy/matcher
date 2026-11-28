package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    static final String KEY_HOME_BACKDROP_BLUR = "home_backdrop_blur";
    static final String KEY_NESTED_MENU_BLUR = "nested_menu_blur";
    static final String KEY_DOCK_AMBIENT_GLOW = "dock_ambient_glow";
    static final String KEY_GLASS_MATERIAL = "glass_material";
    static final String KEY_UI_SOUNDS = "ui_click_sounds";

    static final String GLASS_TONAL = "tonal";
    static final String GLASS_OBSIDIAN = "obsidian";
    static final String GLASS_FROST = "frost";
    static final String GLASS_CLEAR = "clear";
    static final String GLASS_CHROMATIC = "chromatic";
    static final String GLASS_AUTO = "auto";
    static final String KEY_OLED_DRIFT = "oled_screensaver_drift";
    static final String KEY_AERIAL_4K = "aerial_4k_enabled";
    static final String KEY_AERIAL_FAVORITES = "aerial_favorite_urls";
    static final String KEY_AERIAL_FAVORITES_ONLY = "aerial_favorites_only";
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
    static final String KEY_CUSTOM_REMOTE_KEYS = "custom_remote_keys_v1";
    static final String KEY_CUSTOM_APP_LABELS = "custom_app_labels_v1";
    static final String KEY_CUSTOM_HIDDEN_APPS = "custom_hidden_apps_v1";
    static final String KEY_WEATHER_CITY = "weather_city_override";
    static final String KEY_WEATHER_LAT = "weather_lat_override";
    static final String KEY_WEATHER_LON = "weather_lon_override";
    static final String KEY_JUST_UPDATED_VER = "just_updated_version";
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

    static void setJustUpdatedVersion(Context ctx, @Nullable String version) {
        if (version == null || version.trim().isEmpty()) {
            prefs(ctx).edit().remove(KEY_JUST_UPDATED_VER).apply();
        } else {
            prefs(ctx).edit().putString(KEY_JUST_UPDATED_VER, version.trim()).apply();
        }
    }

    @Nullable
    static String consumeJustUpdatedVersion(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String v = p.getString(KEY_JUST_UPDATED_VER, null);
        if (v != null) p.edit().remove(KEY_JUST_UPDATED_VER).apply();
        return v != null && !v.trim().isEmpty() ? v.trim() : null;
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

    static boolean isHomeBackdropBlurEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HOME_BACKDROP_BLUR, true);
    }

    static void setHomeBackdropBlurEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_HOME_BACKDROP_BLUR, on).apply();
        bumpRevision(ctx);
    }

    static boolean isNestedMenuBlurEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_NESTED_MENU_BLUR, true);
    }

    static void setNestedMenuBlurEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_NESTED_MENU_BLUR, on).apply();
        bumpRevision(ctx);
    }

    static boolean isDockAmbientGlowEnabled(Context ctx, boolean themeDefault) {
        return prefs(ctx).getBoolean(KEY_DOCK_AMBIENT_GLOW, themeDefault);
    }

    static void setDockAmbientGlowEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_DOCK_AMBIENT_GLOW, on).apply();
        bumpRevision(ctx);
    }

    static String glassMaterial(Context ctx) {
        String raw = prefs(ctx).getString(KEY_GLASS_MATERIAL, GLASS_TONAL);
        return raw == null || raw.isEmpty() ? GLASS_TONAL : raw.toLowerCase(Locale.US);
    }

    static void setGlassMaterial(Context ctx, String material) {
        String clean = (material == null || material.isEmpty()) ? GLASS_TONAL : material.toLowerCase(Locale.US);
        prefs(ctx).edit().putString(KEY_GLASS_MATERIAL, clean).apply();
        bumpRevision(ctx);
    }

    /** Resolves stored glass material (including Auto Day/Night via configured sunrise/sunset coordinates) to a concrete style ID. */
    static int resolveGlassStyle(Context ctx) {
        String key = glassMaterial(ctx);
        if (!GLASS_AUTO.equals(key)) {
            return ExpressiveGlass.styleFromKey(key);
        }
        boolean isDay = true;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            String lat = weatherLatitude(ctx);
            String lon = weatherLongitude(ctx);
            int[] sun = WallpaperMood.sun(now.toLocalDate(), lat, lon, java.time.ZoneId.systemDefault());
            int min = now.getHour() * 60 + now.getMinute();
            isDay = min >= sun[0] && min < sun[1];
        } catch (RuntimeException ignored) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            isDay = hour >= 7 && hour < 19;
        }
        return ExpressiveGlass.resolveAutoStyle(isDay);
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

    static boolean isAerial4kEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AERIAL_4K, false);
    }

    static void setAerial4kEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AERIAL_4K, on).apply();
        bumpRevision(ctx);
    }

    static Set<String> aerialFavorites(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(KEY_AERIAL_FAVORITES, null);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    static boolean isAerialFavorite(Context ctx, @Nullable String url) {
        if (url == null || url.isEmpty()) return false;
        return aerialFavorites(ctx).contains(url);
    }

    static boolean toggleAerialFavorite(Context ctx, String url) {
        Set<String> set = new HashSet<>(aerialFavorites(ctx));
        boolean added;
        if (set.contains(url)) {
            set.remove(url);
            added = false;
        } else {
            set.add(url);
            added = true;
        }
        prefs(ctx).edit().putStringSet(KEY_AERIAL_FAVORITES, set).apply();
        bumpRevision(ctx);
        return added;
    }

    static boolean isAerialFavoritesOnly(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AERIAL_FAVORITES_ONLY, false);
    }

    static void setAerialFavoritesOnly(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AERIAL_FAVORITES_ONLY, on).apply();
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
        return prefs(ctx).getBoolean(KEY_UPDATER_AUTO, false);
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

    // ---- Custom Remote Button Mappings (In-App Learn Button & Phone QR) ------------------------

    static final class RemoteMapping {
        /** Key token: "scan:<int>" or "KEYCODE_..." or numeric keycode string. */
        final String keyToken;
        /** Human-readable label shown in settings UI (e.g. "Button 6 (scan 240)"). */
        final String label;
        /** Single-tap target (package, package/.Activity, action word, inputs, or empty). */
        final String tap;
        /** Double-tap target (or empty). */
        final String dbl;
        /** Hold (0.5 s) target (or empty). */
        final String hold;

        RemoteMapping(String keyToken, @Nullable String label, @Nullable String tap,
                      @Nullable String dbl, @Nullable String hold) {
            this.keyToken = keyToken == null ? "" : keyToken.trim();
            this.label = label == null ? "" : label.trim();
            this.tap = tap == null ? "" : tap.trim();
            this.dbl = dbl == null ? "" : dbl.trim();
            this.hold = hold == null ? "" : hold.trim();
        }

        boolean isEmpty() {
            return keyToken.isEmpty() || (tap.isEmpty() && dbl.isEmpty() && hold.isEmpty());
        }
    }

    static List<RemoteMapping> customRemoteMappings(Context ctx) {
        return parseRemoteMappings(prefs(ctx).getString(KEY_CUSTOM_REMOTE_KEYS, ""));
    }

    static void saveCustomRemoteMappings(Context ctx, List<RemoteMapping> list) {
        prefs(ctx).edit().putString(KEY_CUSTOM_REMOTE_KEYS, serializeRemoteMappings(list)).apply();
        bumpRevision(ctx);
        Config.invalidateCache();
    }

    static void putCustomRemoteMapping(Context ctx, RemoteMapping mapping) {
        if (mapping == null || mapping.keyToken.isEmpty()) return;
        List<RemoteMapping> list = new ArrayList<>(customRemoteMappings(ctx));
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).keyToken.equalsIgnoreCase(mapping.keyToken)) {
                idx = i;
                break;
            }
        }
        if (mapping.isEmpty()) {
            if (idx >= 0) list.remove(idx);
        } else if (idx >= 0) {
            list.set(idx, mapping);
        } else {
            list.add(mapping);
        }
        saveCustomRemoteMappings(ctx, list);
    }

    static void removeCustomRemoteMapping(Context ctx, String keyToken) {
        if (keyToken == null || keyToken.trim().isEmpty()) return;
        List<RemoteMapping> list = new ArrayList<>(customRemoteMappings(ctx));
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).keyToken.equalsIgnoreCase(keyToken.trim())) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) saveCustomRemoteMappings(ctx, list);
    }

    static void clearCustomRemoteMappings(Context ctx) {
        prefs(ctx).edit().remove(KEY_CUSTOM_REMOTE_KEYS).apply();
        bumpRevision(ctx);
        Config.invalidateCache();
    }

    static List<String> customAppKeyEntries(Context ctx) {
        return expandRemoteMappings(customRemoteMappings(ctx));
    }

    /** Pure-JVM expansion into RemoteKeys app_keys syntax ("key:target", "double:key:target", "hold:key:target"). */
    static List<String> expandRemoteMappings(List<RemoteMapping> mappings) {
        List<String> out = new ArrayList<>();
        if (mappings == null) return out;
        for (RemoteMapping m : mappings) {
            if (m == null || m.keyToken.isEmpty()) continue;
            if (!m.tap.isEmpty()) out.add(m.keyToken + ":" + m.tap);
            if (!m.dbl.isEmpty()) out.add("double:" + m.keyToken + ":" + m.dbl);
            if (!m.hold.isEmpty()) out.add("hold:" + m.keyToken + ":" + m.hold);
        }
        return out;
    }

    static String serializeRemoteMappings(@Nullable List<RemoteMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (RemoteMapping m : mappings) {
            if (m == null || m.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(escField(m.keyToken)).append('\t')
                    .append(escField(m.label)).append('\t')
                    .append(escField(m.tap)).append('\t')
                    .append(escField(m.dbl)).append('\t')
                    .append(escField(m.hold));
        }
        return sb.toString();
    }

    static List<RemoteMapping> parseRemoteMappings(@Nullable String raw) {
        List<RemoteMapping> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 5) continue;
            RemoteMapping m = new RemoteMapping(
                    unescField(p[0]), unescField(p[1]), unescField(p[2]), unescField(p[3]), unescField(p[4]));
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }

    // ---- Custom Runtime App Labels, Hidden Apps & Weather City (No Rebuild Needed) -------------

    static Map<String, String> customAppLabels(Context ctx) {
        return parseStringMap(prefs(ctx).getString(KEY_CUSTOM_APP_LABELS, ""));
    }

    static void saveCustomAppLabels(Context ctx, Map<String, String> labels) {
        prefs(ctx).edit().putString(KEY_CUSTOM_APP_LABELS, serializeStringMap(labels)).apply();
        bumpRevision(ctx);
        Config.invalidateCache();
    }

    static List<String> customHiddenApps(Context ctx) {
        return parseStringList(prefs(ctx).getString(KEY_CUSTOM_HIDDEN_APPS, ""));
    }

    static void saveCustomHiddenApps(Context ctx, List<String> packages) {
        prefs(ctx).edit().putString(KEY_CUSTOM_HIDDEN_APPS, serializeStringList(packages)).apply();
        bumpRevision(ctx);
        Config.invalidateCache();
    }

    static String weatherCityOverride(Context ctx) {
        return prefs(ctx).getString(KEY_WEATHER_CITY, "").trim();
    }

    static String weatherLatOverride(Context ctx) {
        return prefs(ctx).getString(KEY_WEATHER_LAT, "").trim();
    }

    static String weatherLonOverride(Context ctx) {
        return prefs(ctx).getString(KEY_WEATHER_LON, "").trim();
    }

    static String weatherCity(Context ctx) {
        String custom = weatherCityOverride(ctx);
        if (!custom.isEmpty()) return custom;
        try { return ctx.getString(R.string.weather_city).trim(); } catch (RuntimeException ignored) { return ""; }
    }

    static String weatherLatitude(Context ctx) {
        String custom = weatherLatOverride(ctx);
        if (!custom.isEmpty()) return custom;
        try { return ctx.getString(R.string.latitude).trim(); } catch (RuntimeException ignored) { return ""; }
    }

    static String weatherLongitude(Context ctx) {
        String custom = weatherLonOverride(ctx);
        if (!custom.isEmpty()) return custom;
        try { return ctx.getString(R.string.longitude).trim(); } catch (RuntimeException ignored) { return ""; }
    }

    static void setWeatherLocation(Context ctx, @Nullable String city, @Nullable String lat, @Nullable String lon) {
        prefs(ctx).edit()
                .putString(KEY_WEATHER_CITY, city == null ? "" : city.trim())
                .putString(KEY_WEATHER_LAT, lat == null ? "" : lat.trim())
                .putString(KEY_WEATHER_LON, lon == null ? "" : lon.trim())
                .apply();
        bumpRevision(ctx);
    }

    static boolean isValidCoordinate(@Nullable String raw, double min, double max) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty()) return false;
        try {
            double v = Double.parseDouble(s);
            return !Double.isNaN(v) && !Double.isInfinite(v) && v >= min && v <= max;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static String serializeStringMap(@Nullable Map<String, String> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (k.isEmpty() || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(escField(k)).append('\t').append(escField(v));
        }
        return sb.toString();
    }

    static Map<String, String> parseStringMap(@Nullable String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String k = unescField(line.substring(0, tab)).trim();
            String v = unescField(line.substring(tab + 1)).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    static String serializeStringList(@Nullable List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (String item : list) {
            String s = item == null ? "" : item.trim();
            if (s.isEmpty() || !seen.add(s)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(escField(s));
        }
        return sb.toString();
    }

    static List<String> parseStringList(@Nullable String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String s = unescField(line).trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static String escField(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unescField(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 't') out.append('\t');
                else if (n == 'n') out.append('\n');
                else out.append(n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
