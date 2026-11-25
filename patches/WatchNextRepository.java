package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads Android TV's system Watch Next provider ({@link TvContract.WatchNextPrograms}) and
 * preview channels titled "Continue Watching" / "Pokračovat ve sledování" (e.g. Stremio, Disney+,
 * Prime Video, Max, Plex). Requires {@code android.permission.READ_TV_LISTINGS}.
 */
final class WatchNextRepository {

    private static final String TAG = "WatchNextRepository";
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final int MAX_PER_APP = 2;
    static final int MAX_ITEMS = 12;

    private static final Set<String> CONTINUE_CHANNEL_NAMES = new HashSet<>(Arrays.asList(
            "continue watching",
            "pokračovat ve sledování",
            "pokracovat ve sledovani",
            "pokračovať v pozeraní",
            "seguir viendo",
            "continuar viendo",
            "continuar assistindo",
            "weitersehen"));

    static final class Item {
        final String pkg;
        final String title;
        @Nullable final String episode;
        final String appName;
        final long durationMs;
        final long positionMs;
        final long engagedAtMs;
        @Nullable final String intentUri;
        @Nullable final Uri artworkUri;

        Item(String pkg, String title, @Nullable String episode, String appName,
             long durationMs, long positionMs, long engagedAtMs,
             @Nullable String intentUri, @Nullable Uri artworkUri) {
            this.pkg = pkg;
            this.title = title;
            this.episode = episode;
            this.appName = appName;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.engagedAtMs = engagedAtMs;
            this.intentUri = intentUri;
            this.artworkUri = artworkUri;
        }

        float progress() {
            return computeProgress(positionMs, durationMs);
        }

        String hideKey() {
            return buildHideKey(pkg, title);
        }
    }

    private WatchNextRepository() { }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Pure-JVM progress ratio in [0..1], or -1 when unknown / not meaningfully started. */
    static float computeProgress(long positionMs, long durationMs) {
        if (durationMs <= 0L || positionMs < 1000L || positionMs >= durationMs) return -1f;
        return Math.max(0f, Math.min(1f, (float) positionMs / (float) durationMs));
    }

    /** Pure-JVM formatted episode label (e.g. "S2 E5") or episode title, never duplicating main title. */
    @Nullable
    static String formatEpisodeLabel(@Nullable String season, @Nullable String episode,
                                     @Nullable String episodeTitle, String title) {
        String result = null;
        if (season != null && !season.trim().isEmpty() && episode != null && !episode.trim().isEmpty()) {
            result = "S" + season.trim() + " E" + episode.trim();
        } else if (episodeTitle != null && !episodeTitle.trim().isEmpty()) {
            result = episodeTitle.trim();
        }
        if (result != null && (result.isEmpty() || title.contains(result))) return null;
        return result;
    }

    static String buildHideKey(String pkg, String title) {
        return pkg + "|" + title;
    }

    static List<Item> load(Context ctx) {
        if (!FeaturePrefs.isWatchNextEnabled(ctx) || !hasPermission(ctx)) {
            return Collections.emptyList();
        }
        ContentResolver cr = ctx.getContentResolver();
        List<RawRow> raw = new ArrayList<>(readTable(cr, TvContract.WatchNextPrograms.CONTENT_URI, true));
        for (long channelId : findContinueChannels(cr, ctx.getPackageName())) {
            raw.addAll(readTable(cr, TvContract.buildPreviewProgramsUriForChannel(channelId), false));
        }

        Set<String> disabledPkgs = FeaturePrefs.watchNextOffPackages(ctx);
        Set<String> hiddenKeys = FeaturePrefs.watchNextHiddenItems(ctx);
        for (Iterator<RawRow> it = raw.iterator(); it.hasNext(); ) {
            RawRow r = it.next();
            if (disabledPkgs.contains(r.pkg) || hiddenKeys.contains(buildHideKey(r.pkg, r.title))) {
                it.remove();
            }
        }
        Collections.sort(raw, (a, b) -> Long.compare(b.engagedAtMs, a.engagedAtMs));

        List<Item> out = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        Set<String> dedup = new HashSet<>();
        PackageManager pm = ctx.getPackageManager();
        for (RawRow r : raw) {
            String dedupKey = r.pkg + "|" + (r.intentUri != null ? r.intentUri : r.title);
            int count = perApp.containsKey(r.pkg) ? perApp.get(r.pkg) : 0;
            if (count >= MAX_PER_APP || !dedup.add(dedupKey)) continue;
            perApp.put(r.pkg, count + 1);
            out.add(new Item(
                    r.pkg,
                    r.title,
                    r.episode,
                    resolveAppLabel(pm, r.pkg),
                    r.durationMs,
                    r.positionMs,
                    r.engagedAtMs,
                    r.intentUri,
                    parseSafeArtworkUri(r.thumbnail != null ? r.thumbnail : r.poster)));
            if (out.size() >= MAX_ITEMS) break;
        }
        return out;
    }

