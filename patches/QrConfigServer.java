package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ephemeral 5-minute LAN HTTP server bound strictly to the TV's private Wi-Fi/Ethernet IPv4
 * address with a one-time random URL path token. Lets the user scan a QR code on their phone
 * to configure remote button mappings, custom app labels & hidden apps, weather city/coordinates,
 * upload a custom wallpaper, export/import {@code config.json}, or paste API keys for TMDB,
 * Trakt, Plex, Home Assistant, Immich, and GitHub Updates without typing on a TV remote.
 */
final class QrConfigServer {

    private static final String TAG = "QrConfigServer";
    static final long TIMEOUT_MS = 5L * 60L * 1000L;
    private static final int MAX_HEAD = 8192;
    private static final int MAX_BODY = 96 * 1024;
    private static final int MAX_CONFIG_JSON = 64 * 1024;
    private static final int MAX_WALLPAPER_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REQUESTS = 160;
    private static final int MAX_CONCURRENT = 4;
    private static final long READ_DEADLINE_NS = 25_000_000_000L;

    interface Listener {
        void onSaved(String summary);
        void onClosed(boolean expired);
    }

    private final Context appCtx;
    private final ServerSocket server;
    private final String path;
    final String url;
    private final Listener listener;
    private final AtomicInteger activeConns = new AtomicInteger();
    private volatile boolean closed;

    QrConfigServer(Context ctx, InetAddress lanIp, Listener listener) throws IOException {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        byte[] rnd = new byte[12];
        new SecureRandom().nextBytes(rnd);
        this.path = "/" + Base64.encodeToString(rnd, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        this.server = new ServerSocket(0, 4, lanIp);
        this.url = "http://" + lanIp.getHostAddress() + ":" + server.getLocalPort() + path;
        Thread t = new Thread(this::serveLoop, "tvhome-qr-server");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    static InetAddress findLanAddress(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            List<Network> networks = new ArrayList<>();
            if (active != null) networks.add(active);
            for (Network n : cm.getAllNetworks()) {
                if (!n.equals(active)) networks.add(n);
            }
            for (Network n : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    continue;
                }
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "LAN address lookup failed", e);
        }
        return null;
    }

