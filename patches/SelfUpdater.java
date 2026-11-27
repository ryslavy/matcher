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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub Releases OTA self-updater for {@code com.gathin.tvhome} and installed modular add-ons.
 * Checks {@code https://api.github.com/repos/<owner>/<repo>/releases/latest} (plus optional
 * {@code manifest.json} release asset), verifies asset URL domain, size (<= 50 MB), SHA-256 digest,
 * newer versionCode, and matching signing certificate against the installed package, then streams
 * into {@link PackageInstaller}. {@link UpdateCompletedReceiver} restores Accessibility helpers
 * automatically via {@link ServiceRepair} right after an in-place upgrade finishes.
 */
final class SelfUpdater {

    private static final String TAG = "SelfUpdater";
    private static final int JOB_ID = 4220;
    private static final long WEEK_MS = 7L * 24L * 3_600_000L;
    private static final long MAX_APK_BYTES = 50L << 20;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
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

    static final class AddonUpdate {
        final String id;
        final String packageName;
        final String displayName;
        final String version;
        final long versionCode;
        final String apkUrl;
        @Nullable final String expectedSha256;
        final long apkSize;

        AddonUpdate(String id, String packageName, String displayName, String version, long versionCode,
                    String apkUrl, @Nullable String expectedSha256, long apkSize) {
            this.id = id;
            this.packageName = packageName;
            this.displayName = displayName;
            this.version = version;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.expectedSha256 = expectedSha256;
            this.apkSize = apkSize;
        }
    }

    static final class CheckResult {
        final boolean updateAvailable;
        final String latestTag;
        @Nullable final String apkUrl;
        @Nullable final String expectedSha256;
        final long apkSize;
        final String releaseNotes;
        final List<AddonUpdate> addonUpdates;
        @Nullable final String error;

        CheckResult(boolean updateAvailable, String latestTag, @Nullable String apkUrl,
                    @Nullable String expectedSha256, long apkSize, @Nullable String error) {
            this(updateAvailable, latestTag, apkUrl, expectedSha256, apkSize, "",
                    Collections.emptyList(), error);
        }

        CheckResult(boolean updateAvailable, String latestTag, @Nullable String apkUrl,
                    @Nullable String expectedSha256, long apkSize, @Nullable String releaseNotes,
                    @Nullable List<AddonUpdate> addonUpdates, @Nullable String error) {
            this.updateAvailable = updateAvailable;
            this.latestTag = latestTag == null ? "" : latestTag;
            this.apkUrl = apkUrl;
            this.expectedSha256 = expectedSha256;
            this.apkSize = apkSize;
            this.releaseNotes = releaseNotes == null ? "" : releaseNotes;
            this.addonUpdates = addonUpdates == null ? Collections.emptyList() : addonUpdates;
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

    static void cancelScheduled(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js != null) {
            try {
                js.cancel(JOB_ID);
            } catch (RuntimeException ignored) {
            }
        }
    }

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
        downloadAndInstallTarget(activity, activity.getPackageName(), check.apkUrl, check.expectedSha256, cb);
    }

    static void downloadAndInstallAddon(Activity activity, AddonUpdate addon, InstallCallback cb) {
        downloadAndInstallTarget(activity, addon.packageName, addon.apkUrl, addon.expectedSha256, cb);
    }

