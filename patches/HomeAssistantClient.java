package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local Home Assistant REST API client for Control Center quick controls (up to 8 safe entities).
 * Strictly restricts allowed domains and cover device classes so locks, alarms, garage doors,
 * gates, and unclassified covers can never be operated from a shared TV remote.
 */
final class HomeAssistantClient {

    private static final String TAG = "HomeAssistantClient";
    static final int MAX_CONTROLS = 8;
    static final long REFRESH_INTERVAL_MS = 30_000L;

    static final Set<String> ALLOWED_DOMAINS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "light", "switch", "fan", "input_boolean", "cover",
            "scene", "script", "automation", "button", "input_button", "camera")));

    static final Set<String> SAFE_COVER_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "awning", "blind", "curtain", "shade", "shutter")));

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvhome-ha");
        t.setDaemon(true);
        return t;
    });
    @Nullable private static volatile Handler mainHandler;

    private static void postMain(Runnable r) {
        Handler h = mainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            mainHandler = h;
        }
        h.post(r);
    }

    static final class Control {
        final String entityId;
        final String domain;
        volatile String name;
        volatile String state = "";
        @Nullable volatile String deviceClass;

        Control(String entityId) {
            this.entityId = entityId;
            this.domain = entityId.substring(0, entityId.indexOf('.'));
            this.name = fallbackName(entityId);
        }

        boolean isActive() {
            if ("unavailable".equals(state) || "unknown".equals(state)) return false;
            switch (domain) {
                case "light":
                case "switch":
                case "fan":
                case "input_boolean":
                case "automation":
                    return "on".equals(state);
                case "cover":
                    return "open".equals(state) || "opening".equals(state);
                default:
                    return true;
            }
        }

        boolean isUnavailable() {
            return "unavailable".equals(state);
        }

        boolean isOffered() {
            if (!"cover".equals(domain)) return true;
            String dc = deviceClass;
            return dc != null && SAFE_COVER_CLASSES.contains(dc);
        }

        boolean isToggleable() {
            return "light".equals(domain) || "switch".equals(domain) || "fan".equals(domain)
                    || "input_boolean".equals(domain) || "cover".equals(domain) || "automation".equals(domain);
        }

        boolean isCamera() {
            return "camera".equals(domain);
        }
    }

    interface ActionCallback {
        void onDone(boolean ok);
    }

    private static final Object LOCK = new Object();
    private static String cachedConfigSig = "";
    private static List<Control> cachedControls = Collections.emptyList();

    private HomeAssistantClient() { }

    static boolean isReady(Context ctx) {
        if (!FeaturePrefs.isHomeAssistantEnabled(ctx)) return false;
        String url = FeaturePrefs.homeAssistantUrl(ctx);
        String token = FeaturePrefs.homeAssistantToken(ctx);
        if (!FeaturePrefs.isAllowedServerUrl(url) || token.isEmpty()) return false;
        return !parseEntities(FeaturePrefs.homeAssistantEntities(ctx)).isEmpty();
    }

    /** Pure-JVM entity parser enforcing strict domain allowlist and 8-entity cap. */
    static List<Control> parseEntities(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<Control> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String entity = part.trim().toLowerCase(Locale.ROOT);
            if (entity.isEmpty() || !seen.add(entity)) continue;
            if (!isAllowedEntityId(entity)) continue;
            out.add(new Control(entity));
            if (out.size() >= MAX_CONTROLS) break;
        }
        return out;
    }

    static boolean isAllowedEntityId(@Nullable String entityId) {
        if (entityId == null) return false;
        int dot = entityId.indexOf('.');
        if (dot <= 0 || dot >= entityId.length() - 1) return false;
        String domain = entityId.substring(0, dot);
        if (!ALLOWED_DOMAINS.contains(domain)) return false;
        String objectId = entityId.substring(dot + 1);
        for (int i = 0; i < objectId.length(); i++) {
            char ch = objectId.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ok) return false;
        }
        return true;
    }

    static boolean isCoverClassAllowed(@Nullable String deviceClass) {
        if (deviceClass == null) return false;
        return SAFE_COVER_CLASSES.contains(deviceClass.trim().toLowerCase(Locale.ROOT));
    }

    static List<Control> controls(Context ctx) {
        String sig = FeaturePrefs.homeAssistantUrl(ctx) + "|" + FeaturePrefs.homeAssistantEntities(ctx);
        synchronized (LOCK) {
            if (!sig.equals(cachedConfigSig)) {
                cachedConfigSig = sig;
                cachedControls = parseEntities(FeaturePrefs.homeAssistantEntities(ctx));
            }
            return new ArrayList<>(cachedControls);
        }
    }

    static List<Control> cameras(Context ctx) {
        List<Control> out = new ArrayList<>();
        for (Control ctl : controls(ctx)) {
            if (ctl.isCamera()) out.add(ctl);
        }
        return out;
    }

    /**
     * Fetches a live JPEG snapshot for a {@code camera.*} entity via Home Assistant's
     * {@code /api/camera_proxy/<entity_id>} endpoint (read-only). Bounds decode width to 1280 px
     * and pre-uploads the texture via {@link Bitmap#prepareToDraw()} for flicker-free PiP rendering.
     */
    @Nullable
    static Bitmap fetchCameraSnapshot(Context ctx, String entityId) {
        if (!isReady(ctx) || !isAllowedEntityId(entityId) || !entityId.startsWith("camera.")) return null;
        String baseUrl = trimSlash(FeaturePrefs.homeAssistantUrl(ctx));
        String token = FeaturePrefs.homeAssistantToken(ctx);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/api/camera_proxy/" + entityId).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "image/jpeg, image/png, image/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            byte[] data;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(65536);
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > 4 * 1024 * 1024) return null;
                    buf.write(chunk, 0, n);
                }
                data = buf.toByteArray();
            }
            if (data.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp != null) bmp.prepareToDraw();
            return bmp;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "HA camera snapshot failed for " + entityId + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static void refreshStates(Context ctx, @Nullable Runnable onUpdated) {
        if (!isReady(ctx)) return;
        final Context app = ctx.getApplicationContext();
        final String baseUrl = trimSlash(FeaturePrefs.homeAssistantUrl(app));
        final String token = FeaturePrefs.homeAssistantToken(app);
        final List<Control> list = controls(app);
        IO.execute(() -> {
            try {
                for (Control ctl : list) {
                    readState(baseUrl, token, ctl);
                }
            } catch (IOException e) {
                Log.w(TAG, "HA state refresh failed: " + e.getMessage());
                for (Control ctl : list) ctl.state = "";
            }
            if (onUpdated != null) postMain(onUpdated);
        });
    }

    static void activate(Context ctx, Control ctl, @Nullable ActionCallback cb) {
        if (!isReady(ctx) || ctl.isUnavailable() || !ctl.isOffered()) {
            if (cb != null) postMain(() -> cb.onDone(false));
            return;
        }
        final Context app = ctx.getApplicationContext();
        final String baseUrl = trimSlash(FeaturePrefs.homeAssistantUrl(app));
        final String token = FeaturePrefs.homeAssistantToken(app);
        final String before = ctl.state;
        if (ctl.isToggleable()) ctl.state = optimisticFlipped(ctl);
        IO.execute(() -> {
            boolean ok;
            try {
                String body = "{\"entity_id\":" + JSONObject.quote(ctl.entityId) + "}";
                request(baseUrl, token, "POST", "/api/services/" + servicePathFor(ctl.domain), body);
                ok = true;
            } catch (IOException e) {
                Log.w(TAG, "HA activate failed for " + ctl.entityId + ": " + e.getMessage());
                ctl.state = before;
                ok = false;
            }
            final boolean success = ok;
            if (cb != null) postMain(() -> cb.onDone(success));
            if (success) {
                try {
                    Thread.sleep(600L);
                    readState(baseUrl, token, ctl);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                }
                if (cb != null) postMain(() -> cb.onDone(true));
            }
        });
    }

    static String servicePathFor(String domain) {
        switch (domain) {
            case "scene":
            case "script":
                return domain + "/turn_on";
            case "button":
            case "input_button":
                return domain + "/press";
            default:
                return domain + "/toggle";
        }
    }

    private static String optimisticFlipped(Control ctl) {
        if ("cover".equals(ctl.domain)) return ctl.isActive() ? "closed" : "open";
        return ctl.isActive() ? "off" : "on";
    }

    private static void readState(String baseUrl, String token, Control ctl) throws IOException {
        try {
            String resp = request(baseUrl, token, "GET", "/api/states/" + ctl.entityId, null);
            JSONObject obj = new JSONObject(resp);
            ctl.state = obj.optString("state", "");
            JSONObject attrs = obj.optJSONObject("attributes");
            if (attrs != null) {
                String friendly = attrs.optString("friendly_name", "").trim();
                if (!friendly.isEmpty()) ctl.name = friendly;
                if ("cover".equals(ctl.domain)) {
                    ctl.deviceClass = attrs.optString("device_class", "").trim().toLowerCase(Locale.ROOT);
                }
            } else if ("cover".equals(ctl.domain)) {
                ctl.deviceClass = "";
            }
        } catch (NotFoundException e) {
            ctl.state = "unavailable";
        } catch (JSONException e) {
            throw new IOException("Invalid HA JSON for " + ctl.entityId);
        }
    }

    private static String request(String baseUrl, String token, String method, String path, @Nullable String jsonBody)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            if (code == 404) throw new NotFoundException();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > 512 * 1024) throw new IOException("HA response too large");
                    buf.write(chunk, 0, n);
                }
                return buf.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String fallbackName(String entityId) {
        int dot = entityId.indexOf('.');
        String raw = dot >= 0 ? entityId.substring(dot + 1) : entityId;
        String spaced = raw.replace('_', ' ').trim();
        if (spaced.isEmpty()) return entityId;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String trimSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static final class NotFoundException extends IOException { }
}
