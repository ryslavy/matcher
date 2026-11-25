package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Frosted Liquid Glass Discover overlay embedded inside {@link OverlayActivity} ({@code MODE_DISCOVER}):
 * surfaces Continue Watching (Android TV Watch Next + preview channels with progress bars & hold-OK hide)
 * and Unsponsored Trending Picks (TMDB / Trakt / Plex filtered to installed apps, opening in Nuvio or app)
 * while keeping the main Home screen completely minimal and clutter-free.
 */
final class DiscoverDialog {

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private final Activity activity;
    private final float density;
    private final boolean reducedMotion;
    private final Runnable onDismissed;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final FrameLayout panelStage;
    private final LinearLayout panel;
    @Nullable private FocusRingController focusRing;
    @Nullable private FrameLayout embeddedHost;
    @Nullable private Dialog qrDialog;
    private LinearLayout watchNextStrip;
    private View watchNextHeader;
    private View watchNextPermRow;
    private LinearLayout picksStrip;
    private TextView picksEmptyView;
    private final TextView[] filterTabs = new TextView[3];
    private int activeFilter; // 0 = All, 1 = Movies, 2 = TV
    private List<TrendingPicksRepository.Pick> allPicks = new ArrayList<>();
    private boolean closed;
    private boolean dismissing;

    DiscoverDialog(Activity activity, boolean reducedMotion, Runnable onDismissed) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.reducedMotion = reducedMotion;
        this.onDismissed = onDismissed;

