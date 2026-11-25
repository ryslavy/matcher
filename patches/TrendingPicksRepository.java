package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unsponsored trending & new-release picks from TMDB filtered strictly to the streaming apps
 * installed on this TV (including Czech Oneplay, Netflix, Max, Disney+, Prime Video, Apple TV+,
 * Magenta TV), enriched with optional Trakt.tv watchlist/watched filtering, Plex recently added,
 * and direct deep-links into Nuvio ({@code nuvio://tmdb/<movie|tv>/<id>}) or Watchmode.
 */
final class TrendingPicksRepository {

    private static final String TAG = "TrendingPicks";
    private static final String TMDB_API = "https://api.tmdb.org/3";
    private static final String TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280";
    private static final String TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500";
    private static final int GENRE_ANIMATION = 16;
    private static final long CACHE_TTL_MS = 6L * 3_600_000L;
    private static final int MAX_TMDB_PICKS = 18;
    private static final String[] NUVIO_PKGS = {"com.nuvio.tv", "com.nuvio.app"};

    /** TMDB watch-provider name substring (lowercase) -> {displayLabel, androidPackage}. */
    static final String[][] STREAMING_SERVICES = {
            {"netflix", "Netflix", "com.netflix.ninja"},
            {"prime video", "Prime Video", "com.amazon.amazonvideo.livingroom"},
            {"amazon prime", "Prime Video", "com.amazon.amazonvideo.livingroom"},
            {"disney", "Disney+", "com.disney.disneyplus"},
            {"max", "Max", "com.wbd.stream"},
            {"hbo", "Max", "com.wbd.stream"},
            {"apple tv", "Apple TV+", "com.apple.atve.androidtv.appletv"},
            {"oneplay", "Oneplay", "net.cme.voyo.cz.tvapp"},
            {"voyo", "Oneplay", "net.cme.voyo.cz.tvapp"},
            {"skyshowtime", "SkyShowtime", "com.skyshowtime.skyshowtime.google"},
            {"crunchyroll", "Crunchyroll", "com.crunchyroll.crunchyroid"},
            {"magenta", "Magenta TV", "cz.tmobile.tvgo"},
            {"paramount", "Paramount+", "com.cbs.ott"}
    };

    static final class Pick {
        int tmdbId;
        String kind = "movie"; // "movie" or "tv"
        String title = "";
        String subtitle = "";
        String serviceLabel = "";
        String targetPkg = "";
        @Nullable String deepLink;
        String backdropUrl = "";
        String posterUrl = "";
        boolean fromWatchlist;
    }

    private TrendingPicksRepository() { }

    /** Pure-JVM Nuvio deep-link builder: {@code nuvio://tmdb/<movie|tv>/<id>}. */
    @Nullable
    static String buildNuvioDeepLink(String kind, int tmdbId) {
        if (tmdbId <= 0) return null;
        String type = "tv".equalsIgnoreCase(kind) ? "tv" : "movie";
        return "nuvio://tmdb/" + type + "/" + tmdbId;
    }

    /** Pure-JVM provider matcher against a set of installed packages. */
    @Nullable
    static String[] matchInstalledService(String providerName, Set<String> installedPackages) {
        if (providerName == null || installedPackages == null || installedPackages.isEmpty()) return null;
        String lower = providerName.trim().toLowerCase(Locale.ROOT);
        for (String[] svc : STREAMING_SERVICES) {
            if (lower.contains(svc[0]) && installedPackages.contains(svc[2])) {
                return new String[]{svc[1], svc[2]};
            }
        }
        return null;
    }

    /** Loads cached or fresh picks off the main thread. */
    static List<Pick> load(Context ctx, boolean forceRefresh) {
        File cacheFile = new File(ctx.getCacheDir(), "trending_picks_v1.json");
        String cacheKey = buildCacheKey(ctx);
        if (!forceRefresh && cacheFile.exists()
                && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
            List<Pick> cached = readCache(cacheFile, cacheKey);
            if (cached != null) return cached;
        }

        List<Pick> merged = new ArrayList<>();
        String tmdbKey = FeaturePrefs.tmdbKey(ctx);
        if (!tmdbKey.isEmpty()) {
            merged.addAll(fetchTmdbPicks(ctx, tmdbKey));
        }
        if (PlexClient.isConfigured(ctx)) {
            merged.addAll(PlexClient.fetchRecentlyAdded(ctx));
        }
        writeCache(cacheFile, cacheKey, merged);
        return merged;
    }

    /** Non-blocking read of already cached picks (used by screensaver). */
    static List<Pick> readCachedOnly(Context ctx) {
        File cacheFile = new File(ctx.getCacheDir(), "trending_picks_v1.json");
        List<Pick> list = readCache(cacheFile, buildCacheKey(ctx));
        return list != null ? list : Collections.emptyList();
    }

    @Nullable
    static Intent buildLaunchIntent(Context ctx, Pick pick) {
        PackageManager pm = ctx.getPackageManager();
        if (pick.deepLink != null && !pick.deepLink.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(pick.deepLink));
            if (pick.targetPkg != null && !pick.targetPkg.isEmpty()) {
                view.setPackage(pick.targetPkg);
            }
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(view, 0) != null) return view;
            // Retry without explicit package in case deep link resolves via intent filter
            Intent generic = new Intent(Intent.ACTION_VIEW, Uri.parse(pick.deepLink))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(generic, 0) != null) return generic;
        }
        if (pick.targetPkg != null && !pick.targetPkg.isEmpty()) {
            Intent lb = pm.getLeanbackLaunchIntentForPackage(pick.targetPkg);
            if (lb != null) return lb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent std = pm.getLaunchIntentForPackage(pick.targetPkg);
            if (std != null) return std.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    @Nullable
    static Bitmap loadCachedBitmap(Context ctx, String imageUrl, int maxWidthPx) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;
        File dir = new File(ctx.getCacheDir(), "picks_art");
        if (!dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        String hash = Integer.toHexString(imageUrl.hashCode()) + ".jpg";
        File file = new File(dir, hash);
        if (!file.exists() || file.length() < 1024) {
            try {
                downloadImage(imageUrl, file);
            } catch (IOException e) {
                return null;
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    private static List<Pick> fetchTmdbPicks(Context ctx, String tmdbKey) {
        List<Pick> out = new ArrayList<>();
        Set<String> installed = installedPackages(ctx);
        String nuvioPkg = findInstalledNuvio(installed);
        boolean useNuvio = FeaturePrefs.openPicksInNuvio(ctx) && nuvioPkg != null;
        boolean hideAnim = FeaturePrefs.hideAnimationPicks(ctx);
        String region = FeaturePrefs.tmdbRegion(ctx);
        String lang = Locale.getDefault().getLanguage().startsWith("cs") ? "cs-CZ" : "en-US";
        TraktClient.AccountData trakt = TraktClient.fetchAccountData(ctx);
        Set<String> seenIds = new HashSet<>();

        try {
            // 1. Pull trending movies & TV shows for the week
            List<JSONObject> candidates = new ArrayList<>();
            collectTmdbList(tmdbKey, "/trending/movie/week?language=" + lang, "movie", candidates);
            collectTmdbList(tmdbKey, "/trending/tv/week?language=" + lang, "tv", candidates);

            for (JSONObject item : candidates) {
                if (out.size() >= MAX_TMDB_PICKS) break;
                int id = item.optInt("id", 0);
                String kind = item.optString("_kind", "movie");
                String key = kind + ":" + id;
                if (id <= 0 || !seenIds.add(key)) continue;
                if (trakt.watchedKeys.contains(key)) continue;
                if (hideAnim && hasGenre(item, GENRE_ANIMATION)) continue;

                String title = "tv".equals(kind)
                        ? item.optString("name", item.optString("original_name", ""))
                        : item.optString("title", item.optString("original_title", ""));
                String backdropPath = item.optString("backdrop_path", "");
                String posterPath = item.optString("poster_path", "");
                if (title.isEmpty() || (backdropPath.isEmpty() && posterPath.isEmpty())) continue;

                // Match against installed streaming services in user's region (or allow all when Nuvio mode active)
                String[] matchedSvc = resolveRegionService(tmdbKey, kind, id, region, installed);
                if (matchedSvc == null && !useNuvio) continue;

                Pick p = new Pick();
                p.tmdbId = id;
                p.kind = kind;
                p.title = title;
                String date = "tv".equals(kind)
                        ? item.optString("first_air_date", "")
                        : item.optString("release_date", "");
                String year = date.length() >= 4 ? date.substring(0, 4) : "";
                double vote = item.optDouble("vote_average", 0.0);
                String rating = vote > 0.5 ? String.format(Locale.US, "★ %.1f", vote) : "";
                boolean onWatchlist = trakt.watchlistKeys.contains(key);
                p.fromWatchlist = onWatchlist;
                if (useNuvio) {
                    p.targetPkg = nuvioPkg;
                    p.serviceLabel = matchedSvc != null ? ("Nuvio · " + matchedSvc[0]) : "Nuvio";
                    p.deepLink = buildNuvioDeepLink(kind, id);
                } else {
                    p.serviceLabel = matchedSvc[0];
                    p.targetPkg = matchedSvc[1];
                }
                StringBuilder sub = new StringBuilder();
                if (onWatchlist) sub.append("Watchlist");
                if (!year.isEmpty()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(year);
                }
                if (!rating.isEmpty()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(rating);
                }
                p.subtitle = sub.toString();
                p.backdropUrl = backdropPath.isEmpty() ? "" : (TMDB_BACKDROP_BASE + backdropPath);
                p.posterUrl = posterPath.isEmpty() ? p.backdropUrl : (TMDB_POSTER_BASE + posterPath);
                out.add(p);
            }
            // Bubble Trakt watchlist matches to front
            Collections.sort(out, (a, b) -> Boolean.compare(b.fromWatchlist, a.fromWatchlist));
        } catch (Exception e) {
            Log.w(TAG, "TMDB picks fetch failed: " + e.getMessage());
        }
        return out;
    }

    private static void collectTmdbList(String key, String path, String kind, List<JSONObject> into) throws Exception {
        JSONObject root = new JSONObject(tmdbGet(key, path));
        JSONArray results = root.optJSONArray("results");
        if (results == null) return;
        for (int i = 0; i < Math.min(results.length(), 14); i++) {
            JSONObject obj = results.getJSONObject(i);
            obj.put("_kind", kind);
            into.add(obj);
        }
    }

    @Nullable
    private static String[] resolveRegionService(String key, String kind, int id, String region,
                                                 Set<String> installed) {
        try {
            JSONObject root = new JSONObject(tmdbGet(key, "/" + kind + "/" + id + "/watch/providers"));
            JSONObject results = root.optJSONObject("results");
            JSONObject regional = results == null ? null : results.optJSONObject(region);
            if (regional == null) return null;
            String[] buckets = {"flatrate", "free", "ads"};
            for (String b : buckets) {
                JSONArray providers = regional.optJSONArray(b);
                if (providers == null) continue;
                for (int i = 0; i < providers.length(); i++) {
                    String name = providers.getJSONObject(i).optString("provider_name", "");
                    String[] match = matchInstalledService(name, installed);
                    if (match != null) return match;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean hasGenre(JSONObject obj, int genreId) {
        JSONArray genres = obj.optJSONArray("genre_ids");
        if (genres == null) return false;
        for (int i = 0; i < genres.length(); i++) {
            if (genres.optInt(i) == genreId) return true;
        }
        return false;
    }

    private static Set<String> installedPackages(Context ctx) {
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = ctx.getPackageManager();
        for (String[] svc : STREAMING_SERVICES) {
            if (isInstalled(pm, svc[2])) pkgs.add(svc[2]);
        }
        for (String nuvio : NUVIO_PKGS) {
            if (isInstalled(pm, nuvio)) pkgs.add(nuvio);
        }
        return pkgs;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Nullable
    private static String findInstalledNuvio(Set<String> installed) {
        for (String pkg : NUVIO_PKGS) {
            if (installed.contains(pkg)) return pkg;
        }
        return null;
    }

    private static String tmdbGet(String key, String path) throws IOException {
        boolean isBearer = key.length() > 45;
        String sep = path.contains("?") ? "&" : "?";
        String fullUrl = isBearer ? (TMDB_API + path) : (TMDB_API + path + sep + "api_key=" + key);
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12_000);
        conn.setRequestProperty("Accept", "application/json");
        if (isBearer) conn.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("TMDB HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > 2 * 1024 * 1024) break;
                    buf.write(chunk, 0, n);
                }
                return buf.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadImage(String url, File dst) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12_000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Image HTTP " + code);
            File tmp = new File(dst.getPath() + ".tmp");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8L << 20) throw new IOException("Image too large");
                    out.write(buf, 0, n);
                }
            }
            if (dst.exists()) //noinspection ResultOfMethodCallIgnored
                dst.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(dst);
        } finally {
            conn.disconnect();
        }
    }

    private static String buildCacheKey(Context ctx) {
        return FeaturePrefs.tmdbRegion(ctx) + "|" + FeaturePrefs.hideAnimationPicks(ctx)
                + "|" + FeaturePrefs.openPicksInNuvio(ctx) + "|" + FeaturePrefs.revision(ctx);
    }

    @Nullable
    private static List<Pick> readCache(File file, String expectedKey) {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            JSONObject root = new JSONObject(buf.toString("UTF-8"));
            if (!expectedKey.equals(root.optString("key"))) return null;
            JSONArray arr = root.optJSONArray("picks");
            if (arr == null) return null;
            List<Pick> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Pick p = new Pick();
                p.tmdbId = o.optInt("id", 0);
                p.kind = o.optString("kind", "movie");
                p.title = o.optString("title", "");
                p.subtitle = o.optString("subtitle", "");
                p.serviceLabel = o.optString("service", "");
                p.targetPkg = o.optString("pkg", "");
                String dl = o.optString("link", "");
                p.deepLink = dl.isEmpty() ? null : dl;
                p.backdropUrl = o.optString("backdrop", "");
                p.posterUrl = o.optString("poster", "");
                p.fromWatchlist = o.optBoolean("wl", false);
                out.add(p);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeCache(File file, String key, List<Pick> picks) {
        try {
            JSONArray arr = new JSONArray();
            for (Pick p : picks) {
                JSONObject o = new JSONObject();
                o.put("id", p.tmdbId);
                o.put("kind", p.kind);
                o.put("title", p.title);
                o.put("subtitle", p.subtitle);
                o.put("service", p.serviceLabel);
                o.put("pkg", p.targetPkg);
                o.put("link", p.deepLink == null ? "" : p.deepLink);
                o.put("backdrop", p.backdropUrl);
                o.put("poster", p.posterUrl);
                o.put("wl", p.fromWatchlist);
                arr.put(o);
            }
            JSONObject root = new JSONObject().put("key", key).put("picks", arr);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (file.exists()) //noinspection ResultOfMethodCallIgnored
                file.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
    }
}