    @Nullable
    static Intent buildLaunchIntent(Context ctx, Item item) {
        if (item.intentUri != null && !item.intentUri.isEmpty()) {
            try {
                Intent parsed = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                if (parsed.getPackage() == null && parsed.getComponent() == null) {
                    parsed.setPackage(item.pkg);
                }
                parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (ctx.getPackageManager().resolveActivity(parsed, 0) != null) {
                    return parsed;
                }
            } catch (Exception e) {
                Log.w(TAG, "WatchNext deep-link parse failed for " + item.pkg, e);
            }
        }
        PackageManager pm = ctx.getPackageManager();
        Intent leanback = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (leanback != null) {
            leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return leanback;
        }
        Intent launch = pm.getLaunchIntentForPackage(item.pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launch;
        }
        return null;
    }

    private static List<Long> findContinueChannels(ContentResolver cr, String selfPkg) {
        List<Long> ids = new ArrayList<>();
        String[] cols = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_PACKAGE_NAME
        };
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, cols, null, null, null)) {
            if (c == null) return ids;
            int idIdx = c.getColumnIndex(TvContract.Channels._ID);
            int nameIdx = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int pkgIdx = c.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            while (c.moveToNext()) {
                String name = nameIdx < 0 ? null : c.getString(nameIdx);
                String pkg = pkgIdx < 0 ? null : c.getString(pkgIdx);
                if (name != null && !selfPkg.equals(pkg)
                        && CONTINUE_CHANNEL_NAMES.contains(name.trim().toLowerCase(Locale.ROOT))) {
                    ids.add(c.getLong(idIdx));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Preview channel query failed: " + e.getMessage());
        }
        return ids;
    }

    private static List<RawRow> readTable(ContentResolver cr, Uri uri, boolean isWatchNext) {
        List<RawRow> out = new ArrayList<>();
        List<String> cols = new ArrayList<>(Arrays.asList(
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI));
        if (isWatchNext) {
            cols.add(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            cols.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        try (Cursor c = cr.query(uri, cols.toArray(new String[0]), null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                if ("0".equals(colStr(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                if (isWatchNext && colLong(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
                        == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) {
                    continue;
                }
                String title = colStr(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String pkg = colStr(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (title == null || title.trim().isEmpty() || pkg == null || pkg.trim().isEmpty()) continue;
                RawRow r = new RawRow();
                r.pkg = pkg.trim();
                r.title = title.trim();
                r.episode = formatEpisodeLabel(
                        colStr(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                        colStr(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                        colStr(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE),
                        r.title);
                r.durationMs = colLong(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                r.positionMs = colLong(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                r.engagedAtMs = isWatchNext
                        ? colLong(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
                        : System.currentTimeMillis();
                r.poster = colStr(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                r.thumbnail = colStr(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                r.intentUri = colStr(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                out.add(r);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "TvContract read failed (" + uri + "): " + e.getMessage());
        }
        return out;
    }

    @Nullable
    private static Uri parseSafeArtworkUri(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Uri uri = Uri.parse(raw.trim());
            String scheme = uri.getScheme();
            if ("content".equalsIgnoreCase(scheme)
                    || "android.resource".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)) {
                return uri;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String resolveAppLabel(PackageManager pm, String pkg) {
        try {
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return label != null ? label.toString() : pkg;
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @Nullable
    private static String colStr(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long colLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0L : c.getLong(i);
    }

    private static final class RawRow {
        String pkg;
        String title;
        @Nullable String episode;
        long durationMs;
        long positionMs;
        long engagedAtMs;
        @Nullable String poster;
        @Nullable String thumbnail;
        @Nullable String intentUri;
    }
}
