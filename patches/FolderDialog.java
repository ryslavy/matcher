package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Apple TV style frosted-glass folder overlay displaying member apps in a 4-column 16:9 grid. */
final class FolderDialog {

    interface Callbacks {
        void onLaunch(AppAdapter.Entry app);
        void onRemoveApp(String folderId, String pkg);
        void onMoveAppToFolder(String fromFolderId, String toFolderId, String pkg);
        void onReorderApps(String folderId, List<String> orderedPackages);
        void onAddApps(String folderId, String folderName);
        void onRename(String folderId, String currentName);
        void onDismissed();
    }

    private static final int COLUMNS = 4;
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    private final Activity activity;
    private final boolean animations;
    private final boolean reducedMotion;
    private final boolean rtl;
    private final float density;
    private final Callbacks callbacks;
    private final FrameLayout backdrop;
    private final FrameLayout panelStage;
    private final LinearLayout panel;
    final PopupWindow popup;
    @Nullable
    private FrameLayout embeddedHost;

    private final int stagePad;
    private final int dialogWidth;
    private final int maxDialogHeight;
    private final int cardWidth;
    private final int cardHeight;

    private String folderId;
    private String folderName;
    private List<AppAdapter.Entry> members = new ArrayList<>();
    private int selectedPosition;
    private int lastFocusedCardPos = -1;
    private boolean closed;
    private boolean dismissing;
    private boolean initialFocusPending;

    private TextView btnAdd;
    private TextView btnReorder;
    private TextView btnRename;
    private TextView hintView;
    private FrameLayout shelf;
    private RecyclerView list;
    private MemberAdapter memberAdapter;
    private GlassView highlight;
    private SpringAnimation springX, springY;
    @Nullable
    private View highlightedView;
    private boolean highlightShown;
    private boolean highlightOnHeader;
    private PopupWindow actionPopup;
    private boolean reordering;
    @Nullable
    private String movingPkg;
    @Nullable
    private List<String> orderAtStart;
    private final Rect highlightWorkRect = new Rect();
    private final java.util.Map<String, Long> stablePkgIds = new java.util.HashMap<>();
    private long nextStableId = 1L;

