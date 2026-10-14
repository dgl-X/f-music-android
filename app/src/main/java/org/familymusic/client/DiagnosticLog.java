package org.familymusic.client;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class DiagnosticLog {
    private static final DiagnosticBuffer BUFFER = new DiagnosticBuffer(600, 32 * 1024);
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static String sessionId = UUID.randomUUID().toString();
    private static long startedAt = System.currentTimeMillis();
    private static long startedElapsed = SystemClock.elapsedRealtime();

    static synchronized void beginSession() {
        BUFFER.clear();sessionId = UUID.randomUUID().toString();
        startedAt = System.currentTimeMillis();startedElapsed = SystemClock.elapsedRealtime();
    }

    static synchronized void add(Context context, String event) {
        BUFFER.add(FORMAT.format(new Date()) + " +" + (SystemClock.elapsedRealtime() - startedElapsed) + "ms  " + event);
    }

    static synchronized JSONArray snapshot(Context context) {
        return new JSONArray(BUFFER.snapshot());
    }

    static synchronized JSONObject session() {
        JSONObject result = new JSONObject();
        try {
            result.put("id", sessionId).put("started_at_ms", startedAt)
                    .put("age_ms", Math.max(0, SystemClock.elapsedRealtime() - startedElapsed))
                    .put("event_count", BUFFER.snapshot().size()).put("log_bytes", BUFFER.bytes())
                    .put("dropped_events", BUFFER.dropped()).put("scope", "process");
        } catch (Exception ignored) {}
        return result;
    }

    private DiagnosticLog() {}
}
