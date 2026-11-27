package com.gathin.tvhome;

import android.app.Application;

/**
 * Application entry point for installing the crash-loop watchdog and priming lightweight
 * background services (synthesized UI click sounds, periodic Immich sync, weekly update check).
 */
public final class TvHomeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashWatchdog.install(this);
        UiClickSound.init(this);
        ExpressiveGlass.setHomeLiquidOpticsEnabled(FeaturePrefs.isHomeLiquidOpticsEnabled(this));
        ImmichSyncManager.scheduleIfEnabled(this);
        SelfUpdater.scheduleWeeklyIfEnabled(this);
    }
}
