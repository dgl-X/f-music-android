package org.familymusic.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConnectQueueOrder {
    private ConnectQueueOrder() {}

    static List<Track> restore(List<String> requestedIds, List<Track> resolvedTracks) {
        Map<String, Track> byId = new HashMap<>();
        for (Track track : resolvedTracks) {
            if (track != null && track.id != null && !track.id.isEmpty()) byId.putIfAbsent(track.id, track);
        }

        List<Track> ordered = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String id : requestedIds) {
            Track track = byId.get(id);
            if (track != null && added.add(id)) ordered.add(track);
        }
        return ordered;
    }
}
