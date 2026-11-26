package io.github.luyzfernando08.sleeptimer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Self-contained Apple tvOS / Android 17 Expressive frosted-glass UI kit and living wobbly
 * Fresnel focus ring shared across all CleanYourTV modular add-ons (zero external dependencies).
 */
final class GlassKit {

    static final float RADIUS_CAPSULE = -1f;
    static final float RADIUS_AUTO = -2f;
    static final float INSET_DP = 24f;
    private static final float KAPPA = 0.55228475f;
    private static final int DEFAULT_SEED_RGB = 0x223050;

    private static final Interpolator EASE_OUT_EXPO = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final Interpolator SPRING_POP = new PathInterpolator(0.20f, 1.12f, 0.34f, 1f);
    private static final Interpolator EASE_IN_EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);
    private static final Interpolator TACTILE_FOCUS = new OvershootInterpolator(1.05f);
    private static final Interpolator SMOOTH_DECEL = new DecelerateInterpolator(1.6f);

    static final String EXTRA_BACKDROP_JPEG = "com.gathin.tvhome.BACKDROP_JPEG";
    static final String EXTRA_OVER_HOME = "com.gathin.tvhome.OVER_HOME";
    static final String EXTRA_INHERIT_BACKDROP = "com.gathin.tvhome.INHERIT_BACKDROP";
    static final String ACTION_ADDON_DISMISSING = "com.gathin.tvhome.ACTION_ADDON_DISMISSING";

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

    private static final String LIQUID_GLASS_PANEL_AGSL =
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
                    + "uniform float uWobble;\n"
                    + "uniform float uPhase;\n"
                    + "half3 sampleBlur(float2 coord) {\n"
                    + "    half3 c = uBlur.eval(coord).rgb * half(0.36);\n"
                    + "    c += uBlur.eval(coord + float2( 5.25,  2.15)).rgb * half(0.16);\n"
                    + "    c += uBlur.eval(coord + float2(-5.25, -2.15)).rgb * half(0.16);\n"
                    + "    c += uBlur.eval(coord + float2(-2.15,  5.25)).rgb * half(0.16);\n"
                    + "    c += uBlur.eval(coord + float2( 2.15, -5.25)).rgb * half(0.16);\n"
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
                    + "half3 applyVibrancyAndAdapt(half3 col, half vibBoost, half dither) {\n"
                    + "    half luma = dot(col, half3(0.2126, 0.7152, 0.0722));\n"
                    + "    half satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));\n"
                    + "    half satRoom = half(1.0) - smoothstep(half(0.18), half(0.82), satNow);\n"
                    + "    half hlProtect = half(1.0) - smoothstep(half(0.70), half(0.96), luma);\n"
                    + "    half vibAmt = half(1.0) + vibBoost * mix(half(0.28), half(1.0), satRoom * hlProtect);\n"
                    + "    half3 v = clamp(mix(half3(luma), col, vibAmt), half3(0.0), half3(1.0));\n"
                    + "    half hlTone = smoothstep(half(0.68), half(0.98), luma);\n"
                    + "    v = mix(v, v * half(0.84), hlTone * half(0.35));\n"
                    + "    return clamp(v + half3(dither), half3(0.0), half3(1.0));\n"
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
                    + "        baseBlur = applyVibrancyAndAdapt(baseBlur * half(0.94), half(0.24), dither);\n"
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
                    + "    float grav = (pow(1.0 + 4.0 * t, -2.0) - 0.04) / 0.96;\n"
                    + "    float circleProf = 1.0 - sqrt(max(0.0, 1.0 - u * u));\n"
                    + "    float lensCurve = mix(grav, circleProf, 0.35);\n"
                    + "    float2 ripple = float2(0.0);\n"
                    + "    if (uWobble > 0.001) {\n"
                    + "        ripple = float2(sin(fragCoord.y * 0.045 + uPhase), cos(fragCoord.x * 0.032 + uPhase * 1.3))\n"
                    + "                * (uWobble * 6.5 * (0.35 + 0.65 * lensCurve));\n"
                    + "    }\n"
                    + "    float disp = lensCurve * uRefract;\n"
                    + "    float cornerPrism = 0.28 + 0.72 * clamp(abs((p.x * p.y) / max(1.0, halfSize.x * halfSize.y)) * 1.65, 0.0, 1.0);\n"
                    + "    float ca = lensCurve * uChromatic * cornerPrism;\n"
                    + "    float2 baseUv = screenPos + ripple - n * disp;\n"
                    + "    float2 uvR = baseUv + n * ca;\n"
                    + "    float2 uvO = baseUv + n * (ca * 0.45);\n"
                    + "    float2 uvG = baseUv;\n"
                    + "    float2 uvC = baseUv - n * (ca * 0.45);\n"
                    + "    float2 uvB = baseUv - n * ca;\n"
                    + "    float lightDot = dot(n, float2(-0.4472, -0.8944));\n"
                    + "    float lobeF = pow(max(lightDot, 0.0), 3.6);\n"
                    + "    float lobeB = pow(max(-lightDot, 0.0), 3.8);\n"
                    + "    half3 specCol = mix(half3(1.0), half3(uRimTint), half(0.24));\n"
                    + "    float hair = clamp(1.0 - abs(distIn - 1.0) / 2.2, 0.0, 1.0);\n"
                    + "    float glowIn = clamp((distIn - 0.8) / 2.2, 0.0, 1.0) * pow(clamp(1.0 - distIn / max(4.0, uBezel * 0.44), 0.0, 1.0), 1.6);\n"
                    + "    if (uLiquidMode > 0.5) {\n"
                    + "        half3 sR = uSharp.eval(uvR).rgb;\n"
                    + "        half3 sO = uSharp.eval(uvO).rgb;\n"
                    + "        half3 sG = uSharp.eval(uvG).rgb;\n"
                    + "        half3 sC = uSharp.eval(uvC).rgb;\n"
                    + "        half3 sB = uSharp.eval(uvB).rgb;\n"
                    + "        half3 lensFrost = half3((sR.r * half(0.55) + sO.r * half(0.45)),\n"
                    + "                (sO.g * half(0.22) + sG.g * half(0.56) + sC.g * half(0.22)),\n"
                    + "                (sC.b * half(0.45) + sB.b * half(0.55)));\n"
                    + "        half3 deepBlur = half3(sampleBlur(uvR).r, sampleBlur(uvG).g, sampleBlur(uvB).b);\n"
                    + "        float clarity = clamp(pow(lensCurve, 0.68) * uEdgeClarity, 0.0, 0.92);\n"
                    + "        half3 refracted = mix(deepBlur, lensFrost, half(clarity));\n"
                    + "        refracted = applyVibrancyAndAdapt(refracted * half(0.94), half(0.46), dither);\n"
                    + "        float caustic = smoothstep(uBezel * 0.36, 0.0, abs(distIn - uBezel * 0.30)) * (0.12 * lobeF + 0.06 * lobeB);\n"
                    + "        float rimWeight = smoothstep(1.0, 0.0, t);\n"
                    + "        float refractAlpha = pow(rimWeight, 1.08) * 0.84;\n"
                    + "        float specAlpha = clamp(hair * (0.06 + 0.68 * (lobeF + lobeB)) + glowIn * (0.18 * lobeF + 0.06 * lobeB) + caustic, 0.0, 0.94);\n"
                    + "        float outA = clamp(specAlpha + refractAlpha * (1.0 - specAlpha), 0.0, 0.96);\n"
                    + "        half3 premul = clamp(specCol * half(specAlpha) + refracted * half(refractAlpha * (1.0 - specAlpha)), half3(0.0), half3(outA));\n"
                    + "        return half4(premul * half(mask), half(outA * mask));\n"
                    + "    }\n"
                    + "    half3 refracted = half3(sampleBlur(uvR).r, sampleBlur(uvG).g, sampleBlur(uvB).b);\n"
                    + "    if (uEdgeClarity > 0.01 && t < 0.99) {\n"
                    + "        half3 sharpEdge = half3(sampleSharpSmooth(uvR).r, sampleSharpSmooth(uvG).g, sampleSharpSmooth(uvB).b);\n"
                    + "        refracted = mix(refracted, sharpEdge, half(pow(lensCurve, 1.05) * uEdgeClarity * 0.38));\n"
                    + "    }\n"
                    + "    refracted = applyVibrancyAndAdapt(refracted * half(0.90), half(0.30), dither);\n"
                    + "    float rimWeight = smoothstep(1.0, 0.0, t);\n"
                    + "    float refractAlpha = pow(rimWeight, 1.55) * 0.44;\n"
                    + "    float specAlpha = clamp(hair * (0.06 + 0.62 * (lobeF + lobeB)) + glowIn * (0.14 * lobeF + 0.05 * lobeB), 0.0, 0.90);\n"
                    + "    float outA = clamp(specAlpha + refractAlpha * (1.0 - specAlpha), 0.0, 0.92);\n"
                    + "    half3 premul = clamp(specCol * half(specAlpha) + refracted * half(refractAlpha * (1.0 - specAlpha)), half3(0.0), half3(outA));\n"
                    + "    return half4(premul * half(mask), half(outA * mask));\n"
                    + "}";

    private static final class RefractionBackdrop {
        final Bitmap sharp;
        final Bitmap blur;
        final BitmapShader sharpShader;
        final BitmapShader blurShader;
        final int screenW;
        final int screenH;
        final int version;
        final boolean overHome;

        RefractionBackdrop(Bitmap sharp, Bitmap blur, int screenW, int screenH, int version, boolean overHome) {
            this.sharp = sharp;
            this.blur = blur;
            sharp.prepareToDraw();
            blur.prepareToDraw();
            this.screenW = Math.max(1, screenW);
            this.screenH = Math.max(1, screenH);
            this.version = version;
            this.overHome = overHome;
            this.sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.sharpShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            Matrix sm = new Matrix();
            sm.setScale(Math.max(1, screenW) / (float) Math.max(1, sharp.getWidth()),
                    Math.max(1, screenH) / (float) Math.max(1, sharp.getHeight()));
            this.sharpShader.setLocalMatrix(sm);

            this.blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.blurShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            Matrix bm = new Matrix();
            bm.setScale(Math.max(1, screenW) / (float) Math.max(1, blur.getWidth()),
                    Math.max(1, screenH) / (float) Math.max(1, blur.getHeight()));
            this.blurShader.setLocalMatrix(bm);
        }
    }

    private static volatile RefractionBackdrop activeBackdrop;
    private static volatile RefractionBackdrop nestedBackdrop;
    private static volatile WeakReference<ViewGroup> nestedSourcePanel;
    private static volatile Bitmap activeSharpOnly;
    private static volatile int backdropVersionCounter = 1;
    private static volatile ExecutorService backdropExec;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<RuntimeShader> LIQUID_SHADER_POOL = new ArrayDeque<>();
    private static final Map<Long, RenderEffect> BACKDROP_EFFECT_CACHE = new HashMap<>();
    private static final Object BLUR_SCRATCH_LOCK = new Object();
    private static int[] blurPxScratch;
    private static int[] blurTmpScratch;

    private static final WeakHashMap<View, Spec> SPECS = new WeakHashMap<>();
    private static final WeakHashMap<Dialog, FocusRing> DIALOG_RINGS = new WeakHashMap<>();
    private static final WeakHashMap<Dialog, Runnable> DIALOG_DISMISS_HOOKS = new WeakHashMap<>();
    private static final WeakHashMap<Dialog, Runnable> DIALOG_CLOSE_RUNNERS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> CLOSING_VIEWS = new WeakHashMap<>();
    private static final WeakHashMap<View, ValueAnimator> PANEL_ANIMS = new WeakHashMap<>();
    private static final Spec DEFAULT_SPEC = new Spec(1.0f, 0f, RADIUS_AUTO, 0);
    private static final Spec EDIT_TEXT_SPEC = new Spec(1.0f, 0f, 14f, 0);
    private static BitmapShader sharedNoiseShader;

    private GlassKit() { }

    private static ExecutorService backdropExec() {
        ExecutorService e = backdropExec;
        if (e == null) {
            synchronized (GlassKit.class) {
                e = backdropExec;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(r -> new Thread(r, "glass-backdrop"));
                    backdropExec = e;
                }
            }
        }
        return e;
    }

    private static float displayRefreshRate(View view) {
        if (view != null) {
            Display d = view.getDisplay();
            if (d != null) {
                float hz = d.getRefreshRate();
                if (hz >= 20f && hz <= 480f) return hz;
            }
        }
        return 60f;
    }

    private static RuntimeShader acquireLiquidShader() {
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

    private static void recycleLiquidShader(RuntimeShader shader) {
        if (shader == null) return;
        synchronized (LIQUID_SHADER_POOL) {
            if (LIQUID_SHADER_POOL.size() < 4) LIQUID_SHADER_POOL.addLast(shader);
        }
    }

    private static void drawBezelRing(Canvas canvas, float left, float top, float right, float bottom,
                                      float radius, float bezel, Paint paint) {
        float w = right - left;
        float h = bottom - top;
        float holeInset = (float) Math.ceil(Math.max(bezel + 2f, (bezel + 2f) * 0.70711f + radius * 0.29289f));
        if (w <= 2f * holeInset + 4f || h <= 2f * holeInset + 4f) {
            canvas.drawRect(left, top, right, bottom, paint);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    static void forwardBackdropExtras(Activity from, Intent to) {
        if (from == null || to == null) return;
        Intent src = from.getIntent();
        if (src == null) return;
        if (src.hasExtra(EXTRA_OVER_HOME)) {
            to.putExtra(EXTRA_OVER_HOME, src.getBooleanExtra(EXTRA_OVER_HOME, false));
        }
        byte[] jpeg = src.getByteArrayExtra(EXTRA_BACKDROP_JPEG);
        if (jpeg != null && jpeg.length > 0) {
            to.putExtra(EXTRA_BACKDROP_JPEG, jpeg);
        }
    }

    /**
     * Decodes launcher-captured backdrop JPEG off the hot blur path (immutable Bitmap so HWUI RenderThread
     * upload never races with background blur generation). Defaults {@code overHome} to {@code false} and
     * clears stale cached backdrops whenever an Intent carries no JPEG.
     */
    static boolean installBackdropFromIntent(Activity activity, Intent intent) {
        final boolean overHome = intent != null && intent.getBooleanExtra(EXTRA_OVER_HOME, false);
        byte[] jpeg = intent != null ? intent.getByteArrayExtra(EXTRA_BACKDROP_JPEG) : null;
        if (jpeg != null && jpeg.length > 0) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inMutable = false;
                final Bitmap sharp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                if (sharp != null) {
                    activeSharpOnly = sharp;
                    final android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                    final int sw = dm.widthPixels, sh = dm.heightPixels;
                    final WeakReference<Activity> actRef = new WeakReference<>(activity);
                    backdropExec().execute(() -> {
                        Bitmap blur = makeFastBlurred(sharp, 360, 202);
                        if (blur != null) {
                            activeBackdrop = new RefractionBackdrop(sharp, blur, sw, sh, ++backdropVersionCounter, overHome);
                            MAIN_HANDLER.post(() -> {
                                Activity a = actRef.get();
                                if (a != null && !a.isFinishing() && !a.isDestroyed() && a.getWindow() != null) {
                                    invalidateLiquidPanels(a.getWindow().peekDecorView());
                                }
                            });
                        }
                    });
                } else {
                    activeBackdrop = null;
                    activeSharpOnly = null;
                }
            } catch (RuntimeException | OutOfMemoryError ignored) {
                activeBackdrop = null;
                activeSharpOnly = null;
            }
        } else {
            activeBackdrop = null;
            activeSharpOnly = null;
        }
        return overHome;
    }

    /** Synchronously decodes + blurs a tiny 240x135 backdrop (~3ms) for QuickBars so Frame 0 never snaps unblurred. */
    static boolean installQuickBarBackdropFromIntent(Activity activity, Intent intent) {
        final boolean overHome = intent != null && intent.getBooleanExtra(EXTRA_OVER_HOME, false);
        byte[] jpeg = intent != null ? intent.getByteArrayExtra(EXTRA_BACKDROP_JPEG) : null;
        if (jpeg != null && jpeg.length > 0) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inMutable = false;
                Bitmap sharp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                if (sharp != null) {
                    activeSharpOnly = sharp;
                    Bitmap blur = makeFastBlurred(sharp, 240, 135);
                    if (blur != null) {
                        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                        activeBackdrop = new RefractionBackdrop(
                                sharp, blur, dm.widthPixels, dm.heightPixels, ++backdropVersionCounter, overHome);
                    }
                    return overHome;
                }
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
        }
        activeBackdrop = null;
        activeSharpOnly = null;
        return overHome;
    }

    private static Bitmap makeFastBlurred(Bitmap sharp, int bw, int bh) {
        try {
            Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(sharp, null, new Rect(0, 0, bw, bh), new Paint(Paint.FILTER_BITMAP_FLAG));
            int needed = bw * bh;
            int radius = Math.max(8, Math.round(bw * 22f / 640f));
            synchronized (BLUR_SCRATCH_LOCK) {
                if (blurPxScratch == null || blurPxScratch.length < needed) {
                    blurPxScratch = new int[needed];
                    blurTmpScratch = new int[needed];
                }
                int[] px = blurPxScratch;
                int[] tmp = blurTmpScratch;
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

    private static RenderEffect createBackdropBlurEffect(float density, float radiusDp, int seedRgb, Shader.TileMode tileMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        int seed = seedRgb == 0 ? DEFAULT_SEED_RGB : (seedRgb & 0x00FFFFFF);
        float sigma = Math.min(38f, Math.max(12f, radiusDp * density * 0.57735f));
        long key = (((long) seed) << 16) | (((long) Math.round(sigma * 10f)) << 3) | tileMode.ordinal();
        synchronized (BACKDROP_EFFECT_CACHE) {
            RenderEffect cached = BACKDROP_EFFECT_CACHE.get(key);
            if (cached != null) return cached;
        }
        RenderEffect blur = RenderEffect.createBlurEffect(sigma, sigma, tileMode);
        RenderEffect effect = blur;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader shader = new RuntimeShader(BACKDROP_AGSL);
                int tint = tonalAccent800(seed);
                shader.setFloatUniform("uSaturation", 1.26f);
                shader.setFloatUniform("uLift", 0.012f);
                shader.setFloatUniform("uDim", 0.86f);
                shader.setFloatUniform("uTint",
                        ((tint >> 16) & 0xFF) / 255f,
                        ((tint >> 8) & 0xFF) / 255f,
                        (tint & 0xFF) / 255f);
                shader.setFloatUniform("uTintMix", 0.14f);
                shader.setFloatUniform("uGrain", 0.014f);
                effect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"), blur);
            } catch (RuntimeException ignored) {
            }
        }
        synchronized (BACKDROP_EFFECT_CACHE) {
            if (BACKDROP_EFFECT_CACHE.size() >= 8) BACKDROP_EFFECT_CACHE.clear();
            BACKDROP_EFFECT_CACHE.put(key, effect);
        }
        return effect;
    }

    /**
     * 2-layer hardware-cached full-screen backdrop blur host:
     *  - Outer FrameLayout holds LAYER_TYPE_HARDWARE and animates alpha (0 -> 1 -> 0).
     *  - Inner ImageView holds the native 1080p Skia RenderEffect at constant alpha=1.
     * Guarantees HWUI rasterizes the 38px Gaussian blur ONCE into the host FBO instead of re-blurring every frame.
     */
    private static FrameLayout attachFullScreenBlurBackdrop(FrameLayout backdrop, float density, int seedRgb) {
        Bitmap src = activeSharpOnly;
        if (src == null || src.isRecycled()) {
            RefractionBackdrop bg = activeBackdrop;
            src = (bg != null && bg.sharp != null && !bg.sharp.isRecycled()) ? bg.sharp : null;
        }
        if (src == null) return null;
        FrameLayout host = new FrameLayout(backdrop.getContext());
        host.setClickable(false);
        host.setFocusable(false);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView view = new ImageView(backdrop.getContext());
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setImageBitmap(src);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect effect = createBackdropBlurEffect(density, 34f, seedRgb, Shader.TileMode.CLAMP);
            if (effect != null) {
                view.setRenderEffect(effect);
            } else {
                view.setColorFilter(0x22080B12);
            }
            view.setLayerType(View.LAYER_TYPE_NONE, null);
            host.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            view.setColorFilter(0x22080B12);
        }
        host.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.setAlpha(0.002f);
        backdrop.addView(host, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return host;
    }

    private static void collectLiquidPanels(View v, List<View> out) {
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

    private static void invalidateCached(List<View> targets) {
        for (int i = 0, n = targets.size(); i < n; i++) {
            View v = targets.get(i);
            if (v.getBackground() != null) v.getBackground().invalidateSelf();
            v.invalidate();
        }
    }

    static void invalidateLiquidPanels(View v) {
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

    static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    static float density(Context ctx) {
        return ctx.getResources().getDisplayMetrics().density;
    }

    static int insetPx(float density) {
        return Math.round(INSET_DP * density);
    }

    static final class Spec {
        final float scale;
        final float liftDp;
        final float radiusDp;
        final int accent;

        Spec(float scale, float liftDp, float radiusDp, int accent) {
            this.scale = scale;
            this.liftDp = liftDp;
            this.radiusDp = radiusDp;
            this.accent = accent;
        }
    }

    static void configureFocus(View view, float scale, float liftDp, float radiusDp, int accent) {
        if (view != null) {
            SPECS.put(view, new Spec(scale, liftDp, radiusDp, accent));
        }
    }

    static void disableOutlineShadow(View v) {
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.setOutlineAmbientShadowColor(Color.TRANSPARENT);
            v.setOutlineSpotShadowColor(Color.TRANSPARENT);
        }
    }

    static void clearDialogShadow(Window window) {
        if (window == null) return;
        window.setElevation(0f);
        disableOutlineShadow(window.getDecorView());
    }

    static void applyWindowGlass(Window window, float density, float dimAmount) {
        applyWindowGlass(window, density, dimAmount, false);
    }

    static void applyWindowGlass(Window window, float density, float dimAmount, boolean enableWindowBlur) {
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
            if (enableWindowBlur) {
                int radiusPx = Math.max(16, Math.min(80, Math.round(34f * density)));
                lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                lp.setBlurBehindRadius(radiusPx);
                window.setBackgroundBlurRadius(radiusPx);
            } else {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                lp.setBlurBehindRadius(0);
                window.setBackgroundBlurRadius(0);
            }
        }
        window.setAttributes(lp);
    }

    static void applyPanelSurface(View panel, float density, float cornerRadiusDp, boolean smoked, boolean clipToOutline) {
        applyPanelSurface(panel, density, cornerRadiusDp, smoked, clipToOutline, DEFAULT_SEED_RGB);
    }

    static void applyPanelSurface(View panel, float density, float cornerRadiusDp, boolean smoked,
                                  boolean clipToOutline, int seedRgb) {
        applyPanelSurface(panel, density, cornerRadiusDp, smoked, clipToOutline, seedRgb, false);
    }

    static void applyPanelSurface(View panel, float density, float cornerRadiusDp, boolean smoked,
                                  boolean clipToOutline, int seedRgb, boolean overSharpBackdrop) {
        float radiusPx = cornerRadiusDp * density;
        int resolvedSeed = seedRgb == 0 ? DEFAULT_SEED_RGB : seedRgb;
        Drawable existing = panel.getBackground();
        if (existing instanceof PanelDrawable) {
            ((PanelDrawable) existing).updateConfig(radiusPx, smoked, resolvedSeed, overSharpBackdrop);
        } else {
            panel.setBackground(new PanelDrawable(panel, density, radiusPx, smoked, resolvedSeed, overSharpBackdrop));
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

    static StateListDrawable makeRowBg(float density, float radiusDp) {
        return makeChoiceBg(density, radiusDp, 0xFF4ADE80);
    }

    static StateListDrawable makePillBg(float density, float radiusDp) {
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x34FFFFFF, 0x1BFFFFFF});
        focused.setCornerRadius(radiusDp * density);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22FFFFFF, 0x11FFFFFF});
        rest.setCornerRadius(radiusDp * density);
        rest.setStroke(Math.max(1, Math.round(1.0f * density)), 0x36FFFFFF);
        bg.addState(new int[0], rest);
        return bg;
    }

    static StateListDrawable makeChoiceBg(float density, float radiusDp, int accentArgb) {
        int rgb = accentArgb & 0x00FFFFFF;
        StateListDrawable bg = new StateListDrawable();

        GradientDrawable focusedSel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x48000000 | rgb, 0x26000000 | rgb});
        focusedSel.setCornerRadius(radiusDp * density);
        bg.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, focusedSel);

        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2CFFFFFF, 0x18FFFFFF});
        focused.setCornerRadius(radiusDp * density);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable selected = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38000000 | rgb, 0x1C000000 | rgb});
        selected.setCornerRadius(radiusDp * density);
        selected.setStroke(Math.max(1, Math.round(1.2f * density)), 0xA8000000 | rgb);
        bg.addState(new int[]{android.R.attr.state_selected}, selected);

        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x18FFFFFF, 0x0CFFFFFF});
        rest.setCornerRadius(radiusDp * density);
        rest.setStroke(Math.max(1, Math.round(1.0f * density)), 0x2AFFFFFF);
        bg.addState(new int[0], rest);
        return bg;
    }

    static StateListDrawable makeCardBg(float density, float radiusDp) {
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2CFFFFFF, 0x16FFFFFF});
        focused.setCornerRadius(radiusDp * density);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1AFFFFFF, 0x0DFFFFFF});
        rest.setCornerRadius(radiusDp * density);
        rest.setStroke(Math.max(1, Math.round(1.0f * density)), 0x2CFFFFFF);
        bg.addState(new int[0], rest);
        return bg;
    }

    static GradientDrawable makeInputBg(float density, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0x30FFFFFF : 0x1EFFFFFF);
        d.setCornerRadius(14f * density);
        d.setStroke(Math.max(1, Math.round(1.2f * density)), focused ? 0x55FFFFFF : 0x3DFFFFFF);
        return d;
    }

    static void animateFocus(View v, boolean focused, float focusScale, float liftDp, float density) {
        v.animate().cancel();
        if (focused && v.getTranslationY() > 0f) v.setTranslationY(0f);
        float s = focused ? focusScale : 1f;
        float ty = focused ? -liftDp * density : 0f;
        v.animate()
                .setStartDelay(0L)
                .scaleX(s).scaleY(s).translationX(0f).translationY(ty)
                .setDuration(focused ? 170L : 125L)
                .setInterpolator(focused ? TACTILE_FOCUS : EASE_OUT_EXPO)
                .start();
    }

    static void attachRowFocus(View row, float density, float radiusDp, int accent) {
        configureFocus(row, 1.015f, 0f, radiusDp, accent);
        row.setOnFocusChangeListener((v, focused) -> animateFocus(v, focused, 1.015f, 0f, density));
    }

    static void attachPillFocus(View pill, float scale, float liftDp, float density, float radiusDp, int accent) {
        configureFocus(pill, scale, liftDp, radiusDp, accent);
        pill.setOnFocusChangeListener((v, focused) -> animateFocus(v, focused, scale, liftDp, density));
    }

    static void pulseClick(View v, float restScale) {
        if (v == null) return;
        v.animate().cancel();
        float dip = restScale * 0.93f;
        v.animate().setStartDelay(0L).scaleX(dip).scaleY(dip).setDuration(60L).setInterpolator(EASE_IN_EXIT)
                .withEndAction(() -> {
                    if (!v.isAttachedToWindow()) return;
                    v.animate().setStartDelay(0L).scaleX(restScale).scaleY(restScale).setDuration(165L)
                            .setInterpolator(TACTILE_FOCUS).start();
                }).start();
    }

    static void setClosing(View view, boolean closing) {
        if (view == null) return;
        if (closing) CLOSING_VIEWS.put(view, Boolean.TRUE);
        else CLOSING_VIEWS.remove(view);
    }

    static boolean isClosing(View view) {
        return view != null && Boolean.TRUE.equals(CLOSING_VIEWS.get(view));
    }

    static void cancelPanelAnim(View view) {
        if (view == null) return;
        view.animate().cancel();
        ValueAnimator prev = PANEL_ANIMS.remove(view);
        if (prev != null) prev.cancel();
    }

    private static void setLiquidRestingCoords(List<View> targets, boolean resting) {
        for (int i = 0, n = targets.size(); i < n; i++) {
            Drawable bg = targets.get(i).getBackground();
            if (bg instanceof PanelDrawable) {
                ((PanelDrawable) bg).useRestingCoords = resting;
            }
        }
    }

    private static void glidePanel(View view,
                                   float fromTy, float toTy,
                                   float fromScale, float toScale,
                                   boolean entering,
                                   long durationMs,
                                   Interpolator curve,
                                   Runnable onEnd) {
        cancelPanelAnim(view);
        final List<View> liquidTargets = new ArrayList<>(2);
        collectLiquidPanels(view, liquidTargets);
        final int prevLayer = view.getLayerType();
        final boolean useHwLayer = view.getWidth() <= 4096 && view.getHeight() <= 4096;
        if (useHwLayer) {
            if (entering && !liquidTargets.isEmpty()) {
                setLiquidRestingCoords(liquidTargets, true);
            }
            if (prevLayer != View.LAYER_TYPE_HARDWARE) {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }
        view.setTranslationY(fromTy);
        view.setScaleX(fromScale);
        view.setScaleY(fromScale);
        view.setAlpha(entering ? 0f : 1f);
        if (entering || !useHwLayer) {
            invalidateCached(liquidTargets);
        }

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durationMs);
        anim.setInterpolator(null);
        anim.addUpdateListener(a -> {
            float raw = (float) a.getAnimatedValue();
            float k = curve.getInterpolation(raw);
            view.setTranslationY(fromTy + (toTy - fromTy) * k);
            float s = fromScale + (toScale - fromScale) * k;
            view.setScaleX(s);
            view.setScaleY(s);
            if (entering) {
                float ak = Math.min(1f, raw / 0.42f);
                view.setAlpha(ak * (2f - ak));
            } else {
                float ft = Math.max(0f, (raw - 0.35f) / 0.65f);
                view.setAlpha(1f - ft * ft);
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
                        setLiquidRestingCoords(liquidTargets, false);
                    }
                    if (view.getLayerType() != prevLayer) {
                        view.setLayerType(prevLayer, null);
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
                if (PANEL_ANIMS.get(view) == anim) PANEL_ANIMS.remove(view);
                restoreLayer();
                if (!canceled) {
                    view.setTranslationY(toTy);
                    view.setScaleX(toScale);
                    view.setScaleY(toScale);
                    view.setAlpha(entering ? 1f : 0f);
                    if (!useHwLayer) invalidateCached(liquidTargets);
                    if (onEnd != null) onEnd.run();
                }
            }
        });
        PANEL_ANIMS.put(view, anim);
        anim.start();
    }

    static void revealModal(View view, float density) {
        revealModal(view, density, null);
    }

    static void revealModal(View view, float density, Runnable shouldRun) {
        cancelPanelAnim(view);
        setClosing(view, false);
        view.setAlpha(0f);
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.setTranslationY(84f * density);
        view.post(() -> view.postOnAnimation(() -> {
            if (!view.isAttachedToWindow()) return;
            if (shouldRun != null) shouldRun.run();
            if (isClosing(view)) return;
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);
            glidePanel(view, 84f * density, 0f, 0.94f, 1f, true, 330L, EASE_OUT_EXPO, null);
        }));
    }

    static void dismissModal(View view, float density, Runnable onEnd) {
        cancelPanelAnim(view);
        setClosing(view, true);
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
        glidePanel(view, view.getTranslationY(), 72f * density, view.getScaleX(), 0.94f, false, 220L, EASE_IN_EXIT, onEnd);
    }

    static void revealBottomBar(View view, float density) {
        cancelPanelAnim(view);
        setClosing(view, false);
        view.setAlpha(0f);
        view.setScaleX(0.95f);
        view.setScaleY(0.95f);
        view.setTranslationY(76f * density);
        view.post(() -> view.postOnAnimation(() -> {
            if (!view.isAttachedToWindow() || isClosing(view)) return;
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight());
            glidePanel(view, 76f * density, 0f, 0.95f, 1f, true, 300L, EASE_OUT_EXPO, null);
        }));
    }

    static void dismissBottomBar(View view, float density, Runnable onEnd) {
        cancelPanelAnim(view);
        setClosing(view, true);
        glidePanel(view, view.getTranslationY(), 64f * density, view.getScaleX(), 0.95f, false, 210L, EASE_IN_EXIT, onEnd);
    }

    static View makeDivider(Context ctx, int topMarginDp, int bottomMarginDp) {
        View line = new View(ctx);
        line.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
        lp.topMargin = dp(ctx, topMarginDp);
        lp.bottomMargin = dp(ctx, bottomMarginDp);
        line.setLayoutParams(lp);
        return line;
    }

    static TextView makeTitle(Context ctx, CharSequence text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(23f);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLetterSpacing(0.01f);
        return tv;
    }

    static TextView makeSubtitle(Context ctx, CharSequence text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTextColor(0xA6FFFFFF);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return tv;
    }

    static TextView makeSectionHeading(Context ctx, CharSequence text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.12f);
        tv.setTextColor(0x99FFFFFF);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(ctx, 6), dp(ctx, 14), dp(ctx, 6), dp(ctx, 6));
        return tv;
    }

    static TextView makeHint(Context ctx, CharSequence text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0x8CFFFFFF);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return tv;
    }

    static TextView makePillButton(Context ctx, CharSequence label, int accent) {
        float d = density(ctx);
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextSize(14f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(ctx, 18), 0, dp(ctx, 18), 0);
        btn.setBackground(makePillBg(d, 21f));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        attachPillFocus(btn, 1.06f, 1.5f, d, RADIUS_CAPSULE, accent);
        return btn;
    }

    private static final WeakHashMap<ViewGroup, PanelBlurState> PANEL_BLUR_STATES = new WeakHashMap<>();

    private static final class PanelBlurState {
        float fraction;
        boolean targetBlurred;
        boolean ringWasVisible;
        ExpressiveBlurVeilDrawable veilDrawable;
        ValueAnimator animator;
    }

    private static final class ExpressiveBlurVeilDrawable extends Drawable {
        private final Bitmap sharpChildren;
        private final Bitmap softwareFallback;
        private final float cornerRadiusPx;
        private final float insetPx;
        private final float density;
        private final int seedRgb;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint tintVeilPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final RectF clipRect = new RectF();
        private final Path clipPath = new Path();
        private RenderNode effectNode;
        private RenderNode cacheNode;
        private int builtW = -1, builtH = -1;
        private int curAlpha;
        private boolean released;

        ExpressiveBlurVeilDrawable(Bitmap sharpChildren, Bitmap softwareFallback,
                                   float cornerRadiusPx, float insetPx, float density, int seedRgb) {
            this.sharpChildren = sharpChildren;
            this.softwareFallback = softwareFallback;
            this.cornerRadiusPx = cornerRadiusPx;
            this.insetPx = insetPx;
            this.density = density;
            this.seedRgb = seedRgb;
        }

        private void ensureBuilt(int w, int h) {
            if (w == builtW && h == builtH) return;
            builtW = w;
            builtH = h;
            clipPath.reset();
            clipRect.set(insetPx, insetPx, Math.max(insetPx + 1f, w - insetPx), Math.max(insetPx + 1f, h - insetPx));
            float r = Math.max(0f, Math.min(cornerRadiusPx - insetPx, Math.min(clipRect.width(), clipRect.height()) * 0.5f));
            clipPath.addRoundRect(clipRect, r, r, Path.Direction.CW);

            int accent800 = tonalAccent800(seedRgb);
            int topTint = (0x20 << 24) | mixRgb(0x101520, accent800, 0.38f);
            int botTint = (0x34 << 24) | mixRgb(0x080B12, accent800, 0.28f);
            tintVeilPaint.setShader(new LinearGradient(
                    0f, clipRect.top, 0f, clipRect.bottom,
                    topTint, botTint, Shader.TileMode.CLAMP));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect fx = createBackdropBlurEffect(density, 28f, seedRgb, Shader.TileMode.DECAL);
                if (fx != null) {
                    if (effectNode == null) effectNode = new RenderNode("expressive-panel-fx");
                    if (cacheNode == null) cacheNode = new RenderNode("expressive-panel-cache");

                    effectNode.setPosition(0, 0, w, h);
                    effectNode.setRenderEffect(fx);
                    RecordingCanvas ec = effectNode.beginRecording(w, h);
                    ec.drawBitmap(sharpChildren, null, new Rect(0, 0, w, h), bitmapPaint);
                    effectNode.endRecording();

                    cacheNode.setPosition(0, 0, w, h);
                    Outline outline = new Outline();
                    int l = Math.round(clipRect.left);
                    int t = Math.round(clipRect.top);
                    int ri = Math.max(l + 1, Math.round(clipRect.right));
                    int bt = Math.max(t + 1, Math.round(clipRect.bottom));
                    outline.setRoundRect(l, t, ri, bt, r);
                    cacheNode.setOutline(outline);
                    cacheNode.setClipToOutline(true);
                    cacheNode.setUseCompositingLayer(true, null);

                    RecordingCanvas cc = cacheNode.beginRecording(w, h);
                    cc.drawRenderNode(effectNode);
                    cc.drawRect(clipRect, tintVeilPaint);
                    cacheNode.endRecording();
                    cacheNode.setAlpha(curAlpha / 255f);
                }
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (released || curAlpha <= 0 || sharpChildren.isRecycled()) return;
            Rect b = getBounds();
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            ensureBuilt(w, h);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cacheNode != null && canvas.isHardwareAccelerated()) {
                if (b.left != 0 || b.top != 0) {
                    int save = canvas.save();
                    canvas.translate(b.left, b.top);
                    canvas.drawRenderNode(cacheNode);
                    canvas.restoreToCount(save);
                } else {
                    canvas.drawRenderNode(cacheNode);
                }
                return;
            }

            Bitmap fb = (softwareFallback != null && !softwareFallback.isRecycled()) ? softwareFallback : sharpChildren;
            int save = canvas.save();
            if (b.left != 0 || b.top != 0) canvas.translate(b.left, b.top);
            canvas.clipPath(clipPath);
            bitmapPaint.setAlpha(curAlpha);
            canvas.drawBitmap(fb, null, new Rect(0, 0, w, h), bitmapPaint);
            bitmapPaint.setAlpha(255);
            tintVeilPaint.setAlpha(curAlpha);
            canvas.drawRect(clipRect, tintVeilPaint);
            tintVeilPaint.setAlpha(255);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            int clamped = Math.max(0, Math.min(255, alpha));
            if (curAlpha != clamped) {
                curAlpha = clamped;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cacheNode != null) {
                    cacheNode.setAlpha(clamped / 255f);
                }
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            bitmapPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        void release() {
            if (released) return;
            released = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (cacheNode != null) {
                    cacheNode.setUseCompositingLayer(false, null);
                    cacheNode.discardDisplayList();
                    cacheNode = null;
                }
                if (effectNode != null) {
                    effectNode.setRenderEffect(null);
                    effectNode.discardDisplayList();
                    effectNode = null;
                }
            }
            if (softwareFallback != null && !softwareFallback.isRecycled()) {
                softwareFallback.recycle();
            }
            if (!sharpChildren.isRecycled()) {
                sharpChildren.recycle();
            }
        }
    }

    private static void blurPremulRgba(int[] px, int[] tmp, int w, int h, int radius, int dim255) {
        if (w <= 0 || h <= 0 || radius <= 0 || px.length < w * h || tmp.length < w * h) return;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int a = (c >>> 24) & 0xFF;
            if (a == 0) {
                px[i] = 0;
            } else if (a < 255) {
                int r = (((c >> 16) & 0xFF) * a + 127) / 255;
                int g = (((c >> 8) & 0xFF) * a + 127) / 255;
                int b = ((c & 0xFF) * a + 127) / 255;
                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            boxBlurRgbaH(px, tmp, w, h, radius);
            boxBlurRgbaV(tmp, px, w, h, radius);
        }
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int a = (c >>> 24) & 0xFF;
            if (a == 0) {
                px[i] = 0;
            } else {
                int pr = (c >> 16) & 0xFF;
                int pg = (c >> 8) & 0xFF;
                int pb = c & 0xFF;
                int r = Math.min(255, (pr * dim255 + (a >> 1)) / a);
                int g = Math.min(255, (pg * dim255 + (a >> 1)) / a);
                int b = Math.min(255, (pb * dim255 + (a >> 1)) / a);
                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void boxBlurRgbaH(int[] src, int[] dst, int w, int h, int r) {
        int span = r * 2 + 1;
        int invSpan = (1 << 16) / span;
        int wMax = w - 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int as = 0, rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + (i < 0 ? 0 : (i > wMax ? wMax : i))];
                as += (c >>> 24) & 0xFF;
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = (((as * invSpan) >>> 16) << 24)
                        | (((rs * invSpan) >>> 16) << 16)
                        | (((gs * invSpan) >>> 16) << 8)
                        | ((bs * invSpan) >>> 16);
                int outX = x - r;
                int inX = x + r + 1;
                int outC = src[row + (outX < 0 ? 0 : outX)];
                int inC = src[row + (inX > wMax ? wMax : inX)];
                as += ((inC >>> 24) & 0xFF) - ((outC >>> 24) & 0xFF);
                rs += ((inC >> 16) & 0xFF) - ((outC >> 16) & 0xFF);
                gs += ((inC >> 8) & 0xFF) - ((outC >> 8) & 0xFF);
                bs += (inC & 0xFF) - (outC & 0xFF);
            }
        }
    }

    private static void boxBlurRgbaV(int[] src, int[] dst, int w, int h, int r) {
        int span = r * 2 + 1;
        int invSpan = (1 << 16) / span;
        int hMax = h - 1;
        for (int x = 0; x < w; x++) {
            int as = 0, rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[(i < 0 ? 0 : (i > hMax ? hMax : i)) * w + x];
                as += (c >>> 24) & 0xFF;
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (((as * invSpan) >>> 16) << 24)
                        | (((rs * invSpan) >>> 16) << 16)
                        | (((gs * invSpan) >>> 16) << 8)
                        | ((bs * invSpan) >>> 16);
                int outY = y - r;
                int inY = y + r + 1;
                int outC = src[(outY < 0 ? 0 : outY) * w + x];
                int inC = src[(inY > hMax ? hMax : inY) * w + x];
                as += ((inC >>> 24) & 0xFF) - ((outC >>> 24) & 0xFF);
                rs += ((inC >> 16) & 0xFF) - ((outC >> 16) & 0xFF);
                gs += ((inC >> 8) & 0xFF) - ((outC >> 8) & 0xFF);
                bs += (inC & 0xFF) - (outC & 0xFF);
            }
        }
    }

    private static ExpressiveBlurVeilDrawable capturePanelChildrenAndBuildVeil(ViewGroup panel, float density) {
        int pw = panel.getWidth();
        int ph = panel.getHeight();
        if (pw <= 4 || ph <= 4) return null;
        try {
            float scale = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ? Math.min(0.65f, 720f / Math.max(pw, ph))
                    : Math.min(0.45f, 240f / Math.max(pw, ph));
            int sw = Math.max(16, Math.round(pw * scale));
            int sh = Math.max(16, Math.round(ph * scale));
            Bitmap sharpChildren = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(sharpChildren);
            sc.scale(sw / (float) pw, sh / (float) ph);
            PanelDrawable pd = (panel.getBackground() instanceof PanelDrawable)
                    ? (PanelDrawable) panel.getBackground() : null;
            if (pd != null) pd.suppressDraw = true;
            try {
                panel.draw(sc);
            } finally {
                if (pd != null) pd.suppressDraw = false;
            }
            sharpChildren.prepareToDraw();

            float softScale = Math.min(1f, 220f / Math.max(sw, sh));
            int rw = Math.max(16, Math.round(sw * softScale));
            int rh = Math.max(16, Math.round(sh * softScale));
            Bitmap softChildren = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            new Canvas(softChildren).drawBitmap(sharpChildren, null, new Rect(0, 0, rw, rh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            int softRadius = Math.max(3, Math.round(Math.max(rw, rh) * 3.5f / 220f));
            int needed = rw * rh;
            synchronized (BLUR_SCRATCH_LOCK) {
                if (blurPxScratch == null || blurPxScratch.length < needed) {
                    blurPxScratch = new int[needed];
                    blurTmpScratch = new int[needed];
                }
                softChildren.getPixels(blurPxScratch, 0, rw, 0, 0, rw, rh);
                blurPremulRgba(blurPxScratch, blurTmpScratch, rw, rh, softRadius, 214);
                softChildren.setPixels(blurPxScratch, 0, rw, 0, 0, rw, rh);
            }
            buildNestedCompositeBackdrop(panel, softChildren, pd, density);

            Bitmap swFallback = null;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                int deepRadius = Math.max(8, Math.round(Math.max(rw, rh) * 10f / 220f));
                synchronized (BLUR_SCRATCH_LOCK) {
                    if (blurPxScratch == null || blurPxScratch.length < needed) {
                        blurPxScratch = new int[needed];
                        blurTmpScratch = new int[needed];
                    }
                    softChildren.getPixels(blurPxScratch, 0, rw, 0, 0, rw, rh);
                    blurPremulRgba(blurPxScratch, blurTmpScratch, rw, rh, deepRadius, 224);
                    softChildren.setPixels(blurPxScratch, 0, rw, 0, 0, rw, rh);
                }
                softChildren.prepareToDraw();
                swFallback = softChildren;
            } else {
                softChildren.recycle();
            }

            float rad = pd != null ? pd.cornerRadiusPx : 24f * density;
            int seed = pd != null ? pd.seedRgb : DEFAULT_SEED_RGB;
            return new ExpressiveBlurVeilDrawable(sharpChildren, swFallback, rad, 1.5f * density, density, seed);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static void buildNestedCompositeBackdrop(ViewGroup panel, Bitmap softChildren,
                                                     PanelDrawable pd, float density) {
        try {
            RefractionBackdrop base = activeBackdrop;
            android.util.DisplayMetrics dm = panel.getResources().getDisplayMetrics();
            int screenW = (base != null && base.screenW > 0) ? base.screenW : dm.widthPixels;
            int screenH = (base != null && base.screenH > 0) ? base.screenH : dm.heightPixels;
            if (screenW <= 0 || screenH <= 0) return;

            int cw = 480, ch = 270;
            Bitmap compSharp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(compSharp);
            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (base != null && !base.blur.isRecycled()) {
                c.drawBitmap(base.blur, null, new Rect(0, 0, cw, ch), fp);
                c.drawColor(0x64060A12);
            } else {
                c.drawColor(0xFF10141C);
            }
            int[] loc = new int[2];
            panel.getLocationOnScreen(loc);
            float sx = cw / (float) screenW;
            float sy = ch / (float) screenH;
            RectF dst = new RectF(
                    loc[0] * sx, loc[1] * sy,
                    (loc[0] + panel.getWidth()) * sx, (loc[1] + panel.getHeight()) * sy);
            float rad = (pd != null ? pd.cornerRadiusPx : 24f * density) * sx;
            int seed = pd != null ? pd.seedRgb : DEFAULT_SEED_RGB;
            int accent800 = tonalAccent800(seed);
            int topBase = mixRgb(0x131720, accent800, 0.36f);
            int midBase = mixRgb(0x0B0E15, accent800, 0.28f);
            int botBase = mixRgb(0x07090E, accent800, 0.22f);
            Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            glassFill.setShader(new LinearGradient(
                    dst.left, dst.top, dst.left, dst.bottom,
                    new int[]{(0x5C << 24) | topBase, (0x6A << 24) | midBase, (0x78 << 24) | botBase},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(dst, rad, rad, glassFill);
            c.drawBitmap(softChildren, null, dst, fp);
            Paint scrimP = new Paint(Paint.ANTI_ALIAS_FLAG);
            scrimP.setColor(0x3C080C14);
            c.drawRoundRect(dst, rad, rad, scrimP);

            Bitmap compBlur = makeFastBlurred(compSharp, 240, 135);
            if (compBlur != null) {
                nestedBackdrop = new RefractionBackdrop(
                        compSharp, compBlur, screenW, screenH, ++backdropVersionCounter, true);
                nestedSourcePanel = new WeakReference<>(panel);
            } else {
                compSharp.recycle();
            }
        } catch (RuntimeException | OutOfMemoryError ignored) {
        }
    }

    private static void clearNestedBackdropIfOwned(ViewGroup panel) {
        WeakReference<ViewGroup> ref = nestedSourcePanel;
        if (ref != null && ref.get() == panel) {
            nestedSourcePanel = null;
            nestedBackdrop = null;
        }
    }

    private static boolean isSameOrDescendant(View host, View ancestor) {
        if (host == null || ancestor == null) return false;
        View cur = host;
        while (cur != null) {
            if (cur == ancestor) return true;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static void applyPanelBlurFraction(ViewGroup panel, FocusRing focusRing,
                                               PanelBlurState state, float f) {
        state.fraction = f;
        boolean hasVeil = state.veilDrawable != null;
        float childAlpha = hasVeil ? (1f - f) : (1f - 0.28f * f);
        for (int i = 0, n = panel.getChildCount(); i < n; i++) {
            panel.getChildAt(i).setAlpha(childAlpha);
        }
        if (state.veilDrawable != null) {
            state.veilDrawable.setBounds(0, 0, panel.getWidth(), panel.getHeight());
            state.veilDrawable.setAlpha(Math.max(0, Math.min(255, Math.round(255f * f))));
        }
        if (focusRing != null && state.ringWasVisible) {
            focusRing.glass.setAlpha(1f - 0.90f * f);
        }
    }

    private static void releasePanelBlurVeil(ViewGroup panel, PanelBlurState state) {
        if (state.veilDrawable != null) {
            panel.getOverlay().remove(state.veilDrawable);
            state.veilDrawable.release();
            state.veilDrawable = null;
        }
        clearNestedBackdropIfOwned(panel);
    }

    static void setPanelContentBlur(ViewGroup panel, float density, boolean blurred) {
        setPanelContentBlur(panel, null, density, blurred);
    }

    static void setPanelContentBlur(ViewGroup panel, FocusRing focusRing, float density, boolean blurred) {
        if (panel == null) return;
        PanelBlurState state = PANEL_BLUR_STATES.get(panel);
        if (state == null) {
            if (!blurred) return;
            state = new PanelBlurState();
            state.ringWasVisible = focusRing != null && focusRing.glass.getAlpha() > 0.01f;
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
        if (blurred && state.veilDrawable == null) {
            ExpressiveBlurVeilDrawable veil = capturePanelChildrenAndBuildVeil(panel, density);
            if (veil != null) {
                veil.setBounds(0, 0, panel.getWidth(), panel.getHeight());
                panel.getOverlay().add(veil);
                state.veilDrawable = veil;
            }
        }
        final PanelBlurState st = state;
        float start = st.fraction;
        float target = blurred ? 1f : 0f;
        if (Math.abs(target - start) < 0.001f) {
            applyPanelBlurFraction(panel, focusRing, st, target);
            if (!blurred) {
                releasePanelBlurVeil(panel, st);
                PANEL_BLUR_STATES.remove(panel);
            }
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(start, target);
        anim.setDuration(blurred ? 190L : 150L);
        anim.setInterpolator(blurred ? EASE_OUT_EXPO : EASE_IN_EXIT);
        anim.addUpdateListener(a -> applyPanelBlurFraction(panel, focusRing, st, (float) a.getAnimatedValue()));
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
                    applyPanelBlurFraction(panel, focusRing, st, target);
                    if (!blurred) {
                        releasePanelBlurVeil(panel, st);
                        PANEL_BLUR_STATES.remove(panel);
                    }
                }
            }
        });
        st.animator = anim;
        anim.start();
    }

    /**
     * Enforces strict viewport clipping on every {@link ScrollView} and {@link HorizontalScrollView}
     * inside {@code root}. When a parent panel sets {@code clipChildren=false}, Android HWUI clears
     * {@code RenderNode.setClipToBounds} on direct children and {@code ViewGroup.dispatchDraw} skips
     * {@code clipRect} whenever padding is zero ({@code FLAG_PADDING_NOT_NULL == 0}). Combining a
     * 1px vertical padding with {@link ViewOutlineProvider#BOUNDS} + {@code clipToOutline=true}
     * guarantees both canvas-level and hardware RenderNode viewport clipping.
     */
    static void clipScrollContainers(View v) {
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

    /** Full floating frosted-glass modal Activity stage with living wobbly Fresnel focus ring. */
    static final class Modal {
        final Activity activity;
        final float density;
        final FrameLayout backdrop;
        FrameLayout backdropView;
        boolean overHome;
        boolean inheritBackdrop;
        final int seedRgb;
        final FrameLayout panelStage;
        final LinearLayout panel;
        final FocusRing focusRing;
        private boolean closing;
        private boolean initialRevealDone;
        private int blurDepth;
        private final Runnable deferredUnblur;

        private Modal(Activity activity, FrameLayout backdrop, FrameLayout backdropView, boolean overHome,
                      boolean inheritBackdrop, int seedRgb,
                      FrameLayout panelStage, LinearLayout panel, FocusRing focusRing) {
            this.activity = activity;
            this.density = density(activity);
            this.backdrop = backdrop;
            this.backdropView = backdropView;
            this.overHome = overHome;
            this.inheritBackdrop = inheritBackdrop;
            this.seedRgb = seedRgb;
            this.panelStage = panelStage;
            this.panel = panel;
            this.focusRing = focusRing;
            this.deferredUnblur = () -> {
                if (blurDepth == 0) setPanelContentBlur(this.panel, this.focusRing, this.density, false);
            };
        }

        void onNewIntent(Intent intent) {
            activity.setIntent(intent);
            overHome = installBackdropFromIntent(activity, intent);
            inheritBackdrop = intent != null && intent.getBooleanExtra(EXTRA_INHERIT_BACKDROP, false);
            if (backdropView != null) {
                backdropView.animate().cancel();
                backdrop.removeView(backdropView);
                backdropView = null;
            }
            if (!overHome && !inheritBackdrop) {
                backdropView = attachFullScreenBlurBackdrop(backdrop, density, seedRgb);
                if (backdropView != null) backdropView.setAlpha(1f);
            }
            boolean smoked = !overHome && !inheritBackdrop && backdropView == null;
            applyWindowGlass(activity.getWindow(), density, smoked ? 0.36f : 0f, smoked);
            applyPanelSurface(panel, density, 26f, smoked, false, seedRgb);
            if (closing) {
                closing = false;
                setClosing(panelStage, false);
                cancelPanelAnim(panelStage);
                panelStage.setAlpha(1f);
                panelStage.setScaleX(1f);
                panelStage.setScaleY(1f);
                panelStage.setTranslationY(0f);
            }
            invalidateLiquidPanels(panelStage);
        }

        void reveal() {
            closing = false;
            clipScrollContainers(panel);
            setClosing(panelStage, false);
            if (backdropView != null) {
                backdropView.animate().cancel();
                backdropView.setAlpha(0.002f);
            }
            revealModal(panelStage, density, () -> {
                initialRevealDone = true;
                if (!closing && backdropView != null) {
                    backdropView.animate().cancel();
                    backdropView.animate().alpha(1f).setDuration(195L).setInterpolator(EASE_OUT_EXPO).start();
                }
            });
        }

        void onResume() {
            clipScrollContainers(panel);
            if (initialRevealDone && !closing && backdropView != null) {
                backdropView.animate().cancel();
                backdropView.setAlpha(1f);
            }
            if (closing) {
                closing = false;
                setClosing(panelStage, false);
                cancelPanelAnim(panelStage);
                panelStage.setAlpha(1f);
                panelStage.setScaleX(1f);
                panelStage.setScaleY(1f);
                panelStage.setTranslationY(0f);
            }
            invalidateLiquidPanels(panelStage);
        }

        @SuppressWarnings("deprecation")
        void onPause() {
            if (closing) {
                cancelPanelAnim(panelStage);
                if (!activity.isFinishing()) {
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
            }
        }

        @SuppressWarnings("deprecation")
        void dismiss() {
            if (closing) return;
            closing = true;
            setClosing(panelStage, true);
            if (overHome && !inheritBackdrop) {
                try {
                    activity.sendBroadcast(new Intent(ACTION_ADDON_DISMISSING).setPackage("com.gathin.tvhome"));
                } catch (RuntimeException ignored) {
                }
            }
            if (backdropView != null) {
                backdropView.animate().cancel();
                backdropView.animate().alpha(0f).setDuration(140L).setInterpolator(EASE_IN_EXIT).start();
            }
            dismissModal(panelStage, density, () -> {
                if (!closing) return;
                activity.finish();
                activity.overridePendingTransition(0, 0);
            });
        }

        void pushBlur() {
            MAIN_HANDLER.removeCallbacks(deferredUnblur);
            blurDepth++;
            if (blurDepth == 1) setPanelContentBlur(panel, focusRing, density, true);
        }

        void popBlur() {
            if (blurDepth <= 0) return;
            blurDepth--;
            if (blurDepth == 0) {
                MAIN_HANDLER.removeCallbacks(deferredUnblur);
                MAIN_HANDLER.postDelayed(deferredUnblur, 90L);
            }
        }

        void setBlurred(boolean blurred) {
            if (blurred) pushBlur();
            else popBlur();
        }

        void refreshFocus() {
            clipScrollContainers(panel);
            focusRing.refresh();
        }
    }

    static Modal installModal(Activity activity, int maxWidthDp, int maxHeightDp, boolean wrapHeight) {
        return installModal(activity, maxWidthDp, maxHeightDp, wrapHeight, DEFAULT_SEED_RGB);
    }

    static Modal installModal(Activity activity, int maxWidthDp, int maxHeightDp, boolean wrapHeight, int seedRgb) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        float d = density(activity);
        Intent intent = activity.getIntent();
        boolean overHome = installBackdropFromIntent(activity, intent);
        boolean inheritBackdrop = intent != null && intent.getBooleanExtra(EXTRA_INHERIT_BACKDROP, false);

        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int pad = insetPx(d);
        int w = Math.min(Math.round(maxWidthDp * d), Math.max(Math.round(360 * d), dm.widthPixels - Math.round(72 * d)));
        int h = wrapHeight
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : Math.min(Math.round(maxHeightDp * d), Math.max(Math.round(280 * d), dm.heightPixels - Math.round(52 * d)));

        final Modal[] holder = new Modal[1];
        FrameLayout backdrop = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && holder[0] != null) {
                        holder[0].dismiss();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        backdrop.setClipChildren(false);
        backdrop.setClipToPadding(false);

        FrameLayout backdropView = (!overHome && !inheritBackdrop)
                ? attachFullScreenBlurBackdrop(backdrop, d, seedRgb) : null;
        boolean smoked = !overHome && !inheritBackdrop && backdropView == null;
        // Zero FLAG_DIM_BEHIND when AGSL blur veil is active (Home or captured backdrop) to eliminate Frame-0 snap & double-dim.
        applyWindowGlass(activity.getWindow(), d, smoked ? 0.36f : 0f, smoked);

        FrameLayout panelStage = new FrameLayout(activity);
        panelStage.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panelStage.setClipChildren(false);
        panelStage.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Math.round(24 * d), Math.round(18 * d), Math.round(24 * d), Math.round(14 * d));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setOnClickListener(v -> { /* consume inside taps */ });
        applyPanelSurface(panel, d, 26f, smoked, false, seedRgb);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                wrapHeight ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        panelLp.setMargins(pad, pad, pad, pad);
        panelStage.addView(panel, panelLp);

        FocusRing ring = new FocusRing(panelStage, panel, d);

        int stageH = wrapHeight ? ViewGroup.LayoutParams.WRAP_CONTENT : (h + 2 * pad);
        backdrop.addView(panelStage, new FrameLayout.LayoutParams(w + 2 * pad, stageH, Gravity.CENTER));
        activity.setContentView(backdrop);

        Modal modal = new Modal(activity, backdrop, backdropView, overHome, inheritBackdrop, seedRgb, panelStage, panel, ring);
        holder[0] = modal;
        backdrop.setOnClickListener(v -> modal.dismiss());
        return modal;
    }

    /** Registers an extra dismiss hook without overwriting GlassKit's internal blur/animation cleanup listener. */
    static void setDialogDismissHook(Dialog dialog, Runnable onDismiss) {
        if (dialog == null) return;
        if (onDismiss == null) DIALOG_DISMISS_HOOKS.remove(dialog);
        else DIALOG_DISMISS_HOOKS.put(dialog, onDismiss);
    }

    /** Smoothly animates out a dialog created by {@link #createFrostedDialog} before dismissing. */
    static void dismissFrostedDialog(Dialog dialog) {
        if (dialog == null || !dialog.isShowing()) return;
        Runnable runner = DIALOG_CLOSE_RUNNERS.get(dialog);
        if (runner != null) runner.run();
        else dialog.dismiss();
    }

    /** Wraps a Dialog's panel in a padded frosted-glass host with our living wobbly Fresnel focus ring. */
    static Dialog createFrostedDialog(Activity activity, View panel, int widthDp, Modal parentModal) {
        float d = density(activity);
        int pad = insetPx(d);
        final boolean[] pushedBlur = {false};
        final boolean[] poppedBlur = {false};
        final boolean[] dismissing = {false};
        final Dialog[] dialogRef = new Dialog[1];
        FrameLayout host = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        Dialog dlg = dialogRef[0];
                        if (!dismissing[0] && dlg != null && dlg.isShowing()) {
                            dismissing[0] = true;
                            if (parentModal != null && pushedBlur[0] && !poppedBlur[0]) {
                                poppedBlur[0] = true;
                                parentModal.popBlur();
                            }
                            dismissModal(this, d, () -> {
                                if (dlg.isShowing()) dlg.dismiss();
                            });
                        }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        host.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        if (panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.setMargins(pad, pad, pad, pad);
        host.addView(panel, lp);
        FocusRing ring = new FocusRing(host, panel, d);

        Dialog dialog = new Dialog(activity);
        dialogRef[0] = dialog;
        DIALOG_RINGS.put(dialog, ring);
        Runnable closeAnimated = () -> {
            Dialog dlg = dialogRef[0];
            if (!dismissing[0] && dlg != null && dlg.isShowing()) {
                dismissing[0] = true;
                if (parentModal != null && pushedBlur[0] && !poppedBlur[0]) {
                    poppedBlur[0] = true;
                    parentModal.popBlur();
                }
                dismissModal(host, d, () -> {
                    if (dlg.isShowing()) dlg.dismiss();
                });
            }
        };
        DIALOG_CLOSE_RUNNERS.put(dialog, closeAnimated);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(host);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            clearDialogShadow(w);
            w.setLayout(Math.round(widthDp * d) + 2 * pad, ViewGroup.LayoutParams.WRAP_CONTENT);
            applyWindowGlass(w, d, 0f, false);
            w.setWindowAnimations(0);
        }
        dialog.setOnDismissListener(dd -> {
            cancelPanelAnim(host);
            DIALOG_CLOSE_RUNNERS.remove(dialog);
            if (parentModal != null && pushedBlur[0] && !poppedBlur[0]) {
                poppedBlur[0] = true;
                parentModal.popBlur();
            }
            Runnable hook = DIALOG_DISMISS_HOOKS.remove(dialog);
            if (hook != null) hook.run();
        });
        revealModal(host, d, () -> {
            if (!dismissing[0] && parentModal != null && !pushedBlur[0]) {
                pushedBlur[0] = true;
                parentModal.pushBlur();
            }
        });
        return dialog;
    }

    /** Frosted-glass single-select list dialog replacing stock Android AlertDialog. */
    static Dialog showListDialog(Activity activity, Modal parentModal, CharSequence titleText,
                                 CharSequence[] items, int checkedIndex, int accent,
                                 CharSequence neutralLabel, Runnable onNeutral, IntConsumer onPick) {
        float d = density(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        applyPanelSurface(box, d, 24f, true, false);
        box.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(activity, 8), 0, dp(activity, 8), dp(activity, 10));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(activity, 18));
        clipScrollContainers(scroll);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 6));
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));

        int maxScrollH = dp(activity, 300);
        int scrollH = items.length * dp(activity, 48) > maxScrollH ? maxScrollH : ViewGroup.LayoutParams.WRAP_CONTENT;
        box.addView(scroll, new LinearLayout.LayoutParams(-1, scrollH));

        Dialog dialog = createFrostedDialog(activity, box, 460, parentModal);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean checked = idx == checkedIndex;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11));
            row.setBackground(makeChoiceBg(d, 14f, accent != 0 ? accent : 0xFF4ADE80));
            row.setSelected(checked);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);

            TextView tv = new TextView(activity);
            tv.setText(items[i]);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15.5f);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

            if (checked) {
                View dot = new View(activity);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(accent != 0 ? accent : 0xFF4ADE80);
                dot.setBackground(dotBg);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 8));
                dlp.leftMargin = dp(activity, 10);
                row.addView(dot, dlp);
            }

            row.setOnClickListener(v -> {
                dismissFrostedDialog(dialog);
                if (onPick != null) onPick.accept(idx);
            });
            attachRowFocus(row, d, 14f, checked ? (accent != 0 ? accent : 0xFF4ADE80) : 0);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rlp.topMargin = dp(activity, 6);
            rows.addView(row, rlp);
        }

        if (neutralLabel != null && onNeutral != null) {
            LinearLayout footer = new LinearLayout(activity);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.END);
            footer.setPadding(dp(activity, 6), dp(activity, 12), dp(activity, 6), dp(activity, 2));
            footer.setClipChildren(false);
            footer.setClipToPadding(false);
            TextView btn = makePillButton(activity, neutralLabel, 0);
            btn.setOnClickListener(v -> {
                dismissFrostedDialog(dialog);
                onNeutral.run();
            });
            footer.addView(btn, new LinearLayout.LayoutParams(-2, dp(activity, 38)));
            box.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }

        dialog.show();
        int focusIdx = checkedIndex >= 0 && checkedIndex < rows.getChildCount() ? checkedIndex : 0;
        if (rows.getChildCount() > 0) rows.getChildAt(focusIdx).requestFocus();
        return dialog;
    }

    /** Frosted-glass multi-select dialog (e.g. weekday selector in Sleep Timer schedule). */
    static Dialog showMultiChoiceDialog(Activity activity, Modal parentModal, CharSequence titleText,
                                        CharSequence[] items, boolean[] initialChecked,
                                        CharSequence positiveLabel, CharSequence negativeLabel,
                                        int accent, Consumer<boolean[]> onConfirm) {
        float d = density(activity);
        boolean[] state = initialChecked.clone();
        int useAccent = accent != 0 ? accent : 0xFF4ADE80;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        applyPanelSurface(box, d, 24f, true, false);
        box.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(activity, 8), 0, dp(activity, 8), dp(activity, 10));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(activity, 16));
        clipScrollContainers(scroll);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 6));
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));

        int maxScrollH = dp(activity, 260);
        int scrollH = items.length * dp(activity, 46) > maxScrollH ? maxScrollH : ViewGroup.LayoutParams.WRAP_CONTENT;
        box.addView(scroll, new LinearLayout.LayoutParams(-1, scrollH));

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(activity, 6), dp(activity, 12), dp(activity, 6), dp(activity, 2));
        footer.setClipChildren(false);
        footer.setClipToPadding(false);

        Dialog dialog = createFrostedDialog(activity, box, 460, parentModal);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
            row.setBackground(makeChoiceBg(d, 14f, useAccent));
            row.setSelected(state[idx]);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);

            TextView tv = new TextView(activity);
            tv.setText(items[i]);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15.5f);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView mark = new TextView(activity);
            mark.setText(state[idx] ? "\u2713" : "");
            mark.setTextColor(useAccent);
            mark.setTextSize(16f);
            mark.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(mark, new LinearLayout.LayoutParams(-2, -2));

            attachRowFocus(row, d, 14f, state[idx] ? useAccent : 0);
            row.setOnClickListener(v -> {
                state[idx] = !state[idx];
                row.setSelected(state[idx]);
                mark.setText(state[idx] ? "\u2713" : "");
                configureFocus(row, 1.015f, 0f, 14f, state[idx] ? useAccent : 0);
                FocusRing ring = DIALOG_RINGS.get(dialog);
                if (ring != null) ring.refresh();
                pulseClick(row, 1.015f);
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rlp.topMargin = dp(activity, 6);
            rows.addView(row, rlp);
        }

        TextView btnNeg = makePillButton(activity, negativeLabel, 0);
        btnNeg.setOnClickListener(v -> dismissFrostedDialog(dialog));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, dp(activity, 38));
        nlp.rightMargin = dp(activity, 10);
        footer.addView(btnNeg, nlp);

        TextView btnPos = makePillButton(activity, positiveLabel, useAccent);
        btnPos.setOnClickListener(v -> onConfirm.accept(state.clone()));
        footer.addView(btnPos, new LinearLayout.LayoutParams(-2, dp(activity, 38)));
        box.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        dialog.show();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
        return dialog;
    }

    // ---- Shared panel drawable (Android 17 Expressive translucent frosted glass) -----------------

    private static BitmapShader noiseShader() {
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
            Bitmap bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            sharedNoiseShader = new BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        return sharedNoiseShader;
    }

    private static final class PanelDrawable extends Drawable {
        private final WeakReference<View> hostRef;
        private final float density;
        private float cornerRadiusPx;
        private boolean smoked;
        private int seedRgb;
        private boolean overSharpBackdrop;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerShadowBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF insetRect = new RectF();
        private final int[] scrLoc = new int[2];
        private final Matrix rootMatrix = new Matrix();
        private final float[] rootMatrixVals = new float[9];
        private final Matrix tempMatrix = new Matrix();
        private final float[] matrixVals = new float[9];
        private RuntimeShader liquidShader;
        private boolean liquidShaderFailed;
        private int boundBackdropVersion = -1;
        private float rimR, rimG, rimB;
        private int liquidAlpha = 255;
        private int lastW = -1, lastH = -1;
        private boolean lastEffectiveSmoked;
        boolean useRestingCoords;
        boolean suppressDraw;

        PanelDrawable(View host, float density, float cornerRadiusPx, boolean smoked, int seedRgb, boolean overSharpBackdrop) {
            this.hostRef = host != null ? new WeakReference<>(host) : null;
            this.density = density;
            this.cornerRadiusPx = cornerRadiusPx;
            this.smoked = smoked;
            this.seedRgb = seedRgb & 0x00FFFFFF;
            this.overSharpBackdrop = overSharpBackdrop;
            updateRimRgb();

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

        private void updateRimRgb() {
            int rimTint = tonalRimTint(this.seedRgb);
            this.rimR = ((rimTint >> 16) & 0xFF) / 255f;
            this.rimG = ((rimTint >> 8) & 0xFF) / 255f;
            this.rimB = (rimTint & 0xFF) / 255f;
        }

        void updateConfig(float radiusPx, boolean smoked, int seedRgb, boolean overSharpBackdrop) {
            this.cornerRadiusPx = radiusPx;
            this.smoked = smoked;
            this.seedRgb = seedRgb & 0x00FFFFFF;
            this.overSharpBackdrop = overSharpBackdrop;
            updateRimRgb();
            this.lastW = -1;
            invalidateSelf();
        }

        private void ensureShaders(RectF r, boolean effectiveSmoked) {
            int w = Math.round(r.width());
            int h = Math.round(r.height());
            if (w == lastW && h == lastH && effectiveSmoked == lastEffectiveSmoked) return;
            lastW = w;
            lastH = h;
            lastEffectiveSmoked = effectiveSmoked;

            int accent800 = tonalAccent800(seedRgb);
            int topBase = mixRgb(0x131720, accent800, 0.36f);
            int midBase = mixRgb(0x0B0E15, accent800, 0.28f);
            int botBase = mixRgb(0x07090E, accent800, 0.22f);
            int[] cols = effectiveSmoked
                    ? new int[]{(0xA4 << 24) | topBase, (0xB2 << 24) | midBase, (0xC0 << 24) | botBase}
                    : new int[]{(0x5C << 24) | topBase, (0x6A << 24) | midBase, (0x78 << 24) | botBase};
            int topWithCaustic = blendOverWhite(cols[0], 0x12);
            int stop18 = lerpArgb(cols[0], cols[1], 0.18f / 0.48f);
            fillPaint.setShader(new LinearGradient(
                    r.left, r.top, r.left, r.bottom,
                    new int[]{topWithCaustic, stop18, cols[1], cols[2]},
                    new float[]{0f, 0.18f, 0.48f, 1f}, Shader.TileMode.CLAMP));

            int bounce = tonalRimTint(seedRgb);
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
            RefractionBackdrop nested = nestedBackdrop;
            ViewGroup nestedSrc = nestedSourcePanel != null ? nestedSourcePanel.get() : null;
            RefractionBackdrop bg = (nested != null && !nested.sharp.isRecycled() && !nested.blur.isRecycled()
                    && !isSameOrDescendant(host, nestedSrc)) ? nested : activeBackdrop;
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
                rootMatrix.reset();
                rootView.transformMatrixToGlobal(rootMatrix);
                rootMatrix.getValues(rootMatrixVals);
                tempMatrix.reset();
                host.transformMatrixToGlobal(tempMatrix);
                tempMatrix.getValues(matrixVals);
                originX = (matrixVals[Matrix.MTRANS_X] - rootMatrixVals[Matrix.MTRANS_X]) + winOffX;
                originY = (matrixVals[Matrix.MTRANS_Y] - rootMatrixVals[Matrix.MTRANS_Y]) + winOffY;
                scaleX = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_X]));
                scaleY = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_Y]));
            }

            fillPaint.setDither(true);
            rimPaint.setDither(true);
            float w = r.width();
            float h = r.height();
            boolean optical = bg.overHome;
            float bezel = Math.min((optical ? 24f : 18f) * density, Math.min(w, h) * 0.24f);
            float refract = Math.min((optical ? 46f : 28f) * density, bezel * 1.95f);
            float chromatic = (optical ? 4.4f : 2.4f) * density;

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
                    interiorBlurOnly ? 0.0f : (optical ? 0.88f : 0.0f));
            liquidShader.setFloatUniform("uInteriorFill", interiorBlurOnly ? 1.0f : 0.0f);
            liquidShader.setFloatUniform("uLiquidMode", optical ? 1.0f : 0.0f);
            liquidShader.setFloatUniform("uRimTint", rimR, rimG, rimB);
            liquidShader.setFloatUniform("uWobble", 0.0f);
            liquidShader.setFloatUniform("uPhase", 0.0f);

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
        public void draw(Canvas canvas) {
            if (suppressDraw) return;
            Rect b = getBounds();
            if (b.isEmpty()) return;
            rect.set(b);
            float rad = Math.min(cornerRadiusPx, Math.min(rect.width(), rect.height()) * 0.5f);

            // For floating QuickActivity bars over sharp external apps/Home, lay down the silky blurred
            // backdrop first; if unavailable (DRM video), automatically fall back to rich smoked opacity.
            boolean drewInteriorBlur = false;
            if (overSharpBackdrop) {
                drewInteriorBlur = drawLiquidGlass(canvas, rect, rad, true);
            }

            // Layer 1: Single-pass Material 3 Expressive translucent glass body (with baked top specular caustic).
            ensureShaders(rect, smoked || (overSharpBackdrop && !drewInteriorBlur));
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
        @Override public void setColorFilter(ColorFilter cf) { fillPaint.setColorFilter(cf); invalidateSelf(); }
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---- Zero-dependency living wobbly Fresnel focus ring & damped spring controller -------------

    static final class FocusRing {
        private final FrameLayout host;
        private final View scope;
        private final float density;
        private final WobblyRingView glass;
        private final Rect workRect = new Rect();
        private View currentTarget;
        private boolean shown;
        private boolean attached;

        // Pure-Java damped harmonic spring (matches SpringForce stiffness=310, dampingRatio=0.68).
        private float posX, posY, targetX, targetY, velX, velY;
        private boolean springing;
        private long lastSpringNs;
        private final Runnable springTick = this::stepSpring;

        private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = this::onFocusChanged;
        private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::onScrollChanged;
        private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onGlobalLayout;

        FocusRing(FrameLayout host, View scope, float density) {
            this.host = host;
            this.scope = scope;
            this.density = density;
            host.setClipChildren(false);
            host.setClipToPadding(false);
            clipScrollContainers(scope);

            glass = new WobblyRingView(host.getContext());
            glass.setAlpha(0f);
            glass.setFocusable(false);
            glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            host.addView(glass, new FrameLayout.LayoutParams(10, 10));

            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { bind(); snapInitial(); }
                @Override public void onViewDetachedFromWindow(View v) { unbind(); }
            });
            if (host.isAttachedToWindow()) {
                bind();
                snapInitial();
            }
        }

        void setRenderEffect(RenderEffect effect) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                glass.setRenderEffect(effect);
            }
        }

        private void snapInitial() {
            host.post(() -> {
                View now = scope.findFocus();
                if (now != null && now != glass && isDescendantOf(now, scope)) moveTo(now, false);
            });
        }

        private void bind() {
            clipScrollContainers(scope);
            if (attached) return;
            ViewTreeObserver vto = host.getViewTreeObserver();
            if (!vto.isAlive()) return;
            attached = true;
            vto.addOnGlobalFocusChangeListener(focusListener);
            vto.addOnScrollChangedListener(scrollListener);
            vto.addOnGlobalLayoutListener(layoutListener);
        }

        private void unbind() {
            if (!attached) return;
            attached = false;
            stopSpring();
            glass.resetMotion();
            ViewTreeObserver vto = host.getViewTreeObserver();
            if (vto.isAlive()) {
                vto.removeOnGlobalFocusChangeListener(focusListener);
                vto.removeOnScrollChangedListener(scrollListener);
                vto.removeOnGlobalLayoutListener(layoutListener);
            }
        }

        private void onFocusChanged(View oldF, View newF) {
            if (newF != null && newF != glass && isDescendantOf(newF, scope)) {
                moveTo(newF, true);
            } else if (newF == null || !isDescendantOf(newF, scope)) {
                hide();
            }
        }

        private void onScrollChanged() {
            if (shown && currentTarget != null && currentTarget.isAttachedToWindow()) {
                snapForScroll(currentTarget);
            }
        }

        private void onGlobalLayout() {
            if (currentTarget != null && currentTarget.isAttachedToWindow() && currentTarget.isFocused()) {
                if (!shown) moveTo(currentTarget, false);
                else snapForScroll(currentTarget);
            }
        }

        void refresh() {
            View f = scope.findFocus();
            if (f != null && f != glass && isDescendantOf(f, scope)) {
                moveTo(f, false);
            }
        }

        void hide() {
            currentTarget = null;
            if (!shown) return;
            shown = false;
            stopSpring();
            glass.resetMotion();
            glass.animate().cancel();
            glass.animate().alpha(0f).setDuration(140L).start();
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
            float pad = INSET_DP * density;
            float targetW = workRect.width() * spec.scale;
            float targetH = workRect.height() * spec.scale;
            int gw = Math.round(targetW + 2f * pad);
            int gh = Math.round(targetH + 2f * pad);
            float radiusPx = resolveRadiusPx(spec, targetW, targetH, density);

            ViewGroup.LayoutParams lp = glass.getLayoutParams();
            if (lp.width != gw || lp.height != gh) {
                lp.width = gw;
                lp.height = gh;
                glass.setLayoutParams(lp);
            }
            glass.setRadius(radiusPx);
            glass.setAccent(spec.accent);

            float x = workRect.exactCenterX() - gw / 2f;
            float y = workRect.exactCenterY() - gh / 2f - spec.liftDp * density;

            if (!shown || !animate) {
                stopSpring();
                glass.resetMotion();
                posX = targetX = x;
                posY = targetY = y;
                glass.setTranslationX(x);
                glass.setTranslationY(y);
                glass.animate().cancel();
                if (!shown) glass.animate().alpha(1f).setDuration(150L).start();
                else glass.setAlpha(1f);
                shown = true;
            } else {
                glass.animate().cancel();
                glass.setAlpha(1f);
                glass.onJump(x - posX, y - posY);
                targetX = x;
                targetY = y;
                startSpring();
            }
        }

        private void snapForScroll(View target) {
            if (target.getWidth() <= 0 || target.getHeight() <= 0) return;
            if (!locateInHost(host, target, workRect)) return;
            Spec spec = readSpec(target);
            float pad = INSET_DP * density;
            float targetW = workRect.width() * spec.scale;
            float targetH = workRect.height() * spec.scale;
            int gw = Math.round(targetW + 2f * pad);
            int gh = Math.round(targetH + 2f * pad);
            ViewGroup.LayoutParams lp = glass.getLayoutParams();
            if (lp.width != gw || lp.height != gh) {
                lp.width = gw;
                lp.height = gh;
                glass.setLayoutParams(lp);
            }
            glass.setRadius(resolveRadiusPx(spec, targetW, targetH, density));
            glass.setAccent(spec.accent);
            float x = workRect.exactCenterX() - gw / 2f;
            float y = workRect.exactCenterY() - gh / 2f - spec.liftDp * density;
            targetX = x;
            targetY = y;
            if (!springing) {
                posX = x;
                posY = y;
                glass.setTranslationX(x);
                glass.setTranslationY(y);
            }
        }

        private void startSpring() {
            if (!springing) {
                springing = true;
                lastSpringNs = System.nanoTime();
                glass.postOnAnimation(springTick);
            }
        }

        private void stopSpring() {
            springing = false;
            velX = velY = 0f;
            glass.removeCallbacks(springTick);
        }

        private void stepSpring() {
            if (!springing) return;
            long nowNs = System.nanoTime();
            float defaultDt = 1f / displayRefreshRate(glass);
            float dt = lastSpringNs == 0L ? defaultDt : clamp((nowNs - lastSpringNs) * 1e-9f, 0.001f, 0.05f);
            lastSpringNs = nowNs;

            // Sub-stepped symplectic Euler (stiffness=310, dampingRatio=0.68) — identical feel across 50-240 Hz.
            final float k = 310f;
            final float c = 23.95f;
            float remaining = dt;
            while (remaining > 0f) {
                float step = Math.min(remaining, 0.004f);
                float ax = k * (targetX - posX) - c * velX;
                float ay = k * (targetY - posY) - c * velY;
                velX += ax * step;
                velY += ay * step;
                posX += velX * step;
                posY += velY * step;
                remaining -= step;
            }

            glass.setTranslationX(posX);
            glass.setTranslationY(posY);
            glass.setVelocity(velX, velY);

            if (Math.abs(targetX - posX) < 0.35f && Math.abs(targetY - posY) < 0.35f
                    && Math.abs(velX) < 4f && Math.abs(velY) < 4f) {
                posX = targetX;
                posY = targetY;
                velX = velY = 0f;
                springing = false;
                glass.setTranslationX(posX);
                glass.setTranslationY(posY);
                glass.setVelocity(0f, 0f);
            } else {
                glass.postOnAnimation(springTick);
            }
        }

        private static Spec readSpec(View v) {
            Spec s = SPECS.get(v);
            if (s != null) return s;
            return v instanceof EditText ? EDIT_TEXT_SPEC : DEFAULT_SPEC;
        }

        private static float resolveRadiusPx(Spec spec, float scaledW, float scaledH, float density) {
            float halfMin = Math.min(scaledW, scaledH) * 0.5f;
            if (spec.radiusDp >= 0f) {
                return Math.min(halfMin, spec.radiusDp * density * spec.scale);
            }
            if (spec.radiusDp == RADIUS_CAPSULE) return halfMin;
            if (Math.abs(scaledW - scaledH) <= 2f * density || scaledH <= 54f * density) return halfMin;
            return Math.min(halfMin, 16f * density);
        }

        private static boolean locateInHost(View host, View target, Rect out) {
            float x = 0f, y = 0f;
            View cur = target;
            while (cur != null && cur != host) {
                x += cur.getLeft();
                y += cur.getTop();
                if (cur != target) {
                    x += cur.getTranslationX();
                    y += cur.getTranslationY();
                }
                ViewParent p = cur.getParent();
                if (!(p instanceof View)) return false;
                cur = (View) p;
                x -= cur.getScrollX();
                y -= cur.getScrollY();
            }
            if (cur != host) return false;
            int ix = Math.round(x);
            int iy = Math.round(y);
            out.set(ix, iy, ix + target.getWidth(), iy + target.getHeight());
            return true;
        }

        private static boolean isDescendantOf(View view, View ancestor) {
            View cur = view;
            while (cur != null) {
                if (cur == ancestor) return true;
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
            return false;
        }
    }

    /** Exact optical Fresnel liquid-glass ring matching the launcher's GlassView. */
    private static final class WobblyRingView extends View {
        private final Paint flightFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint contrastRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerCaustic = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF rect = new RectF();
        private final RectF workRect = new RectF();
        private final Path cardPath = new Path();
        private final float density;
        private float radius;
        private int primaryRgb = 0xEAF0FA;
        private int secondaryRgb = 0xDCE6F7;
        private boolean accented;
        private int auraBaseAlpha = 0x5A;
        private int haloBaseAlpha = 0x42;
        private boolean shadersDirty = true;

        private boolean animActive;
        private float rawVx, rawVy, smoothVx, smoothVy;
        private float wobX, wobY, wobVx, wobVy, wavePhase, energy;
        private long lastFrameNs;
        private float frameE, frameSx, frameSy, frameCx, frameCy;
        private float frameBiasX, frameBiasY;
        private float frameTopDip, frameBotDip, frameLeftDip, frameRightDip;

        WobblyRingView(Context ctx) {
            super(ctx);
            density = ctx.getResources().getDisplayMetrics().density;
            radius = 16f * density;

            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(0x36000000);
            shadowPaint.setMaskFilter(new BlurMaskFilter(8.5f * density, BlurMaskFilter.Blur.NORMAL));

            auraPaint.setStyle(Paint.Style.STROKE);
            auraPaint.setStrokeWidth(6.5f * density);
            auraPaint.setMaskFilter(new BlurMaskFilter(11.5f * density, BlurMaskFilter.Blur.NORMAL));

            haloPaint.setStyle(Paint.Style.STROKE);
            haloPaint.setStrokeWidth(2.6f * density);
            haloPaint.setMaskFilter(new BlurMaskFilter(3.6f * density, BlurMaskFilter.Blur.NORMAL));

            contrastRim.setStyle(Paint.Style.STROKE);
            contrastRim.setStrokeWidth(0.8f * density);
            contrastRim.setColor(0x2E000000);

            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(2.15f * density);
            rim.setColor(0xF5FFFFFF);

            innerCaustic.setStyle(Paint.Style.STROKE);
            innerCaustic.setStrokeWidth(0.9f * density);

            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void setVelocity(float vx, float vy) {
            rawVx = vx;
            rawVy = vy;
            if (Math.abs(vx) > 12f || Math.abs(vy) > 12f) wakeLiquid();
        }

        void onJump(float dxPx, float dyPx) {
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

        void resetMotion() {
            rawVx = rawVy = smoothVx = smoothVy = wobX = wobY = wobVx = wobVy = energy = 0f;
            if (animActive) {
                animActive = false;
                setLayerType(LAYER_TYPE_HARDWARE, null);
                invalidate();
            }
        }

        private void wakeLiquid() {
            if (!animActive) {
                animActive = true;
                lastFrameNs = System.nanoTime();
                setLayerType(LAYER_TYPE_NONE, null);
                postInvalidateOnAnimation();
            }
        }

        void setAccent(int argb) {
            int nextP, nextS, nextA, nextH;
            boolean nextAcc;
            if (argb == 0) {
                nextP = 0xEAF0FA;
                nextS = 0xDCE6F7;
                nextAcc = false;
                nextA = 0x5A;
                nextH = 0x42;
            } else {
                nextP = refineFocusAccent(argb);
                nextS = mixRgb(nextP, 0xFFFFFF, 0.20f);
                nextAcc = true;
                nextA = 0xA2;
                nextH = 0x6E;
            }
            if (nextP == primaryRgb && nextS == secondaryRgb && nextAcc == accented) return;
            primaryRgb = nextP;
            secondaryRgb = nextS;
            accented = nextAcc;
            auraBaseAlpha = nextA;
            haloBaseAlpha = nextH;
            shadersDirty = true;
            invalidate();
        }

        void setRadius(float px) {
            if (Math.abs(radius - px) < 0.05f) return;
            radius = px;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            shadersDirty = true;
        }

        private void rebuildShaders(RectF r) {
            shadersDirty = false;
            int c0 = 0xFCFFFFFF;
            int c1 = accented ? (0xC6000000 | primaryRgb) : 0xACFFFFFF;
            int c2 = 0x52FFFFFF;
            int c3 = accented ? (0xA4000000 | secondaryRgb) : 0x8AFFFFFF;
            int c4 = accented ? (0xEA000000 | secondaryRgb) : 0xE2FFFFFF;
            rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{c0, c1, c2, c3, c4}, new float[]{0f, 0.24f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            innerCaustic.setShader(new LinearGradient(r.left, r.top, r.left, r.top + Math.max(1f, r.height() * 0.25f),
                    0x64FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            LinearGradient glowGrad = new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{0xFF000000 | primaryRgb, 0xFF000000 | secondaryRgb}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            auraPaint.setShader(glowGrad);
            haloPaint.setShader(glowGrad);
            flightFill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{0x1EFFFFFF, 0x06FFFFFF, 0x14000000 | secondaryRgb}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        private void stepPhysics() {
            long nowNs = System.nanoTime();
            float defaultDt = 1f / displayRefreshRate(this);
            float dt = lastFrameNs == 0L ? defaultDt : clamp((nowNs - lastFrameNs) * 1e-9f, 0.001f, 0.05f);
            lastFrameNs = nowNs;
            float scale = Math.max(1f, 1500f * density);
            float targetNx = clamp(rawVx / scale, -1.35f, 1.35f);
            float targetNy = clamp(rawVy / scale, -1.35f, 1.35f);
            float prevVx = smoothVx, prevVy = smoothVy;
            float alpha = 1f - (float) Math.exp(-26.0 * dt);
            smoothVx += (targetNx - smoothVx) * alpha;
            smoothVy += (targetNy - smoothVy) * alpha;
            wobVx += -(smoothVx - prevVx) * 0.88f;
            wobVy += -(smoothVy - prevVy) * 0.88f;
            float decay = (float) Math.exp(-7.0 * dt);
            rawVx *= decay;
            rawVy *= decay;
            float remaining = dt;
            while (remaining > 0f) {
                float step = Math.min(remaining, 0.008f);
                wobVx += (-265f * wobX - 14.5f * wobVx) * step;
                wobVy += (-265f * wobY - 14.5f * wobVy) * step;
                wobX = clamp(wobX + wobVx * step, -1.2f, 1.2f);
                wobY = clamp(wobY + wobVy * step, -1.2f, 1.2f);
                remaining -= step;
            }
            wavePhase += dt * 15.0f;
            float speedE = (float) Math.hypot(smoothVx, smoothVy);
            float wobE = (float) Math.hypot(wobX, wobY) * 1.4f + (float) Math.hypot(wobVx, wobVy) * 0.05f;
            energy = Math.min(1.2f, Math.max(speedE, wobE));
            if (energy < 0.006f && Math.abs(rawVx) < 6f && Math.abs(rawVy) < 6f) {
                rawVx = rawVy = smoothVx = smoothVy = wobX = wobY = wobVx = wobVy = energy = 0f;
                animActive = false;
                post(() -> { if (!animActive) setLayerType(LAYER_TYPE_HARDWARE, null); });
            } else {
                postInvalidateOnAnimation();
            }
        }

        private void prepareBlobFrame(RectF base) {
            frameE = Math.min(1f, energy);
            float halfW = Math.max(1f, base.width() * 0.5f);
            float halfH = Math.max(1f, base.height() * 0.5f);
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
            float topSpan = (r - rTR) - (l + rTL);
            float rightSpan = (b - rBR) - (t + rTR);
            float botSpan = (r - rBR) - (l + rBL);
            float leftSpan = (b - rBL) - (t + rTL);
            float spanRef = 24f * density;

            path.reset();
            path.moveTo(l + rTL, t);
            if (topSpan > 1f) path.quadTo((l + r) * 0.5f, t + frameTopDip * Math.min(1f, topSpan / spanRef), r - rTR, t);
            path.cubicTo(r - rTR + cTR, t, r, t + rTR - cTR, r, t + rTR);
            if (rightSpan > 1f) path.quadTo(r - frameRightDip * Math.min(1f, rightSpan / spanRef), (t + b) * 0.5f, r, b - rBR);
            path.cubicTo(r, b - rBR + cBR, r - rBR + cBR, b, r - rBR, b);
            if (botSpan > 1f) path.quadTo((l + r) * 0.5f, b - frameBotDip * Math.min(1f, botSpan / spanRef), l + rBL, b);
            path.cubicTo(l + rBL - cBL, b, l, b - rBL + cBL, l, b - rBL);
            if (leftSpan > 1f) path.quadTo(l + frameLeftDip * Math.min(1f, leftSpan / spanRef), (t + b) * 0.5f, l, t + rTL);
            path.cubicTo(l, t + rTL - cTL, l + rTL - cTL, t, l + rTL, t);
            path.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (animActive) stepPhysics();
            float inset = INSET_DP * density;
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            if (rect.width() <= 1f || rect.height() <= 1f) return;
            if (shadersDirty) rebuildShaders(rect);

            boolean moving = animActive && energy >= 0.006f;
            float e = moving ? Math.min(1f, energy) : 0f;
            float settle = 1f - clamp(e * 4.5f, 0f, 1f);

            float out = 2.0f * density;
            float glowHw, glowHh, glowCx, glowCy, glowR;
            if (moving) {
                prepareBlobFrame(rect);
                buildBlobPath(cardPath, rect, radius, 0f);
                float maxExtra = (INSET_DP - 6f) * density;
                glowHw = Math.min(rect.width() * 0.5f + maxExtra, rect.width() * 0.5f * (1f + frameSx));
                glowHh = Math.min(rect.height() * 0.5f + maxExtra, rect.height() * 0.5f * (1f + frameSy));
                glowCx = frameCx;
                glowCy = frameCy;
                float fullR = Math.min(glowHw, glowHh);
                float flightTargetR = Math.max(radius, fullR * 0.80f);
                glowR = clamp(radius + (flightTargetR - radius) * Math.min(1f, frameE * 0.50f), 4f, fullR);
            } else {
                cardPath.reset();
                cardPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
                glowHw = rect.width() * 0.5f;
                glowHh = rect.height() * 0.5f;
                glowCx = rect.centerX();
                glowCy = rect.centerY();
                glowR = radius;
            }

            // Draw blurred aura/halo as RoundRects so Skia hits analytic GPU GrRRectBlurEffect (zero CPU blur).
            int save = canvas.save();
            canvas.clipOutPath(cardPath);
            if (settle > 0.02f) {
                shadowPaint.setAlpha(Math.round(0x36 * settle));
                workRect.set(rect.left - density, rect.top + 2.5f * density,
                        rect.right + density, rect.bottom + 5.5f * density);
                canvas.drawRoundRect(workRect, radius + density, radius + density, shadowPaint);
            }
            float bloom = 1.0f + 0.14f * e;
            auraPaint.setAlpha(Math.min(255, Math.round(auraBaseAlpha * bloom)));
            workRect.set(glowCx - glowHw - out, glowCy - glowHh - out,
                    glowCx + glowHw + out, glowCy + glowHh + out);
            canvas.drawRoundRect(workRect, glowR + out, glowR + out, auraPaint);
            haloPaint.setAlpha(Math.min(255, Math.round(haloBaseAlpha * bloom)));
            workRect.set(glowCx - glowHw, glowCy - glowHh, glowCx + glowHw, glowCy + glowHh);
            canvas.drawRoundRect(workRect, glowR, glowR, haloPaint);
            canvas.restoreToCount(save);

            if (e > 0.03f) {
                flightFill.setAlpha(Math.round(e * 175f));
                canvas.drawPath(cardPath, flightFill);
            }

            float fineSettle = settle * settle;
            if (fineSettle > 0.02f) {
                contrastRim.setAlpha(Math.round(0x2E * fineSettle));
                float outerOffset = rim.getStrokeWidth() * 0.5f + contrastRim.getStrokeWidth() * 0.5f;
                canvas.drawRoundRect(rect.left - outerOffset, rect.top - outerOffset,
                        rect.right + outerOffset, rect.bottom + outerOffset,
                        radius + outerOffset, radius + outerOffset, contrastRim);
            }

            canvas.drawPath(cardPath, rim);

            if (fineSettle > 0.02f) {
                innerCaustic.setAlpha(Math.round(255f * fineSettle));
                float lip = rim.getStrokeWidth() * 0.5f + innerCaustic.getStrokeWidth() * 0.7f;
                workRect.set(rect.left + lip, rect.top + lip, rect.right - lip, rect.bottom - lip);
                float innerR = Math.max(0f, radius - lip);
                int cSave = canvas.save();
                canvas.clipRect(workRect.left, workRect.top, workRect.right, workRect.top + workRect.height() * 0.25f);
                canvas.drawRoundRect(workRect, innerR, innerR, innerCaustic);
                canvas.restoreToCount(cSave);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            resetMotion();
            super.onDetachedFromWindow();
        }
    }

    // ---- Color math utilities --------------------------------------------------------------------

    private static int tonalAccent800(int seedRgb) {
        float[] hsv = new float[3];
        rgbToHsv((seedRgb >> 16) & 0xFF, (seedRgb >> 8) & 0xFF, seedRgb & 0xFF, hsv);
        float sat = clamp(hsv[1] * 0.72f, 0.20f, 0.44f);
        float val = clamp(0.22f + hsv[2] * 0.09f, 0.22f, 0.31f);
        return hsvToRgb(hsv[0], sat, val);
    }

    private static int tonalRimTint(int seedRgb) {
        float[] hsv = new float[3];
        rgbToHsv((seedRgb >> 16) & 0xFF, (seedRgb >> 8) & 0xFF, seedRgb & 0xFF, hsv);
        return hsvToRgb(hsv[0], clamp(hsv[1] * 0.38f, 0.08f, 0.28f), 0.92f);
    }

    private static int refineFocusAccent(int argb) {
        int rgb = argb & 0x00FFFFFF;
        if (rgb == 0) return 0xE8EEF8;
        float[] hsv = new float[3];
        rgbToHsv((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsv);
        if (hsv[1] < 0.08f) return 0xE8EEF8;
        hsv[1] = clamp(hsv[1] * 0.74f, 0.18f, 0.62f);
        hsv[2] = clamp(0.68f + hsv[2] * 0.24f, 0.68f, 0.94f);
        return mixRgb(hsvToRgb(hsv[0], hsv[1], hsv[2]), 0xF0F5FF, 0.24f);
    }

    private static int mixRgb(int a, int b, float t) {
        float k = clamp(t, 0f, 1f);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (clampByte(ar + (br - ar) * k) << 16) | (clampByte(ag + (bg - ag) * k) << 8) | clampByte(ab + (bb - ab) * k);
    }

    private static void rgbToHsv(int r, int g, int b, float[] out) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min, h;
        if (delta <= 1e-5f) h = 0f;
        else if (max == rf) h = 60f * (((gf - bf) / delta) % 6f);
        else if (max == gf) h = 60f * (((bf - rf) / delta) + 2f);
        else h = 60f * (((rf - gf) / delta) + 4f);
        if (h < 0f) h += 360f;
        out[0] = h;
        out[1] = max <= 1e-5f ? 0f : delta / max;
        out[2] = max;
    }

    private static int hsvToRgb(float h, float s, float v) {
        float hh = ((h % 360f) + 360f) % 360f;
        float c = v * s, x = c * (1f - Math.abs(((hh / 60f) % 2f) - 1f)), m = v - c;
        float rf = 0f, gf = 0f, bf = 0f;
        if (hh < 60f) { rf = c; gf = x; }
        else if (hh < 120f) { rf = x; gf = c; }
        else if (hh < 180f) { gf = c; bf = x; }
        else if (hh < 240f) { gf = x; bf = c; }
        else if (hh < 300f) { rf = x; bf = c; }
        else { rf = c; bf = x; }
        return (clampByte((rf + m) * 255f) << 16) | (clampByte((gf + m) * 255f) << 8) | clampByte((bf + m) * 255f);
    }

    private static float clamp(float v, float min, float max) { return v < min ? min : (v > max ? max : v); }
    private static int clampByte(float v) { int i = Math.round(v); return i < 0 ? 0 : (i > 255 ? 255 : i); }
}
