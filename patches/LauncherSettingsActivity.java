package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Remote-friendly Apple tvOS / Android 17 Expressive frosted-glass Launcher Settings modal
 * floating over the live blurred Home screen; system settings stay one row away.
 */
public final class LauncherSettingsActivity extends Activity {

    private static final int SELECTED_ACCENT = 0xFF4ADE80;
    @Nullable
    private static WeakReference<LauncherSettingsActivity> activeRef;

    @Nullable
    private static LauncherSettingsActivity active() {
        return activeRef != null ? activeRef.get() : null;
    }

    static boolean isLive() {
        LauncherSettingsActivity a = active();
        return a != null && a.resumed && !a.isFinishing() && !a.isDestroyed();
    }

    static void refreshBackdropIfOpen() {
        LauncherSettingsActivity a = active();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            if (a.overHome) {
                ExpressiveGlass.invalidateLiquidPanels(a.backdrop);
            } else {
                a.applyCapturedBackdrop(false, false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    static void launch(android.content.Context ctx, boolean overHome, boolean seamlessHandoff) {
        MainActivity.markSettingsLaunchPending();
        if (overHome) MainActivity.prepareSettingsBackdropBlur();
        else MainActivity.setSettingsOpen(true);
        Intent intent = new Intent(ctx, LauncherSettingsActivity.class)
                .putExtra(ExpressiveGlass.EXTRA_OVER_HOME, overHome)
                .putExtra(ExpressiveGlass.EXTRA_SEAMLESS_HANDOFF, seamlessHandoff)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
            if (ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(0, 0);
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
            MainActivity.clearSettingsLaunchPending();
            MainActivity.setSettingsOpen(false);
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private FrameLayout backdrop;
    @Nullable
    private FrameLayout backdropView;
    private FrameLayout panelStage;
    private LinearLayout panel;
    @Nullable
    private FocusRingController focusRing;
    @Nullable
    private Dialog subDialog;
    @Nullable
    private LauncherFeatureSettings featureSettings;
    private float density;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;
    private boolean reducedMotion;
    private boolean overHome = true;
    private boolean seamlessHandoff;
    private int backdropGenOnCreate;
    private boolean closing;
    private boolean resumed;
    private boolean initialRevealDone;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        activeRef = new WeakReference<>(this);
        MainActivity.clearSettingsLaunchPending();
        density = getResources().getDisplayMetrics().density;
        Intent startIntent = getIntent();
        overHome = startIntent == null || !startIntent.hasExtra(ExpressiveGlass.EXTRA_OVER_HOME)
                ? MainActivity.isHomeVisible()
                : startIntent.getBooleanExtra(ExpressiveGlass.EXTRA_OVER_HOME, true);
        seamlessHandoff = startIntent != null
                && startIntent.getBooleanExtra(ExpressiveGlass.EXTRA_SEAMLESS_HANDOFF, false);
        backdropGenOnCreate = ExpressiveGlass.overlayBackdropGeneration();
        if (overHome) MainActivity.prepareSettingsBackdropBlur();
        else MainActivity.setSettingsOpen(true);
        if (QuickSettingsActivity.isLive()) {
            QuickSettingsActivity.finishForHandoff();
        }
        if (OverlayActivity.isLive()) {
            OverlayActivity.finishForHandoff();
        }

        boolean hasLiveBackdrop = !overHome && ExpressiveGlass.hasOverlayBackdrop();
        if (!overHome && !hasLiveBackdrop) {
            ExpressiveGlass.applyWindowBlur(getWindow(), density, ExpressiveGlass.SHADE_BLUR_RADIUS_DP, 0.36f);
        } else {
            ExpressiveGlass.setWindowDimOnly(getWindow(), 0f);
        }

        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        reducedMotion = config.reducedMotion;
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        backdrop = findViewById(R.id.settingsBackdrop);
        panelStage = findViewById(R.id.settingsPanelStage);
        panel = findViewById(R.id.settingsPanel);
        PointerSupport.install(panel);
        UiClickSound.attachFocusSound(panel);
        if (hasLiveBackdrop) {
            applyCapturedBackdrop(!seamlessHandoff, seamlessHandoff);
        }

        // Fit floating modal sheet proportionally on any 720p/1080p/4K screen.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int stagePad = FocusRingController.wrapInsetPx(density);
        int wantW = Math.min(dp(740), Math.max(dp(420), dm.widthPixels - dp(80)));
        int wantH = Math.min(dp(480), Math.max(dp(300), dm.heightPixels - dp(56)));
        ViewGroup.LayoutParams stageLp = panelStage.getLayoutParams();
        stageLp.width = wantW + 2 * stagePad;
        stageLp.height = wantH + 2 * stagePad;
        panelStage.setLayoutParams(stageLp);
        if (panel.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) panel.getLayoutParams()).setMargins(stagePad, stagePad, stagePad, stagePad);
        }
        ExpressiveGlass.applyPanelSurface(panel, density, 26f, !overHome && !hasLiveBackdrop, false);
        if (panel.getChildCount() > 0 && panel.getChildAt(0) instanceof TextView) {
            ExpressiveGlass.applyLuminousTitle((TextView) panel.getChildAt(0), density, false);
        }

        backdrop.setOnClickListener(v -> animateClose());
        panel.setOnClickListener(v -> { /* consume clicks inside modal sheet */ });

        focusRing = FocusRingController.attach(panelStage, panel, density, animations, reducedMotion);

        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            animateClose();
        });

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                Motion.pulseClick(v, animations ? 1.045f : 1f, reducedMotion || !animations);
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                Motion.pulseClick(v, animations ? 1.045f : 1f, reducedMotion || !animations);
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                Motion.pulseClick(v, animations ? 1.045f : 1f, reducedMotion || !animations);
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                Motion.pulseClick(v, animations ? 1.045f : 1f, reducedMotion || !animations);
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            Motion.pulseClick(v, animations ? 1.018f : 1f, reducedMotion || !animations);
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                Motion.pulseClick(v, animations ? 1.018f : 1f, reducedMotion || !animations);
                setAdvanced(false);
            } else {
                showAdvancedConfirmDialog();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            Motion.pulseClick(v, animations ? 1.018f : 1f, reducedMotion || !animations);
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        LinearLayout settingsContent = findViewById(R.id.settingsContent);
        if (settingsContent != null) {
            featureSettings = new LauncherFeatureSettings(
                    this, settingsContent, density, animations, reducedMotion,
                    this::changed, this::setPanelContentBlur);
        }

        attachTactileMotion(reorderChoice);
        refresh();

        final View initialFocus = config.launcherSettingsButton
                ? reorderChoice
                : columnChoices[Math.max(0, Math.min(columns - 4, 3))];
        boolean reduce = reducedMotion || !animations;
        panelStage.setAlpha(0f);
        if (!reduce) {
            panelStage.setScaleX(0.94f);
            panelStage.setScaleY(0.94f);
            panelStage.setTranslationY(84f * density);
        }
        initialFocus.requestFocus();
        panelStage.post(() -> panelStage.postOnAnimation(() -> {
            if (closing || isFinishing() || isDestroyed()) return;
            initialRevealDone = true;
            panelStage.setPivotX(panelStage.getWidth() / 2f);
            panelStage.setPivotY(panelStage.getHeight() / 2f);
            if (overHome) {
                MainActivity.startBackdropBlurFadeInIfOpen();
            } else {
                ExpressiveGlass.startOverlayBackdropFadeIn(backdropView, reducedMotion);
            }
            Motion.revealModal(panelStage, density, reduce);
        }));
    }

