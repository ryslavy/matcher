package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Curated catalog of official Apple TV 1080p SDR Aerial slow-motion videos for the Ambient Screensaver.
 * Ships with 14 iconic built-in day/night streams for instant Frame-1 playback, and asynchronously refreshes
 * the full official catalog (55+ Apple CDN clips + modern tvOS Aerials) into a 7-day SharedPreferences cache.
 */
final class AerialCatalog {

    private static final String PREFS = "tvos_prefs";
    private static final String KEY_ENABLED = "aerial_video_enabled";
    private static final String KEY_CATALOG = "aerial_catalog_lines";
    private static final String KEY_FETCHED_AT = "aerial_catalog_ts";
    private static final long CATALOG_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final String PHOBOS_MANIFEST =
            "http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/entries.json";
    private static final String BZAMAYO_MANIFEST =
            "https://bzamayo.com/extras/apple-tv-screensavers.json";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean refreshInFlight;
    private static volatile String cachedRaw;
    private static volatile List<Clip> cachedClips;
    private static final Pattern PHOBOS_OBJ = Pattern.compile("\\{[^{}]*\"url\"\\s*:\\s*\"([^\"]+\\.mov)\"[^{}]*\\}");
    private static final Pattern PHOBOS_LABEL = Pattern.compile("\"accessibilityLabel\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PHOBOS_TOD = Pattern.compile("\"timeOfDay\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BZAMAYO_ENTRY = Pattern.compile("\"videoURL\"\\s*:\\s*\"([^\"]+_SDR_2K_AVC\\.mov)\"[^{}]*\"name\"\\s*:\\s*\"([^\"]+)\"");

    static final class Clip {
        final String url;
        final String label;
        final boolean night;

        Clip(String url, String label, boolean night) {
            this.url = url;
            this.label = label;
            this.night = night;
        }
    }

