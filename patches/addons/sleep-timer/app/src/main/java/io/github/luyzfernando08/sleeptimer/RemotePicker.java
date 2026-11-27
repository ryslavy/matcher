package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.Locale;

/** Frosted-glass numeric wheels controlled entirely by D-pad; never opens the keyboard. */
final class RemotePicker extends Dialog {
    interface Result { void accept(int[] values); }

    private final GlassKit.Modal parentModal;
    private final int[] values, limits, labels;
    private final Result result;
    private final String title;
    private final boolean date;
    private LinearLayout[] wheels;
    private TextView[] numbers;
    private TextView save;

    static RemotePicker time(Activity a, String title, int minute, Result result) {
        return time(a, null, title, minute, result);
    }

    static RemotePicker time(Activity a, GlassKit.Modal parentModal, String title, int minute, Result result) {
        RemotePicker picker = new RemotePicker(a, parentModal, title, new int[]{minute / 60, minute % 60}, new int[]{24, 60},
                new int[]{R.string.remote_hours, R.string.remote_minutes}, false, result);
        picker.show();
        return picker;
    }

    static RemotePicker date(Activity a, String title, LocalDate date, Result result) {
        return date(a, null, title, date, result);
    }

    static RemotePicker date(Activity a, GlassKit.Modal parentModal, String title, LocalDate date, Result result) {
        RemotePicker picker = new RemotePicker(a, parentModal, title, new int[]{date.getDayOfMonth(), date.getMonthValue(), date.getYear()},
                new int[]{31, 12, 2100},
                new int[]{R.string.remote_day, R.string.remote_month, R.string.remote_year}, true, result);
        picker.show();
        return picker;
    }

    private RemotePicker(Activity a, GlassKit.Modal parentModal, String title, int[] values,
                         int[] limits, int[] labels, boolean date, Result result) {
        super(a);
        this.parentModal = parentModal;
        this.title = title;
        this.values = values;
        this.limits = limits;
        this.labels = labels;
        this.date = date;
        this.result = result;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        float d = GlassKit.density(getContext());
        int pad = GlassKit.insetPx(d);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(22), dp(26), dp(22));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        GlassKit.applyPanelSurface(root, d, 24f, true, false, 0x261E45);

        TextView heading = GlassKit.makeTitle(getContext(), title);
        heading.setPadding(0, 0, 0, dp(14));
        root.addView(heading);

        LinearLayout body = new LinearLayout(getContext());
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        root.addView(body);

        LinearLayout fields = new LinearLayout(getContext());
        fields.setGravity(Gravity.CENTER);
        fields.setClipChildren(false);
        fields.setClipToPadding(false);
        body.addView(fields, new LinearLayout.LayoutParams(0, dp(156), 1f));

