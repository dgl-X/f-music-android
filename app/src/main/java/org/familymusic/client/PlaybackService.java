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
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionResult;

import java.util.ArrayList;
import java.util.List;
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
    private volatile long prefetchGeneration;
    private String cookie;
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private final Handler prefetchHandler = new Handler(Looper.getMainLooper());
    private final Handler smartCacheHandler = new Handler(Looper.getMainLooper());
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Runnable sleepPause = () -> { if (mediaSession != null) mediaSession.getPlayer().pause(); new AppSettings(this).putLong("sleep_deadline", 0); };
    private LoudnessEnhancer loudnessEnhancer;
    private int audioSessionId = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET;
    private boolean restoringPosition;
    private final PlaybackRetryGuard retryGuard = new PlaybackRetryGuard();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final NetworkLogGuard networkLogGuard = new NetworkLogGuard();
    private String countedSmartCacheMediaId = "";

    @Override public void onCreate() {
        super.onCreate();
        ApiClient.initialize(this);
        DiagnosticLog.add(this, "playback service created");
        cookie = getSharedPreferences("session", MODE_PRIVATE).getString("cookie", "");
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 180_000, 1_000, 2_000)
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
                smartCacheHandler.removeCallbacksAndMessages(null);
                countedSmartCacheMediaId = "";
                retryGuard.transition(mediaItem == null ? "" : mediaItem.mediaId);
                DiagnosticLog.add(PlaybackService.this, "transition reason=" + reason + " track=" + (mediaItem == null ? "none" : mediaItem.mediaId) + " index=" + player.getCurrentMediaItemIndex());
                schedulePrefetch(player);
                scheduleMeaningfulListen(player);
                skipUnavailableOfflineTransition(player, mediaItem, reason);
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
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) { schedulePrefetch(player); scheduleMeaningfulListen(player); }
                else { prefetchHandler.removeCallbacksAndMessages(null); smartCacheHandler.removeCallbacksAndMessages(null); cancelPrefetch(); }
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                DiagnosticLog.add(PlaybackService.this, "position discontinuity reason=" + discontinuityName(reason) + " from=" + oldPosition.positionMs + " to=" + newPosition.positionMs + " oldTrack=" + oldPosition.mediaItemIndex + " newTrack=" + newPosition.mediaItemIndex);
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) schedulePrefetch(player);
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
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                DiagnosticLog.add(PlaybackService.this, "player error code=" + error.errorCode + " message=" + error.getMessage() + " cause=" + errorCause(error));
                cancelPrefetch();
                MediaItem current = player.getCurrentMediaItem();
                String mediaId = current == null ? "" : current.mediaId;
                boolean validatedNetwork = hasValidatedNetwork();
                int cachedIndex = validatedNetwork ? C.INDEX_UNSET : nextCachedIndex(player);
                SmartCachePolicy.OfflineAction offlineError = SmartCachePolicy.sourceErrorAction(validatedNetwork, cachedIndex != C.INDEX_UNSET);
                if (offlineError != SmartCachePolicy.OfflineAction.KEEP) {
                    if (offlineError == SmartCachePolicy.OfflineAction.SKIP && cachedIndex != C.INDEX_UNSET) {
                        boolean playWhenReady = player.getPlayWhenReady();
                        MediaItem cached = player.getMediaItemAt(cachedIndex);
                        DiagnosticLog.add(PlaybackService.this, "offline fallback track=" + cached.mediaId + " index=" + cachedIndex);
                        new SmartCacheStats(PlaybackService.this).recordOfflineSkip();
                        retryHandler.removeCallbacksAndMessages(null);
                        retryGuard.transition(cached.mediaId);
                        player.seekTo(cachedIndex, 0);
                        player.prepare();
                        if (playWhenReady) player.play(); else player.pause();
                    } else {
                        DiagnosticLog.add(PlaybackService.this, "offline fallback unavailable, pause queue");
                        new SmartCacheStats(PlaybackService.this).recordOfflineStop();
                        player.pause();
                    }
                    return;
                }
                boolean unavailableRemote = isUnavailableRemote(error, mediaId);
                PlaybackRetryGuard.Decision decision = retryGuard.onError(mediaId, unavailableRemote ? 1 : PlaybackRetryGuard.MAX_ATTEMPTS);
                if (unavailableRemote) DiagnosticLog.add(PlaybackService.this, "remote node unavailable, short retry track=" + mediaId);
                retryHandler.removeCallbacksAndMessages(null);
                if (current != null && decision.retry) {
                    if (decision.attempt == 1) {
                        PlaybackCache.get(PlaybackService.this).remove(current);
                        DiagnosticLog.add(PlaybackService.this, "discard playback cache after source error track=" + mediaId);
                    }
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
            .setCallback(new MediaSession.Callback() {
                @Override public int onPlayerCommandRequest(MediaSession session, MediaSession.ControllerInfo controller, int command) {
                    if (command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                            || command == Player.COMMAND_SEEK_BACK
                            || command == Player.COMMAND_SEEK_FORWARD) {
                        cancelPrefetch();
                        DiagnosticLog.add(PlaybackService.this, "cancel smart cache before seek command=" + command);
                    }
                    return SessionResult.RESULT_SUCCESS;
                }
            })
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(new SessionBitmapLoader(this, cookie))
            .build();
        long remaining = new AppSettings(this).sleepDeadline() - System.currentTimeMillis();
        if (remaining > 0) sleepHandler.postDelayed(sleepPause, remaining); else new AppSettings(this).putLong("sleep_deadline", 0);
    }

    private static boolean isUnavailableRemote(androidx.media3.common.PlaybackException error, String mediaId) {
        if (mediaId == null || !mediaId.startsWith("remote:")) return false;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return code == 502 || code == 503;
            }
        }
        return false;
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

    private static String errorCause(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) result.append(" <- ");
            result.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) result.append(':').append(current.getMessage());
        }
        return result.toString();
    }

    private void registerNetworkLogging() {
        connectivityManager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { logNetwork(network); }
            @Override public void onLost(Network network) { if (networkLogGuard.lost(network.toString())) DiagnosticLog.add(PlaybackService.this, "network lost"); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { logNetwork(network, capabilities); }
        };
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback); } catch (RuntimeException error) { DiagnosticLog.add(this, "network callback unavailable=" + error.getClass().getSimpleName()); }
    }

    private void logNetwork(Network network) {
        NetworkCapabilities capabilities = connectivityManager == null ? null : connectivityManager.getNetworkCapabilities(network);
        logNetwork(network, capabilities);
    }

    private void logNetwork(Network network, NetworkCapabilities capabilities) {
        String state = capabilities == null ? "transport=unknown validated=false vpn=false" : NetworkLogGuard.describe(
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        if (networkLogGuard.update(network.toString(), state)) DiagnosticLog.add(this, "network " + state);
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
        MediaItem current = player.getCurrentMediaItem();
        if (current == null) return;
        String currentId = current.mediaId;
        prefetchHandler.postDelayed(() -> {
            MediaItem actual = player.getCurrentMediaItem();
            if (player.isPlaying() && actual != null && actual.mediaId.equals(currentId)) prefetchNext(player);
        }, 3000);
    }

    private void prefetchNext(ExoPlayer player) {
        AppSettings settings = new AppSettings(this);
        long prefetchBytes = settings.prefetchBytes();
        if (prefetchBytes <= 0) { cancelPrefetch(); return; }
        MediaItem current = player.getCurrentMediaItem();
        List<MediaItem> upcoming = upcomingItems(player, settings.smartCacheDepth());
        cancelPrefetch();
        long generation = prefetchGeneration;
        DiagnosticLog.add(this, "smart cache upcoming=" + upcoming.size() + " bytes=" + prefetchBytes);
        prefetchExecutor.execute(() -> {
            if (!upcoming.isEmpty()) prefetchPart(upcoming.get(0), prefetchBytes, generation);
            for (int i = 1; i < upcoming.size(); i++) prefetchPart(upcoming.get(i), prefetchBytes, generation);
        });
    }

    private void scheduleMeaningfulListen(ExoPlayer player) {
        MediaItem current = player.getCurrentMediaItem();
        if (!player.isPlaying() || current == null || current.mediaId.equals(countedSmartCacheMediaId)) return;
        String expectedId = current.mediaId;
        boolean startedCached = PlaybackCache.get(this).isFullyAvailable(current);
        smartCacheHandler.removeCallbacksAndMessages(null);
        smartCacheHandler.postDelayed(() -> {
            MediaItem actual = player.getCurrentMediaItem();
            if (!player.isPlaying() || actual == null || !expectedId.equals(actual.mediaId)
                    || expectedId.equals(countedSmartCacheMediaId)) return;
            countedSmartCacheMediaId = expectedId;
            new SmartCacheStats(PlaybackService.this).recordMeaningfulListen(startedCached);
            DiagnosticLog.add(PlaybackService.this, "smart cache meaningful listen track=" + expectedId + " hit=" + startedCached);
            AppSettings settings = new AppSettings(PlaybackService.this);
            if (!SmartCachePolicy.mayCompleteTrack(settings.smartCacheEnabled(), settings.smartCacheWifiOnly(), isUnmeteredNetwork())) return;
            long generation = prefetchGeneration;
            prefetchExecutor.execute(() -> prefetchComplete(actual, generation, settings.smartCacheMaxTrackBytes()));
        }, SmartCachePolicy.MEANINGFUL_LISTEN_MS);
    }

    private List<MediaItem> upcomingItems(ExoPlayer player, int limit) {
        List<MediaItem> result = new ArrayList<>();
        int current = player.getCurrentMediaItemIndex(), index = current;
        int repeatMode = player.getRepeatMode() == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_OFF : player.getRepeatMode();
        for (int checked = 0; checked < player.getMediaItemCount() - 1 && result.size() < limit && index != C.INDEX_UNSET; checked++) {
            index = player.getCurrentTimeline().getNextWindowIndex(index, repeatMode, player.getShuffleModeEnabled());
            if (index == C.INDEX_UNSET || index == current) break;
            MediaItem item = player.getMediaItemAt(index);
            if (!result.contains(item)) result.add(item);
        }
        return result;
    }

    private int nextCachedIndex(ExoPlayer player) {
        int current = player.getCurrentMediaItemIndex(), index = current;
        int repeatMode = player.getRepeatMode() == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_OFF : player.getRepeatMode();
        for (int checked = 0; checked < player.getMediaItemCount() - 1; checked++) {
            index = player.getCurrentTimeline().getNextWindowIndex(index, repeatMode, player.getShuffleModeEnabled());
            if (index == C.INDEX_UNSET || index == current) return C.INDEX_UNSET;
            if (PlaybackCache.get(this).isFullyAvailable(player.getMediaItemAt(index))) return index;
        }
        return C.INDEX_UNSET;
    }

    private void skipUnavailableOfflineTransition(ExoPlayer player, @Nullable MediaItem transitioned, int reason) {
        if (connectivityManager == null || transitioned == null) return;
        // Do not interrupt a track which was already playing when connectivity
        // disappeared. Its Media3 buffer and partial cache may still last until
        // the network returns; the error handler performs the fallback later.
        boolean eligibleTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED;
        if (!eligibleTransition) return;
        SmartCachePolicy.OfflineAction initialAction = SmartCachePolicy.transitionAction(
                hasValidatedNetwork(), PlaybackCache.get(this).isFullyAvailable(transitioned), true, true);
        if (initialAction == SmartCachePolicy.OfflineAction.KEEP) return;
        String expectedId = transitioned.mediaId;
        prefetchHandler.post(() -> {
            MediaItem actual = player.getCurrentMediaItem();
            if (actual == null || !expectedId.equals(actual.mediaId) || hasValidatedNetwork()
                    || PlaybackCache.get(PlaybackService.this).isFullyAvailable(actual)) return;
            int cachedIndex = nextCachedIndex(player);
            SmartCachePolicy.OfflineAction action = SmartCachePolicy.transitionAction(false, false, true, cachedIndex != C.INDEX_UNSET);
            if (action == SmartCachePolicy.OfflineAction.PAUSE) {
                DiagnosticLog.add(PlaybackService.this, "offline transition has no cached continuation track=" + expectedId);
                new SmartCacheStats(PlaybackService.this).recordOfflineStop();
                player.pause();
                return;
            }
            boolean playWhenReady = player.getPlayWhenReady();
            MediaItem cached = player.getMediaItemAt(cachedIndex);
            DiagnosticLog.add(PlaybackService.this, "offline transition skip=" + expectedId + " cached=" + cached.mediaId + " index=" + cachedIndex);
            new SmartCacheStats(PlaybackService.this).recordOfflineSkip();
            retryGuard.transition(cached.mediaId);
            player.seekTo(cachedIndex, 0);
            player.prepare();
            if (playWhenReady) player.play(); else player.pause();
        });
    }

    private void prefetchPart(MediaItem item, long bytes, long generation) {
        if (item == null || generation != prefetchGeneration) return;
        CacheWriter writer = PlaybackCache.get(this).prefetchWriter(item, cookie, bytes);
        runPrefetch(writer, generation);
    }

    private void prefetchComplete(MediaItem item, long generation, long maxTrackBytes) {
        if (item == null || generation != prefetchGeneration) return;
        CacheWriter writer = PlaybackCache.get(this).completeWriter(item, cookie, maxTrackBytes);
        runPrefetch(writer, generation);
    }

    private void runPrefetch(CacheWriter writer, long generation) {
        if (writer == null || generation != prefetchGeneration) return;
        activePrefetch = writer;
        try { writer.cache(); } catch (Exception ignored) {
        } finally { if (activePrefetch == writer) activePrefetch = null; }
    }

    private boolean isUnmeteredNetwork() {
        if (connectivityManager == null) return false;
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private boolean hasValidatedNetwork() {
        if (connectivityManager == null) return false;
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void cancelPrefetch() {
        prefetchGeneration++;
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
        smartCacheHandler.removeCallbacksAndMessages(null);
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
