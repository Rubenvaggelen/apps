package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class TvMainActivity extends Activity {
    private static final int BG = PremiumUi.BG;
    private static final int BLUE = PremiumUi.BLUE_SOFT;
    private static final int MUTED = Color.rgb(150, 164, 180);

    private TheOneCast.Receiver castReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The TV launcher must check private Dev Hub updates too.
        MediaPlayerUpdateChecker.checkForUpdate(this);
        MediaPlayerAuthorization.requireAccess(this, () -> {
            showTvHome();
            startReceiver();
        });
    }

    private void showTvHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(42), dp(30), dp(42), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.theone.mediaplayer.R.drawable.remote_final);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(18), 0, 0, 0);

        TextView brand = label("THE ONE", 16, BLUE, true);
        TextView title = label("THE ONE MEDIA PLAYER", 27, Color.WHITE, true);
        titles.addView(brand);
        titles.addView(title);
        header.addView(titles);
        root.addView(header);

        TextView ready = label(
                "Android TV-versie • gebruik de pijltjestoetsen en OK op je afstandsbediening.",
                17,
                MUTED,
                false
        );
        ready.setPadding(0, dp(18), 0, dp(24));
        root.addView(ready);

        addButton(root, "Live TV", () -> openCatalog("live"));
        addButton(root, "Films", () -> openCatalog("movie"));
        addButton(root, "Series", () -> openCatalog("series"));
        addButton(root, "XXX 🔒", () -> startActivity(new Intent(this, AdultHubActivity.class)));
        addButton(root, "Over de bibliotheek", () ->
                Toast.makeText(this, "Alle beschikbare zenders, films en series verschijnen automatisch in één bibliotheek.", Toast.LENGTH_LONG).show());

        TextView cast = label(
                "Stream vanaf telefoon staat klaar wanneer telefoon en TV op hetzelfde wifi-netwerk zitten.",
                15,
                BLUE,
                false
        );
        cast.setPadding(0, dp(24), 0, 0);
        root.addView(cast);
        root.addView(PremiumUi.familyFooter(this));

        setContentView(scroll);
    }

    private void startReceiver() {
        try {
            castReceiver = new TheOneCast.Receiver(new TheOneCast.ReceiverListener() {
                @Override
                public void onPlay(String url, String title) {
                    runOnUiThread(() -> {
                        Intent play = new Intent(TvMainActivity.this, MainActivity.class);
                        play.putExtra("play_url", url);
                        play.putExtra("play_title", title);
                        startActivity(play);
                    });
                }

                @Override
                public void onStop() {
                    runOnUiThread(() ->
                            Toast.makeText(TvMainActivity.this, "Stream gestopt.", Toast.LENGTH_SHORT).show());
                }
            });
            castReceiver.start();
        } catch (Throwable ignored) {
            castReceiver = null;
        }
    }

    private void openCatalog(String mode) {
        Intent intent = new Intent(this, XtreamCatalogActivity.class);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }

    private void addButton(LinearLayout root, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(20);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setMinHeight(dp(64));
        button.setPadding(dp(26), dp(12), dp(26), dp(12));
        button.setBackground(PremiumUi.familyTileBackground(this, false));
        button.setOnClickListener(v -> action.run());
        button.setOnFocusChangeListener((v, focused) -> {
            button.setBackground(PremiumUi.familyTileBackground(this, focused));
            button.setScaleX(focused ? 1.025f : 1f);
            button.setScaleY(focused ? 1.025f : 1f);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(14);
        root.addView(button, lp);
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (castReceiver != null) {
            castReceiver.stop();
            castReceiver = null;
        }
        super.onDestroy();
    }
}
