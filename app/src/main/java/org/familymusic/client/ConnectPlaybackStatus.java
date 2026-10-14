package org.familymusic.client;

import org.json.JSONObject;

/** Playback intent controls buttons; actual playback controls the clock. */
final class ConnectPlaybackStatus {
    private ConnectPlaybackStatus() {}
    static String status(boolean intent,boolean actual,boolean buffering,boolean preparing){
        return preparing?"preparing":intent&&actual?"playing":intent&&buffering?"buffering":intent?"waiting":"paused";
    }
    static boolean advances(JSONObject state){
        return state!=null&&state.optBoolean("playing")&&(state.has("is_playing")?state.optBoolean("is_playing"):!state.optBoolean("buffering"));
    }
}
