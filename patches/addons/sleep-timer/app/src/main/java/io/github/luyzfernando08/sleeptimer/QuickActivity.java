package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Frosted-glass bottom capsule quick bar for a held remote key: Left/Right steps through off and
 * each duration immediately over whatever is playing; OK, Back, or idle closes smoothly.
 */
public class QuickActivity extends Activity {
    private static final long CLOSE_AFTER_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable close = this::dismiss;
    private LinearLayout panel;
    private TextView label;
    private View[] stepDots;
    private GradientDrawable[] stepDotBgs;
    private float density;
    private boolean closing;
    /** 0 is off; otherwise the index into Timer.MINUTES plus one. */
    private int choice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        density = GlassKit.density(this);
        GlassKit.installQuickBarBackdropFromIntent(this, getIntent());
        GlassKit.applyWindowGlass(getWindow(), density, 0f, false);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int padX = Ui.dp(this, 28), padY = Ui.dp(this, 18);
        panel.setPadding(padX, padY, padX, padY);
        GlassKit.applyPanelSurface(panel, density, 24f, false, true, 0x261E45, true);

        label = Ui.text(this, 19f, Color.WHITE);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        panel.addView(label);

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        steps.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 12);
        stepDots = new View[Timer.MINUTES.length + 1];
        stepDotBgs = new GradientDrawable[stepDots.length];
        for (int i = 0; i < stepDots.length; i++) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(3f * density);
            dot.setBackground(bg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 5));
            if (i < stepDots.length - 1) dlp.setMarginEnd(Ui.dp(this, 6));
            steps.addView(dot, dlp);
            stepDots[i] = dot;
            stepDotBgs[i] = bg;
        }
        panel.addView(steps, slp);

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(this, 44);
        root.addView(panel, lp);
        root.setOnClickListener(v -> dismiss());
        panel.setOnClickListener(v -> closeLater());
        setContentView(root);
        GlassKit.revealBottomBar(panel, density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetIfClosing();
        choice = currentChoice();
        show();
        closeLater();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        GlassKit.installQuickBarBackdropFromIntent(this, intent);
        GlassKit.invalidateLiquidPanels(panel);
        show();
        resetIfClosing();
        closeLater();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onPause() {
        handler.removeCallbacks(close);
        GlassKit.cancelPanelAnim(panel);
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
        super.onPause();
    }

    private void resetIfClosing() {
        if (closing) {
            closing = false;
            GlassKit.setClosing(panel, false);
            GlassKit.cancelPanelAnim(panel);
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                dismiss();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @SuppressWarnings("deprecation")
    private void dismiss() {
        if (closing) return;
        closing = true;
        handler.removeCallbacks(close);
        GlassKit.dismissBottomBar(panel, density, () -> {
            finish();
            overridePendingTransition(0, 0);
        });
    }

    private int currentChoice() {
        long end = Timer.end(this);
        if (end == 0) return 0;
        long leftMinutes = (end - System.currentTimeMillis() + 59_999) / 60_000;
        for (int i = 0; i < Timer.MINUTES.length; i++) {
            if (Timer.MINUTES[i] >= leftMinutes) return i + 1;
        }
        return Timer.MINUTES.length;
    }

    private void step(int direction) {
        choice = Math.max(0, Math.min(Timer.MINUTES.length, choice + direction));
        if (choice == 0) Timer.cancel(this);
        else Timer.start(this, Timer.MINUTES[choice - 1]);
        show();
        closeLater();
    }

    private void show() {
        long end = Timer.end(this);
        boolean light = GlassKit.isLightGlass();
        if (!Timer.canSleep()) {
            label.setText(R.string.no_service);
            label.setTextColor(GlassKit.adaptiveAccentTextColor(0xFFFCA5A5));
        } else if (end == 0) {
            label.setText(R.string.state_off);
            label.setTextColor(GlassKit.adaptiveSecondaryTextColor());
        } else {
            label.setText(getString(R.string.in_minutes, (end - System.currentTimeMillis() + 59_999) / 60_000) + " · "
                    + getString(R.string.state_on, DateFormat.getTimeFormat(this).format(end)));
            label.setTextColor(GlassKit.adaptivePrimaryTextColor());
        }
        if (stepDotBgs != null) {
            int activeLav = GlassKit.adaptiveAccentTextColor(Ui.ACCENT_LAVENDER);
            int filledLav = light ? 0x885B21B6 : 0x88A78BFA;
            int restDot = light ? 0x260F172A : 0x2EFFFFFF;
            int offActive = light ? 0xCC0F172A : 0xCCFFFFFF;
            for (int i = 0; i < stepDotBgs.length; i++) {
                int color;
                if (i == choice) {
                    color = choice == 0 ? offActive : activeLav;
                } else if (choice > 0 && i > 0 && i < choice) {
                    color = filledLav;
                } else {
                    color = restDot;
                }
                stepDotBgs[i].setColor(color);
            }
        }
    }

    private void closeLater() {
        handler.removeCallbacks(close);
        handler.postDelayed(close, CLOSE_AFTER_MS);
    }
}
