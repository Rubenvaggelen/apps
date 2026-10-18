package com.theone.tv;

import android.app.*;import android.content.*;import android.content.res.ColorStateList;import android.graphics.*;import android.graphics.drawable.*;import android.os.*;import android.text.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;import android.util.LruCache;
import androidx.media3.common.*;import androidx.media3.datasource.*;import androidx.media3.exoplayer.*;import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;import androidx.media3.ui.*;
import org.json.*;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;

public class MainActivity extends Activity {
 private FamilyLicenseGate familyLicense;
 private FamilyUpdateNotice appUpdater;
 private static final int BG=Color.rgb(7,13,24),PANEL=Color.rgb(17,29,45),BLUE=Color.rgb(41,157,255),MUTED=Color.rgb(149,172,196);
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor(),imageWorker=Executors.newFixedThreadPool(3);
 private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
 private MediaRepository repo;private SharedPreferences prefs;private Set<String> favorites;
 private final Map<String,List<CatalogEntry>> catalogs=new HashMap<>();private final Map<String,List<CatalogEntry>> variants=new ConcurrentHashMap<>();private Map<String,List<CatalogEntry>> countryCatalogs=new HashMap<>();
 private List<CatalogEntry> visible=new ArrayList<>(),filtered=new ArrayList<>(),episodeList;
 private String country="ALL";private String section="live",query="";private boolean loading=false,destroyed=false,testMode=false,adultConfirmed=false;private int limit=96,loadGeneration=0;
 private volatile String preferredLanguage="en";private volatile List<String> availableLanguages=Collections.singletonList("en");private int languageGeneration=0;private final ExecutorService languageWorker=Executors.newFixedThreadPool(1);
 private TextView summary;private ListView grid;private Button more;private EditText search;
 // Keep an in-memory return point so entering XXX never resets browsing progress.
 private static final class ListLocation {
   final String section,country,query;
   final int limit,firstRow,topOffset;
   ListLocation(String section,String country,String query,int limit,int firstRow,int topOffset){
     this.section=section;this.country=country;this.query=query;
     this.limit=limit;this.firstRow=firstRow;this.topOffset=topOffset;
   }
 }
 private ListLocation xxxReturnLocation,playerReturnLocation,pendingListRestore;
 private ListLocation rememberListPosition(){
   View first=grid==null?null:grid.getChildAt(0);
   return new ListLocation(section,country,
       search==null?query:search.getText().toString(),limit,
       grid==null?0:grid.getFirstVisiblePosition(),first==null?0:first.getTop());
 }
 private void returnFromXxx(){
   if(!section.equals("xxx"))return;
   ListLocation previous=xxxReturnLocation;
   xxxReturnLocation=null;
   if(previous==null){switchSection("live");return;}
   section=previous.section;
   country=previous.country;
   query=previous.query;
   limit=previous.limit;
   episodeList=null;
   pendingListRestore=previous;
   showCatalog();
 }

