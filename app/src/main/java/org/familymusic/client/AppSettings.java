package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private final SharedPreferences values;
    AppSettings(Context context) { values = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    boolean autoSaveLikes() { return values.getBoolean("auto_save_likes", true); }
    boolean autoSaveWifiOnly() { return values.getBoolean("auto_save_wifi_only", false); }
    String autoSaveMode() { return values.getString("auto_save_mode", "after_listen"); }
    long cacheBytes() { return values.getLong("cache_bytes", 1024L * 1024L * 1024L); }
    long prefetchBytes() { return values.getLong("prefetch_bytes", 3L * 1024L * 1024L); }
    String startTab() { return values.getString("start_tab", "liked"); }
    String lastTab() { return values.getString("last_tab", "liked"); }
    String wifiQuality() { return values.getString("wifi_quality", "original"); }
    String mobileQuality() { return values.getString("mobile_quality", "original"); }
    String downloadQuality() { return values.getString("download_quality", "original"); }
    long sleepDeadline() { return values.getLong("sleep_deadline", 0); }
    boolean loudnessNormalization() { return values.getBoolean("loudness_normalization", false); }
    void putBoolean(String key, boolean value) { values.edit().putBoolean(key, value).apply(); }
    void putLong(String key, long value) { values.edit().putLong(key, value).apply(); }
    void putString(String key, String value) { values.edit().putString(key, value).apply(); }
}
