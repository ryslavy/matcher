package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads recently opened TV apps in exact MRU order using Android's UsageStatsManager (GET_USAGE_STATS),
 * enriched with live MediaSession playback badges and local launch history fallback, plus background closing.
 */
final class RecentApps {

    private static final String PREFS_DISMISSED = "recents_dismissed";
    private static final String PREFS_LAUNCHED = "recents_launched";
    private static final long WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_ITEMS = 16;

    static final class Item {
        final String pkg;
        final String label;
        final long lastUsedMs;
        final Drawable banner;
        final Drawable icon;
        final int accent;
        final boolean playing;
        @Nullable
        final String mediaTrack;

        Item(String pkg, String label, long lastUsedMs, Drawable banner, Drawable icon,
             int accent, boolean playing, @Nullable String mediaTrack) {
            this.pkg = pkg;
            this.label = label;
            this.lastUsedMs = lastUsedMs;
            this.banner = banner;
            this.icon = icon;
            this.accent = accent;
            this.playing = playing;
            this.mediaTrack = mediaTrack;
        }
    }

    private RecentApps() { }

    /** Records an app launch from Home so Recents stays populated immediately even without UsageStats. */
    static void recordLaunch(Context ctx, @Nullable String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(ctx.getPackageName())) return;
        ctx.getSharedPreferences(PREFS_LAUNCHED, Context.MODE_PRIVATE)
                .edit().putLong(pkg, System.currentTimeMillis()).apply();
    }

    /** Suppresses synthetic UsageStats entries caused by automated AppForceStopper Settings opens. */
    static void suppressAutoSettingsEntry(Context ctx, @Nullable String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        ctx.getApplicationContext().getSharedPreferences(PREFS_DISMISSED, Context.MODE_PRIVATE)
                .edit().putLong(pkg, System.currentTimeMillis() + 1500L).apply();
    }

    /** Closes {@code pkg} (stopping active media and genuinely force-stopping via Accessibility when running). */
    static boolean closeApp(Context ctx, String pkg, @Nullable MediaSessionTracker mediaTracker,
                            boolean returnToRecents) {
        if (pkg == null || pkg.isEmpty()) return false;
        ctx.getSharedPreferences(PREFS_DISMISSED, Context.MODE_PRIVATE)
                .edit().putLong(pkg, System.currentTimeMillis()).apply();
        List<MediaController> controllers = mediaTracker != null ? mediaTracker.controllers() : null;
        if (!AppForceStopper.isProtectedAccessibilityPackage(ctx, pkg)) {
            terminatePackage(ctx.getSystemService(ActivityManager.class), pkg, controllers);
            // Fast silent loopback ADB force-stop if previously authorized (zero Settings flash).
            final Context app = ctx.getApplicationContext();
            new Thread(() -> {
                if (!LocalAdbClient.forceStopSilent(app, pkg)) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            AppForceStopper.forceStop(app, Collections.singletonList(pkg), returnToRecents, null));
                }
            }, "adb-force-stop").start();
            return true;
        }
        return AppForceStopper.forceStop(ctx, Collections.singletonList(pkg), returnToRecents, null);
    }

    static boolean closeAll(Context ctx, List<String> packages, @Nullable MediaSessionTracker mediaTracker,
                            @Nullable Runnable onComplete) {
        if (packages == null || packages.isEmpty()) return false;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS_DISMISSED, Context.MODE_PRIVATE).edit();
        for (String pkg : packages) {
            if (pkg != null && !pkg.isEmpty()) ed.putLong(pkg, now);
        }
        ed.apply();
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        List<MediaController> controllers = mediaTracker != null ? mediaTracker.controllers() : null;
        for (String pkg : packages) {
            if (!AppForceStopper.isProtectedAccessibilityPackage(ctx, pkg)) {
                terminatePackage(am, pkg, controllers);
            }
        }
        return AppForceStopper.forceStop(ctx, packages, false, onComplete);
    }

    private static void terminatePackage(@Nullable ActivityManager am, @Nullable String pkg,
                                         @Nullable List<MediaController> controllers) {
        if (pkg == null || pkg.isEmpty()) return;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (pkg.equals(c.getPackageName())) {
                    try {
                        c.getTransportControls().stop();
                    } catch (RuntimeException ignored) {
                        try {
                            c.getTransportControls().pause();
                        } catch (RuntimeException ignored2) {
                        }
                    }
                }
            }
        }
        if (am != null) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) {
            }
        }
    }

    static long freeRamMb(Context ctx) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null) return 0L;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024L * 1024L);
    }

    static boolean switchToApp(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        boolean isAddon = MainActivity.isGlassAddon(pkg);
        boolean overHome = MainActivity.isHomeVisible();
        if (isAddon) {
            if (overHome) MainActivity.setAddonModalOpen(true);
            MainActivity.attachAddonBackdropExtras(launch, overHome);
        }
        try {
            recordLaunch(ctx, pkg);
            ctx.startActivity(launch);
            return true;
        } catch (RuntimeException ignored) {
            if (isAddon && overHome) MainActivity.setAddonModalOpen(false);
            return false;
        }
    }

    /** Loads up to {@link #MAX_ITEMS} recent user apps sorted newest-first. Safe to call off the UI thread. */
    static List<Item> load(Context ctx, Map<String, String> appLabels,
                           @Nullable List<MediaController> mediaControllers) {
        long now = System.currentTimeMillis();
        String selfPkg = ctx.getPackageName();
        SharedPreferences dismissedPrefs = ctx.getSharedPreferences(PREFS_DISMISSED, Context.MODE_PRIVATE);
        SharedPreferences launchedPrefs = ctx.getSharedPreferences(PREFS_LAUNCHED, Context.MODE_PRIVATE);

        Map<String, Long> lastUsedByPkg = new HashMap<>();

        // 1. System UsageStatsManager events (most accurate: catches remote buttons, voice, cross-app launches).
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm != null) {
            try {
                UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
                if (events != null) {
                    UsageEvents.Event ev = new UsageEvents.Event();
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev);
                        int type = ev.getEventType();
                        if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                            String pkg = ev.getPackageName();
                            long ts = ev.getTimeStamp();
                            if (pkg != null && ts > 0L) {
                                Long prev = lastUsedByPkg.get(pkg);
                                if (prev == null || ts > prev) lastUsedByPkg.put(pkg, ts);
                            }
                        }
                    }
                }
                if (lastUsedByPkg.isEmpty()) {
                    List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now);
                    if (stats != null) {
                        for (UsageStats s : stats) {
                            String pkg = s.getPackageName();
                            long ts = s.getLastTimeUsed();
                            if (pkg != null && ts > now - WINDOW_MS) {
                                Long prev = lastUsedByPkg.get(pkg);
                                if (prev == null || ts > prev) lastUsedByPkg.put(pkg, ts);
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        // 2. Merge local launcher launch history (guarantees instant update right after launching from Home).
        for (Map.Entry<String, ?> entry : launchedPrefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof Long)) continue;
            long ts = (Long) entry.getValue();
            if (ts <= now - WINDOW_MS) continue;
            String pkg = entry.getKey();
            Long prev = lastUsedByPkg.get(pkg);
            if (prev == null || ts > prev) lastUsedByPkg.put(pkg, ts);
        }

        // 3. Merge live media sessions (so an app actively playing audio is always surfaced and tagged).
        Map<String, Boolean> mediaPlaying = new HashMap<>();
        Map<String, String> mediaTracks = new HashMap<>();
        if (mediaControllers != null) {
            for (MediaController mc : mediaControllers) {
                String pkg = mc.getPackageName();
                if (pkg == null) continue;
                PlaybackState ps = mc.getPlaybackState();
                boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
                MediaMetadata md = mc.getMetadata();
                String track = MediaSessionTracker.extractTitle(md);
                mediaPlaying.put(pkg, playing);
                if (!TextUtils.isEmpty(track)) mediaTracks.put(pkg, track);
                if (playing) {
                    Long prev = lastUsedByPkg.get(pkg);
                    if (prev == null || now > prev) lastUsedByPkg.put(pkg, now);
                } else if (!lastUsedByPkg.containsKey(pkg)) {
                    lastUsedByPkg.put(pkg, now - 60_000L);
                }
            }
        }

        // Build filtered & sorted candidate list.
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> leanbackByPkg = queryLeanbackMap(pm);
        Set<String> excluded = buildExcludedSet(selfPkg);
        List<Map.Entry<String, Long>> candidates = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastUsedByPkg.entrySet()) {
            String pkg = e.getKey();
            long usedMs = e.getValue();
            long dismissedAt = dismissedPrefs.getLong(pkg, 0L);
            boolean isPlaying = Boolean.TRUE.equals(mediaPlaying.get(pkg));
            if (!shouldInclude(pkg, excluded, usedMs, dismissedAt, isPlaying)) continue;
            if (!leanbackByPkg.containsKey(pkg) && !isLaunchableUserApp(pm, pkg)) continue;
            candidates.add(e);
        }

        candidates.sort((a, b) -> {
            boolean pa = Boolean.TRUE.equals(mediaPlaying.get(a.getKey()));
            boolean pb = Boolean.TRUE.equals(mediaPlaying.get(b.getKey()));
            return compareEntries(pa, a.getValue(), pb, b.getValue());
        });

        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, Long> e : candidates) {
            if (result.size() >= MAX_ITEMS) break;
            String pkg = e.getKey();
            String label = resolveLabel(pm, pkg, appLabels);
            Drawable banner = loadBanner(ctx, pm, pkg, leanbackByPkg.get(pkg));
            Drawable icon = banner == null ? loadIcon(pm, pkg) : null;
            int accent = AppAdapter.vividColor(banner != null ? banner : icon);
            boolean playing = Boolean.TRUE.equals(mediaPlaying.get(pkg));
            result.add(new Item(pkg, label, e.getValue(), banner, icon, accent, playing, mediaTracks.get(pkg)));
        }
        return result;
    }

    static Set<String> buildExcludedSet(String selfPkg) {
        Set<String> set = new HashSet<>();
        if (selfPkg != null) set.add(selfPkg);
        set.add("com.google.android.apps.tv.launcherx");
        set.add("com.google.android.tungsten.setupwraith");
        set.add("com.google.android.apps.tv.dreamx");
        set.add("com.android.systemui");
        set.add("com.google.android.packageinstaller");
        set.add("com.android.packageinstaller");
        return set;
    }

    /** Pure filter helper for unit tests. */
    static boolean shouldInclude(@Nullable String pkg, Set<String> excluded,
                                 long lastUsedMs, long dismissedAtMs, boolean playing) {
        if (pkg == null || pkg.isEmpty() || excluded.contains(pkg)) return false;
        if (playing) return true;
        return lastUsedMs > 0L && lastUsedMs > dismissedAtMs;
    }

    /** Pure MRU sort helper: currently playing apps bubble first, then newest timestamp first. */
    static int compareEntries(boolean playingA, long lastUsedA, boolean playingB, long lastUsedB) {
        if (playingA != playingB) return playingA ? -1 : 1;
        return Long.compare(lastUsedB, lastUsedA);
    }

    /** Pure relative-time label ("Právě teď", "Před 4 min", "Před 2 hod", etc.). */
    static String formatRelativeTime(Context ctx, long lastUsedMs, long nowMs) {
        long diffSec = Math.max(0L, (nowMs - lastUsedMs) / 1000L);
        if (diffSec < 60L) return ctx.getString(R.string.recents_just_now);
        long mins = diffSec / 60L;
        if (mins < 60L) return ctx.getString(R.string.recents_minutes_ago, (int) mins);
        long hours = mins / 60L;
        return ctx.getString(R.string.recents_hours_ago, (int) hours);
    }

    private static boolean isLaunchableUserApp(PackageManager pm, String pkg) {
        try {
            if (pm.getLeanbackLaunchIntentForPackage(pkg) != null) return true;
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Map<String, ResolveInfo> queryLeanbackMap(PackageManager pm) {
        Map<String, ResolveInfo> map = new LinkedHashMap<>();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (ri.activityInfo != null && !map.containsKey(ri.activityInfo.packageName)) {
                    map.put(ri.activityInfo.packageName, ri);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return map;
    }

    private static String resolveLabel(PackageManager pm, String pkg, @Nullable Map<String, String> custom) {
        if (custom != null) {
            String over = custom.get(pkg);
            if (!TextUtils.isEmpty(over)) return over;
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = ai.loadLabel(pm);
            if (!TextUtils.isEmpty(l)) return Cards.cleanLabel(l.toString(), pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return pkg;
    }

    @Nullable
    private static Drawable loadBanner(Context ctx, PackageManager pm, String pkg, @Nullable ResolveInfo ri) {
        Drawable banner = null;
        try {
            int bannerId = 0;
            ApplicationInfo appInfo = null;
            if (ri != null && ri.activityInfo != null) {
                bannerId = ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ri.activityInfo.applicationInfo.banner;
                appInfo = ri.activityInfo.applicationInfo;
            } else {
                appInfo = pm.getApplicationInfo(pkg, 0);
                bannerId = appInfo.banner;
            }
            if (bannerId != 0 && appInfo != null) {
                Resources res = pm.getResourcesForApplication(appInfo);
                int dpi = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = res.getDrawableForDensity(bannerId, dpi, null);
            }
            if (banner == null && ri != null && ri.activityInfo != null) {
                banner = ri.activityInfo.loadBanner(pm);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return banner != null && AppAdapter.isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight())
                ? banner : null;
    }

    @Nullable
    private static Drawable loadIcon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }
}
