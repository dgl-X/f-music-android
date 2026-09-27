package org.familymusic.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NetworkLogGuardTest {
    @Test public void repeatedCapabilitiesAreSuppressed() {
        NetworkLogGuard guard = new NetworkLogGuard();
        String wifi = NetworkLogGuard.describe(true, false, false, false, true);
        assertTrue(guard.update("42", wifi));
        assertFalse(guard.update("42", wifi));
        assertTrue(guard.update("42", NetworkLogGuard.describe(true, false, false, false, false)));
    }

    @Test public void networkReplacementAndOnlyActiveLossAreLogged() {
        NetworkLogGuard guard = new NetworkLogGuard();
        String state = NetworkLogGuard.describe(false, true, false, false, true);
        assertTrue(guard.update("mobile-a", state));
        assertTrue(guard.update("mobile-b", state));
        assertFalse(guard.lost("mobile-a"));
        assertTrue(guard.lost("mobile-b"));
        assertFalse(guard.lost("mobile-b"));
    }

    @Test public void vpnRemainsVisibleAlongsidePhysicalTransport() {
        assertEquals("transport=wifi+vpn validated=true vpn=true", NetworkLogGuard.describe(true, false, false, true, true));
    }
}
