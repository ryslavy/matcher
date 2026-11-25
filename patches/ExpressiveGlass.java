package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android 16 QPR / Android 17 Material 3 Expressive frosted-glass engine ported to Android 14 (API 34).
 *
 * Derived directly from AOSP (temp-android16-plus):
 *  - SystemUI blur dimensions (dimens.xml): max_shade_window_blur_radius = 34dp, volume/dialog = 23dp.
 *  - RenderEngine KawaseBlurDualFilter / BlurFilter: 1/sqrt(3) sigma conversion (0.57735) + optical zoomScale.
 *  - SystemUI ShadeColors.kt: multi-layer dynamic frosted tinting (luminous #404040 lift + deep lStar=4
 *    neutral base + wallpaper-extracted system_accent1_800 dynamic tint) + anti-banding etched-glass grain.
 */
final class ExpressiveGlass {

    /** AOSP max_shade_window_blur_radius & global_actions_blur_radius (34dp). */
    static final float SHADE_BLUR_RADIUS_DP = 34f;
    /** AOSP volume_dialog_background_surface_blur_radius (23dp). */
    static final float DIALOG_BLUR_RADIUS_DP = 23f;
    /** AOSP KawaseBlurDualFilter.cpp conversion factor (1 / sqrt(3)) to match Skia RenderEffect sigma. */
    static final float SKIA_SIGMA_FACTOR = 0.57735f;
    /** Subtle optical glass magnification behind floating frosted capsules (inspired by AOSP BlurFilter zoomScale). */
    static final float REFRACTION_ZOOM = 1.035f;
    /** AOSP core/res system_surface_effect_0_dark fallback seed when wallpaper is neutral or disabled. */
    static final int DEFAULT_SEED_RGB = 0x223050;

    /**
     * AGSL post-blur shader (API 33+): chains directly onto Skia's GPU blur pass and runs ONCE into Skia's
     * cached offscreen texture while modals animate at 60 fps.
     * Adds Material 3 Expressive vibrancy saturation boost, AOSP ShadeColors luminous dark lift + dynamic
     * wallpaper tint harmony, and Interleaved Gradient Noise (IGN) etched-glass dithering to kill 8-bit banding.
     */
    private static final String BACKDROP_AGSL =
            "uniform shader content;\n"
                    + "uniform float uSaturation;\n"
                    + "uniform float uLift;\n"
                    + "uniform float uDim;\n"
                    + "uniform float3 uTint;\n"
                    + "uniform float uTintMix;\n"
                    + "uniform float uGrain;\n"
                    + "half4 main(float2 coord) {\n"
                    + "    half4 c = content.eval(coord);\n"
                    + "    if (c.a <= 0.001) return half4(0.0);\n"
                    + "    float3 rgb = float3(c.rgb) / max(float(c.a), 0.001);\n"
                    + "    float lum = dot(rgb, float3(0.2126, 0.7152, 0.0722));\n"
                    + "    rgb = mix(float3(lum), rgb, uSaturation);\n"
                    + "    rgb = rgb * uDim + float3(uLift);\n"
                    + "    rgb = mix(rgb, uTint, uTintMix);\n"
                    + "    float n = fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715)))) - 0.5;\n"
                    + "    rgb = clamp(rgb + n * uGrain, 0.0, 1.0);\n"
                    + "    return half4(half3(rgb * float(c.a)), c.a);\n"
                    + "}";

    /**
     * Real-time Liquid Glass rim-refraction AGSL shader (API 33+):
     *  - On Home & Screensaver (uLiquidMode > 0.5): true Apple tvOS 26 / macOS 27 optical Liquid Glass lens
     *    sampling crisp hardware-bilinear uSharp along the curved thick bezel with Snell refraction,
     *    RGB prism dispersion, crisp directional Fresnel specular, and opposite-side internal bounce.
     *  - Over external apps (uLiquidMode <= 0.5): smooth frosted refraction sampling pre-blurred uBlur
     *    so compressed video/DRM streams never show macroblock artifacts.
     */
    static final String LIQUID_GLASS_PANEL_AGSL =
            "uniform shader uSharp;\n"
                    + "uniform shader uBlur;\n"
                    + "uniform float2 uPanelSize;\n"
                    + "uniform float2 uScreenOrigin;\n"
                    + "uniform float2 uScreenScale;\n"
                    + "uniform float uRadius;\n"
                    + "uniform float uBezel;\n"
                    + "uniform float uRefract;\n"
                    + "uniform float uChromatic;\n"
                    + "uniform float uEdgeClarity;\n"
                    + "uniform float uInteriorFill;\n"
                    + "uniform float uLiquidMode;\n"
                    + "uniform float3 uRimTint;\n"
                    + "half3 sampleBlur(float2 coord) {\n"
                    + "    half3 c = uBlur.eval(coord).rgb * half(0.36);\n"
                    + "    c += uBlur.eval(coord + float2( 5.25,  2.15)).rgb * half(0.16);\n"
                    + "    c += uBlur.eval(coord + float2(-5.25, -2.15)).rgb * half(0.16);\n"
                    + "    c += uBlur.eval(coord + float2(-2.15,  5.25)).rgb * half(0.16);\n"
                    + "    c += uBlur.eval(coord + float2( 2.15, -5.25)).rgb * half(0.16);\n"
                    + "    return c;\n"
                    + "}\n"
                    + "half3 sampleFrostedRim(float2 coord) {\n"
                    + "    half3 c = uSharp.eval(coord).rgb * half(0.28);\n"
                    + "    c += uSharp.eval(coord + float2( 8.6,  3.5)).rgb * half(0.18);\n"
                    + "    c += uSharp.eval(coord + float2(-8.6, -3.5)).rgb * half(0.18);\n"
                    + "    c += uSharp.eval(coord + float2(-3.5,  8.6)).rgb * half(0.18);\n"
                    + "    c += uSharp.eval(coord + float2( 3.5, -8.6)).rgb * half(0.18);\n"
                    + "    return c;\n"
                    + "}\n"
                    + "half3 sampleSharpSmooth(float2 coord) {\n"
                    + "    half3 c = uSharp.eval(coord).rgb * half(0.36);\n"
                    + "    c += uSharp.eval(coord + float2( 4.25,  1.75)).rgb * half(0.16);\n"
                    + "    c += uSharp.eval(coord + float2(-4.25, -1.75)).rgb * half(0.16);\n"
                    + "    c += uSharp.eval(coord + float2(-1.75,  4.25)).rgb * half(0.16);\n"
                    + "    c += uSharp.eval(coord + float2( 1.75, -4.25)).rgb * half(0.16);\n"
                    + "    return c;\n"
                    + "}\n"
                    + "half4 main(float2 fragCoord) {\n"
                    + "    float2 halfSize = uPanelSize * 0.5;\n"
                    + "    float2 p = fragCoord - halfSize;\n"
                    + "    float r = min(uRadius, min(halfSize.x, halfSize.y));\n"
                    + "    float2 q = abs(p) - halfSize + float2(r);\n"
                    + "    float2 qPos = max(q, float2(0.0));\n"
                    + "    float d = length(qPos) + min(max(q.x, q.y), 0.0) - r;\n"
                    + "    float mask = 1.0 - smoothstep(-0.75, 0.75, d);\n"
                    + "    if (mask <= 0.001) return half4(0.0);\n"
                    + "    float ign = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715)))) - 0.5;\n"
                    + "    half dither = half(ign * (1.6 / 255.0));\n"
                    + "    float2 screenPos = uScreenOrigin + fragCoord * uScreenScale;\n"
                    + "    if (uInteriorFill > 0.5) {\n"
                    + "        half3 baseBlur = sampleBlur(screenPos);\n"
                    + "        half bl = dot(baseBlur, half3(0.2126, 0.7152, 0.0722));\n"
                    + "        baseBlur = clamp(mix(half3(bl), baseBlur, half(1.18)) * half(0.92) + half3(0.015) + half3(dither), half3(0.0), half3(1.0));\n"
                    + "        return half4(baseBlur * half(mask), half(mask));\n"
                    + "    }\n"
                    + "    float distIn = max(0.0, -d);\n"
                    + "    if (distIn >= uBezel) return half4(0.0);\n"
                    + "    float2 grad = (q.x > 0.0 || q.y > 0.0)\n"
                    + "            ? normalize(qPos + float2(1e-5))\n"
                    + "            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
                    + "    float2 n = grad * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
                    + "    float t = clamp(distIn / max(1.0, uBezel), 0.0, 1.0);\n"
                    + "    float u = 1.0 - t;\n"
                    + "    float lensCurve = clamp(u / sqrt(max(0.16, 1.0 - u * u)), 0.0, 1.95) / 1.95;\n"
                    + "    float disp = lensCurve * uRefract;\n"
                    + "    float2 uvR = screenPos - n * (disp - lensCurve * uChromatic);\n"
                    + "    float2 uvG = screenPos - n * disp;\n"
                    + "    float2 uvB = screenPos - n * (disp + lensCurve * uChromatic);\n"
                    + "    float lightDir = dot(n, float2(-0.4190, -0.9080));\n"
                    + "    float facing = max(0.0, lightDir);\n"
                    + "    float back = max(0.0, -lightDir);\n"
                    + "    half3 specCol = mix(half3(1.0), half3(uRimTint), half(0.28));\n"
                    + "    if (uLiquidMode > 0.5) {\n"
                    + "        half3 lensFrost = half3(uSharp.eval(uvR).r, uSharp.eval(uvG).g, uSharp.eval(uvB).b);\n"
                    + "        half3 deepBlur = half3(sampleBlur(uvR).r, sampleBlur(uvG).g, sampleBlur(uvB).b);\n"
                    + "        float clarity = clamp(pow(lensCurve, 0.72) * uEdgeClarity, 0.0, 0.92);\n"
                    + "        half3 refracted = mix(deepBlur, lensFrost, half(clarity));\n"
                    + "        half luma = dot(refracted, half3(0.2126, 0.7152, 0.0722));\n"
                    + "        refracted = clamp(mix(half3(luma), refracted, half(1.38)) * half(1.26) + half3(0.065) + half3(dither), half3(0.0), half3(1.0));\n"
                    + "        float meniscusDark = 1.0 - 0.28 * exp(-pow((t - 0.16) / 0.11, 2.0));\n"
                    + "        refracted *= half(meniscusDark);\n"
                    + "        float rimLine = smoothstep(2.5, 0.0, distIn);\n"
                    + "        float innerHalo = smoothstep(uBezel * 0.62, 0.0, distIn);\n"
                    + "        float fresnel = rimLine * (0.38 + 0.62 * facing + 0.36 * back);\n"
                    + "        float caustic = smoothstep(uBezel * 0.38, 0.0, abs(distIn - uBezel * 0.34)) * (0.14 + 0.18 * facing + 0.09 * back) + innerHalo * (0.06 * facing + 0.03 * back);\n"
                    + "        float rimWeight = smoothstep(1.0, 0.0, t);\n"
                    + "        float refractAlpha = pow(rimWeight, 1.05) * 0.86;\n"
                    + "        float specAlpha = clamp(fresnel * 0.88 + caustic, 0.0, 0.96);\n"
                    + "        float outA = clamp(specAlpha + refractAlpha * (1.0 - specAlpha), 0.0, 0.98);\n"
                    + "        half3 premul = clamp(specCol * half(specAlpha) + refracted * half(refractAlpha * (1.0 - specAlpha)), half3(0.0), half3(outA));\n"
                    + "        return half4(premul * half(mask), half(outA * mask));\n"
                    + "    }\n"
                    + "    half3 refracted = half3(sampleBlur(uvR).r, sampleBlur(uvG).g, sampleBlur(uvB).b);\n"
                    + "    if (uEdgeClarity > 0.01 && t < 0.99) {\n"
                    + "        half3 sharpEdge = half3(sampleSharpSmooth(uvR).r, sampleSharpSmooth(uvG).g, sampleSharpSmooth(uvB).b);\n"
                    + "        refracted = mix(refracted, sharpEdge, half(pow(lensCurve, 1.1) * uEdgeClarity * 0.35));\n"
                    + "    }\n"
                    + "    half luma = dot(refracted, half3(0.2126, 0.7152, 0.0722));\n"
                    + "    refracted = clamp(mix(half3(luma), refracted, half(1.26)) * half(1.24) + half3(0.085) + half3(dither), half3(0.0), half3(1.0));\n"
                    + "    float rimLine = smoothstep(2.8, 0.0, distIn);\n"
                    + "    float fresnel = rimLine * (0.40 + 0.44 * facing + 0.30 * back);\n"
                    + "    float caustic = smoothstep(uBezel * 0.48, 0.0, abs(distIn - uBezel * 0.20)) * (0.085 + 0.115 * facing + 0.055 * back);\n"
                    + "    float rimWeight = smoothstep(1.0, 0.0, t);\n"
                    + "    float refractAlpha = pow(rimWeight, 1.65) * 0.42;\n"
                    + "    float specAlpha = clamp(fresnel * 0.76 + caustic, 0.0, 0.92);\n"
                    + "    float outA = clamp(specAlpha + refractAlpha * (1.0 - specAlpha), 0.0, 0.94);\n"
                    + "    half3 premul = clamp(specCol * half(specAlpha) + refracted * half(refractAlpha * (1.0 - specAlpha)), half3(0.0), half3(outA));\n"
                    + "    return half4(premul * half(mask), half(outA * mask));\n"
                    + "}";

