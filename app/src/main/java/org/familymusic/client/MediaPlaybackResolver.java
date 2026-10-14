package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Called only on Media3 / cache / download worker threads, never for the queue on UI. */
final class MediaPlaybackResolver {
    static final String CODECS="mp3,aac,flac,opus,vorbis,pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le";
    private final Context context;
    private final SharedPreferences saved;
    private static final java.util.Map<String,Boolean> rejectedNative=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String,Boolean>(){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Boolean> entry){return size()>256;}
    });
    MediaPlaybackResolver(Context context) {
        this.context=context; saved=context.getSharedPreferences("media_selections_v1",Context.MODE_PRIVATE);
    }
    MediaSelection saved(String key) {
        try {
            JSONObject json=new JSONObject(saved.getString(key,"{}"));
            String origin=json.getString("origin"),track=json.getString("track");
            if(!origin.equals(ApiClient.ORIGIN))return null;
            return MediaSelection.parse(json,origin,track,key);
        }catch(Exception ignored){return null;}
    }
    boolean nativeRejected(String key){return rejectedNative.containsKey(key);}
    boolean rejectNative(String key){return rejectedNative.put(key,true)==null;}
    MediaSelection resolve(Uri uri,String key,String cookie,boolean wait) throws IOException {
        String path=uri.getPath();
        if(key==null||!uri.toString().startsWith(ApiClient.ORIGIN+"/")||path==null
                ||!path.matches("/api/v1/tracks/[0-9a-f-]+/stream"))return null;
        String track=path.split("/")[4],quality=uri.getQueryParameter("quality");
        String endpoint=ApiClient.ORIGIN+"/api/v1/tracks/"+track+"/playback?quality="+Uri.encode(quality==null?"original":quality)+"&codecs="+(nativeRejected(key)?"aac":CODECS);
        long deadline=SystemClock.elapsedRealtime()+30_000;
        do {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
            HttpURLConnection connection=null;
            JSONObject json;
            try {
                connection=(HttpURLConnection)new URL(endpoint).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
                connection.setRequestProperty("Cookie",cookie);connection.setRequestProperty("Accept","application/json");
                int status=connection.getResponseCode();
                java.io.InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
                if(input==null)throw new IOException("Сервер не вернул источник аудио");
                byte[] body;
                try(input;java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
                    byte[] buffer=new byte[2048];int count;
                    while(output.size()<=16384&&(count=input.read(buffer,0,Math.min(buffer.length,16385-output.size())))!=-1)output.write(buffer,0,count);
                    body=output.toByteArray();
                }
                if(body.length>16384)throw new IOException("Слишком большой ответ источника");
                json=new JSONObject(new String(body,StandardCharsets.UTF_8));
                // Only an absent endpoint permits compatibility with older nodes.
                if(status==404&&"Маршрут не найден".equals(json.optString("error")))return null;
                if(status!=200)throw new IOException("Источник аудио: HTTP "+status);
            }catch(IOException error){throw error;}
            catch(Exception error){throw new IOException("Некорректный ответ источника",error);}
            finally{if(connection!=null)connection.disconnect();}
            String status=json.optString("status");
            if("ready".equals(status)){
                MediaSelection selection=MediaSelection.parse(json,ApiClient.ORIGIN,track,key);
                try{json.put("origin",ApiClient.ORIGIN).put("track",track);saved.edit().putString(key,json.toString()).apply();}catch(Exception ignored){}
                DiagnosticLog.add(context,"media selection ready variant="+selection.variant+" size="+selection.size);
                return selection;
            }
            if(!"preparing".equals(status))throw new IOException("failed".equals(status)?"Не удалось подготовить аудио":"Формат аудио не поддерживается");
            if(!wait)throw new IOException("Аудио ещё готовится");
            DiagnosticLog.add(context,"media selection preparing");
            try{Thread.sleep(Math.max(250,Math.min(1500,json.optInt("retry_after_ms",1000))));}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
        }while(SystemClock.elapsedRealtime()<deadline);
        throw new IOException("Подготовка аудио пока не завершена");
    }
    void clear(){saved.edit().clear().apply();}
}
