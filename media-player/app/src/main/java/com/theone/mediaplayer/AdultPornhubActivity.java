package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.CookieManager;
import android.util.Log;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adult-entitlement-gated Pornhub Webmasters catalog and official embed player.
 * No native browser, WebView popups or external links. Never shows videos whose
 * provider duration is missing, malformed, or below 05:00.
 */
public final class AdultPornhubActivity extends Activity {
    private static final String API="https://www.pornhub.com/webmasters/search?search=";
    private static final String EMBED="https://www.pornhub.com/embed/";
    private static final int PAGE_LIMIT=30;
    private FrameLayout outer;
    private WebView player;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private final List<Video> displayed=new ArrayList<>();
    private String lastSearch="";
    private int lastPage=1;
    private boolean showingPlayer=false;

    private static final class Video {
        final String id,title,duration;
        Video(String id,String title,String duration){
            this.id=id;this.title=title;this.duration=duration;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
        outer=new FrameLayout(this);
        outer.setBackgroundColor(PremiumUi.BG);
        setContentView(outer);
        showSearch();
    }

    private boolean authorized(){
        if(MediaPlayerAdultGate.isUnlocked())return true;
        finish();
        return false;
    }

    private TextView label(String text,int sp,int color){
        TextView view=new TextView(this);
        view.setText(text);view.setTextSize(sp);view.setTextColor(color);
        view.setPadding(0,dp(7),0,dp(7));
        return view;
    }

    private Button button(String title,Runnable action){
        Button button=PremiumUi.chipButton(this,title);
        button.setFocusable(true);
        button.setOnClickListener(v->{if(authorized())action.run();});
        return button;
    }

