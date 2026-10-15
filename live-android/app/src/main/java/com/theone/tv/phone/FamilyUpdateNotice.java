package com.theone.tv.phone;

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
                ui.post(() -> { if (!closed) install(); });
            } catch (Exception error) {
                ui.post(() -> { if (!closed) toast("Update afgewezen: "+error.getMessage()); });
            } finally { tmp.delete(); busy = false; }
        });
    }

    @SuppressWarnings("deprecation")
    private void verify(File candidate) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo next = pm.getPackageArchiveInfo(candidate.getAbsolutePath(), flags);
        PackageInfo old = pm.getPackageInfo(activity.getPackageName(), flags);
        if (next == null || !activity.getPackageName().equals(next.packageName))
            throw new SecurityException("Onjuiste app-update");
        long n = Build.VERSION.SDK_INT >= 28 ? next.getLongVersionCode() : next.versionCode;
        long o = Build.VERSION.SDK_INT >= 28 ? old.getLongVersionCode() : old.versionCode;
        if (n <= o) throw new SecurityException("Geen nieuwere build");
        Signature[] aa = Build.VERSION.SDK_INT >= 28 ? next.signingInfo.getApkContentsSigners() : next.signatures;
        Signature[] bb = Build.VERSION.SDK_INT >= 28 ? old.signingInfo.getApkContentsSigners() : old.signatures;
        if (aa == null || bb == null || aa.length == 0 ||
            !new HashSet<>(Arrays.asList(aa)).equals(new HashSet<>(Arrays.asList(bb))))
            throw new SecurityException("Ondertekening van APK komt niet overeen");
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
