package com.theone.tv;

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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Automatic private update check; only approved Android Keystore identities can download. */
final class FamilyUpdateNotice {
    private static final String SCOPE = "tv_android";
    private static final String URL_BASE = "https://rubenvanaggelen.com/the-one-remote-api/family-updates.php?action=";
    private static final String KEY = "the-one-family-install-v1";
    private static final long LIMIT = 200L * 1024L * 1024L;
    private final Activity activity;
    private final File apk;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean closed, busy, waitingForInstallPermission;
    private long lastCheck;
    private int offeredVersion;
    private volatile String verifiedDownloadSha256;

    FamilyUpdateNotice(Activity activity) {
        this.activity = activity;
        this.apk = new File(activity.getCacheDir(), "updates/family-update.apk");
    }

    void checkOnOpen() {
        if (closed || busy) return;
        if (waitingForInstallPermission && (Build.VERSION.SDK_INT < 26 ||
                activity.getPackageManager().canRequestPackageInstalls())) {
            waitingForInstallPermission = false;
            install();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastCheck != 0L && now-lastCheck < 15L*60L*1000L) return;
        lastCheck = now;
        busy = true;
        executor.execute(() -> {
            try {
                JSONObject meta = request("check", null, null);
                if (!meta.optBoolean("ok") || !activity.getPackageName().equals(meta.optString("package")))
                    throw new SecurityException("Ongeldige updatebron");
                int latest = meta.getInt("version_code");
                String checksum = meta.getString("sha256");
                String version = meta.getString("version_name");
                PackageInfo current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long installed = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
                if (latest <= installed || !checksum.matches("[a-f0-9]{64}")) return;
                ui.post(() -> {
                    if (closed || activity.isFinishing() || activity.isDestroyed() || offeredVersion >= latest) return;
                    offeredVersion = latest;
                    new AlertDialog.Builder(activity)
                        .setTitle("The One-update")
                        .setMessage("Versie " + version + " is beschikbaar. Downloaden en veilig installeren?")
                        .setPositiveButton("Bijwerken", (dialog, which) -> download(checksum))
                        .setNegativeButton("Later", null).show();
                });
            } catch (Exception ignored) {
                // Offline or inaccessible update service never interrupts the media player.
            } finally { busy = false; }
        });
    }


