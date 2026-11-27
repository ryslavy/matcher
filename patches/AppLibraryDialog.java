package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** On-demand installed-app picker in a multi-row frosted-glass grid. */
final class AppLibraryDialog {
    private static final int COLUMNS = 6;
    private static final int CARD_HEIGHT_DP = 116;
    private static final int DIALOG_HEIGHT_DP = 470;
    private static final String ORDER_PREFS = "library_order";
    private static final String ORDER_KEY = "packages";
    private static final int REORDER_ACCENT = 0xFF38BDF8;
    /**
     * The catalogue and labels load here, off the UI thread: each label opens the app's resources,
     * and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Dedicated parallel pool for app icons so slow resources never stall catalogue scans or scrolling. */
    private static final ExecutorService ICON_LOADER = Executors.newFixedThreadPool(2);
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();
    /** Decoded icon ConstantStates kept across openings (UI thread only, bounded), invalidated on app update. */
    private static final android.util.LruCache<String, CachedIcon> ICON_CACHE = new android.util.LruCache<>(96);
    @Nullable
    private static Collator cachedCollator;
    @Nullable
    private static java.util.Locale cachedCollatorLocale;

    private static Collator getCollator() {
        java.util.Locale cur = java.util.Locale.getDefault();
        if (cachedCollator == null || !cur.equals(cachedCollatorLocale)) {
            cachedCollator = Collator.getInstance(cur);
            cachedCollatorLocale = cur;
        }
        return cachedCollator;
    }

