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
        if(heartbeat.optBoolean("blocked"))throw new IOException("Dit DJ-apparaat is geblokkeerd in Main.");
        JSONObject status=post("devices.php","access_status",new JSONObject().put("device_id",deviceId).put("scope","dj"));
        if(!status.optBoolean("allowed")){
            if(!status.optBoolean("pending"))post("devices.php","request_access",new JSONObject().put("device_id",deviceId).put("scope","dj"));
            token="";expires=0;
            throw new IOException("Toestemming gevraagd. Keur ‘The One DJ • "+Build.MODEL+"’ voor DJ goed in Main en open Shared Media opnieuw.");
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
            if(!"GET".equals(request.getMethod()))return json(405,"{\"error\":\"Alleen lezen toegestaan\"}");
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
            HttpURLConnection c=connect(ROOT+"music.php?action=stream&token="+Uri.encode(token)
                +"&device="+Uri.encode(device)+"&stick="+Uri.encode(stick)+"&path="+Uri.encode(file));
            String range=request.getRequestHeaders().get("Range");
            if(range!=null)c.setRequestProperty("Range",range);
            int code=c.getResponseCode();
            if(code!=200&&code!=206){c.disconnect();if(code==401){token="";expires=0;}return json(code,"{\"error\":\"Nummer niet beschikbaar in Shared Media\"}");}
            Map<String,String> headers=new HashMap<>();
            for(String h:new String[]{"Content-Length","Content-Range","Accept-Ranges"}){String value=c.getHeaderField(h);if(value!=null)headers.put(h,value);}
            String mime=c.getContentType();if(mime==null)mime="audio/mpeg";mime=mime.split(";")[0];
            InputStream stream=new FilterInputStream(c.getInputStream()){
                @Override public void close() throws IOException {try{super.close();}finally{c.disconnect();}}
            };
            return new WebResourceResponse(mime,null,code,code==206?"Partial Content":"OK",headers,stream);
        } catch(Exception e){
            String message=e instanceof IOException?e.getMessage():"Shared Media is niet bereikbaar. Probeer opnieuw.";
            JSONObject error=new JSONObject();
            try{error.put("error",message);}catch(Exception ignored){}
            return json(503,error.toString());
        }
    }
    private static WebResourceResponse json(int code,String body) {
        return new WebResourceResponse("application/json","UTF-8",code,code==200?"OK":"Unavailable",
            new HashMap<>(),new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