    /** Kept for regression tests; APK installation itself compares Android versionCode. */
    static int compareVersions(String left, String right) {
        if (left == null || right == null ||
            !left.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
            !right.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return -1;
        try {
            String[] a = left.split("\\.");
            String[] b = right.split("\\.");
            for (int i = 0; i < 3; i++) {
                long x = Long.parseLong(a[i]), y = Long.parseLong(b[i]);
                if (x != y) return Long.compare(x, y);
            }
            return 0;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private JSONObject signedBody(String action) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(KEY, null);
        if (entry == null) throw new SecurityException("Apparaat is niet geregistreerd");
        byte[] pub = entry.getCertificate().getPublicKey().getEncoded();
        String id = "fam-" + hex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(pub), 24));
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String nonce = Base64.encodeToString(random, Base64.NO_WRAP);
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(entry.getPrivateKey());
        signer.update((action+"|"+id+"|"+SCOPE+"|"+nonce).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("device_id", id)
            .put("package", activity.getPackageName())
            .put("nonce", nonce)
            .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP));
    }

    private JSONObject request(String action, File target, String expected) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(URL_BASE+action).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Cache-Control", "no-store");
            byte[] body = signedBody(action).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("Updateserver HTTP "+status);
            if (target == null) {
                StringBuilder payload = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) payload.append(line);
                }
                return new JSONObject(payload.toString());
            }
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = conn.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    size += len;
                    if (size > LIMIT) throw new IOException("Updatebestand te groot");
                    hash.update(buffer, 0, len);
                    output.write(buffer, 0, len);
                }
            }
            if (size == 0 || !hex(hash.digest()).equals(expected)) throw new SecurityException("SHA-256 controle mislukt");
            return null;
        } finally { conn.disconnect(); }
    }

    private void download(String expectedSha) {
        if (closed || busy) return;
        busy = true;
        verifiedDownloadSha256=null;
        toast("Beveiligde update downloaden…");
        executor.execute(() -> {
            File tmp = new File(apk.getParentFile(), "update.tmp");
            try {
                if (!apk.getParentFile().isDirectory() && !apk.getParentFile().mkdirs())
                    throw new IOException("Geen updateopslag beschikbaar");
                request("download", tmp, expectedSha);
                verify(tmp);
                if (apk.exists() && !apk.delete()) throw new IOException("Oude update niet te vervangen");
                if (!tmp.renameTo(apk)) throw new IOException("Update opslaan mislukt");
                verifiedDownloadSha256=expectedSha;
                ui.post(() -> { if (!closed) install(); });
            } catch (Exception error) {
                ui.post(() -> { if (!closed) toast("Update afgewezen: "+error.getMessage()); });
            } finally { tmp.delete(); busy = false; }
        });
    }

    /**
     * Android TV firmware, including some Sony Bravia versions, can return a
     * PackageInfo with null signingInfo for an APK on disk. The old updater
     * dereferenced that field and rejected every legitimate download.
     *
     * Prefer modern SigningInfo; fall back to legacy signatures when the
     * archive parser omits signingInfo. If a vendor omits both, the APK is
     * still pinned to the SHA-256 returned by our authenticated server and
     * the operating system's package installer MUST enforce matching signing
     * certificates on an in-place update.
     */
    @SuppressWarnings("deprecation")
    static Signature[] signaturesOf(PackageInfo info) {
        if(info==null)return null;
        if(Build.VERSION.SDK_INT>=28 && info.signingInfo!=null){
            Signature[] current=info.signingInfo.getApkContentsSigners();
            if(current!=null && current.length>0)return current;
        }
        return info.signatures!=null && info.signatures.length>0?
            info.signatures:null;
    }

    private static boolean sameSigner(Signature[] current,Signature[] installed){
        return current!=null && installed!=null && current.length>0 &&
            installed.length>0 &&
            new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(installed)));
    }

    private static String sha256(File candidate) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(candidate)){
            byte[] buf=new byte[32768];
            for(int n;(n=in.read(buf))!=-1;)md.update(buf,0,n);
        }
        return hex(md.digest());
    }

    @SuppressWarnings("deprecation")
    private void verify(File candidate) throws Exception {
        // Never install cached or modified bytes without checking the
        // authenticated update server's verified digest once more.
        String expected=verifiedDownloadSha256;
        if(expected==null || !expected.matches("[a-f0-9]{64}") ||
            !expected.equals(sha256(candidate)))
            throw new SecurityException("Updatebestand niet geverifieerd");
        PackageManager pm=activity.getPackageManager();
        int flags=PackageManager.GET_SIGNATURES;
        if(Build.VERSION.SDK_INT>=28)flags|=PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo next=pm.getPackageArchiveInfo(candidate.getAbsolutePath(),flags);
        PackageInfo installed=pm.getPackageInfo(activity.getPackageName(),flags);
        if(next==null || installed==null ||
            !activity.getPackageName().equals(next.packageName))
            throw new SecurityException("Onjuiste app-update");
        long newCode=Build.VERSION.SDK_INT>=28?
            next.getLongVersionCode():next.versionCode;
        long oldCode=Build.VERSION.SDK_INT>=28?
            installed.getLongVersionCode():installed.versionCode;
        if(newCode<=oldCode)
            throw new SecurityException("Geen nieuwere build");
        Signature[] nextSigners=signaturesOf(next);
        Signature[] installedSigners=signaturesOf(installed);
        if(nextSigners!=null && installedSigners!=null &&
            !sameSigner(nextSigners,installedSigners))
            throw new SecurityException("Ondertekening van APK komt niet overeen");
        // No unchecked vendor-provided null is ever dereferenced. Android's
        // PackageInstaller independently rejects any wrong signing identity.
    }

    private void install() {
        try {
            verify(apk);
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                waitingForInstallPermission = true;
                new AlertDialog.Builder(activity)
                    .setTitle("Installatietoestemming")
                    .setMessage("Sta Android toe om deze app bij te werken.")
                    .setPositiveButton("Instellingen", (d,w) -> activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+activity.getPackageName()))))
                    .setNegativeButton("Later",null).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName()+".updates", apk);
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) { toast("Installatie geblokkeerd: "+error.getMessage()); }
    }
    private void toast(String message) { Toast.makeText(activity,message,Toast.LENGTH_LONG).show(); }
    void close() { closed = true; executor.shutdownNow(); ui.removeCallbacksAndMessages(null); }
}
