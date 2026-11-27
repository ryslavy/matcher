package io.github.maxhayim.brightness;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Frosted-glass settings row with living wobbly focus ring support and rounded amber slider bar.
 */
final class Row extends LinearLayout {
    interface Step {
        void step(int direction);
    }

    private static final int AMBER = 0xFFF5A524;

    private final float density;
    private final TextView value;
    private final TextView desc;
    private final View fill;
    private final View rest;

    Row(Context context, String name, String description, boolean bar, Runnable onClick, Step onStep) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int ph = Math.round(16 * density), pv = Math.round(11 * density);
        setPadding(ph, pv, ph, pv);
        setBackground(GlassKit.makeChoiceBg(density, 14f, AMBER));
        boolean active = onClick != null || onStep != null;
        setFocusable(active);
        setFocusableInTouchMode(active);
        setClickable(onClick != null);
        if (active) {
            GlassKit.attachRowFocus(this, density, 14f, 0);
        }
        if (onClick != null) {
            setOnClickListener(v -> {
                GlassKit.pulseClick(v, 1.015f);
                onClick.run();
            });
        }
        if (onStep != null) {
            setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) onStep.step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            });
        }

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(VERTICAL);
        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(15.5f);
        label.setTextColor(0xFFFFFFFF);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(label);
        desc = new TextView(context);
        desc.setTextSize(12f);
        desc.setTextColor(0x99FFFFFF);
        desc.setPadding(0, Math.round(2 * density), Math.round(14 * density), 0);
        text.addView(desc);
        setDetail(description);
        addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        if (bar) {
            LinearLayout track = new LinearLayout(context);
            track.setOrientation(HORIZONTAL);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(GlassKit.isLightGlass() ? 0x260F172A : 0x2EFFFFFF);
            trackBg.setCornerRadius(4f * density);
            track.setBackground(trackBg);
            track.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 4f * density);
                }
            });
            track.setClipToOutline(true);
            fill = new View(context);
            GradientDrawable fillBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFFF5A524, 0xFFFDE047});
            fillBg.setCornerRadius(4f * density);
            fill.setBackground(fillBg);
            rest = new View(context);
            track.addView(fill, new LayoutParams(0, LayoutParams.MATCH_PARENT, 0f));
            track.addView(rest, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            FrameLayout holder = new FrameLayout(context);
            holder.addView(track, new FrameLayout.LayoutParams(
                    Math.round(170 * density), Math.round(7 * density), Gravity.CENTER_VERTICAL));
            LayoutParams hp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            hp.rightMargin = Math.round(14 * density);
            addView(holder, hp);
        } else {
            fill = rest = null;
        }

        value = new TextView(context);
        value.setTextSize(15.5f);
        value.setTextColor(0xE0FFFFFF);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.END);
        value.setMinWidth(Math.round(82 * density));
        addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    void setDetail(String d) {
        desc.setText(d == null ? "" : d);
        desc.setVisibility(d == null || d.isEmpty() ? GONE : VISIBLE);
    }

    void setValue(String v) {
        value.setText(v);
    }

    void setAccentActive(boolean active) {
        setSelected(active);
        GlassKit.markPreserveTextColor(value);
        value.setTextColor(active ? GlassKit.adaptiveAccentTextColor(0xFFFDE047) : GlassKit.adaptiveSecondaryTextColor());
        GlassKit.configureFocus(this, 1.015f, 0f, 14f, active ? AMBER : 0);
    }

    void setBar(float fraction) {
        if (fill == null) return;
        float f = Math.max(0f, Math.min(1f, fraction));
        ((LayoutParams) fill.getLayoutParams()).weight = f;
        ((LayoutParams) rest.getLayoutParams()).weight = 1f - f;
        fill.requestLayout();
    }
}
