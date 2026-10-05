package com.gmailorg.thedj;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.lang.ref.WeakReference;

/** Keeps the existing DJ engine active while the user switches applications. */
public final class DjPlaybackService extends Service {
    private static WeakReference<MainActivity> host=new WeakReference<>(null);
    private static boolean background;
    static void attach(MainActivity activity){host=new WeakReference<>(activity);}
    static void background(boolean value){background=value;}
    static void detach(MainActivity activity){if(host.get()==activity)host.clear();}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private long renewed;
    private final Runnable pump=new Runnable(){
        @Override public void run(){
            MainActivity activity=host.get();
            if(activity==null||activity.isFinishing()){stopSelf();return;}
            long now=SystemClock.elapsedRealtime();
            if(cpu!=null&&(now-renewed>=60000||!cpu.isHeld())){
                cpu.acquire(600000);renewed=now;
            }
            activity.pumpBackgroundPlayback();
            handler.postDelayed(this,background?250:1000);
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("dj-playback",
            "DJ afspelen",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent intent=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"dj-playback")
            .setSmallIcon(R.drawable.the_one_dj_logo)
            .setContentTitle("The One DJ")
            .setContentText("Muziek afspelen en voorbereiden • tik om DJ te openen")
            .setContentIntent(intent).setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT).setShowWhen(false).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(1046,notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1046,notification);
        PowerManager power=getSystemService(PowerManager.class);
        cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"TheOneDJ:playback");
        cpu.setReferenceCounted(false);cpu.acquire(600000);
        renewed=SystemClock.elapsedRealtime();
        WifiManager network=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(network!=null){
            wifi=network.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"TheOneDJ:audio");
            wifi.setReferenceCounted(false);wifi.acquire();
        }
        handler.post(pump);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        // Do not restart an empty service without its DJ engine after process death.
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        handler.removeCallbacks(pump);
        if(cpu!=null&&cpu.isHeld())cpu.release();
        if(wifi!=null&&wifi.isHeld())wifi.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
