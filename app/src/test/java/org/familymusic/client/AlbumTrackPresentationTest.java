package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AlbumTrackPresentationTest {
    @Test public void usesMetadataTrackNumberAndFallsBackToPosition() {
        assertEquals("7", AlbumTrackPresentation.number(7, 2));
        assertEquals("2", AlbumTrackPresentation.number(0, 2));
    }

    @Test public void startsEachDiscOnlyForMultiDiscAlbums() {
        assertFalse(AlbumTrackPresentation.startsDisc(1, 0, false));
        assertTrue(AlbumTrackPresentation.startsDisc(1, 0, true));
        assertFalse(AlbumTrackPresentation.startsDisc(1, 1, true));
        assertTrue(AlbumTrackPresentation.startsDisc(2, 1, true));
        assertEquals("ДИСК 2", AlbumTrackPresentation.discTitle(2));
    }
}
