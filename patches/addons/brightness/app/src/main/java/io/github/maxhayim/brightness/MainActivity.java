package io.github.maxhayim.brightness;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The tile and module card open this: one list, the first row the master switch, so normal screen is a single OK
 * away. Left and Right change a value and the layer follows at once, over this screen too, as a preview.
 * No text fields and nothing to type.
 */
public class MainActivity extends Activity {
    private GlassKit.Modal modal;
    private Dimming.Settings s;
    private LinearLayout rows;
    private TextView status;
    private Row permissionRow, onRow, brightnessRow, nightRow, nightBrightnessRow, warmthRow, startRow, endRow;
    private TvPicture tv;
    private boolean tvAvailable;
    /** The TV's "allow" prompt while this app may not change the picture yet; null once allowed. */
    private volatile PendingIntent tvAsk;
    private Row tvRow, tvBacklightRow, tvContrastRow, tvBrightnessRow, tvDynamicRow, tvTakeRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modal = GlassKit.installModal(this, 740, 490, false, 0x3D2E18);
        LinearLayout page = modal.panel;

        TextView title = GlassKit.makeTitle(this, getString(R.string.app_name));
        title.setPadding(px(6), 0, px(6), 0);
        status = GlassKit.makeSubtitle(this, "");
        status.setPadding(px(6), px(3), px(6), 0);
        page.addView(title);
        page.addView(status);
        page.addView(GlassKit.makeDivider(this, 10, 4));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(20));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(px(6), px(6), px(6), px(10));
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tv = new TvPicture(this);
        tvAvailable = tv.available();
        build();
        modal.reveal();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (modal != null) modal.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
        // The overlay permission may have been granted in Settings or over ADB meanwhile.
        s = Prefs.load(this);
        boolean allowed = DimService.canDraw(this);
        if ((permissionRow == null) == !allowed) build();
        refresh();
        DimService.start(this);
        if (tvAvailable) {
            // A binder call into the TV's settings app, which may have to start: off the main thread.
            new Thread(() -> {
                PendingIntent ask = tv.permissionRequest();
                runOnUiThread(() -> {
                    boolean changed = (ask == null) != (tvAsk == null);
                    tvAsk = ask;
                    if (changed && !isFinishing()) build();
                });
            }).start();
        }
    }

    @Override
    protected void onPause() {
        if (modal != null) modal.onPause();
        super.onPause();
    }

    private void build() {
        s = Prefs.load(this);
        rows.removeAllViews();
        permissionRow = null;
        if (!DimService.canDraw(this)) {
            permissionRow = add(new Row(this, getString(R.string.perm_title),
                    getString(R.string.perm_detail), false, this::askPermission, null));
            permissionRow.setValue(getString(R.string.perm_denied));
        }
        onRow = add(new Row(this, getString(R.string.row_dimming), getString(R.string.row_dimming_detail),
                false, () -> {
                    s.on = !s.on;
                    changed();
                }, d -> {
                    s.on = d > 0;
                    changed();
                }));
        brightnessRow = add(new Row(this, getString(R.string.row_brightness), getString(R.string.row_brightness_detail), true, null,
                d -> {
                    s.brightness = Dimming.stepBrightness(s.brightness, d);
                    changed();
                }));
        nightRow = add(new Row(this, getString(R.string.row_night_mode), null, false, () -> stepNight(1), this::stepNight));
        nightBrightnessRow = add(new Row(this, getString(R.string.row_night_brightness), getString(R.string.row_night_brightness_detail), true,
                null, d -> {
                    s.nightBrightness = Dimming.stepBrightness(s.nightBrightness, d);
                    changed();
                }));
        warmthRow = add(new Row(this, getString(R.string.row_warmth), getString(R.string.row_warmth_detail), true, null, d -> {
            s.warmth += d * Dimming.WARMTH_STEP;
            changed();
        }));
        startRow = add(new Row(this, getString(R.string.row_night_starts), getString(R.string.row_night_starts_detail), false, null,
                d -> {
                    s.nightStart += d * Dimming.TIME_STEP;
                    changed();
                }));
        endRow = add(new Row(this, getString(R.string.row_night_ends), getString(R.string.row_night_ends_detail), false, null, d -> {
            s.nightEnd += d * Dimming.TIME_STEP;
            changed();
        }));
        if (tvAvailable) buildTvRows();
        (permissionRow != null ? permissionRow : onRow).requestFocus();
    }

    /** The TV's own picture, on TVs whose picture menu allows it: the light the dimming layer can't take away. */
    private void buildTvRows() {
        tvRow = add(new Row(this, getString(R.string.row_tv_picture), getString(R.string.row_tv_picture_detail), false, () -> {
                    s.tvPicture = !s.tvPicture;
                    changed();
                }, d -> {
                    s.tvPicture = d > 0;
                    changed();
                }));
        if (tvAsk != null) {
            Row ask = add(new Row(this, getString(R.string.row_tv_perm),
                    getString(R.string.row_tv_perm_detail), false, () -> {
                        try {
                            tvAsk.send();
                        } catch (PendingIntent.CanceledException ignored) {
                            // Asked again on the next open.
                        }
                    }, null));
            ask.setValue(getString(R.string.perm_denied));
        }
        tvBacklightRow = add(new Row(this, getString(R.string.row_tv_backlight), getString(R.string.row_tv_backlight_detail),
                true, null, d -> {
                    s.tvNight = s.tvNight.withBacklight(Dimming.stepPercent(s.tvNight.backlight, d));
                    changed();
                }));
        tvContrastRow = add(new Row(this, getString(R.string.row_tv_contrast), getString(R.string.row_tv_contrast_detail), true, null,
                d -> {
                    s.tvNight = s.tvNight.withContrast(Dimming.stepPercent(s.tvNight.contrast, d));
                    changed();
                }));
        tvBrightnessRow = add(new Row(this, getString(R.string.row_tv_brightness), getString(R.string.row_tv_brightness_detail),
                true, null, d -> {
                    s.tvNight = s.tvNight.withBrightness(Dimming.stepPercent(s.tvNight.brightness, d));
                    changed();
                }));
        tvDynamicRow = add(new Row(this, getString(R.string.row_tv_dynamic), getString(R.string.row_tv_dynamic_detail),
                false, () -> {
                    s.tvNight = s.tvNight.withDynamicBacklight(!s.tvNight.dynamicBacklight);
                    changed();
                }, null));
        tvTakeRow = add(new Row(this, getString(R.string.row_tv_take),
                getString(R.string.row_tv_take_detail), false, this::takeTvPicture, null));
    }

    private void takeTvPicture() {
        tvTakeRow.setValue(getString(R.string.val_reading));
        new Thread(() -> {
            Dimming.Picture now = tv.read();
            runOnUiThread(() -> {
                if (now == null) {
                    tvTakeRow.setValue(getString(R.string.perm_denied));
                    return;
                }
                s.tvNight = now;
                changed();
                tvTakeRow.setValue(getString(R.string.val_taken));
            });
        }).start();
    }

    private Row add(Row r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (rows.getChildCount() > 0) lp.topMargin = px(8);
        rows.addView(r, lp);
        return r;
    }

    /** Night mode picked here; it replaces whatever the one-press toggle remembered. */
    private void stepNight(int d) {
        s.night = Dimming.Night.values()[(s.night.ordinal() + d + 3) % 3];
        Prefs.setNightBeforeToggle(this, null);
        changed();
    }

    /** Saves, which the running service sees at once; starts it when the new settings need it. */
    private void changed() {
        Prefs.save(this, s);
        refresh();
        DimService.start(this);
    }

    private void refresh() {
        boolean h24 = DateFormat.is24HourFormat(this);
        int minute = DimService.minuteNow();
        onRow.setValue(getString(s.on ? R.string.val_on : R.string.val_off));
        onRow.setAccentActive(s.on);
        brightnessRow.setValue(s.brightness + " %");
        brightnessRow.setBar(fraction(s.brightness));
        nightRow.setValue(getString(s.night == Dimming.Night.OFF ? R.string.val_off
                : s.night == Dimming.Night.ON ? R.string.val_on : R.string.val_scheduled));
        nightRow.setAccentActive(s.night != Dimming.Night.OFF);
        nightRow.setDetail(getString(s.night == Dimming.Night.SCHEDULED
                ? R.string.night_detail_scheduled
                : s.night == Dimming.Night.ON ? R.string.night_detail_on : R.string.night_detail_off));
        nightBrightnessRow.setValue(s.nightBrightness + " %");
        nightBrightnessRow.setBar(fraction(s.nightBrightness));
        warmthRow.setValue(s.warmth + " %");
        warmthRow.setBar(s.warmth / 100f);
        startRow.setValue(Dimming.clock(s.nightStart, h24));
        endRow.setValue(Dimming.clock(s.nightEnd, h24));
        boolean scheduled = s.night == Dimming.Night.SCHEDULED;
        startRow.setAlpha(scheduled ? 1f : 0.5f);
        endRow.setAlpha(scheduled ? 1f : 0.5f);

        if (tvAvailable && tvRow != null) {
            tvRow.setValue(getString(s.tvPicture ? R.string.val_on : R.string.val_off));
            tvBacklightRow.setValue(s.tvNight.backlight + " %");
            tvBacklightRow.setBar(s.tvNight.backlight / 100f);
            tvContrastRow.setValue(s.tvNight.contrast + " %");
            tvContrastRow.setBar(s.tvNight.contrast / 100f);
            tvBrightnessRow.setValue(s.tvNight.brightness + " %");
            tvBrightnessRow.setBar(s.tvNight.brightness / 100f);
            tvDynamicRow.setValue(getString(s.tvNight.dynamicBacklight ? R.string.val_on : R.string.val_off));
            float a = s.tvPicture ? 1f : 0.5f;
            tvBacklightRow.setAlpha(a);
            tvContrastRow.setAlpha(a);
            tvBrightnessRow.setAlpha(a);
            tvDynamicRow.setAlpha(a);
        }

        Dimming.Level l = Dimming.level(s, minute);
        String text;
        if (!s.on) text = getString(R.string.status_off);
        else if (permissionRow != null) text = getString(R.string.status_no_perm);
        else if (l.night) text = scheduled
                ? getString(R.string.status_night_until, l.brightness, l.warmth, Dimming.clock(s.nightEnd, h24))
                : getString(R.string.status_night_now, l.brightness, l.warmth);
        else text = (scheduled && s.nightStart != s.nightEnd)
                ? getString(R.string.status_day_until, l.brightness, Dimming.clock(s.nightStart, h24))
                : getString(R.string.status_day_now, l.brightness);
        status.setText(text);
        if (modal != null) modal.refreshFocus();
    }

    private void askPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException e) {
            // Many TVs hide this setting; the ADB shell can still grant it.
            permissionRow.setDetail(getString(R.string.perm_no_screen, getPackageName()));
        }
    }

    private static float fraction(int brightness) {
        return (brightness - Dimming.MIN_BRIGHTNESS) / (float) (Dimming.MAX_BRIGHTNESS - Dimming.MIN_BRIGHTNESS);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
