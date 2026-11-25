package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Shared Apple tvOS-inspired motion language and reduced-motion rules.
 * Every interpolator is lazily instantiated once (zero per-event allocations, JVM unit-test safe).
 */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    /** Lazy holder prevents Android framework Interpolator construction during pure JVM unit tests. */
    private static final class Curves {
        static final Interpolator EASE_OUT_EXPO = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
        static final Interpolator SPRING_POP = new PathInterpolator(0.20f, 1.12f, 0.34f, 1f);
        static final Interpolator EASE_IN_EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);
        static final Interpolator TACTILE_FOCUS = new OvershootInterpolator(1.05f);
        // Subtle 1% elastic settle that stays tightly aligned inside GlassView's rounded selection frame.
        static final Interpolator TILE_FOCUS = new OvershootInterpolator(0.35f);
        static final Interpolator TILE_FOCUS_PLAIN = new OvershootInterpolator(0.85f);
        static final Interpolator SMOOTH_DECEL = new DecelerateInterpolator(1.6f);
    }

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }

    static Interpolator easeOutExpo() { return Curves.EASE_OUT_EXPO; }
    static Interpolator springPop() { return Curves.SPRING_POP; }
    static Interpolator easeInExit() { return Curves.EASE_IN_EXIT; }
    static Interpolator tactileFocus() { return Curves.TACTILE_FOCUS; }
    static Interpolator tileFocus() { return Curves.TILE_FOCUS; }
    static Interpolator tileFocusPlain() { return Curves.TILE_FOCUS_PLAIN; }
    static Interpolator smoothDecel() { return Curves.SMOOTH_DECEL; }

    private static final WeakHashMap<View, ValueAnimator> PANEL_ANIMS = new WeakHashMap<>();

    static void cancelPanelAnim(View panel) {
        if (panel == null) return;
        panel.animate().cancel();
        ValueAnimator prev = PANEL_ANIMS.remove(panel);
        if (prev != null) prev.cancel();
    }

    /** Returns the active display refresh rate in Hz (e.g. 50, 60, 90, 120, 144) with zero hardcoded caps. */
    static float displayRefreshRate(View view) {
        if (view != null) {
            Display d = view.getDisplay();
            if (d != null) {
                float hz = d.getRefreshRate();
                if (hz >= 20f && hz <= 480f) return hz;
            }
        }
        return 60f;
    }

    private static void invalidateCached(List<View> targets) {
        for (int i = 0, n = targets.size(); i < n; i++) {
            View v = targets.get(i);
            if (v.getBackground() != null) v.getBackground().invalidateSelf();
            v.invalidate();
        }
    }

    /**
     * Explicit vsync-locked glass panel glide animator (runs natively at any active display refresh rate):
     * caches the sliding/scaling panel in a hardware GPU texture for the duration of the glide so every
     * enter and exit frame executes as a single ~1.5ms hardware quad blit (zero per-frame saveLayerAlpha,
     * zero 30-child redraws, and zero AGSL re-execution competing with Home's backdrop blur fade). On enter,
     * {@code PanelDrawable} samples resting screen coordinates on Frame 0 so releasing the hardware layer
     * at animation end is pixel-identical with zero visual pop.
     */
    static void glidePanel(View panel,
                           float fromTx, float toTx,
                           float fromTy, float toTy,
                           float fromScaleX, float toScaleX,
                           float fromScaleY, float toScaleY,
                           boolean entering,
                           long durationMs,
                           Interpolator motionCurve,
                           Runnable onEnd) {
        glidePanel(panel, fromTx, toTx, fromTy, toTy, fromScaleX, toScaleX, fromScaleY, toScaleY,
                entering, durationMs, motionCurve, onEnd, true);
    }

    static void glidePanel(View panel,
                           float fromTx, float toTx,
                           float fromTy, float toTy,
                           float fromScaleX, float toScaleX,
                           float fromScaleY, float toScaleY,
                           boolean entering,
                           long durationMs,
                           Interpolator motionCurve,
                           Runnable onEnd,
                           boolean allowHwLayer) {
        cancelPanelAnim(panel);
        final List<View> liquidTargets = new ArrayList<>(2);
        ExpressiveGlass.collectLiquidPanels(panel, liquidTargets);
        final int prevLayer = panel.getLayerType();
        final boolean useHwLayer = allowHwLayer && panel.getWidth() <= 4096 && panel.getHeight() <= 4096;
        if (useHwLayer) {
            if (entering && !liquidTargets.isEmpty()) {
                ExpressiveGlass.setLiquidRestingCoords(liquidTargets, true);
            }
            if (prevLayer != View.LAYER_TYPE_HARDWARE) {
                panel.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }
        panel.setTranslationX(fromTx);
        panel.setTranslationY(fromTy);
        panel.setScaleX(fromScaleX);
        panel.setScaleY(fromScaleY);
        panel.setAlpha(entering ? 0f : 1f);
        if (!useHwLayer) {
            invalidateCached(liquidTargets);
        }

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durationMs);
        anim.setInterpolator(null); // linear raw progress; motion & alpha use separate curves
        anim.addUpdateListener(a -> {
            float raw = (float) a.getAnimatedValue();
            float k = motionCurve.getInterpolation(raw);
            panel.setTranslationX(fromTx + (toTx - fromTx) * k);
            panel.setTranslationY(fromTy + (toTy - fromTy) * k);
            panel.setScaleX(fromScaleX + (toScaleX - fromScaleX) * k);
            panel.setScaleY(fromScaleY + (toScaleY - fromScaleY) * k);
            if (entering) {
                // Ramp glass opacity smoothly in lockstep with Home's backdrop blur veil so translucent panels
                // never hit full brightness over a still-bright unblurred background and darken afterward.
                float ramp = useHwLayer ? 0.42f : 0.50f;
                float alphaK = Math.min(1f, raw / ramp);
                panel.setAlpha(alphaK * (2f - alphaK));
            } else {
                // Keep glass solidly visible across the first ~35% of exit glide, then fade cleanly at the end.
                float fadeT = Math.max(0f, (raw - 0.35f) / 0.65f);
                panel.setAlpha(1f - fadeT * fadeT);
            }
            if (!useHwLayer) {
                invalidateCached(liquidTargets);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;
            private boolean restored;

            private void restoreLayer() {
                if (restored) return;
                restored = true;
                if (useHwLayer) {
                    if (entering && !liquidTargets.isEmpty()) {
                        ExpressiveGlass.setLiquidRestingCoords(liquidTargets, false);
                    }
                    if (panel.getLayerType() != prevLayer) {
                        panel.setLayerType(prevLayer, null);
                    }
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
                restoreLayer();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (PANEL_ANIMS.get(panel) == anim) PANEL_ANIMS.remove(panel);
                restoreLayer();
                if (!canceled) {
                    panel.setTranslationX(toTx);
                    panel.setTranslationY(toTy);
                    panel.setScaleX(toScaleX);
                    panel.setScaleY(toScaleY);
                    panel.setAlpha(entering ? 1f : 0f);
                    if (!useHwLayer) invalidateCached(liquidTargets);
                    if (onEnd != null) onEnd.run();
                }
            }
        });
        PANEL_ANIMS.put(panel, anim);
        anim.start();
    }

    /** Center frosted modal emergence: graceful upward glass glide with live 60 fps refraction. */
    static void revealModal(View panel, float density, boolean reducedMotion) {
        cancelPanelAnim(panel);
        if (reducedMotion) {
            panel.setAlpha(0f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);
            panel.animate().setStartDelay(0L).alpha(1f).setDuration(REDUCED_MS).setInterpolator(smoothDecel()).start();
            return;
        }
        glidePanel(panel,
                0f, 0f,
                84f * density, 0f,
                0.94f, 1f,
                0.94f, 1f,
                true, 330L, easeOutExpo(), null);
    }

    /** Smooth modal exit glide with live 60 fps refraction before window removal. */
    static void dismissModal(View panel, float density, boolean reducedMotion, Runnable onEnd) {
        cancelPanelAnim(panel);
        if (reducedMotion) {
            panel.animate().setStartDelay(0L).alpha(0f).setDuration(REDUCED_MS)
                    .setInterpolator(smoothDecel())
                    .withEndAction(onEnd).start();
            return;
        }
        glidePanel(panel,
                panel.getTranslationX(), 0f,
                panel.getTranslationY(), 72f * density,
                panel.getScaleX(), 0.94f,
                panel.getScaleY(), 0.94f,
                false, 220L, easeInExit(), onEnd);
    }

    /** Anchored frosted dropdown bloom (from top or bottom edge) with live refraction glide. */
    static void revealDropdown(View panel, float density, boolean fromBottom, float pivotY, boolean reducedMotion) {
        cancelPanelAnim(panel);
        if (reducedMotion) {
            panel.setAlpha(0f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);
            panel.animate().setStartDelay(0L).alpha(1f).setDuration(REDUCED_MS).setInterpolator(smoothDecel()).start();
            return;
        }
        panel.setPivotY(pivotY);
        glidePanel(panel,
                0f, 0f,
                (fromBottom ? 44f : -44f) * density, 0f,
                0.94f, 1f,
                0.90f, 1f,
                true, 260L, easeOutExpo(), null);
    }

    /** Smooth frosted dropdown exit collapsing back toward its anchor with live 60 fps edge refraction. */
    static void dismissDropdown(View panel, float density, boolean fromBottom, float pivotY,
                                boolean reducedMotion, Runnable onEnd) {
        cancelPanelAnim(panel);
        if (reducedMotion) {
            panel.animate().setStartDelay(0L).alpha(0f).setDuration(REDUCED_MS)
                    .setInterpolator(smoothDecel())
                    .withEndAction(onEnd).start();
            return;
        }
        panel.setPivotY(pivotY);
        glidePanel(panel,
                panel.getTranslationX(), 0f,
                panel.getTranslationY(), (fromBottom ? 34f : -34f) * density,
                panel.getScaleX(), 0.94f,
                panel.getScaleY(), 0.90f,
                false, 175L, easeInExit(), onEnd);
    }

    /** Top-corner card bloom (Media Mini-Player) expanding smoothly from the top-right/left pill edge. */
    static void revealTopCornerCard(View panel, float density, boolean rtl, int widthPx, boolean reducedMotion) {
        cancelPanelAnim(panel);
        if (reducedMotion) {
            panel.setAlpha(0f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);
            panel.animate().setStartDelay(0L).alpha(1f).setDuration(REDUCED_MS).setInterpolator(smoothDecel()).start();
            return;
        }
        panel.setPivotX(rtl ? widthPx * 0.15f : widthPx * 0.85f);
        panel.setPivotY(0f);
        // Direct GPU draw (no LAYER_TYPE_HARDWARE FBO swap on end) keeps the small 8-view mini-player
        // at rock-steady edge brightness with live 60fps refraction and zero end-of-glide blink.
        glidePanel(panel,
                0f, 0f,
                -44f * density, 0f,
                0.90f, 1f,
                0.90f, 1f,
                true, 260L, easeOutExpo(), null, false);
    }

    /** Smooth top-corner card exit collapsing back up toward the Vinyl pill with live 60 fps refraction. */
    static void dismissTopCornerCard(View panel, float density, boolean rtl, int widthPx,
                                     boolean reducedMotion, Runnable onEnd) {
        cancelPanelAnim(panel);
        if (reducedMotion) {
            panel.animate().setStartDelay(0L).alpha(0f).setDuration(REDUCED_MS)
                    .setInterpolator(smoothDecel())
                    .withEndAction(onEnd).start();
            return;
        }
        panel.setPivotX(rtl ? widthPx * 0.15f : widthPx * 0.85f);
        panel.setPivotY(0f);
        glidePanel(panel,
                panel.getTranslationX(), 0f,
                panel.getTranslationY(), -34f * density,
                panel.getScaleX(), 0.90f,
                panel.getScaleY(), 0.90f,
                false, 180L, easeInExit(), onEnd, false);
    }

    /** Gently cascades visible children upward into place (translation-only inside fading parents avoids nested alpha layers). */
    static void staggerChildren(ViewGroup container, float density, boolean reducedMotion, int maxCount) {
        if (reducedMotion || container == null) return;
        int visible = 0;
        for (int i = 0; i < container.getChildCount() && visible < maxCount; i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isFocused()) {
                child.setTranslationY(0f);
                visible++;
                continue;
            }
            child.animate().cancel();
            child.setTranslationY(8f * density);
            child.animate()
                    .translationY(0f)
                    .setStartDelay(16L + visible * 22L)
                    .setDuration(200L)
                    .setInterpolator(easeOutExpo())
                    .withEndAction(() -> child.animate().setStartDelay(0L))
                    .start();
            visible++;
        }
    }

    /** Slides visible children horizontally into place (Control Center rows; no nested alpha layer so rounded corners stay razor-crisp). */
    static void staggerChildrenHorizontal(ViewGroup container, float density, float fromDxDp,
                                          boolean reducedMotion, int maxCount) {
        if (reducedMotion || container == null) return;
        int visible = 0;
        for (int i = 0; i < container.getChildCount() && visible < maxCount; i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.animate().cancel();
            child.setTranslationX(fromDxDp * density);
            child.animate()
                    .translationX(0f)
                    .setStartDelay(18L + visible * 26L)
                    .setDuration(240L)
                    .setInterpolator(easeOutExpo())
                    .withEndAction(() -> child.animate().setStartDelay(0L))
                    .start();
            visible++;
        }
    }

    /** Crisp spring focus scale + subtle lift for pills, tiles, and transport buttons (no rectangular elevation shadow artifacts). */
    static void animateFocus(View v, boolean focused, float focusScale, float liftDp,
                             float density, boolean reducedMotion) {
        v.animate().cancel();
        if (focused && v.getTranslationY() > 0f) v.setTranslationY(0f);
        float s = focused ? focusScale : 1f;
        float ty = focused && !reducedMotion ? -liftDp * density : 0f;
        v.animate()
                .setStartDelay(0L)
                .scaleX(s).scaleY(s)
                .translationX(0f)
                .translationY(ty)
                .setDuration(reducedMotion ? REDUCED_MS : (focused ? 170L : 125L))
                .setInterpolator(reducedMotion ? smoothDecel() : (focused ? tactileFocus() : easeOutExpo()))
                .setUpdateListener(v.getBackground() instanceof FrostedPillDrawable ? a -> v.invalidate() : null)
                .start();
    }

    /** Tactile row focus for frosted dropdown menus and picker lists. */
    static void attachRowFocus(View row, float density, boolean reducedMotion) {
        FocusRingController.configure(row, 1.015f, 0f, 16f, 0);
        row.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            if (focused && v.getTranslationY() > 0f) v.setTranslationY(0f);
            float s = focused ? 1.015f : 1f;
            v.animate()
                    .setStartDelay(0L)
                    .scaleX(s).scaleY(s).translationX(0f).translationY(0f)
                    .setDuration(reducedMotion ? REDUCED_MS : (focused ? 150L : 115L))
                    .setInterpolator(reducedMotion ? smoothDecel() : (focused ? tactileFocus() : easeOutExpo()))
                    .start();
        });
    }

    /** Tactile pill focus for rounded glass buttons. */
    static void attachPillFocus(View pill, float focusScale, float liftDp, float density, boolean reducedMotion) {
        FocusRingController.configure(pill, focusScale, liftDp, FocusRingController.RADIUS_CAPSULE, 0);
        pill.setOnFocusChangeListener((v, focused) ->
                animateFocus(v, focused, focusScale, liftDp, density, reducedMotion));
    }

    /** Immediate tactile click-dip bounce (feels physical on toggle switches & media buttons). */
    static void pulseClick(View v, float restScale, boolean reducedMotion) {
        if (reducedMotion || v == null) return;
        v.animate().cancel();
        float dip = restScale * 0.93f;
        v.animate()
                .setStartDelay(0L)
                .scaleX(dip).scaleY(dip)
                .setDuration(60L)
                .setInterpolator(easeInExit())
                .withEndAction(() -> {
                    if (!v.isAttachedToWindow()) return;
                    v.animate()
                            .setStartDelay(0L)
                            .scaleX(restScale).scaleY(restScale)
                            .setDuration(165L)
                            .setInterpolator(tactileFocus())
                            .start();
                })
                .start();
    }
}
