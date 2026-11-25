package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight ambient data helper for the screensaver:
 * - Current weather via Open-Meteo (free, no API key) with 30-minute SharedPreferences cache.
 * - Next sunrise/sunset event computed offline from the configured GPS coordinates.
 * - Connected Bluetooth/USB audio output name via AudioManager (zero extra runtime permissions).
 * - Active VPN transport check.
 */
final class AmbientWeather {

    static final long CACHE_TTL_MS = 30L * 60_000L;
    private static final long STALE_MAX_MS = 6L * 60L * 60_000L;
    private static final String PREFS = "ambient_weather";
    private static final long FAIL_COOLDOWN_MS = 5L * 60_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static volatile long lastFailedMs;
    private static volatile Handler mainHandler;
    private static final Pattern P_TEMP = Pattern.compile("\"temperature_2m\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_FEELS = Pattern.compile("\"apparent_temperature\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_CODE = Pattern.compile("\"weather_code\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_IS_DAY = Pattern.compile("\"is_day\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    static final class Snapshot {
        final double tempC;
        final double feelsC;
        final int wmoCode;
        final boolean isDay;
        final long fetchedAtMs;

        Snapshot(double tempC, double feelsC, int wmoCode, boolean isDay, long fetchedAtMs) {
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.wmoCode = wmoCode;
            this.isDay = isDay;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private AmbientWeather() {}

    /** Delivers cached weather immediately when usable, and refreshes asynchronously if older than 30 min. */
    static void fetch(Context ctx, String lat, String lon, Consumer<Snapshot> callback) {
        if (TextUtils.isEmpty(lat) || TextUtils.isEmpty(lon)) return;
        Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        Snapshot cached = readCache(app, lat, lon, now);
        if (cached != null) {
            callback.accept(cached);
            if (now >= cached.fetchedAtMs && now - cached.fetchedAtMs < CACHE_TTL_MS) return;
        }
        long failedAt = lastFailedMs;
        if (failedAt > 0L && now >= failedAt && now - failedAt < FAIL_COOLDOWN_MS) return;
        if (!inFlight.compareAndSet(false, true)) return;
        WeakReference<Consumer<Snapshot>> callbackRef = new WeakReference<>(callback);
        IO.execute(() -> {
            try {
                Snapshot fresh = download(lat, lon);
                if (fresh != null) {
                    lastFailedMs = 0L;
                    writeCache(app, lat, lon, fresh);
                    Handler main = mainHandler;
                    if (main == null) {
                        main = new Handler(Looper.getMainLooper());
                        mainHandler = main;
                    }
                    main.post(() -> {
                        Consumer<Snapshot> cb = callbackRef.get();
                        if (cb != null) cb.accept(fresh);
                    });
                } else {
                    lastFailedMs = System.currentTimeMillis();
                }
            } finally {
                inFlight.set(false);
            }
        });
    }

    @Nullable
    static Snapshot parseJson(@Nullable String body, long nowMs) {
        if (body == null || body.isEmpty()) return null;
        int idx = body.indexOf("\"current\"");
        if (idx < 0) return null;
        int open = body.indexOf('{', idx);
        if (open < 0) return null;
        int close = body.indexOf('}', open);
        if (close < 0) return null;
        String section = body.substring(open, close + 1);
        Double temp = extractNumber(section, P_TEMP);
        if (temp == null) return null;
        Double feels = extractNumber(section, P_FEELS);
        Double code = extractNumber(section, P_CODE);
        Double isDay = extractNumber(section, P_IS_DAY);
        return new Snapshot(
                temp,
                feels != null ? feels : temp,
                code != null ? (int) Math.round(code) : 0,
                isDay == null || Math.round(isDay) != 0,
                nowMs);
    }

    @Nullable
    private static Double extractNumber(String jsonObject, Pattern p) {
        Matcher m = p.matcher(jsonObject);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static Snapshot download(String lat, String lon) {
        HttpURLConnection conn = null;
        try {
            String endpoint = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,apparent_temperature,weather_code,is_day&timezone=auto",
                    lat.trim(), lon.trim());
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("User-Agent", "CleanYourTV-Ambient/1.0");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder(512);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return parseJson(sb.toString(), System.currentTimeMillis());
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private static Snapshot readCache(Context ctx, String lat, String lon, long nowMs) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long ts = p.getLong("ts", 0L);
        if (ts <= 0L || nowMs < ts || nowMs - ts > STALE_MAX_MS) return null;
        if (!lat.equals(p.getString("lat", "")) || !lon.equals(p.getString("lon", ""))) return null;
        return new Snapshot(
                p.getFloat("temp", 0f),
                p.getFloat("feels", 0f),
                p.getInt("code", 0),
                p.getBoolean("day", true),
                ts);
    }

    private static void writeCache(Context ctx, String lat, String lon, Snapshot s) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lat", lat)
                .putString("lon", lon)
                .putFloat("temp", (float) s.tempC)
                .putFloat("feels", (float) s.feelsC)
                .putInt("code", s.wmoCode)
                .putBoolean("day", s.isDay)
                .putLong("ts", s.fetchedAtMs)
                .apply();
    }

    static int iconResForWmo(int wmoCode, boolean isDay) {
        if (wmoCode == 0) return isDay ? R.drawable.ic_weather_sun : R.drawable.ic_weather_moon;
        if (wmoCode == 1 || wmoCode == 2) return isDay ? R.drawable.ic_weather_partly : R.drawable.ic_weather_cloud;
        if (wmoCode == 3) return R.drawable.ic_weather_cloud;
        if (wmoCode == 45 || wmoCode == 48) return R.drawable.ic_weather_fog;
        if ((wmoCode >= 51 && wmoCode <= 67) || (wmoCode >= 80 && wmoCode <= 82)) return R.drawable.ic_weather_rain;
        if ((wmoCode >= 71 && wmoCode <= 77) || wmoCode == 85 || wmoCode == 86) return R.drawable.ic_weather_snow;
        if (wmoCode >= 95) return R.drawable.ic_weather_storm;
        return R.drawable.ic_weather_cloud;
    }

    static int conditionResForWmo(int wmoCode) {
        if (wmoCode == 0) return R.string.weather_clear;
        if (wmoCode == 1 || wmoCode == 2) return R.string.weather_partly;
        if (wmoCode == 3) return R.string.weather_cloudy;
        if (wmoCode == 45 || wmoCode == 48) return R.string.weather_fog;
        if (wmoCode >= 51 && wmoCode <= 57) return R.string.weather_drizzle;
        if ((wmoCode >= 61 && wmoCode <= 67)) return R.string.weather_rain;
        if ((wmoCode >= 71 && wmoCode <= 77) || wmoCode == 85 || wmoCode == 86) return R.string.weather_snow;
        if (wmoCode >= 80 && wmoCode <= 82) return R.string.weather_showers;
        if (wmoCode >= 95) return R.string.weather_storm;
        return R.string.weather_cloudy;
    }

    /** Returns {isSunrise (1 or 0), minuteOfDay} for the next upcoming solar transition. */
    static int[] nextSolarEvent(int nowMin, int sunriseMin, int sunsetMin) {
        if (nowMin < sunriseMin) return new int[]{1, sunriseMin};
        if (nowMin < sunsetMin) return new int[]{0, sunsetMin};
        return new int[]{1, sunriseMin};
    }

    static String formatHourMin(int minuteOfDay) {
        int clamped = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", clamped / 60, clamped % 60);
    }

    /** Returns the connected Bluetooth / USB headset or speaker name without needing BLUETOOTH_CONNECT. */
    @Nullable
    static String activeAudioDeviceName(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outputs == null) return null;
            for (AudioDeviceInfo dev : outputs) {
                int type = dev.getType();
                boolean wireless = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET;
                if (!wireless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    wireless = type == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
                }
                if (!wireless) continue;
                CharSequence raw = dev.getProductName();
                String name = raw != null ? raw.toString().trim() : "";
                if (!name.isEmpty() && !name.equalsIgnoreCase("Android") && !name.equalsIgnoreCase("MiTV")) {
                    return name;
                }
                return "Bluetooth Audio";
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    static boolean isVpnActive(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static String formatTemperature(double tempC) {
        return Math.round(tempC) + " \u00B0C";
    }
}
