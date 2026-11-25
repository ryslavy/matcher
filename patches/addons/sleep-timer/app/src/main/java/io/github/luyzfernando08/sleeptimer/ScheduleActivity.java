package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Frosted-glass remote-friendly weekly wake/sleep schedule editor. Changes apply only on Save. */
public class ScheduleActivity extends Activity {
    private GlassKit.Modal modal;
    private Dialog activeDialog;
    private boolean enabled;
    private List<ScheduleRules.Rule> rules;
    private List<ScheduleRules.Pause> pauses;
    private LinearLayout list;
    private String[] days;
    private int focusIndex;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        days = new String[]{
                getString(R.string.sched_23), getString(R.string.sched_24), getString(R.string.sched_25),
                getString(R.string.sched_26), getString(R.string.sched_27), getString(R.string.sched_28),
                getString(R.string.sched_29)
        };
        enabled = Schedule.on(this);
        rules = Schedule.rules(this);
        pauses = Schedule.pauses(this);
        if (state != null) {
            enabled = state.getBoolean("enabled");
            rules.clear();
            pauses.clear();
            int[] masks = state.getIntArray("days"), times = state.getIntArray("times");
            boolean[] wakes = state.getBooleanArray("wakes");
            if (masks != null && times != null && wakes != null) {
                for (int i = 0; i < masks.length; i++) {
                    rules.add(new ScheduleRules.Rule(masks[i], times[i], wakes[i]));
                }
            }
            String[] ranges = state.getStringArray("ranges");
            if (ranges != null) {
                for (int i = 0; i < ranges.length; i += 2) {
                    pauses.add(new ScheduleRules.Pause(LocalDate.parse(ranges[i]), LocalDate.parse(ranges[i + 1])));
                }
            }
        }
        modal = GlassKit.installModal(this, 780, 490, false, 0x261E45);
        render();
        modal.reveal();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
    }

    @Override
    protected void onPause() {
        dismissActiveDialog();
        if (modal != null) modal.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissActiveDialog();
        super.onDestroy();
    }

    private void dismissActiveDialog() {
        if (activeDialog != null) {
            Dialog d = activeDialog;
            activeDialog = null;
            if (d.isShowing()) d.dismiss();
        }
    }

    private void track(Dialog d) {
        dismissActiveDialog();
        activeDialog = d;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("enabled", enabled);
        int[] masks = new int[rules.size()], times = new int[rules.size()];
        boolean[] wakes = new boolean[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            masks[i] = rules.get(i).days;
            times[i] = rules.get(i).minute;
            wakes[i] = rules.get(i).wake;
        }
        out.putIntArray("days", masks);
        out.putIntArray("times", times);
        out.putBooleanArray("wakes", wakes);
        String[] ranges = new String[pauses.size() * 2];
        for (int i = 0; i < pauses.size(); i++) {
            ranges[2 * i] = pauses.get(i).start.toString();
            ranges[2 * i + 1] = pauses.get(i).end.toString();
        }
        out.putStringArray("ranges", ranges);
    }

    private void render() {
        LinearLayout panel = modal.panel;
        panel.removeAllViews();

        TextView title = GlassKit.makeTitle(this, getString(R.string.sched_0));
        title.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        panel.addView(title);
        panel.addView(GlassKit.makeDivider(this, 10, 6));

        LinearLayout topWrap = new LinearLayout(this);
        topWrap.setOrientation(LinearLayout.VERTICAL);
        topWrap.setClipChildren(false);
        topWrap.setClipToPadding(false);
        topWrap.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 4));

        int toggleAccent = enabled ? Ui.ACCENT_EMERALD : Ui.ACCENT_AMBER;
        TextView toggle = Ui.row(topWrap, 0, 14f, toggleAccent);
        toggle.setText(enabled ? R.string.sched_2 : R.string.sched_3);
        toggle.setSelected(enabled);
        toggle.setTextColor(enabled ? 0xFFA7F3D0 : 0xFFFDE68A);
        toggle.setOnClickListener(v -> {
            enabled = !enabled;
            focusIndex = 0;
            render();
        });
        panel.addView(topWrap, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(this, 18));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 10));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        section(R.string.remote_events);
        for (ScheduleRules.Rule r : rules) {
            int accent = r.wake ? Ui.ACCENT_EMERALD : Ui.ACCENT_LAVENDER;
            TextView row = button(label(r), accent, () -> edit(r));
            row.setTextColor(r.wake ? 0xFFA7F3D0 : 0xFFDDD6FE);
        }
        button(getString(R.string.sched_4), 0, () -> edit(null));

        section(R.string.remote_exclusions);
        for (ScheduleRules.Pause p : pauses) {
            button(getString(R.string.sched_5) + p.start + "  -  " + p.end, Ui.ACCENT_AMBER, () ->
                    track(GlassKit.showListDialog(
                            this, modal, getString(R.string.sched_6),
                            new String[]{getString(R.string.sched_7), getString(R.string.sched_8)},
                            -1, Ui.ACCENT_LAVENDER, null, null,
                            n -> {
                                if (n == 1) {
                                    pauses.remove(p);
                                    render();
                                } else {
                                    dateRange(p);
                                }
                            })));
        }
        button(getString(R.string.sched_9), 0, () -> dateRange(null));

        panel.addView(GlassKit.makeDivider(this, 6, 8));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setClipChildren(false);
        footer.setClipToPadding(false);
        footer.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 4));

        TextView cancelBtn = GlassKit.makePillButton(this, getString(R.string.sched_17), 0);
        cancelBtn.setOnClickListener(v -> modal.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
        clp.rightMargin = Ui.dp(this, 10);
        footer.addView(cancelBtn, clp);

        TextView save = GlassKit.makePillButton(this, getString(R.string.sched_10), Ui.ACCENT_EMERALD);
        save.setTextColor(0xFFA7F3D0);
        save.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            Schedule.save(this, enabled, rules, pauses);
            modal.dismiss();
        });
        footer.addView(save, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        if (focusIndex <= 0 || focusIndex > list.getChildCount() || !list.getChildAt(focusIndex - 1).requestFocus()) {
            toggle.requestFocus();
        }
        panel.post(() -> modal.refreshFocus());
    }

    private void section(int text) {
        TextView heading = GlassKit.makeSectionHeading(this, getString(text));
        list.addView(heading);
    }

    private TextView button(String text, int accent, Runnable action) {
        TextView b = Ui.row(list, 0, 14f, accent);
        b.setText(text);
        b.setTextSize(15.5f);
        b.setOnClickListener(v -> {
            focusIndex = list.indexOfChild(b) + 1;
            action.run();
        });
        return b;
    }

    private String label(ScheduleRules.Rule r) {
        StringBuilder s = new StringBuilder(String.format(Locale.getDefault(), "%02d:%02d", r.minute / 60, r.minute % 60));
        s.append("    ").append(getString(r.wake ? R.string.sched_14 : R.string.sched_15)).append("\n");
        for (int i = 0; i < 7; i++) {
            if ((r.days & (1 << i)) != 0) s.append(shortDay(i)).append("  ");
        }
        return s.toString().trim();
    }

    private String shortDay(int index) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String name = DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT_STANDALONE, locale);
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    private void edit(ScheduleRules.Rule old) {
        String[] choices = old == null
                ? new String[]{getString(R.string.sched_14), getString(R.string.sched_15)}
                : new String[]{getString(R.string.sched_14), getString(R.string.sched_15), getString(R.string.sched_8)};
        track(GlassKit.showListDialog(
                this, modal, old == null ? getString(R.string.sched_13) : label(old),
                choices, -1, Ui.ACCENT_LAVENDER, null, null,
                n -> {
                    if (n == 2) {
                        rules.remove(old);
                        render();
                        return;
                    }
                    ScheduleRules.Rule draft = new ScheduleRules.Rule(
                            old == null ? 31 : old.days,
                            old == null ? 480 : old.minute,
                            n == 0);
                    boolean[] checked = new boolean[7];
                    for (int i = 0; i < 7; i++) checked[i] = (draft.days & (1 << i)) != 0;

                    final Dialog[] dialogRef = new Dialog[1];
                    dialogRef[0] = GlassKit.showMultiChoiceDialog(
                            this, modal, getString(R.string.sched_16), days, checked,
                            getString(R.string.sched_18), getString(R.string.sched_17),
                            draft.wake ? Ui.ACCENT_EMERALD : Ui.ACCENT_LAVENDER,
                            picked -> {
                                int mask = 0;
                                for (int i = 0; i < 7; i++) {
                                    if (picked[i]) mask |= (1 << i);
                                }
                                if (mask == 0) {
                                    Toast.makeText(this, R.string.sched_19, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                draft.days = mask;
                                if (dialogRef[0] != null) dialogRef[0].dismiss();
                                track(RemotePicker.time(
                                        this, modal,
                                        getString(draft.wake ? R.string.remote_wake_time : R.string.remote_sleep_time),
                                        draft.minute,
                                        values -> {
                                            draft.minute = values[0] * 60 + values[1];
                                            int idx = old != null ? rules.indexOf(old) : -1;
                                            if (idx >= 0) rules.set(idx, draft);
                                            else rules.add(draft);
                                            render();
                                        }));
                            });
                    track(dialogRef[0]);
                }));
    }

    private void dateRange(ScheduleRules.Pause old) {
        track(RemotePicker.date(this, modal, getString(R.string.sched_22), old == null ? LocalDate.now() : old.start, first -> {
            LocalDate from = LocalDate.of(first[2], first[1], first[0]);
            track(RemotePicker.date(this, modal, getString(R.string.sched_21), old == null ? from : old.end, last -> {
                LocalDate to = LocalDate.of(last[2], last[1], last[0]);
                if (to.isBefore(from)) {
                    Toast.makeText(this, R.string.sched_20, Toast.LENGTH_LONG).show();
                    return;
                }
                ScheduleRules.Pause range = new ScheduleRules.Pause(from, to);
                int idx = old != null ? pauses.indexOf(old) : -1;
                if (idx >= 0) pauses.set(idx, range);
                else pauses.add(range);
                render();
            }));
        }));
    }
}
