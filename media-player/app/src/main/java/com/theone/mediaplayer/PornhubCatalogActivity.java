package com.theone.mediaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * R18+ protected catalog of official Pornhub embeds.
 *
 * No public browser, no arbitrary URLs, and no video without independently
 * verified duration >= 300 seconds. The in-app WebView is used solely for the
 * official embed; it is not an unrestricted Pornhub website browser.
 */
public final class PornhubCatalogActivity extends Activity {
    private static final String API = "https://www.pornhub.com/webmasters/";
    private static final int TEXT = Color.WHITE;
    private final List<Video> displayed = new ArrayList<>();
    private FrameLayout host;
    private ScrollView catalog;
    private LinearLayout results;
    private TextView status;
    private EditText query;
    private WebView player;
    private View fullscreenCustom;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int generation = 0;
    private boolean playing = false;

    private static final class Video {
        final String id, title, duration;
        Video(String id,String title,String duration) {
            this.id=id;this.title=title;this.duration=duration;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!MediaPlayerAdultGate.isUnlocked()) {
            finish(); // Entry is only via AdultHub after separate Main+PIN check.
            return;
        }
        buildShell();
        search("");
    }

    private void buildShell() {
        host=new FrameLayout(this);
        host.setBackgroundColor(PremiumUi.BG);
        catalog=new ScrollView(this);
        catalog.setFillViewport(true);
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(24),dp(22),dp(24),dp(30));
        catalog.addView(outer);
        outer.addView(PremiumUi.brandHeader(this,"XXX • Pornhub 5+"));

        Button back=PremiumUi.chipButton(this,"←  Terug naar Adult");
        back.setOnClickListener(v->finish());
        back.setFocusable(true);
        outer.addView(back);

        TextView info=new TextView(this);
        info.setText("Alleen video's van minimaal 5 minuten. Ontbrekende speelduur = geblokkeerd. Alleen officieel ingesloten video's.");
        info.setTextColor(PremiumUi.MUTED);
        info.setTextSize(16);
        info.setPadding(0,dp(18),0,dp(10));
        outer.addView(info);

