package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

final class ConnectManager {
    interface Completion { void finish(boolean success, String error); }
    interface Listener {
        void command(String action, JSONObject payload, Completion completion);
        void state(JSONObject state);
        void enabled(boolean value);
        JSONObject snapshot();
    }

    private final ConnectApi api;
    private final java.util.function.Supplier<ConnectEventStream> eventFactory;
    private final Context context;
    private volatile long lastContactAt, lastCommandLatencyMs, commandStartedAt;
    private volatile String connection = "connected";
    private final Listener listener;
    private Listener observer;
    private JSONObject latestState;
    void observe(Listener value){observer=value;if(value!=null){value.enabled(enabled);if(latestState!=null)value.state(latestState);}}
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String deviceId;
    private final String deviceName;
    private volatile boolean enabled;
    private volatile boolean running;
    private volatile boolean polling;
    private volatile boolean commandInFlight;
    private volatile long lastCommandId;
    private volatile long pendingCommandId;
    private volatile boolean pendingCommandSuccess;
    private volatile String pendingCommandError = "";
    private JSONObject pendingCommandState;
    private final ConnectStateRevision stateRevision = new ConnectStateRevision();
    private volatile long playbackEpoch;
    private volatile String activeDeviceId = "";
    private JSONObject queuedState;
    private long queuedStateEpoch;
    private boolean stateUpdateInFlight;
    private volatile int generation;
    private ConnectEventStream events;
    private boolean pollAgain, statusInFlight;
    private String sessionOrigin = "", sessionCookie = "";
    private final Runnable pollTask = new Runnable() { @Override public void run() { poll(); } };
    private final Runnable statusTask = () -> { if(running){if(enabled)refreshStatus();else start();} };

    ConnectManager(Context context, ApiClient api, Listener listener) {
        this(context,api,listener,ConnectEventStream::new);
    }

    ConnectManager(Context context, ConnectApi api, Listener listener, java.util.function.Supplier<ConnectEventStream> eventFactory) {
        this.api = api; this.listener = listener;this.context=context.getApplicationContext();
        this.eventFactory=eventFactory;
        preferences = context.getSharedPreferences("connect", Context.MODE_PRIVATE);
        String saved = preferences.getString("device_id", "");
        if (saved.isEmpty()) { saved = "android_" + UUID.randomUUID().toString().replace("-", ""); preferences.edit().putString("device_id", saved).apply(); }
        deviceId = saved;
        deviceName = preferences.getString("device_name", Build.MANUFACTURER + " " + Build.MODEL);
        lastCommandId = preferences.getLong("last_command_id", 0);
        pendingCommandId = preferences.getLong("pending_command_id", 0);
        pendingCommandSuccess = preferences.getBoolean("pending_command_success", false);
        pendingCommandError = preferences.getString("pending_command_error", "");
        try { pendingCommandState = new JSONObject(preferences.getString("pending_command_state", "{}")); } catch (Exception ignored) {}
    }

    String deviceId() { return deviceId; }
    boolean enabled() { return enabled; }
    String connection() { return connection; }
    JSONObject diagnostics() {
        JSONObject result=new JSONObject();
        try { result.put("enabled",enabled).put("connection",connection)
            .put("remote",enabled&&!activeDeviceId.isEmpty()&&!deviceId.equals(activeDeviceId))
            .put("playback_epoch",playbackEpoch).put("last_command_id",lastCommandId)
            .put("command_pending",commandInFlight).put("last_command_latency_ms",lastCommandLatencyMs)
            .put("last_contact_age_ms",lastContactAt==0?-1:Math.max(0,SystemClock.elapsedRealtime()-lastContactAt));
        } catch(Exception ignored) {}
        return result;
    }
    private void contact() { lastContactAt=SystemClock.elapsedRealtime();connection="connected"; }
    private void connectionLost() { if(!connection.equals("reconnecting"))DiagnosticLog.add(context,"Connect connection lost");connection="reconnecting"; }

