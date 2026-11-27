package com.lionlev.tv.capacity;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.BidiFormatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Frosted-glass Capacity Stats modal with live RAM and storage visual usage meters. */
public final class StatsActivity extends Activity {
    private static final int ACCENT_CYAN = 0xFF38BDF8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private volatile Handler background;
    private GlassKit.Modal modal;
    private TextView ramPrimary, ramPercent, ramSecondary;
    private View ramFill, ramRest;
    private TextView storagePrimary, storagePercent;
    private View storageFill, storageRest;

    private static final class Snapshot {
        final String ramAvailLine;
        final String ramUsedLine;
        final float ramUsedRatio;
        final String storageLine;
        final float storageUsedRatio;

        Snapshot(String ramAvailLine, String ramUsedLine, float ramUsedRatio,
                 String storageLine, float storageUsedRatio) {
            this.ramAvailLine = ramAvailLine;
            this.ramUsedLine = ramUsedLine;
            this.ramUsedRatio = ramUsedRatio;
            this.storageLine = storageLine;
            this.storageUsedRatio = storageUsedRatio;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        modal = GlassKit.installModal(this, 620, 460, true, 0x16324A);
        float d = modal.density;
        LinearLayout panel = modal.panel;

        TextView title = GlassKit.makeTitle(this, getString(R.string.app_name));
        title.setPadding(dp(4), dp(2), dp(4), 0);
        panel.addView(title);
        panel.addView(GlassKit.makeDivider(this, 12, 12));

        // RAM metric card
        LinearLayout ramCard = makeMetricCard(d);
        LinearLayout ramTop = new LinearLayout(this);
        ramTop.setOrientation(LinearLayout.HORIZONTAL);
        ramTop.setGravity(Gravity.CENTER_VERTICAL);
        ramPrimary = makeCardPrimary(getString(R.string.loading));
        ramTop.addView(ramPrimary, new LinearLayout.LayoutParams(0, -2, 1f));
        ramPercent = makePercentBadge();
        ramTop.addView(ramPercent, new LinearLayout.LayoutParams(-2, -2));
        ramCard.addView(ramTop, new LinearLayout.LayoutParams(-1, -2));

        View[] ramViews = addProgressTrack(ramCard, d);
        ramFill = ramViews[0];
        ramRest = ramViews[1];

        ramSecondary = new TextView(this);
        ramSecondary.setTextSize(13.5f);
        ramSecondary.setTextColor(0xA6FFFFFF);
        ramSecondary.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        ramSecondary.setPadding(0, dp(8), 0, 0);
        ramSecondary.setVisibility(View.GONE);
        ramCard.addView(ramSecondary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(10);
        panel.addView(ramCard, cardLp);

        // Storage metric card
        LinearLayout storageCard = makeMetricCard(d);
        LinearLayout storageTop = new LinearLayout(this);
        storageTop.setOrientation(LinearLayout.HORIZONTAL);
        storageTop.setGravity(Gravity.CENTER_VERTICAL);
        storagePrimary = makeCardPrimary(getString(R.string.loading));
        storageTop.addView(storagePrimary, new LinearLayout.LayoutParams(0, -2, 1f));
        storagePercent = makePercentBadge();
        storageTop.addView(storagePercent, new LinearLayout.LayoutParams(-2, -2));
        storageCard.addView(storageTop, new LinearLayout.LayoutParams(-1, -2));

        View[] storageViews = addProgressTrack(storageCard, d);
        storageFill = storageViews[0];
        storageRest = storageViews[1];
        panel.addView(storageCard, new LinearLayout.LayoutParams(-1, -2));

        TextView note = GlassKit.makeHint(this, getString(R.string.note));
        note.setPadding(dp(4), dp(12), dp(4), dp(4));
        note.setLineSpacing(dp(2), 1f);
        panel.addView(note, new LinearLayout.LayoutParams(-1, -2));

        panel.addView(GlassKit.makeDivider(this, 10, 10));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setClipChildren(false);
        footer.setClipToPadding(false);
        footer.setPadding(dp(4), dp(2), dp(4), dp(4));

        TextView back = GlassKit.makePillButton(this, getString(R.string.back), ACCENT_CYAN);
        back.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            modal.dismiss();
        });
        footer.addView(back, new LinearLayout.LayoutParams(-2, dp(42)));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        modal.reveal();
        back.requestFocus();
    }

    private LinearLayout makeMetricCard(float d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setBackground(GlassKit.makeStaticCardBg(d, 16f));
        return card;
    }

