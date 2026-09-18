package com.theone.mediaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(10));

        LinearLayout brand = PremiumUi.brandHeader(this, "YouTube");
        header.addView(brand);

        Button back = PremiumUi.chipButton(this, "←  Terug");
        back.setOnClickListener(v -> goBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        backLp.topMargin = dp(6);
        header.addView(back, backLp);

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

        String videoId = getIntent().getStringExtra("video_id");
        if (videoId == null || videoId.trim().isEmpty()) {
            String videoUrl = getIntent().getStringExtra("video_url");
            videoId = extractVideoId(videoUrl);
        } else {
            videoId = videoId.trim();
        }

        if (!videoId.isEmpty()) {
            String safeVideoId = videoId.replaceAll("[^A-Za-z0-9_-]", "");
            String playerHtml =
                    "<!doctype html><html><head>"
                            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                            + "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}"
                            + "iframe{position:absolute;inset:0;width:100%;height:100%;border:0;}</style>"
                            + "</head><body>"
                            + "<iframe src='https://www.youtube.com/embed/" + safeVideoId
                            + "?autoplay=1&playsinline=1&rel=0&controls=1&enablejsapi=1"
                            + "&origin=https%3A%2F%2Fapp.theone.local'"
                            + " allow='autoplay; encrypted-media; picture-in-picture; fullscreen'"
                            + " referrerpolicy='strict-origin-when-cross-origin'"
                            + " allowfullscreen></iframe>"
                            + "</body></html>";

            webView.loadDataWithBaseURL(
                    "https://app.theone.local/",
                    playerHtml,
                    "text/html",
                    "UTF-8",
                    null
            );
        } else {
            String query = getIntent().getStringExtra("query");
            if (query == null) query = "";
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                webView.loadUrl("https://m.youtube.com/results?search_query=" + encoded);
            } catch (Exception e) {
                webView.loadUrl("https://m.youtube.com/");
            }
        }
    }

    private String extractVideoId(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (host.contains("youtu.be")) {
                String path = uri.getPath();
                return path == null ? "" : path.replace("/", "").trim();
            }
            String v = uri.getQueryParameter("v");
            if (v != null && !v.trim().isEmpty()) return v.trim();

            String path = uri.getPath() == null ? "" : uri.getPath();
            int shorts = path.indexOf("/shorts/");
            if (shorts >= 0) {
                String id = path.substring(shorts + 8);
                int slash = id.indexOf('/');
                return (slash >= 0 ? id.substring(0, slash) : id).trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
