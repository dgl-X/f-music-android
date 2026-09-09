package org.familymusic.client;

import org.json.JSONObject;

final class Track {
    final String id;
    String title;
    String artist;
    String album;
    String genre;
    Integer year;
    String coverUrl;
    final String filename;
    final String mimeType;
    boolean liked;
    boolean aac192Ready;
    boolean aac96Ready;
    int playCount;
    double durationSeconds;
    Double replayGainDb;
    final boolean remote;
    final String remoteRef;
    final String streamUrl;
    final String sourceLabel;
    final String availability;
    final boolean streamAvailable;

    Track(JSONObject json) {
        id = json.optString("id");
        title = json.optString("title", "Без названия");
        artist = json.optString("artist", "Неизвестный исполнитель");
        album = json.optString("album", "");
        genre = json.optString("genre", "");
        year = json.isNull("year") || !json.has("year") ? null : json.optInt("year");
        coverUrl = json.optString("cover_url", "");
        filename = json.optString("filename", "");
        mimeType = json.optString("mime_type", "audio/*");
        liked = json.optBoolean("liked", false);
        aac192Ready = json.optBoolean("aac_192_ready", false);
        aac96Ready = json.optBoolean("aac_96_ready", false);
        playCount = json.optInt("play_count", 0);
        durationSeconds = json.optDouble("duration_seconds", 0);
        replayGainDb = json.isNull("replay_gain_db") || !json.has("replay_gain_db") ? null : json.optDouble("replay_gain_db");
        remote = json.optBoolean("remote", false);
        remoteRef = json.optString("remote_ref", "");
        streamUrl = json.optString("stream_url", "");
        sourceLabel = json.optString("source_label", "");
        availability = json.optString("availability", "online");
        streamAvailable = json.optBoolean("stream_available", true);
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id); json.put("title", title); json.put("artist", artist);
            json.put("album", album); json.put("cover_url", coverUrl); json.put("filename", filename);
            json.put("mime_type", mimeType); json.put("liked", liked); json.put("genre", genre);
            json.put("aac_192_ready", aac192Ready); json.put("aac_96_ready", aac96Ready);
            json.put("play_count", playCount);
            json.put("duration_seconds", durationSeconds);
            if (replayGainDb == null) json.put("replay_gain_db", JSONObject.NULL); else json.put("replay_gain_db", replayGainDb);
            if (year == null) json.put("year", JSONObject.NULL); else json.put("year", year);
            json.put("remote", remote); json.put("remote_ref", remoteRef); json.put("stream_url", streamUrl);
            json.put("source_label", sourceLabel); json.put("availability", availability); json.put("stream_available", streamAvailable);
        } catch (Exception ignored) {}
        return json;
    }

    String streamPath(String quality) {
        if (remote && !streamUrl.isEmpty()) return streamUrl;
        return "/api/v1/tracks/" + id + "/stream?quality=" + android.net.Uri.encode(quality);
    }
}
