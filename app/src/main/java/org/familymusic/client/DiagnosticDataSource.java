package org.familymusic.client;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Transparent observer: records the actual length exposed by CacheDataSource. */
final class DiagnosticDataSource implements DataSource {
    private final DataSource delegate;
    private final Context context;
    private DataSpec opened;
    private long bytes;
    DiagnosticDataSource(DataSource delegate, Context context) { this.delegate=delegate;this.context=context; }
    @Override public void addTransferListener(TransferListener listener) { delegate.addTransferListener(listener); }
    @Override public long open(DataSpec spec) throws IOException {
        opened=spec;bytes=0;
        try {
            long length=delegate.open(spec);
            DiagnosticLog.add(context,"media open kind="+kind(spec.uri)+" position="+spec.position+" requested_length="+spec.length+" opened_length="+length);
            return length;
        } catch(IOException error) {
            DiagnosticLog.add(context,"media open failed kind="+kind(spec.uri)+" position="+spec.position+" type="+error.getClass().getSimpleName());throw error;
        }
    }
    static String kind(Uri uri) {
        if(uri==null)return "unknown";
        if("file".equals(uri.getScheme()))return "offline";
        String path=uri.getPath();return path!=null&&path.contains("/federation/")?"federation":path!=null&&path.contains("/tracks/")?"local":"other";
    }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        int read=delegate.read(buffer,offset,length);if(read>0)bytes+=read;return read;
    }
    @Override public Uri getUri() { return delegate.getUri(); }
    @Override public Map<String,List<String>> getResponseHeaders() { return delegate.getResponseHeaders(); }
    @Override public void close() throws IOException {
        try { delegate.close(); }
        finally { if(opened!=null)DiagnosticLog.add(context,"media close kind="+kind(opened.uri)+" bytes="+bytes);opened=null; }
    }
}
