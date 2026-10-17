package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
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
public final class AndroidPornhubActivity extends Activity {
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
    // Google TV fullscreen: keep the same WebView/video session; only hide
    // the surrounding UI. Never reload the provider's already playing iframe.
    private boolean tvFullscreen=false;
    private int previousSystemUiFlags=0;
    private View[] tvVideoChrome;
    private Button tvBackOverlay;
    private TextView tvSeekOverlay;
    private LinearLayout phoneSeekButtons;
    private final Runnable hidePhoneButtons=()->{
        if(tvFullscreen && phoneSeekButtons!=null)phoneSeekButtons.setVisibility(View.GONE);
    };
    // Provider duration is validated before it enters the catalog. The
    // playback clock is a fallback estimate ONLY when the iframe keeps its
    // real HTMLVideoElement inaccessible across the browser origin boundary.
    private int tvClipDurationSeconds=-1;
    private double tvEstimatedPositionSeconds=0;
    private long tvEstimatedPositionAtMs=0;
    private boolean tvPlaybackRequested=false;
    private final Runnable hideTvSeek=()->{
        if(tvSeekOverlay!=null)tvSeekOverlay.setVisibility(View.GONE);
    };
    private final Handler tvOverlayHandler=new Handler(Looper.getMainLooper());
    private final Runnable hideTvBack=()->{
        if(tvFullscreen && tvBackOverlay!=null){
            if(tvBackOverlay.hasFocus() && player!=null)player.requestFocus();
            tvBackOverlay.setVisibility(View.GONE);
        }
    };

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
        leaveTvFullscreen();
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
        boolean tv=false; // Android phone: keep the native touchscreen keyboard.
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
        tvClipDurationSeconds=AdultVideoDuration.parseSeconds(video.duration);
        tvEstimatedPositionSeconds=0;
        tvEstimatedPositionAtMs=0;
        tvPlaybackRequested=false;
        outer.removeAllViews();
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        outer.addView(column,new FrameLayout.LayoutParams(-1,-1));
        final Button backToResults=button("← Terug naar resultaten",this::showSearch);
        final TextView videoTitle=label(video.title+"  •  "+video.duration,15,Color.WHITE);
        column.addView(backToResults);
        column.addView(videoTitle);
        final TextView playerStatus=label("Videospeler starten…",14,PremiumUi.MUTED);
        column.addView(playerStatus);
        // A Google TV remote often has no dedicated Play key. Provide a real,
        // focusable on-screen Play button that the remote's central OK activates.
        final boolean tv=true; // Use the proven Google TV iframe and play workflow on Android.
        final Button remotePlay;
        if(tv) {
            remotePlay=button("▶ Afspelen — tik om te starten",()->startTvPlayback(playerStatus));
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
        if(false && remotePlay!=null) { // Android uses touch; do not steal hardware OK keys.
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
                        playerStatus.setText("Videobeeld geladen. Tik op ▶ Afspelen om de video te starten.");
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
                if(tv) {
                    enterTvFullscreen();
                    if(tvBackOverlay!=null)tvBackOverlay.bringToFront();
                    if(phoneSeekButtons!=null)phoneSeekButtons.bringToFront();
                }
            }
            @Override public void onHideCustomView(){closeFullscreen();}
        });
        // On TV, the provider's official embed is primarily designed to be
        // hosted inside an iframe. Offer both the iframe and direct embed.
        // Never load arbitrary search results, popup pages or external domains.
        final boolean[] iframeMode={tv};
        Button otherMode=null;
        if(tv) {
            otherMode=button("↔ Andere spelerweergave proberen",()->{
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
        if(tv) tvVideoChrome=new View[]{backToResults,videoTitle,playerStatus,remotePlay,otherMode};
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
            status.setText("Het videobeeld wordt nog opgebouwd. Probeer opnieuw over een moment.");
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
            tvPlaybackRequested=true;
            tvEstimatedPositionAtMs=SystemClock.elapsedRealtime();
            status.setText("Afspelen gestart; overschakelen naar volledig scherm…");
            // The official provider player lives in a cross-origin iframe, so
            // playback state cannot be read reliably. After a successful
            // user-initiated play gesture, expand the SAME playing WebView.
            activePlayer.postDelayed(()->{
                if(showingPlayer && player==activePlayer && !embedLoadFailed &&
                   !isFinishing() && !isDestroyed())enterTvFullscreen();
            },900);
        } else {
            status.setText("De videospeler heeft de afspeelbediening niet aangenomen. "+
                "Probeer de andere weergave.");
        }
    }

    private void enterTvFullscreen() {
        if(tvFullscreen || !showingPlayer || player==null ||
           !authorized())return;
        tvFullscreen=true;
        previousSystemUiFlags=getWindow().getDecorView().getSystemUiVisibility();
        if(tvVideoChrome!=null)for(View chrome:tvVideoChrome) {
            if(chrome!=null)chrome.setVisibility(View.GONE);
        }
        // Keep the WebView in its original parent and do not call loadUrl:
        // a re-created iframe would interrupt the video that already plays.
        int flags=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        tvBackOverlay=button("← Terug",this::showSearch);
        tvBackOverlay.setAllCaps(false);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(
            dp(170),dp(54),Gravity.TOP|Gravity.START);
        p.setMargins(dp(20),dp(20),0,0);
        outer.addView(tvBackOverlay,p);
        tvBackOverlay.bringToFront();
        // Brief seek feedback is independent of the Back button overlay.
        tvSeekOverlay=label("",21,Color.WHITE);
        tvSeekOverlay.setBackgroundColor(0xB8000000);
        tvSeekOverlay.setGravity(Gravity.CENTER);
        tvSeekOverlay.setPadding(dp(22),dp(8),dp(22),dp(8));
        FrameLayout.LayoutParams seekLayout=new FrameLayout.LayoutParams(
            -2,dp(56),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        seekLayout.bottomMargin=dp(36);
        outer.addView(tvSeekOverlay,seekLayout);
        tvSeekOverlay.setVisibility(View.GONE);
        // Keep the original official embed session and extend only the phone
        // control surface. TV v1021 stays unchanged and uses its own activity.
        phoneSeekButtons=new LinearLayout(this);
        phoneSeekButtons.setOrientation(LinearLayout.HORIZONTAL);
        phoneSeekButtons.setGravity(Gravity.CENTER);
        Button back10=button("⏪ 10 sec",()->{
            seekTvVideo(-10);
            showPhoneButtons();
        });
        Button forward10=button("10 sec ⏩",()->{
            seekTvVideo(10);
            showPhoneButtons();
        });
        phoneSeekButtons.addView(back10,new LinearLayout.LayoutParams(dp(128),dp(53)));
        phoneSeekButtons.addView(forward10,new LinearLayout.LayoutParams(dp(128),dp(53)));
        FrameLayout.LayoutParams phoneSeekLayout=new FrameLayout.LayoutParams(
            -1,dp(58),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        phoneSeekLayout.bottomMargin=dp(25);
        outer.addView(phoneSeekButtons,phoneSeekLayout);
        player.setOnTouchListener((v,event)->{
            if(event.getAction()==MotionEvent.ACTION_UP && tvFullscreen){
                showTvBackOverlay(false);
                showPhoneButtons();
            }
            return false; // All gestures still reach the embedded provider.
        });
        player.requestFocus();
        showTvBackOverlay(false);
        showPhoneButtons();
    }

    private void showPhoneButtons(){
        if(!tvFullscreen || phoneSeekButtons==null)return;
        tvOverlayHandler.removeCallbacks(hidePhoneButtons);
        phoneSeekButtons.setVisibility(View.VISIBLE);
        phoneSeekButtons.bringToFront();
        tvOverlayHandler.postDelayed(hidePhoneButtons,4500);
    }

    private void showTvBackOverlay(boolean focus) {
        if(!tvFullscreen || tvBackOverlay==null)return;
        tvBackOverlay.bringToFront();
        tvBackOverlay.setVisibility(View.VISIBLE);
        if(focus)tvBackOverlay.requestFocus();
        tvOverlayHandler.removeCallbacks(hideTvBack);
        tvOverlayHandler.postDelayed(hideTvBack,4500);
    }

    private void showTvSeekMessage(String message){
        if(!tvFullscreen || tvSeekOverlay==null)return;
        tvOverlayHandler.removeCallbacks(hideTvSeek);
        tvSeekOverlay.setText(message);
        tvSeekOverlay.setVisibility(View.VISIBLE);
        tvSeekOverlay.bringToFront();
        tvOverlayHandler.postDelayed(hideTvSeek,2000);
    }

    private String formatTvTime(double seconds) {
        int total=Math.max(0,(int)Math.round(seconds));
        int hours=total/3600;
        int minutes=(total/60)%60;
        int remainder=total%60;
        return hours>0
            ?String.format(java.util.Locale.ROOT,"%d:%02d:%02d",hours,minutes,remainder)
            :String.format(java.util.Locale.ROOT,"%d:%02d",minutes,remainder);
    }

    private double estimatedTvPosition() {
        if(!tvPlaybackRequested || tvEstimatedPositionAtMs<=0)return 0;
        long elapsed=SystemClock.elapsedRealtime()-tvEstimatedPositionAtMs;
        double estimate=tvEstimatedPositionSeconds+Math.max(0,elapsed)/1000.0;
        if(tvClipDurationSeconds>0)estimate=Math.min(estimate,tvClipDurationSeconds-0.5);
        return Math.max(0,estimate);
    }

    private void setEstimatedTvPosition(double value) {
        tvEstimatedPositionSeconds=Math.max(0,value);
        tvEstimatedPositionAtMs=SystemClock.elapsedRealtime();
    }

    /**
     * The player shows a real, seekable progress timeline on Google TV.
     * Some official embeds keep the HTML5 video inside a protected iframe:
     * sending J/L keyboard shortcuts to the outer WebView does not seek it.
     * First seek a same-origin HTMLVideoElement when possible; otherwise
     * activate the official player's own visible timeline with the same
     * native pointer events that already made TV playback work in v1014.
     * No third-party media URL extraction, cross-origin DOM access or reload.
     */
    private void seekTvVideo(int seconds){
        if(!tvFullscreen || !authorized() || player==null || embedLoadFailed)return;
        if(tvClipDurationSeconds<300 || !tvPlaybackRequested) {
            showTvSeekMessage("Video nog niet klaar om te spoelen");
            return;
        }
        final WebView active=player;
        final String js="(function(){try{" +
            "function find(doc,depth){if(!doc||depth>3)return null;" +
            "var v=doc.querySelectorAll('video');" +
            "for(var i=0;i<v.length;i++)if(v[i].readyState>0)return v[i];" +
            "var fs=doc.querySelectorAll('iframe');" +
            "for(var j=0;j<fs.length;j++){try{" +
            "var nested=find(fs[j].contentDocument,depth+1);if(nested)return nested;" +
            "}catch(ignore){}}" +
            "return null;}" +
            "var video=find(document,0);if(!video)return 'PROTECTED_FRAME';" +
            "var old=video.currentTime;if(!isFinite(old))return 'NOT_READY';" +
            "var duration=video.duration;" +
            "var next=Math.max(0,old+("+seconds+"));" +
            "if(isFinite(duration)&&duration>0)next=Math.min(next,Math.max(0,duration-0.5));" +
            "video.currentTime=next;" +
            "return Math.abs(video.currentTime-old)>0.1?'SEEKED:'+video.currentTime:'NO_CHANGE';" +
            "}catch(e){return 'PROTECTED_FRAME';}})();";
        active.evaluateJavascript(js,result->{
            if(!tvFullscreen || player!=active || isFinishing() || isDestroyed())return;
            if(result!=null && result.contains("SEEKED:")){
                try {
                    String value=result.replaceAll("[^0-9.]"," ").trim().split(" ")[0];
                    double actual=Double.parseDouble(value);
                    setEstimatedTvPosition(actual);
                    showTvSeekMessage((seconds<0?"⏪ ":"⏩ ")+formatTvTime(actual));
                    return;
                }catch(Exception ignored) {}
            }
            // The provider iframe did not expose its media clock. Use duration
            // metadata plus our local playback clock to target the visible
            // seek bar, which accepts native TV WebView pointer interaction.
            double predicted=estimatedTvPosition();
            double target=Math.max(0,Math.min(tvClipDurationSeconds-0.5,predicted+seconds));
            if(Math.abs(target-predicted)<0.3){
                showTvSeekMessage(seconds<0?"Begin van video":"Einde van video");
                return;
            }
            tapProviderTimeline(active,target,seconds);
        });
    }

    private void tapProviderTimeline(WebView active,double target,int direction){
        if(active.getWidth()<dp(200) || active.getHeight()<dp(120)){
            showTvSeekMessage("Tijdlijn nog niet beschikbaar");
            return;
        }
        // Pornhub's embed exposes a horizontal progress bar just above the
        // bottom control row. Its interior starts after the play/volume area.
        // The WebView stays in-place and the seek is performed on the actual
        // official provider control, not an app-owned dummy progress bar.
        final float width=active.getWidth(),height=active.getHeight();
        final float left=width*0.17f,right=width*0.96f;
        final float ratio=(float)Math.max(0,Math.min(1,target/tvClipDurationSeconds));
        final float x=left+ratio*(right-left);
        final float y=Math.max(dp(15),height-dp(38));
        final long at=SystemClock.uptimeMillis();
        MotionEvent hover=MotionEvent.obtain(at,at,MotionEvent.ACTION_HOVER_MOVE,x,y,0);
        hover.setSource(InputDevice.SOURCE_MOUSE);
        try{active.dispatchGenericMotionEvent(hover);}
        catch(RuntimeException ignored) {}
        finally{hover.recycle();}
        active.postDelayed(()->{
            if(!tvFullscreen || player!=active || isFinishing() || isDestroyed())return;
            long now=SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
            MotionEvent up=MotionEvent.obtain(now,now+65,MotionEvent.ACTION_UP,x,y,0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean received=false;
            try{
                received=active.dispatchTouchEvent(down);
                received=active.dispatchTouchEvent(up)||received;
            }catch(RuntimeException ex){
                Log.w("TheOneAdult","TV progress-bar pointer event failed");
            }finally{down.recycle();up.recycle();}
            if(received){
                setEstimatedTvPosition(target);
                showTvSeekMessage((direction<0?"⏪ ":"⏩ ")+
                    "Tijdlijn ≈ "+formatTvTime(target));
            }else{
                showTvSeekMessage("Spoelen niet ondersteund door deze speler");
            }
        },130);
    }

    private void leaveTvFullscreen() {
        tvOverlayHandler.removeCallbacks(hidePhoneButtons);
        if(phoneSeekButtons!=null){
            if(phoneSeekButtons.getParent() instanceof ViewGroup)
                ((ViewGroup)phoneSeekButtons.getParent()).removeView(phoneSeekButtons);
            phoneSeekButtons=null;
        }
        tvOverlayHandler.removeCallbacks(hideTvBack);
        tvOverlayHandler.removeCallbacks(hideTvSeek);
        if(tvSeekOverlay!=null){
            if(tvSeekOverlay.getParent() instanceof ViewGroup)
                ((ViewGroup)tvSeekOverlay.getParent()).removeView(tvSeekOverlay);
            tvSeekOverlay=null;
        }
        if(tvBackOverlay!=null){
            if(tvBackOverlay.getParent() instanceof ViewGroup)
                ((ViewGroup)tvBackOverlay.getParent()).removeView(tvBackOverlay);
            tvBackOverlay=null;
        }
        if(tvFullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(previousSystemUiFlags);
            tvFullscreen=false;
        }
        if(tvVideoChrome!=null)for(View chrome:tvVideoChrome) {
            if(chrome!=null)chrome.setVisibility(View.VISIBLE);
        }
        tvVideoChrome=null;
        tvPlaybackRequested=false;
        tvEstimatedPositionAtMs=0;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(tvFullscreen && event.getKeyCode()==KeyEvent.KEYCODE_BACK) {
            if(event.getAction()==KeyEvent.ACTION_UP && !event.isCanceled())showSearch();
            return true; // Remote Back must work even after the overlay auto-hides.
        }
        if(tvFullscreen && (event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT ||
           event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT ||
           event.getKeyCode()==KeyEvent.KEYCODE_MEDIA_REWIND ||
           event.getKeyCode()==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) {
                boolean backwards=event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT ||
                    event.getKeyCode()==KeyEvent.KEYCODE_MEDIA_REWIND;
                seekTvVideo(backwards?-10:10);
            }
            return true;
        }
        if(tvFullscreen && tvBackOverlay!=null &&
           event.getAction()==KeyEvent.ACTION_DOWN) {
            int key=event.getKeyCode();
            if(key==KeyEvent.KEYCODE_DPAD_UP &&
               tvBackOverlay.getVisibility()!=View.VISIBLE) {
                showTvBackOverlay(true);
                return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_UP ||
               key==KeyEvent.KEYCODE_DPAD_DOWN ||
               key==KeyEvent.KEYCODE_DPAD_LEFT ||
               key==KeyEvent.KEYCODE_DPAD_RIGHT)
                showTvBackOverlay(false);
        }
        return super.dispatchKeyEvent(event);
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
        leaveTvFullscreen();
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
        // TV remote Back exits immersive playback in one press, even if the
        // temporary on-screen Back control has already disappeared.
        if(tvFullscreen){showSearch();return;}
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
        tvOverlayHandler.removeCallbacks(hideTvBack);
        tvOverlayHandler.removeCallbacks(hideTvSeek);
        tvOverlayHandler.removeCallbacks(hidePhoneButtons);
        destroyPlayer();
        super.onDestroy();
    }
    private int dp(int n){
        return Math.round(n*getResources().getDisplayMetrics().density);
    }
}
