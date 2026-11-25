package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional local Plex Media Server client reading recently added movies & shows
 * ({@code /library/recentlyAdded}) and producing direct deep-links for the Plex Android TV app.
 */
final class PlexClient {

    private static final String TAG = "PlexClient";
    static final String PLEX_PACKAGE = "com.plexapp.android";

    private PlexClient() { }

    static boolean isConfigured(Context ctx) {
        return FeaturePrefs.isAllowedServerUrl(FeaturePrefs.plexUrl(ctx))
                && !FeaturePrefs.plexToken(ctx).isEmpty();
    }

    static List<TrendingPicksRepository.Pick> fetchRecentlyAdded(Context ctx) {
        if (!isConfigured(ctx)) return Collections.emptyList();
        String base = trimSlash(FeaturePrefs.plexUrl(ctx));
        String token = FeaturePrefs.plexToken(ctx);
        try {
            String machineId = fetchMachineId(base, token);
            String body = getJson(base + "/library/recentlyAdded?X-Plex-Container-Size=10", token);
            JSONObject container = new JSONObject(body).optJSONObject("MediaContainer");
            JSONArray meta = container == null ? null : container.optJSONArray("Metadata");
            if (meta == null) return Collections.emptyList();
            List<TrendingPicksRepository.Pick> out = new ArrayList<>();
            for (int i = 0; i < meta.length(); i++) {
                JSONObject m = meta.getJSONObject(i);
                String type = m.optString("type", "");
                String kind = "movie".equals(type) ? "movie" : "tv";
                String title = "episode".equals(type)
                        ? m.optString("grandparentTitle", m.optString("title", ""))
                        : m.optString("title", "");
                if (title.isEmpty()) continue;
                int year = m.optInt("year", 0);
                String key = m.optString("key", "");
                String thumb = m.optString("art", m.optString("thumb", ""));
                String artUrl = thumb.isEmpty() ? "" : (base + thumb + "?X-Plex-Token=" + URLEncoder.encode(token, "UTF-8"));
                String deepLink = (!machineId.isEmpty() && !key.isEmpty())
                        ? ("plex://server/" + machineId + "/details?key=" + URLEncoder.encode(key, "UTF-8"))
                        : null;
                TrendingPicksRepository.Pick pick = new TrendingPicksRepository.Pick();
                pick.tmdbId = 0;
                pick.kind = kind;
                pick.title = title;
                pick.subtitle = year > 0 ? ("Plex · " + year) : "Plex";
                pick.serviceLabel = "Plex";
                pick.targetPkg = PLEX_PACKAGE;
                pick.deepLink = deepLink;
                pick.backdropUrl = artUrl;
                pick.posterUrl = artUrl;
                out.add(pick);
                if (out.size() >= 6) break;
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "Plex recently added skipped: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String fetchMachineId(String base, String token) {
        try {
            String body = getJson(base + "/", token);
            JSONObject mc = new JSONObject(body).optJSONObject("MediaContainer");
            return mc == null ? "" : mc.optString("machineIdentifier", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getJson(String url, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Plex-Token", token);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Plex HTTP " + code);
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

    private static String trimSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
