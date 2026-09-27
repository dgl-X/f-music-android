package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerSeekGuardTest {
    @Test public void periodicUpdatesAreBlockedForTheWholeGesture() {
        PlayerSeekGuard guard = new PlayerSeekGuard();
        assertTrue(guard.allowsPeriodicUpdate());
        guard.start(12_000);
        assertFalse(guard.allowsPeriodicUpdate());
        PlayerSeekGuard.Result result = guard.finish(48_500);
        assertTrue(guard.allowsPeriodicUpdate());
        assertEquals(12_000, result.fromMs);
        assertEquals(48_500, result.toMs);
    }

    @Test public void cancelledGestureRestoresPeriodicUpdates() {
        PlayerSeekGuard guard = new PlayerSeekGuard();
        guard.start(1_000);
        guard.cancel();
        assertTrue(guard.allowsPeriodicUpdate());
    }
}
