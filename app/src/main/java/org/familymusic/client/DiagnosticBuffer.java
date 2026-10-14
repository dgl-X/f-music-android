package org.familymusic.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded, process-local journal. No disk access or previous-session data. */
final class DiagnosticBuffer {
    private final int maxEntries;
    private final int maxBytes;
    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private int bytes;
    private long dropped;
    DiagnosticBuffer(int maxEntries, int maxBytes) { this.maxEntries = maxEntries; this.maxBytes = maxBytes; }
    static String sanitize(String value) {
        String safe = String.valueOf(value)
                .replaceAll("(?s)-----BEGIN[^\\r\\n]*PRIVATE KEY-----.*", "[private key hidden]")
                .replaceAll("(?i)\\b(?:https?|wss?|file)://\\S+", "[url]")
                .replaceAll("(?i)[\"']?(authorization|proxy-authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*[^\\r\\n]*", "$1=[hidden]")
                .replaceAll("(?i)[\"']?((?:access_|refresh_)?token|password|api[_-]?key|secret|private[_-]?key)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "$1=[hidden]")
                .replaceAll("(?i)\\bBearer\\s+\\S+", "Bearer [hidden]")
                .replaceAll("\\b[0-9]{6,14}:[A-Za-z0-9_-]{20,}\\b", "[token hidden]")
                .replaceAll("[\\p{Cntrl}]", " ");
        return safe.length() > 600 ? safe.substring(0, 600) : safe;
    }
    synchronized void add(String value) {
        String safe = sanitize(value);entries.addLast(safe);bytes += safe.getBytes(StandardCharsets.UTF_8).length;
        while (entries.size() > maxEntries || bytes > maxBytes) { bytes -= entries.removeFirst().getBytes(StandardCharsets.UTF_8).length; dropped++; }
    }
    synchronized List<String> snapshot() { return new ArrayList<>(entries); }
    synchronized int bytes() { return bytes; }
    synchronized long dropped() { return dropped; }
    synchronized void clear() { entries.clear(); bytes = 0; dropped = 0; }
}
