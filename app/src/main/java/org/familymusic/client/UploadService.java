package org.familymusic.client;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UploadService extends Service {
    static final String ACTION_STATUS = BuildConfig.APPLICATION_ID + ".UPLOAD_STATUS";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_DONE = "done";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".UPLOAD_PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".UPLOAD_RESUME";
    private static final String CHANNEL = "music_uploads";
    private static final int NOTIFICATION = 72;
    private static final int CHUNK = 1024 * 1024;
    private final ConcurrentLinkedQueue<Uri> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean working = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences state;

    static void enqueue(Context context, Uri uri) {
        Intent intent = new Intent(context, UploadService.class).setData(uri);
        context.startForegroundService(intent);
    }

    static void resume(Context context) {
        SharedPreferences saved = context.getSharedPreferences("active_upload", MODE_PRIVATE);
        String uri = saved.getString("uri", "");
        if (!uri.isEmpty()) context.startForegroundService(new Intent(context, UploadService.class).setData(Uri.parse(uri)));
    }

    @Override public void onCreate() {
        super.onCreate(); ApiClient.initialize(this); state = getSharedPreferences("active_upload", MODE_PRIVATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Загрузка музыки", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent!=null&&ACTION_PAUSE.equals(intent.getAction())){paused.set(true);state.edit().putString("status","paused").apply();publish("Загрузка приостановлена",state.getInt("progress",0),false,false);return START_NOT_STICKY;}
        if(intent!=null&&ACTION_RESUME.equals(intent.getAction()))paused.set(false);
        startForeground(NOTIFICATION, notification("Подготовка загрузки…", 0, true));
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null) { String saved = state.getString("uri", ""); if (!saved.isEmpty()) uri = Uri.parse(saved); }
        if (uri != null) queue.add(uri);
        startQueue();
        return START_STICKY;
    }

    private void startQueue() {
        if (!working.compareAndSet(false, true)) return;
        executor.execute(() -> {
            Uri uri;
            while ((uri = queue.poll()) != null) {
                try { upload(uri); }
                catch (UploadPaused stopped) { queue.clear(); break; }
                catch (Exception error) {String message=error.getMessage()==null?"Ошибка загрузки":error.getMessage();publish(message,0,false,false);state.edit().putString("status","failed").putString("error",message).apply();}
            }
            working.set(false);
            if (!queue.isEmpty()) { startQueue(); return; }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        });
    }

    private void upload(Uri uri) throws Exception {
        FileInfo file = fileInfo(uri);
        state.edit().putString("name",file.name).putString("status","uploading").putString("error","").apply();
        String savedUri = state.getString("uri", "");
        String uploadId = uri.toString().equals(savedUri) ? state.getString("upload_id", "") : "";
        JSONObject status;
        if (uploadId.isEmpty()) {
            JSONObject body = new JSONObject().put("filename", file.name).put("size", file.size).put("mime_type", file.mime);
            status = requestJson("POST", "/uploads", body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", null);
            uploadId = status.getString("id");
            state.edit().putString("uri", uri.toString()).putString("upload_id", uploadId).apply();
        } else status = requestJson("GET", "/uploads/" + uploadId, null, null, null);
        String uploadStatus = status.optString("status", "uploading");
        if (uploadStatus.equals("ready") || uploadStatus.equals("duplicate")) { complete(file.name); return; }
        if (uploadStatus.equals("processing")) { waitUntilProcessed(uploadId, file.name); return; }
        long offset = status.optLong("offset", 0);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Не удалось открыть " + file.name);
            skipFully(input, offset);
            byte[] buffer = new byte[CHUNK];
            while (offset < file.size) {
                if(paused.get())throw new UploadPaused();
                int wanted = (int) Math.min(buffer.length, file.size - offset);
                int count = readChunk(input, buffer, wanted);
                if (count <= 0) throw new Exception("Файл закончился раньше заявленного размера");
                long end = offset + count - 1;
                JSONObject result = requestJson("PUT", "/uploads/" + uploadId, copy(buffer, count), "application/octet-stream", "bytes " + offset + "-" + end + "/" + file.size);
                offset = result.optLong("offset", end + 1);
                int percent = file.size == 0 ? 100 : (int) Math.min(100, offset * 100 / file.size);
                publish(file.name + " — " + percent + "%", percent, false, false);
                if (result.optBoolean("processing")) { waitUntilProcessed(uploadId, file.name); return; }
            }
        }
        waitUntilProcessed(uploadId, file.name);
    }

    private void waitUntilProcessed(String id, String name) throws Exception {
        publish(name + " — обработка…", 100, true, false);
        while (true) {
            JSONObject result = requestJson("GET", "/uploads/" + id, null, null, null);
            String status = result.optString("status");
            if (status.equals("ready") || status.equals("duplicate")) { complete(name); return; }
            if (status.equals("failed")) throw new Exception(result.optString("error", "Не удалось обработать файл"));
            Thread.sleep(2000);
        }
    }

    private void complete(String name) {
        state.edit().clear().putString("last_message",name + " — готово").apply();
        publish(name + " — готово", 100, false, true);
    }

    private JSONObject requestJson(String method, String path, byte[] body, String contentType, String range) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ApiClient.BASE + path).openConnection();
        try {
            connection.setRequestMethod(method); connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", "application/json");
            String cookie = getSharedPreferences("session", MODE_PRIVATE).getString("cookie", "");
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            if (!method.equals("GET")) connection.setRequestProperty("Origin", ApiClient.ORIGIN);
            if (range != null) connection.setRequestProperty("Content-Range", range);
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readText(stream); JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code < 200 || code >= 300) throw new Exception(json.optString("error", "Ошибка сервера: " + code));
            return json;
        } finally { connection.disconnect(); }
    }

    private FileInfo fileInfo(Uri uri) throws Exception {
        String name = "track"; long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { name = cursor.getString(0); size = cursor.getLong(1); }
        }
        if (size < 0) throw new Exception("Не удалось определить размер файла");
        String mime = getContentResolver().getType(uri); if (mime == null) mime = "audio/*";
        return new FileInfo(name, size, mime);
    }

    private void publish(String message, int progress, boolean indeterminate, boolean done) {
        state.edit().putString("last_message",message).putInt("progress",progress).putString("status",done?"done":paused.get()?"paused":"active").apply();
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message, progress, indeterminate));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_DONE, done));
    }

    private android.app.Notification notification(String message, int progress, boolean indeterminate) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_music_note).setContentTitle("Family Music")
                .setContentText(message).setOnlyAlertOnce(true).setOngoing(progress < 100 || indeterminate).setProgress(100, progress, indeterminate).setContentIntent(open).build();
    }

    private static void skipFully(InputStream input, long bytes) throws Exception { while (bytes > 0) { long skipped = input.skip(bytes); if (skipped <= 0) { if (input.read() == -1) throw new Exception("Не удалось продолжить чтение файла"); skipped = 1; } bytes -= skipped; } }
    private static int readChunk(InputStream input, byte[] buffer, int wanted) throws Exception { int total = 0; while (total < wanted) { int read = input.read(buffer, total, wanted - total); if (read < 0) break; total += read; } return total; }
    private static byte[] copy(byte[] source, int count) { if (count == source.length) return source; ByteArrayOutputStream output = new ByteArrayOutputStream(count); output.write(source, 0, count); return output.toByteArray(); }
    private static String readText(InputStream stream) throws Exception { if (stream == null) return ""; StringBuilder result = new StringBuilder(); try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) result.append(line); } return result.toString(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
    private static final class UploadPaused extends Exception {}
    private static final class FileInfo {
        final String name; final long size; final String mime;
        FileInfo(String name, long size, String mime) { this.name = name; this.size = size; this.mime = mime; }
    }
}
