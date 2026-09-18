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
import android.view.WindowManager;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
        try {
            prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);

            ArrayList<String> playQueue = getIntent().getStringArrayListExtra("play_queue");
            String playUrl = getIntent().getStringExtra("play_url");

            if (playQueue != null && !playQueue.isEmpty()) {
                showPlayerQueue(playQueue);
            } else if (playUrl != null && (playUrl.startsWith("http://") || playUrl.startsWith("https://"))) {
                showPlayer(playUrl);
            } else {
                showShell("Home");
                MediaPlayerUpdateChecker.checkForUpdate(this);
            }
        } catch (Throwable startupError) {
            showSafeStartupScreen(startupError);
        }
    }

    private void showSafeStartupScreen(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(text("THE ONE MEDIA PLAYER", 26, Color.WHITE, true));
        TextView message = text(
                "De app is gestart in veilige modus. Fout: " + error.getClass().getSimpleName(),
                16,
                MUTED,
                false
        );
        message.setPadding(0, dp(18), 0, 0);
        root.addView(message);
        setContentView(root);
    }

    private void showShell(String section) {
        playerFullscreen = false;
        releasePlayer();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PremiumUi.BG);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));

        LinearLayout header = PremiumUi.brandHeader(this, "Player");
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(16), 0, dp(14));
        addNavButton(nav, "Live TV", () -> openXtreamCatalog("live"));
        addNavButton(nav, "Films", () -> openXtreamCatalog("movie"));
        addNavButton(nav, "Series", () -> openXtreamCatalog("series"));
        addNavButton(nav, "Verder kijken", () -> showSection("Verder kijken", "Je kijkvoortgang verschijnt hier."));
        addNavButton(nav, "Favorieten", () -> showSection("Favorieten", "Je favoriete zenders, films en series verschijnen hier."));
        addNavButton(nav, "Instellingen", this::showSettings);
        navScroll.addView(nav);
        root.addView(navScroll);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        root.post(this::exitImmersiveFullscreen);

        if ("Home".equals(section)) showHome();
        else if ("Films".equals(section)) showFilms();
        else if ("Test M3U".equals(section)) showDemoM3u();
        else if ("Instellingen".equals(section)) showSettings();
        else showLiveTv();
    }

    private void showHome() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Media Player", "Kies Live TV, Films of Series.");
        addInfo(box, "Je privé bron wordt pas geladen nadat je een onderdeel opent.");
        scroll.addView(box);
        content.addView(scroll);
    }

    private void ensurePrivateSourceConfigured() {
        if (prefs == null) return;
        String source = readPrivateSourceAsset();
        if (source.startsWith("http://") || source.startsWith("https://")) {
            prefs.edit()
                    .putString("source_type", "M3U")
                    .putString("m3u_url", source)
                    .apply();
        }
    }

    private String readPrivateSourceAsset() {
        try (InputStream in = getAssets().open("private_source.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void openXtreamCatalog(String mode) {
        Intent intent = new Intent(this, XtreamCatalogActivity.class);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }

    private void openCatalog(String mode) {
        Intent intent = new Intent(this, TmdbCatalogActivity.class);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }

    private void showLiveTv() {
        ensurePrivateSourceConfigured();
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Live TV", "Je ingestelde bron wordt direct in The One Media Player geladen.");
        addConfiguredSourceSummary(box);

        String type = prefs.getString("source_type", "STALKER");
        String m3uUrl = prefs.getString("m3u_url", "").trim();
        if ("M3U".equals(type) && !m3uUrl.isEmpty()) {
            loadConfiguredM3u(box, m3uUrl);
        } else {
            addInfo(box, "Nog geen privé M3U-bron ingesteld. Hieronder staan alleen de teststreams.");
            addPlayableCard(box, "Apple HLS Test", "Adaptieve HLS teststream", APPLE_HLS);
            addPlayableCard(box, "Mux HLS Test", "Big Buck Bunny via HLS", MUX_HLS);
            addExternalCard(box, "NASA Live", "Gratis officiële NASA-stream via YouTube", NASA_YOUTUBE);
        }

        addInfo(box, "The One Media Player bevat zelf geen advertenties.");
        scroll.addView(box);
        content.addView(scroll);
    }

    private void loadConfiguredM3u(LinearLayout box, String playlistUrl) {
        TextView loading = text("Bron laden…", 15, MUTED, false);
        loading.setPadding(0, dp(8), 0, dp(12));
        box.addView(loading);

        new Thread(() -> {
            try {
                String m3u = downloadText(playlistUrl);
                List<DemoEntry> all = parseM3u(m3u);
                List<DemoEntry> live = new ArrayList<>();
                for (DemoEntry entry : all) {
                    if (isLiveEntry(entry)) live.add(entry);
                }

                runOnUiThread(() -> {
                    try {
                        box.removeView(loading);
                        if (live.isEmpty()) {
                            addInfo(box, "De bron is geladen, maar er zijn geen Live TV-zenders herkend.");
                            return;
                        }

                        addInfo(box, "Jouw bron: " + live.size() + " Live TV-zenders gevonden.");
                        int limit = Math.min(live.size(), 250);
                        for (int i = 0; i < limit; i++) {
                            DemoEntry entry = live.get(i);
                            addPlayableCard(box, entry.title, entry.group, entry.url);
                        }
                        if (live.size() > limit) {
                            addInfo(box, "Eerste " + limit + " zenders getoond. Zoeken en categorieën voegen we in de volgende uitbreiding toe.");
                        }
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    box.removeView(loading);
                    addInfo(box, "Bron kon niet worden geladen. Controleer je verbinding of importeer de bron opnieuw.");
                });
            }
        }).start();
    }

    private boolean isLiveEntry(DemoEntry entry) {
        String lower = entry.url.toLowerCase();
        return !lower.contains("/movie/") && !lower.contains("/series/");
    }

    private String downloadText(String urlValue) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlValue).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/0.4");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) throw new IllegalStateException("HTTP " + code);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 1200) {
                    out.append(line).append('\n');
                    lineCount++;
                }
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                return out.toString();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void addConfiguredSourceSummary(LinearLayout box) {
        String type = prefs.getString("source_type", "STALKER");
        String label;
        String value;
        if ("XTREAM".equals(type)) {
            label = "Xtream";
            value = prefs.getString("xtream_server", "http://line.liondnscloud.ru:80");
        } else if ("M3U".equals(type)) {
            label = "M3U";
            String source = prefs.getString("m3u_url", "").trim();
            value = source.isEmpty() ? "Nog geen M3U-bron ingesteld" : "Privé M3U-bron ingesteld";
        } else {
            label = "Stalker / MAC";
            value = prefs.getString("stalker_server", "http://line.liondnscloud.ru:80");
        }
        LinearLayout card = cardContainer();
        card.addView(text("Bron: " + label, 18, BLUE, true));
        TextView detail = text(value, 14, MUTED, false);
        detail.setPadding(0, dp(5), 0, dp(10));
        card.addView(detail);
        Button settings = button("Bron instellen");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SourceConfigActivity.class)));
        card.addView(settings);
        addCard(box, card);
    }

    private void showFilms() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Films", "Gratis Blender Open Movies voor de afspeeltest.");
        addPlayableCard(box, "Big Buck Bunny", "Blender Foundation - direct MP4", BBB_MP4);
        addPlayableCard(box, "Sintel", "Blender Foundation - direct MKV", SINTEL_MKV);
        addExternalCard(box, "Big Buck Bunny op YouTube", "Officiële Blender-video", BBB_YOUTUBE);
        addInfo(box, "Externe apps zoals YouTube kunnen hun eigen advertenties tonen.");
        scroll.addView(box);
        content.addView(scroll);
    }

    private void showDemoM3u() {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Ingebouwde test-M3U", "Test HLS, films en externe YouTube-links.");
        List<DemoEntry> entries = parseM3u(DEMO_M3U);
        for (DemoEntry entry : entries) {
            if (isExternalUrl(entry.url)) addExternalCard(box, entry.title, entry.group + " • opent extern", entry.url);
            else addPlayableCard(box, entry.title, entry.group + " • speelt in Media3", entry.url);
        }
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
        box.setPadding(dp(4), dp(16), dp(4), dp(22));
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
        tile.setBackground(PremiumUi.card(this));
        tile.setElevation(dp(4));
        tile.setPadding(dp(20), dp(22), dp(20), dp(22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.addView(tile, lp);
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(PremiumUi.card(this));
        card.setElevation(dp(5));
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
        LinearLayout box = baseBox("Instellingen", "Beheer je bron of test een losse stream-URL.");

        Button source = button("Bron instellen: Stalker / Xtream / M3U");
        source.setOnClickListener(v -> startActivity(new Intent(this, SourceConfigActivity.class)));
        box.addView(source);

        TextView sourceInfo = text("Server voorgeladen: http://line.liondnscloud.ru:80", 14, MUTED, false);
        sourceInfo.setPadding(0, dp(8), 0, dp(18));
        box.addView(sourceInfo);

        EditText url = PremiumUi.searchField(this, "Losse test-stream URL");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("source_url", ""));
        box.addView(url, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(16), 0, dp(18));
        Button save = button("Opslaan");
        save.setOnClickListener(v -> {
            prefs.edit().putString("source_url", url.getText().toString().trim()).apply();
            Toast.makeText(this, "Test-URL opgeslagen", Toast.LENGTH_SHORT).show();
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
                "Meerdere apparaten\n\nDeze APK kan op meerdere Android-telefoons en Android/Google TV's worden geïnstalleerd. Tijdens video kun je draaien zonder herstart. Fullscreen verbergt klok en navigatiebalk. WhatsApp-meldingen blijven toegestaan. The One Media Player bevat zelf geen advertenties.",
                17,
                Color.WHITE,
                false
        );
        multi.setBackground(PremiumUi.card(this));
        multi.setElevation(dp(4));
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
        ArrayList<String> single = new ArrayList<>();
        single.add(url);
        showPlayerQueue(single);
    }

    private void showPlayerQueue(List<String> urls) {
        releasePlayer();
        playerFullscreen = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        activePlayerView = new PlayerView(this);
        player = new ExoPlayer.Builder(this).build();
        activePlayerView.setPlayer(player);
        activePlayerView.setUseController(true);
        activePlayerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);

        ArrayList<MediaItem> items = new ArrayList<>();
        for (String url : urls) {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                items.add(MediaItem.fromUri(url));
            }
        }

        if (items.isEmpty()) {
            showShell("Home");
            return;
        }

        // ExoPlayer gaat automatisch door naar het volgende MediaItem.
        // Bij handmatig teruggaan wordt de player vrijgegeven en stopt de queue.
        player.setMediaItems(items);
        player.prepare();
        player.play();

        root.addView(
                activePlayerView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
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
        }
    }

    private void exitImmersiveFullscreen() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (playerFullscreen) {
            if (activePlayerView != null) activePlayerView.requestLayout();
            enterImmersiveFullscreen();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playerFullscreen) enterImmersiveFullscreen();
    }

    @Override
    public void onBackPressed() {
        if (playerFullscreen) showShell("Home");
        else super.onBackPressed();
    }

    private void addNavButton(LinearLayout parent, String label, Runnable action) {
        Button b = PremiumUi.chipButton(this, label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        parent.addView(b, lp);
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

    private void releasePlayer() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
