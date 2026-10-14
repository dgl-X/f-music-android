package org.familymusic.client;

import org.junit.Test;
import java.io.StringReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ConnectEventStreamTest {
    private int hints(String input) throws IOException {
        AtomicInteger count=new AtomicInteger();
        ConnectEventStream.readHints(new StringReader(input),count::incrementAndGet);
        return count.get();
    }
    @Test public void parsesBomCommentsAndCrLf() throws Exception {
        assertEquals(2,hints("\ufeffretry: 1500\r\nevent: resync\r\ndata: {}\r\n\r\n: heartbeat\nevent:resync\ndata: one\ndata: two\n\n"));
    }
    @Test public void ignoresCommandsAndIncompleteFrames() throws Exception {
        assertEquals(0,hints("event: command\ndata: {\"action\":\"next\"}\n\nevent: resync\ndata: {}\n"));
    }
    @Test public void resetsEventNameBetweenFrames() throws Exception {
        assertEquals(1,hints("event: resync\n\ndata: {}\n\n\nevent: resync\nevent: unknown\n\n"));
    }
    @Test public void boundsFramesAndLines() throws Exception {
        try { hints("data: "+"x".repeat(16384)); fail("Oversized frame accepted"); }
        catch(IOException expected) { assertEquals("Event frame too large",expected.getMessage()); }
    }
    @Test public void closedTransportIsNotHealthy() {
        ConnectEventStream stream=new ConnectEventStream();stream.close();assertFalse(stream.healthy());
    }
}