    synchronized void start() {
        stop();
        running=true;
        sessionOrigin=api.origin();sessionCookie=api.cookie();
        int currentGeneration = generation;
        api.get("/connect/status", new ApiClient.Callback() {
            @Override public void success(JSONObject json) {
                if (currentGeneration != generation) return;
                enabled = json.optBoolean("enabled"); handler.post(() -> {listener.enabled(enabled);if(observer!=null)observer.enabled(enabled);});
                if (enabled) register(currentGeneration,json);else suspend();
            }
            @Override public void failure(String message) { if(currentGeneration!=generation)return;suspend(); }
        });
    }

    synchronized void stop() { generation++;running=false;handler.removeCallbacks(pollTask);handler.removeCallbacks(statusTask);if(events!=null){events.close();events=null;} enabled = false; polling = false; commandInFlight = false;pollAgain=false;statusInFlight=false;stateUpdateInFlight=false;queuedState=null; }

    private synchronized void suspend(){
        boolean resume=running;stop();running=resume;queuedState=null;latestState=null;pendingCommandId=0;pendingCommandState=null;
        preferences.edit().remove("pending_command_id").remove("pending_command_success").remove("pending_command_error").remove("pending_command_state").apply();
        handler.post(()->{JSONObject empty=new JSONObject();listener.state(empty);listener.enabled(false);if(observer!=null){observer.state(empty);observer.enabled(false);}});
        if(running)handler.postDelayed(statusTask,15000);
    }

    private void register(int currentGeneration, JSONObject status) {
        if (!enabled || currentGeneration != generation) return;
        JSONObject body = new JSONObject(), capabilities = new JSONObject();
        try {
            capabilities.put("playback", true).put("playback_ready", true).put("transfer_prepare", true).put("seek", true).put("volume", true).put("shuffle", true).put("repeat", true);
            body.put("id", deviceId).put("name", deviceName).put("client_type", "android").put("capabilities", capabilities);
        } catch (Exception ignored) {}
        api.post("/connect/devices", body, new ApiClient.Callback() {
            @Override public void success(JSONObject json) { handler.post(()->{if(currentGeneration!=generation)return;deliverState(json.optJSONObject("state"));configureEvents(status);requestPoll();scheduleStatus();}); }
            @Override public void failure(String message) { if(currentGeneration==generation)handler.postDelayed(()->register(currentGeneration,status), 5000); }
        });
    }

    private void scheduleStatus(){handler.removeCallbacks(statusTask);if(running)handler.postDelayed(statusTask,15000);}

    private boolean sameSession(){return sessionOrigin.equals(api.origin())&&sessionCookie.equals(api.cookie())&&!sessionCookie.isEmpty();}

    private void refreshStatus(){
        if(!sameSession()){stop();return;}
        if(statusInFlight)return;
        statusInFlight=true;int currentGeneration=generation;
        api.get("/connect/status",new ApiClient.Callback(){
            @Override public void success(JSONObject json){handler.post(()->{if(currentGeneration!=generation)return;statusInFlight=false;if(!json.optBoolean("enabled")){suspend();return;}configureEvents(json);scheduleStatus();});}
            @Override public void failure(String message){handler.post(()->{if(currentGeneration!=generation)return;statusInFlight=false;scheduleStatus();});}
            @Override public void failure(int status,String message){if(status==401){handler.post(()->{if(currentGeneration==generation)suspend();});}else failure(message);}
        });
    }

    private void configureEvents(JSONObject status){
        JSONArray transports=status.optJSONArray("transports");boolean supported=false;
        if(transports!=null)for(int i=0;i<transports.length();i++)supported|="sse".equals(transports.optString(i));
        supported&="/api/v1/connect/events".equals(status.optString("events_url"));
        if(!supported){if(events!=null){events.close();events=null;}requestPoll();return;}
        if(events!=null)return;
        int currentGeneration=generation;
        events=eventFactory.get();
        events.start(sessionOrigin,sessionCookie,deviceId,()->running&&currentGeneration==generation&&sameSession(),new ConnectEventStream.Listener(){
            @Override public void hint(){handler.post(()->{if(currentGeneration==generation&&enabled)requestPoll();});}
            @Override public void disconnected(){handler.post(()->{if(currentGeneration==generation&&enabled)requestPoll();});}
        });
    }