        wheels = new LinearLayout[values.length];
        numbers = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            LinearLayout wheel = new LinearLayout(getContext());
            wheels[i] = wheel;
            wheel.setOrientation(LinearLayout.VERTICAL);
            wheel.setGravity(Gravity.CENTER);
            wheel.setFocusable(true);
            wheel.setFocusableInTouchMode(true);
            wheel.setClickable(true);
            wheel.setPadding(dp(8), dp(10), dp(8), dp(10));
            wheel.setBackground(GlassKit.makeChoiceBg(d, 16f, Ui.ACCENT_EMERALD));
            GlassKit.configureFocus(wheel, 1.04f, 1.5f, 16f, Ui.ACCENT_EMERALD);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(152), 1f);
            lp.setMargins(dp(6), 0, dp(6), 0);
            fields.addView(wheel, lp);

            TextView caption = part(wheel, 13.5f, 22, 0x99FFFFFF);
            caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            caption.setText(labels[i]);
            TextView upArrow = part(wheel, 16f, 22, 0xB3FFFFFF);
            upArrow.setText("\u25b2");
            TextView number = part(wheel, 38f, 54, Color.WHITE);
            number.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            numbers[i] = number;
            TextView downArrow = part(wheel, 16f, 22, 0xB3FFFFFF);
            downArrow.setText("\u25bc");

            wheel.setOnFocusChangeListener((v, focused) -> {
                GlassKit.animateFocus(v, focused, 1.04f, 1.5f, d);
                int color = focused ? GlassKit.adaptiveAccentTextColor(0xFFA7F3D0) : GlassKit.adaptivePrimaryTextColor();
                int arrowFoc = GlassKit.adaptiveAccentTextColor(Ui.ACCENT_EMERALD);
                int arrowRest = GlassKit.adaptiveHintTextColor();
                upArrow.setTextColor(focused ? arrowFoc : arrowRest);
                number.setTextColor(color);
                downArrow.setTextColor(focused ? arrowFoc : arrowRest);
                caption.setTextColor(focused ? GlassKit.adaptivePrimaryTextColor() : GlassKit.adaptiveSecondaryTextColor());
            });
            wheel.setOnClickListener(v -> advance(index));
            wheel.setOnKeyListener((v, key, event) -> {
                boolean up = key == KeyEvent.KEYCODE_DPAD_UP;
                boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
                if (up || down) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        change(index, (up ? 1 : -1) * (event.getRepeatCount() > 5 ? 5 : 1));
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int next = index + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                        if (next >= values.length) save.requestFocus();
                        else wheels[Math.max(0, next)].requestFocus();
                    }
                    return true;
                }
                return false;
            });
        }

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(dp(164), dp(156));
        actionLp.leftMargin = dp(14);
        body.addView(actions, actionLp);

        TextView cancel = Ui.row(actions, 0, 14f, 0);
        cancel.setGravity(Gravity.CENTER);
        cancel.setText(R.string.sched_17);
        cancel.setOnClickListener(v -> dismiss());

        save = Ui.row(actions, 0, 14f, Ui.ACCENT_EMERALD);
        save.setGravity(Gravity.CENTER);
        save.setText(R.string.sched_10);
        save.setTextColor(0xFFA7F3D0);
        save.setOnClickListener(v -> {
            int[] snapshot = values.clone();
            dismiss();
            result.accept(snapshot);
        });

        cancel.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT) wheels[wheels.length - 1].requestFocus();
                    else save.requestFocus();
                }
                return true;
            }
            return false;
        });
        save.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT) wheels[wheels.length - 1].requestFocus();
                    else cancel.requestFocus();
                }
                return true;
            }
            return false;
        });

        refresh();
        GlassKit.applyAdaptiveContentTheme(root);

        final boolean[] pushedBlur = {false};
        final boolean[] poppedBlur = {false};
        final boolean[] dismissing = {false};
        FrameLayout host = new FrameLayout(getContext()) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && !dismissing[0]) {
                        dismissing[0] = true;
                        if (parentModal != null && pushedBlur[0] && !poppedBlur[0]) {
                            poppedBlur[0] = true;
                            parentModal.popBlur();
                        }
                        GlassKit.dismissModal(this, d, () -> {
                            if (isShowing()) dismiss();
                        });
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        host.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        rlp.setMargins(pad, pad, pad, pad);
        host.addView(root, rlp);
        new GlassKit.FocusRing(host, root, d);

        setContentView(host);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            GlassKit.clearDialogShadow(w);
            w.setLayout(dp(700) + 2 * pad, WindowManager.LayoutParams.WRAP_CONTENT);
            GlassKit.applyWindowGlass(w, d, 0f, false);
            w.setWindowAnimations(0);
        }
        setOnDismissListener(dd -> {
            GlassKit.cancelPanelAnim(host);
            if (parentModal != null && pushedBlur[0] && !poppedBlur[0]) {
                poppedBlur[0] = true;
                parentModal.popBlur();
            }
        });
        GlassKit.revealModal(host, d, () -> {
            if (!dismissing[0] && parentModal != null && !pushedBlur[0]) {
                pushedBlur[0] = true;
                parentModal.pushBlur();
            }
        });
        wheels[0].requestFocus();
    }

    private int dp(int n) { return Ui.dp(getContext(), n); }

    private void advance(int i) {
        if (i + 1 < wheels.length) wheels[i + 1].requestFocus();
        else save.requestFocus();
    }

    private void change(int i, int delta) {
        if (date) {
            if (i == 2) values[i] = Math.max(2020, Math.min(2100, values[i] + delta));
            else {
                int max = i == 0 ? LocalDate.of(values[2], values[1], 1).lengthOfMonth() : 12;
                values[i] = Math.floorMod(values[i] - 1 + delta, max) + 1;
            }
            values[0] = Math.min(values[0], LocalDate.of(values[2], values[1], 1).lengthOfMonth());
        } else {
            values[i] = Math.floorMod(values[i] + delta, limits[i]);
        }
        refresh();
    }

    private TextView part(LinearLayout parent, float size, int height, int color) {
        TextView view = Ui.text(getContext(), size, color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        parent.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height)));
        return view;
    }

    private void refresh() {
        for (int i = 0; i < values.length; i++) {
            numbers[i].setText(String.format(Locale.getDefault(), "%02d", values[i]));
        }
    }
}
