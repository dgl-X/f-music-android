package org.familymusic.client;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class TrackTest {
    @Test public void preservesPlaybackMetadataAcrossQueueSerialization() throws Exception {
        Track source=new Track(new JSONObject().put("id","track-1").put("title","Песня").put("artist","Исполнитель").put("album","Альбом").put("genre","Rock").put("year",2024).put("cover_url","/cover").put("filename","song.flac").put("mime_type","audio/flac").put("liked",true).put("aac_192_ready",true).put("duration_seconds",245.75).put("replay_gain_db",-4.5));
        Track restored=new Track(source.toJson());
        assertEquals(source.id,restored.id);assertEquals(source.title,restored.title);assertEquals(source.artist,restored.artist);assertEquals(source.album,restored.album);assertEquals(source.genre,restored.genre);assertEquals(source.year,restored.year);assertEquals(source.coverUrl,restored.coverUrl);assertEquals(source.filename,restored.filename);assertEquals(source.mimeType,restored.mimeType);assertTrue(restored.liked);assertTrue(restored.aac192Ready);assertEquals(245.75,restored.durationSeconds,0.001);assertEquals(-4.5,restored.replayGainDb,0.001);
    }

    @Test public void optionalValuesRemainAbsent() throws Exception {
        Track track=new Track(new JSONObject().put("id","track-2"));
        Track restored=new Track(track.toJson());
        assertNull(restored.year);assertNull(restored.replayGainDb);assertEquals(0,restored.durationSeconds,0.0);
    }

    @Test public void preservesFederatedTrackReferenceAndStream() throws Exception {
        Track track=new Track(new JSONObject().put("id","remote:opaque").put("title","Удалённая")
                .put("remote",true).put("remote_ref","opaque").put("stream_url","/api/v1/federation/stream?ref=opaque&quality=original")
                .put("source_label","Семейный сервер").put("availability","online").put("stream_available",true));
        Track restored=new Track(track.toJson());
        assertTrue(restored.remote);assertEquals("opaque",restored.remoteRef);assertEquals("Семейный сервер",restored.sourceLabel);
        assertEquals("/api/v1/federation/stream?ref=opaque&quality=original",restored.streamPath("lossless"));assertTrue(restored.streamAvailable);
    }
}
