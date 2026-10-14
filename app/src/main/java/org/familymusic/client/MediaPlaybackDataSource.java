package org.familymusic.client;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The same selection survives close/open Range seeks inside a media period. */
final class MediaPlaybackDataSource implements DataSource {
    private final DataSource delegate;
    interface Resolver { MediaSelection resolve(DataSpec spec)throws IOException; }
    private final Resolver resolver;
    private String logicalKey;
    private MediaSelection selection;
    private boolean resolved;
    private boolean ended;
    MediaPlaybackDataSource(DataSource delegate,PlaybackCache cache,String cookie){this(delegate,spec->cache.resolve(spec.uri,spec.key,cookie,true));}
    MediaPlaybackDataSource(DataSource delegate,Resolver resolver){this.delegate=delegate;this.resolver=resolver;}
    @Override public void addTransferListener(TransferListener listener){delegate.addTransferListener(listener);}
    @Override public long open(DataSpec spec)throws IOException{
        ended=false;
        if(!java.util.Objects.equals(logicalKey,spec.key)){logicalKey=spec.key;selection=null;resolved=false;}
        if(!resolved){selection=resolver.resolve(spec);resolved=true;}
        if(selection==null)return delegate.open(spec);
        long remaining=selection.remaining(spec.position,spec.length);
        if(remaining==0){ended=true;return 0;}
        DataSpec actual=spec.buildUpon().setUri(selection.url).setKey(selection.key).setLength(remaining).build();
        long opened=delegate.open(actual);
        // With a bounded first prefetch, CacheDataSource may otherwise expose an
        // unknown stream length. FLAC without SEEKTABLE then becomes unseekable.
        return opened==C.LENGTH_UNSET?remaining:opened;
    }
    @Override public int read(byte[] buffer,int offset,int length)throws IOException{return length==0?0:ended?C.RESULT_END_OF_INPUT:delegate.read(buffer,offset,length);}
    @Override public Uri getUri(){return delegate.getUri();}
    @Override public Map<String,List<String>> getResponseHeaders(){return delegate.getResponseHeaders();}
    @Override public void close()throws IOException{delegate.close();}
}
