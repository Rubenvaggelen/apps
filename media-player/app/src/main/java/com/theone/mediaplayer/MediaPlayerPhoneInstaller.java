package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;
import android.view.ViewGroup;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;

/** No external browser is required to install a signed Android phone Media Player update.
 * Credentials for the existing protected Dev Hub are used for this one download only.
 * No password, HTTP Basic token or private APK is ever copied to an intent or log.
 */
public final class MediaPlayerPhoneInstaller {
    private static final String HOST = "dev.rubenvanaggelen.com";
    private static final String PRIVATE_APK =
        "https://dev.rubenvanaggelen.com/family-download-20261013.php?app=media-player";
    private static final String PHONE_PACKAGE = "com.theone.mediaplayer";
    private static final long MAX_BYTES = 120L * 1024 * 1024;
    private static volatile boolean waitingForPermission;
    private static volatile boolean downloading;
    private MediaPlayerPhoneInstaller() {}

    static boolean permittedUrl(String raw) {
        try {
            URL u = new URL(raw);
            return "https".equalsIgnoreCase(u.getProtocol())
                && HOST.equalsIgnoreCase(u.getHost())
                && (u.getPort() == -1 || u.getPort() == 443);
        } catch(Exception e){ return false; }
    }

    public static void open(Activity activity) {
        if (!PHONE_PACKAGE.equals(activity.getPackageName())) return;
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(pad,pad,pad,pad);
        TextView message = new TextView(activity);
        message.setText("Log eenmalig in bij de beveiligde Dev Hub. De telefoon downloadt de ondertekende APK zelf, zonder externe browser. Je gegevens worden niet bewaard.");
        message.setTextSize(16);
        form.addView(message);
        EditText user = new EditText(activity);
        user.setSingleLine(true);
        user.setHint("Dev Hub gebruikersnaam");
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(user,new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText pass = new EditText(activity);
        pass.setSingleLine(true);
        pass.setHint("Dev Hub wachtwoord");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(pass,new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("The One Media Player • Android-update")
            .setView(form)
            .setPositiveButton("Downloaden en installeren",null)
            .setNegativeButton("Annuleren",null)
            .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String username = user.getText().toString().trim();
            String password = pass.getText().toString();
            if(username.isEmpty() || password.isEmpty()){
                user.setError("Vul je Dev Hub gegevens in");return;
            }
            pass.setText("");
            dialog.dismiss();
            download(activity, username, password);
        }));
        dialog.show();
        user.requestFocus();
    }

    private static void download(Activity activity, String user, String password) {
        if (downloading) return;
        downloading = true;
        android.widget.Toast.makeText(activity,"Beveiligde telefoonupdate downloaden…",android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            File directory = new File(activity.getCacheDir(),"updates");
            File tmp = new File(directory,"mediaplayer-phone.tmp");
            File apk = new File(directory,"mediaplayer-phone.apk");
            try {
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IllegalStateException("Updateopslag niet beschikbaar");
                String basic = Base64.encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
                URL url = new URL(PRIVATE_APK);
                HttpURLConnection conn = null;
                try {
                    int redirects = 0;
                    while(true){
                        if(!permittedUrl(url.toString())) throw new SecurityException("Ongeldige updatehost");
                        conn = (HttpURLConnection)url.openConnection();
                        conn.setInstanceFollowRedirects(false);
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(45000);
                        conn.setRequestProperty("Authorization","Basic "+basic);
                        conn.setRequestProperty("Cache-Control","no-store");
                        int response = conn.getResponseCode();
                        if(response >= 300 && response <= 308 && response != 304){
                            String location = conn.getHeaderField("Location");
                            if(location == null || ++redirects > 4) throw new SecurityException("Ongeldige redirect");
                            URL next = new URL(url,location);
                            if(!permittedUrl(next.toString())) throw new SecurityException("Redirect buiten Dev Hub");
                            conn.disconnect();
                            conn = null;
                            url = next;
                            continue;
                        }
                        if(response == 401 || response == 403)
                            throw new SecurityException("Dev Hub-aanmelding geweigerd");
                        if(response != 200)
                            throw new IllegalStateException("Updateserver antwoordt met HTTP "+response);
                        if(conn.getContentLengthLong() > MAX_BYTES)
                            throw new IllegalStateException("APK is te groot");
                        long total=0;
                        try(InputStream input=conn.getInputStream();FileOutputStream output=new FileOutputStream(tmp)){
                            byte[] buffer = new byte[32768];
                            for(int n;(n=input.read(buffer))!=-1;){
                                total+=n;
                                if(total>MAX_BYTES)throw new IllegalStateException("APK is te groot");
                                output.write(buffer,0,n);
                            }
                        }
                        if(total<100000)throw new IllegalStateException("Geen geldig APK-bestand ontvangen");
                        break;
                    }
                } finally {
                    if(conn!=null)conn.disconnect();
                    // The Basic token never leaves the current download operation.
                }
                verify(activity,tmp);
                if(apk.exists()&&!apk.delete())throw new IllegalStateException("Oude download verwijderen mislukt");
                if(!tmp.renameTo(apk))throw new IllegalStateException("Download opslaan mislukt");
                activity.runOnUiThread(() -> {
                    if(!activity.isFinishing())install(activity,apk);
                });
            } catch(Exception error) {
                tmp.delete();
                activity.runOnUiThread(() -> {
                    if(!activity.isFinishing())new AlertDialog.Builder(activity)
                        .setTitle("telefoonupdate niet geïnstalleerd")
                        .setMessage(error.getMessage()==null ? "Download of controle mislukt." : error.getMessage())
                        .setPositiveButton("Opnieuw proberen",(d,w)->open(activity))
                        .setNegativeButton("Later",null).show();
                });
            } finally { downloading = false; }
        },"phone-native-update").start();
    }

    private static Signature[] signatures(PackageInfo info) {
        if(info==null)return null;
        if(Build.VERSION.SDK_INT>=28 && info.signingInfo!=null){
            Signature[] available = info.signingInfo.getApkContentsSigners();
            if(available!=null&&available.length>0)return available;
        }
        return info.signatures;
    }

    @SuppressWarnings("deprecation")
    private static void verify(Activity activity, File apk) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags=PackageManager.GET_SIGNATURES;
        if(Build.VERSION.SDK_INT>=28)flags|=PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo installed=pm.getPackageInfo(PHONE_PACKAGE,flags);
        PackageInfo next=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        if(next==null || !PHONE_PACKAGE.equals(next.packageName))
            throw new SecurityException("Dit bestand is geen The One Media Player telefoonupdate");
        long current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
        long available=Build.VERSION.SDK_INT>=28?next.getLongVersionCode():next.versionCode;
        if(available<=current)
            throw new SecurityException("Geen nieuwere Media Player telefoonversie beschikbaar");
        Signature[] a=signatures(installed),b=signatures(next);
        if(a!=null&&a.length>0&&b!=null&&b.length>0
            && !new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b))))
            throw new SecurityException("APK heeft een andere ondertekening");
        // Some Sony firmware returns null signatures for on-disk APKs.
        // Android's system installer independently enforces the installed package's signer.
    }

    private static void install(Activity activity, File apk) {
        try {
            verify(activity,apk);
            if(Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()){
                waitingForPermission=true;
                new AlertDialog.Builder(activity)
                    .setTitle("Installatietoestemming nodig")
                    .setMessage("Sta The One Media Player toe om de gedownloade update te installeren. Ga daarna met Terug naar de app.")
                    .setPositiveButton("Instellingen",(d,w)->activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:"+activity.getPackageName()))))
                    .setNegativeButton("Later",null).show();
                return;
            }
            waitingForPermission=false;
            Uri uri=FileProvider.getUriForFile(activity,PHONE_PACKAGE+".updates",apk);
            Intent installer = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(installer);
        } catch (Exception error) {
            new AlertDialog.Builder(activity).setTitle("Installatie niet gestart")
                .setMessage(error.getMessage()==null?"Geen systeeminstaller beschikbaar.":error.getMessage())
                .setPositiveButton("OK",null).show();
        }
    }

    public static void resumeIfPending(Activity activity) {
        if(!waitingForPermission)return;
        if(Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls())return;
        File apk=new File(activity.getCacheDir(),"updates/mediaplayer-phone.apk");
        waitingForPermission=false;
        if(apk.isFile())install(activity,apk);
    }
}
