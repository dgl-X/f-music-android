package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteSeekGuardTest {
    @Test public void oldServerPositionCannotPullTheSliderBackAfterSeek() {
        RemoteSeekGuard guard = new RemoteSeekGuard();
        guard.start(60_000, "track-1", 4, 1_000);
        assertEquals(60_500, guard.position(12_000, 180_000, true, "track-1", 4, 1_500));
        guard.observe(12_000, true, "track-1", 4, 1_500);
        assertTrue(guard.active());
    }

    @Test public void confirmedPositionReturnsControlToServerState() {
        RemoteSeekGuard guard = new RemoteSeekGuard();
        guard.start(60_000, "track-1", 4, 1_000);
        guard.observe(61_000, true, "track-1", 4, 2_000);
        assertFalse(guard.active());
        assertEquals(61_000, guard.position(61_000, 180_000, true, "track-1", 4, 2_000));
    }

    @Test public void trackChangeOrTimeoutCancelsOptimisticSeek() {
        RemoteSeekGuard guard = new RemoteSeekGuard();
        guard.start(60_000, "track-1", 4, 1_000);
        assertEquals(5_000, guard.position(5_000, 180_000, true, "track-2", 4, 1_100));
        guard.start(60_000, "track-1", 4, 1_000);
        assertEquals(9_000, guard.position(9_000, 180_000, true, "track-1", 4, 10_000));
    }
}
