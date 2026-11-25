package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ambient screensaver scene shared by {@link ClockActivity} and {@link ClockDream}:
 * - Dimmed home mood wallpaper + blurred atmospheric album-art wash + dynamic album-colored radial glow.
 * - Floating thin clock, uppercase date, live local weather (Open-Meteo), next sunrise/sunset,
 *   and conditional Bluetooth audio / VPN badges.
 * - Frosted-glass "Now Playing" showcase card with artwork, live progress bar, and direct remote control
 *   (Left/Right track skip, Up/Down play/pause without waking the screensaver).
 * - Rotates across 4 balanced editorial compositions every minute with micro-jitter for OLED/TV burn-in safety.
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    private static final long GLOW_FADE_MS = 2400L;
    private static final ArgbEvaluator GLOW_EVALUATOR = new ArgbEvaluator();
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    /** Releases HW video decoder off the UI thread so waking back to Home never drops frames. */
    private static final ExecutorService AERIAL_RELEASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final float density;

    private final View rootView;
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView wallpaperView;
    private final TextureView videoView;
    private final View videoScrim;
    private final ImageView artWashView;
    private final View ambientGlowView;
    private final Bitmap glowMaskBitmap;
    private final Canvas glowMaskCanvas;
    private final Paint glowMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BitmapDrawable glowDrawable;
    private float glowCenterX = -1f;
    private float glowCenterY = -1f;
    private final View aerialLocationBox;
    private final TextView aerialLocationText;

    // Weather, solar & status badges
    private final View weatherGroup;
    private final ImageView weatherIcon;
    private final TextView weatherText;
    private final View infoDot;
    private final View solarGroup;
    private final ImageView solarIcon;
    private final TextView solarText;
    private final View badgesRow;
    private final View audioChip;
    private final TextView audioText;
    private final TextView vpnChip;

    // Frosted-glass Now Playing card
    private final View mediaCard;
    private final View artFrame;
    private final ImageView artImage;
    private final ImageView artFallback;
    private final View artRim;
    private final ImageView mediaAppIcon;
    private final TextView mediaAppName;
    private final View mediaStatusDot;
    private final TextView mediaStatusText;
    private final TextView mediaTitle;
    private final TextView mediaSubtitle;
    private final View progressBlock;
    private final View progressTrack;
    private final View progressFill;
    private final TextView timeElapsed;
    private final TextView timeDuration;
    private final TextView liveBadge;
    private final View controlsRow;
    private final ImageView controlsIcon;
    private final TextView controlsHint;

    private Context appCtx;
    private boolean interactiveControls;
    private Map<String, String> appLabels = Collections.emptyMap();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Map<String, String> nameCache = new HashMap<>();
    private final Map<String, Bitmap> uriArtCache = new HashMap<>();

    private MediaSessionTracker mediaTracker;
    @Nullable
    private MediaController activeMedia;
    private boolean hasMedia;
    private String lastArtKey = "";
    private int lastProgressWidth = -1;

    private int colorIndex;
    private int glowColor;
    private int layoutSlot = -1;
    private int currentJitterX;
    private int currentJitterY;
    private float currentRandX = 0.5f;
    private float currentRandY = 0.5f;
    private float placedContentY;
    private float placedMediaX;
    private float placedMediaY;
    private boolean placedOnce;
    private boolean wallpaperShown;
    private boolean glowOff;
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    private int wallpaperGeneration;
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods = Collections.emptyMap();
    private String latitude = "";
    private String longitude = "";
    private String weatherCity = "";
    private boolean started;

    // Apple TV Aerial 1080p slow-motion video playback state
    private boolean aerialEnabled;
    private boolean aerialVideoActive;
    private boolean usingImmichPhotos;
    @Nullable
    private TrendingPicksRepository.Pick featuredPick;
    private Surface videoSurface;
    private MediaPlayer aerialPlayer;
    private String currentAerialUrl;
    private int aerialRetryCount;
    private final Matrix videoMatrix = new Matrix();
    private final GradientDrawable statusDotDrawable = new GradientDrawable();
    @Nullable
    private Bitmap washBitmap;

    // Live Aerial video & screensaver wallpaper backdrop for real-time Liquid Glass refraction on mediaCard.
    // Zero artificial FPS cap: natural atomic busy-gate runs at full native video / display refresh rate.
    private static final int VIDEO_SAMPLE_W = 360;
    private static final int VIDEO_SAMPLE_H = 202;
    private final AtomicBoolean videoSampleBusy = new AtomicBoolean(false);
    private int videoBufSlot;
    @Nullable
    private Bitmap[] videoSharpBufs;
    @Nullable
    private Bitmap[] videoBlurBufs;
    @Nullable
    private int[] videoPxScratch;
    @Nullable
    private int[] videoTmpScratch;
    private static final double OLED_DRIFT_PERIOD_MS = 600_000.0;
    private static final long OLED_DRIFT_TICK_MS = 2_000L;
    @Nullable
    private final LiquidClockView liquidClockView;
    private long driftStartMs;
    private long lastClockSampleMs;
    @Nullable
    private ExpressiveGlass.RefractionBackdrop wallpaperBackdrop;
    @Nullable
    private ValueAnimator glowAnimator;

    private final Runnable oledDriftTick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (FeaturePrefs.isOledDriftEnabled(appCtx)) {
                double phase = 2.0 * Math.PI * (SystemClock.uptimeMillis() - driftStartMs) / OLED_DRIFT_PERIOD_MS;
                float dx = (float) (18f * density * Math.sin(phase));
                float dy = (float) (12f * density * Math.sin(2.0 * phase + 0.7));
                content.setTranslationX(dx);
                content.setTranslationY(dy);
                if (liquidClockView != null) liquidClockView.invalidate();
            } else {
                content.setTranslationX(0f);
                content.setTranslationY(0f);
            }
            handler.postDelayed(this, OLED_DRIFT_TICK_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            moveScene();
            if (!hasMedia) shiftGlowTo(PALETTE[(colorIndex = (colorIndex + 1) % PALETTE.length)]);
            advanceWallpaper();
            refreshAmbientMeta();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (!started || activeMedia == null) return;
            updateProgress(activeMedia.getPlaybackState(), activeMedia.getMetadata());
            PlaybackState ps = activeMedia.getPlaybackState();
            if (ps != null && MediaSessionTracker.isPlayingState(ps.getState())) {
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable resetHintRunnable = new Runnable() {
        @Override
        public void run() {
            if (controlsIcon != null) controlsIcon.setVisibility(View.GONE);
            if (controlsRow != null) controlsRow.setVisibility(View.GONE);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        this.rootView = root;
        this.appCtx = root.getContext().getApplicationContext();
        this.density = root.getResources().getDisplayMetrics().density;
        content = root.findViewById(R.id.dreamContent);
        View rawClk = root.findViewById(R.id.dreamClock);
        liquidClockView = rawClk instanceof LiquidClockView ? (LiquidClockView) rawClk : null;
        if (liquidClockView != null) {
            liquidClockView.setLiquidEnabled(FeaturePrefs.isLiquidClockEnabled(appCtx), true);
        }
        clockRefresh = new ClockRefresh(content);
        wallpaperView = root.findViewById(R.id.dreamWallpaper);
        videoView = root.findViewById(R.id.dreamVideo);
        videoScrim = root.findViewById(R.id.dreamVideoScrim);
        artWashView = root.findViewById(R.id.dreamArtWash);
        ambientGlowView = root.findViewById(R.id.dreamAmbient);
        aerialLocationBox = root.findViewById(R.id.dreamAerialLocation);
        aerialLocationText = root.findViewById(R.id.dreamAerialLocationText);

        weatherGroup = root.findViewById(R.id.dreamWeatherGroup);
        weatherIcon = root.findViewById(R.id.dreamWeatherIcon);
        weatherText = root.findViewById(R.id.dreamWeatherText);
        infoDot = root.findViewById(R.id.dreamInfoDot);
        solarGroup = root.findViewById(R.id.dreamSolarGroup);
        solarIcon = root.findViewById(R.id.dreamSolarIcon);
        solarText = root.findViewById(R.id.dreamSolarText);
        badgesRow = root.findViewById(R.id.dreamBadgesRow);
        audioChip = root.findViewById(R.id.dreamAudioChip);
        audioText = root.findViewById(R.id.dreamAudioText);
        vpnChip = root.findViewById(R.id.dreamVpnChip);

        mediaCard = root.findViewById(R.id.dreamMediaCard);
        artFrame = root.findViewById(R.id.dreamArtFrame);
        artImage = root.findViewById(R.id.dreamArtImage);
        artFallback = root.findViewById(R.id.dreamArtFallback);
        artRim = root.findViewById(R.id.dreamArtRim);
        mediaAppIcon = root.findViewById(R.id.dreamMediaAppIcon);
        mediaAppName = root.findViewById(R.id.dreamMediaAppName);
        mediaStatusDot = root.findViewById(R.id.dreamMediaStatusDot);
        mediaStatusText = root.findViewById(R.id.dreamMediaStatusText);
        mediaTitle = root.findViewById(R.id.dreamMediaTitle);
        mediaSubtitle = root.findViewById(R.id.dreamMediaSubtitle);
        progressBlock = root.findViewById(R.id.dreamProgressBlock);
        progressTrack = root.findViewById(R.id.dreamProgressTrack);
        progressFill = root.findViewById(R.id.dreamProgressFill);
        timeElapsed = root.findViewById(R.id.dreamTimeElapsed);
        timeDuration = root.findViewById(R.id.dreamTimeDuration);
        liveBadge = root.findViewById(R.id.dreamLiveBadge);
        controlsRow = root.findViewById(R.id.dreamControlsRow);
        controlsIcon = root.findViewById(R.id.dreamControlsIcon);
        controlsHint = root.findViewById(R.id.dreamControlsHint);

        // Pre-baked 480x270 dithered white radial glow mask tinted via GPU setTint() — eliminates full-screen
        // 1920x1080 radial gradient evaluation on every Aerial video frame with zero 8-bit banding.
        glowMaskPaint.setDither(true);
        glowMaskBitmap = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
        glowMaskCanvas = new Canvas(glowMaskBitmap);
        glowDrawable = new BitmapDrawable(root.getResources(), glowMaskBitmap);
        glowDrawable.setFilterBitmap(true);
        glowDrawable.setDither(true);
        glowDrawable.setGravity(Gravity.FILL);
        ambientGlowView.setBackground(glowDrawable);
        updateGlowCenter(0.5f, 0.5f);
        View.OnLayoutChangeListener reClamp = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (started && placedOnce && ((r - l) != (or - ol) || (b - t) != (ob - ot))) {
                placeScene(false);
            }
        };
        content.addOnLayoutChangeListener(reClamp);
        mediaCard.addOnLayoutChangeListener(reClamp);
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        styleAmbientWidgets();
    }

    private void styleAmbientWidgets() {
        if (mediaCard != null) {
            ExpressiveGlass.applyPanelSurface(mediaCard, density, 24f, false, false, true);
        }
        ExpressiveGlass.applyLuminousTitle(rootView.findViewById(R.id.dreamClock), density, true);
        ExpressiveGlass.applyReadableShadow(rootView.findViewById(R.id.dreamDate), density, false);
        ExpressiveGlass.applyReadableShadow(weatherText, density, true);
        ExpressiveGlass.applyReadableShadow(solarText, density, true);
        if (aerialLocationText != null) {
            aerialLocationText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
        applyRoundedOutline(artFrame, dp(18));
        statusDotDrawable.setShape(GradientDrawable.OVAL);
        mediaStatusDot.setBackground(statusDotDrawable);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(dp(18));
        rim.setStroke(Math.max(1, Math.round(1.1f * density)), 0x45FFFFFF);
        artRim.setBackground(rim);

        applyRoundedOutline(mediaAppIcon, dp(4));
        applyRoundedOutline(progressTrack, dp(2));
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x2EFFFFFF);
        trackBg.setCornerRadius(dp(2));
        progressTrack.setBackground(trackBg);

        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xF0FFFFFF);
        fillBg.setCornerRadius(dp(2));
        progressFill.setBackground(fillBg);

        GradientDrawable audioBg = new GradientDrawable();
        audioBg.setColor(0x28FFFFFF);
        audioBg.setCornerRadius(dp(13));
        audioBg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        audioChip.setBackground(audioBg);

        GradientDrawable vpnBg = new GradientDrawable();
        vpnBg.setColor(0x241B4D2E);
        vpnBg.setCornerRadius(dp(13));
        vpnBg.setStroke(Math.max(1, dp(1)), 0x6634C759);
        vpnChip.setBackground(vpnBg);

        if (videoScrim != null) {
            videoScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x5C000000, 0x2E000000, 0x6B000000}));
        }
        if (aerialLocationBox != null) {
            GradientDrawable locBg = new GradientDrawable();
            locBg.setColor(0x33000000);
            locBg.setCornerRadius(dp(14));
            locBg.setStroke(Math.max(1, dp(1)), 0x30FFFFFF);
            aerialLocationBox.setBackground(locBg);
        }
        if (videoView != null) {
            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                    releaseSurface();
                    videoSurface = new Surface(st);
                    if (started && aerialEnabled) startNextAerial();
                }
                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                    if (aerialPlayer != null) {
                        applyVideoCenterCrop(aerialPlayer.getVideoWidth(), aerialPlayer.getVideoHeight());
                    }
                }
                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    stopAerialPlayerAsync(true, st);
                    return false;
                }
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    sampleAerialVideoForGlass();
                }
            });
        }
    }

    private void ensureVideoSampleBuffers() {
        if (videoSharpBufs != null && videoBlurBufs != null) return;
        try {
            videoSharpBufs = new Bitmap[]{
                    Bitmap.createBitmap(VIDEO_SAMPLE_W, VIDEO_SAMPLE_H, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(VIDEO_SAMPLE_W, VIDEO_SAMPLE_H, Bitmap.Config.ARGB_8888)
            };
            videoBlurBufs = new Bitmap[]{
                    Bitmap.createBitmap(VIDEO_SAMPLE_W, VIDEO_SAMPLE_H, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(VIDEO_SAMPLE_W, VIDEO_SAMPLE_H, Bitmap.Config.ARGB_8888)
            };
            int n = VIDEO_SAMPLE_W * VIDEO_SAMPLE_H;
            videoPxScratch = new int[n];
            videoTmpScratch = new int[n];
        } catch (OutOfMemoryError ignored) {
            videoSharpBufs = null;
            videoBlurBufs = null;
        }
    }

    /** Samples the live Aerial TextureView frame for Liquid Glass mediaCard and/or iOS 26 glass clock. */
    private void sampleAerialVideoForGlass() {
        if (!started || !aerialVideoActive || videoView == null) return;
        boolean cardVisible = hasMedia && mediaCard != null
                && mediaCard.getVisibility() == View.VISIBLE && mediaCard.getAlpha() > 0.01f;
        boolean clockLiquid = liquidClockView != null && FeaturePrefs.isLiquidClockEnabled(appCtx);
        if (!cardVisible && !clockLiquid) return;
        if (!cardVisible) {
            long now = SystemClock.uptimeMillis();
            if (now - lastClockSampleMs < 120L) return;
            lastClockSampleMs = now;
        }
        if (!videoSampleBusy.compareAndSet(false, true)) return;
        int rootW = rootView.getWidth();
        int rootH = rootView.getHeight();
        if (rootW <= 0 || rootH <= 0) {
            videoSampleBusy.set(false);
            return;
        }
        ensureVideoSampleBuffers();
        if (videoSharpBufs == null || videoBlurBufs == null) {
            videoSampleBusy.set(false);
            return;
        }
        final int slot = videoBufSlot;
        videoBufSlot ^= 1;
        final Bitmap sharp = videoSharpBufs[slot];
        final Bitmap blur = videoBlurBufs[slot];
        if (sharp == null || blur == null || sharp.isRecycled() || blur.isRecycled()) {
            videoSampleBusy.set(false);
            return;
        }
        try {
            videoView.getBitmap(sharp);
        } catch (RuntimeException ignored) {
            videoSampleBusy.set(false);
            return;
        }
        final int[] px = videoPxScratch;
        final int[] tmp = videoTmpScratch;
        ExpressiveGlass.runOnRefractThread(() -> {
            try {
                if (!started || !aerialVideoActive) return;
                if (cardVisible) {
                    if (!ExpressiveGlass.blurInto(sharp, blur, px, tmp, 14)) return;
                }
                ExpressiveGlass.RefractionBackdrop next =
                        ExpressiveGlass.wrapBackdrop(sharp, blur, rootW, rootH);
                handler.post(() -> {
                    if (started && aerialVideoActive) {
                        if (mediaCard != null) ExpressiveGlass.setPanelBackdrop(mediaCard, next);
                        if (liquidClockView != null) liquidClockView.setCustomBackdrop(next);
                    }
                });
            } finally {
                videoSampleBusy.set(false);
            }
        });
    }

    /** Configures wallpaper, location, labels, and whether D-pad media hints should be shown. */
    void useHomeWallpaper(Context ctx) {
        useHomeWallpaper(ctx, true, null);
    }

    void useHomeWallpaper(Context ctx, boolean interactiveControls) {
        useHomeWallpaper(ctx, interactiveControls, null);
    }

    void useHomeWallpaper(Context ctx, boolean interactiveControls, @Nullable Config preloadedConfig) {
        this.appCtx = LauncherLocale.wrap(ctx.getApplicationContext());
        this.interactiveControls = interactiveControls;
        if (controlsRow != null) {
            controlsRow.setVisibility(View.GONE);
        } else if (controlsHint != null) {
            controlsHint.setVisibility(View.GONE);
        }
        latitude = ctx.getString(R.string.latitude);
        longitude = ctx.getString(R.string.longitude);
        weatherCity = ctx.getString(R.string.weather_city);
        aerialEnabled = AerialCatalog.isEnabled(ctx);

        Config cfg = preloadedConfig;
        if (cfg == null) {
            try {
                cfg = Config.load(ctx);
            } catch (RuntimeException e) {
                return;
            }
        }
        appLabels = cfg.appLabels != null ? cfg.appLabels : Collections.emptyMap();
        glowOff = !cfg.clockGlow;
        if (glowOff) ambientGlowView.setBackground(null);
        if (!cfg.clockWallpaper) return;

        List<File> immichPhotos = ImmichSyncManager.listLocalPhotos(ctx);
        if (!immichPhotos.isEmpty()) {
            usingImmichPhotos = true;
            aerialEnabled = false;
            rotation = new ArrayList<>(immichPhotos);
            wallpaperFile = rotation.get(random.nextInt(rotation.size()));
            rotationMs = Math.max(45_000L, cfg.wallpaperRotationMinutes * 60_000L);
            order = "shuffle";
            moods = Collections.emptyMap();
        } else if (cfg.wallpapers.isEmpty()) {
            usingImmichPhotos = false;
            wallpaperFile = null;
        } else {
            usingImmichPhotos = false;
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
        }
        featuredPick = null;
        if (FeaturePrefs.watchFromScreensaver(ctx)) {
            List<TrendingPicksRepository.Pick> cached = TrendingPicksRepository.readCachedOnly(ctx);
            if (!cached.isEmpty()) {
                featuredPick = cached.get(random.nextInt(cached.size()));
            }
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    boolean hasFeaturedPick() {
        return featuredPick != null;
    }

    boolean openFeaturedPick(android.app.Activity activity) {
        if (featuredPick == null) return false;
        android.content.Intent launch = TrendingPicksRepository.buildLaunchIntent(activity, featuredPick);
        if (launch == null) return false;
        try {
            activity.startActivity(launch);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    void start() {
        started = true;
        content.setAlpha(0f);
        mediaCard.setAlpha(0f);

        if (appCtx != null) {
            if (mediaTracker == null) {
                mediaTracker = new MediaSessionTracker(appCtx, this::onMediaUpdated);
            }
            mediaTracker.start();
            refreshAmbientMeta();
        }

        rootView.post(() -> {
            if (!started) return;
            placeScene(true);
            content.animate().cancel();
            content.setY(placedContentY + dp(18));
            content.animate().alpha(1f).y(placedContentY)
                    .setDuration(920L).setInterpolator(Motion.easeOutExpo()).start();
            if (hasMedia && mediaCard.getVisibility() == View.VISIBLE) {
                mediaCard.animate().cancel();
                mediaCard.setY(placedMediaY + dp(18));
                mediaCard.animate().alpha(1f).y(placedMediaY)
                        .setStartDelay(80L).setDuration(920L).setInterpolator(Motion.easeOutExpo())
                        .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                        .start();
            }
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        driftStartMs = SystemClock.uptimeMillis();
        handler.removeCallbacks(oledDriftTick);
        handler.postDelayed(oledDriftTick, OLED_DRIFT_TICK_MS);
        if (liquidClockView != null) {
            liquidClockView.setLiquidEnabled(FeaturePrefs.isLiquidClockEnabled(appCtx), true);
        }
        clockRefresh.start();
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
        if (appCtx != null) {
            aerialEnabled = !usingImmichPhotos && AerialCatalog.isEnabled(appCtx);
            if (aerialEnabled && videoView != null && videoView.isAvailable()) {
                if (videoSurface == null && videoView.getSurfaceTexture() != null) {
                    videoSurface = new Surface(videoView.getSurfaceTexture());
                }
                startNextAerial();
            } else if (featuredPick != null && aerialLocationBox != null && aerialLocationText != null) {
                String caption = featuredPick.title
                        + (featuredPick.serviceLabel.isEmpty() ? "" : " · " + featuredPick.serviceLabel)
                        + " · OK";
                aerialLocationText.setText(caption);
                aerialLocationBox.setVisibility(View.VISIBLE);
                aerialLocationBox.setAlpha(0.90f);
                aerialLocationBox.post(this::positionAerialCaption);
            }
        }
    }

    /** Stops live playback/tickers without blanking visuals so the window exit fade stays silky. */
    void pauseForExit() {
        started = false;
        handler.removeCallbacksAndMessages(null);
        if (glowAnimator != null) {
            glowAnimator.cancel();
            glowAnimator = null;
        }
        clockRefresh.stop();
        content.animate().cancel();
        mediaCard.animate().cancel();
        artWashView.animate().cancel();
        artFrame.animate().cancel();
        mediaTitle.animate().cancel();
        mediaSubtitle.animate().cancel();
        weatherGroup.animate().cancel();
        if (aerialLocationBox != null) aerialLocationBox.animate().cancel();
        if (videoView != null) videoView.animate().cancel();
        if (videoScrim != null) videoScrim.animate().cancel();
        stopAerialPlayerAsync(true);
        if (mediaTracker != null) mediaTracker.stop();
        wallpaperGeneration++;
        aerialVideoActive = false;
    }

    /** Reclaims all bitmaps & GPU textures after the screensaver window has finished fading out. */
    void releaseResources() {
        stopAerialPlayerAsync(true);
        ExpressiveGlass.setPanelBackdrop(mediaCard, null);
        if (liquidClockView != null) liquidClockView.setCustomBackdrop(null);
        final Bitmap[] sharpToFree = videoSharpBufs;
        final Bitmap[] blurToFree = videoBlurBufs;
        final ExpressiveGlass.RefractionBackdrop wpToFree = wallpaperBackdrop;
        videoSharpBufs = null;
        videoBlurBufs = null;
        videoPxScratch = null;
        videoTmpScratch = null;
        wallpaperBackdrop = null;
        if (sharpToFree != null || blurToFree != null || wpToFree != null) {
            ExpressiveGlass.runOnRefractThread(() -> {
                if (sharpToFree != null) {
                    for (Bitmap b : sharpToFree) if (b != null && !b.isRecycled()) b.recycle();
                }
                if (blurToFree != null) {
                    for (Bitmap b : blurToFree) if (b != null && !b.isRecycled()) b.recycle();
                }
                if (wpToFree != null) {
                    if (!wpToFree.sharp.isRecycled()) wpToFree.sharp.recycle();
                    if (!wpToFree.blur.isRecycled()) wpToFree.blur.recycle();
                }
            });
        }
        wallpaperView.setImageDrawable(null);
        wallpaperShown = false;
        artImage.setImageBitmap(null);
        artWashView.setImageBitmap(null);
        recycleWashBitmap();
        uriArtCache.clear();
        lastArtKey = "";
    }

    void stop() {
        pauseForExit();
        releaseResources();
    }

    private void recycleWashBitmap() {
        Bitmap old = washBitmap;
        washBitmap = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    boolean isAerialVideoActive() {
        return aerialEnabled && (aerialVideoActive || aerialPlayer != null);
    }

    /** Advances to the next Apple TV Aerial video clip immediately (Apple TV swipe-next feel). */
    void skipAerial() {
        if (!started || !aerialEnabled || videoSurface == null) return;
        aerialRetryCount = 0;
        if (aerialLocationBox != null && aerialLocationBox.getVisibility() == View.VISIBLE) {
            aerialLocationBox.animate().cancel();
            aerialLocationBox.animate().alpha(0f).setDuration(220L)
                    .setInterpolator(Motion.easeInExit()).start();
        }
        if (videoView != null) {
            videoView.animate().alpha(0.18f).setDuration(260)
                    .withEndAction(() -> { if (started) startNextAerial(); }).start();
        } else {
            startNextAerial();
        }
    }

    private void startNextAerial() {
        if (!started || appCtx == null || !aerialEnabled || videoSurface == null) return;
        stopAerialPlayer();
        boolean night = isNightNow();
        AerialCatalog.Clip clip = AerialCatalog.pick(appCtx, night, currentAerialUrl, random);
        currentAerialUrl = clip.url;

        MediaPlayer mp = new MediaPlayer();
        aerialPlayer = mp;
        try {
            mp.setSurface(videoSurface);
            mp.setVolume(0f, 0f);
            mp.setLooping(false);
            mp.setDataSource(clip.url);
            mp.setOnVideoSizeChangedListener((player, vw, vh) -> {
                if (player == aerialPlayer) applyVideoCenterCrop(vw, vh);
            });
            mp.setOnPreparedListener(player -> {
                if (!started || player != aerialPlayer) return;
                player.setVolume(0f, 0f);
                applyVideoCenterCrop(player.getVideoWidth(), player.getVideoHeight());
                player.start();
            });
            mp.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && started && player == aerialPlayer) {
                    aerialRetryCount = 0;
                    aerialVideoActive = true;
                    if (videoView != null) videoView.animate().alpha(1f).setDuration(1100).start();
                    if (videoScrim != null) videoScrim.animate().alpha(1f).setDuration(1100).start();
                    // Soften album-art wash once live 1080p Aerial video appears so scenery stays vivid.
                    if (hasMedia && artWashView.getAlpha() > 0.15f) {
                        artWashView.animate().alpha(0.14f).setDuration(900).start();
                    }
                    if (aerialLocationBox != null && aerialLocationText != null) {
                        String place = AerialCatalog.displayLabel(appCtx, clip.label, clip.night);
                        if (!TextUtils.isEmpty(place)) {
                            aerialLocationText.setText(place);
                            aerialLocationBox.setVisibility(View.VISIBLE);
                            aerialLocationBox.setAlpha(0f);
                            aerialLocationBox.post(() -> {
                                if (!started) return;
                                positionAerialCaption();
                                float targetY = aerialLocationBox.getY();
                                aerialLocationBox.setY(targetY + dp(8));
                                aerialLocationBox.animate().alpha(0.92f).y(targetY)
                                        .setDuration(750L).setInterpolator(Motion.easeOutExpo()).start();
                            });
                        }
                    }
                    setGlow(glowColor);
                }
                return false;
            });
            mp.setOnCompletionListener(player -> {
                if (started && player == aerialPlayer) skipAerial();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (player == aerialPlayer) {
                    stopAerialPlayer();
                    if (started && aerialRetryCount < 2) {
                        aerialRetryCount++;
                        handler.postDelayed(this::startNextAerial, 1200L);
                    } else {
                        hideAerialVideo();
                    }
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopAerialPlayer();
            hideAerialVideo();
        }
    }

    private void stopAerialPlayer() {
        stopAerialPlayerAsync(false, null);
    }

    private void stopAerialPlayerAsync(boolean alsoReleaseSurface) {
        stopAerialPlayerAsync(alsoReleaseSurface, null);
    }

    private void stopAerialPlayerAsync(boolean alsoReleaseSurface, @Nullable SurfaceTexture textureToRelease) {
        final MediaPlayer mp = aerialPlayer;
        aerialPlayer = null;
        final Surface surfaceToRelease = alsoReleaseSurface ? videoSurface : null;
        if (alsoReleaseSurface) videoSurface = null;
        if (mp == null && surfaceToRelease == null && textureToRelease == null) return;
        if (mp != null) {
            try {
                mp.setOnPreparedListener(null);
                mp.setOnInfoListener(null);
                mp.setOnCompletionListener(null);
                mp.setOnErrorListener(null);
                mp.setVolume(0f, 0f);
                // Detach from Surface synchronously BEFORE releasing off-thread so the next MediaPlayer
                // never races against BufferQueueProducer already connected on the same Surface.
                mp.setSurface(null);
            } catch (Exception ignored) {
            }
        }
        AERIAL_RELEASE_EXECUTOR.execute(() -> {
            if (mp != null) {
                try {
                    mp.stop();
                } catch (Exception ignored) {
                }
                try {
                    mp.release();
                } catch (Exception ignored) {
                }
            }
            if (surfaceToRelease != null) {
                try {
                    surfaceToRelease.release();
                } catch (Exception ignored) {
                }
            }
            if (textureToRelease != null) {
                try {
                    textureToRelease.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void releaseSurface() {
        final Surface s = videoSurface;
        videoSurface = null;
        if (s != null) {
            AERIAL_RELEASE_EXECUTOR.execute(() -> {
                try {
                    s.release();
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void hideAerialVideo() {
        aerialVideoActive = false;
        ExpressiveGlass.setPanelBackdrop(mediaCard, wallpaperBackdrop);
        if (liquidClockView != null) liquidClockView.setCustomBackdrop(wallpaperBackdrop);
        if (videoView != null) videoView.animate().alpha(0f).setDuration(500).start();
        if (videoScrim != null) videoScrim.animate().alpha(0f).setDuration(500).start();
        if (aerialLocationBox != null) aerialLocationBox.setVisibility(View.GONE);
    }

    private void applyVideoCenterCrop(int videoW, int videoH) {
        if (videoView == null || videoW <= 0 || videoH <= 0) return;
        int viewW = videoView.getWidth();
        int viewH = videoView.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float videoRatio = videoW / (float) videoH;
        float viewRatio = viewW / (float) viewH;
        float scaleX = 1f, scaleY = 1f;
        if (videoRatio > viewRatio) scaleX = videoRatio / viewRatio;
        else scaleY = viewRatio / videoRatio;
        videoMatrix.reset();
        videoMatrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f);
        videoView.setTransform(videoMatrix);
    }

    private boolean isNightNow() {
        LocalDateTime now = LocalDateTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        if (!TextUtils.isEmpty(latitude) && !TextUtils.isEmpty(longitude)) {
            int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
            return minute < sun[0] || minute >= sun[1];
        }
        return minute < 7 * 60 || minute >= 19 * 60;
    }

    private void positionAerialCaption() {
        if (aerialLocationBox == null || aerialLocationBox.getVisibility() != View.VISIBLE) return;
        int pw = rootView.getWidth();
        int ph = rootView.getHeight();
        int lw = aerialLocationBox.getWidth();
        int lh = aerialLocationBox.getHeight();
        if (pw <= 0 || ph <= 0 || lw <= 0 || lh <= 0) return;
        int mx = dp(44);
        int my = dp(34);
        // Place caption in the opposite bottom corner from whichever element occupies the bottom edge.
        boolean bottomRightBusy = hasMedia && ((layoutSlot & 3) == 1 || (layoutSlot & 3) == 2);
        float x = bottomRightBusy ? mx : (pw - lw - mx);
        float y = ph - lh - my;
        aerialLocationBox.setX(x);
        aerialLocationBox.setY(y);
    }

    boolean hasActiveMedia() {
        return hasMedia && activeMedia != null;
    }

    boolean openActiveMediaApp(Context ctx) {
        if (activeMedia == null) return false;
        return MediaSessionTracker.openSessionApp(ctx, activeMedia);
    }

    void togglePlayPause() {
        if (mediaTracker == null || activeMedia == null) return;
        PlaybackState ps = activeMedia.getPlaybackState();
        boolean wasPlaying = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
        mediaTracker.togglePlayPause(activeMedia);
        flashControlFeedback(
                wasPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play,
                appCtx != null ? appCtx.getString(wasPlaying ? R.string.media_paused : R.string.media_playing) : "",
                0);
    }

    void skipPrevious() {
        if (activeMedia == null) return;
        MediaSessionTracker.skipPrevious(activeMedia);
        flashControlFeedback(R.drawable.ic_media_prev,
                appCtx != null ? appCtx.getString(R.string.ambient_action_prev) : "", -1);
    }

    void skipNext() {
        if (activeMedia == null) return;
        MediaSessionTracker.skipNext(activeMedia);
        flashControlFeedback(R.drawable.ic_media_next,
                appCtx != null ? appCtx.getString(R.string.ambient_action_next) : "", 1);
    }

    private void flashControlFeedback(int iconRes, String msg, int dirX) {
        mediaCard.animate().cancel();
        if (dirX != 0) {
            final float homeX = placedMediaX;
            mediaCard.animate().x(homeX + dirX * dp(12)).scaleX(1.018f).scaleY(1.018f)
                    .setDuration(105L).setInterpolator(Motion.easeOutExpo())
                    .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                    .withEndAction(() -> mediaCard.animate().x(homeX).scaleX(1f).scaleY(1f)
                            .setDuration(210L).setInterpolator(Motion.springPop())
                            .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                            .start())
                    .start();
        } else {
            mediaCard.animate().scaleX(1.032f).scaleY(1.032f)
                    .setDuration(100L).setInterpolator(Motion.easeOutExpo())
                    .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                    .withEndAction(() -> mediaCard.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200L).setInterpolator(Motion.springPop())
                            .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                            .start())
                    .start();
        }
        if (controlsHint != null && interactiveControls && !TextUtils.isEmpty(msg)) {
            handler.removeCallbacks(resetHintRunnable);
            if (controlsRow != null) controlsRow.setVisibility(View.VISIBLE);
            if (controlsIcon != null) {
                if (iconRes != 0) {
                    controlsIcon.setImageResource(iconRes);
                    controlsIcon.setVisibility(View.VISIBLE);
                } else {
                    controlsIcon.setVisibility(View.GONE);
                }
            }
            controlsHint.setText(msg);
            controlsHint.setTextColor(Color.WHITE);
            handler.postDelayed(resetHintRunnable, 1500L);
        }
    }

    private void refreshAmbientMeta() {
        if (appCtx == null) return;
        // 1. Offline sunrise / sunset from configured coordinates (local).
        if (!TextUtils.isEmpty(latitude) && !TextUtils.isEmpty(longitude)) {
            LocalDateTime now = LocalDateTime.now();
            int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
            int[] next = AmbientWeather.nextSolarEvent(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]);
            solarIcon.setImageResource(next[0] == 1 ? R.drawable.ic_ambient_sun_up : R.drawable.ic_ambient_sun_down);
            solarText.setText(AmbientWeather.formatHourMin(next[1]));
            solarGroup.setVisibility(View.VISIBLE);

            // 2. Live Open-Meteo weather (instant from 30-min cache, refreshed async).
            AmbientWeather.fetch(appCtx, latitude, longitude, snap -> {
                if (!started || appCtx == null) return;
                weatherIcon.setImageResource(AmbientWeather.iconResForWmo(snap.wmoCode, snap.isDay));
                String cond = appCtx.getString(AmbientWeather.conditionResForWmo(snap.wmoCode));
                StringBuilder label = new StringBuilder(AmbientWeather.formatTemperature(snap.tempC))
                        .append(" \u00B7 ").append(cond);
                if (!TextUtils.isEmpty(weatherCity)) label.append(" \u00B7 ").append(weatherCity);
                weatherText.setText(label.toString());
                boolean wasHidden = weatherGroup.getVisibility() != View.VISIBLE;
                weatherGroup.setVisibility(View.VISIBLE);
                if (wasHidden) {
                    weatherGroup.animate().cancel();
                    weatherGroup.setAlpha(0f);
                    weatherGroup.animate().alpha(1f).setDuration(450L)
                            .setInterpolator(Motion.easeOutExpo()).start();
                }
                infoDot.setVisibility(solarGroup.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
            });
        }
        infoDot.setVisibility(weatherGroup.getVisibility() == View.VISIBLE && solarGroup.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);

        // 3. Conditional BT Audio & VPN micro-badges.
        String audioName = AmbientWeather.activeAudioDeviceName(appCtx);
        if (!TextUtils.isEmpty(audioName)) {
            audioText.setText(audioName);
            audioChip.setVisibility(View.VISIBLE);
        } else {
            audioChip.setVisibility(View.GONE);
        }
        boolean vpn = AmbientWeather.isVpnActive(appCtx);
        vpnChip.setVisibility(vpn ? View.VISIBLE : View.GONE);
        badgesRow.setVisibility((audioChip.getVisibility() == View.VISIBLE || vpn) ? View.VISIBLE : View.GONE);
    }

    private int washGeneration;

    private void onMediaUpdated(List<MediaController> controllers) {
        if (!started) return;
        boolean hadMedia = hasMedia;
        activeMedia = (controllers != null && !controllers.isEmpty()) ? controllers.get(0) : null;
        hasMedia = activeMedia != null;

        if (!hasMedia) {
            handler.removeCallbacks(progressTicker);
            lastArtKey = "";
            if (hadMedia) {
                mediaCard.animate().cancel();
                mediaCard.animate().alpha(0f).setDuration(450)
                        .withEndAction(() -> {
                            mediaCard.setVisibility(View.GONE);
                            if (started) moveScene();
                        }).start();
                final int gen = ++washGeneration;
                artWashView.animate().cancel();
                artWashView.animate().alpha(0f).setDuration(600)
                        .withEndAction(() -> {
                            if (gen == washGeneration) {
                                artWashView.setImageBitmap(null);
                                recycleWashBitmap();
                            }
                        }).start();
                shiftGlowTo(PALETTE[colorIndex]);
            } else {
                mediaCard.setVisibility(View.GONE);
            }
            return;
        }

        bindMedia(activeMedia);
        if (!hadMedia) {
            mediaCard.setVisibility(View.VISIBLE);
            mediaCard.setAlpha(0f);
            mediaCard.post(() -> {
                if (!started || !hasMedia) return;
                placeScene(false);
                float targetY = mediaCard.getY();
                mediaCard.setY(targetY + dp(14));
                mediaCard.animate().alpha(1f).y(targetY)
                        .setDuration(680L).setInterpolator(Motion.easeOutExpo())
                        .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                        .start();
            });
        }
    }

    private void bindMedia(MediaController c) {
        if (appCtx == null) return;
        String pkg = c.getPackageName();
        PlaybackState ps = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
        String appName = loadAppName(pkg);

        mediaAppIcon.setImageDrawable(loadAppIcon(pkg));
        mediaAppName.setText(appName);
        statusDotDrawable.setColor(playing ? 0xFF34C759 : 0x80FFFFFF);
        mediaStatusText.setText(playing ? R.string.media_playing : R.string.media_paused);

        String title = MediaSessionTracker.extractTitle(md);
        String subtitle = MediaSessionTracker.extractSubtitle(md);
        mediaTitle.setText(!TextUtils.isEmpty(title) ? title : appName);
        mediaSubtitle.setText(!TextUtils.isEmpty(subtitle) ? subtitle : appName);

        // Artwork + ambient album color extraction + tiny 96x96 GPU-friendly blurred backdrop wash.
        String artKey = pkg + "|" + (title != null ? title : "") + "|" + (subtitle != null ? subtitle : "");
        boolean trackChanged = !lastArtKey.isEmpty() && !artKey.equals(lastArtKey);
        Bitmap art = MediaSessionTracker.extractArtwork(appCtx, md, 320, uriArtCache);
        if (!artKey.equals(lastArtKey) || (art != null && artImage.getVisibility() != View.VISIBLE)) {
            lastArtKey = artKey;
            if (art != null && !art.isRecycled()) {
                artFrame.setBackground(null);
                artImage.setImageBitmap(art);
                artImage.setVisibility(View.VISIBLE);
                artFallback.setVisibility(View.GONE);

                int vivid = AppAdapter.vividColor(new BitmapDrawable(appCtx.getResources(), art));
                shiftGlowTo(vivid);
                washGeneration++;
                artWashView.animate().cancel();
                // Pre-blur the atmospheric wash ONCE per track change into a silky 320x180 Gaussian texture
                // instead of running a 1920x1080 92px GPU RenderEffect blur on every Aerial video frame.
                Bitmap tinyWash = ExpressiveGlass.makeFastBlurred(art, 320, 180);
                if (tinyWash == null) tinyWash = Bitmap.createScaledBitmap(art, 320, 180, true);
                artWashView.setImageBitmap(tinyWash);
                Bitmap prevWash = washBitmap;
                washBitmap = (tinyWash != art) ? tinyWash : null;
                if (prevWash != null && prevWash != tinyWash && !prevWash.isRecycled()) {
                    prevWash.recycle();
                }
                artWashView.animate().alpha(aerialVideoActive ? 0.14f : 0.32f).setDuration(900).start();
            } else {
                Drawable icon = loadAppIcon(pkg);
                int accent = AppAdapter.vividColor(icon);
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{blendColor(accent, 0xFF1E2636, 0.55f), blendColor(accent, 0xFF101520, 0.78f)});
                bg.setCornerRadius(dp(18));
                artFrame.setBackground(bg);
                artImage.setImageBitmap(null);
                artImage.setVisibility(View.GONE);
                artFallback.setImageDrawable(icon);
                artFallback.setVisibility(View.VISIBLE);

                shiftGlowTo(accent);
                final int gen = ++washGeneration;
                artWashView.animate().cancel();
                artWashView.animate().alpha(0f).setDuration(600)
                        .withEndAction(() -> {
                            if (gen == washGeneration) {
                                artWashView.setImageBitmap(null);
                                recycleWashBitmap();
                            }
                        }).start();
            }
            if (trackChanged && mediaCard.getVisibility() == View.VISIBLE && mediaCard.getAlpha() > 0.5f) {
                artFrame.animate().cancel();
                artFrame.setScaleX(0.93f);
                artFrame.setScaleY(0.93f);
                artFrame.setAlpha(0.45f);
                artFrame.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(360L).setInterpolator(Motion.springPop()).start();
                mediaTitle.animate().cancel();
                mediaTitle.setAlpha(0.25f);
                mediaTitle.setTranslationY(dp(8));
                mediaTitle.animate().alpha(1f).translationY(0f)
                        .setDuration(320L).setInterpolator(Motion.easeOutExpo()).start();
                mediaSubtitle.animate().cancel();
                mediaSubtitle.setAlpha(0.25f);
                mediaSubtitle.setTranslationY(dp(6));
                mediaSubtitle.animate().alpha(1f).translationY(0f)
                        .setDuration(360L).setInterpolator(Motion.easeOutExpo()).start();
            }
        }

        handler.removeCallbacks(progressTicker);
        updateProgress(ps, md);
        if (playing) handler.postDelayed(progressTicker, 1000L);
    }

    private void updateProgress(@Nullable PlaybackState ps, @Nullable MediaMetadata md) {
        long duration = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0L;
        if (duration > 0L && ps != null) {
            long pos = Math.min(duration, MediaSessionTracker.currentPositionMs(ps, SystemClock.elapsedRealtime()));
            progressBlock.setVisibility(View.VISIBLE);
            liveBadge.setVisibility(View.GONE);
            timeElapsed.setText(MediaSessionTracker.formatTime(pos));
            timeDuration.setText(MediaSessionTracker.formatTime(duration));
            float fraction = Math.max(0f, Math.min(1f, pos / (float) duration));
            int trackW = progressTrack.getWidth();
            if (trackW > 0) {
                int targetW = Math.round(trackW * fraction);
                if (targetW != lastProgressWidth) {
                    lastProgressWidth = targetW;
                    progressFill.getLayoutParams().width = targetW;
                    progressFill.requestLayout();
                }
            } else {
                progressTrack.post(() -> {
                    if (started && activeMedia != null) updateProgress(ps, md);
                });
            }
        } else {
            progressBlock.setVisibility(View.GONE);
            boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
            if (appCtx != null) {
                liveBadge.setText(playing ? "\u25CF  " + appCtx.getString(R.string.media_live)
                        : appCtx.getString(R.string.media_paused));
            }
            liveBadge.setTextColor(playing ? 0xFF6BE88E : 0x99FFFFFF);
            liveBadge.setVisibility(View.VISIBLE);
        }
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            // Build matching dimmed backdrop for mediaCard's Liquid Glass refraction when Aerial video is off.
            ExpressiveGlass.RefractionBackdrop wpBg = buildWallpaperRefraction(bmp, shade, w, h);
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();
                    if (wpBg != null) {
                        if (!wpBg.sharp.isRecycled()) wpBg.sharp.recycle();
                        if (!wpBg.blur.isRecycled()) wpBg.blur.recycle();
                    }
                    return;
                }
                wallpaperShown = true;
                ExpressiveGlass.RefractionBackdrop oldWpBg = wallpaperBackdrop;
                wallpaperBackdrop = wpBg;
                if (!aerialVideoActive && wpBg != null) {
                    ExpressiveGlass.setPanelBackdrop(mediaCard, wpBg);
                    if (liquidClockView != null) liquidClockView.setCustomBackdrop(wpBg);
                }
                if (oldWpBg != null && oldWpBg != wpBg) {
                    ExpressiveGlass.runOnRefractThread(() -> {
                        if (!oldWpBg.sharp.isRecycled()) oldWpBg.sharp.recycle();
                        if (!oldWpBg.blur.isRecycled()) oldWpBg.blur.recycle();
                    });
                }
                // Soft vertical vignette over the dimmed mood wallpaper keeps typography ultra-crisp.
                GradientDrawable vignette = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x42000000, 0x1A000000, 0x6E000000});
                wallpaperView.setScaleType(usingImmichPhotos && bmp.getHeight() > bmp.getWidth()
                        ? ImageView.ScaleType.FIT_CENTER
                        : ImageView.ScaleType.CENTER_CROP);
                wallpaperView.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp),
                        new ColorDrawable(shade),
                        vignette}));
                setGlow(glowColor);
            });
        }, "clock-wallpaper").start();
    }

    @Nullable
    private static ExpressiveGlass.RefractionBackdrop buildWallpaperRefraction(Bitmap src, int shade, int screenW, int screenH) {
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
            Bitmap sharp = Bitmap.createBitmap(ExpressiveGlass.REFRACT_SHARP_W, ExpressiveGlass.REFRACT_SHARP_H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(sharp);
            c.drawBitmap(src, crop, new Rect(0, 0, sharp.getWidth(), sharp.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
            if (shade != 0) c.drawColor(shade);
            Bitmap blur = ExpressiveGlass.makeFastBlurred(sharp, ExpressiveGlass.REFRACT_BLUR_W, ExpressiveGlass.REFRACT_BLUR_H, true);
            if (blur == null) {
                sharp.recycle();
                return null;
            }
            return ExpressiveGlass.wrapBackdrop(sharp, blur, screenW, screenH);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveScene() {
        float outContentY = content.getY() - dp(10);
        content.animate().alpha(0f).y(outContentY).setDuration(750L)
                .setInterpolator(Motion.easeInExit()).withLayer().withEndAction(() -> {
            if (!started) return;
            placeScene(true);
            float targetContentY = content.getY();
            content.setY(targetContentY + dp(14));
            content.animate().alpha(1f).y(targetContentY).setDuration(1050L)
                    .setInterpolator(Motion.easeOutExpo()).withLayer().start();
            if (hasMedia && mediaCard.getVisibility() == View.VISIBLE) {
                float targetMediaY = mediaCard.getY();
                mediaCard.setY(targetMediaY + dp(14));
                mediaCard.animate().alpha(1f).y(targetMediaY).setDuration(1050L)
                        .setInterpolator(Motion.easeOutExpo())
                        .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                        .start();
            }
        }).start();
        if (hasMedia && mediaCard.getVisibility() == View.VISIBLE) {
            mediaCard.animate().alpha(0f).y(mediaCard.getY() - dp(10)).setDuration(750L)
                    .setInterpolator(Motion.easeInExit())
                    .setUpdateListener(a -> ExpressiveGlass.invalidateLiquidPanels(mediaCard))
                    .start();
        }
    }

    private void placeScene(boolean advanceSlot) {
        int parentW = rootView.getWidth();
        int parentH = rootView.getHeight();
        if (parentW <= 0 || parentH <= 0) return;

        int clockW = content.getWidth();
        int clockH = content.getHeight();
        int mediaW = mediaCard.getWidth();
        int mediaH = mediaCard.getHeight();
        boolean showMedia = hasMedia && mediaCard.getVisibility() == View.VISIBLE && mediaW > 0 && mediaH > 0;

        if (advanceSlot || layoutSlot < 0) {
            int nextSlot = random.nextInt(4);
            if (nextSlot == layoutSlot) nextSlot = (nextSlot + 1) % 4;
            layoutSlot = nextSlot;
            int jitterRange = dp(18);
            currentJitterX = random.nextInt(jitterRange * 2 + 1) - jitterRange;
            currentJitterY = random.nextInt(jitterRange * 2 + 1) - jitterRange;
            currentRandX = random.nextFloat();
            currentRandY = random.nextFloat();
        }

        int marginX = dp(56);
        int marginY = dp(48);
        placedOnce = true;

        float[] pos = computePlacement(layoutSlot, parentW, parentH, clockW, clockH,
                mediaW, mediaH, showMedia, marginX, marginY, currentJitterX, currentJitterY,
                currentRandX, currentRandY);
        content.setX(pos[0]);
        content.setY(pos[1]);
        placedContentY = pos[1];
        if (showMedia) {
            mediaCard.setX(pos[2]);
            mediaCard.setY(pos[3]);
            placedMediaX = pos[2];
            placedMediaY = pos[3];
            ExpressiveGlass.invalidateLiquidPanels(mediaCard);
            updateGlowCenter((pos[2] + mediaW / 2f) / parentW, (pos[3] + mediaH / 2f) / parentH);
        } else {
            updateGlowCenter((pos[0] + clockW / 2f) / parentW, (pos[1] + clockH / 2f) / parentH);
        }
        positionAerialCaption();
    }

    /**
     * Pure placement math returning {clockX, clockY, mediaX, mediaY}.
     * When music is active, rotates through 4 non-overlapping editorial quadrant compositions:
     *   0: Clock Top-Left,    Now Playing Bottom-Left
     *   1: Clock Top-Left,    Now Playing Bottom-Right
     *   2: Clock Top-Right,   Now Playing Bottom-Right
     *   3: Clock Bottom-Left, Now Playing Top-Right
     */
    static float[] computePlacement(int slot, int parentW, int parentH,
                                    int clockW, int clockH, int mediaW, int mediaH,
                                    boolean showMedia, int marginX, int marginY,
                                    int jitterX, int jitterY, float randX, float randY) {
        int maxClockX = Math.max(marginX, parentW - clockW - marginX);
        int maxClockY = Math.max(marginY, parentH - clockH - marginY);
        if (!showMedia) {
            float cx = marginX + (maxClockX - marginX) * (0.08f + 0.84f * randX);
            float cy = marginY + (maxClockY - marginY) * (0.08f + 0.84f * randY);
            return new float[]{cx, cy, 0f, 0f};
        }
        int maxMediaX = Math.max(marginX, parentW - mediaW - marginX);
        int maxMediaY = Math.max(marginY, parentH - mediaH - marginY);
        float cx, cy, mx, my;
        switch (slot & 3) {
            case 0: // Top-Left clock + Bottom-Left Now Playing
                cx = clamp(marginX + jitterX, marginX, maxClockX);
                cy = clamp(marginY + jitterY, marginY, maxClockY);
                mx = clamp(marginX + jitterX, marginX, maxMediaX);
                my = clamp(maxMediaY - jitterY, marginY, maxMediaY);
                break;
            case 1: // Top-Left clock + Bottom-Right Now Playing
                cx = clamp(marginX + jitterX, marginX, maxClockX);
                cy = clamp(marginY + jitterY, marginY, maxClockY);
                mx = clamp(maxMediaX - jitterX, marginX, maxMediaX);
                my = clamp(maxMediaY - jitterY, marginY, maxMediaY);
                break;
            case 2: // Top-Right clock + Bottom-Right Now Playing
                cx = clamp(maxClockX - jitterX, marginX, maxClockX);
                cy = clamp(marginY + jitterY, marginY, maxClockY);
                mx = clamp(maxMediaX - jitterX, marginX, maxMediaX);
                my = clamp(maxMediaY - jitterY, marginY, maxMediaY);
                break;
            default: // Bottom-Left clock + Top-Right Now Playing
                cx = clamp(marginX + jitterX, marginX, maxClockX);
                cy = clamp(maxClockY - jitterY, marginY, maxClockY);
                mx = clamp(maxMediaX - jitterX, marginX, maxMediaX);
                my = clamp(marginY + jitterY, marginY, maxMediaY);
                break;
        }
        return new float[]{cx, cy, mx, my};
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void shiftGlowTo(int targetColor) {
        if (glowOff) return;
        if (glowAnimator != null) {
            glowAnimator.cancel();
            glowAnimator = null;
        }
        int from = glowColor;
        if (from == targetColor) return;
        ValueAnimator anim = ValueAnimator.ofObject(GLOW_EVALUATOR, from, targetColor);
        anim.setDuration(GLOW_FADE_MS);
        anim.setInterpolator(Motion.smoothDecel());
        anim.addUpdateListener(a -> {
            if (started) setGlow((int) a.getAnimatedValue());
        });
        glowAnimator = anim;
        anim.start();
    }

    private void updateGlowCenter(float cx, float cy) {
        if (Math.abs(cx - glowCenterX) < 0.01f && Math.abs(cy - glowCenterY) < 0.01f) return;
        glowCenterX = cx;
        glowCenterY = cy;
        int w = glowMaskBitmap.getWidth();
        int h = glowMaskBitmap.getHeight();
        glowMaskBitmap.eraseColor(Color.TRANSPARENT);
        glowMaskPaint.setShader(new RadialGradient(
                cx * w, cy * h, Math.max(1f, w * 0.56f),
                new int[]{0x52FFFFFF, 0x1EFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        glowMaskCanvas.drawRect(0f, 0f, w, h, glowMaskPaint);
        ambientGlowView.invalidate();
    }

    private void setGlow(int color) {
        glowColor = color;
        if (glowOff) return;
        int c = color & 0x00FFFFFF;
        // Richer ambient tint when synced to album art; softer over dimmed home wallpaper when idle.
        int alpha = hasMedia ? 255 : (wallpaperShown ? 118 : 206);
        glowDrawable.setTint(0xFF000000 | c);
        glowDrawable.setAlpha(alpha);
        ambientGlowView.invalidate();
    }

    private Drawable loadAppIcon(String pkg) {
        Drawable cached = iconCache.get(pkg);
        if (cached != null) return cached;
        Drawable d = null;
        try {
            PackageManager pm = appCtx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            d = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d == null) d = appCtx.getDrawable(R.drawable.ic_apps);
        iconCache.put(pkg, d);
        return d;
    }

    private String loadAppName(String pkg) {
        String custom = appLabels.get(pkg);
        if (!TextUtils.isEmpty(custom)) return custom;
        String cached = nameCache.get(pkg);
        if (cached != null) return cached;
        String name = pkg;
        try {
            PackageManager pm = appCtx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = ai.loadLabel(pm);
            if (!TextUtils.isEmpty(l)) name = Cards.cleanLabel(l.toString(), pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        nameCache.put(pkg, name);
        return name;
    }

    private void applyRoundedOutline(View v, int radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    private static int blendColor(int a, int b, float ratioB) {
        float ratioA = 1f - ratioB;
        int r = Math.round(((a >> 16) & 0xFF) * ratioA + ((b >> 16) & 0xFF) * ratioB);
        int g = Math.round(((a >> 8) & 0xFF) * ratioA + ((b >> 8) & 0xFF) * ratioB);
        int bl = Math.round((a & 0xFF) * ratioA + (b & 0xFF) * ratioB);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