    private TextView makeCardPrimary(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16.5f);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    private TextView makePercentBadge() {
        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setTextColor(0xCCFFFFFF);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(10), 0, 0, 0);
        return tv;
    }

    private View[] addProgressTrack(LinearLayout parent, float d) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(GlassKit.isLightGlass() ? 0x240F172A : 0x24FFFFFF);
        trackBg.setCornerRadius(5f * d);
        track.setBackground(trackBg);

        View fill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFF4ADE80);
        fillBg.setCornerRadius(5f * d);
        fill.setBackground(fillBg);
        View rest = new View(this);
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        track.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(8));
        lp.topMargin = dp(10);
        parent.addView(track, lp);
        return new View[]{fill, rest};
    }

    private void updateMeter(View fill, View rest, TextView percentLabel, float ratio) {
        if (ratio < 0f) {
            percentLabel.setText("");
            return;
        }
        float clamped = Math.max(0.02f, Math.min(1f, ratio));
        int pct = Math.round(clamped * 100f);
        percentLabel.setText(pct + "%");

        int startColor, endColor, textColor;
        if (clamped >= 0.85f) {
            startColor = 0xFFFB7185;
            endColor = 0xFFF87171;
            textColor = 0xFFFCA5A5;
        } else if (clamped >= 0.65f) {
            startColor = 0xFFFBBF24;
            endColor = 0xFFF59E0B;
            textColor = 0xFFFDE68A;
        } else {
            startColor = 0xFF34D399;
            endColor = 0xFF38BDF8;
            textColor = 0xFFA7F3D0;
        }
        GlassKit.markPreserveTextColor(percentLabel);
        percentLabel.setTextColor(GlassKit.adaptiveAccentTextColor(textColor));

        float d = modal.density;
        GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{startColor, endColor});
        bar.setCornerRadius(5f * d);
        fill.setBackground(bar);

        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = clamped;
        ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight = Math.max(0f, 1f - clamped);
        fill.requestLayout();
    }

    private int dp(float v) {
        return GlassKit.dp(this, v);
    }

    private String number(String value) {
        return BidiFormatter.getInstance().unicodeWrap(value, android.text.TextDirectionHeuristics.LTR);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
        worker = new HandlerThread("addon-stats");
        worker.start();
        Handler current = new Handler(worker.getLooper());
        background = current;
        current.post(new Runnable() {
            @Override
            public void run() {
                if (background != current) return;
                Snapshot snap = sample();
                ui.post(() -> {
                    if (background == current) applySnapshot(snap);
                });
                if (background == current) current.postDelayed(this, 10000);
            }
        });
    }

    @Override
    public void onPause() {
        background = null;
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
        ui.removeCallbacksAndMessages(null);
        if (modal != null) modal.onPause();
        super.onPause();
    }

    private void applySnapshot(Snapshot s) {
        ramPrimary.setText(s.ramAvailLine != null ? s.ramAvailLine : getString(R.string.unavailable));
        if (s.ramUsedLine != null && !s.ramUsedLine.isEmpty()) {
            ramSecondary.setText(s.ramUsedLine);
            ramSecondary.setVisibility(View.VISIBLE);
        } else {
            ramSecondary.setVisibility(View.GONE);
        }
        updateMeter(ramFill, ramRest, ramPercent, s.ramUsedRatio);

        storagePrimary.setText(s.storageLine != null ? s.storageLine : getString(R.string.unavailable));
        updateMeter(storageFill, storageRest, storagePercent, s.storageUsedRatio);
    }

    private Snapshot sample() {
        String ramAvail = null;
        String ramUsed = null;
        float ramRatio = -1f;
        try (java.io.FileReader reader = new java.io.FileReader("/proc/meminfo")) {
            long[] memory = MemoryCapacity.readMb(reader);
            if (memory[0] >= 0) {
                ramAvail = getString(R.string.memory, number(StatsCapacity.memory(memory[0], memory[1]) + " MB"));
                if (memory[1] > 0) {
                    long used = Math.max(0, memory[1] - memory[0]);
                    ramUsed = getString(R.string.memory_used, number(StatsCapacity.memory(used, memory[1]) + " MB"));
                    ramRatio = used / (float) memory[1];
                }
            }
        } catch (java.io.IOException ignored) { }

        java.io.File data = Environment.getDataDirectory();
        long free = data.getFreeSpace();
        long total = data.getTotalSpace();
        String storage = getString(R.string.storage, number(StatsCapacity.storage(free, total)));
        float storageRatio = total > 0 ? Math.max(0L, total - free) / (float) total : -1f;
        return new Snapshot(ramAvail, ramUsed, ramRatio, storage, storageRatio);
    }
}
