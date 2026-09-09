package org.familymusic.client;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.media3.common.util.BitmapLoader;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads private cover artwork for Android's media notification and lock screen. */
final class SessionBitmapLoader implements BitmapLoader {
    private final Context context;
    private final String cookie;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    SessionBitmapLoader(Context context, String cookie) {
        this.context = context.getApplicationContext();
        this.cookie = cookie;
    }

    @Override public boolean supportsMimeType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @Override public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) return Futures.immediateFailedFuture(new IllegalArgumentException("Invalid artwork"));
        return Futures.immediateFuture(bitmap);
    }

    @Override public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
        SettableFuture<Bitmap> result = SettableFuture.create();
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String scheme = uri.getScheme();
                Bitmap bitmap;
                if (ContentResolver.SCHEME_FILE.equals(scheme) || ContentResolver.SCHEME_CONTENT.equals(scheme)) {
                    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                } else {
                    URL url = new URL(uri.toString());
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(15000);
                    // Never forward the authenticated session to another host.
                    if (ApiClient.ORIGIN.equals(url.getProtocol() + "://" + url.getHost()) && !cookie.isEmpty()) {
                        connection.setRequestProperty("Cookie", cookie);
                    }
                    try (InputStream input = connection.getInputStream()) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                }
                if (bitmap == null) throw new IllegalArgumentException("Invalid artwork");
                result.set(bitmap);
            } catch (Throwable error) {
                result.setException(error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
        return result;
    }
}
