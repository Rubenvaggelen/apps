package com.theone.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class XtreamConfig {
    public final String server;
    public final String username;
    public final String password;

    private XtreamConfig(String server, String username, String password) {
        this.server = normalize(server);
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
    }

    public static XtreamConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("media_player", Context.MODE_PRIVATE);

        XtreamConfig saved = new XtreamConfig(
                prefs.getString("xtream_server", ""),
                prefs.getString("xtream_user", ""),
                prefs.getString("xtream_pass", "")
        );
        if (saved.isConfigured()) {
            return saved;
        }

        try (InputStream in = context.getAssets().open("private_xtream.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
            JSONObject json = new JSONObject(raw.toString());

            XtreamConfig bundled = new XtreamConfig(
                    json.optString("server", ""),
                    json.optString("username", ""),
                    json.optString("password", "")
            );

            if (bundled.isConfigured()) {
                prefs.edit()
                        .putString("source_type", "XTREAM")
                        .putString("xtream_server", bundled.server)
                        .putString("xtream_user", bundled.username)
                        .putString("xtream_pass", bundled.password)
                        .apply();
            }

            return bundled;
        } catch (Throwable ignored) {
            return new XtreamConfig("", "", "");
        }
    }

    public boolean isConfigured() {
        return server.startsWith("http")
                && !username.isEmpty()
                && !password.isEmpty()
                && !username.startsWith("PRIVATE_")
                && !password.startsWith("PRIVATE_");
    }

    public String api(String action) {
        return server + "/player_api.php?username=" + query(username)
                + "&password=" + query(password)
                + "&action=" + query(action);
    }

    public String api(String action, String key, String value) {
        return api(action) + "&" + query(key) + "=" + query(value);
    }

    public String stream(String type, String id, String extension) {
        String prefix;
        if ("movie".equals(type)) prefix = "movie";
        else if ("series".equals(type)) prefix = "series";
        else prefix = "live";
        String ext = extension == null || extension.trim().isEmpty()
                ? ("live".equals(prefix) ? "ts" : "mp4")
                : extension.trim().replace(".", "");
        return server + "/" + prefix + "/" + pathCredential(username) + "/" + pathCredential(password)
                + "/" + id + "." + ext;
    }

    private static String pathCredential(String value) {
        return Uri.encode(value == null ? "" : value, "@");
    }

    private static String query(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static String normalize(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
