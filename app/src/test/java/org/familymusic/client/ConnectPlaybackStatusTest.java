package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ConnectPlaybackStatusTest {
    @Test public void bufferingKeepsIntentWithoutClaimingAudiblePlayback() {
        assertEquals("buffering",ConnectPlaybackStatus.status(true,false,true,false));
        assertEquals("playing",ConnectPlaybackStatus.status(true,true,false,false));
    }
    @Test public void focusWaitingPauseAndPreparationAreDistinct() {
        assertEquals("waiting",ConnectPlaybackStatus.status(true,false,false,false));
        assertEquals("paused",ConnectPlaybackStatus.status(false,false,false,false));
        assertEquals("preparing",ConnectPlaybackStatus.status(false,false,true,true));
    }
}
