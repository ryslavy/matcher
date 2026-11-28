package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Top-level broadcast receiver for {@code adb shell am broadcast -a com.gathin.tvhome.ACTION_RELOAD -p com.gathin.tvhome}
 * (protected by {@code WRITE_SECURE_SETTINGS}): triggers Matcher's graceful Tier 1/2 reload without
 * {@code am force-stop} killing Accessibility services.
 */
public final class ReloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !MainActivity.ACTION_RELOAD.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        boolean reloadTiles = intent.getBooleanExtra("tiles", true);
        ServiceRepair.restore(app);
        MainActivity.requestGracefulReload(app, reloadTiles);
    }
}
