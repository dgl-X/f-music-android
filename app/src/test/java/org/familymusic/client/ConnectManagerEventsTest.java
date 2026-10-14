package org.familymusic.client;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class ConnectManagerEventsTest {
    private final class Api implements ConnectApi {
        int polls, writes, acks, statuses;
        String cookie="music_session=test";
        boolean supported=true, enabled=true, holdPoll, failAck;
        JSONObject command;
        ApiClient.Callback held;
        public String origin(){return "https://music.test";}
        public String cookie(){return cookie;}
        public void get(String path,ApiClient.Callback callback){
            try {
                if(path.equals("/connect/status")){
                    statuses++;JSONObject status=new JSONObject().put("enabled",enabled);
                    if(supported)status.put("transports",new JSONArray().put("sse").put("polling")).put("events_url","/api/v1/connect/events");
                    callback.success(status);
                }else{
                    polls++;
                    if(holdPoll){held=callback;return;}
                    callback.success(response());
                }
            }catch(Exception error){throw new AssertionError(error);}
        }
        JSONObject response()throws Exception{return new JSONObject().put("state",new JSONObject().put("revision",1)).put("items",command==null?new JSONArray():new JSONArray().put(command));}
        public void post(String path,JSONObject body,ApiClient.Callback callback){
            if(path.endsWith("/ack")){acks++;if(failAck){callback.failure("offline");return;}command=null;}
            callback.success(new JSONObject());
        }
        public void put(String path,JSONObject body,ApiClient.Callback callback){writes++;callback.success(new JSONObject());}
    }
    private static final class Events extends ConnectEventStream {
        Listener listener;BooleanSupplier valid;boolean live,closed;
        @Override synchronized void start(String origin,String cookie,String id,BooleanSupplier valid,Listener listener){this.listener=listener;this.valid=valid;}
        @Override boolean healthy(){return live&&!closed;}
        @Override public void close(){closed=true;live=false;}
        void hint(){if(valid.getAsBoolean()){live=true;listener.hint();}}
        void lost(){live=false;listener.disconnected();}
    }
    private Api api;private Events events;private ConnectManager manager;private int executed, states;
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void advance(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Before public void setup(){
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("connect",0).edit().clear().commit();
        api=new Api();events=new Events();executed=0;states=0;
        manager=new ConnectManager(context,api,new ConnectManager.Listener(){
            public void command(String action,JSONObject payload,ConnectManager.Completion completion){executed++;completion.finish(true,"");}
            public void state(JSONObject state){states++;}
            public void enabled(boolean enabled){}
            public JSONObject snapshot(){return new JSONObject();}
        },()->events);
    }
    @After public void cleanup(){manager.stop();idle();}
    private void start(){manager.start();idle();events.hint();idle();}
    @Test public void healthyHintsReplaceFastPollingAndNeverPublishState(){
        start();int count=api.polls;advance(5000);assertEquals(count,api.polls);
        events.hint();events.hint();events.hint();idle();assertEquals(count+1,api.polls);assertEquals(0,api.writes);
        advance(15000);assertTrue(api.statuses>=2);assertEquals(0,api.writes);
    }
    @Test public void hintsDuringRequestCoalesceIntoOneFollowUp()throws Exception{
        start();api.holdPoll=true;events.hint();idle();int count=api.polls;
        for(int i=0;i<10;i++)events.hint();idle();assertEquals(count,api.polls);
        api.holdPoll=false;api.held.success(api.response());idle();assertEquals(count+1,api.polls);
    }
    @Test public void lostAckRetriesWithoutReexecutingCommand()throws Exception{
        start();api.command=new JSONObject().put("id",7).put("action","next");api.failAck=true;
        events.hint();idle();assertEquals(1,executed);assertEquals(1,api.acks);
        api.failAck=false;advance(1500);assertEquals(1,executed);assertEquals(2,api.acks);
    }
    @Test public void disconnectionFallsBackAndReconnectSuppressesTimer(){
        start();events.lost();idle();int count=api.polls;advance(3100);assertTrue(api.polls>count);
        events.hint();idle();count=api.polls;advance(4000);assertEquals(count,api.polls);
    }
    @Test public void capabilityAndPersonalDisableCloseStream(){
        start();api.supported=false;advance(15000);assertTrue(events.closed);int count=api.polls;advance(2000);assertTrue(api.polls>count);
        api.enabled=false;advance(15000);assertFalse(manager.enabled());
    }
    @Test public void stopDiscardsLateHintsAndCookieChangesStopTransport(){
        start();manager.stop();int count=api.polls;events.listener.hint();idle();assertEquals(count,api.polls);
        manager.start();idle();api.cookie="";advance(15000);assertFalse(manager.enabled());assertTrue(events.closed);
    }
    @Test public void olderServerUsesPollingWithoutStream(){
        api.supported=false;manager.start();idle();assertNull(events.listener);int count=api.polls;advance(3100);assertTrue(api.polls>count);
    }
}
