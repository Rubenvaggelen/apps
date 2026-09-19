package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class MediaPlayerUpdateChecker {
    private static final String RELEASES_URL =
            "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=40";
    private static final String TAG_PREFIX = "media-player-v";
    private static final String APK_MIME = "application/vnd.android.package-archive";

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
                            if (!name.endsWith(".apk")) continue;
                            if (!(name.contains("media-player") || name.contains("mediaplayer"))) continue;
                            if (name.contains("android-tv") || name.contains("-tv") || name.contains("_tv")) continue;

                            downloadUrl = asset.optString("browser_download_url", "");
                            if (!downloadUrl.isEmpty()) break;
                        }
                    }

                    // Geen webpagina meer openen: alleen een echte APK is geldig als update.
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
                // Update-check mag de Media Player nooit blokkeren.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private static long getCurrentVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo update) {
        if (activity.isFinishing()) return;

        new AlertDialog.Builder(activity)
                .setTitle("Nieuwe versie beschikbaar")
                .setMessage("Er is een nieuwe versie van The One Media Player beschikbaar.")
                .setPositiveButton("Bijwerken", (dialog, which) -> beginUpdate(activity, update))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void beginUpdate(Activity activity, UpdateInfo update) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(settings);
                Toast.makeText(
                        activity,
                        "Sta installatie van updates toe en kies daarna opnieuw Bijwerken.",
                        Toast.LENGTH_LONG
                ).show();
            } catch (Throwable ignored) {
            }
            return;
        }

        try {
            DownloadManager manager =
                    (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                Toast.makeText(activity, "Update kon niet worden gestart.", Toast.LENGTH_SHORT).show();
                return;
            }

            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();

            String fileName = "The-One-Media-Player-v" + update.versionCode + ".apk";
            if (dir != null) {
                File previous = new File(dir, fileName);
                if (previous.exists()) previous.delete();
            }

            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(update.downloadUrl))
                            .setTitle("The One Media Player update")
                            .setDescription("Versie " + update.versionCode + " wordt gedownload")
                            .setMimeType(APK_MIME)
                            .setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            .setAllowedOverMetered(true)
                            .setAllowedOverRoaming(true)
                            .setDestinationInExternalFilesDir(
                                    activity,
                                    Environment.DIRECTORY_DOWNLOADS,
                                    fileName
                            );

            long downloadId = manager.enqueue(request);
            Toast.makeText(activity, "Update wordt gedownload…", Toast.LENGTH_SHORT).show();

            new Thread(() -> waitForDownload(activity, manager, downloadId)).start();
        } catch (Throwable e) {
            Toast.makeText(activity, "Update kon niet worden gestart.", Toast.LENGTH_SHORT).show();
        }
    }

    private static void waitForDownload(
            Activity activity,
            DownloadManager manager,
            long downloadId
    ) {
        long end = System.currentTimeMillis() + (10L * 60L * 1000L);

        while (System.currentTimeMillis() < end) {
            Cursor cursor = null;
            try {
                DownloadManager.Query query =
                        new DownloadManager.Query().setFilterById(downloadId);
                cursor = manager.query(query);

                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status = statusIndex >= 0
                            ? cursor.getInt(statusIndex)
                            : DownloadManager.STATUS_FAILED;

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri apkUri = manager.getUriForDownloadedFile(downloadId);
                        activity.runOnUiThread(() -> openInstaller(activity, apkUri));
                        return;
                    }

                    if (status == DownloadManager.STATUS_FAILED) {
                        activity.runOnUiThread(() -> Toast.makeText(
                                activity,
                                "Download van de update is mislukt.",
                                Toast.LENGTH_LONG
                        ).show());
                        return;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }

            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                return;
            }
        }

        activity.runOnUiThread(() -> Toast.makeText(
                activity,
                "De update-download duurde te lang.",
                Toast.LENGTH_LONG
        ).show());
    }

    private static void openInstaller(Activity activity, Uri apkUri) {
        if (apkUri == null || activity.isFinishing()) return;

        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, APK_MIME);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Throwable e) {
            Toast.makeText(
                    activity,
                    "De Android-installatie kon niet worden geopend.",
                    Toast.LENGTH_LONG
            ).show();
        }
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
