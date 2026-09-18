package com.theone.mediaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

import java.net.URLEncoder;

public class YouTubeActivity extends Activity {
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int BLUE = Color.rgb(32, 184, 255);

    private WebView webView;
    private WebChromeClient chromeClient;
    private FrameLayout root;
    private FrameLayout fullscreenHolder;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(18), dp(18), dp(14));

        Button back = new Button(this);
        back.setText("←");
        back.setAllCaps(false);
        back.setTextColor(Color.WHITE);
        back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 92, 130)));
        back.setOnClickListener(v -> goBack());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("  ◉  THE ONE   MEDIA PLAYER");
        title.setTextColor(BLUE);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        shell.addView(header);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " TheOneMediaPlayer/1.1");

        webView.setBackgroundColor(BG);
        webView.setWebViewClient(new WebViewClient());

        chromeClient = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                fullscreenView = view;
                fullscreenCallback = callback;

                fullscreenHolder = new FrameLayout(YouTubeActivity.this);
                fullscreenHolder.setBackgroundColor(Color.BLACK);
                fullscreenHolder.addView(
                        view,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )
                );

                root.addView(
                        fullscreenHolder,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )
                );

                shell.setVisibility(View.GONE);
                enterFullscreen();
            }

            @Override
            public void onHideCustomView() {
                exitVideoFullscreen(shell);
            }
        };
        webView.setWebChromeClient(chromeClient);

        shell.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        String query = getIntent().getStringExtra("query");
        if (query == null) query = "";

        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            webView.loadUrl("https://m.youtube.com/results?search_query=" + encoded);
        } catch (Exception e) {
            webView.loadUrl("https://m.youtube.com/");
        }
    }

    private void goBack() {
        if (fullscreenView != null) {
            if (chromeClient != null) {
                chromeClient.onHideCustomView();
            }
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private void exitVideoFullscreen(LinearLayout shell) {
        if (fullscreenView == null) return;

        try {
            if (fullscreenHolder != null) {
                root.removeView(fullscreenHolder);
            }
        } catch (Throwable ignored) {
        }

        fullscreenView = null;
        fullscreenHolder = null;

        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }

        shell.setVisibility(View.VISIBLE);
        exitFullscreen();
    }

    private void enterFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void exitFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                ((ViewGroup) webView.getParent()).removeView(webView);
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignored) {
        }

        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
