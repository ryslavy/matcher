package io.github.luyzfernando08.sleeptimer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Frosted-glass UI helpers shared across Sleep Timer screens. */
final class Ui {
    static final int ACCENT_LAVENDER = 0xFFA78BFA;
    static final int ACCENT_EMERALD = 0xFF4ADE80;
    static final int ACCENT_CORAL = 0xFFF87171;
    static final int ACCENT_AMBER = 0xFFFBBF24;

    private Ui() { }

    static TextView text(Context ctx, float sp, int color) {
        TextView view = new TextView(ctx);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    static TextView row(LinearLayout parent, int widthDp) {
        return row(parent, widthDp, 14f, 0);
    }

    static TextView row(LinearLayout parent, int widthDp, float radiusDp, int accent) {
        Context ctx = parent.getContext();
        float d = GlassKit.density(ctx);
        TextView row = text(ctx, 16f, Color.WHITE);
        row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 18), dp(ctx, 13), dp(ctx, 18), dp(ctx, 13));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setBackground(GlassKit.makeChoiceBg(d, radiusDp, accent != 0 ? accent : ACCENT_LAVENDER));
        GlassKit.attachRowFocus(row, d, radiusDp, accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                widthDp <= 0 ? LinearLayout.LayoutParams.MATCH_PARENT : dp(ctx, widthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        parent.addView(row, lp);
        return row;
    }

    static GradientDrawable pill(Context ctx, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, 16));
        return d;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
