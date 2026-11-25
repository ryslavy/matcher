package com.gathin.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syncs up to 40 high-resolution (>= 1080p) personal photos every 6 hours from a user's
 * self-hosted Immich server into private app storage for the ambient screensaver.
 * Enforces {@link FeaturePrefs#isAllowedServerUrl(String)} (HTTPS or private LAN HTTP),
 * disables redirects, and scales large originals safely to fit 1080p without cropping portraits.
 */
final class ImmichSyncManager {

    private static final String TAG = "ImmichSyncManager";
    private static final int JOB_ID = 4210;
    private static final long PERIOD_MS = 6L * 3_600_000L;
    private static final int MAX_PHOTOS = 40;
    private static final int MIN_SHORT_SIDE = 1080;
    private static final long MAX_DOWNLOAD_BYTES = 24L << 20;
    private static final String DIR_NAME = "immich_photos";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvhome-immich");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean syncing;

    static final class Album {
        final String id;
        final String name;
        final int count;

        Album(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    interface AlbumsCallback {
        void onAlbums(List<Album> albums, @Nullable String error);
    }

    interface SyncCallback {
        void onComplete(int syncedCount, int totalCount, @Nullable String error);
    }

    private ImmichSyncManager() { }

    static boolean isConfigured(Context ctx) {
        return FeaturePrefs.isImmichEnabled(ctx)
                && FeaturePrefs.isAllowedServerUrl(FeaturePrefs.immichUrl(ctx))
                && !FeaturePrefs.immichKey(ctx).isEmpty();
    }

    static File photoDir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), DIR_NAME);
    }

    static List<File> listLocalPhotos(Context ctx) {
        if (!FeaturePrefs.isImmichEnabled(ctx)) return Collections.emptyList();
        File dir = photoDir(ctx);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg") && !name.startsWith("."));
        if (files == null || files.length == 0) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }

    static void scheduleIfEnabled(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!isConfigured(ctx)) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        try {
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, SyncJob.class))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to schedule Immich sync job", e);
        }
    }

    static void loadAlbums(Context ctx, AlbumsCallback cb) {
        final String baseUrl = trimSlash(FeaturePrefs.immichUrl(ctx));
        final String apiKey = FeaturePrefs.immichKey(ctx);
        if (!FeaturePrefs.isAllowedServerUrl(baseUrl) || apiKey.isEmpty()) {
            MAIN.post(() -> cb.onAlbums(Collections.emptyList(), "Immich není nakonfigurován"));
            return;
        }
        IO.execute(() -> {
            try {
                String json = requestText(baseUrl, apiKey, "GET", "/api/albums", null);
                JSONArray arr = new JSONArray(json);
                List<Album> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.getJSONObject(i);
                    int count = a.optInt("assetCount", 0);
                    if (count <= 0) continue;
                    out.add(new Album(a.optString("id"), a.optString("albumName", "Album"), count));
                }
                Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
                MAIN.post(() -> cb.onAlbums(out, null));
            } catch (Exception e) {
                Log.w(TAG, "Immich album list failed: " + e.getMessage());
                MAIN.post(() -> cb.onAlbums(Collections.emptyList(), e.getMessage()));
            }
        });
    }

    static void syncNow(Context ctx, @Nullable SyncCallback cb) {
        if (syncing) {
            if (cb != null) MAIN.post(() -> cb.onComplete(0, listLocalPhotos(ctx).size(), null));
            return;
        }
        final Context app = ctx.getApplicationContext();
        final String baseUrl = trimSlash(FeaturePrefs.immichUrl(app));
        final String apiKey = FeaturePrefs.immichKey(app);
        if (!FeaturePrefs.isAllowedServerUrl(baseUrl) || apiKey.isEmpty()) {
            if (cb != null) MAIN.post(() -> cb.onComplete(0, 0, "Immich není nakonfigurován"));
            return;
        }
        syncing = true;
        IO.execute(() -> {
            int added = 0;
            String err = null;
            try {
                added = runSync(app, baseUrl, apiKey);
            } catch (Exception e) {
                err = e.getMessage();
                Log.w(TAG, "Immich sync failed: " + err);
            } finally {
                syncing = false;
            }
            final int fAdded = added;
            final int fTotal = listLocalPhotos(app).size();
            final String fErr = err;
            if (cb != null) MAIN.post(() -> cb.onComplete(fAdded, fTotal, fErr));
        });
    }

    private static int runSync(Context app, String baseUrl, String apiKey) throws Exception {
        JSONObject query = new JSONObject()
                .put("size", MAX_PHOTOS * 2)
                .put("type", "IMAGE")
                .put("withExif", true);
        String albumId = FeaturePrefs.immichAlbumId(app);
        if (!albumId.isEmpty()) {
            query.put("albumIds", new JSONArray().put(albumId));
        }
        JSONArray items = new JSONArray(requestText(baseUrl, apiKey, "POST", "/api/search/random", query.toString()));
        File dir = photoDir(app);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create immich_photos directory");
        }
        int saved = 0;
        for (int i = 0; i < items.length() && saved < MAX_PHOTOS; i++) {
            JSONObject asset = items.getJSONObject(i);
            String id = asset.optString("id", "");
            if (!"IMAGE".equals(asset.optString("type")) || !id.matches("[0-9a-fA-F-]{8,64}")) continue;
            JSONObject exif = asset.optJSONObject("exifInfo");
            if (exif != null) {
                int w = exif.optInt("exifImageWidth", 0);
                int h = exif.optInt("exifImageHeight", 0);
                if (w > 0 && h > 0 && Math.min(w, h) < MIN_SHORT_SIDE) continue;
            }
            File target = new File(dir, "immich_" + id + ".jpg");
            if (target.exists() && target.length() > 8192) {
                //noinspection ResultOfMethodCallIgnored
                target.setLastModified(System.currentTimeMillis());
                saved++;
                continue;
            }
            File tmp = new File(dir, ".tmp_" + id);
            try {
                // Use Immich preview endpoint (already EXIF-rotated upright, crisp ~1440px, low RAM on 2GB TV box).
                downloadFile(baseUrl, apiKey, "/api/assets/" + id + "/thumbnail?size=preview", tmp);
                if (transcodeBoundedJpeg(tmp, target)) {
                    saved++;
                }
            } catch (IOException e) {
                Log.w(TAG, "Skip Immich photo " + id + ": " + e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        pruneOldest(dir, MAX_PHOTOS);
        return saved;
    }

    /** Decodes with safe downsampling to <= 1920px longest side (preserving portrait aspect ratio) and writes JPEG. */
    private static boolean transcodeBoundedJpeg(File src, File dst) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), bounds);
        if (bounds.outWidth < 640 || bounds.outHeight < 480) return false;
        int sample = 1;
        while ((bounds.outWidth / (sample * 2)) >= 1920 || (bounds.outHeight / (sample * 2)) >= 1080) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(src.getPath(), opts);
        if (bmp == null) return false;
        File outTmp = new File(dst.getPath() + ".part");
        try (FileOutputStream fos = new FileOutputStream(outTmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.getFD().sync();
        } finally {
            bmp.recycle();
        }
        if (dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.delete();
        return outTmp.renameTo(dst);
    }

    private static void pruneOldest(File dir, int maxKeep) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg") && !name.startsWith("."));
        if (files == null || files.length <= maxKeep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int toDelete = files.length - maxKeep;
        for (int i = 0; i < toDelete; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private static String requestText(String baseUrl, String apiKey, String method, String path, @Nullable String json)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (json != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Immich HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > 4 * 1024 * 1024) throw new IOException("Immich response too large");
                    buf.write(chunk, 0, n);
                }
                return buf.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadFile(String baseUrl, String apiKey, String path, File dst) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(18_000);
        conn.setRequestProperty("x-api-key", apiKey);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Immich asset HTTP " + code);
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Asset too large");
                    out.write(buf, 0, n);
                }
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

    /** Periodic 6-hour background sync job. */
    public static final class SyncJob extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            if (!isConfigured(this)) return false;
            syncNow(this, (added, total, error) -> jobFinished(params, error != null));
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }
}
