package org.familymusic.client;

import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;

/** Immutable identity shared by playback, prefetch and offline copying. */
final class MediaSelection {
    final String url, variant, key;
    final long size;
    MediaSelection(String url, String variant, String key, long size) {
        this.url=url;this.variant=variant;this.key=key;this.size=size;
    }
    static MediaSelection parse(JSONObject json, String origin, String track, String logicalKey) throws IOException {
        String variant=json.optString("variant"),path=json.optString("stream_url");
        if (!variant.matches("original|normalized_flac|aac_96|aac_192")) throw new IOException("Неизвестный вариант аудио");
        try {
            URI uri=new URI(path);
            if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getFragment()!=null
                    ||!uri.getPath().equals("/api/v1/tracks/"+track+"/stream")
                    ||uri.getRawQuery()==null||!java.util.Arrays.asList(uri.getRawQuery().split("&")).contains("variant="+variant))
                throw new IOException("Некорректный источник аудио");
            long size=json.optLong("size_bytes",-1);
            return new MediaSelection(origin+path,variant,logicalKey+":media:"+variant,size>0?size:-1);
        } catch(java.net.URISyntaxException error) { throw new IOException("Некорректный источник аудио",error); }
    }
    long remaining(long position, long requested) throws IOException {
        if(size<=0)return requested;
        if(position<0||position>size)throw new IOException("Позиция вне аудиофайла");
        return requested<0?size-position:Math.min(requested,size-position);
    }
}