    private void showSearch(){
        if(!authorized())return;
        showingPlayer=false;
        destroyPlayer();
        outer.removeAllViews();
        ScrollView scroll=new ScrollView(this);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22),dp(16),dp(22),dp(28));
        scroll.addView(box);
        outer.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        box.addView(PremiumUi.brandHeader(this,"XXX • Pornhub"));
        box.addView(button("← Adult",this::finish));
        box.addView(label("Alleen video's van 5 minuten of langer",18,Color.WHITE));
        box.addView(label("De videoduur wordt vooraf gecontroleerd. Video's zonder geldige duur worden verborgen.",13,PremiumUi.MUTED));
        EditText search=PremiumUi.searchField(this,"Zoek op Pornhub…");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setText(lastSearch);
        search.setFocusable(true);
        search.setFocusableInTouchMode(true);
        box.addView(search);
        final LinearLayout results=new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        final java.util.function.Consumer<String> submitSearch=term->{
            lastSearch=term.trim();
            lastPage=1;
            if(lastSearch.length()<2){
                Toast.makeText(this,"Vul minimaal 2 letters in.",Toast.LENGTH_SHORT).show();
                return;
            }
            search.setText(lastSearch);
            searchVideos(lastSearch,lastPage,results);
        };
        boolean tv=getPackageName().endsWith(".tv");
        if(tv) {
            // Many TV launchers do not provide a working native text IME.
            // Make D-pad OK open our own keyboard, and expose an obvious action.
            search.setShowSoftInputOnFocus(false);
            search.setOnClickListener(v->{
                if(authorized())AdultTvKeyboard.show(this,search.getText().toString(),submitSearch);
            });
            box.addView(button("⌨ Zoektekst invoeren met afstandsbediening",()->
                AdultTvKeyboard.show(this,search.getText().toString(),submitSearch)));
        } else {
            search.setOnClickListener(v->{
                search.requestFocus();
                InputMethodManager keyboard=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                if(keyboard!=null)keyboard.showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);
            });
            search.setOnEditorActionListener((view,action,event)->{
                if(action==EditorInfo.IME_ACTION_SEARCH){
                    submitSearch.accept(search.getText().toString());
                    return true;
                }
                return false;
            });
        }
        box.addView(button("Zoeken",()->submitSearch.accept(search.getText().toString())));
        box.addView(results);
        renderResults(results);
        if(!lastSearch.isEmpty() && displayed.isEmpty())searchVideos(lastSearch,lastPage,results);
    }

    private void searchVideos(String query,int page,LinearLayout resultContainer){
        if(!authorized())return;
        resultContainer.removeAllViews();
        resultContainer.addView(label("Video's vanaf 5 minuten laden…",15,Color.WHITE));
        final String queryAtRequest=query;
        new Thread(()->{
            final List<Video> matches=new ArrayList<>();
            String error=null;
            HttpURLConnection connection=null;
            try{
                String url=API+URLEncoder.encode(queryAtRequest,"UTF-8")+"&page="+page;
                connection=(HttpURLConnection)new URL(url).openConnection();
                connection.setConnectTimeout(9000);connection.setReadTimeout(14000);
                connection.setRequestProperty("Accept","application/json");
                connection.setRequestProperty("User-Agent","TheOneMediaPlayer/2.4");
                if(connection.getResponseCode()!=200)throw new java.io.IOException("HTTP "+connection.getResponseCode());
                try(InputStream in=connection.getInputStream()){
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    byte[] chunk=new byte[8192];int n;
                    while((n=in.read(chunk))!=-1){
                        buffer.write(chunk,0,n);
                        if(buffer.size()>2*1024*1024)throw new java.io.IOException("Antwoord te groot");
                    }
                    JSONObject response=new JSONObject(buffer.toString("UTF-8"));
                    JSONArray videos=response.optJSONArray("videos");
                    if(videos==null)throw new java.io.IOException("Geen geldige videocatalogus");
                    for(int i=0;i<videos.length() && i<PAGE_LIMIT;i++){
                        JSONObject item=videos.optJSONObject(i);
                        if(item==null)continue;
                        String duration=item.optString("duration","");
                        if(!AdultVideoDuration.allow(duration))continue;
                        String id=item.optString("video_id","");
                        if(!id.matches("[A-Za-z0-9]{1,64}"))continue;
                        String title=item.optString("title","Video").trim();
                        if(title.length()>120)title=title.substring(0,120);
                        matches.add(new Video(id,title,duration));
                    }
                }
            }catch(Exception e){error="De Pornhub-catalogus is nu niet bereikbaar.";}
            finally{if(connection!=null)connection.disconnect();}
            String finalError=error;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||!authorized()||!lastSearch.equals(queryAtRequest)||lastPage!=page)return;
                displayed.clear();displayed.addAll(matches);
                resultContainer.removeAllViews();
                if(finalError!=null){
                    resultContainer.addView(label(finalError,15,Color.WHITE));
                    resultContainer.addView(button("Opnieuw proberen",()->searchVideos(queryAtRequest,page,resultContainer)));
                }else{
                    renderResults(resultContainer);
                }
            });
        },"adult-video-catalog").start();
    }

    private void renderResults(LinearLayout box){
        if(lastSearch.isEmpty())return;
        if(displayed.isEmpty()){
            box.addView(label("Geen video's van minimaal 5 minuten gevonden op deze pagina.",15,Color.WHITE));
        }
        for(Video video:displayed){
            box.addView(button(video.title+"  •  "+video.duration,()->showEmbeddedVideo(video)));
        }
        // Paginate in constant memory; results remain restricted by 5-min filter.
        box.addView(button("Volgende resultaten →",()->{
            lastPage++;
            displayed.clear();
            searchVideos(lastSearch,lastPage,box);
        }));
        if(lastPage>1)box.addView(button("← Vorige resultaten",()->{
            lastPage--;
            displayed.clear();
            searchVideos(lastSearch,lastPage,box);
        }));
    }

    private void showEmbeddedVideo(Video video){
        if(!authorized() || !AdultVideoDuration.allow(video.duration) ||
           !video.id.matches("[A-Za-z0-9]{1,64}"))return;
        showingPlayer=true;
        outer.removeAllViews();
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        outer.addView(column,new FrameLayout.LayoutParams(-1,-1));
        column.addView(button("← Terug naar resultaten",this::showSearch));
        column.addView(label(video.title+"  •  "+video.duration,15,Color.WHITE));
        final TextView playerStatus=label("Videospeler starten…",14,PremiumUi.MUTED);
        column.addView(playerStatus);
        try {
            player=new WebView(this);
        } catch (Throwable webViewUnavailable) {
            // Android TV builds without a system WebView must not crash or open
            // an external browser. Keep the Adult library accessible.
            showingPlayer=false;
            outer.removeAllViews();
            LinearLayout unavailable=new LinearLayout(this);
            unavailable.setPadding(dp(24),dp(24),dp(24),dp(24));
            unavailable.setOrientation(LinearLayout.VERTICAL);
            unavailable.addView(label(
                "De ingebouwde videospeler is op dit toestel niet beschikbaar.",
                17,Color.WHITE));
            unavailable.addView(button("← Terug naar Adult-resultaten",this::showSearch));
            outer.addView(unavailable);
            return;
        }
        player.setBackgroundColor(Color.BLACK);
        WebSettings settings=player.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // HTML5 video needs JS, DOM storage, cookies and an unrestricted
        // in-app media gesture; standard Android WebView otherwise stays blank.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(player,true);
        player.setLayerType(View.LAYER_TYPE_HARDWARE,null);
        player.setFocusable(true);
        player.setFocusableInTouchMode(true);
        player.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){
                playerStatus.setText("Videopagina laden…");
            }
            @Override public void onPageFinished(WebView view,String url){
                playerStatus.setText("Videopagina geladen. Tik op ▶ om af te spelen.");
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(request.isForMainFrame()) {
                    playerStatus.setText("Videopagina niet bereikbaar ("+error.getErrorCode()+"). Probeer terug en een andere video.");
                    Log.w("TheOneAdult","Adult embed WebView network error "+error.getErrorCode());
                }
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse error){
                if(request.isForMainFrame()) {
                    playerStatus.setText("Videopagina geweigerd (HTTP "+error.getStatusCode()+"). Probeer een andere video.");
                    Log.w("TheOneAdult","Adult embed HTTP "+error.getStatusCode());
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                // Prevent video recommendation navigation to unverified/short clips.
                if(!request.isForMainFrame())return false;
                Uri uri=request.getUrl();
                return !(("https".equalsIgnoreCase(uri.getScheme()) &&
                    "www.pornhub.com".equalsIgnoreCase(uri.getHost()) &&
                    ("/embed/"+video.id).equals(uri.getPath())));
            }
        });
        player.setWebChromeClient(new WebChromeClient(){
            @Override public void onShowCustomView(View view,CustomViewCallback callback){
                if(fullscreenView!=null){callback.onCustomViewHidden();return;}
                fullscreenView=view;
                fullscreenCallback=callback;
                outer.addView(view,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            }
            @Override public void onHideCustomView(){closeFullscreen();}
        });
        column.addView(player,new LinearLayout.LayoutParams(-1,0,1f));
        player.requestFocus();
        player.loadUrl(EMBED+video.id);
    }

    private void closeFullscreen(){
        if(fullscreenView==null)return;
        View old=fullscreenView;
        fullscreenView=null;
        outer.removeView(old);
        WebChromeClient.CustomViewCallback cb=fullscreenCallback;
        fullscreenCallback=null;
        if(cb!=null)cb.onCustomViewHidden();
    }

    private void destroyPlayer(){
        closeFullscreen();
        if(player!=null){
            player.stopLoading();
            player.loadUrl("about:blank");
            if(player.getParent() instanceof ViewGroup)((ViewGroup)player.getParent()).removeView(player);
            player.destroy();
            player=null;
        }
    }

    @Override public void onBackPressed(){
        if(fullscreenView!=null){closeFullscreen();return;}
        if(showingPlayer){showSearch();return;}
        super.onBackPressed();
    }
    @Override protected void onResume(){
        super.onResume();
        if(player!=null)player.onResume();
        if(!MediaPlayerAdultGate.isUnlocked())finish();
    }
    @Override protected void onPause(){
        if(player!=null)player.onPause();
        super.onPause();
    }
    @Override protected void onDestroy(){
        destroyPlayer();
        super.onDestroy();
    }
    private int dp(int n){
        return Math.round(n*getResources().getDisplayMetrics().density);
    }
}