    private void requestPoll(){
        if(!enabled)return;
        if(polling){pollAgain=true;return;}
        handler.removeCallbacks(pollTask);handler.post(pollTask);
    }

    private void schedulePoll(long delay){
        handler.removeCallbacks(pollTask);
        if(pollAgain){pollAgain=false;handler.post(pollTask);}
        else if(events==null||!events.healthy())handler.postDelayed(pollTask,delay);
    }

    private void poll() {
        if (!enabled || polling) return;
        if(!sameSession()){stop();return;}
        if (commandInFlight) { handler.postDelayed(pollTask, 500); return; }
        polling = true;
        int currentGeneration=generation;
        api.get("/connect/commands?device_id=" + deviceId + "&after=" + lastCommandId, new ApiClient.Callback() {
            @Override public void success(JSONObject json) {
                handler.post(()->{
                if(currentGeneration!=generation)return;
                polling = false; deliverState(json.optJSONObject("state"));
                JSONArray items=json.optJSONArray("items");if(items!=null)for(int i=0;i<items.length()&&!commandInFlight;i++){JSONObject command=items.optJSONObject(i);if(command!=null&&command.optLong("id")>lastCommandId)dispatch(command);}
                schedulePoll(1500);
                });
            }
            @Override public void failure(String message) { handler.post(()->{if(currentGeneration!=generation)return;connectionLost();polling=false;pollAgain=false;handler.postDelayed(pollTask,4000);}); }
            @Override public void failure(int status,String message) { if(currentGeneration!=generation)return;if(status==401||status==404&&message.contains("Connect")){handler.post(()->{if(currentGeneration==generation)suspend();});return;}failure(message); }
        });
    }

    private void dispatch(JSONObject command) {
        long id=command.optLong("id");
        commandStartedAt=SystemClock.elapsedRealtime();DiagnosticLog.add(context,"Connect command received id="+id+" epoch="+playbackEpoch);
        if(id==pendingCommandId){commandInFlight=true;ack(id,pendingCommandSuccess,pendingCommandError);return;}
        commandInFlight = true;
        JSONObject payload=command.optJSONObject("payload") == null ? new JSONObject() : command.optJSONObject("payload");
        String action=command.optString("action");long commandEpoch=payload.optLong("playback_epoch",playbackEpoch);
        if((action.equals("deactivate")&&commandEpoch<playbackEpoch)||(!action.equals("transfer")&&!action.equals("deactivate")&&commandEpoch!=playbackEpoch)){rememberAndAck(id,false,"Устаревшая эпоха команды");return;}
        int currentGeneration=generation;
        handler.post(()->{if(!enabled||currentGeneration!=generation)return;listener.command(action,payload,(success,error)->{if(enabled&&currentGeneration==generation)rememberAndAck(id,success,error);});});
    }

    private void rememberAndAck(long id,boolean success,String error){
        lastCommandLatencyMs=Math.max(0,SystemClock.elapsedRealtime()-commandStartedAt);
        DiagnosticLog.add(context,"Connect command completed id="+id+" success="+success+" elapsed_ms="+lastCommandLatencyMs);
        pendingCommandId=id;pendingCommandSuccess=success;pendingCommandError=error==null?"":error;
        pendingCommandState=success?listener.snapshot():null;
        preferences.edit().putString("pending_command_state",pendingCommandState==null?"{}":pendingCommandState.toString()).apply();
        preferences.edit().putLong("pending_command_id",id).putBoolean("pending_command_success",success).putString("pending_command_error",pendingCommandError).apply();
        ack(id,success,pendingCommandError);
    }

