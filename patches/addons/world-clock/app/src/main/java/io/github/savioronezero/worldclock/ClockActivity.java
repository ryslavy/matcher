package io.github.savioronezero.worldclock;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Frosted-glass World Clock modal. Offline; uses system time zones and frosted D-pad dialogs. */
public final class ClockActivity extends Activity {
    private static final int INK = Color.WHITE;
    private static final int MUTED = 0xA6FFFFFF;
    private static final int ACCENT = 0xFF38BDF8;
    private static final int MAX_VISIBLE_MATCHES = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ClockViews> clocks = new ArrayList<>();
    private final List<View> focusNodes = new ArrayList<>();
    private List<String> ids, catalogue;
    private SharedPreferences preferences;
    private GlassKit.Modal modal;
    private TextView reference;
    private Dialog dialog;
    private Runnable pendingFilter;
    private int selected, format;
    private boolean resumed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refresh();
            handler.postDelayed(this, ClockModel.nextMinute(System.currentTimeMillis()));
        }
    };

    private final BroadcastReceiver changedTime = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (resumed) {
                handler.removeCallbacks(tick);
                tick.run();
            }
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = ClockPreferences.store(this);
        ClockPreferences saved = new ClockPreferences(preferences);
        format = saved.format;
        ids = saved.ids;
        catalogue = ClockModel.catalogue();
        selected = state == null ? 0 : Math.max(0, Math.min(ids.size() - 1, state.getInt("selected", 0)));
        modal = GlassKit.installModal(this, 860, 496, false, 0x122D45);
        render();
        modal.reveal();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changedTime, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(changedTime, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
        resumed = true;
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        if (pendingFilter != null) {
            handler.removeCallbacks(pendingFilter);
            pendingFilter = null;
        }
        dismissDialog();
        if (modal != null) modal.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(changedTime);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected", selected);
        super.onSaveInstanceState(state);
    }

    private void save() {
        preferences.edit().putString("zones_v1", new JSONArray(ids).toString())
                .putInt("format_v1", format).apply();
    }

    private void render() {
        clocks.clear();
        focusNodes.clear();
        float d = modal.density;
        LinearLayout panel = modal.panel;
        panel.removeAllViews();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setClipChildren(false);
        toolbar.setClipToPadding(false);
        toolbar.setPadding(dp(4), 0, dp(4), 0);

        TextView title = GlassKit.makeTitle(this, getString(R.string.app_name));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView add = toolbarPill(getString(R.string.add_city));
        add.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            if (ids.size() == ClockModel.LIMIT) Toast.makeText(this, R.string.city_limit, Toast.LENGTH_SHORT).show();
            else chooseCity(-1);
        });
        toolbar.addView(add, buttonParams());
        focusNodes.add(add);

        TextView clockFormat = toolbarPill(getString(R.string.clock_format));
        clockFormat.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            chooseFormat();
        });
        toolbar.addView(clockFormat, buttonParams());
        focusNodes.add(clockFormat);

        TextView screensaver = toolbarPill(getString(R.string.screensaver));
        screensaver.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            screensaverOptions();
        });
        toolbar.addView(screensaver, buttonParams());
        focusNodes.add(screensaver);

        panel.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        reference = GlassKit.makeSubtitle(this, "");
        reference.setPadding(dp(4), dp(4), dp(4), 0);
        panel.addView(reference, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(GlassKit.makeDivider(this, 10, 10));

        LinearLayout grid = column();
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), dp(2), dp(4), dp(4));
        panel.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        int columns = Math.min(3, ids.size());
        for (int start = 0; start < ids.size(); start += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (start > 0) rowParams.topMargin = dp(12);
            grid.addView(row, rowParams);
            for (int col = 0; col < columns; col++) {
                final int index = start + col;
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cardParams.leftMargin = dp(12);
                if (index >= ids.size()) {
                    row.addView(new View(this), cardParams);
                    continue;
                }
                ClockViews card = new ClockViews(ids.get(index), d);
                clocks.add(card);
                row.addView(card.root, cardParams);
                focusNodes.add(card.root);
                card.root.setOnFocusChangeListener((v, focused) -> {
                    if (focused) selected = index;
                    GlassKit.animateFocus(v, focused, 1.025f, 1.5f, d);
                });
                card.root.setOnClickListener(v -> {
                    GlassKit.pulseClick(v, 1.025f);
                    cityOptions(index);
                });
            }
        }

        for (View node : focusNodes) node.setId(View.generateViewId());
        for (int i = 0; i < focusNodes.size(); i++) {
            View node = focusNodes.get(i);
            node.setNextFocusLeftId(target(i, ClockNavigation.LEFT));
            node.setNextFocusRightId(target(i, ClockNavigation.RIGHT));
            node.setNextFocusUpId(target(i, ClockNavigation.UP));
            node.setNextFocusDownId(target(i, ClockNavigation.DOWN));
        }
        refresh();
        focusNodes.get(ClockNavigation.TOOLBAR + Math.max(0, Math.min(selected, ids.size() - 1))).requestFocus();
        panel.post(() -> modal.refreshFocus());
    }

    private int target(int node, int direction) {
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        return focusNodes.get(ClockNavigation.next(node, ids.size(), direction, rtl)).getId();
    }

    private void refresh() {
        Instant instant = Instant.now();
        ZoneId local = ZoneId.systemDefault();
        Locale locale = Locale.getDefault();
        boolean twentyFour = format == 2 || (format == 0 && android.text.format.DateFormat.is24HourFormat(this));
        reference.setText(getString(R.string.local_reference, local.getDisplayName(TextStyle.FULL, locale)));
        for (ClockViews views : clocks) {
            ClockModel.Reading reading = ClockModel.at(views.id, instant, local, twentyFour, locale);
            if (reading.period.isEmpty()) views.time.setText(reading.time);
            else {
                SpannableString display = new SpannableString(reading.time + " " + reading.period);
                display.setSpan(new RelativeSizeSpan(0.4f), reading.time.length() + 1, display.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                views.time.setText(display);
            }
            views.date.setText(reading.date);
            views.offset.setText(reading.offset);
            views.day.setText(reading.dayDifference == 0 ? getString(R.string.same_day)
                    : reading.dayDifference == 1 ? getString(R.string.tomorrow)
                    : reading.dayDifference == -1 ? getString(R.string.yesterday)
                    : getResources().getQuantityString(R.plurals.day_difference,
                            Math.abs(reading.dayDifference), reading.dayDifference));
            GlassKit.markPreserveTextColor(views.day);
            views.day.setTextColor(reading.dayDifference == 0
                    ? GlassKit.adaptiveSecondaryTextColor()
                    : GlassKit.adaptiveAccentTextColor(0xFF7DD3FC));
        }
    }

    private void cityOptions(int index) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.change_city));
        actions.add(() -> chooseCity(index));
        if (index > 0) {
            labels.add(getString(R.string.move_earlier));
            actions.add(() -> {
                ids = ClockModel.move(ids, index, index - 1);
                selected = index - 1;
                save();
                render();
            });
        }
        if (index + 1 < ids.size()) {
            labels.add(getString(R.string.move_later));
            actions.add(() -> {
                ids = ClockModel.move(ids, index, index + 1);
                selected = index + 1;
                save();
                render();
            });
        }
        if (ids.size() > 1) {
            labels.add(getString(R.string.remove_city));
            actions.add(() -> {
                ids = ClockModel.remove(ids, index);
                selected = Math.min(index, ids.size() - 1);
                save();
                render();
            });
        }
        dismissDialog();
        trackDialog(GlassKit.showListDialog(
                this, modal, cityLabel(ids.get(index)),
                labels.toArray(new String[0]), -1, ACCENT, null, null,
                which -> actions.get(which).run()));
    }

    private String cityLabel(String id) {
        return ClockModel.LOCAL.equals(id) ? getString(R.string.local) : ClockModel.city(id);
    }

    private void chooseFormat() {
        dismissDialog();
        trackDialog(GlassKit.showListDialog(
                this, modal, getString(R.string.clock_format),
                new String[]{getString(R.string.format_system), getString(R.string.format_12), getString(R.string.format_24)},
                format, ACCENT, getString(R.string.close), () -> { },
                which -> {
                    format = which;
                    save();
                    refresh();
                }));
    }

    private void screensaverOptions() {
        dismissDialog();
        ClockPreferences options = new ClockPreferences(preferences);
        String[] brightness = {getString(R.string.dim), getString(R.string.medium), getString(R.string.bright)};
        String[] backgrounds = {getString(R.string.gradient), getString(R.string.starfield),
                getString(R.string.world_map), getString(R.string.plain)};
        String[] labels = {
                getString(R.string.preview),
                getString(R.string.background_option, backgrounds[options.background]),
                getString(R.string.motion_option, getString(options.motion ? R.string.motion_on : R.string.motion_reduced)),
                getString(R.string.brightness_option, brightness[options.brightness]),
                getString(R.string.tv_screensaver_settings)
        };
        trackDialog(GlassKit.showListDialog(
                this, modal, getString(R.string.screensaver), labels, -1, ACCENT,
                getString(R.string.close), () -> { },
                which -> {
                    if (which == 0) {
                        startActivity(new Intent(this, ClockPreviewActivity.class));
                    } else if (which == 1) {
                        trackDialog(GlassKit.showListDialog(
                                this, modal, getString(R.string.background), backgrounds,
                                options.background, ACCENT, getString(R.string.close), () -> { },
                                value -> preferences.edit().putInt("dream_background_v1", value).apply()));
                    } else if (which == 2) {
                        preferences.edit().putBoolean("dream_motion_v1", !options.motion).apply();
                        screensaverOptions();
                    } else if (which == 3) {
                        trackDialog(GlassKit.showListDialog(
                                this, modal, getString(R.string.clock_brightness), brightness,
                                options.brightness, ACCENT, getString(R.string.close), () -> { },
                                value -> preferences.edit().putInt("dream_brightness_v1", value).apply()));
                    } else {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_DREAM_SETTINGS));
                        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                            Toast.makeText(this, R.string.screensaver_unavailable, Toast.LENGTH_LONG).show();
                        }
                    }
                }));
    }

    private void chooseCity(int replacing) {
        dismissDialog();
        float d = modal.density;

        LinearLayout box = column();
        GlassKit.applyPanelSurface(box, d, 24f, true, false, 0x122D45);
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(this);
        title.setText(replacing < 0 ? R.string.add_city : R.string.change_city);
        title.setTextColor(INK);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.search_city);
        search.setTextColor(INK);
        search.setHintTextColor(0x88FFFFFF);
        search.setTextSize(15.5f);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setBackground(GlassKit.makeInputBg(d, false));
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setOnFocusChangeListener((v, focused) -> search.setBackground(GlassKit.makeInputBg(d, focused)));
        GlassKit.configureFocus(search, 1.0f, 0f, 14f, ACCENT);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(46));
        searchLp.bottomMargin = dp(8);
        box.addView(search, searchLp);

        TextView empty = GlassKit.makeSubtitle(this, getString(R.string.no_cities));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(24), 0, dp(24));
        empty.setVisibility(View.GONE);
        box.addView(empty, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(18));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        LinearLayout rows = column();
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(dp(6), dp(4), dp(6), dp(6));
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        box.addView(scroll, new LinearLayout.LayoutParams(-1, dp(236)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(6), dp(12), dp(6), dp(2));
        footer.setClipChildren(false);
        footer.setClipToPadding(false);

        Dialog picker = GlassKit.createFrostedDialog(this, box, 520, modal);

        TextView closeBtn = GlassKit.makePillButton(this, getString(R.string.close), 0);
        closeBtn.setOnClickListener(v -> picker.dismiss());
        footer.addView(closeBtn, new LinearLayout.LayoutParams(-2, dp(38)));
        box.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        Runnable filter = () -> {
            List<String> matches = ClockModel.search(catalogue, search.getText().toString(), ids, replacing);
            rows.removeAllViews();
            if (matches.isEmpty()) {
                scroll.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
                return;
            }
            empty.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            int limit = Math.min(matches.size(), MAX_VISIBLE_MATCHES);
            for (int i = 0; i < limit; i++) {
                final String id = matches.get(i);
                TextView row = new TextView(this);
                row.setText(cityLabel(id) + "  ·  " + ClockModel.area(id));
                row.setTextColor(GlassKit.adaptivePrimaryTextColor());
                row.setTextSize(15f);
                row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                row.setBackground(GlassKit.makeChoiceBg(d, 13f, ACCENT));
                row.setFocusable(true);
                row.setFocusableInTouchMode(true);
                row.setClickable(true);
                GlassKit.attachRowFocus(row, d, 13f, ACCENT);
                row.setOnClickListener(v -> {
                    List<String> updated = replacing < 0 ? ClockModel.add(ids, id) : ClockModel.replace(ids, replacing, id);
                    if (updated.equals(ids)) {
                        if (replacing >= 0 && id.equals(ids.get(replacing))) {
                            picker.dismiss();
                            return;
                        }
                        Toast.makeText(this, R.string.already_shown, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ids = updated;
                    selected = replacing < 0 ? ids.size() - 1 : replacing;
                    save();
                    picker.dismiss();
                    render();
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rlp.topMargin = dp(5);
                rows.addView(row, rlp);
            }
            scroll.scrollTo(0, 0);
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pendingFilter != null) handler.removeCallbacks(pendingFilter);
                pendingFilter = () -> {
                    pendingFilter = null;
                    if (picker.isShowing()) filter.run();
                };
                handler.postDelayed(pendingFilter, 120L);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        search.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN && rows.getChildCount() > 0) {
                rows.getChildAt(0).requestFocus();
                return true;
            }
            return false;
        });
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
            if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            return true;
        });

        filter.run();
        if (picker.getWindow() != null) {
            picker.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        trackDialog(picker);
        picker.show();
        search.requestFocus();
    }

    private void dismissDialog() {
        if (dialog != null) {
            Dialog prev = dialog;
            dialog = null;
            prev.dismiss();
        }
    }

    private void trackDialog(Dialog next) {
        dialog = next;
        GlassKit.setDialogDismissHook(next, () -> {
            if (pendingFilter != null) {
                handler.removeCallbacks(pendingFilter);
                pendingFilter = null;
            }
            if (dialog == next) {
                dialog = null;
            }
        });
    }

    private final class ClockViews {
        final String id;
        final LinearLayout root;
        final TextView time, date, offset, day;

        ClockViews(String id, float d) {
            this.id = id;
            root = column();
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(12), dp(12), dp(12), dp(12));
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            root.setBackground(GlassKit.makeCardBg(d, 16f));
            GlassKit.configureFocus(root, 1.025f, 1.5f, 16f, ACCENT);

            TextView city = text(cityLabel(id), 19, INK);
            city.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            city.setMaxLines(1);
            city.setGravity(Gravity.CENTER);
            city.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, TypedValue.COMPLEX_UNIT_SP);
            root.addView(city, new LinearLayout.LayoutParams(-1, dp(30)));

            time = text("", 54, INK);
            time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            time.setGravity(Gravity.CENTER);
            time.setMaxLines(1);
            time.setIncludeFontPadding(false);
            time.setTextDirection(View.TEXT_DIRECTION_LTR);
            time.setAutoSizeTextTypeUniformWithConfiguration(18, 54, 2, TypedValue.COMPLEX_UNIT_SP);
            root.addView(time, new LinearLayout.LayoutParams(-1, 0, 1f));

            date = text("", 15, INK);
            date.setGravity(Gravity.CENTER);
            root.addView(date);
            day = text("", 12.5f, MUTED);
            day.setGravity(Gravity.CENTER);
            root.addView(day);
            offset = text("", 12.5f, MUTED);
            offset.setGravity(Gravity.CENTER);
            root.addView(offset);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView toolbarPill(String label) {
        return GlassKit.makePillButton(this, label, ACCENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(38));
        p.leftMargin = dp(8);
        return p;
    }
}
