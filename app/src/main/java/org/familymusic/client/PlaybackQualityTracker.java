package org.familymusic.client;
import android.content.Context;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PlaybackQualityTracker implements TransferListener {
    private final Context context;
    PlaybackQualityTracker(Context context){this.context=context;}
    private static final Map<String, String> variants = new ConcurrentHashMap<>();
    static String variant(String cacheKey) {
        int marker=cacheKey==null?-1:cacheKey.lastIndexOf(":media:");
        return marker<0?variants.getOrDefault(cacheKey==null?"":cacheKey, ""):cacheKey.substring(marker+7);
    }
    @Override public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean network) {}
    @Override public void onTransferStart(DataSource source, DataSpec dataSpec, boolean network) {
        if (!(source instanceof HttpDataSource) || dataSpec.key == null) return;
        String length="unknown",range="unknown",mime="unknown",variant="unknown";
        for (Map.Entry<String, List<String>> header : ((HttpDataSource) source).getResponseHeaders().entrySet()) {
            if(header.getKey()==null||header.getValue().isEmpty())continue;
            String value=header.getValue().get(0);
            if(header.getKey().equalsIgnoreCase("X-Music-Variant")&&value.matches("original|normalized_flac|aac_96|aac_192")){variant=value;variants.put(dataSpec.key,value);}
            if(header.getKey().equalsIgnoreCase("Content-Length")&&value.matches("[0-9]{1,19}"))length=value;
            if(header.getKey().equalsIgnoreCase("Content-Range")&&value.matches("bytes [0-9]{1,19}-[0-9]{1,19}/[0-9]{1,19}"))range=value;
            if(header.getKey().equalsIgnoreCase("Content-Type")){String type=value.split(";",2)[0].trim();if(type.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))mime=type;}
        }
        DiagnosticLog.add(context,"media HTTP status="+((HttpDataSource)source).getResponseCode()+" position="+dataSpec.position+" requested_length="+dataSpec.length+" content_length="+length+" range="+range+" mime="+mime+" variant="+variant);
    }
    @Override public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean network, int bytes) {}
    @Override public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean network) {}
}
