package org.familymusic.client;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Connect lifetime and command execution belong to the actual audio player. */
final class ConnectPlayback implements ConnectManager.Listener,ConnectSessionPlayer.Remote {
    private final Context context;
    private final ExoPlayer player;
    private final ApiClient api;
    private final Handler handler=new Handler(Looper.getMainLooper());
    final ConnectManager manager;
    private JSONObject state;
    private long stateReceivedAt;
    private Runnable sessionStateChanged=()->{};
    public void sessionStateListener(Runnable listener){sessionStateChanged=listener;}
    public ConnectCommandRouter.Destination systemDestination(){return ConnectCommandRouter.destination(manager.enabled(),manager.deviceId(),state==null?null:state.optString("active_device_id"),preparing);}
    public JSONObject remoteState(){return state==null?new JSONObject():state;}
    public MediaItem remoteMediaItem(){
        String id=state==null?"":state.optString("track_id","");
        if(id.isEmpty()||id.equals("null"))return null;
        Track track=knownTracks.get(id);
        MediaMetadata.Builder metadata=new MediaMetadata.Builder().setTitle(track==null?"Загрузка трека…":track.title);
        if(track!=null){metadata.setArtist(track.artist).setAlbumTitle(track.album);if(!track.coverUrl.isEmpty())metadata.setArtworkUri(Uri.parse(ApiClient.ORIGIN+track.coverUrl));}
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build()).build();
    }
    boolean remoteActive(){return systemDestination()==ConnectCommandRouter.Destination.REMOTE;}
    boolean remotePlaying(){return state!=null&&state.optBoolean("playing");}
    boolean remoteAdvancing(){return ConnectPlaybackStatus.advances(state);}
    boolean remoteBuffering(){return state!=null&&state.optBoolean("buffering");}
    long remoteDuration(){return state==null?0:Math.round(Math.max(0,state.optDouble("duration_seconds"))*1000);}
    public long remotePosition(){
        if(state==null)return 0;
        long position=Math.round(Math.max(0,state.optDouble("position_seconds"))*1000);
        if(remoteAdvancing())position+=Math.max(0,SystemClock.elapsedRealtime()-stateReceivedAt);
        return remoteDuration()>0?Math.min(position,remoteDuration()):position;
    }
    public void systemCommand(String action,JSONObject payload){
        DiagnosticLog.add(context,"system Connect command="+action);
        manager.command(action,payload);
    }
    private boolean preparing,closed;
    private int preparationGeneration;
    private final List<String> history=new ArrayList<>();
    private String previousId="";
    String queueSource="";
    private boolean navigatingBack;
    private final java.util.Map<String,Track> knownTracks=new java.util.HashMap<>();
    Track track(String id){return knownTracks.get(id);}
    private final Runnable heartbeat=new Runnable(){@Override public void run(){publish();if(!closed)handler.postDelayed(this,3000);}};

    ConnectPlayback(Context context,ExoPlayer player){
        this.context=context;this.player=player;api=new ApiClient(context);
        manager=new ConnectManager(context,api,this);
        player.addListener(new Player.Listener(){
            @Override public void onEvents(Player p,Player.Events events){if(!preparing)publish();}
            @Override public void onMediaItemTransition(MediaItem item,int reason){
                if(preparing)return;
                if(!navigatingBack&&!previousId.isEmpty()&&(item==null||!previousId.equals(item.mediaId))){history.add(previousId);if(history.size()>10000)history.remove(0);}
                navigatingBack=false;
                previousId=item==null?"":item.mediaId;
            }
        });
        manager.start();handler.postDelayed(heartbeat,3000);
    }

    boolean preparing(){return preparing;}
    void close(){closed=true;handler.removeCallbacksAndMessages(null);manager.stop();}
    private void publish(){
        if(closed||preparing||!manager.enabled()||player.getCurrentMediaItem()==null)return;
        if(state!=null&&!state.isNull("active_device_id")&&!state.optString("active_device_id").isEmpty()&&!manager.deviceId().equals(state.optString("active_device_id")))return;
        manager.updateState(snapshot());
    }
    private String metadataRequest="",metadataFailed="";
    private long metadataFailedAt;
    @Override public void state(JSONObject value){if(closed)return;state=value;stateReceivedAt=SystemClock.elapsedRealtime();sessionStateChanged.run();resolveRemoteMetadata();}
    private void resolveRemoteMetadata(){
        MediaItem item=remoteMediaItem();
        if(!remoteActive()||item==null||knownTracks.containsKey(item.mediaId)||item.mediaId.equals(metadataRequest)||(item.mediaId.equals(metadataFailed)&&SystemClock.elapsedRealtime()-metadataFailedAt<10000))return;
        final String id=item.mediaId;metadataRequest=id;
        JSONObject body=new JSONObject();try{body.put("ids",new JSONArray().put(id));}catch(Exception ignored){}
        api.post("/tracks/resolve",body,new ApiClient.Callback(){
            @Override public void success(JSONObject result){handler.post(()->{
                if(closed)return;
                if(id.equals(metadataRequest))metadataRequest="";
                JSONArray items=result.optJSONArray("items");JSONObject json=items==null?null:items.optJSONObject(0);
                if(json==null||!id.equals(json.optString("id"))){metadataFailed=id;metadataFailedAt=SystemClock.elapsedRealtime();return;}
                knownTracks.put(id,new Track(json));
                if(remoteActive()&&state!=null&&id.equals(state.optString("track_id")))sessionStateChanged.run();
            });}
            @Override public void failure(String message){handler.post(()->{if(id.equals(metadataRequest))metadataRequest="";metadataFailed=id;metadataFailedAt=SystemClock.elapsedRealtime();});}
        });
    }
    @Override public void enabled(boolean value){if(!closed)sessionStateChanged.run();}

    @Override public JSONObject snapshot(){
        JSONObject result=new JSONObject();JSONArray queue=new JSONArray(),order=new JSONArray(),past=new JSONArray();
        for(int i=0;i<player.getMediaItemCount();i++)queue.put(player.getMediaItemAt(i).mediaId);
        Timeline timeline=player.getCurrentTimeline();
        int index=timeline.getFirstWindowIndex(true);
        for(int n=0;index>=0&&n<player.getMediaItemCount();n++){
            order.put(player.getMediaItemAt(index).mediaId);
            index=timeline.getNextWindowIndex(index,Player.REPEAT_MODE_OFF,true);
        }
        for(String id:history)past.put(id);
        MediaItem current=player.getCurrentMediaItem();
        boolean intent=player.getPlayWhenReady()&&player.getPlaybackState()!=Player.STATE_ENDED;
        boolean actual=intent&&player.isPlaying(),buffering=intent&&player.getPlaybackState()==Player.STATE_BUFFERING;
        try{result.put("track_id",current==null?JSONObject.NULL:current.mediaId)
            .put("position_seconds",Math.max(0,player.getCurrentPosition())/1000.0)
            .put("duration_seconds",durationMs()/1000.0)
            .put("queue",queue).put("shuffle_order",order).put("playback_history",past)
            .put("queue_source",queueSource)
            .put("playing",intent).put("is_playing",actual).put("buffering",buffering)
            .put("playback_status",ConnectPlaybackStatus.status(intent,actual,buffering,preparing)).put("shuffle",player.getShuffleModeEnabled())
            .put("repeat_mode",player.getRepeatMode()==Player.REPEAT_MODE_ONE?"one":player.getRepeatMode()==Player.REPEAT_MODE_ALL?"all":"off")
            .put("volume",player.getVolume());}catch(Exception ignored){}
        return result;
    }

    @Override public void command(String action,JSONObject payload,ConnectManager.Completion done){
        if(closed){done.finish(false,"Плеер закрыт");return;}
        if(action.equals("transfer")){prepare(payload,done);return;}
        try{
            if(action.equals("deactivate")){player.pause();done.finish(true,"");return;}
            if(action.equals("activate")){
                if(payload.has("position_seconds")){PlaybackService.requestLocalSeek(Math.round(Math.max(0,payload.optDouble("position_seconds"))*1000));}
                player.setPlayWhenReady(payload.optBoolean("playing"));
            }else switch(action){
                case "play" -> player.play();
                case "pause" -> player.pause();
                case "next" -> player.seekToNextMediaItem();
                case "previous" -> previous();
                case "seek" -> PlaybackService.requestLocalSeek(Math.round(Math.max(0,payload.optDouble("position_seconds"))*1000));
                case "set_shuffle" -> player.setShuffleModeEnabled(payload.optBoolean("enabled"));
                case "set_repeat" -> player.setRepeatMode(payload.optString("mode").equals("one")?Player.REPEAT_MODE_ONE:payload.optString("mode").equals("all")?Player.REPEAT_MODE_ALL:Player.REPEAT_MODE_OFF);
                case "set_volume" -> player.setVolume((float)Math.max(0,Math.min(1,payload.optDouble("volume",1))));
                default -> {done.finish(false,"Неизвестная команда");return;}
            }
            MediaItem item=player.getCurrentMediaItem();
            if(item==null){done.finish(false,"Трека нет");return;}
            long expected=action.equals("seek")||payload.has("position_seconds")?Math.round(Math.max(0,payload.optDouble("position_seconds"))*1000):player.getCurrentPosition();
            awaitReady(item.mediaId,expected,()->{if(action.equals("activate"))preparing=false;done.finish(true,"");},done);
        }catch(Exception error){preparing=false;done.finish(false,error.getMessage());}
    }

    public void previous(){
        if(player.getCurrentPosition()>player.getMaxSeekToPreviousPosition()){player.seekTo(0);return;}
        while(!history.isEmpty()){
            String id=history.remove(history.size()-1);
            for(int i=0;i<player.getMediaItemCount();i++)if(player.getMediaItemAt(i).mediaId.equals(id)){navigatingBack=true;player.seekTo(i,0);return;}
        }
        player.seekToPreviousMediaItem();
    }

    private void prepare(JSONObject payload,ConnectManager.Completion done){
        JSONArray ids=payload.optJSONArray("queue");String current=payload.optString("track_id");
        if(ids==null||ids.length()==0||current.isEmpty()){done.finish(false,"Пустая очередь");return;}
        JSONObject body=new JSONObject();try{body.put("ids",ids);}catch(Exception ignored){}
        api.post("/tracks/resolve",body,new ApiClient.Callback(){
            @Override public void success(JSONObject result){
                List<String> requested=new ArrayList<>();for(int i=0;i<ids.length();i++)requested.add(ids.optString(i));
                List<Track> resolved=new ArrayList<>();JSONArray values=result.optJSONArray("items");if(values!=null)for(int i=0;i<values.length();i++)resolved.add(new Track(values.optJSONObject(i)));
                List<Track> tracks=ConnectQueueOrder.restore(requested,resolved);List<MediaItem> items=new ArrayList<>();int selected=-1;
                OfflineStore offline=new OfflineStore(context);AppSettings settings=new AppSettings(context);
                android.net.ConnectivityManager cm=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
                String requestedQuality=caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)?settings.wifiQuality():settings.mobileQuality();
                java.util.Set<String> localIds=new java.util.HashSet<>();for(Track local:offline.all())localIds.add(local.id);
                for(Track track:tracks){boolean local=localIds.contains(track.id);if(!track.streamAvailable&&!local)continue;if(track.id.equals(current))selected=items.size();items.add(mediaItem(track,offline,requestedQuality,local));}
                final int selectedIndex=selected;
                handler.post(()->{
                if(closed){done.finish(false,"Плеер закрыт");return;}
                if(selectedIndex<0){done.finish(false,"Трек недоступен");return;}
                knownTracks.clear();for(Track track:tracks)knownTracks.put(track.id,track);
                preparing=true;int generation=++preparationGeneration;
                handler.postDelayed(()->{if(!closed&&preparing&&generation==preparationGeneration){preparing=false;player.pause();DiagnosticLog.add(context,"Connect transfer timed out, kept paused");}},35000);
                queueSource=payload.optString("queue_source","Family Music Connect");player.pause();player.setShuffleModeEnabled(false);player.setMediaItems(items,selectedIndex,Math.round(Math.max(0,payload.optDouble("position_seconds"))*1000));
                restoreOrder(payload,items);player.setShuffleModeEnabled(payload.optBoolean("shuffle"));
                player.setRepeatMode(payload.optString("repeat_mode").equals("one")?Player.REPEAT_MODE_ONE:payload.optString("repeat_mode").equals("all")?Player.REPEAT_MODE_ALL:Player.REPEAT_MODE_OFF);
                if(payload.has("volume"))player.setVolume((float)Math.max(0,Math.min(1,payload.optDouble("volume",1))));
                player.prepare();previousId=current;
                awaitReady(current,Math.round(Math.max(0,payload.optDouble("position_seconds"))*1000),()->{
                    if(!payload.optBoolean("transfer_prepare")){preparing=false;player.setPlayWhenReady(payload.optBoolean("playing"));}
                    done.finish(true,"");
                },done);
            });}
            @Override public void failure(String message){handler.post(()->done.finish(false,message));}
        });
    }

    private void restoreOrder(JSONObject payload,List<MediaItem> items){
        JSONArray order=payload.optJSONArray("shuffle_order");List<String> ids=new ArrayList<>(),requested=new ArrayList<>();
        for(MediaItem item:items)ids.add(item.mediaId);if(order!=null)for(int i=0;i<order.length();i++)requested.add(order.optString(i));
        int[] permutation=ConnectQueueOrder.permutation(ids,requested);
        if(order!=null)player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(permutation,0));
        history.clear();JSONArray past=payload.optJSONArray("playback_history");if(past!=null)for(int i=0;i<past.length();i++)history.add(past.optString(i));
    }

    private MediaItem mediaItem(Track track,OfflineStore offline,String requested,boolean local){
        Bundle extras=new Bundle();if(track.replayGainDb!=null)extras.putDouble("replay_gain_db",track.replayGainDb);extras.putLong("server_duration_ms",Math.round(track.durationSeconds*1000));
        MediaMetadata.Builder metadata=new MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setExtras(extras);
        String cover=offline.cover(track.id);if(!cover.isEmpty())metadata.setArtworkUri(Uri.parse(cover));else if(!track.coverUrl.isEmpty())metadata.setArtworkUri(Uri.parse(ApiClient.ORIGIN+track.coverUrl));
        String quality=QualitySelector.playback(track.aac192Ready,track.aac96Ready,requested);
        String streamPath=track.streamPath(quality);
        if(!track.remote&&quality.equals("original")&&!requested.equals("original"))streamPath+="&prepare="+Uri.encode(requested.equals("compact")?"aac_96":"aac_192");
        MediaItem.Builder item=new MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata.build()).setUri(local?offline.uri(track.id):Uri.parse(ApiClient.ORIGIN+streamPath));
        if(!local)item.setCustomCacheKey(PlaybackCache.key(track.id,quality));return item.build();
    }

    private void awaitReady(String id,long position,Runnable ready,ConnectManager.Completion done){
        long deadline=SystemClock.elapsedRealtime()+12000;
        handler.post(new Runnable(){@Override public void run(){
            if(closed){done.finish(false,"Плеер закрыт");return;}
            MediaItem item=player.getCurrentMediaItem();long expected=player.getDuration()>0?Math.min(position,player.getDuration()):position;
            if(item!=null&&id.equals(item.mediaId)&&player.getPlaybackState()==Player.STATE_READY&&Math.abs(player.getCurrentPosition()-expected)<1500){ready.run();return;}
            if(player.getPlayerError()!=null||SystemClock.elapsedRealtime()>=deadline){player.pause();preparing=false;done.finish(false,"Плеер не подтвердил команду");return;}
            handler.postDelayed(this,50);
        }});
    }

    private long durationMs(){
        if(player.getDuration()>0)return player.getDuration();
        MediaItem item=player.getCurrentMediaItem();
        return item!=null&&item.mediaMetadata.extras!=null?Math.max(0,item.mediaMetadata.extras.getLong("server_duration_ms",0)):0;
    }
}
