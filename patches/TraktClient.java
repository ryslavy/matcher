package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional Trakt.tv PKCE device-code client (no client secret needed).
 * Syncs watched TMDB IDs (to hide already-watched items in Discover) and watchlist items
 * (to boost user's saved shows/movies to the top of Trending Picks).
 */
final class TraktClient {

    private static final String TAG = "TraktClient";
    private static final String API = "https://api.trakt.tv";

    static final class AccountData {
        final Set<String> watchedKeys = new HashSet<>(); // "movie:603", "tv:1396"
        final List<String> watchlistKeys = new ArrayList<>();
    }

    interface DeviceSignInListener {
        void onCodeReady(String verificationUrl, String userCode);
        void onSuccess(String username);
        void onError(String message);
    }

    private static volatile boolean signingIn;

    private TraktClient() { }

    static boolean hasClientId(Context ctx) {
        return !FeaturePrefs.traktClientId(ctx).isEmpty();
    }

    static boolean isSignedIn(Context ctx) {
        return FeaturePrefs.isTraktSignedIn(ctx);
    }

    static void signOut(Context ctx) {
        final String clientId = FeaturePrefs.traktClientId(ctx);
        final String token = FeaturePrefs.traktAccessToken(ctx);
        FeaturePrefs.clearTraktTokens(ctx);
        if (!clientId.isEmpty() && !token.isEmpty()) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject().put("token", token).put("client_id", clientId);
                    send("POST", "/oauth/revoke", clientId, null, body.toString());
                } catch (Exception ignored) {
                }
            }, "trakt-revoke").start();
        }
    }

    static void startDeviceSignIn(Context ctx, DeviceSignInListener listener) {
        if (signingIn) return;
        final String clientId = FeaturePrefs.traktClientId(ctx);
        if (clientId.isEmpty()) {
            listener.onError("Nejprve zadejte Trakt Client ID přes QR nastavení.");
            return;
        }
        signingIn = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] rnd = new byte[48];
                new SecureRandom().nextBytes(rnd);
                String verifier = Base64.encodeToString(rnd, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                String challenge = Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

                JSONObject req = new JSONObject()
                        .put("client_id", clientId)
                        .put("code_challenge", challenge)
                        .put("code_challenge_method", "S256");
                Response startResp = send("POST", "/oauth/device/code", clientId, null, req.toString());
                if (startResp.code != 200) throw new IOException("Trakt HTTP " + startResp.code);
                JSONObject start = new JSONObject(startResp.body);
                String userCode = start.getString("user_code");
                String verifyUrl = start.optString("verification_url", "https://trakt.tv/activate");
                String deviceCode = start.getString("device_code");
                long intervalMs = Math.max(5L, start.optLong("interval", 5L)) * 1000L;
                long expiresAt = System.currentTimeMillis() + start.optLong("expires_in", 600L) * 1000L;
                listener.onCodeReady(verifyUrl, userCode);

                while (System.currentTimeMillis() < expiresAt) {
                    Thread.sleep(intervalMs);
                    JSONObject poll = new JSONObject()
                            .put("code", deviceCode)
                            .put("client_id", clientId)
                            .put("code_verifier", verifier);
                    Response r = send("POST", "/oauth/device/token", clientId, null, poll.toString());
                    if (r.code == 200) {
                        JSONObject tok = new JSONObject(r.body);
                        saveTokenResponse(app, tok);
                        String user = fetchUsername(clientId, tok.optString("access_token", ""));
                        listener.onSuccess(user.isEmpty() ? "Trakt" : user);
                        return;
                    }
                    if (r.code == 400) continue;
                    if (r.code == 429) {
                        intervalMs += 1000L;
                        continue;
                    }
                    if (r.code == 418) {
                        listener.onError("Přihlášení bylo na Trakt odmítnuto.");
                        return;
                    }
                    if (r.code == 410) break;
                    throw new IOException("Trakt HTTP " + r.code);
                }
                listener.onError("Platnost kódu vypršela.");
            } catch (Exception e) {
                Log.w(TAG, "Trakt sign-in error", e);
                listener.onError("Chyba Trakt: " + e.getMessage());
            } finally {
                signingIn = false;
            }
        }, "trakt-signin").start();
    }

    /** Loads watched IDs and watchlist TMDB keys off the main thread. Returns empty data if not signed in. */
    static AccountData fetchAccountData(Context ctx) {
        AccountData data = new AccountData();
        if (!isSignedIn(ctx)) return data;
        try {
            String clientId = FeaturePrefs.traktClientId(ctx);
            String token = validAccessToken(ctx, clientId);
            if (token == null) return data;

            // Watched movies & shows
            parseWatchedArray(send("GET", "/sync/watched/movies", clientId, token, null), "movie", data.watchedKeys);
            parseWatchedArray(send("GET", "/sync/watched/shows", clientId, token, null), "tv", data.watchedKeys);

            // Watchlist movies & shows (first 16)
            parseWatchlistArray(send("GET", "/sync/watchlist/movies?limit=10", clientId, token, null),
                    "movie", data.watchlistKeys);
            parseWatchlistArray(send("GET", "/sync/watchlist/shows?limit=10", clientId, token, null),
                    "tv", data.watchlistKeys);
        } catch (Exception e) {
            Log.w(TAG, "Trakt sync skipped: " + e.getMessage());
        }
        return data;
    }

    private static void parseWatchedArray(Response resp, String kind, Set<String> out) {
        if (resp.code != 200) return;
        String jsonKey = "movie".equals(kind) ? "movie" : "show";
        try {
            JSONArray arr = new JSONArray(resp.body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i).optJSONObject(jsonKey);
                JSONObject ids = item == null ? null : item.optJSONObject("ids");
                int tmdb = ids == null ? 0 : ids.optInt("tmdb", 0);
                if (tmdb > 0) out.add(kind + ":" + tmdb);
            }
        } catch (Exception ignored) {
        }
    }

    private static void parseWatchlistArray(Response resp, String kind, List<String> out) {
        if (resp.code != 200) return;
        String jsonKey = "movie".equals(kind) ? "movie" : "show";
        try {
            JSONArray arr = new JSONArray(resp.body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i).optJSONObject(jsonKey);
                JSONObject ids = item == null ? null : item.optJSONObject("ids");
                int tmdb = ids == null ? 0 : ids.optInt("tmdb", 0);
                if (tmdb > 0) out.add(kind + ":" + tmdb);
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static synchronized String validAccessToken(Context ctx, String clientId) throws Exception {
        String access = FeaturePrefs.traktAccessToken(ctx);
        String refresh = FeaturePrefs.traktRefreshToken(ctx);
        long expiresAt = FeaturePrefs.traktExpiresAt(ctx);
        if (!access.isEmpty() && System.currentTimeMillis() + 120_000L < expiresAt) {
            return access;
        }
        if (refresh.isEmpty() || clientId.isEmpty()) return null;
        JSONObject req = new JSONObject()
                .put("refresh_token", refresh)
                .put("client_id", clientId)
                .put("grant_type", "refresh_token");
        Response r = send("POST", "/oauth/token", clientId, null, req.toString());
        if (r.code != 200) return null;
        JSONObject tok = new JSONObject(r.body);
        saveTokenResponse(ctx, tok);
        return tok.optString("access_token", null);
    }

    private static void saveTokenResponse(Context ctx, JSONObject tok) {
        long createdSec = tok.optLong("created_at", System.currentTimeMillis() / 1000L);
        long expiresInSec = tok.optLong("expires_in", 86400L);
        FeaturePrefs.saveTraktTokens(
                ctx,
                tok.optString("access_token", ""),
                tok.optString("refresh_token", ""),
                (createdSec + expiresInSec) * 1000L);
    }

    private static String fetchUsername(String clientId, String accessToken) {
        try {
            Response r = send("GET", "/users/settings", clientId, accessToken, null);
            if (r.code == 200) {
                JSONObject u = new JSONObject(r.body).optJSONObject("user");
                if (u != null) return u.optString("username", "");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static Response send(String method, String path, String clientId,
                                 @Nullable String bearer, @Nullable String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("trakt-api-version", "2");
        conn.setRequestProperty("trakt-api-key", clientId);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        try {
            if (json != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (stream != null) {
                try (InputStream in = stream) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        if (buf.size() + n > 2 * 1024 * 1024) break;
                        buf.write(chunk, 0, n);
                    }
                    body = buf.toString("UTF-8");
                }
            }
            return new Response(code, body);
        } finally {
            conn.disconnect();
        }
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
