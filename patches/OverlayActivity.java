package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Translucent system-wide overlay Activity hosting either the Apple tvOS App Switcher (Recents)
 * or the App Library directly over ANY active app/movie (Netflix, YouTube, Oneplay, etc.)
 * or over the blurred Home screen — using the same taskAffinity="" overlay architecture as
 * {@link QuickSettingsActivity}. Pressing Back returns seamlessly to whatever was underneath,
 * and switching directly between Recents and App Library happens in-place without tearing down the host window.
 */
public final class OverlayActivity extends Activity {

    static final String EXTRA_MODE = "com.gathin.tvhome.extra.OVERLAY_MODE";
    static final String MODE_RECENTS = "recents";
    static final String MODE_LIBRARY = "library";
    static final String MODE_FOLDER = "folder";
    static final String MODE_DISCOVER = "discover";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long LAUNCH_IN_FLIGHT_MS = 800L;
    @Nullable
    private static WeakReference<OverlayActivity> activeRef;
    @Nullable
    private static String pendingMode;
    private static long launchPendingUntilMs;
    @Nullable
    private static AppAdapter.Entry pendingFolderEntry;
    @Nullable
    private static FolderDialog.Callbacks pendingFolderCallbacks;

    @Nullable
    private static OverlayActivity active() {
        return activeRef != null ? activeRef.get() : null;
    }

    static boolean isLive() {
        OverlayActivity a = active();
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static boolean isLaunchInFlight() {
        return SystemClock.uptimeMillis() < launchPendingUntilMs;
    }

    static void refreshBackdropIfOpen() {
        OverlayActivity a = active();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            if (a.overHome) {
                ExpressiveGlass.invalidateLiquidPanels(a.root);
            } else {
                a.applyCapturedBackdrop();
            }
        }
    }

    private FrameLayout root;
    @Nullable
    private FrameLayout backdropView;
    private float density;
    private Config config;
    private String currentMode;
    private boolean overHome;
    private boolean seamlessHandoff;
    private boolean closing;
    private boolean switching;
    private boolean handingOff;
    private int backdropGenOnCreate;

