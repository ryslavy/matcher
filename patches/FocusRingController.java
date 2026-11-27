package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Attaches a living wobbly {@link GlassView} optical focus ring to any panel, popup, dialog,
 * Control Center, Media Mini-Player, or Settings screen so EVERY focusable control across the
 * launcher shares the exact same liquid-glass Fresnel selection language (zero solid-white blocks).
 */
final class FocusRingController {

    /** Full pill / circle radius: always {@code min(w, h) / 2}, regardless of size (e.g. 56dp Play circle). */
    static final float RADIUS_CAPSULE = -1f;
    /** Fallback radius when a view carries no explicit {@link Spec}. */
    static final float RADIUS_AUTO = -2f;

    /** Optional per-view focus geometry & accent hint stored on {@link R.id#focus_ring_spec}. */
    static final class Spec {
        final float scale;
        final float liftDp;
        /** Corner radius in dp; {@link #RADIUS_CAPSULE} resolves to full circle/capsule, {@link #RADIUS_AUTO} infers. */
        final float radiusDp;
        final int accent;

        Spec(float scale, float liftDp, float radiusDp, int accent) {
            this.scale = scale;
            this.liftDp = liftDp;
            this.radiusDp = radiusDp;
            this.accent = accent;
        }
    }

    static void configure(@Nullable View view, float scale, float liftDp, float radiusDp, int accent) {
        if (view != null) {
            view.setTag(R.id.focus_ring_spec, new Spec(scale, liftDp, radiusDp, accent));
        }
    }

    static int wrapInsetPx(float density) {
        return Math.round(GlassView.insetPx(density));
    }

    /**
     * Wraps a floating popup or dialog panel inside a transparent padded {@link FrameLayout} host so the
     * living wobbly {@link GlassView} glow can spill smoothly past the panel's edges with zero clipping.
     */
    static FrameLayout wrapPopup(View panel, float density, boolean animations, boolean reducedMotion) {
        FrameLayout host = new FrameLayout(panel.getContext());
        host.setClipChildren(false);
        host.setClipToPadding(false);
        int pad = wrapInsetPx(density);
        if (panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.setMargins(pad, pad, pad, pad);
        host.addView(panel, panelLp);
        attach(host, panel, density, animations, reducedMotion);
        return host;
    }

    /** Attaches a living wobbly focus ring across an entire Activity via its root {@code android.R.id.content}. */
    @Nullable
    static FocusRingController attachToActivity(Activity activity, boolean animations, boolean reducedMotion) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return null;
        FrameLayout host = (FrameLayout) content;
        host.setClipChildren(false);
        host.setClipToPadding(false);
        float density = activity.getResources().getDisplayMetrics().density;
        return attach(host, host, density, animations, reducedMotion);
    }

    /** Attaches a {@link GlassView} overlay onto {@code host}, tracking all focusable descendants of {@code scope}. */
    static FocusRingController attach(FrameLayout host, View scope, float density,
                                      boolean animations, boolean reducedMotion) {
        return new FocusRingController(host, scope, density, animations, reducedMotion);
    }

    private final FrameLayout host;
    private final View scope;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    private final GlassView glass;
    private final SpringAnimation springX;
    private final SpringAnimation springY;
    private final Rect workRect = new Rect();

    @Nullable
    private View currentTarget;
    private boolean shown;
    private boolean attached;
    private boolean suppressed;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = this::onGlobalFocusChanged;

    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::onScrollChanged;

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onGlobalLayout;

