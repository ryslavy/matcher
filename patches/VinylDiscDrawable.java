package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.Choreographer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Procedural spinning vinyl-record icon for the top-right Now Playing pill.
 * Rotates smoothly while media is playing and rests with 0% CPU while paused or hidden.
 */
final class VinylDiscDrawable extends Drawable {

    private static final float DEGREES_PER_SEC = 72f;   // calm 12 RPM

    private final float density;
    private final int sizePx;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint labelRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holeRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix artMatrix = new Matrix();
    private final RectF sheenRect = new RectF();

    private Bitmap artInputRef;
    private Bitmap artSource;
    private BitmapShader artShader;
    private float cachedLabelR = -1f;
    private int accentColor = 0xFF5AC8FA;
    private float angleDeg;
    private float currentSpeedDeg;   // smooth turntable spin-up & coast-down momentum (deg/s)
    private long lastFrameNanos;
    private boolean spinning;
    private boolean lifecycleActive;
    private boolean reducedMotion;
    private boolean focused;
    private boolean framePosted;
    private float sheenRadius = -1f;

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        framePosted = false;
        if (!shouldTick()) return;
        if (lastFrameNanos != 0L) {
            float dt = Math.min(0.1f, Math.max(0f, (frameTimeNanos - lastFrameNanos) * 1e-9f));
            float target = spinning ? DEGREES_PER_SEC : 0f;
            float rate = spinning ? 6.5f : 5.2f;
            currentSpeedDeg += (target - currentSpeedDeg) * (1f - (float) Math.exp(-rate * dt));
            if (!spinning && currentSpeedDeg < 0.8f) {
                currentSpeedDeg = 0f;
            }
            angleDeg = (angleDeg + currentSpeedDeg * dt) % 360f;
        }
        lastFrameNanos = frameTimeNanos;
        invalidateSelf();
        if (shouldTick()) scheduleNextFrame();
    };

    VinylDiscDrawable(float density, boolean reducedMotion) {
        this.density = density;
        this.reducedMotion = reducedMotion;
        this.sizePx = Math.round(26f * density);

        discPaint.setColor(0xFF161922);

        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setStrokeWidth(Math.max(1f, 0.7f * density));
        groovePaint.setColor(0x2EFFFFFF);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, 0.9f * density));

        labelRingPaint.setStyle(Paint.Style.STROKE);
        labelRingPaint.setStrokeWidth(Math.max(1f, 0.75f * density));
        labelRingPaint.setColor(0x66FFFFFF);

        holePaint.setColor(0xFF0E1016);
        holeRimPaint.setStyle(Paint.Style.STROKE);
        holeRimPaint.setStrokeWidth(Math.max(1f, 0.7f * density));
        holeRimPaint.setColor(0xAAFFFFFF);
    }

    void setReducedMotion(boolean reduced) {
        if (reducedMotion == reduced) return;
        reducedMotion = reduced;
        updateTicking();
    }

    void setSpinning(boolean playing) {
        if (spinning == playing) return;
        spinning = playing;
        updateTicking();
        invalidateSelf();
    }

    void setLifecycleActive(boolean active) {
        if (lifecycleActive == active) return;
        lifecycleActive = active;
        updateTicking();
    }

    void setAccentAndArt(int accentArgb, @Nullable Bitmap art) {
        int nextAccent = accentArgb != 0 ? (0xFF000000 | (accentArgb & 0xFFFFFF)) : 0xFF5AC8FA;
        Bitmap validArt = (art != null && !art.isRecycled() && art.getWidth() > 0 && art.getHeight() > 0) ? art : null;
        if (nextAccent == accentColor && validArt == artInputRef) return;
        accentColor = nextAccent;
        if (validArt != artInputRef) {
            artInputRef = validArt;
            // Downscale full-res album art (1-4 MB) to a tiny 48x48 (~9 KB) disc label thumbnail.
            if (validArt != null && (validArt.getWidth() > 64 || validArt.getHeight() > 64)) {
                artSource = Bitmap.createScaledBitmap(validArt, 48, 48, true);
            } else {
                artSource = validArt;
            }
            artShader = artSource != null ? new BitmapShader(artSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            cachedLabelR = -1f;
        }
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            currentSpeedDeg = 0f;
            if (framePosted) {
                Choreographer.getInstance().removeFrameCallback(frameCallback);
                framePosted = false;
            }
        }
        return changed;
    }

    private boolean shouldTick() {
        return lifecycleActive && !reducedMotion && (spinning || currentSpeedDeg > 0.8f);
    }

    private void updateTicking() {
        if (!lifecycleActive || reducedMotion) {
            currentSpeedDeg = spinning ? DEGREES_PER_SEC : 0f;
        }
        if (shouldTick()) {
            lastFrameNanos = 0L;
            scheduleNextFrame();
        } else if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            framePosted = false;
        }
    }

    private void scheduleNextFrame() {
        if (framePosted || !shouldTick()) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] stateSet) {
        boolean nextFocused = false;
        for (int s : stateSet) {
            if (s == android.R.attr.state_focused) {
                nextFocused = true;
                break;
            }
        }
        if (nextFocused == focused) return false;
        focused = nextFocused;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) * 0.5f - 0.75f * density;
        if (r <= 2f) return;

        if (r != sheenRadius) {
            sheenPaint.setShader(new SweepGradient(0f, 0f,
                    new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x2CFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.12f, 0.28f, 0.50f, 0.62f, 1f}));
            sheenRadius = r;
        }

        int save = c.save();
        c.translate(cx, cy);
        c.rotate(angleDeg);

        // Dark vinyl disc body.
        c.drawCircle(0f, 0f, r, discPaint);

        // Dual specular vinyl reflection bands.
        sheenRect.set(-r, -r, r, r);
        c.drawOval(sheenRect, sheenPaint);

        // Concentric vinyl micro-grooves.
        c.drawCircle(0f, 0f, r * 0.86f, groovePaint);
        c.drawCircle(0f, 0f, r * 0.73f, groovePaint);
        c.drawCircle(0f, 0f, r * 0.60f, groovePaint);

        // Center record label (circular album art if available, otherwise vivid accent color).
        float labelR = r * 0.42f;
        if (artShader != null && artSource != null && !artSource.isRecycled()) {
            if (labelR != cachedLabelR) {
                float scale = Math.max((labelR * 2f) / artSource.getWidth(), (labelR * 2f) / artSource.getHeight());
                artMatrix.reset();
                artMatrix.setTranslate(-artSource.getWidth() * 0.5f, -artSource.getHeight() * 0.5f);
                artMatrix.postScale(scale, scale);
                artShader.setLocalMatrix(artMatrix);
                cachedLabelR = labelR;
            }
            labelPaint.setShader(artShader);
            labelPaint.setAlpha(spinning ? 255 : 210);
        } else {
            labelPaint.setShader(null);
            labelPaint.setColor(accentColor);
            labelPaint.setAlpha(spinning ? 255 : 185);
        }
        c.drawCircle(0f, 0f, labelR, labelPaint);
        c.drawCircle(0f, 0f, labelR, labelRingPaint);

        // Tiny center spindle hole.
        float holeR = Math.max(1.5f * density, r * 0.12f);
        c.drawCircle(0f, 0f, holeR, holePaint);
        c.drawCircle(0f, 0f, holeR, holeRimPaint);

        c.restoreToCount(save);

        // Outer vinyl bevel rim (subtle luminous glass sheen on frosted dark pill).
        rimPaint.setColor(focused ? 0x99FFFFFF : (spinning ? 0x85FFFFFF : 0x55FFFFFF));
        c.drawCircle(cx, cy, r, rimPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        discPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        discPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
