package org.familymusic.client;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only remote queue for MediaSession; never installed in the audio player. */
final class ConnectSessionTimeline extends Timeline {
    final List<String> ids=new ArrayList<>();
    private final Map<Object,Integer> indices=new HashMap<>();
    private final MediaItem current;
    private final long durationUs;
    final int currentIndex;
    private final List<Integer> shuffled=new ArrayList<>();
    ConnectSessionTimeline(JSONArray queue,JSONArray order,MediaItem current,long durationMs){
        this.current=current;durationUs=durationMs>0?durationMs*1000:C.TIME_UNSET;
        java.util.Set<String> unique=new java.util.LinkedHashSet<>();
        if(queue!=null)for(int i=0;i<queue.length();i++){String id=queue.optString(i);if(!id.isEmpty())unique.add(id);}
        if(current!=null)unique.add(current.mediaId);
        ids.addAll(unique);
        currentIndex=current==null?0:Math.max(0,ids.indexOf(current.mediaId));
        for(int i=0;i<ids.size();i++)indices.put(ids.get(i),i);
        java.util.Set<Integer> ordered=new java.util.LinkedHashSet<>();
        if(order!=null)for(int i=0;i<order.length();i++){Integer index=indices.get(order.optString(i));if(index!=null)ordered.add(index);}
        for(int i=0;i<ids.size();i++)ordered.add(i);
        shuffled.addAll(ordered);
    }
    MediaItem item(int index){return current!=null&&ids.get(index).equals(current.mediaId)?current:new MediaItem.Builder().setMediaId(ids.get(index)).build();}
    @Override public int getWindowCount(){return ids.size();}
    @Override public int getPeriodCount(){return ids.size();}
    @Override public Window getWindow(int index,Window window,long projectionUs){
        return window.set(ids.get(index),item(index),null,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,true,false,null,0,index==currentIndex?durationUs:C.TIME_UNSET,index,index,0);
    }
    @Override public Period getPeriod(int index,Period period,boolean setIds){return period.set(setIds?ids.get(index):null,setIds?ids.get(index):null,index,index==currentIndex?durationUs:C.TIME_UNSET,0);}
    @Override public Object getUidOfPeriod(int index){return ids.get(index);}
    @Override public int getIndexOfPeriod(Object uid){return indices.getOrDefault(uid,C.INDEX_UNSET);}
    @Override public int getFirstWindowIndex(boolean shuffle){return ids.isEmpty()?C.INDEX_UNSET:shuffle?shuffled.get(0):0;}
    @Override public int getLastWindowIndex(boolean shuffle){return ids.isEmpty()?C.INDEX_UNSET:shuffle?shuffled.get(shuffled.size()-1):ids.size()-1;}
    @Override public int getNextWindowIndex(int index,int repeat,boolean shuffle){return adjacent(index,repeat,shuffle,1);}
    @Override public int getPreviousWindowIndex(int index,int repeat,boolean shuffle){return adjacent(index,repeat,shuffle,-1);}
    private int adjacent(int index,int repeat,boolean shuffle,int direction){
        if(repeat==1)return index;
        int position=(shuffle?shuffled.indexOf(index):index)+direction;
        if(position<0||position>=ids.size()){if(repeat!=2)return C.INDEX_UNSET;position=(position+ids.size())%ids.size();}
        return shuffle?shuffled.get(position):position;
    }
}
