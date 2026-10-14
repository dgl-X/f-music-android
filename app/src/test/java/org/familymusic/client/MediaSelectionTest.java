package org.familymusic.client;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class MediaSelectionTest {
    private JSONObject json(String path)throws Exception {
        return new JSONObject().put("variant","aac_192").put("stream_url",path).put("size_bytes",1000);
    }
    @Test public void distinctBytesHaveDistinctIdentitiesAndKnownRemainingLength()throws Exception {
        MediaSelection aac=MediaSelection.parse(json("/api/v1/tracks/track/stream?variant=aac_192"),"https://music.test","track","key");
        assertEquals("key:media:aac_192",aac.key);
        assertEquals(1000,aac.remaining(0,-1));assertEquals(300,aac.remaining(700,-1));assertEquals(100,aac.remaining(700,100));
        assertEquals(300,aac.remaining(700,900));assertEquals(0,aac.remaining(1000,-1));
        assertThrows(java.io.IOException.class,()->aac.remaining(1001,-1));
        MediaSelection flac=MediaSelection.parse(json("/api/v1/tracks/track/stream?variant=normalized_flac").put("variant","normalized_flac"),"https://music.test","track","key");
        assertNotEquals(aac.key,flac.key);
    }
    @Test public void selectedSourceCannotEscapeSameServerTrackOrVariant()throws Exception {
        for(String path:new String[]{"https://other.test/api/v1/tracks/track/stream?variant=aac_192","//other.test/api/v1/tracks/track/stream?variant=aac_192","/api/v1/tracks/another/stream?variant=aac_192","/api/v1/tracks/track/stream?variant=original","/api/v1/tracks/track/stream?variant=aac_192#x"}){
            assertThrows(path,java.io.IOException.class,()->MediaSelection.parse(json(path),"https://music.test","track","key"));
        }
    }
}
