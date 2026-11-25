package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple tvOS Control Center style frosted-glass media mini-player.
 * Shows all active/resumable media apps (Spotify, YouTube, SoundCloud, radio, etc.), lets the user switch
 * which app resumes playback with a single OK press, and provides transport controls + live progress.
 */
final class MediaMiniPlayerDialog {

    private final Activity activity;
    private final MediaSessionTracker tracker;
    private final Map<String, String> appLabels;
    private final boolean reducedMotion;
    private final boolean rtl;
    private final float density;
    private final Runnable onDismissed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout panel;
    private final FrameLayout cardHost;
    final PopupWindow popup;

    private final LinearLayout singleHeader;
    private final ImageView singleAppIcon;
    private final TextView singleAppName;
    private final View singleStatusDot;
    private final TextView singleStatusText;

    private final HorizontalScrollView sourcesScroll;
    private final LinearLayout sourcesRow;

    private final FrameLayout artFrame;
    private final ImageView artImage;
    private final ImageView artFallbackIcon;
    private final TextView trackTitle;
    private final TextView trackSubtitle;
    private final LinearLayout progressBlock;
    private final FrameLayout progressTrack;
    private final GradientDrawable progressTrackBg;
    private final View progressFill;
    private final GradientDrawable progressFillBg;
    private final View progressThumb;
    private final int thumbSizePx;
    private final TextView timeElapsed;
    private final TextView timeDuration;
    private final TextView liveBadge;

    private final ImageView btnPrev;
    private final ImageView btnPlayPause;
    private final ImageView btnNext;
    private final ImageView btnOpen;
    private final TextView multiHint;

    private final Map<String, Drawable> appIconCache = new HashMap<>();
    private final Map<String, String> appNameCache = new HashMap<>();
    private final Map<String, Bitmap> uriArtCache = new HashMap<>();
    private final Map<String, Integer> accentCache = new HashMap<>();
    private String lastFallbackArtPkg;

    private List<MediaController> sessions = new ArrayList<>();
    private String selectedPkg;
    private String lastSourcesKey = "";
    private String lastBoundTrackKey = "";
    private Boolean lastBoundPlaying;
    private int popupWidth;
    private boolean closed;
    private boolean dismissing;
    private int lastProgressWidth = -1;
    private boolean seekable;
    private long currentDurationMs;
    private long currentPosMs;
    private boolean scrubbing;
    private long scrubPosMs;
    private long seekHoldUntilMs;
    private long seekHoldPosMs;

