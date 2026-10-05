package org.familymusic.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConnectTransferPolicyTest {
    @Test
    public void returningPlaybackToThisPhoneUsesActiveRemoteSnapshot() {
        assertTrue(ConnectTransferPolicy.useRemoteSnapshot("phone", "phone", true));
        assertFalse(ConnectTransferPolicy.useRemoteSnapshot("web", "phone", true));
        assertFalse(ConnectTransferPolicy.useRemoteSnapshot("phone", "phone", false));
    }
}
