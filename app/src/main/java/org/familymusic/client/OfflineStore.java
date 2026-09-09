package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class OfflineStore {
    interface Callback { void done(); void failure(String message); default void progress(int percent) {} }

    private final File directory;
    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Set<String> paused = ConcurrentHashMap.newKeySet();

    OfflineStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(context.getFilesDir(), "offline");
        if (!directory.exists()) directory.mkdirs();
        preferences = context.getSharedPreferences("offline_music", Context.MODE_PRIVATE);
    }

    boolean hasTracks() { return !all().isEmpty(); }
    long sizeBytes() { return directorySize(directory); }
    synchronized void clearAll() { for (Track track : all()) remove(track.id); }
    boolean contains(String trackId) { return file(trackId).isFile() && metadata().has(trackId); }
    Uri uri(String trackId) { return Uri.fromFile(file(trackId)); }
    String cover(String trackId) { return coverFile(trackId).isFile() ? Uri.fromFile(coverFile(trackId)).toString() : ""; }
    synchronized JSONArray transferTasks() { try { return new JSONArray(preferences.getString("transfers", "[]")); } catch (Exception ignored) { return new JSONArray(); } }
    void pause(String trackId) { paused.add(trackId); updateTransfer(null, trackId, "paused", -1, ""); }
    void clearTransfer(String trackId) { synchronized(this){JSONArray source=transferTasks(),next=new JSONArray();for(int i=0;i<source.length();i++){JSONObject item=source.optJSONObject(i);if(item!=null&&!trackId.equals(item.optString("id")))next.put(item);}preferences.edit().putString("transfers",next.toString()).apply();} }

    synchronized List<Track> all() {
        List<Track> tracks = new ArrayList<>();
        JSONObject saved = metadata();
        JSONArray names = saved.names();
        if (names == null) return tracks;
        for (int i = 0; i < names.length(); i++) {
            String id = names.optString(i);
            JSONObject item = saved.optJSONObject(id);
            if (item != null && file(id).isFile()) tracks.add(new Track(item));
        }
        tracks.sort((left, right) -> left.artist.compareToIgnoreCase(right.artist));
        return tracks;
    }

    void download(Track track, String cookie, String quality, Callback callback) {
        paused.remove(track.id); updateTransfer(track,track.id,"queued",0,"");
        executor.execute(() -> {
            File target = file(track.id);
            File temporary = new File(directory, track.id + ".part");
            HttpURLConnection connection = null;
            try {
                long offset=temporary.isFile()?temporary.length():0;
                connection = (HttpURLConnection) new URL(ApiClient.ORIGIN + track.streamPath(quality)).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Cookie", cookie);
                connection.setRequestProperty("User-Agent", "FamilyMusic-Android/0.4");
                if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
                int status = connection.getResponseCode();
                if (status != 200 && status != 206) throw new Exception("Сервер вернул " + status);
                if(status==200)offset=0;
                long total=connection.getContentLengthLong()>0?offset+connection.getContentLengthLong():-1,written=offset;
                updateTransfer(track,track.id,"downloading",total>0?(int)Math.min(99,written*100/total):0,"");
                int lastPercent=-1;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary,offset>0)) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {if(paused.contains(track.id))throw new DownloadPaused();output.write(buffer,0,read);written+=read;int percent=total>0?(int)Math.min(99,written*100/total):0;if(percent!=lastPercent){lastPercent=percent;updateTransfer(track,track.id,"downloading",percent,"");callback.progress(percent);}}
                    output.getFD().sync();
                }
                if (!temporary.renameTo(target)) throw new Exception("Не удалось сохранить файл");
                downloadCover(track, cookie);
                saveMetadata(track);
                clearTransfer(track.id);
                callback.done();
            } catch (DownloadPaused stopped) {
                updateTransfer(track,track.id,"paused",-1,"");callback.failure("Скачивание приостановлено");
            } catch (Exception error) {
                updateTransfer(track,track.id,"failed",-1,error.getMessage()==null?"Ошибка скачивания":error.getMessage());
                callback.failure(error.getMessage() == null ? "Ошибка скачивания" : error.getMessage());
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private synchronized void updateTransfer(Track track,String id,String status,int progress,String error){
        JSONArray source=transferTasks(),next=new JSONArray();boolean found=false;
        for(int i=0;i<source.length();i++){JSONObject item=source.optJSONObject(i);if(item==null)continue;if(id.equals(item.optString("id"))){found=true;try{item.put("status",status);if(progress>=0)item.put("progress",progress);item.put("error",error);if(track!=null)item.put("track",track.toJson());}catch(Exception ignored){}}next.put(item);}
        if(!found&&track!=null)try{next.put(new JSONObject().put("id",id).put("status",status).put("progress",Math.max(0,progress)).put("error",error).put("track",track.toJson()));}catch(Exception ignored){}
        preferences.edit().putString("transfers",next.toString()).apply();
    }

    private static final class DownloadPaused extends Exception {}

    void saveFromPlaybackCache(Track track, String cookie, String quality, Callback callback) {
        executor.execute(() -> {
            File target = file(track.id);
            File temporary = new File(directory, track.id + ".part");
            try {
                PlaybackCache.get(context).copyCompleteTrack(track.id, quality, temporary);
                if (!temporary.renameTo(target)) throw new Exception("Не удалось сохранить файл");
                downloadCover(track, cookie);
                saveMetadata(track);
                callback.done();
            } catch (Exception error) {
                temporary.delete();
                callback.failure(error.getMessage() == null ? "Трек не сохранился из кэша" : error.getMessage());
            }
        });
    }

    synchronized void remove(String trackId) {
        file(trackId).delete();
        coverFile(trackId).delete();
        JSONObject saved = metadata();
        saved.remove(trackId);
        preferences.edit().putString("tracks", saved.toString()).commit();
    }

    void updateMetadata(Track track) {
        if (!contains(track.id)) return;
        try { saveMetadata(track); } catch (Exception ignored) {}
    }

    void saveCover(String trackId, byte[] bytes) {
        if (!contains(trackId)) return;
        try (FileOutputStream output = new FileOutputStream(coverFile(trackId))) { output.write(bytes); }
        catch (Exception ignored) {}
    }

    void removeCover(String trackId) { coverFile(trackId).delete(); }

    private File file(String trackId) { return new File(directory, trackId + ".audio"); }
    private File coverFile(String trackId) { return new File(directory, trackId + ".cover"); }

    private synchronized void saveMetadata(Track track) throws Exception {
        JSONObject saved = metadata();
        saved.put(track.id, track.toJson());
        preferences.edit().putString("tracks", saved.toString()).commit();
    }

    private void downloadCover(Track track, String cookie) {
        if (track.coverUrl.isEmpty()) return;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ApiClient.ORIGIN + track.coverUrl).openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
            connection.setRequestProperty("Cookie", cookie);
            if (connection.getResponseCode() != 200) return;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(coverFile(track.id))) {
                byte[] buffer = new byte[32 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
        } catch (Exception ignored) { coverFile(track.id).delete(); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private JSONObject metadata() {
        try { return new JSONObject(preferences.getString("tracks", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }
    private long directorySize(File value) { long total = 0; File[] files = value.listFiles(); if (files != null) for (File file : files) total += file.isDirectory() ? directorySize(file) : file.length(); return total; }
}
