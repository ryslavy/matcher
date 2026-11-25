package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * Apple TV "Reduce Loud Sounds" / Night Sound controller backed by Android's native AOSP
 * {@link DynamicsProcessing} audio effect on the global output mix (session 0).
 * Compresses sudden loud explosions/peaks while lifting mid-band movie dialogue for late-night watching.
 * Kept alive inside the persistent launcher/HomeKeyService process across all streaming apps.
 */
final class NightAudioController {

    private static final String TAG = "TvHome.NightAudio";
    private static final String PREFS = "tvos_prefs";
    private static final String KEY_NIGHT_AUDIO = "night_audio_enabled";
    private static final String KEY_BEDTIME = "bedtime_active";
    private static final String KEY_BEDTIME_AT = "bedtime_at_ms";
    private static final long BEDTIME_TTL_MS = 3L * 60L * 60L * 1000L;

    private static final Object LOCK = new Object();
    private static DynamicsProcessing dynamics;
    private static LoudnessEnhancer fallbackEnhancer;
    private static boolean active;

    private NightAudioController() {}

    static boolean isEnabled(Context ctx) {
        synchronized (LOCK) {
            if (active) return true;
        }
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NIGHT_AUDIO, false);
    }

    static boolean isHardwareActive() {
        synchronized (LOCK) {
            return active && isEffectAliveLocked();
        }
    }

    private static boolean isEffectAliveLocked() {
        try {
            if (dynamics != null && dynamics.getEnabled()) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (fallbackEnhancer != null && fallbackEnhancer.getEnabled()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Re-applies persisted state when HomeKeyService / MainActivity starts. */
    static void restore(Context ctx) {
        if (isEnabled(ctx)) {
            setEnabled(ctx, true);
        }
    }

    /** Enables or disables global Night Sound compression. Returns true if the requested state succeeded. */
    static boolean setEnabled(Context ctx, boolean enable) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (!enable) {
                releaseLocked();
                active = false;
                savePref(app, KEY_NIGHT_AUDIO, false);
                return true;
            }
            if (active && isEffectAliveLocked()) {
                savePref(app, KEY_NIGHT_AUDIO, true);
                return true;
            }
            releaseLocked();
            boolean ok = attachDynamicsLocked();
            if (!ok) ok = attachFallbackLoudnessLocked();
            active = ok;
            savePref(app, KEY_NIGHT_AUDIO, ok);
            return ok;
        }
    }

    static boolean isBedtimeActive(Context ctx) {
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_BEDTIME, false)) return false;
        long at = prefs.getLong(KEY_BEDTIME_AT, 0L);
        long now = System.currentTimeMillis();
        if (at > 0L && (now < at || now - at > BEDTIME_TTL_MS)) {
            prefs.edit().putBoolean(KEY_BEDTIME, false).remove(KEY_BEDTIME_AT).apply();
            return false;
        }
        return true;
    }

    static void setBedtimeActive(Context ctx, boolean on) {
        SharedPreferences.Editor ed = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BEDTIME, on);
        if (on) ed.putLong(KEY_BEDTIME_AT, System.currentTimeMillis());
        else ed.remove(KEY_BEDTIME_AT);
        ed.apply();
    }

    private static boolean attachDynamicsLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        DynamicsProcessing dp = null;
        try {
            DynamicsProcessing.Config cfg = buildNightConfig();
            dp = new DynamicsProcessing(0, 0, cfg);
            int res = dp.setEnabled(true);
            if (res != AudioEffect.SUCCESS) {
                dp.release();
                Log.w(TAG, "DynamicsProcessing setEnabled failed: " + res);
                return false;
            }
            dynamics = dp;
            Log.i(TAG, "Night Sound enabled via DynamicsProcessing (session 0)");
            return true;
        } catch (Throwable t) {
            if (dp != null) {
                try {
                    dp.release();
                } catch (Throwable ignored) {
                }
            }
            Log.w(TAG, "DynamicsProcessing attach failed, trying fallback", t);
            return false;
        }
    }

    /**
     * Pure 3-band MBC + Limiter curve tuned for late-night TV movies:
     *  - Band 0 (<= 250 Hz): tames heavy sub-bass/explosions (-22 dB threshold, 5:1 ratio, -2 dB post-gain)
     *  - Band 1 (250..3500 Hz): boosts quiet speech (+4.5 dB pre-gain, 3:1 compression above -18 dB)
     *  - Band 2 (> 3500 Hz): smooths loud shrieks/glass peaks (-20 dB threshold, 4:1 ratio)
     *  - Limiter: hard -2.5 dB ceiling so sudden volume jumps cannot wake the household.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    static DynamicsProcessing.Config buildNightConfig() {
        DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                false, 0,   // preEq
                true, 3,    // mbc (3 bands: low / voice / high)
                false, 0,   // postEq
                true        // limiter
        );

        DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, 3);
        mbc.setBand(0, new DynamicsProcessing.MbcBand(
                true, 250f, 8f, 120f, 5.0f, -22f, 4f, -48f, 1.0f, 0f, -2.0f));
        mbc.setBand(1, new DynamicsProcessing.MbcBand(
                true, 3500f, 5f, 90f, 3.0f, -18f, 4f, -52f, 1.0f, 4.5f, 1.5f));
        mbc.setBand(2, new DynamicsProcessing.MbcBand(
                true, 20000f, 4f, 80f, 4.0f, -20f, 3f, -50f, 1.0f, 0f, -1.0f));
        builder.setMbcAllChannelsTo(mbc);

        DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                true, true, 0, 1.0f, 120f, 6.0f, -2.5f, 0f);
        builder.setLimiterAllChannelsTo(limiter);
        return builder.build();
    }

    private static boolean attachFallbackLoudnessLocked() {
        LoudnessEnhancer le = null;
        try {
            le = new LoudnessEnhancer(0);
            le.setTargetGain(250); // +2.5 dB gentle dialog lift
            int res = le.setEnabled(true);
            if (res != AudioEffect.SUCCESS) {
                le.release();
                Log.w(TAG, "LoudnessEnhancer setEnabled failed: " + res);
                return false;
            }
            fallbackEnhancer = le;
            Log.i(TAG, "Night Sound enabled via LoudnessEnhancer fallback (session 0)");
            return true;
        } catch (Throwable t) {
            if (le != null) {
                try {
                    le.release();
                } catch (Throwable ignored) {
                }
            }
            Log.w(TAG, "LoudnessEnhancer fallback failed", t);
            return false;
        }
    }

    private static void releaseLocked() {
        if (dynamics != null) {
            try {
                dynamics.setEnabled(false);
                dynamics.release();
            } catch (Throwable ignored) {
            }
            dynamics = null;
        }
        if (fallbackEnhancer != null) {
            try {
                fallbackEnhancer.setEnabled(false);
                fallbackEnhancer.release();
            } catch (Throwable ignored) {
            }
            fallbackEnhancer = null;
        }
    }

    private static void savePref(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }
}
