package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SmartCacheCatalog {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smart-cache-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private final SharedPreferences preferences;
    private final CountDownLatch loaded = new CountDownLatch(1);
    private JSONObject items = new JSONObject();
    private volatile boolean metadataLoaded;

    SmartCacheCatalog(Context context) {
        preferences = context.getSharedPreferences("smart_cache_catalog", Context.MODE_PRIVATE);
        WRITER.execute(() -> {
            items = readMetadata();
            metadataLoaded = true;
            loaded.countDown();
        });
    }

    void remember(Track track, String quality) {
        WRITER.execute(() -> rememberNow(track, quality, true));
    }

    void rememberAll(List<Track> tracks, String requestedQuality) {
        if (tracks == null || tracks.isEmpty()) return;
        List<Track> snapshot = new ArrayList<>(tracks);
        WRITER.execute(() -> {
            synchronized (SmartCacheCatalog.this) {
                for (Track track : snapshot) rememberNow(track,
                        track.cachedQuality.isEmpty()
                                ? QualitySelector.playback(track.aac192Ready, track.aac96Ready, requestedQuality)
                                : track.cachedQuality, false);
                trim(items, 5000);
                persist();
            }
        });
    }

    private synchronized void rememberNow(Track track, String quality, boolean persist) {
        if (track == null || track.id.isEmpty() || quality == null || quality.isEmpty()) return;
        try {
            items.put(track.id + ":" + quality, new JSONObject()
                    .put("track", track.toJson())
                    .put("quality", quality)
                    .put("seen_at", System.currentTimeMillis()));
            if (persist) { trim(items, 5000); persist(); }
        } catch (Exception ignored) {}
    }

    // Before the background read completes, optimistically expose the offline
    // library. This keeps startup non-blocking; available() performs the exact
    // check on its worker before displaying any tracks.
    synchronized boolean hasEntries() { return !metadataLoaded || items.length() > 0; }

    int lastAvailableCount() { return preferences.getInt("available_count", -1); }

    synchronized void clear() {
        awaitLoaded();
        items = new JSONObject();
        preferences.edit().remove("items").remove("available_count").apply();
    }

    synchronized List<Track> available(PlaybackCache cache) {
        awaitLoaded();
        Map<String, Track> unique = new LinkedHashMap<>();
        JSONArray ids = items.names();
        if (ids == null) { preferences.edit().putInt("available_count", 0).apply(); return new ArrayList<>(); }
        boolean changed = false;
        long staleBefore = System.currentTimeMillis() - 10 * 60_000L;
        java.util.Set<String> cachedKeys = cache.cachedKeys();
        for (int index = 0; index < ids.length(); index++) {
            String entryId = ids.optString(index);
            JSONObject entry = items.optJSONObject(entryId);
            JSONObject data = entry == null ? null : entry.optJSONObject("track");
            if (data == null) { items.remove(entryId); changed = true; continue; }
            Track track = new Track(data);
            String quality = entry.optString("quality", "original");
            if (cache.isFullyAvailable(track.id, quality) && !unique.containsKey(track.id)) {
                track.cachedQuality = quality;
                unique.put(track.id, track);
            }
            else if (!cachedKeys.contains(PlaybackCache.key(track.id, quality)) && entry.optLong("seen_at", 0) < staleBefore) {
                items.remove(entryId);
                changed = true;
            }
        }
        if (changed) persist();
        List<Track> result = new ArrayList<>(unique.values());
        result.sort((left, right) -> left.artist.compareToIgnoreCase(right.artist));
        preferences.edit().putInt("available_count", result.size()).apply();
        return result;
    }

    private JSONObject readMetadata() {
        try { return new JSONObject(preferences.getString("items", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private void awaitLoaded() {
        if (metadataLoaded) return;
        boolean interrupted = false;
        while (!metadataLoaded) {
            try { loaded.await(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void persist() { preferences.edit().putString("items", items.toString()).apply(); }

    private static void trim(JSONObject items, int limit) {
        int remove = items.length() - limit;
        if (remove <= 0) return;
        JSONArray names = items.names();
        if (names == null) return;
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < names.length(); index++) ids.add(names.optString(index));
        ids.sort((left, right) -> Long.compare(
                items.optJSONObject(left) == null ? 0 : items.optJSONObject(left).optLong("seen_at", 0),
                items.optJSONObject(right) == null ? 0 : items.optJSONObject(right).optLong("seen_at", 0)));
        for (int index = 0; index < remove && index < ids.size(); index++) items.remove(ids.get(index));
    }
}
