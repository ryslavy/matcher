package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Appends modular opt-in feature rows into {@link LauncherSettingsActivity}'s scroll container:
 * Liquid Glass optics & clock, UI click sounds, OLED screensaver drift, Discover / Watch Next /
 * Trakt / Nuvio, QR phone setup, Home Assistant, Immich photos, Crash Watchdog, GitHub Updates,
 * and safe 1-click loopback ADB stock launcher restore.
 */
final class LauncherFeatureSettings {

    private static final String[] REGIONS = {"CZ", "SK", "DE", "GB", "US"};

    private final Activity activity;
    private final LinearLayout container;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    private final Runnable onPreferenceChanged;
    @Nullable private final Consumer<Boolean> onBlurParent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> refreshers = new ArrayList<>();
    private final List<View> actionRows = new ArrayList<>();
    @Nullable private Dialog activeDialog;

    LauncherFeatureSettings(Activity activity, LinearLayout container, float density,
                            boolean animations, boolean reducedMotion, Runnable onPreferenceChanged) {
        this(activity, container, density, animations, reducedMotion, onPreferenceChanged, null);
    }

    LauncherFeatureSettings(Activity activity, LinearLayout container, float density,
                            boolean animations, boolean reducedMotion, Runnable onPreferenceChanged,
                            @Nullable Consumer<Boolean> onBlurParent) {
        this.activity = activity;
        this.container = container;
        this.density = density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.onPreferenceChanged = onPreferenceChanged;
        this.onBlurParent = onBlurParent;
        buildAll();
    }