    private final View.OnLayoutChangeListener syncHighlightOnLayout = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (highlightShown) snapHighlight();
    };

    FolderDialog(Activity activity, AppAdapter.Entry folder, boolean animations, boolean reducedMotion, Callbacks callbacks) {
        this.activity = activity;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.callbacks = callbacks;
        this.stagePad = FocusRingController.wrapInsetPx(density);

        panelStage = new FrameLayout(activity);
        panelStage.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panelStage.setClipChildren(false);
        panelStage.setClipToPadding(false);

        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(14), dp(20), 0);
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
        dialogWidth = Math.min(dp(820), Math.max(dp(320), screenW - dp(64)));
        maxDialogHeight = Math.min(dp(475), Math.max(dp(260), screenH - dp(56)));
        cardWidth = Math.max(dp(116), Math.min(dp(168), (dialogWidth - dp(64)) / COLUMNS - dp(16)));
        cardHeight = Math.round(cardWidth * 95f / 168f);

        backdrop = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        animateClose(false);
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        backdrop.setClipChildren(false);
        backdrop.setClipToPadding(false);
        backdrop.setOnClickListener(v -> animateClose(true));
        panel.setOnClickListener(v -> { /* consume clicks inside panel */ });

        popup = new PopupWindow(backdrop, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(null);
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            if (reordering) endReorder(true);
            closed = true;
            panelStage.animate().cancel();
            panel.animate().cancel();
            dismissActions();
            stopHighlight();
            callbacks.onDismissed();
        });

        bindFolder(folder);
    }

    boolean isShowing() {
        return embeddedHost != null ? (!closed && panelStage.getParent() != null) : popup.isShowing();
    }

    boolean isDismissing() {
        return dismissing;
    }

    /** Embeds the folder directly inside OverlayActivity — exact same window & motion curve as App Library / Recents. */
    void showInOverlay(FrameLayout host) {
        if (closed) return;
        this.embeddedHost = host;
        boolean reduce = reducedMotion || !animations;
        panelStage.setAlpha(0f);
        if (!reduce) {
            panelStage.setScaleX(0.94f);
            panelStage.setScaleY(0.94f);
            panelStage.setTranslationY(84f * density);
        }
        if (panelStage.getParent() instanceof ViewGroup) {
            ((ViewGroup) panelStage.getParent()).removeView(panelStage);
        }
        host.addView(panelStage, new FrameLayout.LayoutParams(
                dialogWidth + 2 * stagePad, heightFor(members.size()) + 2 * stagePad, Gravity.CENTER));
        initialFocusPending = true;
        focusInitialCard();
        panelStage.post(() -> panelStage.postOnAnimation(() -> {
            if (closed || dismissing || !isShowing()) return;
            panelStage.setPivotX(panelStage.getWidth() / 2f);
            panelStage.setPivotY(panelStage.getHeight() / 2f);
            if (activity instanceof OverlayActivity) {
                ((OverlayActivity) activity).notifyContentRevealStarted();
            }
            Motion.revealModal(panelStage, density, reduce);
        }));
    }

    void show() {
        if (closed) return;
        this.embeddedHost = null;
        if (activity instanceof MainActivity) ((MainActivity) activity).prepareBackdropBlurForPopup();
        boolean reduce = reducedMotion || !animations;
        panelStage.setAlpha(0f);
        if (!reduce) {
            panelStage.setScaleX(0.94f);
            panelStage.setScaleY(0.94f);
            panelStage.setTranslationY(84f * density);
        }
        if (panelStage.getParent() != backdrop) {
            if (panelStage.getParent() instanceof ViewGroup) ((ViewGroup) panelStage.getParent()).removeView(panelStage);
            backdrop.addView(panelStage, new FrameLayout.LayoutParams(
                    dialogWidth + 2 * stagePad, heightFor(members.size()) + 2 * stagePad, Gravity.CENTER));
        }
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        initialFocusPending = true;
        focusInitialCard();
        panelStage.post(() -> panelStage.postOnAnimation(() -> {
            if (closed || dismissing || !isShowing()) return;
            panelStage.setPivotX(panelStage.getWidth() / 2f);
            panelStage.setPivotY(panelStage.getHeight() / 2f);
            if (activity instanceof MainActivity) ((MainActivity) activity).startBackdropBlurFadeIn();
            Motion.revealModal(panelStage, density, reduce);
        }));
    }

    void update(AppAdapter.Entry folder) {
        if (closed) return;
        dismissActions();
        if (folder == null || folder.folderMembers.isEmpty()) {
            close();
            return;
        }
        bindFolder(folder);
        ViewGroup.LayoutParams lp = panelStage.getLayoutParams();
        int h = heightFor(members.size()) + 2 * stagePad;
        if (lp != null && lp.height != h) {
            lp.height = h;
            panelStage.setLayoutParams(lp);
        }
        focusInitialCard();
    }

    /** Plays the exact same smooth modal dismiss animation as App Library & Recents on Back / outside tap. */
    void animateClose() {
        animateClose(false);
    }

    void animateClose(boolean forceModal) {
        if (closed || dismissing) return;
        if (!forceModal && actionPopup != null && actionPopup.isShowing()) {
            dismissActions();
            return;
        }
        if (!forceModal && reordering) {
            if (movingPkg != null) dropCard();
            else endReorder(true);
            return;
        }
        if (reordering) endReorder(true);
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
                if (panelStage.getParent() == embeddedHost) embeddedHost.removeView(panelStage);
                callbacks.onDismissed();
            }
            return;
        }
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;
            callbacks.onDismissed();
        }
    }

    String folderId() {
        return folderId;
    }

    private int heightFor(int count) {
        int rows = Math.max(1, (count + COLUMNS - 1) / COLUMNS);
        int rowHeight = cardHeight + dp(44);
        int want = dp(126) + rows * rowHeight;
        return Math.min(maxDialogHeight, Math.max(dp(250), want));
    }

    private void stopHighlight() {
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        if (highlight != null) highlight.resetMotion();
        if (list != null) list.stopScroll();
        highlightedView = null;
        highlightShown = false;
    }

    private float cardFocusScale() {
        return animations && !reducedMotion ? 1.06f : 1f;
    }

    private float cardScaleFor(boolean focused, boolean carried) {
        if (!animations || reducedMotion) return 1f;
        if (carried) return 1.09f;
        return focused ? 1.06f : 1f;
    }

    private float cardLiftDpFor(boolean focused, boolean carried) {
        if (!animations || reducedMotion) return 0f;
        if (carried) return 5f;
        return focused ? 3f : 0f;
    }

    private void highlightCard(View cardFrame, int accent, boolean animate) {
        if (highlight == null || shelf == null || cardFrame.getParent() == null) return;
        if (!FocusRingController.locateInHost(panelStage, cardFrame, highlightWorkRect)) return;
        highlightedView = cardFrame;
        highlightOnHeader = false;
        boolean carried = reordering && movingPkg != null
                && selectedPosition >= 0 && selectedPosition < members.size()
                && movingPkg.equals(members.get(selectedPosition).pkg);
        int pad = Math.round(GlassView.insetPx(density));
        float scale = cardScaleFor(true, carried);
        float liftPx = cardLiftDpFor(true, carried) * density;
        int w = Math.round(highlightWorkRect.width() * scale) + 2 * pad;
        int h = Math.round(highlightWorkRect.height() * scale) + 2 * pad;
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(dp(14) * scale);
        highlight.setAccent(reordering ? REORDER_ACCENT : accent);
        float x = highlightWorkRect.exactCenterX() - w / 2f;
        float y = highlightWorkRect.exactCenterY() - h / 2f - liftPx;
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

    private void highlightPill(View pill) {
        if (highlight == null || pill.getParent() == null) return;
        if (pill.getWidth() <= 0 || pill.getHeight() <= 0) {
            pill.post(() -> {
                if (pill.hasFocus()) highlightPill(pill);
            });
            return;
        }
        if (!FocusRingController.locateInHost(panelStage, pill, highlightWorkRect)) return;
        highlightedView = pill;
        highlightOnHeader = true;
        int pad = Math.round(GlassView.insetPx(density));
        float scale = animations && !reducedMotion ? 1.07f : 1f;
        float liftPx = animations && !reducedMotion ? 1.5f * density : 0f;
        float targetW = highlightWorkRect.width() * scale;
        float targetH = highlightWorkRect.height() * scale;
        int w = Math.round(targetW) + 2 * pad;
        int h = Math.round(targetH) + 2 * pad;
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(Math.min(targetW, targetH) * 0.5f);
        highlight.setAccent((pill == btnReorder && reordering) ? REORDER_ACCENT : 0);
        float x = highlightWorkRect.exactCenterX() - w / 2f;
        float y = highlightWorkRect.exactCenterY() - h / 2f - liftPx;
        if (!highlightShown || !animations) {
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
        int pad = Math.round(GlassView.insetPx(density));
        float scale, liftPx, radiusPx;
        if (highlightOnHeader) {
            scale = animations && !reducedMotion ? 1.07f : 1f;
            liftPx = animations && !reducedMotion ? 1.5f * density : 0f;
            radiusPx = Math.min(highlightWorkRect.width() * scale, highlightWorkRect.height() * scale) * 0.5f;
        } else {
            boolean carried = reordering && movingPkg != null
                    && selectedPosition >= 0 && selectedPosition < members.size()
                    && movingPkg.equals(members.get(selectedPosition).pkg);
            scale = cardScaleFor(true, carried);
            liftPx = cardLiftDpFor(true, carried) * density;
            radiusPx = dp(14) * scale;
        }
        int w = Math.round(highlightWorkRect.width() * scale) + 2 * pad;
        int h = Math.round(highlightWorkRect.height() * scale) + 2 * pad;
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            highlight.setLayoutParams(lp);
        }
        highlight.setRadius(radiusPx);
        float x = highlightWorkRect.exactCenterX() - w / 2f;
        float y = highlightWorkRect.exactCenterY() - h / 2f - liftPx;
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

    // ---- folder internal reorder mode ------------------------------------------------------------

    private List<String> currentPackageOrder() {
        List<String> out = new ArrayList<>(members.size());
        for (AppAdapter.Entry e : members) out.add(e.pkg);
        return out;
    }

    private int indexOfPkg(@Nullable String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < members.size(); i++) {
            if (pkg.equals(members.get(i).pkg)) return i;
        }
        return -1;
    }

    private float memberAlpha(int position) {
        if (!reordering) return 1f;
        if (movingPkg != null) {
            return (position >= 0 && position < members.size() && movingPkg.equals(members.get(position).pkg))
                    ? 1f : 0.42f;
        }
        return position == selectedPosition ? 1f : 0.72f;
    }

    private void toggleReorder() {
        if (reordering) endReorder(true);
        else beginReorder(-1);
    }

    private void beginReorder(int pickUpIndex) {
        if (members.size() < 2) return;
        reordering = true;
        orderAtStart = currentPackageOrder();
        movingPkg = (pickUpIndex >= 0 && pickUpIndex < members.size()) ? members.get(pickUpIndex).pkg : null;
        if (pickUpIndex >= 0 && pickUpIndex < members.size()) {
            selectedPosition = pickUpIndex;
        }
        if (btnReorder != null) {
            btnReorder.setText(R.string.folder_btn_done);
            btnReorder.setActivated(true);
            btnReorder.setTextColor(ExpressiveGlass.adaptiveAccentTextColor(0xFF93D1FF));
        }
        int target = Math.max(0, Math.min(selectedPosition, members.size() - 1));
        boolean fromHeader = highlightOnHeader;
        focusCard(target);
        if (list != null) {
            list.post(() -> {
                if (closed || list == null) return;
                refreshReorderVisuals();
                RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(target);
                if (vh instanceof MemberHolder) {
                    highlightCard(((MemberHolder) vh).card, REORDER_ACCENT, fromHeader);
                }
            });
        }
    }

    private void pickUpCard(int pos) {
        if (!reordering || pos < 0 || pos >= members.size()) return;
        movingPkg = members.get(pos).pkg;
        selectedPosition = pos;
        refreshReorderVisuals();
        if (list != null) {
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
            if (vh instanceof MemberHolder) {
                highlightCard(((MemberHolder) vh).card, REORDER_ACCENT, false);
            }
        }
    }

    private void dropCard() {
        if (movingPkg == null) return;
        movingPkg = null;
        refreshReorderVisuals();
        if (list != null) {
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(selectedPosition);
            if (vh instanceof MemberHolder) {
                highlightCard(((MemberHolder) vh).card, REORDER_ACCENT, false);
            }
        }
    }

    private void endReorder(boolean notifyCallbacks) {
        if (!reordering) return;
        reordering = false;
        movingPkg = null;
        if (btnReorder != null) {
            btnReorder.setText(R.string.folder_btn_reorder);
            btnReorder.setActivated(false);
            btnReorder.setTextColor(ExpressiveGlass.adaptivePillTextColors());
        }
        refreshReorderVisuals();
        if (highlight != null && !highlightOnHeader && list != null
                && selectedPosition >= 0 && selectedPosition < members.size()) {
            AppAdapter.Entry cur = members.get(selectedPosition);
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(selectedPosition);
            if (vh instanceof MemberHolder) {
                highlightCard(((MemberHolder) vh).card, cur.ambient, false);
            }
        } else if (highlight != null && highlightOnHeader && btnReorder != null && btnReorder.hasFocus()) {
            highlightPill(btnReorder);
        }
        List<String> after = currentPackageOrder();
        List<String> before = orderAtStart;
        orderAtStart = null;
        if (notifyCallbacks && before != null && !after.equals(before)) {
            callbacks.onReorderApps(folderId, after);
        }
    }

    private void refreshReorderVisuals() {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            RecyclerView.ViewHolder vh = list.getChildViewHolder(child);
            if (!(vh instanceof MemberHolder)) continue;
            MemberHolder mh = (MemberHolder) vh;
            int pos = mh.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) continue;
            child.setAlpha(memberAlpha(pos));
            boolean focused = child.hasFocus();
            boolean carried = reordering && movingPkg != null
                    && pos < members.size() && movingPkg.equals(members.get(pos).pkg);
            float targetScale = cardScaleFor(focused, carried);
            float targetY = -cardLiftDpFor(focused, carried) * density;
            if (animations && !reducedMotion) {
                mh.card.animate().cancel();
                mh.card.animate().scaleX(targetScale).scaleY(targetScale).translationY(targetY)
                        .setDuration(140L).setInterpolator(Motion.easeOutExpo()).start();
            } else {
                mh.card.setScaleX(targetScale);
                mh.card.setScaleY(targetScale);
                mh.card.setTranslationY(targetY);
            }
        }
    }

    private void moveCarriedBy(int keyCode) {
        if (!reordering || movingPkg == null || list == null || memberAdapter == null) return;
        int from = indexOfPkg(movingPkg);
        if (from < 0) return;
        int n = members.size();
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
        AppAdapter.Entry moved = members.remove(from);
        members.add(to, moved);
        selectedPosition = to;
        lastFocusedCardPos = to;
        memberAdapter.notifyItemMoved(from, to);
        final int target = to;
        list.scrollToPosition(target);
        refocusCarriedAfterLayout(target, 10);
    }

    private void refocusCarriedAfterLayout(int target, int remainingFrames) {
        if (closed || list == null) return;
        list.postOnAnimation(() -> {
            if (closed || list == null) return;
            if ((list.hasPendingAdapterUpdates() || list.isLayoutRequested()) && remainingFrames > 0) {
                refocusCarriedAfterLayout(target, remainingFrames - 1);
                return;
            }
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(target);
            if (vh instanceof MemberHolder) {
                MemberHolder mh = (MemberHolder) vh;
                if (!mh.itemView.hasFocus()) mh.itemView.requestFocus();
                refreshReorderVisuals();
                highlightCard(mh.card, REORDER_ACCENT, true);
            }
        });
    }

    private void bindFolder(AppAdapter.Entry folder) {
        reordering = false;
        movingPkg = null;
        orderAtStart = null;
        stopHighlight();
        highlightOnHeader = false;
        if (highlight != null && highlight.getParent() == panelStage) {
            panelStage.removeView(highlight);
        }
        this.folderId = folder.folderId;
        this.folderName = folder.label;
        this.members = new ArrayList<>(folder.folderMembers);
        if (selectedPosition >= members.size()) selectedPosition = Math.max(0, members.size() - 1);

        panel.removeAllViews();

        // Header: Folder title + app count on the left, "+ Add", "Reorder", & "Rename" glass pills on the right.
        LinearLayout header = new LinearLayout(activity);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(5), dp(8), dp(5));
        header.setClipChildren(false);
        header.setClipToPadding(false);

        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.HORIZONTAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText(folderName, 23, Color.WHITE);
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView count = makeText(activity.getResources().getQuantityString(
                R.plurals.library_app_count, members.size(), members.size()), 13, 0xA6FFFFFF);
        count.setSingleLine(true);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        countLp.leftMargin = dp(10);
        countLp.rightMargin = dp(8);
        titleBox.addView(count, countLp);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean canReorder = members.size() >= 2;
        btnAdd = makeHeaderPill(activity.getString(R.string.folder_btn_add));
        btnAdd.setOnClickListener(v -> {
            if (reordering) endReorder(true);
            callbacks.onAddApps(folderId, folderName);
        });
        btnReorder = makeHeaderPill(activity.getString(R.string.folder_btn_reorder));
        btnReorder.setVisibility(canReorder ? View.VISIBLE : View.GONE);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnRename = makeHeaderPill(activity.getString(R.string.folder_btn_rename));
        btnRename.setOnClickListener(v -> {
            if (reordering) endReorder(true);
            callbacks.onRename(folderId, folderName);
        });

        btnAdd.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                (canReorder ? btnReorder : btnRename).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusCard(Math.min(selectedPosition, members.size() - 1));
                return true;
            }
            return false;
        });
        btnReorder.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                btnAdd.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                btnRename.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusCard(Math.min(selectedPosition, members.size() - 1));
                return true;
            }
            return false;
        });
        btnRename.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                (canReorder ? btnReorder : btnAdd).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusCard(Math.min(selectedPosition, members.size() - 1));
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-2, dp(34));
        addLp.rightMargin = dp(8);
        header.addView(btnAdd, addLp);
        if (canReorder) {
            LinearLayout.LayoutParams reorderLp = new LinearLayout.LayoutParams(-2, dp(34));
            reorderLp.rightMargin = dp(8);
            header.addView(btnReorder, reorderLp);
        }
        header.addView(btnRename, new LinearLayout.LayoutParams(-2, dp(34)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        hintView = makeText(activity.getString(R.string.folder_hint), 12, 0xA6FFFFFF);
        hintView.setSingleLine(true);
        hintView.setEllipsize(TextUtils.TruncateAt.END);
        hintView.setPadding(dp(8), 0, dp(8), dp(6));
        hintView.setVisibility(View.GONE);
        panel.addView(hintView, new LinearLayout.LayoutParams(-1, dp(24)));

        shelf = new FrameLayout(activity);
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(true);
        shelf.setClipToPadding(true);

        list = TopFade.newRecyclerView(activity, 38f * density, true);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        list.setLayoutManager(new GridLayoutManager(activity, COLUMNS, RecyclerView.VERTICAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(16), dp(16), dp(16), dp(24));
        memberAdapter = new MemberAdapter();
        memberAdapter.setHasStableIds(true);
        list.setAdapter(memberAdapter);
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));

        float focusScale = cardFocusScale();
        highlight = new GlassView(activity, null);
        highlight.setLightSurface(ExpressiveGlass.isLightGlass());
        highlight.setRadius(dp(14) * focusScale);
        highlight.setLiquidEnabled(animations && !reducedMotion);
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int pad = Math.round(GlassView.insetPx(density));
        panelStage.addView(highlight, new FrameLayout.LayoutParams(
                Math.round(cardWidth * focusScale) + 2 * pad,
                Math.round(cardHeight * focusScale) + 2 * pad));

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
                if (!highlightOnHeader) snapHighlight();
            }
        });
        panel.removeOnLayoutChangeListener(syncHighlightOnLayout);
        panel.addOnLayoutChangeListener(syncHighlightOnLayout);
        shelf.addOnLayoutChangeListener(syncHighlightOnLayout);
        list.addOnLayoutChangeListener(syncHighlightOnLayout);
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1f));
        ExpressiveGlass.applyAdaptiveContentTheme(panel);
    }

    private TextView makeHeaderPill(String label) {
        TextView btn = new TextView(activity);
        btn.setText(label);
        btn.setTextSize(13f);
        btn.setTypeface(UiFont.get(activity, R.string.font_medium));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), 0, dp(14), 0);
        btn.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 17f, 0));
        btn.setTextColor(ExpressiveGlass.adaptivePillTextColors());
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

    private void focusInitialCard() {
        if (list == null) return;
        int target = Math.max(0, Math.min(selectedPosition, members.size() - 1));
        list.post(() -> {
            if (closed || !isShowing() || list == null) return;
            focusCard(target);
        });
    }

    private void focusCard(int target) {
        if (list == null || target < 0 || target >= members.size()) return;
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        list.scrollToPosition(target);
        list.post(() -> {
            if (list == null || !isShowing()) return;
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(target);
            if (h != null) h.itemView.requestFocus();
        });
    }

    private void setPanelContentBlur(boolean blurred) {
        ExpressiveGlass.animatePanelContentBlur(panel, highlight, density, blurred, reducedMotion || !animations);
    }

    private void dismissActions() {
        dismissActions(false);
    }

    private void dismissActions(boolean keepBlur) {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
        if (!keepBlur) setPanelContentBlur(false);
    }

    private void showAppActions(AppAdapter.Entry app, View anchor) {
        dismissActions();
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
            if (!closed && isShowing()) {
                panel.post(() -> {
                    if (!closed && isShowing() && actionPopup == null) focusCard(selectedPosition);
                });
            }
        });

        addActionRow(rows, activity.getString(R.string.library_open), () -> {
            close();
            callbacks.onLaunch(app);
        });
        if (members.size() >= 2) {
            addActionRow(rows, activity.getString(R.string.tile_move), () -> {
                dismissActions();
                int idx = indexOfPkg(app.pkg);
                if (idx >= 0) beginReorder(idx);
            });
        }
        addActionRow(rows, activity.getString(R.string.folder_remove_app), () -> {
            dismissActions();
            callbacks.onRemoveApp(folderId, app.pkg);
        });

        List<Folders.Folder> others = new ArrayList<>();
        for (Folders.Folder f : Folders.load(activity)) {
            if (!folderId.equals(f.id)) others.add(f);
        }
        if (!others.isEmpty()) {
            addActionRow(rows, activity.getString(R.string.folder_move_to), () -> {
                dismissActions(true);
                showMoveFolderPicker(app, anchor, others);
            });
        }

        Intent info = SystemSettings.appInfo(activity, app.pkg);
        if (info != null) {
            addActionRow(rows, activity.getString(R.string.library_app_info), () -> {
                close();
                try {
                    activity.startActivity(info);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
                }
            });
        }

        showSubPopup(menu, menuHost, menuPanel, rows, anchor);
    }

    private void showMoveFolderPicker(AppAdapter.Entry app, View anchor, List<Folders.Folder> others) {
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
            if (!closed && isShowing()) {
                panel.post(() -> {
                    if (!closed && isShowing() && actionPopup == null) focusCard(selectedPosition);
                });
            }
        });
        for (Folders.Folder target : others) {
            addActionRow(rows, target.name, () -> {
                dismissActions();
                callbacks.onMoveAppToFolder(folderId, target.id, app.pkg);
            });
        }
        showSubPopup(menu, menuHost, menuPanel, rows, anchor);
    }

    private void showSubPopup(PopupWindow menu, FrameLayout menuHost, View menuPanel, LinearLayout rows, View anchor) {
        int menuPad = FocusRingController.wrapInsetPx(density);
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
        int x = Math.max(bounds.left + dp(12), Math.min(loc[0] + anchor.getWidth() - dp(12), bounds.right - menuWidth - dp(12)));
        int y = Math.max(bounds.top + dp(12), Math.min(loc[1] + dp(8), bounds.bottom - menuPanel.getMeasuredHeight() - dp(12)));
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

    private TextView makeText(String value, int sizeSp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private final class MemberAdapter extends RecyclerView.Adapter<MemberHolder> {
        @NonNull
        @Override
        public MemberHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setClipChildren(false);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setClickable(true);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(8), dp(6), dp(8), dp(8));
            item.setLayoutParams(params);

            FrameLayout card = new FrameLayout(activity);
            card.setId(R.id.card);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
                }
            });
            card.setClipToOutline(true);
            ExpressiveGlass.disableOutlineShadow(card);
            card.setCameraDistance(8000f * density);
            TileSheenDrawable sheen = (animations && !reducedMotion) ? new TileSheenDrawable() : null;
            if (sheen != null) card.setForeground(sheen);
            item.addView(card, new LinearLayout.LayoutParams(cardWidth, cardHeight));

            ImageView banner = new ImageView(activity);
            banner.setId(R.id.banner);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ExpressiveGlass.markPreserveImage(banner);
            card.addView(banner, new FrameLayout.LayoutParams(-1, -1));

            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ExpressiveGlass.markPreserveImage(icon);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER);
            card.addView(icon, iconLp);

            TextView name = makeText("", 13, ExpressiveGlass.adaptiveSecondaryTextColor());
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(cardWidth, dp(22));
            nameLp.topMargin = dp(6);
            item.addView(name, nameLp);

            return new MemberHolder(item, card, sheen, banner, icon, name);
        }

        @Override
        public void onViewRecycled(@NonNull MemberHolder h) {
            h.card.animate().cancel();
            h.card.setScaleX(1f);
            h.card.setScaleY(1f);
            h.card.setTranslationY(0f);
            h.card.setTranslationZ(0f);
            h.card.setRotationX(0f);
            h.card.setRotationY(0f);
            if (h.sheen != null) h.sheen.reset();
            super.onViewRecycled(h);
        }

        @Override
        public long getItemId(int position) {
            String pkg = members.get(position).pkg;
            Long id = stablePkgIds.get(pkg);
            if (id == null) {
                id = nextStableId++;
                stablePkgIds.put(pkg, id);
            }
            return id;
        }

        @Override
        public void onBindViewHolder(@NonNull MemberHolder h, int position) {
            AppAdapter.Entry app = members.get(position);
            boolean focusedNow = h.itemView.hasFocus();
            boolean carriedNow = reordering && movingPkg != null && movingPkg.equals(app.pkg);
            float s = cardScaleFor(focusedNow, carriedNow);
            h.itemView.setAlpha(memberAlpha(position));
            h.card.animate().cancel();
            h.card.setScaleX(s);
            h.card.setScaleY(s);
            h.card.setTranslationY(-cardLiftDpFor(focusedNow, carriedNow) * density);
            h.card.setRotationX(0f);
            h.card.setRotationY(0f);
            if (h.sheen != null) h.sheen.reset();
            h.name.setText(app.name);
            if (app.banner != null) {
                h.card.setBackground(null);
                h.banner.setImageDrawable(app.banner);
                h.banner.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(null);
                h.icon.setVisibility(View.GONE);
            } else {
                h.card.setBackground(h.fallbackBg);
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
                h.icon.setImageDrawable(app.icon);
                h.icon.setVisibility(View.VISIBLE);
            }
            h.itemView.setOnClickListener(v -> {
                if (reordering) {
                    int pos = h.getBindingAdapterPosition();
                    if (movingPkg != null) dropCard();
                    else if (pos != RecyclerView.NO_POSITION) pickUpCard(pos);
                    return;
                }
                close();
                callbacks.onLaunch(app);
            });
            h.itemView.setOnLongClickListener(v -> {
                if (reordering) return true;
                showAppActions(app, h.card);
                return true;
            });
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                int idx = h.getBindingAdapterPosition();
                boolean carried = reordering && movingPkg != null && movingPkg.equals(app.pkg);
                boolean snapInitial = focused && initialFocusPending;
                if (snapInitial) initialFocusPending = false;
                if (animations && !reducedMotion) {
                    h.card.animate().cancel();
                    float targetS = cardScaleFor(focused, carried);
                    float targetLiftY = -cardLiftDpFor(focused, carried) * density;
                    if (snapInitial) {
                        // Snap initial card cleanly before panel's hardware-layered 255 ms reveal so
                        // no concurrent 3D tilt or sheen invalidates the cached GPU texture mid-fade.
                        h.card.setScaleX(targetS);
                        h.card.setScaleY(targetS);
                        h.card.setRotationX(0f);
                        h.card.setRotationY(0f);
                        h.card.setTranslationY(targetLiftY);
                        if (h.sheen != null) h.sheen.reset();
                    } else {
                        int dCol = (lastFocusedCardPos >= 0 && idx >= 0) ? (idx % COLUMNS) - (lastFocusedCardPos % COLUMNS) : 1;
                        int dRow = (lastFocusedCardPos >= 0 && idx >= 0) ? (idx / COLUMNS) - (lastFocusedCardPos / COLUMNS) : 0;
                        int dir = dCol < 0 ? -1 : 1;
                        if (focused) {
                            if (dRow != 0 && dCol == 0) {
                                h.card.setRotationY(0f);
                                h.card.setRotationX(dRow > 0 ? -2.4f : 2.4f);
                            } else {
                                h.card.setRotationY(-2.4f * dir);
                                h.card.setRotationX(1.0f);
                            }
                            if (h.sheen != null) h.sheen.onFocusChanged(true, true, dir);
                        } else if (h.sheen != null) {
                            h.sheen.onFocusChanged(false, true, dir);
                        }
                        h.card.animate()
                                .setStartDelay(0L)
                                .scaleX(targetS).scaleY(targetS)
                                .rotationX(0f).rotationY(0f)
                                .translationY(targetLiftY)
                                .setDuration(focused ? 230L : 165L)
                                .setInterpolator(focused ? Motion.tileFocus() : Motion.easeOutExpo())
                                .start();
                    }
                }
                if (focused) {
                    if (idx != RecyclerView.NO_POSITION) {
                        selectedPosition = idx;
                        lastFocusedCardPos = idx;
                    }
                    if (reordering && movingPkg == null) refreshReorderVisuals();
                    h.name.setTextColor(ExpressiveGlass.adaptivePrimaryTextColor());
                    if (app.ambient == 0) {
                        app.ambient = AppAdapter.vividColor(app.banner != null ? app.banner : app.icon);
                    }
                    int accent = reordering ? REORDER_ACCENT : app.ambient;
                    if (snapInitial && h.card.getWidth() > 0) {
                        highlightCard(h.card, accent, false);
                    } else {
                        v.post(() -> {
                            if (v.hasFocus() && list != null) highlightCard(h.card, accent, !snapInitial);
                        });
                    }
                } else {
                    h.name.setTextColor(ExpressiveGlass.adaptiveSecondaryTextColor());
                }
            });
            h.itemView.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = h.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return false;
                if (reordering && movingPkg != null) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                            || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        moveCarriedBy(key);
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_MENU) return true;
                    return false;
                }
                if (key == KeyEvent.KEYCODE_MENU) {
                    if (!reordering) showAppActions(app, h.card);
                    return true;
                }
                boolean backHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean fwdHoriz = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (backHoriz && index % COLUMNS == 0) return true;
                if (fwdHoriz && (index % COLUMNS == COLUMNS - 1 || index == members.size() - 1)) return true;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (index < COLUMNS) {
                        if (reordering && btnReorder != null) btnReorder.requestFocus();
                        else if (btnAdd != null) btnAdd.requestFocus();
                        return true;
                    }
                    focusCard(index - COLUMNS);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int lastRowStart = ((members.size() - 1) / COLUMNS) * COLUMNS;
                    if (index < lastRowStart) focusCard(Math.min(index + COLUMNS, members.size() - 1));
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return members.size();
        }
    }

    private static final class MemberHolder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        @Nullable
        final TileSheenDrawable sheen;
        final GradientDrawable fallbackBg;
        final ImageView banner;
        final ImageView icon;
        final TextView name;

        MemberHolder(View itemView, FrameLayout card, @Nullable TileSheenDrawable sheen,
                     ImageView banner, ImageView icon, TextView name) {
            super(itemView);
            this.card = card;
            this.sheen = sheen;
            this.fallbackBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF232B3A, 0xFF141923});
            this.banner = banner;
            this.icon = icon;
            this.name = name;
        }
    }
}
