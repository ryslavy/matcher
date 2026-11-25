package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.IntSupplier;

/**
 * Top-bar pill background with real frosted-glass blurred wallpaper backdrop (matching DockDecoration),
 * top specular sheen and dual-tone rim at rest, switching cleanly to Apple TV white while focused.
 * Optionally takes an accent colour (for the VPN status pill) so even status pills keep live frosted blur.
 */
final class FrostedPillDrawable extends Drawable {

    private static Bitmap sharedBackdropBmp;
    private static BitmapShader sharedBackdropShader;
    private static Bitmap sharedHeroBmp;
    private static BitmapShader sharedHeroShader;

    static void clearSharedShaders() {
        sharedBackdropBmp = null;
        sharedBackdropShader = null;
        sharedHeroBmp = null;
        sharedHeroShader = null;
    }

    private final View host;
    private final FrameLayout root;
    private final WallpaperPlayer wallpaperPlayer;
    @Nullable
    private final ImageView heroArt;
    @Nullable
    private final IntSupplier ambientColorSupplier;
    private final float density;
    private final float cornerRadiusPx;
    private final int accentColor;

    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint heroPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ambientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerContrast = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private Paint noise;
    private final Paint focusedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activatedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activatedRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF strokeRect = new RectF();
    private final RectF shadowRect = new RectF();
    private final int[] rootPos = new int[2];
    private final int[] scrLoc = new int[2];
    private final Matrix shaderMatrix = new Matrix();
    private final Matrix tempMatrix = new Matrix();
    private final float[] matrixVals = new float[9];
    @Nullable
    private RuntimeShader liquidShader;
    private boolean liquidFailed;
    private int boundBackdropVersion = -1;
    private int lastRimRgb = -1;
    private float cachedRimR = 1f, cachedRimG = 1f, cachedRimB = 1f;
    private int liquidAlpha = 255;

    private boolean focused;
    private boolean activated;
    private int shaderHeight = -1;
    private int shaderWidth = -1;
    private int shaderSeed = -1;

    FrostedPillDrawable(View host, FrameLayout root, WallpaperPlayer wallpaperPlayer,
                        @Nullable ImageView heroArt, @Nullable IntSupplier ambientColorSupplier,
                        float density, float cornerRadiusPx) {
        this(host, root, wallpaperPlayer, heroArt, ambientColorSupplier, density, cornerRadiusPx, 0);
    }

    FrostedPillDrawable(View host, FrameLayout root, WallpaperPlayer wallpaperPlayer,
                        @Nullable ImageView heroArt, @Nullable IntSupplier ambientColorSupplier,
                        float density, float cornerRadiusPx, int accentColor) {
        this.host = host;
        this.root = root;
        this.wallpaperPlayer = wallpaperPlayer;
        this.heroArt = heroArt;
        this.ambientColorSupplier = ambientColorSupplier;
        this.density = density;
        this.cornerRadiusPx = cornerRadiusPx;
        this.accentColor = accentColor;

        outerContrast.setStyle(Paint.Style.STROKE);
        outerContrast.setStrokeWidth(0.8f * density);
        outerContrast.setColor(0x32000000);

        rim.setStyle(Paint.Style.STROKE);
        if (accentColor != 0) {
            rim.setStrokeWidth(1.6f * density);
            rim.setColor(accentColor);
            focusedFill.setColor((0x2C << 24) | (accentColor & 0xFFFFFF));
        } else {
            rim.setStrokeWidth(1.25f * density);
            focusedFill.setColor(0x1EFFFFFF);
        }

        activatedFill.setColor(0x5C6EC1FF);
        activatedRim.setStyle(Paint.Style.STROKE);
        activatedRim.setStrokeWidth(1.5f * density);
        activatedRim.setColor(0xFF6EC1FF);
    }

