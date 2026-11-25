package com.gathin.tvhome;

import android.media.session.PlaybackState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaSessionsTest {

    @Test
    public void relevantSessionFiltersGhostsAndSelf() {
        assertFalse(MediaSessionTracker.isRelevantSession(
                "com.gathin.tvhome", "com.gathin.tvhome", PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE, true));
        assertFalse(MediaSessionTracker.isRelevantSession(
                "com.android.bluetooth", "com.gathin.tvhome", PlaybackState.STATE_ERROR, 0L, false));
        assertFalse(MediaSessionTracker.isRelevantSession(
                "com.android.bluetooth", "com.gathin.tvhome", PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY, false));
        assertFalse(MediaSessionTracker.isRelevantSession(
                "com.spotify.tv.android", "com.gathin.tvhome", PlaybackState.STATE_STOPPED, PlaybackState.ACTION_PLAY, true));

        assertTrue(MediaSessionTracker.isRelevantSession(
                "com.spotify.tv.android", "com.gathin.tvhome", PlaybackState.STATE_PLAYING, 0L, false));
        assertTrue(MediaSessionTracker.isRelevantSession(
                "com.spotify.tv.android", "com.gathin.tvhome", PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY, true));
        assertTrue(MediaSessionTracker.isRelevantSession(
                "com.android.bluetooth", "com.gathin.tvhome", PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY, true));
    }

    @Test
    public void playingSessionsSortAheadOfPausedAndMostRecentFirst() {
        assertTrue(MediaSessionTracker.compareSessions(
                PlaybackState.STATE_PLAYING, 100L, PlaybackState.STATE_PAUSED, 9999L) < 0);
        assertTrue(MediaSessionTracker.compareSessions(
                PlaybackState.STATE_PAUSED, 500L, PlaybackState.STATE_PAUSED, 100L) < 0);
        assertTrue(MediaSessionTracker.compareSessions(
                PlaybackState.STATE_BUFFERING, 100L, PlaybackState.STATE_PAUSED, 5000L) < 0);
    }

    @Test
    public void positionInterpolationAndTimeFormatting() {
        assertEquals(35_000L, MediaSessionTracker.interpolatePositionMs(
                PlaybackState.STATE_PLAYING, 30_000L, 1.0f, 100_000L, 105_000L));
        assertEquals(30_000L, MediaSessionTracker.interpolatePositionMs(
                PlaybackState.STATE_PAUSED, 30_000L, 0.0f, 100_000L, 105_000L));
        assertEquals("0:00", MediaSessionTracker.formatTime(0L));
        assertEquals("0:28", MediaSessionTracker.formatTime(28_962L));
        assertEquals("3:42", MediaSessionTracker.formatTime(222_000L));
        assertEquals("1:05:09", MediaSessionTracker.formatTime(3_909_000L));
    }

    @Test
    public void seekCapabilityAdaptiveStepAndClamping() {
        assertTrue(MediaSessionTracker.canSeek(PlaybackState.ACTION_SEEK_TO, 180_000L));
        assertTrue(MediaSessionTracker.canSeek(0L, 180_000L));
        assertFalse(MediaSessionTracker.canSeek(PlaybackState.ACTION_PLAY_PAUSE, 180_000L));
        assertFalse(MediaSessionTracker.canSeek(PlaybackState.ACTION_SEEK_TO, 0L));

        assertEquals(5_000L, MediaSessionTracker.seekStepMs(180_000L, 0));
        assertEquals(10_000L, MediaSessionTracker.seekStepMs(180_000L, 4));
        assertEquals(20_000L, MediaSessionTracker.seekStepMs(180_000L, 12));
        assertEquals(15_000L, MediaSessionTracker.seekStepMs(45L * 60_000L, 0));
        assertEquals(30_000L, MediaSessionTracker.seekStepMs(90L * 60_000L, 0));

        assertEquals(0L, MediaSessionTracker.clampPosition(-5_000L, 180_000L));
        assertEquals(180_000L, MediaSessionTracker.clampPosition(200_000L, 180_000L));
        assertEquals(45_000L, MediaSessionTracker.clampPosition(45_000L, 180_000L));
    }
}
