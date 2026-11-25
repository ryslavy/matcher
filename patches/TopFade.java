package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * fade_rows: rows fade softly at the scroll edge instead of clipping sharply under the header.
 *
 * <p>{@link FadingRecyclerView} uses Android's native partial-strip vertical fading edge (saving a tiny
 * ~48dp unclipped layer only over the faded edge strip instead of forcing the entire 1920x840 RecyclerView
 * into an offscreen {@link View#LAYER_TYPE_HARDWARE} FBO on every scroll frame). A fallback ItemDecoration
 * remains for plain RecyclerViews.
 */
public final class TopFade extends RecyclerView.ItemDecoration {

    /**
     * RecyclerView whose vertical fading edge tracks {@link #computeVerticalScrollOffset()} at the view's
     * physical outer bounds (ignoring large clipToPadding=false top padding used to park the dock at the bottom).
     */
    public static final class FadingRecyclerView extends RecyclerView {
        private boolean edgeEnabled;
        private boolean fadeBottom;

        public FadingRecyclerView(@NonNull Context context) {
            super(context);
        }

        public FadingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
        }

        public FadingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        void enableFade(float lengthPx, boolean includeBottom) {
            edgeEnabled = lengthPx > 0f;
            fadeBottom = includeBottom;
            setVerticalFadingEdgeEnabled(edgeEnabled);
            setFadingEdgeLength(Math.max(1, Math.round(lengthPx)));
            invalidate();
        }

        @Override
        protected boolean isPaddingOffsetRequired() {
            return edgeEnabled;
        }

        @Override
        protected int getTopPaddingOffset() {
            return edgeEnabled ? -getPaddingTop() : 0;
        }

        @Override
        protected int getBottomPaddingOffset() {
            return edgeEnabled ? getPaddingBottom() : 0;
        }

        @Override
        protected int getLeftPaddingOffset() {
            return edgeEnabled ? -getPaddingLeft() : 0;
        }

        @Override
        protected int getRightPaddingOffset() {
            return edgeEnabled ? getPaddingRight() : 0;
        }

        @Override
        protected float getTopFadingEdgeStrength() {
            if (!edgeEnabled) return 0f;
            int len = getVerticalFadingEdgeLength();
            if (len <= 0) return 0f;
            return height(computeVerticalScrollOffset(), len) / (float) len;
        }

        @Override
        protected float getBottomFadingEdgeStrength() {
            if (!edgeEnabled || !fadeBottom) return 0f;
            int len = getVerticalFadingEdgeLength();
            if (len <= 0) return 0f;
            int remaining = computeVerticalScrollRange() - computeVerticalScrollExtent()
                    - computeVerticalScrollOffset();
            return height(remaining, len) / (float) len;
        }
    }

    private final float length;
    private final boolean includeBottom;
    private final Paint paint = new Paint();
    private final Paint bottomPaint = new Paint();
    private final LinearGradient topShader;
    private final LinearGradient bottomShader;
    private final Matrix shaderMatrix = new Matrix();
    private boolean layerActive;

    private TopFade(float length, boolean includeBottom) {
        this.length = length;
        this.includeBottom = includeBottom;
        topShader = new LinearGradient(0f, 0f, 0f, 1f, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        bottomShader = new LinearGradient(0f, 0f, 0f, 1f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        paint.setShader(topShader);
        bottomPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        bottomPaint.setShader(bottomShader);
    }

    static FadingRecyclerView newRecyclerView(Context ctx, float length, boolean includeBottom) {
        FadingRecyclerView rv = new FadingRecyclerView(ctx);
        rv.enableFade(length, includeBottom);
        return rv;
    }

    static void attach(RecyclerView grid, float length) {
        if (grid instanceof FadingRecyclerView) {
            ((FadingRecyclerView) grid).enableFade(length, false);
            return;
        }
        install(grid, new TopFade(length, false));
    }

    /** Fades both top and bottom edges of a scrollable list whenever content extends past that edge. */
    static void attachVerticalEdges(RecyclerView list, float length) {
        if (list instanceof FadingRecyclerView) {
            ((FadingRecyclerView) list).enableFade(length, true);
            return;
        }
        install(list, new TopFade(length, true));
    }

    private static void install(RecyclerView rv, TopFade fade) {
        rv.addItemDecoration(fade);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                fade.syncLayer(recyclerView);
            }
        });
        rv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> fade.syncLayer(rv));
        rv.post(() -> fade.syncLayer(rv));
    }

    /** How tall the fade is after scrolling {@code scrolled} pixels: nothing at rest, then up to {@code length}. */
    static float height(int scrolled, float length) {
        return Math.max(0f, Math.min(length, scrolled));
    }

    private boolean needsFade(RecyclerView rv) {
        int offset = rv.computeVerticalScrollOffset();
        if (height(offset, length) > 0f) return true;
        if (!includeBottom) return false;
        int remaining = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent() - offset;
        return height(remaining, length) > 0f;
    }

    private void syncLayer(RecyclerView rv) {
        boolean need = needsFade(rv);
        if (need != layerActive) {
            layerActive = need;
            rv.setLayerType(need ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        }
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int offset = parent.computeVerticalScrollOffset();
        float fade = height(offset, length);
        float botFade = 0f;
        if (includeBottom) {
            int remaining = parent.computeVerticalScrollRange() - parent.computeVerticalScrollExtent() - offset;
            botFade = height(remaining, length);
        }
        boolean need = fade > 0f || botFade > 0f;
        if (need != layerActive) {
            layerActive = need;
            parent.setLayerType(need ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
            if (need) {
                parent.postInvalidateOnAnimation();
                return;
            }
        }
        if (!need) return;

        if (fade > 0f) {
            shaderMatrix.setScale(1f, fade);
            topShader.setLocalMatrix(shaderMatrix);
            c.drawRect(0f, 0f, parent.getWidth(), fade, paint);
        }
        if (botFade > 0f) {
            int h = parent.getHeight();
            shaderMatrix.setScale(1f, botFade);
            shaderMatrix.postTranslate(0f, h - botFade);
            bottomShader.setLocalMatrix(shaderMatrix);
            c.drawRect(0f, h - botFade, parent.getWidth(), h, bottomPaint);
        }
    }
}
