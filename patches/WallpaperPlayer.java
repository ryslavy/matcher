package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final FrameLayout imageHost;
    private final ImageView image;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** Cinematic scrim opacity (0..255) baked directly into decoded wallpaper to eliminate a full-screen overdraw pass. */
    private final int scrimAlpha;
    private final boolean rtl;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    /** 160x90 pre-blurred vibrant snapshot of the active wallpaper for frosted glass UI elements. */
    private Bitmap blurredCurrent;
    /** 480x270 crisp scaled wallpaper for AGSL Liquid Glass edge refraction. */
    private Bitmap sharpCurrent;
    private File displayedFile;
    private boolean sharpCrispMode;
    private Runnable onWallpaperChanged;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** The grid is scrolled away from the dock, so the caption is faded out (scrolledAway). */
    private boolean captionAway;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this(context, root, root, config);
    }

    WallpaperPlayer(Context context, FrameLayout root, FrameLayout imageHost, Config config) {
        this.root = root;
        this.imageHost = imageHost != null ? imageHost : root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim & scrim are painted into the picture once on decode, eliminating full-screen overdraw layers.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        scrimAlpha = config.cinematicHome && config.cinematicScrim > 0
                ? Math.min(255, config.cinematicScrim * 255 / 100) : 0;
        rtl = context.getResources().getBoolean(R.bool.rtl_menu_bar);
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : FeaturePrefs.weatherLatitude(context);
        longitude = moods.isEmpty() ? "" : FeaturePrefs.weatherLongitude(context);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            imageHost.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        boolean crisp = !ExpressiveGlass.isHomeBackdropBlurEnabled();
        while (first != null) {
            DecodedWallpaper decoded = decode(first, crisp);
            if (decoded != null) {
                displayedFile = first;
                sharpCrispMode = crisp;
                show(decoded);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    /**
     * Re-decodes the active wallpaper's 960x540 uSharp backdrop off-thread when the user toggles
     * sharp-Home mode so Dock, top-right circular pills, and Liquid Clock immediately match.
     */
    void refreshSharpnessIfNeeded(boolean crisp) {
        if (closed || current == null || displayedFile == null || sharpCrispMode == crisp) return;
        final File target = displayedFile;
        decoder.execute(() -> {
            DecodedWallpaper decoded = decode(target, crisp);
            root.post(() -> {
                if (closed || !target.equals(displayedFile) || sharpCrispMode == crisp) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                if (decoded == null) return;
                sharpCrispMode = crisp;
                show(decoded);
            });
        });
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    Bitmap blurred() {
        return blurredCurrent;
    }

    /** True when a decoded wallpaper (with baked header + cinematic readability scrim) is active on screen. */
    boolean hasActiveWallpaper() {
        return current != null;
    }

    void setOnWallpaperChanged(Runnable listener) {
        onWallpaperChanged = listener;
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null && blurredCurrent == null && sharpCurrent == null) return;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        blurredCurrent = null;
        sharpCurrent = null;
        displayedFile = null;
        ExpressiveGlass.setHomeWallpaperBackdrop(null, null, 0, 0);
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: calendar holiday/season + time-of-day group, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, sunDay,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        final boolean crisp = !ExpressiveGlass.isHomeBackdropBlurEnabled();
        loading = true;
        decoder.execute(() -> {
            DecodedWallpaper decoded = decode(next, crisp);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                if (decoded == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                displayedFile = next;
                sharpCrispMode = crisp;
                show(decoded);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits a gap
     * below the buttons' row rather than flush against it; the offset is fixed, so a taller top bar (font scale,
     * an extra date line) eats into that gap — and it has to stay clear of the clock block and of the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        // Room for the shadow (6 dp of blur, 1 dp down) inside the view: Home's root, and the layer a fade draws
        // through, cut everything at the view's edges, which left a hard-edged dark box around the text. The
        // margins below take the padding back, so the text stays where it was.
        int pad = Math.round(8 * dp);
        text.setPadding(pad, pad, pad, pad);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp) + 2 * pad);
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(137 * dp) - pad;
        params.setMarginEnd(Math.round(62 * dp) - pad);
        // In a holder of its own, so scrolling can fade it (scrolledAway) without fighting the picture-change fade.
        FrameLayout holder = new FrameLayout(context);
        holder.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(holder, params);
        return text;
    }

    /**
     * Home scrolled away from the dock (true) or back to it (false). The caption sits on the screen, not in the
     * grid, so rows scrolled up under it would put the text over the tiles: it fades out while the grid is scrolled.
     */
    void scrolledAway(boolean away) {
        if (caption == null || away == captionAway) return;
        captionAway = away;
        View holder = (View) caption.getParent();
        holder.animate().cancel();
        holder.animate().alpha(away ? 0f : 1f).setDuration(away ? 150 : 300).start();
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    private static final int BLUR_W = 480;
    private static final int BLUR_H = 270;

    private static final class DecodedWallpaper {
        final Bitmap main;
        final Bitmap blurred;
        final Bitmap sharp;
        final int seedRgb;

        DecodedWallpaper(Bitmap main, Bitmap blurred, Bitmap sharp, int seedRgb) {
            this.main = main;
            this.blurred = blurred;
            this.sharp = sharp;
            this.seedRgb = seedRgb;
        }

        void recycle() {
            if (main != null && !main.isRecycled()) main.recycle();
            if (blurred != null && !blurred.isRecycled()) blurred.recycle();
            if (sharp != null && !sharp.isRecycled()) sharp.recycle();
        }
    }

    /** Puts a picture on screen and releases the previous main texture after a frame has drawn the new one. */
    private void show(DecodedWallpaper pair) {
        Bitmap old = current;
        current = pair.main;
        blurredCurrent = pair.blurred;
        sharpCurrent = pair.sharp;
        ExpressiveGlass.setDynamicSeedRgb(pair.seedRgb);
        ExpressiveGlass.setHomeWallpaperBackdrop(pair.sharp, pair.blurred, width, height);
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        image.setImageBitmap(pair.main);
        if (onWallpaperChanged != null) onWallpaperChanged.run();
        // Let GC reclaim small sharp/blurred bitmaps once active BitmapShaders & JPEG encoder release them.
        if (old != null && old != pair.main) image.postDelayed(old::recycle, 500);
    }

    private DecodedWallpaper decode(File file, boolean crispSharp) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            // Into sRGB once, here. A photo with an Adobe RGB or Display P3 profile otherwise keeps that colour space
            // and the GPU converts the full-screen picture on every frame.
            options.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
            options.inMutable = true;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            Canvas canvas = new Canvas(decoded);
            if (dimColor != 0) canvas.drawColor(dimColor);
            // Extract vibrant frosted-glass backdrop & dynamic Material You seed BEFORE painting edge scrims.
            int[] seedOut = {ExpressiveGlass.DEFAULT_SEED_RGB};
            Bitmap blurred = makeBlurred(decoded, this.width, this.height, seedOut);
            paintReadabilityScrims(canvas, decoded.getWidth(), decoded.getHeight());
            Bitmap sharp = makeSharpScaled(decoded, this.width, this.height, crispSharp);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return new DecodedWallpaper(decoded, blurred, sharp, seedOut[0]);
            decoded.recycle();
            return new DecodedWallpaper(gpu, blurred, sharp, seedOut[0]);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private static Bitmap makeSharpScaled(Bitmap src, int screenW, int screenH, boolean crisp) {
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            Rect crop;
            if (screenW > 0 && screenH > 0 && (long) sw * screenH > (long) screenW * sh) {
                int cw = Math.max(1, Math.round(sh * (float) screenW / screenH));
                int left = Math.max(0, (sw - cw) / 2);
                crop = new Rect(left, 0, Math.min(sw, left + cw), sh);
            } else if (screenW > 0 && screenH > 0) {
                int ch = Math.max(1, Math.round(sw * (float) screenH / screenW));
                int top = Math.max(0, (sh - ch) / 2);
                crop = new Rect(0, top, sw, Math.min(sh, top + ch));
            } else {
                crop = new Rect(0, 0, sw, sh);
            }
            // When sharp-Home mode is active (crisp == true), keep 960x540 uSharp 100% untouched so Dock,
            // top-right circular pills, and Liquid Clock refract crystal-sharp wallpaper details along their
            // curved Liquid Glass 2.0 bezels (matching the Screensaver Now Playing card). Otherwise apply
            // a soft 2-pass prefilter matching full-screen blurred Home mode.
            int lw = ExpressiveGlass.HOME_WALLPAPER_SHARP_W;
            int lh = ExpressiveGlass.HOME_WALLPAPER_SHARP_H;
            Bitmap out = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(src, crop, new Rect(0, 0, lw, lh), new Paint(Paint.FILTER_BITMAP_FLAG));
            if (!crisp) {
                int[] px = new int[lw * lh];
                int[] tmp = new int[lw * lh];
                out.getPixels(px, 0, lw, 0, 0, lw, lh);
                for (int pass = 0; pass < 2; pass++) {
                    boxBlurHorizontal(px, tmp, lw, lh, 5);
                    boxBlurVertical(tmp, px, lw, lh, 5);
                }
                out.setPixels(px, 0, lw, 0, 0, lw, lh);
            }
            out.prepareToDraw();
            return out;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    /**
     * Bakes a feather-soft top header readability vignette (for the clock, greeting & date over bright skies)
     * plus the cinematic left/bottom shelf scrim once into the wallpaper bitmap — zero per-frame GPU overdraw.
     */
    private void paintReadabilityScrims(Canvas canvas, int w, int h) {
        Paint paint = new Paint(Paint.DITHER_FLAG);
        // Gentle top vignette so clock, greeting, date & top pills stay effortlessly legible over snowy/bright skies.
        paint.setShader(new LinearGradient(0f, 0f, 0f, h * 0.27f,
                new int[]{0x5C04060B, 0x2404060B, 0x0004060B},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, w, h * 0.27f, paint);

        if (scrimAlpha > 0) {
            float k = scrimAlpha / 255f;
            int side0 = (Math.round(0xD1 * k) << 24) | 0x060406;
            int side1 = (Math.round(0x59 * k) << 24) | 0x060406;
            paint.setShader(new LinearGradient(rtl ? w : 0f, 0f, rtl ? 0f : w, 0f,
                    new int[]{side0, side1, 0x00060406}, new float[]{0f, 0.42f, 0.70f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, w, h, paint);

            int bot1 = (Math.round(0xDB * k) << 24) | 0x060406;
            int bot2 = (Math.round(0xFF * k) << 24) | 0x060406;
            paint.setShader(new LinearGradient(0f, 0f, 0f, h,
                    new int[]{0x00060406, 0x00060406, bot1, bot2},
                    new float[]{0f, 0.28f, 0.58f, 0.76f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, w, h, paint);
        }
    }

    /**
     * Creates a vibrant 240x135 Android 17 style frosted-glass backdrop texture matching CENTER_CROP aspect
     * (4-pass separable O(N) Gaussian-approximating blur + vibrancy lift + Material You seed extraction).
     */
    private static Bitmap makeBlurred(Bitmap src, int screenW, int screenH, int[] seedOut) {
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            Rect crop;
            if (screenW > 0 && screenH > 0 && (long) sw * screenH > (long) screenW * sh) {
                int cw = Math.max(1, Math.round(sh * (float) screenW / screenH));
                int left = Math.max(0, (sw - cw) / 2);
                crop = new Rect(left, 0, Math.min(sw, left + cw), sh);
            } else if (screenW > 0 && screenH > 0) {
                int ch = Math.max(1, Math.round(sw * (float) screenH / screenW));
                int top = Math.max(0, (sh - ch) / 2);
                crop = new Rect(0, top, sw, Math.min(sh, top + ch));
            } else {
                crop = new Rect(0, 0, sw, sh);
            }
            Bitmap out = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(src, crop, new Rect(0, 0, BLUR_W, BLUR_H), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] px = new int[BLUR_W * BLUR_H];
            out.getPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
            if (seedOut != null && seedOut.length > 0) {
                seedOut[0] = ExpressiveGlass.extractSeedRgb(px);
            }
            saturateAndLift(px, 1.28f, 6f);
            int[] tmp = new int[px.length];
            for (int pass = 0; pass < 4; pass++) {
                boxBlurHorizontal(px, tmp, BLUR_W, BLUR_H, 12);
                boxBlurVertical(tmp, px, BLUR_W, BLUR_H, 12);
            }
            out.setPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
            out.prepareToDraw();
            return out;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static void saturateAndLift(int[] px, float saturation, float blackLift) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(
                    clamp(lum + (r - lum) * saturation + blackLift),
                    clamp(lum + (g - lum) * saturation + blackLift),
                    clamp(lum + (b - lum) * saturation + blackLift));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    private static void boxBlurHorizontal(int[] src, int[] dst, int w, int h, int radius) {
        int span = 2 * radius + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int p = src[row + Math.max(0, Math.min(w - 1, k))];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = (0xFF << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int remove = src[row + Math.max(0, x - radius)];
                int add = src[row + Math.min(w - 1, x + radius + 1)];
                r += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
                b += (add & 0xFF) - (remove & 0xFF);
            }
        }
    }

    private static void boxBlurVertical(int[] src, int[] dst, int w, int h, int radius) {
        int span = 2 * radius + 1;
        for (int x = 0; x < w; x++) {
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int p = src[Math.max(0, Math.min(h - 1, k)) * w + x];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (0xFF << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int remove = src[Math.max(0, y - radius) * w + x];
                int add = src[Math.min(h - 1, y + radius + 1) * w + x];
                r += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
                b += (add & 0xFF) - (remove & 0xFF);
            }
        }
    }
}
