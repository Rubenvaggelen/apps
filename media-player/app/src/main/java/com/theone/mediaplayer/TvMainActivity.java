package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final int BG = Color.rgb(4, 7, 12);
    private static final int BLUE = Color.rgb(35, 190, 255);
    private static final int MUTED = Color.rgb(150, 164, 180);

    private TheOneCast.Receiver castReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showTvHome();
        startReceiver();
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
        logo.setImageResource(com.theone.mediaplayer.R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(18), 0, 0, 0);

        TextView brand = label("THE ONE", 16, BLUE, true);
        TextView title = label("MEDIA PLAYER • TV", 30, Color.WHITE, true);
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
        addButton(root, "Instellingen", () ->
                startActivity(new Intent(this, SourceConfigActivity.class)));

        TextView cast = label(
                "Stream vanaf telefoon staat klaar wanneer telefoon en TV op hetzelfde wifi-netwerk zitten.",
                15,
                BLUE,
                false
        );
        cast.setPadding(0, dp(24), 0, 0);
        root.addView(cast);

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
        button.setBackground(buttonBackground(false));
        button.setOnClickListener(v -> action.run());
        button.setOnFocusChangeListener((v, focused) -> {
            button.setBackground(buttonBackground(focused));
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

    private GradientDrawable buttonBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                focused
                        ? new int[]{Color.rgb(5, 74, 122), Color.rgb(13, 151, 214)}
                        : new int[]{Color.rgb(10, 18, 30), Color.rgb(14, 42, 62)}
        );
        d.setCornerRadius(dp(24));
        d.setStroke(dp(focused ? 2 : 1), focused ? Color.rgb(104, 226, 255) : Color.rgb(48, 141, 184));
        return d;
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
