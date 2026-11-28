package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntSupplier;

/** Draws the tvOS style frosted-glass band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint heroPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ambientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerContrast = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private Paint noise;
    private final RectF rect = new RectF();
    private final RectF shadowRect = new RectF();
    private final Rect gridOffset = new Rect();

    @Nullable
    private FrameLayout root;
    @Nullable
    private WallpaperPlayer wallpaperPlayer;
    @Nullable
    private ImageView heroArt;
    @Nullable
    private IntSupplier ambientColorSupplier;
    @Nullable
    private Bitmap cachedBackdropBmp;
    @Nullable
    private BitmapShader cachedBackdropShader;
    @Nullable
    private Bitmap cachedHeroBmp;
    @Nullable
    private BitmapShader cachedHeroShader;
    @Nullable
    private RuntimeShader dockLiquidShader;
    private boolean dockLiquidFailed;
    private int boundBackdropVersion = -1;
    private int lastRimSeed = -1;
    private float cachedRimR = 1f, cachedRimG = 1f, cachedRimB = 1f;
    private final Paint dockLiquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix dockLocalMatrix = new Matrix();
    @Nullable
    private ValueAnimator rippleAnim;
    private float rippleWobble;
    private float ripplePhase;

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** The band's local dimensions and dynamic wallpaper tint the glass gradients were built for. */
    private int shaderW = -1, shaderH = -1;
    private int shaderSeed = -1;
    private int shaderStyle = -1;
    private int shaderOpacity = -1;
    private int opacity = 100;
    private float cachedRadius = -1f;
    private LinearGradient bandShader, sheenShader, edgeShader;
    private final Matrix shaderMatrix = new Matrix();
    private final Paint overlayBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF overlayDst = new RectF();
    @Nullable
    private Bitmap overlayCache;
    private int overlayPadPx;
    /** Tight LAYER_TYPE_HARDWARE backdrop behind the grid so static dock glass & AGSL rim render ONCE into GPU VRAM. */
    @Nullable
    private DockBackdropView backdropView;
    /** Lightweight GPU-cheap tint + hero-art wash layered above backdropView so color glides at 60 FPS with 0 AGSL rebuilds. */
    @Nullable
    private DockTintOverlayView tintOverlayView;
    private final Rect dockClipRect = new Rect();
    private boolean backdropResizePosted;
    private final RectF cachedLocalRect = new RectF();
    private int cachedDockW = -1, cachedDockH = -1, cachedDockPad;
    private int cachedScreenX = Integer.MIN_VALUE, cachedScreenY = Integer.MIN_VALUE;
    private float cachedDockRadius = -1f;
    private int cachedBgVersion = -1, cachedDockSeed = -1, cachedDockStyle = -1, cachedDockOpacity = -1;
    private boolean cachedOpticalMode;
    @Nullable
    private Bitmap cachedBlurRef;
    private final boolean translucent;
    /** Draw the band and its edge (config.xml dock_band); false leaves the tiles floating on the wallpaper. */
    private final boolean bandVisible;
    /** single_row: the tiles after the dock share its row, so they take the same offsets to stay level with it. */
    private final boolean singleRow;
    /** dock_band_all: in a single row, the band runs behind every app instead of the dock apps only. */
    private final boolean bandAll;
    /** Compact multi-row mode (rows_below_dock >= 2 without dock names): tighten vertical dock inset above Row 1. */
    private final boolean compactMultiRow;

    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll) {
        this(dockSize, density, translucent, names, opacity, lightGlass, evenPadding, bandVisible, singleRow, bandAll, false);
    }

    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll, boolean compactMultiRow) {
        this.dockSize = dockSize;
        this.singleRow = singleRow;
        this.bandAll = singleRow && bandAll;
        this.compactMultiRow = compactMultiRow && !singleRow;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        this.evenPadding = evenPadding;
        this.bandVisible = bandVisible;
        this.translucent = translucent;
        this.opacity = Math.max(30, Math.min(100, opacity));
        band.setColor(translucent ? 0x8C121014 : (Math.round(this.opacity * 2.55f) << 24) | 0x141416);
        outerContrast.setStyle(Paint.Style.STROKE);
        outerContrast.setStrokeWidth(0.85f * density);
        outerContrast.setColor(0x32000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth((lightGlass ? 1.25f : 1f) * density);
        edge.setColor(lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /** Wires the live blurred wallpaper + hero artwork + ambient tint drawn via zero-clipPath BitmapShaders. */
    void setBackdropSources(FrameLayout root, WallpaperPlayer wallpaperPlayer,
                            @Nullable ImageView heroArt, @Nullable IntSupplier ambientColorSupplier) {
        this.root = root;
        this.wallpaperPlayer = wallpaperPlayer;
        this.heroArt = heroArt;
        this.ambientColorSupplier = ambientColorSupplier;
    }

    /** Attaches a tight hardware-cached backdrop View behind {@code grid} inside its FrameLayout wrapper. */
    void attachBackdropHost(RecyclerView grid) {
        if (!lightGlass || !bandVisible || grid == null) return;
        ViewParent parent = grid.getParent();
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout wrapper = (FrameLayout) parent;
        if (backdropView != null) {
            if (backdropView.getParent() == wrapper) return;
            if (backdropView.getParent() instanceof ViewGroup) {
                ((ViewGroup) backdropView.getParent()).removeView(backdropView);
            }
        }
        backdropView = new DockBackdropView(grid.getContext());
        backdropView.setClickable(false);
        backdropView.setFocusable(false);
        backdropView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        backdropView.setVisibility(View.INVISIBLE);
        backdropView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wrapper.addView(backdropView, 0, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));

        if (tintOverlayView != null && tintOverlayView.getParent() instanceof ViewGroup) {
            ((ViewGroup) tintOverlayView.getParent()).removeView(tintOverlayView);
        }
        tintOverlayView = new DockTintOverlayView(grid.getContext());
        tintOverlayView.setClickable(false);
        tintOverlayView.setFocusable(false);
        tintOverlayView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tintOverlayView.setVisibility(View.INVISIBLE);
        wrapper.addView(tintOverlayView, 1, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                syncBackdropPositionOnScroll(rv);
            }
        });
    }

    /** Updates dock band opacity live in-place for both plain and Liquid Glass docks (Tier 1 hot reload). */
    void setOpacity(int opacity) {
        int clamped = Math.max(30, Math.min(100, opacity));
        this.opacity = clamped;
        if (!translucent && !lightGlass) {
            band.setColor((Math.round(clamped * 2.55f) << 24) | 0x141416);
        }
        if (lightGlass) {
            shaderOpacity = -1;
            cachedDockOpacity = -1;
            invalidateBackdrop();
        }
    }

    /** Forces the cached hardware dock backdrop to repaint on the next draw (e.g. wallpaper/style update). */
    boolean invalidateBackdrop() {
        if (backdropView != null && backdropView.getVisibility() == View.VISIBLE) {
            backdropView.invalidate();
            if (tintOverlayView != null && tintOverlayView.getVisibility() == View.VISIBLE) {
                tintOverlayView.invalidate();
            }
            return true;
        }
        return false;
    }

    /**
     * Ultra-cheap (~0.08 ms) repaint of ONLY the smooth ambient color & hero-art wash over the Dock.
     * Never invalidates the heavy LAYER_TYPE_HARDWARE AGSL rim in DockBackdropView or RecyclerView!
     */
    boolean invalidateTintOnly() {
        if (tintOverlayView != null && tintOverlayView.getVisibility() == View.VISIBLE) {
            tintOverlayView.invalidate();
            return true;
        }
        return false;
    }

    /** Triggers a brief liquid-lens ripple across the Dock glass when focus moves between dock tiles. */
    void triggerRipple(boolean reducedMotion) {
        if (reducedMotion || !lightGlass || !bandVisible || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!ExpressiveGlass.isHomeLiquidOpticsEnabled()) return;
        if (rippleAnim != null) {
            rippleAnim.cancel();
            rippleAnim = null;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(320L);
        anim.setInterpolator(Motion.easeOutExpo());
        anim.addUpdateListener(va -> {
            float v = (float) va.getAnimatedValue();
            rippleWobble = v * v;
            ripplePhase = (1f - v) * 12.5f;
            if (backdropView != null && backdropView.getVisibility() == View.VISIBLE) {
                backdropView.invalidate();
            }
        });
        rippleAnim = anim;
        anim.start();
    }

    /** Immediately freezes any active Dock ripple (e.g. on app launch or pause). */
    void cancelRipple() {
        if (rippleAnim != null) {
            rippleAnim.cancel();
            rippleAnim = null;
        }
        if (rippleWobble > 0f) {
            rippleWobble = 0f;
            ripplePhase = 0f;
            if (backdropView != null && backdropView.getVisibility() == View.VISIBLE) {
                backdropView.invalidate();
            }
        }
    }

    /** Releases cached glass overlay bitmap on memory trim. */
    void clearCache() {
        cancelRipple();
        if (overlayCache != null) {
            overlayCache.recycle();
            overlayCache = null;
        }
        shaderW = shaderH = shaderSeed = shaderStyle = shaderOpacity = -1;
        cachedBackdropBmp = null;
        cachedBackdropShader = null;
        cachedHeroBmp = null;
        cachedHeroShader = null;
        cachedDockW = cachedDockH = cachedBgVersion = cachedDockSeed = cachedDockStyle = cachedDockOpacity = -1;
        cachedBlurRef = null;
        if (backdropView != null) {
            backdropView.setLayerType(View.LAYER_TYPE_NONE, null);
            backdropView.setVisibility(View.INVISIBLE);
        }
        if (tintOverlayView != null) {
            tintOverlayView.setVisibility(View.INVISIBLE);
        }
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && (singleRow || pos < dockSize)) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) ((compactMultiRow ? 18 : 12) * density);
        }
    }

    private void hideBackdropHosts() {
        if (backdropView != null) {
            if (backdropView.getVisibility() == View.VISIBLE) backdropView.setVisibility(View.INVISIBLE);
            if (backdropView.getClipBounds() != null) backdropView.setClipBounds(null);
        }
        if (tintOverlayView != null) {
            if (tintOverlayView.getVisibility() == View.VISIBLE) tintOverlayView.setVisibility(View.INVISIBLE);
            if (tintOverlayView.getClipBounds() != null) tintOverlayView.setClipBounds(null);
        }
    }

    /**
     * Matches TopFade.FadingRecyclerView's top fade edge so the frosted Dock bar vanishes in lockstep with
     * its icons as the user scrolls down to rows below the dock (never lingering orphaned at the top edge).
     */
    private static float computeScrollFadeAlpha(RecyclerView parent, RectF dockRect) {
        if (!parent.isVerticalFadingEdgeEnabled()) return 1f;
        float fadeLen = parent.getVerticalFadingEdgeLength();
        if (fadeLen <= 0f || parent.computeVerticalScrollOffset() <= 0) return 1f;
        float fullH = Math.max(1f, dockRect.height());
        float startBottom = fadeLen + fullH * 0.55f;
        float endBottom = fadeLen * 0.35f;
        if (dockRect.bottom >= startBottom) return 1f;
        if (dockRect.bottom <= endBottom) return 0f;
        float t = (dockRect.bottom - endBottom) / Math.max(1f, startBottom - endBottom);
        return t * t * (3f - 2f * t);
    }

    /** Computes dock bounds in {@code parent} coordinates into {@link #rect}. Returns corner radius, or < 0 if no dock tile is attached. */
    private float measureDockRect(RecyclerView parent) {
        boolean any = false;
        int cardWidth = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || (!bandAll && pos >= dockSize)) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            cardWidth = card.getWidth();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return -1f;
        float pad = 14 * density;
        float sidePad = 22 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float radius = AppAdapter.cornerRadius(cardWidth, density);
        if (evenPadding) {
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            float room = singleRow
                    ? Math.max(0f, parent.getPaddingTop() - density)
                    : Math.min(rect.left, parent.getWidth() - rect.right) - density;
            if (compactMultiRow) room = Math.min(room, 14f * density);
            float even = evenPaddingPx(inner, dockSize, cardWidth, room);
            rect.inset(-even, -even);
            radius = radiusPx(radius, even);
        } else {
            rect.inset(singleRow ? -sidePad : -side, -pad);
            radius = 26 * density;
        }
        if (singleRow) {
            rect.left = Math.max(rect.left, density);
            rect.right = Math.min(rect.right, parent.getWidth() - density);
        }
        rect.bottom += namesRoom;
        return radius;
    }

    /**
     * Synchronizes sibling backdropView & tintOverlayView BEFORE wrapper draws Frame N so the Dock glass
     * never lags 1 frame behind its scrolling icons and never overflows above grid's top edge.
     */
    private void syncBackdropPositionOnScroll(RecyclerView parent) {
        if (!bandVisible || !lightGlass || backdropView == null || root == null) return;
        float radius = measureDockRect(parent);
        if (radius < 0f || rect.bottom <= 0f || rect.top >= parent.getHeight()) {
            hideBackdropHosts();
            return;
        }
        float scrollAlpha = computeScrollFadeAlpha(parent, rect);
        if (scrollAlpha <= 0.005f) {
            hideBackdropHosts();
            return;
        }
        int rw = Math.max(1, Math.round(rect.width()));
        int rh = Math.max(1, Math.round(rect.height()));
        int pad = Math.max(4, Math.round(8f * density));
        int totalW = rw + 2 * pad;
        int totalH = rh + 2 * pad;
        if (backdropView.getWidth() != totalW || backdropView.getHeight() != totalH) return;
        float tx = rect.left - pad;
        float ty = rect.top - pad;
        int clipTop = Math.max(0, Math.round(-ty));
        int clipBot = Math.min(totalH, Math.round(parent.getHeight() - ty));
        if (clipBot <= clipTop) {
            hideBackdropHosts();
            return;
        }
        if (backdropView.getTranslationX() != tx) backdropView.setTranslationX(tx);
        if (backdropView.getTranslationY() != ty) backdropView.setTranslationY(ty);
        if (backdropView.getAlpha() != scrollAlpha) backdropView.setAlpha(scrollAlpha);
        if (tintOverlayView != null) {
            if (tintOverlayView.getTranslationX() != tx) tintOverlayView.setTranslationX(tx);
            if (tintOverlayView.getTranslationY() != ty) tintOverlayView.setTranslationY(ty);
            if (tintOverlayView.getAlpha() != scrollAlpha) tintOverlayView.setAlpha(scrollAlpha);
        }
        if (clipTop > 0 || clipBot < totalH) {
            dockClipRect.set(0, clipTop, totalW, clipBot);
            backdropView.setClipBounds(dockClipRect);
            if (tintOverlayView != null) tintOverlayView.setClipBounds(dockClipRect);
        } else {
            if (backdropView.getClipBounds() != null) backdropView.setClipBounds(null);
            if (tintOverlayView != null && tintOverlayView.getClipBounds() != null) tintOverlayView.setClipBounds(null);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!bandVisible) return;
        float radius = measureDockRect(parent);
        if (radius < 0f || rect.bottom <= 0f || rect.top >= parent.getHeight()) {
            hideBackdropHosts();
            return;
        }
        float scrollAlpha = computeScrollFadeAlpha(parent, rect);
        if (scrollAlpha <= 0.005f) {
            hideBackdropHosts();
            return;
        }
        if (lightGlass) {
            int seed = ExpressiveGlass.dynamicSeedRgb();
            int rw = Math.max(1, Math.round(rect.width()));
            int rh = Math.max(1, Math.round(rect.height()));
            if (syncHardwareBackdrop(parent, rw, rh, radius, seed, scrollAlpha)) {
                return;
            }
            // Fallback path on Frame 0 before the tight hardware layer finishes sizing (strictly clipped to grid).
            int save = c.save();
            c.clipRect(0f, 0f, parent.getWidth(), parent.getHeight());
            drawFrostedBackdrop(c, parent, rect, radius, 0f, 0f, false);
            Bitmap overlay = getOrBuildOverlayCache(rw, rh, radius, seed);
            if (overlay != null) {
                overlayDst.set(rect.left - overlayPadPx, rect.top - overlayPadPx,
                        rect.right + overlayPadPx, rect.bottom + overlayPadPx);
                c.drawBitmap(overlay, null, overlayDst, overlayBmpPaint);
            }
            if (!drawDockLiquidRim(c, rect, radius, gridOffset.left + rect.left, gridOffset.top + rect.top)) {
                c.drawRoundRect(rect, radius, radius, outerContrast);
                c.drawRoundRect(rect, radius, radius, edge);
            }
            c.restoreToCount(save);
            return;
        }
        int save = c.save();
        c.clipRect(0f, 0f, parent.getWidth(), parent.getHeight());
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
        c.restoreToCount(save);
    }

    /**
     * Updates the tight LAYER_TYPE_HARDWARE DockBackdropView behind the grid.
     * Returns true once sized and ready so RecyclerView skips all 5 dock GPU passes on idle/focus/vinyl frames.
     */
    private boolean syncHardwareBackdrop(RecyclerView parent, int rw, int rh, float radius, int seed, float scrollAlpha) {
        if (backdropView == null || root == null) return false;
        int pad = Math.max(4, Math.round(8f * density));
        int totalW = rw + 2 * pad;
        int totalH = rh + 2 * pad;
        gridOffset.set(0, 0, parent.getWidth(), parent.getHeight());
        root.offsetDescendantRectToMyCoords(parent, gridOffset);
        int scrX = Math.round(gridOffset.left + rect.left);
        int scrY = Math.round(gridOffset.top + rect.top);
        float tx = rect.left - pad;
        float ty = rect.top - pad;
        int clipTop = Math.max(0, Math.round(-ty));
        int clipBot = Math.min(totalH, Math.round(parent.getHeight() - ty));
        if (clipBot <= clipTop) {
            hideBackdropHosts();
            return true;
        }

        if (backdropView.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            backdropView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        if (backdropView.getTranslationX() != tx) backdropView.setTranslationX(tx);
        if (backdropView.getTranslationY() != ty) backdropView.setTranslationY(ty);
        if (backdropView.getAlpha() != scrollAlpha) backdropView.setAlpha(scrollAlpha);
        if (tintOverlayView != null) {
            if (tintOverlayView.getTranslationX() != tx) tintOverlayView.setTranslationX(tx);
            if (tintOverlayView.getTranslationY() != ty) tintOverlayView.setTranslationY(ty);
            if (tintOverlayView.getAlpha() != scrollAlpha) tintOverlayView.setAlpha(scrollAlpha);
        }
        if (clipTop > 0 || clipBot < totalH) {
            dockClipRect.set(0, clipTop, totalW, clipBot);
            backdropView.setClipBounds(dockClipRect);
            if (tintOverlayView != null) tintOverlayView.setClipBounds(dockClipRect);
        } else {
            if (backdropView.getClipBounds() != null) backdropView.setClipBounds(null);
            if (tintOverlayView != null && tintOverlayView.getClipBounds() != null) tintOverlayView.setClipBounds(null);
        }

        ViewGroup.LayoutParams lp = backdropView.getLayoutParams();
        if (lp != null && (lp.width != totalW || lp.height != totalH)) {
            if (!backdropResizePosted) {
                backdropResizePosted = true;
                final int fw = totalW, fh = totalH;
                backdropView.post(() -> {
                    backdropResizePosted = false;
                    if (backdropView == null) return;
                    ViewGroup.LayoutParams cur = backdropView.getLayoutParams();
                    if (cur != null && (cur.width != fw || cur.height != fh)) {
                        cur.width = fw;
                        cur.height = fh;
                        backdropView.setLayoutParams(cur);
                    }
                    if (tintOverlayView != null) {
                        ViewGroup.LayoutParams tlp = tintOverlayView.getLayoutParams();
                        if (tlp != null && (tlp.width != fw || tlp.height != fh)) {
                            tlp.width = fw;
                            tlp.height = fh;
                            tintOverlayView.setLayoutParams(tlp);
                        }
                    }
                    if (backdropView.getVisibility() == View.GONE) {
                        backdropView.setVisibility(View.INVISIBLE);
                    }
                    parent.invalidate();
                });
            }
            if (backdropView.getVisibility() == View.VISIBLE) {
                backdropView.setVisibility(View.INVISIBLE);
            }
            if (tintOverlayView != null && tintOverlayView.getVisibility() == View.VISIBLE) {
                tintOverlayView.setVisibility(View.INVISIBLE);
            }
            return false;
        }
        if (backdropView.getWidth() != totalW || backdropView.getHeight() != totalH) {
            // Keep INVISIBLE (never GONE) so FrameLayout actually measures & lays out backdropView!
            if (backdropView.getVisibility() == View.GONE) {
                backdropView.post(() -> {
                    if (backdropView != null && backdropView.getVisibility() == View.GONE) {
                        backdropView.setVisibility(View.INVISIBLE);
                    }
                    parent.invalidate();
                });
            } else if (backdropView.getVisibility() == View.VISIBLE) {
                backdropView.setVisibility(View.INVISIBLE);
            }
            if (tintOverlayView != null && tintOverlayView.getVisibility() == View.VISIBLE) {
                tintOverlayView.setVisibility(View.INVISIBLE);
            }
            return false;
        }

        Bitmap blurred = wallpaperPlayer != null ? wallpaperPlayer.blurred() : null;
        ExpressiveGlass.RefractionBackdrop bg = ExpressiveGlass.homeWallpaperRefraction();
        int bgVer = bg != null ? bg.version : -1;
        int style = ExpressiveGlass.glassStyle();
        boolean optical = ExpressiveGlass.isHomeLiquidOpticsEnabled();

        boolean wasVisible = backdropView.getVisibility() == View.VISIBLE;
        boolean dirty = !wasVisible || rw != cachedDockW || rh != cachedDockH || pad != cachedDockPad
                || scrX != cachedScreenX || scrY != cachedScreenY
                || Math.abs(radius - cachedDockRadius) > 0.25f
                || seed != cachedDockSeed || style != cachedDockStyle || opacity != cachedDockOpacity
                || optical != cachedOpticalMode || bgVer != cachedBgVersion || blurred != cachedBlurRef;

        if (dirty) {
            cachedDockW = rw;
            cachedDockH = rh;
            cachedDockPad = pad;
            cachedScreenX = scrX;
            cachedScreenY = scrY;
            cachedDockRadius = radius;
            cachedDockSeed = seed;
            cachedDockStyle = style;
            cachedDockOpacity = opacity;
            cachedOpticalMode = optical;
            cachedBgVersion = bgVer;
            cachedBlurRef = blurred;
            if (!wasVisible) {
                backdropView.setVisibility(View.VISIBLE);
                backdropView.invalidate();
                if (tintOverlayView != null) {
                    tintOverlayView.setVisibility(View.VISIBLE);
                    tintOverlayView.invalidate();
                }
                // Handoff frame: child 0 (backdropView) was INVISIBLE earlier in this draw pass, so
                // render fallback once for 0-blink handoff and switch permanently to GPU cache next frame.
                backdropView.post(parent::invalidate);
                return false;
            }
            backdropView.invalidate();
            if (tintOverlayView != null) {
                if (tintOverlayView.getVisibility() != View.VISIBLE) tintOverlayView.setVisibility(View.VISIBLE);
                tintOverlayView.invalidate();
            }
        }
        return true;
    }

    private final class DockBackdropView extends View {
        DockBackdropView(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            if (cachedDockW <= 0 || cachedDockH <= 0 || root == null) return;
            int seed = ExpressiveGlass.dynamicSeedRgb();
            int style = ExpressiveGlass.glassStyle();
            ExpressiveGlass.RefractionBackdrop bg = ExpressiveGlass.homeWallpaperRefraction();
            Bitmap blurred = wallpaperPlayer != null ? wallpaperPlayer.blurred() : null;
            cachedDockSeed = seed;
            cachedDockStyle = style;
            cachedDockOpacity = opacity;
            cachedOpticalMode = ExpressiveGlass.isHomeLiquidOpticsEnabled();
            cachedBgVersion = bg != null ? bg.version : -1;
            cachedBlurRef = blurred;

            int pad = cachedDockPad;
            cachedLocalRect.set(pad, pad, pad + cachedDockW, pad + cachedDockH);
            drawFrostedWallpaperOnly(c, null, cachedLocalRect, cachedDockRadius,
                    cachedScreenX - pad, cachedScreenY - pad, true);
            Bitmap overlay = getOrBuildOverlayCache(cachedDockW, cachedDockH, cachedDockRadius, seed);
            if (overlay != null) {
                overlayDst.set(cachedLocalRect.left - overlayPadPx, cachedLocalRect.top - overlayPadPx,
                        cachedLocalRect.right + overlayPadPx, cachedLocalRect.bottom + overlayPadPx);
                c.drawBitmap(overlay, null, overlayDst, overlayBmpPaint);
            }
            if (!drawDockLiquidRim(c, cachedLocalRect, cachedDockRadius, cachedScreenX, cachedScreenY)) {
                c.drawRoundRect(cachedLocalRect, cachedDockRadius, cachedDockRadius, outerContrast);
                c.drawRoundRect(cachedLocalRect, cachedDockRadius, cachedDockRadius, edge);
            }
        }
    }

    /** Ultra-cheap (~0.08 ms) GPU overlay for silky 60 FPS ambient color & heroArt crossfades on the Dock. */
    private final class DockTintOverlayView extends View {
        private final RectF tintRect = new RectF();

        DockTintOverlayView(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            if (cachedDockW <= 0 || cachedDockH <= 0 || root == null) return;
            int pad = cachedDockPad;
            tintRect.set(pad, pad, pad + cachedDockW, pad + cachedDockH);
            drawHeroAndAmbientOverlay(c, null, tintRect, cachedDockRadius,
                    cachedScreenX - pad, cachedScreenY - pad, true);
        }
    }

    @Nullable
    private Bitmap getOrBuildOverlayCache(int rw, int rh, float radius, int seed) {
        int style = ExpressiveGlass.glassStyle();
        if (overlayCache != null && !overlayCache.isRecycled()
                && rw == shaderW && rh == shaderH && seed == shaderSeed && style == shaderStyle
                && opacity == shaderOpacity && Math.abs(radius - cachedRadius) < 0.25f) {
            return overlayCache;
        }
        int pad = Math.max(4, Math.round(8f * density));
        int fullW = rw + 2 * pad;
        int fullH = rh + 2 * pad;
        try {
            Bitmap bmp = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(bmp);

            int[] glassCols = ExpressiveGlass.dockGlassColors(style, seed, opacity);
            float shadowScale = 0.55f + 0.45f * ((Math.max(30, Math.min(100, opacity)) - 30f) / 70f);
            int bounce = ExpressiveGlass.tonalRimTint(seed);
            int caustic = style == ExpressiveGlass.STYLE_OBSIDIAN ? 0x0C
                    : (style == ExpressiveGlass.STYLE_FROST ? 0x28 : 0x16);
            int topWithCaustic = blendOverWhite(glassCols[0], Math.max(6, Math.round(caustic * shadowScale)));
            int stop22 = lerpArgb(glassCols[0], glassCols[1], 0.22f / 0.48f);
            bandShader = new LinearGradient(0f, 0f, 0f, rh,
                    new int[]{topWithCaustic, stop22, glassCols[1], glassCols[2]},
                    new float[]{0f, 0.22f, 0.48f, 1f}, Shader.TileMode.CLAMP);
            if (style == ExpressiveGlass.STYLE_FROST) {
                outerContrast.setColor(0x280F172A);
                edgeShader = new LinearGradient(0f, 0f, rw, rh,
                        new int[]{0xE6FFFFFF, 0x52FFFFFF, 0x220F172A, 0x380F172A},
                        new float[]{0f, 0.35f, 0.70f, 1f}, Shader.TileMode.CLAMP);
            } else {
                outerContrast.setColor(0x32000000);
                int topSpec = style == ExpressiveGlass.STYLE_OBSIDIAN ? 0x62FFFFFF : 0x76FFFFFF;
                edgeShader = new LinearGradient(0f, 0f, rw, rh,
                        new int[]{topSpec, 0x24FFFFFF, 0x1AFFFFFF, (0x44 << 24) | bounce},
                        new float[]{0f, 0.35f, 0.70f, 1f}, Shader.TileMode.CLAMP);
            }
            shaderMatrix.setTranslate(pad, pad);
            bandShader.setLocalMatrix(shaderMatrix);
            edgeShader.setLocalMatrix(shaderMatrix);
            band.setDither(true);
            band.setShader(bandShader);
            edge.setDither(true);
            edge.setShader(edgeShader);

            RectF local = new RectF(pad, pad, pad + rw, pad + rh);
            shadow.setColor(Math.round(0x2C * shadowScale) << 24);
            shadowRect.set(local);
            shadowRect.inset(-1.5f * density, -1.5f * density);
            shadowRect.offset(0, 4.5f * density);
            oc.drawRoundRect(shadowRect, radius + 1.5f * density, radius + 1.5f * density, shadow);
            shadow.setColor(Math.round(0x24 * shadowScale) << 24);
            shadowRect.set(local);
            shadowRect.offset(0, 2f * density);
            oc.drawRoundRect(shadowRect, radius, radius, shadow);

            oc.drawRoundRect(local, radius, radius, band);
            bmp.prepareToDraw();

            if (overlayCache != null) overlayCache.recycle();
            overlayCache = bmp;
            overlayPadPx = pad;
            shaderW = rw;
            shaderH = rh;
            shaderSeed = seed;
            shaderStyle = style;
            shaderOpacity = opacity;
            cachedRadius = radius;
            return bmp;
        } catch (OutOfMemoryError ignored) {
            return null;
        }
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

    private void drawFrostedBackdrop(Canvas c, @Nullable RecyclerView parent, RectF targetRect, float radius,
                                     float originOffsetX, float originOffsetY, boolean useExplicitOrigin) {
        drawFrostedWallpaperOnly(c, parent, targetRect, radius, originOffsetX, originOffsetY, useExplicitOrigin);
        drawHeroAndAmbientOverlay(c, parent, targetRect, radius, originOffsetX, originOffsetY, useExplicitOrigin);
    }

    private void drawFrostedWallpaperOnly(Canvas c, @Nullable RecyclerView parent, RectF targetRect, float radius,
                                          float originOffsetX, float originOffsetY, boolean useExplicitOrigin) {
        if (root == null || wallpaperPlayer == null) return;
        Bitmap blurred = wallpaperPlayer.blurred();
        if (blurred == null || blurred.isRecycled()) return;
        int rootW = root.getWidth(), rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0 || blurred.getWidth() <= 0 || blurred.getHeight() <= 0) return;

        float offX, offY;
        if (useExplicitOrigin) {
            offX = originOffsetX;
            offY = originOffsetY;
        } else {
            if (parent == null) return;
            gridOffset.set(0, 0, parent.getWidth(), parent.getHeight());
            root.offsetDescendantRectToMyCoords(parent, gridOffset);
            offX = gridOffset.left;
            offY = gridOffset.top;
        }

        // Layer 1: Smooth Material 3 Expressive frosted wallpaper across the Dock interior (cached in VRAM).
        float zoom = ExpressiveGlass.REFRACTION_ZOOM;
        if (blurred != cachedBackdropBmp || cachedBackdropShader == null) {
            cachedBackdropBmp = blurred;
            cachedBackdropShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cachedBackdropShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            backdropPaint.setDither(true);
            backdropPaint.setShader(cachedBackdropShader);
        }
        shaderMatrix.reset();
        shaderMatrix.setScale(rootW / (float) blurred.getWidth(), rootH / (float) blurred.getHeight());
        shaderMatrix.postTranslate(-offX, -offY);
        shaderMatrix.postScale(zoom, zoom, targetRect.centerX(), targetRect.centerY());
        cachedBackdropShader.setLocalMatrix(shaderMatrix);
        backdropPaint.setAlpha(ExpressiveGlass.dockBackdropAlpha(opacity));
        c.drawRoundRect(targetRect, radius, radius, backdropPaint);
    }

    private void drawHeroAndAmbientOverlay(Canvas c, @Nullable RecyclerView parent, RectF targetRect, float radius,
                                           float originOffsetX, float originOffsetY, boolean useExplicitOrigin) {
        if (root == null) return;
        int rootW = root.getWidth(), rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0) return;

        float offX, offY;
        if (useExplicitOrigin) {
            offX = originOffsetX;
            offY = originOffsetY;
        } else {
            if (parent == null) return;
            gridOffset.set(0, 0, parent.getWidth(), parent.getHeight());
            root.offsetDescendantRectToMyCoords(parent, gridOffset);
            offX = gridOffset.left;
            offY = gridOffset.top;
        }

        if (heroArt != null && heroArt.getVisibility() == View.VISIBLE && heroArt.getAlpha() > 0f
                && heroArt.getDrawable() instanceof BitmapDrawable) {
            Bitmap hb = ((BitmapDrawable) heroArt.getDrawable()).getBitmap();
            if (hb != null && !hb.isRecycled() && hb.getWidth() > 0 && hb.getHeight() > 0) {
                if (hb != cachedHeroBmp || cachedHeroShader == null) {
                    cachedHeroBmp = hb;
                    cachedHeroShader = new BitmapShader(hb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        cachedHeroShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                    }
                    heroPaint.setDither(true);
                    heroPaint.setShader(cachedHeroShader);
                }
                float zoom = ExpressiveGlass.REFRACTION_ZOOM;
                shaderMatrix.reset();
                shaderMatrix.setScale(rootW / (float) hb.getWidth(), rootH / (float) hb.getHeight());
                shaderMatrix.postTranslate(-offX, -offY);
                shaderMatrix.postScale(zoom, zoom, targetRect.centerX(), targetRect.centerY());
                cachedHeroShader.setLocalMatrix(shaderMatrix);
                // Keep hero tint subtle over the frosted glass so it never washes out glass depth or rims.
                heroPaint.setAlpha(Math.min(180, Math.round(heroArt.getAlpha() * 210)));
                c.drawRoundRect(targetRect, radius, radius, heroPaint);
            }
        }
        if (ambientColorSupplier != null) {
            int ambientTint = ambientColorSupplier.getAsInt();
            if (ambientTint != 0) {
                ambientPaint.setColor(ambientTint);
                c.drawRoundRect(targetRect, radius, radius, ambientPaint);
            }
        }
    }

    /** Draws Layer 2: real-time Liquid Glass curved rim refraction + chromatic dispersion + Fresnel specular. */
    private boolean drawDockLiquidRim(Canvas c, RectF targetRect, float radius, float screenOriginX, float screenOriginY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || dockLiquidFailed) return false;
        ExpressiveGlass.RefractionBackdrop bg = ExpressiveGlass.homeWallpaperRefraction();
        if (bg == null) return false;
        if (dockLiquidShader == null) {
            dockLiquidShader = ExpressiveGlass.acquireLiquidShader();
            boundBackdropVersion = -1;
            if (dockLiquidShader == null) {
                dockLiquidFailed = true;
                return false;
            }
        }
        float w = targetRect.width(), h = targetRect.height();
        boolean optical = ExpressiveGlass.isHomeLiquidOpticsEnabled();
        float bezel = Math.min((optical ? 24f : 16f) * density, Math.min(w, h) * 0.24f);
        float refract = Math.min((optical ? 44f : 26f) * density, bezel * 1.90f);
        int seed = ExpressiveGlass.dynamicSeedRgb();
        if (seed != lastRimSeed) {
            int rimTint = ExpressiveGlass.tonalRimTint(seed);
            cachedRimR = ((rimTint >> 16) & 0xFF) / 255f;
            cachedRimG = ((rimTint >> 8) & 0xFF) / 255f;
            cachedRimB = (rimTint & 0xFF) / 255f;
            lastRimSeed = seed;
        }

        if (boundBackdropVersion != bg.version) {
            dockLiquidShader.setInputShader("uSharp", bg.sharpShader);
            dockLiquidShader.setInputShader("uBlur", bg.blurShader);
            boundBackdropVersion = bg.version;
        }
        dockLiquidShader.setFloatUniform("uPanelSize", w, h);
        dockLiquidShader.setFloatUniform("uScreenOrigin", screenOriginX, screenOriginY);
        dockLiquidShader.setFloatUniform("uScreenScale", 1f, 1f);
        dockLiquidShader.setFloatUniform("uRadius", radius);
        dockLiquidShader.setFloatUniform("uBezel", bezel);
        dockLiquidShader.setFloatUniform("uRefract", refract);
        dockLiquidShader.setFloatUniform("uChromatic", (optical ? 4.2f : 2.2f) * density);
        dockLiquidShader.setFloatUniform("uEdgeClarity", optical ? 0.88f : 0.16f);
        dockLiquidShader.setFloatUniform("uInteriorFill", 0.0f);
        dockLiquidShader.setFloatUniform("uLiquidMode", optical ? 1.0f : 0.0f);
        dockLiquidShader.setFloatUniform("uSurfaceLight", ExpressiveGlass.isLightGlass() ? 1.0f : 0.0f);
        dockLiquidShader.setFloatUniform("uInteriorDim", ExpressiveGlass.interiorDimForStyle(ExpressiveGlass.glassStyle()));
        dockLiquidShader.setFloatUniform("uRimTint", cachedRimR, cachedRimG, cachedRimB);
        dockLiquidShader.setFloatUniform("uWobble", rippleWobble);
        dockLiquidShader.setFloatUniform("uPhase", ripplePhase);

        int save = c.save();
        c.translate(targetRect.left, targetRect.top);
        dockLiquidPaint.setShader(null);
        dockLiquidPaint.setShader(dockLiquidShader);
        ExpressiveGlass.drawBezelRing(c, 0f, 0f, w, h, radius, bezel, dockLiquidPaint);
        c.restoreToCount(save);
        return true;
    }
}