    /**
     * Computes host's unscrolled top-left position inside root. Unlike ViewGroup.offsetDescendantRectToMyCoords,
     * this intentionally ignores host's own scrollX/scrollY: singleLine centered TextViews (such as btnWifi and
     * btnVpn) scroll their internal layout to ~524k px, while View.drawBackground draws at host's unscrolled box.
     */
    private boolean locateInRoot(int[] out) {
        int x = 0, y = 0;
        View cur = host;
        while (cur != null && cur != root) {
            x += cur.getLeft() + Math.round(cur.getTranslationX());
            y += cur.getTop() + Math.round(cur.getTranslationY());
            ViewParent parent = cur.getParent();
            if (!(parent instanceof View)) return false;
            cur = (View) parent;
            if (cur != root) {
                x -= cur.getScrollX();
                y -= cur.getScrollY();
            }
        }
        if (cur != root) return false;
        out[0] = x;
        out[1] = y;
        return true;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] stateSet) {
        boolean nextFocused = false;
        boolean nextActivated = false;
        for (int s : stateSet) {
            if (s == android.R.attr.state_focused) nextFocused = true;
            else if (s == android.R.attr.state_activated) nextActivated = true;
        }
        if (nextFocused == focused && nextActivated == activated) return false;
        focused = nextFocused;
        activated = nextActivated;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        rect.set(b);
        float r = Math.min(cornerRadiusPx, Math.min(rect.width(), rect.height()) * 0.5f);

        // Soft outer elevation shadow matching DockDecoration (keep live frosted glass even when focused!).
        shadow.setColor(0x26000000);
        shadowRect.set(rect);
        shadowRect.inset(-1f * density, -1f * density);
        shadowRect.offset(0, 3f * density);
        c.drawRoundRect(shadowRect, r + density, r + density, shadow);

        // True frosted-glass backdrop: anti-aliased shared BitmapShader aligned to this pill's exact coordinates in root.
        Bitmap blurred = wallpaperPlayer != null ? wallpaperPlayer.blurred() : null;
        if (blurred != null && !blurred.isRecycled() && host.getWidth() > 0 && root.getWidth() > 0 && root.getHeight() > 0
                && locateInRoot(rootPos)) {
            if (blurred != sharedBackdropBmp || sharedBackdropShader == null) {
                sharedBackdropBmp = blurred;
                sharedBackdropShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sharedBackdropShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                }
            }
            backdropPaint.setDither(true);
            fill.setDither(true);
            if (backdropPaint.getShader() != sharedBackdropShader) {
                backdropPaint.setShader(sharedBackdropShader);
            }
            float zoom = ExpressiveGlass.REFRACTION_ZOOM;
            shaderMatrix.reset();
            shaderMatrix.setScale(root.getWidth() / (float) blurred.getWidth(),
                    root.getHeight() / (float) blurred.getHeight());
            shaderMatrix.postTranslate(-rootPos[0], -rootPos[1]);
            shaderMatrix.postScale(zoom, zoom, rect.centerX(), rect.centerY());
            sharedBackdropShader.setLocalMatrix(shaderMatrix);
            c.drawRoundRect(rect, r, r, backdropPaint);
            // Composite cinematic heroArt (already softly blurred) so glass tint matches the live screen behind it.
            if (heroArt != null && heroArt.getVisibility() == View.VISIBLE && heroArt.getAlpha() > 0f
                    && heroArt.getDrawable() instanceof BitmapDrawable) {
                Bitmap heroBmp = ((BitmapDrawable) heroArt.getDrawable()).getBitmap();
                if (heroBmp != null && !heroBmp.isRecycled() && heroBmp.getWidth() > 0 && heroBmp.getHeight() > 0) {
                    if (heroBmp != sharedHeroBmp || sharedHeroShader == null) {
                        sharedHeroBmp = heroBmp;
                        sharedHeroShader = new BitmapShader(heroBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            sharedHeroShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                        }
                    }
                    heroPaint.setDither(true);
                    if (heroPaint.getShader() != sharedHeroShader) {
                        heroPaint.setShader(sharedHeroShader);
                    }
                    shaderMatrix.reset();
                    shaderMatrix.setScale(root.getWidth() / (float) heroBmp.getWidth(),
                            root.getHeight() / (float) heroBmp.getHeight());
                    shaderMatrix.postTranslate(-rootPos[0], -rootPos[1]);
                    shaderMatrix.postScale(zoom, zoom, rect.centerX(), rect.centerY());
                    sharedHeroShader.setLocalMatrix(shaderMatrix);
                    heroPaint.setAlpha(Math.round(heroArt.getAlpha() * 255));
                    c.drawRoundRect(rect, r, r, heroPaint);
                }
            }
            if (ambientColorSupplier != null) {
                int ambientArgb = ambientColorSupplier.getAsInt();
                if (ambientArgb != 0) {
                    ambientPaint.setColor(ambientArgb);
                    c.drawRoundRect(rect, r, r, ambientPaint);
                }
            }
        }

        if (activated) {
            c.drawRoundRect(rect, r, r, activatedFill);
            drawLiquidRim(c, r, 0x6EC1FF);
            c.drawRoundRect(rect, r, r, activatedRim);
            return;
        }

        int w = b.width();
        int h = b.height();
        int seed = ExpressiveGlass.dynamicSeedRgb();
        if (w != shaderWidth || h != shaderHeight || seed != shaderSeed) {
            if (accentColor != 0) {
                fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{0x86141A24, 0x7A0B1018, 0x66080C14},
                        new float[]{0f, 0.30f, 1f}, Shader.TileMode.CLAMP));
            } else {
                int[] glassCols = ExpressiveGlass.dockGlassColors(seed);
                int bounce = ExpressiveGlass.tonalRimTint(seed);
                int topWithCaustic = blendOverWhite(glassCols[0], 0x18);
                int stop24 = lerpArgb(glassCols[0], glassCols[1], 0.24f / 0.48f);
                fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{topWithCaustic, stop24, glassCols[1], glassCols[2]},
                        new float[]{0f, 0.24f, 0.48f, 1f}, Shader.TileMode.CLAMP));
                rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{0x78FFFFFF, 0x24FFFFFF, 0x1AFFFFFF, (0x46 << 24) | bounce},
                        new float[]{0f, 0.35f, 0.70f, 1f}, Shader.TileMode.CLAMP));
            }
            fill.setDither(true);
            rim.setDither(true);
            shaderWidth = w;
            shaderHeight = h;
            shaderSeed = seed;
        }

        c.drawRoundRect(rect, r, r, fill);
        if (focused) {
            c.drawRoundRect(rect, r, r, focusedFill);
        }

        // Layer 2: Real-time Liquid Glass curved rim refraction + chromatic dispersion + Fresnel specular!
        int rimTintRgb = accentColor != 0 ? (accentColor & 0x00FFFFFF) : ExpressiveGlass.tonalRimTint(seed);
        boolean liquidDrawn = drawLiquidRim(c, r, rimTintRgb);

        if (!focused) {
            if (accentColor != 0) {
                float half = rim.getStrokeWidth() * 0.5f;
                strokeRect.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half);
                c.drawRoundRect(strokeRect, Math.max(0f, r - half), Math.max(0f, r - half), rim);
            } else if (!liquidDrawn) {
                c.drawRoundRect(rect, r, r, outerContrast);
                c.drawRoundRect(rect, r, r, rim);
            }
        }
    }

    /** Draws Layer 2: curved-bezel Liquid Glass edge refraction strictly on the circular/pill outer lip. */
    private boolean drawLiquidRim(Canvas c, float radius, int rimTintRgb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || liquidFailed) return false;
        if (!host.isAttachedToWindow()) return false;
        ExpressiveGlass.RefractionBackdrop bg = ExpressiveGlass.homeWallpaperRefraction();
        if (bg == null) return false;
        if (liquidShader == null) {
            liquidShader = ExpressiveGlass.acquireLiquidShader();
            boundBackdropVersion = -1;
            if (liquidShader == null) {
                liquidFailed = true;
                return false;
            }
        }
        View rootView = host.getRootView();
        rootView.getLocationOnScreen(scrLoc);
        tempMatrix.reset();
        host.transformMatrixToGlobal(tempMatrix);
        tempMatrix.getValues(matrixVals);
        float originX = matrixVals[Matrix.MTRANS_X] + scrLoc[0];
        float originY = matrixVals[Matrix.MTRANS_Y] + scrLoc[1];
        float scaleX = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_X]));
        float scaleY = Math.max(0.05f, Math.abs(matrixVals[Matrix.MSCALE_Y]));

        float w = rect.width();
        float h = rect.height();
        boolean optical = ExpressiveGlass.isHomeLiquidOpticsEnabled();
        float bezel = Math.min((optical ? 14f : 10.5f) * density, Math.min(w, h) * 0.34f);
        float refract = Math.min((optical ? 28f : 20f) * density, bezel * 2.0f);

        if (rimTintRgb != lastRimRgb) {
            cachedRimR = ((rimTintRgb >> 16) & 0xFF) / 255f;
            cachedRimG = ((rimTintRgb >> 8) & 0xFF) / 255f;
            cachedRimB = (rimTintRgb & 0xFF) / 255f;
            lastRimRgb = rimTintRgb;
        }
        if (boundBackdropVersion != bg.version) {
            liquidShader.setInputShader("uSharp", bg.sharpShader);
            liquidShader.setInputShader("uBlur", bg.blurShader);
            boundBackdropVersion = bg.version;
        }
        liquidShader.setFloatUniform("uPanelSize", w, h);
        liquidShader.setFloatUniform("uScreenOrigin", originX, originY);
        liquidShader.setFloatUniform("uScreenScale", scaleX, scaleY);
        liquidShader.setFloatUniform("uRadius", radius);
        liquidShader.setFloatUniform("uBezel", bezel);
        liquidShader.setFloatUniform("uRefract", refract);
        liquidShader.setFloatUniform("uChromatic", (optical ? 3.6f : 2.0f) * density);
        liquidShader.setFloatUniform("uEdgeClarity", optical ? 0.88f : 0.16f);
        liquidShader.setFloatUniform("uInteriorFill", 0.0f);
        liquidShader.setFloatUniform("uLiquidMode", optical ? 1.0f : 0.0f);
        liquidShader.setFloatUniform("uRimTint", cachedRimR, cachedRimG, cachedRimB);

        liquidPaint.setShader(null);
        liquidPaint.setShader(liquidShader);
        liquidPaint.setAlpha(liquidAlpha);
        ExpressiveGlass.drawBezelRing(c, rect.left, rect.top, rect.right, rect.bottom, radius, bezel, liquidPaint);
        return true;
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
                | (Math.round(ab + (bb - ab) * t));
    }

    @Override
    public void setAlpha(int alpha) {
        liquidAlpha = alpha;
        backdropPaint.setAlpha(alpha);
        fill.setAlpha(alpha);
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
        shadow.setAlpha(alpha);
        ambientPaint.setAlpha(alpha);
        focusedFill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
