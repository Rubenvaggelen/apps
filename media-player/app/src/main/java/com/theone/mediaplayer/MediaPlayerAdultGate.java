package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** XXX entitlement is independent of ordinary Media Player access. Fail closed. */
public final class MediaPlayerAdultGate {
    private static final String ENDPOINT =
        "https://rubenvanaggelen.com/the-one-remote-api/devices.php?action=";
    private static final String SCOPE="media_player_xxx";
    private static volatile long openUntil=0;
    private static int failedAttempts=0;
    private static long retryAfter=0;

    private MediaPlayerAdultGate() {}
    public static boolean isUnlocked() {
        return SystemClock.elapsedRealtime() < openUntil;
    }
    public static void lock() { openUntil=0; }

    public static void request(Activity activity,Runnable afterApproval) {
        if(isUnlocked()) {afterApproval.run();return;}
        if(SystemClock.elapsedRealtime()<retryAfter) {
            Toast.makeText(activity,"Wacht even voordat je opnieuw probeert.",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("XXX • aparte toestemming")
            .setMessage("Voor XXX is een eigen goedkeuring in The One Main vereist. Deze toestemming staat los van de Media Player-licentie.")
            .setNegativeButton("Annuleren",null)
            .setPositiveButton("Toegang controleren",(dialog,which)->checkRemote(activity,afterApproval))
            .show();
    }
    private static void checkRemote(Activity activity,Runnable afterApproval) {
        String deviceId=MediaPlayerAuthorization.registeredDeviceId(activity);
        if(deviceId.isEmpty()){
            Toast.makeText(activity,"Registreer Media Player eerst via The One Main.",Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(()->{
            boolean allowed=false,pending=false,online=false;
            try {
                JSONObject body=new JSONObject().put("device_id",deviceId).put("scope",SCOPE);
                JSONObject status=post("access_status",body);
                online=true;
                allowed=status.optBoolean("allowed",false);
                pending=status.optBoolean("pending",false);
                if(!allowed&&!pending) {
                    JSONObject req=post("request_access",body);
                    pending=req.optBoolean("pending",false);
                }
            }catch(Exception ignored) {}
            boolean accepted=allowed,waiting=pending,connected=online;
            activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(!accepted){
                    new AlertDialog.Builder(activity).setTitle("XXX vergrendeld")
                        .setMessage(!connected?"De afzonderlijke XXX-toestemming kan nu niet gecontroleerd worden. Toegang blijft geblokkeerd.":
                            waiting?"Je XXX-aanvraag staat klaar in The One Main. Alleen de eigenaar kan toestemming geven.":
                            "Geen XXX-toestemming. Laat de eigenaar de aparte aanvraag behandelen.")
                        .setPositiveButton("OK",null).show();
                    return;
                }
                promptPin(activity,afterApproval);
            });
        },"xxx-permission").start();
    }
    private static JSONObject post(String action,JSONObject data) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(ENDPOINT+action).openConnection();
        c.setConnectTimeout(8500);c.setReadTimeout(8500);
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try {
            byte[] body=data.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=c.getOutputStream()){out.write(body);}
            if(c.getResponseCode()!=200)throw new java.io.IOException("Access response denied");
            try(InputStream in=c.getInputStream()) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buffer=new byte[1024];int n;
                while((n=in.read(buffer))!=-1){
                    out.write(buffer,0,n);
                    if(out.size()>16384)throw new java.io.IOException("Access response oversized");
                }
                JSONObject response=new JSONObject(out.toString("UTF-8"));
                if(!response.optBoolean("ok",false))throw new java.io.IOException("Invalid access");
                return response;
            }
        }finally{c.disconnect();}
    }
    private static void promptPin(Activity activity,Runnable onGranted) {
        SharedPreferences prefs=activity.getSharedPreferences("media_player",Context.MODE_PRIVATE);
        EditText pin=new EditText(activity);
        pin.setHint("PIN");
        pin.setSingleLine(true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(activity).setTitle("XXX • PIN-beveiliging")
            .setMessage(AdultPin.isConfigured(prefs)?"Voer je eigen PIN in.":
                "Gebruik eenmalig de standaard-PIN 1306. Je moet daarna een nieuwe PIN instellen.")
            .setView(pin).setNegativeButton("Terug",null)
            .setPositiveButton("Verder",(d,w)->{
                String input=pin.getText().toString();
                boolean correct=AdultPin.isConfigured(prefs)?AdultPin.check(prefs,input):"1306".equals(input);
                if(!correct) {
                    failedAttempts++;
                    if(failedAttempts>=5){retryAfter=SystemClock.elapsedRealtime()+5L*60L*1000L;failedAttempts=0;}
                    Toast.makeText(activity,"PIN onjuist",Toast.LENGTH_SHORT).show();
                    return;
                }
                failedAttempts=0;
                if(!AdultPin.isConfigured(prefs))changePin(activity,prefs,onGranted);
                else grant(onGranted);
            }).show();
    }
    private static void changePin(Activity activity,SharedPreferences prefs,Runnable onGranted) {
        LinearLayout group=new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(32,0,32,0);
        EditText first=new EditText(activity),again=new EditText(activity);
        first.setHint("Nieuwe PIN (4-8 cijfers)");
        again.setHint("Herhaal nieuwe PIN");
        for(EditText field:new EditText[]{first,again}){
            field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            field.setSingleLine(true);
            group.addView(field,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        AlertDialog dialog=new AlertDialog.Builder(activity)
            .setTitle("Verander de standaard-PIN")
            .setMessage("1306 mag niet opnieuw worden gebruikt.")
            .setView(group).setCancelable(false)
            .setNegativeButton("Annuleren",(d,w)->lock())
            .setPositiveButton("Opslaan",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String chosen=first.getText().toString();
            if(!chosen.equals(again.getText().toString())||!AdultPin.validate(chosen)){
                first.setError("Kies 4-8 cijfers, anders dan 1306, en bevestig dezelfde PIN.");
                return;
            }
            try{
                AdultPin.save(prefs,chosen);
                dialog.dismiss();
                grant(onGranted);
            }catch(Exception e){first.setError("PIN kon niet worden opgeslagen");}
        }));
        dialog.show();
    }
    private static void grant(Runnable onGranted) {
        openUntil=SystemClock.elapsedRealtime()+10L*60L*1000L;
        onGranted.run();
    }
}