    /**
     * iOS 26 / visionOS Liquid Glass beveled clock typography shader (API 33+):
     * Uses precomputed glyph slope/height maps (R/G = surface normal, B = thickness) and crisp coverage mask
     * so clock digits optically refract the live wallpaper or screensaver video behind them with RGB prism
     * dispersion, top-lit pearl face sheen, and directional Fresnel rim highlights.
     */
    static final String LIQUID_GLASS_TEXT_AGSL =
            "uniform shader uBg;\n"
                    + "uniform shader uSlope;\n"
                    + "uniform shader uCover;\n"
                    + "uniform float2 uOrigin;\n"
                    + "uniform float2 uScale;\n"
                    + "uniform float2 uInk;\n"
                    + "uniform float uRefract;\n"
                    + "uniform float uSpecular;\n"
                    + "uniform float3 uRimTint;\n"
                    + "half4 main(float2 local) {\n"
                    + "    half a = uCover.eval(local).a;\n"
                    + "    if (a <= 0.002) return half4(0.0);\n"
                    + "    half3 s = uSlope.eval(local).rgb;\n"
                    + "    float2 n = (float2(s.r, s.g) - 0.5) * 2.0;\n"
                    + "    float bend = length(n);\n"
                    + "    float height = float(s.b);\n"
                    + "    float2 screenPos = uOrigin + local * uScale;\n"
                    + "    float2 p = screenPos + n * uRefract;\n"
                    + "    half3 bg;\n"
                    + "    if (bend > 0.02) {\n"
                    + "        float2 ca = n * (uRefract * 0.22);\n"
                    + "        bg = half3(uBg.eval(p + ca).r, uBg.eval(p).g, uBg.eval(p - ca).b);\n"
                    + "    } else {\n"
                    + "        bg = uBg.eval(p).rgb;\n"
                    + "    }\n"
                    + "    half l = dot(bg, half3(0.2126, 0.7152, 0.0722));\n"
                    + "    bg = mix(half3(l), bg, half(1.22));\n"
                    + "    float ty = clamp((local.y - uInk.x) / max(1.0, uInk.y), 0.0, 1.0);\n"
                    + "    half3 pearlTint = mix(half3(1.0), half3(uRimTint), half(0.24));\n"
                    + "    half3 col = mix(pearlTint * half(0.84 + 0.16 * (1.0 - ty)), bg * half(0.55) + pearlTint * half(0.52), half(0.36));\n"
                    + "    float2 dir = n / max(bend, 0.001);\n"
                    + "    float2 L = float2(-0.447, -0.894);\n"
                    + "    float facing = clamp(-dot(dir, L), 0.0, 1.0);\n"
                    + "    float back = clamp(dot(dir, L), 0.0, 1.0);\n"
                    + "    float edge = smoothstep(0.12, 0.55, bend);\n"
                    + "    float rim = (1.0 - smoothstep(0.45, 0.65, height)) * edge;\n"
                    + "    half3 specCol = mix(half3(1.0), half3(uRimTint), half(0.18));\n"
                    + "    col += specCol * half(rim * (0.14 + 0.34 * facing + 0.16 * back) * uSpecular);\n"
                    + "    float ign = fract(52.9829189 * fract(dot(local, float2(0.06711056, 0.00583715)))) - 0.5;\n"
                    + "    col = clamp(col + half3(ign * (1.4 / 255.0)), half3(0.0), half3(1.0));\n"
                    + "    return half4(col * a, a);\n"
                    + "}";

    static final String EXTRA_BACKDROP_JPEG = "com.gathin.tvhome.BACKDROP_JPEG";
    static final String EXTRA_OVER_HOME = "com.gathin.tvhome.OVER_HOME";
    static final String EXTRA_SEAMLESS_HANDOFF = "com.gathin.tvhome.SEAMLESS_HANDOFF";
    static final String ACTION_ADDON_DISMISSING = "com.gathin.tvhome.ACTION_ADDON_DISMISSING";

    static final int HOME_WALLPAPER_SHARP_W = 960;
    static final int HOME_WALLPAPER_SHARP_H = 540;
    static final int REFRACT_SHARP_W = 480;
    static final int REFRACT_SHARP_H = 270;
    static final int REFRACT_BLUR_W = 320;
    static final int REFRACT_BLUR_H = 180;

    static volatile boolean homeLiquidOpticsEnabled = true;

    static final class RefractionBackdrop {
        final Bitmap sharp;
        final Bitmap blur;
        final BitmapShader sharpShader;
        final BitmapShader blurShader;
        final int screenW;
        final int screenH;
        final int version;
        final boolean externalApp;

        RefractionBackdrop(Bitmap sharp, Bitmap blur, int screenW, int screenH, int version) {
            this(sharp, blur, screenW, screenH, version, false);
        }

        RefractionBackdrop(Bitmap sharp, Bitmap blur, int screenW, int screenH, int version, boolean externalApp) {
            this.sharp = sharp;
            this.blur = blur;
            this.screenW = Math.max(1, screenW);
            this.screenH = Math.max(1, screenH);
            this.version = version;
            this.externalApp = externalApp;
            this.sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.sharpShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            Matrix sm = new Matrix();
            sm.setScale(this.screenW / (float) Math.max(1, sharp.getWidth()),
                    this.screenH / (float) Math.max(1, sharp.getHeight()));
            this.sharpShader.setLocalMatrix(sm);

            this.blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.blurShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            Matrix bm = new Matrix();
            bm.setScale(this.screenW / (float) Math.max(1, blur.getWidth()),
                    this.screenH / (float) Math.max(1, blur.getHeight()));
            this.blurShader.setLocalMatrix(bm);
        }
    }

    private static volatile int dynamicSeedRgb = DEFAULT_SEED_RGB;
    @Nullable
    private static Bitmap sharedNoiseBitmap;
    @Nullable
    private static BitmapShader sharedNoiseShader;
    /** Live GPU HardwareBuffer screenshot captured by HomeKeyService when opening overlays over external apps. */
    @Nullable
    private static volatile Bitmap overlayBackdropBitmap;
    @Nullable
    private static volatile RefractionBackdrop overlayRefraction;
    private static volatile int overlayBackdropGen;
    @Nullable
    private static volatile RefractionBackdrop homeLiveRefraction;
    @Nullable
    private static volatile RefractionBackdrop homeWallpaperRefraction;
    private static volatile int backdropVersionCounter = 1;
    @Nullable
    private static volatile byte[] cachedJpegBytes;
    private static volatile int cachedJpegVersion = -1;
    @Nullable
    private static volatile ExecutorService refractExec;
    @Nullable
    private static volatile Handler mainHandler;
    private static final java.util.Map<Long, RenderEffect> EFFECT_CACHE = new java.util.HashMap<>();
    private static int effectCacheSeed = -1;
    private static final java.util.ArrayDeque<RuntimeShader> LIQUID_SHADER_POOL = new java.util.ArrayDeque<>();
    private static final Object BLUR_SCRATCH_LOCK = new Object();
    @Nullable
    private static int[] blurPxScratch;
    @Nullable
    private static int[] blurTmpScratch;

