package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * On-demand & rotating 1920x1080 CC0/Unsplash web wallpaper fetcher backed by
 * {@code https://picsum.photos/1920/1080}.
 *
 * Keeps the active photo cached atomically under {@code filesDir/picsum/current.jpg} so cold starts
 * and offline boots render immediately on Frame 0 with zero black flash, while refreshes happen off
 * the UI thread with strict HTTPS-only redirect validation and magic-byte verification.
 */
final class PicsumWallpapers {

    private static final String TAG = "TvHome.Picsum";
    static final String CHOICE_PICSUM = "picsum";
    static final String ENDPOINT = "https://picsum.photos/1920/1080";
    static final int DEFAULT_ROTATION_MINUTES = 60;

    private static final String PREFS_NAME = "picsum_wallpapers";
    private static final String KEY_LAST_FETCH_MS = "last_fetch_ms";
    private static final String KEY_LAST_ID = "last_id";
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MIN_IMAGE_BYTES = 4 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "picsum-fetch");
        t.setDaemon(true);
        return t;
    });
    @Nullable
    private static volatile Handler mainHandler;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static Handler mainHandler() {
        Handler h = mainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            mainHandler = h;
        }
        return h;
    }

    private PicsumWallpapers() {
    }

    static File cacheDir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "picsum");
    }

    static File currentFile(Context ctx) {
        return new File(cacheDir(ctx), "current.jpg");
    }

    static boolean hasCached(Context ctx) {
        File f = currentFile(ctx);
        return f.isFile() && f.length() >= MIN_IMAGE_BYTES;
    }

    static long effectiveIntervalMs(int configRotationMinutes) {
        int mins = configRotationMinutes > 0 ? configRotationMinutes : DEFAULT_ROTATION_MINUTES;
        return mins * 60_000L;
    }

    static boolean isRefreshDue(Context ctx, long intervalMs) {
        if (!hasCached(ctx)) return true;
        if (intervalMs <= 0L) intervalMs = DEFAULT_ROTATION_MINUTES * 60_000L;
        long last = prefs(ctx).getLong(KEY_LAST_FETCH_MS, 0L);
        return WallpaperRotation.due(last, System.currentTimeMillis(), intervalMs);
    }

    static boolean isInFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * Fetches a fresh 1920x1080 photo from Picsum off the UI thread and delivers the updated cache
     * file (or {@code null} on failure) on the main thread. Concurrent calls coalesce cleanly.
     */
    static void fetchFreshAsync(Context ctx, @Nullable Consumer<File> onDone) {
        final Context app = ctx.getApplicationContext();
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            if (onDone != null) mainHandler().post(() -> onDone.accept(hasCached(app) ? currentFile(app) : null));
            return;
        }
        WORKER.execute(() -> {
            File result = null;
            try {
                result = fetchFreshBlocking(app);
            } finally {
                IN_FLIGHT.set(false);
            }
            final File finalFile = result;
            if (onDone != null) mainHandler().post(() -> onDone.accept(finalFile));
        });
    }

    @Nullable
    static File fetchFreshBlocking(Context ctx) {
        Context app = ctx.getApplicationContext();
        File dir = cacheDir(app);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create picsum cache directory");
            return null;
        }
        String url = ENDPOINT + "?t=" + System.currentTimeMillis();
        DownloadResult dl = downloadFollowingHttpsRedirects(url);
        if (dl == null || dl.bytes == null || dl.bytes.length < MIN_IMAGE_BYTES || !isValidImageMagic(dl.bytes)) {
            Log.w(TAG, "Picsum download failed or invalid image payload");
            return null;
        }
        File dst = currentFile(app);
        File tmp = new File(dir, "current.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(dl.bytes);
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.w(TAG, "Failed writing picsum wallpaper", e);
            return null;
        }
        if (dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.delete();
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.w(TAG, "Failed committing picsum wallpaper");
            return null;
        }
        SharedPreferences.Editor ed = prefs(app).edit()
                .putLong(KEY_LAST_FETCH_MS, System.currentTimeMillis());
        if (dl.picsumId != null && !dl.picsumId.isEmpty()) {
            ed.putString(KEY_LAST_ID, dl.picsumId);
        }
        ed.apply();
        return dst;
    }

    private static final class DownloadResult {
        final byte[] bytes;
        @Nullable final String picsumId;

        DownloadResult(byte[] bytes, @Nullable String picsumId) {
            this.bytes = bytes;
            this.picsumId = picsumId;
        }
    }

    @Nullable
    private static DownloadResult downloadFollowingHttpsRedirects(String startUrl) {
        String current = startUrl;
        String picsumId = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isAllowedHttpsUrl(current)) {
                Log.w(TAG, "Blocked non-HTTPS redirect: " + current);
                return null;
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "Matcher-TV/1.2");
                conn.setRequestProperty("Accept", "image/jpeg,image/webp,image/png,*/*;q=0.8");
                int code = conn.getResponseCode();
                String idHdr = conn.getHeaderField("Picsum-ID");
                if (idHdr != null && !idHdr.trim().isEmpty()) picsumId = idHdr.trim();

                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    String next = resolveRedirectUrl(current, loc);
                    if (next == null) return null;
                    current = next;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP " + code + " from " + current);
                    return null;
                }
                long cl = conn.getContentLengthLong();
                if (cl > MAX_IMAGE_BYTES) {
                    Log.w(TAG, "Image too large: " + cl);
                    return null;
                }
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(
                            cl > 0 && cl <= MAX_IMAGE_BYTES ? (int) cl : 128 * 1024);
                    byte[] buf = new byte[16 * 1024];
                    int total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_IMAGE_BYTES) return null;
                        out.write(buf, 0, n);
                    }
                    return new DownloadResult(out.toByteArray(), picsumId);
                }
            } catch (IOException e) {
                Log.w(TAG, "Picsum fetch I/O error: " + e.getMessage());
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        Log.w(TAG, "Too many redirects from picsum.photos");
        return null;
    }

    /** Pure-JVM helper: only permits HTTPS URLs with a non-empty host. */
    static boolean isAllowedHttpsUrl(@Nullable String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            URI u = URI.create(url.trim());
            return "https".equalsIgnoreCase(u.getScheme())
                    && u.getHost() != null
                    && !u.getHost().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Pure-JVM helper: resolves relative or absolute redirect Location against the current URL. */
    @Nullable
    static String resolveRedirectUrl(String baseUrl, @Nullable String location) {
        if (location == null || location.trim().isEmpty()) return null;
        try {
            URI resolved = URI.create(baseUrl).resolve(location.trim());
            String s = resolved.toString();
            return isAllowedHttpsUrl(s) ? s : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Pure-JVM helper: verifies JPEG, PNG, or WebP magic header bytes. */
    static boolean isValidImageMagic(@Nullable byte[] b) {
        if (b == null || b.length < 12) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        // WebP: RIFF....WEBP
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** Returns the last fetched Picsum photo ID if available (for subtle diagnostics/caption). */
    static String lastPhotoId(Context ctx) {
        String id = prefs(ctx).getString(KEY_LAST_ID, "");
        return id == null ? "" : id.trim();
    }

    /** Pure-JVM helper: checks whether a stored wallpaper choice string selects Picsum. */
    static boolean isChoicePicsum(@Nullable String choice) {
        return choice != null && CHOICE_PICSUM.equals(choice.trim().toLowerCase(Locale.ROOT));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