        query=PremiumUi.searchField(this,"Zoek video's van 5 minuten of langer");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v,action,event)->{
            if (action==EditorInfo.IME_ACTION_SEARCH || (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
                search(query.getText().toString());
                return true;
            }
            return false;
        });
        outer.addView(query);

        Button search=PremiumUi.primaryButton(this,"Zoeken • minimaal 5:00");
        search.setFocusable(true);
        search.setOnClickListener(v->search(query.getText().toString()));
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin=dp(10);
        outer.addView(search,slp);

        status=new TextView(this);
        status.setTextColor(PremiumUi.MUTED);
        status.setTextSize(15);
        status.setPadding(0,dp(22),0,dp(16));
        outer.addView(status);
        results=new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        outer.addView(results);

        host.addView(catalog,new FrameLayout.LayoutParams(-1,-1));
        setContentView(host);
    }

    private void search(String text) {
        if (!MediaPlayerAdultGate.isUnlocked()) { finish(); return; }
        String term=text==null?"":text.trim();
        if (term.length()>80) term=term.substring(0,80);
        final String searchTerm=term;
        final int token=++generation;
        status.setText("Video's van 5 minuten of langer laden…");
        displayed.clear();
        results.removeAllViews();
        new Thread(()->{
            List<Video> verified=new ArrayList<>();
            String error=null;
            try {
                String endpoint=API+"search?ordering=mostviewed&thumbsize=small";
                if (!searchTerm.isEmpty()) endpoint+="&search="+URLEncoder.encode(searchTerm,"UTF-8");
                JSONObject payload=fetch(endpoint);
                JSONArray videos=payload.optJSONArray("videos");
                if (videos==null)throw new java.io.IOException("Video metadata unavailable");
                for(int i=0;i<videos.length();i++){
                    JSONObject row=videos.optJSONObject(i);
                    if(row==null)continue;
                    String id=row.optString("video_id",""),duration=row.optString("duration","");
                    if (!PornhubDurationFilter.safeVideoId(id) ||
                        !PornhubDurationFilter.permits(duration)) continue;
                    String title=row.optString("title","Video").trim();
                    if(title.isEmpty())title="Video";
                    verified.add(new Video(id,title,duration));
                }
            }catch(Exception ex){ error="De officiële catalogus is niet beschikbaar. Probeer later opnieuw."; }
            final String message=error;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||token!=generation)return;
                displayed.addAll(verified);
                if(message!=null){status.setText(message);return;}
                status.setText(verified.isEmpty()
                    ?"Geen gecontroleerde video's van minimaal 5 minuten gevonden."
                    :verified.size()+" gecontroleerde video's • allemaal minimaal 5:00");
                for(Video v:verified){
                    Button row=PremiumUi.chipButton(this,v.title+"  •  "+v.duration);
                    row.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                    row.setFocusable(true);
                    row.setOnClickListener(clicked->confirmAndPlay(v));
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
                    lp.bottomMargin=dp(8);
                    results.addView(row,lp);
                }
            });
        },"adult-pornhub-catalog").start();
    }

    private void confirmAndPlay(Video chosen) {
        if (!MediaPlayerAdultGate.isUnlocked() ||
            !PornhubDurationFilter.permits(chosen.duration) ||
            !PornhubDurationFilter.safeVideoId(chosen.id)) {
            status.setText("Video geblokkeerd.");
            return;
        }
        int token=generation;
        status.setText("Speelduur bij de bron opnieuw controleren…");
        new Thread(()->{
            boolean allowed=false;
            try {
                JSONObject response=fetch(API+"video_by_id?id="+
                    URLEncoder.encode(chosen.id,"UTF-8"));
                JSONObject video=response.optJSONObject("video");
                allowed=video!=null
                    && chosen.id.equals(video.optString("video_id",""))
                    && PornhubDurationFilter.permits(video.optString("duration",""));
            }catch(Exception ignored){} // Fail closed; never start an unverified video.
            final boolean accepted=allowed;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||token!=generation)return;
                if(!MediaPlayerAdultGate.isUnlocked()||!accepted){
                    status.setText("Video geblokkeerd: speelduur kon niet veilig worden bevestigd (minimaal 5:00).");
                    return;
                }
                openVerifiedEmbed(chosen.id);
            });
        },"adult-pornhub-verify").start();
    }

    private static JSONObject fetch(String uri) throws Exception {
        URL url=new URL(uri);
        if(!"https".equals(url.getProtocol()) ||
            !"www.pornhub.com".equalsIgnoreCase(url.getHost()) ||
            !url.getPath().startsWith("/webmasters/"))
            throw new java.io.IOException("Invalid catalog origin");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("User-Agent","TheOneMediaPlayer/2.4");
        try{
            if(conn.getResponseCode()!=200)throw new java.io.IOException("API unavailable");
            try(InputStream in=conn.getInputStream()) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] bytes=new byte[8192];
                int n;
                while((n=in.read(bytes))!=-1) {
                    out.write(bytes,0,n);
                    if(out.size()>2*1024*1024)throw new java.io.IOException("Catalog response too large");
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{conn.disconnect();}
    }

    private void openVerifiedEmbed(String id) {
        String url=PornhubDurationFilter.embedUrl(id);
        if(url==null||!MediaPlayerAdultGate.isUnlocked())return;
        try{
            player=new WebView(this);
            player.setBackgroundColor(Color.BLACK);
            player.getSettings().setJavaScriptEnabled(true);
            player.getSettings().setDomStorageEnabled(true);
            player.getSettings().setMediaPlaybackRequiresUserGesture(true);
            player.getSettings().setAllowFileAccess(false);
            player.getSettings().setAllowContentAccess(false);
            player.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
            player.getSettings().setSupportMultipleWindows(false);
            player.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    // Do not become a general browser or leave the 5-minute catalog.
                    String destination=req.getUrl().toString();
                    if ("about:blank".equals(destination))return false;
                    // The only allowed iframe navigation is to the SINGLE
                    // duration-verified embed. Provider recommendations, popups
                    // and arbitrary external links must not bypass the >=5m gate.
                    return !(!req.isForMainFrame() &&
                        destination.equals(PornhubDurationFilter.embedUrl(id)));
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view,String destination) {
                    return !("about:blank".equals(destination) ||
                        PornhubDurationFilter.embedUrl(id).equals(destination));
                }
            });
            player.setWebChromeClient(new WebChromeClient(){
                @Override public void onShowCustomView(View view,CustomViewCallback callback) {
                    if(fullscreenCustom!=null){callback.onCustomViewHidden();return;}
                    fullscreenCustom=view;fullscreenCallback=callback;
                    host.addView(view,new FrameLayout.LayoutParams(-1,-1));
                }
                @Override public void onHideCustomView() { hideCustomView(); }
            });
            // Only the already verified single video is embedded, not a browseable website.
            String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"+
                "<body style='background:#000;margin:0;overflow:hidden'>"+
                "<iframe title='Geverifieerde video' src='"+url+"' scrolling='no' "+
                "style='border:0;width:100vw;height:100vh' allow='fullscreen; autoplay; encrypted-media' allowfullscreen></iframe>"+
                "</body></html>";
            player.loadDataWithBaseURL("https://www.pornhub.com/",html,"text/html","UTF-8",null);
            playing=true;
            catalog.setVisibility(View.GONE);
            host.addView(player,new FrameLayout.LayoutParams(-1,-1));
            Button close=PremiumUi.chipButton(this,"← Terug naar Adult-video's");
            close.setFocusable(true);
            close.setOnClickListener(v->closePlayer());
            FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
            cp.setMargins(dp(12),dp(12),0,0);
            host.addView(close,cp);
            close.setTag("pornhub_close");
            player.requestFocus();
        }catch(Throwable ex) {
            closePlayer();
            status.setText("Ingebouwde speler is op dit apparaat niet beschikbaar.");
        }
    }

    private void hideCustomView() {
        if(fullscreenCustom==null)return;
        host.removeView(fullscreenCustom);
        fullscreenCustom=null;
        if(fullscreenCallback!=null){fullscreenCallback.onCustomViewHidden();fullscreenCallback=null;}
    }

    private void closePlayer() {
        hideCustomView();
        if(player!=null) {
            player.stopLoading();
            player.loadUrl("about:blank");
            host.removeView(player);
            player.destroy();
            player=null;
        }
        for(int i=host.getChildCount()-1;i>=0;i--) {
            View view=host.getChildAt(i);
            if("pornhub_close".equals(view.getTag()))host.removeViewAt(i);
        }
        playing=false;
        if(catalog!=null)catalog.setVisibility(View.VISIBLE);
        if(status!=null)status.setText("Alle video's in deze lijst zijn minimaal 5:00.");
    }

    @Override public void onBackPressed() {
        if(playing){closePlayer();return;}
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
        if(player!=null)player.onResume();
    }

    @Override protected void onPause() {
        if(player!=null)player.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        closePlayer();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}
