package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiClient {
    static volatile String ORIGIN = BuildConfig.SERVER_ORIGIN;
    static volatile String BASE = ORIGIN + "/api/v1";
    private final SharedPreferences preferences;
    private final String deviceName;
    private final String clientName;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    interface Callback {
        void success(JSONObject json);
        void failure(String message);
    }

    ApiClient(Context context) {
        preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE);
        deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        String version;
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { version = ""; }
        clientName = "Family Music Android " + version;
        initialize(context);
    }

    static void initialize(Context context) {
        SharedPreferences saved = context.getSharedPreferences("session", Context.MODE_PRIVATE);
        try { applyOrigin(normalizeOrigin(saved.getString("server_origin", BuildConfig.SERVER_ORIGIN))); }
        catch (Exception ignored) { applyOrigin(BuildConfig.SERVER_ORIGIN); }
    }

    String origin() { return ORIGIN; }

    void setOrigin(String value) throws Exception {
        String normalized = normalizeOrigin(value);
        if (!normalized.equals(ORIGIN)) {
            preferences.edit().putString("server_origin", normalized).remove("cookie").apply();
            applyOrigin(normalized);
        }
    }

    private static synchronized void applyOrigin(String value) {
        ORIGIN = value;
        BASE = value + "/api/v1";
    }

    static String normalizeOrigin(String value) throws Exception {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty()) throw new Exception("Укажите адрес сервера");
        if (!candidate.contains("://")) candidate = "https://" + candidate;
        URI uri = new URI(candidate);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new Exception("Сервер должен использовать HTTPS");
        if (uri.getHost() == null || uri.getHost().isEmpty()) throw new Exception("Некорректный адрес сервера");
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                uri.getPort() != -1 || uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
            throw new Exception("Укажите только адрес сервера без пути и параметров");
        }
        return "https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT);
    }

    boolean hasSession() { return !preferences.getString("cookie", "").isEmpty(); }
    String cookie() { return preferences.getString("cookie", ""); }
    void clearSession() { preferences.edit().remove("cookie").apply(); }

    void get(String path, Callback callback) { request("GET", path, null, callback); }
    void post(String path, JSONObject body, Callback callback) { request("POST", path, body, callback); }
    void patch(String path, JSONObject body, Callback callback) { request("PATCH", path, body, callback); }
    void put(String path, JSONObject body, Callback callback) { request("PUT", path, body, callback); }
    void delete(String path, Callback callback) { request("DELETE", path, null, callback); }
    void putBytes(String path, byte[] body, String mimeType, Callback callback) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(BASE + path).openConnection();
                connection.setRequestMethod("PUT"); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Origin", ORIGIN);
                addClientHeaders(connection);
                String cookie = cookie(); if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", mimeType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode(); InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = read(stream); JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                if (status >= 200 && status < 300) callback.success(json); else callback.failure(json.optString("error", "Ошибка сервера: " + status));
            } catch (Exception error) { callback.failure(error.getMessage() == null ? "Ошибка соединения" : error.getMessage()); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void request(String method, String path, JSONObject body, Callback callback) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(BASE + path).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "application/json");
                addClientHeaders(connection);
                String cookie = cookie();
                if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                if (!method.equals("GET") && !method.equals("HEAD") && !cookie.isEmpty()) connection.setRequestProperty("Origin", ORIGIN);
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                String setCookie = connection.getHeaderField("Set-Cookie");
                if (setCookie != null && setCookie.startsWith("music_session=")) {
                    String value = setCookie.split(";", 2)[0];
                    if (value.endsWith("=")) clearSession(); else preferences.edit().putString("cookie", value).apply();
                }
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = read(stream);
                JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                if (status >= 200 && status < 300) callback.success(json);
                else callback.failure(json.optString("error", "Ошибка сервера: " + status));
            } catch (Exception error) {
                callback.failure(error.getMessage() == null ? "Ошибка соединения" : error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void addClientHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("X-Device-Name", deviceName);
        connection.setRequestProperty("X-Client-Name", clientName);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
