package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
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
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple tvOS style frosted-glass App Switcher (Recents) carousel.
 * - Left / Right glides across recent apps with the living liquid-blob GlassView highlight.
 * - OK switches immediately into the focused app.
 * - Double-tap Up (Apple TV swipe-up gesture) lifts the card on the first tap and flings/closes it on the second.
 * - Down reaches quick action pills (Close app · App info · Close all).
 * - Hold OK / Menu opens a frosted context menu over the blurred carousel.
 */
final class RecentsDialog {

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final Map<String, String> appLabels;
    @Nullable
    private final MediaSessionTracker mediaTracker;
    private final boolean animations;
    private final boolean reducedMotion;
    private final boolean rtl;
    private final float density;
    private final Runnable onDismissed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout panelStage;
    private final LinearLayout panel;
    final PopupWindow popup;

    private final int stagePad;
    private final int dialogWidth;
    private final int cardWidth;
    private final int cardHeight;

    private TextView countView;
    private TextView ramView;
    private FrameLayout shelf;
    private RecyclerView list;
    private GlassView highlight;
    private SpringAnimation springX, springY;
    private float scrollOffsetX;
    private boolean highlightShown;
    private boolean highlightOnBar;
    private CardAdapter cardAdapter;

    private LinearLayout actionBar;
    private TextView btnCloseOne;
    private TextView btnAppInfo;
    private TextView btnCloseAll;
    private PopupWindow actionPopup;

    private List<RecentApps.Item> items = new ArrayList<>();
    private int selectedPosition;
    private int lastFocusedCardPos = -1;
    private int armedClosePos = RecyclerView.NO_POSITION;
    private boolean closingItem;
    private boolean closed;
    private boolean dismissing;
    @Nullable
    private FrameLayout embeddedHost;

    RecentsDialog(Activity activity, Map<String, String> appLabels, @Nullable MediaSessionTracker mediaTracker,
                  boolean animations, boolean reducedMotion, Runnable onDismissed) {
        this.activity = activity;
        this.appLabels = appLabels;
        this.mediaTracker = mediaTracker;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.onDismissed = onDismissed;
        this.stagePad = FocusRingController.wrapInsetPx(density);

        panelStage = new FrameLayout(activity);
        panelStage.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panelStage.setClipChildren(false);
        panelStage.setClipToPadding(false);

        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);
        panel.setPadding(dp(22), dp(18), dp(22), dp(14));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        FrameLayout.LayoutParams panelInStage = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelInStage.setMargins(stagePad, stagePad, stagePad, stagePad);
        panelStage.addView(panel, panelInStage);

        View decor = activity.getWindow().getDecorView();
        int screenW = decor.getWidth() > 0 ? decor.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        dialogWidth = Math.min(dp(860), Math.max(dp(420), screenW - dp(56)));
        cardWidth = dp(228);
        cardHeight = Math.round(cardWidth * 9f / 16f);

