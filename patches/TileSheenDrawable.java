package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Apple tvOS style glass specular sheen drawn as a card foreground overlay.
 * Sweeps a diagonal light reflection along the focus travel direction across a focused tile,
 * settles into a soft top-left glass glint while focused, and dissolves smoothly on unfocus.
 * Zero allocations in {@link #draw(Canvas)}.
 */
final class TileSheenDrawable extends Drawable {

    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private LinearGradient sweepShader;
    private LinearGradient glintShader;
    private int lastW = -1, lastH = -1;

    private float sweepPos = -1f;
    private float sweepAlpha = 0f;
    private float restAlpha = 0f;
    private float tiltShiftX = 0f;
    private float tiltShiftY = 0f;
    private ValueAnimator animator;

    /** Shifts the live resting specular catchlight dynamically with the tile's 3D tilt/sway angles. */
    void setTiltGlint(float normX, float normY) {
        float cx = Math.max(-1f, Math.min(1f, normX));
        float cy = Math.max(-1f, Math.min(1f, normY));
        if (Math.abs(cx - tiltShiftX) > 0.01f || Math.abs(cy - tiltShiftY) > 0.01f) {
            tiltShiftX = cx;
            tiltShiftY = cy;
            if (restAlpha > 0.005f) invalidateSelf();
        }
    }

    void onFocusChanged(boolean focused, boolean animate, int direction) {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!focused) {
            sweepAlpha = 0f;
            if (!animate || restAlpha <= 0.01f) {
                restAlpha = 0f;
                invalidateSelf();
                return;
            }
            final float startRest = restAlpha;
            animator = ValueAnimator.ofFloat(startRest, 0f);
            animator.setDuration(140L);
            animator.setInterpolator(Motion.easeOutExpo());
            animator.addUpdateListener(va -> {
                restAlpha = (float) va.getAnimatedValue();
                invalidateSelf();
            });
            animator.start();
            return;
        }
        if (!animate) {
            sweepAlpha = 0f;
            restAlpha = 1f;
            invalidateSelf();
            return;
        }
        final float start = direction >= 0 ? -0.48f : 1.48f;
        final float end = direction >= 0 ? 1.48f : -0.48f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(440L);
        animator.setInterpolator(Motion.easeOutExpo());
        animator.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            sweepPos = start + (end - start) * t;
            // Bell curve for sheen sweep: rises rapidly, fades softly as it leaves the card edge.
            sweepAlpha = (float) Math.sin(t * Math.PI);
            restAlpha = Math.min(1f, t * 1.45f);
            invalidateSelf();
        });
        animator.start();
    }

    void reset() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        sweepAlpha = 0f;
        restAlpha = 0f;
        tiltShiftX = 0f;
        tiltShiftY = 0f;
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible && animator != null) {
            animator.cancel();
            animator = null;
            sweepAlpha = 0f;
        }
        return changed;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (sweepAlpha <= 0.005f && restAlpha <= 0.005f) return;
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            // Crisp narrow diagonal specular catchlight that glides across on focus entry without milky haze.
            float bandW = w * 0.42f;
            sweepShader = new LinearGradient(
                    -bandW / 2f, 0f, bandW / 2f, h * 0.42f,
                    new int[]{0x00FFFFFF, 0x36FFFFFF, 0x12FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.48f, 0.66f, 1f},
                    Shader.TileMode.CLAMP);
            sweepPaint.setShader(sweepShader);

            // Whisper-subtle top-left optical corner catchlight only (keeps icons and folders 100% rich & vivid).
            glintShader = new LinearGradient(
                    0f, 0f, w * 0.34f, h * 0.34f,
                    new int[]{0x0EFFFFFF, 0x03FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
            glintPaint.setShader(glintShader);
        }

        if (restAlpha > 0.005f) {
            matrix.reset();
            matrix.setTranslate(b.left + tiltShiftX * w * 0.24f, b.top + tiltShiftY * h * 0.20f);
            glintShader.setLocalMatrix(matrix);
            glintPaint.setAlpha(Math.round(restAlpha * 255f));
            canvas.drawRect(b, glintPaint);
        }
        if (sweepAlpha > 0.005f) {
            matrix.reset();
            matrix.setTranslate(b.left + sweepPos * w, b.top);
            sweepShader.setLocalMatrix(matrix);
            sweepPaint.setAlpha(Math.round(sweepAlpha * 255f));
            canvas.drawRect(b, sweepPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) { }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