    private void applyCapturedBackdrop(boolean deferFadeStart, boolean startOpaque) {
        if (backdrop == null) return;
        backdropView = ExpressiveGlass.attachOverlayBackdrop(
                backdrop, backdropView, density, reducedMotion, deferFadeStart, startOpaque);
        if (backdropView != null) {
            backdropGenOnCreate = ExpressiveGlass.overlayBackdropGeneration();
            ExpressiveGlass.setWindowDimOnly(getWindow(), 0f);
            ExpressiveGlass.setPanelSmoked(panel, false);
        }
        ExpressiveGlass.invalidateLiquidPanels(backdrop);
    }

    private void attachTactileMotion(View reorderChoice) {
        if (!animations) return;
        for (TextView[] group : new TextView[][]{columnChoices, rowChoices, dockChoices, clockChoices}) {
            for (TextView tv : group) {
                Motion.attachPillFocus(tv, 1.045f, 1.5f, density, reducedMotion);
                FocusRingController.configure(tv, 1.045f, 1.5f, 14f, 0);
            }
        }
        View[] rows = {
                reorderChoice, wallpaperChoice, animationsChoice, advancedChoice,
                openOnStartChoice, followThemeChoice,
                findViewById(R.id.defaultHomeChoice), findViewById(R.id.tvSettingsChoice)
        };
        for (View row : rows) {
            if (row != null) {
                Motion.attachPillFocus(row, 1.018f, 1f, density, reducedMotion);
                FocusRingController.configure(row, 1.018f, 1f, 14f, 0);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        UiClickSound.onKeyEvent(this, event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                animateClose();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    private void animateClose() {
        if (closing) return;
        closing = true;
        dismissSubDialog();
        if (overHome) {
            MainActivity.fadeOutSettingsBlurNow();
        } else if (backdropView != null) {
            backdropView.animate().cancel();
            if (reducedMotion || !animations) {
                backdropView.setAlpha(0f);
            } else {
                backdropView.animate().alpha(0f).setDuration(175L).setInterpolator(Motion.easeInExit()).start();
            }
        }
        panelStage.setPivotX(panelStage.getWidth() / 2f);
        panelStage.setPivotY(panelStage.getHeight() / 2f);
        Motion.dismissModal(panelStage, density, reducedMotion || !animations, () -> {
            if (active() == this) activeRef = null;
            MainActivity.setSettingsOpen(false);
            super.finish();
            overridePendingTransition(0, 0);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        activeRef = new WeakReference<>(this);
        if (initialRevealDone && !closing && overHome) {
            MainActivity.setSettingsOpen(true);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        dismissSubDialog();
        ExpressiveGlass.clearPanelContentBlurImmediate(panel, focusRing != null ? focusRing.glassView() : null);
        if (active() == this) activeRef = null;
        MainActivity.setSettingsOpen(false);
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        dismissSubDialog();
        ExpressiveGlass.clearPanelContentBlurImmediate(panel, focusRing != null ? focusRing.glassView() : null);
        if (backdropView != null) {
            ExpressiveGlass.releaseOverlayBackdrop(backdropView);
            backdropView = null;
        }
        if (active() == this) activeRef = null;
        MainActivity.setSettingsOpen(false);
        if (!overHome) {
            final int gen = backdropGenOnCreate;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isLive() && !MainActivity.isSettingsLaunchInFlight()
                        && !OverlayActivity.isLive() && !OverlayActivity.isLaunchInFlight()
                        && !QuickSettingsActivity.isLive() && !QuickSettingsActivity.isLaunchInFlight()) {
                    ExpressiveGlass.clearOverlayBackdropIfGeneration(gen);
                }
            }, 200L);
        }
        super.onDestroy();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void setPanelContentBlur(boolean blurred) {
        ExpressiveGlass.animatePanelContentBlur(panel, focusRing != null ? focusRing.glassView() : null,
                density, blurred, reducedMotion || !animations);
    }

    private void dismissSubDialog() {
        if (featureSettings != null) featureSettings.dismissActiveDialog();
        Dialog d = subDialog;
        subDialog = null;
        if (d != null && d.isShowing()) d.dismiss();
        setPanelContentBlur(false);
    }

    /** Frosted-glass picker dialog replacing stock Android AlertDialog for wallpaper & open-on-start lists. */
    private void showFrostedPicker(int titleRes, String[] labels, int checkedIndex,
                                   @Nullable String neutralLabel, @Nullable Runnable onNeutral,
                                   java.util.function.IntConsumer onPick) {
        dismissSubDialog();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(box, density, 24f, true, false);
        box.setPadding(dp(18), dp(18), dp(18), dp(16));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setPadding(dp(10), 0, dp(10), dp(10));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(20));
        FocusRingController.clipScrollContainers(scroll);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(dp(6), dp(4), dp(6), dp(4));
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));

        int maxScrollH = dp(300);
        int rowH = dp(42);
        int scrollH = labels.length * rowH > maxScrollH ? maxScrollH : ViewGroup.LayoutParams.WRAP_CONTENT;
        box.addView(scroll, new LinearLayout.LayoutParams(-1, scrollH));

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        int dialogPad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(box, density, animations, reducedMotion);
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];
        FrameLayout keyHost = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && dismissRunner[0] != null) {
                        dismissRunner[0].run();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        dialog.setContentView(keyHost);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            w.setLayout(dp(440) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setWindowAnimations(0);
        }

        java.util.function.Consumer<Runnable> animateCloseSub = afterDismiss -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            setPanelContentBlur(false);
            if (!dialog.isShowing()) {
                if (afterDismiss != null) afterDismiss.run();
                return;
            }
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (afterDismiss != null) afterDismiss.run();
            });
        };
        dismissRunner[0] = () -> animateCloseSub.accept(null);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            row.findViewById(R.id.icon).setVisibility(View.GONE);
            View dot = row.findViewById(R.id.dot);
            dot.setVisibility(idx == checkedIndex ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.status).setVisibility(View.GONE);
            TextView tv = row.findViewById(R.id.title);
            tv.setText(labels[i]);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.setOnClickListener(v -> animateCloseSub.accept(() -> onPick.accept(idx)));
            Motion.attachRowFocus(row, density, reduce);
            if (idx == checkedIndex) {
                FocusRingController.configure(row, 1.015f, 0f, 16f, SELECTED_ACCENT);
            }
            rows.addView(row);
        }

        if (neutralLabel != null && onNeutral != null) {
            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.END);
            footer.setPadding(dp(6), dp(12), dp(6), dp(2));
            footer.setClipChildren(false);
            footer.setClipToPadding(false);

            TextView btnNeutral = makeDialogPill(neutralLabel);
            btnNeutral.setOnClickListener(v -> animateCloseSub.accept(onNeutral));
            footer.addView(btnNeutral, new LinearLayout.LayoutParams(-2, dp(38)));
            box.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }

        dialog.setOnDismissListener(d -> {
            Motion.cancelPanelAnim(keyHost);
            if (subDialog == dialog) {
                subDialog = null;
                setPanelContentBlur(false);
            }
        });
        subDialog = dialog;
        keyHost.setAlpha(0f);
        if (!reduce) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * density);
        }
        dialog.show();
        int focusIdx = checkedIndex >= 0 && checkedIndex < rows.getChildCount() ? checkedIndex : 0;
        if (rows.getChildCount() > 0) rows.getChildAt(focusIdx).requestFocus();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (subDialog != dialog || !dialog.isShowing() || dismissing[0] || closing || isFinishing() || isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            setPanelContentBlur(true);
            Motion.revealModal(keyHost, density, reduce);
        }));
    }

    private void showAdvancedConfirmDialog() {
        dismissSubDialog();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ExpressiveGlass.applyPanelSurface(box, density, 24f, true, false);
        box.setPadding(dp(24), dp(22), dp(24), dp(18));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(this);
        title.setText(R.string.advanced_effects);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView msg = new TextView(this);
        msg.setText(R.string.advanced_effects_warning);
        msg.setTextColor(0xCCFFFFFF);
        msg.setTextSize(14.5f);
        msg.setTypeface(UiFont.get(this, R.string.font_body));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(10);
        box.addView(msg, mlp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(dp(4), dp(16), dp(4), dp(2));
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];

        TextView btnCancel = makeDialogPill(getString(android.R.string.cancel));
        TextView btnEnable = makeDialogPill(getString(R.string.enable));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, dp(40));
        clp.rightMargin = dp(10);
        buttons.addView(btnCancel, clp);
        buttons.addView(btnEnable, new LinearLayout.LayoutParams(-2, dp(40)));
        box.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        int dialogPad = FocusRingController.wrapInsetPx(density);
        FrameLayout ringHost = FocusRingController.wrapPopup(box, density, animations, reducedMotion);
        FrameLayout keyHost = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && dismissRunner[0] != null) {
                        dismissRunner[0].run();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        dialog.setContentView(keyHost);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            w.setLayout(dp(460) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setWindowAnimations(0);
        }
        java.util.function.Consumer<Runnable> animateCloseSub = afterDismiss -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            setPanelContentBlur(false);
            if (!dialog.isShowing()) {
                if (afterDismiss != null) afterDismiss.run();
                return;
            }
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (afterDismiss != null) afterDismiss.run();
            });
        };
        dismissRunner[0] = () -> animateCloseSub.accept(null);
        btnCancel.setOnClickListener(v -> dismissRunner[0].run());
        btnEnable.setOnClickListener(v -> animateCloseSub.accept(() -> setAdvanced(true)));

        dialog.setOnDismissListener(d -> {
            Motion.cancelPanelAnim(keyHost);
            if (subDialog == dialog) {
                subDialog = null;
                setPanelContentBlur(false);
            }
        });
        subDialog = dialog;
        keyHost.setAlpha(0f);
        if (!reduce) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * density);
        }
        dialog.show();
        btnEnable.requestFocus();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (subDialog != dialog || !dialog.isShowing() || dismissing[0] || closing || isFinishing() || isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            setPanelContentBlur(true);
            Motion.revealModal(keyHost, density, reduce);
        }));
    }

    private TextView makeDialogPill(String label) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(14f);
        btn.setTypeface(UiFont.get(this, R.string.font_medium));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), 0, dp(16), 0);
        btn.setBackgroundResource(R.drawable.pill_bg);
        btn.setTextColor(getColorStateList(R.color.pill_text));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        Motion.attachPillFocus(btn, 1.06f, 1.5f, density, reducedMotion || !animations);
        return btn;
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size() : indexOf(images, current);
        showFrostedPicker(R.string.wallpaper_label, labels, checked,
                getString(R.string.wallpaper_theme_default), () -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                }, which -> {
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                });
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        showFrostedPicker(R.string.open_on_start_label, labels, checked, null, null, which -> {
            if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
            else {
                OpenOnStart.skipThisBoot(this);
                choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
            }
            refresh();
        });
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            boolean sel = columns == i + 4;
            columnChoices[i].setSelected(sel);
            FocusRingController.configure(columnChoices[i], 1.045f, 1.5f, 14f, sel ? SELECTED_ACCENT : 0);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            boolean sel = rowsBelowDock == i;
            rowChoices[i].setSelected(sel);
            FocusRingController.configure(rowChoices[i], 1.045f, 1.5f, 14f, sel ? SELECTED_ACCENT : 0);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            boolean sel = dockOpacity == DOCK_OPACITIES[i];
            dockChoices[i].setSelected(sel);
            FocusRingController.configure(dockChoices[i], 1.045f, 1.5f, 14f, sel ? SELECTED_ACCENT : 0);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            boolean sel = CLOCK_FORMATS[i].equals(clockFormat);
            clockChoices[i].setSelected(sel);
            FocusRingController.configure(clockChoices[i], 1.045f, 1.5f, 14f, sel ? SELECTED_ACCENT : 0);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        if (openOnStartChoice.getVisibility() == View.VISIBLE) {
            openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        }
        boolean following = !hasOnTvChoices();
        followThemeChoice.setSelected(following);
        FocusRingController.configure(followThemeChoice, 1.018f, 1f, 14f, following ? SELECTED_ACCENT : 0);
        if (focusRing != null) focusRing.refresh();
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
