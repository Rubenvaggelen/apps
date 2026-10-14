package com.theone.tv.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows updates for this exact Android phone APK only. Never installs another Family app. */
final class FamilyUpdateNotice {
    private static final String ASSET_PREFIX = "The-One-TV-Android-v";
    private static final String ENDPOINT = "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100";
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastCheck = 0L;
    private int lastOffered = 0;
    FamilyUpdateNotice(Activity activity) { this.activity = activity; }
    void checkOnOpen() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastCheck != 0 && now - lastCheck < 900000L) return;
        lastCheck = now;
        worker.execute(() -> {
            try {
                PackageInfo installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long current = android.os.Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
                HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "TheOne-TV-Android-Updater");
                JSONArray releases;
                try {
                    if (connection.getResponseCode() != 200) return;
                    StringBuilder json = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) json.append(line);
                    }
                    releases = new JSONArray(json.toString());
                } finally { connection.disconnect(); }
                int best = 0;
                String target = null;
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
                        String[] parts = version.split("\\.");
                        if (parts.length != 3) continue;
                        int versionCode = Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1]) * 10 + Integer.parseInt(parts[2]);
                        String url = asset.optString("browser_download_url");
                        if (!url.startsWith("https://github.com/Rubenvaggelen/apps/releases/download/")) continue;
                        if (versionCode > current && versionCode > best) {
                            best = versionCode;
                            target = url;
                        }
                    }
                }
                final int chosenVersion = best;
                final String chosenUrl = target;
                if (chosenUrl == null) return;
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || chosenVersion <= lastOffered) return;
                    lastOffered = chosenVersion;
                    new AlertDialog.Builder(activity)
                        .setTitle("The One TV-update")
                        .setMessage("Er is een nieuwere Android-versie beschikbaar. Open de officiële release om de update te installeren.")
                        .setPositiveButton("Update openen", (dialog, button) -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(chosenUrl))))
                        .setNegativeButton("Later", null).show();
                });
            } catch (Exception ignored) {
                // An unavailable update service must not interrupt TV playback.
            }
        });
    }
    void close() { worker.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
