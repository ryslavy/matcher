package io.github.maxhayim.headphones;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Frosted-glass headphones row with living wobbly focus ring and emerald active highlight. */
final class Row extends LinearLayout {
    private static final int EMERALD = 0xFF4ADE80;
    private final float density;
    private final TextView value;
    private final TextView desc;

    Row(Context context, String name, String description, Runnable onClick) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int ph = Math.round(16 * density), pv = Math.round(11 * density);
        setPadding(ph, pv, ph, pv);
        setBackground(GlassKit.makeChoiceBg(density, 14f, EMERALD));
        boolean active = onClick != null;
        setFocusable(active);
        setFocusableInTouchMode(active);
        setClickable(active);
        if (active) {
            GlassKit.attachRowFocus(this, density, 14f, 0);
            setOnClickListener(v -> {
                GlassKit.pulseClick(v, 1.015f);
                onClick.run();
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

        value = new TextView(context);
        value.setTextSize(14.5f);
        value.setTextColor(0xD8FFFFFF);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.END);
        value.setMinWidth(Math.round(86 * density));
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
        value.setTextColor(active ? 0xFF6EE7B7 : 0xD8FFFFFF);
        GlassKit.configureFocus(this, 1.015f, 0f, 14f, active ? EMERALD : 0);
    }
}
