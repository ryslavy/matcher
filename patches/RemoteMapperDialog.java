package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-app Liquid Glass 2.0 "Learn Remote Button" remapper dialog.
 * Lets any user on any Android TV / Google TV box capture physical remote keys (keyCode + scanCode)
 * directly on the TV without ADB or JSON editing, and bind independent Tap, Double-Tap, and Hold
 * actions to any installed app or built-in Matcher overlay.
 */
final class RemoteMapperDialog {

    static final class TargetOption {
        final String target;
        final String label;
        final boolean isAction;

        TargetOption(String target, String label, boolean isAction) {
            this.target = target;
            this.label = label;
            this.isAction = isAction;
        }
    }

    private static final int MODE_LIST = 0;
    private static final int MODE_CAPTURE = 1;
    private static final int MODE_EDIT = 2;
    private static final int MODE_PICK = 3;

    private final Activity activity;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    private final Runnable onChanged;
    @Nullable private final Consumer<Boolean> onBlurParent;

    private final Dialog dialog;
    private final FrameLayout keyHost;
    private final LinearLayout box;
    private final TextView titleView;
    private final TextView subtitleView;
    private final LinearLayout contentList;
    private final ScrollView scrollView;
    private boolean dismissing;
    private int mode = MODE_LIST;

    // Active edit state
    private String editKeyToken = "";
    private String editKeyLabel = "";
    private String editTap = "";
    private String editDouble = "";
    private String editHold = "";
    private int pickingSlot; // 0 = tap, 1 = double, 2 = hold

    private List<TargetOption> cachedOptions;
    private Map<String, String> targetLabelCache;

