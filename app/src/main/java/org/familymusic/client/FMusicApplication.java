package org.familymusic.client;

import android.app.Application;
import android.os.Build;

public final class FMusicApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagnosticLog.beginSession();
        DiagnosticLog.add(this, "session started version=" + BuildConfig.VERSION_NAME + " sdk=" + Build.VERSION.SDK_INT);
        Thread cleanup = new Thread(() -> deleteSharedPreferences("diagnostic_log"), "old-diagnostic-cleanup");
        cleanup.setDaemon(true);cleanup.start();
    }
}