    /** Iconic built-in Apple TV 1080p H.264 streams (instant playback even before manifest download). */
    private static final Clip[] SEED_CLIPS = {
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_GL_G004_C010_v03_6Mbps.mov", "Greenland", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b1-1.mov", "Hawaii", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b3-3.mov", "San Francisco", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b1-3.mov", "New York City", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b2-1.mov", "China", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b5-2.mov", "London", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_LW_L001_C006_PS_v01_6Mbps.mov", "Liwa", false),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_DB_D011_D009_SIGNCMP_v15_6Mbps.mov", "Dubai", true),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b1-2.mov", "London", true),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b2-3.mov", "New York City", true),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b1-4.mov", "San Francisco", true),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/b6-2.mov", "Hawaii", true),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_HK_B005_C011_t9_6M_HB_tag0.mov", "Hong Kong", true),
            new Clip("http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_LA_A006_C008_t9_6M_HB_tag0.mov", "Los Angeles", true)
    };

    private AerialCatalog() {}

    static boolean isEnabled(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /** Picks a time-of-day matched Aerial clip (avoiding the currently playing URL) and triggers async catalog refresh. */
    @NonNull
    static Clip pick(Context ctx, boolean preferNight, @Nullable String excludeUrl, Random rng) {
        Context app = ctx.getApplicationContext();
        maybeRefreshAsync(app);
        List<Clip> all = loadClips(app);
        List<Clip> matching = new ArrayList<>();
        for (Clip c : all) {
            if (c.night == preferNight && (excludeUrl == null || !excludeUrl.equals(c.url))) {
                matching.add(c);
            }
        }
        if (matching.isEmpty()) {
            for (Clip c : all) {
                if (excludeUrl == null || !excludeUrl.equals(c.url)) matching.add(c);
            }
        }
        if (matching.isEmpty()) return SEED_CLIPS[0];
        return matching.get(rng.nextInt(matching.size()));
    }

    /** Translates common Apple TV Aerial place names to clean Czech when running in Czech locale. */
    static String displayLabel(Context ctx, String rawLabel, boolean night) {
        boolean cs = "cs".equalsIgnoreCase(Locale.getDefault().getLanguage())
                || "cs".equalsIgnoreCase(ctx.getResources().getConfiguration().getLocales().get(0).getLanguage());
        return localizePlace(rawLabel, cs);
    }

    static String localizePlace(String raw, boolean czech) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (!czech) return trimmed;
        switch (trimmed) {
            case "Greenland": return "Grónsko";
            case "Dubai": return "Dubaj";
            case "Hawaii": return "Havaj";
            case "London": return "Londýn";
            case "China": return "Čína";
            case "Hong Kong": return "Hongkong";
            case "Liwa": return "Poušť Liwa";
            case "Iceland": return "Island";
            case "Antarctica": return "Antarktida";
            case "Earth": return "Země z vesmíru";
            case "Scotland": return "Skotsko";
            case "Patagonia": return "Patagonie";
            case "New York City": return "New York";
            case "Grand Canyon": return "Grand Canyon";
            default: return trimmed;
        }
    }

    static String normalizeAerialUrl(String url) {
        if (url == null) return "";
        String clean = url.replace("\\/", "/").trim();
        // Apple's sylvan CDN uses a private root CA; cleartext HTTP is explicitly whitelisted in network_security_config.
        if (clean.startsWith("https://sylvan.apple.com/")) {
            return "http://sylvan.apple.com/" + clean.substring("https://sylvan.apple.com/".length());
        }
        return clean;
    }

    private static List<Clip> loadClips(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY_CATALOG, "");
        List<Clip> cached = cachedClips;
        if (cached != null && saved.equals(cachedRaw)) return cached;
        List<Clip> result = new ArrayList<>();
        if (!TextUtils.isEmpty(saved)) {
            for (String line : saved.split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length == 3 && !parts[0].isEmpty()) {
                    result.add(new Clip(parts[0], parts[1], "1".equals(parts[2])));
                }
            }
        }
        if (result.isEmpty()) {
            for (Clip c : SEED_CLIPS) result.add(c);
        }
        cachedRaw = saved;
        cachedClips = result;
        return result;
    }

    private static void maybeRefreshAsync(Context app) {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(KEY_FETCHED_AT, 0L);
        long now = System.currentTimeMillis();
        if ((last > 0L && now >= last && now - last < CATALOG_TTL_MS) || refreshInFlight) return;
        refreshInFlight = true;
        IO.execute(() -> {
            try {
                Map<String, Clip> merged = new LinkedHashMap<>();
                for (Clip c : SEED_CLIPS) merged.put(c.url, c);

                String phobos = fetchText(PHOBOS_MANIFEST);
                for (Clip c : parsePhobosJson(phobos)) merged.put(c.url, c);

                String modern = fetchText(BZAMAYO_MANIFEST);
                for (Clip c : parseBzamayoJson(modern)) merged.put(c.url, c);

                if (merged.size() > SEED_CLIPS.length) {
                    StringBuilder sb = new StringBuilder(8192);
                    for (Clip c : merged.values()) {
                        sb.append(c.url).append('\t')
                                .append(c.label.replace('\t', ' ').replace('\n', ' ')).append('\t')
                                .append(c.night ? '1' : '0').append('\n');
                    }
                    String serialized = sb.toString();
                    p.edit()
                            .putString(KEY_CATALOG, serialized)
                            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                            .apply();
                    cachedRaw = serialized;
                    cachedClips = new ArrayList<>(merged.values());
                }
            } finally {
                refreshInFlight = false;
            }
        });
    }

    @Nullable
    private static String fetchText(String endpoint) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "CleanYourTV-Aerial/1.0");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder(16384);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Pure parser for Apple's official a1.phobos.apple.com entries.json (55 1080p H.264 day/night videos). */
    static List<Clip> parsePhobosJson(@Nullable String body) {
        List<Clip> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        Matcher m = PHOBOS_OBJ.matcher(body);
        while (m.find()) {
            String chunk = m.group(0);
            String url = normalizeAerialUrl(m.group(1));
            Matcher lm = PHOBOS_LABEL.matcher(chunk);
            Matcher tm = PHOBOS_TOD.matcher(chunk);
            String label = lm.find() ? lm.group(1) : "Aerial";
            boolean night = tm.find() && "night".equalsIgnoreCase(tm.group(1));
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.add(new Clip(url, label, night));
            }
        }
        return out;
    }

    /** Pure parser for modern tvOS 2K SDR AVC entries (Iceland, Alps, Earth from Space, Patagonia, etc.). */
    static List<Clip> parseBzamayoJson(@Nullable String body) {
        List<Clip> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        Matcher m = BZAMAYO_ENTRY.matcher(body);
        while (m.find()) {
            String url = normalizeAerialUrl(m.group(1));
            String name = m.group(2);
            String low = name.toLowerCase(Locale.US);
            boolean night = low.contains("night") || low.contains("dusk") || low.contains("sunset")
                    || low.contains("aurora") || low.contains("space") || low.contains("earth");
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.add(new Clip(url, name, night));
            }
        }
        return out;
    }
}
