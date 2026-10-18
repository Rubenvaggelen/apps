package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The One Media Player updater (Android + Google TV).
 *
 * The signed APKs are published ONLY to authenticated private Dev Hub, not
 * GitHub Releases. Read version metadata from our successful GitHub release
 * workflow, then open the matching protected download page in the browser.
 * Never place Dev Hub credentials or private download URLs in an APK.
 *
 * Android requires explicit user approval for regular app installations.
 */
public final class MediaPlayerUpdateChecker {
    private static final String RELEASE_RUNS =
        "https://api.github.com/repos/Rubenvaggelen/apps/actions/workflows/" +
        "media-player-adult-private-release.yml/runs?branch=" +
        "feature%2Fmedia-player-adult-5min-20261016&status=success&per_page=20";
    private static final String TV_RELEASE_RUNS =
        "https://api.github.com/repos/Rubenvaggelen/apps/actions/workflows/" +
        "media-player-tv-pornhub-transport-release.yml/runs?branch=" +
        "feature%2Fmedia-player-adult-5min-20261016&status=success&per_page=20";
    private static final String PRIVATE_DOWNLOAD =
        "https://dev.rubenvanaggelen.com/family-download-20261013.php?app=";
    private static final String PREFS="the_one_player_updater";
    private static final String LAST_CHECK="last_check";
    private static final long CHECK_INTERVAL_MS=15L*60L*1000L;
    private static final Pattern ANDROID_PATTERN=Pattern.compile("Android\\s+v(\\d+)");
    private static final Pattern TV_PATTERN=Pattern.compile("TV\\s+v(\\d+)");
    private static final AtomicBoolean inProgress=new AtomicBoolean(false);
    private static final AtomicBoolean dialogOpen=new AtomicBoolean(false);

    private MediaPlayerUpdateChecker() {}

    public static void checkForUpdate(Activity activity){
        check(activity,false);
    }

    public static void checkForUpdateManual(Activity activity){
        check(activity,true);
    }

    private static void check(Activity activity, boolean manual){
        if(activity==null||activity.isFinishing())return;
        SharedPreferences settings=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(!manual && now-settings.getLong(LAST_CHECK,0L)<CHECK_INTERVAL_MS)return;
        if(!inProgress.compareAndSet(false,true))return;
        final boolean tv=activity.getPackageName().equals("com.theone.mediaplayer.tv");
        if(manual)Toast.makeText(activity,"Controleren op nieuwe versie…",Toast.LENGTH_SHORT).show();

        new Thread(()->{
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(tv ? TV_RELEASE_RUNS : RELEASE_RUNS).openConnection();
                conn.setRequestProperty("Accept","application/vnd.github+json");
                conn.setRequestProperty("User-Agent","TheOneMediaPlayer-VersionCheck");
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(9000);
                if(conn.getResponseCode()!=200)throw new IllegalStateException("Release check unavailable");
                StringBuilder data=new StringBuilder();
                try(BufferedReader in=new BufferedReader(
                    new InputStreamReader(conn.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){
                    String line;
                    while((line=in.readLine())!=null){
                        data.append(line);
                        if(data.length()>500000)throw new IllegalStateException("Response too large");
                    }
                }
                JSONArray runs=new JSONObject(data.toString()).optJSONArray("workflow_runs");
                if(runs==null)throw new IllegalStateException("No workflow runs");
                Pattern pattern=tv?TV_PATTERN:ANDROID_PATTERN;
                int latest=0;
                for(int i=0;i<runs.length();i++){
                    JSONObject run=runs.optJSONObject(i);
                    if(run==null || !"success".equals(run.optString("conclusion")))continue;
                    if(!"feature/media-player-adult-5min-20261016".equals(run.optString("head_branch")))continue;
                    Matcher match=pattern.matcher(run.optString("name",""));
                    if(match.find()){
                        try{ latest=Math.max(latest,Integer.parseInt(match.group(1))); }
                        catch(NumberFormatException ignored){}
                    }
                }
                final int available=latest;
                long installed=currentVersion(activity);
                settings.edit().putLong(LAST_CHECK,System.currentTimeMillis()).apply();
                activity.runOnUiThread(()->{
                    if(activity.isFinishing() || activity.isDestroyed())return;
                    if(available>installed)showDialog(activity,available,tv);
                    else if(manual)Toast.makeText(activity,
                        "Je hebt de nieuwste Media Player-versie.",Toast.LENGTH_LONG).show();
                });
            }catch(Exception e){
                if(manual)activity.runOnUiThread(()->{
                    if(!activity.isFinishing())Toast.makeText(activity,
                        "Updatecontrole niet beschikbaar. Probeer later opnieuw.",
                        Toast.LENGTH_LONG).show();
                });
            }finally{
                if(conn!=null)conn.disconnect();
                inProgress.set(false);
            }
        },"media-player-auto-update").start();
    }

    private static long currentVersion(Context context){
        try{
            android.content.pm.PackageInfo info=context.getPackageManager()
                .getPackageInfo(context.getPackageName(),0);
            return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
        }catch(Exception ignored){return 0;}
    }

    private static void showDialog(Activity activity,int version,boolean tv){
        if(!dialogOpen.compareAndSet(false,true))return;
        String name=tv?"Google TV":"Android";
        AlertDialog dialog=new AlertDialog.Builder(activity)
            .setTitle("The One Media Player-update")
            .setMessage("Nieuwe "+name+"-versie v"+version+
                " staat klaar in de privé-Dev Hub. Open de download en bevestig " +
                "de installatie. Je bestaande instellingen blijven behouden.")
            .setPositiveButton("Update openen",(d,w)->openPrivateDownload(activity,tv))
            .setNegativeButton("Later",null)
            .create();
        dialog.setOnDismissListener(d->dialogOpen.set(false));
        dialog.show();
    }

    private static void openPrivateDownload(Activity activity,boolean tv){
        if(tv)MediaPlayerTvInstaller.open(activity);
        else MediaPlayerPhoneInstaller.open(activity);
    }
}
