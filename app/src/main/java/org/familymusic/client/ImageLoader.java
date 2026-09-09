package org.familymusic.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final String cookie;
    private final File diskCache;

    ImageLoader(Context context) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 10);
        cache = new LruCache<>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount() / 1024; }
        };
        cookie = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("cookie", "");
        diskCache = new File(context.getCacheDir(), "covers");
        diskCache.mkdirs();
        executor.execute(this::trimDiskCache);
    }

    void load(String path, ImageView target) {
        target.setTag(path);
        if (path == null || path.isEmpty()) { target.setImageResource(R.drawable.ic_music_note); return; }
        Bitmap ready = cache.get(path);
        if (ready != null) { target.setImageBitmap(ready); return; }
        target.setImageResource(R.drawable.ic_music_note);
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (path.startsWith("file:")) {
                    Bitmap bitmap = BitmapFactory.decodeFile(Uri.parse(path).getPath());
                    if (bitmap != null) {
                        cache.put(path, bitmap);
                        target.post(() -> { if (path.equals(target.getTag())) target.setImageBitmap(bitmap); });
                    }
                    return;
                }
                File stored = cacheFile(path);
                Bitmap storedBitmap = stored.isFile() ? BitmapFactory.decodeFile(stored.getAbsolutePath()) : null;
                if (storedBitmap != null) {
                    stored.setLastModified(System.currentTimeMillis());
                    cache.put(path, storedBitmap);
                    target.post(() -> { if (path.equals(target.getTag())) target.setImageBitmap(storedBitmap); });
                    return;
                }
                connection = (HttpURLConnection) new URL(ApiClient.ORIGIN + path).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                File temporary = new File(diskCache, stored.getName() + "." + Thread.currentThread().getId() + ".part");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32 * 1024]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                if (stored.isFile()) temporary.delete();
                else if (!temporary.renameTo(stored)) { temporary.delete(); return; }
                Bitmap bitmap = BitmapFactory.decodeFile(stored.getAbsolutePath());
                if (bitmap == null) { stored.delete(); return; }
                cache.put(path, bitmap);
                target.post(() -> { if (path.equals(target.getTag())) target.setImageBitmap(bitmap); });
            } catch (Exception ignored) {
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private File cacheFile(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(); for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return new File(diskCache, name + ".img");
        } catch (Exception ignored) { return new File(diskCache, Integer.toHexString(path.hashCode()) + ".img"); }
    }

    private void trimDiskCache() {
        File[] files = diskCache.listFiles(file -> file.isFile());
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long kept = 0, limit = 64L * 1024L * 1024L;
        for (File file : files) { kept += file.length(); if (kept > limit) file.delete(); }
    }
}
