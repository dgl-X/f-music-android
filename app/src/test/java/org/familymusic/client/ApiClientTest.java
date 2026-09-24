package org.familymusic.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ApiClientTest {
    @Test public void onlyUnauthorizedResponseEndsLocalSession() {
        assertTrue(ApiClient.isAuthenticationFailure(401));
        assertFalse(ApiClient.isAuthenticationFailure(0));
        assertFalse(ApiClient.isAuthenticationFailure(408));
        assertFalse(ApiClient.isAuthenticationFailure(500));
    }

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
