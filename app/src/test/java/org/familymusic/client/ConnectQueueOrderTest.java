package org.familymusic.client;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ConnectQueueOrderTest {
    private static Track track(String id) throws Exception {
        return new Track(new JSONObject().put("id", id).put("title", id));
    }

    @Test
    public void restoresAuthoritativeConnectOrder() throws Exception {
        List<Track> ordered = ConnectQueueOrder.restore(
                Arrays.asList("a", "b", "c"),
                Arrays.asList(track("c"), track("a"), track("b")));

        assertEquals(Arrays.asList("a", "b", "c"),
                Arrays.asList(ordered.get(0).id, ordered.get(1).id, ordered.get(2).id));
    }

    @Test
    public void ignoresMissingAndDuplicateIds() throws Exception {
        List<Track> ordered = ConnectQueueOrder.restore(
                Arrays.asList("a", "missing", "a", "b"),
                Arrays.asList(track("b"), track("a"), track("a")));

        assertEquals(2, ordered.size());
        assertEquals("a", ordered.get(0).id);
        assertEquals("b", ordered.get(1).id);
    }
}
