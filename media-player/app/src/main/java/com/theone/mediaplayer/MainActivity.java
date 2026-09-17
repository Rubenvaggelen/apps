package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);

    private FrameLayout content;
    private ExoPlayer player;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
        showShell("Live TV");
    }

    private void showShell(String section) {
        releasePlayer();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = text("◉  THE ONE", 24, BLUE, true);
        header.addView(logo);
        TextView title = text("   MEDIA PLAYER", 20, Color.WHITE, true);
        header.addView(title);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(16), 0, dp(14));
        addNavButton(nav, "Live TV", () -> showSection("Live TV", "Je zenders komen hier zodra we jouw bron koppelen."));
        addNavButton(nav, "Films", () -> showSection("Films", "Films en categorieën worden straks uit jouw bron geladen."));
        addNavButton(nav, "Series", () -> showSection("Series", "Series, seizoenen en afleveringen komen hier."));
        addNavButton(nav, "Verder kijken", () -> showSection("Verder kijken", "Je kijkvoortgang verschijnt hier."));
        addNavButton(nav, "Favorieten", () -> showSection("Favorieten", "Je favoriete zenders, films en series verschijnen hier."));
        addNavButton(nav, "Instellingen", this::showSettings);
        navScroll.addView(nav);
        root.addView(navScroll);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        showSection(section, "Je zenders komen hier zodra we jouw bron koppelen.");
    }

    private void showSection(String title, String subtitle) {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(12), dp(6), dp(18));
        box.addView(text(title, 32, Color.WHITE, true));
        TextView sub = text(subtitle, 17, MUTED, false);
        sub.setPadding(0, dp(8), 0, dp(22));
        box.addView(sub);

        String[] cards = title.equals("Live TV")
                ? new String[]{"Favoriete zenders", "Alle zenders", "Sport", "Nieuws", "Entertainment"}
                : title.equals("Films")
                ? new String[]{"Nieuw", "Actie", "Comedy", "Thriller", "Familie"}
                : title.equals("Series")
                ? new String[]{"Verder kijken", "Nieuw", "Drama", "Crime", "Comedy"}
                : new String[]{"Nog leeg", "Klaar voor synchronisatie"};

        for (String card : cards) {
            TextView tile = text(card, 20, Color.WHITE, true);
            tile.setBackgroundColor(PANEL);
            tile.setPadding(dp(20), dp(22), dp(20), dp(22));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            box.addView(tile, lp);
        }
        TextView note = text("Ga naar Instellingen om later je TV/film/series-URL toe te voegen.", 16, BLUE, true);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);
        scroll.addView(box);
        content.addView(scroll);
    }

    private void showSettings() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(12), dp(6), dp(18));
        box.addView(text("Instellingen", 32, Color.WHITE, true));
        TextView info = text("Je echte TV/film/series-bron voegen we later toe. Je kunt nu al een losse stream-URL opslaan en testen.", 17, MUTED, false);
        info.setPadding(0, dp(8), 0, dp(18));
        box.addView(info);

        EditText url = new EditText(this);
        url.setTextColor(Color.WHITE);
        url.setHintTextColor(MUTED);
        url.setHint("Bron- of test-URL");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("source_url", ""));
        url.setPadding(dp(14), dp(14), dp(14), dp(14));
        url.setBackgroundColor(PANEL);
        box.addView(url, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(16), 0, dp(18));
        Button save = button("Opslaan");
        save.setOnClickListener(v -> {
            prefs.edit().putString("source_url", url.getText().toString().trim()).apply();
            Toast.makeText(this, "Bron opgeslagen", Toast.LENGTH_SHORT).show();
        });
        actions.addView(save);
        Button test = button("Test afspelen");
        test.setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                prefs.edit().putString("source_url", value).apply();
                showPlayer(value);
            } else {
                Toast.makeText(this, "Vul eerst een geldige http(s)-URL in", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testLp.leftMargin = dp(12);
        actions.addView(test, testLp);
        box.addView(actions);

        TextView multi = text("Meerdere apparaten\n\nDeze APK kan op meerdere Android-telefoons en Android/Google TV's worden geïnstalleerd. Account-sync voor favorieten en kijkvoortgang bouwen we daarna in.", 17, Color.WHITE, false);
        multi.setBackgroundColor(PANEL);
        multi.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.addView(multi);
        scroll.addView(box);
        content.addView(scroll);
    }

    private void showPlayer(String url) {
        releasePlayer();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        PlayerView playerView = new PlayerView(this);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(true);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        root.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button back = button("← Terug");
        back.setOnClickListener(v -> showShell("Live TV"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(16), dp(16), 0, 0);
        root.addView(back, lp);
        setContentView(root);
    }

    private void addNavButton(LinearLayout parent, String label, Runnable action) {
        Button b = button(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        parent.addView(b, lp);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
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

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }
}