    private RemoteMapperDialog(Activity activity, float density, boolean animations, boolean reducedMotion,
                               Runnable onChanged, @Nullable Consumer<Boolean> onBlurParent) {
        this.activity = activity;
        this.density = density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.onChanged = onChanged;
        this.onBlurParent = onBlurParent;

        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(dp(22), dp(18), dp(22), dp(16));
        ExpressiveGlass.applyPanelSurface(box, density, 24f, true, false);

        titleView = new TextView(activity);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setPadding(dp(6), 0, dp(6), dp(4));
        ExpressiveGlass.applyLuminousTitle(titleView, density, false);
        box.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        subtitleView = new TextView(activity);
        subtitleView.setTextColor(0xB3FFFFFF);
        subtitleView.setTextSize(13f);
        subtitleView.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));

        scrollView = new ScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        contentList = new LinearLayout(activity);
        contentList.setOrientation(LinearLayout.VERTICAL);
        contentList.setClipChildren(false);
        contentList.setClipToPadding(false);
        contentList.setPadding(dp(4), dp(2), dp(4), dp(6));
        scrollView.addView(contentList, new FrameLayout.LayoutParams(-1, -2));
        box.addView(scrollView, new LinearLayout.LayoutParams(-1, dp(360)));

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int dialogPad = FocusRingController.wrapInsetPx(density);

        FrameLayout ringHost = FocusRingController.wrapPopup(box, density, animations, reducedMotion);
        keyHost = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (mode == MODE_CAPTURE) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                            disarmCapture();
                            showListMode();
                        }
                        return true;
                    }
                    if (!HomeKeyService.isNavigationOrCancelKey(event.getKeyCode())) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                            onKeyCaptured(event);
                        }
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        handleBack();
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
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setLayout(dp(560) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(0);
        }

        dialog.setOnDismissListener(d -> {
            disarmCapture();
            Motion.cancelPanelAnim(keyHost);
            if (onBlurParent != null) onBlurParent.accept(false);
        });
    }

    static Dialog show(Activity activity, float density, boolean animations, boolean reducedMotion,
                       Runnable onChanged, @Nullable Consumer<Boolean> onBlurParent) {
        RemoteMapperDialog ctrl = new RemoteMapperDialog(
                activity, density, animations, reducedMotion, onChanged, onBlurParent);
        ctrl.open();
        return ctrl.dialog;
    }

    private void open() {
        showListMode();
        boolean reduce = reducedMotion || !animations;
        keyHost.setAlpha(0f);
        if (!reduce) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * density);
        }
        dialog.show();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (!dialog.isShowing() || dismissing || activity.isFinishing() || activity.isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            if (onBlurParent != null) onBlurParent.accept(true);
            Motion.revealModal(keyHost, density, reduce);
        }));
    }

    private void handleBack() {
        if (mode == MODE_PICK) {
            showEditMode();
        } else if (mode == MODE_EDIT) {
            showListMode();
        } else if (mode == MODE_CAPTURE) {
            disarmCapture();
            showListMode();
        } else {
            animateClose();
        }
    }

    private void animateClose() {
        if (dismissing) return;
        dismissing = true;
        disarmCapture();
        if (onBlurParent != null) onBlurParent.accept(false);
        if (!dialog.isShowing()) return;
        boolean reduce = reducedMotion || !animations;
        keyHost.setPivotX(keyHost.getWidth() / 2f);
        keyHost.setPivotY(keyHost.getHeight() / 2f);
        Motion.dismissModal(keyHost, density, reduce, () -> {
            if (dialog.isShowing()) dialog.dismiss();
        });
    }

    private void disarmCapture() {
        HomeKeyService.keyCaptureListener = null;
    }

    private void showListMode() {
        disarmCapture();
        mode = MODE_LIST;
        titleView.setText(R.string.rm_title);
        boolean serviceActive = HomeKeyService.running != null || InputKeyService.running != null;
        subtitleView.setText(serviceActive ? R.string.rm_subtitle_ready : R.string.rm_subtitle_need_a11y);
        contentList.removeAllViews();
        scrollView.scrollTo(0, 0);

        if (!serviceActive) {
            addRow(activity.getString(R.string.rm_open_a11y),
                    activity.getString(R.string.rm_open_a11y_sub),
                    "", 0xFFF59E0B, () -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception ignored) {
                            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        View learnRow = addRow(activity.getString(R.string.rm_learn_btn),
                activity.getString(R.string.rm_learn_sub),
                "+", 0xFF38BDF8, this::showCaptureMode);

        List<FeaturePrefs.RemoteMapping> list = FeaturePrefs.customRemoteMappings(activity);
        if (list.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.rm_empty);
            empty.setTextColor(0x99FFFFFF);
            empty.setTextSize(13f);
            empty.setPadding(dp(8), dp(14), dp(8), dp(6));
            contentList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (FeaturePrefs.RemoteMapping m : list) {
                String title = m.label.isEmpty() ? m.keyToken : m.label;
                String summary = formatMappingSummary(m);
                addRow(title, summary, "", 0, () -> openEditFor(m));
            }
            addRow(activity.getString(R.string.rm_reset_all),
                    activity.getString(R.string.rm_reset_all_sub),
                    "", 0xFFF87171, () -> {
                        FeaturePrefs.clearCustomRemoteMappings(activity);
                        HomeKeyService.reloadRemoteKeys();
                        onChanged.run();
                        Toast.makeText(activity, R.string.rm_reset_done, Toast.LENGTH_SHORT).show();
                        showListMode();
                    });
        }

        applyThemeAndFocus(learnRow);
    }

    private void showCaptureMode() {
        mode = MODE_CAPTURE;
        titleView.setText(R.string.rm_capture_title);
        subtitleView.setText(R.string.rm_capture_hint);
        contentList.removeAllViews();
        scrollView.scrollTo(0, 0);

        TextView wait = new TextView(activity);
        wait.setText(R.string.rm_capture_waiting);
        wait.setTextColor(0xFF93C5FD);
        wait.setTextSize(17f);
        wait.setGravity(Gravity.CENTER);
        wait.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wait.setPadding(dp(12), dp(42), dp(12), dp(36));
        contentList.addView(wait, new LinearLayout.LayoutParams(-1, -2));

        View cancelRow = addRow(activity.getString(R.string.fs_cancel), "", "", 0, () -> {
            disarmCapture();
            showListMode();
        });

        HomeKeyService.keyCaptureListener = event -> {
            onKeyCaptured(event);
            return true;
        };
        applyThemeAndFocus(cancelRow);
    }

    private void onKeyCaptured(KeyEvent event) {
        if (mode != MODE_CAPTURE) return;
        disarmCapture();
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        String token = RemoteKeys.canonicalKeyToken(keyCode, scanCode);
        String label = RemoteKeys.humanKeyLabel(keyCode, scanCode);

        FeaturePrefs.RemoteMapping existing = null;
        for (FeaturePrefs.RemoteMapping m : FeaturePrefs.customRemoteMappings(activity)) {
            if (m.keyToken.equalsIgnoreCase(token)) {
                existing = m;
                break;
            }
        }
        if (existing != null) {
            openEditFor(existing);
        } else {
            editKeyToken = token;
            editKeyLabel = label;
            editTap = "";
            editDouble = "";
            editHold = "";
            showEditMode();
        }
    }

    private void openEditFor(FeaturePrefs.RemoteMapping m) {
        editKeyToken = m.keyToken;
        editKeyLabel = m.label.isEmpty() ? m.keyToken : m.label;
        editTap = m.tap;
        editDouble = m.dbl;
        editHold = m.hold;
        showEditMode();
    }

    private void showEditMode() {
        disarmCapture();
        mode = MODE_EDIT;
        titleView.setText(editKeyLabel);
        subtitleView.setText(activity.getString(R.string.rm_edit_subtitle, editKeyToken));
        contentList.removeAllViews();
        scrollView.scrollTo(0, 0);

        View tapRow = addRow(activity.getString(R.string.rm_slot_tap),
                describeTarget(editTap), "", 0, () -> showPickMode(0));
        addRow(activity.getString(R.string.rm_slot_double),
                describeTarget(editDouble), "", 0, () -> showPickMode(1));
        addRow(activity.getString(R.string.rm_slot_hold),
                describeTarget(editHold), "", 0, () -> showPickMode(2));

        addRow(activity.getString(R.string.folder_save), "", "\u2713", 0xFF4ADE80, () -> {
            FeaturePrefs.RemoteMapping updated = new FeaturePrefs.RemoteMapping(
                    editKeyToken, editKeyLabel, editTap, editDouble, editHold);
            if (updated.isEmpty()) {
                FeaturePrefs.removeCustomRemoteMapping(activity, editKeyToken);
            } else {
                FeaturePrefs.putCustomRemoteMapping(activity, updated);
            }
            HomeKeyService.reloadRemoteKeys();
            onChanged.run();
            Toast.makeText(activity, R.string.rm_saved, Toast.LENGTH_SHORT).show();
            showListMode();
        });

        addRow(activity.getString(R.string.rm_delete_btn), "", "", 0xFFF87171, () -> {
            FeaturePrefs.removeCustomRemoteMapping(activity, editKeyToken);
            HomeKeyService.reloadRemoteKeys();
            onChanged.run();
            showListMode();
        });

        applyThemeAndFocus(tapRow);
    }

    private void showPickMode(int slot) {
        disarmCapture();
        mode = MODE_PICK;
        pickingSlot = slot;
        int slotName = slot == 0 ? R.string.rm_slot_tap
                : (slot == 1 ? R.string.rm_slot_double : R.string.rm_slot_hold);
        titleView.setText(slotName);
        subtitleView.setText(editKeyLabel);
        contentList.removeAllViews();
        scrollView.scrollTo(0, 0);

        String current = slot == 0 ? editTap : (slot == 1 ? editDouble : editHold);
        View focusTarget = null;
        for (TargetOption opt : allTargetOptions()) {
            boolean selected = opt.target.equals(current);
            View row = addRow(opt.label,
                    opt.target.isEmpty() || opt.isAction ? "" : opt.target,
                    selected ? "\u2713" : "",
                    selected ? 0xFF4ADE80 : 0,
                    () -> {
                        if (pickingSlot == 0) editTap = opt.target;
                        else if (pickingSlot == 1) editDouble = opt.target;
                        else editHold = opt.target;
                        showEditMode();
                    });
            if (selected || focusTarget == null) focusTarget = row;
        }

        applyThemeAndFocus(focusTarget);
    }

    private View addRow(String title, String subtitle, String badge, int accentRgb, Runnable onClick) {
        boolean reduce = reducedMotion || !animations;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(accentRgb != 0
                ? ExpressiveGlass.makeAdaptiveChoiceBg(density, 14f, accentRgb)
                : ExpressiveGlass.makeAdaptiveRowBg(density, 14f, true));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(activity);
            s.setText(subtitle);
            s.setTextColor(0xA6FFFFFF);
            s.setTextSize(12f);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.topMargin = dp(2);
            col.addView(s, sLp);
        }

        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        if (badge != null && !badge.isEmpty()) {
            TextView b = new TextView(activity);
            b.setText(badge);
            b.setTextColor(accentRgb != 0 ? (0xFF000000 | accentRgb) : 0xCC93C5FD);
            b.setTextSize(16f);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.leftMargin = dp(10);
            row.addView(b, bLp);
        }

        row.setOnClickListener(v -> {
            Motion.pulseClick(v, animations ? 1.018f : 1f, reduce);
            onClick.run();
        });
        Motion.attachPillFocus(row, 1.018f, 1f, density, reduce);
        FocusRingController.configure(row, 1.018f, 1f, 14f, accentRgb);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (contentList.getChildCount() > 0) lp.topMargin = dp(6);
        contentList.addView(row, lp);
        return row;
    }

    private void applyThemeAndFocus(@Nullable View focusTarget) {
        ExpressiveGlass.applyAdaptiveContentTheme(box);
        if (focusTarget != null) {
            focusTarget.requestFocus();
            focusTarget.post(() -> {
                ExpressiveGlass.invalidateLiquidPanels(box);
                focusTarget.requestFocus();
            });
        }
    }

    private String formatMappingSummary(FeaturePrefs.RemoteMapping m) {
        StringBuilder sb = new StringBuilder();
        if (!m.tap.isEmpty()) {
            sb.append(activity.getString(R.string.rm_short_tap)).append(": ").append(describeTarget(m.tap));
        }
        if (!m.dbl.isEmpty()) {
            if (sb.length() > 0) sb.append("  \u00B7  ");
            sb.append(activity.getString(R.string.rm_short_double)).append(": ").append(describeTarget(m.dbl));
        }
        if (!m.hold.isEmpty()) {
            if (sb.length() > 0) sb.append("  \u00B7  ");
            sb.append(activity.getString(R.string.rm_short_hold)).append(": ").append(describeTarget(m.hold));
        }
        return sb.toString();
    }

    private String describeTarget(String target) {
        if (target == null || target.isEmpty()) return activity.getString(R.string.rm_target_none);
        ensureTargetOptions();
        String label = targetLabelCache.get(target);
        return label != null ? label : target;
    }

    private List<TargetOption> allTargetOptions() {
        ensureTargetOptions();
        return cachedOptions;
    }

    private void ensureTargetOptions() {
        if (cachedOptions != null) return;
        List<TargetOption> list = new ArrayList<>();
        list.add(new TargetOption("", activity.getString(R.string.rm_target_none), true));
        list.addAll(builtInActions(activity));
        list.addAll(listInstalledApps(activity));
        cachedOptions = list;
        targetLabelCache = new LinkedHashMap<>();
        for (TargetOption o : list) targetLabelCache.put(o.target, o.label);
    }

    /** Shared list of built-in Launcher & TV actions (reused by QrConfigServer phone web editor). */
    static List<TargetOption> builtInActions(Context ctx) {
        List<TargetOption> actions = new ArrayList<>();
        actions.add(new TargetOption("control_center", ctx.getString(R.string.cc_title), true));
        actions.add(new TargetOption("library", ctx.getString(R.string.app_library), true));
        actions.add(new TargetOption("recents_overlay", ctx.getString(R.string.recents_title), true));
        actions.add(new TargetOption("discover", ctx.getString(R.string.discover_title), true));
        actions.add(new TargetOption("screensaver", ctx.getString(R.string.cc_screensaver), true));
        actions.add(new TargetOption("inputs", ctx.getString(R.string.inputs), true));
        if (isInstalled(ctx, "io.github.maxhayim.brightness")) {
            actions.add(new TargetOption("io.github.maxhayim.brightness/.QuickActivity",
                    ctx.getString(R.string.cc_brightness), true));
        }
        if (isInstalled(ctx, "io.github.luyzfernando08.sleeptimer")) {
            actions.add(new TargetOption("io.github.luyzfernando08.sleeptimer/.QuickActivity",
                    ctx.getString(R.string.cc_sleep_timer), true));
        }
        actions.add(new TargetOption("settings", ctx.getString(R.string.tv_settings), true));
        actions.add(new TargetOption("wifi", ctx.getString(R.string.quick_network), true));
        actions.add(new TargetOption("mute", ctx.getString(R.string.rm_action_mute), true));
        actions.add(new TargetOption("sleep", ctx.getString(R.string.cc_sleep_tv), true));
        actions.add(new TargetOption("ignore", ctx.getString(R.string.rm_action_ignore), true));
        return actions;
    }

    /** Shared sorted list of launchable TV / Android apps (reused by QrConfigServer phone web editor). */
    static List<TargetOption> listInstalledApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> byPkg = new LinkedHashMap<>();
        String self = ctx.getPackageName();
        Intent[] queries = {
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        };
        for (Intent q : queries) {
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            if (ris == null) continue;
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(self) || byPkg.containsKey(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                String name = lbl != null && lbl.length() > 0 ? lbl.toString().trim() : pkg;
                byPkg.put(pkg, name);
            }
        }
        List<TargetOption> out = new ArrayList<>(byPkg.size());
        for (Map.Entry<String, String> e : byPkg.entrySet()) {
            out.add(new TargetOption(e.getKey(), e.getValue(), false));
        }
        Collections.sort(out, Comparator.comparing(o -> o.label.toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    private static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
