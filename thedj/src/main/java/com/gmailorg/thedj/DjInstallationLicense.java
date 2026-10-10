package com.gmailorg.thedj;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;

import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Standalone DJ license is INSTALLATION BOUND. Stored only in noBackupFilesDir.
 * Every fresh download/install or reinstall gets a new ID and needs owner approval.
 * A normal APK upgrade retains its existing approved token.
 */
public final class DjInstallationLicense {
    private static final String ENDPOINT =
        "https://rubenvanaggelen.com/the-one-remote-api/licenses.php?action=";
    private static final String INSTALL_ID = "dj-install-id-v1";
    private static final String PERSON = "dj-person-v1";
    private static final String TOKEN = "dj-license-token-v1";
    private static final String REQUEST = "dj-license-request-v1";
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pollTask;
    private Runnable approvedAction;
    private boolean stopped;
    private boolean checking;
    private boolean granted;
    private TextView message;
    private EditText nameInput;

    public DjInstallationLicense(Activity activity) {
        this.activity = activity;
    }

    private File file(String name) { return new File(activity.getNoBackupFilesDir(), name); }

    private String read(String name) {
        try { return new String(java.nio.file.Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8).trim(); }
        catch (Exception ex) { return ""; }
    }
    private void write(String name, String value) throws Exception {
        File target=file(name);
        File staged=file(name+".new");
        java.nio.file.Files.write(staged.toPath(),value.getBytes(StandardCharsets.UTF_8));
        if (!staged.renameTo(target)) throw new Exception("Kan licentie niet veilig bewaren");
    }
    private String id() throws Exception {
        String current=read(INSTALL_ID);
        try { UUID.fromString(current); return current; } catch (Exception ignored) {}
        String generated=UUID.randomUUID().toString();
        write(INSTALL_ID,generated); // Abort if it cannot be persisted. Never use transient IDs.
        return generated;
    }
    private JSONObject base() throws Exception {
        return new JSONObject()
            .put("app","dj")
            .put("device_id",id())
            .put("installation_id",id());
    }
    private JSONObject post(String action, JSONObject payload) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(ENDPOINT+action).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        connection.setRequestProperty("Accept","application/json");
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        try {
            try(java.io.OutputStream out=connection.getOutputStream()){
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int responseCode=connection.getResponseCode();
            if(responseCode!=200) throw new Exception("Licentieserver niet bereikbaar ("+responseCode+")");
            try(java.io.InputStream in=connection.getInputStream()){
                java.io.ByteArrayOutputStream all=new java.io.ByteArrayOutputStream();
                byte[] bytes=new byte[4096]; int n;
                while((n=in.read(bytes))!=-1){
                    if(all.size()+n>32768)throw new Exception("Ongeldig antwoord");
                    all.write(bytes,0,n);
                }
                JSONObject result=new JSONObject(all.toString("UTF-8"));
                if(!result.optBoolean("ok",false))throw new Exception("Licentieserver weigert aanvraag");
                return result;
            }
        }finally{connection.disconnect();}
    }

    /** Call from onCreate BEFORE constructing any DJ WebView or audio service. */
    private boolean isExistingPreLicenseInstallation() {
        try {
            @SuppressWarnings("deprecation")
            android.content.pm.PackageInfo packageInfo =
                activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            // Existing DJ v1049 and older installations continue without a forced
            // mass re-enrollment. Uninstall/reinstall resets firstInstallTime.
            return packageInfo.firstInstallTime > 0L &&
                packageInfo.firstInstallTime < 1791662400000L; // 2026-10-10T20:00Z
        } catch (Exception ignored) {
            return false; // Fail closed for unknown install metadata.
        }
    }

    public void begin(Runnable launchApprovedPlayer) {
        approvedAction=launchApprovedPlayer;
        if (isExistingPreLicenseInstallation()) {
            granted=true;
            approvedAction.run();
            return;
        }
        render();
        check();
        pollTask=new Runnable(){
            @Override public void run(){
                if(stopped||granted)return;
                check();
                ui.postDelayed(this,15000);
            }
        };
        ui.postDelayed(pollTask,15000);
    }

