package org.familymusic.client;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
final class PlaybackCache {
    private static final String CACHE_PREFIX = "stream-v3:";
    private static final ExecutorService WARMUP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "playback-cache-warmup");
        thread.setDaemon(true);
        return thread;
    });
    private static PlaybackCache instance;
    private final SimpleCache cache;
    private final Context context;

    static synchronized PlaybackCache get(Context context) {
        if (instance == null) instance = new PlaybackCache(context.getApplicationContext());
        return instance;
    }

    static void warm(Context context, Runnable ready) {
        Context application = context.getApplicationContext();
        WARMUP.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            get(application);
            long elapsed = SystemClock.elapsedRealtime() - started;
            DiagnosticLog.add(application, "startup playback cache ready in " + elapsed + " ms");
            new Handler(Looper.getMainLooper()).post(ready);
        });
    }

    private PlaybackCache(Context context) {
        this.context = context;
        File directory = new File(context.getCacheDir(), "playback");
        cache = new SimpleCache(directory, new LeastRecentlyUsedCacheEvictor(new AppSettings(context).cacheBytes()), new StandaloneDatabaseProvider(context));
        for (String key : new java.util.HashSet<>(cache.getKeys())) {
            if (!key.startsWith(CACHE_PREFIX)) try { cache.removeResource(key); } catch (Exception ignored) {}
        }
    }

    static String key(String trackId, String quality) { return CACHE_PREFIX + Integer.toHexString(ApiClient.ORIGIN.hashCode()) + ":" + trackId + ":" + quality; }

    DataSource.Factory streamingFactory(String cookie) {
        DefaultHttpDataSource.Factory upstream = new DefaultHttpDataSource.Factory()
                .setUserAgent("FamilyMusic-Android/1.0.10")
                .setTransferListener(new PlaybackQualityTracker())
                .setDefaultRequestProperties(cookie.isEmpty() ? Collections.emptyMap() : Collections.singletonMap("Cookie", cookie));
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context, upstream))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    CacheWriter prefetchWriter(MediaItem item, String cookie, long bytes) {
        MediaItem.LocalConfiguration local = item.localConfiguration;
        if (local == null || !"http".equals(local.uri.getScheme()) && !"https".equals(local.uri.getScheme())) return null;
        CacheDataSource source = (CacheDataSource) streamingFactory(cookie).createDataSource();
        DataSpec request = new DataSpec.Builder()
                .setUri(local.uri)
                .setKey(local.customCacheKey)
                .setPosition(0)
                .setLength(bytes)
                .build();
        return new CacheWriter(source, request, null, null);
    }

    CacheWriter completeWriter(MediaItem item, String cookie, long maxBytes) {
        MediaItem.LocalConfiguration local = item.localConfiguration;
        if (local == null || local.customCacheKey == null) return null;
        if (!"http".equals(local.uri.getScheme()) && !"https".equals(local.uri.getScheme())) return null;
        String key = local.customCacheKey;
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
        if (length <= 0 || length > maxBytes || cache.getCachedBytes(key, 0, length) >= length) return null;
        CacheDataSource source = (CacheDataSource) streamingFactory(cookie).createDataSource();
        DataSpec request = new DataSpec.Builder().setUri(local.uri).setKey(key).setPosition(0).setLength(length).build();
        return new CacheWriter(source, request, null, null);
    }

    long sizeBytes() { return cache.getCacheSpace(); }

    void remove(MediaItem item) {
        MediaItem.LocalConfiguration local = item == null ? null : item.localConfiguration;
        String key = local == null ? null : local.customCacheKey;
        if (key == null || !key.startsWith(CACHE_PREFIX)) return;
        try { cache.removeResource(key); } catch (Exception ignored) {}
    }

    synchronized void clear() {
        for (String key : new java.util.HashSet<>(cache.getKeys())) {
            try { cache.removeResource(key); } catch (Exception ignored) {}
        }
    }

    void copyCompleteTrack(String trackId, String quality, File temporary) throws IOException {
        DataSource source = new CacheDataSource.Factory().setCache(cache).createDataSource();
        DataSpec request = new DataSpec.Builder()
                .setUri(Uri.parse(ApiClient.BASE + "/tracks/" + trackId + "/stream?quality=" + Uri.encode(quality)))
                .setKey(key(trackId, quality)).build();
        long length = source.open(request);
        if (length == C.LENGTH_UNSET || cache.getCachedBytes(key(trackId, quality), 0, length) < length) {
            source.close();
            throw new IOException("Трек ещё не полностью получен");
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[128 * 1024]; int read;
            while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) output.write(buffer, 0, read);
            output.getFD().sync();
        } finally { source.close(); }
    }
}
