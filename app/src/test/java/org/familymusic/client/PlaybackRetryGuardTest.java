package org.familymusic.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackRetryGuardTest {
    @Test public void staleRetryCannotTouchNextTrack() {
        PlaybackRetryGuard guard = new PlaybackRetryGuard();
        guard.transition("first");
        PlaybackRetryGuard.Decision retry = guard.onError("first");
        guard.transition("second");
        assertFalse(guard.isCurrent(retry.token, "second"));
        assertFalse(guard.isCurrent(retry.token, "first"));
    }

    @Test public void fourthFailureSkipsInsteadOfRetryingForever() {
        PlaybackRetryGuard guard = new PlaybackRetryGuard();
        guard.transition("track");
        assertTrue(guard.onError("track").retry);
        assertTrue(guard.onError("track").retry);
        assertTrue(guard.onError("track").retry);
        assertFalse(guard.onError("track").retry);
    }

    @Test public void successfulRecoveryStartsFreshBudget() {
        PlaybackRetryGuard guard = new PlaybackRetryGuard();
        guard.transition("track");
        PlaybackRetryGuard.Decision stale = guard.onError("track");
        guard.recovered();
        assertFalse(guard.isCurrent(stale.token, "track"));
        assertTrue(guard.onError("track").retry);
    }
}