        popup = new PopupWindow(panelStage, dialogWidth + 2 * stagePad, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            closingItem = false;
            panelStage.animate().cancel();
            panel.animate().cancel();
            dismissActions();
            stopHighlight();
            onDismissed.run();
        });
    }

    void show() {
        showInternal(null, false);
    }

    /** Embeds Recents directly inside an overlay host Activity (e.g. floating over Netflix / YouTube). */
    void showInOverlay(FrameLayout host, boolean smokedGlass) {
        showInternal(host, smokedGlass);
    }

    void setSmoked(boolean smoked) {
        ExpressiveGlass.setPanelSmoked(panel, smoked);
    }

    private void showInternal(@Nullable FrameLayout host, boolean smoked) {
        this.embeddedHost = host;
        ExpressiveGlass.setPanelSmoked(panel, smoked);
        List<MediaController> mediaSnapshot = mediaTracker != null ? mediaTracker.controllers() : null;
        LOADER.execute(() -> {
            List<RecentApps.Item> loaded = RecentApps.load(activity, appLabels, mediaSnapshot);
            main.post(() -> {
                if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                items = new ArrayList<>(loaded);
                selectedPosition = 0;
                armedClosePos = RecyclerView.NO_POSITION;
                buildContent();
                reveal();
            });
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
        if (!forceModal && armedClosePos != RecyclerView.NO_POSITION) {
            clearArmedClose();
            return;
        }
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
        dismissActions();
        ExpressiveGlass.clearPanelContentBlurImmediate(panel, highlight);
        Motion.cancelPanelAnim(panelStage);
        Motion.cancelPanelAnim(panel);
        cancelVisibleCardAnimations();
        if (embeddedHost != null) {
            if (!closed) {
                closed = true;
                closingItem = false;
                panelStage.animate().cancel();
                panel.animate().cancel();
                stopHighlight();
                if (panelStage.getParent() == embeddedHost) embeddedHost.removeView(panelStage);
                onDismissed.run();
            }
            return;
        }
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;
            onDismissed.run();
        }
    }

    private void cancelVisibleCardAnimations() {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            child.animate().cancel();
            RecyclerView.ViewHolder vh = list.getChildViewHolder(child);
            if (vh instanceof CardHolder) {
                CardHolder h = (CardHolder) vh;
                h.card.animate().cancel();
                h.closeOverlay.animate().cancel();
                if (h.sheen != null) h.sheen.reset();
            }
        }
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
                    dialogWidth + 2 * stagePad, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            if (panelStage.getParent() != embeddedHost) {
                if (panelStage.getParent() instanceof ViewGroup) ((ViewGroup) panelStage.getParent()).removeView(panelStage);
                embeddedHost.addView(panelStage, lp);
            }
        } else {
            if (activity instanceof MainActivity) ((MainActivity) activity).prepareBackdropBlurForPopup();
            popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        }
        if (!items.isEmpty() && list != null) {
            list.post(() -> {
                if (!closed && isShowing()) focusCard(0);
            });
        } else {
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
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

    private void buildContent() {
        stopHighlight();
        highlightOnBar = false;
        if (highlight != null && highlight.getParent() == panelStage) {
            panelStage.removeView(highlight);
        }
        panel.removeAllViews();

        // Header: Title + count on left, live Free RAM indicator on right.
        LinearLayout header = new LinearLayout(activity);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(6), dp(2));

        TextView title = makeText(activity.getString(R.string.recents_title), 22f, Color.WHITE);
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));

        countView = makeText("", 13.5f, 0xA6FFFFFF);
        countView.setSingleLine(true);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.leftMargin = dp(12);
        header.addView(countView, cLp);

        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));

        ramView = makeText("", 13f, 0xB3FFFFFF);
        ramView.setSingleLine(true);
        header.addView(ramView, new LinearLayout.LayoutParams(-2, -2));

        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        updateHeaderStats();

        if (items.isEmpty()) {
            TextView empty = makeText(activity.getString(R.string.recents_empty), 16f, 0xA6FFFFFF);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(54), dp(16), dp(54));
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            ExpressiveGlass.applyAdaptiveContentTheme(panel);
            return;
        }

        // Horizontal card carousel + living liquid-blob highlight overlay.
        shelf = new FrameLayout(activity);
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(true);
        shelf.setClipToPadding(true);

        list = new FadingHorizontalRecyclerView(activity, 36f * density);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        if (animations && !reducedMotion) {
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setRemoveDuration(0L);
            animator.setMoveDuration(220L);
            animator.setAddDuration(160L);
            animator.setSupportsChangeAnimations(false);
            list.setItemAnimator(animator);
        } else {
            list.setItemAnimator(null);
        }
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(20), dp(28), dp(20), dp(12));
        cardAdapter = new CardAdapter();
        list.setAdapter(cardAdapter);
        shelf.addView(list, new FrameLayout.LayoutParams(-1, dp(224)));

        float focusScale = cardFocusScale();
        highlight = new GlassView(activity, null);
        highlight.setLightSurface(ExpressiveGlass.isLightGlass());
        highlight.setRadius(dp(16) * focusScale);
        highlight.setLiquidEnabled(animations && !reducedMotion);
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int pad = Math.round(GlassView.insetPx(density));
        panelStage.addView(highlight, new FrameLayout.LayoutParams(
                Math.round(cardWidth * focusScale) + 2 * pad,
                Math.round(cardHeight * focusScale) + 2 * pad));

        scrollOffsetX = 0f;
        springX = new SpringAnimation(highlight, new FloatPropertyCompat<View>("recentsScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffsetX; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffsetX); }
        });
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
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (highlightOnBar) return;
                scrollOffsetX += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });

        LinearLayout.LayoutParams shelfLp = new LinearLayout.LayoutParams(-1, dp(224));
        shelfLp.topMargin = dp(2);
        panel.addView(shelf, shelfLp);

        // Bottom frosted quick-action bar: Close selected · App info · Close all.
        actionBar = new LinearLayout(activity);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.CENTER);
        actionBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        actionBar.setClipChildren(false);
        actionBar.setClipToPadding(false);

        btnCloseOne = makeActionPill(activity.getString(R.string.recents_close_app));
        btnAppInfo = makeActionPill(activity.getString(R.string.library_app_info));
        btnCloseAll = makeActionPill(activity.getString(R.string.recents_close_all));

        btnCloseOne.setOnClickListener(v -> {
            if (selectedPosition >= 0 && selectedPosition < items.size()) {
                closeItemAt(selectedPosition, true);
            }
        });
        btnAppInfo.setOnClickListener(v -> {
            if (selectedPosition >= 0 && selectedPosition < items.size()) {
                openAppInfo(items.get(selectedPosition));
            }
        });
        btnCloseAll.setOnClickListener(v -> closeAllItems());

        View[] pills = {btnCloseOne, btnAppInfo, btnCloseAll};
        for (int i = 0; i < pills.length; i++) {
            final int idx = i;
            pills[i].setOnFocusChangeListener((v, focused) -> {
                Motion.animateFocus(v, focused, 1.06f, 1.5f, density, reducedMotion);
                if (focused) {
                    clearArmedClose();
                    highlightPill(v);
                }
            });
            pills[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                boolean backHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean fwdHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (backHoriz && idx == 0) return true;
                if (fwdHoriz && idx == pills.length - 1) return true;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    focusCard(Math.max(0, Math.min(selectedPosition, items.size() - 1)));
                    return true;
                }
                return false;
            });
        }

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(-2, dp(36));
        p1.rightMargin = dp(10);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-2, dp(36));
        p2.rightMargin = dp(10);
        actionBar.addView(btnCloseOne, p1);
        actionBar.addView(btnAppInfo, p2);
        actionBar.addView(btnCloseAll, new LinearLayout.LayoutParams(-2, dp(36)));

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.topMargin = dp(2);
        panel.addView(actionBar, barLp);
        ExpressiveGlass.applyAdaptiveContentTheme(panel);
    }

    private void updateHeaderStats() {
        if (countView != null) {
            countView.setText(activity.getResources().getQuantityString(
                    R.plurals.library_app_count, items.size(), items.size()));
        }
        if (ramView != null) {
            long freeMb = RecentApps.freeRamMb(activity);
            ramView.setText(freeMb > 0 ? activity.getString(R.string.recents_free_ram, (int) freeMb) : "");
        }
    }

    private void stopHighlight() {
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        if (list != null) list.stopScroll();
        highlightShown = false;
    }

    private void hideHighlight() {
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        highlightShown = false;
        if (highlight != null) {
            highlight.resetMotion();
            highlight.setAlpha(0f);
        }
    }

    private float cardFocusScale() {
        return animations && !reducedMotion ? 1.06f : 1f;
    }

    private void highlightCard(View cardFrame, int accent, boolean animate) {
        if (highlight == null || shelf == null || cardFrame.getParent() == null) return;
        Rect rect = new Rect();
        if (!FocusRingController.locateInHost(panelStage, cardFrame, rect)) return;
        highlightOnBar = false;
        int pad = Math.round(GlassView.insetPx(density));
        float scale = cardFocusScale();
        float liftPx = animations && !reducedMotion ? 4f * density : 0f;
        int w = Math.round(rect.width() * scale) + 2 * pad;
        int h = Math.round(rect.height() * scale) + 2 * pad;
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(dp(16) * scale);
        highlight.setAccent(accent);
        float x = rect.exactCenterX() - w / 2f + scrollOffsetX;
        float y = rect.exactCenterY() - h / 2f - liftPx;
        if (!highlightShown || !animate || !animations) {
            springX.cancel();
            springY.cancel();
            highlight.resetMotion();
            highlight.setTranslationX(x - scrollOffsetX);
            highlight.setTranslationY(y);
        } else {
            highlight.onJump((x - scrollOffsetX) - highlight.getTranslationX(), y - highlight.getTranslationY());
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private void highlightPill(View pill) {
        if (highlight == null || pill.getParent() == null) return;
        if (pill.getWidth() <= 0 || pill.getHeight() <= 0) {
            pill.post(() -> {
                if (pill.hasFocus()) highlightPill(pill);
            });
            return;
        }
        Rect rect = new Rect();
        if (!FocusRingController.locateInHost(panelStage, pill, rect)) return;
        highlightOnBar = true;
        int pad = Math.round(GlassView.insetPx(density));
        float scale = animations && !reducedMotion ? 1.06f : 1f;
        float liftPx = animations && !reducedMotion ? 1.5f * density : 0f;
        float targetW = rect.width() * scale;
        float targetH = rect.height() * scale;
        int w = Math.round(targetW) + 2 * pad;
        int h = Math.round(targetH) + 2 * pad;
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(Math.min(targetW, targetH) * 0.5f);
        highlight.setAccent(0);
        float x = rect.exactCenterX() - w / 2f + scrollOffsetX;
        float y = rect.exactCenterY() - h / 2f - liftPx;
        if (!highlightShown || !animations) {
            springX.cancel();
            springY.cancel();
            highlight.resetMotion();
            highlight.setTranslationX(x - scrollOffsetX);
            highlight.setTranslationY(y);
        } else {
            highlight.onJump((x - scrollOffsetX) - highlight.getTranslationX(), y - highlight.getTranslationY());
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private void focusCard(int target) {
        if (list == null || target < 0 || target >= items.size()) return;
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(target);
        if (vh != null) {
            vh.itemView.requestFocus();
            return;
        }
        list.scrollToPosition(target);
        list.post(() -> {
            if (list == null || !isShowing()) return;
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(target);
            if (h != null) h.itemView.requestFocus();
        });
    }

    private void clearArmedClose() {
        if (armedClosePos == RecyclerView.NO_POSITION) return;
        int prev = armedClosePos;
        armedClosePos = RecyclerView.NO_POSITION;
        if (list == null) return;
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(prev);
        if (vh instanceof CardHolder) {
            CardHolder h = (CardHolder) vh;
            h.itemView.animate().cancel();
            h.itemView.animate().translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(reducedMotion ? Motion.REDUCED_MS : 185L)
                    .setInterpolator(Motion.easeOutExpo()).start();
            h.closeOverlay.animate().cancel();
            h.closeOverlay.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(120L)
                    .withEndAction(() -> h.closeOverlay.setVisibility(View.GONE)).start();
            if (h.itemView.hasFocus() && prev < items.size()) {
                h.itemView.post(() -> {
                    if (h.itemView.hasFocus() && prev < items.size()) highlightCard(h.card, items.get(prev).accent, true);
                });
            }
        }
    }

    private void armClose(int pos, CardHolder h) {
        armedClosePos = pos;
        hideHighlight();
        h.itemView.animate().cancel();
        h.itemView.animate().translationY(-dp(20)).scaleX(1.03f).scaleY(1.03f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 210L)
                .setInterpolator(reducedMotion ? Motion.smoothDecel() : Motion.springPop()).start();
        h.closeOverlay.animate().cancel();
        h.closeOverlay.setVisibility(View.VISIBLE);
        h.closeOverlay.setAlpha(0f);
        h.closeOverlay.setScaleX(0.88f);
        h.closeOverlay.setScaleY(0.88f);
        h.closeOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 180L)
                .setInterpolator(reducedMotion ? Motion.smoothDecel() : Motion.springPop()).start();
    }

    private void closeItemAt(int pos, boolean returnFocusToCard) {
        if (closingItem || pos < 0 || pos >= items.size()) return;
        closingItem = true;
        RecentApps.Item removed = items.get(pos);
        armedClosePos = RecyclerView.NO_POSITION;
        hideHighlight();

        RecyclerView.ViewHolder vh = list != null ? list.findViewHolderForAdapterPosition(pos) : null;
        Runnable finishRemove = () -> {
            closingItem = false;
            if (closed) return;
            boolean moreRemain = items.size() > 1;
            boolean forceStopping = RecentApps.closeApp(activity, removed.pkg, mediaTracker, moreRemain);
            int curIdx = items.indexOf(removed);
            if (curIdx >= 0) {
                items.remove(curIdx);
                if (cardAdapter != null) cardAdapter.notifyItemRemoved(curIdx);
            }
            updateHeaderStats();
            if (forceStopping) {
                // Settings App Info takes focus for ~0.4s to execute system Force Stop and returns automatically.
                return;
            }
            if (items.isEmpty()) {
                buildContent();
                panel.setFocusableInTouchMode(true);
                panel.requestFocus();
                return;
            }
            selectedPosition = Math.max(0, Math.min(pos, items.size() - 1));
            if (returnFocusToCard && list != null) {
                Runnable focusNext = () -> {
                    if (!closed && isShowing()) focusCard(selectedPosition);
                };
                RecyclerView.ItemAnimator anim = list.getItemAnimator();
                if (anim != null) {
                    list.post(() -> anim.isRunning(focusNext::run));
                } else {
                    list.post(focusNext);
                }
            }
        };

        if (vh != null && animations && !reducedMotion) {
            vh.itemView.animate().cancel();
            vh.itemView.animate().translationY(-dp(165)).rotationX(7f).alpha(0f).scaleX(0.82f).scaleY(0.82f)
                    .setDuration(180L).setInterpolator(Motion.easeInExit())
                    .withLayer()
                    .withEndAction(() -> {
                        vh.itemView.setTranslationY(0f);
                        vh.itemView.setRotationX(0f);
                        vh.itemView.setAlpha(1f);
                        vh.itemView.setScaleX(1f);
                        vh.itemView.setScaleY(1f);
                        finishRemove.run();
                    }).start();
        } else {
            finishRemove.run();
        }
    }

    private void closeAllItems() {
        if (items.isEmpty()) return;
        List<String> pkgs = new ArrayList<>(items.size());
        for (RecentApps.Item item : items) pkgs.add(item.pkg);
        int count = pkgs.size();
        android.content.Context appCtx = activity.getApplicationContext();
        String msg = activity.getResources().getQuantityString(
                R.plurals.recents_closed_all_toast, count, count);
        Runnable showToast = () -> Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show();
        boolean forceStopping = RecentApps.closeAll(activity, pkgs, mediaTracker, showToast);
        if (!forceStopping) showToast.run();
        close();
    }

    private void openAppInfo(RecentApps.Item item) {
        Intent info = SystemSettings.appInfo(activity, item.pkg);
        if (info == null) return;
        info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(info);
            close();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.label), Toast.LENGTH_SHORT).show();
        }
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

    private void showCardActions(RecentApps.Item item, int pos, View anchor) {
        dismissActions();
        clearArmedClose();
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
        PopupWindow menu = new PopupWindow(menuHost, dp(230) + 2 * menuPad, ViewGroup.LayoutParams.WRAP_CONTENT, true);
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
            if (!closed && isShowing() && !items.isEmpty()) {
                panel.post(() -> {
                    if (!closed && isShowing() && actionPopup == null) {
                        focusCard(Math.max(0, Math.min(selectedPosition, items.size() - 1)));
                    }
                });
            }
        });

        addActionRow(rows, activity.getString(R.string.recents_switch), () -> {
            close();
            switchToAppAnimated(item.pkg);
        });
        addActionRow(rows, activity.getString(R.string.recents_close_app), () -> {
            dismissActions();
            closeItemAt(pos, true);
        });
        Intent info = SystemSettings.appInfo(activity, item.pkg);
        if (info != null) {
            addActionRow(rows, activity.getString(R.string.library_app_info), () -> openAppInfo(item));
        }

        int menuWidth = dp(230);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(80));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth + 2 * menuPad);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int[] pLoc = new int[2];
        panel.getLocationOnScreen(pLoc);
        Rect bounds = new Rect(pLoc[0], pLoc[1], pLoc[0] + panel.getWidth(), pLoc[1] + panel.getHeight());
        boolean reduce = reducedMotion || !animations;
        int x = Math.max(bounds.left + dp(12), Math.min(loc[0] + dp(16), bounds.right - menuWidth - dp(12)));
        int y = Math.max(bounds.top + dp(12), Math.min(loc[1] + dp(10), bounds.bottom - menuPanel.getMeasuredHeight() - dp(12)));

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

    @SuppressWarnings("deprecation")
    private void switchToAppAnimated(String pkg) {
        boolean isAddon = MainActivity.isGlassAddon(pkg);
        if (RecentApps.switchToApp(activity, pkg)) {
            if (isAddon) {
                activity.overridePendingTransition(0, 0);
            } else if (animations && !reducedMotion) {
                activity.overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        }
    }

    private void addActionRow(LinearLayout rows, String label, Runnable action) {
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
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (key == KeyEvent.KEYCODE_DPAD_UP && index == 0)
                    || (key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1));
        });
    }

    private TextView makeActionPill(String label) {
        TextView btn = new TextView(activity);
        btn.setText(label);
        btn.setTextSize(13.5f);
        btn.setTypeface(UiFont.get(activity, R.string.font_medium));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), 0, dp(16), 0);
        btn.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 18f, 0));
        btn.setTextColor(ExpressiveGlass.adaptivePillTextColors());
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        return btn;
    }

    private TextView makeText(String value, float sizeSp, int color) {
        TextView tv = new TextView(activity);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(UiFont.get(activity, R.string.font_medium));
        tv.setLetterSpacing(0.01f);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static int blendColor(int a, int b, float ratioB) {
        float ratioA = 1f - ratioB;
        int r = Math.round(((a >> 16) & 0xFF) * ratioA + ((b >> 16) & 0xFF) * ratioB);
        int g = Math.round(((a >> 8) & 0xFF) * ratioA + ((b >> 8) & 0xFF) * ratioB);
        int bl = Math.round((a & 0xFF) * ratioA + (b & 0xFF) * ratioB);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Uses Android's native partial-strip horizontal fading edge (fading only ~36dp edge strips)
     * instead of forcing the entire 1700px carousel into an offscreen LAYER_TYPE_HARDWARE FBO on every scroll frame.
     */
    private static final class FadingHorizontalRecyclerView extends RecyclerView {
        FadingHorizontalRecyclerView(@NonNull android.content.Context context, float lengthPx) {
            super(context);
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength(Math.max(1, Math.round(lengthPx)));
        }

        @Override
        protected boolean isPaddingOffsetRequired() {
            return true;
        }

        @Override
        protected int getLeftPaddingOffset() {
            return -getPaddingLeft();
        }

        @Override
        protected int getRightPaddingOffset() {
            return getPaddingRight();
        }

        @Override
        protected int getTopPaddingOffset() {
            return -getPaddingTop();
        }

        @Override
        protected int getBottomPaddingOffset() {
            return getPaddingBottom();
        }

        @Override
        protected float getLeftFadingEdgeStrength() {
            int len = getHorizontalFadingEdgeLength();
            if (len <= 0) return 0f;
            return Math.max(0f, Math.min(1f, computeHorizontalScrollOffset() / (float) len));
        }

        @Override
        protected float getRightFadingEdgeStrength() {
            int len = getHorizontalFadingEdgeLength();
            if (len <= 0) return 0f;
            int remaining = computeHorizontalScrollRange() - computeHorizontalScrollExtent()
                    - computeHorizontalScrollOffset();
            return Math.max(0f, Math.min(1f, remaining / (float) len));
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setClipChildren(false);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(9), 0, dp(9), 0);
            root.setLayoutParams(params);

            FrameLayout card = new FrameLayout(activity);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            card.setClipToOutline(true);
            ExpressiveGlass.disableOutlineShadow(card);
            card.setCameraDistance(8000f * density);
            TileSheenDrawable sheen = (animations && !reducedMotion) ? new TileSheenDrawable() : null;
            if (sheen != null) card.setForeground(sheen);
            root.addView(card, new LinearLayout.LayoutParams(cardWidth, cardHeight));

            ImageView banner = new ImageView(activity);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ExpressiveGlass.markPreserveImage(banner);
            card.addView(banner, new FrameLayout.LayoutParams(-1, -1));

            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ExpressiveGlass.markPreserveImage(icon);
            card.addView(icon, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

            // Playing badge (top-right corner).
            TextView playingBadge = makeText("\u25CF  " + activity.getString(R.string.recents_playing_badge), 11f, Color.WHITE);
            playingBadge.setPadding(dp(9), dp(4), dp(9), dp(4));
            GradientDrawable playBg = new GradientDrawable();
            playBg.setColor(0xC413281A);
            playBg.setCornerRadius(dp(12));
            playBg.setStroke(Math.max(1, dp(1)), 0xCC34C759);
            playingBadge.setBackground(playBg);
            playingBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            pbLp.setMargins(0, dp(8), dp(8), 0);
            card.addView(playingBadge, pbLp);

            // Armed swipe-up-to-close overlay: subtle dark scrim + crimson rim + centered pill for high contrast.
            FrameLayout closeOverlay = new FrameLayout(activity);
            GradientDrawable armedRim = new GradientDrawable();
            armedRim.setColor(0x73000000);
            armedRim.setCornerRadius(dp(16));
            armedRim.setStroke(dp(2), 0xE6EF4444);
            closeOverlay.setBackground(armedRim);
            closeOverlay.setVisibility(View.GONE);

            TextView closeBadge = makeText("\u2191  " + activity.getString(R.string.recents_swipe_close), 11.5f, Color.WHITE);
            closeBadge.setPadding(dp(11), dp(5), dp(11), dp(5));
            GradientDrawable closeBg = new GradientDrawable();
            closeBg.setColor(0xF0D9382E);
            closeBg.setCornerRadius(dp(14));
            closeBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
            closeBadge.setBackground(closeBg);
            closeOverlay.addView(closeBadge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            card.addView(closeOverlay, new FrameLayout.LayoutParams(-1, -1));

            TextView name = makeText("", 15f, ExpressiveGlass.adaptiveSecondaryTextColor());
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(cardWidth, -2);
            nameLp.topMargin = dp(10);
            root.addView(name, nameLp);

            TextView meta = makeText("", 12f, ExpressiveGlass.adaptiveHintTextColor());
            meta.setGravity(Gravity.CENTER);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(cardWidth, -2);
            metaLp.topMargin = dp(3);
            root.addView(meta, metaLp);

            return new CardHolder(root, card, sheen, banner, icon, playingBadge, closeOverlay, name, meta);
        }

        @Override
        public void onViewRecycled(@NonNull CardHolder h) {
            h.itemView.animate().cancel();
            h.card.animate().cancel();
            h.closeOverlay.animate().cancel();
            h.itemView.setTranslationY(0f);
            h.itemView.setRotationX(0f);
            h.itemView.setAlpha(1f);
            h.itemView.setScaleX(1f);
            h.itemView.setScaleY(1f);
            h.card.setScaleX(1f);
            h.card.setScaleY(1f);
            h.card.setTranslationY(0f);
            h.card.setTranslationZ(0f);
            h.card.setRotationX(0f);
            h.card.setRotationY(0f);
            h.card.setAlpha(1f);
            if (h.sheen != null) h.sheen.reset();
            super.onViewRecycled(h);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            RecentApps.Item item = items.get(position);
            boolean focusedNow = h.itemView.hasFocus();
            float s = focusedNow ? cardFocusScale() : 1f;
            h.itemView.animate().cancel();
            h.card.animate().cancel();
            h.itemView.setTranslationY(0f);
            h.itemView.setRotationX(0f);
            h.itemView.setAlpha(1f);
            h.itemView.setScaleX(1f);
            h.itemView.setScaleY(1f);
            h.card.setScaleX(s);
            h.card.setScaleY(s);
            h.card.setTranslationY(focusedNow && animations && !reducedMotion ? -4f * density : 0f);
            h.card.setRotationX(0f);
            h.card.setRotationY(0f);
            h.card.setAlpha(focusedNow || !animations ? 1f : 0.80f);
            if (h.sheen != null) h.sheen.reset();
            h.closeOverlay.setVisibility(View.GONE);
            h.closeOverlay.setAlpha(1f);

            if (item.banner != null) {
                h.card.setBackground(null);
                h.banner.setImageDrawable(item.banner);
                h.banner.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(null);
                h.icon.setVisibility(View.GONE);
            } else {
                int topCol = blendColor(item.accent, 0xFF1F2838, 0.52f);
                int botCol = blendColor(item.accent, 0xFF0E131D, 0.78f);
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topCol, botCol});
                h.card.setBackground(bg);
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
                h.icon.setImageDrawable(item.icon);
                h.icon.setVisibility(View.VISIBLE);
            }

            h.playingBadge.setVisibility(item.playing ? View.VISIBLE : View.GONE);
            h.name.setText(item.label);
            if (!TextUtils.isEmpty(item.mediaTrack)) {
                h.meta.setText(activity.getString(R.string.recents_media_track, item.mediaTrack));
            } else {
                h.meta.setText(RecentApps.formatRelativeTime(activity, item.lastUsedMs, System.currentTimeMillis()));
            }

            h.itemView.setOnClickListener(v -> {
                clearArmedClose();
                close();
                switchToAppAnimated(item.pkg);
            });
            h.itemView.setOnLongClickListener(v -> {
                int idx = h.getBindingAdapterPosition();
                if (idx != RecyclerView.NO_POSITION) showCardActions(items.get(idx), idx, h.card);
                return true;
            });
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                int idx = h.getBindingAdapterPosition();
                if (animations && !reducedMotion) {
                    h.card.animate().cancel();
                    int dir = (lastFocusedCardPos >= 0 && idx >= 0 && idx < lastFocusedCardPos) ? -1 : 1;
                    if (focused) {
                        h.card.setRotationY(-2.4f * dir);
                        h.card.setRotationX(1.0f);
                        if (h.sheen != null) h.sheen.onFocusChanged(true, true, dir);
                    } else if (h.sheen != null) {
                        h.sheen.onFocusChanged(false, true, dir);
                    }
                    float targetS = focused ? cardFocusScale() : 1f;
                    h.card.animate()
                            .scaleX(targetS).scaleY(targetS)
                            .rotationX(0f).rotationY(0f)
                            .translationY(focused ? -4f * density : 0f)
                            .alpha(focused ? 1f : 0.80f)
                            .setDuration(focused ? 235L : 165L)
                            .setInterpolator(focused ? Motion.tileFocus() : Motion.easeOutExpo())
                            .start();
                } else {
                    h.card.setAlpha(focused || !animations ? 1f : 0.85f);
                }
                if (focused) {
                    if (idx != RecyclerView.NO_POSITION) {
                        if (armedClosePos != idx) clearArmedClose();
                        selectedPosition = idx;
                        lastFocusedCardPos = idx;
                    }
                    h.name.setTextColor(ExpressiveGlass.adaptivePrimaryTextColor());
                    h.meta.setTextColor(ExpressiveGlass.adaptiveSecondaryTextColor());
                    v.post(() -> {
                        if (v.hasFocus() && list != null && armedClosePos != h.getBindingAdapterPosition()) {
                            highlightCard(h.card, item.accent, true);
                        }
                    });
                } else {
                    h.name.setTextColor(ExpressiveGlass.adaptiveSecondaryTextColor());
                    h.meta.setTextColor(ExpressiveGlass.adaptiveHintTextColor());
                }
            });
            h.itemView.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int idx = h.getBindingAdapterPosition();
                if (idx == RecyclerView.NO_POSITION) return false;
                if (key == KeyEvent.KEYCODE_MENU) {
                    showCardActions(items.get(idx), idx, h.card);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (armedClosePos == idx) {
                        closeItemAt(idx, true);
                    } else {
                        clearArmedClose();
                        armClose(idx, h);
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (armedClosePos == idx) {
                        clearArmedClose();
                        return true;
                    }
                    if (btnCloseOne != null) btnCloseOne.requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_BACK && armedClosePos == idx) {
                    clearArmedClose();
                    return true;
                }
                boolean backHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean fwdHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (backHoriz && idx == 0) return true;
                if (fwdHoriz && idx == items.size() - 1) return true;
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        @Nullable
        final TileSheenDrawable sheen;
        final ImageView banner;
        final ImageView icon;
        final TextView playingBadge;
        final View closeOverlay;
        final TextView name;
        final TextView meta;

        CardHolder(View itemView, FrameLayout card, @Nullable TileSheenDrawable sheen, ImageView banner, ImageView icon,
                   TextView playingBadge, View closeOverlay, TextView name, TextView meta) {
            super(itemView);
            this.card = card;
            this.sheen = sheen;
            this.banner = banner;
            this.icon = icon;
            this.playingBadge = playingBadge;
            this.closeOverlay = closeOverlay;
            this.name = name;
            this.meta = meta;
        }
    }
}
