package org.familymusic.client;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Metadata-only view of a remote queue. Never changes the local audio player. */
final class ConnectRemoteQueue {
    static final int PAGE_SIZE = 50;
    static final class Row {
        final String id, title, artist;
        final boolean current;
        Row(String id, String title, String artist, boolean current) {
            this.id=id;this.title=title;this.artist=artist;this.current=current;
        }
    }
    static final class Page {
        final int offset, total, currentIndex;
        final List<Row> rows;
        final boolean loading;
        final String error;
        Page(int offset,int total,int currentIndex,List<Row> rows,boolean loading,String error) {
            this.offset=offset;this.total=total;this.currentIndex=currentIndex;
            this.rows=Collections.unmodifiableList(rows);this.loading=loading;this.error=error;
        }
    }
    private final ConnectApi api;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Page> changed;
    private final List<String> ids=new ArrayList<>();
    private final String currentId;
    private final Map<String,Track> cache=new LinkedHashMap<String,Track>(64,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Track> entry){return size()>PAGE_SIZE*3;}
    };
    private int generation;
    private boolean closed;

    ConnectRemoteQueue(ConnectApi api,JSONObject state,Consumer<Runnable> dispatch,Consumer<Page> changed) {
        this.api=api;this.dispatch=dispatch;this.changed=changed;
        currentId=state.optString("track_id","");
        java.util.Set<String> unique=new java.util.LinkedHashSet<>();
        JSONArray queue=state.optJSONArray("queue");
        if(queue!=null)for(int i=0;i<queue.length();i++){
            String id=queue.optString(i);if(!id.isEmpty()&&!id.equals("null"))unique.add(id);
        }
        if(!currentId.isEmpty()&&!currentId.equals("null"))unique.add(currentId);
        ids.addAll(unique);
    }

    int currentOffset(){int index=ids.indexOf(currentId);return Math.max(0,index)/PAGE_SIZE*PAGE_SIZE;}
    void close(){closed=true;generation++;cache.clear();}

    void load(int requestedOffset) {
        if(closed)return;
        int offset=Math.max(0,Math.min(requestedOffset,Math.max(0,ids.size()-1)))/PAGE_SIZE*PAGE_SIZE;
        int token=++generation;
        List<String> visible=new ArrayList<>(ids.subList(offset,Math.min(ids.size(),offset+PAGE_SIZE)));
        JSONArray missing=new JSONArray();
        for(String id:visible)if(!cache.containsKey(id))missing.put(id);
        emit(offset,visible,missing.length()>0,"");
        if(missing.length()==0)return;
        JSONObject body=new JSONObject();try{body.put("ids",missing);}catch(Exception ignored){}
        api.post("/tracks/resolve",body,new ApiClient.Callback(){
            @Override public void success(JSONObject result){dispatch.accept(()->{
                if(closed||token!=generation)return;
                JSONArray items=result.optJSONArray("items");
                if(items!=null)for(int i=0;i<items.length();i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item!=null&&visible.contains(item.optString("id")))cache.put(item.optString("id"),new Track(item));
                }
                emit(offset,visible,false,"");
            });}
            @Override public void failure(String message){dispatch.accept(()->{
                if(!closed&&token==generation)emit(offset,visible,false,"Не удалось загрузить названия. Нажми «Обновить».");
            });}
        });
    }

    private void emit(int offset,List<String> visible,boolean loading,String error) {
        List<Row> rows=new ArrayList<>();
        for(String id:visible){
            Track track=cache.get(id);
            String title=track!=null?track.title:loading?"Загружаем название…":error.isEmpty()?"Трек недоступен":"Название не загружено";
            rows.add(new Row(id,title,track==null?"":track.artist,id.equals(currentId)));
        }
        changed.accept(new Page(offset,ids.size(),ids.indexOf(currentId),rows,loading,error));
    }
}
