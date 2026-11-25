package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ephemeral 5-minute LAN HTTP server bound strictly to the TV's private Wi-Fi/Ethernet IPv4
 * address with a one-time random URL path token. Lets the user scan a QR code on their phone
 * and paste API keys, Home Assistant / Immich / Plex URLs & tokens, or GitHub updater repo
 * without typing on a TV remote.
 */
final class QrConfigServer {

    private static final String TAG = "QrConfigServer";
    static final long TIMEOUT_MS = 5L * 60L * 1000L;
    private static final int MAX_HEAD = 8192;
    private static final int MAX_BODY = 16384;
    private static final int MAX_REQUESTS = 120;
    private static final int MAX_CONCURRENT = 4;
    private static final long READ_DEADLINE_NS = 15_000_000_000L;

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
            s.setSoTimeout(10_000);
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
        int len = contentLength(head);
        if (len < 0 || len > MAX_BODY) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        byte[] body = new byte[len];
        for (int n = 0; n < len; ) {
            int got = in.read(body, n, len - n);
            if (got < 0 || System.nanoTime() > deadline) throw new IOException("Truncated body");
            n += got;
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

    @Nullable
    private String applyFields(Map<String, String> f) {
        String haUrl = val(f, "ha_url");
        if (!haUrl.isEmpty() && !FeaturePrefs.isAllowedServerUrl(haUrl)) {
            return "Neplatná URL pro Home Assistant (povoleno HTTPS nebo lokální LAN HTTP).";
        }
        String immichUrl = val(f, "immich_url");
        if (!immichUrl.isEmpty() && !FeaturePrefs.isAllowedServerUrl(immichUrl)) {
            return "Neplatná URL pro Immich (povoleno HTTPS nebo lokální LAN HTTP).";
        }
        String plexUrl = val(f, "plex_url");
        if (!plexUrl.isEmpty() && !FeaturePrefs.isAllowedServerUrl(plexUrl)) {
            return "Neplatná URL pro Plex (povoleno HTTPS nebo lokální LAN HTTP).";
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
        if (!val(f, "tmdb_key").isEmpty()) updated.add("TMDB");
        if (!val(f, "watchmode_key").isEmpty()) updated.add("Watchmode");
        if (!val(f, "trakt_client_id").isEmpty()) updated.add("Trakt");
        if (!val(f, "plex_url").isEmpty() || !val(f, "plex_token").isEmpty()) updated.add("Plex");
        if (!val(f, "ha_url").isEmpty() || !val(f, "ha_token").isEmpty() || !val(f, "ha_entities").isEmpty()) {
            updated.add("Home Assistant");
        }
        if (!val(f, "immich_url").isEmpty() || !val(f, "immich_key").isEmpty()) updated.add("Immich");
        if (!val(f, "updater_repo").isEmpty()) updated.add("GitHub Updates");
        if (updated.isEmpty()) return "Uloženo (beze změn).";
        StringBuilder sb = new StringBuilder("Uloženo: ");
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
        String tmdbHint = FeaturePrefs.tmdbKey(appCtx).isEmpty() ? "TMDB API klíč (v3 nebo Read Token)" : "Uloženo — vyplňte jen pro změnu";
        String wmHint = FeaturePrefs.watchmodeKey(appCtx).isEmpty() ? "Watchmode API klíč (volitelné)" : "Uloženo — vyplňte jen pro změnu";
        String traktHint = FeaturePrefs.traktClientId(appCtx).isEmpty() ? "Trakt Client ID (volitelné)" : "Uloženo — vyplňte jen pro změnu";
        String haTokHint = FeaturePrefs.homeAssistantToken(appCtx).isEmpty() ? "Long-Lived Access Token" : "Token uložen — vyplňte jen pro změnu";
        String immichHint = FeaturePrefs.immichKey(appCtx).isEmpty() ? "Immich API klíč" : "Klíč uložen — vyplňte jen pro změnu";
        String plexTokHint = FeaturePrefs.plexToken(appCtx).isEmpty() ? "X-Plex-Token (volitelné)" : "Token uložen — vyplňte jen pro změnu";

        String html = "<!doctype html><html lang='cs'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>CleanYourTV — Nastavení z mobilu</title>"
                + "<style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0e1218;color:#eef3fa;margin:0;padding:18px;}"
                + ".wrap{max-width:560px;margin:0 auto;}"
                + "h1{font-size:20px;margin:0 0 6px;font-weight:700;}"
                + ".sub{font-size:13px;color:#9eb0c8;margin:0 0 16px;}"
                + ".card{background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.14);border-radius:16px;padding:14px 16px;margin-bottom:14px;}"
                + "h2{font-size:14px;text-transform:uppercase;letter-spacing:.06em;color:#8ab4f8;margin:0 0 10px;}"
                + "label{display:block;font-size:13px;color:#cfd9e6;margin:8px 0 4px;}"
                + "input{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:10px;border:1px solid rgba(255,255,255,0.18);"
                + "background:rgba(0,0,0,0.35);color:#fff;font-size:14px;}"
                + "button{width:100%;padding:14px;border-radius:14px;border:0;background:#3b82f6;color:#fff;font-size:16px;font-weight:600;margin-top:6px;}"
                + ".note{font-size:12px;color:#8ea0b8;margin-top:6px;}"
                + "</style></head><body><div class='wrap'>"
                + "<h1>Nastavení TV z mobilu</h1>"
                + "<p class='sub'>Vyplňte jen pole, která chcete nastavit nebo změnit. Prázdná pole zůstanou beze změny.</p>"
                + "<form method='POST'>"
                + "<div class='card'><h2>Tipy &amp; Obsah (TMDB / Trakt / Plex)</h2>"
                + "<label>TMDB API klíč</label><input name='tmdb_key' type='password' autocomplete='off' placeholder='" + tmdbHint + "'>"
                + "<label>Region (kód země, např. CZ, SK, US)</label><input name='tmdb_region' value='" + region + "' maxlength='4'>"
                + "<label>Watchmode API klíč (přímé odkazy do aplikací)</label><input name='watchmode_key' type='password' autocomplete='off' placeholder='" + wmHint + "'>"
                + "<label>Trakt Client ID</label><input name='trakt_client_id' autocomplete='off' placeholder='" + traktHint + "'>"
                + "<label>Plex Server URL (např. http://192.168.1.50:32400)</label><input name='plex_url' value='" + plexUrl + "' placeholder='http://192.168.1.x:32400'>"
                + "<label>Plex Token</label><input name='plex_token' type='password' autocomplete='off' placeholder='" + plexTokHint + "'>"
                + "</div>"
                + "<div class='card'><h2>Home Assistant (Ovládací centrum)</h2>"
                + "<label>Server URL</label><input name='ha_url' value='" + haUrl + "' placeholder='http://homeassistant.local:8123'>"
                + "<label>Long-Lived Access Token</label><input name='ha_token' type='password' autocomplete='off' placeholder='" + haTokHint + "'>"
                + "<label>Entity (max 8, oddělené čárkou)</label><input name='ha_entities' value='" + haEnt + "' placeholder='light.obyvak, switch.tv_led, scene.film'>"
                + "<div class='note'>Z bezpečnostních důvodů jsou povoleny jen light, switch, fan, input_boolean, okenní žaluzie/rolety, scene, script, automation a button (nikdy zámky ani garáž).</div>"
                + "</div>"
                + "<div class='card'><h2>Immich Fotky (Spořič obrazovky)</h2>"
                + "<label>Immich URL</label><input name='immich_url' value='" + immichUrl + "' placeholder='http://192.168.1.x:2283'>"
                + "<label>Immich API klíč</label><input name='immich_key' type='password' autocomplete='off' placeholder='" + immichHint + "'>"
                + "</div>"
                + "<div class='card'><h2>Aktualizace (GitHub Releases)</h2>"
                + "<label>Repozitář (owner/repo)</label><input name='updater_repo' value='" + repo + "'>"
                + "</div>"
                + "<button type='submit'>Odeslat do TV</button>"
                + "</form></div></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] renderResultHtml(boolean ok, String message) {
        String color = ok ? "#4ade80" : "#f87171";
        String title = ok ? "Odesláno do televize ✓" : "Chyba";
        String html = "<!doctype html><html lang='cs'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title>"
                + "<style>body{font-family:-apple-system,sans-serif;background:#0e1218;color:#eef3fa;padding:32px 20px;text-align:center;}"
                + "h1{color:" + color + ";font-size:22px;}p{color:#c4d2e4;font-size:15px;}</style></head>"
                + "<body><h1>" + title + "</h1><p>" + esc(message) + "</p>"
                + "<p style='font-size:13px;color:#8fa2bc'>Tuto stránku můžete zavřít.</p></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
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
}
