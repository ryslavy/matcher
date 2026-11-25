package com.gathin.tvhome;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecentAppsTest {

    @Test
    public void filtersExcludedAndDismissedUntilRelaunched() {
        Set<String> excluded = RecentApps.buildExcludedSet("com.gathin.tvhome");

        assertFalse(RecentApps.shouldInclude("com.gathin.tvhome", excluded, 1000L, 0L, false));
        assertFalse(RecentApps.shouldInclude("com.google.android.apps.tv.launcherx", excluded, 1000L, 0L, false));
        assertFalse(RecentApps.shouldInclude("com.spotify.tv.android", excluded, 1000L, 1500L, false));
        assertFalse(RecentApps.shouldInclude("com.spotify.tv.android", excluded, 1000L, 1000L, false));

        // Relaunched after dismissal -> reappears!
        assertTrue(RecentApps.shouldInclude("com.spotify.tv.android", excluded, 2000L, 1500L, false));
        // Actively playing -> always visible!
        assertTrue(RecentApps.shouldInclude("com.spotify.tv.android", excluded, 1000L, 1500L, true));
    }

    @Test
    public void playingSortsFirstThenMostRecentlyUsed() {
        assertTrue(RecentApps.compareEntries(true, 100L, false, 9999L) < 0);
        assertTrue(RecentApps.compareEntries(false, 5000L, false, 2000L) < 0);
    }

    @Test
    public void holdBackOpensRecentsThreshold() {
        assertFalse(HomeKeyService.isBackHold(0L, 1000L));
        assertFalse(HomeKeyService.isBackHold(1000L, 1250L));
        assertFalse(HomeKeyService.isBackHold(1000L, 1499L));
        assertTrue(HomeKeyService.isBackHold(1000L, 1500L));
        assertTrue(HomeKeyService.isBackHold(1000L, 1850L));
        assertFalse(HomeKeyService.isBackHold(1500L, 1400L));
    }

    @Test
    public void forceStopFiltersSelfAndSettingsAndMatchesLocalizedLabels() {
        assertFalse(AppForceStopper.isForceStoppable(null, "com.gathin.tvhome"));
        assertFalse(AppForceStopper.isForceStoppable("", "com.gathin.tvhome"));
        assertFalse(AppForceStopper.isForceStoppable("com.gathin.tvhome", "com.gathin.tvhome"));
        assertFalse(AppForceStopper.isForceStoppable("com.android.tv.settings", "com.gathin.tvhome"));
        assertFalse(AppForceStopper.isForceStoppable("com.android.settings", "com.gathin.tvhome"));
        assertTrue(AppForceStopper.isForceStoppable("com.spotify.tv.android", "com.gathin.tvhome"));
        assertTrue(AppForceStopper.isForceStoppable("io.gh.reisxd.tizentube.cobalt", "com.gathin.tvhome"));

        Set<String> labels = java.util.Collections.singleton("vynutit zastavení");
        assertTrue(AppForceStopper.matchesLabel("Vynutit zastavení", labels));
        assertTrue(AppForceStopper.matchesLabel("  VYNUTIT ZASTAVENÍ  ", labels));
        assertFalse(AppForceStopper.matchesLabel("Odinstalovat", labels));
        assertFalse(AppForceStopper.matchesLabel("Vymazat data", labels));
        assertFalse(AppForceStopper.matchesLabel(null, labels));
    }
}
