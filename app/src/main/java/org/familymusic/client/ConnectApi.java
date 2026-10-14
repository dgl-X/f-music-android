package org.familymusic.client;

import org.json.JSONObject;

/** Narrow HTTP boundary for deterministic Connect transport/lifecycle tests. */
interface ConnectApi {
    String origin();
    String cookie();
    void get(String path, ApiClient.Callback callback);
    void post(String path, JSONObject body, ApiClient.Callback callback);
    void put(String path, JSONObject body, ApiClient.Callback callback);
}
