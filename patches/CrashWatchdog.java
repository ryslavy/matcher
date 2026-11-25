package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Crash-loop safeguard: records uncaught exception timestamps in {@code files/home-crashes.txt}.
 * If the launcher crashes 3 times within 5 minutes, automatically attempts a silent loopback ADB
 * restore of the stock Google TV launcher (enabling {@code launcherx} BEFORE {@code setupwraith})
 * so the user is never stuck on a black screen.
 */
final class CrashWatchdog {

    private static final String TAG = "CrashWatchdog";
    static final long WINDOW_MS = 5L * 60L * 1000L;
    static final int CRASH_THRESHOLD = 3;
    private static final String CRASH_FILE = "home-crashes.txt";

    private static volatile boolean installed;

    private CrashWatchdog() { }

    static synchronized void install(Context appCtx) {
        if (installed) return;
        installed = true;
        final Context app = appCtx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                if (FeaturePrefs.isCrashWatchdogEnabled(app)) {
                    onUncaughtCrash(app, throwable);
                }
            } catch (Throwable ignored) {
            } finally {
                if (previous != null) previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void onUncaughtCrash(Context app, @Nullable Throwable throwable) {
        File file = new File(app.getFilesDir(), CRASH_FILE);
        long now = System.currentTimeMillis();
        List<Long> recent = readTimestamps(file);
        boolean shouldRecover = recordAndShouldRecover(recent, now);
        writeTimestamps(file, recent);
        Log.e(TAG, "Recorded launcher crash (" + recent.size() + "/" + CRASH_THRESHOLD + " in 5 min)", throwable);
        if (!shouldRecover) return;

        Log.e(TAG, "Crash-loop threshold reached — restoring stock Google TV launcher via loopback ADB");
        Thread recover = new Thread(() -> {
            boolean ok = LocalAdbClient.restoreStockLauncherSilent(app);
            if (ok) {
                clearHistory(app);
            }
        }, "crash-watchdog-recover");
        recover.start();
        try {
            recover.join(4500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pure-JVM helper: prunes entries outside the 5-minute window, appends {@code nowMs},
     * and returns true if {@link #CRASH_THRESHOLD} crashes occurred within {@link #WINDOW_MS}.
     */
    static boolean recordAndShouldRecover(List<Long> timestamps, long nowMs) {
        long cutoff = nowMs - WINDOW_MS;
        List<Long> kept = new ArrayList<>();
        for (Long ts : timestamps) {
            if (ts != null && ts >= cutoff && ts <= nowMs + 60_000L) {
                kept.add(ts);
            }
        }
        kept.add(nowMs);
        timestamps.clear();
        timestamps.addAll(kept);
        return kept.size() >= CRASH_THRESHOLD;
    }

    static void clearHistory(Context ctx) {
        File file = new File(ctx.getApplicationContext().getFilesDir(), CRASH_FILE);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    static int recentCrashCount(Context ctx) {
        File file = new File(ctx.getApplicationContext().getFilesDir(), CRASH_FILE);
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        int count = 0;
        for (Long ts : readTimestamps(file)) {
            if (ts != null && ts >= cutoff) count++;
        }
        return count;
    }

    private static List<Long> readTimestamps(File file) {
        List<Long> out = new ArrayList<>();
        if (!file.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        out.add(Long.parseLong(trimmed));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void writeTimestamps(File file, List<Long> list) {
        try (FileWriter fw = new FileWriter(file, false)) {
            for (Long ts : list) {
                fw.write(Long.toString(ts));
                fw.write('\n');
            }
        } catch (IOException ignored) {
        }
    }
}
