package org.familymusic.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ConnectRemoteQueueTest {
    private static final class Api implements ConnectApi {
        final List<ApiClient.Callback> callbacks=new ArrayList<>();
        final List<JSONObject> requests=new ArrayList<>();
        public String origin(){return "https://music.test";}
        public String cookie(){return "test";}
        public void get(String path,ApiClient.Callback callback){fail("Unexpected GET");}
        public void put(String path,JSONObject body,ApiClient.Callback callback){fail("Queue viewer must not publish state");}
        public void post(String path,JSONObject body,ApiClient.Callback callback){assertEquals("/tracks/resolve",path);requests.add(body);callbacks.add(callback);}
    }
    private JSONObject state(int count,int current)throws Exception {
        JSONArray ids=new JSONArray();for(int i=0;i<count;i++)ids.put("id-"+i);
        return new JSONObject().put("queue",ids).put("track_id",current<0?"":"id-"+current);
    }
    private JSONObject tracks(String...ids)throws Exception {
        JSONArray items=new JSONArray();for(String id:ids)items.put(new JSONObject().put("id",id).put("title","Title "+id).put("artist","Artist"));
        return new JSONObject().put("items",items);
    }
    @Test public void requestsOnlyVisiblePageAndPreservesQueueOrder()throws Exception {
        Api api=new Api();List<ConnectRemoteQueue.Page> pages=new ArrayList<>();
        ConnectRemoteQueue model=new ConnectRemoteQueue(api,state(2264,2034),Runnable::run,pages::add);
        assertEquals(2000,model.currentOffset());model.load(model.currentOffset());
        assertEquals(50,api.requests.get(0).getJSONArray("ids").length());
        assertEquals("id-2000",api.requests.get(0).getJSONArray("ids").getString(0));
        assertEquals("Загружаем название…",pages.get(0).rows.get(0).title);
        api.callbacks.get(0).success(tracks("id-2034","id-2000","foreign"));
        ConnectRemoteQueue.Page ready=pages.get(pages.size()-1);
        assertEquals(2264,ready.total);assertEquals(2034,ready.currentIndex);
        assertEquals("Title id-2000",ready.rows.get(0).title);assertTrue(ready.rows.get(34).current);
        assertEquals("Трек недоступен",ready.rows.get(1).title);assertFalse(ready.loading);
    }
    @Test public void stalePageResponseAndClosedDialogCannotReplaceVisibleRows()throws Exception {
        Api api=new Api();List<ConnectRemoteQueue.Page> pages=new ArrayList<>();
        ConnectRemoteQueue model=new ConnectRemoteQueue(api,state(120,1),Runnable::run,pages::add);
        model.load(0);model.load(50);int count=pages.size();
        api.callbacks.get(0).success(tracks("id-0"));assertEquals(count,pages.size());
        api.callbacks.get(1).success(tracks("id-50"));assertEquals("Title id-50",pages.get(pages.size()-1).rows.get(0).title);
        model.load(100);model.close();count=pages.size();
        api.callbacks.get(2).success(tracks("id-100"));model.load(0);assertEquals(count,pages.size());
    }
    @Test public void failedResolutionCanBeRetriedWithoutMutatingQueue()throws Exception {
        Api api=new Api();List<ConnectRemoteQueue.Page> pages=new ArrayList<>();JSONObject original=state(2,0);String before=original.toString();
        ConnectRemoteQueue model=new ConnectRemoteQueue(api,original,Runnable::run,pages::add);
        model.load(0);api.callbacks.get(0).failure("offline");
        assertFalse(pages.get(1).error.isEmpty());assertEquals("Название не загружено",pages.get(1).rows.get(0).title);
        model.load(0);api.callbacks.get(1).success(tracks("id-0","id-1"));assertEquals("Title id-1",pages.get(pages.size()-1).rows.get(1).title);
        int calls=api.requests.size();model.load(0);assertEquals(calls,api.requests.size());assertEquals(before,original.toString());
    }
    @Test public void boundedCacheDoesNotKeepTheWholeLibrary()throws Exception {
        Api api=new Api();List<ConnectRemoteQueue.Page> pages=new ArrayList<>();
        ConnectRemoteQueue model=new ConnectRemoteQueue(api,state(300,0),Runnable::run,pages::add);
        for(int page=0;page<5;page++){
            model.load(page*50);String[] ids=new String[50];for(int i=0;i<50;i++)ids[i]="id-"+(page*50+i);
            api.callbacks.get(page).success(tracks(ids));
        }
        int calls=api.requests.size();model.load(200);assertEquals(calls,api.requests.size());
        model.load(0);assertEquals(calls+1,api.requests.size());assertEquals(50,api.requests.get(calls).getJSONArray("ids").length());
    }
    @Test public void emptyQueueAndOffsetsAreSafe()throws Exception {
        Api api=new Api();List<ConnectRemoteQueue.Page> pages=new ArrayList<>();
        ConnectRemoteQueue empty=new ConnectRemoteQueue(api,state(0,-1),Runnable::run,pages::add);
        empty.load(-50);assertEquals(0,pages.get(0).total);assertTrue(api.requests.isEmpty());
        ConnectRemoteQueue queue=new ConnectRemoteQueue(api,state(51,0),Runnable::run,pages::add);
        queue.load(10000);assertEquals(50,pages.get(1).offset);assertEquals(1,pages.get(1).rows.size());
    }
}
