package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class MediaPlayerUpdateChecker {
    private static final String RELEASES_URL =
            "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=40";
    private static final String TAG_PREFIX = "media-player-v";

    private MediaPlayerUpdateChecker() {}

    public static void checkForUpdate(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "TheOneMediaPlayer-Updater");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) return;

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }

                JSONArray releases = new JSONArray(body.toString());
                UpdateInfo newest = null;

                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null || release.optBoolean("draft", false)) continue;

                    String tag = release.optString("tag_name", "");
                    if (!tag.startsWith(TAG_PREFIX)) continue;

                    int versionCode;
                    try {
                        versionCode = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
                    } catch (Throwable ignored) {
                        continue;
                    }

                    String downloadUrl = "";
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int a = 0; a < assets.length(); a++) {
                            JSONObject asset = assets.optJSONObject(a);
                            if (asset == null) continue;
                            String name = asset.optString("name", "").toLowerCase();
                            if (name.endsWith(".apk") &&
                                    (name.contains("media-player") || name.contains("mediaplayer"))) {
                                downloadUrl = asset.optString("browser_download_url", "");
                                if (!downloadUrl.isEmpty()) break;
                            }
                        }
                    }

                    if (downloadUrl.isEmpty()) {
                        downloadUrl = release.optString("html_url", "");
                    }
                    if (downloadUrl.isEmpty()) continue;

                    if (newest == null || versionCode > newest.versionCode) {
                        newest = new UpdateInfo(
                                versionCode,
                                release.optString("name", tag),
                                downloadUrl
                        );
                    }
                }

                if (newest == null) return;

                long currentVersion = getCurrentVersionCode(activity);
                if (newest.versionCode <= currentVersion) return;

                UpdateInfo finalNewest = newest;
                activity.runOnUiThread(() -> showUpdateDialog(activity, finalNewest));
            } catch (Throwable ignored) {
                // Update-check mag nooit de Media Player blokkeren.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private static long getCurrentVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo update) {
        if (activity.isFinishing()) return;

        new AlertDialog.Builder(activity)
                .setTitle("Nieuwe versie beschikbaar")
                .setMessage(
                        "Er is een nieuwe versie van The One Media Player beschikbaar. "
                                + "Wil je die nu downloaden?"
                )
                .setPositiveButton("Downloaden", (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(update.downloadUrl)
                        ));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private static class UpdateInfo {
        final int versionCode;
        final String name;
        final String downloadUrl;

        UpdateInfo(int versionCode, String name, String downloadUrl) {
            this.versionCode = versionCode;
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }
}
