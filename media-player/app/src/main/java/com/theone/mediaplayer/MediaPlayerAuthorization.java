package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time person registration + owner-controlled Media Player authorization.
 * The server-side right is shared with The One Main device management.
 */
public final class MediaPlayerAuthorization {
    private static final String ENDPOINT =
            "https://rubenvanaggelen.com/the-one-remote-api/devices.php";
    private static final String PREFS = "media_player_authorization";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PERSON_NAME = "person_name";
    private static final String KEY_ALLOWED = "media_player_allowed";
    private static final String ACCESS_MEDIA_PLAYER = "media_player";
    private static final long RECHECK_MS = 8000L;

    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int TEXT = Color.rgb(236, 242, 247);
    private static final int MUTED = Color.rgb(154, 166, 178);

    private MediaPlayerAuthorization() {}

    public static void requireAccess(Activity activity, Runnable onAllowed) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String personName = prefs.getString(KEY_PERSON_NAME, "");
        if (personName == null || personName.trim().isEmpty()) {
            showNameScreen(activity, onAllowed);
            return;
        }

        showCheckingScreen(activity, "Toegang controleren…");
        verify(activity, onAllowed, false);
    }

    private static void showNameScreen(Activity activity, Runnable onAllowed) {
        LinearLayout root = baseRoot(activity);

        TextView title = title(activity, "Welkom bij The One Media Player");
        root.addView(title);

        TextView message = body(activity,
                "Vul éénmalig je naam in. Daarna stuur ik een toegangsaanvraag naar The One. " +
                "De Media Player blijft vergrendeld totdat de beheerder toestemming geeft.");
        root.addView(message, margins(-1, -2, 0, 12, 0, 18));

        EditText name = new EditText(activity);
        name.setHint("Jouw naam");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setTextColor(TEXT);
        name.setHintTextColor(MUTED);
        name.setBackground(PremiumUi.card(activity));
        name.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        root.addView(name, margins(-1, -2, 0, 0, 0, 12));

        Button submit = button(activity, "Toegang aanvragen");
        root.addView(submit, margins(-1, -2, 0, 0, 0, 8));

        TextView info = body(activity,
                "Je naam wordt aan dit apparaat gekoppeld en verschijnt bij Apparaten beheren.");
        root.addView(info);

        submit.setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError("Vul je naam in");
                return;
            }
            if (value.length() > 80) value = value.substring(0, 80);

            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PERSON_NAME, value)
                    .apply();

            showCheckingScreen(activity, "Apparaat registreren…");
            verify(activity, onAllowed, true);
        });

        activity.setContentView(root);
    }

    private static void showCheckingScreen(Activity activity, String text) {
        LinearLayout root = baseRoot(activity);
        root.setGravity(Gravity.CENTER);

        TextView logo = title(activity, "THE ONE");
        logo.setTextColor(BLUE);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);

        TextView status = body(activity, text);
        status.setGravity(Gravity.CENTER);
        root.addView(status, margins(-1, -2, 0, 12, 0, 0));

        activity.setContentView(root);
    }

    private static void showWaitingScreen(
            Activity activity,
            Runnable onAllowed,
            boolean blocked,
            String error
    ) {
        LinearLayout root = baseRoot(activity);
        root.setGravity(Gravity.CENTER);

        TextView title = title(activity, blocked ? "Apparaat geblokkeerd" : "Wachten op toestemming");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        String name = personName(activity);
        String message;
        if (blocked) {
            message = "Dit apparaat is door de beheerder geblokkeerd.";
        } else if (error != null && !error.trim().isEmpty()) {
            message = "De autorisatie kon niet worden gecontroleerd. Controleer je verbinding en probeer opnieuw.";
        } else {
            message = "De aanvraag voor " + (name.isEmpty() ? "dit apparaat" : name) +
                    " staat klaar in The One → Apparaten beheren → Media Player.";
        }

        TextView body = body(activity, message);
        body.setGravity(Gravity.CENTER);
        root.addView(body, margins(-1, -2, 0, 12, 0, 18));

        Button retry = button(activity, "Opnieuw controleren");
        root.addView(retry, margins(-1, -2, 0, 0, 0, 8));
        retry.setOnClickListener(v -> {
            showCheckingScreen(activity, "Toegang opnieuw controleren…");
            verify(activity, onAllowed, false);
        });

        TextView device = body(activity, "Apparaat-ID: " + deviceId(activity));
        device.setTextSize(11f);
        device.setGravity(Gravity.CENTER);
        root.addView(device);

        activity.setContentView(root);

        if (!blocked && (error == null || error.trim().isEmpty())) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> {
                if (!activity.isFinishing()) {
                    verify(activity, onAllowed, false);
                }
            }, RECHECK_MS);
        }
    }

    private static void verify(Activity activity, Runnable onAllowed, boolean forceRequest) {
        AtomicBoolean completed = new AtomicBoolean(false);
        new Thread(() -> {
            try {
                JSONObject heartbeat = request("heartbeat",
                        new JSONObject()
                                .put("device_id", deviceId(activity))
                                .put("name", deviceName())
                                .put("person_name", personName(activity))
                                .put("platform", "Media Player • Android " + Build.VERSION.RELEASE)
                                .put("version", BuildConfig.VERSION_CODE)
                                .put("device_role", "main"));

                if (heartbeat.optBoolean("blocked", false)) {
                    cacheAllowed(activity, false);
                    runUi(activity, () -> showWaitingScreen(activity, onAllowed, true, null));
                    return;
                }

                JSONObject status = request("access_status",
                        new JSONObject()
                                .put("device_id", deviceId(activity))
                                .put("scope", ACCESS_MEDIA_PLAYER));

                if (status.optBoolean("allowed", false)) {
                    cacheAllowed(activity, true);
                    if (completed.compareAndSet(false, true)) {
                        runUi(activity, onAllowed);
                    }
                    return;
                }

                boolean pending = status.optBoolean("pending", false);
                if (!pending || forceRequest) {
                    JSONObject requested = request("request_access",
                            new JSONObject()
                                    .put("device_id", deviceId(activity))
                                    .put("scope", ACCESS_MEDIA_PLAYER));
                    if (requested.optBoolean("allowed", false)) {
                        cacheAllowed(activity, true);
                        if (completed.compareAndSet(false, true)) {
                            runUi(activity, onAllowed);
                        }
                        return;
                    }
                }

                cacheAllowed(activity, false);
                runUi(activity, () -> showWaitingScreen(activity, onAllowed, false, null));
            } catch (Throwable error) {
                cacheAllowed(activity, false);
                runUi(activity, () -> showWaitingScreen(
                        activity,
                        onAllowed,
                        false,
                        error.getMessage()
                ));
            }
        }, "media-player-auth").start();
    }

    private static JSONObject request(String action, JSONObject body) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(ENDPOINT + "?action=" + action).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(6500);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "TheOneMediaPlayer-Authorization");
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream(),
                    java.nio.charset.StandardCharsets.UTF_8
            ));
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
            reader.close();

            JSONObject json = new JSONObject(raw.toString().isEmpty() ? "{}" : raw.toString());
            if (code < 200 || code >= 300 || !json.optBoolean("ok", false)) {
                throw new IllegalStateException(json.optString("error", "Serverfout " + code));
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private static String personName(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PERSON_NAME, "");
        return value == null ? "" : value.trim();
    }

    private static String deviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_DEVICE_ID, "");
        if (current != null && !current.trim().isEmpty()) return current.trim();

        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    private static void cacheAllowed(Context context, boolean allowed) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALLOWED, allowed)
                .apply();
    }

    private static String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String value = (manufacturer + " " + model).trim();
        return value.isEmpty() ? "Media Player apparaat" : value + " • Media Player";
    }

    private static void runUi(Activity activity, Runnable runnable) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) runnable.run();
        });
    }

    private static LinearLayout baseRoot(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(activity, 28), dp(activity, 28), dp(activity, 28), dp(activity, 28));
        root.setBackgroundColor(BG);
        return root;
    }

    private static TextView title(Activity activity, String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(TEXT);
        view.setTextSize(25f);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private static TextView body(Activity activity, String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(MUTED);
        view.setTextSize(15f);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private static Button button(Activity activity, String value) {
        Button button = PremiumUi.primaryButton(activity, value);
        button.setMinimumHeight(dp(activity, 48));
        return button;
    }

    private static LinearLayout.LayoutParams margins(
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom
    ) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                height == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
