package org.familymusic.client;

import static org.junit.Assert.*;
import android.app.Application;
import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class ConnectSessionPlayerTest {
    private ExoPlayer local;
    private Remote remote;
    private ConnectSessionPlayer view;
    private static MediaItem item(String id,String title){return new MediaItem.Builder().setMediaId(id)
            .setUri("https://music.test/unused.mp3")
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist("Artist "+id)
                    .setArtworkUri(Uri.parse("https://music.test/"+id+".jpg")).build()).build();}
    private static final class Remote implements ConnectSessionPlayer.Remote {
        boolean active=true;Runnable changed=()->{};JSONObject state=new JSONObject();
        final java.util.Map<String,MediaItem> metadata=new java.util.HashMap<>();
        final List<String> commands=new ArrayList<>();
        Remote()throws Exception{state.put("queue",new JSONArray().put("remote-a").put("remote-b")).put("track_id","remote-a").put("duration_seconds",180).put("position_seconds",12).put("playing",true).put("is_playing",true);metadata.put("remote-a",item("remote-a","Remote A"));metadata.put("remote-b",item("remote-b","Remote B"));}
        public ConnectCommandRouter.Destination systemDestination(){return active?ConnectCommandRouter.Destination.REMOTE:ConnectCommandRouter.Destination.LOCAL;}
        public JSONObject remoteState(){return state;}
        public long remotePosition(){return Math.round(state.optDouble("position_seconds",0)*1000);}
        public MediaItem remoteMediaItem(){String id=state.optString("track_id");return metadata.getOrDefault(id,item(id,"Загрузка трека…"));}
        public void sessionStateListener(Runnable listener){changed=listener;}
        public void systemCommand(String action,JSONObject payload){commands.add(action+":"+payload.optLong("position_seconds",-1));}
        public void previous(){commands.add("local-previous");}
    }
    @Before public void setup()throws Exception {
        local=new ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build();
        local.setMediaItems(java.util.Arrays.asList(item("local-a","Local A"),item("local-b","Local B")));
        remote=new Remote();view=new ConnectSessionPlayer(local,remote);
    }
    @After public void cleanup(){local.release();}
    @Test public void notificationMetadataAndTimelineDescribeTheSameRemoteTrack(){
        assertEquals("Remote A",view.getMediaMetadata().title.toString());
        assertEquals("remote-a",view.getCurrentMediaItem().mediaId);
        Timeline.Window window=view.getCurrentTimeline().getWindow(view.getCurrentMediaItemIndex(),new Timeline.Window());
        assertEquals(view.getCurrentMediaItem().mediaMetadata,window.mediaItem.mediaMetadata);
        assertEquals(Uri.parse("https://music.test/remote-a.jpg"),view.getMediaMetadata().artworkUri);
        assertEquals(12000,view.getCurrentPosition());assertEquals(180000,view.getDuration());
        assertEquals("local-a",local.getCurrentMediaItem().mediaId);assertFalse(local.getPlayWhenReady());
    }
    @Test public void trackChangesPublishMetadataAndTransitionEvents()throws Exception {
        List<String> titles=new ArrayList<>(),ids=new ArrayList<>();final boolean[] event={false};
        view.addListener(new Player.Listener(){
            @Override public void onMediaMetadataChanged(MediaMetadata value){titles.add(value.title.toString());}
            @Override public void onMediaItemTransition(MediaItem value,int reason){ids.add(value.mediaId);}
            @Override public void onEvents(Player player,Player.Events events){event[0]=events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)&&events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION);assertSame(view,player);}
        });
        remote.state.put("track_id","remote-b");remote.changed.run();
        assertEquals(java.util.Collections.singletonList("Remote B"),titles);assertEquals(java.util.Collections.singletonList("remote-b"),ids);assertTrue(event[0]);
        assertEquals("local-a",local.getCurrentMediaItem().mediaId);assertEquals(1,view.getCurrentMediaItemIndex());
    }
    @Test public void metadataArrivalDoesNotReplayTheTransitionAndStaleMetadataCannotReplaceCurrent()throws Exception {
        remote.state.put("track_id","unknown");remote.changed.run();
        assertEquals("Загрузка трека…",view.getMediaMetadata().title.toString());
        final int[] transitions={0},metadata={0};
        view.addListener(new Player.Listener(){public void onMediaItemTransition(MediaItem value,int reason){transitions[0]++;}public void onMediaMetadataChanged(MediaMetadata value){metadata[0]++;}});
        remote.metadata.put("remote-a",item("remote-a","Late old metadata"));remote.changed.run();
        assertEquals("unknown",view.getCurrentMediaItem().mediaId);assertEquals(0,metadata[0]);
        remote.metadata.put("unknown",item("unknown","Resolved"));remote.changed.run();
        assertEquals("Resolved",view.getMediaMetadata().title.toString());assertEquals(1,metadata[0]);assertEquals(0,transitions[0]);
    }
    @Test public void switchingBackRestoresLocalMetadataWithoutReplacingItsQueue(){
        final List<String> titles=new ArrayList<>();view.addListener(new Player.Listener(){public void onMediaMetadataChanged(MediaMetadata value){titles.add(value.title.toString());}});
        local.seekTo(1,1000);remote.active=false;remote.changed.run();
        assertEquals("local-b",view.getCurrentMediaItem().mediaId);assertEquals("Local B",view.getMediaMetadata().title.toString());
        assertEquals(2,local.getMediaItemCount());assertEquals(1000,view.getCurrentPosition());assertTrue(titles.contains("Local B"));assertFalse(local.getPlayWhenReady());
    }
    @Test public void systemCommandsAreRemoteAndQueueMutationIsNotAdvertised(){
        view.play();view.seekTo(31000);view.seekToNextMediaItem();view.pause();
        assertEquals(java.util.Arrays.asList("play:-1","seek:31","next:-1","pause:-1"),remote.commands);
        assertFalse(view.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS));
        assertFalse(view.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM));
        assertEquals("local-a",local.getCurrentMediaItem().mediaId);assertFalse(local.getPlayWhenReady());
    }
    @Test public void largeQueueAndPositionUpdatesDoNotRebuildTheSessionTimeline()throws Exception {
        JSONArray ids=new JSONArray();for(int i=0;i<2264;i++)ids.put("track-"+i);
        remote.state.put("queue",ids).put("track_id","track-2000");remote.changed.run();
        Timeline timeline=view.getCurrentTimeline();assertEquals(2264,timeline.getWindowCount());assertEquals(2000,view.getCurrentMediaItemIndex());assertEquals(2001,view.getNextMediaItemIndex());
        remote.state.put("position_seconds",50);remote.changed.run();assertSame(timeline,view.getCurrentTimeline());
        assertEquals(50000,view.getCurrentPosition());assertEquals(2,local.getMediaItemCount());
    }
    @Test public void localCallbacksCannotOverwriteRemoteStateAndAreForwardedAfterReturn(){
        final List<String> ids=new ArrayList<>();final int[] events={0};
        ConnectSessionListener bridge=new ConnectSessionListener(new Player.Listener(){
            public void onMediaItemTransition(MediaItem item,int reason){ids.add(item.mediaId);}
            public void onEvents(Player player,Player.Events value){events[0]++;assertSame(view,player);}
        },view,()->remote.active);
        Player.Events event=new Player.Events(new androidx.media3.common.FlagSet.Builder().add(Player.EVENT_MEDIA_ITEM_TRANSITION).build());
        bridge.onMediaItemTransition(item("local-a","Local"),0);bridge.onEvents(local,event);assertTrue(ids.isEmpty());assertEquals(0,events[0]);
        remote.active=false;bridge.onMediaItemTransition(item("local-a","Local"),0);bridge.onEvents(local,event);assertEquals(1,ids.size());assertEquals(1,events[0]);
    }
    @Test public void listenerBridgeCoversEveryMedia3Callback()throws Exception {
        for(java.lang.reflect.Method method:Player.Listener.class.getDeclaredMethods())
            assertNotNull(ConnectSessionListener.class.getDeclaredMethod(method.getName(),method.getParameterTypes()));
    }
    @Test public void localServerEchoDoesNotEmitPlayerEventsOrResaveState(){
        remote.active=false;remote.changed.run();
        final int[] events={0};view.addListener(new Player.Listener(){public void onEvents(Player player,Player.Events value){events[0]++;}});
        for(int i=0;i<10;i++)remote.changed.run();
        assertEquals(0,events[0]);
    }
    @Test public void realMediaControllerReceivesRemoteMetadataInsteadOfLocalQueue()throws Exception {
        androidx.media3.session.MediaSession session=new androidx.media3.session.MediaSession.Builder(RuntimeEnvironment.getApplication(),view)
                .setBitmapLoader(new androidx.media3.common.util.BitmapLoader(){
                    public boolean supportsMimeType(String mime){return true;}
                    public com.google.common.util.concurrent.ListenableFuture<android.graphics.Bitmap> decodeBitmap(byte[] bytes){return com.google.common.util.concurrent.Futures.immediateFailedFuture(new java.io.IOException("No artwork in test"));}
                    public com.google.common.util.concurrent.ListenableFuture<android.graphics.Bitmap> loadBitmap(Uri uri){return decodeBitmap(new byte[0]);}
                }).build();
        androidx.media3.session.MediaController controller=null;
        try {
            com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> future=new androidx.media3.session.MediaController.Builder(RuntimeEnvironment.getApplication(),session.getToken()).buildAsync();
            for(int i=0;i<100&&!future.isDone();i++){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
            controller=future.get(1,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("remote-a",controller.getCurrentMediaItem().mediaId);assertEquals("Remote A",controller.getMediaMetadata().title.toString());
            controller.seekToNextMediaItem();
            for(int i=0;i<30&&remote.commands.isEmpty();i++){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertEquals(java.util.Collections.singletonList("next:-1"),remote.commands);
            remote.state.put("track_id","remote-b");remote.changed.run();
            for(int i=0;i<30&&!"remote-b".equals(controller.getCurrentMediaItem().mediaId);i++){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertEquals("remote-b",controller.getCurrentMediaItem().mediaId);assertEquals("Remote B",controller.getMediaMetadata().title.toString());
            assertEquals(Uri.parse("https://music.test/remote-b.jpg"),controller.getMediaMetadata().artworkUri);
            assertEquals("local-a",local.getCurrentMediaItem().mediaId);assertEquals(2,local.getMediaItemCount());
            remote.active=false;remote.changed.run();
            for(int i=0;i<30&&!"local-a".equals(controller.getCurrentMediaItem().mediaId);i++){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertEquals("local-a",controller.getCurrentMediaItem().mediaId);assertEquals("Local A",controller.getMediaMetadata().title.toString());
        }finally{if(controller!=null)controller.release();session.release();}
    }
}
