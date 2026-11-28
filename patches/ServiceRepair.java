package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped (Settings, a
 * cleaner app, or am force-stop), and the remote's Home, Input and app buttons then stop working until the helper
 * is turned on again over ADB. With restore_key_services on, a running helper records itself here and drops the
 * record when Android unbinds it (the user turned it off); a force stop kills the process before that, so the
 * record stays. Home starting again puts recorded helpers back on the list, when the installer granted
 * WRITE_SECURE_SETTINGS. With the flag off nothing is recorded and nothing is restored.
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";
    private static final String EXTERNAL_PREFS = "accessibility_external_helpers";
    private static final ComponentName SLEEP_TIMER_SERVICE =
            new ComponentName("io.github.luyzfernando08.sleeptimer",
                    "io.github.luyzfernando08.sleeptimer.SleepService");
    private static final AtomicBoolean REPAIR_IN_FLIGHT = new AtomicBoolean(false);

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    static void restore(Context context) {
        restoreInternal(context, false);
    }

    /**
     * Always runs the atomic Accessibility bounce-rebind after an in-place APK / OTA upgrade
     * ({@code MY_PACKAGE_REPLACED}), even when {@code enabled_accessibility_services} still lists
     * our service, so Android 14 drops any stale service binder immediately.
     */
    static void forceRebindAfterUpdate(Context context) {
        restoreInternal(context, true);
    }

    private static void restoreInternal(Context context, boolean forceCycle) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            ContentResolver cr = context.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<ComponentName> enabled = new ArrayList<>();
            if (list != null) {
                for (String entry : list.split(":")) {
                    ComponentName name = ComponentName.unflattenFromString(entry);
                    if (name != null) enabled.add(name);
                }
            }
            List<ComponentName> recorded = new ArrayList<>();
            for (String service : prefs(context).getAll().keySet()) {
                ComponentName cn = new ComponentName(context.getPackageName(), service);
                if (!recorded.contains(cn)) recorded.add(cn);
            }
            // On Android 14, an in-place APK upgrade calls onUnbind() right before killing the old process
            // (which removes our service from prefs), even though enabled_accessibility_services still lists
            // our own package's service. Preserve any enabled service from our own package so OTA bounce-rebind
            // always brings HomeKeyService back cleanly!
            String ownPkg = context.getPackageName();
            for (ComponentName cn : enabled) {
                if (ownPkg.equals(cn.getPackageName()) && !recorded.contains(cn)) {
                    recorded.add(cn);
                    prefs(context).edit().putBoolean(cn.getClassName(), true).apply();
                }
            }
            // Also guard Sleep Timer's SleepService once observed enabled so an accidental force-stop
            // never leaves Sleep Timer disabled in Accessibility settings.
            SharedPreferences ext = context.getSharedPreferences(EXTERNAL_PREFS, Context.MODE_PRIVATE);
            String sleepKey = SLEEP_TIMER_SERVICE.flattenToShortString();
            if (enabled.contains(SLEEP_TIMER_SERVICE)) {
                if (!ext.getBoolean(sleepKey, false)) {
                    ext.edit().putBoolean(sleepKey, true).apply();
                }
                recorded.add(SLEEP_TIMER_SERVICE);
            } else if (ext.getBoolean(sleepKey, false)) {
                try {
                    context.getPackageManager().getServiceInfo(SLEEP_TIMER_SERVICE, 0);
                    recorded.add(SLEEP_TIMER_SERVICE);
                } catch (PackageManager.NameNotFoundException ignored) {
                    ext.edit().remove(sleepKey).apply();
                }
            }
            if (recorded.isEmpty()) return;
            // The list without our recorded helpers, then with all of them.
            StringBuilder others = new StringBuilder();
            for (ComponentName name : enabled) {
                if (recorded.contains(name)) continue;
                if (others.length() > 0) others.append(':');
                others.append(name.flattenToShortString());
            }
            StringBuilder updated = new StringBuilder(others);
            boolean missing = false;
            for (ComponentName name : recorded) {
                if (!enabled.contains(name)) missing = true;
                if (updated.length() > 0) updated.append(':');
                updated.append(name.flattenToShortString());
            }
            // Hisense songshan's standby kill leaves the list naming the helper and only drops the switch to 0, with
            // the helper marked crashed (#3404): a confirmed 0 needs the same cycle. Default -1, so an absent or
            // unreadable switch with a complete list is left alone unless forceCycle is requested after an update.
            if (!forceCycle && !missing && Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 0) return;
            if (!REPAIR_IN_FLIGHT.compareAndSet(false, true)) return;
            final String without = others.toString();
            final String with = updated.toString();
            final boolean alreadyPresent = !missing;
            final long pauseMs = forceCycle ? 700L : 1000L;
            new Thread(() -> {
                try {
                    if (forceCycle && alreadyPresent) {
                        if (!replaceList(cr, list, without)) return;
                        Thread.sleep(pauseMs);
                        if (!replaceList(cr, without, with)) return;
                        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                        return;
                    }
                    if (!replaceList(cr, list, with)) return;
                    Thread.sleep(pauseMs);
                    if (!replaceList(cr, with, without)) return;
                    Thread.sleep(pauseMs);
                    if (!replaceList(cr, without, with)) return;
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (RuntimeException | InterruptedException ignored) {
                    // The setting is the system's; a refusal leaves the helper off, as before.
                } finally {
                    REPAIR_IN_FLIGHT.set(false);
                }
            }, "ServiceRepair").start();
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the helper off, as before.
        }
    }

    /** Writes the enabled list only while it still reads {@code expected}; an absent list counts as empty. */
    private static boolean replaceList(ContentResolver cr, String expected, String updated) {
        String now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Objects.equals(now == null ? "" : now, expected == null ? "" : expected)
                && Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
