package com.gmailorg.thedj;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/** DJ-only update discovery and verified in-app download. Never updates Main. */
final class DjUpdater {
    private final Activity activity;
    private final BooleanSupplier playing;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private boolean busy,closed,resumed,awaitingPermission;
    private long lastCheck;
    private int offered;
    private final File apk;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){check(false);if(resumed&&!closed)handler.postDelayed(this,900000);}};
    DjUpdater(Activity activity,BooleanSupplier playing){
        this.activity=activity;this.playing=playing;
        apk=new File(activity.getFilesDir(),"updates/dj-update.apk");
    }
    void onResume(){
        resumed=true;
        if(awaitingPermission){
            awaitingPermission=false;
            if(activity.getPackageManager().canRequestPackageInstalls())install();
            else toast("Installatie niet toegestaan. Controleer opnieuw op updates om het opnieuw te proberen.");
        }
        handler.removeCallbacks(tick);handler.post(tick);
    }
    void onPause(){resumed=false;handler.removeCallbacks(tick);}
    void close(){closed=true;handler.removeCallbacks(tick);executor.shutdown();}
    private void ui(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isFinishing()&&!activity.isDestroyed())action.run();});}
    private void toast(String message){Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private int installedVersion()throws Exception{return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;}
    private HttpURLConnection connection(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","The-One-DJ-Updater");
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("Cache-Control","no-cache");
        return c;
    }
    void check(boolean manual){
        if(closed)return;
        if(busy){if(manual)toast("DJ-update wordt al gecontroleerd of gedownload.");return;}
        if(!manual&&(!resumed||playing.getAsBoolean()||System.currentTimeMillis()-lastCheck<900000))return;
        busy=true;lastCheck=System.currentTimeMillis();
        if(manual)toast("DJ-updates controleren…");
        executor.execute(()->{
            int best=0,current=0;String download=null,error=null;boolean foundDj=false;
            try{
                current=installedVersion();
                // Family releases share one repository. Search several pages and filter DJ tags/assets.
                for(int page=1;page<=3;page++){
                    HttpURLConnection c=connection("https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100&page="+page);
                    JSONArray releases;
                    try{
                        if(c.getResponseCode()!=200)throw new Exception("Updatecontrole mislukt (HTTP "+c.getResponseCode()+"). Probeer later opnieuw.");
                        StringBuilder text=new StringBuilder();
                        try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(),"UTF-8"))){String line;while((line=r.readLine())!=null)text.append(line);}
                        releases=new JSONArray(text.toString());
                    }finally{c.disconnect();}
                    for(int i=0;i<releases.length();i++){
                        JSONObject release=releases.getJSONObject(i);
                        if(release.optBoolean("draft")||release.optBoolean("prerelease"))continue;
                        int version=DjUpdatePolicy.version(release.optString("tag_name"));
                        if(version==0)continue;
                        JSONArray assets=release.optJSONArray("assets");if(assets==null)continue;
                        for(int j=0;j<assets.length();j++){
                            JSONObject asset=assets.getJSONObject(j);String url=asset.optString("browser_download_url");
                            if("thedj-debug.apk".equals(asset.optString("name"))&&DjUpdatePolicy.validUrl(version,url)){
                                foundDj=true;
                                if(version>current&&version>best){best=version;download=url;}
                            }
                        }
                    }
                    if(releases.length()<100)break;
                }
                if(!foundDj)throw new Exception("Geen DJ-updatebron gevonden. Probeer later opnieuw; je huidige versie blijft werken.");
            }catch(Exception e){error=e.getMessage();}
            final int found=best,installed=current;final String url=download,failure=error;
            ui(()->{
                busy=false;
                if(failure!=null){if(manual)toast(failure);return;}
                if(url==null){if(manual)toast("Geen nieuwere DJ-update. Geïnstalleerd: build "+installed+".");return;}
                if(!resumed||(!manual&&(playing.getAsBoolean()||offered==found)))return;
                offered=found;
                new AlertDialog.Builder(activity).setTitle("The One DJ-update")
                    .setMessage("Geïnstalleerd: "+installed+". Beschikbaar: "+found+". Je playlist en instellingen blijven behouden. Installeer wanneer je klaar bent met draaien.")
                    .setPositiveButton("Bijwerken",(d,w)->download(url)).setNegativeButton("Later",null).show();
            });
        });
    }
    private void download(String url){
        if(closed||busy)return;busy=true;toast("DJ-update wordt op de achtergrond gedownload.");
        executor.execute(()->{
            String error=null;File temp=new File(apk.getParentFile(),"dj-update.tmp");
            try{
                if(!apk.getParentFile().isDirectory()&&!apk.getParentFile().mkdirs())throw new Exception("Update kon niet worden opgeslagen.");
                HttpURLConnection c=connection(url);c.setRequestProperty("Accept","application/octet-stream");
                try{
                    if(c.getResponseCode()!=200)throw new Exception("Download mislukt (HTTP "+c.getResponseCode()+").");
                    try(InputStream input=c.getInputStream();FileOutputStream output=new FileOutputStream(temp)){
                        byte[] buffer=new byte[32768];long total=0;int n;
                        while((n=input.read(buffer))!=-1){total+=n;if(total>150L*1024*1024)throw new Exception("Updatebestand te groot.");output.write(buffer,0,n);}
                        if(total==0)throw new Exception("Lege download.");
                    }
                }finally{c.disconnect();}
                verify(temp);
                if(apk.exists()&&!apk.delete())throw new Exception("Oude update kon niet worden vervangen.");
                if(!temp.renameTo(apk))throw new Exception("Update kon niet worden opgeslagen.");
            }catch(Exception e){error=e.getMessage();}finally{temp.delete();}
            final String failure=error;
            ui(()->{busy=false;if(failure!=null)toast(failure);else install();});
        });
    }
    @SuppressWarnings("deprecation")
    private void verify(File file)throws Exception{
        PackageManager pm=activity.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo next=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),current=pm.getPackageInfo(activity.getPackageName(),flags);
        if(next==null||!activity.getPackageName().equals(next.packageName)||next.versionCode<=current.versionCode)throw new Exception("Dit bestand is geen nieuwere DJ-update.");
        Signature[] a=Build.VERSION.SDK_INT>=28?(next.signingInfo==null?null:next.signingInfo.getApkContentsSigners()):next.signatures;
        Signature[] b=Build.VERSION.SDK_INT>=28?(current.signingInfo==null?null:current.signingInfo.getApkContentsSigners()):current.signatures;
        if(a==null||b==null||a.length==0||!new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b))))throw new Exception("Ondertekening van DJ-update komt niet overeen.");
    }
    private void install(){
        try{
            verify(apk);
            if(!activity.getPackageManager().canRequestPackageInstalls()){
                new AlertDialog.Builder(activity).setTitle("DJ mag updates installeren")
                    .setMessage("Sta installatie vanuit The One DJ eenmalig toe. Daarna opent Android deze update.")
                    .setPositiveButton("Instellen",(d,w)->{
                        awaitingPermission=true;
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
                    }).setNegativeButton("Later",null).show();
            }else{
                Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",apk);
                activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }
        }catch(Exception e){toast(e.getMessage()==null?"Installatie kon niet worden geopend.":e.getMessage());}
    }
}
