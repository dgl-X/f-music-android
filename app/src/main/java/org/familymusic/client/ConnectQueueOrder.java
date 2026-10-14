package org.familymusic.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConnectQueueOrder {
    private ConnectQueueOrder() {}

    static int[] permutation(List<String> ids,List<String> requested){
        Map<String,Integer> positions=new HashMap<>();for(int i=0;i<ids.size();i++)positions.put(ids.get(i),i);
        List<Integer> ordered=new ArrayList<>();Set<Integer> seen=new HashSet<>();
        for(String id:requested){Integer index=positions.get(id);if(index!=null&&seen.add(index))ordered.add(index);}
        for(int i=0;i<ids.size();i++)if(seen.add(i))ordered.add(i);
        int[] result=new int[ordered.size()];for(int i=0;i<result.length;i++)result[i]=ordered.get(i);return result;
    }

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
