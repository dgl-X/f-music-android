package org.familymusic.client;

import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.FlagSet;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Timeline;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Routes MediaSession/notification/Bluetooth actions without touching local audio. */
final class ConnectSessionPlayer extends ForwardingPlayer {
    interface Remote {
        ConnectCommandRouter.Destination systemDestination();
        JSONObject remoteState();
        long remotePosition();
        MediaItem remoteMediaItem();
        void sessionStateListener(Runnable listener);
        void systemCommand(String action,JSONObject payload);
        void previous();
    }
    private final Remote connect;
    private final Set<Player.Listener> listeners=new LinkedHashSet<>();
    private final java.util.Map<Player.Listener,Player.Listener> bridges=new java.util.HashMap<>();
    private ConnectSessionTimeline remoteTimeline;
    private String timelineQueue="",timelineOrder="";
    private MediaItem timelineItem;
    private long timelineDuration=-1;
    private boolean previouslyRemote;
    private MediaItem previouslyShown;

    ConnectSessionPlayer(Player player,Remote connect){
        super(player);this.connect=connect;
        connect.sessionStateListener(this::stateChanged);
        refreshTimeline();previouslyRemote=remoteActive();previouslyShown=getCurrentMediaItem();
    }
    private boolean remoteActive(){return connect.systemDestination()==ConnectCommandRouter.Destination.REMOTE;}
    private JSONObject remoteState(){return connect.remoteState();}
    private boolean remotePlaying(){return remoteState().optBoolean("playing");}
    private boolean remoteAdvancing(){return ConnectPlaybackStatus.advances(remoteState());}
    private boolean remoteBuffering(){return remoteState().optBoolean("buffering");}
    private long remoteDuration(){return Math.round(Math.max(0,remoteState().optDouble("duration_seconds",0))*1000);}
    private void refreshTimeline(){
        if(!remoteActive())return;
        MediaItem item=connect.remoteMediaItem();long duration=remoteDuration();
        org.json.JSONArray queue=remoteState().optJSONArray("queue"),order=remoteState().optJSONArray("shuffle_order");
        String nextQueue=queue==null?"":queue.toString(),nextOrder=order==null?"":order.toString();
        if(remoteTimeline==null||!java.util.Objects.equals(item,timelineItem)||duration!=timelineDuration||!nextQueue.equals(timelineQueue)||!nextOrder.equals(timelineOrder)){
            remoteTimeline=new ConnectSessionTimeline(queue,order,item,duration);
            timelineQueue=nextQueue;timelineOrder=nextOrder;timelineItem=item;timelineDuration=duration;
        }
    }
    private void route(String action,JSONObject payload,Runnable local){
        ConnectCommandRouter.dispatch(connect.systemDestination(),action,local,command->connect.systemCommand(command,payload));
    }
    private void seek(String action,long position,Runnable local){
        JSONObject payload=new JSONObject();try{payload.put("position_seconds",Math.max(0,position)/1000.0);}catch(Exception ignored){}
        route(action,payload,local);
    }
    @Override public void play(){route("play",new JSONObject(),()->super.play());}
    @Override public void pause(){route("pause",new JSONObject(),()->super.pause());}
    @Override public void setPlayWhenReady(boolean playing){route(playing?"play":"pause",new JSONObject(),()->super.setPlayWhenReady(playing));}
    @Override public void stop(){route("pause",new JSONObject(),()->super.stop());}
    @Override public void seekToNextMediaItem(){route("next",new JSONObject(),()->super.seekToNextMediaItem());}
    @Override public void seekToNext(){route("next",new JSONObject(),()->super.seekToNext());}
    @Override public void seekToPreviousMediaItem(){route("previous",new JSONObject(),connect::previous);}
    @Override public void seekToPrevious(){route("previous",new JSONObject(),connect::previous);}
    @Override public void seekTo(long position){seek("seek",position,()->super.seekTo(position));}
    @Override public void seekTo(int index,long position){seek("seek",position,()->super.seekTo(index,position));}
    @Override public void seekBack(){seek("seek",Math.max(0,getCurrentPosition()-getSeekBackIncrement()),()->super.seekBack());}
    @Override public void seekForward(){seek("seek",getCurrentPosition()+getSeekForwardIncrement(),()->super.seekForward());}
    @Override public boolean getPlayWhenReady(){return remoteActive()?remotePlaying():super.getPlayWhenReady();}
    @Override public boolean isPlaying(){return remoteActive()?remoteAdvancing():super.isPlaying();}
    @Override public int getPlaybackState(){return remoteActive()?remoteBuffering()?Player.STATE_BUFFERING:Player.STATE_READY:super.getPlaybackState();}
    @Override public long getCurrentPosition(){return remoteActive()?connect.remotePosition():super.getCurrentPosition();}
    @Override public long getContentPosition(){return remoteActive()?connect.remotePosition():super.getContentPosition();}
    @Override public long getBufferedPosition(){return remoteActive()?connect.remotePosition():super.getBufferedPosition();}
    @Override public long getContentBufferedPosition(){return remoteActive()?connect.remotePosition():super.getContentBufferedPosition();}
    @Override public long getDuration(){return remoteActive()?remoteDuration():super.getDuration();}
    @Override public long getContentDuration(){return remoteActive()?remoteDuration():super.getContentDuration();}
    @Override public Timeline getCurrentTimeline(){return remoteActive()?remoteTimeline==null?Timeline.EMPTY:remoteTimeline:super.getCurrentTimeline();}
    @Override public MediaItem getCurrentMediaItem(){return remoteActive()?connect.remoteMediaItem():super.getCurrentMediaItem();}
    @Override public MediaMetadata getMediaMetadata(){MediaItem item=getCurrentMediaItem();return remoteActive()?item==null?MediaMetadata.EMPTY:item.mediaMetadata:super.getMediaMetadata();}
    @Override public int getCurrentMediaItemIndex(){return remoteActive()?remoteTimeline==null?0:remoteTimeline.currentIndex:super.getCurrentMediaItemIndex();}
    @Override public int getCurrentWindowIndex(){return getCurrentMediaItemIndex();}
    @Override public int getCurrentPeriodIndex(){return remoteActive()?getCurrentMediaItemIndex():super.getCurrentPeriodIndex();}
    @Override public int getMediaItemCount(){return remoteActive()?getCurrentTimeline().getWindowCount():super.getMediaItemCount();}
    @Override public MediaItem getMediaItemAt(int index){return remoteActive()?remoteTimeline.item(index):super.getMediaItemAt(index);}
    @Override public boolean getShuffleModeEnabled(){return remoteActive()?remoteState().optBoolean("shuffle"):super.getShuffleModeEnabled();}
    @Override public int getRepeatMode(){return remoteActive()?remoteState().optString("repeat_mode").equals("one")?REPEAT_MODE_ONE:remoteState().optString("repeat_mode").equals("all")?REPEAT_MODE_ALL:REPEAT_MODE_OFF:super.getRepeatMode();}
    @Override public int getNextMediaItemIndex(){return remoteActive()?getCurrentTimeline().isEmpty()?C.INDEX_UNSET:getCurrentTimeline().getNextWindowIndex(getCurrentMediaItemIndex(),getRepeatMode()==REPEAT_MODE_ONE?REPEAT_MODE_OFF:getRepeatMode(),getShuffleModeEnabled()):super.getNextMediaItemIndex();}
    @Override public int getPreviousMediaItemIndex(){return remoteActive()?getCurrentTimeline().isEmpty()?C.INDEX_UNSET:getCurrentTimeline().getPreviousWindowIndex(getCurrentMediaItemIndex(),getRepeatMode()==REPEAT_MODE_ONE?REPEAT_MODE_OFF:getRepeatMode(),getShuffleModeEnabled()):super.getPreviousMediaItemIndex();}
    @Override public int getNextWindowIndex(){return getNextMediaItemIndex();}
    @Override public int getPreviousWindowIndex(){return getPreviousMediaItemIndex();}
    @Override public boolean hasNextMediaItem(){return getNextMediaItemIndex()!=C.INDEX_UNSET;}
    @Override public boolean hasPreviousMediaItem(){return getPreviousMediaItemIndex()!=C.INDEX_UNSET;}
    @Override public androidx.media3.common.PlaybackException getPlayerError(){return remoteActive()?null:super.getPlayerError();}
    @Override public boolean isLoading(){return remoteActive()?remoteBuffering():super.isLoading();}
    @Override public long getTotalBufferedDuration(){return remoteActive()?0:super.getTotalBufferedDuration();}
    @Override public int getBufferedPercentage(){return remoteActive()?remoteDuration()>0?(int)Math.min(100,connect.remotePosition()*100/remoteDuration()):0:super.getBufferedPercentage();}
    @Override public boolean isCurrentMediaItemSeekable(){return remoteActive()?getCurrentMediaItem()!=null:super.isCurrentMediaItemSeekable();}
    @Override public boolean isCurrentWindowSeekable(){return isCurrentMediaItemSeekable();}
    @Override public Player.Commands getAvailableCommands(){
        if(!remoteActive())return super.getAvailableCommands();
        return new Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_TIMELINE).add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM).add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build();
    }
    @Override public boolean isCommandAvailable(int command){return getAvailableCommands().contains(command);}
    @Override public void addListener(Player.Listener listener){
        if(!listeners.add(listener))return;
        Player.Listener bridge=new ConnectSessionListener(listener,this,this::remoteActive);bridges.put(listener,bridge);super.addListener(bridge);
    }
    @Override public void removeListener(Player.Listener listener){listeners.remove(listener);Player.Listener bridge=bridges.remove(listener);if(bridge!=null)super.removeListener(bridge);}
    private void stateChanged(){
        Timeline oldTimeline=getCurrentTimeline();refreshTimeline();
        boolean remote=remoteActive(),modeChanged=remote!=previouslyRemote;
        // Local ExoPlayer already emits its own events. Echoing server acknowledgements
        // here would trigger another state save, PUT, SSE hint, and so on forever.
        if(!remote&&!modeChanged)return;
        MediaItem item=getCurrentMediaItem();boolean itemChanged=modeChanged||!java.util.Objects.equals(previouslyShown==null?null:previouslyShown.mediaId,item==null?null:item.mediaId);
        boolean metadataChanged=modeChanged||!java.util.Objects.equals(previouslyShown==null?null:previouslyShown.mediaMetadata,item==null?null:item.mediaMetadata);
        boolean timelineChanged=modeChanged||oldTimeline!=getCurrentTimeline();
        previouslyRemote=remote;previouslyShown=item;
        FlagSet.Builder flags=new FlagSet.Builder().add(Player.EVENT_PLAY_WHEN_READY_CHANGED).add(Player.EVENT_IS_PLAYING_CHANGED).add(Player.EVENT_PLAYBACK_STATE_CHANGED).add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED);
        if(itemChanged)flags.add(Player.EVENT_MEDIA_ITEM_TRANSITION);
        if(metadataChanged)flags.add(Player.EVENT_MEDIA_METADATA_CHANGED);
        if(timelineChanged)flags.add(Player.EVENT_TIMELINE_CHANGED);
        Player.Events events=new Player.Events(flags.build());
        for(Player.Listener listener:new LinkedHashSet<>(listeners)){
            if(timelineChanged)listener.onTimelineChanged(getCurrentTimeline(),Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
            if(itemChanged)listener.onMediaItemTransition(item,Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
            if(metadataChanged)listener.onMediaMetadataChanged(getMediaMetadata());
            listener.onPlayWhenReadyChanged(getPlayWhenReady(),Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE);
            listener.onIsPlayingChanged(isPlaying());listener.onPlaybackStateChanged(getPlaybackState());listener.onAvailableCommandsChanged(getAvailableCommands());listener.onEvents(this,events);
        }
    }
}
