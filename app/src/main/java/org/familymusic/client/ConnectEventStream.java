package org.familymusic.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Hints only: commands and acknowledgements always use the durable REST queue. */
class ConnectEventStream implements AutoCloseable {
    interface Listener { void hint(); void disconnected(); }
    private volatile boolean closed, healthy;
    private volatile HttpURLConnection connection;
    private Thread worker;

    boolean healthy() { return healthy && !closed; }

    synchronized void start(String origin, String cookie, String deviceId, BooleanSupplier valid, Listener listener) {
        if (worker != null) throw new IllegalStateException("Already started");
        worker = new Thread(() -> {
            int failures = 0;
            while (!closed && valid.getAsBoolean()) {
                try {
                    // No advertised arbitrary URL, redirects, or credentials in the query string.
                    if (!origin.equals(ApiClient.normalizeOrigin(origin)) || cookie.isEmpty()) return;
                    HttpURLConnection current = (HttpURLConnection) new URL(origin + "/api/v1/connect/events?device_id="
                        + URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())).openConnection();
                    connection = current;
                    if (closed) { current.disconnect(); break; }
                    current.setInstanceFollowRedirects(false);
                    current.setConnectTimeout(10000);
                    current.setReadTimeout(45000);
                    current.setRequestProperty("Accept", "text/event-stream");
                    current.setRequestProperty("Cookie", cookie);
                    if (current.getResponseCode() != 200 || !String.valueOf(current.getContentType()).startsWith("text/event-stream")) {
                        throw new IOException("Event stream unavailable");
                    }
                    try (Reader reader = new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8)) {
                        readHints(reader, () -> {
                            if (!closed && valid.getAsBoolean()) { healthy = true; listener.hint(); }
                        });
                    }
                    if (healthy) failures = 0;
                } catch (Exception ignored) {
                    if (healthy) failures = 0;
                } finally {
                    healthy = false;
                    HttpURLConnection current = connection; connection = null;
                    if (current != null) current.disconnect();
                }
                if (closed || !valid.getAsBoolean()) break;
                listener.disconnected();
                try { Thread.sleep(Math.min(30000, 1500L << Math.min(failures++, 5))); }
                catch (InterruptedException ignored) { break; }
            }
            healthy = false;
        }, "connect-events");
        worker.setDaemon(true);
        worker.start();
    }

    @Override public void close() {
        closed = true; healthy = false;
        // Disconnect may wait for a blocked read on some Android implementations.
        // Never do that on the UI thread; the read timeout is a second safety net.
        HttpURLConnection current = connection;
        if (current != null) {
            Thread closer = new Thread(current::disconnect, "connect-events-close");
            closer.setDaemon(true); closer.start();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) currentWorker.interrupt();
    }

    /** Bounded SSE parser. Ignore payloads: even a forged event cannot execute a command. */
    static void readHints(Reader reader, Runnable hint) throws IOException {
        StringBuilder line = new StringBuilder();
        String event = "";
        int frameSize = 0;
        boolean first = true, carriageReturn = false;
        for (int value; (value = reader.read()) != -1;) {
            char c = (char) value;
            if (first) { first = false; if (c == '\ufeff') continue; }
            if (carriageReturn && c == '\n') { carriageReturn = false; continue; }
            carriageReturn = c == '\r';
            if (++frameSize > 16384) throw new IOException("Event frame too large");
            if (c != '\r' && c != '\n') { line.append(c); continue; }
            String text = line.toString(); line.setLength(0);
            if (text.isEmpty()) {
                if (event.equals("resync")) hint.run();
                event = ""; frameSize = 0;
            } else if (text.startsWith("event:")) {
                event = text.substring(6);
                if (event.startsWith(" ")) event = event.substring(1);
            }
        }
    }
}
