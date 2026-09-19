package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.Session;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.MediaLoadRequestData;

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

    private static final int REQ_PICK_CAST_MEDIA = 7301;
    private TheOneCast.Receiver castReceiver;
    private TheOneCast.PhoneMediaServer phoneMediaServer;
    private TheOneCast.TvDevice selectedTv;
    private Uri selectedCastUri;
    private TextView castStatus;

    private CastContext googleCastContext;
    private CastSession googleCastSession;
    private MediaRouteButton googleCastButton;
    private String pendingGoogleCastUrl;
    private String pendingGoogleCastTitle;
    private boolean pendingLocalGoogleCast;

    private final SessionManagerListener<CastSession> googleCastSessionListener =
            new SessionManagerListener<CastSession>() {
                @Override public void onSessionStarting(CastSession session) {}
                @Override public void onSessionStarted(CastSession session, String sessionId) {
                    googleCastSession = session;
                    handleGoogleCastConnected();
                }
                @Override public void onSessionStartFailed(CastSession session, int error) {
                    pendingGoogleCastUrl = null;
                    pendingLocalGoogleCast = false;
                }
                @Override public void onSessionEnding(CastSession session) {}
                @Override public void onSessionEnded(CastSession session, int error) {
                    googleCastSession = null;
                }
                @Override public void onSessionResuming(CastSession session, String sessionId) {}
                @Override public void onSessionResumed(CastSession session, boolean wasSuspended) {
                    googleCastSession = session;
                    handleGoogleCastConnected();
                }
                @Override public void onSessionResumeFailed(CastSession session, int error) {
                    googleCastSession = null;
                }
                @Override public void onSessionSuspended(CastSession session, int reason) {}
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
            if (isTvBuild()) {
                try {
                    startTvCastReceiver();
                } catch (Throwable castReceiverError) {
                    Log.w("TheOneMediaPlayer", "TV cast receiver unavailable at startup", castReceiverError);
                    castReceiver = null;
                }
            }

            ArrayList<String> playQueue = getIntent().getStringArrayListExtra("play_queue");
            String playUrl = getIntent().getStringExtra("play_url");

            if (playQueue != null && !playQueue.isEmpty()) {
                showPlayerQueue(playQueue);
            } else if (playUrl != null && (playUrl.startsWith("http://") || playUrl.startsWith("https://"))) {
                showPlayer(playUrl);
            } else {
                showShell("Home");
                if (!"com.theone.mediaplayer.tv".equals(getPackageName())) {
                    MediaPlayerUpdateChecker.checkForUpdate(this);
                }
            }
        } catch (Throwable startupError) {
            Log.e("TheOneMediaPlayer", "Startup failed", startupError);
            showSafeStartupScreen(startupError);
        }
    }

    private void initGoogleCast() {
        try {
            googleCastContext = CastContext.getSharedInstance(this);
            googleCastContext.getSessionManager().addSessionManagerListener(
                    googleCastSessionListener,
                    CastSession.class
            );
            Session current = googleCastContext.getSessionManager().getCurrentSession();
            if (current instanceof CastSession) {
                googleCastSession = (CastSession) current;
            }
        } catch (Throwable ignored) {
            googleCastContext = null;
            googleCastSession = null;
        }
    }

    private MediaRouteButton createGoogleCastButton() {
        try {
            MediaRouteButton route = new MediaRouteButton(this);
            route.setContentDescription("Chromecast / Google Cast");
            route.setFocusable(true);
            route.setBackground(PremiumUi.card(this));
            route.setPadding(dp(10), dp(8), dp(10), dp(8));
            route.setMinimumWidth(dp(52));
            route.setMinimumHeight(dp(46));
            CastButtonFactory.setUpMediaRouteButton(this, route);
            googleCastButton = route;
            return route;
        } catch (Throwable castButtonError) {
            Log.w("TheOneMediaPlayer", "Google Cast button unavailable", castButtonError);
            googleCastButton = null;
            return null;
        }
    }

    private void requestGoogleCast(String url, String title) {
        if (isTvBuild()) return;
        pendingGoogleCastUrl = url;
        pendingGoogleCastTitle = title == null || title.trim().isEmpty()
                ? "The One Media Player"
                : title.trim();
        pendingLocalGoogleCast = false;

        if (googleCastSession != null && googleCastSession.isConnected()) {
            handleGoogleCastConnected();
            return;
        }

        if (googleCastButton != null) {
            googleCastButton.performClick();
        } else {
            Toast.makeText(this, "Open eerst de Chromecast-knop.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestLocalGoogleCast() {
        if (isTvBuild()) return;
        if (selectedCastUri == null) {
            choosePhoneMedia();
            return;
        }
        pendingLocalGoogleCast = true;
        pendingGoogleCastUrl = null;

        if (googleCastSession != null && googleCastSession.isConnected()) {
            handleGoogleCastConnected();
            return;
        }

        if (googleCastButton != null) {
            googleCastButton.performClick();
        } else {
            Toast.makeText(this, "Open eerst de Chromecast-knop.", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleGoogleCastConnected() {
        if (googleCastSession == null || !googleCastSession.isConnected()) return;

        if (pendingLocalGoogleCast && selectedCastUri != null) {
            pendingLocalGoogleCast = false;
            castSelectedPhoneMediaToGoogle();
            return;
        }

        if (pendingGoogleCastUrl != null) {
            String url = pendingGoogleCastUrl;
            String title = pendingGoogleCastTitle;
            pendingGoogleCastUrl = null;
            pendingGoogleCastTitle = null;
            loadGoogleCastMedia(url, title, null);
        }
    }

    private void castSelectedPhoneMediaToGoogle() {
        try {
            if (googleCastSession == null || googleCastSession.getCastDevice() == null) return;
            if (phoneMediaServer != null) phoneMediaServer.stop();

            phoneMediaServer = new TheOneCast.PhoneMediaServer(this, selectedCastUri);
            phoneMediaServer.start();

            java.net.InetAddress remote = googleCastSession.getCastDevice().getInetAddress();
            String url = phoneMediaServer.urlFor(remote);
            if (url == null) {
                Toast.makeText(this, "Kon lokaal telefoonadres niet bepalen.", Toast.LENGTH_SHORT).show();
                return;
            }

            String title = TheOneCast.displayName(this, selectedCastUri);
            String type = getContentResolver().getType(selectedCastUri);
            loadGoogleCastMedia(url, title, type);
        } catch (Throwable e) {
            Toast.makeText(this, "Streamen naar Chromecast lukte niet.", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadGoogleCastMedia(String url, String title, String mimeOverride) {
        try {
            if (googleCastSession == null || !googleCastSession.isConnected()) return;
            RemoteMediaClient remote = googleCastSession.getRemoteMediaClient();
            if (remote == null) return;

            MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
            metadata.putString(MediaMetadata.KEY_TITLE,
                    title == null || title.trim().isEmpty() ? "The One Media Player" : title.trim());

            String mime = mimeOverride == null || mimeOverride.trim().isEmpty()
                    ? guessCastMimeType(url)
                    : mimeOverride;

            int streamType = isLikelyLiveStream(url)
                    ? MediaInfo.STREAM_TYPE_LIVE
                    : MediaInfo.STREAM_TYPE_BUFFERED;

            MediaInfo info = new MediaInfo.Builder(url)
                    .setStreamType(streamType)
                    .setContentType(mime)
                    .setMetadata(metadata)
                    .build();

            long position = player == null ? 0 : Math.max(0, player.getCurrentPosition());
            MediaLoadRequestData request = new MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(position)
                    .build();

            remote.load(request);
            if (player != null) player.pause();

            String device = googleCastSession.getCastDevice() == null
                    ? "Chromecast"
                    : googleCastSession.getCastDevice().getFriendlyName();
            Toast.makeText(this, "Afspelen op " + device, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "Chromecast kon deze stream niet starten.", Toast.LENGTH_SHORT).show();
        }
    }

    private String guessCastMimeType(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.contains(".m3u8")) return "application/x-mpegURL";
        if (lower.contains(".mpd")) return "application/dash+xml";
        if (lower.contains(".mp4") || lower.contains(".m4v")) return "video/mp4";
        if (lower.contains(".mkv")) return "video/x-matroska";
        if (lower.contains(".mp3")) return "audio/mpeg";
        if (lower.contains(".aac")) return "audio/aac";
        if (lower.contains(".ts") || lower.contains("/live/")) return "video/mp2t";
        return "video/mp4";
    }

    private boolean isLikelyLiveStream(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return lower.contains("/live/") || lower.endsWith(".ts") || lower.contains("live=");
    }

    private void showSafeStartupScreen(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(text("THE ONE MEDIA PLAYER", 26, Color.WHITE, true));
        TextView message = text(
                "De app is gestart in veilige modus. Fout: "
                        + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage()),
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
        if (!isTvBuild()) {
            // Google Cast wordt pas geladen wanneer de gebruiker deze functie opent.
            // Zo kan een Cast-/Play Services-probleem de Media Player nooit meer bij startup blokkeren.
            addNavButton(nav, "Stream naar TV", this::showCastPanel);
        }
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

        if (isTvBuild()) {
            LinearLayout castCard = cardContainer();
            castCard.addView(text("📺 Stream vanaf telefoon", 20, Color.WHITE, true));
            TextView ready = text(
                    "Klaar om media van The One Media Player op je telefoon te ontvangen. Zorg dat telefoon en TV op hetzelfde wifi-netwerk zitten.",
                    15,
                    MUTED,
                    false
            );
            ready.setPadding(0, dp(8), 0, 0);
            castCard.addView(ready);
            addCard(box, castCard);
        } else {
            Button cast = button("📺 Stream naar TV / Chromecast");
            cast.setOnClickListener(v -> showCastPanel());
            box.addView(cast);
            TextView castInfo = text(
                    "Ondersteunt The One Android TV én Google Cast-apparaten zoals Chromecast en Google TV.",
                    14,
                    MUTED,
                    false
            );
            castInfo.setPadding(0, dp(8), 0, 0);
            box.addView(castInfo);
        }

        scroll.addView(box);
        content.addView(scroll);
    }

    private boolean isTvBuild() {
        return "com.theone.mediaplayer.tv".equals(getPackageName());
    }

    private void startTvCastReceiver() {
        if (castReceiver != null) return;
        castReceiver = new TheOneCast.Receiver(new TheOneCast.ReceiverListener() {
            @Override
            public void onPlay(String url, String title) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Stream ontvangen: " + title, Toast.LENGTH_SHORT).show();
                    showPlayer(url);
                });
            }

            @Override
            public void onStop() {
                runOnUiThread(() -> showShell("Home"));
            }
        });
        castReceiver.start();
    }

    private void showCastPanel() {
        if (isTvBuild()) return;

        // Lazy initialisatie: Cast is optioneel en mag de hoofdapp nooit laten crashen.
        if (googleCastContext == null) {
            initGoogleCast();
        }

        content.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox("Stream naar TV", "Stuur video of muziek van je telefoon rechtstreeks naar The One Media Player op Android TV.");
        addInfo(box, "Telefoon en TV moeten op hetzelfde wifi-netwerk zitten.");

        LinearLayout googleCard = cardContainer();
        googleCard.addView(text("Google Cast", 20, Color.WHITE, true));
        TextView googleInfo = text(
                "Voor Chromecast, Google TV en andere Google Cast-apparaten. Kies eerst je apparaat met het Cast-icoon.",
                14,
                MUTED,
                false
        );
        googleInfo.setPadding(0, dp(6), 0, dp(10));
        googleCard.addView(googleInfo);

        MediaRouteButton panelCastButton = createGoogleCastButton();
        if (panelCastButton != null) {
            googleCard.addView(panelCastButton, new LinearLayout.LayoutParams(dp(64), dp(52)));
        } else {
            TextView castFallback = text(
                    "Google Cast-knop is op dit apparaat niet beschikbaar. The One Android TV-streaming blijft wel werken.",
                    14,
                    MUTED,
                    false
            );
            googleCard.addView(castFallback);
        }

        Button castLocalGoogle = button("Stream gekozen telefoonbestand naar Chromecast");
        castLocalGoogle.setOnClickListener(v -> requestLocalGoogleCast());
        googleCard.addView(castLocalGoogle);

        addCard(box, googleCard);
        addInfo(box, "The One Android TV blijft daarnaast rechtstreeks beschikbaar.");

        castStatus = text("Nog geen The One TV verbonden.", 15, MUTED, false);
        castStatus.setPadding(0, 0, 0, dp(14));
        box.addView(castStatus);

        Button findTv = button("1. Zoek Android TV");
        findTv.setOnClickListener(v -> discoverTv(true, null));
        box.addView(findTv);

        Button choose = button("2. Kies video of muziek op telefoon");
        choose.setOnClickListener(v -> choosePhoneMedia());
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chooseLp.topMargin = dp(10);
        box.addView(choose, chooseLp);

        Button start = button("3. Start stream op TV");
        start.setOnClickListener(v -> startSelectedPhoneMediaCast());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        startLp.topMargin = dp(10);
        box.addView(start, startLp);

        Button stop = button("Stop stream op TV");
        stop.setOnClickListener(v -> stopTvCast());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        stopLp.topMargin = dp(10);
        box.addView(stop, stopLp);

        scroll.addView(box);
        content.addView(scroll);
    }

    private void choosePhoneMedia() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        startActivityForResult(pick, REQ_PICK_CAST_MEDIA);
    }

    private void discoverTv(boolean showFeedback, Runnable afterFound) {
        if (castStatus != null) castStatus.setText("Android TV zoeken…");
        TheOneCast.discover(devices -> runOnUiThread(() -> {
            if (devices.isEmpty()) {
                selectedTv = null;
                if (castStatus != null) castStatus.setText("Geen The One Android TV gevonden. Open de TV-app en controleer wifi.");
                if (showFeedback) Toast.makeText(this, "Geen Android TV gevonden", Toast.LENGTH_SHORT).show();
                return;
            }

            if (devices.size() == 1) {
                selectedTv = devices.get(0);
                updateCastStatus("Verbonden met " + selectedTv.name);
                if (afterFound != null) afterFound.run();
                return;
            }

            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) labels[i] = devices.get(i).toString();
            new AlertDialog.Builder(this)
                    .setTitle("Kies Android TV")
                    .setItems(labels, (dialog, which) -> {
                        selectedTv = devices.get(which);
                        updateCastStatus("Verbonden met " + selectedTv.name);
                        if (afterFound != null) afterFound.run();
                    })
                    .setNegativeButton("Annuleren", null)
                    .show();
        }));
    }

    private void startSelectedPhoneMediaCast() {
        if (selectedCastUri == null) {
            choosePhoneMedia();
            return;
        }
        if (selectedTv == null) {
            discoverTv(false, this::startSelectedPhoneMediaCast);
            return;
        }

        try {
            if (phoneMediaServer != null) phoneMediaServer.stop();
            phoneMediaServer = new TheOneCast.PhoneMediaServer(this, selectedCastUri);
            phoneMediaServer.start();
            String url = phoneMediaServer.urlFor(selectedTv);
            if (url == null) {
                updateCastStatus("Kon lokaal telefoonadres niet bepalen.");
                return;
            }

            String title = TheOneCast.displayName(this, selectedCastUri);
            updateCastStatus("Stream starten: " + title);
            TheOneCast.sendPlay(selectedTv, url, title, (ok, message) ->
                    runOnUiThread(() -> {
                        updateCastStatus(message);
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    })
            );
        } catch (Throwable e) {
            updateCastStatus("Kon media niet delen vanaf telefoon.");
        }
    }

    private void castCurrentUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        Runnable send = () -> TheOneCast.sendPlay(
                selectedTv,
                url,
                "The One Media Player",
                (ok, message) -> runOnUiThread(() ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show())
        );
        if (selectedTv == null) discoverTv(false, send);
        else send.run();
    }

    private void stopTvCast() {
        if (selectedTv == null) {
            discoverTv(false, this::stopTvCast);
            return;
        }
        TheOneCast.sendStop(selectedTv, (ok, message) -> runOnUiThread(() -> {
            updateCastStatus(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }));
        if (phoneMediaServer != null) {
            phoneMediaServer.stop();
            phoneMediaServer = null;
        }
    }

    private void updateCastStatus(String value) {
        if (castStatus != null) castStatus.setText(value);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_CAST_MEDIA || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        selectedCastUri = uri;
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }

        String name = TheOneCast.displayName(this, uri);
        updateCastStatus("Gekozen: " + name);
        if (selectedTv != null) startSelectedPhoneMediaCast();
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
        Button subtitles = PremiumUi.chipButton(this, "CC  Ondertiteling");
        subtitles.setOnClickListener(v -> showSubtitleSelector());
        FrameLayout.LayoutParams subtitleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        subtitleLp.topMargin = dp(14);
        subtitleLp.rightMargin = dp(14);
        root.addView(subtitles, subtitleLp);

        if (!isTvBuild() && !urls.isEmpty()) {
            String castUrl = urls.get(0);
            if (castUrl != null && (castUrl.startsWith("http://") || castUrl.startsWith("https://"))) {
                MediaRouteButton googleRoute = createGoogleCastButton();
                if (googleRoute != null) {
                    // CastButtonFactory beheert zelf de click listener van MediaRouteButton.
                    // Zet alleen de media klaar; bij een geslaagde Cast-sessie wordt deze geladen.
                    pendingGoogleCastUrl = castUrl;
                    pendingGoogleCastTitle = "The One Media Player";
                    pendingLocalGoogleCast = false;

                    FrameLayout.LayoutParams googleLp = new FrameLayout.LayoutParams(
                            dp(56),
                            dp(48),
                            Gravity.TOP | Gravity.START
                    );
                    googleLp.topMargin = dp(14);
                    googleLp.leftMargin = dp(14);
                    root.addView(googleRoute, googleLp);
                }

                Button cast = PremiumUi.chipButton(this, "📺 The One TV");
                cast.setOnClickListener(v -> castCurrentUrl(castUrl));
                FrameLayout.LayoutParams castLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START
                );
                castLp.topMargin = dp(70);
                castLp.leftMargin = dp(14);
                root.addView(cast, castLp);
            }
        }

        boolean movieOrSeries = false;
        for (String url : urls) {
            if (url == null) continue;
            String lower = url.toLowerCase();
            if (lower.contains("/movie/") || lower.contains("/series/")) {
                movieOrSeries = true;
                break;
            }
        }

        if (movieOrSeries) {
            final boolean[] firstPlaybackControls = {true};
            activePlayerView.setControllerVisibilityListener(
                    (androidx.media3.ui.PlayerView.ControllerVisibilityListener) visibility -> {
                if (visibility == View.GONE) {
                    subtitles.setVisibility(View.GONE);
                    firstPlaybackControls[0] = false;
                } else if (!firstPlaybackControls[0]) {
                    subtitles.setVisibility(View.VISIBLE);
                }
            });

            // Tijdens het starten kort beschikbaar; zodra de film/serie speelt verdwijnt CC.
            // Tik op de speler om de bediening (en CC) later weer te tonen.
            subtitles.postDelayed(() -> subtitles.setVisibility(View.GONE), 1800);
        }

        setContentView(root);
        root.post(this::enterImmersiveFullscreen);
    }

    private void showSubtitleSelector() {
        if (player == null) return;

        List<SubtitleOption> options = new ArrayList<>();
        int selectedIndex = 0;
        int optionNumber = 1;

        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;

            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i, true)) continue;

                Format format = group.getTrackFormat(i);
                String label = subtitleLabel(format, optionNumber++);
                SubtitleOption option = new SubtitleOption(group, i, label);
                options.add(option);

                if (group.isTrackSelected(i)) selectedIndex = options.size();
            }
        }

        if (options.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Ondertiteling")
                    .setMessage("Voor deze video zijn geen selecteerbare ondertitels gevonden.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String[] labels = new String[options.size() + 1];
        labels[0] = "Uit";
        for (int i = 0; i < options.size(); i++) labels[i + 1] = options.get(i).label;

        final int initiallySelected = selectedIndex;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ondertiteling kiezen")
                .setSingleChoiceItems(labels, initiallySelected, null)
                .setNegativeButton("Annuleren", null)
                .setPositiveButton("Kiezen", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int checked = dialog.getListView().getCheckedItemPosition();

                    if (checked <= 0) {
                        player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters()
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                        .build()
                        );
                    } else {
                        SubtitleOption chosen = options.get(checked - 1);
                        TrackSelectionOverride override = new TrackSelectionOverride(
                                chosen.group.getMediaTrackGroup(),
                                chosen.trackIndex
                        );
                        player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters()
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(override)
                                        .build()
                        );
                    }

                    dialog.dismiss();
                    enterImmersiveFullscreen();
                }));

        dialog.show();
    }

    private String subtitleLabel(Format format, int fallbackIndex) {
        String label = format.label == null ? "" : format.label.trim();
        String language = format.language == null ? "" : format.language.trim();

        if (!label.isEmpty() && !language.isEmpty() && !label.equalsIgnoreCase(language)) {
            return label + " • " + language.toUpperCase();
        }
        if (!label.isEmpty()) return label;
        if (!language.isEmpty()) return language.toUpperCase();
        return "Ondertiteling " + fallbackIndex;
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
        if (playerFullscreen && isTvBuild()) {
            finish();
        } else if (playerFullscreen) {
            showShell("Home");
        } else {
            super.onBackPressed();
        }
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
        if (castReceiver != null) {
            castReceiver.stop();
            castReceiver = null;
        }
        if (phoneMediaServer != null) {
            phoneMediaServer.stop();
            phoneMediaServer = null;
        }
        if (googleCastContext != null) {
            try {
                googleCastContext.getSessionManager().removeSessionManagerListener(
                        googleCastSessionListener,
                        CastSession.class
                );
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    private static class SubtitleOption {
        final Tracks.Group group;
        final int trackIndex;
        final String label;

        SubtitleOption(Tracks.Group group, int trackIndex, String label) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.label = label;
        }
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
