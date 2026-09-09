package org.familymusic.client;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PlaybackQualityTracker implements TransferListener {
    private static final Map<String, String> variants = new ConcurrentHashMap<>();
    static String variant(String cacheKey) { return variants.getOrDefault(cacheKey, ""); }
    @Override public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean network) {}
    @Override public void onTransferStart(DataSource source, DataSpec dataSpec, boolean network) {
        if (!(source instanceof HttpDataSource) || dataSpec.key == null) return;
        for (Map.Entry<String, List<String>> header : ((HttpDataSource) source).getResponseHeaders().entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase("X-Music-Variant") && !header.getValue().isEmpty()) variants.put(dataSpec.key, header.getValue().get(0));
        }
    }
    @Override public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean network, int bytes) {}
    @Override public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean network) {}
}