    void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    /** Rebuilds adaptive inner-row backgrounds live when the user switches Glass Material style. */
    void refreshTheme() {
        for (View row : actionRows) {
            row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 14f, true));
        }
        refresh();
    }

    void dismissActiveDialog() {
        if (activeDialog != null) {
            try { activeDialog.dismiss(); } catch (Exception ignored) { }
            activeDialog = null;
        }
    }

    private void buildAll() {
        // ---- Section 1: Liquid Glass & Experience ----
        addSectionHeader(R.string.fs_sec_glass);
        addToggleRow(R.string.fs_liquid_clock,
                () -> FeaturePrefs.isLiquidClockEnabled(activity),
                v -> {
                    FeaturePrefs.setLiquidClockEnabled(activity, v);
                    onPreferenceChanged.run();
                });
        addToggleRow(R.string.fs_home_liquid,
                () -> FeaturePrefs.isHomeLiquidOpticsEnabled(activity),
                v -> {
                    FeaturePrefs.setHomeLiquidOpticsEnabled(activity, v);
                    ExpressiveGlass.setHomeLiquidOpticsEnabled(v);
                    onPreferenceChanged.run();
                });
        addToggleRow(R.string.fs_home_backdrop_blur,
                () -> FeaturePrefs.isHomeBackdropBlurEnabled(activity),
                v -> {
                    FeaturePrefs.setHomeBackdropBlurEnabled(activity, v);
                    MainActivity.applyHomeBackdropBlurPreference(v);
                });
        addActionRow(R.string.fs_glass_material,
                () -> glassMaterialLabel(FeaturePrefs.glassMaterial(activity)),
                this::showGlassStylePicker);
        addActionRow(R.string.fs_remote_buttons, () -> {
            int count = FeaturePrefs.customRemoteMappings(activity).size();
            return count > 0 ? String.valueOf(count) : "+";
        }, () -> {
            dismissActiveDialog();
            activeDialog = RemoteMapperDialog.show(
                    activity, density, animations, reducedMotion, this::refresh, onBlurParent);
        });
        addToggleRow(R.string.fs_ui_sounds,
                () -> FeaturePrefs.isUiSoundsEnabled(activity),
                v -> {
                    FeaturePrefs.setUiSoundsEnabled(activity, v);
                    UiClickSound.refreshEnabled(activity);
                });
        addToggleRow(R.string.fs_oled_drift,
                () -> FeaturePrefs.isOledDriftEnabled(activity),
                v -> FeaturePrefs.setOledDriftEnabled(activity, v));
        addActionRow(R.string.fs_aerial_quality,
                () -> activity.getString(FeaturePrefs.isAerial4kEnabled(activity)
                        ? R.string.fs_aerial_4k : R.string.fs_aerial_1080p),
                () -> {
                    FeaturePrefs.setAerial4kEnabled(activity, !FeaturePrefs.isAerial4kEnabled(activity));
                    refresh();
                });
        addActionRow(R.string.fs_aerial_favorites_only, () -> {
            int count = AerialCatalog.favoriteCount(activity);
            boolean on = FeaturePrefs.isAerialFavoritesOnly(activity);
            String state = activity.getString(on ? R.string.cc_on : R.string.cc_off);
            return count > 0 ? state + " (\u2605 " + count + ")" : state;
        }, () -> {
            FeaturePrefs.setAerialFavoritesOnly(activity, !FeaturePrefs.isAerialFavoritesOnly(activity));
            refresh();
        });

        // ---- Section 2: Content & Discovery ----
        addSectionHeader(R.string.fs_sec_content);
        addActionRow(R.string.fs_open_discover, () -> "", () -> {
            OverlayActivity.launch(activity, OverlayActivity.MODE_DISCOVER, true);
            activity.finish();
        });
        addActionRow(R.string.fs_watch_next, () -> {
            boolean on = FeaturePrefs.isWatchNextEnabled(activity);
            if (!on) return activity.getString(R.string.cc_off);
            return WatchNextRepository.hasPermission(activity)
                    ? activity.getString(R.string.cc_on)
                    : activity.getString(R.string.discover_grant_adb_btn);
        }, () -> {
            if (!FeaturePrefs.isWatchNextEnabled(activity)) {
                FeaturePrefs.setWatchNextEnabled(activity, true);
                refresh();
            } else if (!WatchNextRepository.hasPermission(activity)) {
                grantWatchNextViaAdb();
            } else {
                FeaturePrefs.setWatchNextEnabled(activity, false);
                refresh();
            }
        });
        addToggleRow(R.string.fs_open_nuvio,
                () -> FeaturePrefs.openPicksInNuvio(activity),
                v -> FeaturePrefs.setOpenPicksInNuvio(activity, v));
        addToggleRow(R.string.fs_watch_saver,
                () -> FeaturePrefs.watchFromScreensaver(activity),
                v -> FeaturePrefs.setWatchFromScreensaver(activity, v));
        addToggleRow(R.string.fs_hide_animation,
                () -> FeaturePrefs.hideAnimationPicks(activity),
                v -> FeaturePrefs.setHideAnimationPicks(activity, v));
        addActionRow(R.string.fs_region,
                () -> FeaturePrefs.tmdbRegion(activity),
                () -> {
                    String cur = FeaturePrefs.tmdbRegion(activity);
                    int idx = 0;
                    for (int i = 0; i < REGIONS.length; i++) {
                        if (REGIONS[i].equalsIgnoreCase(cur)) {
                            idx = (i + 1) % REGIONS.length;
                            break;
                        }
                    }
                    FeaturePrefs.setTmdbRegion(activity, REGIONS[idx]);
                    refresh();
                });
        addActionRow(R.string.fs_trakt,
                () -> TraktClient.isSignedIn(activity)
                        ? activity.getString(R.string.cc_on)
                        : (TraktClient.hasClientId(activity) ? activity.getString(R.string.cc_off) : "QR"),
                this::handleTraktRowClick);

        // ---- Section 3: Smart Home, Photos & Phone Setup ----
        addSectionHeader(R.string.fs_sec_smart);
        addActionRow(R.string.fs_qr_setup, () -> "QR", () -> {
            dismissActiveDialog();
            activeDialog = QrSetupDialog.show(activity, () -> {
                ImmichSyncManager.scheduleIfEnabled(activity);
                onPreferenceChanged.run();
                refresh();
            }, onBlurParent);
        });
        addActionRow(R.string.fs_ha_toggle, () -> {
            boolean on = FeaturePrefs.isHomeAssistantEnabled(activity);
            int n = HomeAssistantClient.parseEntities(FeaturePrefs.homeAssistantEntities(activity)).size();
            if (!on) return activity.getString(R.string.cc_off);
            return n > 0 ? (activity.getString(R.string.cc_on) + " (" + n + ")") : activity.getString(R.string.cc_on);
        }, () -> {
            FeaturePrefs.setHomeAssistantEnabled(activity, !FeaturePrefs.isHomeAssistantEnabled(activity));
            refresh();
        });
        addActionRow(R.string.fs_immich_toggle, () -> {
            boolean on = FeaturePrefs.isImmichEnabled(activity);
            if (!on) return activity.getString(R.string.cc_off);
            int photos = ImmichSyncManager.listLocalPhotos(activity).size();
            return activity.getString(R.string.cc_on) + " (" + photos + ")";
        }, () -> {
            boolean next = !FeaturePrefs.isImmichEnabled(activity);
            FeaturePrefs.setImmichEnabled(activity, next);
            ImmichSyncManager.scheduleIfEnabled(activity);
            refresh();
        });
        addActionRow(R.string.fs_immich_album, () -> {
            String name = FeaturePrefs.immichAlbumName(activity);
            return name.isEmpty() ? activity.getString(R.string.fs_immich_all_albums) : name;
        }, this::pickImmichAlbum);
        addActionRow(R.string.fs_immich_sync, () -> "", () -> {
            Toast.makeText(activity, "Immich…", Toast.LENGTH_SHORT).show();
            ImmichSyncManager.syncNow(activity, (added, total, err) -> {
                String msg = err != null ? err : ("Immich: " + total + " fotek");
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        });

        // ---- Section 4: Safety & Recovery ----
        addSectionHeader(R.string.fs_sec_system);
        addActionRow(R.string.fs_check_updates,
                () -> "v" + SelfUpdater.installedVersionName(activity),
                this::showUpdatesDialog);
        addToggleRow(R.string.fs_watchdog,
                () -> FeaturePrefs.isCrashWatchdogEnabled(activity),
                v -> FeaturePrefs.setCrashWatchdogEnabled(activity, v));
        addActionRow(R.string.fs_restore_stock, () -> "ADB", this::confirmRestoreStockLauncher);
    }

    private void addSectionHeader(int titleRes) {
        TextView tv = new TextView(activity);
        tv.setText(titleRes);
        tv.setTextColor(0x99FFFFFF);
        tv.setTextSize(12.5f);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.08f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(6);
        lp.leftMargin = dp(4);
        container.addView(tv, lp);
    }

    private interface BoolSupplier { boolean get(); }
    private interface BoolConsumer { void accept(boolean v); }
    private interface StrSupplier { String get(); }

    private void addToggleRow(int labelRes, BoolSupplier getter, BoolConsumer setter) {
        addActionRow(labelRes,
                () -> activity.getString(getter.get() ? R.string.cc_on : R.string.cc_off),
                () -> {
                    setter.accept(!getter.get());
                    refresh();
                });
    }

    private void addActionRow(int labelRes, StrSupplier valueSupplier, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(ExpressiveGlass.makeAdaptiveRowBg(density, 14f, true));
        actionRows.add(row);

        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView value = new TextView(activity);
        value.setTextColor(0xCC93C5FD);
        value.setTextSize(13.5f);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));

        Runnable update = () -> {
            String text = valueSupplier.get();
            value.setText(text);
            value.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        };
        refreshers.add(update);
        update.run();

        row.setOnClickListener(v -> {
            Motion.pulseClick(v, animations ? 1.018f : 1f, reducedMotion || !animations);
            onClick.run();
        });
        if (animations) {
            Motion.attachPillFocus(row, 1.018f, 1f, density, reducedMotion);
            FocusRingController.configure(row, 1.018f, 1f, 14f, 0);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        container.addView(row, lp);
    }

    private void grantWatchNextViaAdb() {
        Toast.makeText(activity, R.string.adb_connecting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try {
                LocalAdbClient.grantTvPermissions(activity, () -> main.post(() ->
                        Toast.makeText(activity, R.string.adb_accept_prompt, Toast.LENGTH_LONG).show()));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fErr = err;
            main.post(() -> {
                if (fErr == null) {
                    Toast.makeText(activity, R.string.discover_grant_success, Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(activity, fErr, Toast.LENGTH_LONG).show();
                }
            });
        }, "adb-grant-watchnext").start();
    }

    private void handleTraktRowClick() {
        if (TraktClient.isSignedIn(activity)) {
            TraktClient.signOut(activity);
            Toast.makeText(activity, R.string.fs_trakt_signed_out, Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (!TraktClient.hasClientId(activity)) {
            dismissActiveDialog();
            activeDialog = QrSetupDialog.show(activity, this::refresh, onBlurParent);
            return;
        }
        Toast.makeText(activity, "Trakt…", Toast.LENGTH_SHORT).show();
        TraktClient.startDeviceSignIn(activity, new TraktClient.DeviceSignInListener() {
            @Override
            public void onCodeReady(String verificationUrl, String userCode) {
                main.post(() -> Toast.makeText(activity,
                        activity.getString(R.string.fs_trakt_code_msg, verificationUrl, userCode),
                        Toast.LENGTH_LONG).show());
            }

            @Override
            public void onSuccess(String username) {
                main.post(() -> {
                    Toast.makeText(activity,
                            activity.getString(R.string.fs_trakt_signed_in, username),
                            Toast.LENGTH_LONG).show();
                    refresh();
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void pickImmichAlbum() {
        ImmichSyncManager.loadAlbums(activity, (albums, err) -> {
            if (err != null) {
                Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                return;
            }
            if (albums.isEmpty()) {
                FeaturePrefs.setImmichAlbum(activity, "", "");
                refresh();
                return;
            }
            // Cycle cleanly through "All photos" + fetched albums on TV remote OK
            String curId = FeaturePrefs.immichAlbumId(activity);
            int curIdx = -1;
            for (int i = 0; i < albums.size(); i++) {
                if (albums.get(i).id.equals(curId)) {
                    curIdx = i;
                    break;
                }
            }
            int nextIdx = curIdx + 1;
            if (nextIdx >= albums.size()) {
                FeaturePrefs.setImmichAlbum(activity, "", "");
            } else {
                ImmichSyncManager.Album a = albums.get(nextIdx);
                FeaturePrefs.setImmichAlbum(activity, a.id, a.name);
            }
            refresh();
        });
    }

    private void showUpdatesDialog() {
        dismissActiveDialog();
        final boolean reduce = reducedMotion || !animations;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(dp(22), dp(18), dp(22), dp(16));
        ExpressiveGlass.applyPanelSurface(box, density, 24f, true, false);

        TextView title = new TextView(activity);
        title.setText(R.string.fs_updates_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(4));
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView statusTv = new TextView(activity);
        statusTv.setText(activity.getString(R.string.fs_updates_current,
                "v" + SelfUpdater.installedVersionName(activity), FeaturePrefs.updaterRepo(activity)));
        statusTv.setTextColor(0xB3FFFFFF);
        statusTv.setTextSize(13f);
        statusTv.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(statusTv, new LinearLayout.LayoutParams(-1, -2));

        TextView notesTv = new TextView(activity);
        notesTv.setTextColor(0xCCFFFFFF);
        notesTv.setTextSize(12.5f);
        notesTv.setPadding(dp(8), 0, dp(8), dp(10));
        notesTv.setVisibility(View.GONE);
        box.addView(notesTv, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(dp(4), dp(2), dp(4), dp(4));
        box.addView(rows, new LinearLayout.LayoutParams(-1, -2));

        final Dialog dlg = new Dialog(activity);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int dialogPad = FocusRingController.wrapInsetPx(density);
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];

        FrameLayout ringHost = FocusRingController.wrapPopup(box, density, animations, reducedMotion);
        FrameLayout keyHost = new FrameLayout(activity) {
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
        dlg.setContentView(keyHost);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setLayout(dp(540) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(0);
        }

        Runnable animateClose = () -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            if (onBlurParent != null) onBlurParent.accept(false);
            if (!dlg.isShowing()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dlg.isShowing()) dlg.dismiss();
            });
        };
        dismissRunner[0] = animateClose;

        final SelfUpdater.CheckResult[] latest = {null};
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            rows.removeAllViews();
            SelfUpdater.CheckResult res = latest[0];
            View firstRow = null;

            View checkRow = makeModalRow(rows, activity.getString(R.string.fs_updates_check_now), "", 0xFF38BDF8, () -> {
                statusTv.setText(R.string.fs_updates_checking);
                SelfUpdater.checkNow(activity, r -> {
                    if (!dlg.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    latest[0] = r;
                    if (r.error != null) {
                        statusTv.setText(r.error);
                        notesTv.setVisibility(View.GONE);
                    } else if (r.updateAvailable) {
                        statusTv.setText(activity.getString(R.string.fs_update_found, r.latestTag));
                        if (!r.releaseNotes.isEmpty()) {
                            notesTv.setText(r.releaseNotes);
                            notesTv.setVisibility(View.VISIBLE);
                        }
                    } else {
                        statusTv.setText(activity.getString(R.string.fs_update_uptodate, r.latestTag));
                        notesTv.setVisibility(View.GONE);
                    }
                    rebuild[0].run();
                });
            });
            firstRow = checkRow;

            if (res != null && res.updateAvailable && res.apkUrl != null) {
                View installRow = makeModalRow(rows,
                        activity.getString(R.string.fs_updates_install_launcher, res.latestTag),
                        res.apkSize > 0 ? String.format(java.util.Locale.US, "%.1f MB", res.apkSize / (1024f * 1024f)) : "",
                        0xFF4ADE80, () -> {
                            statusTv.setText(R.string.fs_updates_downloading);
                            SelfUpdater.downloadAndInstall(activity, res, (launched, err) -> {
                                if (!launched && err != null) {
                                    statusTv.setText(err);
                                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                                } else {
                                    animateClose.run();
                                }
                            });
                        });
                firstRow = installRow;
            }
            if (res != null && !res.addonUpdates.isEmpty()) {
                for (SelfUpdater.AddonUpdate au : res.addonUpdates) {
                    makeModalRow(rows,
                            activity.getString(R.string.fs_updates_install_addon, au.displayName, au.version),
                            "", 0xFF38BDF8, () -> {
                                statusTv.setText(R.string.fs_updates_downloading);
                                SelfUpdater.downloadAndInstallAddon(activity, au, (launched, err) -> {
                                    if (!launched && err != null) {
                                        statusTv.setText(err);
                                        Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                                    }
                                });
                            });
                }
            }

            boolean autoOn = FeaturePrefs.isAutoUpdateEnabled(activity);
            makeModalRow(rows,
                    activity.getString(R.string.fs_updates_weekly),
                    activity.getString(autoOn ? R.string.cc_on : R.string.cc_off),
                    0, () -> {
                        boolean next = !FeaturePrefs.isAutoUpdateEnabled(activity);
                        FeaturePrefs.setAutoUpdateEnabled(activity, next);
                        SelfUpdater.scheduleWeeklyIfEnabled(activity);
                        rebuild[0].run();
                    });

            ExpressiveGlass.applyAdaptiveContentTheme(box);
            if (firstRow != null) {
                final View f = firstRow;
                f.requestFocus();
                f.post(() -> ExpressiveGlass.invalidateLiquidPanels(box));
            }
        };

        rebuild[0].run();

        dlg.setOnDismissListener(d1 -> {
            Motion.cancelPanelAnim(keyHost);
            if (activeDialog == dlg) activeDialog = null;
            if (onBlurParent != null) onBlurParent.accept(false);
        });

        activeDialog = dlg;
        keyHost.setAlpha(0f);
        if (!reduce) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * density);
        }
        dlg.show();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (activeDialog != dlg || !dlg.isShowing() || dismissing[0]
                    || activity.isFinishing() || activity.isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            if (onBlurParent != null) onBlurParent.accept(true);
            Motion.revealModal(keyHost, density, reduce);
        }));
    }

    private View makeModalRow(LinearLayout parent, String labelText, String rightText, int accentRgb, Runnable onClick) {
        boolean reduce = reducedMotion || !animations;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(accentRgb != 0
                ? ExpressiveGlass.makeAdaptiveChoiceBg(density, 14f, accentRgb)
                : ExpressiveGlass.makeAdaptiveRowBg(density, 14f, true));

        TextView lbl = new TextView(activity);
        lbl.setText(labelText);
        lbl.setTextColor(Color.WHITE);
        lbl.setTextSize(14.5f);
        lbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));

        if (rightText != null && !rightText.isEmpty()) {
            TextView r = new TextView(activity);
            r.setText(rightText);
            r.setTextColor(accentRgb != 0 ? (0xFF000000 | accentRgb) : 0xCC93C5FD);
            r.setTextSize(13f);
            r.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-2, -2);
            rLp.leftMargin = dp(10);
            row.addView(r, rLp);
        }

        row.setOnClickListener(v -> {
            Motion.pulseClick(v, animations ? 1.018f : 1f, reduce);
            onClick.run();
        });
        Motion.attachPillFocus(row, 1.018f, 1f, density, reduce);
        FocusRingController.configure(row, 1.018f, 1f, 14f, accentRgb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (parent.getChildCount() > 0) lp.topMargin = dp(6);
        parent.addView(row, lp);
        return row;
    }

    private String glassMaterialLabel(String key) {
        if (FeaturePrefs.GLASS_OBSIDIAN.equals(key)) return activity.getString(R.string.glass_style_obsidian);
        if (FeaturePrefs.GLASS_FROST.equals(key)) return activity.getString(R.string.glass_style_frost);
        if (FeaturePrefs.GLASS_CLEAR.equals(key)) return activity.getString(R.string.glass_style_clear);
        if (FeaturePrefs.GLASS_CHROMATIC.equals(key)) return activity.getString(R.string.glass_style_chromatic);
        if (FeaturePrefs.GLASS_AUTO.equals(key)) return activity.getString(R.string.glass_style_auto);
        return activity.getString(R.string.glass_style_tonal);
    }

    private void showGlassStylePicker() {
        dismissActiveDialog();
        final String[] keys = {
                FeaturePrefs.GLASS_TONAL,
                FeaturePrefs.GLASS_OBSIDIAN,
                FeaturePrefs.GLASS_FROST,
                FeaturePrefs.GLASS_CLEAR,
                FeaturePrefs.GLASS_CHROMATIC,
                FeaturePrefs.GLASS_AUTO
        };
        final int[] titles = {
                R.string.glass_style_tonal,
                R.string.glass_style_obsidian,
                R.string.glass_style_frost,
                R.string.glass_style_clear,
                R.string.glass_style_chromatic,
                R.string.glass_style_auto
        };
        final int[] subs = {
                R.string.glass_style_tonal_sub,
                R.string.glass_style_obsidian_sub,
                R.string.glass_style_frost_sub,
                R.string.glass_style_clear_sub,
                R.string.glass_style_chromatic_sub,
                R.string.glass_style_auto_sub
        };
        String currentKey = FeaturePrefs.glassMaterial(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        ExpressiveGlass.applyPanelSurface(box, density, 24f, true, false);

        TextView title = new TextView(activity);
        title.setText(R.string.fs_glass_material_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(8), 0, dp(8), dp(10));
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(dp(4), dp(2), dp(4), dp(4));
        box.addView(rows, new LinearLayout.LayoutParams(-1, -2));

        final Dialog dlg = new Dialog(activity);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        final int dialogPad = FocusRingController.wrapInsetPx(density);
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];

        FrameLayout ringHost = FocusRingController.wrapPopup(box, density, animations, reducedMotion);
        FrameLayout keyHost = new FrameLayout(activity) {
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
        dlg.setContentView(keyHost);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setLayout(dp(500) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(0);
        }

        Consumer<Runnable> animateClose = afterDismiss -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            if (onBlurParent != null) onBlurParent.accept(false);
            if (!dlg.isShowing()) {
                if (afterDismiss != null) afterDismiss.run();
                return;
            }
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dlg.isShowing()) dlg.dismiss();
                if (afterDismiss != null) afterDismiss.run();
            });
        };
        dismissRunner[0] = () -> animateClose.accept(null);

        int focusIdx = 0;
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            boolean selected = key.equals(currentKey);
            if (selected) focusIdx = i;

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setSelected(selected);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(ExpressiveGlass.makeAdaptiveChoiceBg(density, 14f, 0xFF4ADE80));

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView t = new TextView(activity);
            t.setText(titles[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(15f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            col.addView(t);

            TextView sub = new TextView(activity);
            sub.setText(subs[i]);
            sub.setTextColor(0xA6FFFFFF);
            sub.setTextSize(12f);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.topMargin = dp(2);
            col.addView(sub, sLp);

            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            if (selected) {
                TextView check = new TextView(activity);
                check.setText("\u2713");
                check.setTextColor(0xFF4ADE80);
                check.setTextSize(16f);
                check.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                LinearLayout.LayoutParams ckLp = new LinearLayout.LayoutParams(-2, -2);
                ckLp.leftMargin = dp(10);
                row.addView(check, ckLp);
            }

            row.setOnClickListener(v -> animateClose.accept(() -> {
                MainActivity.applyGlassMaterialPreference(key);
                refresh();
            }));
            Motion.attachPillFocus(row, 1.018f, 1f, density, reduce);
            FocusRingController.configure(row, 1.018f, 1f, 14f, selected ? 0xFF4ADE80 : 0);

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rLp.topMargin = dp(6);
            rows.addView(row, rLp);
        }
        ExpressiveGlass.applyAdaptiveContentTheme(box);

        dlg.setOnDismissListener(d1 -> {
            Motion.cancelPanelAnim(keyHost);
            if (activeDialog == dlg) activeDialog = null;
            if (onBlurParent != null) onBlurParent.accept(false);
        });

        activeDialog = dlg;
        keyHost.setAlpha(0f);
        if (!reduce) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * density);
        }
        dlg.show();
        if (focusIdx < rows.getChildCount()) rows.getChildAt(focusIdx).requestFocus();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (activeDialog != dlg || !dlg.isShowing() || dismissing[0]
                    || activity.isFinishing() || activity.isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            if (onBlurParent != null) onBlurParent.accept(true);
            Motion.revealModal(keyHost, density, reduce);
        }));
    }

    private void confirmRestoreStockLauncher() {
        dismissActiveDialog();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(dp(24), dp(20), dp(24), dp(18));
        ExpressiveGlass.applyPanelSurface(box, density, 24f, true, false);

        TextView title = new TextView(activity);
        title.setText(R.string.fs_restore_confirm_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ExpressiveGlass.applyLuminousTitle(title, density, false);
        box.addView(title);

        TextView msg = new TextView(activity);
        msg.setText(R.string.fs_restore_confirm_msg);
        msg.setTextColor(0xD0DCE8F8);
        msg.setTextSize(14f);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-1, -2);
        mLp.topMargin = dp(8);
        mLp.bottomMargin = dp(18);
        box.addView(msg, mLp);

        final Dialog dlg = new Dialog(activity);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean reduce = reducedMotion || !animations;
        final int dialogPad = FocusRingController.wrapInsetPx(density);
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);

        TextView cancel = makeDialogBtn(R.string.fs_cancel, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.rightMargin = dp(10);
        buttons.addView(cancel, cLp);

        TextView confirm = makeDialogBtn(R.string.fs_restore_ok, 0xFFEF4444);
        buttons.addView(confirm);
        box.addView(buttons);
        ExpressiveGlass.applyAdaptiveContentTheme(box);

        FrameLayout ringHost = FocusRingController.wrapPopup(box, density, animations, reducedMotion);
        FrameLayout keyHost = new FrameLayout(activity) {
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
        dlg.setContentView(keyHost);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setLayout(dp(460) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(0);
        }

        Consumer<Runnable> animateClose = afterDismiss -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            if (onBlurParent != null) onBlurParent.accept(false);
            if (!dlg.isShowing()) {
                if (afterDismiss != null) afterDismiss.run();
                return;
            }
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, density, reduce, () -> {
                if (dlg.isShowing()) dlg.dismiss();
                if (afterDismiss != null) afterDismiss.run();
            });
        };
        dismissRunner[0] = () -> animateClose.accept(null);

        cancel.setOnClickListener(v -> dismissRunner[0].run());
        confirm.setOnClickListener(v -> animateClose.accept(() -> {
            Toast.makeText(activity, R.string.adb_connecting, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String err = null;
                try {
                    LocalAdbClient.restoreStockLauncher(activity, () -> main.post(() ->
                            Toast.makeText(activity, R.string.adb_accept_prompt, Toast.LENGTH_LONG).show()));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fErr = err;
                main.post(() -> Toast.makeText(activity,
                        fErr != null ? fErr : "Google TV Launcher obnoven \u2713",
                        Toast.LENGTH_LONG).show());
            }, "adb-restore-stock").start();
        }));

        dlg.setOnDismissListener(d1 -> {
            Motion.cancelPanelAnim(keyHost);
            if (activeDialog == dlg) activeDialog = null;
            if (onBlurParent != null) onBlurParent.accept(false);
        });

        activeDialog = dlg;
        keyHost.setAlpha(0f);
        if (!reduce) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * density);
        }
        dlg.show();
        cancel.requestFocus();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (activeDialog != dlg || !dlg.isShowing() || dismissing[0]
                    || activity.isFinishing() || activity.isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            if (onBlurParent != null) onBlurParent.accept(true);
            Motion.revealModal(keyHost, density, reduce);
        }));
    }

    private TextView makeDialogBtn(int textRes, int accentColor) {
        TextView tv = new TextView(activity);
        tv.setText(textRes);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setClickable(true);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        tv.setBackground(ExpressiveGlass.makeAdaptiveTileBg(density, 16f, accentColor));
        Motion.attachPillFocus(tv, 1.06f, 1.5f, density, reducedMotion || !animations);
        FocusRingController.configure(tv, 1.06f, 1.5f, 16f, accentColor);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
