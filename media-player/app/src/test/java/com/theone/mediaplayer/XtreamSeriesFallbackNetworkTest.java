package com.theone.mediaplayer;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Constructor;
import java.util.*;

public class XtreamSeriesFallbackNetworkTest {
  @Test public void emptyFullCatalogFallsBackToTwoCategories() throws Exception {
    try(ServerSocket socket=new ServerSocket(0,10,InetAddress.getLoopbackAddress())){
      Thread worker=new Thread(()->{
        while(!socket.isClosed()){
          try(Socket conn=socket.accept()){
            BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8));
            String request=reader.readLine(),line;
            if(request==null)continue;
            while((line=reader.readLine())!=null && !line.isEmpty()){}
            String body;
            if(request.contains("get_series_categories")){
              body="[{\"category_id\":\"1\"},{\"category_id\":\"2\"}]";
            }else if(request.contains("category_id=1")){
              body="[{\"series_id\":101,\"name\":\"Marshall\"}]";
            }else if(request.contains("category_id=2")){
              body="[{\"series_id\":102,\"name\":\"VisionQuest\"}]";
            }else{
              body="[]";
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            String head="HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+
                "\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n";
            OutputStream out=conn.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
          }catch(Exception ignored){}
        }
      },"catalog-test-server");
      worker.setDaemon(true);worker.start();
      Constructor<XtreamConfig> ctor=XtreamConfig.class.getDeclaredConstructor(String.class,String.class,String.class);
      ctor.setAccessible(true);
      XtreamConfig config=ctor.newInstance("http://127.0.0.1:"+socket.getLocalPort(),"user","test");
      List<String> names=Collections.synchronizedList(new ArrayList<>());
      boolean complete=config.streamSeriesCatalog(10,(batch,second)->{
        for(int i=0;i<batch.length();i++){
          JSONObject item=batch.optJSONObject(i);
          if(item!=null)names.add(item.optString("name"));
        }
      });
      assertTrue("Category fallback should recover from empty full library",complete);
      assertEquals(Arrays.asList("Marshall","VisionQuest"),names);
    }
  }
}
