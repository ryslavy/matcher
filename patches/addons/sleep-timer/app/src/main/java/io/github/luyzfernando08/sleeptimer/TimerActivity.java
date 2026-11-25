package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Frosted-glass Sleep Timer modal: pick a duration, cancel, or open the weekly schedule. */
public class TimerActivity extends Activity {
    private GlassKit.Modal modal;
    private TextView state, first, cancel, scheduleBadge;
    private TextView[] durationPills;
    private boolean scheduleOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modal = GlassKit.installModal(this, 760, 440, true, 0x261E45);
        float d = modal.density;
        LinearLayout panel = modal.panel;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), 0);

        TextView title = GlassKit.makeTitle(this, getString(R.string.app_name));
        header.addView(title);

        state = Ui.text(this, 14.5f, 0xFFC4B5FD);
        state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        state.setPadding(0, Ui.dp(this, 8), 0, 0);
        header.addView(state);

        scheduleBadge = Ui.text(this, 12.5f, 0x99FFFFFF);
        scheduleBadge.setPadding(0, Ui.dp(this, 2), 0, 0);
        scheduleBadge.setVisibility(View.GONE);
        header.addView(scheduleBadge);

        panel.addView(header);
        panel.addView(GlassKit.makeDivider(this, 12, 10));

        // Duration capsules in a horizontal strip with room for the living focus ring.
        HorizontalScrollView choices = new HorizontalScrollView(this);
        choices.setHorizontalScrollBarEnabled(false);
        choices.setOverScrollMode(View.OVER_SCROLL_NEVER);
        choices.setHorizontalFadingEdgeEnabled(true);
        choices.setFadingEdgeLength(Ui.dp(this, 18));
        GlassKit.clipScrollContainers(choices);

        LinearLayout lengths = new LinearLayout(this);
        lengths.setOrientation(LinearLayout.HORIZONTAL);
        lengths.setGravity(Gravity.CENTER_VERTICAL);
        lengths.setClipChildren(false);
        lengths.setClipToPadding(false);
        lengths.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 10));

        durationPills = new TextView[Timer.MINUTES.length];
        for (int i = 0; i < Timer.MINUTES.length; i++) {
            final int minutes = Timer.MINUTES[i];
            TextView pill = Ui.text(this, 15.5f, Color.WHITE);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
            pill.setText(getString(R.string.in_minutes, minutes));
            pill.setFocusable(true);
            pill.setFocusableInTouchMode(true);
            pill.setClickable(true);
            pill.setBackground(GlassKit.makeChoiceBg(d, 22f, Ui.ACCENT_LAVENDER));
            GlassKit.attachPillFocus(pill, 1.06f, 1.5f, d, GlassKit.RADIUS_CAPSULE, Ui.ACCENT_LAVENDER);
            pill.setOnClickListener(v -> {
                GlassKit.pulseClick(v, 1.06f);
                Timer.start(this, minutes);
                modal.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
            if (i < Timer.MINUTES.length - 1) lp.setMarginEnd(Ui.dp(this, 10));
            lengths.addView(pill, lp);
            durationPills[i] = pill;
            if (first == null) first = pill;
        }
        choices.addView(lengths);
        panel.addView(choices, new LinearLayout.LayoutParams(-1, -2));

        panel.addView(GlassKit.makeDivider(this, 6, 6));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 6));

        cancel = Ui.row(actions, 0, 14f, Ui.ACCENT_CORAL);
        cancel.setText(R.string.cancel);
        cancel.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.015f);
            Timer.cancel(this);
            refresh();
            modal.refreshFocus();
        });

        TextView schedule = Ui.row(actions, 0, 14f, Ui.ACCENT_LAVENDER);
        schedule.setText(R.string.schedule_row);
        schedule.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.015f);
            if (!scheduleOpen) {
                scheduleOpen = true;
                modal.pushBlur();
                modal.panelStage.animate().cancel();
                modal.panelStage.animate().alpha(0f).setDuration(140L).start();
            }
            Intent schedIntent = new Intent(this, ScheduleActivity.class);
            GlassKit.forwardBackdropExtras(this, schedIntent);
            schedIntent.putExtra(GlassKit.EXTRA_INHERIT_BACKDROP, true);
            startActivity(schedIntent);
            @SuppressWarnings("deprecation")
            Runnable noAnim = () -> overridePendingTransition(0, 0);
            noAnim.run();
        });

        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        modal.reveal();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scheduleOpen && modal != null) {
            scheduleOpen = false;
            modal.popBlur();
            modal.panelStage.animate().cancel();
            modal.panelStage.animate().alpha(1f).setDuration(180L).start();
        }
        if (modal != null) modal.onResume();
        refresh();
        View initial = Timer.end(this) != 0 ? cancel : first;
        if (initial != null) initial.requestFocus();
        if (modal != null) modal.refreshFocus();
    }

    @Override
    protected void onPause() {
        if (modal != null) modal.onPause();
        super.onPause();
    }

    private void refresh() {
        if (state == null) return;
        long end = Timer.end(this);
        if (!Timer.canSleep()) {
            state.setText(R.string.no_service);
            state.setTextColor(0xFFFCA5A5);
        } else if (end == 0) {
            state.setText(R.string.state_off);
            state.setTextColor(0xA6FFFFFF);
        } else {
            state.setText(getString(R.string.state_on, DateFormat.getTimeFormat(this).format(end)));
            state.setTextColor(0xFFC4B5FD);
        }

        long leftMinutes = end > 0 ? (end - System.currentTimeMillis() + 59_999) / 60_000 : -1;
        int matched = -1;
        if (leftMinutes > 0) {
            for (int i = 0; i < Timer.MINUTES.length; i++) {
                if (Timer.MINUTES[i] >= leftMinutes) {
                    matched = i;
                    break;
                }
            }
            if (matched < 0) matched = Timer.MINUTES.length - 1;
        }
        if (durationPills != null) {
            for (int i = 0; i < durationPills.length; i++) {
                durationPills[i].setSelected(i == matched);
            }
        }
        if (cancel != null) {
            cancel.setAlpha(end != 0 ? 1f : 0.62f);
            cancel.setSelected(end != 0);
            GlassKit.configureFocus(cancel, 1.015f, 0f, 14f, end != 0 ? Ui.ACCENT_CORAL : 0);
        }

        String bed = Schedule.describe(this);
        if (scheduleBadge != null) {
            if (!bed.isEmpty()) {
                scheduleBadge.setText(bed);
                scheduleBadge.setVisibility(View.VISIBLE);
                setTitle(getString(R.string.app_name) + " · " + bed);
            } else {
                scheduleBadge.setVisibility(View.GONE);
            }
        }
    }
}