    private void ack(long id, boolean success, String error) {
        int currentGeneration=generation;
        JSONObject body=new JSONObject(),result=new JSONObject();try{body.put("device_id",deviceId).put("success",success);if(error!=null&&!error.isEmpty())result.put("error",error);if(pendingCommandState!=null)result.put("state",pendingCommandState);body.put("result",result);}catch(Exception ignored){}
        api.post("/connect/commands/"+id+"/ack",body,new ApiClient.Callback(){
            @Override public void success(JSONObject json){handler.post(()->{if(currentGeneration!=generation)return;lastCommandId=Math.max(lastCommandId,id);pendingCommandId=0;preferences.edit().putLong("last_command_id",lastCommandId).remove("pending_command_id").remove("pending_command_success").remove("pending_command_error").apply();commandInFlight=false;deliverState(json.optJSONObject("state"));requestPoll();});}
            @Override public void failure(String message){handler.post(()->{if(currentGeneration!=generation)return;connectionLost();DiagnosticLog.add(context,"Connect ACK retry id="+id);commandInFlight=false;handler.removeCallbacks(pollTask);handler.postDelayed(pollTask,1500);});}
        });
    }

    void devices(ApiClient.Callback callback) { api.get("/connect/devices",callback); }
    void transfer(String targetDeviceId, JSONObject state, ApiClient.Callback callback) { JSONObject body=new JSONObject();try{body.put("source_device_id",deviceId).put("target_device_id",targetDeviceId).put("state",state);}catch(Exception ignored){}api.post("/connect/transfer",body,callback); }
    void command(String action, JSONObject payload) { JSONObject body=new JSONObject();try{body.put("source_device_id",deviceId).put("action",action).put("payload",payload==null?new JSONObject():payload);}catch(Exception ignored){}api.post("/connect/commands",body,new ApiClient.Callback(){@Override public void success(JSONObject json){}@Override public void failure(String message){}}); }
    synchronized void updateState(JSONObject state) {
        if(!enabled)return;
        try{queuedState=new JSONObject(state.toString());queuedStateEpoch=playbackEpoch;}catch(Exception ignored){return;}
        sendQueuedState();
    }

    private synchronized void sendQueuedState(){
        if(!enabled||stateUpdateInFlight||queuedState==null)return;
        if(activeDeviceId.isEmpty()&&!queuedState.optBoolean("playing")){queuedState=null;return;}
        if(queuedStateEpoch!=playbackEpoch||!activeDeviceId.isEmpty()&&!deviceId.equals(activeDeviceId)){queuedState=null;return;}
        JSONObject state=queuedState;queuedState=null;stateUpdateInFlight=true;JSONObject body=new JSONObject();
        int currentGeneration=generation;
        try{java.util.Iterator<String> keys=state.keys();while(keys.hasNext()){String key=keys.next();body.put(key,state.opt(key));}body.put("device_id",deviceId).put("playback_epoch",playbackEpoch);}catch(Exception ignored){stateUpdateInFlight=false;return;}
        api.put("/connect/state",body,new ApiClient.Callback(){
            @Override public void success(JSONObject json){if(currentGeneration==generation){deliverState(json);finishStateUpdate();}}
            @Override public void failure(String message){if(currentGeneration==generation)finishStateUpdate();}
            @Override public void failure(int status,String message){if(currentGeneration!=generation)return;if(status==404&&message.contains("Connect"))suspend();else finishStateUpdate();}
        });
    }

    private synchronized void finishStateUpdate(){stateUpdateInFlight=false;sendQueuedState();}

    private void deliverState(JSONObject state){
        contact();
        if(state==null||!stateRevision.accept(state.optLong("revision",-1)))return;
        long nextEpoch=Math.max(0,state.optLong("playback_epoch",playbackEpoch));
        String nextOwner=state.optString("active_device_id","");
        if(nextEpoch!=playbackEpoch||!nextOwner.equals(activeDeviceId))DiagnosticLog.add(context,"Connect owner changed epoch="+nextEpoch+" owner="+(nextOwner.isEmpty()?"none":deviceId.equals(nextOwner)?"local":"remote"));
        playbackEpoch=nextEpoch;activeDeviceId=nextOwner;latestState=state;
        int currentGeneration=generation;
        handler.post(()->{if(currentGeneration!=generation)return;listener.state(state);if(observer!=null)observer.state(state);});
    }
}
