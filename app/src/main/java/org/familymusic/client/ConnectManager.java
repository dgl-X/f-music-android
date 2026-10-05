package org.familymusic.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

final class ConnectManager {
    interface Completion { void finish(boolean success, String error); }
    interface Listener {
        void command(String action, JSONObject payload, Completion completion);
        void state(JSONObject state);
        void enabled(boolean value);
    }

    private final ApiClient api;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String deviceId;
    private final String deviceName;
    private volatile boolean enabled;
    private volatile boolean polling;
    private volatile boolean commandInFlight;
    private volatile long lastCommandId;
    private volatile long pendingCommandId;
    private volatile boolean pendingCommandSuccess;
    private volatile String pendingCommandError = "";
    private final ConnectStateRevision stateRevision = new ConnectStateRevision();
    private volatile long playbackEpoch;
    private JSONObject queuedState;
    private boolean stateUpdateInFlight;
    private int generation;
    private final Runnable pollTask = new Runnable() { @Override public void run() { poll(); } };

    ConnectManager(Context context, ApiClient api, Listener listener) {
        this.api = api; this.listener = listener;
        preferences = context.getSharedPreferences("connect", Context.MODE_PRIVATE);
        String saved = preferences.getString("device_id", "");
        if (saved.isEmpty()) { saved = "android_" + UUID.randomUUID().toString().replace("-", ""); preferences.edit().putString("device_id", saved).apply(); }
        deviceId = saved;
        deviceName = preferences.getString("device_name", Build.MANUFACTURER + " " + Build.MODEL);
        lastCommandId = preferences.getLong("last_command_id", 0);
        pendingCommandId = preferences.getLong("pending_command_id", 0);
        pendingCommandSuccess = preferences.getBoolean("pending_command_success", false);
        pendingCommandError = preferences.getString("pending_command_error", "");
    }

    String deviceId() { return deviceId; }
    boolean enabled() { return enabled; }

    void start() {
        stop();
        int currentGeneration = generation;
        api.get("/connect/status", new ApiClient.Callback() {
            @Override public void success(JSONObject json) {
                if (currentGeneration != generation) return;
                enabled = json.optBoolean("enabled"); handler.post(() -> listener.enabled(enabled));
                if (enabled) register(currentGeneration);
            }
            @Override public void failure(String message) { if(currentGeneration!=generation)return;enabled = false; handler.post(() -> listener.enabled(false)); }
        });
    }

    void stop() { generation++;handler.removeCallbacks(pollTask); enabled = false; polling = false; commandInFlight = false; }

    private void register(int currentGeneration) {
        if (!enabled || currentGeneration != generation) return;
        JSONObject body = new JSONObject(), capabilities = new JSONObject();
        try {
            capabilities.put("playback", true).put("playback_ready", true).put("seek", true).put("volume", true).put("shuffle", true).put("repeat", true);
            body.put("id", deviceId).put("name", deviceName).put("client_type", "android").put("capabilities", capabilities);
        } catch (Exception ignored) {}
        api.post("/connect/devices", body, new ApiClient.Callback() {
            @Override public void success(JSONObject json) { if(currentGeneration!=generation)return;deliverState(json.optJSONObject("state"));handler.post(pollTask); }
            @Override public void failure(String message) { if(currentGeneration==generation)handler.postDelayed(()->register(currentGeneration), 5000); }
        });
    }

