package org.familymusic.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SmartCachePolicyTest {
    @Test public void completionHonorsToggleAndWifiPolicy() {
        assertFalse(SmartCachePolicy.mayCompleteTrack(false, false, true));
        assertFalse(SmartCachePolicy.mayCompleteTrack(true, true, false));
        assertTrue(SmartCachePolicy.mayCompleteTrack(true, true, true));
        assertTrue(SmartCachePolicy.mayCompleteTrack(true, false, false));
    }

    @Test public void onlineAvailableAndIneligibleTransitionsKeepSelectedTrack() {
        assertEquals(SmartCachePolicy.OfflineAction.KEEP,
                SmartCachePolicy.transitionAction(true, false, true, true));
        assertEquals(SmartCachePolicy.OfflineAction.KEEP,
                SmartCachePolicy.transitionAction(false, false, false, true));
        assertEquals(SmartCachePolicy.OfflineAction.KEEP,
                SmartCachePolicy.transitionAction(false, true, true, false));
    }

    @Test public void automaticOfflineTransitionSkipsOrPausesDeterministically() {
        assertEquals(SmartCachePolicy.OfflineAction.SKIP,
                SmartCachePolicy.transitionAction(false, false, true, true));
        assertEquals(SmartCachePolicy.OfflineAction.PAUSE,
                SmartCachePolicy.transitionAction(false, false, true, false));
    }

    @Test public void offlineSourceErrorUsesCachedContinuationOnly() {
        assertEquals(SmartCachePolicy.OfflineAction.KEEP,
                SmartCachePolicy.sourceErrorAction(true, true));
        assertEquals(SmartCachePolicy.OfflineAction.SKIP,
                SmartCachePolicy.sourceErrorAction(false, true));
        assertEquals(SmartCachePolicy.OfflineAction.PAUSE,
                SmartCachePolicy.sourceErrorAction(false, false));
    }
}
