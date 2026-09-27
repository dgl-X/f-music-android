package org.familymusic.client;

import java.util.ArrayList;
import java.util.List;

final class NetworkLogGuard {
    private String activeNetwork = "";
    private String activeState = "";

    synchronized boolean update(String network, String state) {
        String nextNetwork = network == null ? "" : network;
        String nextState = state == null ? "" : state;
        if (nextNetwork.equals(activeNetwork) && nextState.equals(activeState)) return false;
        activeNetwork = nextNetwork;
        activeState = nextState;
        return true;
    }

    synchronized boolean lost(String network) {
        if (!activeNetwork.equals(network == null ? "" : network)) return false;
        activeNetwork = "";
        activeState = "";
        return true;
    }

    static String describe(boolean wifi, boolean cellular, boolean ethernet, boolean vpn, boolean validated) {
        List<String> transports = new ArrayList<>();
        if (wifi) transports.add("wifi");
        if (cellular) transports.add("cellular");
        if (ethernet) transports.add("ethernet");
        if (vpn) transports.add("vpn");
        if (transports.isEmpty()) transports.add("other");
        return "transport=" + String.join("+", transports) + " validated=" + validated + " vpn=" + vpn;
    }
}
