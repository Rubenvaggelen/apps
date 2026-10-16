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
    private String selectedCountry = "Nederland";

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
        header.addView(text("   MEDIA PLAYER", 20, Color.WHITE, true));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(16), 0, dp(14));
        addNavButton(nav, "Live TV", this::showLiveTv);
        addNavButton(nav, "Films", this::showFilms);
        addNavButton(nav, "Series", () -> showCatalog("series"));
        addNavButton(nav, "XXX 🔒", this::openAdultSection);
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
        else if ("Instellingen".equals(section)) showSettings();
        else showLiveTv();
    }

    private void showLiveTv() {
        if (hasXtreamSources()) {showCatalog("live"); return;}
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Live TV", "Gebruik de gratis teststreams of koppel je eigen bron via Instellingen.");
        addConfiguredSourceSummary(box);
        addPlayableCard(box, "Apple HLS Test", "Adaptieve HLS teststream", APPLE_HLS);
        addPlayableCard(box, "Mux HLS Test", "Big Buck Bunny via HLS", MUX_HLS);
        addExternalCard(box, "NASA Live", "Gratis officiële NASA-stream via YouTube", NASA_YOUTUBE);
        addInfo(box, "The One Media Player bevat zelf geen advertenties.");
        scroll.addView(box);
        content.addView(scroll);
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
            value = prefs.getString("m3u_url", "Nog geen M3U-URL ingesteld");
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
        if (hasXtreamSources()) {showCatalog("movie"); return;}
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

    private boolean hasXtreamSources() {
        return !prefs.getString("xtream_1_server", prefs.getString("xtream_server", "")).isEmpty()
                && !prefs.getString("xtream_1_user", prefs.getString("xtream_user", "")).isEmpty();
    }

    private void showCatalog(String kind) {
        content.removeAllViews();
        String title = "live".equals(kind) ? "Live TV" : "movie".equals(kind) ? "Films" : "Series";
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox(title, "Alles op één plek");
        box.addView(text("Bronnen laden…", 17, MUTED, false));
        scroll.addView(box);
        content.addView(scroll);
        new Thread(() -> {
            XtreamCatalog.Result result = XtreamCatalog.load(prefs, kind);
            runOnUiThread(() -> {
                if (content.indexOfChild(scroll) < 0) return;
                box.removeAllViews();
                box.addView(text(title+" ("+result.entries.size()+")", 27, Color.WHITE, true));
                for (String error : result.errors) addInfo(box, error);
                if (result.entries.isEmpty()) {
                    addInfo(box, "Geen items gevonden. Controleer de accounts via Instellingen.");
                }
                if("live".equals(kind)) {
                    android.widget.Spinner countries=new android.widget.Spinner(this);
                    List<String> options=new ArrayList<>();options.add("Alle landen");options.addAll(ChannelOrganizer.countries(result.entries));
                    android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);
                    countries.setAdapter(adapter);
                    int selected=options.indexOf(selectedCountry);
                    countries.setSelection(selected>=0?selected:0);
                    countries.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                        @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
                        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,int position,long id){
                            String value=options.get(position);
                            if(!value.equals(selectedCountry)){selectedCountry=value;showCatalog("live");}
                        }
                    });
                    box.addView(countries);
                }
                int displayed = 0;
                List<XtreamCatalog.Entry> visible = "live".equals(kind)?ChannelOrganizer.forCountry(result.entries,selectedCountry):result.entries;
                for (XtreamCatalog.Entry entry : visible) {
                    if (displayed++ >= 400) break;
                    if ("series".equals(kind)) {
                        LinearLayout card=cardContainer();
                        card.addView(text(entry.title,20,Color.WHITE,true));
                        Button episodes=button("Seizoenen en afleveringen");
                        episodes.setOnClickListener(v -> showEpisodes(entry));
                        card.addView(episodes);
                        addCard(box,card);
                    } else {
                        List<String> urls=XtreamCatalog.playbackUrls(prefs,entry);
                        if(!urls.isEmpty())addPlayableCardWithFallback(box,entry.title,urls);
                    }
                }
                if(result.entries.size()>400) addInfo(box,"Eerste 400 resultaten getoond; zoeken en paginering volgen.");
            });
        }).start();
    }

    private void openAdultSection() {
        String deviceId=prefs.getString("licensed_device_id","");
        if(deviceId.isEmpty()) {
            new android.app.AlertDialog.Builder(this).setTitle("XXX — aparte toestemming vereist")
                .setMessage("XXX is geblokkeerd totdat dit apparaat afzonderlijk via The One Main is geregistreerd en goedgekeurd.")
                .setPositiveButton("OK",null).show();
            return;
        }
        new Thread(() -> {
            boolean allowed=false;
            boolean requested=false;
            try {
                org.json.JSONObject body=new org.json.JSONObject();
                body.put("device_id",deviceId);
                body.put("scope","media_player_xxx");
                org.json.JSONObject status=postAccess("access_status",body);
                allowed=status.optBoolean("allowed",false);
                if(!allowed) {
                    org.json.JSONObject response=postAccess("request_access",body);
                    requested=response.optBoolean("ok",false);
                }
            } catch(Exception ignored) { }
            final boolean granted=allowed;
            final boolean pending=requested;
            runOnUiThread(() -> {
                if(!granted) {
                    new android.app.AlertDialog.Builder(this).setTitle("XXX — toestemming nodig")
                        .setMessage(pending?"Een afzonderlijk XXX-toegangsverzoek is ingediend. Alleen de eigenaar kan dit in Main goedkeuren.":"XXX is vergrendeld. De afzonderlijke toestemming kon niet worden gecontroleerd.")
                        .setPositiveButton("OK",null).show();
                    return;
                }
                askAdultPin();
            });
        }).start();
    }
    private org.json.JSONObject postAccess(String action, org.json.JSONObject body) throws Exception {
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("https://rubenvanaggelen.com/the-one-remote-api/devices.php?action="+action).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        try {
            connection.getOutputStream().write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Access verification unavailable");
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            try(java.io.InputStream in=connection.getInputStream()){
                while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>65536)throw new java.io.IOException("Response too large");}
            }
            return new org.json.JSONObject(out.toString("UTF-8"));
        } finally {connection.disconnect();}
    }
    private void askAdultPin() {
        EditText pin=new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        new android.app.AlertDialog.Builder(this).setTitle("XXX — PIN")
            .setMessage(AdultPin.isConfigured(prefs)?"Voer je persoonlijke PIN in.":"Eenmalige tijdelijke PIN: 1306. Daarna kies je een nieuwe PIN.")
            .setView(pin).setNegativeButton("Annuleren",null)
            .setPositiveButton("Verder",(dialog,which)->{
                String entered=pin.getText().toString();
                if(!AdultPin.isConfigured(prefs)) {
                    if("1306".equals(entered))changeAdultPin();
                    else Toast.makeText(this,"Onjuiste PIN",Toast.LENGTH_SHORT).show();
                } else if(AdultPin.check(prefs,entered))showAdultSection();
                else Toast.makeText(this,"Onjuiste PIN",Toast.LENGTH_SHORT).show();
            }).show();
    }
    private void changeAdultPin() {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18),0,dp(18),0);
        EditText first=new EditText(this);
        first.setHint("Nieuwe PIN (4-8 cijfers)");
        first.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        layout.addView(first);
        EditText second=new EditText(this);
        second.setHint("Herhaal nieuwe PIN");
        second.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        layout.addView(second);
        new android.app.AlertDialog.Builder(this).setTitle("Stel je persoonlijke XXX-PIN in")
            .setView(layout).setNegativeButton("Annuleren",null)
            .setPositiveButton("Opslaan",(d,w)->{
                String candidate=first.getText().toString();
                if(!candidate.equals(second.getText().toString())||!AdultPin.validate(candidate)){
                    Toast.makeText(this,"PIN moet 4-8 cijfers bevatten, verschillen van 1306 en overeenkomen",Toast.LENGTH_LONG).show();
                    return;
                }
                try {AdultPin.save(prefs,candidate);showAdultSection();}
                catch(Exception e){Toast.makeText(this,"PIN opslaan mislukt",Toast.LENGTH_LONG).show();}
            }).show();
    }
    private void showAdultSection() {
        content.removeAllViews();
        ScrollView scroll=new ScrollView(this);
        LinearLayout box=baseBox("XXX","Afgeschermde bibliotheek");
        box.addView(text("Catalogus laden…",16,MUTED,false));
        scroll.addView(box);content.addView(scroll);
        new Thread(() -> {
            List<XtreamCatalog.Entry> all=new ArrayList<>();
            for(String kind:new String[]{"live","movie","series"})all.addAll(XtreamCatalog.load(prefs,kind,true).entries);
            runOnUiThread(() -> {
                if(content.indexOfChild(scroll)<0)return;
                box.removeAllViews();
                box.addView(text("XXX",28,Color.WHITE,true));
                for(XtreamCatalog.Entry entry:all) {
                    if("series".equals(entry.kind)){
                        LinearLayout card=cardContainer();
                        card.addView(text(entry.title,20,Color.WHITE,true));
                        Button open=button("Afleveringen");
                        open.setOnClickListener(v -> showEpisodes(entry));card.addView(open);addCard(box,card);
                    }else {
                        List<String> urls=XtreamCatalog.playbackUrls(prefs,entry);
                        if(!urls.isEmpty())addPlayableCardWithFallback(box,entry.title,urls);
                    }
                }
                if(all.isEmpty())addInfo(box,"Geen XXX-content beschikbaar");
            });
        }).start();
    }

    private void showEpisodes(XtreamCatalog.Entry series) {
        content.removeAllViews();
        ScrollView scroll=new ScrollView(this);
        LinearLayout box=baseBox(series.title,"Afleveringen");
        box.addView(text("Afleveringen laden…",16,MUTED,false));
        scroll.addView(box);
        content.addView(scroll);
        new Thread(() -> {
            List<XtreamCatalog.Entry> episodes=new ArrayList<>();
            String failure=null;
            try {episodes=XtreamCatalog.episodes(prefs,series);}
            catch(Exception e){failure="Afleveringen konden niet worden geladen.";}
            final List<XtreamCatalog.Entry> loaded=episodes;
            final String error=failure;
            runOnUiThread(() -> {
                if(content.indexOfChild(scroll)<0)return;
                box.removeAllViews();
                box.addView(text(series.title,26,Color.WHITE,true));
                if(error!=null)addInfo(box,error);
                for(XtreamCatalog.Entry episode:loaded){
                    List<String> urls=XtreamCatalog.playbackUrls(prefs,episode);
                    if(!urls.isEmpty())addPlayableCardWithFallback(box,episode.title,urls);
                }
            });
        }).start();
    }

    private void addPlayableCardWithFallback(LinearLayout box,String title,List<String> urls) {
        LinearLayout card=cardContainer();
        card.addView(text(title,20,Color.WHITE,true));
        Button play=button("▶ Afspelen");
        play.setOnClickListener(v -> showPlayerWithFallback(urls,0));
        card.addView(play);
        addCard(box,card);
    }

    private void showPlayerWithFallback(List<String> urls,int index) {
        if(urls.isEmpty()||index>=urls.size()){
            Toast.makeText(this,"Afspelen niet mogelijk",Toast.LENGTH_LONG).show();
            return;
        }
        showPlayer(urls.get(index));
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if(index+1<urls.size())runOnUiThread(() -> showPlayerWithFallback(urls,index+1));
                else runOnUiThread(() -> Toast.makeText(MainActivity.this,"Geen beschikbare stream",Toast.LENGTH_LONG).show());
            }
        });
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
        LinearLayout box = baseBox("Instellingen", "Beheer je bron of test een losse stream-URL.");

        Button source = button("Bron instellen: Stalker / Xtream / M3U");
        source.setOnClickListener(v -> startActivity(new Intent(this, SourceConfigActivity.class)));
        box.addView(source);

        TextView sourceInfo = text("Server voorgeladen: http://line.liondnscloud.ru:80", 14, MUTED, false);
        sourceInfo.setPadding(0, dp(8), 0, dp(18));
        box.addView(sourceInfo);

        EditText url = new EditText(this);
        url.setTextColor(Color.WHITE);
        url.setHintTextColor(MUTED);
        url.setHint("Losse test-stream URL");
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
        if (playerFullscreen) showShell("Live TV");
        else super.onBackPressed();
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
