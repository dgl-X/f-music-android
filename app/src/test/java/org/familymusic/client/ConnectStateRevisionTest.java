package org.familymusic.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConnectStateRevisionTest {
    @Test
    public void acceptsOnlyNewConnectSnapshots() {
        ConnectStateRevision revisions = new ConnectStateRevision();

        assertTrue(revisions.accept(8));
        assertFalse(revisions.accept(8));
        assertFalse(revisions.accept(7));
        assertTrue(revisions.accept(9));
    }
}
