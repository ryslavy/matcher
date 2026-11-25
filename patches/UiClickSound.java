package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Soft synthesized UI navigation sounds ("tock" on focus move, "thock" on activate) played on a
 * dedicated background {@link HandlerThread} via {@link SoundPool} so binder calls into the audio
 * server never stutter focus animations on the UI thread.
 */
final class UiClickSound {

    private static final String TAG = "UiClickSound";
    private static final int SAMPLE_RATE = 48_000;
    private static final long MIN_MOVE_GAP_MS = 28L;

    @Nullable private static volatile UiClickSound instance;
    private static volatile boolean enabled = true;

    @Nullable private volatile SoundPool pool;
    private volatile int moveSoundId;
    private volatile int openSoundId;
    private volatile int backSoundId;
    private volatile long lastMoveMs;
    private final HandlerThread thread;
    private final Handler handler;

    private UiClickSound(Context appCtx) {
        thread = new HandlerThread("tvhome-ui-sound", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> initPool(appCtx));
    }

    private void initPool(Context appCtx) {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool p = new SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(attrs)
                    .build();
            moveSoundId = p.load(ensureWav(appCtx, "ui_tock_move.wav", 1420f, 0.0085f, 0.028f), 1);
            openSoundId = p.load(ensureWav(appCtx, "ui_tock_open.wav", 860f, 0.020f, 0.078f), 1);
            backSoundId = p.load(ensureWav(appCtx, "ui_tock_back.wav", 620f, 0.015f, 0.055f), 1);
            pool = p;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "UI click sounds unavailable", e);
        }
    }

    static void init(Context ctx) {
        Context app = ctx.getApplicationContext();
        enabled = FeaturePrefs.isUiSoundsEnabled(app);
        if (instance == null) {
            synchronized (UiClickSound.class) {
                if (instance == null) {
                    instance = new UiClickSound(app);
                }
            }
        }
    }

    static void refreshEnabled(Context ctx) {
        enabled = FeaturePrefs.isUiSoundsEnabled(ctx);
    }

    static void playMove(Context ctx) {
        if (!enabled) return;
        init(ctx);
        UiClickSound self = instance;
        if (self == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - self.lastMoveMs < MIN_MOVE_GAP_MS) return;
        self.lastMoveMs = now;
        self.handler.post(() -> {
            SoundPool p = self.pool;
            int id = self.moveSoundId;
            if (p != null && id != 0) p.play(id, 0.13f, 0.13f, 1, 0, 1.0f);
        });
    }

    static void playOpen(Context ctx) {
        if (!enabled) return;
        init(ctx);
        UiClickSound self = instance;
        if (self == null) return;
        self.handler.post(() -> {
            SoundPool p = self.pool;
            int id = self.openSoundId;
            if (p != null && id != 0) p.play(id, 0.17f, 0.17f, 1, 0, 1.0f);
        });
    }

    static void playBack(Context ctx) {
        if (!enabled) return;
        init(ctx);
        UiClickSound self = instance;
        if (self == null) return;
        self.handler.post(() -> {
            SoundPool p = self.pool;
            int id = self.backSoundId;
            if (p != null && id != 0) p.play(id, 0.12f, 0.12f, 1, 0, 1.0f);
        });
    }

    /** Plays subtle move sound whenever focus shifts between two distinct views under {@code root}. */
    static void attachFocusSound(@Nullable android.view.View root) {
        if (root == null) return;
        final Context app = root.getContext().getApplicationContext();
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (oldFocus != null && newFocus != null && oldFocus != newFocus) {
                playMove(app);
            }
        });
    }

    /** Plays subtle open/back click for remote OK/Enter and Back key presses (ACTION_DOWN, repeat 0). */
    static void onKeyEvent(Context ctx, @Nullable android.view.KeyEvent event) {
        if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return;
        }
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || code == android.view.KeyEvent.KEYCODE_ENTER
                || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
            playOpen(ctx);
        } else if (code == android.view.KeyEvent.KEYCODE_BACK) {
            playBack(ctx);
        }
    }

    /** Synthesizes a clean PCM16 mono WAV (sine + gentle 2nd harmonic, 1.5 ms anti-pop attack, exp decay). */
    static byte[] synthesizeWavBytes(float freqHz, float decaySec, float durationSec) {
        int samples = Math.max(1, Math.round(SAMPLE_RATE * durationSec));
        ByteBuffer buf = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII))
                .putInt(36 + samples * 2)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * 2)
                .putShort((short) 2)
                .putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = Math.min(1.0, t / 0.0015) * Math.exp(-t / decaySec);
            double wave = 0.76 * Math.sin(2.0 * Math.PI * freqHz * t)
                    + 0.24 * Math.sin(2.0 * Math.PI * freqHz * 2.01 * t);
            int sample = (int) Math.round(wave * env * 0.68 * Short.MAX_VALUE);
            buf.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample)));
        }
        return buf.array();
    }

    private static String ensureWav(Context ctx, String name, float freqHz, float decaySec, float durationSec)
            throws IOException {
        File f = new File(ctx.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        byte[] wav = synthesizeWavBytes(freqHz, decaySec, durationSec);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(wav);
        }
        return f.getPath();
    }
}
