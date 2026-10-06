package org.familymusic.client;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class PlaybackQueuePolicyTest {
    @Test public void existingTrackReusesCurrentQueueFromTheSameSection() {
        assertEquals(PlaybackQueuePolicy.SelectionAction.REUSE,
                PlaybackQueuePolicy.selectionAction(Arrays.asList("one", "two"), "two", "Все треки", "Все треки"));
    }

    @Test public void existingTrackRebuildsQueueAfterSwitchingToLikes() {
        assertEquals(PlaybackQueuePolicy.SelectionAction.REBUILD,
                PlaybackQueuePolicy.selectionAction(Arrays.asList("one", "two"), "two", "Все треки", "Мне нравится"));
    }

    @Test public void newlyLoadedTrackRebuildsWholeQueue() {
        assertEquals(PlaybackQueuePolicy.SelectionAction.REBUILD,
                PlaybackQueuePolicy.selectionAction(Arrays.asList("one", "two"), "three", "Все треки", "Все треки"));
    }
}
