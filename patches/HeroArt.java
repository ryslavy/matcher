package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to 48×27 pixels, saturated and smoothed,
 * then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame,
 * which a 1 GB TV cannot spare.
 */
final class HeroArt {
    static final int W = 128, H = 72;
    private static final float SATURATION = 1.45f;

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. */
    static Bitmap from(Drawable live) {
        return processRasterized(rasterize(live));
    }

    /** Fast UI-thread step: draws a private copy of {@code live} into a 4x bitmap so background blur stays thread-safe. */
    static Bitmap rasterize(Drawable live) {
        if (live == null) return null;
        // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        return big;
    }

    /** Background-thread safe step: downscales, saturates, and runs 3-pass O(W*H) running-sum box blur. */
    static Bitmap processRasterized(Bitmap big) {
        if (big == null || big.isRecycled()) return null;
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
        big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, W, H, true);
        half.recycle();

        int[] px = new int[W * H];
        int[] tmp = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        saturate(px);
        // Three box passes approximate a gaussian, so the stretched image shows no seams between pixels.
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, W, H, 6, true);
            boxBlur(tmp, px, W, H, 6, false);
        }
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, W, 0, 0, W, H);
        out.prepareToDraw();
        return out;
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = 0xFF000000
                    | (clamp(lum + (r - lum) * SATURATION) << 16)
                    | (clamp(lum + (g - lum) * SATURATION) << 8)
                    | clamp(lum + (b - lum) * SATURATION);
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** One O(W*H) sliding-window box blur pass along rows (horizontal) or columns, edges clamped. */
    private static void boxBlur(int[] src, int[] dst, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int j = k < 0 ? 0 : (k >= length ? length - 1 : k);
                int p = horizontal ? src[line * w + j] : src[j * w + line];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                dst[horizontal ? (line * w + i) : (i * w + line)] =
                        0xFF000000 | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int outJ = i - radius;
                if (outJ < 0) outJ = 0;
                int inJ = i + radius + 1;
                if (inJ >= length) inJ = length - 1;
                int pOut = horizontal ? src[line * w + outJ] : src[outJ * w + line];
                int pIn = horizontal ? src[line * w + inJ] : src[inJ * w + line];
                r += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                g += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                b += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }
}
