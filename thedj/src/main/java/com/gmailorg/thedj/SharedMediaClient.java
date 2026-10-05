package com.gmailorg.thedj;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Android transport for the desktop engine's Shared Media routes. Secrets stay native. */
final class SharedMediaClient {
    private static final String ROOT="https://rubenvanaggelen.com/the-one-remote-api/";
    private final Activity activity;
    private final SharedPreferences prefs;
    private final String deviceId;
    private String token="";
    private long expires=0;
    private boolean nameDialog=false;
    private static final class AccessError extends IOException {
        AccessError(String message){super(message);}
    }

    SharedMediaClient(Activity activity) {
        this.activity=activity;
        prefs=activity.getSharedPreferences("dj_shared_media",Context.MODE_PRIVATE);
        String id=prefs.getString("device_id","");
        if(id.isEmpty()) { id=UUID.randomUUID().toString();prefs.edit().putString("device_id",id).apply(); }
        deviceId=id;
    }
    private JSONObject post(String file,String action,JSONObject body) throws Exception {
        HttpURLConnection c=connect(ROOT+file+"?action="+action);
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try {
            try(java.io.OutputStream out=c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code=c.getResponseCode();
            InputStream in=code<400?c.getInputStream():c.getErrorStream();
            String raw=read(in);
            if(code<200||code>=300) throw new IOException("Shared Media-server reageert met "+code);
            return new JSONObject(raw);
        } finally { c.disconnect(); }
    }
    private static String read(InputStream in) throws IOException {
        if(in==null)return "";
        try(java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();InputStream input=in) {
            byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","TheOneDJ/Android");
        return c;
    }
    private void askName() {
        activity.runOnUiThread(()->{
            if(nameDialog||activity.isFinishing())return;
            nameDialog=true;
            EditText field=new EditText(activity);field.setSingleLine(true);field.setHint("Jouw naam");
            AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("DJ koppelen aan The One Family")
                .setMessage("Vul je naam in. Geef dit DJ-apparaat daarna vanuit Main toestemming voor DJ.")
                .setView(field).setPositiveButton("Opslaan",null).setNegativeButton("Annuleren",null).create();
            dialog.setOnDismissListener(d->nameDialog=false);
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String name=field.getText().toString().trim();
                if(name.isEmpty()){field.setError("Vul je naam in");return;}
                prefs.edit().putString("person_name",name).apply();dialog.dismiss();
                Toast.makeText(activity,"Open Shared Media opnieuw om toestemming aan te vragen.",Toast.LENGTH_LONG).show();
            }));
            dialog.show();
        });
    }
    private synchronized void authorize() throws Exception {
        String name=prefs.getString("person_name","");
        if(name.isEmpty()){askName();throw new IOException("Vul eerst je naam in en open Shared Media opnieuw.");}
        JSONObject heartbeat=post("devices.php","heartbeat",new JSONObject()
            .put("device_id",deviceId).put("name","The One DJ • "+Build.MODEL)
            .put("person_name",name).put("platform","Android "+Build.VERSION.RELEASE)
            .put("version",activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName));
        if(heartbeat.optBoolean("blocked"))throw new AccessError("Dit DJ-apparaat is geblokkeerd in Main.");
        JSONObject status=post("devices.php","access_status",new JSONObject().put("device_id",deviceId).put("scope","dj"));
        if(!status.optBoolean("allowed")){
            if(!status.optBoolean("pending"))post("devices.php","request_access",new JSONObject().put("device_id",deviceId).put("scope","dj"));
            token="";expires=0;
            throw new AccessError("Toestemming gevraagd. Keur ‘The One DJ • "+Build.MODEL+"’ voor DJ goed in Main en open Shared Media opnieuw.");
        }
        if(token.isEmpty()||System.currentTimeMillis()>=expires){
            JSONObject session=post("music.php","browse-login",new JSONObject());
            token=session.optString("token");
            if(token.isEmpty())throw new IOException("Shared Media-aanmelding mislukt.");
            expires=System.currentTimeMillis()+Math.max(1,session.optLong("expires_in",3600)-60)*1000L;
        }
    }
    WebResourceResponse intercept(WebResourceRequest request) {
        Uri uri=request.getUrl();
        if(!"https".equals(uri.getScheme())||!"appassets.androidplatform.net".equals(uri.getHost()))return null;
        String path=uri.getPath();
        if(path==null||!path.startsWith("/shared-media/"))return null;
        try {
            // Desktop inbox polling must not silently register an Android device.
            if(path.equals("/shared-media/inbox"))return json(200,"{\"items\":[]}");
            if(!path.equals("/shared-media/catalog")&&!path.equals("/shared-media/file"))
                return json(404,"{\"error\":\"Deze functie is niet beschikbaar op Android\"}");
            boolean head="HEAD".equals(request.getMethod());
            if(!"GET".equals(request.getMethod())&&!(head&&path.equals("/shared-media/file")))
                return json(405,"{\"error\":\"Alleen lezen toegestaan\"}");
            authorize();
            if(path.equals("/shared-media/catalog")){
                HttpURLConnection c=connect(ROOT+"music.php?action=catalog&include_inactive=1&request_device_id="+Uri.encode(deviceId));
                c.setRequestProperty("Authorization","Bearer "+token);
                try {
                    int code=c.getResponseCode();
                    if(code!=200){if(code==401){token="";expires=0;}return json(code,"{\"error\":\"Shared Media-catalogus ophalen mislukt\"}");}
                    return json(200,read(c.getInputStream()));
                } finally { c.disconnect(); }
            }
            String device=uri.getQueryParameter("device"),stick=uri.getQueryParameter("stick"),file=uri.getQueryParameter("path");
            if(device==null||stick==null||file==null)return json(400,"{\"error\":\"Onvolledige muziekverwijzing\"}");
            String range=request.getRequestHeaders().get("Range");
            HttpURLConnection opened=null;
            int code=0;
            for(int attempt=0;attempt<2;attempt++){
                opened=connect(ROOT+"music.php?action=stream&token="+Uri.encode(token)
                    +"&device="+Uri.encode(device)+"&stick="+Uri.encode(stick)+"&path="+Uri.encode(file));
                if(head)opened.setRequestMethod("HEAD");
                if(range!=null)opened.setRequestProperty("Range",range);
                code=opened.getResponseCode();
                if(code==401&&attempt==0){
                    opened.disconnect();
                    synchronized(this){token="";expires=0;authorize();}
                    continue;
                }
                break;
            }
            final HttpURLConnection c=opened;
            if(code!=200&&code!=206){
                c.disconnect();
                String message=code==404?"Dit nummer is niet beschikbaar via Shared Media."
                    :code==401?"Shared Media-aanmelding kon niet worden vernieuwd."
                    :code==403?"DJ heeft geen toestemming voor dit nummer."
                    :"Shared Media-server reageert met HTTP "+code+". Probeer opnieuw.";
                JSONObject error=new JSONObject();error.put("error",message);
                return json(code,error.toString());
            }
            Map<String,String> headers=new HashMap<>();
            for(String h:new String[]{"Content-Length","Content-Range","Accept-Ranges"}){String value=c.getHeaderField(h);if(value!=null)headers.put(h,value);}
            String mime=c.getContentType();if(mime==null)mime="audio/mpeg";mime=mime.split(";")[0];
            if(head){c.disconnect();return new WebResourceResponse(mime,null,code,"OK",headers,new ByteArrayInputStream(new byte[0]));}
            // Finish the network transfer before handing audio to WebView. A WebView
            // consumer must never retain an upstream socket or receive partial audio.
            java.io.File audio=java.io.File.createTempFile("dj-transfer-",".audio",activity.getCacheDir());
            boolean complete=false;
            try {
                long expected=c.getContentLengthLong(),total=0;
                try(InputStream input=c.getInputStream();java.io.OutputStream out=new java.io.FileOutputStream(audio)){
                    byte[] buffer=new byte[65536];int n;
                    while((n=input.read(buffer))!=-1){
                        total+=n;
                        if(total>256L*1024*1024)throw new IOException("Audio exceeds download limit");
                        out.write(buffer,0,n);
                    }
                }
                if(expected>=0&&total!=expected)throw new IOException("Incomplete audio transfer");
                headers.put("Content-Length",Long.toString(total));
                complete=true;
            } finally {
                c.disconnect();
                if(!complete)audio.delete();
            }
            final java.io.File downloaded=audio;
            InputStream stream;
            try {
                stream=new FilterInputStream(new java.io.FileInputStream(downloaded)){
                    private boolean released;
                    private long remaining=downloaded.length();
                    private void consumed(int n) throws IOException {
                        if(n<0){release();return;}
                        remaining-=n;if(remaining<=0)release();
                    }
                    private void release() throws IOException {
                        if(released)return;
                        released=true;
                        try{super.close();}finally{downloaded.delete();}
                    }
                    @Override public int read() throws IOException {
                        if(released)return -1;
                        try{int n=in.read();consumed(n<0?-1:1);return n;}
                        catch(IOException e){try{release();}catch(IOException ignored){}throw e;}
                    }
                    @Override public int read(byte[] b,int offset,int length) throws IOException {
                        if(length==0)return 0;
                        if(released)return -1;
                        try{int n=in.read(b,offset,length);consumed(n);return n;}
                        catch(IOException e){try{release();}catch(IOException ignored){}throw e;}
                    }
                    @Override public void close() throws IOException {release();}
                };
            } catch(IOException e){downloaded.delete();throw e;}
            return new WebResourceResponse(mime,null,code,code==206?"Partial Content":"OK",headers,stream);
        } catch(Exception e){
            String message=e instanceof AccessError?e.getMessage()
                :e instanceof java.net.SocketTimeoutException?"Shared Media-verbinding duurde te lang. Probeer opnieuw."
                :e instanceof java.net.UnknownHostException?"Shared Media-server kon niet worden gevonden. Controleer internet."
                :"Android kon Shared Media niet bereiken ("+e.getClass().getSimpleName()+"). Probeer opnieuw.";
            JSONObject error=new JSONObject();
            try{error.put("error",message);}catch(Exception ignored){}
            return json(e instanceof AccessError?403:503,error.toString());
        }
    }
    private static WebResourceResponse json(int code,String body) {
        return new WebResourceResponse("application/json","UTF-8",code,code==200?"OK":"Unavailable",
            new HashMap<>(),new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