    private void serveLoop() {
        long until = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
        boolean expired = false;
        int requests = 0;
        try {
            while (!closed) {
                long leftMs = (until - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0 || requests >= MAX_REQUESTS) {
                    expired = true;
                    break;
                }
                server.setSoTimeout((int) Math.min(leftMs, 20_000L));
                Socket sock;
                try {
                    sock = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                requests++;
                if (activeConns.incrementAndGet() > MAX_CONCURRENT) {
                    activeConns.decrementAndGet();
                    try { sock.close(); } catch (IOException ignored) { }
                    continue;
                }
                Thread t = new Thread(() -> handleClient(sock), "tvhome-qr-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (!closed) Log.w(TAG, "QR server stopped: " + e.getMessage());
        } finally {
            close();
            listener.onClosed(expired);
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15_000);
            handleRequest(s.getInputStream(), s.getOutputStream(), System.nanoTime() + READ_DEADLINE_NS);
        } catch (IOException | RuntimeException ignored) {
        } finally {
            activeConns.decrementAndGet();
        }
    }

    private void handleRequest(InputStream in, OutputStream out, long deadline) throws IOException {
        String head = readHead(in, deadline);
        if (head == null) return;
        int crlf = head.indexOf("\r\n");
        String[] reqLine = (crlf > 0 ? head.substring(0, crlf) : head).split(" ");
        String method = reqLine.length > 0 ? reqLine[0] : "";
        String target = reqLine.length > 1 ? reqLine[1] : "";
        int q = target.indexOf('?');
        if (q >= 0) target = target.substring(0, q);

        // Route 1: Download current config.json
        if ((path + "/config.json").equals(target) && "GET".equals(method)) {
            byte[] json = readConfigJsonBytes();
            respondDownload(out, "200 OK", "application/json; charset=utf-8", "config.json", json);
            return;
        }
        // Route 2: Upload wallpaper image directly from phone gallery
        if ((path + "/wallpaper").equals(target) && "POST".equals(method)) {
            handleWallpaperUpload(in, out, head, deadline);
            return;
        }
        // Route 3: Import raw config.json body
        if ((path + "/config.json").equals(target) && "POST".equals(method)) {
            handleConfigJsonUpload(in, out, head, deadline);
            return;
        }
        // Route 4: Main form page
        if (!path.equals(target)) {
            respond(out, "404 Not Found", "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            byte[] html = "HEAD".equals(method) ? new byte[0] : renderFormHtml();
            respond(out, "200 OK", "text/html; charset=utf-8", html);
            return;
        }
        if (!"POST".equals(method)) {
            respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        byte[] body = readBody(in, contentLength(head), MAX_BODY, deadline);
        if (body == null) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        Map<String, String> fields = parseForm(new String(body, StandardCharsets.UTF_8));
        String error = applyFields(fields);
        if (error != null) {
            respond(out, "400 Bad Request", "text/html; charset=utf-8", renderResultHtml(false, error));
            return;
        }
        String summary = buildSavedSummary(fields);
        respond(out, "200 OK", "text/html; charset=utf-8", renderResultHtml(true, summary));
        listener.onSaved(summary);
    }

    private void handleWallpaperUpload(InputStream in, OutputStream out, String head, long deadline) throws IOException {
        int len = contentLength(head);
        if (len <= 0 || len > MAX_WALLPAPER_BYTES) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8",
                    "Image must be between 1 B and 10 MB.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] img = readBody(in, len, MAX_WALLPAPER_BYTES, deadline);
        if (img == null || !isValidImageMagic(img)) {
            respond(out, "400 Bad Request", "text/plain; charset=utf-8",
                    "Unsupported image format (use JPG, PNG, or WebP).".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File dir = Config.dir(appCtx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            respond(out, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "External storage unavailable.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File dst = new File(dir, Config.WALLPAPER_NAME);
        File tmp = new File(dir, Config.WALLPAPER_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(img);
            fos.getFD().sync();
        }
        if (dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.delete();
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            respond(out, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "Could not save wallpaper.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // Clear any explicit "none" / collection override so the uploaded wallpaper shows immediately on Home.
        SharedPreferences choices = appCtx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit()
                .remove(Config.WALLPAPER_CHOICE_KEY)
                .putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L)
                .apply();
        Config.invalidateCache();
        FeaturePrefs.bumpRevision(appCtx);
        String msg = "Wallpaper updated (" + (img.length / 1024) + " KB)";
        respond(out, "200 OK", "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
        listener.onSaved(msg);
    }

    private void handleConfigJsonUpload(InputStream in, OutputStream out, String head, long deadline) throws IOException {
        int len = contentLength(head);
        if (len <= 0 || len > MAX_CONFIG_JSON) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8",
                    "config.json must be <= 64 KB.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] raw = readBody(in, len, MAX_CONFIG_JSON, deadline);
        if (raw == null) {
            respond(out, "400 Bad Request", "text/plain; charset=utf-8",
                    "Empty config.json.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (!isValidJsonObjectSyntax(text)) {
            respond(out, "400 Bad Request", "text/plain; charset=utf-8",
                    "Invalid JSON object.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            new JSONObject(text);
        } catch (Exception e) {
            respond(out, "400 Bad Request", "text/plain; charset=utf-8",
                    ("Invalid JSON: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
            return;
        }
        File dir = Config.dir(appCtx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            respond(out, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "External storage unavailable.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File dst = new File(dir, Config.FILE_NAME);
        File tmp = new File(dir, Config.FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        if (dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.delete();
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            respond(out, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "Could not write config.json.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        SharedPreferences choices = appCtx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit()
                .putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L)
                .apply();
        Config.invalidateCache();
        HomeKeyService.reloadRemoteKeys();
        String msg = "config.json imported (" + text.length() + " B)";
        respond(out, "200 OK", "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
        listener.onSaved(msg);
    }

    private byte[] readConfigJsonBytes() {
        File dir = Config.dir(appCtx);
        File f = dir != null ? new File(dir, Config.FILE_NAME) : null;
        if (f != null && f.isFile() && f.length() > 0 && f.length() <= MAX_CONFIG_JSON) {
            try (FileInputStream fis = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch (IOException ignored) {
            }
        }
        return "{}\n".getBytes(StandardCharsets.UTF_8);
    }

    @Nullable
    private String applyFields(Map<String, String> f) {
        String haUrl = val(f, "ha_url");
        if (!haUrl.isEmpty() && !FeaturePrefs.isAllowedServerUrl(haUrl)) {
            return "Invalid Home Assistant URL (HTTPS or private LAN HTTP only).";
        }
        String immichUrl = val(f, "immich_url");
        if (!immichUrl.isEmpty() && !FeaturePrefs.isAllowedServerUrl(immichUrl)) {
            return "Invalid Immich URL (HTTPS or private LAN HTTP only).";
        }
        String plexUrl = val(f, "plex_url");
        if (!plexUrl.isEmpty() && !FeaturePrefs.isAllowedServerUrl(plexUrl)) {
            return "Invalid Plex URL (HTTPS or private LAN HTTP only).";
        }
        String wLat = val(f, "weather_lat");
        String wLon = val(f, "weather_lon");
        if ((!wLat.isEmpty() || !wLon.isEmpty())
                && (!FeaturePrefs.isValidCoordinate(wLat, -90.0, 90.0)
                || !FeaturePrefs.isValidCoordinate(wLon, -180.0, 180.0))) {
            return "Invalid latitude (-90..90) or longitude (-180..180).";
        }

        boolean remoteChanged = false;
        String delKey = val(f, "rm_delete_key");
        if (!delKey.isEmpty()) {
            FeaturePrefs.removeCustomRemoteMapping(appCtx, delKey);
            remoteChanged = true;
        }
        String rk = val(f, "rm_key");
        if (!rk.isEmpty()) {
            String rLabel = val(f, "rm_label");
            String rTap = val(f, "rm_tap");
            String rDbl = val(f, "rm_double");
            String rHold = val(f, "rm_hold");
            FeaturePrefs.RemoteMapping m = new FeaturePrefs.RemoteMapping(rk, rLabel, rTap, rDbl, rHold);
            if (m.isEmpty()) FeaturePrefs.removeCustomRemoteMapping(appCtx, rk);
            else FeaturePrefs.putCustomRemoteMapping(appCtx, m);
            remoteChanged = true;
        }
        if (remoteChanged) {
            HomeKeyService.reloadRemoteKeys();
        }

        if (f.containsKey("weather_city") || f.containsKey("weather_lat") || f.containsKey("weather_lon")) {
            String wCity = val(f, "weather_city");
            if (!wCity.isEmpty() || !wLat.isEmpty() || !wLon.isEmpty()) {
                FeaturePrefs.setWeatherLocation(appCtx, wCity, wLat, wLon);
            }
        }
        if ("1".equals(val(f, "apply_app_overrides"))) {
            FeaturePrefs.saveCustomAppLabels(appCtx, parseAppLabelsText(f.get("app_labels_text")));
            FeaturePrefs.saveCustomHiddenApps(appCtx, parseHiddenAppsText(f.get("hidden_apps_text")));
            SharedPreferences choices = appCtx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                    choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L).apply();
        }

        if (f.containsKey("tmdb_key")) {
            String v = val(f, "tmdb_key");
            if (!v.isEmpty()) FeaturePrefs.setTmdbKey(appCtx, v);
        }
        if (f.containsKey("tmdb_region")) {
            String v = val(f, "tmdb_region");
            if (!v.isEmpty()) FeaturePrefs.setTmdbRegion(appCtx, v);
        }
        if (f.containsKey("watchmode_key")) {
            String v = val(f, "watchmode_key");
            if (!v.isEmpty()) FeaturePrefs.setWatchmodeKey(appCtx, v);
        }
        if (f.containsKey("trakt_client_id")) {
            String v = val(f, "trakt_client_id");
            if (!v.isEmpty()) FeaturePrefs.setTraktClientId(appCtx, v);
        }
        if (!plexUrl.isEmpty()) FeaturePrefs.setPlexUrl(appCtx, plexUrl);
        if (f.containsKey("plex_token")) {
            String v = val(f, "plex_token");
            if (!v.isEmpty()) FeaturePrefs.setPlexToken(appCtx, v);
        }
        if (!haUrl.isEmpty()) {
            FeaturePrefs.setHomeAssistantUrl(appCtx, haUrl);
            FeaturePrefs.setHomeAssistantEnabled(appCtx, true);
        }
        if (f.containsKey("ha_token")) {
            String v = val(f, "ha_token");
            if (!v.isEmpty()) FeaturePrefs.setHomeAssistantToken(appCtx, v);
        }
        if (f.containsKey("ha_entities")) {
            String v = val(f, "ha_entities");
            if (!v.isEmpty()) FeaturePrefs.setHomeAssistantEntities(appCtx, v);
        }
        if (!immichUrl.isEmpty()) {
            FeaturePrefs.setImmichUrl(appCtx, immichUrl);
            FeaturePrefs.setImmichEnabled(appCtx, true);
        }
        if (f.containsKey("immich_key")) {
            String v = val(f, "immich_key");
            if (!v.isEmpty()) FeaturePrefs.setImmichKey(appCtx, v);
        }
        if (f.containsKey("updater_repo")) {
            String v = val(f, "updater_repo");
            if (!v.isEmpty() && v.contains("/")) FeaturePrefs.setUpdaterRepo(appCtx, v);
        }
        return null;
    }

    private String buildSavedSummary(Map<String, String> f) {
        List<String> updated = new ArrayList<>();
        if (!val(f, "rm_key").isEmpty() || !val(f, "rm_delete_key").isEmpty()) updated.add("Remote keys");
        if (!val(f, "weather_city").isEmpty() || !val(f, "weather_lat").isEmpty()) updated.add("Weather");
        if ("1".equals(val(f, "apply_app_overrides"))) updated.add("Apps & Labels");
        if (!val(f, "tmdb_key").isEmpty()) updated.add("TMDB");
        if (!val(f, "watchmode_key").isEmpty()) updated.add("Watchmode");
        if (!val(f, "trakt_client_id").isEmpty()) updated.add("Trakt");
        if (!val(f, "plex_url").isEmpty() || !val(f, "plex_token").isEmpty()) updated.add("Plex");
        if (!val(f, "ha_url").isEmpty() || !val(f, "ha_token").isEmpty() || !val(f, "ha_entities").isEmpty()) {
            updated.add("Home Assistant");
        }
        if (!val(f, "immich_url").isEmpty() || !val(f, "immich_key").isEmpty()) updated.add("Immich");
        if (!val(f, "updater_repo").isEmpty()) updated.add("GitHub Updates");
        if (updated.isEmpty()) return "Saved.";
        StringBuilder sb = new StringBuilder("Saved: ");
        for (int i = 0; i < updated.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(updated.get(i));
        }
        return sb.toString();
    }

    private byte[] renderFormHtml() {
        String haUrl = esc(FeaturePrefs.homeAssistantUrl(appCtx));
        String haEnt = esc(FeaturePrefs.homeAssistantEntities(appCtx));
        String immichUrl = esc(FeaturePrefs.immichUrl(appCtx));
        String plexUrl = esc(FeaturePrefs.plexUrl(appCtx));
        String region = esc(FeaturePrefs.tmdbRegion(appCtx));
        String repo = esc(FeaturePrefs.updaterRepo(appCtx));
        String wCity = esc(FeaturePrefs.weatherCity(appCtx));
        String wLat = esc(FeaturePrefs.weatherLatitude(appCtx));
        String wLon = esc(FeaturePrefs.weatherLongitude(appCtx));

        String tmdbHint = FeaturePrefs.tmdbKey(appCtx).isEmpty() ? "TMDB API key (v3 or Read Token)" : "Saved — leave blank to keep";
        String wmHint = FeaturePrefs.watchmodeKey(appCtx).isEmpty() ? "Watchmode API key (optional)" : "Saved — leave blank to keep";
        String traktHint = FeaturePrefs.traktClientId(appCtx).isEmpty() ? "Trakt Client ID (optional)" : "Saved — leave blank to keep";
        String haTokHint = FeaturePrefs.homeAssistantToken(appCtx).isEmpty() ? "Long-Lived Access Token" : "Saved — leave blank to keep";
        String immichHint = FeaturePrefs.immichKey(appCtx).isEmpty() ? "Immich API key" : "Saved — leave blank to keep";
        String plexTokHint = FeaturePrefs.plexToken(appCtx).isEmpty() ? "X-Plex-Token (optional)" : "Saved — leave blank to keep";

        // Build target dropdown options from live installed apps + Matcher actions
        StringBuilder targetOpts = new StringBuilder();
        targetOpts.append("<option value=''>— None —</option>");
        targetOpts.append("<optgroup label='Matcher &amp; TV Actions'>");
        for (RemoteMapperDialog.TargetOption o : RemoteMapperDialog.builtInActions(appCtx)) {
            targetOpts.append("<option value='").append(esc(o.target)).append("'>")
                    .append(esc(o.label)).append("</option>");
        }
        targetOpts.append("</optgroup><optgroup label='Installed Apps'>");
        List<RemoteMapperDialog.TargetOption> installed = RemoteMapperDialog.listInstalledApps(appCtx);
        StringBuilder appCatalogHint = new StringBuilder();
        for (RemoteMapperDialog.TargetOption o : installed) {
            targetOpts.append("<option value='").append(esc(o.target)).append("'>")
                    .append(esc(o.label)).append(" (").append(esc(o.target)).append(")</option>");
            appCatalogHint.append("<option value='").append(esc(o.target)).append("'>")
                    .append(esc(o.label)).append(" — ").append(esc(o.target)).append("</option>");
        }
        targetOpts.append("</optgroup>");

        StringBuilder currentKeysHtml = new StringBuilder();
        List<FeaturePrefs.RemoteMapping> currentMaps = FeaturePrefs.customRemoteMappings(appCtx);
        if (!currentMaps.isEmpty()) {
            currentKeysHtml.append("<div class='note' style='margin-bottom:8px'>Current custom buttons:</div>");
            for (FeaturePrefs.RemoteMapping m : currentMaps) {
                currentKeysHtml.append("<div class='chip'><b>").append(esc(m.label.isEmpty() ? m.keyToken : m.label))
                        .append("</b> (<code>").append(esc(m.keyToken)).append("</code>) — ");
                if (!m.tap.isEmpty()) currentKeysHtml.append("Tap: ").append(esc(m.tap)).append(" ");
                if (!m.dbl.isEmpty()) currentKeysHtml.append("2×: ").append(esc(m.dbl)).append(" ");
                if (!m.hold.isEmpty()) currentKeysHtml.append("Hold: ").append(esc(m.hold));
                currentKeysHtml.append("</div>");
            }
        }

        StringBuilder labelsText = new StringBuilder();
        for (Map.Entry<String, String> e : FeaturePrefs.customAppLabels(appCtx).entrySet()) {
            if (labelsText.length() > 0) labelsText.append('\n');
            labelsText.append(e.getKey()).append('=').append(e.getValue());
        }
        StringBuilder hiddenText = new StringBuilder();
        for (String pkg : FeaturePrefs.customHiddenApps(appCtx)) {
            if (hiddenText.length() > 0) hiddenText.append('\n');
            hiddenText.append(pkg);
        }

        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang='en'><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<title>Matcher — TV Phone Setup</title>")
                .append("<style>")
                .append("body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0e1218;color:#eef3fa;margin:0;padding:18px;}")
                .append(".wrap{max-width:600px;margin:0 auto;}")
                .append("h1{font-size:20px;margin:0 0 6px;font-weight:700;}")
                .append(".sub{font-size:13px;color:#9eb0c8;margin:0 0 16px;}")
                .append(".card{background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.14);border-radius:16px;padding:14px 16px;margin-bottom:14px;}")
                .append("h2{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:#8ab4f8;margin:0 0 10px;}")
                .append("label{display:block;font-size:13px;color:#cfd9e6;margin:8px 0 4px;}")
                .append("input,select,textarea{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:10px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.38);color:#fff;font-size:14px;}")
                .append("textarea{min-height:74px;font-family:monospace;font-size:12.5px;}")
                .append("button,.btn{display:block;width:100%;box-sizing:border-box;text-align:center;text-decoration:none;padding:13px;border-radius:14px;border:0;background:#3b82f6;color:#fff;font-size:15px;font-weight:600;margin-top:8px;cursor:pointer;}")
                .append(".btn2{background:rgba(255,255,255,0.12);border:1px solid rgba(255,255,255,0.2);}")
                .append(".row2{display:grid;grid-template-columns:1fr 1fr;gap:8px;}")
                .append(".note{font-size:12px;color:#8ea0b8;margin-top:6px;}")
                .append(".chip{font-size:12px;background:rgba(56,189,248,0.12);border:1px solid rgba(56,189,248,0.28);border-radius:10px;padding:7px 10px;margin-bottom:6px;}")
                .append("</style></head><body><div class='wrap'>")
                .append("<h1>Matcher — Configure TV from Phone</h1>")
                .append("<p class='sub'>Only changed fields are applied. Secrets left blank stay untouched.</p>")

                .append("<form method='POST'>")
                .append("<div class='card'><h2>Remote Buttons (Tap / Double / Hold)</h2>")
                .append(currentKeysHtml)
                .append("<label>Button token (e.g. scan:240 or KEYCODE_BUTTON_6)</label>")
                .append("<input name='rm_key' placeholder='scan:240 or KEYCODE_BUTTON_6'>")
                .append("<label>Label (optional)</label><input name='rm_label' placeholder='Netflix button'>")
                .append("<label>Tap action</label><select name='rm_tap'>").append(targetOpts).append("</select>")
                .append("<label>Double-tap action</label><select name='rm_double'>").append(targetOpts).append("</select>")
                .append("<label>Hold (0.5 s) action</label><select name='rm_hold'>").append(targetOpts).append("</select>")
                .append("<label>Remove custom button token (optional)</label><input name='rm_delete_key' placeholder='scan:240'>")
                .append("<div class='note'>Tip: Use Launcher Settings &rarr; Remote buttons &rarr; Learn button on TV to auto-detect any button&apos;s scancode.</div>")
                .append("</div>")

                .append("<div class='card'><h2>Weather &amp; Sunrise/Sunset Location</h2>")
                .append("<label>City name</label><input name='weather_city' value='").append(wCity).append("' placeholder='Prague'>")
                .append("<div class='row2'><div><label>Latitude</label><input name='weather_lat' value='").append(wLat).append("' placeholder='50.0755'></div>")
                .append("<div><label>Longitude</label><input name='weather_lon' value='").append(wLon).append("' placeholder='14.4378'></div></div>")
                .append("</div>")

                .append("<div class='card'><h2>Custom App Labels &amp; Hidden Apps</h2>")
                .append("<label>Quick package lookup</label><select id='pkgPick' onchange=\"if(this.value)document.getElementById('lblBox').value+=(document.getElementById('lblBox').value?'\\n':'')+this.value+'=';\">")
                .append("<option value=''>— Pick installed app to insert package —</option>").append(appCatalogHint).append("</select>")
                .append("<label>Custom labels (one per line: package=Name)</label>")
                .append("<textarea id='lblBox' name='app_labels_text'>").append(esc(labelsText.toString())).append("</textarea>")
                .append("<label>Hidden apps (one package per line)</label>")
                .append("<textarea name='hidden_apps_text'>").append(esc(hiddenText.toString())).append("</textarea>")
                .append("<label style='display:flex;align-items:center;gap:8px;margin-top:8px'><input type='checkbox' name='apply_app_overrides' value='1' style='width:auto'> Apply label &amp; hidden-app edits above</label>")
                .append("</div>")

                .append("<div class='card'><h2>Discover &amp; Streaming (TMDB / Trakt / Plex)</h2>")
                .append("<label>TMDB API key</label><input name='tmdb_key' type='password' autocomplete='off' placeholder='").append(tmdbHint).append("'>")
                .append("<label>Region (ISO-2, e.g. CZ, PL, DE, US)</label><input name='tmdb_region' value='").append(region).append("' maxlength='4'>")
                .append("<label>Watchmode API key</label><input name='watchmode_key' type='password' autocomplete='off' placeholder='").append(wmHint).append("'>")
                .append("<label>Trakt Client ID</label><input name='trakt_client_id' autocomplete='off' placeholder='").append(traktHint).append("'>")
                .append("<label>Plex Server URL</label><input name='plex_url' value='").append(plexUrl).append("' placeholder='http://192.168.1.x:32400'>")
                .append("<label>Plex Token</label><input name='plex_token' type='password' autocomplete='off' placeholder='").append(plexTokHint).append("'>")
                .append("</div>")

                .append("<div class='card'><h2>Home Assistant (Control Center)</h2>")
                .append("<label>Server URL</label><input name='ha_url' value='").append(haUrl).append("' placeholder='http://homeassistant.local:8123'>")
                .append("<label>Long-Lived Access Token</label><input name='ha_token' type='password' autocomplete='off' placeholder='").append(haTokHint).append("'>")
                .append("<label>Entities (max 8, comma-separated)</label><input name='ha_entities' value='").append(haEnt).append("' placeholder='light.living, switch.tv, scene.movie, camera.door'>")
                .append("</div>")

                .append("<div class='card'><h2>Immich Photos &amp; GitHub Updates</h2>")
                .append("<label>Immich URL</label><input name='immich_url' value='").append(immichUrl).append("' placeholder='http://192.168.1.x:2283'>")
                .append("<label>Immich API key</label><input name='immich_key' type='password' autocomplete='off' placeholder='").append(immichHint).append("'>")
                .append("<label>GitHub Releases repo (owner/repo)</label><input name='updater_repo' value='").append(repo).append("' placeholder='ryslavy/matcher'>")
                .append("</div>")

                .append("<button type='submit'>Send Settings to TV</button>")
                .append("</form>")

                .append("<div class='card' style='margin-top:14px'><h2>Wallpaper &amp; config.json</h2>")
                .append("<label>Upload custom wallpaper from phone (JPG / PNG / WebP, max 10 MB)</label>")
                .append("<input id='wpFile' type='file' accept='image/jpeg,image/png,image/webp'>")
                .append("<button type='button' class='btn2' onclick='uploadWp()'>Upload Wallpaper to TV</button>")
                .append("<div id='wpSt' class='note'></div>")
                .append("<hr style='border:0;border-top:1px solid rgba(255,255,255,0.12);margin:14px 0'>")
                .append("<a class='btn btn2' href='").append(esc(path)).append("/config.json'>Download current config.json</a>")
                .append("<label style='margin-top:10px'>Import config.json (paste JSON or pick file)</label>")
                .append("<input id='cfgFile' type='file' accept='.json,application/json' onchange='loadCfgFile(this)'>")
                .append("<textarea id='cfgTxt' placeholder='{\"dock_glass\": true, ...}' style='margin-top:6px'></textarea>")
                .append("<button type='button' class='btn2' onclick='uploadCfg()'>Import config.json to TV</button>")
                .append("<div id='cfgSt' class='note'></div>")
                .append("</div>")

                .append("<script>")
                .append("async function uploadWp(){const f=document.getElementById('wpFile').files[0];const st=document.getElementById('wpSt');if(!f){st.textContent='Choose an image first.';return;}st.textContent='Uploading…';const r=await fetch('").append(esc(path)).append("/wallpaper',{method:'POST',body:f});st.textContent=await r.text();}")
                .append("function loadCfgFile(inp){const f=inp.files[0];if(!f)return;const fr=new FileReader();fr.onload=()=>document.getElementById('cfgTxt').value=fr.result;fr.readAsText(f);}")
                .append("async function uploadCfg(){const t=document.getElementById('cfgTxt').value.trim();const st=document.getElementById('cfgSt');if(!t){st.textContent='Paste or choose config.json first.';return;}st.textContent='Importing…';const r=await fetch('").append(esc(path)).append("/config.json',{method:'POST',headers:{'Content-Type':'application/json'},body:t});st.textContent=await r.text();}")
                .append("</script></div></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] renderResultHtml(boolean ok, String message) {
        String color = ok ? "#4ade80" : "#f87171";
        String title = ok ? "Sent to TV \u2713" : "Error";
        String html = "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title>"
                + "<style>body{font-family:-apple-system,sans-serif;background:#0e1218;color:#eef3fa;padding:32px 20px;text-align:center;}"
                + "h1{color:" + color + ";font-size:22px;}p{color:#c4d2e4;font-size:15px;}</style></head>"
                + "<body><h1>" + title + "</h1><p>" + esc(message) + "</p>"
                + "<p style='font-size:13px;color:#8fa2bc'>You can close this page or go back to make more edits.</p></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    /** Pure-JVM parser for "pkg=Custom Label" lines from the Phone QR web form. */
    static Map<String, String> parseAppLabelsText(@Nullable String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int sep = s.indexOf('=');
            if (sep <= 0) sep = s.indexOf(':');
            if (sep <= 0) continue;
            String pkg = s.substring(0, sep).trim();
            String label = s.substring(sep + 1).trim();
            if (!pkg.isEmpty() && !label.isEmpty() && pkg.contains(".")) {
                out.put(pkg, label);
            }
        }
        return out;
    }

    /** Pure-JVM parser for newline/comma-separated hidden package names from the Phone QR web form. */
    static List<String> parseHiddenAppsText(@Nullable String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String token : raw.split("[\\r\\n,]+")) {
            String pkg = token.trim();
            if (!pkg.isEmpty() && pkg.contains(".") && !out.contains(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Pure-JVM magic-byte check for JPEG, PNG, or WebP wallpaper uploads. */
    static boolean isValidImageMagic(@Nullable byte[] b) {
        if (b == null || b.length < 12) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return true;
        // WebP: RIFF....WEBP
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** Quick structural guard before invoking Android's JSONObject parser. */
    static boolean isValidJsonObjectSyntax(@Nullable String text) {
        if (text == null) return false;
        String s = text.trim();
        return s.length() >= 2 && s.startsWith("{") && s.endsWith("}");
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8").trim();
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                map.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    @Nullable
    private static byte[] readBody(InputStream in, int len, int maxBytes, long deadline) throws IOException {
        if (len < 0 || len > maxBytes) return null;
        byte[] body = new byte[len];
        for (int n = 0; n < len; ) {
            int got = in.read(body, n, len - n);
            if (got < 0 || System.nanoTime() > deadline) throw new IOException("Truncated body");
            n += got;
        }
        return body;
    }

    private static String val(Map<String, String> map, String key) {
        String v = map.get(key);
        return v == null ? "" : v.trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @Nullable
    private static String readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while (matched < 4 && (b = in.read()) >= 0) {
            if (head.size() >= MAX_HEAD || System.nanoTime() > deadline) return null;
            head.write(b);
            if ((b == '\r' && (matched == 0 || matched == 2))
                    || (b == '\n' && (matched == 1 || matched == 3))) {
                matched++;
            } else {
                matched = (b == '\r') ? 1 : 0;
            }
        }
        return matched == 4 ? head.toString("ISO-8859-1") : null;
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return 0;
    }

    private static void respond(OutputStream out, String status, String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static void respondDownload(OutputStream out, String status, String contentType,
                                        String filename, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }
}
