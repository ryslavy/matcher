package com.gathin.tvhome;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks active and resumable Android MediaSessions via {@link GuardRevival} so the top-right vinyl button
 * and quick-settings mini-player stay synchronized in real time across Spotify, YouTube, SoundCloud, radio, etc.
 */
final class MediaSessionTracker {

    private static final String TAG = "TvHome.Media";

    interface Listener {
        void onSessionsUpdated(List<MediaController> controllers);
    }

    private static final class TrackedSession {
        final MediaController controller;
        final MediaController.Callback callback;
        @Nullable PlaybackState state;
        @Nullable MediaMetadata metadata;

        TrackedSession(MediaController controller, MediaController.Callback callback,
                       @Nullable PlaybackState state, @Nullable MediaMetadata metadata) {
            this.controller = controller;
            this.callback = callback;
            this.state = state;
            this.metadata = metadata;
        }
    }

    private final Context context;
    private final ComponentName listenerComponent;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, TrackedSession> tracked = new LinkedHashMap<>();
    private final List<MediaController> current = new ArrayList<>();
    private MediaSessionManager manager;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = controllers ->
            main.post(() -> applyControllers(controllers));

    MediaSessionTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listenerComponent = new ComponentName(this.context, GuardRevival.class);
        this.listener = listener;
    }

    void start() {
        if (started) {
            refresh();
            return;
        }
        manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return;
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main);
            started = true;
            refresh();
        } catch (SecurityException notGranted) {
            // Notification listener permission not yet granted (cmd notification allow_listener ...); stay silent.
            started = false;
            clearTracked();
            listener.onSessionsUpdated(Collections.emptyList());
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaSessionManager unavailable: " + e);
            started = false;
        }
    }

    void refresh() {
        if (!started) return;
        if (manager == null) manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return;
        try {
            applyControllers(manager.getActiveSessions(listenerComponent));
        } catch (SecurityException notGranted) {
            clearTracked();
            listener.onSessionsUpdated(Collections.emptyList());
        } catch (RuntimeException ignored) {
        }
    }

    void stop() {
        started = false;
        main.removeCallbacksAndMessages(null);
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
        }
        clearTracked();
    }

    List<MediaController> controllers() {
        return Collections.unmodifiableList(new ArrayList<>(current));
    }

    private void clearTracked() {
        for (TrackedSession ts : tracked.values()) {
            try {
                ts.controller.unregisterCallback(ts.callback);
            } catch (RuntimeException ignored) {
            }
        }
        tracked.clear();
        current.clear();
    }

    private void applyControllers(@Nullable List<MediaController> raw) {
        if (!started) return;
        // Re-sync callbacks by MediaSession.Token (Android returns fresh MediaController wrappers each call)
        // and cache playback state + metadata locally so state ticks avoid redundant Binder IPCs.
        List<MediaController> incoming = raw != null ? raw : Collections.emptyList();
        Set<MediaSession.Token> incomingTokens = new HashSet<>();
        for (MediaController c : incoming) {
            if (c != null && c.getSessionToken() != null) incomingTokens.add(c.getSessionToken());
        }
        for (MediaSession.Token oldToken : new ArrayList<>(tracked.keySet())) {
            if (!incomingTokens.contains(oldToken)) {
                TrackedSession old = tracked.remove(oldToken);
                if (old != null) {
                    try {
                        old.controller.unregisterCallback(old.callback);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        for (MediaController c : incoming) {
            if (c == null) continue;
            MediaSession.Token token = c.getSessionToken();
            if (token == null || tracked.containsKey(token)) continue;
            final TrackedSession[] holder = new TrackedSession[1];
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(@Nullable PlaybackState state) {
                    if (holder[0] != null) holder[0].state = state;
                    notifyFiltered();
                }

                @Override
                public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                    if (holder[0] != null) holder[0].metadata = metadata;
                    notifyFiltered();
                }

                @Override
                public void onSessionDestroyed() {
                    main.post(() -> refresh());
                }
            };
            try {
                c.registerCallback(cb, main);
                TrackedSession ts = new TrackedSession(c, cb, c.getPlaybackState(), c.getMetadata());
                holder[0] = ts;
                tracked.put(token, ts);
            } catch (RuntimeException ignored) {
            }
        }
        notifyFiltered();
    }

    private void notifyFiltered() {
        String selfPkg = context.getPackageName();
        Map<String, TrackedSession> byPkg = new LinkedHashMap<>();
        for (TrackedSession ts : tracked.values()) {
            String pkg = ts.controller.getPackageName();
            PlaybackState ps = ts.state;
            MediaMetadata md = ts.metadata;
            int state = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            long actions = ps != null ? ps.getActions() : 0L;
            String title = extractTitle(md);
            if (!isRelevantSession(pkg, selfPkg, state, actions, !TextUtils.isEmpty(title))) continue;

            TrackedSession existing = byPkg.get(pkg);
            if (existing == null || compareTracked(ts, existing) < 0) {
                byPkg.put(pkg, ts);
            }
        }
        List<TrackedSession> sorted = new ArrayList<>(byPkg.values());
        sorted.sort(MediaSessionTracker::compareTracked);
        current.clear();
        for (TrackedSession ts : sorted) {
            current.add(ts.controller);
        }
        listener.onSessionsUpdated(Collections.unmodifiableList(new ArrayList<>(current)));
    }

    private static int compareTracked(TrackedSession a, TrackedSession b) {
        PlaybackState pa = a.state;
        PlaybackState pb = b.state;
        int sa = pa != null ? pa.getState() : PlaybackState.STATE_NONE;
        int sb = pb != null ? pb.getState() : PlaybackState.STATE_NONE;
        long ua = pa != null ? pa.getLastPositionUpdateTime() : 0L;
        long ub = pb != null ? pb.getLastPositionUpdateTime() : 0L;
        return compareSessions(sa, ua, sb, ub);
    }

    /** True for states representing active playback or active buffering/seeking. */
    static boolean isPlayingState(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING;
    }

    /**
     * Pure filter determining whether a media session should appear in the Now Playing pill / mini-player.
     * Excludes our launcher itself, stopped/error sessions, and disconnected Bluetooth ghost sessions.
     */
    static boolean isRelevantSession(@Nullable String pkg, @Nullable String selfPkg,
                                     int playbackState, long actions, boolean hasTitle) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(selfPkg)) return false;
        if (isPlayingState(playbackState)) return true;
        if (playbackState == PlaybackState.STATE_PAUSED) {
            if ("com.android.bluetooth".equals(pkg) && !hasTitle) return false;
            boolean canResume = (actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
            return hasTitle || canResume;
        }
        return false;
    }

    /** Orders playing sessions ahead of paused ones, breaking ties by most recent state update first. */
    static int compareSessions(int stateA, long updatedA, int stateB, long updatedB) {
        boolean playA = isPlayingState(stateA);
        boolean playB = isPlayingState(stateB);
        if (playA != playB) return playA ? -1 : 1;
        return Long.compare(ubClamp(updatedB), ubClamp(updatedA));
    }

    private static long ubClamp(long v) {
        return Math.max(0L, v);
    }

    /** Pauses any other playing sessions and starts playback on {@code target}. */
    void switchAndPlay(@NonNull MediaController target) {
        MediaSession.Token targetToken = target.getSessionToken();
        for (MediaController other : current) {
            if (other == target || (targetToken != null && targetToken.equals(other.getSessionToken()))) continue;
            TrackedSession ts = other.getSessionToken() != null ? tracked.get(other.getSessionToken()) : null;
            PlaybackState ps = ts != null ? ts.state : other.getPlaybackState();
            if (ps != null && isPlayingState(ps.getState())) {
                try {
                    other.getTransportControls().pause();
                } catch (RuntimeException ignored) {
                }
            }
        }
        try {
            target.getTransportControls().play();
        } catch (RuntimeException ignored) {
        }
    }

    void togglePlayPause(@NonNull MediaController target) {
        PlaybackState ps = target.getPlaybackState();
        if (ps != null && isPlayingState(ps.getState())) {
            try {
                target.getTransportControls().pause();
            } catch (RuntimeException ignored) {
            }
        } else {
            switchAndPlay(target);
        }
    }

    static void skipPrevious(@NonNull MediaController target) {
        PlaybackState ps = target.getPlaybackState();
        long actions = ps != null ? ps.getActions() : 0L;
        try {
            if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0 || actions == 0L) {
                target.getTransportControls().skipToPrevious();
            } else if ((actions & PlaybackState.ACTION_REWIND) != 0) {
                target.getTransportControls().rewind();
            } else if ((actions & PlaybackState.ACTION_SEEK_TO) != 0 && ps != null) {
                long pos = currentPositionMs(ps, SystemClock.elapsedRealtime());
                target.getTransportControls().seekTo(Math.max(0L, pos - 10_000L));
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void skipNext(@NonNull MediaController target) {
        PlaybackState ps = target.getPlaybackState();
        long actions = ps != null ? ps.getActions() : 0L;
        try {
            if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0 || actions == 0L) {
                target.getTransportControls().skipToNext();
            } else if ((actions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
                target.getTransportControls().fastForward();
            } else if ((actions & PlaybackState.ACTION_SEEK_TO) != 0 && ps != null) {
                long pos = currentPositionMs(ps, SystemClock.elapsedRealtime());
                target.getTransportControls().seekTo(pos + 10_000L);
            }
        } catch (RuntimeException ignored) {
        }
    }

    static boolean canSeek(long actions, long durationMs) {
        return durationMs > 0L && (actions == 0L || (actions & PlaybackState.ACTION_SEEK_TO) != 0L);
    }

    static boolean canSeek(@Nullable PlaybackState ps, long durationMs) {
        return ps != null && canSeek(ps.getActions(), durationMs);
    }

    /** Adaptive seek step that scales smoothly with track duration and D-pad hold repeat count. */
    static long seekStepMs(long durationMs, int repeatCount) {
        long base;
        if (durationMs <= 15L * 60_000L) base = 5_000L;
        else if (durationMs <= 60L * 60_000L) base = 15_000L;
        else base = 30_000L;
        if (repeatCount >= 12) return base * 4L;
        if (repeatCount >= 4) return base * 2L;
        return base;
    }

    static long clampPosition(long positionMs, long durationMs) {
        if (durationMs <= 0L) return 0L;
        return Math.max(0L, Math.min(durationMs, positionMs));
    }

    static void seekTo(@NonNull MediaController target, long positionMs) {
        try {
            target.getTransportControls().seekTo(Math.max(0L, positionMs));
        } catch (RuntimeException ignored) {
        }
    }

    static boolean openSessionApp(Context ctx, @NonNull MediaController target) {
        // Launch directly from our foreground activity first: on Android 14+ (API 34), sending another app's
        // sessionActivity PendingIntent is silently blocked by BAL hardening unless both sides opt in.
        PackageManager pm = ctx.getPackageManager();
        String pkg = target.getPackageName();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                ctx.startActivity(launch);
                RecentApps.recordLaunch(ctx, pkg);
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        // Fallback for headless/cast sessions without a launcher activity, opting into BAL on Android 14+.
        PendingIntent pi = target.getSessionActivity();
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Bundle opts = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            .toBundle();
                    pi.send(ctx, 0, null, null, null, null, opts);
                } else {
                    pi.send();
                }
                RecentApps.recordLaunch(ctx, pkg);
                return true;
            } catch (PendingIntent.CanceledException | RuntimeException ignored) {
            }
        }
        return false;
    }

    @Nullable
    static Bitmap extractArtwork(Context ctx, @Nullable MediaMetadata md, int maxSizePx,
                                 @Nullable Map<String, Bitmap> uriCache) {
        if (md == null) return null;
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp != null && !bmp.isRecycled()) return bmp;

        if (ctx == null) return null;
        String uriStr = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (TextUtils.isEmpty(uriStr)) uriStr = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (TextUtils.isEmpty(uriStr)) uriStr = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (TextUtils.isEmpty(uriStr) || (!uriStr.startsWith("content://") && !uriStr.startsWith("file://"))) {
            return null;
        }
        if (uriCache != null) {
            Bitmap cached = uriCache.get(uriStr);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = Uri.parse(uriStr);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (java.io.InputStream boundsIn = cr.openInputStream(uri)) {
                if (boundsIn == null) return null;
                BitmapFactory.decodeStream(boundsIn, null, opts);
            }
            int sample = 1;
            int longest = Math.max(opts.outWidth, opts.outHeight);
            while (maxSizePx > 0 && longest / (sample * 2) >= maxSizePx) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            try (java.io.InputStream dataIn = cr.openInputStream(uri)) {
                if (dataIn == null) return null;
                Bitmap decoded = BitmapFactory.decodeStream(dataIn, null, opts);
                if (decoded != null && uriCache != null) {
                    if (uriCache.size() >= 4) uriCache.clear();
                    uriCache.put(uriStr, decoded);
                }
                return decoded;
            }
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    @Nullable
    static String extractTitle(@Nullable MediaMetadata md) {
        if (md == null) return null;
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (TextUtils.isEmpty(t)) t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        return TextUtils.isEmpty(t) ? null : t.toString().trim();
    }

    @Nullable
    static String extractSubtitle(@Nullable MediaMetadata md) {
        if (md == null) return null;
        String title = extractTitle(md);
        CharSequence artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        CharSequence album = md.getText(MediaMetadata.METADATA_KEY_ALBUM);
        String a = TextUtils.isEmpty(artist) ? "" : artist.toString().trim();
        String al = TextUtils.isEmpty(album) ? "" : album.toString().trim();
        if (!a.isEmpty() && !al.isEmpty() && !al.equalsIgnoreCase(a)
                && (title == null || !al.equalsIgnoreCase(title))) {
            return a + " \u00B7 " + al;
        }
        return !a.isEmpty() ? a : (!al.isEmpty() && (title == null || !al.equalsIgnoreCase(title))) ? al : null;
    }

    static long currentPositionMs(@Nullable PlaybackState state, long elapsedRealtimeMs) {
        if (state == null) return 0L;
        return interpolatePositionMs(state.getState(), state.getPosition(), state.getPlaybackSpeed(),
                state.getLastPositionUpdateTime(), elapsedRealtimeMs);
    }

    /** Pure elapsed-position helper for unit tests and live progress bar ticks. */
    static long interpolatePositionMs(int playbackState, long positionMs, float speed,
                                      long lastUpdateElapsedMs, long nowElapsedMs) {
        if (positionMs < 0L) return 0L;
        if (playbackState == PlaybackState.STATE_PLAYING && lastUpdateElapsedMs > 0L && nowElapsedMs > lastUpdateElapsedMs) {
            float rate = speed > 0f ? speed : 1f;
            return Math.max(0L, positionMs + Math.round((nowElapsedMs - lastUpdateElapsedMs) * rate));
        }
        return positionMs;
    }

    static String formatTime(long ms) {
        if (ms <= 0L) return "0:00";
        long totalSec = ms / 1000L;
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hours > 0L) return String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs);
        return String.format(Locale.US, "%d:%02d", mins, secs);
    }
}
