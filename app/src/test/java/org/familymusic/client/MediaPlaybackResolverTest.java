package org.familymusic.client;

import static org.junit.Assert.*;
import android.app.Application;
import android.net.Uri;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class MediaPlaybackResolverTest {
    private static final String TRACK="11111111-1111-4111-8111-111111111111";
    @Test public void readyIsPersistedButHttpErrorsNeverFallBackToLegacy()throws Exception {
        for(int code:new int[]{200,401,403,500,404})run(code,false);
        run(404,true);
    }
    private void run(int code,boolean legacy)throws Exception {
        ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));
        String origin=ApiClient.ORIGIN;
        final int[] requests={0};
        Thread responder=new Thread(()->{try(Socket socket=server.accept()){
            java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            requests[0]++;
            String body=code==200?"{\"status\":\"ready\",\"variant\":\"original\",\"size_bytes\":900,\"stream_url\":\"/api/v1/tracks/"+TRACK+"/stream?quality=original&variant=original\"}":"{\"error\":\""+(legacy?"Маршрут не найден":"Трек не найден")+"\"}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 "+code+" Test\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
        }catch(Exception error){throw new RuntimeException(error);}});
        responder.setDaemon(true);responder.start();ApiClient.ORIGIN="http://127.0.0.1:"+server.getLocalPort();
        try {
            MediaPlaybackResolver resolver=new MediaPlaybackResolver(RuntimeEnvironment.getApplication());
            resolver.clear();
            Uri uri=Uri.parse(ApiClient.ORIGIN+"/api/v1/tracks/"+TRACK+"/stream?quality=original");
            if(code==200){MediaSelection selected=resolver.resolve(uri,"key","",true);assertEquals(900,selected.size);assertEquals(selected.key,resolver.saved("key").key);}
            else if(legacy)assertNull(resolver.resolve(uri,"key","",true));
            else assertThrows(java.io.IOException.class,()->resolver.resolve(uri,"key","",true));
            assertEquals(1,requests[0]);
        }finally{ApiClient.ORIGIN=origin;server.close();responder.join(1000);}
    }
}
