package io.github.sumyabazar1.weather;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.format.TextStyle;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Frosted-glass weather modal: live Open-Meteo conditions, US AQI pill, multi-day forecast, and
 * frosted settings / city search dialogs (zero stock AlertDialogs).
 */
public final class WeatherActivity extends Activity {
    private static final String PREFS = "place";
    private static final int ACCENT_SKY = 0xFF38BDF8;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    private SharedPreferences place;
    private SharedPreferences options;
    private GlassKit.Modal modal;
    private Dialog activeDialog;
    private Dialog subDialog;
    private int lastSeedRgb = 0x163858;
    private View content;
    private TextView message;
    private boolean shown;
    private boolean resumed;
    private int requestId;
    private int searchId;
    private Forecast.Report lastReport;
    private Date updatedAt;
    private int forecastDays;
    private boolean showHumidity;
    private boolean showWind;
    private boolean fahrenheit;
    private boolean bothUnits;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        modal = GlassKit.installModal(this, 840, 480, true, 0x163858);
        float d = modal.density;
        getLayoutInflater().inflate(R.layout.activity_weather, modal.panel, true);

        place = getSharedPreferences(PREFS, MODE_PRIVATE);
        options = getSharedPreferences("options", MODE_PRIVATE);
        readOptions();
        content = findViewById(R.id.content);
        message = findViewById(R.id.message);

        View changeCity = findViewById(R.id.changeCity);
        View weatherSettings = findViewById(R.id.weatherSettings);
        changeCity.setBackground(GlassKit.makePillBg(d, 20f));
        weatherSettings.setBackground(GlassKit.makePillBg(d, 20f));
        GlassKit.attachPillFocus(changeCity, 1.06f, 1.5f, d, GlassKit.RADIUS_CAPSULE, ACCENT_SKY);
        GlassKit.attachPillFocus(weatherSettings, 1.06f, 1.5f, d, GlassKit.RADIUS_CAPSULE, ACCENT_SKY);