    @Nullable
    private MediaSessionTracker mediaTracker;
    @Nullable
    private RecentsDialog recentsDialog;
    @Nullable
    private AppLibraryDialog libraryDialog;
    @Nullable
    private FolderDialog folderDialog;
    @Nullable
    private DiscoverDialog discoverDialog;

    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (libraryDialog != null) libraryDialog.refresh();
        }
    };
    private boolean packageReceiverRegistered;

    static void launch(Context ctx, String mode) {
        launch(ctx, mode, false);
    }

    static void launch(Context ctx, String mode, boolean seamlessHandoff) {
        String target = MODE_LIBRARY.equals(mode) ? MODE_LIBRARY
                : MODE_FOLDER.equals(mode) ? MODE_FOLDER
                : MODE_DISCOVER.equals(mode) ? MODE_DISCOVER : MODE_RECENTS;
        Context hostCtx = ctx instanceof Activity ? ctx : ctx.getApplicationContext();
        Runnable run = () -> launchOnMain(hostCtx, target, seamlessHandoff);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            MAIN.post(run);
        }
    }

    static void launchFolder(Context ctx, AppAdapter.Entry folder, FolderDialog.Callbacks callbacks) {
        Runnable run = () -> {
            pendingFolderEntry = folder;
            pendingFolderCallbacks = callbacks;
            launchOnMain(ctx instanceof Activity ? ctx : ctx.getApplicationContext(), MODE_FOLDER, false);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            MAIN.post(run);
        }
    }

    /** Closes OverlayActivity immediately during a seamless handoff to Control Center / Settings without dropping blur. */
    @SuppressWarnings("deprecation")
    static void finishForHandoff() {
        OverlayActivity live = active();
        if (live == null || live.isFinishing() || live.isDestroyed()) return;
        live.handingOff = true;
        live.closing = true;
        if (active() == live) activeRef = null;
        live.finish();
        live.overridePendingTransition(0, 0);
    }

    static void updateFolder(@Nullable AppAdapter.Entry updated) {
        OverlayActivity live = active();
        if (live == null || live.folderDialog == null) return;
        if (updated == null || updated.folderMembers.isEmpty()) {
            live.finishCleanly();
        } else {
            live.folderDialog.update(updated);
        }
    }

    static void closeFolderIfOpen() {
        OverlayActivity live = active();
        if (live != null && live.folderDialog != null) {
            live.finishCleanly();
        }
    }

    @SuppressWarnings("deprecation")
    private static void launchOnMain(Context ctx, String mode, boolean seamlessHandoff) {
        OverlayActivity live = active();
        if (live != null && !live.isFinishing() && !live.isDestroyed()) {
            live.handleOverlayRequest(mode);
            return;
        }
        // Coalesce rapid back-to-back presses arriving before onCreate() runs into a single launch.
        long now = SystemClock.uptimeMillis();
        if (now < launchPendingUntilMs) {
            pendingMode = mode;
            if (!MODE_FOLDER.equals(mode)) {
                pendingFolderEntry = null;
                pendingFolderCallbacks = null;
            }
            return;
        }
        pendingMode = mode;
        launchPendingUntilMs = now + LAUNCH_IN_FLIGHT_MS;
        final boolean handoff = seamlessHandoff || QuickSettingsActivity.isLive() || LauncherSettingsActivity.isLive();
        final boolean launchOverHome = MainActivity.isHomeVisible();
        Runnable startActivity = () -> {
            if (launchOverHome) MainActivity.prepareOverlayBackdropBlur();
            else MainActivity.setOverlayOpen(true);
            String target = pendingMode != null ? pendingMode : mode;
            Intent intent = new Intent(ctx, OverlayActivity.class)
                    .putExtra(EXTRA_MODE, target)
                    .putExtra(ExpressiveGlass.EXTRA_OVER_HOME, launchOverHome)
                    .putExtra(ExpressiveGlass.EXTRA_SEAMLESS_HANDOFF, handoff)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
                if (ctx instanceof Activity) {
                    ((Activity) ctx).overridePendingTransition(0, 0);
                }
            } catch (ActivityNotFoundException | SecurityException ignored) {
                launchPendingUntilMs = 0L;
                pendingMode = null;
                pendingFolderEntry = null;
                pendingFolderCallbacks = null;
                if (active() == null) MainActivity.setOverlayOpen(false);
            }
        };
        if (launchOverHome) {
            ExpressiveGlass.clearOverlayBackdrop();
            MainActivity.captureLiveHomeBackdropThen(startActivity);
        } else {
            HomeKeyService.captureOverlayBackdrop(startActivity);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        activeRef = new WeakReference<>(this);
        config = Config.load(this);
        Intent startIntent = getIntent();
        overHome = startIntent != null && startIntent.hasExtra(ExpressiveGlass.EXTRA_OVER_HOME)
                ? startIntent.getBooleanExtra(ExpressiveGlass.EXTRA_OVER_HOME, true)
                : MainActivity.isHomeVisible();
        seamlessHandoff = startIntent != null
                && startIntent.getBooleanExtra(ExpressiveGlass.EXTRA_SEAMLESS_HANDOFF, false);
        backdropGenOnCreate = ExpressiveGlass.overlayBackdropGeneration();
        if (overHome) MainActivity.prepareOverlayBackdropBlur();
        else MainActivity.setOverlayOpen(true);
        if (QuickSettingsActivity.isLive()) {
            QuickSettingsActivity.finishForHandoff();
        }

        density = getResources().getDisplayMetrics().density;
        boolean hasLiveBackdrop = !overHome && ExpressiveGlass.hasOverlayBackdrop();
        if (!overHome && !hasLiveBackdrop) {
            ExpressiveGlass.applyWindowBlur(getWindow(), density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, 0.36f);
        } else {
            // AGSL blur veil (backdropBlurVeilHost on Home OR backdropView over apps) owns 100% of darkening
            // with smooth alpha animation — clear FLAG_DIM_BEHIND so there is zero double-dimming or Frame-0 snap.
            ExpressiveGlass.setWindowDimOnly(getWindow(), 0f);
        }

        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setOnClickListener(v -> dismissCurrentAnimated(true));
        if (hasLiveBackdrop) {
            applyCapturedBackdrop(!seamlessHandoff, seamlessHandoff);
        }
        setContentView(root);
        PointerSupport.install(root);
        UiClickSound.attachFocusSound(root);

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        packageReceiverRegistered = true;

        String mode = pendingMode != null ? pendingMode : readMode(getIntent());
        pendingMode = null;
        launchPendingUntilMs = 0L;
        if (!MODE_FOLDER.equals(mode)) {
            pendingFolderEntry = null;
            pendingFolderCallbacks = null;
        }
        showMode(mode);
    }

    private String readMode(@Nullable Intent intent) {
        String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : null;
        if (MODE_LIBRARY.equals(mode)) return MODE_LIBRARY;
        if (MODE_FOLDER.equals(mode)) return MODE_FOLDER;
        if (MODE_DISCOVER.equals(mode)) return MODE_DISCOVER;
        return MODE_RECENTS;
    }

    private void showMode(String mode) {
        if (MODE_LIBRARY.equals(mode) && !config.appLibrary) {
            finishCleanly();
            return;
        }
        teardownCurrentDialog();
        currentMode = mode;
        boolean smoked = !overHome && backdropView == null;

        if (MODE_FOLDER.equals(mode)) {
            stopMediaTracker();
            AppAdapter.Entry entry = pendingFolderEntry;
            FolderDialog.Callbacks cb = pendingFolderCallbacks;
            pendingFolderEntry = null;
            pendingFolderCallbacks = null;
            if (entry == null || cb == null) {
                finishCleanly();
                return;
            }
            FolderDialog.Callbacks wrapped = new FolderDialog.Callbacks() {
                @Override public void onLaunch(AppAdapter.Entry app) {
                    boolean isAddon = MainActivity.isGlassAddon(app.pkg);
                    if (isAddon && overHome) MainActivity.setAddonModalOpen(true);
                    cb.onLaunch(app);
                    if (!isAddon) finishCleanly();
                }
                @Override public void onRemoveApp(String folderId, String pkg) {
                    cb.onRemoveApp(folderId, pkg);
                }
                @Override public void onMoveAppToFolder(String fromFolderId, String toFolderId, String pkg) {
                    cb.onMoveAppToFolder(fromFolderId, toFolderId, pkg);
                }
                @Override public void onReorderApps(String folderId, java.util.List<String> orderedPackages) {
                    cb.onReorderApps(folderId, orderedPackages);
                }
                @Override public void onAddApps(String folderId, String folderName) {
                    MainActivity.markFolderPromptPending();
                    finishCleanly();
                    cb.onAddApps(folderId, folderName);
                }
                @Override public void onRename(String folderId, String currentName) {
                    MainActivity.markFolderPromptPending();
                    finishCleanly();
                    cb.onRename(folderId, currentName);
                }
                @Override public void onDismissed() {
                    finishCleanly();
                    cb.onDismissed();
                }
            };
            folderDialog = new FolderDialog(this, entry, config.animations, config.reducedMotion, wrapped);
            folderDialog.showInOverlay(root);
        } else if (MODE_LIBRARY.equals(mode)) {
            stopMediaTracker();
            final boolean liveHome = overHome && MainActivity.hasLiveAdapter();
            final Set<String> hidden = liveHome ? null : new HashSet<>(config.hiddenApps);
            final Set<String> tvApps = liveHome ? null : queryLeanbackPackages();
            @SuppressWarnings("unchecked")
            final Set<String>[] addedRef = new Set[]{liveHome ? null : LibraryApps.load(this)};
            @SuppressWarnings("unchecked")
            final Set<String>[] removedRef = new Set[]{liveHome ? null : LibraryApps.loadRemoved(this)};
            Predicate<String> onHome = liveHome
                    ? MainActivity::isPackageOnLiveHome
                    : pkg -> LibraryApps.visible(tvApps.contains(pkg), hidden.contains(pkg),
                            addedRef[0].contains(pkg), removedRef[0].contains(pkg));
            Runnable onChanged = () -> {
                if (!liveHome) {
                    addedRef[0] = LibraryApps.load(this);
                    removedRef[0] = LibraryApps.loadRemoved(this);
                }
                MainActivity.notifyLibraryChanged();
            };
            libraryDialog = new AppLibraryDialog(
                    this,
                    config.animations,
                    config.reducedMotion,
                    config.appLibrarySelection,
                    config.libraryOkOpens,
                    onHome,
                    onChanged,
                    this::finishCleanly,
                    new HashSet<>(config.libraryExcludedApps));
            libraryDialog.showInOverlay(root, smoked);
        } else if (MODE_DISCOVER.equals(mode)) {
            stopMediaTracker();
            discoverDialog = new DiscoverDialog(this, config.reducedMotion, this::finishCleanly);
            discoverDialog.showInOverlay(root, smoked);
        } else {
            if (mediaTracker == null) {
                mediaTracker = new MediaSessionTracker(this, controllers -> { });
            }
            mediaTracker.start();
            recentsDialog = new RecentsDialog(
                    this,
                    config.appLabels,
                    mediaTracker,
                    config.animations,
                    config.reducedMotion,
                    this::finishCleanly);
            recentsDialog.showInOverlay(root, smoked);
        }
    }

    private Set<String> queryLeanbackPackages() {
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
        }
        return pkgs;
    }

    private void teardownCurrentDialog() {
        RecentsDialog r = recentsDialog;
        recentsDialog = null;
        AppLibraryDialog l = libraryDialog;
        libraryDialog = null;
        FolderDialog f = folderDialog;
        folderDialog = null;
        DiscoverDialog d = discoverDialog;
        discoverDialog = null;
        if (r != null) r.close();
        if (l != null) l.close();
        if (f != null) f.close();
        if (d != null) d.close();
    }

    private void handleOverlayRequest(String nextMode) {
        if (isFinishing() || isDestroyed()) return;
        boolean midDismiss = (recentsDialog != null && recentsDialog.isDismissing())
                || (libraryDialog != null && libraryDialog.isDismissing())
                || (folderDialog != null && folderDialog.isDismissing());
        if (nextMode.equals(currentMode) && !MODE_FOLDER.equals(nextMode) && !midDismiss) {
            dismissCurrentAnimated(true);
            return;
        }
        // Switch in-place (Recents <-> App Library <-> Folder) or cancel a mid-exit animation without
        // restarting OverlayActivity, preventing onPause() teardown and keeping backdrop blur continuous.
        closing = false;
        if (overHome) {
            MainActivity.prepareOverlayBackdropBlur();
            MainActivity.startBackdropBlurFadeInIfOpen();
        } else {
            MainActivity.setOverlayOpen(true);
            if (backdropView != null) {
                backdropView.animate().cancel();
                ExpressiveGlass.startOverlayBackdropFadeIn(backdropView, config != null && config.reducedMotion);
            }
        }
        switching = true;
        teardownCurrentDialog();
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if (child != backdropView) root.removeViewAt(i);
        }
        switching = false;
        showMode(nextMode);
    }

    private void applyCapturedBackdrop() {
        applyCapturedBackdrop(false, false);
    }

    private void applyCapturedBackdrop(boolean deferFadeStart, boolean startOpaque) {
        if (root == null) return;
        backdropView = ExpressiveGlass.attachOverlayBackdrop(
                root, backdropView, density, config != null && config.reducedMotion, deferFadeStart, startOpaque);
        if (backdropView != null) {
            backdropGenOnCreate = ExpressiveGlass.overlayBackdropGeneration();
            ExpressiveGlass.setWindowDimOnly(getWindow(), 0f);
            if (libraryDialog != null) libraryDialog.setSmoked(false);
            if (recentsDialog != null) recentsDialog.setSmoked(false);
            if (discoverDialog != null) discoverDialog.setSmoked(false);
        }
        ExpressiveGlass.invalidateLiquidPanels(root);
    }

    /** Called by the hosted dialog on its exact postOnAnimation reveal vsync so blur dissolves in lockstep. */
    void notifyContentRevealStarted() {
        if (closing || isFinishing() || isDestroyed()) return;
        if (overHome) {
            MainActivity.startBackdropBlurFadeInIfOpen();
        } else {
            ExpressiveGlass.startOverlayBackdropFadeIn(backdropView, config != null && config.reducedMotion);
        }
    }

    private void fadeOutBackdrop() {
        if (overHome) {
            MainActivity.fadeOutOverlayBlurNow();
            return;
        }
        if (backdropView == null) return;
        backdropView.animate().cancel();
        if (config != null && config.reducedMotion) {
            backdropView.setAlpha(0f);
        } else {
            backdropView.animate().alpha(0f).setDuration(175L).setInterpolator(Motion.easeInExit()).start();
        }
    }

    private void dismissCurrentAnimated(boolean forceModal) {
        if (closing) return;
        if (recentsDialog != null) {
            recentsDialog.animateClose(forceModal);
            if (recentsDialog != null && recentsDialog.isDismissing()) fadeOutBackdrop();
        } else if (libraryDialog != null) {
            libraryDialog.animateClose(forceModal);
            if (libraryDialog != null && libraryDialog.isDismissing()) fadeOutBackdrop();
        } else if (folderDialog != null) {
            folderDialog.animateClose(forceModal);
            if (folderDialog != null && folderDialog.isDismissing()) fadeOutBackdrop();
        } else if (discoverDialog != null) {
            fadeOutBackdrop();
            discoverDialog.animateClose();
        } else {
            fadeOutBackdrop();
            finishCleanly();
        }
    }

    @SuppressWarnings("deprecation")
    private void finishCleanly() {
        if (closing || switching) return;
        closing = true;
        if (active() == this) {
            activeRef = null;
            MainActivity.setOverlayOpen(false);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void stopMediaTracker() {
        if (mediaTracker != null) {
            mediaTracker.stop();
            mediaTracker = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleOverlayRequest(readMode(intent));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        dismissCurrentAnimated(false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        UiClickSound.onKeyEvent(this, event);
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS) {
            handleOverlayRequest(MODE_LIBRARY);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        if (active() == this) {
            activeRef = null;
            if (!handingOff) MainActivity.setOverlayOpen(false);
        } else if (!handingOff) {
            MainActivity.setOverlayOpen(false);
        }
        stopMediaTracker();
        closing = true;
        teardownCurrentDialog();
        super.onPause();
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (active() == this) {
            activeRef = null;
            if (!handingOff) MainActivity.setOverlayOpen(false);
        }
        stopMediaTracker();
        if (packageReceiverRegistered) {
            packageReceiverRegistered = false;
            try {
                unregisterReceiver(packageReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        if (backdropView != null) {
            ExpressiveGlass.releaseOverlayBackdrop(backdropView);
            backdropView = null;
        }
        final int gen = backdropGenOnCreate;
        MAIN.postDelayed(() -> {
            if (!isLive() && !isLaunchInFlight()
                    && !QuickSettingsActivity.isLive() && !QuickSettingsActivity.isLaunchInFlight()
                    && !LauncherSettingsActivity.isLive() && !MainActivity.isSettingsLaunchInFlight()) {
                ExpressiveGlass.clearOverlayBackdropIfGeneration(gen);
            }
        }, 200L);
        super.onDestroy();
    }
}
