package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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

public class SourceConfigActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);
    private static final String DEFAULT_SERVER = "http://line.liondnscloud.ru:80";

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
            fields.addView(info("M3U ondersteunt een eigen playlist-URL. EPG/XMLTV kan later voor de tv-gids worden gebruikt."));
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

        Button close = button("← Terug naar Media Player");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(18);
        fields.addView(close, closeLp);
        close.setOnClickListener(v -> finish());
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
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 92, 130)));
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