        int pad = FocusRingController.wrapInsetPx(density);
        panelStage = new FrameLayout(activity);
        panelStage.setClipChildren(false);
        panelStage.setClipToPadding(false);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(dp(24), dp(18), dp(24), dp(18));
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, false, false);
        panel.setOnClickListener(v -> { /* consume */ });

        buildChrome();

        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int panelW = Math.min(dp(860), Math.max(dp(560), dm.widthPixels - dp(80)));
        int panelH = Math.min(dp(490), Math.max(dp(340), dm.heightPixels - dp(64)));
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(panelW, panelH);
        innerLp.setMargins(pad, pad, pad, pad);
        panelStage.addView(panel, innerLp);
        focusRing = FocusRingController.attach(panelStage, panel, density, !reducedMotion, reducedMotion);
    }

    private void buildChrome() {
        // Header row: title + quick QR setup & refresh pills
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        header.setClipToPadding(false);

        TextView title = new TextView(activity);
        title.setText(R.string.discover_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView qrBtn = makePill(activity.getString(R.string.qr_setup_short));
        qrBtn.setOnClickListener(v -> {
            if (qrDialog != null && qrDialog.isShowing()) qrDialog.dismiss();
            qrDialog = QrSetupDialog.show(activity, () -> loadData(true), this::setPanelContentBlur);
        });
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(-2, dp(34));
        qrLp.rightMargin = dp(8);
        header.addView(qrBtn, qrLp);

        TextView refreshBtn = makePill(activity.getString(R.string.discover_refresh));
        refreshBtn.setOnClickListener(v -> loadData(true));
        header.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView bodyScroll = new ScrollView(activity);
        bodyScroll.setVerticalScrollBarEnabled(false);
        bodyScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        FocusRingController.clipScrollContainers(bodyScroll);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(4), dp(12), dp(4), dp(8));

        // Section 1: Continue Watching
        TextView wnLabel = makeSectionLabel(activity.getString(R.string.discover_continue_watching));
        watchNextHeader = wnLabel;
        body.addView(wnLabel);

        watchNextPermRow = buildWatchNextPermissionBanner();
        body.addView(watchNextPermRow);

        HorizontalScrollView wnScroll = new HorizontalScrollView(activity);
        wnScroll.setHorizontalScrollBarEnabled(false);
        wnScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wnScroll.setClipChildren(false);
        wnScroll.setClipToPadding(false);
        watchNextStrip = new LinearLayout(activity);
        watchNextStrip.setOrientation(LinearLayout.HORIZONTAL);
        watchNextStrip.setClipChildren(false);
        watchNextStrip.setClipToPadding(false);
        watchNextStrip.setPadding(dp(4), dp(6), dp(4), dp(12));
        wnScroll.addView(watchNextStrip);
        body.addView(wnScroll, new LinearLayout.LayoutParams(-1, -2));

        // Section 2: Trending Picks header + filter tabs
        LinearLayout picksHead = new LinearLayout(activity);
        picksHead.setOrientation(LinearLayout.HORIZONTAL);
        picksHead.setGravity(Gravity.CENTER_VERTICAL);
        picksHead.setClipChildren(false);
        picksHead.setClipToPadding(false);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(-1, -2);
        phLp.topMargin = dp(4);
        phLp.bottomMargin = dp(6);

        TextView picksLabel = makeSectionLabel(activity.getString(R.string.discover_trending));
        picksHead.addView(picksLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        String[] tabNames = {
                activity.getString(R.string.discover_tab_all),
                activity.getString(R.string.discover_tab_movies),
                activity.getString(R.string.discover_tab_shows)
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tab = makePill(tabNames[i]);
            tab.setOnClickListener(v -> {
                activeFilter = idx;
                updateTabStyles();
                renderPicks();
            });
            filterTabs[i] = tab;
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, dp(32));
            if (i > 0) tLp.leftMargin = dp(6);
            picksHead.addView(tab, tLp);
        }
        body.addView(picksHead, phLp);
        updateTabStyles();

        picksEmptyView = new TextView(activity);
        picksEmptyView.setTextColor(0xB8DCE8F8);
        picksEmptyView.setTextSize(14f);
        picksEmptyView.setPadding(dp(4), dp(12), dp(4), dp(12));
        picksEmptyView.setVisibility(View.GONE);
        body.addView(picksEmptyView);

        HorizontalScrollView picksScroll = new HorizontalScrollView(activity);
        picksScroll.setHorizontalScrollBarEnabled(false);
        picksScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        picksScroll.setClipChildren(false);
        picksScroll.setClipToPadding(false);
        picksStrip = new LinearLayout(activity);
        picksStrip.setOrientation(LinearLayout.HORIZONTAL);
        picksStrip.setClipChildren(false);
        picksStrip.setClipToPadding(false);
        picksStrip.setPadding(dp(4), dp(6), dp(4), dp(12));
        picksScroll.addView(picksStrip);
        body.addView(picksScroll, new LinearLayout.LayoutParams(-1, -2));

        bodyScroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(6);
        panel.addView(bodyScroll, scrollLp);
    }

    private View buildWatchNextPermissionBanner() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14f * density);
        bg.setColor(0x20FFFFFF);
        bg.setStroke(Math.max(1, dp(1)), 0x35FFFFFF);
        row.setBackground(bg);

        TextView msg = new TextView(activity);
        msg.setText(R.string.discover_grant_watchnext_msg);
        msg.setTextColor(0xD8EEF4FF);
        msg.setTextSize(13.5f);
        row.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView btn = makePill(activity.getString(R.string.discover_grant_adb_btn));
        btn.setOnClickListener(v -> {
            btn.setEnabled(false);
            Toast.makeText(activity, R.string.adb_connecting, Toast.LENGTH_SHORT).show();
            IO.execute(() -> {
                String err = null;
                try {
                    LocalAdbClient.grantTvPermissions(activity, () -> main.post(() ->
                            Toast.makeText(activity, R.string.adb_accept_prompt, Toast.LENGTH_LONG).show()));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fErr = err;
                main.post(() -> {
                    btn.setEnabled(true);
                    if (fErr == null) {
                        Toast.makeText(activity, R.string.discover_grant_success, Toast.LENGTH_SHORT).show();
                        loadData(false);
                    } else {
                        Toast.makeText(activity, fErr, Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, dp(34));
        bLp.leftMargin = dp(12);
        row.addView(btn, bLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setVisibility(View.GONE);
        return row;
    }

    void showInOverlay(FrameLayout host, boolean smokedGlass) {
        if (closed) return;
        this.embeddedHost = host;
        ExpressiveGlass.setPanelSmoked(panel, smokedGlass);
        if (panelStage.getParent() instanceof ViewGroup) {
            ((ViewGroup) panelStage.getParent()).removeView(panelStage);
        }
        panelStage.setAlpha(0f);
        if (!reducedMotion) {
            panelStage.setScaleX(0.95f);
            panelStage.setScaleY(0.95f);
            panelStage.setTranslationY(dp(42));
        }
        host.addView(panelStage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        panelStage.post(() -> panelStage.postOnAnimation(() -> {
            if (closed || dismissing) return;
            if (activity instanceof OverlayActivity) {
                ((OverlayActivity) activity).notifyContentRevealStarted();
            }
            Motion.revealModal(panelStage, density, reducedMotion);
        }));
        loadData(false);
    }

    void setSmoked(boolean smoked) {
        ExpressiveGlass.setPanelSmoked(panel, smoked);
    }

    boolean isShowing() {
        return !closed && embeddedHost != null && panelStage.getParent() != null;
    }

    void animateClose() {
        if (closed || dismissing) return;
        dismissing = true;
        if (reducedMotion) {
            close();
            return;
        }
        Motion.dismissModal(panelStage, density, false, this::close);
    }

    private void setPanelContentBlur(boolean blurred) {
        ExpressiveGlass.animatePanelContentBlur(panel, focusRing != null ? focusRing.glassView() : null,
                density, blurred, reducedMotion);
    }

    void close() {
        if (closed) return;
        closed = true;
        dismissing = false;
        if (qrDialog != null) {
            try { if (qrDialog.isShowing()) qrDialog.dismiss(); } catch (RuntimeException ignored) { }
            qrDialog = null;
        }
        ExpressiveGlass.clearPanelContentBlurImmediate(panel, focusRing != null ? focusRing.glassView() : null);
        panelStage.animate().cancel();
        if (panelStage.getParent() instanceof ViewGroup) {
            ((ViewGroup) panelStage.getParent()).removeView(panelStage);
        }
        onDismissed.run();
    }

    private void loadData(boolean forceRefresh) {
        boolean hasWnPerm = WatchNextRepository.hasPermission(activity);
        watchNextPermRow.setVisibility(FeaturePrefs.isWatchNextEnabled(activity) && !hasWnPerm
                ? View.VISIBLE : View.GONE);

        IO.execute(() -> {
            List<WatchNextRepository.Item> wnItems = WatchNextRepository.load(activity);
            List<TrendingPicksRepository.Pick> picks = TrendingPicksRepository.load(activity, forceRefresh);
            main.post(() -> {
                if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                renderWatchNext(wnItems);
                allPicks = picks;
                renderPicks();
                focusFirstInteractive();
            });
        });
    }

    private void renderWatchNext(List<WatchNextRepository.Item> items) {
        watchNextStrip.removeAllViews();
        if (items.isEmpty()) {
            watchNextStrip.setVisibility(View.GONE);
            if (watchNextPermRow.getVisibility() != View.VISIBLE) {
                watchNextHeader.setVisibility(View.GONE);
            }
            return;
        }
        watchNextHeader.setVisibility(View.VISIBLE);
        watchNextStrip.setVisibility(View.VISIBLE);
        for (WatchNextRepository.Item item : items) {
            View card = buildWatchNextCard(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(208), dp(148));
            lp.rightMargin = dp(12);
            watchNextStrip.addView(card, lp);
        }
    }

    private View buildWatchNextCard(WatchNextRepository.Item item) {
        FrameLayout card = new FrameLayout(activity);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16f * density);
        bg.setColor(0x28182230);
        bg.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        card.setBackground(bg);

        ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        if (item.artworkUri != null) {
            loadUriAsync(item.artworkUri, art);
        }

        // Bottom dark scrim for text legibility
        View scrim = new View(activity);
        GradientDrawable sc = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC06090F});
        scrim.setBackground(sc);
        card.addView(scrim, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(10), 0, dp(10), dp(8));

        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title);

        String sub = item.episode != null ? (item.episode + " · " + item.appName) : item.appName;
        TextView subView = new TextView(activity);
        subView.setText(sub);
        subView.setTextColor(0xC0DCE6F5);
        subView.setTextSize(11.5f);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(subView);

        float prog = item.progress();
        if (prog > 0f) {
            FrameLayout barTrack = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(2f * density);
            trackBg.setColor(0x44FFFFFF);
            barTrack.setBackground(trackBg);
            View barFill = new View(activity);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setCornerRadius(2f * density);
            fillBg.setColor(0xFF60A5FA);
            barFill.setBackground(fillBg);
            barTrack.addView(barFill, new FrameLayout.LayoutParams(Math.max(dp(6), Math.round(dp(188) * prog)), -1));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(4));
            bLp.topMargin = dp(5);
            meta.addView(barTrack, bLp);
        }

        card.addView(meta, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        Motion.attachPillFocus(card, 1.04f, 1f, density, reducedMotion);
        FocusRingController.configure(card, 1.04f, 1f, 16f, 0);

        card.setOnClickListener(v -> {
            Intent launch = WatchNextRepository.buildLaunchIntent(activity, item);
            if (launch != null) {
                try {
                    activity.startActivity(launch);
                    close();
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.setOnLongClickListener(v -> {
            FeaturePrefs.hideWatchNextItem(activity, item.hideKey());
            Toast.makeText(activity, R.string.discover_hidden_toast, Toast.LENGTH_SHORT).show();
            loadData(false);
            return true;
        });
        return card;
    }

    private void renderPicks() {
        picksStrip.removeAllViews();
        List<TrendingPicksRepository.Pick> filtered = new ArrayList<>();
        for (TrendingPicksRepository.Pick p : allPicks) {
            if (activeFilter == 1 && !"movie".equals(p.kind)) continue;
            if (activeFilter == 2 && !"tv".equals(p.kind)) continue;
            filtered.add(p);
        }
        if (filtered.isEmpty()) {
            picksStrip.setVisibility(View.GONE);
            picksEmptyView.setVisibility(View.VISIBLE);
            picksEmptyView.setText(FeaturePrefs.tmdbKey(activity).isEmpty() && !PlexClient.isConfigured(activity)
                    ? R.string.discover_empty_no_key
                    : R.string.discover_empty_no_matches);
            return;
        }
        picksEmptyView.setVisibility(View.GONE);
        picksStrip.setVisibility(View.VISIBLE);
        for (TrendingPicksRepository.Pick p : filtered) {
            View card = buildPickCard(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(166), dp(224));
            lp.rightMargin = dp(12);
            picksStrip.addView(card, lp);
        }
    }

    private View buildPickCard(TrendingPicksRepository.Pick pick) {
        FrameLayout card = new FrameLayout(activity);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16f * density);
        bg.setColor(0x2C182230);
        bg.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        card.setBackground(bg);

        ImageView poster = new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        String url = !pick.posterUrl.isEmpty() ? pick.posterUrl : pick.backdropUrl;
        if (!url.isEmpty()) {
            IO.execute(() -> {
                Bitmap bmp = TrendingPicksRepository.loadCachedBitmap(activity, url, dp(220));
                if (bmp != null) {
                    main.post(() -> {
                        if (!closed) poster.setImageBitmap(bmp);
                    });
                }
            });
        }

        // Top service badge pill
        if (!pick.serviceLabel.isEmpty()) {
            TextView badge = new TextView(activity);
            badge.setText(pick.serviceLabel);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(10.5f);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable bBg = new GradientDrawable();
            bBg.setCornerRadius(10f * density);
            bBg.setColor(0xB80E1522);
            badge.setBackground(bBg);
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            bLp.setMargins(dp(8), dp(8), dp(8), 0);
            card.addView(badge, bLp);
        }

        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xDD06090F}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM));

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(10), 0, dp(10), dp(9));

        TextView title = new TextView(activity);
        title.setText(pick.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title);

        if (!pick.subtitle.isEmpty()) {
            TextView sub = new TextView(activity);
            sub.setText(pick.subtitle);
            sub.setTextColor(0xC0DCE6F5);
            sub.setTextSize(11f);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            meta.addView(sub);
        }
        card.addView(meta, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        Motion.attachPillFocus(card, 1.045f, 1f, density, reducedMotion);
        FocusRingController.configure(card, 1.045f, 1f, 16f, 0);
        card.setOnClickListener(v -> {
            Intent launch = TrendingPicksRepository.buildLaunchIntent(activity, pick);
            if (launch != null) {
                try {
                    activity.startActivity(launch);
                    close();
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
        });
        return card;
    }

    private void loadUriAsync(Uri uri, ImageView target) {
        IO.execute(() -> {
            Bitmap bmp = null;
            try {
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    bmp = TrendingPicksRepository.loadCachedBitmap(activity, uri.toString(), dp(260));
                } else {
                    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                        if (in != null) bmp = BitmapFactory.decodeStream(in);
                    }
                }
            } catch (Exception ignored) {
            }
            if (bmp != null) {
                final Bitmap ready = bmp;
                main.post(() -> {
                    if (!closed) target.setImageBitmap(ready);
                });
            }
        });
    }

    private void focusFirstInteractive() {
        if (watchNextStrip.getChildCount() > 0) {
            watchNextStrip.getChildAt(0).requestFocus();
        } else if (picksStrip.getChildCount() > 0) {
            picksStrip.getChildAt(0).requestFocus();
        } else if (filterTabs[0] != null) {
            filterTabs[0].requestFocus();
        }
    }

    private void updateTabStyles() {
        for (int i = 0; i < filterTabs.length; i++) {
            TextView tab = filterTabs[i];
            if (tab == null) continue;
            boolean sel = i == activeFilter;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(16f * density);
            bg.setColor(sel ? 0x4C60A5FA : 0x22FFFFFF);
            bg.setStroke(Math.max(1, dp(1)), sel ? 0x9993C5FD : 0x38FFFFFF);
            tab.setBackground(bg);
            tab.setTextColor(sel ? Color.WHITE : 0xCCDAE6F6);
        }
    }

    private TextView makeSectionLabel(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xD8E8F0FC);
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(4), dp(2), dp(4), dp(4));
        return tv;
    }

    private TextView makePill(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setPadding(dp(14), dp(4), dp(14), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16f * density);
        bg.setColor(0x28FFFFFF);
        bg.setStroke(Math.max(1, dp(1)), 0x45FFFFFF);
        tv.setBackground(bg);
        Motion.attachPillFocus(tv, 1.04f, 1f, density, reducedMotion);
        FocusRingController.configure(tv, 1.04f, 1f, 16f, 0);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
