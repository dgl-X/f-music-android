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
import androidx.media3.datasource.cache.ContentMetadataMutations;
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
    private final MediaPlaybackResolver resolver;

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
        resolver = new MediaPlaybackResolver(context);
        File directory = new File(context.getCacheDir(), "playback");
        cache = new SimpleCache(directory, new LeastRecentlyUsedCacheEvictor(new AppSettings(context).cacheBytes()), new StandaloneDatabaseProvider(context));
        for (String key : new java.util.HashSet<>(cache.getKeys())) {
            if (!key.startsWith(CACHE_PREFIX)) try { cache.removeResource(key); } catch (Exception ignored) {}
        }
    }

    static String key(String trackId, String quality) { return CACHE_PREFIX + Integer.toHexString(ApiClient.ORIGIN.hashCode()) + ":" + trackId + ":" + quality; }

    DataSource.Factory streamingFactory(String cookie) {
        CacheDataSource.Factory factory=cacheFactory(cookie);
        return () -> new DiagnosticDataSource(new MediaPlaybackDataSource(factory.createDataSource(),this,cookie),context);
    }

    private boolean completeKey(String key) {
        if(key==null)return false;
        long length=ContentMetadata.getContentLength(cache.getContentMetadata(key));
        return length>0&&cache.getCachedBytes(key,0,length)>=length;
    }

    String resolvedKey(String key) {
        if(key==null)return null;
        MediaSelection saved=resolver.saved(key);
        return saved==null?key:saved.key;
    }

    MediaSelection resolve(Uri uri,String key,String cookie,boolean wait) throws IOException {
        if(key==null)return null;
        MediaSelection saved=resolver.saved(key);
        if(saved!=null&&completeKey(saved.key)&&(!resolver.nativeRejected(key)||saved.variant.startsWith("aac_")))return saved;
        // Fully cached pre-upgrade bytes remain playable without the network.
        if(saved==null&&completeKey(key)&&!resolver.nativeRejected(key))return null;
        MediaSelection selection=resolver.resolve(uri,key,cookie,wait);
        if(selection!=null&&selection.size>0){
            ContentMetadataMutations mutations=new ContentMetadataMutations();
            ContentMetadataMutations.setContentLength(mutations,selection.size);
            cache.applyContentMetadataMutations(selection.key,mutations);
        }
        return selection;
    }

    boolean rejectNative(MediaItem item) {
        MediaItem.LocalConfiguration local=item==null?null:item.localConfiguration;
        if(local==null||local.customCacheKey==null||!"local".equals(DiagnosticDataSource.kind(local.uri)))return false;
        MediaSelection selected=resolver.saved(local.customCacheKey);
        if(selected!=null&&selected.variant.startsWith("aac_"))return false;
        return resolver.rejectNative(local.customCacheKey);
    }

    private DataSpec request(MediaItem.LocalConfiguration local,String cookie,long bytes) throws IOException {
        MediaSelection selection=resolve(local.uri,local.customCacheKey,cookie,false);
        DataSpec.Builder builder=new DataSpec.Builder().setUri(selection==null?local.uri:Uri.parse(selection.url))
                .setKey(selection==null?local.customCacheKey:selection.key).setPosition(0);
        long length=selection==null?bytes:selection.remaining(0,bytes);
        if(length>0)builder.setLength(length);
        return builder.build();
    }

    private CacheDataSource.Factory cacheFactory(String cookie) {
        DefaultHttpDataSource.Factory upstream = new DefaultHttpDataSource.Factory()
                .setUserAgent("FamilyMusic-Android/"+BuildConfig.VERSION_NAME)
                .setTransferListener(new PlaybackQualityTracker(context))
                .setDefaultRequestProperties(cookie.isEmpty() ? Collections.emptyMap() : Collections.singletonMap("Cookie", cookie));
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context, upstream))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    CacheWriter prefetchWriter(MediaItem item, String cookie, long bytes) {
        MediaItem.LocalConfiguration local = item.localConfiguration;
        if (local == null || !"http".equals(local.uri.getScheme()) && !"https".equals(local.uri.getScheme())) return null;
        CacheDataSource source = cacheFactory(cookie).createDataSource();
        DiagnosticLog.add(context,"prefetch request bytes="+bytes+" cache_length="+ContentMetadata.getContentLength(cache.getContentMetadata(local.customCacheKey==null?"":local.customCacheKey)));
        try { return new CacheWriter(source, request(local,cookie,bytes), null, null); }
        catch(IOException error){return null;}
    }

    CacheWriter completeWriter(MediaItem item, String cookie, long maxBytes) {
        MediaItem.LocalConfiguration local = item.localConfiguration;
        if (local == null || local.customCacheKey == null) return null;
        if (!"http".equals(local.uri.getScheme()) && !"https".equals(local.uri.getScheme())) return null;
        String key = resolvedKey(local.customCacheKey);
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
        if (length <= 0 || length > maxBytes || cache.getCachedBytes(key, 0, length) >= length) return null;
        CacheDataSource source = cacheFactory(cookie).createDataSource();
        DiagnosticLog.add(context,"cache completion request length="+length);
        try { return new CacheWriter(source, request(local,cookie,length), null, null); }
        catch(IOException error){return null;}
    }

    long sizeBytes() { return cache.getCacheSpace(); }
    org.json.JSONObject diagnostics(MediaItem item) {
        org.json.JSONObject result=new org.json.JSONObject();
        String key=item==null||item.localConfiguration==null?null:resolvedKey(item.localConfiguration.customCacheKey);
        try {
            long length=key==null?-1:ContentMetadata.getContentLength(cache.getContentMetadata(key));
            result.put("content_length",length).put("cached_bytes",key==null?0:cache.getCachedBytes(key,0,length>0?length:Long.MAX_VALUE))
                    .put("fully_cached",isFullyAvailable(item)).put("variant",key==null?"":PlaybackQualityTracker.variant(key));
        } catch(Exception ignored) {}
        return result;
    }

    boolean isFullyAvailable(MediaItem item) {
        MediaItem.LocalConfiguration local = item == null ? null : item.localConfiguration;
        if (local == null) return false;
        String scheme = local.uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
        String key = resolvedKey(local.customCacheKey);
        if (key == null || !key.startsWith(CACHE_PREFIX)) return false;
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
        return length > 0 && cache.getCachedBytes(key, 0, length) >= length;
    }

    boolean isFullyAvailable(String trackId, String quality) {
        String key = resolvedKey(key(trackId, quality));
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
        return length > 0 && cache.getCachedBytes(key, 0, length) >= length;
    }

    java.util.Set<String> cachedKeys() { return new java.util.HashSet<>(cache.getKeys()); }

    int completeTrackCount() {
        int count = 0;
        for (String key : new java.util.HashSet<>(cache.getKeys())) {
            if (!key.startsWith(CACHE_PREFIX)) continue;
            long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
            if (length > 0 && cache.getCachedBytes(key, 0, length) >= length) count++;
        }
        return count;
    }

    void remove(MediaItem item) {
        MediaItem.LocalConfiguration local = item == null ? null : item.localConfiguration;
        String key = local == null ? null : resolvedKey(local.customCacheKey);
        if (key == null || !key.startsWith(CACHE_PREFIX)) return;
        try { cache.removeResource(key); } catch (Exception ignored) {}
    }

    synchronized void clear() {
        for (String key : new java.util.HashSet<>(cache.getKeys())) {
            try { cache.removeResource(key); } catch (Exception ignored) {}
        }
        resolver.clear();
    }

    void copyCompleteTrack(String trackId, String quality, File temporary) throws IOException {
        DataSource source = new CacheDataSource.Factory().setCache(cache).createDataSource();
        String key=resolvedKey(key(trackId,quality));
        DataSpec request = new DataSpec.Builder()
                .setUri(Uri.parse(ApiClient.BASE + "/tracks/" + trackId + "/stream?quality=" + Uri.encode(quality)))
                .setKey(key).build();
        long length = source.open(request);
        if (length == C.LENGTH_UNSET || cache.getCachedBytes(key, 0, length) < length) {
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
