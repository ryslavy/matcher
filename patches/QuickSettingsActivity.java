package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple tvOS style right-hand slide-in Control Center floating over Home or ANY running app/movie.
 * Triggered from the top-right Home control pill, remote Settings key, or long-press Home.
 * Features:
 *  - Inline compact Now Playing strip (when music/media is active)
 *  - 2x3 frosted-glass control tiles: Goodnight routine, Night Sound (Reduce Loud Sounds),
 *    Bluetooth/Audio output, Sleep Timer, Brightness dimmer, and Apple TV Aerial Videos toggle
 *  - Bottom quick pills: Screensaver, expandable Settings drawer, and Sleep TV (Hold OK for Restart).
 */
public final class QuickSettingsActivity extends Activity {

    private static final ComponentName BEDTIME_ACTIVITY =
            new ComponentName("io.github.luyzfernando08.sleeptimer", "io.github.luyzfernando08.sleeptimer.BedtimeActivity");
    private static final ComponentName SLEEP_QUICK =
            new ComponentName("io.github.luyzfernando08.sleeptimer", "io.github.luyzfernando08.sleeptimer.QuickActivity");
    private static final ComponentName BRIGHTNESS_QUICK =
            new ComponentName("io.github.maxhayim.brightness", "io.github.maxhayim.brightness.QuickActivity");
    private static final String BT_CONNECT_PKG = "com.gathin.btconnect";
    private static final String PREFS_NAME = "cc_prefs";
    private static final String KEY_TILE_ORDER = "tile_order";
    private static final int REORDER_ACCENT = 0xFF38BDF8;
    private static final String[] DEFAULT_TILE_ORDER = {
            "bedtime", "night_audio", "audio", "sleep_timer", "brightness", "aerial"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long LAUNCH_IN_FLIGHT_MS = 800L;
    @Nullable
    private static WeakReference<QuickSettingsActivity> activeRef;
    private static long launchPendingUntilMs;

    @Nullable
    private static QuickSettingsActivity active() {
        return activeRef != null ? activeRef.get() : null;
    }

    static boolean isLive() {
        QuickSettingsActivity a = active();
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static boolean isLaunchInFlight() {
        return SystemClock.uptimeMillis() < launchPendingUntilMs;
    }

    static void refreshBackdropIfOpen() {
        QuickSettingsActivity a = active();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            if (a.overHome) {
                ExpressiveGlass.invalidateLiquidPanels(a.rootLayout);
            } else {
                a.applyCapturedBackdrop();
            }
        }
    }

    /** Closes Control Center immediately during a seamless handoff to OverlayActivity / Settings without dropping blur. */
    @SuppressWarnings("deprecation")
    static void finishForHandoff() {
        QuickSettingsActivity live = active();
        if (live == null || live.isFinishing() || live.isDestroyed()) return;
        live.handingOff = true;
        live.closing = true;
        if (active() == live) activeRef = null;
        live.finish();
        live.overridePendingTransition(0, 0);
    }

    static void launch(Context ctx) {
        Context hostCtx = ctx instanceof Activity ? ctx : ctx.getApplicationContext();
        Runnable run = () -> launchOnMain(hostCtx);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            MAIN.post(run);
        }
    }