    static void clearIconCache() {
        ICON_CACHE.evictAll();
    }

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private static final class CachedIcon {
        final long updated;
        @Nullable
        final Drawable.ConstantState state;
        @Nullable
        final Drawable fallback;
        final int accent;

        CachedIcon(long updated, Drawable drawable, int accent) {
            this.updated = updated;
            this.state = drawable.getConstantState();
            this.fallback = this.state == null ? drawable : null;
            this.accent = accent;
        }

        Drawable create(Activity activity) {
            if (state != null) {
                Drawable copy = state.newDrawable(activity.getResources());
                if (copy != null) return copy.mutate();
            }
            return fallback;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** ImageViews waiting for an in-flight icon decode (UI thread only), cleared on close. */
    private final Map<String, List<ImageView>> iconWaiters = new HashMap<>();
    private final Runnable dismissed;
    private boolean closed;
    private boolean dismissing;
    @Nullable
    private FrameLayout embeddedHost;
    private int currentWidth;
    private int currentHeight;
    /** Only the latest load fills the catalogue, and it shows the popup if an opening is waiting. */
    private final LibraryLoads loads = new LibraryLoads();
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final FrameLayout panelStage;
    private final LinearLayout panel;
    private final int stagePad;
    private final Rect highlightWorkRect = new Rect();
    private final Drawable.ConstantState cardBgHomeState;
    private final Drawable.ConstantState cardBgRestState;
    private final Drawable.ConstantState cardBgCarriedState;
    private final Drawable.ConstantState recentDotState;
    private final boolean reducedMotion;
    private final boolean selectionMode;
    /** library_ok_opens without app_library_selection: OK opens every app, Add to Home is in the hold menu. */
    private final boolean okOpens;
    private final boolean animations;
    private final boolean rtl;
    private TextView instructions;
    private TextView btnReorder;
    private int totalApps;
    final PopupWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private final Map<String, Long> stableIds = new HashMap<>();
    private long nextStableId = 1L;
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    private final Map<String, Long> updatedTimes = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long catalogTime;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private Catalog catalogAdapter;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation springX, springY;
    @Nullable
    private View highlightedView;
    @Nullable
    private String highlightedPkg;
    private boolean highlightShown;
    private boolean highlightedIsPill;
    private boolean reordering;
    @Nullable
    private String movingPkg;
    private final int catalogWidth;
    private final int catalogHeight;
    private final int cardWidth;
    private final int cardHeight;

    private final View.OnLayoutChangeListener syncHighlightOnLayout = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (highlightShown) snapHighlight();
    };

    private void stopHighlight() {
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        if (highlight != null) highlight.resetMotion();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        catalogAdapter = null;
        highlightedView = null;
        highlightedPkg = null;
        highlightShown = false;
        highlightedIsPill = false;
    }

    private void highlight(View card, String pkg, boolean animate) {
        moveHighlight(card, false, pkg, animate);
    }

    private void highlightPill(View pill) {
        moveHighlight(pill, true, null, true);
    }

    private void moveHighlight(@Nullable View target, boolean isPill, @Nullable String pkg, boolean animate) {
        if (highlight == null || target == null || !target.isAttachedToWindow()) return;
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            target.post(() -> {
                if (!closed && target.hasFocus()) moveHighlight(target, isPill, pkg, animate);
            });
            return;
        }
        if (!FocusRingController.locateInHost(panelStage, target, highlightWorkRect)) return;
        highlightedView = target;
        highlightedIsPill = isPill;
        highlightedPkg = pkg;

        float pad = GlassView.insetPx(density);
        float scale = isPill ? ((animations && !reducedMotion) ? 1.07f : 1f) : 1f;
        float liftPx = isPill ? ((animations && !reducedMotion) ? 1.5f * density : 0f) : 0f;
        float targetW = highlightWorkRect.width() * scale;
        float targetH = highlightWorkRect.height() * scale;
        int gw = Math.round(targetW + 2f * pad);
        int gh = Math.round(targetH + 2f * pad);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw;
            lp.height = gh;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(isPill ? Math.min(targetW, targetH) * 0.5f : dp(18));
        if (isPill) {
            highlight.setAccent(reordering ? REORDER_ACCENT : 0);
        } else {
            CachedIcon cached = pkg != null ? ICON_CACHE.get(pkg) : null;
            highlight.setAccent(reordering ? REORDER_ACCENT : (cached != null ? cached.accent : 0));
        }
        float x = highlightWorkRect.exactCenterX() - gw / 2f;
        float y = highlightWorkRect.exactCenterY() - gh / 2f - liftPx;
        if (!highlightShown || !animate || !animations) {
            springX.cancel();
            springY.cancel();
            highlight.resetMotion();
            highlight.setTranslationX(x);
            highlight.setTranslationY(y);
        } else {
            highlight.onJump(x - highlight.getTranslationX(), y - highlight.getTranslationY());
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    /** Re-locks the wobbly ring onto {@code highlightedView} after scrolling or layout changes (zero drift). */
    private void snapHighlight() {
        if (highlight == null || !highlightShown || highlightedView == null) return;
        View target = highlightedView;
        if (!target.isAttachedToWindow() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (!FocusRingController.locateInHost(panelStage, target, highlightWorkRect)) return;

        float pad = GlassView.insetPx(density);
        float scale = highlightedIsPill ? ((animations && !reducedMotion) ? 1.07f : 1f) : 1f;
        float liftPx = highlightedIsPill ? ((animations && !reducedMotion) ? 1.5f * density : 0f) : 0f;
        float targetW = highlightWorkRect.width() * scale;
        float targetH = highlightWorkRect.height() * scale;
        int gw = Math.round(targetW + 2f * pad);
        int gh = Math.round(targetH + 2f * pad);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw;
            lp.height = gh;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(highlightedIsPill ? Math.min(targetW, targetH) * 0.5f : dp(18));
        float x = highlightWorkRect.exactCenterX() - gw / 2f;
        float y = highlightWorkRect.exactCenterY() - gh / 2f - liftPx;
        if (springX != null && springX.isRunning()) {
            springX.animateToFinalPosition(x);
        } else {
            highlight.setTranslationX(x);
        }
        if (springY != null && springY.isRunning()) {
            springY.animateToFinalPosition(y);
        } else {
            highlight.setTranslationY(y);
        }
    }

    private GradientDrawable surface(int color, int radius) {
        return surface(color, radius, 0x34FFFFFF);
    }

    private GradientDrawable surface(int color, int radius, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private void resize(int width, int height) {
        currentWidth = width;
        currentHeight = height;
        int stageW = width + 2 * stagePad;
        int stageH = height + 2 * stagePad;
        if (embeddedHost != null) {
            ViewGroup.LayoutParams lp = panelStage.getLayoutParams();
            if (lp != null && (lp.width != stageW || lp.height != stageH)) {
                lp.width = stageW;
                lp.height = stageH;
                panelStage.setLayoutParams(lp);
            }
            return;
        }
        popup.setWidth(stageW);
        popup.setHeight(stageH);
        if (popup.isShowing()) popup.update(stageW, stageH);
    }

    /** library_excluded_apps (config.xml), never offered in the catalogue; empty by default, so existing
     *  setups are unaffected. See LibraryApps.excludedFromCatalog(). */
    private final Set<String> excludedApps;

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, boolean selectionMode, boolean okOpens, Predicate<String> onHome, Runnable changed, Runnable dismissed, Set<String> excludedApps) {
        this.activity = activity;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.selectionMode = selectionMode;
        this.okOpens = okOpens && !selectionMode;
        this.onHome = onHome;
        this.changed = changed;
        this.dismissed = dismissed;
        this.excludedApps = excludedApps;
        density = activity.getResources().getDisplayMetrics().density;
        stagePad = FocusRingController.wrapInsetPx(density);
        cardBgHomeState = ExpressiveGlass.makeAdaptiveCardBg(density, 18f, true).getConstantState();
        cardBgRestState = ExpressiveGlass.makeAdaptiveCardBg(density, 18f, false).getConstantState();
        cardBgCarriedState = surface(
                ExpressiveGlass.isLightGlass() ? 0x2E0284C7 : 0x3438BDF8, 18,
                ExpressiveGlass.isLightGlass() ? 0xCC0369A1 : 0xCC38BDF8).getConstantState();
        recentDotState = surface(0xFF70D9A0, 8).getConstantState();

        panelStage = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK && embeddedHost == null && !reordering) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        animateClose(false);
                    }
                    return true;
                }
                if (reordering) {
                    if (code == KeyEvent.KEYCODE_BACK) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                            if (movingPkg != null) dropCard();
                            else endReorder(true);
                        }
                        return true;
                    }
                    if (movingPkg != null && (code == KeyEvent.KEYCODE_DPAD_LEFT
                            || code == KeyEvent.KEYCODE_DPAD_RIGHT
                            || code == KeyEvent.KEYCODE_DPAD_UP
                            || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) moveCarriedBy(code);
                        return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            }
        };
        panelStage.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panelStage.setClipChildren(false);
        panelStage.setClipToPadding(false);

        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), 0);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);

        FrameLayout.LayoutParams panelInStage = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        panelInStage.setMargins(stagePad, stagePad, stagePad, stagePad);
        panelStage.addView(panel, panelInStage);

        View decor = activity.getWindow().getDecorView();
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int screenW = decor.getWidth() > 0 ? decor.getWidth() : dm.widthPixels;
        int screenH = decor.getHeight() > 0 ? decor.getHeight() : dm.heightPixels;
        catalogWidth = Math.min(dp(820), Math.max(dp(200), screenW - dp(64)));
        catalogHeight = Math.min(dp(DIALOG_HEIGHT_DP), Math.max(dp(280), screenH - dp(56)));
        currentWidth = catalogWidth;
        currentHeight = catalogHeight;
        cardWidth = Math.max(dp(92), Math.min(dp(116), (catalogWidth - dp(60)) / COLUMNS - dp(10)));
        cardHeight = dp(CARD_HEIGHT_DP);
        popup = new PopupWindow(panelStage, catalogWidth + 2 * stagePad, catalogHeight + 2 * stagePad, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            Motion.cancelPanelAnim(panelStage);
            Motion.cancelPanelAnim(panel);
            dismissActions();
            stopHighlight();
            iconWaiters.clear();
            if (embeddedHost == null && activity instanceof MainActivity) {
                ((MainActivity) activity).updateBackdropBlur();
            }
            dismissed.run();
        });
    }

    boolean isShowing() {
        return embeddedHost != null ? (!closed && panelStage.getParent() != null) : popup.isShowing();
    }

    boolean hasActionPopup() {
        return actionPopup != null && actionPopup.isShowing();
    }

    boolean isDismissing() {
        return dismissing;
    }

    /** Animates modal out smoothly before closing (used on Back / outside tap in OverlayActivity). */
    void animateClose() {
        animateClose(false);
    }

    void animateClose(boolean forceModal) {
        if (closed || dismissing) return;
        if (!forceModal && hasActionPopup()) {
            dismissActions();
            return;
        }
        if (!forceModal && reordering) {
            if (movingPkg != null) dropCard();
            else endReorder(true);
            return;
        }
        if (reordering) endReorder(true);
        if (!isShowing()) {
            close();
            return;
        }
        dismissing = true;
        dismissActions();
        stopHighlight();
        if (embeddedHost == null && activity instanceof MainActivity) {
            ((MainActivity) activity).setBackdropBlur(false);
        }
        panelStage.setPivotX(panelStage.getWidth() / 2f);
        panelStage.setPivotY(panelStage.getHeight() / 2f);
        Motion.dismissModal(panelStage, density, reducedMotion || !animations, this::close);
    }

    void close() {
        if (reordering) endReorder(true);
        dismissActions();
        ExpressiveGlass.clearPanelContentBlurImmediate(panel, highlight);
        Motion.cancelPanelAnim(panelStage);
        Motion.cancelPanelAnim(panel);
        if (embeddedHost != null) {
            if (!closed) {
                closed = true;
                panelStage.animate().cancel();
                panel.animate().cancel();
                stopHighlight();
                iconWaiters.clear();
                if (panelStage.getParent() == embeddedHost) embeddedHost.removeView(panelStage);
                dismissed.run();
            }
            return;
        }
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            iconWaiters.clear();
            dismissed.run();
        }
    }

    void show() {
        this.embeddedHost = null;
        load(true);
    }

    void setSmoked(boolean smoked) {
        ExpressiveGlass.setPanelSmoked(panel, smoked);
    }

    /** Embeds App Library directly inside an overlay host Activity (e.g. floating over Netflix / YouTube). */
    void showInOverlay(FrameLayout host, boolean smokedGlass) {
        this.embeddedHost = host;
        ExpressiveGlass.setPanelSmoked(panel, smokedGlass);
        load(true);
    }

    private void reveal() {
        boolean reduce = reducedMotion || !animations;
        panelStage.setAlpha(0f);
        if (!reduce) {
            panelStage.setScaleX(0.94f);
            panelStage.setScaleY(0.94f);
            panelStage.setTranslationY(84f * density);
        }
        if (embeddedHost != null) {
            panel.setOnClickListener(v -> { /* consume clicks inside panel */ });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    currentWidth + 2 * stagePad, currentHeight + 2 * stagePad, Gravity.CENTER);
            if (panelStage.getParent() != embeddedHost) {
                if (panelStage.getParent() instanceof ViewGroup) ((ViewGroup) panelStage.getParent()).removeView(panelStage);
                embeddedHost.addView(panelStage, lp);
            }
        } else {
            if (activity instanceof MainActivity) ((MainActivity) activity).prepareBackdropBlurForPopup();
            popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        }
        panelStage.post(() -> panelStage.postOnAnimation(() -> {
            if (closed || dismissing || !isShowing()) return;
            panelStage.setPivotX(panelStage.getWidth() / 2f);
            panelStage.setPivotY(panelStage.getHeight() / 2f);
            if (activity instanceof OverlayActivity) {
                ((OverlayActivity) activity).notifyContentRevealStarted();
            } else if (activity instanceof MainActivity) {
                ((MainActivity) activity).startBackdropBlurFadeIn();
            }
            Motion.revealModal(panelStage, density, reduce);
        }));
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    void refresh() {
        load(false);
    }

    /**
     * Reads the catalogue on LOADER, then fills it on the UI thread. reveal: from opening the library; the popup
     * appears once the latest load has filled it, even when a refresh replaced the opening's own load.
     */
    private void load(boolean reveal) {
        dismissActions();
        int load = loads.start(reveal);
        PackageManager pm = activity.getApplicationContext().getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !LibraryApps.excludedFromCatalog(excludedApps, pkg) && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, Long> installed = new HashMap<>();
            Map<String, Long> updatedMap = new HashMap<>();
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    installed.put(pkg, info.firstInstallTime);
                    updated = info.lastUpdateTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    installed.put(pkg, 0L);
                }
                updatedMap.put(pkg, updated);
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            final List<String> savedOrder = selectionMode ? null : loadCustomOrder();
            main.post(() -> {
                if (closed || !loads.isLatest(load)) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                installTimes.clear();
                installTimes.putAll(installed);
                updatedTimes.clear();
                updatedTimes.putAll(updatedMap);
                for (String pkg : ICON_CACHE.snapshot().keySet()) {
                    if (!unique.containsKey(pkg)) ICON_CACHE.remove(pkg);
                }
                labels.clear();
                labels.putAll(names);
                fill(new ArrayList<>(unique.values()), savedOrder);
                if (loads.takeReveal()) reveal();
            });
        });
    }

    private void fill(List<ResolveInfo> catalogue) {
        fill(catalogue, selectionMode ? null : loadCustomOrder());
    }

    private void fill(List<ResolveInfo> catalogue, @Nullable List<String> savedOrder) {
        totalApps = catalogue.size();
        apps = catalogue;
        Map<String, Boolean> home = new HashMap<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            home.put(pkg, onHome.test(pkg));
        }
        catalogTime = System.currentTimeMillis();
        Collator collator = getCollator();
        apps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            if (selectionMode) return LibraryApps.compareCatalog(home.get(pa), label(a), pa,
                    home.get(pb), label(b), pb, collator);
            int priority = LibraryApps.comparePriority(home.get(pa), installTimes.get(pa),
                    home.get(pb), installTimes.get(pb), catalogTime);
            if (priority != 0) return priority;
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        if (!selectionMode && savedOrder != null) applySavedOrder(savedOrder);
        showCatalog();
    }

    private List<String> loadCustomOrder() {
        SharedPreferences prefs = activity.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(ORDER_KEY, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    private void saveCustomOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < apps.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(apps.get(i).activityInfo.packageName);
        }
        activity.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE)
                .edit().putString(ORDER_KEY, sb.toString()).apply();
    }

    private void applySavedOrder(List<String> saved) {
        if (saved == null || saved.isEmpty()) return;
        Map<String, Integer> rank = new HashMap<>(saved.size() * 2);
        for (int i = 0; i < saved.size(); i++) rank.put(saved.get(i), i);
        List<ResolveInfo> ordered = new ArrayList<>(apps.size());
        List<ResolveInfo> extras = new ArrayList<>();
        for (ResolveInfo info : apps) {
            if (rank.containsKey(info.activityInfo.packageName)) ordered.add(info);
            else extras.add(info);
        }
        ordered.sort((a, b) -> Integer.compare(
                rank.get(a.activityInfo.packageName), rank.get(b.activityInfo.packageName)));
        apps = new ArrayList<>(apps.size());
        apps.addAll(ordered);
        apps.addAll(extras);
    }

    private int indexOfPkg(@Nullable String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < apps.size(); i++) {
            if (pkg.equals(apps.get(i).activityInfo.packageName)) return i;
        }
        return -1;
    }

    private TextView makeHeaderPill(String label) {
        TextView btn = new TextView(activity);
        btn.setText(label);
        btn.setTextSize(13f);
        btn.setTypeface(UiFont.get(activity, R.string.font_medium));
        btn.setTextColor(ExpressiveGlass.adaptivePillTextColors());
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setPadding(dp(14), 0, dp(14), 0);
        btn.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 17f, 0));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        btn.addOnLayoutChangeListener(syncHighlightOnLayout);
        btn.setOnFocusChangeListener((v, focused) -> {
            float scale = animations && !reducedMotion ? 1.07f : 1f;
            Motion.animateFocus(v, focused, scale, 1.5f, density, reducedMotion || !animations);
            if (focused) highlightPill(v);
        });
        return btn;
    }

    private void toggleReorder() {
        if (reordering) endReorder(true);
        else beginReorder(-1);
    }

    private void beginReorder(int pickUpIndex) {
        if (selectionMode || apps.size() < 2) return;
        reordering = true;
        movingPkg = (pickUpIndex >= 0 && pickUpIndex < apps.size())
                ? apps.get(pickUpIndex).activityInfo.packageName : null;
        if (pickUpIndex >= 0 && pickUpIndex < apps.size()) {
            selectedPosition = pickUpIndex;
            selectedPackage = movingPkg;
        }
        if (btnReorder != null) {
            btnReorder.setText(R.string.folder_btn_done);
            btnReorder.setActivated(true);
            btnReorder.setTextColor(ExpressiveGlass.adaptiveAccentTextColor(0xFF93D1FF));
        }
        int target = Math.max(0, Math.min(selectedPosition, apps.size() - 1));
        boolean fromHeader = highlightedIsPill;
        focusCatalogItem(target);
        if (catalog != null) {
            catalog.post(() -> {
                if (closed || catalog == null) return;
                refreshReorderVisuals();
                RecyclerView.ViewHolder vh = catalog.findViewHolderForAdapterPosition(target);
                if (vh != null && target < apps.size()) {
                    highlight(vh.itemView, apps.get(target).activityInfo.packageName, fromHeader);
                }
            });
        }
    }

    private void pickUpCard(int pos) {
        if (!reordering || pos < 0 || pos >= apps.size()) return;
        movingPkg = apps.get(pos).activityInfo.packageName;
        selectedPackage = movingPkg;
        selectedPosition = pos;
        refreshReorderVisuals();
        if (catalog != null) {
            RecyclerView.ViewHolder vh = catalog.findViewHolderForAdapterPosition(pos);
            if (vh != null) highlight(vh.itemView, movingPkg, false);
        }
    }

    private void dropCard() {
        if (movingPkg == null) return;
        movingPkg = null;
        saveCustomOrder();
        refreshReorderVisuals();
        if (catalog != null && selectedPosition >= 0 && selectedPosition < apps.size()) {
            RecyclerView.ViewHolder vh = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (vh != null) highlight(vh.itemView, apps.get(selectedPosition).activityInfo.packageName, false);
        }
    }

    private void endReorder(boolean save) {
        if (!reordering) return;
        reordering = false;
        movingPkg = null;
        if (save) saveCustomOrder();
        if (btnReorder != null) {
            btnReorder.setText(R.string.folder_btn_reorder);
            btnReorder.setActivated(false);
            btnReorder.setTextColor(ExpressiveGlass.adaptivePillTextColors());
        }
        refreshReorderVisuals();
        if (highlight != null && !highlightedIsPill && catalog != null
                && selectedPosition >= 0 && selectedPosition < apps.size()) {
            RecyclerView.ViewHolder vh = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (vh != null) {
                highlight(vh.itemView, apps.get(selectedPosition).activityInfo.packageName, false);
            }
        } else if (highlight != null && highlightedIsPill && btnReorder != null && btnReorder.hasFocus()) {
            highlightPill(btnReorder);
        }
    }

    private float cardAlpha(int pos) {
        if (!reordering) return 1f;
        if (movingPkg == null) return 0.84f;
        return (pos >= 0 && pos < apps.size() && movingPkg.equals(apps.get(pos).activityInfo.packageName)) ? 1f : 0.48f;
    }

    private Drawable cardBgFor(boolean home, boolean carried) {
        if (carried) {
            return cardBgCarriedState != null ? cardBgCarriedState.newDrawable()
                    : surface(0x3438BDF8, 18, 0xCC38BDF8);
        }
        Drawable.ConstantState bgState = home ? cardBgHomeState : cardBgRestState;
        return bgState != null ? bgState.newDrawable() : surface(home ? 0x22FFFFFF : 0x14FFFFFF, 18);
    }

    private void refreshReorderVisuals() {
        if (catalog == null) return;
        for (int i = 0; i < catalog.getChildCount(); i++) {
            View child = catalog.getChildAt(i);
            RecyclerView.ViewHolder vh = catalog.getChildViewHolder(child);
            if (!(vh instanceof Row)) continue;
            Row row = (Row) vh;
            int pos = row.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= apps.size()) continue;
            String pkg = apps.get(pos).activityInfo.packageName;
            boolean focused = child.hasFocus();
            boolean carried = reordering && movingPkg != null && movingPkg.equals(pkg);
            child.setAlpha(cardAlpha(pos));
            child.setBackground(cardBgFor(onHome.test(pkg), carried));
            float targetScale = carried ? 1.18f : (focused ? 1.12f : 1f);
            float targetLift = carried ? 4f : (focused ? 2f : 0f);
            if (animations) {
                Motion.animateFocus(row.icon, focused || carried, targetScale, targetLift, density, reducedMotion);
            } else {
                row.icon.setScaleX(1f);
                row.icon.setScaleY(1f);
                row.icon.setTranslationY(0f);
            }
        }
    }

    private void moveCarriedBy(int keyCode) {
        if (!reordering || movingPkg == null || catalog == null || catalogAdapter == null) return;
        int from = indexOfPkg(movingPkg);
        if (from < 0) return;
        int n = apps.size();
        boolean backHoriz = rtl ? keyCode == KeyEvent.KEYCODE_DPAD_RIGHT : keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean fwdHoriz = rtl ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        int to = from;
        if (backHoriz) {
            if (from > 0) to = from - 1;
        } else if (fwdHoriz) {
            if (from + 1 < n) to = from + 1;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (from >= COLUMNS) to = from - COLUMNS;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int lastRowStart = ((n - 1) / COLUMNS) * COLUMNS;
            if (from < lastRowStart) to = Math.min(from + COLUMNS, n - 1);
        }
        if (to == from) return;
        ResolveInfo moved = apps.remove(from);
        apps.add(to, moved);
        selectedPosition = to;
        selectedPackage = movingPkg;
        catalogAdapter.notifyItemMoved(from, to);
        final int target = to;
        catalog.scrollToPosition(target);
        refocusCarriedAfterLayout(target, 10);
    }

    private void refocusCarriedAfterLayout(int target, int remainingFrames) {
        if (closed || catalog == null) return;
        catalog.postOnAnimation(() -> {
            if (closed || catalog == null) return;
            if ((catalog.hasPendingAdapterUpdates() || catalog.isLayoutRequested()) && remainingFrames > 0) {
                refocusCarriedAfterLayout(target, remainingFrames - 1);
                return;
            }
            RecyclerView.ViewHolder vh = catalog.findViewHolderForAdapterPosition(target);
            if (vh != null) {
                if (!vh.itemView.hasFocus()) vh.itemView.requestFocus();
                refreshReorderVisuals();
                highlight(vh.itemView, movingPkg, true);
            }
        });
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon from the shared cache, or loaded on ICON_LOADER and delivered to every matching tile. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        long updated = updatedTimes.getOrDefault(pkg, 0L);
        icon.setTag(pkg);
        icon.setAlpha(1f);
        CachedIcon cached = ICON_CACHE.get(pkg);
        if (cached != null && cached.updated == updated) {
            icon.setImageDrawable(cached.create(activity));
            return;
        }
        icon.setImageDrawable(null);
        List<ImageView> waiters = iconWaiters.get(pkg);
        if (waiters != null) {
            if (!waiters.contains(icon)) waiters.add(icon);
            return;
        }
        waiters = new ArrayList<>(2);
        waiters.add(icon);
        iconWaiters.put(pkg, waiters);
        PackageManager pm = activity.getApplicationContext().getPackageManager();
        ICON_LOADER.execute(() -> {
            if (closed) return;
            Drawable loaded = loadDrawable(pm, app, pkg);
            int accent = loaded != null ? AppAdapter.vividColor(loaded) : 0;
            main.post(() -> {
                List<ImageView> pending = iconWaiters.remove(pkg);
                CachedIcon entry = loaded != null ? new CachedIcon(updated, loaded, accent) : null;
                if (entry != null) ICON_CACHE.put(pkg, entry);
                if (closed) return;
                deliverIcon(pkg, entry, pending);
            });
        });
    }

    @Nullable
    private static Drawable loadDrawable(PackageManager pm, ResolveInfo app, String pkg) {
        Drawable loaded = null;
        try {
            loaded = app.loadIcon(pm);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            loaded = null;
        }
        if (loaded == null || loaded.getIntrinsicWidth() <= 0 || loaded.getIntrinsicHeight() <= 0) {
            try {
                Drawable appIcon = pm.getApplicationIcon(pkg);
                if (appIcon != null && appIcon.getIntrinsicWidth() > 0 && appIcon.getIntrinsicHeight() > 0) {
                    loaded = appIcon;
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError ignored) {
                // Fall back to the generic app glyph on the UI thread.
            }
        }
        return loaded;
    }

    private void deliverIcon(String pkg, @Nullable CachedIcon entry, @Nullable List<ImageView> pending) {
        if (pending != null) {
            for (ImageView iv : pending) {
                if (pkg.equals(iv.getTag())) applyIcon(iv, entry);
            }
        }
        if (catalog == null) return;
        for (int i = 0; i < catalog.getChildCount(); i++) {
            ImageView iv = catalog.getChildAt(i).findViewById(R.id.icon);
            if (iv != null && pkg.equals(iv.getTag()) && (pending == null || !pending.contains(iv))) {
                applyIcon(iv, entry);
            }
        }
        if (highlight != null && !highlightedIsPill && pkg.equals(selectedPackage)) {
            highlight.setAccent(reordering ? REORDER_ACCENT : (entry != null ? entry.accent : 0));
        }
    }

    private void applyIcon(ImageView iv, @Nullable CachedIcon entry) {
        Drawable shown = entry != null ? entry.create(activity) : activity.getDrawable(R.drawable.ic_apps);
        iv.setImageDrawable(shown);
        iv.setAlpha(1f);
    }

    private void heading(String text) {
        TextView title = text(text, 18, 0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Reuse translated actions instead of shipping another hint in every locale. */
    private String hint(boolean home) {
        if (selectionMode) return activity.getString(android.R.string.ok) + ": "
                + activity.getString(home ? R.string.library_remove_shortcut : R.string.library_add_home);
        return activity.getString(home || okOpens ? R.string.library_hint_open : R.string.library_hint_add);
    }

    private void showCatalog() {
        reordering = false;
        movingPkg = null;
        stopHighlight();
        if (highlight != null && highlight.getParent() == panelStage) {
            panelStage.removeView(highlight);
        }
        resize(catalogWidth, apps.size() <= COLUMNS ? Math.min(dp(250), catalogHeight) : catalogHeight);
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(19), dp(6), dp(19), dp(6));
        header.setClipChildren(false);
        header.setClipToPadding(false);
        TextView title = text(activity.getString(R.string.app_library), 22, Color.WHITE);
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        title.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView count = text(activity.getResources().getQuantityString(
                R.plurals.library_app_count, totalApps, totalApps), 13, 0xA6FFFFFF);
        count.setSingleLine(true);
        boolean canReorder = !selectionMode && apps.size() >= 2;
        btnReorder = canReorder ? makeHeaderPill(activity.getString(R.string.folder_btn_reorder)) : null;
        if (btnReorder != null) {
            btnReorder.setOnClickListener(v -> toggleReorder());
            btnReorder.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT
                        || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusCatalogItem(Math.min(selectedPosition, apps.size() - 1));
                    return true;
                }
                return false;
            });
        }
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        if (btnReorder != null) countLp.rightMargin = dp(12);
        header.addView(count, countLp);
        if (btnReorder != null) {
            header.addView(btnReorder, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        // The launcher has no global supportsRtl: explicitly mirror this horizontal row.
        if (rtl) {
            if (btnReorder != null) {
                header.removeView(btnReorder);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, dp(34));
                rlp.rightMargin = dp(12);
                header.addView(btnReorder, 0, rlp);
            }
            header.removeView(count);
            header.addView(count, btnReorder != null ? 1 : 0);
            count.setPadding(0, 0, dp(12), 0);
        }
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        instructions = text(hint(false), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(19), 0, dp(19), dp(6));
        instructions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            heading(activity.getString(R.string.library_empty));
            ExpressiveGlass.applyAdaptiveContentTheme(panel);
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            return;
        }
        ExpressiveGlass.applyAdaptiveContentTheme(panel);
        shelf = new FrameLayout(activity);
        // The glass uses physical x/y coordinates; only the catalogue mirrors its item order.
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(true);
        shelf.setClipToPadding(true);
        RecyclerView list = TopFade.newRecyclerView(activity, 38f * density, true);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new GridLayoutManager(activity, COLUMNS, RecyclerView.VERTICAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setItemViewCacheSize(COLUMNS * 2);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(14), dp(12), dp(14), dp(20));
        catalogAdapter = new Catalog();
        catalogAdapter.setHasStableIds(true);
        list.setAdapter(catalogAdapter);
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setLightSurface(ExpressiveGlass.isLightGlass());
        highlight.setRadius(dp(18));
        highlight.setLiquidEnabled(animations && !reducedMotion);
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int pad = Math.round(GlassView.insetPx(density));
        panelStage.addView(highlight, new FrameLayout.LayoutParams(cardWidth + 2 * pad, cardHeight + 2 * pad));
        springX = new SpringAnimation(highlight, SpringAnimation.TRANSLATION_X);
        springY = new SpringAnimation(highlight, SpringAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) Motion.reduce(s);
            else s.setSpring(new SpringForce().setStiffness(290f).setDampingRatio(0.66f));
        }
        springX.addUpdateListener((anim, value, velocity) -> highlight.setVelocityX(velocity));
        springX.addEndListener((anim, canceled, value, velocity) -> highlight.setVelocityX(0f));
        springY.addUpdateListener((anim, value, velocity) -> highlight.setVelocityY(velocity));
        springY.addEndListener((anim, canceled, value, velocity) -> highlight.setVelocityY(0f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!highlightedIsPill) snapHighlight();
            }
        });
        panel.removeOnLayoutChangeListener(syncHighlightOnLayout);
        panel.addOnLayoutChangeListener(syncHighlightOnLayout);
        shelf.addOnLayoutChangeListener(syncHighlightOnLayout);
        list.addOnLayoutChangeListener(syncHighlightOnLayout);
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void focusCatalogItem(int target) {
        if (catalog == null || target < 0 || target >= apps.size()) return;
        RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(target);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        catalog.scrollToPosition(target);
        catalog.post(() -> {
            if (catalog == null || !isShowing()) return;
            RecyclerView.ViewHolder h = catalog.findViewHolderForAdapterPosition(target);
            if (h != null) h.itemView.requestFocus();
        });
    }

    private void setPanelContentBlur(boolean blurred) {
        ExpressiveGlass.animatePanelContentBlur(panel, highlight, density, blurred, reducedMotion || !animations);
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
        setPanelContentBlur(false);
    }

    private void restoreCardFocus() {
        if (catalog == null || !isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (selectionMode) {
            updateSelectionFocus(app, add);
            changed.run();
            fill(new ArrayList<>(apps));
            return;
        }
        selectedOffset = 0;
        if (add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = catalog.getLayoutManager().getDecoratedTop(card)
                        - params.topMargin - catalog.getPaddingTop();
            }
            // Advance through missing shortcuts instead of following the newly checked app
            // into the Home group. Preserve its screen slot when the catalogue is rebuilt.
            int next = LibraryApps.nextFocus(selectedPosition, apps.size(),
                    index -> !onHome.test(apps.get(index).activityInfo.packageName));
            if (next >= 0) {
                selectedPackage = apps.get(next).activityInfo.packageName;
                selectedPosition = next;
            }
        }
        dismissActions();
        changed.run();
        refresh();
    }

    private void updateSelectionFocus(ResolveInfo app, boolean add) {
        int position = apps.indexOf(app);
        int next = add ? -1 : LibraryApps.nextAfterRemoval(position, apps.size());
        selectedOffset = 0;
        if (!add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = catalog.getLayoutManager().getDecoratedTop(card)
                        - params.topMargin - catalog.getPaddingTop();
            }
        }
        selectedPackage = next >= 0 ? apps.get(next).activityInfo.packageName : app.activityInfo.packageName;
    }

    @SuppressWarnings("deprecation")
    private void open(ResolveInfo app) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        boolean glassAddon = MainActivity.isGlassAddon(app.activityInfo.packageName);
        boolean overHome = MainActivity.isHomeVisible();
        if (glassAddon) {
            Runnable launchNow = () -> {
                if (overHome) MainActivity.setAddonModalOpen(true);
                MainActivity.attachAddonBackdropExtras(intent, overHome);
                try {
                    RecentApps.recordLaunch(activity, app.activityInfo.packageName);
                    activity.startActivity(intent);
                    activity.overridePendingTransition(0, 0);
                    // When hosted inside OverlayActivity, let onPause() tear down once the remote add-on
                    // renders to avoid a 1-3 frame unblurred flash; close immediately in standalone popup mode.
                    if (embeddedHost == null) close();
                } catch (ActivityNotFoundException | SecurityException e) {
                    if (overHome) MainActivity.setAddonModalOpen(false);
                    Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
                }
            };
            if (!overHome && !ExpressiveGlass.hasOverlayRefraction()) {
                HomeKeyService.captureOverlayBackdropForAddon(launchNow);
            } else {
                launchNow.run();
            }
            return;
        }
        close();
        try {
            RecentApps.recordLaunch(activity, app.activityInfo.packageName);
            activity.startActivity(intent);
            if (animations && !reducedMotion) {
                activity.overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        ExpressiveGlass.applyPanelSurface(menuPanel, density, 20f, true, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        int menuPad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(menuPanel, density, animations, reducedMotion);
        final boolean[] dismissingMenu = new boolean[1];
        final PopupWindow[] menuRef = new PopupWindow[1];
        FrameLayout menuHost = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        PopupWindow m = menuRef[0];
                        if (!dismissingMenu[0] && m != null && m.isShowing()) {
                            dismissingMenu[0] = true;
                            setPanelContentBlur(false);
                            Motion.dismissDropdown(this, density, false, menuPad, reducedMotion, m::dismiss);
                        }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        menuHost.setClipChildren(false);
        menuHost.setClipToPadding(false);
        menuHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(menuHost);
        UiClickSound.attachFocusSound(menuHost);
        PopupWindow menu = new PopupWindow(menuHost, dp(220) + 2 * menuPad, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menuRef[0] = menu;
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            dismissingMenu[0] = false;
            Motion.cancelPanelAnim(menuHost);
            ringHost.animate().cancel();
            if (actionPopup == menu) {
                actionPopup = null;
                setPanelContentBlur(false);
            }
            restoreCardFocus();
        });
        addAction(rows, R.string.library_open, () -> open(app));
        boolean home = onHome.test(pkg);
        addAction(rows, home ? R.string.library_remove_shortcut : R.string.library_add_home,
                () -> changeHome(app, !home));
        if (!selectionMode && apps.size() >= 2) {
            addAction(rows, R.string.tile_move, () -> {
                dismissActions();
                int idx = indexOfPkg(pkg);
                beginReorder(idx >= 0 ? idx : selectedPosition);
            });
        }
        Intent info = SystemSettings.appInfo(activity, pkg);
        if (info != null) addAction(rows, R.string.library_app_info, () -> {
            close();
            try {
                activity.startActivity(info);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
            }
        });
        int menuWidth = dp(220);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(80));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth + 2 * menuPad);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int[] panelLocation = new int[2];
        panel.getLocationOnScreen(panelLocation);
        Rect screen = new Rect(panelLocation[0], panelLocation[1],
                panelLocation[0] + panel.getWidth(), panelLocation[1] + panel.getHeight());
        boolean reduce = reducedMotion || !animations;
        int x = Math.max(screen.left + dp(12), Math.min(location[0] + anchor.getWidth() - dp(12), screen.right - menuWidth - dp(12)));
        int y = Math.max(screen.top + dp(12), Math.min(location[1] + dp(8), screen.bottom - menuPanel.getMeasuredHeight() - dp(12)));
        menuHost.setAlpha(0f);
        if (!reduce) {
            menuHost.setPivotY(menuPad);
            menuHost.setScaleX(0.94f);
            menuHost.setScaleY(0.90f);
            menuHost.setTranslationY(-44f * density);
        }
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x - menuPad, y - menuPad);
        rows.getChildAt(0).requestFocus();
        menuHost.post(() -> menuHost.postOnAnimation(() -> {
            if (actionPopup != menu || !menu.isShowing()) return;
            setPanelContentBlur(true);
            Motion.revealDropdown(menuHost, density, false, menuPad, reduce);
        }));
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 16f, false));
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        Motion.attachRowFocus(row, density, reducedMotion);
        ExpressiveGlass.applyAdaptiveContentTheme(row);
        rows.addView(row);
        row.setOnKeyListener((v, key, event) -> {
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_FIRST_STRONG);
        view.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.setLayoutParams(params);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(0, dp(4), 0, 0);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ExpressiveGlass.markPreserveImage(icon);
            content.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView name = text("", 13, ExpressiveGlass.adaptivePrimaryTextColor());
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(36));
            nameParams.topMargin = dp(6);
            content.addView(name, nameParams);
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(1), dp(1), 0);
            row.addView(check, badge);
            return new Row(row, icon, name, check);
        }
        @Override public void onViewRecycled(@NonNull Row holder) {
            holder.icon.animate().cancel();
            holder.icon.setScaleX(1f);
            holder.icon.setScaleY(1f);
            holder.icon.setTranslationY(0f);
            holder.icon.setAlpha(1f);
            super.onViewRecycled(holder);
        }

        @Override public long getItemId(int position) {
            String pkg = apps.get(position).activityInfo.packageName;
            Long id = stableIds.get(pkg);
            if (id == null) {
                id = nextStableId++;
                stableIds.put(pkg, id);
            }
            return id;
        }

        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            ImageView iconView = holder.icon;
            iconView.animate().cancel();
            iconView.setAlpha(1f);
            boolean focusedNow = holder.itemView.hasFocus();
            boolean carriedNow = reordering && movingPkg != null && movingPkg.equals(pkg);
            float iconS = animations && !reducedMotion
                    ? (carriedNow ? 1.18f : (focusedNow ? 1.12f : 1f)) : 1f;
            iconView.setScaleX(iconS);
            iconView.setScaleY(iconS);
            iconView.setTranslationY(animations && !reducedMotion
                    ? (carriedNow ? -dp(4) : (focusedNow ? -dp(2) : 0f)) : 0f);
            bindIcon(iconView, app);
            TextView name = holder.name;
            String appLabel = label(app);
            name.setText(appLabel);
            name.setEllipsize(TextUtils.TruncateAt.END);
            boolean home = onHome.test(pkg);
            boolean recent = !selectionMode && LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            String badgeState = home ? activity.getString(R.string.library_on_home)
                    : recent ? activity.getString(R.string.library_recent) : null;
            holder.itemView.setContentDescription(badgeState != null ? appLabel + ", " + badgeState : appLabel);
            holder.itemView.setAlpha(cardAlpha(position));
            holder.itemView.setBackground(cardBgFor(home, carriedNow));
            View check = holder.check;
            if (home || recent) {
                int badgeSize = dp(home ? 16 : 8);
                ViewGroup.LayoutParams checkLp = check.getLayoutParams();
                if (checkLp.width != badgeSize || checkLp.height != badgeSize) {
                    checkLp.width = badgeSize;
                    checkLp.height = badgeSize;
                    check.setLayoutParams(checkLp);
                }
                if (home) check.setBackgroundResource(R.drawable.library_check);
                else check.setBackground(recentDotState != null ? recentDotState.newDrawable()
                        : surface(0xFF70D9A0, 8));
                check.setVisibility(View.VISIBLE);
            } else {
                check.setVisibility(View.GONE);
            }
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= apps.size()) return;
                if (reordering) {
                    if (movingPkg == null) pickUpCard(pos);
                    else dropCard();
                    return;
                }
                ResolveInfo curApp = apps.get(pos);
                String curPkg = curApp.activityInfo.packageName;
                boolean onHomeNow = onHome.test(curPkg);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(curApp);
                else changeHome(curApp, !onHomeNow);
            });
            holder.itemView.setOnLongClickListener(selectionMode ? null : v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= apps.size()) return true;
                if (reordering) {
                    if (movingPkg == null) pickUpCard(pos);
                    else dropCard();
                    return true;
                }
                showActions(apps.get(pos), v);
                return true;
            });
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                int pos = holder.getBindingAdapterPosition();
                String curPkg = (pos >= 0 && pos < apps.size()) ? apps.get(pos).activityInfo.packageName : pkg;
                boolean carried = reordering && movingPkg != null && movingPkg.equals(curPkg);
                iconView.setAlpha(1f);
                if (animations) {
                    Motion.animateFocus(iconView, focused || carried,
                            carried ? 1.18f : 1.12f, carried ? 4f : 2f, density, reducedMotion);
                }
                if (focused) {
                    selectedPackage = curPkg;
                    if (!reordering) instructions.setText(hint(onHome.test(curPkg)));
                    v.post(() -> {
                        int pNow = holder.getBindingAdapterPosition();
                        String pPkg = (pNow >= 0 && pNow < apps.size())
                                ? apps.get(pNow).activityInfo.packageName : curPkg;
                        if (v.hasFocus() && catalog != null) highlight(v, pPkg, true);
                    });
                    if (pos != RecyclerView.NO_POSITION) selectedPosition = pos;
                }
            });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = holder.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION || index >= apps.size()) return false;
                if (key == KeyEvent.KEYCODE_MENU && !selectionMode && !reordering) {
                    showActions(apps.get(index), v);
                    return true;
                }
                if (reordering && movingPkg != null) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                            || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        moveCarriedBy(key);
                        return true;
                    }
                    return false;
                }
                boolean backHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean fwdHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (backHoriz && index % COLUMNS == 0) return true;
                if (fwdHoriz && (index % COLUMNS == COLUMNS - 1 || index == apps.size() - 1)) return true;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (index >= COLUMNS) {
                        focusCatalogItem(index - COLUMNS);
                    } else if (!selectionMode && btnReorder != null && btnReorder.getVisibility() == View.VISIBLE) {
                        btnReorder.requestFocus();
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int lastRowStart = ((apps.size() - 1) / COLUMNS) * COLUMNS;
                    if (index < lastRowStart) focusCatalogItem(Math.min(index + COLUMNS, apps.size() - 1));
                    return true;
                }
                return false;
            });
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final View check;

        Row(View view, ImageView icon, TextView name, View check) {
            super(view);
            this.icon = icon;
            this.name = name;
            this.check = check;
        }
    }
}
