package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);

    private static final String APPLE_HLS = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8";
    private static final String MUX_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
    private static final String BBB_MP4 = "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4";
    private static final String SINTEL_MKV = "https://download.blender.org/durian/movies/Sintel.2010.720p.mkv";
    private static final String BBB_YOUTUBE = "https://www.youtube.com/watch?v=aqz-KE-bpKQ";
    private static final String NASA_YOUTUBE = "https://www.youtube.com/@NASA/live";

    private static final String DEMO_M3U =
            "#EXTM3U\n" +
            "#EXTINF:-1 group-title=\"Live TV\",Apple HLS Test\n" + APPLE_HLS + "\n" +
            "#EXTINF:-1 group-title=\"Live TV\",Mux Big Buck Bunny HLS\n" + MUX_HLS + "\n" +
            "#EXTINF:-1 group-title=\"Live TV\",NASA Live (YouTube)\n" + NASA_YOUTUBE + "\n" +
            "#EXTINF:-1 group-title=\"Films\",Big Buck Bunny\n" + BBB_MP4 + "\n" +
            "#EXTINF:-1 group-title=\"Films\",Sintel\n" + SINTEL_MKV + "\n" +
            "#EXTINF:-1 group-title=\"YouTube\",Big Buck Bunny - Blender Official\n" + BBB_YOUTUBE + "\n";

    private FrameLayout content;
    private ExoPlayer player;
    private PlayerView activePlayerView;
    private SharedPreferences prefs;
    private boolean playerFullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
        showShell("Live TV");
    }

    private void showShell(String section) {
        playerFullscreen = false;
        releasePlayer();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("◉  THE ONE", 24, BLUE, true));
        header.addView(text("   MEDIA PLAYER TEST", 20, Color.WHITE, true));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(16), 0, dp(14));
        addNavButton(nav, "Live TV", this::showLiveTv);
        addNavButton(nav, "Films", this::showFilms);
        addNavButton(nav, "Series", () -> showSection("Series", "Series komen hier zodra jouw echte bron is gekoppeld."));
        addNavButton(nav, "Test M3U", this::showDemoM3u);
        addNavButton(nav, "Verder kijken", () -> showSection("Verder kijken", "Je kijkvoortgang verschijnt hier."));
        addNavButton(nav, "Favorieten", () -> showSection("Favorieten", "Je favoriete zenders, films en series verschijnen hier."));
        addNavButton(nav, "Instellingen", this::showSettings);
        navScroll.addView(nav);
        root.addView(navScroll);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        root.post(this::exitImmersiveFullscreen);

        if ("Films".equals(section)) showFilms();
        else if ("Test M3U".equals(section)) showDemoM3u();
        else showLiveTv();
    }

    private void showLiveTv() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Live TV - gratis test", "Openbare teststreams om de player op telefoon en TV te controleren.");
        addPlayableCard(box, "Apple HLS Test", "Adaptieve HLS teststream", APPLE_HLS);
        addPlayableCard(box, "Mux HLS Test", "Big Buck Bunny via HLS", MUX_HLS);
        addExternalCard(box, "NASA Live", "Gratis officiële NASA-stream via YouTube", NASA_YOUTUBE);
        addInfo(box, "The One Media Player toont zelf geen reclame. Later koppelen we jouw eigen geautoriseerde M3U/Xtream-bron.");
        scroll.addView(box);
        content.addView(scroll);
    }

    private void showFilms() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Films - gratis test", "Vrij beschikbare Blender Open Movies voor onze eerste afspeeltest.");
        addPlayableCard(box, "Big Buck Bunny", "Blender Foundation - direct MP4", BBB_MP4);
        addPlayableCard(box, "Sintel", "Blender Foundation - direct MKV", SINTEL_MKV);
        addExternalCard(box, "Big Buck Bunny op YouTube", "Officiële Blender-video", BBB_YOUTUBE);
        addInfo(box, "Geen advertenties van The One Media Player. Externe apps zoals YouTube kunnen hun eigen advertenties tonen.");
        scroll.addView(box);
        content.addView(scroll);
    }

    private void showDemoM3u() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Ingebouwde test-M3U", "Deze lijst demonstreert M3U-items met HLS, films en YouTube-links.");
        List<DemoEntry> entries = parseM3u(DEMO_M3U);
        for (DemoEntry entry : entries) {
            if (isExternalUrl(entry.url)) {
                addExternalCard(box, entry.title, entry.group + " • opent extern", entry.url);
            } else {
                addPlayableCard(box, entry.title, entry.group + " • speelt in Media3", entry.url);
            }
        }
        TextView rawTitle = text("M3U testinhoud", 18, BLUE, true);
        rawTitle.setPadding(0, dp(18), 0, dp(8));
        box.addView(rawTitle);
        TextView raw = text(DEMO_M3U, 12, MUTED, false);
        raw.setTextIsSelectable(true);
        raw.setBackgroundColor(PANEL);
        raw.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.addView(raw);
        scroll.addView(box);
        content.addView(scroll);
    }

    private void showSection(String title, String subtitle) {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox(title, subtitle);
        String[] cards = title.equals("Series")
                ? new String[]{"Verder kijken", "Nieuw", "Drama", "Crime", "Comedy"}
                : new String[]{"Nog leeg", "Klaar voor synchronisatie"};
        for (String card : cards) addStaticCard(box, card);
        scroll.addView(box);
        content.addView(scroll);
    }

    private LinearLayout baseBox(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(12), dp(6), dp(18));
        box.addView(text(title, 32, Color.WHITE, true));
        TextView sub = text(subtitle, 17, MUTED, false);
        sub.setPadding(0, dp(8), 0, dp(22));
        box.addView(sub);
        return box;
    }

    private void addPlayableCard(LinearLayout box, String title, String subtitle, String url) {
        LinearLayout card = cardContainer();
        card.addView(text(title, 20, Color.WHITE, true));
        TextView desc = text(subtitle, 14, MUTED, false);
        desc.setPadding(0, dp(5), 0, dp(10));
        card.addView(desc);
        Button play = button("▶ Afspelen");
        play.setOnClickListener(v -> showPlayer(url));
        card.addView(play);
        addCard(box, card);
    }

    private void addExternalCard(LinearLayout box, String title, String subtitle, String url) {
        LinearLayout card = cardContainer();
        card.addView(text(title, 20, Color.WHITE, true));
        TextView desc = text(subtitle, 14, MUTED, false);
        desc.setPadding(0, dp(5), 0, dp(10));
        card.addView(desc);
        Button open = button("Openen");
        open.setOnClickListener(v -> openExternal(url));
        card.addView(open);
        addCard(box, card);
    }

    private void addStaticCard(LinearLayout box, String label) {
        TextView tile = text(label, 20, Color.WHITE, true);
        tile.setBackgroundColor(PANEL);
        tile.setPadding(dp(20), dp(22), dp(20), dp(22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.addView(tile, lp);
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(PANEL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        return card;
    }

    private void addCard(LinearLayout box, LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.addView(card, lp);
    }

    private void addInfo(LinearLayout box, String value) {
        TextView note = text(value, 14, BLUE, false);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
    }

    private void showSettings() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Instellingen", "Je kunt een losse stream-URL testen. Jouw echte TV/film/series-bron koppelen we later.");

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
                if (isExternalUrl(value)) openExternal(value); else showPlayer(value);
            } else {
                Toast.makeText(this, "Vul eerst een geldige http(s)-URL in", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testLp.leftMargin = dp(12);
        actions.addView(test, testLp);
        box.addView(actions);

        Button demo = button("Open ingebouwde test-M3U");
        demo.setOnClickListener(v -> showDemoM3u());
        box.addView(demo);

        TextView multi = text(
                "Meerdere apparaten\n\nDeze APK kan op meerdere Android-telefoons en Android/Google TV's worden geïnstalleerd. Tijdens video kun je je telefoon draaien zonder dat de stream opnieuw begint. De player gebruikt volledig scherm zonder status- of navigatiebalk. WhatsApp-meldingen blijven toegestaan. The One Media Player bevat zelf geen advertenties.",
                17,
                Color.WHITE,
                false
        );
        multi.setBackgroundColor(PANEL);
        multi.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams multiLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        multiLp.topMargin = dp(18);
        box.addView(multi, multiLp);

        scroll.addView(box);
        content.addView(scroll);
    }

    private List<DemoEntry> parseM3u(String m3u) {
        List<DemoEntry> result = new ArrayList<>();
        String pendingTitle = null;
        String pendingGroup = "Overig";
        for (String rawLine : m3u.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                pendingTitle = comma >= 0 ? line.substring(comma + 1).trim() : "Media";
                pendingGroup = extractGroup(line);
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingTitle != null) {
                result.add(new DemoEntry(pendingTitle, pendingGroup, line));
                pendingTitle = null;
                pendingGroup = "Overig";
            }
        }
        return result;
    }

    private String extractGroup(String extinf) {
        String token = "group-title=\"";
        int start = extinf.indexOf(token);
        if (start < 0) return "Overig";
        start += token.length();
        int end = extinf.indexOf('"', start);
        return end > start ? extinf.substring(start, end) : "Overig";
    }

    private boolean isExternalUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("nasa.gov");
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Geen app gevonden om deze link te openen", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPlayer(String url) {
        releasePlayer();
        playerFullscreen = true;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        activePlayerView = new PlayerView(this);
        player = new ExoPlayer.Builder(this).build();
        activePlayerView.setPlayer(player);
        activePlayerView.setUseController(true);
        activePlayerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);

        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();

        root.addView(activePlayerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button back = button("← Terug");
        back.setOnClickListener(v -> showShell("Live TV"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(16), dp(16), 0, 0);
        root.addView(back, lp);

        setContentView(root);
        root.post(this::enterImmersiveFullscreen);
    }

    private void enterImmersiveFullscreen() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        } catch (Throwable ignored) {
            // Fullscreen mag nooit de player laten crashen.
        }
    }

    private void exitImmersiveFullscreen() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Throwable ignored) {
            // Ook bij recente Android/Samsung-versies veilig terugkeren.
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (playerFullscreen) {
            if (activePlayerView != null) {
                activePlayerView.requestLayout();
            }
            enterImmersiveFullscreen();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playerFullscreen) {
            enterImmersiveFullscreen();
        }
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
        if (activePlayerView != null) {
            activePlayerView.setPlayer(null);
            activePlayerView = null;
        }
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

    private static class DemoEntry {
        final String title;
        final String group;
        final String url;

        DemoEntry(String title, String group, String url) {
            this.title = title;
            this.group = group;
            this.url = url;
        }
    }
}