    private static void downloadAndInstallTarget(Activity activity, String targetPackage,
                                                 @Nullable String apkUrl, @Nullable String expectedSha256,
                                                 InstallCallback cb) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            cb.onResult(false, "Missing APK download URL.");
            return;
        }
        final Context app = activity.getApplicationContext();
        final String repo = FeaturePrefs.updaterRepo(app);
        if (!isValidReleaseDownloadUrl(repo, apkUrl)) {
            cb.onResult(false, "Invalid update download domain.");
            return;
        }
        IO.execute(() -> {
            String safeName = targetPackage.replaceAll("[^A-Za-z0-9._-]", "_") + "-update.apk";
            File apk = new File(app.getCacheDir(), safeName);
            try {
                String actualSha = downloadAndHash(apkUrl, apk, MAX_APK_BYTES);
                if (expectedSha256 != null && !expectedSha256.isEmpty()
                        && !expectedSha256.equalsIgnoreCase(actualSha)) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    throw new IOException("SHA-256 mismatch");
                }
                verifyApkSignatureAndVersion(app, targetPackage, apk);
                streamToPackageInstaller(app, targetPackage, apk);
                postMain(() -> cb.onResult(true, null));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                Log.w(TAG, "Update install failed for " + targetPackage, e);
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
        return isValidReleaseAssetUrl(repo, url, ".apk");
    }

    static boolean isValidReleaseAssetUrl(@Nullable String repo, @Nullable String url, String suffix) {
        if (repo == null || repo.trim().isEmpty() || url == null || suffix == null) return false;
        String cleanRepo = repo.trim();
        if (!cleanRepo.matches("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) return false;
        String prefix = "https://github.com/" + cleanRepo + "/releases/download/";
        return url.startsWith(prefix) && url.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT));
    }

    /** Chooses the canonical Matcher launcher APK asset over add-on APKs when manifest.json is absent. */
    static boolean isPreferredLauncherAsset(@Nullable String assetName) {
        if (assetName == null) return false;
        String lower = assetName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".apk")) return false;
        if (lower.contains("addon")) return false;
        return lower.contains("matcher-launcher") || lower.contains("tvhome") || lower.contains("launcher");
    }

    static String truncateNotes(@Nullable String body, int maxChars) {
        if (body == null) return "";
        String clean = body.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (clean.length() <= maxChars) return clean;
        return clean.substring(0, Math.max(0, maxChars - 1)).trim() + "\u2026";
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
                return new CheckResult(false, "", null, null, 0, "Invalid repository name");
            }
            String body = httpGetString("https://api.github.com/repos/" + repo + "/releases/latest",
                    "application/vnd.github+json", 512 * 1024);
            if (body == null) {
                return new CheckResult(false, installedVersionName(app), null, null, 0, null);
            }
            JSONObject rel = new JSONObject(body);
            String tag = rel.optString("tag_name", "").trim();
            String notes = truncateNotes(rel.optString("body", ""), 420);
            String installed = installedVersionName(app);
            long installedCode = installedVersionCode(app, app.getPackageName());

            String manifestUrl = null;
            String apkUrl = null;
            String sha = null;
            long size = 0;
            boolean pickedPreferred = false;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    String lower = name.toLowerCase(Locale.ROOT);
                    String url = a.optString("browser_download_url", "");
                    if ("manifest.json".equals(lower) && isValidReleaseAssetUrl(repo, url, ".json")) {
                        manifestUrl = url;
                        continue;
                    }
                    if (!lower.endsWith(".apk")) continue;
                    boolean preferred = isPreferredLauncherAsset(name);
                    if (preferred || (!pickedPreferred && apkUrl == null && !lower.contains("addon"))) {
                        apkUrl = url;
                        size = a.optLong("size", 0);
                        String digest = a.optString("digest", "");
                        sha = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null;
                        if (preferred) pickedPreferred = true;
                    }
                }
            }

            List<AddonUpdate> addonUpdates = new ArrayList<>();
            long remoteCode = -1L;
            if (manifestUrl != null) {
                try {
                    String mRaw = httpGetString(manifestUrl, "application/json", MAX_MANIFEST_BYTES);
                    if (mRaw != null && !mRaw.trim().isEmpty()) {
                        JSONObject manifest = new JSONObject(mRaw);
                        String mNotes = manifest.optString("notes", "").trim();
                        if (!mNotes.isEmpty()) notes = truncateNotes(mNotes, 420);
                        JSONObject launcherObj = manifest.optJSONObject("launcher");
                        if (launcherObj != null) {
                            String mVer = launcherObj.optString("version", "").trim();
                            if (!mVer.isEmpty()) tag = mVer;
                            remoteCode = launcherObj.optLong("versionCode", -1L);
                            String mUrl = launcherObj.optString("url", "").trim();
                            if (isValidReleaseDownloadUrl(repo, mUrl)) {
                                apkUrl = mUrl;
                                sha = launcherObj.optString("sha256", sha);
                                size = launcherObj.optLong("size", size);
                            }
                        }
                        JSONArray addonsArr = manifest.optJSONArray("addons");
                        if (addonsArr != null) {
                            for (int i = 0; i < addonsArr.length(); i++) {
                                JSONObject ao = addonsArr.optJSONObject(i);
                                if (ao == null) continue;
                                String pkg = ao.optString("packageName", "").trim();
                                String aUrl = ao.optString("url", "").trim();
                                long aCode = ao.optLong("versionCode", 0L);
                                long aSize = ao.optLong("size", 0L);
                                if (pkg.isEmpty() || aCode <= 0L || aSize > MAX_APK_BYTES
                                        || !isValidReleaseDownloadUrl(repo, aUrl)) {
                                    continue;
                                }
                                long curAddonCode = installedVersionCode(app, pkg);
                                if (curAddonCode > 0L && aCode > curAddonCode) {
                                    addonUpdates.add(new AddonUpdate(
                                            ao.optString("id", pkg),
                                            pkg,
                                            ao.optString("name", pkg),
                                            ao.optString("version", tag),
                                            aCode,
                                            aUrl,
                                            ao.optString("sha256", null),
                                            aSize));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Optional manifest.json parse skipped: " + e.getMessage());
                }
            }

            boolean newer = (!tag.isEmpty() && compareVersions(tag, installed) > 0)
                    || (remoteCode > 0L && installedCode > 0L && remoteCode > installedCode);
            if (size > MAX_APK_BYTES) apkUrl = null;
            return new CheckResult(newer && apkUrl != null, tag.isEmpty() ? installed : tag,
                    apkUrl, sha, size, notes, addonUpdates, null);
        } catch (Exception e) {
            return new CheckResult(false, "", null, null, 0, e.getMessage());
        }
    }

    @Nullable
    private static String httpGetString(String url, String accept, int maxBytes) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("User-Agent", "Matcher-AndroidTV");
        try {
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code < 200 || code >= 300) throw new IOException("GitHub HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                int total = 0;
                while ((n = in.read(chunk)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Response too large");
                    buf.write(chunk, 0, n);
                }
                return buf.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
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
    private static void verifyApkSignatureAndVersion(Context app, String expectedPackage, File apk) throws Exception {
        PackageManager pm = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo current = pm.getPackageInfo(expectedPackage, flags);
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null) throw new IOException("Invalid APK file");
        if (!expectedPackage.equals(archive.packageName)) {
            throw new IOException("APK package mismatch: " + archive.packageName);
        }
        long currentCode = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
        long archiveCode = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveCode < currentCode) {
            throw new IOException("Downloaded APK has an older versionCode");
        }
        String installedCert = certSha256(current);
        String archiveCert = certSha256(archive);
        if (installedCert.isEmpty() || !installedCert.equals(archiveCert)) {
            throw new IOException("Signing certificate mismatch — installed build uses a different signing key");
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

    private static void streamToPackageInstaller(Context app, String targetPackage, File apk) throws IOException {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(targetPackage);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("matcher_update", 0, apk.length())) {
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
            if (BuildConfig.MATCHER_VERSION != null && !BuildConfig.MATCHER_VERSION.isEmpty()) {
                return BuildConfig.MATCHER_VERSION;
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "1.0.0" : pi.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    @SuppressWarnings("deprecation")
    private static long installedVersionCode(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception ignored) {
            return -1L;
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

    /** Automatically restores HomeKeyService (+ SleepService) right after an in-place OTA upgrade. */
    public static final class UpdateCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
            ServiceRepair.restore(context.getApplicationContext());
            scheduleWeeklyIfEnabled(context.getApplicationContext());
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
                ServiceRepair.restore(context.getApplicationContext());
                Toast.makeText(context, R.string.updater_installed, Toast.LENGTH_LONG).show();
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, msg != null ? msg : context.getString(R.string.updater_failed), Toast.LENGTH_LONG).show();
            }
        }
    }
}
