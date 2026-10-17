package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
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
    private boolean embedLoadFailed=false;

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
        // A Google TV remote often has no dedicated Play key. Provide a real,
        // focusable on-screen Play button that the remote's central OK activates.
        final boolean tv=getPackageName().endsWith(".tv");
        final Button remotePlay;
        if(tv) {
            remotePlay=button("▶ Start video met OK",()->startTvPlayback(playerStatus));
            remotePlay.setAllCaps(false);
            column.addView(remotePlay,new LinearLayout.LayoutParams(-1,dp(58)));
            remotePlay.requestFocus();
        } else {
            remotePlay=null;
        }
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
        // Also support the remote OK/Enter key if focus has moved into WebView.
        // Do not intercept Back, arrows, or fullscreen media controls.
        if(tv && remotePlay!=null) {
            player.setOnKeyListener((view,keyCode,event)->{
                boolean select=keyCode==KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode==KeyEvent.KEYCODE_ENTER ||
                    keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode==KeyEvent.KEYCODE_MEDIA_PLAY;
                if(!select || fullscreenView!=null)return false;
                if(event.getAction()==KeyEvent.ACTION_UP)remotePlay.performClick();
                return true;
            });
        }
        player.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){
                if(!embedLoadFailed)playerStatus.setText("Officiële videospeler laden…");
            }
            @Override public void onPageFinished(WebView view,String url){
                if(!embedLoadFailed) {
                    if(tv) {
                        playerStatus.setText("Videobeeld geladen. Selecteer ▶ Start video met OK om de grote afspeelknop in het beeld te bedienen.");
                        if(remotePlay!=null)remotePlay.requestFocus();
                    } else {
                        playerStatus.setText("Spelerpagina geladen. Tik op ▶ om af te spelen.");
                    }
                }
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(isOfficialEmbedRequest(request)) {
                    embedLoadFailed=true;
                    playerStatus.setText("Officiële videospeler niet bereikbaar (netwerkfout "+
                        error.getErrorCode()+"). Kies eventueel de andere weergave.");
                    Log.w("TheOneAdult","Adult official embed network error "+error.getErrorCode());
                }
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse error){
                if(isOfficialEmbedRequest(request)) {
                    embedLoadFailed=true;
                    playerStatus.setText("Officiële videospeler geweigerd (HTTP "+
                        error.getStatusCode()+"). Een andere weergave kan worden geprobeerd.");
                    Log.w("TheOneAdult","Adult official embed HTTP "+error.getStatusCode());
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
        // On TV, the provider's official embed is primarily designed to be
        // hosted inside an iframe. Offer both the iframe and direct embed.
        // Never load arbitrary search results, popup pages or external domains.
        final boolean[] iframeMode={tv};
        if(tv) {
            Button otherMode=button("↔ Andere spelerweergave proberen",()->{
                if(player==null)return;
                iframeMode[0]=!iframeMode[0];
                embedLoadFailed=false;
                playerStatus.setText(iframeMode[0]
                    ?"Ingesloten speler laden…":"Directe speler laden…");
                loadOfficialEmbed(video.id,iframeMode[0]);
                if(remotePlay!=null)remotePlay.requestFocus();
            });
            otherMode.setAllCaps(false);
            column.addView(otherMode,new LinearLayout.LayoutParams(-1,dp(52)));
        }
        column.addView(player,new LinearLayout.LayoutParams(-1,0,1f));
        if(remotePlay!=null)remotePlay.requestFocus();
        else player.requestFocus();
        embedLoadFailed=false;
        loadOfficialEmbed(video.id,tv);
    }

    private boolean isOfficialEmbedRequest(WebResourceRequest request) {
        if(request.isForMainFrame())return true;
        Uri uri=request.getUrl();
        return "https".equalsIgnoreCase(uri.getScheme()) &&
            "www.pornhub.com".equalsIgnoreCase(uri.getHost()) &&
            uri.getPath()!=null && uri.getPath().startsWith("/embed/");
    }

    private void loadOfficialEmbed(String id,boolean iframeMode){
        if(player==null || !id.matches("[A-Za-z0-9]{1,64}"))return;
        if(!iframeMode){
            player.loadUrl(EMBED+id);
            return;
        }
        // Frame content uses the provider's published, official embed URL.
        // No unverified third-party playback URL or extracted video stream.
        String html="<!doctype html><html><head>"+
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"+
            "<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}"+
            "iframe{width:100%;height:100%;border:0}</style></head><body>"+
            "<iframe id='provider-player' src='"+EMBED+id+"' "+
            "allow='autoplay; fullscreen; picture-in-picture' allowfullscreen></iframe>"+
            "</body></html>";
        player.loadDataWithBaseURL("https://www.pornhub.com/",html,"text/html","UTF-8",null);
    }

    /**
     * Google TV has no touch screen. Provider embeds frequently render their
     * actual play overlay in a cross-origin iframe: evaluateJavascript cannot
     * reach that overlay. Translate an explicit OK press into a pointer tap
     * at the CENTER OF THE VISIBLE WEBVIEW, where the provider's central play
     * icon is presented. Dispatch both DOWN and UP into Chromium/WebView.
     * This is only invoked after the owner presses the Play button/remote OK;
     * never attempt to autoplay or bypass provider restrictions.
     */
    private void startTvPlayback(TextView status) {
        if(!showingPlayer || player==null || !authorized())return;
        if(embedLoadFailed){
            status.setText("De provider heeft de speler niet geladen. Probeer de andere weergave.");
            return;
        }
        final WebView activePlayer=player;
        if(activePlayer.getWidth()<=0 || activePlayer.getHeight()<=0){
            status.setText("Het videobeeld wordt nog opgebouwd. Probeer OK over een moment.");
            return;
        }
        final float x=activePlayer.getWidth()/2f;
        final float y=activePlayer.getHeight()/2f;
        final long downAt=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(downAt,downAt,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(downAt,downAt+70,MotionEvent.ACTION_UP,x,y,0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean deliveredDown=false,deliveredUp=false;
        try {
            deliveredDown=activePlayer.dispatchTouchEvent(down);
            deliveredUp=activePlayer.dispatchTouchEvent(up);
        } catch (RuntimeException e) {
            Log.w("TheOneAdult","TV WebView playback remote touch rejected");
        } finally {
            down.recycle();
            up.recycle();
        }
        if(deliveredDown || deliveredUp) {
            status.setText("OK naar de afspeelknop in het videobeeld gestuurd. "+
                "Kijk of de video begint. Zo niet, probeer de andere spelerweergave.");
        } else {
            status.setText("De TV-speler heeft de OK-bediening niet aangenomen. "+
                "Probeer de andere weergave.");
        }
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
