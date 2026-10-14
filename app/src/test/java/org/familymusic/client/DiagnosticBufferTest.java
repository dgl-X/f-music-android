package org.familymusic.client;

import static org.junit.Assert.*;
import org.junit.Test;

public class DiagnosticBufferTest {
    @Test public void newSessionDropsAllPreviousEvents() {
        DiagnosticBuffer buffer = new DiagnosticBuffer(10, 1024);
        buffer.add("previous session");buffer.clear();buffer.add("current session");
        assertEquals(java.util.Collections.singletonList("current session"), buffer.snapshot());assertEquals(0, buffer.dropped());
    }
    @Test public void journalIsBoundedByCountAndUtf8Bytes() {
        DiagnosticBuffer buffer = new DiagnosticBuffer(2, 20);
        buffer.add("one");buffer.add("two");buffer.add("three");assertEquals(2,buffer.snapshot().size());assertEquals(1,buffer.dropped());
        buffer.add("яяяяяяяяяяяяяяяяяяяя");assertTrue(buffer.bytes()<=20);assertTrue(buffer.snapshot().size()<=2);
    }
    @Test public void urlsAndCredentialsCannotEnterTheJournal() {
        // Synthetic markers only; the fixture contains no private key bytes.
        String fakePem=String.join(" ","-----BEGIN","PRIVATE","KEY-----\nsecret\n-----END","PRIVATE","KEY-----");
        for(String event:new String[]{"url=https://user:secret@example.test/path?token=abc", "Authorization: Bearer abc def", "Cookie: sid=abc; other=def", "{\"cookie\":\"abc\"}", "{\"password\":\"abc def\",\"token\":\"xyz\"}", "api_key=secret",fakePem}) {
            String safe=DiagnosticBuffer.sanitize(event);assertFalse(safe.contains("abc"));assertFalse(safe.contains("secret"));assertFalse(safe.contains("xyz"));assertFalse(safe.contains("example.test"));
        }
    }
    @Test public void snapshotsAreIndependentAndEntriesHaveALengthLimit() {
        DiagnosticBuffer buffer=new DiagnosticBuffer(10,4096);buffer.add("x".repeat(1000));assertEquals(600,buffer.snapshot().get(0).length());buffer.snapshot().clear();assertEquals(1,buffer.snapshot().size());
    }
}
