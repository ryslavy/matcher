package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextClock;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * iOS 26 / visionOS Liquid Glass beveled clock typography ({@link TextClock} drop-in):
 * computes a glyph height/slope normal map once per minute on a background thread and runs
 * {@link ExpressiveGlass#LIQUID_GLASS_TEXT_AGSL} on API 33+ so the clock digits optically refract
 * the live Home wallpaper or Screensaver video/photo behind them with RGB prism dispersion and
 * directional Fresnel rim highlights. Falls back smoothly to top-lit pearl luminous text on API < 33
 * or when disabled in Settings.
 */
public final class LiquidClockView extends TextClock {

    private static volatile ExecutorService mapWorker;

    private static ExecutorService worker() {
        ExecutorService e = mapWorker;
        if (e == null) {
            synchronized (LiquidClockView.class) {
                e = mapWorker;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "liquid-clock");
                        t.setDaemon(true);
                        return t;
                    });
                    mapWorker = e;
                }
            }
        }
        return e;
    }

    private static final class GlassMaps {
        final String text;
        final float textSize;
        final Bitmap cover;
        final Bitmap slope;
        final Bitmap shadow;
        final BitmapShader coverShader;
        final BitmapShader slopeShader;
        final float drawX;
        final float drawY;
        final float inkTop;
        final float inkHeight;
        final int width;
        final int height;

        GlassMaps(String text, float textSize, Bitmap cover, Bitmap slope, Bitmap shadow,
                  float drawX, float drawY, float inkTop, float inkHeight) {
            this.text = text;
            this.textSize = textSize;
            this.cover = cover;
            this.slope = slope;
            this.shadow = shadow;
            cover.prepareToDraw();
            slope.prepareToDraw();
            shadow.prepareToDraw();
            this.drawX = drawX;
            this.drawY = drawY;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
            this.width = cover.getWidth();
            this.height = cover.getHeight();
            this.coverShader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.slopeShader = new BitmapShader(slope, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.coverShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
                this.slopeShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
            }
        }

        void recycle() {
            if (!cover.isRecycled()) cover.recycle();
            if (!slope.isRecycled()) slope.recycle();
            if (!shadow.isRecycled()) shadow.recycle();
        }
    }

    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int[] scrLoc = new int[2];
    private final Matrix rootMatrix = new Matrix();
    private final float[] rootVals = new float[9];
    private final Matrix tempMatrix = new Matrix();
    private final float[] matrixVals = new float[9];

    @Nullable
    private RuntimeShader textShader;
    private boolean shaderFailed;
    private boolean liquidEnabled = true;
    private boolean heroScale;
    @Nullable
    private volatile ExpressiveGlass.RefractionBackdrop customBackdrop;
    @Nullable
    private GlassMaps maps;
    private String requestedKey = "";
    private int generation;
    private int boundBackdropVersion = -1;
    private int boundMapsHash = -1;
    private int lastSeed = -1;
    private float rimR = 1f, rimG = 1f, rimB = 1f;

    // Fallback luminous separate shadow parameters (for API < 33 or when liquid clock is toggled off).
    private float fallbackShadowRadiusPx;
    private float fallbackShadowDyPx;
    private int fallbackShadowColor;

    public LiquidClockView(Context context) {
        super(context);
        init();
    }

    public LiquidClockView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LiquidClockView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        shadowPaint.setAlpha(0x5A);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                textShader = new RuntimeShader(ExpressiveGlass.LIQUID_GLASS_TEXT_AGSL);
            } catch (RuntimeException ignored) {
                shaderFailed = true;
            }
        }
    }

    void setLiquidEnabled(boolean enabled, boolean heroScale) {
        boolean changed = this.liquidEnabled != enabled || this.heroScale != heroScale;
        this.liquidEnabled = enabled;
        this.heroScale = heroScale;
        if (changed) {
            requestedKey = "";
            invalidate();
        }
    }

    void setCustomBackdrop(@Nullable ExpressiveGlass.RefractionBackdrop backdrop) {
        this.customBackdrop = backdrop;
        if (liquidEnabled) invalidate();
    }

    void setFallbackShadow(float radiusPx, float dyPx, int color) {
        this.fallbackShadowRadiusPx = radiusPx;
        this.fallbackShadowDyPx = dyPx;
        this.fallbackShadowColor = color;
        invalidate();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        requestedKey = "";
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            requestedKey = "";
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        generation++;
        GlassMaps old = maps;
        maps = null;
        boundMapsHash = -1;
        boundBackdropVersion = -1;
        glassPaint.setShader(null);
        if (old != null) old.recycle();
    }

    @Nullable
    private ExpressiveGlass.RefractionBackdrop resolveBackdrop() {
        ExpressiveGlass.RefractionBackdrop bg = customBackdrop;
        if (bg != null && !bg.sharp.isRecycled()) return bg;
        ExpressiveGlass.RefractionBackdrop wp = ExpressiveGlass.homeWallpaperRefraction();
        if (wp != null && !wp.sharp.isRecycled()) return wp;
        return ExpressiveGlass.homeLiveRefraction();
    }

    private void ensureMapsAsync(String text) {
        if (text.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
        float textSize = getTextSize();
        int baseline = getBaseline();
        int padStart = getPaddingLeft();
        String key = text + "|" + Math.round(textSize * 10f) + "|" + baseline + "|" + padStart + "|" + heroScale;
        if (key.equals(requestedKey)) return;
        requestedKey = key;

        final Paint measurePaint = new Paint(getPaint());
        measurePaint.setShader(null);
        measurePaint.clearShadowLayer();
        measurePaint.setColor(0xFFFFFFFF);
        final float density = getResources().getDisplayMetrics().density;
        final int gen = ++generation;
        final float baseX = padStart;
        final float baseY = baseline > 0 ? baseline : (getPaddingTop() + textSize);
        final boolean large = heroScale;

        worker().execute(() -> {
            GlassMaps built = buildGlassMaps(text, measurePaint, textSize, baseX, baseY, density, large);
            if (built == null) return;
            post(() -> {
                if (gen != generation || !isAttachedToWindow()) {
                    built.recycle();
                    return;
                }
                GlassMaps prev = maps;
                maps = built;
                boundMapsHash = -1;
                if (prev != null) postDelayed(prev::recycle, 120L);
                invalidate();
            });
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        String text = getText() != null ? getText().toString() : "";
        ExpressiveGlass.RefractionBackdrop bg = (liquidEnabled && !shaderFailed
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && textShader != null)
                ? resolveBackdrop() : null;
        if (bg != null && !text.isEmpty()) {
            ensureMapsAsync(text);
            GlassMaps g = maps;
            if (g != null && text.equals(g.text) && !g.cover.isRecycled() && !g.slope.isRecycled()) {
                drawLiquidDigits(canvas, g, bg);
                return;
            }
        }
        drawFallbackLuminous(canvas);
    }

    private void drawLiquidDigits(Canvas canvas, GlassMaps g, ExpressiveGlass.RefractionBackdrop bg) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || textShader == null) {
            drawFallbackLuminous(canvas);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        if (!g.shadow.isRecycled()) {
            canvas.drawBitmap(g.shadow, g.drawX, g.drawY + 2.2f * density, shadowPaint);
        }

        View rootView = getRootView();
        rootView.getLocationOnScreen(scrLoc);
        rootMatrix.reset();
        rootView.transformMatrixToGlobal(rootMatrix);
        rootMatrix.getValues(rootVals);
        tempMatrix.reset();
        transformMatrixToGlobal(tempMatrix);
        tempMatrix.getValues(matrixVals);
        float scaleX = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_X]));
        float scaleY = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_Y]));
        float originX = (matrixVals[Matrix.MTRANS_X] - rootVals[Matrix.MTRANS_X]) + scrLoc[0] + g.drawX * scaleX;
        float originY = (matrixVals[Matrix.MTRANS_Y] - rootVals[Matrix.MTRANS_Y]) + scrLoc[1] + g.drawY * scaleY;

        int seed = ExpressiveGlass.dynamicSeedRgb();
        if (seed != lastSeed) {
            int rimTint = ExpressiveGlass.tonalRimTint(seed);
            rimR = ((rimTint >> 16) & 0xFF) / 255f;
            rimG = ((rimTint >> 8) & 0xFF) / 255f;
            rimB = (rimTint & 0xFF) / 255f;
            lastSeed = seed;
        }

        if (boundBackdropVersion != bg.version) {
            textShader.setInputShader("uBg", bg.sharpShader);
            boundBackdropVersion = bg.version;
        }
        int mapsHash = System.identityHashCode(g);
        if (boundMapsHash != mapsHash) {
            textShader.setInputShader("uSlope", g.slopeShader);
            textShader.setInputShader("uCover", g.coverShader);
            boundMapsHash = mapsHash;
        }
        textShader.setFloatUniform("uOrigin", originX, originY);
        textShader.setFloatUniform("uScale", scaleX, scaleY);
        textShader.setFloatUniform("uInk", g.inkTop, g.inkHeight);
        textShader.setFloatUniform("uRefract", (heroScale ? 15f : 9.5f) * density);
        textShader.setFloatUniform("uSpecular", heroScale ? 0.78f : 0.72f);
        textShader.setFloatUniform("uRimTint", rimR, rimG, rimB);

        int save = canvas.save();
        canvas.translate(g.drawX, g.drawY);
        glassPaint.setShader(null);
        glassPaint.setShader(textShader);
        canvas.drawRect(0f, 0f, g.width, g.height, glassPaint);
        canvas.restoreToCount(save);
    }

    private void drawFallbackLuminous(Canvas canvas) {
        TextPaint tp = getPaint();
        Shader s = tp.getShader();
        if (s != null && fallbackShadowRadiusPx > 0f) {
            tp.setShader(null);
            tp.setShadowLayer(fallbackShadowRadiusPx, 0f, fallbackShadowDyPx, fallbackShadowColor);
            super.onDraw(canvas);
            tp.clearShadowLayer();
            tp.setShader(s);
            super.onDraw(canvas);
        } else {
            super.onDraw(canvas);
        }
    }

    @Nullable
    private static GlassMaps buildGlassMaps(String text, Paint paint, float textSize,
                                            float baseX, float baseY, float density, boolean large) {
        try {
            Rect ink = new Rect();
            paint.getTextBounds(text, 0, text.length(), ink);
            if (ink.width() <= 0 || ink.height() <= 0) return null;
            int pad = Math.max(10, Math.round((large ? 22f : 14f) * density));
            int w = ink.width() + 2 * pad;
            int h = ink.height() + 2 * pad;
            if (w > 2048 || h > 1024) return null;

            Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);

            int[] px = new int[w * h];
            cover.getPixels(px, 0, w, 0, 0, w, h);
            int bevelRadius = Math.max(2, Math.round((large ? 2.6f : 1.7f) * density));
            int shadowRadius = Math.max(3, Math.round((large ? 9.0f : 5.5f) * density));
            int[] slopePx = new int[px.length];
            int[] shadowPx = new int[px.length];
            computeSlopeAndShadow(px, w, h, bevelRadius, shadowRadius, slopePx, shadowPx);

            Bitmap slope = Bitmap.createBitmap(slopePx, w, h, Bitmap.Config.ARGB_8888);
            Bitmap shadow = Bitmap.createBitmap(shadowPx, w, h, Bitmap.Config.ARGB_8888);
            float drawX = baseX + ink.left - pad;
            float drawY = baseY + ink.top - pad;
            return new GlassMaps(text, textSize, cover, slope, shadow, drawX, drawY, pad, Math.max(1, ink.height()));
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    /**
     * Pure JVM-testable bevel slope/height and drop-shadow generator:
     * R/G encode normalized surface slope (128 = flat), B encodes stroke height (0..255).
     */
    static void computeSlopeAndShadow(int[] coverArgb, int w, int h, int bevelRadius, int shadowRadius,
                                      int[] slopeOut, int[] shadowOut) {
        int n = w * h;
        int[] alpha = new int[n];
        for (int i = 0; i < n; i++) {
            alpha[i] = (coverArgb[i] >>> 24) * 16;
        }
        int[] height = blurAlpha(alpha, w, h, Math.max(1, bevelRadius), 3);
        int[] soft = blurAlpha(alpha, w, h, Math.max(1, shadowRadius), 3);

        float maxGrad = 1f;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                float gx = height[i + 1] - height[i - 1];
                float gy = height[i + w] - height[i - w];
                float mag = (float) Math.sqrt(gx * gx + gy * gy);
                if (mag > maxGrad) maxGrad = mag;
            }
        }
        float invMax = 127f / maxGrad;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x;
                float gx = (x > 0 && x < w - 1) ? (height[i + 1] - height[i - 1]) : 0f;
                float gy = (y > 0 && y < h - 1) ? (height[i + w] - height[i - w]) : 0f;
                int r = Math.max(0, Math.min(255, 128 + Math.round(gx * invMax)));
                int g = Math.max(0, Math.min(255, 128 + Math.round(gy * invMax)));
                int b = Math.max(0, Math.min(255, height[i] / 16));
                slopeOut[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadowOut[i] = Math.max(0, Math.min(255, soft[i] / 16)) << 24;
            }
        }
    }

    private static int[] blurAlpha(int[] src, int w, int h, int radius, int passes) {
        int[] a = src.clone();
        int[] b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            boxAlpha(a, b, w, h, radius, true);
            boxAlpha(b, a, w, h, radius, false);
        }
        return a;
    }

    private static void boxAlpha(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int step = horizontal ? 1 : w;
        int lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            int sum = 0;
            for (int i = 0; i <= r && i < len; i++) {
                sum += in[base + i * step];
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = (sum + (div >> 1)) / div;
                int add = i + r + 1;
                int sub = i - r;
                if (add < len) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }
}
