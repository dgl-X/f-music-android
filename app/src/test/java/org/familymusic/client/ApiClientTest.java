package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class ApiClientTest {
    @Test public void normalizesHostAndDefaultScheme() throws Exception {
        assertEquals("https://music.example.com",ApiClient.normalizeOrigin("  MUSIC.Example.Com/ "));
    }

    @Test public void rejectsInsecureOrExpandedAddresses() {
        assertThrows(Exception.class,()->ApiClient.normalizeOrigin("http://music.example.com"));
        assertThrows(Exception.class,()->ApiClient.normalizeOrigin("https://user:pass@music.example.com"));
        assertThrows(Exception.class,()->ApiClient.normalizeOrigin("https://music.example.com/api"));
        assertThrows(Exception.class,()->ApiClient.normalizeOrigin("https://music.example.com:8443"));
        assertThrows(Exception.class,()->ApiClient.normalizeOrigin("https://music.example.com?q=secret"));
    }
}
