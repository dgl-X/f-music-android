package org.familymusic.client;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class PlaybackQueuePolicyTest {
    @Test public void existingTrackReusesCurrentQueue() {
        assertEquals(PlaybackQueuePolicy.SelectionAction.REUSE,
                PlaybackQueuePolicy.selectionAction(Arrays.asList("one", "two"), "two"));
    }

    @Test public void newlyLoadedTrackRebuildsWholeQueue() {
        assertEquals(PlaybackQueuePolicy.SelectionAction.REBUILD,
                PlaybackQueuePolicy.selectionAction(Arrays.asList("one", "two"), "three"));
    }
}
