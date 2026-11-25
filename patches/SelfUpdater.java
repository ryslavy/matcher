package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub Releases self-updater for {@code com.gathin.tvhome}.
 * Checks {@code https://api.github.com/repos/<owner>/<repo>/releases/latest} weekly or on demand,
 * verifies asset URL, size (<= 50 MB), SHA-256 digest (when provided by GitHub), newer versionCode,
 * and matching signing certificate against the installed APK, then streams into {@link PackageInstaller}.
 */
final class SelfUpdater {

    private static final String TAG = "SelfUpdater";
    private static final int JOB_ID = 4220;
    private static final long WEEK_MS = 7L * 24L * 3_600_000L;
    private static final long MAX_APK_BYTES = 50L << 20;
    private static final String ACTION_INSTALL_RESULT = "com.gathin.tvhome.UPDATE_INSTALL_RESULT";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvhome-updater");
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

    static final class CheckResult {
        final boolean updateAvailable;
        final String latestTag;
        @Nullable final String apkUrl;
        @Nullable final String expectedSha256;
        final long apkSize;
        @Nullable final String error;

        CheckResult(boolean updateAvailable, String latestTag, @Nullable String apkUrl,
                    @Nullable String expectedSha256, long apkSize, @Nullable String error) {
            this.updateAvailable = updateAvailable;
            this.latestTag = latestTag;
            this.apkUrl = apkUrl;
            this.expectedSha256 = expectedSha256;
            this.apkSize = apkSize;
            this.error = error;
        }
    }

    interface CheckCallback {
        void onChecked(CheckResult result);
    }

    interface InstallCallback {
        void onResult(boolean launchedInstaller, @Nullable String error);
    }

    private SelfUpdater() { }

    static void scheduleWeeklyIfEnabled(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!FeaturePrefs.isAutoUpdateEnabled(ctx)) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        try {
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, CheckJob.class))
                    .setPeriodic(WEEK_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to schedule weekly update check", e);
        }
    }

    static void checkNow(Context ctx, CheckCallback cb) {
        final Context app = ctx.getApplicationContext();
        final String repo = FeaturePrefs.updaterRepo(app);
        IO.execute(() -> {
            CheckResult res = checkSync(app, repo);
            postMain(() -> cb.onChecked(res));
        });
    }

    static void downloadAndInstall(Activity activity, CheckResult check, InstallCallback cb) {
        if (check.apkUrl == null || check.apkUrl.isEmpty()) {
            cb.onResult(false, "Chybí odkaz na APK.");
            return;
        }
        final Context app = activity.getApplicationContext();
        final String repo = FeaturePrefs.updaterRepo(app);
        if (!isValidReleaseDownloadUrl(repo, check.apkUrl)) {
            cb.onResult(false, "Neplatná doména stažení aktualizace.");
            return;
        }
        IO.execute(() -> {
            File apk = new File(app.getCacheDir(), "tvhome-update.apk");
            try {
                String actualSha = downloadAndHash(check.apkUrl, apk, MAX_APK_BYTES);
                if (check.expectedSha256 != null && !check.expectedSha256.isEmpty()
                        && !check.expectedSha256.equalsIgnoreCase(actualSha)) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    throw new IOException("SHA-256 nesouhlasí");
                }
                verifyApkSignatureAndVersion(app, apk);
                streamToPackageInstaller(app, apk);
                postMain(() -> cb.onResult(true, null));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                Log.w(TAG, "Update install failed", e);
                postMain(() -> cb.onResult(false, e.getMessage()));
            }
        });
    }

    /** Pure-JVM semantic version comparator ("v2.1.0" vs "2.0.9"). */
    static int compareVersions(@Nullable String a, @Nullable String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    static boolean isValidReleaseDownloadUrl(@Nullable String repo, @Nullable String url) {
        if (repo == null || repo.trim().isEmpty() || url == null) return false;
        String cleanRepo = repo.trim();
        if (!cleanRepo.matches("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) return false;
        String prefix = "https://github.com/" + cleanRepo + "/releases/download/";
        return url.startsWith(prefix) && url.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int[] parseVersionParts(@Nullable String v) {
        if (v == null) return new int[]{0};
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static CheckResult checkSync(Context app, String repo) {
        try {
            if (!repo.matches("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) {
                return new CheckResult(false, "", null, null, 0, "Neplatný název repozitáře");
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "CleanYourTV-MiTV");
            try {
                int code = conn.getResponseCode();
                if (code == 404) {
                    return new CheckResult(false, installedVersionName(app), null, null, 0, null);
                }
                if (code < 200 || code >= 300) {
                    return new CheckResult(false, "", null, null, 0, "GitHub HTTP " + code);
                }
                String body;
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    body = buf.toString("UTF-8");
                }
                JSONObject rel = new JSONObject(body);
                String tag = rel.optString("tag_name", "").trim();
                String installed = installedVersionName(app);
                boolean newer = !tag.isEmpty() && compareVersions(tag, installed) > 0;
                String apkUrl = null;
                String sha = null;
                long size = 0;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "").toLowerCase(Locale.ROOT);
                        if (!name.endsWith(".apk")) continue;
                        if (name.contains("tvhome") || name.contains("launcher") || apkUrl == null) {
                            apkUrl = a.optString("browser_download_url", null);
                            size = a.optLong("size", 0);
                            String digest = a.optString("digest", "");
                            if (digest.startsWith("sha256:")) sha = digest.substring("sha256:".length());
                        }
                    }
                }
                if (size > MAX_APK_BYTES) apkUrl = null;
                return new CheckResult(newer && apkUrl != null, tag.isEmpty() ? installed : tag, apkUrl, sha, size, null);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return new CheckResult(false, "", null, null, 0, e.getMessage());
        }
    }

    private static String downloadAndHash(String url, File dst, long maxBytes) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(25_000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Download HTTP " + code);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            File tmp = new File(dst.getPath() + ".tmp");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("APK exceeds 50 MB limit");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            if (dst.exists()) //noinspection ResultOfMethodCallIgnored
                dst.delete();
            if (!tmp.renameTo(dst)) throw new IOException("Failed to finalize downloaded APK");
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    private static void verifyApkSignatureAndVersion(Context app, File apk) throws Exception {
        PackageManager pm = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo current = pm.getPackageInfo(app.getPackageName(), flags);
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null) throw new IOException("Neplatný APK soubor");
        if (!app.getPackageName().equals(archive.packageName)) {
            throw new IOException("APK patří jiné aplikaci: " + archive.packageName);
        }
        long currentCode = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
        long archiveCode = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveCode < currentCode) {
            throw new IOException("Stažené APK má starší versionCode");
        }
        String installedCert = certSha256(current);
        String archiveCert = certSha256(archive);
        if (installedCert.isEmpty() || !installedCert.equals(archiveCert)) {
            throw new IOException("Podpisový certifikát APK nesouhlasí s nainstalovaným launcherem");
        }
    }

    @SuppressWarnings("deprecation")
    private static String certSha256(PackageInfo info) {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            SigningInfo si = info.signingInfo;
            sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        }
        if ((sigs == null || sigs.length == 0) && info.signatures != null) {
            sigs = info.signatures;
        }
        if (sigs == null || sigs.length == 0) return "";
        return sha256Hex(sigs[0].toByteArray());
    }

    private static void streamToPackageInstaller(Context app, File apk) throws IOException {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("tvhome_update", 0, apk.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cbIntent = new Intent(app, InstallReceiver.class).setAction(ACTION_INSTALL_RESULT);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(app, sessionId, cbIntent, piFlags);
            session.commit(pi.getIntentSender());
        }
    }

    static String installedVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "1.0" : pi.versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    public static final class CheckJob extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            checkNow(this, res -> jobFinished(params, res.error != null));
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }

    public static final class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(context, R.string.updater_installed, Toast.LENGTH_LONG).show();
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, msg != null ? msg : context.getString(R.string.updater_failed), Toast.LENGTH_LONG).show();
            }
        }
    }
}
