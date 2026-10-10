package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;

final class SmartCacheStats {
    private static final String PREFS = "smart_cache_stats";
    private final SharedPreferences values;

    SmartCacheStats(Context context) {
        values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void recordMeaningfulListen(boolean alreadyCached) {
        SharedPreferences.Editor edit = values.edit()
                .putLong("meaningful_listens", meaningfulListens() + 1)
                .putLong("last_listen_at", System.currentTimeMillis());
        if (alreadyCached) edit.putLong("cache_hits", cacheHits() + 1);
        edit.apply();
    }

    long meaningfulListens() { return values.getLong("meaningful_listens", 0); }
    long cacheHits() { return values.getLong("cache_hits", 0); }
    long offlineSkips() { return values.getLong("offline_skips", 0); }
    long offlineStops() { return values.getLong("offline_stops", 0); }

    synchronized void recordOfflineSkip() {
        values.edit().putLong("offline_skips", offlineSkips() + 1).apply();
    }

    synchronized void recordOfflineStop() {
        values.edit().putLong("offline_stops", offlineStops() + 1).apply();
    }

    int hitPercent() {
        long listens = meaningfulListens();
        return listens <= 0 ? 0 : (int) Math.min(100, cacheHits() * 100 / listens);
    }
}
