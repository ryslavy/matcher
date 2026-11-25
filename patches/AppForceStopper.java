package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Truly terminates background Android TV apps on Android 14+ (where {@code killBackgroundProcesses}
 * is silently ignored for third-party packages) by driving the system App Info "Force stop" action
 * through the already-bound {@link HomeKeyService} AccessibilityService.
 *
 * Safety invariants:
 *  - Zero idle overhead: window-content accessibility events are enabled only for the ~400 ms while
 *    a force-stop queue is actively running, and turned off immediately afterwards.
 *  - Verifies the exact target package name on App Info before clicking Force Stop, and verifies the
 *    Force Stop confirmation title before clicking OK (can never click Uninstall or Clear Data).
 *  - Skips apps that already carry {@link ApplicationInfo#FLAG_STOPPED}, self, and Settings itself.
 */
final class AppForceStopper {

    private static final String TV_SETTINGS_PKG = "com.android.tv.settings";
    private static final String AOSP_SETTINGS_PKG = "com.android.settings";
    private static final String XIAOMI_SETTINGS_PKG = "com.xiaomi.mitv.settings";
    private static final Set<String> SETTINGS_PACKAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            TV_SETTINGS_PKG,
            AOSP_SETTINGS_PKG,
            XIAOMI_SETTINGS_PKG
    )));
    private static final Pattern PACKAGE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");

    private static final Set<String> FALLBACK_FORCE_STOP_LABELS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "force stop",
            "vynutit zastavení",
            "vynutit ukončení",
            "vynútiť zastavenie",
            "beenden erzwingen",
            "forzar detención",
            "forcer l'arrêt",
            "wymuś zatrzymanie",
            "arresto forzato",
            "forçar parada",
            "gedwongen stoppen",
            "принудительно остановить",
            "примусово зупинити"
    )));

    private static final long POLL_INTERVAL_MS = 85L;
    private static final long STOPPED_POLL_MS = 60L;
    private static final long STOPPED_MAX_WAIT_MS = 450L;
    private static final long PER_APP_TIMEOUT_MS = 2800L;
    private static final long POST_BACK_DELAY_MS = 170L;

    private enum Stage {
        IDLE,
        WAITING_APP_INFO,
        WAITING_CONFIRM,
        WAITING_STOPPED
    }

    @Nullable
    private static Handler mainHandler;
    private static final Deque<String> queue = new ArrayDeque<>();
    private static Stage stage = Stage.IDLE;
    @Nullable
    private static String currentPkg;
    @Nullable
    private static String currentLabel;
    private static long confirmClickedAtMs;
    private static boolean openedSettings;
    private static boolean reopenRecents;
    @Nullable
    private static Runnable completionCallback;
    @Nullable
    private static Set<String> cachedForceStopLabels;
    @Nullable
    private static Set<String> cachedOkLabels;
    @Nullable
    private static Locale cachedLocale;
    @Nullable
    private static Runnable postFinishRunnable;

    private static final Runnable pollRunnable = AppForceStopper::pollTick;
    private static final Runnable timeoutRunnable = AppForceStopper::onPerAppTimeout;

    private AppForceStopper() { }

    private static Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    static boolean isAvailable() {
        return HomeKeyService.running != null;
    }

    static boolean isRunning() {
        return stage != Stage.IDLE;
    }

    /** Pure helper: excludes self and system Settings packages that cannot force-stop themselves. */
    static boolean isForceStoppable(@Nullable String pkg, @Nullable String selfPkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(selfPkg)) return false;
        return !SETTINGS_PACKAGES.contains(pkg);
    }

    /**
     * Returns true when {@code pkg} currently hosts an enabled Android AccessibilityService
     * (such as Sleep Timer's SleepService). Force-stopping such a package causes Android to
     * immediately strip its AccessibilityService from system settings, so Recents must skip it.
     */
    static boolean isProtectedAccessibilityPackage(Context ctx, @Nullable String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            String list = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list != null && !list.isEmpty()) {
                for (String entry : list.split(":")) {
                    android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(entry);
                    if (cn != null && pkg.equals(cn.getPackageName())) return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Pure helper: case-insensitive trimmed match against a normalized label set. */
    static boolean matchesLabel(@Nullable CharSequence text, Set<String> normalizedLabels) {
        if (text == null || normalizedLabels == null || normalizedLabels.isEmpty()) return false;
        String norm = text.toString().trim().toLowerCase(Locale.ROOT);
        return !norm.isEmpty() && normalizedLabels.contains(norm);
    }

    static boolean isAlreadyStopped(Context ctx, @Nullable String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return true;
        }
    }

    /**
     * Queues {@code packages} for genuine system Force Stop. Returns {@code true} if at least one
     * running package was queued and Settings automation started (caller should expect Recents to close
     * briefly and optionally reopen via {@code returnToRecents}).
     */
    static boolean forceStop(Context ctx, List<String> packages, boolean returnToRecents,
                             @Nullable Runnable onComplete) {
        HomeKeyService service = HomeKeyService.running;
        if (service == null || packages == null || packages.isEmpty()) return false;
        String selfPkg = ctx.getPackageName();
        Deque<String> targets = new ArrayDeque<>();
        for (String pkg : packages) {
            if (!isForceStoppable(pkg, selfPkg)) continue;
            if (isProtectedAccessibilityPackage(ctx, pkg)) continue;
            if (isAlreadyStopped(ctx, pkg)) continue;
            if (!targets.contains(pkg)) targets.addLast(pkg);
        }
        if (targets.isEmpty()) return false;

        Runnable start = () -> startOnMain(service, targets, returnToRecents, onComplete);
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else main().post(start);
        return true;
    }

    static void cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishQueue(true);
        } else {
            main().post(() -> finishQueue(true));
        }
    }

    static void onAccessibilityEvent(HomeKeyService service) {
        if (stage == Stage.IDLE) return;
        step(service);
    }

    private static void startOnMain(HomeKeyService service, Deque<String> targets,
                                    boolean returnToRecents, @Nullable Runnable onComplete) {
        cancelTimers();
        queue.clear();
        queue.addAll(targets);
        reopenRecents = returnToRecents;
        completionCallback = onComplete;
        openedSettings = false;
        ensureLabelsLoaded(service);
        service.setForceStopWatching(true);
        advanceNext(service);
    }

    private static void advanceNext(HomeKeyService service) {
        cancelTimers();
        while (!queue.isEmpty()) {
            String next = queue.pollFirst();
            if (next != null && !isAlreadyStopped(service, next)) {
                launchAppInfo(service, next);
                return;
            }
        }
        finishQueue(false);
    }

    private static void launchAppInfo(HomeKeyService service, String pkg) {
        currentPkg = pkg;
        currentLabel = loadAppLabel(service, pkg);
        stage = Stage.WAITING_APP_INFO;
        confirmClickedAtMs = 0L;

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            service.startActivity(intent);
            openedSettings = true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            advanceNext(service);
            return;
        }
        main().postDelayed(timeoutRunnable, PER_APP_TIMEOUT_MS);
        main().postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private static void pollTick() {
        HomeKeyService service = HomeKeyService.running;
        if (service == null || stage == Stage.IDLE) return;
        step(service);
        if (stage != Stage.IDLE) {
            long delay = stage == Stage.WAITING_STOPPED ? STOPPED_POLL_MS : POLL_INTERVAL_MS;
            main().postDelayed(pollRunnable, delay);
        }
    }

    private static void onPerAppTimeout() {
        HomeKeyService service = HomeKeyService.running;
        if (service == null || stage == Stage.IDLE) return;
        advanceNext(service);
    }

    private static void step(HomeKeyService service) {
        if (stage == Stage.IDLE || currentPkg == null) return;

        if (stage == Stage.WAITING_STOPPED) {
            boolean stopped = isAlreadyStopped(service, currentPkg);
            long elapsed = SystemClock.uptimeMillis() - confirmClickedAtMs;
            AccessibilityNodeInfo rootNow = service.getRootInActiveWindow();
            boolean confirmStillOpen = rootNow != null && findConfirmOkButton(rootNow) != null;
            if ((stopped && !confirmStillOpen) || elapsed >= STOPPED_MAX_WAIT_MS) {
                advanceNext(service);
            }
            return;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !SETTINGS_PACKAGES.contains(rootPkg.toString())) return;

        Set<String> forceLabels = cachedForceStopLabels != null ? cachedForceStopLabels : FALLBACK_FORCE_STOP_LABELS;

        if (stage == Stage.WAITING_APP_INFO) {
            if (!matchesTargetScreen(root, currentPkg, currentLabel)) return;
            AccessibilityNodeInfo titleNode = findFirstMatchingText(root, forceLabels);
            if (titleNode == null) return;
            AccessibilityNodeInfo clickable = findClickableAncestor(titleNode);
            if (clickable == null) return;
            if (!clickable.isEnabled()) {
                // Already stopped according to system Settings.
                advanceNext(service);
                return;
            }
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                stage = Stage.WAITING_CONFIRM;
                main().removeCallbacks(pollRunnable);
                main().postDelayed(pollRunnable, POLL_INTERVAL_MS);
            }
            return;
        }

        if (stage == Stage.WAITING_CONFIRM) {
            if (!isForceStopConfirmation(root, forceLabels)) return;
            AccessibilityNodeInfo okButton = findConfirmOkButton(root);
            if (okButton != null && okButton.isEnabled()
                    && okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                stage = Stage.WAITING_STOPPED;
                confirmClickedAtMs = SystemClock.uptimeMillis();
                main().removeCallbacks(pollRunnable);
                main().postDelayed(pollRunnable, STOPPED_POLL_MS);
            }
        }
    }

    private static void finishQueue(boolean aborted) {
        cancelTimers();
        boolean hadSettings = openedSettings;
        boolean wantRecents = !aborted && reopenRecents;
        Runnable done = !aborted ? completionCallback : null;

        stage = Stage.IDLE;
        currentPkg = null;
        currentLabel = null;
        confirmClickedAtMs = 0L;
        openedSettings = false;
        reopenRecents = false;
        completionCallback = null;
        queue.clear();

        HomeKeyService service = HomeKeyService.running;
        if (service != null) {
            service.setForceStopWatching(false);
            if (hadSettings) {
                RecentApps.suppressAutoSettingsEntry(service, TV_SETTINGS_PKG);
                RecentApps.suppressAutoSettingsEntry(service, AOSP_SETTINGS_PKG);
                RecentApps.suppressAutoSettingsEntry(service, XIAOMI_SETTINGS_PKG);
                if (!aborted) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                }
            }
            if (wantRecents || done != null) {
                postFinishRunnable = () -> {
                    postFinishRunnable = null;
                    if (done != null) done.run();
                    if (wantRecents && HomeKeyService.running != null) {
                        OverlayActivity.launch(HomeKeyService.running, OverlayActivity.MODE_RECENTS);
                    }
                };
                main().postDelayed(postFinishRunnable, hadSettings ? POST_BACK_DELAY_MS : 0L);
            }
        } else if (done != null) {
            done.run();
        }
    }

    private static void cancelTimers() {
        main().removeCallbacks(pollRunnable);
        main().removeCallbacks(timeoutRunnable);
        if (postFinishRunnable != null) {
            main().removeCallbacks(postFinishRunnable);
            postFinishRunnable = null;
        }
    }

    private static void ensureLabelsLoaded(Context ctx) {
        Locale current = Locale.getDefault();
        if (cachedForceStopLabels != null && cachedOkLabels != null && current.equals(cachedLocale)) return;
        cachedLocale = current;
        Set<String> force = new HashSet<>(FALLBACK_FORCE_STOP_LABELS);
        addRemoteString(ctx, TV_SETTINGS_PKG, "device_apps_app_management_force_stop", force);
        addRemoteString(ctx, TV_SETTINGS_PKG, "force_stop", force);
        addRemoteString(ctx, AOSP_SETTINGS_PKG, "force_stop", force);
        cachedForceStopLabels = force;

        Set<String> ok = new HashSet<>();
        ok.add("ok");
        String sysOk = ctx.getString(android.R.string.ok);
        if (!TextUtils.isEmpty(sysOk)) ok.add(sysOk.trim().toLowerCase(Locale.ROOT));
        addRemoteString(ctx, TV_SETTINGS_PKG, "okay", ok);
        cachedOkLabels = ok;
    }

    private static void addRemoteString(Context ctx, String pkg, String resName, Set<String> target) {
        try {
            Resources res = ctx.getPackageManager().getResourcesForApplication(pkg);
            int id = res.getIdentifier(resName, "string", pkg);
            if (id != 0) {
                String val = res.getString(id);
                if (!TextUtils.isEmpty(val)) target.add(val.trim().toLowerCase(Locale.ROOT));
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
    }

    @Nullable
    private static String loadAppLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return !TextUtils.isEmpty(l) ? l.toString().trim() : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    /** Confirms the displayed App Info screen belongs to {@code targetPkg} before clicking anything. */
    private static boolean matchesTargetScreen(AccessibilityNodeInfo root, String targetPkg, @Nullable String label) {
        if (containsExactText(root, targetPkg)) return true;
        // On TvSettings the package name is always shown in android:id/summary; if another package's
        // summary is still onscreen during a transition, wait until the new App Info finishes loading.
        List<AccessibilityNodeInfo> summaries = root.findAccessibilityNodeInfosByViewId("android:id/summary");
        if (summaries != null) {
            for (AccessibilityNodeInfo node : summaries) {
                CharSequence t = node != null ? node.getText() : null;
                if (t != null) {
                    String s = t.toString().trim();
                    if (s.equals(targetPkg)) return true;
                    if (!s.equals(targetPkg) && PACKAGE_NAME_PATTERN.matcher(s).matches()) return false;
                }
            }
        }
        return !TextUtils.isEmpty(label) && containsTextIgnoreCase(root, label);
    }

    private static boolean isForceStopConfirmation(AccessibilityNodeInfo root, Set<String> forceLabels) {
        String[] titleIds = {
                TV_SETTINGS_PKG + ":id/guidance_title",
                AOSP_SETTINGS_PKG + ":id/alertTitle",
                "android:id/alertTitle"
        };
        for (String id : titleIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (n != null && matchesLabel(n.getText(), forceLabels)) return true;
            }
        }
        List<AccessibilityNodeInfo> containers =
                root.findAccessibilityNodeInfosByViewId(TV_SETTINGS_PKG + ":id/guidance_container");
        if (containers != null) {
            for (AccessibilityNodeInfo c : containers) {
                CharSequence desc = c != null ? c.getContentDescription() : null;
                if (desc == null) continue;
                String lower = desc.toString().toLowerCase(Locale.ROOT);
                for (String lbl : forceLabels) {
                    if (lower.contains(lbl)) return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static AccessibilityNodeInfo findConfirmOkButton(AccessibilityNodeInfo root) {
        Set<String> okLabels = cachedOkLabels != null ? cachedOkLabels : Collections.singleton("ok");
        // Android TV GuidedStep confirmation panel
        List<AccessibilityNodeInfo> tvItems =
                root.findAccessibilityNodeInfosByViewId(TV_SETTINGS_PKG + ":id/guidedactions_item_title");
        if (tvItems != null) {
            for (AccessibilityNodeInfo item : tvItems) {
                if (item != null && matchesLabel(item.getText(), okLabels)) {
                    AccessibilityNodeInfo clickable = findClickableAncestor(item);
                    if (clickable != null) return clickable;
                }
            }
        }
        // Standard AOSP AlertDialog OK button
        List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
        if (buttons != null) {
            for (AccessibilityNodeInfo btn : buttons) {
                if (btn != null && btn.isClickable() && btn.isEnabled()) return btn;
            }
        }
        return null;
    }

    @Nullable
    private static AccessibilityNodeInfo findFirstMatchingText(AccessibilityNodeInfo node, Set<String> labels) {
        if (node == null) return null;
        if (matchesLabel(node.getText(), labels)) return node;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findFirstMatchingText(child, labels);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean containsExactText(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        CharSequence t = node.getText();
        if (t != null && target.equals(t.toString().trim())) return true;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && containsExactText(child, target)) return true;
        }
        return false;
    }

    private static boolean containsTextIgnoreCase(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        CharSequence t = node.getText();
        if (t != null && target.equalsIgnoreCase(t.toString().trim())) return true;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && containsTextIgnoreCase(child, target)) return true;
        }
        return false;
    }

    @Nullable
    private static AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; cur != null && depth < 6; depth++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }
}