 private boolean renderedFirstFrame=false;private volatile int posterGeneration=0;private final android.util.LruCache<String,Long> failedPosters=new android.util.LruCache<>(512);
 private ExoPlayer player;private PlayerView playerView;private TextView playbackStatus;private CatalogEntry playing;
 private List<CatalogEntry> playSources;private int sourceIndex,retries;private long resumePosition;private boolean userPaused=false;
 private Runnable stallTask,progressTask;private long bufferStarted;private String lastPlaybackError="";
 @Override public void onCreate(Bundle b){super.onCreate(b);
 if (BuildConfig.DEBUG && getIntent() != null && getIntent().getBooleanExtra("tv_test_mode", false)) {
   startLicensedApp(b);
 } else {
   familyLicense=new FamilyLicenseGate(this,"tv_android",()->startLicensedApp(b));
 familyLicense.begin();
 }}
 private void startLicensedApp(Bundle b){
  appUpdater = new FamilyUpdateNotice(this);
  appUpdater.checkOnOpen();prefs=getSharedPreferences("the_one_live_tv",MODE_PRIVATE);favorites=new HashSet<>(prefs.getStringSet("favorites",Collections.emptySet()));preferredLanguage=prefs.getString("preferred_language","en");testMode=BuildConfig.DEBUG&&getIntent().getBooleanExtra("tv_test_mode",false);try{if(!testMode)repo=new MediaRepository(this);}catch(Exception e){showFatal();return;}if(testMode){List<CatalogEntry> samples=new ArrayList<>();for(int i=1;i<=(getIntent().getIntExtra("tv_test_count",12));i++){String country=i<=4?"NL":i<=8?"US":"GB";CatalogEntry entry=new CatalogEntry(i,country+" - Testkanaal "+i,"m3u8","",""+country,"live",0);samples.add(entry);variants.put(entry.key(),Arrays.asList(entry,new CatalogEntry(1000000000+i,country+" - Testkanaal "+i+" HD","ts","",country,"live",0)));}samples.sort(CatalogEntry.CHANNEL_ORDER);catalogs.put("live",samples);catalogs.put("xxx",Collections.emptyList());countryCatalogs=ChannelLayout.prepare(samples,variants).countries;}showCatalog();}
 private boolean compact(){return false;}
 private void showFatal(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(BG);box.setPadding(dp(40),dp(40),dp(40),dp(40));box.addView(label("THE ONE TV",30,BLUE));box.addView(label("De TV-bron ontbreekt. Installeer The One TV opnieuw.",20,Color.WHITE));setContentView(box);}
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView label(String value,int sp,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
 private GradientDrawable background(boolean focus){GradientDrawable d=new GradientDrawable();d.setColor(focus?Color.rgb(21,61,99):PANEL);d.setCornerRadius(dp(9));d.setStroke(dp(focus?3:1),focus?BLUE:Color.rgb(35,52,72));return d;}
 private Button button(String title,int id,Runnable action){Button b=new Button(this);b.setId(id);b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setFocusable(true);if(id<1040)b.setNextFocusDownId(id<1010?1010:1020);b.setPadding(dp(16),dp(9),dp(16),dp(9));b.setBackground(background(false));b.setOnFocusChangeListener((v,f)->v.setBackground(background(f)));b.setOnClickListener(v->action.run());return b;}
 private void addButton(LinearLayout row,Button b){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(49));lp.setMargins(0,0,dp(10),0);row.addView(b,lp);}
 private void showCatalog(){
  if(player!=null && playerReturnLocation!=null){
    pendingListRestore=playerReturnLocation;
    playerReturnLocation=null;
  }
  boolean returningToSavedPosition=pendingListRestore!=null;
  cancelPosterRequests();stopPlayback();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(40),dp(20),dp(40),dp(18));
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);ImageView logo=new ImageView(this);logo.setImageResource(com.theone.tv.R.drawable.tv_logo);header.addView(logo,new LinearLayout.LayoutParams(dp(compact()?72:88),dp(compact()?72:88)));TextView brand=label("  THE ONE TV",compact()?19:27,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));TextView version=label("TV · "+BuildConfig.VERSION_NAME,12,MUTED);header.addView(version);root.addView(header);
  LinearLayout nav=new LinearLayout(this);nav.setPadding(0,dp(15),0,dp(12));addButton(nav,button("Kanalen",1001,()->switchSection("live")));addButton(nav,button("XXX 🔒",1008,()->switchSection("xxx")));addButton(nav,button("Landen",1007,this::chooseCountry));addButton(nav,button("Mijn lijst",1003,()->switchSection("favorites")));addButton(nav,button("Recent gekeken",1004,()->switchSection("continue")));addButton(nav,button("Vernieuwen",1005,this::refresh));HorizontalScrollView navScroll=new HorizontalScrollView(this);navScroll.setHorizontalScrollBarEnabled(false);navScroll.addView(nav);root.addView(navScroll);
  LinearLayout searchRow=new LinearLayout(this);searchRow.setGravity(Gravity.CENTER_VERTICAL);search=new EditText(this);search.setId(1010);search.setNextFocusUpId(1001);search.setNextFocusDownId(1020);search.setNextFocusRightId(1011);search.setSingleLine(true);search.setTextSize(18);search.setTextColor(Color.WHITE);search.setHintTextColor(MUTED);search.setHint("Zoek een kanaal…");search.setText(query);search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setBackground(background(false));search.setPadding(dp(14),dp(9),dp(14),dp(9));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(0,dp(48),1);slp.rightMargin=dp(12);searchRow.addView(search,slp);addButton(searchRow,button("Zoek",1011,()->{query=search.getText().toString();limit=96;filter();hideKeyboard();grid.requestFocus();}));if(section.equals("xxx")){
   LinearLayout returnRow=new LinearLayout(this);
   returnRow.setPadding(0,0,0,dp(6));
   Button returnButton=button("← Terug naar vorige zenderlijst",1009,this::returnFromXxx);
   returnButton.setContentDescription("Terug naar de zenderlijst waar je gebleven was");
   returnRow.addView(returnButton,new LinearLayout.LayoutParams(-1,dp(48)));
   root.addView(returnRow);
  }
  root.addView(searchRow);
  summary=label("Catalogus laden…",14,MUTED);summary.setId(1012);root.addView(summary);
  grid=new ListView(this);grid.setId(1020);grid.setFocusable(true);grid.setFocusableInTouchMode(true);grid.setNextFocusUpId(1010);grid.setNextFocusDownId(1030);grid.setDivider(new ColorDrawable(Color.TRANSPARENT));grid.setDividerHeight(dp(6));grid.setClipToPadding(false);grid.setPadding(dp(3),dp(6),dp(3),dp(6));grid.setSelector(new ColorDrawable(Color.TRANSPARENT));grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);grid.setOnItemClickListener((parent,view,pos,id)->openDetails(visible.get(pos)));grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(v!=null)v.setBackground(background(true));for(int i=0;i<a.getChildCount();i++){View child=a.getChildAt(i);if(child!=v)child.setBackground(background(false));}}public void onNothingSelected(AdapterView<?> a){}});root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER_VERTICAL);TextView family=label("part of the one family",12,BLUE);bottom.addView(family,new LinearLayout.LayoutParams(0,-2,1));more=button("Meer kanalen",1030,()->{limit+=96;filter();grid.requestFocus();grid.setSelection(Math.max(0,limit-96));});bottom.addView(more,new LinearLayout.LayoutParams(-2,dp(43)));root.addView(bottom);setContentView(root);
  search.setOnEditorActionListener((t,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getAction()==KeyEvent.ACTION_UP&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){query=search.getText().toString();limit=96;filter();hideKeyboard();grid.requestFocus();return true;}return false;});
  search.addTextChangedListener(new TextWatcher(){Runnable pending;public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pending!=null)handler.removeCallbacks(pending);pending=()->{query=s.toString();limit=96;filter();};handler.postDelayed(pending,350);}public void afterTextChanged(Editable e){}});
  if(section.equals("episodes")){filter();}else ensureCatalog();if(!loading&&!returningToSavedPosition){grid.requestFocus();grid.setSelection(0);}}
 private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
 private void switchSection(String s){if(s.equals("series")||s.equals("episodes"))return;
  if(s.equals("xxx")&&!adultConfirmed){
   new AlertDialog.Builder(this).setTitle("XXX · 18+")
    .setMessage("Deze map bevat uitsluitend zenders voor volwassenen (18+).")
    .setPositiveButton("Ik ben 18+",(dialog,which)->{adultConfirmed=true;switchSection("xxx");})
    .setNegativeButton("Annuleren",null).show();
   return;
  }if(s.equals("xxx")&&!section.equals("xxx"))xxxReturnLocation=rememberListPosition();
  else if(section.equals("xxx")&&!s.equals("xxx"))xxxReturnLocation=null;
  section=s;query="";limit=96;episodeList=null;pendingListRestore=null;showCatalog();}
 private void refresh(){if(section.equals("episodes")){switchSection("series");return;}if(repo!=null)repo.clearPosterCache();failedPosters.evictAll();limit=96;if(!loading)loadCatalog("live",true);}
 private void ensureCatalog(){if(loading){if(catalogs.containsKey(section))filter();return;}if(section.equals("live")||section.equals("xxx")){if(catalogs.containsKey(section)){filter();return;}loadCatalog("live");}else if(!catalogs.containsKey("live")){loadCatalog("live");}else filter();}
 private void loadCatalog(String type){loadCatalog(type,false);}
 private void loadCatalog(String type,boolean forceCategories){loading=true;final int generation=++loadGeneration;if(catalogs.containsKey(type))filter();else summary.setText(type.equals("xxx")?"XXX-map laden…":"TV-kanalen laden…");catalogWorker.execute(()->{try{repo.load(type,(all,complete,successful)->publishCatalog(type,generation,all,complete),forceCategories);}catch(Exception e){handler.post(()->{if(destroyed||generation!=loadGeneration)return;loading=false;if(catalogs.containsKey(type)){filter();summary.append(" · Vernieuwen tijdelijk niet mogelijk.");}else{summary.setText("Catalogus niet bereikbaar. Kies Vernieuwen om opnieuw te proberen.");more.setVisibility(View.GONE);}});}});}
 private void publishCatalog(String type,int generation,List<CatalogEntry> all,boolean complete){LinkedHashMap<String,List<CatalogEntry>> grouped=CatalogEntry.group(all);List<String> languageOptions=LanguageSelection.languages(grouped.values());String language=preferredLanguage;List<CatalogEntry> reps=new ArrayList<>();Comparator<CatalogEntry> order=CatalogEntry.playbackOrder(language);for(List<CatalogEntry> xs:grouped.values()){xs.sort(order);reps.add(xs.get(0));}reps.sort(CatalogEntry.CHANNEL_ORDER);ChannelLayout.Layout mediaLayout=type.equals("live")?ChannelLayout.prepare(all,grouped):null;handler.post(()->{if(destroyed||generation!=loadGeneration)return;++languageGeneration;if(complete)migrateSavedKeys(grouped);if(type.equals("live")){
  List<CatalogEntry> ordinary=new ArrayList<>(),adult=new ArrayList<>();
  for(CatalogEntry e:reps)(e.isAdult()?adult:ordinary).add(e);
  catalogs.put("live",ordinary);
  catalogs.put("xxx",adult);
  countryCatalogs=mediaLayout.countries;
 }else{catalogs.put(type,reps);}
 variants.putAll(grouped);if(mediaLayout!=null)variants.putAll(mediaLayout.alternativeStreams);LinkedHashSet<String> knownLanguages=new LinkedHashSet<>(availableLanguages);knownLanguages.addAll(languageOptions);availableLanguages=Collections.unmodifiableList(new ArrayList<>(knownLanguages));loading=!complete;if(player==null){filter();if(grid.getCount()>0&&getCurrentFocus()==null){grid.requestFocus();grid.setSelection(0);}}if(!language.equals(preferredLanguage))selectLanguageRepresentatives();if(complete&&player==null)ensureCatalog();});}
 private void migrateSavedKeys(Map<String,List<CatalogEntry>> grouped){if(prefs.getString("saved_entries","{}").equals("{}"))return;Map<String,String> mapping=new HashMap<>();for(List<CatalogEntry> xs:grouped.values())for(CatalogEntry x:xs)mapping.put(x.legacyKey(),x.key());SharedPreferences.Editor edit=prefs.edit();boolean changed=false;for(CatalogEntry old:savedEntries()){String previous=old.legacyKey(),next=mapping.get(previous);if(next==null||previous.equals(next))continue;if(favorites.remove(previous)){favorites.add(next);changed=true;}for(String prefix:new String[]{"position:","watched:"})if(prefs.contains(prefix+previous)){if(!prefs.contains(prefix+next))edit.putLong(prefix+next,prefs.getLong(prefix+previous,0));edit.remove(prefix+previous);changed=true;}}if(changed)edit.putStringSet("favorites",new HashSet<>(favorites)).apply();}
 private void filter(){if(grid==null)return;cancelPosterRequests();List<CatalogEntry> candidates=new ArrayList<>();if(section.equals("episodes")&&episodeList!=null)candidates.addAll(episodeList);else if(section.equals("live"))candidates.addAll(countryCatalogs.getOrDefault(country,catalogs.getOrDefault("live",Collections.emptyList())));else if(section.equals("xxx"))candidates.addAll(catalogs.getOrDefault(section,Collections.emptyList()));else{Set<String> seen=new HashSet<>();for(List<CatalogEntry> c:catalogs.values())for(CatalogEntry e:c)if(!e.isAdult()&&(section.equals("favorites")?favorites.contains(e.key()):prefs.getLong("position:"+e.key(),0)>10000)){candidates.add(e);seen.add(e.key());}for(CatalogEntry e:savedEntries())if(!e.isAdult()&&!seen.contains(e.key())&&(section.equals("favorites")?favorites.contains(e.key()):prefs.getLong("position:"+e.key(),0)>10000)){candidates.add(e);seen.add(e.key());variants.putIfAbsent(e.key(),Collections.singletonList(e));}if(section.equals("continue"))candidates.sort((a,b)->Long.compare(prefs.getLong("watched:"+b.key(),0),prefs.getLong("watched:"+a.key(),0)));}
  String needle=query.trim().toLowerCase(Locale.ROOT);filtered=new ArrayList<>();for(CatalogEntry e:candidates)if((e.title+" "+e.displayTitle()+" "+CatalogEntry.countryName(e.category)).toLowerCase(Locale.ROOT).contains(needle))filtered.add(e);visible=new ArrayList<>(filtered);grid.setAdapter(new Posters());
  if(pendingListRestore!=null && pendingListRestore.section.equals(section)
      && !visible.isEmpty()){
     ListLocation restore=pendingListRestore;
     pendingListRestore=null;
     int row=Math.max(0,Math.min(restore.firstRow,visible.size()-1));
     grid.requestFocus();
     grid.setSelectionFromTop(row,restore.topOffset);
  }
  more.setVisibility(View.GONE);summary.setText(filtered.size()==0?"Geen zenders gevonden.":(section.equals("xxx")?String.format(Locale.getDefault(),"XXX · %,d volwassenenzenders",filtered.size()):String.format(Locale.getDefault(),"%,d kanalen · %s",filtered.size(),section.equals("live")?ChannelLayout.countryName(country):"Mijn lijst"))+(loading?" · Wordt bijgewerkt…":!testMode&&repo.partialSources()?" · Eén TV-bron is tijdelijk niet bereikbaar.":""));}
 private void toggleFavorite(CatalogEntry e){if(e.isAdult())return;rememberEntry(e);if(favorites.contains(e.key()))favorites.remove(e.key());else favorites.add(e.key());prefs.edit().putStringSet("favorites",new HashSet<>(favorites)).apply();Toast.makeText(this,favorites.contains(e.key())?"Toegevoegd aan Mijn lijst":"Verwijderd uit Mijn lijst",Toast.LENGTH_SHORT).show();filter();}
 private void openDetails(CatalogEntry e){new AlertDialog.Builder(this).setTitle(e.displayTitle())
 .setItems(e.isAdult()?new String[]{"▶ Kijk live"}:new String[]{"▶ Kijk live",favorites.contains(e.key())?"Verwijder uit Mijn lijst":"Voeg toe aan Mijn lijst"},(d,n)->{
   if(n==0)startPlayback(e,0);
   else if(!e.isAdult())toggleFavorite(e);
 }).setNegativeButton("Terug",null).show();}
 private void chooseCountry(){
   String[] labels=ChannelLayout.NAMES.clone();
   labels[0]="🇳🇱 Nederland • Ziggo-volgorde";
   int chosen=java.util.Arrays.asList(ChannelLayout.CODES).indexOf(country);
   new AlertDialog.Builder(this).setTitle("Landen • Media Player-indeling")
       .setSingleChoiceItems(labels,chosen<0?12:chosen,(d,n)->{
           country=ChannelLayout.CODES[n];query="";limit=96;d.dismiss();
           search.setText("");filter();grid.requestFocus();
       }).setNegativeButton("Terug",null).show();
  }
  private void startPlayback(CatalogEntry e,long saved){if(e.isAdult()&&!adultConfirmed){switchSection("xxx");return;}playerReturnLocation=rememberListPosition();cancelPosterRequests();playing=e;List<CatalogEntry> found=variants.get(e.key());playSources=new ArrayList<>(found==null?Collections.singletonList(e):found);playSources.sort(Comparator.comparingInt((CatalogEntry option)->option.id>=1000000000?0:1).thenComparing(CatalogEntry.playbackOrder(preferredLanguage)));sourceIndex=0;resumePosition=saved;retries=0;lastPlaybackError="";userPaused=false;showPlayer();}
 private void showPlayer(){FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);playerView=new PlayerView(this);playerView.setId(1040);playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);playerView.setControllerShowTimeoutMs(5000);playerView.setControllerAutoShow(true);playerView.setUseController(true);playerView.setShowSubtitleButton(true);playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);root.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(22),dp(12),dp(22),dp(12));top.setBackgroundColor(0x99070d18);playbackStatus=label(playing.title+" · Live kanaal starten…",16,Color.WHITE);top.addView(playbackStatus);LinearLayout controls=new LinearLayout(this);addButton(controls,button("Terug",1041,this::showCatalog));if(!playing.isAdult())addButton(controls,button("Mijn lijst",1042,()->toggleFavorite(playing)));addButton(controls,button("Taal",1044,this::chooseLanguage));addButton(controls,button("Geluid",1046,this::chooseSound));addButton(controls,button("Audio-info",1047,()->PlaybackAudioDiagnostics.show(this,player)));addButton(controls,button("Audio-uitvoer",1048,this::switchAudioOutput));addButton(controls,button("Ondertiteling",1045,this::chooseSubtitles));addButton(controls,button("Versie",1043,this::chooseVersion));HorizontalScrollView controlScroll=new HorizontalScrollView(this);controlScroll.setHorizontalScrollBarEnabled(false);controlScroll.addView(controls);top.addView(controlScroll);FrameLayout.LayoutParams tlp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);root.addView(top,tlp);setContentView(root);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);handler.postDelayed(()->{if(playerView!=null&&!top.hasFocus())top.setVisibility(View.GONE);},6000);playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->top.setVisibility(visibility==View.VISIBLE||top.hasFocus()?View.VISIBLE:View.GONE));createPlayer();playerView.requestFocus();}
 private void createPlayer(){DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(15000,60000,1500,3000).setTargetBufferBytes(32*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.21 LibVLC/3.0.21").setConnectTimeoutMs(8000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false);DefaultDataSource.Factory source=new DefaultDataSource.Factory(this,http);
  ExoPlayer.Builder playerBuilder=SonyBraviaAudioPolicy.usePcm(prefs.contains("compat_pcm_audio") ? prefs.getBoolean("compat_pcm_audio",false) : null, Build.MANUFACTURER, Build.BRAND, Build.MODEL)?new ExoPlayer.Builder(this,new CompatibleAudioRenderersFactory(this)):new ExoPlayer.Builder(this);player=playerBuilder.setLoadControl(load).setMediaSourceFactory(new DefaultMediaSourceFactory(new ResolvingDataSource.Factory(source,spec->spec.withRequestHeaders(testMode?Collections.emptyMap():repo.headersForUri(spec.uri.toString()))))).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(preferredLanguage.equals("multi")?"en":preferredLanguage).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).setPreferredTextLanguages("nl","en").setSelectUndeterminedTextLanguage(true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,prefs.getBoolean("subtitles_off",false)).build());player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);playerView.setPlayer(player);player.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(player==null)return;if(state==Player.STATE_BUFFERING){bufferStarted=System.currentTimeMillis();CatalogEntry option=playSources.get(sourceIndex);playbackStatus.setText("Even laden… · Bron "+(option.id>=1000000000?"2":"1")+" · "+(option.extension.equals("m3u8")?"HLS":"TS"));scheduleStall();}else{cancelStall();if(state==Player.STATE_READY){int height=player.getVideoSize().height;playbackStatus.setText(playing.title+(height>0?" · "+height+"p":""));}else if(state==Player.STATE_ENDED){prefs.edit().remove("position:"+playing.key()).apply();Toast.makeText(MainActivity.this,"Uitzending afgelopen",Toast.LENGTH_SHORT).show();}}}@Override public void onRenderedFirstFrame(){renderedFirstFrame=true;}@Override public void onVideoSizeChanged(VideoSize size){if(playbackStatus!=null&&playing!=null&&size.height>0)playbackStatus.setText(playing.title+" · "+size.height+"p");}@Override public void onPlayerError(PlaybackException error){lastPlaybackError=error.getErrorCodeName();Throwable reason=error.getCause();while(reason!=null){if(reason instanceof HttpDataSource.InvalidResponseCodeException){lastPlaybackError="HTTP "+((HttpDataSource.InvalidResponseCodeException)reason).responseCode;break;}reason=reason.getCause();}if(player!=null)fallback();}});playCurrent();progressTask=new Runnable(){public void run(){if(player!=null){storeProgress();handler.postDelayed(this,10000);}}};handler.postDelayed(progressTask,10000);}
 private void playCurrent(){if(player==null)return;renderedFirstFrame=false;CatalogEntry x=playSources.get(sourceIndex);MediaItem media=new MediaItem.Builder().setUri(testMode?"asset:///test-clip.mp4":repo.stream(x)).setSubtitleConfigurations(testMode&&getIntent().getBooleanExtra("subtitle_test_mode",false)?Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse("asset:///subtitle-test.srt")).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("nl").build()):Collections.emptyList()).setMediaId(x.key()).setMediaMetadata(new MediaMetadata.Builder().setTitle(playing.title).build()).build();player.setMediaItem(media);player.prepare();player.play();}
 private void fallback(){if(player==null)return;resumePosition=Math.max(resumePosition,player.getCurrentPosition());cancelStall();if(sourceIndex+1<playSources.size()){sourceIndex++;playbackStatus.setText("Andere beschikbare versie proberen…");playCurrent();}else if(retries++==0){playbackStatus.setText("Verbinding opnieuw opbouwen…");handler.postDelayed(()->{if(player!=null)playCurrent();},1500);}else{player.pause();playbackStatus.setText("Deze bron speelt nu niet goed. Kies een ander kanaal of probeer opnieuw.");new AlertDialog.Builder(this).setTitle("Afspelen onderbroken").setMessage("Geen werkende stream na "+playSources.size()+" beschikbare varianten. Laatste melding: "+(lastPlaybackError.isEmpty()?"geen beeld":lastPlaybackError)+". De zender kan bij de aanbieder tijdelijk niet beschikbaar zijn.").setPositiveButton("Opnieuw",(d,n)->{sourceIndex=0;retries=0;playCurrent();}).setNegativeButton("Terug",(d,n)->showCatalog()).show();}}
 private void scheduleStall(){cancelStall();stallTask=()->{if(player!=null&&player.getPlayWhenReady()&&player.getPlaybackState()==Player.STATE_BUFFERING){lastPlaybackError="Geen beeld na 12 seconden";fallback();}};handler.postDelayed(stallTask,12000);}
 private void cancelStall(){if(stallTask!=null){handler.removeCallbacks(stallTask);stallTask=null;}}
 private boolean hasAudioLanguage(String code){if(player==null)return false;for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)&&CatalogEntry.canonical(group.getTrackFormat(i).language).equals(code))return true;return false;}

 private void chooseSubtitles(){
  if(player==null)return;
  ArrayList<Tracks.Group> groups=new ArrayList<>();ArrayList<Integer> indexes=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
  labels.add("Uit");labels.add("Automatisch (Nederlands, daarna Engels)");
  for(Tracks.Group g:player.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){
   Format f=g.getTrackFormat(i);String lang=f.language;
   String name=f.label!=null?f.label:lang!=null?Locale.forLanguageTag(lang).getDisplayLanguage(new Locale("nl")):"Ondertitel "+(groups.size()+1);
   labels.add(name+(g.isTrackSelected(i)?" ✓":""));groups.add(g);indexes.add(i);
  }
  AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Ondertiteling");
  if(groups.isEmpty())dialog.setTitle(player.getPlaybackState()==Player.STATE_BUFFERING?"De stream wordt nog geladen. Probeer opnieuw zodra de video speelt.":"Deze stream bevat geen ondersteunde ondertitels. Probeer een andere versie.");
  dialog.setItems(labels.toArray(new String[0]),(d,which)->{
   TrackSelectionParameters.Builder b=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);
   if(which==0){prefs.edit().putBoolean("subtitles_off",true).apply();b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);}
   else{prefs.edit().putBoolean("subtitles_off",false).apply();b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguages("nl","en").setSelectUndeterminedTextLanguage(true);
    if(which>=2)b.setOverrideForType(new TrackSelectionOverride(groups.get(which-2).getMediaTrackGroup(),indexes.get(which-2)));
   }
   player.setTrackSelectionParameters(b.build());
  }).setNegativeButton("Sluiten",null).show();
 }

 private void switchAudioOutput(){
  if(player==null||playerView==null)return;
  boolean usePcm=!SonyBraviaAudioPolicy.usePcm(prefs.contains("compat_pcm_audio") ? prefs.getBoolean("compat_pcm_audio",false) : null, Build.MANUFACTURER, Build.BRAND, Build.MODEL);
  prefs.edit().putBoolean("compat_pcm_audio",usePcm).apply();
  long saved=Math.max(0L,player.getCurrentPosition());
  cancelStall();
  if(progressTask!=null)handler.removeCallbacks(progressTask);
  playerView.setPlayer(null);
  player.release();
  player=null;
  resumePosition=saved;
  createPlayer();
  Toast.makeText(this,usePcm?"Uitvoer: PCM-test (geen Dolby-passthrough)":"Uitvoer: standaard zoals vóór licenties",Toast.LENGTH_LONG).show();
 }
 private void chooseSound(){
  if(player==null)return;
  ArrayList<Tracks.Group> groups=new ArrayList<>();
  ArrayList<Integer> positions=new ArrayList<>();
  ArrayList<String> choices=new ArrayList<>();
  choices.add("Automatisch (compatibel geluid)");
  for(Tracks.Group group:player.getCurrentTracks().getGroups()){
   if(group.getType()!=C.TRACK_TYPE_AUDIO)continue;
   for(int n=0;n<group.length;n++){
    if(!group.isTrackSupported(n))continue;
    Format format=group.getTrackFormat(n);
    String language=format.language==null?"Onbekend":Locale.forLanguageTag(format.language).getDisplayLanguage(new Locale("nl"));
    String type=format.sampleMimeType==null?"Onbekend formaat":format.sampleMimeType;
    choices.add(language+" · "+type+(format.channelCount>0?" · "+format.channelCount+" kanalen":"")+(group.isTrackSelected(n)?" ✓":""));
    groups.add(group);
    positions.add(n);
   }
  }
  if(groups.isEmpty()){
   new AlertDialog.Builder(this).setTitle("Geen ondersteund geluidsspoor")
    .setMessage("Deze bron biedt geen geluid aan dat dit apparaat kan decoderen. Kies een andere versie of kanaal.").setPositiveButton("OK",null).show();
   return;
  }
  new AlertDialog.Builder(this).setTitle("Geluidsspoor")
   .setItems(choices.toArray(new String[0]),(dialog,which)->{
      TrackSelectionParameters.Builder parameters=player.getTrackSelectionParameters().buildUpon()
       .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false);
      if(which>0)parameters.setOverrideForType(new TrackSelectionOverride(groups.get(which-1).getMediaTrackGroup(),positions.get(which-1)));
      player.setTrackSelectionParameters(parameters.build());
      player.setVolume(1f);
      Toast.makeText(this,"Geluidsspoor geselecteerd",Toast.LENGTH_SHORT).show();
   }).setNegativeButton("Terug",null).show();
 }
 private void chooseLanguage(){LinkedHashSet<String> codes=new LinkedHashSet<>();codes.add("en");if(player==null)codes.addAll(testMode?LanguageSelection.languages(variants.values()):availableLanguages);else for(CatalogEntry entry:playSources){String code=entry.languageCode();if(!code.isEmpty()&&!code.equals("und"))codes.add(code);}if(player!=null)for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<group.length;i++){String code=CatalogEntry.canonical(group.getTrackFormat(i).language);if(group.isTrackSupported(i)&&!code.isEmpty()&&!code.equals("und"))codes.add(code);}List<String> available=new ArrayList<>(codes);String[] labels=new String[available.size()];for(int i=0;i<labels.length;i++)labels[i]=CatalogEntry.languageName(available.get(i))+(available.get(i).equals("en")?" · standaard":"");new AlertDialog.Builder(this).setTitle("Taalvoorkeur").setSingleChoiceItems(labels,available.indexOf(preferredLanguage),(d,n)->{preferredLanguage=available.get(n);prefs.edit().putString("preferred_language",preferredLanguage).apply();d.dismiss();if(player!=null){boolean audio=hasAudioLanguage(preferredLanguage);player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(preferredLanguage.equals("multi")?"en":preferredLanguage).build());if(!audio){resumePosition=player.getCurrentPosition();playSources.sort(CatalogEntry.playbackOrder(preferredLanguage));sourceIndex=0;retries=0;playCurrent();}}else{selectLanguageRepresentatives();}Toast.makeText(this,"Taalvoorkeur: "+CatalogEntry.languageName(preferredLanguage),Toast.LENGTH_SHORT).show();}).setNegativeButton("Terug",null).show();}
 private void selectLanguageRepresentatives(){final int generation=++languageGeneration;if(languageWorker instanceof ThreadPoolExecutor)((ThreadPoolExecutor)languageWorker).getQueue().clear();final String language=preferredLanguage;final Map<String,List<CatalogEntry>> snapshot=new HashMap<>(catalogs);languageWorker.execute(()->{Map<String,List<CatalogEntry>> selected=LanguageSelection.choose(snapshot,variants,language);handler.post(()->{if(destroyed||generation!=languageGeneration||!language.equals(preferredLanguage))return;catalogs.putAll(selected);if(player==null)filter();});});}
 private void chooseVersion(){if(player==null)return;String[] names=new String[playSources.size()];for(int i=0;i<names.length;i++)names[i]=(playSources.get(i).id>=1000000000?"Bron 2":"Bron 1")+" · "+playSources.get(i).rank+"p · "+playSources.get(i).extension;new AlertDialog.Builder(this).setTitle("Beschikbare versies").setSingleChoiceItems(names,sourceIndex,(d,n)->{resumePosition=player.getCurrentPosition();sourceIndex=n;retries=0;playCurrent();d.dismiss();}).setNegativeButton("Terug",null).show();}
 private void rememberEntry(CatalogEntry e){try{JSONObject saved=new JSONObject(prefs.getString("saved_entries","{}"));JSONObject row=new JSONObject();row.put("id",e.id).put("title",e.title).put("extension",e.extension).put("poster",e.poster).put("type",e.type).put("movieId",e.movieId);saved.put(e.key(),row);prefs.edit().putString("saved_entries",saved.toString()).apply();}catch(Exception ignored){}}
 private List<CatalogEntry> savedEntries(){List<CatalogEntry> out=new ArrayList<>();try{JSONObject saved=new JSONObject(prefs.getString("saved_entries","{}"));Iterator<String> keys=saved.keys();while(keys.hasNext()){JSONObject e=saved.getJSONObject(keys.next());out.add(new CatalogEntry(e.getInt("id"),e.getString("title"),e.getString("extension"),e.optString("poster"),"",e.getString("type"),0,e.optString("movieId")));}}catch(Exception ignored){}return out;}
 private void storeProgress(){if(player==null||playing==null)return;rememberEntry(playing);prefs.edit().putLong("position:"+playing.key(),15001).putLong("watched:"+playing.key(),System.currentTimeMillis()).apply();}
 private void zap(int direction){if(playing==null||filtered.isEmpty())return;int index=0;for(int i=0;i<filtered.size();i++)if(filtered.get(i).key().equals(playing.key())){index=i;break;}CatalogEntry next=filtered.get((index+direction+filtered.size())%filtered.size());stopPlayback();startPlayback(next,0);}
 private void stopPlayback(){if(player==null)return;storeProgress();cancelStall();if(progressTask!=null)handler.removeCallbacks(progressTask);playerView.setPlayer(null);player.release();player=null;playerView=null;playing=null;}
 private volatile boolean languageWorkerBlockedForTest;
 CountDownLatch pauseLanguageWorkerForTest(){if(!testMode)throw new IllegalStateException();CountDownLatch release=new CountDownLatch(1);languageWorker.execute(()->{languageWorkerBlockedForTest=true;try{release.await(60,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{languageWorkerBlockedForTest=false;}});return release;}
 boolean isLanguageWorkerBlockedForTest(){return testMode&&languageWorkerBlockedForTest;}
 boolean hasPosterForTest(String url){return testMode&&images.get(url)!=null;}
 boolean isPlaybackReadyForTest(){return testMode&&player!=null&&renderedFirstFrame&&player.isPlaying();}
 private String formatTime(long ms){long s=ms/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
 @Override public boolean dispatchKeyEvent(KeyEvent e){if(player==null&&e.getAction()==KeyEvent.ACTION_DOWN){View focused=getCurrentFocus();int key=e.getKeyCode();if(focused==grid&&key==KeyEvent.KEYCODE_DPAD_UP&&grid.getSelectedItemPosition()<1){findViewById(1001).requestFocus();return true;}if(focused==search&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)){if(key==KeyEvent.KEYCODE_DPAD_UP)findViewById(1001).requestFocus();else grid.requestFocus();return true;}if(focused!=null){int id=focused.getId();int[] order={1001,1008,1007,1003,1004,1005};for(int i=0;i<order.length;i++)if(id==order[i]){if(key==KeyEvent.KEYCODE_DPAD_UP)return true;if(key==KeyEvent.KEYCODE_DPAD_DOWN){search.requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){int step=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;for(int next=i+step;next>=0&&next<order.length;next+=step){View candidate=findViewById(order[next]);if(candidate!=null&&candidate.getVisibility()==View.VISIBLE){candidate.requestFocus();break;}}return true;}}}}if(player!=null&&e.getAction()==KeyEvent.ACTION_DOWN){int k=e.getKeyCode();if(k==KeyEvent.KEYCODE_CHANNEL_UP||k==KeyEvent.KEYCODE_CHANNEL_DOWN){zap(k==KeyEvent.KEYCODE_CHANNEL_UP?1:-1);return true;}if(k==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(player.isPlaying())player.pause();else player.play();return true;}if(k==KeyEvent.KEYCODE_MEDIA_PLAY){player.play();return true;}if(k==KeyEvent.KEYCODE_MEDIA_PAUSE){player.pause();return true;}if(k==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){player.seekForward();return true;}if(k==KeyEvent.KEYCODE_MEDIA_REWIND){player.seekBack();return true;}}return playerView!=null&&playerView.hasFocus()&&playerView.dispatchKeyEvent(e)||super.dispatchKeyEvent(e);}
 @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(playerView!=null)playerView.requestLayout();else{pendingListRestore=rememberListPosition();showCatalog();}}
 @Override public void onBackPressed(){if(player!=null){showCatalog();return;}if(section.equals("xxx")){returnFromXxx();return;}if(section.equals("episodes")){switchSection("series");return;}if(!query.isEmpty()){query="";search.setText("");filter();return;}super.onBackPressed();}
 @Override protected void onStop(){if(player!=null){storeProgress();player.pause();cancelStall();}super.onStop();}
 @Override protected void onResume(){super.onResume();if(appUpdater!=null)appUpdater.checkOnOpen();}
 @Override protected void onDestroy(){if(familyLicense!=null)familyLicense.close();
  if (appUpdater != null) appUpdater.close();destroyed=true;++loadGeneration;stopPlayback();handler.removeCallbacksAndMessages(null);languageWorker.shutdownNow();catalogWorker.shutdownNow();imageWorker.shutdownNow();super.onDestroy();}
 private void cancelPosterRequests(){posterGeneration++;if(imageWorker instanceof ThreadPoolExecutor)((ThreadPoolExecutor)imageWorker).getQueue().clear();}
 private void loadPoster(ImageView image,CatalogEntry entry){
  final int generation=posterGeneration;String tag=entry.type+":"+entry.id;image.setTag(tag);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
  LinkedHashSet<String> urls=new LinkedHashSet<>();String first=PosterMetadata.resolve(entry.poster,"");if(!first.isEmpty())urls.add(first);
  List<CatalogEntry> found=variants.get(entry.key());final List<CatalogEntry> versions=new ArrayList<>(found==null?Collections.singletonList(entry):found);
  for(CatalogEntry x:versions){String url=PosterMetadata.resolve(x.poster,"");if(!url.isEmpty())urls.add(url);}
  for(String url:urls){Bitmap cached=images.get(url);if(cached!=null){image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(cached);return;}}
  if(testMode&&urls.isEmpty())return;
  try{imageWorker.execute(()->{try{
   if(generation!=posterGeneration)return;Bitmap bitmap=null;
   for(String url:urls){if(generation!=posterGeneration)return;bitmap=posterBitmap(url);if(bitmap!=null)break;}
   if(bitmap==null&&!testMode)for(CatalogEntry x:versions){if(generation!=posterGeneration)return;try{for(String url:repo.posters(x)){if(generation!=posterGeneration)return;if(!urls.add(url))continue;bitmap=posterBitmap(url);if(bitmap!=null)break;}}catch(Exception ignored){}if(bitmap!=null)break;}
   final Bitmap loaded=bitmap;if(loaded!=null)handler.post(()->{if(!destroyed&&generation==posterGeneration&&tag.equals(image.getTag())){image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(loaded);}});
  }catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
 }
 private Bitmap posterBitmap(String url){
  Bitmap cached=images.get(url);if(cached!=null)return cached;Long failed=failedPosters.get(url);if(failed!=null&&System.currentTimeMillis()-failed<60000)return null;
  HttpURLConnection connection=null;try{connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(7000);connection.setRequestProperty("User-Agent","Mozilla/5.0 (Android) TheOneTV/1.0.0");connection.setRequestProperty("Accept","image/*");
   Bitmap bitmap;try(InputStream input=connection.getInputStream()){BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=2;bitmap=BitmapFactory.decodeStream(input,null,opts);}
   if(bitmap!=null){images.put(url,bitmap);failedPosters.remove(url);}else failedPosters.put(url,System.currentTimeMillis());return bitmap;
  }catch(Exception ignored){failedPosters.put(url,System.currentTimeMillis());return null;}finally{if(connection!=null)connection.disconnect();}
 }
 private final class Posters extends BaseAdapter {
 public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return visible.get(p).id;}
 public View getView(int position,View recycled,ViewGroup parent){
  CatalogEntry e=visible.get(position);LinearLayout box=new LinearLayout(MainActivity.this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(12),dp(7),dp(12),dp(7));box.setBackground(background(false));box.setContentDescription(e.displayTitle());
  String shownCountry=section.equals("live")?ChannelLayout.countryName(ChannelLayout.channelCountry(e)):CatalogEntry.countryName(e.category);
   String previousCountry=position==0?"":(section.equals("live")?ChannelLayout.countryName(ChannelLayout.channelCountry(visible.get(position-1))):CatalogEntry.countryName(visible.get(position-1).category));
   if(position==0||!shownCountry.equals(previousCountry)){TextView heading=label(shownCountry.toUpperCase(new Locale("nl")),12,BLUE);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(6),0,dp(8));box.addView(heading);}
  LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);
  ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageResource(R.drawable.tv_logo);LinearLayout.LayoutParams icon=new LinearLayout.LayoutParams(dp(compact()?42:54),dp(compact()?42:54));icon.rightMargin=dp(14);row.addView(image,icon);
  LinearLayout text=new LinearLayout(MainActivity.this);text.setOrientation(LinearLayout.VERTICAL);TextView name=label((favorites.contains(e.key())?"★ ":"")+(section.equals("live")?e.title.trim():e.displayTitle()),compact()?16:19,Color.WHITE);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(3);text.addView(name);
  List<CatalogEntry> sources=variants.get(e.key());String detail=CatalogEntry.countryName(e.category)+(e.isEvent()?" · Evenement":"");TextView meta=label(detail,compact()?12:14,MUTED);text.addView(meta);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
  TextView action=label("›",24,BLUE);row.addView(action,new LinearLayout.LayoutParams(dp(22),-2));box.addView(row,new LinearLayout.LayoutParams(-1,-2));loadPoster(image,e);return box;
 }
 }
}