    /** Temporarily ignores transient focus/layout churn while detaching and re-attaching views during reorder. */
    void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
    }

    private void onGlobalFocusChanged(View oldF, View newF) {
        if (suppressed) return;
        if (newF != null && newF != glass && isDescendantOf(newF, scope)) {
            moveTo(newF, true);
        } else if (newF == null || !isDescendantOf(newF, scope)) {
            hide();
        }
    }

    void setRenderEffect(@Nullable RenderEffect effect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glass.setRenderEffect(effect);
        }
    }

    View glassView() {
        return glass;
    }

    private void onScrollChanged() {
        if (suppressed) return;
        if (shown && currentTarget != null && currentTarget.isAttachedToWindow()) {
            snapForScroll(currentTarget);
        }
    }

    private void onGlobalLayout() {
        if (suppressed) return;
        if (currentTarget != null && currentTarget.isAttachedToWindow() && currentTarget.isFocused()) {
            if (!shown) {
                moveTo(currentTarget, false);
            } else {
                snapForScroll(currentTarget);
            }
        }
    }

    /**
     * Enforces strict viewport clipping on every {@link ScrollView} and {@link HorizontalScrollView}
     * inside {@code root}. When a parent panel sets {@code clipChildren=false}, Android HWUI clears
     * {@code RenderNode.setClipToBounds} on direct children and {@code ViewGroup.dispatchDraw} skips
     * {@code clipRect} whenever padding is zero ({@code FLAG_PADDING_NOT_NULL == 0}). Combining a
     * 1px vertical padding with {@link ViewOutlineProvider#BOUNDS} + {@code clipToOutline=true}
     * guarantees both canvas-level and hardware RenderNode viewport clipping.
     */
    static void clipScrollContainers(@Nullable View v) {
        if (v == null) return;
        if (v instanceof ScrollView || v instanceof HorizontalScrollView) {
            ViewGroup sv = (ViewGroup) v;
            sv.setClipChildren(true);
            sv.setClipToPadding(true);
            if (sv.getPaddingLeft() == 0 && sv.getPaddingTop() == 0
                    && sv.getPaddingRight() == 0 && sv.getPaddingBottom() == 0) {
                sv.setPadding(0, 1, 0, 1);
            }
            if (sv.getOutlineProvider() != ViewOutlineProvider.BOUNDS || !sv.getClipToOutline()) {
                sv.setOutlineProvider(ViewOutlineProvider.BOUNDS);
                sv.setClipToOutline(true);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                clipScrollContainers(vg.getChildAt(i));
            }
        }
    }

    private FocusRingController(FrameLayout host, View scope, float density,
                                boolean animations, boolean reducedMotion) {
        this.host = host;
        this.scope = scope;
        this.density = density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;

        host.setClipChildren(false);
        host.setClipToPadding(false);
        clipScrollContainers(scope);

        glass = new GlassView(host.getContext(), null);
        glass.setLightSurface(ExpressiveGlass.isLightGlass());
        glass.setLiquidEnabled(animations && !reducedMotion);
        glass.setAlpha(0f);
        glass.setFocusable(false);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.addView(glass, new FrameLayout.LayoutParams(10, 10));

        springX = new SpringAnimation(glass, SpringAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, SpringAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion || !animations) {
                Motion.reduce(s);
            } else {
                s.setSpring(new SpringForce().setStiffness(310f).setDampingRatio(0.68f));
            }
        }
        springX.addUpdateListener((anim, value, velocity) -> glass.setVelocityX(velocity));
        springX.addEndListener((anim, canceled, value, velocity) -> glass.setVelocityX(0f));
        springY.addUpdateListener((anim, value, velocity) -> glass.setVelocityY(velocity));
        springY.addEndListener((anim, canceled, value, velocity) -> glass.setVelocityY(0f));

        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                bindObservers();
                View focused = scope.findFocus();
                if (focused != null && focused != glass) {
                    host.post(() -> {
                        View now = scope.findFocus();
                        if (now != null && now != glass && isDescendantOf(now, scope)) {
                            moveTo(now, false);
                        }
                    });
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                unbindObservers();
            }
        });
        if (host.isAttachedToWindow()) {
            bindObservers();
            View focused = scope.findFocus();
            if (focused != null && focused != glass) {
                host.post(() -> {
                    View now = scope.findFocus();
                    if (now != null && now != glass && isDescendantOf(now, scope)) {
                        moveTo(now, false);
                    }
                });
            }
        }
    }

    private void bindObservers() {
        clipScrollContainers(scope);
        if (attached) return;
        ViewTreeObserver vto = host.getViewTreeObserver();
        if (!vto.isAlive()) return;
        attached = true;
        vto.addOnGlobalFocusChangeListener(focusListener);
        vto.addOnScrollChangedListener(scrollListener);
        vto.addOnGlobalLayoutListener(layoutListener);
    }

    private void unbindObservers() {
        if (!attached) return;
        attached = false;
        springX.cancel();
        springY.cancel();
        glass.resetMotion();
        ViewTreeObserver vto = host.getViewTreeObserver();
        if (vto.isAlive()) {
            vto.removeOnGlobalFocusChangeListener(focusListener);
            vto.removeOnScrollChangedListener(scrollListener);
            vto.removeOnGlobalLayoutListener(layoutListener);
        }
    }

    void refresh() {
        glass.setLightSurface(ExpressiveGlass.isLightGlass());
        View f = scope.findFocus();
        if (f != null && f != glass && isDescendantOf(f, scope)) {
            moveTo(f, false);
        }
    }

    void refreshStyle() {
        glass.setLightSurface(ExpressiveGlass.isLightGlass());
        glass.invalidate();
    }

    void hide() {
        currentTarget = null;
        if (!shown) return;
        shown = false;
        springX.cancel();
        springY.cancel();
        glass.resetMotion();
        glass.animate().cancel();
        if (animations && !reducedMotion) {
            glass.animate().alpha(0f).setDuration(140L).start();
        } else {
            glass.setAlpha(0f);
        }
    }

    void moveTo(View target, boolean animate) {
        currentTarget = target;
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            target.post(() -> {
                if (target.isFocused() && target.getWidth() > 0 && target.isAttachedToWindow()) {
                    moveTo(target, animate);
                }
            });
            return;
        }
        if (!locateInHost(host, target, workRect)) return;

        Spec spec = readSpec(target);
        float scale = animations ? spec.scale : 1f;
        float liftPx = (animations && !reducedMotion) ? spec.liftDp * density : 0f;
        float pad = GlassView.insetPx(density);

        float targetW = workRect.width() * scale;
        float targetH = workRect.height() * scale;
        int gw = Math.round(targetW + 2f * pad);
        int gh = Math.round(targetH + 2f * pad);
        float radiusPx = resolveRadiusPx(target, spec, scale, targetW, targetH, density);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw;
            lp.height = gh;
            glass.setLayoutParams(lp);
        }
        glass.setLightSurface(ExpressiveGlass.isLightGlass());
        glass.setRadius(radiusPx);
        glass.setAccent(spec.accent);

        float x = workRect.exactCenterX() - gw / 2f;
        float y = workRect.exactCenterY() - gh / 2f - liftPx;

        if (!shown || !animate || !animations) {
            springX.cancel();
            springY.cancel();
            glass.resetMotion();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().cancel();
            if (!shown && animations && !reducedMotion) {
                glass.animate().alpha(1f).setDuration(150L).start();
            } else {
                glass.setAlpha(1f);
            }
            shown = true;
        } else {
            glass.animate().cancel();
            glass.setAlpha(1f);
            glass.onJump(x - glass.getTranslationX(), y - glass.getTranslationY());
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    private void snapForScroll(View target) {
        if (target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (!locateInHost(host, target, workRect)) return;
        Spec spec = readSpec(target);
        float scale = animations ? spec.scale : 1f;
        float liftPx = (animations && !reducedMotion) ? spec.liftDp * density : 0f;
        float pad = GlassView.insetPx(density);
        float targetW = workRect.width() * scale;
        float targetH = workRect.height() * scale;
        int gw = Math.round(targetW + 2f * pad);
        int gh = Math.round(targetH + 2f * pad);
        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw;
            lp.height = gh;
            glass.setLayoutParams(lp);
        }
        glass.setRadius(resolveRadiusPx(target, spec, scale, targetW, targetH, density));
        glass.setAccent(spec.accent);
        float x = workRect.exactCenterX() - gw / 2f;
        float y = workRect.exactCenterY() - gh / 2f - liftPx;
        if (springX.isRunning()) {
            springX.animateToFinalPosition(x);
        } else {
            glass.setTranslationX(x);
        }
        if (springY.isRunning()) {
            springY.animateToFinalPosition(y);
        } else {
            glass.setTranslationY(y);
        }
    }

    private static final Spec EDIT_TEXT_SPEC = new Spec(1.0f, 0f, 14f, 0);
    private static final Spec DEFAULT_SPEC = new Spec(1.0f, 0f, RADIUS_AUTO, 0);

    private static Spec readSpec(View v) {
        Object tag = v.getTag(R.id.focus_ring_spec);
        if (tag instanceof Spec) return (Spec) tag;
        if (v instanceof EditText) return EDIT_TEXT_SPEC;
        return DEFAULT_SPEC;
    }

    static float resolveRadiusPx(View target, Spec spec, float effectiveScale, float scaledW, float scaledH, float density) {
        float halfMin = Math.min(scaledW, scaledH) * 0.5f;
        if (spec.radiusDp >= 0f) {
            return Math.min(halfMin, spec.radiusDp * density * effectiveScale);
        }
        if (spec.radiusDp == RADIUS_CAPSULE) {
            return halfMin;
        }
        // Fallback when untagged: true circle for squares, full capsule for compact controls, 16dp for taller cards.
        if (Math.abs(scaledW - scaledH) <= 2f * density || scaledH <= 54f * density) {
            return halfMin;
        }
        return Math.min(halfMin, 16f * density);
    }

    /**
     * Locates {@code target}'s unscrolled layout rect inside {@code host} (ignoring transient
     * entry-stagger translations on ancestors as well as {@code target}'s own focus lift/scale
     * and internal TextView scrollX so spring targets always land on the exact final layout box).
     */
    static boolean locateInHost(View host, View target, Rect out) {
        int x = 0, y = 0;
        View cur = target;
        while (cur != null && cur != host) {
            x += cur.getLeft();
            y += cur.getTop();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return false;
            cur = (View) p;
            if (cur != host) {
                x -= cur.getScrollX();
                y -= cur.getScrollY();
            }
        }
        if (cur != host) return false;
        out.set(x, y, x + target.getWidth(), y + target.getHeight());
        return true;
    }

    static boolean isDescendantOf(@Nullable View view, @Nullable View ancestor) {
        if (view == null || ancestor == null) return false;
        View cur = view;
        while (cur != null) {
            if (cur == ancestor) return true;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }
}
