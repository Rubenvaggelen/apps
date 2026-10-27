package com.theone.series.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.SurfaceView;
import android.widget.TextView;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.Arrays;

/**
 * Separate playback engine used ONLY for streams with audio tracks ExoPlayer
 * cannot decode on the current TV (notably E-AC-3 / Dolby Digital Plus).
 * VLC supplies a software decoder and outputs downmixed stereo PCM; all
 * normally supported content keeps the original ExoPlayer route.
 */
public final class VlcFallbackActivity extends Activity {
    private LibVLC engine;
    private MediaPlayer vlc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView info;
    private LinearLayout controls;
    private Button pauseButton;
    private String episodeKey;
    private String episodeTitle;
    private boolean completed;
    private long restoreMs;
    private final Runnable hideControls = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        String url = getIntent().getStringExtra("stream_url");
        episodeKey = getIntent().getStringExtra("episode_key");
        episodeTitle = getIntent().getStringExtra("episode_title");
        restoreMs = Math.max(0L, getIntent().getLongExtra("resume_ms", 0));
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView screen = new SurfaceView(this);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(24), dp(14), dp(24), dp(14));
        controls.setBackgroundColor(0xCC091625);
        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(17);
        info.setText((episodeTitle == null ? "The One Series" : episodeTitle) + " · Softwareaudio (stereo)");
        controls.addView(info);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(button("Terug", () -> finish()));
        buttons.addView(button("−10s", () -> seek(-10000)));
        pauseButton = button("Pauze", this::togglePause);
        buttons.addView(pauseButton);
        buttons.addView(button("+10s", () -> seek(10000)));
        controls.addView(buttons);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        root.setOnClickListener(v -> revealControls());
        setContentView(root);
        revealControls();

        try {
            engine = new LibVLC(getApplicationContext(),
                Arrays.asList("--network-caching=2500", "--no-audio-time-stretch"));
            vlc = new MediaPlayer(engine);
            vlc.setAudioOutput("audiotrack");
            // LibVLC decodes E-AC3 and sends PCM through Android AudioTrack.
            vlc.getVLCVout().setVideoView(screen);
            vlc.getVLCVout().attachViews();
            vlc.setEventListener(event -> {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        if (restoreMs > 0) {
                            long seek = restoreMs;
                            restoreMs = 0;
                            ui.post(() -> { if (vlc != null) vlc.setTime(seek); });
                        }
                        ui.post(() -> { if (pauseButton != null) pauseButton.setText("Pauze"); });
                        break;
                    case MediaPlayer.Event.EndReached:
                        completed = true;
                        ui.post(this::finish);
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        ui.post(() -> {
                            if (info != null) info.setText("Afspelen mislukt via softwaredecoder · Probeer een andere bron");
                            revealControls();
                        });
                        break;
                    default: break;
                }
            });
            Media media = new Media(engine, Uri.parse(url));
            media.addOption(":network-caching=2500");
            media.addOption(":http-user-agent=VLC/3.0.21 LibVLC/3.0.21");
            vlc.setMedia(media);
            media.release();
            vlc.play();
        } catch (Exception | LinkageError failure) {
            if (info != null) info.setText("Softwaredecoder kan niet starten op dit apparaat");
            revealControls();
        }
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> { action.run(); revealControls(); });
        return b;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private void revealControls() {
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, 5000);
    }

    private void togglePause() {
        if (vlc == null) return;
        if (vlc.isPlaying()) {
            vlc.pause();
            pauseButton.setText("Afspelen");
        } else {
            vlc.play();
            pauseButton.setText("Pauze");
        }
    }

    private void seek(long delta) {
        if (vlc == null) return;
        long end = vlc.getLength();
        long next = Math.max(0L, vlc.getTime() + delta);
        if (end > 0) next = Math.min(end, next);
        vlc.setTime(next);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && vlc != null) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    togglePause(); revealControls(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    // Remote OK must still activate the focused Back/Play button.
                    if (controls != null && controls.getVisibility() != View.VISIBLE) {
                        revealControls();
                        if (pauseButton != null) pauseButton.requestFocus();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    vlc.play(); revealControls(); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    vlc.pause(); revealControls(); return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    seek(-10000); revealControls(); return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seek(10000); revealControls(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Do not steal D-pad focus navigation from the buttons.
                    if (controls != null && controls.getVisibility() != View.VISIBLE) {
                        revealControls();
                        if (pauseButton != null) pauseButton.requestFocus();
                        return true;
                    }
                    break;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void storeProgress() {
        if (episodeKey == null || vlc == null) return;
        SharedPreferences.Editor editor = getSharedPreferences("the_one_series_tv", MODE_PRIVATE).edit();
        long time = Math.max(0L, vlc.getTime());
        long duration = vlc.getLength();
        if (completed || (duration > 0 && time >= duration - 15000)) {
            editor.remove("position:" + episodeKey);
        } else if (time > 10000) {
            editor.putLong("position:" + episodeKey, time)
                .putLong("watched:" + episodeKey, System.currentTimeMillis())
                .putString("last_title", episodeTitle)
                .putString("last_key", episodeKey);
        }
        editor.apply();
    }

    @Override protected void onPause() {
        storeProgress();
        if (vlc != null && vlc.isPlaying()) vlc.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        storeProgress();
        if (vlc != null) {
            vlc.setEventListener(null);
            vlc.stop();
            if (vlc.getVLCVout().areViewsAttached()) vlc.getVLCVout().detachViews();
            vlc.release();
            vlc = null;
        }
        if (engine != null) { engine.release(); engine = null; }
        super.onDestroy();
    }
}
