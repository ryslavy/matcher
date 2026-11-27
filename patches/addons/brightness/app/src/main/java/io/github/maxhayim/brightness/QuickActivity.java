package io.github.maxhayim.brightness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating Apple tvOS / Android 17 frosted-glass brightness bar at the bottom of the screen.
 */
public class QuickActivity extends Activity {
    private static final long CLOSE_AFTER_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable close = this::dismissSmoothly;
    private Dimming.Settings s;
    private LinearLayout panel;
    private TextView label;
    private GradientDrawable trackBg;
    private GradientDrawable fillBg;
    private View fill;
    private View rest;
    private boolean closing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        float d = GlassKit.density(this);
        GlassKit.installQuickBarBackdropFromIntent(this, getIntent());
        GlassKit.applyWindowGlass(getWindow(), d, 0f, false);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(28), px(18), px(28), px(18));
        GlassKit.applyPanelSurface(panel, d, 24f, false, true, 0x3D2E18, true);

        label = new TextView(this);
        label.setTextSize(19f);
        label.setTextColor(0xFFFFFFFF);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(label);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        trackBg = new GradientDrawable();
        trackBg.setColor(GlassKit.isLightGlass() ? 0x260F172A : 0x2EFFFFFF);
        trackBg.setCornerRadius(5f * d);
        track.setBackground(trackBg);
        track.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 5f * d);
            }
        });
        track.setClipToOutline(true);

        fill = new View(this);
        fillBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                GlassKit.isLightGlass() ? new int[]{0xFFD97706, 0xFF92400E} : new int[]{0xFFF5A524, 0xFFFFFFFF});
        fillBg.setCornerRadius(5f * d);
        fill.setBackground(fillBg);
        GlassKit.applyAdaptiveContentTheme(panel);
        rest = new View(this);
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        track.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(px(420), px(9));
        barLp.topMargin = px(13);
        barLp.bottomMargin = px(2);
        panel.addView(track, barLp);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> dismissSmoothly());
        panel.setOnClickListener(v -> { /* consume inside taps */ });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(52);
        root.addView(panel, lp);
        setContentView(root);
        GlassKit.revealBottomBar(panel, d);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!DimService.canDraw(this)) {
            Intent mainIntent = new Intent(this, MainActivity.class);
            GlassKit.forwardBackdropExtras(this, mainIntent);
            startActivity(mainIntent);
            finish();
            @SuppressWarnings("deprecation")
            Runnable noAnim = () -> overridePendingTransition(0, 0);
            noAnim.run();
            return;
        }
        resetIfClosing();
        s = Prefs.load(this);
        show();
        closeLater();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        GlassKit.installQuickBarBackdropFromIntent(this, intent);
        if (trackBg != null) trackBg.setColor(GlassKit.isLightGlass() ? 0x260F172A : 0x2EFFFFFF);
        if (fillBg != null) {
            fillBg.setColors(GlassKit.isLightGlass()
                    ? new int[]{0xFFD97706, 0xFF92400E} : new int[]{0xFFF5A524, 0xFFFFFFFF});
        }
        GlassKit.applyAdaptiveContentTheme(panel);
        GlassKit.invalidateLiquidPanels(panel);
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
                dismissSmoothly();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @SuppressWarnings("deprecation")
    private void dismissSmoothly() {
        if (closing) return;
        closing = true;
        handler.removeCallbacks(close);
        GlassKit.dismissBottomBar(panel, GlassKit.density(this), () -> {
            finish();
            overridePendingTransition(0, 0);
        });
    }

    private boolean nightNow() {
        return Dimming.nightNow(s, DimService.minuteNow());
    }

    private void step(int direction) {
        closeLater();
        if (!s.on) {
            if (direction > 0) return;
            s.brightness = Dimming.MAX_BRIGHTNESS;
            s.nightBrightness = Dimming.MAX_BRIGHTNESS;
            s.on = true;
        }
        if (nightNow()) s.nightBrightness = Dimming.stepBrightness(s.nightBrightness, direction);
        else s.brightness = Dimming.stepBrightness(s.brightness, direction);
        Prefs.save(this, s);
        DimService.start(this);
        show();
    }

    private void show() {
        boolean night = nightNow();
        int value = !s.on ? Dimming.MAX_BRIGHTNESS : night ? s.nightBrightness : s.brightness;
        label.setText(getString(night ? R.string.quick_night_brightness : R.string.quick_brightness, value));
        float f = Math.max(0f, Math.min(1f, value / (float) Dimming.MAX_BRIGHTNESS));
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = f;
        ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight = 1f - f;
        fill.requestLayout();
    }

    private void closeLater() {
        handler.removeCallbacks(close);
        handler.postDelayed(close, CLOSE_AFTER_MS);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
