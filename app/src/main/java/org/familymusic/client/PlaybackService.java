package org.familymusic.client;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.media.audiofx.LoudnessEnhancer;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class PlaybackService extends MediaSessionService {
    static final String ACTION_SLEEP_TIMER = BuildConfig.APPLICATION_ID + ".SLEEP_TIMER";
    static final String EXTRA_SLEEP_MINUTES = "minutes";
    static final String ACTION_NORMALIZATION_CHANGED = BuildConfig.APPLICATION_ID + ".NORMALIZATION_CHANGED";
    private MediaSession mediaSession;
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();
    private volatile CacheWriter activePrefetch;
    private volatile String prefetchedMediaId = "";
    private String cookie;
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private final Handler prefetchHandler = new Handler(Looper.getMainLooper());
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Runnable sleepPause = () -> { if (mediaSession != null) mediaSession.getPlayer().pause(); new AppSettings(this).putLong("sleep_deadline", 0); };
    private LoudnessEnhancer loudnessEnhancer;
    private int audioSessionId = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET;
    private boolean restoringPosition;
    private final PlaybackRetryGuard retryGuard = new PlaybackRetryGuard();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override public void onCreate() {
        super.onCreate();
        ApiClient.initialize(this);
        DiagnosticLog.add(this, "playback service created");
        cookie = getSharedPreferences("session", MODE_PRIVATE).getString("cookie", "");
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 90_000, 1_000, 2_000)
            .setBackBuffer(30_000, true)
            .build();
        ExoPlayer player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(PlaybackCache.get(this).streamingFactory(cookie), extractors))
            .setLoadControl(loadControl)
            .build();
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                retryHandler.removeCallbacksAndMessages(null);
                retryGuard.transition(mediaItem == null ? "" : mediaItem.mediaId);
                DiagnosticLog.add(PlaybackService.this, "transition reason=" + reason + " track=" + (mediaItem == null ? "none" : mediaItem.mediaId) + " index=" + player.getCurrentMediaItemIndex());
                schedulePrefetch(player);
                applyNormalization(player);
            }
            @Override public void onAudioSessionIdChanged(int id) { audioSessionId=id; recreateLoudnessEnhancer(); applyNormalization(player); }
            @Override public void onShuffleModeEnabledChanged(boolean enabled) {
                DiagnosticLog.add(PlaybackService.this, "shuffle=" + enabled);
                schedulePrefetch(player);
            }
            @Override public void onRepeatModeChanged(int repeatMode) {
                DiagnosticLog.add(PlaybackService.this, "repeat=" + repeatMode);
                schedulePrefetch(player);
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                DiagnosticLog.add(PlaybackService.this, "position discontinuity reason=" + discontinuityName(reason) + " from=" + oldPosition.positionMs + " to=" + newPosition.positionMs + " oldTrack=" + oldPosition.mediaItemIndex + " newTrack=" + newPosition.mediaItemIndex);
                if (!restoringPosition && reason == Player.DISCONTINUITY_REASON_INTERNAL
                        && oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                        && oldPosition.positionMs > 10_000 && newPosition.positionMs + 5_000 < oldPosition.positionMs) {
                    restoringPosition = true;
                    long restoreTo = oldPosition.positionMs;
                    DiagnosticLog.add(PlaybackService.this, "recover internal reset position=" + restoreTo);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        player.seekTo(oldPosition.mediaItemIndex, restoreTo);
                        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
                        restoringPosition = false;
                    });
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                DiagnosticLog.add(PlaybackService.this, "state=" + state + " playWhenReady=" + player.getPlayWhenReady() + " position=" + player.getCurrentPosition() + " duration=" + player.getDuration());
                if (state == Player.STATE_READY && retryGuard.hasAttempts()) {
                    DiagnosticLog.add(PlaybackService.this, "playback recovered track=" + (player.getCurrentMediaItem() == null ? "none" : player.getCurrentMediaItem().mediaId));
                    retryHandler.removeCallbacksAndMessages(null);
                    retryGuard.recovered();
                    schedulePrefetch(player);
                }
                if (state == Player.STATE_ENDED && player.getShuffleModeEnabled() && player.getMediaItemCount() > 1 && player.getRepeatMode() == Player.REPEAT_MODE_OFF) {
                    int first = player.getCurrentTimeline().getFirstWindowIndex(true);
                    if (first != androidx.media3.common.C.INDEX_UNSET) { player.seekToDefaultPosition(first); player.prepare(); player.play(); }
                }
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                DiagnosticLog.add(PlaybackService.this, "player error code=" + error.errorCode + " message=" + error.getMessage());
                cancelPrefetch();
                MediaItem current = player.getCurrentMediaItem();
                String mediaId = current == null ? "" : current.mediaId;
                PlaybackRetryGuard.Decision decision = retryGuard.onError(mediaId);
                retryHandler.removeCallbacksAndMessages(null);
                if (current != null && decision.retry) {
                    int attempt = decision.attempt;
                    int index = player.getCurrentMediaItemIndex();
                    long position = Math.max(0, player.getCurrentPosition());
                    DiagnosticLog.add(PlaybackService.this, "retry playback attempt=" + attempt + " track=" + mediaId + " position=" + position);
                    retryHandler.postDelayed(() -> {
                        MediaItem actual = player.getCurrentMediaItem();
                        if (actual != null && retryGuard.isCurrent(decision.token, actual.mediaId)) {
                            player.seekTo(index, position);
                            player.prepare();
                        }
                    }, Math.min(4_000, attempt * 1_000L));
                } else if (player.hasNextMediaItem()) {
                    DiagnosticLog.add(PlaybackService.this, "retry limit reached, skip track=" + mediaId);
                    boolean playWhenReady = player.getPlayWhenReady();
                    player.seekToNextMediaItem();
                    player.prepare();
                    if (!playWhenReady) player.pause();
                } else {
                    player.pause();
                }
            }
        });
        registerNetworkLogging();
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        mediaSession = new MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(new SessionBitmapLoader(this, cookie))
            .build();
        long remaining = new AppSettings(this).sleepDeadline() - System.currentTimeMillis();
        if (remaining > 0) sleepHandler.postDelayed(sleepPause, remaining); else new AppSettings(this).putLong("sleep_deadline", 0);
    }

    private static String discontinuityName(int reason) {
        return switch (reason) {
            case Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "auto";
            case Player.DISCONTINUITY_REASON_SEEK -> "seek";
            case Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "seek_adjustment";
            case Player.DISCONTINUITY_REASON_REMOVE -> "remove";
            case Player.DISCONTINUITY_REASON_SKIP -> "skip";
            case Player.DISCONTINUITY_REASON_INTERNAL -> "internal";
            default -> String.valueOf(reason);
        };
    }

    private void registerNetworkLogging() {
        connectivityManager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { logNetwork("available", network); }
            @Override public void onLost(Network network) { DiagnosticLog.add(PlaybackService.this, "network lost=" + network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { logNetwork("changed", network); }
        };
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback); } catch (RuntimeException error) { DiagnosticLog.add(this, "network callback unavailable=" + error.getClass().getSimpleName()); }
    }

    private void logNetwork(String event, Network network) {
        NetworkCapabilities capabilities = connectivityManager == null ? null : connectivityManager.getNetworkCapabilities(network);
        String transport = capabilities == null ? "unknown" : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn" : "other";
        DiagnosticLog.add(this, "network " + event + "=" + transport + " validated=" + (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
    }

    private void recreateLoudnessEnhancer() {
        if (loudnessEnhancer != null) { loudnessEnhancer.release(); loudnessEnhancer=null; }
        if (audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) try { loudnessEnhancer=new LoudnessEnhancer(audioSessionId); } catch (Exception ignored) {}
    }

    private void applyNormalization(ExoPlayer player) {
        double db=0; MediaItem item=player.getCurrentMediaItem();
        if (new AppSettings(this).loudnessNormalization() && item != null && item.mediaMetadata.extras != null) db=item.mediaMetadata.extras.getDouble("replay_gain_db",0);
        player.setVolume((float)Math.min(1,Math.pow(10,Math.min(0,db)/20)));
        if (loudnessEnhancer != null) try { loudnessEnhancer.setTargetGain((int)Math.round(Math.max(0,db)*100));loudnessEnhancer.setEnabled(db>0); } catch (Exception ignored) {}
    }

    private void schedulePrefetch(ExoPlayer player) {
        prefetchHandler.removeCallbacksAndMessages(null);
        cancelPrefetch();
        prefetchedMediaId = "";
        MediaItem current = player.getCurrentMediaItem();
        if (current == null) return;
        String currentId = current.mediaId;
        prefetchHandler.postDelayed(() -> {
            MediaItem actual = player.getCurrentMediaItem();
            if (actual != null && actual.mediaId.equals(currentId)) prefetchNext(player);
        }, 4000);
    }

    private void prefetchNext(ExoPlayer player) {
        long prefetchBytes = new AppSettings(this).prefetchBytes();
        if (prefetchBytes <= 0) { cancelPrefetch(); prefetchedMediaId = ""; return; }
        int nextIndex = player.getNextMediaItemIndex();
        if (nextIndex < 0 || nextIndex >= player.getMediaItemCount()) {
            cancelPrefetch();
            prefetchedMediaId = "";
            return;
        }
        MediaItem next = player.getMediaItemAt(nextIndex);
        if (next.mediaId.equals(prefetchedMediaId)) return;
        cancelPrefetch();
        prefetchedMediaId = next.mediaId;
        DiagnosticLog.add(this, "prefetch track=" + next.mediaId + " bytes=" + prefetchBytes);
        prefetchExecutor.execute(() -> {
            CacheWriter writer = PlaybackCache.get(this).prefetchWriter(next, cookie, prefetchBytes);
            if (writer == null || !next.mediaId.equals(prefetchedMediaId)) return;
            activePrefetch = writer;
            try { writer.cache(); } catch (Exception ignored) {
            } finally { if (activePrefetch == writer) activePrefetch = null; }
        });
    }

    private void cancelPrefetch() {
        CacheWriter writer = activePrefetch;
        if (writer != null) writer.cancel();
        activePrefetch = null;
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SLEEP_TIMER.equals(intent.getAction())) {
            sleepHandler.removeCallbacks(sleepPause);
            int minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0);
            new AppSettings(this).putLong("sleep_deadline", minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0);
            if (minutes > 0) sleepHandler.postDelayed(sleepPause, minutes * 60_000L);
        }
        if (intent != null && ACTION_NORMALIZATION_CHANGED.equals(intent.getAction()) && mediaSession != null) applyNormalization((ExoPlayer)mediaSession.getPlayer());
        return super.onStartCommand(intent, flags, startId);
    }

    @Override public void onDestroy() {
        cancelPrefetch();
        prefetchHandler.removeCallbacksAndMessages(null);
        retryHandler.removeCallbacksAndMessages(null);
        sleepHandler.removeCallbacks(sleepPause);
        prefetchExecutor.shutdownNow();
        if (connectivityManager != null && networkCallback != null) try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        if (loudnessEnhancer != null) { loudnessEnhancer.release(); loudnessEnhancer=null; }
        super.onDestroy();
    }
}
