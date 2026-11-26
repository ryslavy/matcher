package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating tvOS-style Liquid Glass Picture-in-Picture overlay for Home Assistant {@code camera.*}
 * entities hosted inside {@link OverlayActivity} ({@code MODE_CAMERA}).
 * Polls {@code /api/camera_proxy/<entity_id>} on a background daemon thread (~1.5 FPS) without
 * dimming or interrupting the TV video underneath:
 * - OK / Enter toggles smoothly between bottom-right PiP (448x286 dp) and centered expanded view (840x510 dp).
 * - Left / Right switches between configured Home Assistant cameras when multiple cameras exist.
 * - Back dismisses cleanly and immediately releases network & bitmap resources.
 */
final class HaCameraOverlay {

    private static final long REFRESH_DELAY_MS = 650L;
    private static final long RETRY_DELAY_MS = 2200L;

    private final Activity activity;
    private final float density;
    private final boolean reducedMotion;
    private final Runnable onDismissed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService poller = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvhome-ha-cam");
        t.setDaemon(true);
        return t;
    });
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final List<HomeAssistantClient.Control> cameras = new ArrayList<>();
    private int cameraIndex;
    private boolean expanded;
    private volatile boolean closed;
    private boolean dismissing;
    private int generation;

    private final LinearLayout panel;
    private final View liveDot;
    private final GradientDrawable liveDotDrawable;
    private final TextView titleView;
    private final TextView counterPill;
    private final TextView timeView;
    private final FrameLayout videoFrame;
    private final ImageView imageView;
    private final TextView statusOverlay;
    private final TextView hintView;

    @Nullable private FrameLayout hostRef;
    @Nullable private Bitmap activeBitmap;
    @Nullable private Bitmap retiredBitmap;

    HaCameraOverlay(Activity activity, @Nullable String initialEntityId, boolean reducedMotion, Runnable onDismissed) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.reducedMotion = reducedMotion;
        this.onDismissed = onDismissed;

        cameras.addAll(HomeAssistantClient.cameras(activity));
        if (initialEntityId != null && !initialEntityId.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < cameras.size(); i++) {
                if (cameras.get(i).entityId.equalsIgnoreCase(initialEntityId)) {
                    cameraIndex = i;
                    found = true;
                    break;
                }
            }
            if (!found && HomeAssistantClient.isAllowedEntityId(initialEntityId)
                    && initialEntityId.startsWith("camera.")) {
                cameras.add(0, new HomeAssistantClient.Control(initialEntityId));
                cameraIndex = 0;
            }
        }

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setFocusable(true);
        panel.setFocusableInTouchMode(true);
        panel.setOnClickListener(v -> toggleExpanded());
        ExpressiveGlass.applyPanelSurface(panel, density, 22f, true, false);

        // Header row: LIVE dot + camera icon + name + multi-cam pill + timestamp
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        liveDot = new View(activity);
        liveDotDrawable = new GradientDrawable();
        liveDotDrawable.setShape(GradientDrawable.OVAL);
        liveDotDrawable.setColor(0xFFFBBF24);
        liveDot.setBackground(liveDotDrawable);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        header.addView(liveDot, dotLp);

        ImageView camIcon = new ImageView(activity);
        camIcon.setImageResource(R.drawable.ic_ha_camera);
        camIcon.setColorFilter(0xE6FFFFFF);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconLp.rightMargin = dp(6);
        header.addView(camIcon, iconLp);

        titleView = new TextView(activity);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14f);
        titleView.setSingleLine(true);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));

        counterPill = new TextView(activity);
        counterPill.setTextColor(0xE0DCE8F8);
        counterPill.setTextSize(11.5f);
        counterPill.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(10f * density);
        pillBg.setColor(0x28FFFFFF);
        counterPill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.rightMargin = dp(8);
        header.addView(counterPill, pillLp);

        timeView = new TextView(activity);
        timeView.setTextColor(0xB8DCE8F8);
        timeView.setTextSize(12f);
        timeView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(timeView, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(8);
        panel.addView(header, headLp);

        // Rounded 16:9 live snapshot container
        videoFrame = new FrameLayout(activity);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setCornerRadius(14f * density);
        frameBg.setColor(0xD0121822);
        videoFrame.setBackground(frameBg);
        videoFrame.setClipToOutline(true);
        videoFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14f * density);
            }
        });

        imageView = new ImageView(activity);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setAlpha(0f);
        videoFrame.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        statusOverlay = new TextView(activity);
        statusOverlay.setTextColor(0xCCDCE8F8);
        statusOverlay.setTextSize(13f);
        statusOverlay.setGravity(Gravity.CENTER);
        statusOverlay.setText(R.string.ha_camera_connecting);
        videoFrame.addView(statusOverlay, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        panel.addView(videoFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        hintView = new TextView(activity);
        hintView.setTextColor(0x99DCE8F8);
        hintView.setTextSize(11f);
        hintView.setGravity(Gravity.CENTER_HORIZONTAL);
        hintView.setSingleLine(true);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(7);
        panel.addView(hintView, hintLp);

        updateHeaderUi();
    }

    void showInOverlay(FrameLayout host) {
        if (closed) return;
        this.hostRef = host;
        if (panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        panel.setAlpha(0f);
        if (!reducedMotion) {
            panel.setScaleX(0.92f);
            panel.setScaleY(0.92f);
            panel.setTranslationY(dp(24));
        }
        host.addView(panel, buildLayoutParams());
        panel.requestFocus();
        panel.post(() -> panel.postOnAnimation(() -> {
            if (closed || dismissing) return;
            Motion.revealModal(panel, density, reducedMotion);
        }));
        startPolling();
    }

    boolean isDismissing() {
        return dismissing;
    }

    boolean handleKeyEvent(KeyEvent event) {
        if (closed || dismissing) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                toggleExpanded();
            }
            return true;
        }
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                && cameras.size() > 1) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                int delta = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                cameraIndex = (cameraIndex + delta + cameras.size()) % cameras.size();
                switchCamera();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                animateClose();
            }
            return true;
        }
        return false;
    }

    private void toggleExpanded() {
        if (closed || dismissing || hostRef == null) return;
        expanded = !expanded;
        updateHeaderUi();
        panel.setLayoutParams(buildLayoutParams());
        if (!reducedMotion) {
            panel.setScaleX(0.96f);
            panel.setScaleY(0.96f);
            panel.animate().cancel();
            panel.animate().scaleX(1f).scaleY(1f)
                    .setDuration(220L).setInterpolator(Motion.easeOutExpo())
                    .withEndAction(() -> ExpressiveGlass.invalidateLiquidPanels(panel))
                    .start();
        } else {
            panel.post(() -> ExpressiveGlass.invalidateLiquidPanels(panel));
        }
    }

    private void switchCamera() {
        generation++;
        liveDotDrawable.setColor(0xFFFBBF24);
        statusOverlay.setText(R.string.ha_camera_connecting);
        statusOverlay.setVisibility(View.VISIBLE);
        imageView.setAlpha(0.25f);
        updateHeaderUi();
        scheduleFetch(0L, generation);
    }

    private FrameLayout.LayoutParams buildLayoutParams() {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int w;
        int h;
        FrameLayout.LayoutParams lp;
        if (expanded) {
            w = Math.min(dp(840), dm.widthPixels - dp(80));
            h = Math.min(dp(510), dm.heightPixels - dp(64));
            lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        } else {
            w = Math.min(dp(448), dm.widthPixels - dp(64));
            h = Math.min(dp(286), dm.heightPixels - dp(64));
            lp = new FrameLayout.LayoutParams(w, h, Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = dp(28);
            lp.bottomMargin = dp(28);
        }
        return lp;
    }

    private void updateHeaderUi() {
        HomeAssistantClient.Control cur = currentCamera();
        titleView.setText(cur != null ? cur.name : activity.getString(R.string.ha_camera_title));
        if (cameras.size() > 1) {
            counterPill.setText((cameraIndex + 1) + " / " + cameras.size());
            counterPill.setVisibility(View.VISIBLE);
        } else {
            counterPill.setVisibility(View.GONE);
        }
        hintView.setText(cameras.size() > 1
                ? activity.getString(expanded ? R.string.ha_camera_hint_expanded_multi : R.string.ha_camera_hint_pip_multi)
                : activity.getString(expanded ? R.string.ha_camera_hint_expanded : R.string.ha_camera_hint_pip));
    }

    @Nullable
    private HomeAssistantClient.Control currentCamera() {
        if (cameras.isEmpty() || cameraIndex < 0 || cameraIndex >= cameras.size()) return null;
        return cameras.get(cameraIndex);
    }

    private void startPolling() {
        generation++;
        scheduleFetch(0L, generation);
    }

    private void scheduleFetch(long delayMs, int gen) {
        if (closed) return;
        main.postDelayed(() -> {
            if (closed || gen != generation) return;
            HomeAssistantClient.Control cam = currentCamera();
            if (cam == null) {
                liveDotDrawable.setColor(0xFFEF4444);
                statusOverlay.setText(R.string.ha_camera_unavailable);
                statusOverlay.setVisibility(View.VISIBLE);
                return;
            }
            final String entityId = cam.entityId;
            try {
                poller.execute(() -> {
                    if (closed || gen != generation) return;
                    Bitmap frame = HomeAssistantClient.fetchCameraSnapshot(activity, entityId);
                    main.post(() -> onFrameResult(frame, gen));
                });
            } catch (RuntimeException ignored) {
            }
        }, delayMs);
    }

    private void onFrameResult(@Nullable Bitmap frame, int gen) {
        if (closed || gen != generation) {
            if (frame != null && !frame.isRecycled()) frame.recycle();
            return;
        }
        if (frame != null) {
            // 1-frame delayed recycle: never recycle a bitmap that RenderThread might still composite.
            Bitmap toFree = retiredBitmap;
            retiredBitmap = activeBitmap;
            activeBitmap = frame;
            imageView.setImageBitmap(frame);
            if (toFree != null && toFree != frame && !toFree.isRecycled()) {
                toFree.recycle();
            }
            if (imageView.getAlpha() < 0.99f) {
                imageView.animate().alpha(1f).setDuration(reducedMotion ? 80L : 220L).start();
            }
            statusOverlay.setVisibility(View.GONE);
            liveDotDrawable.setColor(0xFF22C55E);
            timeView.setText(clockFmt.format(new Date()));
            scheduleFetch(REFRESH_DELAY_MS, gen);
        } else {
            liveDotDrawable.setColor(0xFFEF4444);
            if (activeBitmap == null) {
                statusOverlay.setText(R.string.ha_camera_unavailable);
                statusOverlay.setVisibility(View.VISIBLE);
            }
            scheduleFetch(RETRY_DELAY_MS, gen);
        }
    }

    void animateClose() {
        if (closed || dismissing) return;
        dismissing = true;
        if (reducedMotion) {
            close();
            return;
        }
        Motion.dismissModal(panel, density, false, this::close);
    }

    void close() {
        if (closed) return;
        closed = true;
        dismissing = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        poller.shutdownNow();
        panel.animate().cancel();
        imageView.animate().cancel();
        imageView.setImageDrawable(null);
        if (retiredBitmap != null && !retiredBitmap.isRecycled()) retiredBitmap.recycle();
        retiredBitmap = null;
        if (activeBitmap != null && !activeBitmap.isRecycled()) activeBitmap.recycle();
        activeBitmap = null;
        if (panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        onDismissed.run();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