    private void render() {
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(28,28,28,28);
        panel.setBackgroundColor(Color.rgb(6,12,24));
        TextView title=new TextView(activity);
        title.setText("THE ONE DJ");
        title.setTextColor(Color.rgb(28,186,255));
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        message=new TextView(activity);
        message.setTextColor(Color.WHITE);
        message.setTextSize(16);
        message.setPadding(0,22,0,22);
        message.setGravity(Gravity.CENTER);
        message.setText("Deze DJ-installatie moet worden goedgekeurd via The One Main → Laptop → Apparaten beheren.");
        panel.addView(message);
        String saved=read(PERSON);
        if(saved.isEmpty()){
            nameInput=new EditText(activity);
            nameInput.setTextColor(Color.WHITE);
            nameInput.setHintTextColor(Color.LTGRAY);
            nameInput.setHint("Naam van gebruiker / DJ-apparaat");
            nameInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            nameInput.setSingleLine(true);
            panel.addView(nameInput);
        }
        Button request=new Button(activity);
        request.setText(saved.isEmpty()?"Toegang aanvragen":"Nieuwe aanvraag versturen");
        request.setOnClickListener(v->{
            String person=read(PERSON);
            if(nameInput!=null)person=nameInput.getText().toString().trim();
            if(person.length()<2||person.length()>90){
                message.setText("Vul eerst een naam van minstens twee letters in.");
                return;
            }
            final String chosen=person;
            runAsync(()->{
                write(PERSON,chosen);
                JSONObject response=post("request",base().put("person",chosen));
                String reqId=response.optString("request_id","");
                String secret=response.optString("request_secret","");
                if(reqId.matches("[a-f0-9]{24}")&&secret.matches("[a-f0-9]{64}")){
                    write(REQUEST,new JSONObject().put("id",reqId).put("secret",secret).toString());
                }else if(read(REQUEST).isEmpty()){
                    throw new Exception("Aanvraag bestaat, maar activatiegegevens ontbreken");
                }
                return "Aanvraag verstuurd. Wacht op goedkeuring in Main.";
            });
        });
        panel.addView(request);
        Button check=new Button(activity);
        check.setText("Goedkeuring controleren");
        check.setOnClickListener(v->check());
        panel.addView(check);
        activity.setContentView(panel);
    }

    private interface Work { String execute() throws Exception; }
    private void runAsync(Work work){
        if(stopped||granted||checking)return;
        checking=true;
        new Thread(()->{
            String result;
            try{result=work.execute();}
            catch(Exception ex){result="Wachten op verbinding of toestemming. "+ex.getMessage();}
            final String text=result;
            ui.post(()->{
                checking=false;
                if(stopped||granted)return;
                if(message!=null)message.setText(text);
            });
        },"dj-license-check").start();
    }
    public void check(){
        runAsync(()->{
            String credential=read(TOKEN);
            JSONObject result=post("status",base().put("token",credential));
            if(result.optBoolean("allowed",false)){
                grant();
                return "Goedgekeurd.";
            }
            String request=read(REQUEST);
            if(!request.isEmpty()){
                JSONObject data=new JSONObject(request);
                JSONObject claim=post("claim",base()
                    .put("request_id",data.optString("id"))
                    .put("request_secret",data.optString("secret")));
                if(claim.optBoolean("allowed",false)){
                    String newToken=claim.optJSONObject("credential").optString("token","");
                    if(!newToken.matches("[a-f0-9]{64}"))throw new Exception("Ongeldige licentie");
                    write(TOKEN,newToken);
                    file(REQUEST).delete();
                    grant();
                    return "Goedgekeurd.";
                }
            }
            return "Wacht op jouw goedkeuring in Main → Apparaten beheren. Zonder toestemming kan DJ niet starten.";
        });
    }
    private void grant(){
        ui.post(()->{
            if(stopped||granted)return;
            granted=true;
            if(pollTask!=null)ui.removeCallbacks(pollTask);
            if(approvedAction!=null)approvedAction.run();
        });
    }
    public void revalidate(Runnable revoked){
        if(isExistingPreLicenseInstallation())return; // Existing DJ upgrade stays intact.
        if(!granted||stopped)return;
        new Thread(()->{
            try{
                boolean allowed=post("status",base().put("token",read(TOKEN))).optBoolean("allowed",false);
                if(!allowed)ui.post(()->{
                    if(stopped)return;
                    granted=false;
                    file(TOKEN).delete();
                    revoked.run();
                });
            }catch(Exception ignored){
                // Already running approved installation may be offline.
                // Never permit first-time installation on a network failure.
            }
        },"dj-license-revalidate").start();
    }
    public void close(){
        stopped=true;
        if(pollTask!=null)ui.removeCallbacks(pollTask);
    }
}