    @SuppressWarnings("deprecation")
    private static void launchOnMain(Context ctx) {
        QuickSettingsActivity live = active();
        if (live != null && !live.isFinishing() && !live.isDestroyed()) {
            if (!live.closing) live.slideOutAndFinish();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < launchPendingUntilMs) return;
        launchPendingUntilMs = now + LAUNCH_IN_FLIGHT_MS;
        final boolean handoff = OverlayActivity.isLive() || LauncherSettingsActivity.isLive();
        final boolean launchOverHome = MainActivity.isHomeVisible();
        Runnable startActivity = () -> {
            if (launchOverHome) MainActivity.prepareControlCenterBackdropBlur();
            else MainActivity.setControlCenterOpen(true);
            Intent intent = new Intent(ctx, QuickSettingsActivity.class)
                    .putExtra(ExpressiveGlass.EXTRA_OVER_HOME, launchOverHome)
                    .putExtra(ExpressiveGlass.EXTRA_SEAMLESS_HANDOFF, handoff)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
                if (ctx instanceof Activity) {
                    ((Activity) ctx).overridePendingTransition(0, 0);
                }
            } catch (ActivityNotFoundException | SecurityException ignored) {
                launchPendingUntilMs = 0L;
                if (active() == null) MainActivity.setControlCenterOpen(false);
            }
        };
        if (launchOverHome) {
            ExpressiveGlass.clearOverlayBackdrop();
            MainActivity.captureLiveHomeBackdropThen(startActivity);
        } else {
            HomeKeyService.captureOverlayBackdrop(startActivity);
        }
    }

    private float density;
    private boolean rtl;
    private boolean reducedMotion;
    private boolean overHome;
    private boolean seamlessHandoff;
    private boolean handingOff;
    private int backdropGenOnCreate;
    private FrameLayout rootLayout;
    private FrameLayout panelStage;
    @Nullable
    private FocusRingController focusRing;
    @Nullable
    private FrameLayout backdropView;
    private LinearLayout panel;
    private ScrollView scroll;

    // Inline Now Playing strip
    private LinearLayout mediaBox;
    private ImageView mediaArt;
    private TextView mediaTitle;
    private TextView mediaSub;
    private ImageView mediaPrev;
    private ImageView mediaPlayPause;
    private ImageView mediaNext;
    private MediaSessionTracker mediaTracker;
    @Nullable
    private MediaController activeMedia;

    // Control Tiles
    private LinearLayout tileGridBox;
    private TextView reorderBanner;
    private final Map<String, TileViews> tilesById = new LinkedHashMap<>();
    private final List<String> tileOrder = new ArrayList<>();
    private boolean reorderingTiles;
    private int carriedTileIndex = -1;
    private TileViews tileBedtime;
    private TileViews tileNightAudio;
    private TileViews tileAudio;
    private TileViews tileSleepTimer;
    private TileViews tileBrightness;
    private TileViews tileAerial;

    // Expandable classic settings rows (lazily populated on first expand to skip PackageManager IPCs in onCreate)
    private LinearLayout settingsDrawer;
    private boolean settingsDrawerBuilt;
    private boolean settingsExpanded;

    // Optional Home Assistant smart-home controls (up to 8 safe entities, live 30s polling)
    @Nullable private LinearLayout haSection;
    @Nullable private LinearLayout haGridBox;
    private final List<HomeAssistantClient.Control> haControls = new ArrayList<>();
    private final List<TileViews> haTiles = new ArrayList<>();
    private final Runnable haRefreshTick = new Runnable() {
        @Override
        public void run() {
            if (closing || isFinishing() || isDestroyed()) return;
            HomeAssistantClient.refreshStates(QuickSettingsActivity.this, QuickSettingsActivity.this::rebuildHaTiles);
            MAIN.postDelayed(this, HomeAssistantClient.REFRESH_INTERVAL_MS);
        }
    };

    private boolean closing;
    private boolean initialLayoutSettled;
    private boolean mediaTrackerActive;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        activeRef = new WeakReference<>(this);
        launchPendingUntilMs = 0L;
        density = getResources().getDisplayMetrics().density;
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Config cfg = Config.load(this);
        reducedMotion = cfg.reducedMotion;

        // Android 17 frosted blur: over Home, MainActivity's cached AGSL GPU blur runs underneath; over external
        // apps, HomeKeyService captures a live GPU HardwareBuffer backdrop blurred with our exact AGSL shader.
        Intent startIntent = getIntent();
        overHome = startIntent != null && startIntent.hasExtra(ExpressiveGlass.EXTRA_OVER_HOME)
                ? startIntent.getBooleanExtra(ExpressiveGlass.EXTRA_OVER_HOME, true)
                : MainActivity.isHomeVisible();
        seamlessHandoff = startIntent != null
                && startIntent.getBooleanExtra(ExpressiveGlass.EXTRA_SEAMLESS_HANDOFF, false);
        backdropGenOnCreate = ExpressiveGlass.overlayBackdropGeneration();
        boolean hasLiveBackdrop = !overHome && ExpressiveGlass.hasOverlayBackdrop();
        if (overHome) MainActivity.prepareControlCenterBackdropBlur();
        else MainActivity.setControlCenterOpen(true);
        if (OverlayActivity.isLive()) {
            OverlayActivity.finishForHandoff();
        }
        if (!overHome && !hasLiveBackdrop) {
            ExpressiveGlass.applyWindowBlur(getWindow(), density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, 0.36f);
        } else {
            // AGSL blur veil owns 100% of darkening with smooth alpha animation — zero double-dim or Frame-0 snap.
            ExpressiveGlass.setWindowDimOnly(getWindow(), 0f);
        }

        rootLayout = new FrameLayout(this);
        rootLayout.setOnClickListener(v -> slideOutAndFinish());
        if (hasLiveBackdrop) {
            applyCapturedBackdrop(!seamlessHandoff, seamlessHandoff);
        }

        int panelW = dp(396);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, !overHome && !hasLiveBackdrop, false);
        panel.setPadding(dp(18), dp(14), dp(18), dp(12));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setOnClickListener(v -> { /* consume clicks inside panel */ });

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        FocusRingController.clipScrollContainers(scroll);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(6), dp(4), dp(6), dp(4));

        buildHeader(body, cfg.clockFormat, cfg.appLibrary);
        buildMediaStrip(body);
        buildTileGrid(body);
        buildHomeAssistantSection(body);
        buildBottomRow(body);
        buildSettingsDrawer(body);

        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        ExpressiveGlass.applyAdaptiveContentTheme(panel);

        int ringPad = FocusRingController.wrapInsetPx(density);
        panelStage = new FrameLayout(this);
        panelStage.setClipChildren(false);
        panelStage.setClipToPadding(false);
        FrameLayout.LayoutParams innerPanelLp = new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        innerPanelLp.setMargins(ringPad, ringPad, ringPad, ringPad);
        panelStage.addView(panel, innerPanelLp);
        focusRing = FocusRingController.attach(panelStage, panel, density, !reducedMotion, reducedMotion);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                panelW + 2 * ringPad, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT));
        int edge = Math.max(0, dp(24) - ringPad);
        int vert = Math.max(0, dp(12) - ringPad);
        panelLp.setMargins(edge, vert, edge, vert);
        rootLayout.addView(panelStage, panelLp);
        setContentView(rootLayout);
        PointerSupport.install(panel);
        UiClickSound.attachFocusSound(panel);

        mediaTracker = new MediaSessionTracker(this, this::onMediaUpdated);
        // Bind active media BEFORE Frame 0 layout so mediaBox already has its final height (zero mid-slide relayout!).
        mediaTracker.start();
        mediaTrackerActive = true;

        // Smooth Apple tvOS right-edge glide-in started strictly on Frame 1 (postOnAnimation) in lockstep with blur.
        final float startX = rtl ? -dp(380) : dp(380);
        panelStage.setAlpha(0f);
        if (!reducedMotion) {
            panelStage.setTranslationX(startX);
            panelStage.setScaleX(0.96f);
            panelStage.setScaleY(0.97f);
        }
        panelStage.post(() -> panelStage.postOnAnimation(() -> {
            initialLayoutSettled = true;
            if (closing || isFinishing() || isDestroyed()) return;
            if (overHome) {
                MainActivity.startBackdropBlurFadeInIfOpen();
            } else {
                ExpressiveGlass.startOverlayBackdropFadeIn(backdropView, reducedMotion);
            }
            if (reducedMotion) {
                panelStage.animate().alpha(1f).setDuration(Motion.REDUCED_MS)
                        .setInterpolator(Motion.smoothDecel()).start();
            } else {
                Motion.glidePanel(panelStage,
                        startX, 0f,
                        0f, 0f,
                        0.96f, 1f,
                        0.97f, 1f,
                        true, 360L, Motion.easeOutExpo(), null);
            }
        }));

        // Snap initial focus to first tile in user's order so it never fights the panel slide-in.
        TileViews initial = firstTile();
        initial.root.post(() -> {
            initial.root.requestFocus();
            initial.root.animate().cancel();
            initial.root.setScaleX(1.055f);
            initial.root.setScaleY(1.055f);
            initial.root.setTranslationY(reducedMotion ? 0f : -2f * density);
            initial.icon.animate().cancel();
            initial.icon.setScaleX(1.12f);
            initial.icon.setScaleY(1.12f);
            if (focusRing != null) focusRing.moveTo(initial.root, false);
        });
    }

    @SuppressWarnings("deprecation")
    private void buildHeader(LinearLayout parent, String clockFormat, boolean showLibrary) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView kicker = makeText(getString(R.string.cc_title), 10.5f, 0x99FFFFFF);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.14f);
        left.addView(kicker, new LinearLayout.LayoutParams(-2, -2));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(32f);
        clock.setTypeface(UiFont.get(this, R.string.font_light));
        clock.setFontFeatureSettings("tnum");
        clock.setIncludeFontPadding(false);
        ClockFormat.apply(clock, clockFormat);
        ExpressiveGlass.applyLuminousTitle(clock, density, false);
        LinearLayout.LayoutParams clkLp = new LinearLayout.LayoutParams(-2, -2);
        clkLp.topMargin = dp(2);
        left.addView(clock, clkLp);

        TextClock date = new TextClock(this);
        date.setFormat12Hour(getString(R.string.date_format));
        date.setFormat24Hour(getString(R.string.date_format));
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(12f);
        date.setTypeface(UiFont.get(this, R.string.font_medium));
        date.setAllCaps(true);
        date.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
        dLp.topMargin = dp(2);
        left.addView(date, dLp);

        header.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        // Subtle live status badges + quick Recents overlay shortcut on top-right.
        LinearLayout rightGroup = new LinearLayout(this);
        rightGroup.setOrientation(LinearLayout.HORIZONTAL);
        rightGroup.setGravity(Gravity.CENTER_VERTICAL);
        rightGroup.setClipChildren(false);
        rightGroup.setClipToPadding(false);
        if (AmbientWeather.isVpnActive(this)) {
            boolean light = ExpressiveGlass.isLightGlass();
            TextView vpn = makeText(getString(R.string.ambient_vpn_badge), 11f, light ? 0xFF15803D : 0xFF8DE6A8);
            vpn.setPadding(dp(9), dp(4), dp(9), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(light ? 0x2415803D : 0x281B4D2E);
            bg.setCornerRadius(dp(11));
            bg.setStroke(Math.max(1, dp(1)), light ? 0x8815803D : 0x6634C759);
            vpn.setBackground(bg);
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-2, -2);
            vLp.rightMargin = dp(8);
            rightGroup.addView(vpn, vLp);
        }
        if (showLibrary) {
            ImageView btnLibrary = makeMiniMediaBtn(R.drawable.ic_apps_pill);
            btnLibrary.setContentDescription(getString(R.string.app_library));
            btnLibrary.setOnClickListener(v -> {
                handingOff = true;
                MainActivity.setOverlayOpen(true);
                OverlayActivity.launch(this, OverlayActivity.MODE_LIBRARY, true);
            });
            LinearLayout.LayoutParams libLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            libLp.rightMargin = dp(8);
            rightGroup.addView(btnLibrary, libLp);
        }
        ImageView btnDiscover = makeMiniMediaBtn(R.drawable.ic_discover);
        btnDiscover.setContentDescription(getString(R.string.discover_title));
        btnDiscover.setOnClickListener(v -> {
            handingOff = true;
            MainActivity.setOverlayOpen(true);
            OverlayActivity.launch(this, OverlayActivity.MODE_DISCOVER, true);
        });
        LinearLayout.LayoutParams discLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        discLp.rightMargin = dp(8);
        rightGroup.addView(btnDiscover, discLp);
        ImageView btnRecents = makeMiniMediaBtn(R.drawable.ic_recents);
        btnRecents.setContentDescription(getString(R.string.recents_title));
        btnRecents.setOnClickListener(v -> {
            handingOff = true;
            MainActivity.setOverlayOpen(true);
            OverlayActivity.launch(this, OverlayActivity.MODE_RECENTS, true);
        });
        rightGroup.addView(btnRecents, new LinearLayout.LayoutParams(dp(36), dp(36)));
        header.setClipChildren(false);
        header.setClipToPadding(false);
        header.addView(rightGroup, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.bottomMargin = dp(10);
        parent.addView(header, hLp);
    }

    private void buildMediaStrip(LinearLayout parent) {
        mediaBox = new LinearLayout(this);
        mediaBox.setOrientation(LinearLayout.HORIZONTAL);
        mediaBox.setGravity(Gravity.CENTER_VERTICAL);
        mediaBox.setPadding(dp(11), dp(10), dp(10), dp(10));
        mediaBox.setClipChildren(false);
        mediaBox.setClipToPadding(false);
        mediaBox.setBackground(ExpressiveGlass.makeAdaptiveCardBg(density, 18f, false));
        mediaBox.setVisibility(View.GONE);

        mediaArt = new ImageView(this);
        ExpressiveGlass.markPreserveImage(mediaArt);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        mediaArt.setClipToOutline(true);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        artLp.rightMargin = dp(10);
        mediaBox.addView(mediaArt, artLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        mediaTitle = makeText("", 13.5f, Color.WHITE);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(mediaTitle, new LinearLayout.LayoutParams(-1, -2));

        mediaSub = makeText("", 11.5f, 0xA6FFFFFF);
        mediaSub.setSingleLine(true);
        mediaSub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(2);
        textCol.addView(mediaSub, subLp);
        mediaBox.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        mediaPrev = makeMiniMediaBtn(R.drawable.ic_media_prev);
        mediaPlayPause = makeMiniMediaBtn(R.drawable.ic_media_play);
        mediaNext = makeMiniMediaBtn(R.drawable.ic_media_next);

        mediaPrev.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.12f : 1f, reducedMotion);
            if (activeMedia != null) MediaSessionTracker.skipPrevious(activeMedia);
        });
        mediaPlayPause.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.12f : 1f, reducedMotion);
            if (activeMedia != null && mediaTracker != null) mediaTracker.togglePlayPause(activeMedia);
        });
        mediaNext.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.12f : 1f, reducedMotion);
            if (activeMedia != null) MediaSessionTracker.skipNext(activeMedia);
        });

        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        bLp.leftMargin = dp(4);
        mediaBox.addView(mediaPrev, bLp);
        mediaBox.addView(mediaPlayPause, new LinearLayout.LayoutParams(bLp));
        mediaBox.addView(mediaNext, new LinearLayout.LayoutParams(bLp));

        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-1, -2);
        mLp.bottomMargin = dp(10);
        parent.addView(mediaBox, mLp);
    }

    private void buildTileGrid(LinearLayout parent) {
        tileBedtime = createTile(R.drawable.ic_cc_bedtime, getString(R.string.cc_bedtime),
                getString(R.string.cc_bedtime_sub), 0xFF818CF8, 0x454338CA);
        tileNightAudio = createTile(R.drawable.ic_cc_night_audio, getString(R.string.cc_night_audio),
                getString(R.string.cc_night_audio_off), 0xFF4ADE80, 0x4015803D);
        tileAudio = createTile(R.drawable.ic_ambient_headphones, getString(R.string.cc_audio),
                getString(R.string.cc_audio_tv), 0xFF38BDF8, 0x3D0369A1);
        tileSleepTimer = createTile(R.drawable.ic_cc_sleep_timer, getString(R.string.cc_sleep_timer),
                getString(R.string.cc_sleep_timer_sub), 0xFFFBBF24, 0x3DB45309);
        tileBrightness = createTile(R.drawable.ic_cc_brightness, getString(R.string.cc_brightness),
                getString(R.string.cc_brightness_sub), 0xFFFDE047, 0x3DA16207);
        tileAerial = createTile(R.drawable.ic_cc_aerial, getString(R.string.cc_aerial),
                getString(R.string.cc_aerial_on), 0xFF38BDF8, 0x400284C7);

        tilesById.clear();
        tilesById.put("bedtime", tileBedtime);
        tilesById.put("night_audio", tileNightAudio);
        tilesById.put("audio", tileAudio);
        tilesById.put("sleep_timer", tileSleepTimer);
        tilesById.put("brightness", tileBrightness);
        tilesById.put("aerial", tileAerial);
        loadTileOrder();

        // Tile 1: Goodnight one-click routine (30m Sleep Timer + Night Dimmer + Night Sound!)
        wireTileClicks("bedtime", tileBedtime, v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.055f : 1f, reducedMotion);
            boolean next = !NightAudioController.isBedtimeActive(this);
            NightAudioController.setBedtimeActive(this, next);
            NightAudioController.setEnabled(this, next);
            refreshTileStates();
            Intent bedtimeIntent = new Intent().setComponent(BEDTIME_ACTIVITY)
                    .putExtra("bedtime", next ? "on" : "off");
            launchAndClose(bedtimeIntent);
        });

        // Tile 2: Apple TV "Reduce Loud Sounds" / Night Sound live toggle
        wireTileClicks("night_audio", tileNightAudio, v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.055f : 1f, reducedMotion);
            boolean want = !NightAudioController.isEnabled(this);
            boolean ok = NightAudioController.setEnabled(this, want);
            if (!ok && want) {
                Toast.makeText(this, R.string.cc_night_audio_unavailable, Toast.LENGTH_SHORT).show();
            }
            refreshTileStates();
        });

        // Tile 3: Audio / Bluetooth output switcher
        wireTileClicks("audio", tileAudio, v -> {
            Intent bt = getPackageManager().getLeanbackLaunchIntentForPackage(BT_CONNECT_PKG);
            if (bt == null) bt = getPackageManager().getLaunchIntentForPackage(BT_CONNECT_PKG);
            if (bt == null) bt = SystemSettings.intent(this, Settings.ACTION_BLUETOOTH_SETTINGS);
            launchAndClose(bt);
        });

        // Tile 4: Sleep Timer quick bar over current movie
        wireTileClicks("sleep_timer", tileSleepTimer, v -> launchAndClose(new Intent().setComponent(SLEEP_QUICK)));

        // Tile 5: Night Brightness quick dimmer bar over current movie
        wireTileClicks("brightness", tileBrightness, v -> launchAndClose(new Intent().setComponent(BRIGHTNESS_QUICK)));

        // Tile 6: Toggle Apple TV 1080p Aerial Video Screensaver
        wireTileClicks("aerial", tileAerial, v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.055f : 1f, reducedMotion);
            boolean next = !AerialCatalog.isEnabled(this);
            AerialCatalog.setEnabled(this, next);
            refreshTileStates();
        });

        reorderBanner = makeText(getString(R.string.reorder_hint), 11.5f, 0xFF93D1FF);
        reorderBanner.setMaxLines(2);
        reorderBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, -2);
        rbLp.bottomMargin = dp(8);
        parent.addView(reorderBanner, rbLp);

        tileGridBox = new LinearLayout(this);
        tileGridBox.setOrientation(LinearLayout.VERTICAL);
        tileGridBox.setClipChildren(false);
        tileGridBox.setClipToPadding(false);
        parent.addView(tileGridBox, new LinearLayout.LayoutParams(-1, -2));
        rebuildTileGrid();
    }

    private void wireTileClicks(String id, TileViews tile, View.OnClickListener normalClick) {
        tile.root.setOnClickListener(v -> {
            if (reorderingTiles) {
                int idx = tileOrder.indexOf(id);
                if (carriedTileIndex < 0) pickUpTile(idx);
                else dropTile();
                return;
            }
            normalClick.onClick(v);
        });
        tile.root.setOnLongClickListener(v -> {
            if (!reorderingTiles) {
                startTileReorder(id, true);
            } else {
                int idx = tileOrder.indexOf(id);
                if (carriedTileIndex < 0) pickUpTile(idx);
                else dropTile();
            }
            return true;
        });
    }

    private TileViews firstTile() {
        if (!tileOrder.isEmpty()) {
            TileViews first = tilesById.get(tileOrder.get(0));
            if (first != null) return first;
        }
        return tileBedtime;
    }

    private void loadTileOrder() {
        tileOrder.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_TILE_ORDER, "");
        if (saved != null && !saved.isEmpty()) {
            for (String part : saved.split(",")) {
                String id = part.trim();
                if (tilesById.containsKey(id) && !tileOrder.contains(id)) {
                    tileOrder.add(id);
                }
            }
        }
        for (String id : DEFAULT_TILE_ORDER) {
            if (tilesById.containsKey(id) && !tileOrder.contains(id)) {
                tileOrder.add(id);
            }
        }
    }

    private void saveTileOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tileOrder.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(tileOrder.get(i));
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_TILE_ORDER, sb.toString()).apply();
    }

    private void rebuildTileGrid() {
        if (tileGridBox == null) return;
        tileGridBox.removeAllViews();
        for (TileViews tv : tilesById.values()) {
            if (tv.root.getParent() instanceof ViewGroup) {
                ((ViewGroup) tv.root.getParent()).removeView(tv.root);
            }
        }
        for (int i = 0; i < tileOrder.size(); i += 2) {
            TileViews left = tilesById.get(tileOrder.get(i));
            TileViews right = (i + 1 < tileOrder.size()) ? tilesById.get(tileOrder.get(i + 1)) : null;
            if (left != null && right != null) {
                addTileRow(tileGridBox, left.root, right.root);
            } else if (left != null) {
                View spacer = new View(this);
                spacer.setVisibility(View.INVISIBLE);
                spacer.setFocusable(false);
                addTileRow(tileGridBox, left.root, spacer);
            }
        }
    }

    private void startTileReorder(String focusId, boolean pickUpImmediately) {
        if (settingsExpanded) collapseSettingsDrawer(false);
        reorderingTiles = true;
        int idx = Math.max(0, tileOrder.indexOf(focusId));
        carriedTileIndex = pickUpImmediately ? idx : -1;
        if (reorderBanner != null) reorderBanner.setVisibility(View.VISIBLE);
        refreshTileStates();
        TileViews target = tilesById.get(tileOrder.get(idx));
        if (target != null) {
            target.root.requestFocus();
            target.root.post(() -> {
                if (focusRing != null && target.root.isFocused()) {
                    focusRing.moveTo(target.root, true);
                }
            });
        }
    }

    private void pickUpTile(int idx) {
        if (!reorderingTiles || idx < 0 || idx >= tileOrder.size()) return;
        carriedTileIndex = idx;
        refreshTileStates();
    }

    private void dropTile() {
        if (carriedTileIndex < 0) return;
        carriedTileIndex = -1;
        saveTileOrder();
        refreshTileStates();
    }

    private void endTileReorder(boolean save) {
        if (!reorderingTiles) return;
        reorderingTiles = false;
        carriedTileIndex = -1;
        if (save) saveTileOrder();
        if (reorderBanner != null) reorderBanner.setVisibility(View.GONE);
        refreshTileStates();
    }

    private void moveCarriedTile(int keyCode) {
        if (!reorderingTiles || carriedTileIndex < 0 || carriedTileIndex >= tileOrder.size()) return;
        int from = carriedTileIndex;
        int col = from % 2;
        int row = from / 2;
        boolean backHoriz = rtl ? keyCode == KeyEvent.KEYCODE_DPAD_RIGHT : keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean fwdHoriz = rtl ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        int to = from;
        if (backHoriz && col > 0) to = from - 1;
        else if (fwdHoriz && col < 1 && from + 1 < tileOrder.size()) to = from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && row > 0) to = from - 2;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && from + 2 < tileOrder.size()) to = from + 2;
        if (to == from) return;

        String movedId = tileOrder.remove(from);
        tileOrder.add(to, movedId);
        carriedTileIndex = to;
        saveTileOrder();

        TileViews carried = tilesById.get(movedId);
        if (carried == null) return;
        if (focusRing != null) focusRing.setSuppressed(true);
        rebuildTileGrid();
        refreshTileStates();
        carried.root.requestFocus();
        carried.root.animate().cancel();
        carried.root.setScaleX(1.055f);
        carried.root.setScaleY(1.055f);
        carried.root.setTranslationY(reducedMotion ? 0f : -2f * density);
        carried.icon.animate().cancel();
        carried.icon.setScaleX(1.12f);
        carried.icon.setScaleY(1.12f);
        reanimateCarriedRing(carried.root, 6);
    }

    private void reanimateCarriedRing(View target, int remainingFrames) {
        if (closing || tileGridBox == null) {
            if (focusRing != null) focusRing.setSuppressed(false);
            return;
        }
        tileGridBox.postOnAnimation(() -> {
            if (closing || tileGridBox == null) {
                if (focusRing != null) focusRing.setSuppressed(false);
                return;
            }
            if (tileGridBox.isLayoutRequested() && remainingFrames > 0) {
                reanimateCarriedRing(target, remainingFrames - 1);
                return;
            }
            if (!target.hasFocus()) target.requestFocus();
            if (focusRing != null) {
                focusRing.setSuppressed(false);
                focusRing.moveTo(target, true);
            }
        });
    }

    private void buildHomeAssistantSection(LinearLayout parent) {
        if (!HomeAssistantClient.isReady(this)) return;
        haSection = new LinearLayout(this);
        haSection.setOrientation(LinearLayout.VERTICAL);
        haSection.setClipChildren(false);
        haSection.setClipToPadding(false);

        TextView heading = makeText(getString(R.string.ha_section_title), 11f, 0x99FFFFFF);
        heading.setAllCaps(true);
        heading.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-2, -2);
        hLp.topMargin = dp(4);
        hLp.bottomMargin = dp(6);
        haSection.addView(heading, hLp);

        haGridBox = new LinearLayout(this);
        haGridBox.setOrientation(LinearLayout.VERTICAL);
        haGridBox.setClipChildren(false);
        haGridBox.setClipToPadding(false);
        haSection.addView(haGridBox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-1, -2);
        secLp.bottomMargin = dp(8);
        parent.addView(haSection, secLp);

        rebuildHaTiles();
        MAIN.post(haRefreshTick);
    }

    private void rebuildHaTiles() {
        if (haGridBox == null || haSection == null || closing || isFinishing() || isDestroyed()) return;
        List<HomeAssistantClient.Control> all = HomeAssistantClient.controls(this);
        List<HomeAssistantClient.Control> visible = new ArrayList<>();
        for (HomeAssistantClient.Control c : all) {
            if (c.isOffered()) visible.add(c);
        }
        haControls.clear();
        haControls.addAll(visible);
        haTiles.clear();
        haGridBox.removeAllViews();
        if (visible.isEmpty()) {
            haSection.setVisibility(View.GONE);
            return;
        }
        haSection.setVisibility(View.VISIBLE);
        for (HomeAssistantClient.Control ctl : visible) {
            int iconRes = ctl.isCamera() ? R.drawable.ic_ha_camera : R.drawable.ic_ha_home;
            TileViews tv = createTile(iconRes, ctl.name, formatHaState(ctl),
                    0xFF60A5FA, 0x462563EB);
            tv.setActive(ctl.isActive());
            tv.root.setAlpha(ctl.isUnavailable() ? 0.50f : 1f);
            tv.root.setOnClickListener(v -> {
                Motion.pulseClick(v, v.isFocused() ? 1.055f : 1f, reducedMotion);
                if (ctl.isCamera()) {
                    handingOff = true;
                    OverlayActivity.launchCamera(this, ctl.entityId);
                    return;
                }
                HomeAssistantClient.activate(this, ctl, ok -> {
                    if (!closing && !isFinishing() && !isDestroyed()) {
                        tv.sub.setText(formatHaState(ctl));
                        tv.setActive(ctl.isActive());
                        if (!ok) Toast.makeText(this, R.string.ha_action_failed, Toast.LENGTH_SHORT).show();
                    }
                });
                tv.sub.setText(formatHaState(ctl));
                tv.setActive(ctl.isActive());
            });
            haTiles.add(tv);
        }
        for (int i = 0; i < haTiles.size(); i += 2) {
            View left = haTiles.get(i).root;
            View right = (i + 1 < haTiles.size()) ? haTiles.get(i + 1).root : makeSpacer();
            addTileRow(haGridBox, left, right);
        }
        ExpressiveGlass.applyAdaptiveContentTheme(haGridBox);
    }

    private View makeSpacer() {
        View spacer = new View(this);
        spacer.setVisibility(View.INVISIBLE);
        spacer.setFocusable(false);
        return spacer;
    }

    private String formatHaState(HomeAssistantClient.Control ctl) {
        if (ctl.isUnavailable()) return getString(R.string.ha_state_unavailable);
        if (ctl.isCamera()) return getString(R.string.ha_state_camera);
        if (ctl.state.isEmpty()) return "…";
        if ("on".equals(ctl.state)) return getString(R.string.cc_on);
        if ("off".equals(ctl.state)) return getString(R.string.cc_off);
        if ("open".equals(ctl.state)) return getString(R.string.ha_state_open);
        if ("closed".equals(ctl.state)) return getString(R.string.ha_state_closed);
        return ctl.state;
    }

    private void buildBottomRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        LinearLayout btnSaver = makeBottomPill(R.drawable.ic_cc_screensaver, getString(R.string.cc_screensaver));
        LinearLayout btnSettings = makeBottomPill(R.drawable.ic_settings, getString(R.string.cc_settings));
        LinearLayout btnPower = makeBottomPill(R.drawable.ic_cc_power, getString(R.string.cc_sleep_tv));

        btnSaver.setOnClickListener(v -> launchScreensaver());
        btnSettings.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.06f : 1f, reducedMotion);
            toggleSettingsDrawer();
        });
        btnPower.setOnClickListener(v -> {
            if (HomeKeyService.sleepTv()) {
                finish();
            } else if (HomeKeyService.canOpenPowerMenu()) {
                HomeKeyService.openPowerMenu();
                finish();
            } else {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        btnPower.setOnLongClickListener(v -> {
            if (HomeKeyService.canOpenPowerMenu()) {
                HomeKeyService.openPowerMenu();
                finish();
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        p1.rightMargin = dp(8);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        p2.rightMargin = dp(8);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, dp(42), 1f);

        row.addView(btnSaver, p1);
        row.addView(btnSettings, p2);
        row.addView(btnPower, p3);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.topMargin = dp(4);
        parent.addView(row, rLp);
    }

    private void buildSettingsDrawer(LinearLayout parent) {
        settingsDrawer = new LinearLayout(this);
        settingsDrawer.setOrientation(LinearLayout.VERTICAL);
        settingsDrawer.setVisibility(View.GONE);
        settingsDrawer.setPadding(dp(4), dp(10), dp(6), dp(4));
        settingsDrawer.setClipChildren(false);
        settingsDrawer.setClipToPadding(false);
        parent.addView(settingsDrawer, new LinearLayout.LayoutParams(-1, -2));
    }

    private void ensureSettingsDrawerBuilt() {
        if (settingsDrawerBuilt || settingsDrawer == null) return;
        settingsDrawerBuilt = true;
        addSettingsRow(settingsDrawer, R.drawable.ic_settings, R.string.tv_settings, 0,
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addSettingsRow(settingsDrawer, R.drawable.ic_picture, R.string.quick_picture, 0,
                SystemSettings.displayIntent(this));
        addSettingsRow(settingsDrawer, R.drawable.ic_wifi, R.string.quick_network, 0,
                SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addSettingsRow(settingsDrawer, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        addSettingsActionRow(settingsDrawer, R.drawable.ic_home_settings, R.string.launcher_settings, () -> {
            handingOff = true;
            LauncherSettingsActivity.launch(this, overHome, true);
        });
        addSettingsActionRow(settingsDrawer, R.drawable.ic_swap_vert, R.string.cc_reorder_tiles,
                () -> startTileReorder(tileOrder.isEmpty() ? "bedtime" : tileOrder.get(0), false));
    }

    private void toggleSettingsDrawer() {
        settingsExpanded = !settingsExpanded;
        settingsDrawer.animate().cancel();
        if (settingsExpanded) {
            ensureSettingsDrawerBuilt();
            settingsDrawer.setVisibility(View.VISIBLE);
            settingsDrawer.setTranslationY(0f);
            if (!reducedMotion) {
                settingsDrawer.setAlpha(0f);
                settingsDrawer.animate().alpha(1f)
                        .setDuration(190L).setInterpolator(Motion.easeOutExpo()).start();
                Motion.staggerChildren(settingsDrawer, density, false, 6);
            } else {
                settingsDrawer.setAlpha(1f);
            }
            if (settingsDrawer.getChildCount() > 0) {
                settingsDrawer.post(() -> {
                    ExpressiveGlass.invalidateLiquidPanels(panel);
                    if (settingsExpanded && settingsDrawer.getChildCount() > 0) {
                        settingsDrawer.getChildAt(0).requestFocus();
                        scroll.smoothScrollTo(0, settingsDrawer.getTop());
                    }
                });
            }
        } else {
            collapseSettingsDrawer(false);
        }
    }

    private void collapseSettingsDrawer(boolean refocusBedtime) {
        settingsExpanded = false;
        settingsDrawer.animate().cancel();
        if (reducedMotion || settingsDrawer.getVisibility() != View.VISIBLE) {
            settingsDrawer.setVisibility(View.GONE);
            panel.post(() -> ExpressiveGlass.invalidateLiquidPanels(panel));
            if (refocusBedtime) firstTile().root.requestFocus();
            return;
        }
        settingsDrawer.animate().alpha(0f).translationY(-dp(6))
                .setDuration(130L).setInterpolator(Motion.easeInExit())
                .withEndAction(() -> {
                    if (!settingsExpanded) {
                        settingsDrawer.setVisibility(View.GONE);
                        settingsDrawer.setAlpha(1f);
                        settingsDrawer.setTranslationY(0f);
                        panel.post(() -> ExpressiveGlass.invalidateLiquidPanels(panel));
                        if (refocusBedtime) firstTile().root.requestFocus();
                    }
                }).start();
    }

    private void refreshTileStates() {
        boolean bedtime = NightAudioController.isBedtimeActive(this);
        tileBedtime.setActive(bedtime);
        tileBedtime.sub.setText(bedtime ? R.string.cc_bedtime_on : R.string.cc_bedtime_sub);

        boolean nightAudio = NightAudioController.isEnabled(this);
        tileNightAudio.setActive(nightAudio);
        tileNightAudio.sub.setText(nightAudio ? R.string.cc_night_audio_on : R.string.cc_night_audio_off);

        String audioName = AmbientWeather.activeAudioDeviceName(this);
        boolean btConnected = !TextUtils.isEmpty(audioName);
        tileAudio.setActive(btConnected);
        tileAudio.sub.setText(btConnected ? audioName : getString(R.string.cc_audio_tv));

        boolean aerial = AerialCatalog.isEnabled(this);
        tileAerial.setActive(aerial);
        tileAerial.sub.setText(aerial
                ? (FeaturePrefs.isAerial4kEnabled(this) ? R.string.cc_aerial_on_4k : R.string.cc_aerial_on)
                : R.string.cc_aerial_off);

        tileSleepTimer.setActive(false);
        tileBrightness.setActive(false);

        for (int i = 0; i < tileOrder.size(); i++) {
            TileViews tv = tilesById.get(tileOrder.get(i));
            if (tv == null) continue;
            boolean carried = reorderingTiles && i == carriedTileIndex;
            tv.applyReorderStyle(reorderingTiles, carried);
            tv.root.setAlpha(!reorderingTiles ? 1f : (carried ? 1f : (carriedTileIndex >= 0 ? 0.50f : 0.84f)));
        }
        if (focusRing != null) focusRing.refresh();
    }

    private final java.util.Map<String, Bitmap> uriArtCache = new java.util.HashMap<>();
    private String lastMediaArtKey = "";

    private void onMediaUpdated(List<MediaController> controllers) {
        activeMedia = (controllers != null && !controllers.isEmpty()) ? controllers.get(0) : null;
        if (activeMedia == null) {
            lastMediaArtKey = "";
            mediaBox.setVisibility(View.GONE);
            return;
        }
        MediaMetadata md = activeMedia.getMetadata();
        PlaybackState ps = activeMedia.getPlaybackState();
        boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
        String pkg = activeMedia.getPackageName();
        String title = MediaSessionTracker.extractTitle(md);
        String sub = MediaSessionTracker.extractSubtitle(md);
        mediaTitle.setText(!TextUtils.isEmpty(title) ? title : pkg);
        mediaSub.setText(!TextUtils.isEmpty(sub) ? sub : getString(playing ? R.string.media_playing : R.string.media_paused));
        mediaPlayPause.setImageResource(playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play);

        String artKey = pkg + "|" + (title != null ? title : "") + "|" + (sub != null ? sub : "");
        if (!artKey.equals(lastMediaArtKey)) {
            lastMediaArtKey = artKey;
            Bitmap cached = uriArtCache.get(artKey);
            if (cached != null && !cached.isRecycled()) {
                mediaArt.setImageBitmap(cached);
            } else {
                // Decode/scale album artwork off the UI thread so opening Control Center never stalls Frame 0.
                final MediaMetadata snapMd = md;
                final String snapPkg = pkg;
                final String snapKey = artKey;
                ExpressiveGlass.runBackground(() -> {
                    Bitmap art = MediaSessionTracker.extractArtwork(this, snapMd, 160, null);
                    Drawable fallback = null;
                    if (art == null || art.isRecycled()) {
                        try {
                            fallback = getPackageManager().getApplicationIcon(snapPkg);
                        } catch (Exception ignored) {
                        }
                    }
                    final Bitmap finalArt = art;
                    final Drawable finalFallback = fallback;
                    MAIN.post(() -> {
                        if (isFinishing() || isDestroyed() || !snapKey.equals(lastMediaArtKey)) return;
                        if (finalArt != null && !finalArt.isRecycled()) {
                            uriArtCache.put(snapKey, finalArt);
                            mediaArt.setImageBitmap(finalArt);
                        } else if (finalFallback != null) {
                            mediaArt.setImageDrawable(finalFallback);
                        } else {
                            mediaArt.setImageResource(R.drawable.ic_apps);
                        }
                    });
                });
            }
        }
        boolean wasVisible = mediaBox.getVisibility() == View.VISIBLE;
        mediaBox.setVisibility(View.VISIBLE);
        if (!wasVisible && initialLayoutSettled && !reducedMotion) {
            mediaBox.animate().cancel();
            mediaBox.setAlpha(0f);
            mediaBox.setTranslationY(-dp(8));
            mediaBox.animate().alpha(1f).translationY(0f)
                    .setDuration(230L).setInterpolator(Motion.easeOutExpo()).start();
        }
    }

    private void addTileRow(LinearLayout parent, View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, dp(74), 1f);
        lpL.rightMargin = dp(9);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(0, dp(74), 1f);
        row.addView(left, lpL);
        row.addView(right, lpR);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.bottomMargin = dp(8);
        parent.addView(row, rLp);
    }

    private TileViews createTile(int iconRes, String titleText, String subText, int activeStroke, int activeFill) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(13), dp(8), dp(12), dp(8));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        applyRoundedOutline(box, dp(18));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setDuplicateParentStateEnabled(true);
        icon.setImageTintList(getColorStateList(R.color.pill_text));
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        iLp.bottomMargin = dp(5);
        box.addView(icon, iLp);

        TextView title = makeText(titleText, 13.5f, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setDuplicateParentStateEnabled(true);
        title.setTextColor(getColorStateList(R.color.pill_text));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = makeText(subText, 11f, 0x9EFFFFFF);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setDuplicateParentStateEnabled(true);
        sub.setTextColor(makeSubTextColors());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = dp(2);
        box.addView(sub, sLp);

        box.setOnFocusChangeListener((v, focused) -> {
            Motion.animateFocus(v, focused, 1.055f, 2f, density, reducedMotion);
            icon.animate().cancel();
            icon.animate()
                    .scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f)
                    .setDuration(reducedMotion ? Motion.REDUCED_MS : (focused ? 190L : 130L))
                    .setInterpolator(reducedMotion ? Motion.smoothDecel() : (focused ? Motion.tactileFocus() : Motion.easeOutExpo()))
                    .start();
        });

        TileViews tv = new TileViews(box, icon, title, sub, activeStroke, activeFill);
        tv.setActive(false);
        return tv;
    }

    private LinearLayout makeBottomPill(int iconRes, String label) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(8), 0, dp(8), 0);
        pill.setBackground(makeTileBg(false, 0, 0, dp(14)));
        applyRoundedOutline(pill, dp(14));
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        pill.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setDuplicateParentStateEnabled(true);
        icon.setImageTintList(getColorStateList(R.color.pill_text));
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        iLp.rightMargin = dp(6);
        pill.addView(icon, iLp);

        TextView tv = makeText(label, 12f, Color.WHITE);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setDuplicateParentStateEnabled(true);
        tv.setTextColor(getColorStateList(R.color.pill_text));
        pill.addView(tv, new LinearLayout.LayoutParams(-2, -2));

        Motion.attachPillFocus(pill, 1.06f, 1.5f, density, reducedMotion);
        FocusRingController.configure(pill, 1.06f, 1.5f, 14f, 0);
        return pill;
    }

    private ImageView makeMiniMediaBtn(int iconRes) {
        ImageView btn = new ImageView(this);
        btn.setImageResource(iconRes);
        btn.setScaleType(ImageView.ScaleType.CENTER);
        btn.setImageTintList(getColorStateList(R.color.pill_text));
        btn.setBackground(makeTileBg(false, 0, 0, dp(18)));
        applyRoundedOutline(btn, dp(18));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        Motion.attachPillFocus(btn, 1.12f, 1.5f, density, reducedMotion);
        FocusRingController.configure(btn, 1.12f, 1.5f, -1f, 0);
        return btn;
    }

    private StateListDrawable makeTileBg(boolean active, int activeStroke, int activeFill, int radiusPx) {
        return ExpressiveGlass.makeAdaptiveTileBg(density, radiusPx / Math.max(1f, density), active ? activeStroke : 0);
    }

    private ColorStateList makeSubTextColors() {
        return ExpressiveGlass.adaptiveSubTextColors();
    }

    private void addSettingsRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 16f, false));
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> launchAndClose(target));
        Motion.attachRowFocus(row, density, reducedMotion);
        ExpressiveGlass.applyAdaptiveContentTheme(row);
        rows.addView(row);
    }

    private void addSettingsActionRow(LinearLayout rows, int icon, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 16f, false));
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        Motion.attachRowFocus(row, density, reducedMotion);
        ExpressiveGlass.applyAdaptiveContentTheme(row);
        rows.addView(row);
    }

    @SuppressWarnings("deprecation")
    private void launchScreensaver() {
        handingOff = true;
        try {
            startActivity(new Intent(this, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            overridePendingTransition(0, 0);
        } catch (ActivityNotFoundException | SecurityException e) {
            handingOff = false;
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        // Let onPause() tear down once ClockActivity covers the screen so Home/video never flashes unblurred.
        MAIN.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
                overridePendingTransition(0, 0);
            }
        }, 320L);
    }

    @SuppressWarnings("deprecation")
    private void launchAndClose(Intent target) {
        String pkg = target.getComponent() != null ? target.getComponent().getPackageName() : target.getPackage();
        boolean isAddon = MainActivity.isGlassAddon(pkg);
        String clsName = target.getComponent() != null ? target.getComponent().getClassName() : "";
        boolean isQuickBar = clsName.endsWith("QuickActivity") || clsName.endsWith("BedtimeActivity");
        boolean keepHomeBlur = overHome && isAddon && !isQuickBar;
        if (isAddon) {
            Runnable launchNow = () -> {
                if (keepHomeBlur) {
                    handingOff = true;
                    MainActivity.setAddonModalOpen(true);
                }
                MainActivity.attachAddonBackdropExtras(target, overHome);
                try {
                    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    overridePendingTransition(0, 0);
                } catch (ActivityNotFoundException | SecurityException e) {
                    handingOff = false;
                    if (keepHomeBlur) MainActivity.setAddonModalOpen(false);
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isQuickBar) {
                    MainActivity.setControlCenterOpen(false);
                    finish();
                    overridePendingTransition(0, 0);
                } else {
                    // Wait for onPause() once remote add-on window renders to prevent 1-3 frame unblurred flash.
                    MAIN.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            finish();
                            overridePendingTransition(0, 0);
                        }
                    }, 350L);
                }
            };
            if (!overHome && !ExpressiveGlass.hasOverlayRefraction()) {
                HomeKeyService.captureOverlayBackdropForAddon(launchNow);
            } else {
                launchNow.run();
            }
            return;
        }
        MainActivity.setControlCenterOpen(false);
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void applyCapturedBackdrop() {
        applyCapturedBackdrop(false, false);
    }

    private void applyCapturedBackdrop(boolean deferFadeStart, boolean startOpaque) {
        if (rootLayout == null) return;
        backdropView = ExpressiveGlass.attachOverlayBackdrop(
                rootLayout, backdropView, density, reducedMotion, deferFadeStart, startOpaque);
        if (backdropView != null) {
            backdropGenOnCreate = ExpressiveGlass.overlayBackdropGeneration();
            ExpressiveGlass.setWindowDimOnly(getWindow(), 0f);
            if (panel != null) {
                ExpressiveGlass.setPanelSmoked(panel, false);
            }
        }
        ExpressiveGlass.invalidateLiquidPanels(rootLayout);
    }

    @SuppressWarnings("deprecation")
    private void slideOutAndFinish() {
        if (closing) return;
        closing = true;
        if (overHome) {
            MainActivity.fadeOutControlCenterBlurNow();
        } else if (backdropView != null) {
            backdropView.animate().cancel();
            if (reducedMotion) {
                backdropView.setAlpha(0f);
            } else {
                backdropView.animate().setStartDelay(0L).alpha(0f).setDuration(175L)
                        .setInterpolator(Motion.easeInExit()).start();
            }
        }
        Runnable completeExit = () -> {
            if (active() == this) {
                activeRef = null;
                MainActivity.setControlCenterOpen(false);
            }
            finish();
            overridePendingTransition(0, 0);
        };
        float endX = rtl ? -dp(380) : dp(380);
        View animTarget = panelStage != null ? panelStage : panel;
        Motion.cancelPanelAnim(animTarget);
        if (reducedMotion) {
            animTarget.animate().setStartDelay(0L).alpha(0f).setDuration(Motion.REDUCED_MS)
                    .withEndAction(completeExit).start();
            return;
        }
        Motion.glidePanel(animTarget,
                animTarget.getTranslationX(), endX,
                animTarget.getTranslationY(), 0f,
                animTarget.getScaleX(), 0.96f,
                animTarget.getScaleY(), 0.97f,
                false, 240L, Motion.easeInExit(), completeExit);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTileStates();
        if (mediaTracker != null && !mediaTrackerActive) {
            mediaTracker.start();
            mediaTrackerActive = true;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        slideOutAndFinish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (reorderingTiles) {
            if (carriedTileIndex >= 0) dropTile();
            else endTileReorder(true);
            return;
        }
        if (settingsExpanded) {
            collapseSettingsDrawer(true);
            return;
        }
        slideOutAndFinish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        UiClickSound.onKeyEvent(this, event);
        if (reorderingTiles) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    if (carriedTileIndex >= 0) dropTile();
                    else endTileReorder(true);
                }
                return true;
            }
            if (carriedTileIndex >= 0 && (code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) moveCarriedTile(code);
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
            slideOutAndFinish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        if (active() == this) {
            activeRef = null;
            if (!handingOff) MainActivity.setControlCenterOpen(false);
        } else if (!handingOff) {
            MainActivity.setControlCenterOpen(false);
        }
        if (panelStage != null) Motion.cancelPanelAnim(panelStage);
        if (panel != null) Motion.cancelPanelAnim(panel);
        if (backdropView != null) backdropView.animate().cancel();
        MAIN.removeCallbacks(haRefreshTick);
        if (mediaTracker != null) {
            mediaTracker.stop();
            mediaTrackerActive = false;
        }
        super.onPause();
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (active() == this) {
            activeRef = null;
            if (!handingOff) MainActivity.setControlCenterOpen(false);
        }
        uriArtCache.clear();
        if (backdropView != null) {
            ExpressiveGlass.releaseOverlayBackdrop(backdropView);
            backdropView = null;
        }
        final int gen = backdropGenOnCreate;
        MAIN.postDelayed(() -> {
            if (!isLive() && !isLaunchInFlight()
                    && !OverlayActivity.isLive() && !OverlayActivity.isLaunchInFlight()
                    && !LauncherSettingsActivity.isLive() && !MainActivity.isSettingsLaunchInFlight()) {
                ExpressiveGlass.clearOverlayBackdropIfGeneration(gen);
            }
        }, 200L);
        super.onDestroy();
    }

    private TextView makeText(String value, float sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(UiFont.get(this, R.string.font_medium));
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private void applyRoundedOutline(View v, int radiusPx) {
        // Tiles and pills draw their own rounded GradientDrawable backgrounds with padded children inside,
        // so clipping to outline is unnecessary and would force 14 extra Skia OpenGL clip masks per frame.
        ExpressiveGlass.disableOutlineShadow(v);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private final class TileViews {
        final LinearLayout root;
        final ImageView icon;
        final TextView title;
        final TextView sub;
        final int activeStroke;
        final int activeFill;
        private boolean lastActive;
        private int appliedBgState = -1;

        TileViews(LinearLayout root, ImageView icon, TextView title, TextView sub, int activeStroke, int activeFill) {
            this.root = root;
            this.icon = icon;
            this.title = title;
            this.sub = sub;
            this.activeStroke = activeStroke;
            this.activeFill = activeFill;
        }

        private void applyBackgroundIfChanged(boolean on, boolean reordering, boolean carried) {
            int state = (on ? 1 : 0) | (reordering ? 2 : 0) | (carried ? 4 : 0);
            if (state == appliedBgState) return;
            appliedBgState = state;
            if (carried) {
                root.setBackground(makeTileBg(true, REORDER_ACCENT, 0x520284C7, dp(18)));
                FocusRingController.configure(root, 1.055f, 2f, 18f, REORDER_ACCENT);
            } else if (reordering) {
                root.setBackground(makeTileBg(on, activeStroke, activeFill, dp(18)));
                FocusRingController.configure(root, 1.055f, 2f, 18f, REORDER_ACCENT);
            } else {
                root.setBackground(makeTileBg(on, activeStroke, activeFill, dp(18)));
                FocusRingController.configure(root, 1.055f, 2f, 18f, on ? activeStroke : 0);
            }
            if (root.isFocused() && focusRing != null) focusRing.refresh();
        }

        void setActive(boolean on) {
            this.lastActive = on;
            applyBackgroundIfChanged(on, reorderingTiles, false);
        }

        void applyReorderStyle(boolean reordering, boolean carried) {
            applyBackgroundIfChanged(lastActive, reordering, carried);
        }
    }
}
