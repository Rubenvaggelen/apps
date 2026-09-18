package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SourceConfigActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);
    private static final String DEFAULT_SERVER = "http://line.liondnscloud.ru:80";
    private static final int REQUEST_IMPORT_SOURCE = 7001;

    private SharedPreferences prefs;
    private LinearLayout fields;
    private String selectedType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
        selectedType = prefs.getString("source_type", "STALKER");
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("The One - Bron instellen", 28, Color.WHITE, true);
        root.addView(title);
        TextView sub = text("Kies het type bron. Gebruik alleen je eigen geautoriseerde IPTV-/mediagegevens.", 15, MUTED, false);
        sub.setPadding(0, dp(8), 0, dp(18));
        root.addView(sub);

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setGravity(Gravity.CENTER_VERTICAL);
        addTypeButton(typeRow, "Stalker / MAC", "STALKER");
        addTypeButton(typeRow, "Xtream", "XTREAM");
        addTypeButton(typeRow, "M3U", "M3U");
        root.addView(typeRow);

        fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(0, dp(18), 0, 0);
        root.addView(fields);
        renderFields();

        setContentView(scroll);
    }

    private void addTypeButton(LinearLayout row, String label, String type) {
        Button b = button(label);
        b.setOnClickListener(v -> {
            selectedType = type;
            renderFields();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        row.addView(b, lp);
    }

    private void renderFields() {
        if (fields == null) return;
        fields.removeAllViews();

        TextView current = text("Gekozen: " + displayType(selectedType), 18, BLUE, true);
        current.setPadding(0, 0, 0, dp(12));
        fields.addView(current);

        if ("STALKER".equals(selectedType)) {
            EditText server = input("Server / portal", prefs.getString("stalker_server", DEFAULT_SERVER), false);
            EditText mac = input("MAC-adres, bijvoorbeeld 00:1A:79:xx:xx:xx", prefs.getString("stalker_mac", ""), false);
            EditText portalPath = input("Portalpad (optioneel, bijvoorbeeld /stalker_portal/c/)", prefs.getString("stalker_path", ""), false);
            fields.addView(server);
            fields.addView(spacer());
            fields.addView(mac);
            fields.addView(spacer());
            fields.addView(portalPath);
            fields.addView(spacer());
            fields.addView(info("Stalker/MAC gebruikt je eigen provider-MAC of devicegegevens. Zonder jouw eigen MAC worden geen zenders opgehaald."));
            Button save = button("Opslaan");
            save.setOnClickListener(v -> {
                String s = server.getText().toString().trim();
                String m = mac.getText().toString().trim();
                if (s.isEmpty()) s = DEFAULT_SERVER;
                prefs.edit()
                        .putString("source_type", "STALKER")
                        .putString("stalker_server", normalizeServer(s))
                        .putString("stalker_mac", m)
                        .putString("stalker_path", portalPath.getText().toString().trim())
                        .apply();
                Toast.makeText(this, m.isEmpty() ? "Server opgeslagen. Vul later je eigen MAC in." : "Stalker-bron opgeslagen", Toast.LENGTH_LONG).show();
            });
            fields.addView(save);
        } else if ("XTREAM".equals(selectedType)) {
            EditText server = input("Server", prefs.getString("xtream_server", DEFAULT_SERVER), false);
            EditText user = input("Gebruikersnaam", prefs.getString("xtream_user", ""), false);
            EditText pass = input("Wachtwoord", prefs.getString("xtream_pass", ""), true);
            fields.addView(server);
            fields.addView(spacer());
            fields.addView(user);
            fields.addView(spacer());
            fields.addView(pass);
            fields.addView(spacer());
            fields.addView(info("Xtream gebruikt server + gebruikersnaam + wachtwoord van je eigen abonnement."));
            Button save = button("Opslaan");
            save.setOnClickListener(v -> {
                String s = server.getText().toString().trim();
                if (s.isEmpty()) s = DEFAULT_SERVER;
                prefs.edit()
                        .putString("source_type", "XTREAM")
                        .putString("xtream_server", normalizeServer(s))
                        .putString("xtream_user", user.getText().toString().trim())
                        .putString("xtream_pass", pass.getText().toString())
                        .apply();
                Toast.makeText(this, "Xtream-bron opgeslagen", Toast.LENGTH_SHORT).show();
            });
            fields.addView(save);
        } else {
            EditText m3u = input("M3U / M3U8 URL", prefs.getString("m3u_url", ""), false);
            EditText epg = input("EPG / XMLTV URL (optioneel)", prefs.getString("epg_url", ""), false);
            fields.addView(m3u);
            fields.addView(spacer());
            fields.addView(epg);
            fields.addView(spacer());
            fields.addView(info("M3U ondersteunt een eigen playlist-URL. EPG/XMLTV kan later voor de tv-gids worden gebruikt. Voor privé bronnen kun je ook een bronbestand importeren, zodat inloggegevens niet in openbare broncode hoeven te staan."));

            Button importSource = button("Privé bronbestand importeren");
            importSource.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
                startActivityForResult(intent, REQUEST_IMPORT_SOURCE);
            });
            fields.addView(importSource);
            fields.addView(spacer());

            Button save = button("Opslaan");
            save.setOnClickListener(v -> {
                String url = m3u.getText().toString().trim();
                if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "Gebruik een geldige http(s)-URL", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit()
                        .putString("source_type", "M3U")
                        .putString("m3u_url", url)
                        .putString("epg_url", epg.getText().toString().trim())
                        .apply();
                Toast.makeText(this, "M3U-bron opgeslagen", Toast.LENGTH_SHORT).show();
            });
            fields.addView(save);
        }

        TextView catalogTitle = text("Film- & seriecatalogus", 20, BLUE, true);
        LinearLayout.LayoutParams catalogTitleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        catalogTitleLp.topMargin = dp(20);
        fields.addView(catalogTitle, catalogTitleLp);

        EditText tmdb = input(
                "TMDB API Read Access Token",
                prefs.getString("tmdb_token", ""),
                true
        );
        fields.addView(tmdb);
        fields.addView(spacer());
        fields.addView(info(
                "De catalogus gebruikt TMDB voor films, series, posters, seizoenen en afleveringen. "
                        + "De token wordt alleen lokaal in deze app opgeslagen en niet in GitHub gezet. "
                        + "This product uses the TMDB API but is not endorsed or certified by TMDB."
        ));

        Button saveTmdb = button("TMDB-token opslaan");
        saveTmdb.setOnClickListener(v -> {
            prefs.edit().putString("tmdb_token", tmdb.getText().toString().trim()).apply();
            Toast.makeText(this, "TMDB-token opgeslagen", Toast.LENGTH_SHORT).show();
        });
        fields.addView(saveTmdb);

        Button close = button("← Terug naar Media Player");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(18);
        fields.addView(close, closeLp);
        close.setOnClickListener(v -> finish());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_SOURCE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            String raw = readAll(uri).trim();
            String url = raw;
            String epg = "";

            if (raw.startsWith("{")) {
                JSONObject json = new JSONObject(raw);
                url = json.optString("m3u_url", json.optString("url", "")).trim();
                epg = json.optString("epg_url", "").trim();
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "Dit bronbestand bevat geen geldige M3U-URL", Toast.LENGTH_LONG).show();
                return;
            }

            prefs.edit()
                    .putString("source_type", "M3U")
                    .putString("m3u_url", url)
                    .putString("epg_url", epg)
                    .apply();

            selectedType = "M3U";
            Toast.makeText(this, "Privé M3U-bron geïmporteerd", Toast.LENGTH_LONG).show();
            renderFields();
        } catch (Exception e) {
            Toast.makeText(this, "Bronbestand kon niet worden gelezen", Toast.LENGTH_LONG).show();
        }
    }

    private String readAll(Uri uri) throws Exception {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalStateException("Geen invoer");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        }
    }

    private String displayType(String type) {
        if ("XTREAM".equals(type)) return "Xtream";
        if ("M3U".equals(type)) return "M3U";
        return "Stalker / MAC";
    }

    private String normalizeServer(String value) {
        String s = value.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private EditText input(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(Color.WHITE);
        e.setText(value);
        e.setSingleLine(true);
        e.setPadding(dp(14), dp(14), dp(14), dp(14));
        e.setBackgroundColor(PANEL);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private TextView info(String value) {
        TextView t = text(value, 14, MUTED, false);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView spacer() {
        TextView t = new TextView(this);
        t.setHeight(dp(10));
        return t;
    }

    private Button button(String label) {
        Button b = PremiumUi.primaryButton(this, label);
        b.setFocusable(true);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
