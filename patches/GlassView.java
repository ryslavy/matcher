package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optical Fresnel liquid-glass focus highlight with pre-baked cached Gaussian bloom:
 *  - Bakes the 2-stage BlurMaskFilter optical aura + soft lift shadow ONCE into a lightweight half-res
 *    LRU-cached Bitmap (interior cleanly punched out with PorterDuff.CLEAR), so per-frame animation draws
 *    just a single hardware-filtered drawBitmap + crisp Fresnel bezel (0 BlurMaskFilter & 0 stencil passes).
 *  - Exact cubic Bezier corners (kappa = 0.55228475) so the living liquid blob converges seamlessly to the
 *    resting rounded rectangle with zero corner jump.
 *  - Zero milky fill over focused artwork or folders (icons stay 100% crisp and vivid).
 */
public class GlassView extends View {

    /** Padding around the focused tile's bounds: generous room for 12dp Gaussian bloom and liquid wobble. */
    static final float INSET_DP = 24f;
    /** Exact cubic Bezier control-point ratio for a 90-degree circular arc: 4 * (sqrt(2) - 1) / 3. */
    private static final float KAPPA = 0.55228475f;
    /** Small LRU cache of half-res (~70 KB each) pre-blurred glow bitmaps for instant O(1) focus transitions. */
    private static final int GLOW_CACHE_MAX = 12;
    private static final LinkedHashMap<String, Bitmap> GLOW_CACHE =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > GLOW_CACHE_MAX;
                }
            };

    static void clearGlowCache() {
        GLOW_CACHE.clear();
    }

    static float insetPx(float density) {
        return INSET_DP * density;
    }

    private final Paint glowBmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint flightFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contrastRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerCaustic = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF workRect = new RectF();
    private final Path cardPath = new Path();
    private final float density;
    private float radius;

    /** Refined optical accent colors (0xRRGGBB) for top-left and bottom-right Fresnel prism reflections. */
    private int primaryRgb = 0xEAF0FA;
    private int secondaryRgb = 0xDCE6F7;
    private int rawPrimaryArgb;
    private int rawSecondaryArgb;
    private boolean accented;
    private boolean lightSurface;
    /** Base resting alpha for wide Gaussian aura and tight rim halo. */
    private int auraBaseAlpha = 0x5A;
    private int haloBaseAlpha = 0x42;
    /** glass_style rim or halo: minimal crisp rim (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;
    private boolean shadersDirty = true;

    // Cached glow bitmap bound to the current dimensions/style/colors (zero String allocs per frame).
    private Bitmap currentGlowBmp;
    private int cachedGlowW = -1, cachedGlowH = -1, cachedGlowR = -1;
    private int cachedGlowP = -1, cachedGlowS = -1, cachedGlowStyle = -1;

    // Living wobbly liquid-blob physics state (0% CPU when resting, nanosecond refresh-rate-invariant).
    private boolean liquidEnabled = true;
    private boolean animActive;
    private float rawVx, rawVy;
    private float smoothVx, smoothVy;
    private float wobX, wobY;
    private float wobVx, wobVy;
    private float wavePhase;
    private float energy;
    private long lastFrameNs;

    // Precomputed per-frame deformation constants.
    private float frameE, frameSx, frameSy, frameCx, frameCy;
    private float frameBiasX, frameBiasY;
    private float frameTopDip, frameBotDip, frameLeftDip, frameRightDip;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;

        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x30000000);
        shadowPaint.setMaskFilter(new BlurMaskFilter(8.5f * density, BlurMaskFilter.Blur.NORMAL));

        auraPaint.setStyle(Paint.Style.STROKE);
        auraPaint.setStrokeWidth(6.5f * density);
        auraPaint.setMaskFilter(new BlurMaskFilter(11.5f * density, BlurMaskFilter.Blur.NORMAL));

        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(2.6f * density);
        haloPaint.setMaskFilter(new BlurMaskFilter(3.6f * density, BlurMaskFilter.Blur.NORMAL));

        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        contrastRim.setStyle(Paint.Style.STROKE);
        contrastRim.setStrokeWidth(0.8f * density);
        contrastRim.setColor(0x2E000000);

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.1f * density);
        rim.setColor(0xF5FFFFFF);

        innerCaustic.setStyle(Paint.Style.STROKE);
        innerCaustic.setStrokeWidth(0.9f * density);
    }

    /** Turns the living liquid-blob deformation on or off (disabled in reduced_motion). */
    void setLiquidEnabled(boolean enabled) {
        liquidEnabled = enabled;
        if (!enabled) resetMotion();
    }

    /** Called by SpringAnimation X update/end listeners with instantaneous velocity in px/s. */
    void setVelocityX(float pxPerSec) {
        if (!liquidEnabled) return;
        rawVx = pxPerSec;
        if (Math.abs(pxPerSec) > 12f) wakeLiquid();
    }

    /** Called by SpringAnimation Y update/end listeners with instantaneous velocity in px/s. */
    void setVelocityY(float pxPerSec) {
        if (!liquidEnabled) return;
        rawVy = pxPerSec;
        if (Math.abs(pxPerSec) > 12f) wakeLiquid();
    }

    /** Gives an organic launch stretch and jelly impulse the moment focus starts jumping by (dxPx, dyPx). */
    void onJump(float dxPx, float dyPx) {
        if (!liquidEnabled) return;
        float norm = Math.max(1f, 200f * density);
        float jx = clamp(dxPx / norm, -1.25f, 1.25f);
        float jy = clamp(dyPx / norm, -1.25f, 1.25f);
        if (Math.abs(jx) < 0.04f && Math.abs(jy) < 0.04f) return;
        wobVx += jx * 7.5f;
        wobVy += jy * 7.5f;
        smoothVx += jx * 0.35f;
        smoothVy += jy * 0.35f;
        wakeLiquid();
    }

    /** Immediately snaps back to the crisp resting shape (used on hide or non-animated jumps). */
    void resetMotion() {
        rawVx = rawVy = 0f;
        smoothVx = smoothVy = 0f;
        wobX = wobY = 0f;
        wobVx = wobVy = 0f;
        energy = 0f;
        if (animActive) {
            animActive = false;
            invalidate();
        }
    }

    private void wakeLiquid() {
        if (!animActive) {
            animActive = true;
            lastFrameNs = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    /** Tints the optical Fresnel bezel and velvety Gaussian aura; 0 restores pure crystal glass. */
    void setAccent(int argb) {
        setDualAccent(argb, 0);
    }

    /**
     * Switches between dark-glass luminous white/pastel Fresnel rim and light-surface (Frost White)
     * deep obsidian-slate / jewel-toned Fresnel rim so selection stays razor-crisp on pearl white glass.
     */
    void setLightSurface(boolean light) {
        if (lightSurface == light) return;
        lightSurface = light;
        recomputeAccentColors(true);
    }

    /**
     * Dual-accent Fresnel prism tinting (ideal for folders with 2+ member apps, e.g. emerald + violet).
     * Both colors are softened through {@link ExpressiveGlass#refineFocusAccent(int)} so highlights read
     * as refracted optical glass rather than harsh neon paint.
     */
    void setDualAccent(int primaryArgb, int secondaryArgb) {
        rawPrimaryArgb = primaryArgb;
        rawSecondaryArgb = secondaryArgb;
        recomputeAccentColors(false);
    }

    private void recomputeAccentColors(boolean forceDirty) {
        int nextPrimary;
        int nextSecondary;
        boolean nextAccented;
        int nextAura;
        int nextHalo;
        if (lightSurface) {
            if (rawPrimaryArgb == 0) {
                nextPrimary = 0x1E293B;
                nextSecondary = 0x334155;
                nextAccented = false;
                nextAura = 0x48;
                nextHalo = 0x40;
            } else {
                nextPrimary = ExpressiveGlass.refineLightFocusAccent(rawPrimaryArgb);
                nextSecondary = rawSecondaryArgb != 0
                        ? ExpressiveGlass.refineLightFocusAccent(rawSecondaryArgb)
                        : ExpressiveGlass.mixRgb(nextPrimary, 0x0F172A, 0.22f);
                nextAccented = true;
                nextAura = 0x64;
                nextHalo = 0x50;
            }
        } else {
            if (rawPrimaryArgb == 0) {
                nextPrimary = 0xEAF0FA;
                nextSecondary = 0xDCE6F7;
                nextAccented = false;
                nextAura = 0x5A;
                nextHalo = 0x42;
            } else {
                nextPrimary = ExpressiveGlass.refineFocusAccent(rawPrimaryArgb);
                nextSecondary = rawSecondaryArgb != 0
                        ? ExpressiveGlass.refineFocusAccent(rawSecondaryArgb)
                        : ExpressiveGlass.mixRgb(nextPrimary, 0xFFFFFF, 0.20f);
                nextAccented = true;
                nextAura = 0xA2;
                nextHalo = 0x6E;
            }
        }
        if (!forceDirty && nextPrimary == primaryRgb && nextSecondary == secondaryRgb
                && nextAccented == accented && nextAura == auraBaseAlpha && nextHalo == haloBaseAlpha) {
            return;
        }
        primaryRgb = nextPrimary;
        secondaryRgb = nextSecondary;
        accented = nextAccented;
        auraBaseAlpha = nextAura;
        haloBaseAlpha = nextHalo;
        shadersDirty = true;
        cachedGlowW = -1;
        invalidate();
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        flat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        rim.setStrokeWidth((flat ? 2.0f : 2.15f) * density);
        cachedGlowW = -1;
        invalidate();
    }

    void setRadius(float px) {
        if (Math.abs(radius - px) < 0.05f) return;
        radius = px;
        cachedGlowW = -1;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        shadersDirty = true;
        cachedGlowW = -1;
    }

    private void rebuildShaders(RectF r) {
        shadersDirty = false;
        if (lightSurface) {
            contrastRim.setColor(0x52FFFFFF);
            int c0 = accented ? (0xF2000000 | primaryRgb) : 0xF00F172A;
            int c1 = 0xDC000000 | primaryRgb;
            int c2 = accented ? (0x92000000 | primaryRgb) : 0x8A1E293B;
            int c3 = 0xCE000000 | secondaryRgb;
            int c4 = 0xEC000000 | secondaryRgb;
            rim.setShader(new LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    new int[]{c0, c1, c2, c3, c4},
                    new float[]{0f, 0.24f, 0.52f, 0.78f, 1f},
                    Shader.TileMode.CLAMP));

            innerCaustic.setShader(new LinearGradient(
                    r.left, r.top, r.left, r.top + Math.max(1f, r.height() * 0.25f),
                    0x92FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));

            LinearGradient glowGrad = new LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    new int[]{0xFF000000 | primaryRgb, 0xFF000000 | secondaryRgb},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            auraPaint.setShader(glowGrad);
            haloPaint.setShader(glowGrad);

            flightFill.setShader(new LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    new int[]{0x180F172A, 0x060F172A, 0x12000000 | secondaryRgb},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            return;
        }
        contrastRim.setColor(0x2E000000);
        // Directional Fresnel crystal rim: sparkling top-left specular catchlight, airy translucent glass sides,
        // and luminous prism bounce reflection on the bottom-right corner.
        int c0 = 0xFCFFFFFF;
        int c1 = accented ? (0xC6000000 | primaryRgb) : 0xACFFFFFF;
        int c2 = 0x52FFFFFF;
        int c3 = accented ? (0xA4000000 | secondaryRgb) : 0x8AFFFFFF;
        int c4 = accented ? (0xEA000000 | secondaryRgb) : 0xE2FFFFFF;
        rim.setShader(new LinearGradient(
                r.left, r.top, r.right, r.bottom,
                new int[]{c0, c1, c2, c3, c4},
                new float[]{0f, 0.24f, 0.52f, 0.78f, 1f},
                Shader.TileMode.CLAMP));

        // Razor-thin top inner optical bevel lip (fades quickly before touching tile artwork).
        innerCaustic.setShader(new LinearGradient(
                r.left, r.top, r.left, r.top + Math.max(1f, r.height() * 0.25f),
                0x64FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));

        // Dual-tone ambient light gradient used when baking the half-res cached Gaussian aura bitmap.
        LinearGradient glowGrad = new LinearGradient(
                r.left, r.top, r.right, r.bottom,
                new int[]{0xFF000000 | primaryRgb, 0xFF000000 | secondaryRgb},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
        auraPaint.setShader(glowGrad);
        haloPaint.setShader(glowGrad);

        // Fleeting refractive droplet sheen shown ONLY while morphing in flight between tiles.
        flightFill.setShader(new LinearGradient(
                r.left, r.top, r.right, r.bottom,
                new int[]{0x1EFFFFFF, 0x06FFFFFF, 0x14000000 | secondaryRgb},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
    }

    /** Returns a cached half-res pre-blurred outer glow + lift shadow bitmap with a 100% transparent interior. */
    private Bitmap getOrBuildGlowBitmap(int w, int h, RectF base) {
        int rKey = Math.round(radius * 2f);
        int styleBits = (flat ? 1 : 0) | (halo ? 2 : 0) | (accented ? 4 : 0) | (lightSurface ? 8 : 0);
        if (currentGlowBmp != null && !currentGlowBmp.isRecycled()
                && w == cachedGlowW && h == cachedGlowH && rKey == cachedGlowR
                && primaryRgb == cachedGlowP && secondaryRgb == cachedGlowS && styleBits == cachedGlowStyle) {
            return currentGlowBmp;
        }
        String key = w + "x" + h + ":" + rKey + ":" + primaryRgb + ":" + secondaryRgb + ":" + styleBits;
        Bitmap cached = GLOW_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            cachedGlowW = w;
            cachedGlowH = h;
            cachedGlowR = rKey;
            cachedGlowP = primaryRgb;
            cachedGlowS = secondaryRgb;
            cachedGlowStyle = styleBits;
            currentGlowBmp = cached;
            return cached;
        }
        int bw = Math.max(2, (w + 1) / 2);
        int bh = Math.max(2, (h + 1) / 2);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(bw / (float) w, bh / (float) h);

        if (!flat) {
            workRect.set(base.left - 1f * density, base.top + 2.5f * density,
                    base.right + 1f * density, base.bottom + 5.5f * density);
            c.drawRoundRect(workRect, radius + density, radius + density, shadowPaint);
        }

        float scale = flat ? 0.55f : 1.0f;
        float out = 2.0f * density;
        auraPaint.setAlpha(Math.min(255, Math.round(auraBaseAlpha * scale)));
        workRect.set(base.left - out, base.top - out, base.right + out, base.bottom + out);
        c.drawRoundRect(workRect, radius + out, radius + out, auraPaint);

        haloPaint.setAlpha(Math.min(255, Math.round(haloBaseAlpha * scale)));
        c.drawRoundRect(base, radius, radius, haloPaint);

        // Cleanly punch out the tile interior so artwork stays 100% crisp with zero glow haze.
        c.drawRoundRect(base, radius, radius, clearPaint);
        bmp.prepareToDraw();

        GLOW_CACHE.put(key, bmp);
        cachedGlowW = w;
        cachedGlowH = h;
        cachedGlowR = rKey;
        cachedGlowP = primaryRgb;
        cachedGlowS = secondaryRgb;
        cachedGlowStyle = styleBits;
        currentGlowBmp = bmp;
        return bmp;
    }

    private void stepPhysics() {
        long nowNs = System.nanoTime();
        float defaultDt = 1f / Motion.displayRefreshRate(this);
        float dt = lastFrameNs == 0L ? defaultDt : clamp((nowNs - lastFrameNs) * 1e-9f, 0.001f, 0.05f);
        lastFrameNs = nowNs;

        float scale = Math.max(1f, 1500f * density);
        float targetNx = clamp(rawVx / scale, -1.35f, 1.35f);
        float targetNy = clamp(rawVy / scale, -1.35f, 1.35f);

        float prevVx = smoothVx;
        float prevVy = smoothVy;
        // Exact refresh-rate-invariant exponential smoothing & velocity decay (identical across 50-240 Hz).
        float alpha = 1f - (float) Math.exp(-26.0 * dt);
        smoothVx += (targetNx - smoothVx) * alpha;
        smoothVy += (targetNy - smoothVy) * alpha;

        float dvx = smoothVx - prevVx;
        float dvy = smoothVy - prevVy;
        wobVx += -dvx * 0.88f;
        wobVy += -dvy * 0.88f;

        float decay = (float) Math.exp(-7.0 * dt);
        rawVx *= decay;
        rawVy *= decay;

        // Sub-stepped harmonic jelly spring: exact identical feel from 50 Hz up to 240 Hz with zero energy drift.
        final float stiffness = 265f;
        final float damping = 14.5f;
        float remaining = dt;
        while (remaining > 0f) {
            float step = Math.min(remaining, 0.008f);
            wobVx += (-stiffness * wobX - damping * wobVx) * step;
            wobVy += (-stiffness * wobY - damping * wobVy) * step;
            wobX = clamp(wobX + wobVx * step, -1.2f, 1.2f);
            wobY = clamp(wobY + wobVy * step, -1.2f, 1.2f);
            remaining -= step;
        }

        wavePhase += dt * 15.0f;

        float speedE = (float) Math.hypot(smoothVx, smoothVy);
        float wobE = (float) Math.hypot(wobX, wobY) * 1.4f + (float) Math.hypot(wobVx, wobVy) * 0.05f;
        energy = Math.min(1.2f, Math.max(speedE, wobE));

        if (energy < 0.006f && Math.abs(rawVx) < 6f && Math.abs(rawVy) < 6f) {
            rawVx = rawVy = 0f;
            smoothVx = smoothVy = 0f;
            wobX = wobY = wobVx = wobVy = 0f;
            energy = 0f;
            animActive = false;
        } else {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        resetMotion();
        super.onDetachedFromWindow();
    }

    private void prepareBlobFrame(RectF base) {
        frameE = Math.min(1f, energy);
        float halfW = Math.max(1f, base.width() * 0.5f);
        float halfH = Math.max(1f, base.height() * 0.5f);
        // Cap absolute stretch so wide rows/pills never overshoot INSET_DP and clip their Gaussian aura.
        float maxSx = Math.min(0.16f, (11f * density) / halfW);
        float maxSy = Math.min(0.16f, (11f * density) / halfH);
        frameSx = clamp((Math.abs(smoothVx) * 0.14f - Math.abs(smoothVy) * 0.08f) + wobX * 0.13f - wobY * 0.06f, -maxSx * 0.85f, maxSx);
        frameSy = clamp((Math.abs(smoothVy) * 0.14f - Math.abs(smoothVx) * 0.08f) + wobY * 0.13f - wobX * 0.06f, -maxSy * 0.85f, maxSy);

        float lagX = clamp(smoothVx * 7f * density, -10f * density, 10f * density);
        float lagY = clamp(smoothVy * 7f * density, -10f * density, 10f * density);
        frameCx = base.centerX() - lagX * 0.28f;
        frameCy = base.centerY() - lagY * 0.28f;

        frameBiasX = clamp(smoothVx * 0.24f, -0.28f, 0.28f);
        frameBiasY = clamp(smoothVy * 0.24f, -0.28f, 0.28f);

        // Scale edge wave amplitude proportionally for slim pills/chips so thin capsules stay crisp and silky.
        float edgeScaleH = clamp(halfH / (36f * density), 0.25f, 1f);
        float edgeScaleW = clamp(halfW / (36f * density), 0.25f, 1f);
        float waistH = -Math.abs(smoothVx) * 6.5f * density * edgeScaleH;
        float waistV = -Math.abs(smoothVy) * 6.5f * density * edgeScaleW;
        float waveAmpH = 4.0f * density * frameE * edgeScaleH;
        float waveAmpV = 4.0f * density * frameE * edgeScaleW;
        frameTopDip = waistH + waveAmpH * (float) Math.sin(wavePhase);
        frameBotDip = waistH + waveAmpH * (float) Math.sin(wavePhase + 1.85f);
        frameLeftDip = waistV + waveAmpV * (float) Math.cos(wavePhase + 0.75f);
        frameRightDip = waistV + waveAmpV * (float) Math.cos(wavePhase + 2.55f);
    }

    /**
     * Builds the living liquid-blob contour with exact circular cubic Bezier corners (KAPPA = 0.55228475).
     * As {@code energy -> 0}, this converges pixel-for-pixel into the resting rounded rectangle with zero snap.
     */
    private void buildBlobPath(Path path, RectF base, float baseRadius, float expandPx) {
        float maxExtra = (INSET_DP - 6f) * density;
        float hw = Math.min(base.width() * 0.5f + maxExtra, base.width() * 0.5f * (1f + frameSx) + expandPx);
        float hh = Math.min(base.height() * 0.5f + maxExtra, base.height() * 0.5f * (1f + frameSy) + expandPx);
        float l = frameCx - hw, r = frameCx + hw, t = frameCy - hh, b = frameCy + hh;

        float fullR = Math.min(hw, hh);
        float flightTargetR = Math.max(baseRadius, fullR * 0.80f);
        float liquidR = baseRadius + (flightTargetR - baseRadius) * Math.min(1f, frameE * 0.50f) + expandPx;
        float rTL = clamp(liquidR * (1f - frameBiasX - frameBiasY), 4f, fullR);
        float rTR = clamp(liquidR * (1f + frameBiasX - frameBiasY), 4f, fullR);
        float rBR = clamp(liquidR * (1f + frameBiasX + frameBiasY), 4f, fullR);
        float rBL = clamp(liquidR * (1f - frameBiasX + frameBiasY), 4f, fullR);

        float cTL = rTL * KAPPA, cTR = rTR * KAPPA, cBR = rBR * KAPPA, cBL = rBL * KAPPA;

        // Only bow genuinely straight spans (never emit zero-length 180-deg hairpin spikes on circle/capsule poles).
        float topSpan = (r - rTR) - (l + rTL);
        float rightSpan = (b - rBR) - (t + rTR);
        float botSpan = (r - rBR) - (l + rBL);
        float leftSpan = (b - rBL) - (t + rTL);
        float spanRef = 24f * density;

        path.reset();
        path.moveTo(l + rTL, t);
        if (topSpan > 1f) {
            path.quadTo((l + r) * 0.5f, t + frameTopDip * Math.min(1f, topSpan / spanRef), r - rTR, t);
        }
        path.cubicTo(r - rTR + cTR, t, r, t + rTR - cTR, r, t + rTR);
        if (rightSpan > 1f) {
            path.quadTo(r - frameRightDip * Math.min(1f, rightSpan / spanRef), (t + b) * 0.5f, r, b - rBR);
        }
        path.cubicTo(r, b - rBR + cBR, r - rBR + cBR, b, r - rBR, b);
        if (botSpan > 1f) {
            path.quadTo((l + r) * 0.5f, b - frameBotDip * Math.min(1f, botSpan / spanRef), l + rBL, b);
        }
        path.cubicTo(l + rBL - cBL, b, l, b - rBL + cBL, l, b - rBL);
        if (leftSpan > 1f) {
            path.quadTo(l + frameLeftDip * Math.min(1f, leftSpan / spanRef), (t + b) * 0.5f, l, t + rTL);
        }
        path.cubicTo(l, t + rTL - cTL, l + rTL - cTL, t, l + rTL, t);
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (animActive) stepPhysics();

        int w = getWidth(), h = getHeight();
        float inset = insetPx(density);
        rect.set(inset, inset, w - inset, h - inset);
        if (rect.width() <= 1f || rect.height() <= 1f) return;
        if (shadersDirty) rebuildShaders(rect);

        boolean moving = animActive && liquidEnabled && energy >= 0.006f;
        float e = moving ? Math.min(1f, energy) : 0f;
        // Tight settle onset so static micro-details fade in right as liquid deformation converges to rest.
        float settle = 1f - clamp(e * 4.5f, 0f, 1f);

        float glowL, glowT, glowR, glowB;
        if (moving) {
            prepareBlobFrame(rect);
            buildBlobPath(cardPath, rect, radius, 0f);
            float maxExtra = (INSET_DP - 6f) * density;
            float hw = Math.min(rect.width() * 0.5f + maxExtra, rect.width() * 0.5f * (1f + frameSx));
            float hh = Math.min(rect.height() * 0.5f + maxExtra, rect.height() * 0.5f * (1f + frameSy));
            glowL = frameCx - hw;
            glowT = frameCy - hh;
            glowR = frameCx + hw;
            glowB = frameCy + hh;
        } else {
            glowL = rect.left;
            glowT = rect.top;
            glowR = rect.right;
            glowB = rect.bottom;
        }

        // 1. Pre-baked 2-stage Gaussian bloom + lift shadow (single GPU texture blit, 0 BlurMaskFilter, 0 stencil).
        if (!flat || halo) {
            Bitmap glowBmp = getOrBuildGlowBitmap(w, h, rect);
            if (moving) {
                float kx = (glowR - glowL) / rect.width();
                float ky = (glowB - glowT) / rect.height();
                workRect.set(glowL - inset * kx, glowT - inset * ky, glowR + inset * kx, glowB + inset * ky);
            } else {
                workRect.set(0f, 0f, w, h);
            }
            canvas.drawBitmap(glowBmp, null, workRect, glowBmpPaint);
        }

        // 2. Fleeting translucent refraction shimmer ONLY while morphing in flight (dissolves smoothly to 0%).
        if (!flat && e > 0.03f) {
            flightFill.setAlpha(Math.round(e * 175f));
            canvas.drawPath(cardPath, flightFill);
        }

        // 3. Subtle dark micro-contrast hairline (quadratic ease-in right as wobble finishes settling).
        float fineSettle = settle * settle;
        if (fineSettle > 0.02f) {
            contrastRim.setAlpha(Math.round((lightSurface ? 0x52 : 0x2E) * fineSettle));
            float outerOffset = rim.getStrokeWidth() * 0.5f + contrastRim.getStrokeWidth() * 0.5f;
            canvas.drawRoundRect(
                    rect.left - outerOffset, rect.top - outerOffset,
                    rect.right + outerOffset, rect.bottom + outerOffset,
                    radius + outerOffset, radius + outerOffset, contrastRim);
        }

        // 4. Primary directional Fresnel crystal bezel (ZERO milky fill over icons or folders).
        if (moving) {
            canvas.drawPath(cardPath, rim);
        } else {
            canvas.drawRoundRect(rect, radius, radius, rim);
        }

        // 5. Razor-thin top inner bevel catchlight gliding in gently on settle.
        if (!flat && fineSettle > 0.02f) {
            innerCaustic.setAlpha(Math.round(255f * fineSettle));
            float lip = rim.getStrokeWidth() * 0.5f + innerCaustic.getStrokeWidth() * 0.7f;
            workRect.set(rect.left + lip, rect.top + lip, rect.right - lip, rect.bottom - lip);
            float innerR = Math.max(0f, radius - lip);
            int save = canvas.save();
            canvas.clipRect(workRect.left, workRect.top, workRect.right, workRect.top + workRect.height() * 0.25f);
            canvas.drawRoundRect(workRect, innerR, innerR, innerCaustic);
            canvas.restoreToCount(save);
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }
}
