package com.gmailorg.thedj;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private static final String START_URL =
        "https://appassets.androidplatform.net/assets/index.html?app=android";
    private static final int PICK_AUDIO = 4201;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean immersive = true;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#071019"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void toggleFullscreen() {
                runOnUiThread(() -> toggleSystemBars());
            }
        }, "TheOneNative");

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(
                WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("appassets.androidplatform.net".equals(url.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, url)); }
                catch (ActivityNotFoundException ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(
                WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"audio/*","video/*","application/octet-stream"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, PICK_AUDIO); }
                catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
        hideSystemBars();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int n=0;n<clip.getItemCount();n++) result[n]=clip.getItemAt(n).getUri();
            } else if (data.getData()!=null) result=new Uri[]{data.getData()};
        }
        fileCallback.onReceiveValue(result);
        fileCallback=null;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        immersive = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c=getWindow().getInsetsController();
            if (c!=null) {
                c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        immersive = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c=getWindow().getInsetsController();
            if (c!=null) c.show(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void toggleSystemBars() {
        if (immersive) showSystemBars();
        else hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        new AlertDialog.Builder(this)
            .setTitle("The One DJ afsluiten?")
            .setMessage("De muziek stopt als je de app afsluit.")
            .setPositiveButton("Afsluiten",(d,w)->finish())
            .setNegativeButton("Doorgaan",null)
            .show();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override protected void onDestroy() {
        if (web!=null) { web.loadUrl("about:blank"); web.destroy(); }
        super.onDestroy();
    }
}
