package org.familymusic.client;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LikedDownloadService extends Service {
    static final String ACTION_STATUS = BuildConfig.APPLICATION_ID + ".LIKED_DOWNLOAD_STATUS";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_DONE = "done";
    private static final String CHANNEL = "liked_downloads";
    private static final int NOTIFICATION = 74;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private SharedPreferences state;
    private OfflineStore offline;

    static void enqueue(Context context) {
        context.startForegroundService(new Intent(context, LikedDownloadService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        ApiClient.initialize(this);
        state = getSharedPreferences("liked_download", MODE_PRIVATE);
        offline = new OfflineStore(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Скачивание понравившихся", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION, notification("Получаем список понравившихся…", 0, true, false));
        if (running.compareAndSet(false, true)) executor.execute(this::downloadLiked);
        return START_NOT_STICKY;
    }

    private void downloadLiked() {
        int completed = 0, failed = 0;
        try {
            List<Track> tracks = fetchLiked();
            int total = tracks.size();
            if (total == 0) { finish("В «Мне нравится» пока нет треков", 0, 0, 0); return; }
            publish("Подготовлено: " + total, 0, total, false, false, 0);
            String cookie = getSharedPreferences("session", MODE_PRIVATE).getString("cookie", "");
            String quality = new AppSettings(this).downloadQuality();
            for (Track track : tracks) {
                if (track.remote && !track.streamAvailable) { failed++; publish(track.artist + " — " + track.title + " · сервер недоступен", completed + failed, total, false, false, failed); continue; }
                if (offline.contains(track.id)) {
                    completed++;
                    publish(track.artist + " — " + track.title, completed, total, false, false, failed);
                    continue;
                }
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean success = new AtomicBoolean(false);
                final int before = completed;
                offline.download(track, cookie, quality, new OfflineStore.Callback() {
                    @Override public void done() { success.set(true); latch.countDown(); }
                    @Override public void failure(String message) { latch.countDown(); }
                    @Override public void progress(int percent) {
                        int overall = total == 0 ? 0 : Math.min(99, (before * 100 + percent) / total);
                        publishPercent((before + 1) + " из " + total + " · " + track.artist + " — " + track.title, overall);
                    }
                });
                latch.await();
                if (success.get()) completed++; else failed++;
                publish(track.artist + " — " + track.title, completed + failed, total, false, false, failed);
            }
            String message = failed == 0 ? "Понравившиеся доступны без интернета" : "Готово: " + completed + ", ошибок: " + failed;
            finish(message, completed, total, failed);
        } catch (Exception error) {
            finish(error.getMessage() == null ? "Не удалось скачать понравившиеся" : error.getMessage(), completed, 0, failed + 1);
        } finally {
            running.set(false);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private List<Track> fetchLiked() throws Exception {
        List<Track> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            JSONObject page = request("/favorites?sort=newest&offset=" + offset + "&limit=200");
            JSONArray items = page.optJSONArray("items");
            int count = items == null ? 0 : items.length();
            for (int i = 0; i < count; i++) { JSONObject item = items.optJSONObject(i); if (item != null) result.add(new Track(item)); }
            offset += count;
            if (!page.optBoolean("has_more", offset < page.optInt("total", offset)) || count == 0) return result;
        }
    }

    private JSONObject request(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ApiClient.BASE + path).openConnection();
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/json");
            String cookie = getSharedPreferences("session", MODE_PRIVATE).getString("cookie", "");
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int status = connection.getResponseCode(); InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder text = new StringBuilder(); if (stream != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) text.append(line); }
            JSONObject json = text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
            if (status < 200 || status >= 300) throw new Exception(json.optString("error", "Ошибка сервера: " + status));
            return json;
        } finally { connection.disconnect(); }
    }

    private void publish(String message, int processed, int total, boolean indeterminate, boolean done, int failed) {
        int percent = total == 0 ? 0 : Math.min(100, processed * 100 / total);
        state.edit().putString("status", done ? "done" : "active").putString("message", message).putInt("completed", processed - failed)
                .putInt("failed", failed).putInt("total", total).putInt("progress", percent).apply();
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message, percent, indeterminate, done));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_DONE, done));
    }

    private void publishPercent(String message, int percent) {
        state.edit().putString("status", "active").putString("message", message).putInt("progress", percent).apply();
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message, percent, false, false));
    }

    private void finish(String message, int completed, int total, int failed) { publish(message, completed + failed, total, false, true, failed); }

    private android.app.Notification notification(String message, int progress, boolean indeterminate, boolean done) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_nav_download).setContentTitle("Скачивание «Мне нравится»")
                .setContentText(message).setStyle(new NotificationCompat.BigTextStyle().bigText(message)).setOnlyAlertOnce(true)
                .setOngoing(!done).setProgress(100, progress, indeterminate).setContentIntent(open).build();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
