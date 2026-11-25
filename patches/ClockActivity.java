package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Ambient screensaver shown by Home idle timer, Control Center, or remote shortcut (hold Apps).
 * Uses a translucent overlay window with 100% in-process GPU cross-dissolve enter/exit so waking back
 * to Home (or any running app) never triggers an opaque-surface hitch or WindowManagerShell jump.
 * Supports direct remote playback control (Left/Right skip, Up/Down play/pause, Volume +/-)
 * without waking the screensaver; OK, Back, Home, or any other key wakes immediately.
 */
public class ClockActivity extends Activity {

    private ClockScene scene;
    private View sceneRoot;
    private boolean reducedMotion;
    /** Guards against the finger-release UP of the remote hold key that opened this screensaver. */
    private boolean wakeKeyDownSeen;
    private boolean exiting;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Suppress Android 14 WindowManagerShell default slide/jump transitions; we drive enter/exit in-process.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        setContentView(R.layout.dream_clock);
        getWindow().setBackgroundDrawable(null);
        Config cfg = Config.load(this);
        reducedMotion = cfg.reducedMotion;
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        ViewGroup content = findViewById(android.R.id.content);
        sceneRoot = content != null && content.getChildCount() > 0 ? content.getChildAt(0) : getWindow().getDecorView();
        scene = new ClockScene(sceneRoot);
        scene.useHomeWallpaper(this, true, cfg);

        sceneRoot.setAlpha(0f);
        sceneRoot.animate()
                .alpha(1f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 260L)
                .setInterpolator(Motion.easeOutExpo())
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exiting) return;
        wakeKeyDownSeen = false;
        scene.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissAnimated();
    }

    @Override
    protected void onPause() {
        if (scene != null) scene.pauseForExit();
        super.onPause();
        if (!exiting && !isFinishing()) {
            exiting = true;
            if (sceneRoot != null) sceneRoot.animate().cancel();
            finishSilently();
        }
    }

    @Override
    protected void onDestroy() {
        if (sceneRoot != null) sceneRoot.animate().cancel();
        if (scene != null) scene.releaseResources();
        super.onDestroy();
    }

    private void dismissAnimated() {
        if (exiting || isFinishing()) return;
        exiting = true;
        if (scene != null) scene.pauseForExit();
        if (sceneRoot == null) {
            finishSilently();
            return;
        }
        sceneRoot.animate().cancel();
        sceneRoot.animate()
                .alpha(0f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 250L)
                .setInterpolator(reducedMotion ? Motion.smoothDecel() : Motion.easeOutExpo())
                .withEndAction(this::finishSilently)
                .start();
    }

    @SuppressWarnings("deprecation")
    private void finishSilently() {
        if (!isFinishing()) super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        dismissAnimated();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (exiting) return true;
        int code = event.getKeyCode();
        // Volume keys adjust TV audio naturally without closing the ambient screensaver.
        if (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        // When music is active, directional & media keys control playback directly without waking;
        // DPAD_UP opens the active media app directly and closes the screensaver.
        if (scene != null && scene.hasActiveMedia() && isMediaControlKey(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (scene.openActiveMediaApp(this)) {
                        exiting = true;
                        scene.pauseForExit();
                        finishSilently();
                    }
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    scene.skipPrevious();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    scene.skipNext();
                } else {
                    scene.togglePlayPause();
                }
            }
            return true;
        }
        // When Aerial video is active: Left/Right (when no music) or Channel/Info (anytime) skips to next view!
        if (scene != null && scene.isAerialVideoActive()
                && (code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || (!scene.hasActiveMedia() && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)))) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                scene.skipAerial();
            }
            return true;
        }
        // Watch from Screensaver: OK opens the featured pick directly when enabled.
        if (scene != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && scene.hasFeaturedPick()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                wakeKeyDownSeen = true;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && wakeKeyDownSeen && scene.openFeaturedPick(this)) {
                exiting = true;
                scene.pauseForExit();
                finishSilently();
                return true;
            }
        }
        // Any other key (OK, Back, etc.) wakes cleanly once pressed inside the screensaver.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            wakeKeyDownSeen = true;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && wakeKeyDownSeen) {
            dismissAnimated();
        }
        return true;
    }

    static boolean isMediaControlKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        dismissAnimated();
        return true;
    }
}
