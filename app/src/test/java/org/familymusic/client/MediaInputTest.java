package org.familymusic.client;

import static org.junit.Assert.*;
import android.app.Application;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.*;
import androidx.media3.extractor.flac.FlacExtractor;
import androidx.media3.common.DataReader;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.cache.*;
import androidx.media3.database.StandaloneDatabaseProvider;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class MediaInputTest {
    // Generated 3-second mono silence; valid FLAC with STREAMINFO, no SEEKTABLE.
    private static final byte[] FLAC=java.util.Base64.getDecoder().decode("ZkxhQ4AAACICQAJAAAALAAANAfQA8AAAXcB4PuPhuJFsgnNExHexQQOH//gkCADKAAAAznb/+CQIAc0AAACiDv/4JAgCxAAAABaG//gkCAPDAAAAev7/+CQIBNYAAAD/k//4JAgF0QAAAJPr//gkCAbYAAAAJ2P/+CQIB98AAABLG//4JAgI8gAAAK28//gkCAn1AAAAwcT/+CQICvwAAAB1TP/4JAgL+wAAABk0//gkCAzuAAAAnFn/+CQIDekAAADwIf/4JAgO4AAAAESp//gkCA/nAAAAKNH/+CQIELoAAAAJ4v/4JAgRvQAAAGWa//gkCBK0AAAA0RL/+CQIE7MAAAC9av/4JAgUpgAAADgH//gkCBWhAAAAVH//+CQIFqgAAADg9//4JAgXrwAAAIyP//gkCBiCAAAAaij/+CQIGYUAAAAGUP/4JAgajAAAALLY//gkCBuLAAAA3qD/+CQIHJ4AAABbzf/4JAgdmQAAADe1//gkCB6QAAAAgz3/+CQIH5cAAADvRf/4JAggKgAAAMFb//gkCCEtAAAArSP/+CQIIiQAAAAZq//4JAgjIwAAAHXT//gkCCQ2AAAA8L7/+CQIJTEAAACcxv/4JAgmOAAAAChO//gkCCc/AAAARDb/+CQIKBIAAACikf/4dAgpAX+DAAAAEHw=");
    private SeekMap extract(long length)throws Exception {
        final SeekMap[] result={null};
        FlacExtractor extractor=new FlacExtractor();
        extractor.init(new ExtractorOutput(){
            public TrackOutput track(int id,int type){return new DummyTrackOutput();}
            public void endTracks(){}
            public void seekMap(SeekMap map){result[0]=map;}
        });
        final int[] offset={0};
        DataReader reader=(buffer,start,count)->{if(offset[0]>=FLAC.length)return C.RESULT_END_OF_INPUT;int n=Math.min(count,FLAC.length-offset[0]);System.arraycopy(FLAC,offset[0],buffer,start,n);offset[0]+=n;return n;};
        DefaultExtractorInput input=new DefaultExtractorInput(reader,0,length);
        PositionHolder seek=new PositionHolder();
        for(int i=0;i<20&&result[0]==null;i++)assertNotEquals(Extractor.RESULT_END_OF_INPUT,extractor.read(input,seek));
        assertNotNull(result[0]);return result[0];
    }
    @Test public void flacWithoutSeekTableNeedsKnownInputLength()throws Exception {
        assertFalse(extract(C.LENGTH_UNSET).isSeekable());
        SeekMap map=extract(FLAC.length);assertTrue(map.isSeekable());assertEquals(3_000_000,map.getDurationUs());
        assertTrue(map.getSeekPoints(1_500_000).first.position>0);
    }
    @Test public void rangeSeeksKeepOneVariantAndExposeKnownInputLength()throws Exception {
        final int[] resolved={0};final DataSpec[] opened={null};
        DataSource underlying=new DataSource(){
            public void addTransferListener(TransferListener listener){}
            public long open(DataSpec spec){opened[0]=spec;return C.LENGTH_UNSET;}
            public int read(byte[] b,int o,int n){return C.RESULT_END_OF_INPUT;}
            public Uri getUri(){return opened[0].uri;}
            public void close(){}
        };
        MediaPlaybackDataSource source=new MediaPlaybackDataSource(underlying,spec->{
            resolved[0]++;
            return new MediaSelection("https://music.test/api/v1/tracks/track/stream?variant=original","original","logical:media:original",FLAC.length);
        });
        DataSpec spec=new DataSpec.Builder().setUri("https://music.test/api/v1/tracks/track/stream").setKey("logical").build();
        long length=source.open(spec);assertEquals(FLAC.length,length);assertTrue(extract(length).isSeekable());
        source.close();
        assertEquals(FLAC.length-100,source.open(spec.buildUpon().setPosition(100).build()));
        assertEquals(1,resolved[0]);assertEquals("logical:media:original",opened[0].key);
        assertEquals(FLAC.length-100,opened[0].length);assertEquals(100,opened[0].position);
        source.close();
        source.open(spec.buildUpon().setKey("other-track").build());assertEquals(2,resolved[0]);
        source.close();
    }
    @Test public void partialCacheStaysSeekableAndCompleteCacheNeedsNoNetwork()throws Exception {
        java.io.File directory=java.nio.file.Files.createTempDirectory("media-cache-test-").toFile();
        SimpleCache cache=new SimpleCache(directory,new NoOpCacheEvictor(),new StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()));
        try {
            final int[] networkOpens={0};
            CacheDataSource.Factory factory=new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(()->{networkOpens[0]++;return new ByteArrayDataSource(FLAC);});
            DataSpec spec=new DataSpec.Builder().setUri("https://music.test/file.flac").setKey("logical:media:original").build();
            new CacheWriter(factory.createDataSource(),spec.buildUpon().setLength(64).build(),null,null).cache();
            assertEquals(64,cache.getCachedBytes(spec.key,0,FLAC.length));
            MediaSelection selection=new MediaSelection(spec.uri.toString(),"original",spec.key,FLAC.length);
            MediaPlaybackDataSource source=new MediaPlaybackDataSource(factory.createDataSource(),ignored->selection);
            long length=source.open(spec);assertEquals(FLAC.length,length);assertTrue(extract(length).isSeekable());source.close();
            source.open(spec.buildUpon().setPosition(100).build());byte[] part=new byte[20];assertEquals(20,source.read(part,0,20));
            assertArrayEquals(java.util.Arrays.copyOfRange(FLAC,100,120),part);source.close();
            new CacheWriter(factory.createDataSource(),spec.buildUpon().setLength(FLAC.length).build(),null,null).cache();
            int before=networkOpens[0];
            MediaPlaybackDataSource offline=new MediaPlaybackDataSource(new CacheDataSource.Factory().setCache(cache).createDataSource(),ignored->selection);
            assertEquals(FLAC.length,offline.open(spec));
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[64];int n;
            while((n=offline.read(buffer,0,buffer.length))!=C.RESULT_END_OF_INPUT)bytes.write(buffer,0,n);
            offline.close();assertArrayEquals(FLAC,bytes.toByteArray());assertEquals(before,networkOpens[0]);
        }finally{
            cache.release();
            try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(directory.toPath())){files.sorted(java.util.Comparator.reverseOrder()).forEach(file->{try{java.nio.file.Files.delete(file);}catch(IOException ignored){}});}
        }
    }
}
