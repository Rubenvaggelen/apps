package com.theone.series;

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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-specific update checker: download -> validate package/version/signature -> Android installer. */
final class FamilyUpdateNotice {
    private static final String ASSET_PREFIX = "The-One-Series-Android-v";
    private static final String ENDPOINT = "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100";
    private static final String RELEASE_PREFIX = "https://github.com/Rubenvaggelen/apps/releases/download/";
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File apk;
    private boolean closed;
    private boolean busy;
    private boolean awaitingPermission;
    private long lastCheck;
    private String lastOffered;

    FamilyUpdateNotice(Activity activity) {
        this.activity = activity;
        this.apk = new File(activity.getCacheDir(), "updates/family-update.apk");
    }

    void checkOnOpen() {
        if (closed || busy) return;
        if (awaitingPermission && activity.getPackageManager().canRequestPackageInstalls()) {
            awaitingPermission = false;
            installVerifiedApk();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastCheck != 0 && now - lastCheck < 15L * 60L * 1000L) return;
        lastCheck = now;
        worker.execute(() -> {
            try {
                PackageInfo current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                String currentVersion = current.versionName == null ? "0.0.0" : current.versionName;
                String selectedVersion = currentVersion;
                String selectedUrl = null;
                HttpURLConnection conn = connect(ENDPOINT);
                JSONArray releases;
                try {
                    if (conn.getResponseCode() != 200) return;
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    releases = new JSONArray(body.toString());
                } finally { conn.disconnect(); }
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.getJSONObject(j);
                        String name = asset.optString("name");
                        if (!name.startsWith(ASSET_PREFIX) || !name.endsWith(".apk")) continue;
                        String version = name.substring(ASSET_PREFIX.length(), name.length() - 4);
                        if (compareVersions(version, selectedVersion) <= 0) continue;
                        String url = asset.optString("browser_download_url");
                        if (!url.startsWith(RELEASE_PREFIX)) continue;
                        selectedVersion = version;
                        selectedUrl = url;
                    }
                }
                final String url = selectedUrl;
                final String version = selectedVersion;
                if (url == null) return;
                ui(() -> {
                    if (version.equals(lastOffered)) return;
                    lastOffered = version;
                    new AlertDialog.Builder(activity)
                        .setTitle("The One Series-update")
                        .setMessage("Versie " + version + " is beschikbaar. Downloaden en veilig controleren?")
                        .setPositiveButton("Bijwerken", (dialog, button) -> download(url))
                        .setNegativeButton("Later", null)
                        .show();
                });
            } catch (Exception ignored) {
                // Never interrupt playback if the update service is unavailable.
            }
        });
    }

    static int compareVersions(String a, String b) {
        if (!a.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !b.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return -1;
        String[] first = a.split("\\.");
        String[] second = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            long left = Long.parseLong(first[i]), right = Long.parseLong(second[i]);
            if (left != right) return Long.compare(left, right);
        }
        return 0;
    }

    private HttpURLConnection connect(String link) throws Exception {
        if (!link.startsWith("https://")) throw new IllegalArgumentException("HTTPS vereist");
        HttpURLConnection conn = (HttpURLConnection) new URL(link).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "TheOne-Family-AppUpdater");
        conn.setRequestProperty("Cache-Control", "no-cache");
        return conn;
    }

    private void download(String link) {
        if (busy || closed) return;
        busy = true;
        toast("Update wordt gedownload en gecontroleerd.");
        worker.execute(() -> {
            File tmp = new File(apk.getParentFile(), "download.tmp");
            try {
                if (!apk.getParentFile().isDirectory() && !apk.getParentFile().mkdirs())
                    throw new IllegalStateException("Kan update-map niet maken");
                HttpURLConnection conn = connect(link);
                try {
                    if (conn.getResponseCode() != 200) throw new IllegalStateException("Download HTTP " + conn.getResponseCode());
                    long total = 0;
                    try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            total += count;
                            if (total > MAX_APK_BYTES) throw new IllegalStateException("APK groter dan limiet");
                            out.write(buffer, 0, count);
                        }
                    }
                    if (total == 0) throw new IllegalStateException("Lege APK");
                } finally { conn.disconnect(); }
                verify(tmp);
                if (apk.exists() && !apk.delete()) throw new IllegalStateException("Oude download niet verwijderbaar");
                if (!tmp.renameTo(apk)) throw new IllegalStateException("Update opslaan mislukt");
                ui(this::installVerifiedApk);
            } catch (Exception error) {
                ui(() -> toast("Update niet geïnstalleerd: " + error.getMessage()));
            } finally {
                tmp.delete();
                busy = false;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void verify(File candidate) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(candidate.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName))
            throw new IllegalStateException("Onjuist app-pakket");
        long newer = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (newer <= current) throw new IllegalStateException("Geen nieuwere build");
        Signature[] oldCerts = Build.VERSION.SDK_INT >= 28
            ? (installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners())
            : installed.signatures;
        Signature[] newCerts = Build.VERSION.SDK_INT >= 28
            ? (archive.signingInfo == null ? null : archive.signingInfo.getApkContentsSigners())
            : archive.signatures;
        if (oldCerts == null || newCerts == null || oldCerts.length == 0
            || !new HashSet<>(Arrays.asList(oldCerts)).equals(new HashSet<>(Arrays.asList(newCerts))))
            throw new IllegalStateException("Ondertekening komt niet overeen");
    }

    private void installVerifiedApk() {
        try {
            verify(apk);
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                awaitingPermission = true;
                new AlertDialog.Builder(activity)
                    .setTitle("Update-installatie toestaan")
                    .setMessage("Android moet deze app toestemming geven om APK-updates te installeren.")
                    .setPositiveButton("Instellingen", (d, w) -> activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))))
                    .setNegativeButton("Later", null).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Exception error) {
            toast("Installatie gestopt: " + error.getMessage());
        }
    }

    private void ui(Runnable action) {
        main.post(() -> {
            if (!closed && !activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