    private final Runnable commitSeekRunnable = this::commitScrub;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (closed || !popup.isShowing()) return;
            MediaController active = selectedController();
            if (active != null) {
                updateProgress(active.getPlaybackState(), active.getMetadata());
                PlaybackState ps = active.getPlaybackState();
                if (ps != null && MediaSessionTracker.isPlayingState(ps.getState())) {
                    main.postDelayed(this, 500L);
                }
            }
        }
    };

    MediaMiniPlayerDialog(Activity activity, MediaSessionTracker tracker, Map<String, String> appLabels,
                          boolean reducedMotion, Runnable onDismissed) {
        this.activity = activity;
        this.tracker = tracker;
        this.appLabels = appLabels != null ? appLabels : new HashMap<>();
        this.reducedMotion = reducedMotion;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_menu_bar);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.onDismissed = onDismissed;

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        // 1A. Compact status header when only 1 media app is present.
        singleHeader = new LinearLayout(activity);
        singleHeader.setOrientation(LinearLayout.HORIZONTAL);
        singleHeader.setGravity(Gravity.CENTER_VERTICAL);

        singleAppIcon = new ImageView(activity);
        singleAppIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        applyRoundedOutline(singleAppIcon, dp(5));
        LinearLayout.LayoutParams sIconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        sIconLp.rightMargin = dp(8);
        singleHeader.addView(singleAppIcon, sIconLp);

        singleAppName = makeText(13.5f, 0xEBFFFFFF);
        singleAppName.setSingleLine(true);
        singleAppName.setEllipsize(TextUtils.TruncateAt.END);
        singleHeader.addView(singleAppName, new LinearLayout.LayoutParams(0, -2, 1f));

        singleStatusDot = new View(activity);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(6);
        singleHeader.addView(singleStatusDot, dotLp);

        singleStatusText = makeText(12f, 0xB3FFFFFF);
        singleStatusText.setSingleLine(true);
        singleHeader.addView(singleStatusText, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams singleLp = new LinearLayout.LayoutParams(-1, -2);
        singleLp.bottomMargin = dp(14);
        panel.addView(singleHeader, singleLp);

        // 1B. Focusable app switcher chips when 2+ media apps are present.
        sourcesScroll = new HorizontalScrollView(activity);
        sourcesScroll.setHorizontalScrollBarEnabled(false);
        sourcesScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sourcesScroll.setHorizontalFadingEdgeEnabled(true);
        sourcesScroll.setFadingEdgeLength(dp(18));
        FocusRingController.clipScrollContainers(sourcesScroll);
        sourcesRow = new LinearLayout(activity);
        sourcesRow.setOrientation(LinearLayout.HORIZONTAL);
        sourcesRow.setGravity(Gravity.CENTER_VERTICAL);
        sourcesRow.setClipChildren(false);
        sourcesRow.setClipToPadding(false);
        sourcesRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        sourcesScroll.addView(sourcesRow, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.bottomMargin = dp(12);
        scrollLp.leftMargin = -dp(8);
        scrollLp.rightMargin = -dp(8);
        panel.addView(sourcesScroll, scrollLp);

        // 2. Now Playing Hero Row: rounded square album art + track info + live scrubbable progress.
        LinearLayout heroRow = new LinearLayout(activity);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        heroRow.setClipChildren(false);
        heroRow.setClipToPadding(false);

        artFrame = new FrameLayout(activity);
        applyRoundedOutline(artFrame, dp(16));
        artImage = new ImageView(activity);
        artImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artFrame.addView(artImage, new FrameLayout.LayoutParams(-1, -1));
        artFallbackIcon = new ImageView(activity);
        artFallbackIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artFrame.addView(artFallbackIcon, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        View artRim = new View(activity);
        GradientDrawable artRimBg = new GradientDrawable();
        artRimBg.setCornerRadius(dp(16));
        artRimBg.setStroke(Math.max(1, Math.round(1.1f * density)), 0x3DFFFFFF);
        artRim.setBackground(artRimBg);
        artFrame.addView(artRim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(88), dp(88));
        artLp.rightMargin = dp(16);
        heroRow.addView(artFrame, artLp);

        LinearLayout metaCol = new LinearLayout(activity);
        metaCol.setOrientation(LinearLayout.VERTICAL);
        metaCol.setGravity(Gravity.CENTER_VERTICAL);
        metaCol.setClipChildren(false);
        metaCol.setClipToPadding(false);

        trackTitle = makeText(17.5f, Color.WHITE);
        trackTitle.setMaxLines(2);
        trackTitle.setEllipsize(TextUtils.TruncateAt.END);
        metaCol.addView(trackTitle, new LinearLayout.LayoutParams(-1, -2));

        trackSubtitle = makeText(13.5f, 0xB8FFFFFF);
        trackSubtitle.setSingleLine(true);
        trackSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(4);
        metaCol.addView(trackSubtitle, subLp);

        progressBlock = new LinearLayout(activity);
        progressBlock.setOrientation(LinearLayout.VERTICAL);
        progressBlock.setClipChildren(false);
        progressBlock.setClipToPadding(false);
        progressBlock.setClickable(true);
        progressBlock.setPadding(dp(8), dp(4), dp(8), dp(4));
        StateListDrawable progressFocusBg = new StateListDrawable();
        GradientDrawable progressFocused = new GradientDrawable();
        progressFocused.setColor(0x1CFFFFFF);
        progressFocused.setCornerRadius(dp(12));
        progressFocusBg.addState(new int[]{android.R.attr.state_focused}, progressFocused);
        progressBlock.setBackground(progressFocusBg);
        LinearLayout.LayoutParams progBlockLp = new LinearLayout.LayoutParams(-1, -2);
        progBlockLp.topMargin = dp(4);
        progBlockLp.leftMargin = -dp(8);
        progBlockLp.rightMargin = -dp(8);

        // Fixed-height host keeps total layout height steady while the glowing scrub thumb pops in on focus.
        FrameLayout progressBarHost = new FrameLayout(activity);
        progressBarHost.setClipChildren(false);
        progressBarHost.setClipToPadding(false);

        progressTrack = new FrameLayout(activity);
        progressTrackBg = new GradientDrawable();
        progressTrackBg.setColor(0x2EFFFFFF);
        progressTrackBg.setCornerRadius(dp(3));
        progressTrack.setBackground(progressTrackBg);
        applyRoundedOutline(progressTrack, dp(3));

        progressFill = new View(activity);
        progressFillBg = new GradientDrawable();
        progressFillBg.setColor(0xEBFFFFFF);
        progressFillBg.setCornerRadius(dp(3));
        progressFill.setBackground(progressFillBg);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        progressBarHost.addView(progressTrack, new FrameLayout.LayoutParams(-1, dp(5), Gravity.CENTER_VERTICAL));

        thumbSizePx = dp(14);
        progressThumb = new View(activity);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(Color.WHITE);
        thumbBg.setStroke(Math.max(1, dp(1)), 0x40000000);
        progressThumb.setBackground(thumbBg);
        progressThumb.setElevation(dp(4));
        progressThumb.setAlpha(0f);
        progressThumb.setScaleX(0.4f);
        progressThumb.setScaleY(0.4f);
        progressBarHost.addView(progressThumb, new FrameLayout.LayoutParams(thumbSizePx, thumbSizePx,
                Gravity.CENTER_VERTICAL | Gravity.START));

        progressBlock.addView(progressBarHost, new LinearLayout.LayoutParams(-1, dp(16)));

        LinearLayout timesRow = new LinearLayout(activity);
        timesRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(-1, -2);
        timesLp.topMargin = dp(2);

        timeElapsed = makeText(11.5f, 0x99FFFFFF);
        timeElapsed.setFontFeatureSettings("tnum");
        timeElapsed.setSingleLine(true);
        timesRow.addView(timeElapsed, new LinearLayout.LayoutParams(0, -2, 1f));

        timeDuration = makeText(11.5f, 0x99FFFFFF);
        timeDuration.setFontFeatureSettings("tnum");
        timeDuration.setSingleLine(true);
        timeDuration.setGravity(Gravity.END);
        timesRow.addView(timeDuration, new LinearLayout.LayoutParams(-2, -2));

        progressBlock.addView(timesRow, timesLp);
        metaCol.addView(progressBlock, progBlockLp);

        liveBadge = makeText(12f, 0xB8FFFFFF);
        liveBadge.setSingleLine(true);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(-2, -2);
        liveLp.topMargin = dp(10);
        metaCol.addView(liveBadge, liveLp);

        heroRow.addView(metaCol, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(heroRow, new LinearLayout.LayoutParams(-1, -2));

        // 3. Transport controls row: Prev, Play/Pause (primary), Next, Open App.
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(4), dp(4), dp(4), dp(4));
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(-1, -2);
        ctrlLp.topMargin = dp(18);

        btnPrev = makeControlButton(R.drawable.ic_media_prev, dp(46), false,
                activity.getString(R.string.media_prev));
        btnPlayPause = makeControlButton(R.drawable.ic_media_play, dp(56), true,
                activity.getString(R.string.media_play_pause));
        btnNext = makeControlButton(R.drawable.ic_media_next, dp(46), false,
                activity.getString(R.string.media_next));
        btnOpen = makeControlButton(R.drawable.ic_media_open, dp(46), false,
                activity.getString(R.string.media_open_app));

        addControlWithGap(controls, btnPrev, dp(46), dp(14));
        addControlWithGap(controls, btnPlayPause, dp(56), dp(14));
        addControlWithGap(controls, btnNext, dp(46), dp(18));
        addControlWithGap(controls, btnOpen, dp(46), 0);
        panel.addView(controls, ctrlLp);

        // 4. Footer hint shown when multiple apps are available to switch between.
        multiHint = makeText(11.5f, 0x85FFFFFF);
        multiHint.setText(R.string.media_switch_hint);
        multiHint.setGravity(Gravity.CENTER);
        multiHint.setSingleLine(true);
        multiHint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(12);
        panel.addView(multiHint, hintLp);

        // Wire interactive seek bar scrubbing and D-pad navigation.
        progressBlock.setOnFocusChangeListener((v, focused) -> {
            long dur = reducedMotion ? Motion.REDUCED_MS : 185L;
            progressTrackBg.setColor(focused ? 0x4DFFFFFF : 0x2EFFFFFF);
            progressFillBg.setColor(focused ? Color.WHITE : 0xEBFFFFFF);
            progressTrack.animate().cancel();
            progressTrack.animate().scaleY(focused ? 1.55f : 1f)
                    .setDuration(dur)
                    .setInterpolator(reducedMotion ? Motion.smoothDecel() : Motion.easeOutExpo())
                    .start();
            progressThumb.animate().cancel();
            progressThumb.animate()
                    .alpha(focused ? 1f : 0f)
                    .scaleX(focused ? 1f : 0.35f)
                    .scaleY(focused ? 1f : 0.35f)
                    .setDuration(dur)
                    .setInterpolator(reducedMotion ? Motion.smoothDecel() : (focused ? Motion.springPop() : Motion.easeOutExpo()))
                    .start();
            timeElapsed.setTextColor(focused ? Color.WHITE : 0x99FFFFFF);
            if (!focused) {
                commitScrub();
            }
        });
        progressBlock.setOnClickListener(v -> {
            if (scrubbing) {
                commitScrub();
            } else {
                MediaController c = selectedController();
                if (c != null) tracker.togglePlayPause(c);
            }
        });
        progressBlock.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!seekable || currentDurationMs <= 0L) return true;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (!scrubbing) {
                        scrubPosMs = SystemClock.elapsedRealtime() < seekHoldUntilMs ? seekHoldPosMs : currentPosMs;
                        scrubbing = true;
                        progressThumb.animate().scaleX(1.18f).scaleY(1.18f)
                                .setDuration(reducedMotion ? Motion.REDUCED_MS : 100L).start();
                    }
                    long step = MediaSessionTracker.seekStepMs(currentDurationMs, event.getRepeatCount());
                    boolean forward = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                    scrubPosMs = MediaSessionTracker.clampPosition(scrubPosMs + (forward ? step : -step), currentDurationMs);
                    renderProgress(scrubPosMs, currentDurationMs);
                    main.removeCallbacks(commitSeekRunnable);
                    main.postDelayed(commitSeekRunnable, 220L);
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP && scrubbing) {
                    commitScrub();
                    return true;
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                commitScrub();
                btnPlayPause.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                commitScrub();
                if (sourcesScroll.getVisibility() == View.VISIBLE && sourcesRow.getChildCount() > 0) {
                    focusSelectedSourceChip();
                }
                return true;
            }
            return false;
        });

        // Wire transport clicks and D-pad boundaries.
        btnPrev.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.12f : 1f, reducedMotion);
            resetScrubState();
            MediaController c = selectedController();
            if (c != null) MediaSessionTracker.skipPrevious(c);
        });
        btnPlayPause.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.12f : 1f, reducedMotion);
            MediaController c = selectedController();
            if (c != null) tracker.togglePlayPause(c);
        });
        btnNext.setOnClickListener(v -> {
            Motion.pulseClick(v, v.isFocused() ? 1.12f : 1f, reducedMotion);
            resetScrubState();
            MediaController c = selectedController();
            if (c != null) MediaSessionTracker.skipNext(c);
        });
        btnOpen.setOnClickListener(v -> {
            MediaController c = selectedController();
            close();
            if (c != null) MediaSessionTracker.openSessionApp(activity, c);
        });

        View[] controlViews = {btnPrev, btnPlayPause, btnNext, btnOpen};
        for (int i = 0; i < controlViews.length; i++) {
            final int idx = i;
            controlViews[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                boolean backKey = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean fwdKey = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (backKey && idx == 0) return true;
                if (fwdKey && idx == controlViews.length - 1) return true;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (seekable && progressBlock.getVisibility() == View.VISIBLE) {
                        progressBlock.requestFocus();
                    } else if (sourcesScroll.getVisibility() == View.VISIBLE && sourcesRow.getChildCount() > 0) {
                        focusSelectedSourceChip();
                    }
                    return true;
                }
                return false;
            });
        }

        popupWidth = Math.min(dp(430), Math.max(dp(320), activity.getWindow().getDecorView().getWidth() - dp(80)));
        int ringPad = FocusRingController.wrapInsetPx(density);
        FocusRingController.configure(progressBlock, 1.0f, 0f, 12f, 0);
        FrameLayout ringHost = FocusRingController.wrapPopup(panel, density, !reducedMotion, reducedMotion);
        panel.setOnClickListener(v -> { /* consume inside taps */ });
        cardHost = new FrameLayout(activity);
        cardHost.setClipChildren(false);
        cardHost.setClipToPadding(false);
        cardHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(cardHost);
        UiClickSound.attachFocusSound(cardHost);
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                ExpressiveGlass.invalidateLiquidPanels(panel);
            }
        });

        // Full-screen transparent backdrop (exact same architecture as FolderDialog): guarantees
        // winOffX == 0, winOffY == 0 on Frame 0 so Liquid Glass never waits on WindowManager MSG_RESIZED.
        FrameLayout backdrop = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        animateClose();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        backdrop.setClipChildren(false);
        backdrop.setClipToPadding(false);
        backdrop.setOnClickListener(v -> animateClose());
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                popupWidth + 2 * ringPad, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | (rtl ? Gravity.START : Gravity.END));
        int sideMargin = Math.max(0, dp(56) - ringPad);
        cardLp.topMargin = Math.max(0, dp(112) - ringPad);
        if (rtl) cardLp.leftMargin = sideMargin;
        else cardLp.rightMargin = sideMargin;
        backdrop.addView(cardHost, cardLp);

        popup = new PopupWindow(backdrop, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissing = false;
            main.removeCallbacks(progressTicker);
            main.removeCallbacks(commitSeekRunnable);
            Motion.cancelPanelAnim(cardHost);
            ringHost.animate().cancel();
            panel.animate().cancel();
            artFrame.animate().cancel();
            trackTitle.animate().cancel();
            trackSubtitle.animate().cancel();
            scrubbing = false;
            uriArtCache.clear();
            onDismissed.run();
        });
    }

    void show(List<MediaController> initialSessions) {
        if (closed || initialSessions == null || initialSessions.isEmpty()) return;
        updateSessions(initialSessions);
        if (activity instanceof MainActivity) ((MainActivity) activity).prepareBackdropBlurForPopup();

        int ringPad = FocusRingController.wrapInsetPx(density);
        int fullW = popupWidth + 2 * ringPad;
        cardHost.setAlpha(0f);
        if (!reducedMotion) {
            cardHost.setPivotX(rtl ? fullW * 0.15f : fullW * 0.85f);
            cardHost.setPivotY(0f);
            cardHost.setScaleX(0.90f);
            cardHost.setScaleY(0.90f);
            cardHost.setTranslationY(-44f * density);
        }
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.NO_GRAVITY, 0, 0);
        btnPlayPause.requestFocus();
        cardHost.post(() -> cardHost.postOnAnimation(() -> {
            if (closed || dismissing || !popup.isShowing()) return;
            if (activity instanceof MainActivity) ((MainActivity) activity).startBackdropBlurFadeIn();
            Motion.revealTopCornerCard(cardHost, density, rtl, fullW, reducedMotion);
        }));
    }

    void animateClose() {
        if (closed || dismissing) return;
        if (!popup.isShowing()) {
            close();
            return;
        }
        dismissing = true;
        if (activity instanceof MainActivity) ((MainActivity) activity).setBackdropBlur(false);
        int ringPad = FocusRingController.wrapInsetPx(density);
        Motion.dismissTopCornerCard(cardHost, density, rtl, popupWidth + 2 * ringPad, reducedMotion, this::close);
    }

    void close() {
        main.removeCallbacks(progressTicker);
        main.removeCallbacks(commitSeekRunnable);
        Motion.cancelPanelAnim(cardHost);
        scrubbing = false;
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;
            onDismissed.run();
        }
    }

    void updateSessions(List<MediaController> latest) {
        if (closed) return;
        if (latest == null || latest.isEmpty()) {
            close();
            return;
        }
        sessions = new ArrayList<>(latest);
        if (selectedController() == null) {
            selectedPkg = sessions.get(0).getPackageName();
        }
        rebuildSources();
        bindSelected();
    }

    @Nullable
    private MediaController selectedController() {
        if (sessions.isEmpty()) return null;
        if (selectedPkg != null) {
            for (MediaController c : sessions) {
                if (selectedPkg.equals(c.getPackageName())) return c;
            }
        }
        return sessions.get(0);
    }

    private void rebuildSources() {
        boolean multi = sessions.size() > 1;
        singleHeader.setVisibility(multi ? View.GONE : View.VISIBLE);
        sourcesScroll.setVisibility(multi ? View.VISIBLE : View.GONE);
        multiHint.setVisibility(multi ? View.VISIBLE : View.GONE);

        if (!multi) {
            if (sourcesScroll.hasFocus()) btnPlayPause.requestFocus();
            lastSourcesKey = "";
            return;
        }

        // Skip tearing down & recreating chips when the player roster & playing dots haven't changed,
        // preventing focus ring blink/glitches during live metadata updates.
        StringBuilder keyBuilder = new StringBuilder();
        for (int i = 0; i < sessions.size(); i++) {
            MediaController c = sessions.get(i);
            PlaybackState ps = c.getPlaybackState();
            boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
            keyBuilder.append(c.getPackageName()).append(playing ? ":1|" : ":0|");
        }
        String sourcesKey = keyBuilder.toString();
        if (sourcesKey.equals(lastSourcesKey) && sourcesRow.getChildCount() == sessions.size()) {
            refreshChipSelectedStyles();
            return;
        }
        lastSourcesKey = sourcesKey;

        // Preserve focus if the user was currently navigating source chips.
        String focusedChipPkg = null;
        for (int i = 0; i < sourcesRow.getChildCount(); i++) {
            View child = sourcesRow.getChildAt(i);
            if (child.hasFocus() && child.getTag() instanceof String) {
                focusedChipPkg = (String) child.getTag();
                break;
            }
        }

        sourcesRow.removeAllViews();
        View toRefocus = null;
        for (int i = 0; i < sessions.size(); i++) {
            MediaController c = sessions.get(i);
            String pkg = c.getPackageName();
            boolean selected = pkg.equals(selectedPkg);
            PlaybackState ps = c.getPlaybackState();
            boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());

            LinearLayout chip = new LinearLayout(activity);
            chip.setTag(pkg);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), dp(6), dp(12), dp(6));
            chip.setBackground(makeSourceChipBg());
            chip.setSelected(selected);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            chip.setClickable(true);

            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(loadAppIcon(pkg));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            applyRoundedOutline(icon, dp(4));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
            ilp.rightMargin = dp(7);
            chip.addView(icon, ilp);

            TextView label = makeText(13f, Color.WHITE);
            label.setText(loadAppName(pkg));
            label.setTextColor(activity.getColorStateList(R.color.pill_text));
            label.setDuplicateParentStateEnabled(true);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setMaxWidth(dp(132));
            chip.addView(label, new LinearLayout.LayoutParams(-2, -2));

            if (playing) {
                View dot = new View(activity);
                dot.setBackground(makeDot(0xFF34C759));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
                dlp.leftMargin = dp(6);
                chip.addView(dot, dlp);
            }

            final int idx = i;
            FocusRingController.configure(chip, 1.06f, 1.5f, FocusRingController.RADIUS_CAPSULE, 0);
            chip.setOnFocusChangeListener((v, focused) -> {
                Motion.animateFocus(v, focused, 1.06f, 1.5f, density, reducedMotion);
                if (focused) {
                    ensureChipVisible(v);
                    if (!pkg.equals(selectedPkg)) {
                        resetScrubState();
                        selectedPkg = pkg;
                        refreshChipSelectedStyles();
                        bindSelected();
                    }
                }
            });
            chip.setOnClickListener(v -> {
                resetScrubState();
                selectedPkg = pkg;
                refreshChipSelectedStyles();
                tracker.switchAndPlay(c);
                bindSelected();
                btnPlayPause.requestFocus();
            });
            chip.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                boolean backKey = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean fwdKey = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (backKey && idx == 0) return true;
                if (fwdKey && idx == sessions.size() - 1) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (seekable && progressBlock.getVisibility() == View.VISIBLE) {
                        progressBlock.requestFocus();
                    } else {
                        btnPlayPause.requestFocus();
                    }
                    return true;
                }
                return false;
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, dp(34));
            if (i > 0) clp.leftMargin = dp(8);
            sourcesRow.addView(chip, clp);

            if (pkg.equals(focusedChipPkg)) toRefocus = chip;
        }
        if (toRefocus != null) toRefocus.requestFocus();
    }

    /** Keeps a focused chip comfortably inside the horizontal scroll viewport with room for its 1.06x scale + ring. */
    private void ensureChipVisible(View chip) {
        int viewW = sourcesScroll.getWidth();
        if (viewW <= 0) return;
        int margin = dp(12);
        int left = chip.getLeft() - margin;
        int right = chip.getRight() + margin;
        int curScroll = sourcesScroll.getScrollX();
        int maxScroll = Math.max(0, sourcesRow.getWidth() - viewW);
        int targetScroll = curScroll;
        if (left < curScroll) {
            targetScroll = Math.max(0, left);
        } else if (right > curScroll + viewW) {
            targetScroll = Math.min(maxScroll, right - viewW);
        }
        if (targetScroll != curScroll) {
            sourcesScroll.smoothScrollTo(targetScroll, 0);
        }
    }

    private void refreshChipSelectedStyles() {
        for (int i = 0; i < sourcesRow.getChildCount(); i++) {
            View child = sourcesRow.getChildAt(i);
            boolean sel = selectedPkg != null && selectedPkg.equals(child.getTag());
            if (child.isSelected() != sel) child.setSelected(sel);
        }
    }

    private void focusSelectedSourceChip() {
        for (int i = 0; i < sourcesRow.getChildCount(); i++) {
            View child = sourcesRow.getChildAt(i);
            if (selectedPkg != null && selectedPkg.equals(child.getTag())) {
                child.requestFocus();
                return;
            }
        }
        if (sourcesRow.getChildCount() > 0) sourcesRow.getChildAt(0).requestFocus();
    }

    private void bindSelected() {
        MediaController c = selectedController();
        if (c == null) return;
        String pkg = c.getPackageName();
        PlaybackState ps = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
        String appName = loadAppName(pkg);

        // Single-app status header.
        if (singleHeader.getVisibility() == View.VISIBLE) {
            singleAppIcon.setImageDrawable(loadAppIcon(pkg));
            singleAppName.setText(appName);
            singleStatusDot.setBackground(makeDot(playing ? 0xFF34C759 : 0x80FFFFFF));
            singleStatusText.setText(playing ? R.string.media_playing : R.string.media_paused);
        }

        // Artwork (bitmap metadata -> content:// URI -> vivid accent gradient with app icon).
        Bitmap art = extractArtwork(md);
        if (art != null && !art.isRecycled()) {
            lastFallbackArtPkg = null;
            artFrame.setBackground(null);
            artImage.setImageBitmap(art);
            artImage.setVisibility(View.VISIBLE);
            artFallbackIcon.setVisibility(View.GONE);
        } else {
            Drawable appIcon = loadAppIcon(pkg);
            if (!pkg.equals(lastFallbackArtPkg)) {
                lastFallbackArtPkg = pkg;
                Integer cachedAccent = accentCache.get(pkg);
                int accent = cachedAccent != null ? cachedAccent : AppAdapter.vividColor(appIcon);
                if (cachedAccent == null) accentCache.put(pkg, accent);
                int topCol = blendColor(accent, 0xFF1E2636, 0.55f);
                int botCol = blendColor(accent, 0xFF101520, 0.78f);
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topCol, botCol});
                bg.setCornerRadius(dp(16));
                artFrame.setBackground(bg);
            }
            artImage.setImageBitmap(null);
            artImage.setVisibility(View.GONE);
            artFallbackIcon.setImageDrawable(appIcon);
            artFallbackIcon.setVisibility(View.VISIBLE);
        }

        // Track title & artist/album + smooth cross-drift when track or source changes while visible.
        String title = MediaSessionTracker.extractTitle(md);
        String subtitle = MediaSessionTracker.extractSubtitle(md);
        String trackKey = pkg + "|" + (title != null ? title : "") + "|" + (subtitle != null ? subtitle : "");
        boolean trackChanged = !lastBoundTrackKey.isEmpty() && !trackKey.equals(lastBoundTrackKey);
        lastBoundTrackKey = trackKey;

        trackTitle.setText(!TextUtils.isEmpty(title) ? title : appName);
        if (!TextUtils.isEmpty(subtitle)) {
            trackSubtitle.setText(subtitle);
            trackSubtitle.setVisibility(View.VISIBLE);
        } else if (!TextUtils.isEmpty(title)) {
            trackSubtitle.setText(appName);
            trackSubtitle.setVisibility(View.VISIBLE);
        } else {
            trackSubtitle.setText(playing ? R.string.media_playing : R.string.media_paused);
            trackSubtitle.setVisibility(View.VISIBLE);
        }
        if (trackChanged && !reducedMotion && popup.isShowing()) {
            artFrame.animate().cancel();
            artFrame.setScaleX(0.92f);
            artFrame.setScaleY(0.92f);
            artFrame.setAlpha(0.45f);
            artFrame.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(240L).setInterpolator(Motion.springPop()).start();
            trackTitle.animate().cancel();
            trackTitle.setAlpha(0.25f);
            trackTitle.setTranslationY(dp(6));
            trackTitle.animate().alpha(1f).translationY(0f)
                    .setDuration(220L).setInterpolator(Motion.easeOutExpo()).start();
            trackSubtitle.animate().cancel();
            trackSubtitle.setAlpha(0.25f);
            trackSubtitle.setTranslationY(dp(4));
            trackSubtitle.animate().alpha(1f).translationY(0f)
                    .setDuration(240L).setInterpolator(Motion.easeOutExpo()).start();
        }

        // Transport play/pause icon with crisp state pop when toggling play/pause.
        btnPlayPause.setImageResource(playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        if (lastBoundPlaying != null && lastBoundPlaying != playing && !reducedMotion && popup.isShowing()) {
            Motion.pulseClick(btnPlayPause, btnPlayPause.isFocused() ? 1.12f : 1f, false);
        }
        lastBoundPlaying = playing;

        // Progress bar & ticker.
        main.removeCallbacks(progressTicker);
        updateProgress(ps, md);
        if (playing && !closed) {
            main.postDelayed(progressTicker, 500L);
        }
    }

    private void resetScrubState() {
        main.removeCallbacks(commitSeekRunnable);
        scrubbing = false;
        seekHoldUntilMs = 0L;
        lastProgressWidth = -1;
    }

    private void commitScrub() {
        main.removeCallbacks(commitSeekRunnable);
        if (!scrubbing) return;
        scrubbing = false;
        long target = scrubPosMs;
        seekHoldPosMs = target;
        seekHoldUntilMs = SystemClock.elapsedRealtime() + 750L;
        if (progressBlock.hasFocus()) {
            progressThumb.animate().scaleX(1f).scaleY(1f)
                    .setDuration(reducedMotion ? Motion.REDUCED_MS : 120L).start();
        }
        MediaController c = selectedController();
        if (c != null) MediaSessionTracker.seekTo(c, target);
        if (currentDurationMs > 0L) renderProgress(target, currentDurationMs);
    }

    private void updateProgress(@Nullable PlaybackState ps, @Nullable MediaMetadata md) {
        long duration = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0L;
        currentDurationMs = Math.max(0L, duration);
        seekable = MediaSessionTracker.canSeek(ps, currentDurationMs);
        progressBlock.setFocusable(seekable);
        progressBlock.setFocusableInTouchMode(seekable);
        if (currentDurationMs > 0L && ps != null) {
            long realPos = Math.min(currentDurationMs, MediaSessionTracker.currentPositionMs(ps, SystemClock.elapsedRealtime()));
            currentPosMs = realPos;
            progressBlock.setVisibility(View.VISIBLE);
            liveBadge.setVisibility(View.GONE);
            long displayPos = realPos;
            if (scrubbing) {
                displayPos = scrubPosMs;
            } else if (SystemClock.elapsedRealtime() < seekHoldUntilMs) {
                if (Math.abs(realPos - seekHoldPosMs) <= 2_500L) {
                    seekHoldUntilMs = 0L;
                } else {
                    displayPos = seekHoldPosMs;
                }
            }
            renderProgress(displayPos, currentDurationMs);
        } else {
            resetScrubState();
            if (progressBlock.hasFocus()) btnPlayPause.requestFocus();
            progressBlock.setVisibility(View.GONE);
            boolean playing = ps != null && MediaSessionTracker.isPlayingState(ps.getState());
            liveBadge.setText(playing ? "\u25CF  " + activity.getString(R.string.media_live)
                    : activity.getString(R.string.media_paused));
            liveBadge.setTextColor(playing ? 0xFF6BE88E : 0x99FFFFFF);
            liveBadge.setVisibility(View.VISIBLE);
        }
    }

    private void renderProgress(long posMs, long durationMs) {
        long clamped = MediaSessionTracker.clampPosition(posMs, durationMs);
        timeElapsed.setText(MediaSessionTracker.formatTime(clamped));
        timeDuration.setText(MediaSessionTracker.formatTime(durationMs));
        float fraction = durationMs > 0L ? Math.max(0f, Math.min(1f, clamped / (float) durationMs)) : 0f;
        int trackW = progressTrack.getWidth();
        if (trackW > 0) {
            int targetW = Math.round(trackW * fraction);
            if (targetW != lastProgressWidth) {
                lastProgressWidth = targetW;
                progressFill.getLayoutParams().width = targetW;
                progressFill.requestLayout();
            }
            progressThumb.setTranslationX(targetW - thumbSizePx / 2f);
        } else {
            progressTrack.post(() -> {
                if (!closed && currentDurationMs > 0L) {
                    renderProgress(scrubbing ? scrubPosMs : clamped, currentDurationMs);
                }
            });
        }
    }

    @Nullable
    Bitmap extractArtwork(@Nullable MediaMetadata md) {
        return MediaSessionTracker.extractArtwork(activity, md, 320, uriArtCache);
    }

    private ImageView makeControlButton(int iconRes, int sizePx, boolean primary, String contentDesc) {
        ImageView btn = new ImageView(activity);
        btn.setImageResource(iconRes);
        btn.setImageTintList(activity.getColorStateList(R.color.pill_text));
        btn.setScaleType(ImageView.ScaleType.CENTER);
        btn.setBackground(makeCircleButtonBg(sizePx / 2f, primary));
        btn.setContentDescription(contentDesc);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        Motion.attachPillFocus(btn, 1.12f, 2f, density, reducedMotion);
        return btn;
    }

    private void addControlWithGap(LinearLayout row, View btn, int sizePx, int endGapPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.rightMargin = endGapPx;
        row.addView(btn, lp);
    }

    private StateListDrawable makeCircleButtonBg(float radiusPx, boolean primary) {
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                primary ? new int[]{0x58FFFFFF, 0x32FFFFFF} : new int[]{0x38FFFFFF, 0x1EFFFFFF});
        focused.setCornerRadius(radiusPx);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                primary ? new int[]{0x42FFFFFF, 0x22FFFFFF} : new int[]{0x28FFFFFF, 0x14FFFFFF});
        rest.setCornerRadius(radiusPx);
        rest.setStroke(Math.max(1, Math.round((primary ? 1.4f : 1.0f) * density)),
                primary ? 0x70FFFFFF : 0x3EFFFFFF);
        bg.addState(new int[0], rest);
        return bg;
    }

    private StateListDrawable makeSourceChipBg() {
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focusedSelected = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x52FFFFFF, 0x2CFFFFFF});
        focusedSelected.setCornerRadius(dp(17));
        bg.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, focusedSelected);

        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38FFFFFF, 0x1EFFFFFF});
        focused.setCornerRadius(dp(17));
        bg.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable selected = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x20FFFFFF});
        selected.setCornerRadius(dp(17));
        selected.setStroke(Math.max(1, Math.round(1.3f * density)), 0x78FFFFFF);
        bg.addState(new int[]{android.R.attr.state_selected}, selected);

        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1EFFFFFF, 0x0EFFFFFF});
        rest.setCornerRadius(dp(17));
        rest.setStroke(Math.max(1, Math.round(1.0f * density)), 0x30FFFFFF);
        bg.addState(new int[0], rest);
        return bg;
    }

    private GradientDrawable makeDot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private Drawable loadAppIcon(String pkg) {
        Drawable cached = appIconCache.get(pkg);
        if (cached != null) return cached;
        Drawable d = null;
        try {
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            d = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d == null) d = activity.getDrawable(R.drawable.ic_apps);
        appIconCache.put(pkg, d);
        return d;
    }

    private String loadAppName(String pkg) {
        String custom = appLabels.get(pkg);
        if (!TextUtils.isEmpty(custom)) return custom;
        String cached = appNameCache.get(pkg);
        if (cached != null) return cached;
        String name = pkg;
        try {
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = ai.loadLabel(pm);
            if (!TextUtils.isEmpty(l)) name = Cards.cleanLabel(l.toString(), pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        appNameCache.put(pkg, name);
        return name;
    }

    private TextView makeText(float sizeSp, int color) {
        TextView tv = new TextView(activity);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(UiFont.get(activity, R.string.font_medium));
        tv.setLetterSpacing(0.01f);
        tv.setIncludeFontPadding(false);
        return tv;
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