        changeCity.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            pickCity();
        });
        weatherSettings.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.06f);
            settings();
        });

        modal.reveal();
        changeCity.requestFocus();
        if (!place.contains("name")) {
            message.setText(R.string.pick_first);
            pickCity();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
        resumed = true;
        if (place.contains("name")) refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        requestId++;
        searchId++;
        main.removeCallbacks(tick);
        dismissDialogs();
        if (modal != null) modal.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissDialogs();
        super.onDestroy();
        io.shutdownNow();
    }

    private void dismissDialogs() {
        if (subDialog != null) {
            Dialog d = subDialog;
            subDialog = null;
            if (d.isShowing()) d.dismiss();
        }
        if (activeDialog != null) {
            Dialog d = activeDialog;
            activeDialog = null;
            if (d.isShowing()) d.dismiss();
        }
    }

    private void trackDialog(Dialog d) {
        dismissDialogs();
        activeDialog = d;
    }

    private void refresh() {
        main.removeCallbacks(tick);
        if (!resumed || !place.contains("name")) return;
        int request = ++requestId;
        int requestedDays = forecastDays;
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        io.execute(() -> {
            Forecast.Report report;
            try {
                report = Forecast.fetch(lat, lon, requestedDays);
            } catch (Exception e) {
                report = null;
            }
            Forecast.Report result = report;
            main.post(() -> {
                if (isDestroyed() || !resumed || request != requestId) return;
                if (result != null) {
                    lastReport = result;
                    updatedAt = new Date();
                    show(result);
                } else if (!shown) {
                    message.setText(R.string.offline);
                }
                main.postDelayed(tick, result != null ? Forecast.REFRESH_MS : Forecast.RETRY_MS);
            });
        });
    }

    private static int weatherSeedRgb(int code, boolean day) {
        if (code <= 1) return day ? 0x1E5E9C : 0x101E46;
        if (code == 45 || code == 48) return 0x3D4652;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return 0x425C74;
        if (code >= 95) return 0x2F204A;
        if (code >= 51) return 0x1B314A;
        return 0x2A3A4E;
    }

    private void show(Forecast.Report r) {
        shown = true;
        float d = modal.density;
        int seed = weatherSeedRgb(r.code, r.day);
        if (seed != lastSeedRgb) {
            lastSeedRgb = seed;
            GlassKit.applyPanelSurface(modal.panel, d, 26f, !modal.overHome && modal.backdropView == null,
                    false, seed, modal.overSharpHome, modal.overSharpHome);
        }

        ((TextView) findViewById(R.id.place)).setText(place.getString("name", ""));
        ((TextView) findViewById(R.id.temp)).setText(Forecast.degrees(r.tempC, fahrenheit) + "°");
        ((TextView) findViewById(R.id.sky)).setText(Forecast.sky(r.code, r.day));
        StringBuilder feels = new StringBuilder();
        if (bothUnits) {
            feels.append(getString(fahrenheit ? R.string.other_unit_c : R.string.other_unit_f,
                    Forecast.degrees(r.tempC, !fahrenheit))).append(" · ");
        }
        feels.append(getString(R.string.feels_like, Forecast.degrees(r.feelsC, fahrenheit)));
        if (showHumidity) feels.append(" · ").append(getString(R.string.humidity, r.humidity));
        if (showWind) {
            feels.append(" · ").append(fahrenheit ? getString(R.string.wind_mph, Forecast.mph(r.windKmh))
                    : getString(R.string.wind, r.windKmh));
        }
        ((TextView) findViewById(R.id.feels)).setText(feels);

        TextView air = findViewById(R.id.airQuality);
        GlassKit.markPreserveTextColor(air);
        if (r.aqi >= 0) {
            air.setText(getString(R.string.aqi_line, r.aqi, getString(Forecast.aqiLabel(r.aqi))));
            air.setTextColor(Forecast.aqiTextColour(r.aqi));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Forecast.aqiColour(r.aqi));
            pill.setStroke(Math.max(1, Math.round(1f * d)), GlassKit.isLightGlass() ? 0x280F172A : 0x33FFFFFF);
            pill.setCornerRadius(100 * d);
            air.setBackground(pill);
            air.setVisibility(View.VISIBLE);
        } else {
            air.setVisibility(View.GONE);
        }

        LinearLayout days = findViewById(R.id.days);
        days.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        int count = Math.min(forecastDays, r.days.size());
        boolean compact = count > Forecast.MIN_DAYS;
        for (int i = 0; i < count; i++) {
            Forecast.Day day = r.days.get(i);
            View card = inflater.inflate(R.layout.item_day, days, false);
            card.setBackground(GlassKit.makeStaticCardBg(d, 18f));
            ((TextView) card.findViewById(R.id.dayName)).setText(day.date.getDayOfWeek()
                    .getDisplayName(compact ? TextStyle.SHORT : TextStyle.FULL, Locale.getDefault()));
            TextView sky = card.findViewById(R.id.daySky);
            sky.setText(Forecast.sky(day.code, true));
            TextView temps = card.findViewById(R.id.dayTemps);
            temps.setText(getString(R.string.high_low, Forecast.degrees(day.maxC, fahrenheit),
                    Forecast.degrees(day.minC, fahrenheit)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(i < count - 1 ? Math.round((compact ? 8 : 12) * d) : 0);
            card.setLayoutParams(lp);
            if (compact) {
                int pad = Math.round(11 * d);
                card.setPadding(pad, pad, pad, pad);
                sky.setTextSize(13f);
                sky.setSingleLine(false);
                sky.setLines(2);
                temps.setTextSize(18f);
            }
            days.addView(card);
        }

        ((TextView) findViewById(R.id.updated))
                .setText(getString(R.string.updated, DateFormat.getTimeFormat(this).format(updatedAt)));
        GlassKit.applyAdaptiveContentTheme(modal.panel);
        message.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        modal.panel.post(() -> modal.refreshFocus());
    }

    private void readOptions() {
        forecastDays = Forecast.forecastDays(options.getInt("forecast_days",
                getResources().getInteger(R.integer.forecast_days)));
        showHumidity = options.getBoolean("show_humidity", getResources().getBoolean(R.bool.show_humidity));
        showWind = options.getBoolean("show_wind", getResources().getBoolean(R.bool.show_wind));
        fahrenheit = options.getBoolean("fahrenheit", "f".equalsIgnoreCase(getString(R.string.temperature_unit).trim()));
        bothUnits = options.getBoolean("show_both_units", getResources().getBoolean(R.bool.show_both_units));
    }

    private String optionRow(int label, boolean enabled) {
        return getString(R.string.setting_value, getString(label), getString(enabled ? R.string.option_on : R.string.option_off));
    }

    private String[] optionRows() {
        return new String[]{
                getString(R.string.setting_value, getString(R.string.setting_units),
                        getString(fahrenheit ? R.string.unit_f : R.string.unit_c)),
                optionRow(R.string.setting_both_units, bothUnits),
                getString(R.string.setting_value, getString(R.string.setting_days),
                        dayCount(forecastDays)),
                optionRow(R.string.setting_humidity, showHumidity),
                optionRow(R.string.setting_wind, showWind),
                getString(R.string.setting_reset)
        };
    }

    private String dayCount(int days) {
        return getResources().getQuantityString(R.plurals.day_count, days, days);
    }

    /** Frosted-glass weather settings modal with live in-place updates and frosted sub-pickers. */
    private void settings() {
        int initialDays = forecastDays;
        float d = modal.density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GlassKit.applyPanelSurface(box, d, 24f, true, false, 0x163858);
        box.setPadding(GlassKit.dp(this, 20), GlassKit.dp(this, 18), GlassKit.dp(this, 20), GlassKit.dp(this, 16));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(this);
        title.setText(R.string.weather_settings);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(GlassKit.dp(this, 8), 0, GlassKit.dp(this, 8), GlassKit.dp(this, 10));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(GlassKit.dp(this, 16));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(GlassKit.dp(this, 6), GlassKit.dp(this, 4), GlassKit.dp(this, 6), GlassKit.dp(this, 6));
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        box.addView(scroll, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(GlassKit.dp(this, 6), GlassKit.dp(this, 12), GlassKit.dp(this, 6), GlassKit.dp(this, 2));
        footer.setClipChildren(false);
        footer.setClipToPadding(false);

        Dialog dialog = GlassKit.createFrostedDialog(this, box, 480, modal);
        trackDialog(dialog);
        GlassKit.setDialogDismissHook(dialog, () -> {
            if (activeDialog == dialog) activeDialog = null;
            if (forecastDays != initialDays) refresh();
        });

        TextView[] labels = new TextView[6];
        Runnable syncLabels = () -> {
            readOptions();
            String[] values = optionRows();
            for (int i = 0; i < labels.length && i < values.length; i++) {
                labels[i].setText(values[i]);
            }
            if (lastReport != null) show(lastReport);
        };

        String[] initialValues = optionRows();
        for (int i = 0; i < initialValues.length; i++) {
            final int position = i;
            TextView row = new TextView(this);
            row.setText(initialValues[i]);
            row.setTextColor(position == 5 ? 0xFFFCA5A5 : Color.WHITE);
            row.setTextSize(15.5f);
            row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(GlassKit.dp(this, 14), GlassKit.dp(this, 11), GlassKit.dp(this, 14), GlassKit.dp(this, 11));
            int accent = position == 5 ? 0xFFF87171 : ACCENT_SKY;
            row.setBackground(GlassKit.makeChoiceBg(d, 14f, accent));
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            GlassKit.attachRowFocus(row, d, 14f, accent);
            row.setOnClickListener(v -> {
                GlassKit.pulseClick(v, 1.015f);
                if (position == 0) {
                    if (subDialog != null && subDialog.isShowing()) subDialog.dismiss();
                    subDialog = GlassKit.showListDialog(
                            this, null, getString(R.string.setting_units),
                            new String[]{getString(R.string.unit_c), getString(R.string.unit_f)},
                            fahrenheit ? 1 : 0, ACCENT_SKY, null, null,
                            which -> {
                                options.edit().putBoolean("fahrenheit", which == 1).apply();
                                syncLabels.run();
                            });
                } else if (position == 2) {
                    String[] dayChoices = new String[Forecast.MAX_DAYS - Forecast.MIN_DAYS + 1];
                    for (int k = 0; k < dayChoices.length; k++) dayChoices[k] = dayCount(k + Forecast.MIN_DAYS);
                    if (subDialog != null && subDialog.isShowing()) subDialog.dismiss();
                    subDialog = GlassKit.showListDialog(
                            this, null, getString(R.string.setting_days),
                            dayChoices, forecastDays - Forecast.MIN_DAYS, ACCENT_SKY, null, null,
                            which -> {
                                options.edit().putInt("forecast_days", which + Forecast.MIN_DAYS).apply();
                                syncLabels.run();
                            });
                } else {
                    if (position == 1) options.edit().putBoolean("show_both_units", !bothUnits).apply();
                    if (position == 3) options.edit().putBoolean("show_humidity", !showHumidity).apply();
                    if (position == 4) options.edit().putBoolean("show_wind", !showWind).apply();
                    if (position == 5) options.edit().clear().apply();
                    syncLabels.run();
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rlp.topMargin = GlassKit.dp(this, 6);
            rows.addView(row, rlp);
            labels[i] = row;
        }

        TextView done = GlassKit.makePillButton(this, getString(R.string.settings_done), ACCENT_SKY);
        done.setOnClickListener(v -> dialog.dismiss());
        footer.addView(done, new LinearLayout.LayoutParams(-2, GlassKit.dp(this, 38)));
        box.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        dialog.show();
        labels[0].requestFocus();
    }

    /** Frosted-glass city search dialog; Back leaves the current city untouched. */
    private void pickCity() {
        searchId++;
        float d = modal.density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GlassKit.applyPanelSurface(box, d, 24f, true, false, 0x163858);
        box.setPadding(GlassKit.dp(this, 22), GlassKit.dp(this, 18), GlassKit.dp(this, 22), GlassKit.dp(this, 16));
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView title = new TextView(this);
        title.setText(R.string.pick_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(GlassKit.dp(this, 4), 0, GlassKit.dp(this, 4), GlassKit.dp(this, 12));
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.pick_hint);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0x88FFFFFF);
        name.setTextSize(16f);
        name.setPadding(GlassKit.dp(this, 14), GlassKit.dp(this, 12), GlassKit.dp(this, 14), GlassKit.dp(this, 12));
        name.setBackground(GlassKit.makeInputBg(d, false));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        name.setOnFocusChangeListener((v, focused) -> name.setBackground(GlassKit.makeInputBg(d, focused)));
        GlassKit.configureFocus(name, 1.0f, 0f, 14f, ACCENT_SKY);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(GlassKit.dp(this, 2), GlassKit.dp(this, 14), GlassKit.dp(this, 2), GlassKit.dp(this, 2));
        footer.setClipChildren(false);
        footer.setClipToPadding(false);

        Dialog dialog = GlassKit.createFrostedDialog(this, box, 460, modal);
        if (dialog.getWindow() != null) {
            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = GlassKit.dp(this, 44);
            dialog.getWindow().setAttributes(lp);
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        trackDialog(dialog);

        TextView cancel = GlassKit.makePillButton(this, getString(android.R.string.cancel), 0);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, GlassKit.dp(this, 38));
        clp.rightMargin = GlassKit.dp(this, 10);
        footer.addView(cancel, clp);

        TextView searchBtn = GlassKit.makePillButton(this, getString(R.string.pick_search), ACCENT_SKY);
        searchBtn.setOnClickListener(v -> {
            dialog.dismiss();
            search(name.getText().toString());
        });
        footer.addView(searchBtn, new LinearLayout.LayoutParams(-2, GlassKit.dp(this, 38)));
        box.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        name.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.dismiss();
            search(name.getText().toString());
            return true;
        });

        dialog.show();
        name.requestFocus();
    }

    private void search(String typed) {
        if (!resumed) return;
        int search = ++searchId;
        if (typed.trim().isEmpty()) {
            pickCity();
            return;
        }
        io.execute(() -> {
            List<Forecast.Place> found;
            try {
                found = Forecast.search(typed);
            } catch (Exception e) {
                found = null;
            }
            List<Forecast.Place> places = found;
            main.post(() -> {
                if (isDestroyed() || !resumed || search != searchId) return;
                if (places == null) {
                    Toast.makeText(this, R.string.offline, Toast.LENGTH_LONG).show();
                } else if (places.isEmpty()) {
                    Toast.makeText(this, getString(R.string.pick_none, typed.trim()), Toast.LENGTH_LONG).show();
                    pickCity();
                } else {
                    choose(places);
                }
            });
        });
    }

    private void choose(List<Forecast.Place> places) {
        CharSequence[] rows = new CharSequence[places.size()];
        for (int i = 0; i < rows.length; i++) {
            Forecast.Place p = places.get(i);
            rows[i] = p.detail.isEmpty() ? p.name : p.name + " · " + p.detail;
        }
        trackDialog(GlassKit.showListDialog(
                this, modal, getString(R.string.pick_title), rows, -1, ACCENT_SKY, null, null,
                which -> {
                    Forecast.Place p = places.get(which);
                    place.edit()
                            .putString("name", p.name)
                            .putLong("lat", Double.doubleToRawLongBits(p.latitude))
                            .putLong("lon", Double.doubleToRawLongBits(p.longitude))
                            .apply();
                    lastReport = null;
                    shown = false;
                    content.setVisibility(View.INVISIBLE);
                    message.setVisibility(View.VISIBLE);
                    message.setText(R.string.loading);
                    refresh();
                }));
    }
}