    /** Pre-warms Skia's AGSL shaders and RenderEffect blur caches off the first menu/modal open. */
    static void warmUpLiquidShader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        synchronized (LIQUID_SHADER_POOL) {
            while (LIQUID_SHADER_POOL.size() < 4) {
                try {
                    LIQUID_SHADER_POOL.addLast(new RuntimeShader(LIQUID_GLASS_PANEL_AGSL));
                } catch (RuntimeException ignored) {
                    return;
                }
            }
        }
    }

    /** Pre-warms all AGSL shaders + full-screen & submenu RenderEffect blur stages once after first frame. */
    static void warmUpResources(float density) {
        warmUpLiquidShader();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createBackdropBlurEffect(density, SHADE_BLUR_RADIUS_DP, true, Shader.TileMode.CLAMP);
            for (int i = 1; i < SUBMENU_BLUR_STEPS_DP.length; i++) {
                blurEffectForStep(i, density);
            }
        }
    }

    @Nullable
    static RuntimeShader acquireLiquidShader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        synchronized (LIQUID_SHADER_POOL) {
            RuntimeShader pooled = LIQUID_SHADER_POOL.pollFirst();
            if (pooled != null) return pooled;
        }
        try {
            return new RuntimeShader(LIQUID_GLASS_PANEL_AGSL);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static void recycleLiquidShader(@Nullable RuntimeShader shader) {
        if (shader == null) return;
        synchronized (LIQUID_SHADER_POOL) {
            if (LIQUID_SHADER_POOL.size() < 8) LIQUID_SHADER_POOL.addLast(shader);
        }
    }

    /**
     * Draws only the outer curved bezel ring (4 non-overlapping edge strips) around the flat interior hole
     * where {@code distIn >= bezel}, skipping 50–85% of fragment shader pixels on modals, menus, and Dock.
     */
    static void drawBezelRing(Canvas canvas, float left, float top, float right, float bottom,
                              float radius, float bezel, Paint paint) {
        float w = right - left;
        float h = bottom - top;
        float holeInset = (float) Math.ceil(Math.max(bezel + 2f, (bezel + 2f) * 0.70711f + radius * 0.29289f));
        if (w <= 2f * holeInset + 4f || h <= 2f * holeInset + 4f) {
            canvas.drawRect(left, top, right, bottom, paint);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Punch out the transparent interior (distIn >= bezel) and draw the bezel as one
            // unbroken quad so fractional coordinates / AA never leave a 1px corner seam.
            int save = canvas.save();
            canvas.clipOutRect(left + holeInset, top + holeInset, right - holeInset, bottom - holeInset);
            canvas.drawRect(left, top, right, bottom, paint);
            canvas.restoreToCount(save);
            return;
        }
        boolean wasAa = paint.isAntiAlias();
        paint.setAntiAlias(false);
        float innerTop = top + holeInset;
        float innerBot = bottom - holeInset;
        canvas.drawRect(left, top, right, innerTop, paint);
        canvas.drawRect(left, innerBot, right, bottom, paint);
        canvas.drawRect(left, innerTop, left + holeInset, innerBot, paint);
        canvas.drawRect(right - holeInset, innerTop, right, innerBot, paint);
        paint.setAntiAlias(wasAa);
    }

    private ExpressiveGlass() { }

    private static Handler mainHandler() {
        Handler h = mainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            mainHandler = h;
        }
        return h;
    }

    private static ExecutorService refractExec() {
        ExecutorService e = refractExec;
        if (e == null) {
            synchronized (ExpressiveGlass.class) {
                e = refractExec;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "liquid-refract");
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    });
                    refractExec = e;
                }
            }
        }
        return e;
    }

    static void setOverlayBackdrop(@Nullable Bitmap bitmap) {
        setOverlayBackdrop(bitmap, null);
    }

    static void setOverlayBackdrop(@Nullable Bitmap bitmap, @Nullable Runnable onRefractionReady) {
        Bitmap valid = (bitmap != null && !bitmap.isRecycled()) ? bitmap : null;
        overlayBackdropBitmap = valid;
        if (valid != null) {
            overlayBackdropGen++;
            final int sw = Math.max(1, valid.getWidth());
            final int sh = Math.max(1, valid.getHeight());
            refractExec().execute(() -> {
                if (overlayBackdropBitmap != valid || valid.isRecycled()) {
                    if (onRefractionReady != null) mainHandler().post(onRefractionReady);
                    return;
                }
                RefractionBackdrop built = buildRefractionBackdrop(valid, sw, sh, true);
                if (built != null && overlayBackdropBitmap == valid) {
                    overlayRefraction = built;
                    preEncodeJpegIfCurrent(built);
                    mainHandler().post(() -> {
                        MainActivity.refreshLiveLiquidPanels();
                        if (onRefractionReady != null) onRefractionReady.run();
                    });
                } else if (onRefractionReady != null) {
                    mainHandler().post(onRefractionReady);
                }
            });
        } else {
            overlayRefraction = null;
            if (onRefractionReady != null) mainHandler().post(onRefractionReady);
        }
    }

    static int overlayBackdropGeneration() {
        return overlayBackdropGen;
    }

    static boolean hasOverlayRefraction() {
        RefractionBackdrop ov = overlayRefraction;
        return ov != null && !ov.sharp.isRecycled() && !ov.blur.isRecycled();
    }

    static void clearOverlayBackdropIfGeneration(int expectedGen) {
        if (overlayBackdropGen == expectedGen) {
            clearOverlayBackdrop();
        }
    }

    static void setHomeWallpaperBackdrop(@Nullable Bitmap sharp, @Nullable Bitmap blur, int screenW, int screenH) {
        if (sharp == null || blur == null || sharp.isRecycled() || blur.isRecycled()) {
            homeWallpaperRefraction = null;
            return;
        }
        homeWallpaperRefraction = new RefractionBackdrop(sharp, blur, screenW, screenH, ++backdropVersionCounter, false);
    }

    static boolean isHomeLiquidOpticsEnabled() {
        return homeLiquidOpticsEnabled;
    }

    static void setHomeLiquidOpticsEnabled(boolean enabled) {
        homeLiquidOpticsEnabled = enabled;
    }

    static void clearHomeLiveBackdrop() {
        homeLiveRefraction = null;
    }

    static boolean hasFreshHomeLiveBackdrop() {
        RefractionBackdrop hl = homeLiveRefraction;
        return hl != null && !hl.sharp.isRecycled() && !hl.blur.isRecycled();
    }

    @Nullable
    static RefractionBackdrop homeLiveRefraction() {
        RefractionBackdrop hl = homeLiveRefraction;
        return (hl != null && !hl.sharp.isRecycled() && !hl.blur.isRecycled()) ? hl : null;
    }

    @Nullable
    static RefractionBackdrop homeWallpaperRefraction() {
        RefractionBackdrop b = homeWallpaperRefraction;
        return (b != null && !b.sharp.isRecycled() && !b.blur.isRecycled()) ? b : null;
    }

    @Nullable
    static RefractionBackdrop activeRefractionBackdrop() {
        RefractionBackdrop ov = overlayRefraction;
        if (ov != null && !ov.sharp.isRecycled() && !ov.blur.isRecycled()) return ov;
        RefractionBackdrop hl = homeLiveRefraction;
        if (hl != null && !hl.sharp.isRecycled() && !hl.blur.isRecycled()) return hl;
        return homeWallpaperRefraction();
    }

    private static final Object CAPTURE_LOCK = new Object();
    private static boolean captureInFlight;
    private static final java.util.ArrayList<Runnable> CAPTURE_CALLBACKS = new java.util.ArrayList<>(4);

    private static void drainCaptureCallbacks() {
        final java.util.ArrayList<Runnable> toRun;
        synchronized (CAPTURE_LOCK) {
            captureInFlight = false;
            toRun = new java.util.ArrayList<>(CAPTURE_CALLBACKS);
            CAPTURE_CALLBACKS.clear();
        }
        for (int i = 0, n = toRun.size(); i < n; i++) {
            Runnable r = toRun.get(i);
            if (r != null) r.run();
        }
    }

    /** Captures the live Home window (including wallpaper, dock, and colorful icons) via GPU PixelCopy for dynamic glass refraction. */
    static void captureHomeWindowForRefraction(@Nullable Window window, @Nullable Runnable onReady) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (onReady != null) onReady.run();
            return;
        }
        View decor = window.peekDecorView();
        int sw = decor != null ? decor.getWidth() : 0;
        int sh = decor != null ? decor.getHeight() : 0;
        if (sw <= 0 || sh <= 0) {
            if (onReady != null) onReady.run();
            return;
        }
        synchronized (CAPTURE_LOCK) {
            if (onReady != null) CAPTURE_CALLBACKS.add(onReady);
            if (captureInFlight) return;
            captureInFlight = true;
        }
        try {
            Bitmap dest = Bitmap.createBitmap(REFRACT_SHARP_W, REFRACT_SHARP_H, Bitmap.Config.ARGB_8888);
            Handler main = mainHandler();
            PixelCopy.request(window, dest, result -> {
                if (result == PixelCopy.SUCCESS) {
                    refractExec().execute(() -> {
                        // Apply silky 2-pass Gaussian optical-frost prefilter to the freshly allocated 640x360
                        // capture before publishing it so modal Liquid Glass rims bend smoothly blurred glowing
                        // contours with zero sharp text/logos leaking into the curved bezel.
                        Bitmap blur = makeFastBlurred(dest, REFRACT_BLUR_W, REFRACT_BLUR_H, true);
                        if (blur != null) {
                            RefractionBackdrop built = new RefractionBackdrop(dest, blur, sw, sh, ++backdropVersionCounter, false);
                            homeLiveRefraction = built;
                            main.post(ExpressiveGlass::drainCaptureCallbacks);
                            preEncodeJpegIfCurrent(built);
                        } else {
                            dest.recycle();
                            main.post(ExpressiveGlass::drainCaptureCallbacks);
                        }
                    });
                } else {
                    dest.recycle();
                    drainCaptureCallbacks();
                }
            }, main);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            drainCaptureCallbacks();
        }
    }

    private static void preEncodeJpegIfCurrent(RefractionBackdrop bg) {
        if (bg == null || bg.sharp.isRecycled() || cachedJpegVersion == bg.version) return;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(24576);
            bg.sharp.compress(Bitmap.CompressFormat.JPEG, 76, out);
            cachedJpegBytes = out.toByteArray();
            cachedJpegVersion = bg.version;
        } catch (RuntimeException ignored) {
        }
    }

    /** Encodes the active refraction backdrop into a compact JPEG for cross-process add-on Intents. */
    @Nullable
    static byte[] encodeActiveBackdropJpeg() {
        return encodeBackdropJpeg(activeRefractionBackdrop());
    }

    /**
     * Encodes the exact matching refraction backdrop for cross-process add-on Intents:
     * strictly uses Home backdrop when {@code overHome == true} and external-app backdrop when {@code overHome == false},
     * preventing stale external-app screenshots from leaking onto Home add-ons or Home wallpaper onto external apps.
     */
    @Nullable
    static byte[] encodeActiveBackdropJpeg(boolean overHome) {
        RefractionBackdrop bg;
        if (overHome) {
            bg = homeLiveRefraction();
            if (bg == null) bg = homeWallpaperRefraction();
        } else {
            RefractionBackdrop ov = overlayRefraction;
            bg = (ov != null && !ov.sharp.isRecycled() && !ov.blur.isRecycled()) ? ov : null;
        }
        return encodeBackdropJpeg(bg);
    }

    @Nullable
    private static byte[] encodeBackdropJpeg(@Nullable RefractionBackdrop bg) {
        if (bg == null || bg.sharp.isRecycled()) return null;
        if (cachedJpegVersion == bg.version && cachedJpegBytes != null) {
            return cachedJpegBytes;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(24576);
            bg.sharp.compress(Bitmap.CompressFormat.JPEG, 76, out);
            byte[] bytes = out.toByteArray();
            cachedJpegBytes = bytes;
            cachedJpegVersion = bg.version;
            return bytes;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    static RefractionBackdrop buildRefractionBackdrop(@Nullable Bitmap src, int screenW, int screenH) {
        return buildRefractionBackdrop(src, screenW, screenH, false);
    }

    @Nullable
    static RefractionBackdrop buildRefractionBackdrop(@Nullable Bitmap src, int screenW, int screenH, boolean externalApp) {
        if (src == null || src.isRecycled()) return null;
        Bitmap swCopy = null;
        try {
            Bitmap readable = src;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.getConfig() == Bitmap.Config.HARDWARE) {
                swCopy = src.copy(Bitmap.Config.ARGB_8888, false);
                if (swCopy == null) return null;
                readable = swCopy;
            }
            int targetW = externalApp ? 480 : REFRACT_SHARP_W;
            int targetH = externalApp ? 270 : REFRACT_SHARP_H;
            Bitmap sharp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(sharp);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(readable, null, new Rect(0, 0, targetW, targetH), p);
            Bitmap blur = makeFastBlurred(sharp, REFRACT_BLUR_W, REFRACT_BLUR_H, true);
            if (blur == null) {
                sharp.recycle();
                return null;
            }
            return new RefractionBackdrop(sharp, blur, screenW, screenH, ++backdropVersionCounter, externalApp);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (swCopy != null && !swCopy.isRecycled()) swCopy.recycle();
        }
    }

    /** Runs a background refraction blur job on the shared single-threaded worker. */
    static void runOnRefractThread(Runnable task) {
        refractExec().execute(task);
    }

    /** Zero-allocation 2-pass separable box blur from {@code sharp} into pre-allocated {@code dst} (same dimensions). */
    static boolean blurInto(Bitmap sharp, Bitmap dst, int[] px, int[] tmp, int radius) {
        if (sharp == null || dst == null || sharp.isRecycled() || dst.isRecycled()) return false;
        int w = sharp.getWidth();
        int h = sharp.getHeight();
        if (w <= 0 || h <= 0 || dst.getWidth() != w || dst.getHeight() != h || px.length < w * h || tmp.length < w * h) {
            return false;
        }
        try {
            sharp.getPixels(px, 0, w, 0, 0, w, h);
            // Keep sharp untouched so screensaver LiquidClockView & optical rim refract crisp live Aerial video;
            // only apply 3-pass Gaussian blur into dst for the frosted card interior.
            for (int pass = 0; pass < 3; pass++) {
                boxBlurH(px, tmp, w, h, radius);
                boxBlurV(tmp, px, w, h, radius);
            }
            dst.setPixels(px, 0, w, 0, 0, w, h);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static RefractionBackdrop wrapBackdrop(Bitmap sharp, Bitmap blur, int screenW, int screenH) {
        return new RefractionBackdrop(sharp, blur, screenW, screenH, ++backdropVersionCounter, false);
    }

    /** Fast O(N) 3-pass fixed-point separable Gaussian-approximating blur (preserves caller's sharp bitmap untouched). */
    @Nullable
    static Bitmap makeFastBlurred(Bitmap sharp, int bw, int bh) {
        return makeFastBlurred(sharp, bw, bh, false);
    }

    /**
     * Fast O(N) 3-pass fixed-point separable Gaussian-approximating blur.
     * When {@code prefilterSharp} is true (external-app capture only, on a freshly allocated bitmap not yet
     * bound to any View), softens video macroblocks in uSharp; for Home/Screensaver keeps uSharp crisp.
     */
    @Nullable
    static Bitmap makeFastBlurred(Bitmap sharp, int bw, int bh, boolean prefilterSharp) {
        try {
            Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(sharp, null, new Rect(0, 0, bw, bh), new Paint(Paint.FILTER_BITMAP_FLAG));
            int sw = sharp.getWidth(), sh = sharp.getHeight();
            int needed = Math.max(bw * bh, (prefilterSharp && sharp.isMutable()) ? sw * sh : 0);
            int radius = Math.max(10, Math.round(bw * 22f / 640f));
            int sharpRadius = Math.max(4, Math.round(sw * 5.0f / 480f));
            synchronized (BLUR_SCRATCH_LOCK) {
                if (blurPxScratch == null || blurPxScratch.length < needed) {
                    blurPxScratch = new int[needed];
                    blurTmpScratch = new int[needed];
                }
                int[] px = blurPxScratch;
                int[] tmp = blurTmpScratch;
                if (prefilterSharp && sharp.isMutable() && sw > 0 && sh > 0) {
                    sharp.getPixels(px, 0, sw, 0, 0, sw, sh);
                    for (int pass = 0; pass < 2; pass++) {
                        boxBlurH(px, tmp, sw, sh, sharpRadius);
                        boxBlurV(tmp, px, sw, sh, sharpRadius);
                    }
                    sharp.setPixels(px, 0, sw, 0, 0, sw, sh);
                }
                out.getPixels(px, 0, bw, 0, 0, bw, bh);
                for (int pass = 0; pass < 3; pass++) {
                    boxBlurH(px, tmp, bw, bh, radius);
                    boxBlurV(tmp, px, bw, bh, radius);
                }
                out.setPixels(px, 0, bw, 0, 0, bw, bh);
            }
            return out;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static void boxBlurH(int[] src, int[] dst, int w, int h, int r) {
        final int span = r * 2 + 1;
        final int invSpan = (65536 + (span >> 1)) / span;
        final int wMax = w - 1;
        for (int y = 0; y < h; y++) {
            int off = y * w;
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int xIdx = i < 0 ? 0 : (i > wMax ? wMax : i);
                int c = src[off + xIdx];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[off + x] = 0xFF000000
                        | (((rs * invSpan) >>> 16) << 16)
                        | (((gs * invSpan) >>> 16) << 8)
                        | ((bs * invSpan) >>> 16);
                int outX = x - r;
                int inX = x + r + 1;
                int outC = src[off + (outX < 0 ? 0 : outX)];
                int inC = src[off + (inX > wMax ? wMax : inX)];
                rs += ((inC >> 16) & 0xFF) - ((outC >> 16) & 0xFF);
                gs += ((inC >> 8) & 0xFF) - ((outC >> 8) & 0xFF);
                bs += (inC & 0xFF) - (outC & 0xFF);
            }
        }
    }

    private static void boxBlurV(int[] src, int[] dst, int w, int h, int r) {
        final int span = r * 2 + 1;
        final int invSpan = (65536 + (span >> 1)) / span;
        final int hMax = h - 1;
        for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int yIdx = i < 0 ? 0 : (i > hMax ? hMax : i);
                int c = src[yIdx * w + x];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | (((rs * invSpan) >>> 16) << 16)
                        | (((gs * invSpan) >>> 16) << 8)
                        | ((bs * invSpan) >>> 16);
                int outY = y - r;
                int inY = y + r + 1;
                int outC = src[(outY < 0 ? 0 : outY) * w + x];
                int inC = src[(inY > hMax ? hMax : inY) * w + x];
                rs += ((inC >> 16) & 0xFF) - ((outC >> 16) & 0xFF);
                gs += ((inC >> 8) & 0xFF) - ((outC >> 8) & 0xFF);
                bs += (inC & 0xFF) - (outC & 0xFF);
            }
        }
    }

    /** Runs a lightweight background task (e.g. artwork decode) on the shared glass worker thread. */
    static void runBackground(Runnable task) {
        if (task != null) refractExec().execute(task);
    }

    /** Collects every PanelDrawable host view once at animation start so per-vsync invalidation is O(1). */
    static void collectLiquidPanels(@Nullable View v, java.util.List<View> out) {
        if (v == null) return;
        if (v.getBackground() instanceof PanelDrawable) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                collectLiquidPanels(vg.getChildAt(i), out);
            }
        }
    }

    /**
     * Toggles resting-coordinate refraction on collected {@link PanelDrawable} hosts so hardware-layered
     * modal enter animations rasterize the final screen-aligned glass refraction ONCE on Frame 0 with zero
     * visual pop when the hardware layer is released at animation end.
     */
    static void setLiquidRestingCoords(java.util.List<View> targets, boolean resting) {
        for (int i = 0, n = targets.size(); i < n; i++) {
            Drawable bg = targets.get(i).getBackground();
            if (bg instanceof PanelDrawable) {
                ((PanelDrawable) bg).useRestingCoords = resting;
            }
        }
    }

    /** Invalidates every PanelDrawable inside {@code root} during move/scale animations for live edge refraction. */
    static void invalidateLiquidPanels(@Nullable View v) {
        if (v == null) return;
        Drawable bg = v.getBackground();
        if (bg instanceof PanelDrawable) {
            bg.invalidateSelf();
            v.invalidate();
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                invalidateLiquidPanels(vg.getChildAt(i));
            }
        }
    }

    @Nullable
    static Bitmap overlayBackdrop() {
        Bitmap bmp = overlayBackdropBitmap;
        return (bmp != null && !bmp.isRecycled()) ? bmp : null;
    }

    static boolean hasOverlayBackdrop() {
        return overlayBackdrop() != null;
    }

    /** Releases the temporary GPU backdrop reference once no overlay Activity remains open. */
    static void clearOverlayBackdrop() {
        overlayBackdropBitmap = null;
        overlayRefraction = null;
    }

    /**
     * Attaches (or refreshes) a full-screen Android 17 AGSL blurred backdrop host behind an overlay panel
     * when floating over an external app. Returns the backdrop host View when live blur is active, or null.
     */
    @Nullable
    static FrameLayout attachOverlayBackdrop(FrameLayout root, @Nullable FrameLayout existing,
                                             float density, boolean reducedMotion) {
        return attachOverlayBackdrop(root, existing, density, reducedMotion, false, false);
    }

    @Nullable
    static FrameLayout attachOverlayBackdrop(FrameLayout root, @Nullable FrameLayout existing,
                                             float density, boolean reducedMotion, boolean deferFadeStart) {
        return attachOverlayBackdrop(root, existing, density, reducedMotion, deferFadeStart, false);
    }

    /**
     * Attaches (or refreshes) a 2-layer hardware-cached Android 17 AGSL blurred backdrop host behind an
     * overlay panel. When {@code startOpaque} is true (seamless cross-overlay handoff), displays at alpha=1
     * immediately on Frame 0 so switching Control Center <-> Recents <-> App Library <-> Settings never flashes.
     */
    @Nullable
    static FrameLayout attachOverlayBackdrop(FrameLayout root, @Nullable FrameLayout existing,
                                             float density, boolean reducedMotion,
                                             boolean deferFadeStart, boolean startOpaque) {
        Bitmap bmp = overlayBackdrop();
        if (bmp == null) return existing;
        FrameLayout host = existing;
        ImageView view;
        boolean fresh = host == null;
        if (fresh) {
            host = new FrameLayout(root.getContext());
            host.setClickable(false);
            host.setFocusable(false);
            host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view = new ImageView(root.getContext());
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setClickable(false);
            view.setFocusable(false);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            host.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(host, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            view = (host.getChildCount() > 0 && host.getChildAt(0) instanceof ImageView)
                    ? (ImageView) host.getChildAt(0) : null;
            if (view == null) return host;
        }
        view.setImageBitmap(bmp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(createBackdropBlurEffect(density, SHADE_BLUR_RADIUS_DP, true, Shader.TileMode.CLAMP));
            view.setLayerType(View.LAYER_TYPE_NONE, null);
            host.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        if (fresh) {
            if (reducedMotion || startOpaque) {
                host.setAlpha(1f);
            } else if (deferFadeStart) {
                host.setAlpha(0.002f);
            } else {
                host.setAlpha(0f);
                host.animate().alpha(1f).setDuration(195L).setInterpolator(Motion.easeOutExpo()).start();
            }
        }
        return host;
    }

    /** Starts the cached-FBO fade-in of an external-app overlay backdrop on the exact modal reveal vsync. */
    static void startOverlayBackdropFadeIn(@Nullable View view, boolean reducedMotion) {
        if (view == null) return;
        view.animate().cancel();
        if (reducedMotion) {
            view.setAlpha(1f);
        } else if (view.getAlpha() < 0.999f) {
            view.animate().alpha(1f).setDuration(195L).setInterpolator(Motion.easeOutExpo()).start();
        }
    }

    /** Releases GPU RenderEffect + hardware layer resources for an attached overlay backdrop host. */
    static void releaseOverlayBackdrop(@Nullable FrameLayout host) {
        if (host == null) return;
        host.animate().cancel();
        host.setLayerType(View.LAYER_TYPE_NONE, null);
        if (host.getChildCount() > 0 && host.getChildAt(0) instanceof ImageView) {
            ImageView iv = (ImageView) host.getChildAt(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) iv.setRenderEffect(null);
            iv.setImageDrawable(null);
        }
    }

    private static final float[] SUBMENU_BLUR_STEPS_DP = {0f, 3.5f, 7f, 10.5f, 14f, 17.5f, 20.5f, DIALOG_BLUR_RADIUS_DP};
    private static final java.util.WeakHashMap<ViewGroup, PanelBlurState> PANEL_BLUR_STATES = new java.util.WeakHashMap<>();
    private static final java.util.Map<Integer, RenderEffect> STEP_BLUR_CACHE = new java.util.HashMap<>();

    private static final class PanelBlurState {
        float fraction;
        boolean targetBlurred;
        boolean highlightWasVisible;
        int lastStep = -1;
        @Nullable
        ValueAnimator animator;
    }

    @Nullable
    private static RenderEffect blurEffectForStep(int step, float density) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || step <= 0) return null;
        if (step >= SUBMENU_BLUR_STEPS_DP.length - 1) {
            return createBackdropBlurEffect(density, DIALOG_BLUR_RADIUS_DP, true, Shader.TileMode.DECAL);
        }
        float radiusDp = SUBMENU_BLUR_STEPS_DP[step];
        float sigma = Math.max(2f, radiusDp * (density > 0f ? density : 2f) * SKIA_SIGMA_FACTOR);
        int key = Math.round(sigma * 10f);
        synchronized (STEP_BLUR_CACHE) {
            RenderEffect cached = STEP_BLUR_CACHE.get(key);
            if (cached != null) return cached;
            RenderEffect built = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.DECAL);
            if (STEP_BLUR_CACHE.size() >= 24) STEP_BLUR_CACHE.clear();
            STEP_BLUR_CACHE.put(key, built);
            return built;
        }
    }

    private static int blurStepForFraction(float f) {
        int maxStep = SUBMENU_BLUR_STEPS_DP.length - 1;
        if (f < 0.05f) return 0;
        if (f >= 0.96f) return maxStep;
        int idx = 1 + Math.round(f * (maxStep - 2));
        return Math.max(1, Math.min(maxStep - 1, idx));
    }

    private static void applyPanelBlurFraction(ViewGroup panel, @Nullable View highlight,
                                               PanelBlurState state, float f, float density) {
        state.fraction = f;
        float childAlpha = 1f - 0.22f * f;
        int step = blurStepForFraction(f);
        boolean stepChanged = (step != state.lastStep);
        RenderEffect effect = null;
        if (stepChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            state.lastStep = step;
            effect = blurEffectForStep(step, density);
        }
        for (int i = 0, n = panel.getChildCount(); i < n; i++) {
            View child = panel.getChildAt(i);
            child.setAlpha(childAlpha);
            if (stepChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                child.setRenderEffect(effect);
            }
        }
        if (highlight != null) {
            if (state.highlightWasVisible) {
                highlight.setAlpha(childAlpha);
            }
            if (stepChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                highlight.setRenderEffect(effect);
            }
        }
    }

    /**
     * Smoothly animates a parent modal panel's inner content into/out of native-resolution Skia optical
     * defocus (5 cached Gaussian stages + subtle depth dim) in lockstep with a sub-context menu or sub-dialog.
     * Leaves the parent panel's translucent Liquid Glass rim crisp and handles chained submenus with zero flash.
     */
    static void animatePanelContentBlur(@Nullable ViewGroup panel, @Nullable View highlight,
                                        float density, boolean blurred, boolean reducedMotion) {
        if (panel == null) return;
        PanelBlurState state = PANEL_BLUR_STATES.get(panel);
        if (state == null) {
            if (!blurred) return;
            state = new PanelBlurState();
            state.highlightWasVisible = highlight != null && highlight.getAlpha() > 0.01f;
            PANEL_BLUR_STATES.put(panel, state);
        } else if (state.targetBlurred == blurred) {
            if (state.animator != null && state.animator.isRunning()) return;
            if ((blurred && state.fraction >= 0.999f) || (!blurred && state.fraction <= 0.001f)) return;
        }
        state.targetBlurred = blurred;
        if (state.animator != null) {
            state.animator.cancel();
            state.animator = null;
        }
        float target = blurred ? 1f : 0f;
        if (reducedMotion || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            state.lastStep = -1;
            applyPanelBlurFraction(panel, highlight, state, target, density);
            if (!blurred) PANEL_BLUR_STATES.remove(panel);
            return;
        }
        final PanelBlurState st = state;
        float start = st.fraction;
        if (Math.abs(target - start) < 0.001f) {
            applyPanelBlurFraction(panel, highlight, st, target, density);
            if (!blurred) PANEL_BLUR_STATES.remove(panel);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(start, target);
        anim.setDuration(blurred ? 185L : 155L);
        anim.setInterpolator(blurred ? Motion.easeOutExpo() : Motion.easeInExit());
        anim.addUpdateListener(a -> applyPanelBlurFraction(panel, highlight, st, (float) a.getAnimatedValue(), density));
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (st.animator == anim) st.animator = null;
                if (!canceled) {
                    applyPanelBlurFraction(panel, highlight, st, target, density);
                    if (!blurred) PANEL_BLUR_STATES.remove(panel);
                }
            }
        });
        st.animator = anim;
        anim.start();
    }

    /** Immediately clears any animated content blur during hard modal teardown. */
    static void clearPanelContentBlurImmediate(@Nullable ViewGroup panel, @Nullable View highlight) {
        if (panel == null) return;
        PanelBlurState state = PANEL_BLUR_STATES.remove(panel);
        if (state != null && state.animator != null) {
            state.animator.cancel();
            state.animator = null;
        }
        for (int i = 0, n = panel.getChildCount(); i < n; i++) {
            View child = panel.getChildAt(i);
            child.setAlpha(1f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) child.setRenderEffect(null);
        }
        if (highlight != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) highlight.setRenderEffect(null);
        }
    }

    /**
     * Suppresses Android's native opaque-caster elevation shadow polygon, which otherwise shines straight
     * through translucent frosted-glass views as an inset dark rectangle.
     */
    static void disableOutlineShadow(@Nullable View v) {
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.setOutlineAmbientShadowColor(Color.TRANSPARENT);
            v.setOutlineSpotShadowColor(Color.TRANSPARENT);
        }
    }

    /** Clears Android's system Dialog window elevation shadow so translucent dialog panels show zero ghost box. */
    static void clearDialogShadow(@Nullable Window window) {
        if (window == null) return;
        window.setElevation(0f);
        disableOutlineShadow(window.getDecorView());
    }

    static void setDynamicSeedRgb(int rgb) {
        dynamicSeedRgb = (rgb == 0) ? DEFAULT_SEED_RGB : (rgb & 0x00FFFFFF);
    }

    static int dynamicSeedRgb() {
        return dynamicSeedRgb;
    }

    /**
     * TextView / TextClock that renders a true dark drop shadow first (shader cleared) and a luminous
     * top-lit vertical gradient second, preventing Android Skia from tinting the shadow with the white shader.
     */
    private interface LuminousTarget {
        void setSeparateShadow(float radiusPx, float dyPx, int color);
    }

    public static final class LuminousTextView extends TextView implements LuminousTarget {
        private float shadowRadiusPx, shadowDyPx;
        private int shadowColor;

        public LuminousTextView(Context context) { super(context); }
        public LuminousTextView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
        public LuminousTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @Override
        public void setSeparateShadow(float radiusPx, float dyPx, int color) {
            this.shadowRadiusPx = radiusPx;
            this.shadowDyPx = dyPx;
            this.shadowColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            TextPaint tp = getPaint();
            Shader s = tp.getShader();
            if (s != null && shadowRadiusPx > 0f) {
                tp.setShader(null);
                tp.setShadowLayer(shadowRadiusPx, 0f, shadowDyPx, shadowColor);
                super.onDraw(canvas);
                tp.clearShadowLayer();
                tp.setShader(s);
                super.onDraw(canvas);
            } else {
                super.onDraw(canvas);
            }
        }
    }

    public static final class LuminousTextClock extends TextClock implements LuminousTarget {
        private float shadowRadiusPx, shadowDyPx;
        private int shadowColor;

        public LuminousTextClock(Context context) { super(context); }
        public LuminousTextClock(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
        public LuminousTextClock(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @Override
        public void setSeparateShadow(float radiusPx, float dyPx, int color) {
            this.shadowRadiusPx = radiusPx;
            this.shadowDyPx = dyPx;
            this.shadowColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            TextPaint tp = getPaint();
            Shader s = tp.getShader();
            if (s != null && shadowRadiusPx > 0f) {
                tp.setShader(null);
                tp.setShadowLayer(shadowRadiusPx, 0f, shadowDyPx, shadowColor);
                super.onDraw(canvas);
                tp.clearShadowLayer();
                tp.setShader(s);
                super.onDraw(canvas);
            } else {
                super.onDraw(canvas);
            }
        }
    }

    /**
     * Applies an Apple tvOS / visionOS top-lit luminous pearl gradient harmonized subtly with the active
     * wallpaper seed. On {@link LuminousTextView}/{@link LuminousTextClock}, also draws a clean separate
     * dark depth shadow underneath (never white-tinted by the shader).
     */
    static void applyLuminousTitle(@Nullable TextView tv, float density, boolean displayHero) {
        if (tv == null) return;
        float d = density > 0f ? density : 2f;
        float top = tv.getPaddingTop();
        float span = Math.max(tv.getTextSize() * 1.15f, 16f * d);
        int softTint = mixRgb(0xD8E3F6, tonalRimTint(dynamicSeedRgb), 0.42f);
        int bottomColor = 0xFF000000 | softTint;
        tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        tv.getPaint().clearShadowLayer();
        tv.getPaint().setShader(new LinearGradient(
                0f, top, 0f, top + span,
                new int[]{0xFFFFFFFF, 0xFFF3F7FE, bottomColor},
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
        if (tv instanceof LuminousTarget) {
            if (displayHero) {
                ((LuminousTarget) tv).setSeparateShadow(2.0f * d, 0.8f * d, 0x58000000);
            } else {
                ((LuminousTarget) tv).setSeparateShadow(0f, 0f, Color.TRANSPARENT);
            }
        } else if (tv instanceof LiquidClockView) {
            if (displayHero) {
                ((LiquidClockView) tv).setFallbackShadow(2.0f * d, 0.8f * d, 0x58000000);
            } else {
                ((LiquidClockView) tv).setFallbackShadow(0f, 0f, Color.TRANSPARENT);
            }
        }
        tv.invalidate();
    }

    /**
     * Adds a tight, natural optical contour shadow for floating wallpaper text whose Gaussian tail
     * decays to zero inside the glyph ascent/descent box (never clipping into a rectangular block).
     */
    static void applyReadableShadow(@Nullable TextView tv, float density, boolean small) {
        if (tv == null) return;
        float d = density > 0f ? density : 2f;
        tv.getPaint().setShader(null);
        tv.setShadowLayer(small ? 1.8f * d : 2.2f * d, 0f, small ? 0.7f * d : 0.9f * d, 0x58000000);
    }

    /** Converts AOSP blur radius in dp to a hardware-friendly Skia RenderEffect sigma in px. */
    static float backdropSigmaPx(float density, float radiusDp) {
        float d = density > 0f ? density : 2f;
        float sigma = radiusDp * d * SKIA_SIGMA_FACTOR;
        return clampFloat(sigma, 12f, 38f);
    }

    /**
     * Builds (or returns cached) Android 17 style vibrant frosted-glass RenderEffect chain
     * (Blur -> AGSL Vibrancy/Tint/IGN Grain), avoiding repeated RuntimeShader compilation on the UI thread.
     */
    @Nullable
    static RenderEffect createBackdropBlurEffect(float density, float radiusDp, boolean tintAndDim,
                                                 Shader.TileMode tileMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        int seed = dynamicSeedRgb;
        float sigma = backdropSigmaPx(density, radiusDp);
        long key = (((long) Math.round(sigma * 10f)) << 8)
                | ((long) tileMode.ordinal() << 2)
                | (tintAndDim ? 1L : 0L);
        synchronized (EFFECT_CACHE) {
            if (effectCacheSeed != seed) {
                EFFECT_CACHE.clear();
                effectCacheSeed = seed;
            }
            RenderEffect cached = EFFECT_CACHE.get(key);
            if (cached != null) return cached;
        }
        RenderEffect blur = RenderEffect.createBlurEffect(sigma, sigma, tileMode);
        RenderEffect built = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader shader = new RuntimeShader(BACKDROP_AGSL);
                int tint = tonalAccent800(seed);
                shader.setFloatUniform("uSaturation", tintAndDim ? 1.26f : 1.18f);
                shader.setFloatUniform("uLift", tintAndDim ? 0.012f : 0.008f);
                shader.setFloatUniform("uDim", tintAndDim ? 0.86f : 0.92f);
                shader.setFloatUniform("uTint",
                        ((tint >> 16) & 0xFF) / 255f,
                        ((tint >> 8) & 0xFF) / 255f,
                        (tint & 0xFF) / 255f);
                shader.setFloatUniform("uTintMix", tintAndDim ? 0.14f : 0.06f);
                shader.setFloatUniform("uGrain", 0.014f);
                RenderEffect post = RenderEffect.createRuntimeShaderEffect(shader, "content");
                built = RenderEffect.createChainEffect(post, blur);
            } catch (RuntimeException ignored) {
                // Fall back to vibrancy ColorMatrix chain below.
            }
        }
        if (built == null) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(tintAndDim ? 1.24f : 1.16f);
            RenderEffect sat = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
            built = RenderEffect.createChainEffect(sat, blur);
        }
        synchronized (EFFECT_CACHE) {
            if (effectCacheSeed == seed) {
                if (EFFECT_CACHE.size() >= 8) EFFECT_CACHE.clear();
                EFFECT_CACHE.put(key, built);
            }
        }
        return built;
    }

    /** Applies AOSP 34dp SurfaceFlinger window blur behind overlays floating over external apps (API 31+). */
    static void applyWindowBlur(@Nullable Window window, float density, float blurDp, float dimAmount) {
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        lp.dimAmount = dimAmount;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int radiusPx = Math.max(16, Math.min(80, Math.round(blurDp * density)));
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(radiusPx);
            window.setBackgroundBlurRadius(radiusPx);
        }
        window.setAttributes(lp);
    }

    /** Strips cross-window SurfaceFlinger blur and clears FLAG_DIM_BEHIND when dimAmount <= 0 (prevents double-dimming). */
    static void clearWindowBlur(@Nullable Window window, float dimAmount) {
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        if (dimAmount > 0.001f) {
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = dimAmount;
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(0);
            window.setBackgroundBlurRadius(0);
        }
        window.setAttributes(lp);
    }

    /** Normalizes window dim when AGSL blur backdrop is already active (zero double-dimming over Home or apps). */
    static void setWindowDimOnly(@Nullable Window window, float dimAmount) {
        clearWindowBlur(window, dimAmount);
    }

    /**
     * Extracts a balanced atmospheric Material You seed hue (0xRRGGBB) from sampled ARGB pixels.
     * Pure integer/float math (JVM unit-testable with zero Android framework calls).
     */
    static int extractSeedRgb(@Nullable int[] argbPixels) {
        if (argbPixels == null || argbPixels.length == 0) return DEFAULT_SEED_RGB;
        float[] weight = new float[12];
        float[] sumR = new float[12];
        float[] sumG = new float[12];
        float[] sumB = new float[12];
        float[] hsv = new float[3];
        float total = 0f;
        int stride = Math.max(1, argbPixels.length / 1800);
        for (int i = 0; i < argbPixels.length; i += stride) {
            int p = argbPixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            rgbToHsv(r, g, b, hsv);
            if (hsv[1] < 0.14f || hsv[2] < 0.14f || hsv[2] > 0.96f) continue;
            // Favor mid-tone atmospheric colors over clipped specular highlights or crushed blacks.
            float lumWeight = 1f - Math.abs(hsv[2] - 0.52f) * 1.15f;
            float w = hsv[1] * Math.max(0.15f, lumWeight);
            int bin = ((int) (hsv[0] / 30f)) % 12;
            if (bin < 0) bin += 12;
            weight[bin] += w;
            total += w;
            sumR[bin] += r * w;
            sumG[bin] += g * w;
            sumB[bin] += b * w;
        }
        if (total < 1.5f) return DEFAULT_SEED_RGB;
        int best = 0;
        for (int i = 1; i < 12; i++) {
            if (weight[i] > weight[best]) best = i;
        }
        if (weight[best] <= 0.001f) return DEFAULT_SEED_RGB;
        int r = clampByte(sumR[best] / weight[best]);
        int g = clampByte(sumG[best] / weight[best]);
        int b = clampByte(sumB[best] / weight[best]);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Maps any seed RGB into AOSP Material 3 dark tonal accent1_800 (deep, rich, never neon).
     * Pure JVM-testable math.
     */
    static int tonalAccent800(int seedRgb) {
        int rgb = seedRgb & 0x00FFFFFF;
        float[] hsv = new float[3];
        rgbToHsv((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsv);
        float sat = clampFloat(hsv[1] * 0.72f, 0.20f, 0.44f);
        float val = clampFloat(0.22f + hsv[2] * 0.09f, 0.22f, 0.31f);
        return hsvToRgb(hsv[0], sat, val);
    }

    /** Subtle light-catching specular tint derived from the dynamic wallpaper seed for glass rim bounce. */
    static int tonalRimTint(int seedRgb) {
        int rgb = seedRgb & 0x00FFFFFF;
        float[] hsv = new float[3];
        rgbToHsv((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsv);
        float sat = clampFloat(hsv[1] * 0.38f, 0.08f, 0.28f);
        return hsvToRgb(hsv[0], sat, 0.92f);
    }

    /**
     * Refines raw vivid tile/app colors (such as neon green #53FC18) into an optical glass prism highlight:
     * caps garish neon saturation, preserves recognizable brand hue, lifts muddy dark colors, and mixes
     * gently toward cool crystal light so focus rims look like refracted glass instead of neon paint.
     */
    static int refineFocusAccent(int argb) {
        int rgb = argb & 0x00FFFFFF;
        if (rgb == 0) return 0xE8EEF8;
        float[] hsv = new float[3];
        rgbToHsv((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsv);
        if (hsv[1] < 0.08f) {
            return 0xE8EEF8;
        }
        hsv[1] = clampFloat(hsv[1] * 0.74f, 0.18f, 0.62f);
        hsv[2] = clampFloat(0.68f + hsv[2] * 0.24f, 0.68f, 0.94f);
        int refined = hsvToRgb(hsv[0], hsv[1], hsv[2]);
        return mixRgb(refined, 0xF0F5FF, 0.24f);
    }

    /**
     * Computes AOSP ShadeColors-inspired 3-stop translucent frosted-glass panel gradient colors.
     * Combines AOSP's neutral #404040 luminous lift, deep lStar=4 neutral base (#0E0F12), and dynamic
     * wallpaper accent1_800 tinting.
     */
    static int[] panelGlassColors(int seedRgb, boolean smoked) {
        int accent800 = tonalAccent800(seedRgb);
        int topBase = mixRgb(0x131720, accent800, 0.36f);
        int midBase = mixRgb(0x0B0E15, accent800, 0.28f);
        int botBase = mixRgb(0x07090E, accent800, 0.22f);
        if (smoked) {
            return new int[]{
                    (0xA4 << 24) | topBase,
                    (0xB2 << 24) | midBase,
                    (0xC0 << 24) | botBase
            };
        }
        return new int[]{
                (0x5C << 24) | topBase,
                (0x6A << 24) | midBase,
                (0x78 << 24) | botBase
        };
    }

    /** Translucent Android 17 smoked-luminous tint layered over the live vibrant blurred Home dock & pills. */
    static int[] dockGlassColors(int seedRgb) {
        int accent800 = tonalAccent800(seedRgb);
        int top = mixRgb(0x2A303B, accent800, 0.36f);
        int mid = mixRgb(0x161A22, accent800, 0.28f);
        int bot = mixRgb(0x0E1218, accent800, 0.30f);
        return new int[]{
                (0x4E << 24) | top,
                (0x3E << 24) | mid,
                (0x5E << 24) | bot
        };
    }

    /** Recessed translucent smoked-glass pocket for Home folder tiles so blurred dock glass shines through. */
    static int[] folderPocketColors(int seedRgb) {
        int accent800 = tonalAccent800(seedRgb);
        int top = mixRgb(0x202633, accent800, 0.30f);
        int mid = mixRgb(0x131720, accent800, 0.24f);
        int bot = mixRgb(0x0B0E15, accent800, 0.22f);
        return new int[]{
                (0x78 << 24) | top,
                (0x88 << 24) | mid,
                (0x9A << 24) | bot
        };
    }

    /** Shared 128x128 uncorrelated monochrome noise texture (64 KB RAM) for seamless etched-glass micro-grain. */
    static BitmapShader noiseShader() {
        if (sharedNoiseShader == null) {
            final int size = 128;
            int[] pixels = new int[size * size];
            int state = 0x5A17C3E9;
            for (int i = 0; i < pixels.length; i++) {
                state ^= (state << 13);
                state ^= (state >>> 17);
                state ^= (state << 5);
                int v = state & 0xFF;
                pixels[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
            sharedNoiseBitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            sharedNoiseShader = new BitmapShader(sharedNoiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        return sharedNoiseShader;
    }

    /** Creates a preconfigured Paint drawing subtle etched frosted-glass micro-grain at the requested alpha (0..255). */
    static Paint createNoisePaint(int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(noiseShader());
        p.setAlpha(alpha);
        return p;
    }

    /** Creates the unified Android 17 frosted-glass panel Drawable (zero allocations in draw()). */
    static Drawable createPanelDrawable(float density, float cornerRadiusDp, boolean smoked) {
        return new PanelDrawable(null, density, cornerRadiusDp * density, smoked, false);
    }

    /** Applies the Android 17 Liquid Glass panel Drawable (with live AGSL edge refraction on API 33+). */
    static void applyPanelSurface(View panel, float density, float cornerRadiusDp, boolean smoked, boolean clipToOutline) {
        applyPanelSurface(panel, density, cornerRadiusDp, smoked, clipToOutline, false);
    }

    /** Applies the Android 17 Liquid Glass panel Drawable, optionally blurring a sharp live backdrop (e.g. screensaver video). */
    static void applyPanelSurface(View panel, float density, float cornerRadiusDp, boolean smoked,
                                  boolean clipToOutline, boolean overSharpBackdrop) {
        float radiusPx = cornerRadiusDp * density;
        Drawable existing = panel.getBackground();
        if (existing instanceof PanelDrawable) {
            ((PanelDrawable) existing).updateConfig(radiusPx, smoked, overSharpBackdrop);
        } else {
            panel.setBackground(new PanelDrawable(panel, density, radiusPx, smoked, overSharpBackdrop));
        }
        panel.setElevation(0f);
        panel.setTranslationZ(0f);
        disableOutlineShadow(panel);
        if (clipToOutline) {
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            panel.setClipToOutline(true);
        } else if (panel.getClipToOutline()) {
            panel.setClipToOutline(false);
        }
    }

    /** Updates a panel's smoked fallback state in-place without recreating drawables or leaking listeners. */
    static void setPanelSmoked(@Nullable View panel, boolean smoked) {
        if (panel == null) return;
        Drawable bg = panel.getBackground();
        if (bg instanceof PanelDrawable) {
            ((PanelDrawable) bg).setSmoked(smoked);
            panel.invalidate();
        }
    }

    /** Sets (or clears) a live per-panel refraction backdrop (e.g. live Aerial video frames on screensaver). */
    static void setPanelBackdrop(@Nullable View panel, @Nullable RefractionBackdrop backdrop) {
        if (panel == null) return;
        Drawable bg = panel.getBackground();
        if (bg instanceof PanelDrawable) {
            ((PanelDrawable) bg).setCustomBackdrop(backdrop);
            panel.invalidate();
        }
    }

    /** Zero-allocation-per-frame Android 17 Liquid Glass panel background with live AGSL background refraction. */
    private static final class PanelDrawable extends Drawable {
        @Nullable
        private final WeakReference<View> hostRef;
        private final float density;
        private float cornerRadiusPx;
        private boolean smoked;
        private boolean overSharpBackdrop;
        @Nullable
        private volatile RefractionBackdrop customBackdrop;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerShadowBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF rect = new RectF();
        private final RectF insetRect = new RectF();
        private final int[] winLoc = new int[2];
        private final int[] scrLoc = new int[2];
        private final Matrix tempMatrix = new Matrix();
        private final float[] matrixVals = new float[9];
        @Nullable
        private RuntimeShader liquidShader;
        private boolean liquidShaderFailed;
        private int boundBackdropVersion = -1;
        private int lastRimSeed = -1;
        private float cachedRimR = 1f, cachedRimG = 1f, cachedRimB = 1f;
        private int liquidAlpha = 255;
        private int lastW = -1;
        private int lastH = -1;
        private int lastSeed = -1;
        boolean useRestingCoords;

        PanelDrawable(@Nullable View host, float density, float cornerRadiusPx, boolean smoked, boolean overSharpBackdrop) {
            this.hostRef = host != null ? new WeakReference<>(host) : null;
            this.density = density;
            this.cornerRadiusPx = cornerRadiusPx;
            this.smoked = smoked;
            this.overSharpBackdrop = overSharpBackdrop;
            if (host != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.liquidShader = acquireLiquidShader();
                if (this.liquidShader == null) this.liquidShaderFailed = true;
                host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {
                        if (liquidShader == null && !liquidShaderFailed) {
                            liquidShader = acquireLiquidShader();
                            boundBackdropVersion = -1;
                            if (liquidShader == null) liquidShaderFailed = true;
                        }
                    }
                    @Override public void onViewDetachedFromWindow(View v) {
                        RuntimeShader s = liquidShader;
                        if (s != null) {
                            liquidShader = null;
                            boundBackdropVersion = -1;
                            liquidPaint.setShader(null);
                            recycleLiquidShader(s);
                        }
                    }
                });
            }

            outerShadowBorder.setStyle(Paint.Style.STROKE);
            outerShadowBorder.setStrokeWidth(0.85f * density);
            outerShadowBorder.setColor(0x36000000);

            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(1.25f * density);
        }

        void setCustomBackdrop(@Nullable RefractionBackdrop backdrop) {
            this.customBackdrop = backdrop;
            invalidateSelf();
        }

        void setSmoked(boolean smoked) {
            if (this.smoked == smoked) return;
            this.smoked = smoked;
            this.lastSeed = -1;
            invalidateSelf();
        }

        void updateConfig(float radiusPx, boolean smoked, boolean overSharpBackdrop) {
            this.cornerRadiusPx = radiusPx;
            this.smoked = smoked;
            this.overSharpBackdrop = overSharpBackdrop;
            this.lastSeed = -1;
            invalidateSelf();
        }

        private void ensureShaders(RectF r) {
            int w = Math.round(r.width());
            int h = Math.round(r.height());
            int seed = dynamicSeedRgb;
            if (w == lastW && h == lastH && seed == lastSeed) return;
            lastW = w;
            lastH = h;
            lastSeed = seed;

            int[] cols = panelGlassColors(seed, smoked);
            // Bake the top 18% white specular caustic directly into the panel's single vertical gradient
            // (eliminates a second full-panel clipRect+drawRoundRect pass and 1.54M px invisible micro-noise pass).
            int topWithCaustic = blendOverWhite(cols[0], 0x12);
            int stop18 = lerpArgb(cols[0], cols[1], 0.18f / 0.48f);
            fillPaint.setShader(new LinearGradient(
                    r.left, r.top, r.left, r.bottom,
                    new int[]{topWithCaustic, stop18, cols[1], cols[2]},
                    new float[]{0f, 0.18f, 0.48f, 1f}, Shader.TileMode.CLAMP));

            int bounce = tonalRimTint(seed);
            rimPaint.setShader(new LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    new int[]{0x76FFFFFF, 0x22FFFFFF, 0x18FFFFFF, (0x44 << 24) | bounce},
                    new float[]{0f, 0.34f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        }

        private static int blendOverWhite(int dstArgb, int whiteAlpha) {
            float sa = whiteAlpha / 255f;
            float da = ((dstArgb >>> 24) & 0xFF) / 255f;
            float oa = sa + da * (1f - sa);
            if (oa <= 0.001f) return 0;
            float inv = 1f / oa;
            int r = Math.min(255, Math.round((255f * sa + ((dstArgb >> 16) & 0xFF) * da * (1f - sa)) * inv));
            int g = Math.min(255, Math.round((255f * sa + ((dstArgb >> 8) & 0xFF) * da * (1f - sa)) * inv));
            int b = Math.min(255, Math.round((255f * sa + (dstArgb & 0xFF) * da * (1f - sa)) * inv));
            return (Math.min(255, Math.round(oa * 255f)) << 24) | (r << 16) | (g << 8) | b;
        }

        private static int lerpArgb(int a, int b, float t) {
            int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return (Math.round(aa + (ba - aa) * t) << 24)
                    | (Math.round(ar + (br - ar) * t) << 16)
                    | (Math.round(ag + (bg - ag) * t) << 8)
                    | Math.round(ab + (bb - ab) * t);
        }

        private boolean drawLiquidGlass(Canvas canvas, RectF r, float rad, boolean interiorBlurOnly) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || liquidShaderFailed) return false;
            View host = hostRef != null ? hostRef.get() : null;
            if (host == null || !host.isAttachedToWindow()) return false;
            // Smoked panels over DRM/external apps must NEVER fall back to Home's wallpaper colors when
            // overlayRefraction is unavailable, whereas nested smoked sub-windows opened over Home
            // (context menus, folder pickers, settings sub-dialogs, QR setup) still get full 3D Liquid Glass rims.
            RefractionBackdrop bg;
            if (customBackdrop != null) {
                bg = customBackdrop;
            } else if (smoked) {
                RefractionBackdrop ov = overlayRefraction;
                if (ov != null && !ov.sharp.isRecycled() && !ov.blur.isRecycled()) {
                    bg = ov;
                } else if (MainActivity.isHomeVisible()) {
                    bg = activeRefractionBackdrop();
                } else {
                    bg = null;
                }
            } else {
                bg = activeRefractionBackdrop();
            }
            if (bg == null || bg.sharp.isRecycled() || bg.blur.isRecycled()) return false;

            if (liquidShader == null) {
                liquidShader = acquireLiquidShader();
                boundBackdropVersion = -1;
                if (liquidShader == null) {
                    liquidShaderFailed = true;
                    return false;
                }
            }

            View rootView = host.getRootView();
            rootView.getLocationOnScreen(scrLoc);
            int winOffX = scrLoc[0];
            int winOffY = scrLoc[1];
            if (winOffX == 0 && winOffY == 0
                    && rootView.getLayoutParams() instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) rootView.getLayoutParams();
                int rw = rootView.getWidth();
                int rh = rootView.getHeight();
                int sw = bg.screenW;
                int sh = bg.screenH;
                if (rw > 0 && rh > 0 && sw > 0 && sh > 0 && (rw < sw - 2 || rh < sh - 2)) {
                    boolean subWindow = wlp.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW;
                    int rawGravity = wlp.gravity == Gravity.NO_GRAVITY
                            ? (subWindow ? (Gravity.TOP | Gravity.START) : Gravity.CENTER)
                            : wlp.gravity;
                    int g = Gravity.getAbsoluteGravity(rawGravity, rootView.getLayoutDirection());
                    int hg = g & Gravity.HORIZONTAL_GRAVITY_MASK;
                    int vg = g & Gravity.VERTICAL_GRAVITY_MASK;
                    if (hg == Gravity.RIGHT) {
                        winOffX = sw - rw - wlp.x;
                    } else if (hg == Gravity.CENTER_HORIZONTAL || (!subWindow && hg == 0)) {
                        winOffX = (sw - rw) / 2 + wlp.x;
                    } else {
                        winOffX = wlp.x;
                    }
                    if (vg == Gravity.BOTTOM) {
                        winOffY = sh - rh - wlp.y;
                    } else if (vg == Gravity.CENTER_VERTICAL || (!subWindow && vg == 0)) {
                        winOffY = (sh - rh) / 2 + wlp.y;
                    } else {
                        winOffY = wlp.y;
                    }
                }
            }
            float originX;
            float originY;
            float scaleX;
            float scaleY;
            if (useRestingCoords) {
                float rx = winOffX;
                float ry = winOffY;
                View cur = host;
                while (cur != null && cur != rootView) {
                    rx += cur.getLeft() - cur.getScrollX();
                    ry += cur.getTop() - cur.getScrollY();
                    ViewParent p = cur.getParent();
                    cur = p instanceof View ? (View) p : null;
                }
                originX = rx;
                originY = ry;
                scaleX = 1f;
                scaleY = 1f;
            } else {
                tempMatrix.reset();
                host.transformMatrixToGlobal(tempMatrix);
                tempMatrix.getValues(matrixVals);
                originX = matrixVals[Matrix.MTRANS_X] + winOffX;
                originY = matrixVals[Matrix.MTRANS_Y] + winOffY;
                scaleX = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_X]));
                scaleY = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_Y]));
            }

            fillPaint.setDither(true);
            rimPaint.setDither(true);
            float w = r.width();
            float h = r.height();
            boolean useOpticalLiquid = homeLiquidOpticsEnabled && (!bg.externalApp || overSharpBackdrop);
            float bezel = Math.min((useOpticalLiquid ? 24f : 18f) * density, Math.min(w, h) * 0.24f);
            float refract = Math.min((useOpticalLiquid ? 46f : 28f) * density, bezel * 1.95f);
            float chromatic = (useOpticalLiquid ? 4.4f : 2.4f) * density;
            int seed = dynamicSeedRgb;
            if (seed != lastRimSeed) {
                int rimTint = tonalRimTint(seed);
                cachedRimR = ((rimTint >> 16) & 0xFF) / 255f;
                cachedRimG = ((rimTint >> 8) & 0xFF) / 255f;
                cachedRimB = (rimTint & 0xFF) / 255f;
                lastRimSeed = seed;
            }

            if (boundBackdropVersion != bg.version) {
                liquidShader.setInputShader("uSharp", bg.sharpShader);
                liquidShader.setInputShader("uBlur", bg.blurShader);
                boundBackdropVersion = bg.version;
            }
            liquidShader.setFloatUniform("uPanelSize", w, h);
            liquidShader.setFloatUniform("uScreenOrigin", originX, originY);
            liquidShader.setFloatUniform("uScreenScale", scaleX, scaleY);
            liquidShader.setFloatUniform("uRadius", rad);
            liquidShader.setFloatUniform("uBezel", bezel);
            liquidShader.setFloatUniform("uRefract", refract);
            liquidShader.setFloatUniform("uChromatic", chromatic);
            liquidShader.setFloatUniform("uEdgeClarity",
                    interiorBlurOnly ? 0.0f : (useOpticalLiquid ? 0.88f : 0.0f));
            liquidShader.setFloatUniform("uInteriorFill", interiorBlurOnly ? 1.0f : 0.0f);
            liquidShader.setFloatUniform("uLiquidMode", useOpticalLiquid ? 1.0f : 0.0f);
            liquidShader.setFloatUniform("uRimTint", cachedRimR, cachedRimG, cachedRimB);

            liquidPaint.setShader(null);
            liquidPaint.setShader(liquidShader);
            liquidPaint.setAlpha(liquidAlpha);
            if (interiorBlurOnly) {
                canvas.drawRect(r, liquidPaint);
            } else {
                drawBezelRing(canvas, r.left, r.top, r.right, r.bottom, rad, bezel, liquidPaint);
            }
            return true;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            rect.set(b);
            float rad = Math.min(cornerRadiusPx, Math.min(rect.width(), rect.height()) * 0.5f);

            // For floating panels over sharp video/wallpapers (e.g. screensaver Now Playing card),
            // paint the silky blurred backdrop inside the rounded card before Layer 1 glass tint.
            if (overSharpBackdrop) {
                drawLiquidGlass(canvas, rect, rad, true);
            }

            // Layer 1: Single-pass Material 3 Expressive translucent glass body (with baked top specular caustic).
            ensureShaders(rect);
            canvas.drawRoundRect(rect, rad, rad, fillPaint);

            // Layer 2: Real-time Liquid Glass rim refraction + chromatic dispersion + Fresnel specular!
            if (drawLiquidGlass(canvas, rect, rad, false)) return;

            float halfOuter = outerShadowBorder.getStrokeWidth() * 0.5f;
            insetRect.set(rect.left + halfOuter, rect.top + halfOuter, rect.right - halfOuter, rect.bottom - halfOuter);
            canvas.drawRoundRect(insetRect, Math.max(0f, rad - halfOuter), Math.max(0f, rad - halfOuter), outerShadowBorder);

            float halfRim = rimPaint.getStrokeWidth() * 0.5f + halfOuter;
            insetRect.set(rect.left + halfRim, rect.top + halfRim, rect.right - halfRim, rect.bottom - halfRim);
            canvas.drawRoundRect(insetRect, Math.max(0f, rad - halfRim), Math.max(0f, rad - halfRim), rimPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            liquidAlpha = alpha;
            fillPaint.setAlpha(alpha);
            rimPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---- Pure JVM-safe RGB/HSV utilities (zero android.graphics.Color calls) ---------------------

    static int mixRgb(int a, int b, float t) {
        float k = clampFloat(t, 0f, 1f);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = clampByte(ar + (br - ar) * k);
        int g = clampByte(ag + (bg - ag) * k);
        int bl = clampByte(ab + (bb - ab) * k);
        return (r << 16) | (g << 8) | bl;
    }

    static void rgbToHsv(int r, int g, int b, float[] out) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h;
        if (delta <= 1e-5f) {
            h = 0f;
        } else if (max == rf) {
            h = 60f * (((gf - bf) / delta) % 6f);
        } else if (max == gf) {
            h = 60f * (((bf - rf) / delta) + 2f);
        } else {
            h = 60f * (((rf - gf) / delta) + 4f);
        }
        if (h < 0f) h += 360f;
        float s = max <= 1e-5f ? 0f : delta / max;
        out[0] = h;
        out[1] = s;
        out[2] = max;
    }

    static int hsvToRgb(float h, float s, float v) {
        float hh = ((h % 360f) + 360f) % 360f;
        float c = v * s;
        float x = c * (1f - Math.abs(((hh / 60f) % 2f) - 1f));
        float m = v - c;
        float rf, gf, bf;
        if (hh < 60f) {
            rf = c; gf = x; bf = 0f;
        } else if (hh < 120f) {
            rf = x; gf = c; bf = 0f;
        } else if (hh < 180f) {
            rf = 0f; gf = c; bf = x;
        } else if (hh < 240f) {
            rf = 0f; gf = x; bf = c;
        } else if (hh < 300f) {
            rf = x; gf = 0f; bf = c;
        } else {
            rf = c; gf = 0f; bf = x;
        }
        int r = clampByte((rf + m) * 255f);
        int g = clampByte((gf + m) * 255f);
        int b = clampByte((bf + m) * 255f);
        return (r << 16) | (g << 8) | b;
    }

    private static float clampFloat(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int clampByte(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
