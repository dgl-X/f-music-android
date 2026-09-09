package org.familymusic.client;

import android.content.Context;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

final class DiagnosticLog {
    private static final int MAX_ENTRIES = 120;
    private static final String PREFS = "diagnostic_log";
    private static final String KEY = "events";

    static synchronized void add(Context context, String event) {
        ArrayDeque<String> entries = read(context);
        String safe = String.valueOf(event).replaceAll("(?i)(cookie|token|password|authorization)\\s*[=:]\\s*\\S+", "$1=[hidden]");
        if (safe.length() > 500) safe = safe.substring(0, 500);
        entries.addLast(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + safe);
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
        save(context, entries);
    }

    static synchronized JSONArray snapshot(Context context) {
        JSONArray result = new JSONArray();
        for (String entry : read(context)) result.put(entry);
        return result;
    }

    private static ArrayDeque<String> read(Context context) {
        ArrayDeque<String> result = new ArrayDeque<>();
        try {
            JSONArray stored = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < stored.length(); i++) result.addLast(stored.optString(i));
        } catch (Exception ignored) {}
        return result;
    }

    private static void save(Context context, ArrayDeque<String> entries) {
        JSONArray value = new JSONArray();
        for (String entry : entries) value.put(entry);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.toString()).apply();
    }

    private DiagnosticLog() {}
}