    private void poll() {
        if (!enabled || polling) return;
        if (commandInFlight) { handler.postDelayed(pollTask, 500); return; }
        polling = true;
        api.get("/connect/commands?device_id=" + deviceId + "&after=" + lastCommandId, new ApiClient.Callback() {
            @Override public void success(JSONObject json) {
                polling = false; deliverState(json.optJSONObject("state"));
                JSONArray items=json.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject command=items.optJSONObject(i);if(command!=null)dispatch(command);}
                handler.postDelayed(pollTask,1500);
            }
            @Override public void failure(String message) { polling=false;handler.postDelayed(pollTask,4000); }
            @Override public void failure(int status,String message) { polling=false;if(status==404&&message.contains("Connect")){stop();handler.post(()->listener.enabled(false));return;}handler.postDelayed(pollTask,4000); }
        });
    }

    private void dispatch(JSONObject command) {
        long id=command.optLong("id");
        if(id==pendingCommandId){commandInFlight=true;ack(id,pendingCommandSuccess,pendingCommandError);return;}
        commandInFlight = true;
        JSONObject payload=command.optJSONObject("payload") == null ? new JSONObject() : command.optJSONObject("payload");
        String action=command.optString("action");long commandEpoch=payload.optLong("playback_epoch",playbackEpoch);
        if(!action.equals("transfer")&&!action.equals("deactivate")&&commandEpoch!=playbackEpoch){rememberAndAck(id,false,"Устаревшая эпоха команды");return;}
        handler.post(()->listener.command(action,payload,(success,error)->rememberAndAck(id,success,error)));
    }

    private void rememberAndAck(long id,boolean success,String error){
        pendingCommandId=id;pendingCommandSuccess=success;pendingCommandError=error==null?"":error;
        preferences.edit().putLong("pending_command_id",id).putBoolean("pending_command_success",success).putString("pending_command_error",pendingCommandError).apply();
        ack(id,success,pendingCommandError);
    }

    private void ack(long id, boolean success, String error) {
        JSONObject body=new JSONObject(),result=new JSONObject();try{body.put("device_id",deviceId).put("success",success);if(error!=null&&!error.isEmpty())result.put("error",error);body.put("result",result);}catch(Exception ignored){}
        api.post("/connect/commands/"+id+"/ack",body,new ApiClient.Callback(){
            @Override public void success(JSONObject json){lastCommandId=Math.max(lastCommandId,id);pendingCommandId=0;preferences.edit().putLong("last_command_id",lastCommandId).remove("pending_command_id").remove("pending_command_success").remove("pending_command_error").apply();commandInFlight=false;deliverState(json.optJSONObject("state"));handler.removeCallbacks(pollTask);handler.post(pollTask);}
            @Override public void failure(String message){commandInFlight=false;handler.removeCallbacks(pollTask);handler.postDelayed(pollTask,1500);}
        });
    }

    void devices(ApiClient.Callback callback) { api.get("/connect/devices",callback); }
    void transfer(String targetDeviceId, JSONObject state, ApiClient.Callback callback) { JSONObject body=new JSONObject();try{body.put("source_device_id",deviceId).put("target_device_id",targetDeviceId).put("state",state);}catch(Exception ignored){}api.post("/connect/transfer",body,callback); }
    void command(String action, JSONObject payload) { JSONObject body=new JSONObject();try{body.put("source_device_id",deviceId).put("action",action).put("payload",payload==null?new JSONObject():payload);}catch(Exception ignored){}api.post("/connect/commands",body,new ApiClient.Callback(){@Override public void success(JSONObject json){}@Override public void failure(String message){}}); }
    synchronized void updateState(JSONObject state) {
        if(!enabled)return;
        try{queuedState=new JSONObject(state.toString());}catch(Exception ignored){return;}
        sendQueuedState();
    }

    private synchronized void sendQueuedState(){
        if(!enabled||stateUpdateInFlight||queuedState==null)return;
        JSONObject state=queuedState;queuedState=null;stateUpdateInFlight=true;JSONObject body=new JSONObject();
        try{java.util.Iterator<String> keys=state.keys();while(keys.hasNext()){String key=keys.next();body.put(key,state.opt(key));}body.put("device_id",deviceId).put("playback_epoch",playbackEpoch);}catch(Exception ignored){stateUpdateInFlight=false;return;}
        api.put("/connect/state",body,new ApiClient.Callback(){
            @Override public void success(JSONObject json){deliverState(json);finishStateUpdate();}
            @Override public void failure(String message){finishStateUpdate();}
            @Override public void failure(int status,String message){if(status==404&&message.contains("Connect")){stop();handler.post(()->listener.enabled(false));}finishStateUpdate();}
        });
    }

    private synchronized void finishStateUpdate(){stateUpdateInFlight=false;sendQueuedState();}

    private void deliverState(JSONObject state){
        if(state==null||!stateRevision.accept(state.optLong("revision",-1)))return;playbackEpoch=Math.max(0,state.optLong("playback_epoch",playbackEpoch));handler.post(()->listener.state(state));
    }
}
