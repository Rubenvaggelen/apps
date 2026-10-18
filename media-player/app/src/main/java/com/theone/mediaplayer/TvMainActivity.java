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

    @Override protected void onResume() {
        super.onResume();
        MediaPlayerTvInstaller.resumeIfPending(this);
    }

    private void showTvHome() {
        // Dedicated The One tile grid, not the old full-width list.
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34),dp(25),dp(34),dp(15));
        scroll.addView(root);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.remote_final);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo,new LinearLayout.LayoutParams(dp(76),dp(76)));
        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(16),0,0,0);
        titles.addView(label("THE ONE",14,BLUE,true));
        titles.addView(label("MEDIA PLAYER",30,Color.WHITE,true));
        header.addView(titles);
        root.addView(header);

        TextView intro=label("Kies wat je wilt kijken",17,MUTED,false);
        intro.setPadding(0,dp(18),0,dp(14));
        root.addView(intro);

        PremiumUi.addFamilyGridRow(this,root,
            new String[]{"▣","▶","▤"},
            new String[]{"Live TV","Films","Series"},
            new Runnable[]{
                ()->openCatalog("live"),
                ()->openCatalog("movie"),
                ()->openCatalog("series")
            });
        PremiumUi.addFamilyGridRow(this,root,
            new String[]{"✦","▦","↻"},
            new String[]{"XXX 🔒","Favorieten","Updates"},
            new Runnable[]{
                ()->startActivity(new Intent(this,AdultHubActivity.class)),
                this::openFavorites,
                ()->MediaPlayerUpdateChecker.checkForUpdateManual(this)
            });

        TextView cast=label("Streamen vanaf je telefoon is beschikbaar op hetzelfde wifi-netwerk.",
            14,BLUE,false);
        cast.setGravity(Gravity.CENTER);
        cast.setPadding(0,dp(12),0,0);
        root.addView(cast);

        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        // Footer outside the ScrollView so it stays visible without scrolling.
        screen.addView(PremiumUi.familyFooter(this),
            new LinearLayout.LayoutParams(-1,-2));
        setContentView(screen);
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

    private void openFavorites() {
        Intent intent = new Intent(this, XtreamCatalogActivity.class);
        intent.putExtra("favorites_only", true);
        startActivity(intent);
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
