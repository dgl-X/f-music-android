package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class QualitySelectorTest {
    @Test public void originalNeverRequiresDerivative() {
        assertEquals("original",QualitySelector.playback(true,true,"original"));
    }

    @Test public void compactUsesAac96OnlyWhenReady() {
        assertEquals("aac_96",QualitySelector.playback(false,true,"compact"));
        assertEquals("original",QualitySelector.playback(true,false,"compact"));
    }

    @Test public void highAndAutoUseAac192OnlyWhenReady() {
        assertEquals("aac_192",QualitySelector.playback(true,false,"high"));
        assertEquals("aac_192",QualitySelector.playback(true,false,"auto"));
        assertEquals("original",QualitySelector.playback(false,true,"high"));
    }
}
