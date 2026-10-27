package com.theone.series.tv;

import android.app.*;import android.content.*;import android.content.res.ColorStateList;import android.graphics.*;import android.graphics.drawable.*;import android.os.*;import android.text.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;import android.util.LruCache;
import androidx.media3.common.*;import androidx.media3.datasource.*;import androidx.media3.exoplayer.*;import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;import androidx.media3.ui.*;
import org.json.*;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;

public class MainActivity extends Activity {
 private FamilyLicenseGate familyLicense;
 private FamilyUpdateNotice appUpdater;
 private static final int BG=Color.rgb(7,13,24),PANEL=Color.rgb(17,29,45),BLUE=Color.rgb(41,157,255),MUTED=Color.rgb(149,172,196);
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final ExecutorService episodeWorker=Executors.newSingleThreadExecutor(),catalogWorker=Executors.newSingleThreadExecutor(),imageWorker=Executors.newFixedThreadPool(3);
 private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
 private MediaRepository repo;private SharedPreferences prefs;private Set<String> favorites;
 private final Map<String,List<CatalogEntry>> catalogs=new HashMap<>();private final Map<String,List<CatalogEntry>> variants=new ConcurrentHashMap<>();
 private List<CatalogEntry> visible=new ArrayList<>(),filtered=new ArrayList<>(),episodeList;
 private String section="series",query="";private boolean loading=false,destroyed=false,testMode=false;private int limit=96,loadGeneration=0;
 private volatile String preferredLanguage="en";private volatile List<String> availableLanguages=Collections.singletonList("en");private int languageGeneration=0;private final ExecutorService languageWorker=Executors.newFixedThreadPool(1);
 private TextView summary;private GridView grid;private Button more;private EditText search;
 private CatalogEntry selectedSeries;private EpisodeCatalog.Result selectedEpisodes;private int selectedSeason;
 private boolean renderedFirstFrame=false;private volatile int posterGeneration=0;private final android.util.LruCache<String,Long> failedPosters=new android.util.LruCache<>(512);
 private ExoPlayer player;private PlayerView playerView;private TextView playbackStatus;private CatalogEntry playing;
 private List<CatalogEntry> playSources;private int sourceIndex,retries;private long resumePosition;private boolean userPaused=false;
 private Runnable stallTask,progressTask,audioTrackCheck;private int audioCheckGeneration;private long bufferStarted;
 @Override public void onCreate(Bundle b){super.onCreate(b);
 if (BuildConfig.DEBUG && getIntent() != null && (getIntent().getBooleanExtra("tv_test_mode", false) || getIntent().getBooleanExtra("series_test_mode", false) || getIntent().getBooleanExtra("starter_test_mode", false) || getIntent().getBooleanExtra("subtitle_test_mode", false))) {
   startLicensedApp(b);
 } else {
   familyLicense=new FamilyLicenseGate(this,"series_android",()->startLicensedApp(b));
 familyLicense.begin();
 }}
 private void startLicensedApp(Bundle b){
  appUpdater = new FamilyUpdateNotice(this);
  appUpdater.checkOnOpen();prefs=getSharedPreferences("the_one_series_tv",MODE_PRIVATE);favorites=new HashSet<>(prefs.getStringSet("favorites",Collections.emptySet()));preferredLanguage=prefs.getString("preferred_language","en");testMode=BuildConfig.DEBUG&&getIntent().getBooleanExtra("series_test_mode",false);try{if(!testMode){repo=new MediaRepository(this);if(BuildConfig.DEBUG&&getIntent().getBooleanExtra("starter_test_mode",false))repo.enableOfflineForTest();}}catch(Exception e){showFatal();return;}if(testMode){List<CatalogEntry> samples=new ArrayList<>();for(int i=1;i<=12;i++){CatalogEntry en=new CatalogEntry(i,"EN - Series testserie "+i,"mp4","","","series",i);samples.add(en);variants.put(en.key(),Arrays.asList(en,new CatalogEntry(100+i,"NL - Series testserie "+i,"mp4","","","series",i)));}catalogs.put("series",samples);}showCatalog();}
 private boolean compact(){return false;}
 private void showFatal(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(BG);box.setPadding(dp(40),dp(40),dp(40),dp(40));box.addView(label("THE ONE SERIES",30,BLUE));box.addView(label("De mediabron ontbreekt. Installeer de persoonlijke Series-versie opnieuw.",20,Color.WHITE));setContentView(box);}
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView label(String value,int sp,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
 private GradientDrawable background(boolean focus){GradientDrawable d=new GradientDrawable();d.setColor(focus?Color.rgb(21,61,99):PANEL);d.setCornerRadius(dp(9));d.setStroke(dp(focus?3:1),focus?BLUE:Color.rgb(35,52,72));return d;}
 private Button button(String title,int id,Runnable action){Button b=new Button(this);b.setId(id);b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setFocusable(true);if(id<1040)b.setNextFocusDownId(id<1010?1010:1020);b.setPadding(dp(16),dp(9),dp(16),dp(9));b.setBackground(background(false));b.setOnFocusChangeListener((v,f)->v.setBackground(background(f)));b.setOnClickListener(v->action.run());return b;}
 private void addButton(LinearLayout row,Button b){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(49));lp.setMargins(0,0,dp(10),0);row.addView(b,lp);}
 private void showCatalog(){cancelPosterRequests();stopPlayback();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(40),dp(20),dp(40),dp(18));
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);ImageView logo=new ImageView(this);logo.setImageResource(com.theone.series.tv.R.drawable.series_logo);header.addView(logo,new LinearLayout.LayoutParams(dp(compact()?72:88),dp(compact()?72:88)));TextView brand=label("  THE ONE SERIES",compact()?19:27,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));TextView version=label("TV · 1.1.3",12,MUTED);header.addView(version);root.addView(header);
  LinearLayout nav=new LinearLayout(this);nav.setPadding(0,dp(15),0,dp(12));addButton(nav,button("Series",1001,()->switchSection("series")));addButton(nav,button("Taal",1007,this::chooseLanguage));if(section.equals("episodes")&&selectedEpisodes!=null)addButton(nav,button("Seizoenen",1006,this::showSeasons));addButton(nav,button("Mijn lijst",1003,()->switchSection("favorites")));addButton(nav,button("Verder kijken",1004,()->switchSection("continue")));addButton(nav,button("Vernieuwen",1005,this::refresh));HorizontalScrollView navScroll=new HorizontalScrollView(this);navScroll.setHorizontalScrollBarEnabled(false);navScroll.addView(nav);root.addView(navScroll);
  LinearLayout searchRow=new LinearLayout(this);searchRow.setGravity(Gravity.CENTER_VERTICAL);search=new EditText(this);search.setId(1010);search.setNextFocusUpId(1001);search.setNextFocusDownId(1020);search.setNextFocusRightId(1011);search.setSingleLine(true);search.setTextSize(18);search.setTextColor(Color.WHITE);search.setHintTextColor(MUTED);search.setHint(section.equals("episodes")?"Zoek een aflevering…":"Zoek een serie…");search.setText(query);search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setBackground(background(false));search.setPadding(dp(14),dp(9),dp(14),dp(9));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(0,dp(48),1);slp.rightMargin=dp(12);searchRow.addView(search,slp);addButton(searchRow,button("Zoek",1011,()->{query=search.getText().toString();limit=96;filter();hideKeyboard();grid.requestFocus();}));root.addView(searchRow);
  summary=label("Catalogus laden…",14,MUTED);summary.setId(1012);root.addView(summary);
  grid=new GridView(this);grid.setId(1020);grid.setFocusable(true);grid.setFocusableInTouchMode(true);grid.setNextFocusUpId(1010);grid.setNextFocusDownId(1030);grid.setNumColumns(Math.max(3,Math.min(6,(getResources().getConfiguration().screenWidthDp-80)/170)));grid.setHorizontalSpacing(dp(14));grid.setVerticalSpacing(dp(14));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setClipToPadding(false);grid.setPadding(dp(3),dp(6),dp(3),dp(6));grid.setSelector(new ColorDrawable(Color.TRANSPARENT));grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);grid.setOnItemClickListener((parent,view,pos,id)->openDetails(visible.get(pos)));grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(v!=null)v.setBackground(background(true));for(int i=0;i<a.getChildCount();i++){View child=a.getChildAt(i);if(child!=v)child.setBackground(background(false));}}public void onNothingSelected(AdapterView<?> a){}});root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER_VERTICAL);TextView family=label("part of the one family",12,BLUE);bottom.addView(family,new LinearLayout.LayoutParams(0,-2,1));more=button("Meer titels",1030,()->{limit+=96;filter();grid.requestFocus();grid.setSelection(Math.max(0,limit-96));});bottom.addView(more,new LinearLayout.LayoutParams(-2,dp(43)));root.addView(bottom);setContentView(root);
  search.setOnEditorActionListener((t,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getAction()==KeyEvent.ACTION_UP&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){query=search.getText().toString();limit=96;filter();hideKeyboard();grid.requestFocus();return true;}return false;});
  search.addTextChangedListener(new TextWatcher(){Runnable pending;public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pending!=null)handler.removeCallbacks(pending);pending=()->{query=s.toString();limit=96;filter();};handler.postDelayed(pending,350);}public void afterTextChanged(Editable e){}});
  if(section.equals("episodes")){filter();}else ensureCatalog();if(!loading){grid.requestFocus();grid.setSelection(0);}}
 private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
 private void switchSection(String s){++loadGeneration;loading=false;section=s;query="";limit=96;episodeList=null;showCatalog();}
 private void refresh(){if(section.equals("episodes")){switchSection("series");return;}if(repo!=null)repo.clearPosterCache();failedPosters.evictAll();limit=96;if(!loading)loadCatalog("series");}
 private void ensureCatalog(){if(loading){if(catalogs.containsKey(section))filter();return;}if(section.equals("series")){if(catalogs.containsKey(section)){filter();return;}loadCatalog(section);}else if(!catalogs.containsKey("series")){loadCatalog("series");}else filter();}
 private void loadCatalog(String type){loading=true;final int generation=++loadGeneration;if(catalogs.containsKey(type))filter();else summary.setText("Series laden…");catalogWorker.execute(()->{try{repo.load(type,(all,complete,successful)->publishCatalog(type,generation,all,complete));}catch(Exception e){handler.post(()->{if(destroyed||generation!=loadGeneration)return;loading=false;if(catalogs.containsKey(type)){filter();summary.append(" · Vernieuwen tijdelijk niet mogelijk.");}else{summary.setText("Catalogus niet bereikbaar. Kies Vernieuwen om opnieuw te proberen.");more.setVisibility(View.GONE);}});}});}
 private void publishCatalog(String type,int generation,List<CatalogEntry> all,boolean complete){LinkedHashMap<String,List<CatalogEntry>> grouped=CatalogEntry.group(all);List<String> languageOptions=LanguageSelection.languages(grouped.values());String language=preferredLanguage;List<CatalogEntry> reps=new ArrayList<>();Comparator<CatalogEntry> order=CatalogEntry.playbackOrder(language);for(List<CatalogEntry> xs:grouped.values()){xs.sort(order);reps.add(xs.get(0));}reps.sort((a,b)->Long.compare(b.added,a.added));handler.post(()->{if(destroyed||generation!=loadGeneration)return;++languageGeneration;migrateSavedKeys(grouped);catalogs.put(type,reps);variants.putAll(grouped);LinkedHashSet<String> knownLanguages=new LinkedHashSet<>(availableLanguages);knownLanguages.addAll(languageOptions);availableLanguages=Collections.unmodifiableList(new ArrayList<>(knownLanguages));loading=!complete;if(player==null){filter();if(grid.getCount()>0&&getCurrentFocus()==null){grid.requestFocus();grid.setSelection(0);}}if(!language.equals(preferredLanguage))selectLanguageRepresentatives();if(complete&&player==null)ensureCatalog();});}
 private void migrateSavedKeys(Map<String,List<CatalogEntry>> grouped){if(prefs.getString("saved_entries","{}").equals("{}"))return;Map<String,String> mapping=new HashMap<>();for(List<CatalogEntry> xs:grouped.values())for(CatalogEntry x:xs)mapping.put(x.legacyKey(),x.key());SharedPreferences.Editor edit=prefs.edit();boolean changed=false;for(CatalogEntry old:savedEntries()){String previous=old.legacyKey(),next=mapping.get(previous);if(next==null||previous.equals(next))continue;if(favorites.remove(previous)){favorites.add(next);changed=true;}for(String prefix:new String[]{"position:","watched:"})if(prefs.contains(prefix+previous)){if(!prefs.contains(prefix+next))edit.putLong(prefix+next,prefs.getLong(prefix+previous,0));edit.remove(prefix+previous);changed=true;}}if(changed)edit.putStringSet("favorites",new HashSet<>(favorites)).apply();}
 private void filter(){if(grid==null)return;cancelPosterRequests();List<CatalogEntry> candidates=new ArrayList<>();if(section.equals("episodes")&&episodeList!=null)candidates.addAll(episodeList);else if(section.equals("series"))candidates.addAll(catalogs.getOrDefault(section,Collections.emptyList()));else{Set<String> seen=new HashSet<>();for(List<CatalogEntry> c:catalogs.values())for(CatalogEntry e:c)if(!e.isAdult()&&(section.equals("favorites")?favorites.contains(e.key()):prefs.getLong("position:"+e.key(),0)>10000)){candidates.add(e);seen.add(e.key());}for(CatalogEntry e:savedEntries())if(!e.isAdult()&&!seen.contains(e.key())&&(section.equals("favorites")?favorites.contains(e.key()):prefs.getLong("position:"+e.key(),0)>10000)){candidates.add(e);seen.add(e.key());variants.putIfAbsent(e.key(),Collections.singletonList(e));}if(section.equals("continue"))candidates.sort((a,b)->Long.compare(prefs.getLong("watched:"+b.key(),0),prefs.getLong("watched:"+a.key(),0)));}
  String needle=query.trim().toLowerCase(Locale.ROOT);filtered=new ArrayList<>();for(CatalogEntry e:candidates)if(e.title.toLowerCase(Locale.ROOT).contains(needle))filtered.add(e);visible=new ArrayList<>(filtered.subList(0,Math.min(limit,filtered.size())));grid.setAdapter(new Posters());more.setVisibility(filtered.size()>visible.size()?View.VISIBLE:View.GONE);summary.setText(filtered.size()==0?"Geen titels gevonden.":String.format(Locale.getDefault(),"%,d %s",filtered.size(),section.equals("episodes")?(filtered.size()==1?"aflevering":"afleveringen")+" beschikbaar · Seizoen "+selectedSeason:"series · Kies een serie")+(loading?" · Wordt bijgewerkt…":!testMode&&repo.partialSources()?" · Eén seriebron is tijdelijk niet bereikbaar.":""));}
 private void toggleFavorite(CatalogEntry e){rememberEntry(e);if(favorites.contains(e.key()))favorites.remove(e.key());else favorites.add(e.key());prefs.edit().putStringSet("favorites",new HashSet<>(favorites)).apply();Toast.makeText(this,favorites.contains(e.key())?"Toegevoegd aan Mijn lijst":"Verwijderd uit Mijn lijst",Toast.LENGTH_SHORT).show();filter();}
 private void openDetails(CatalogEntry e){if(!e.isEpisode()){new AlertDialog.Builder(this).setTitle(e.title).setItems(new String[]{"Seizoenen en afleveringen",favorites.contains(e.key())?"Verwijder uit Mijn lijst":"Voeg toe aan Mijn lijst"},(d,n)->{if(n==0)openSeries(e);else toggleFavorite(e);}).setNegativeButton("Terug",null).show();return;}long saved=prefs.getLong("position:"+e.key(),0);List<String> actions=new ArrayList<>();actions.add(saved>10000?"Kijk verder · "+formatTime(saved):"▶ Kijk");if(saved>10000)actions.add("Vanaf het begin");new AlertDialog.Builder(this).setTitle(e.title).setItems(actions.toArray(new String[0]),(d,n)->startPlayback(e,n==0?saved:0)).setNegativeButton("Terug",null).show();}
 private void openSeries(CatalogEntry entry){loading=true;selectedSeries=entry;final int generation=++loadGeneration;summary.setText("Seizoenen en afleveringen laden…");List<CatalogEntry> sources=SeriesSourceMatcher.sources(entry,variants);episodeWorker.execute(()->{try{EpisodeCatalog.Result result;if(testMode){List<CatalogEntry> episodes=new ArrayList<>();for(CatalogEntry parent:sources){JSONObject data=new JSONObject().put("episodes",new JSONObject().put("1",new JSONArray().put(new JSONObject().put("id",1001).put("title","Testaflevering 1").put("episode_num",1)).put(new JSONObject().put("id",1002).put("title","Testaflevering 2").put("episode_num",2))).put("2",new JSONArray().put(new JSONObject().put("id",2001).put("title","Testaflevering 3").put("episode_num",1))));episodes.addAll(EpisodeCatalog.parse(data,parent,"",false));}result=EpisodeCatalog.merge(episodes);}else result=repo.episodes(sources);handler.post(()->{if(destroyed||generation!=loadGeneration)return;loading=false;selectedEpisodes=result;variants.putAll(result.variants);selectLanguageRepresentatives();if(result.seasons.isEmpty()){summary.setText("Deze serie heeft nu geen beschikbare afleveringen.");return;}showSeasons();});}catch(Exception error){handler.post(()->{if(destroyed||generation!=loadGeneration)return;loading=false;summary.setText("Afleveringen niet bereikbaar. Open de serie opnieuw om te proberen.");});}});}
 private void showSeasons(){if(selectedEpisodes==null)return;List<Integer> seasons=new ArrayList<>(selectedEpisodes.seasons.keySet());String[] labels=new String[seasons.size()];for(int i=0;i<labels.length;i++)labels[i]=seasons.get(i)==0?"Specials":"Seizoen "+seasons.get(i);new AlertDialog.Builder(this).setTitle(selectedSeries==null?"Seizoenen":selectedSeries.title).setItems(labels,(d,n)->{selectedSeason=seasons.get(n);episodeList=selectedEpisodes.seasons.get(selectedSeason);section="episodes";query="";limit=96;showCatalog();}).setNegativeButton("Terug",null).show();}
 private void startPlayback(CatalogEntry e,long saved){cancelPosterRequests();playing=e;List<CatalogEntry> found=variants.get(e.key());playSources=new ArrayList<>(found==null?Collections.singletonList(e):found);sortPlaybackSources();sourceIndex=0;resumePosition=saved;retries=0;userPaused=false;showPlayer();}
 private void showPlayer(){FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);playerView=new PlayerView(this);playerView.setId(1040);playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);playerView.setControllerShowTimeoutMs(5000);playerView.setControllerAutoShow(true);playerView.setUseController(true);playerView.setShowSubtitleButton(true);playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);root.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(22),dp(12),dp(22),dp(12));top.setBackgroundColor(0x99070d18);playbackStatus=label(playing.title+" · Aflevering starten…",16,Color.WHITE);top.addView(playbackStatus);LinearLayout controls=new LinearLayout(this);addButton(controls,button("Terug",1041,this::showCatalog));if(!playing.isEpisode())addButton(controls,button("Mijn lijst",1042,()->toggleFavorite(playing)));addButton(controls,button("Taal",1044,this::chooseLanguage));addButton(controls,button("Ondertiteling",1045,this::chooseSubtitles));addButton(controls,button("Versie",1043,this::chooseVersion));addButton(controls,button("Geen geluid?",1046,this::showAudioHelp));HorizontalScrollView controlScroll=new HorizontalScrollView(this);controlScroll.setHorizontalScrollBarEnabled(false);controlScroll.addView(controls);top.addView(controlScroll);FrameLayout.LayoutParams tlp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);root.addView(top,tlp);setContentView(root);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);handler.postDelayed(()->{if(playerView!=null&&!top.hasFocus())top.setVisibility(View.GONE);},6000);playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->top.setVisibility(visibility==View.VISIBLE||top.hasFocus()?View.VISIBLE:View.GONE));createPlayer();playerView.requestFocus();}
 private void createPlayer(){DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(30000,90000,2500,7000).setTargetBufferBytes(64*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.21 LibVLC/3.0.21").setConnectTimeoutMs(8000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false);DefaultDataSource.Factory source=new DefaultDataSource.Factory(this,http);
  player=new ExoPlayer.Builder(this).setLoadControl(load).setMediaSourceFactory(new DefaultMediaSourceFactory(new ResolvingDataSource.Factory(source,spec->spec.withRequestHeaders(testMode?Collections.emptyMap():repo.headersForUri(spec.uri.toString()))))).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(preferredLanguage.equals("multi")?"en":preferredLanguage).setPreferredTextLanguages("nl","en").setSelectUndeterminedTextLanguage(true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,prefs.getBoolean("subtitles_off",false)).build());player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);playerView.setPlayer(player);player.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(player==null)return;if(state==Player.STATE_BUFFERING){bufferStarted=System.currentTimeMillis();playbackStatus.setText("Even laden…");scheduleStall();}else{cancelStall();if(state==Player.STATE_READY){scheduleAudioTrackCheck();int height=player.getVideoSize().height;playbackStatus.setText(playing.title+(height>0?" · "+height+"p":""));}else if(state==Player.STATE_ENDED){prefs.edit().remove("position:"+playing.key()).apply();if(!startNextEpisode())Toast.makeText(MainActivity.this,"Laatste aflevering afgelopen",Toast.LENGTH_SHORT).show();}}}@Override public void onRenderedFirstFrame(){renderedFirstFrame=true;scheduleAudioTrackCheck();}@Override public void onVideoSizeChanged(VideoSize size){if(playbackStatus!=null&&playing!=null&&size.height>0)playbackStatus.setText(playing.title+" · "+size.height+"p");}@Override public void onPlayerError(PlaybackException error){if(player!=null)fallback();}});playCurrent();progressTask=new Runnable(){public void run(){if(player!=null){storeProgress();handler.postDelayed(this,10000);}}};handler.postDelayed(progressTask,10000);}
 private void playCurrent(){if(player==null)return;cancelAudioTrackCheck();renderedFirstFrame=false;CatalogEntry x=playSources.get(sourceIndex);MediaItem media=new MediaItem.Builder().setUri(testMode?"asset:///test-clip.mp4":repo.stream(x)).setSubtitleConfigurations(testMode&&getIntent().getBooleanExtra("subtitle_test_mode",false)?Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse("asset:///subtitle-test.srt")).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("nl").build()):Collections.emptyList()).setMediaId(x.key()).setMediaMetadata(new MediaMetadata.Builder().setTitle(playing.title).build()).build();player.setMediaItem(media,resumePosition);player.prepare();player.play();}
 private boolean startNextEpisode(){CatalogEntry next=NextEpisodeAutoplay.next(selectedEpisodes,playing);if(next==null)return false;
  // Keep the same player and original Sony-compatible audio path (October 15).
  // A new episode starts at zero; the just-finished episode is not resumed.
  playing=next;List<CatalogEntry> found=variants.get(next.key());
  playSources=new ArrayList<>(found==null?Collections.singletonList(next):found);
  sortPlaybackSources();
  sourceIndex=0;resumePosition=0;retries=0;userPaused=false;
  if(playbackStatus!=null)playbackStatus.setText("Volgende aflevering: "+next.title);
  Toast.makeText(this,"Volgende aflevering: "+next.title,Toast.LENGTH_SHORT).show();
  playCurrent();return true;
 }
 private void fallback(){if(player==null)return;cancelAudioTrackCheck();resumePosition=Math.max(resumePosition,player.getCurrentPosition());cancelStall();if(sourceIndex+1<playSources.size()){sourceIndex++;playbackStatus.setText("Andere beschikbare versie proberen…");playCurrent();}else if(retries++==0){playbackStatus.setText("Verbinding opnieuw opbouwen…");handler.postDelayed(()->{if(player!=null)playCurrent();},1500);}else{player.pause();playbackStatus.setText("Deze bron speelt nu niet goed. Kies een andere aflevering of probeer opnieuw.");new AlertDialog.Builder(this).setTitle("Afspelen onderbroken").setMessage("De mediabron of verbinding is tijdelijk niet beschikbaar.").setPositiveButton("Opnieuw",(d,n)->{sourceIndex=0;retries=0;playCurrent();}).setNegativeButton("Terug",(d,n)->showCatalog()).show();}}
 private void scheduleStall(){cancelStall();stallTask=()->{if(player!=null&&player.getPlayWhenReady()&&player.getPlaybackState()==Player.STATE_BUFFERING)fallback();};handler.postDelayed(stallTask,12000);}
 private void cancelStall(){if(stallTask!=null){handler.removeCallbacks(stallTask);stallTask=null;}}

 // Media Player keeps alternate episode URLs and starts with the working second
 // provider. Do the same without changing Sony/Google TV audio decoder settings.
 private void sortPlaybackSources(){
  if(playSources==null)return;
  playSources.sort(Comparator.comparingInt((CatalogEntry e)->e.id>=1000000000?0:1)
      .thenComparing(CatalogEntry.playbackOrder(preferredLanguage)));
 }
 private void cancelAudioTrackCheck(){
  if(audioTrackCheck!=null){handler.removeCallbacks(audioTrackCheck);audioTrackCheck=null;}
  audioCheckGeneration++;
 }
 private void scheduleAudioTrackCheck(){
  cancelAudioTrackCheck();
  if(testMode||player==null||sourceIndex<0||playSources==null)return;
  final int generation=audioCheckGeneration;
  audioTrackCheck=()->{
   audioTrackCheck=null;
   if(player==null||generation!=audioCheckGeneration||!player.getPlayWhenReady()
      ||player.getPlaybackState()!=Player.STATE_READY)return;
   int supported=0,present=0;
   for(Tracks.Group group:player.getCurrentTracks().getGroups()){
    if(group.getType()!=C.TRACK_TYPE_AUDIO)continue;
    for(int i=0;i<group.length;i++){
     present++;
     if(group.isTrackSupported(i))supported++;
    }
   }
   if(supported==0&&present>0){
    if(sourceIndex+1<playSources.size()){
     if(playbackStatus!=null)playbackStatus.setText("Niet-ondersteund audiospoor · Andere bron proberen…");
     fallback();
    }else{
     if(playbackStatus!=null)playbackStatus.setText("Geen decoder · Softwareaudio starten…");
     playWithSoftwareAudio();
    }
   }
  };
  handler.postDelayed(audioTrackCheck,8000);
 }
 private void playWithSoftwareAudio(){
  // A missing device E-AC3 decoder cannot be fixed by passthrough toggling.
  if(testMode||player==null||playing==null||playSources==null||
      sourceIndex<0||sourceIndex>=playSources.size())return;
  CatalogEntry entry=playSources.get(sourceIndex);
  android.content.Intent software=new android.content.Intent(this,VlcFallbackActivity.class);
  software.putExtra("stream_url",repo.stream(entry));
  software.putExtra("episode_key",playing.key());
  software.putExtra("episode_title",playing.title);
  software.putExtra("resume_ms",Math.max(resumePosition,player.getCurrentPosition()));
  showCatalog();
  startActivity(software);
 }
 private String selectedProvider(){
  if(playSources==null||playSources.isEmpty()||sourceIndex<0||sourceIndex>=playSources.size())return "geen bron";
  return playSources.get(sourceIndex).id>=1000000000?"Bron 2":"Bron 1";
 }
 private void showAudioHelp(){
  if(player==null)return;
  new AlertDialog.Builder(this).setTitle("Geen geluid? · "+selectedProvider())
   .setItems(new String[]{"Andere afleveringsbron proberen","Audiospoor kiezen","Audio-informatie"},(d,which)->{
    if(which==0)tryAnotherAudioSource();
    else if(which==1)chooseAudioTrack();
    else showAudioInformation();
   }).setNegativeButton("Sluiten",null).show();
 }
 private void tryAnotherAudioSource(){
  if(player==null||playSources==null||playSources.size()<2){
   new AlertDialog.Builder(this).setTitle("Geen tweede bron")
    .setMessage("Er is voor deze aflevering maar één bron gevonden. Vergelijk dezelfde aflevering in Media Player.")
    .setPositiveButton("OK",null).show();
   return;
  }
  long previous=Math.max(0,player.getCurrentPosition());
  resumePosition=previous;
  sourceIndex=(sourceIndex+1)%playSources.size();
  retries=0;
  if(playbackStatus!=null)playbackStatus.setText("Andere bron: "+selectedProvider()+" · "+(sourceIndex+1)+"/"+playSources.size());
  playCurrent();
 }
 private void chooseAudioTrack(){
  if(player==null)return;
  List<Tracks.Group> groups=new ArrayList<>();
  List<Integer> indices=new ArrayList<>();
  List<String> names=new ArrayList<>();
  for(Tracks.Group group:player.getCurrentTracks().getGroups()){
   if(group.getType()!=C.TRACK_TYPE_AUDIO)continue;
   for(int i=0;i<group.length;i++){
    if(!group.isTrackSupported(i))continue;
    Format f=group.getTrackFormat(i);
    names.add((f.language==null?"Onbekende taal":CatalogEntry.languageName(f.language))+
       " · "+(f.sampleMimeType==null?"codec onbekend":f.sampleMimeType)+(group.isTrackSelected(i)?" ✓":""));
    groups.add(group);indices.add(i);
   }
  }
  if(names.isEmpty()){
   new AlertDialog.Builder(this).setTitle("Geen ondersteund audiospoor")
    .setMessage("Probeer een andere afleveringsbron met de knop 'Geen geluid?'.")
    .setPositiveButton("Andere bron",(d,n)->tryAnotherAudioSource())
    .setNegativeButton("Sluiten",null).show();
   return;
  }
  new AlertDialog.Builder(this).setTitle("Audiospoor kiezen")
   .setItems(names.toArray(new String[0]),(d,n)->{
    if(player==null)return;
    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
       .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false)
       .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
       .setOverrideForType(new TrackSelectionOverride(groups.get(n).getMediaTrackGroup(),indices.get(n))).build());
   }).setNegativeButton("Sluiten",null).show();
 }
 private void showAudioInformation(){
  if(player==null)return;
  StringBuilder details=new StringBuilder();
  details.append(selectedProvider()).append(" · Afleveringsbron ")
    .append(sourceIndex+1).append(" van ").append(playSources==null?0:playSources.size());
  int count=0;
  for(Tracks.Group group:player.getCurrentTracks().getGroups()){
   if(group.getType()!=C.TRACK_TYPE_AUDIO)continue;
   for(int i=0;i<group.length;i++){
    Format f=group.getTrackFormat(i);
    details.append("\\nSpoor ").append(++count).append(": ")
      .append(f.sampleMimeType==null?"onbekend":f.sampleMimeType)
      .append(", ").append(f.channelCount>0?f.channelCount+" kanalen":"kanalen onbekend")
      .append(", ").append(f.language==null?"taal onbekend":f.language)
      .append(group.isTrackSupported(i)?", ondersteund":", niet ondersteund")
      .append(group.isTrackSelected(i)?", geselecteerd":"");
   }
  }
  if(count==0)details.append("\\nGeen audiosporen gedetecteerd.");
  new AlertDialog.Builder(this).setTitle("Audio-informatie")
    .setMessage(details.toString()).setPositiveButton("OK",null).show();
 }

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

 private void chooseLanguage(){LinkedHashSet<String> codes=new LinkedHashSet<>();codes.add("en");if(player==null)codes.addAll(testMode?LanguageSelection.languages(variants.values()):availableLanguages);else for(CatalogEntry entry:playSources){String code=entry.languageCode();if(!code.isEmpty()&&!code.equals("und"))codes.add(code);}if(player!=null)for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<group.length;i++){String code=CatalogEntry.canonical(group.getTrackFormat(i).language);if(group.isTrackSupported(i)&&!code.isEmpty()&&!code.equals("und"))codes.add(code);}List<String> available=new ArrayList<>(codes);String[] labels=new String[available.size()];for(int i=0;i<labels.length;i++)labels[i]=CatalogEntry.languageName(available.get(i))+(available.get(i).equals("en")?" · standaard":"");new AlertDialog.Builder(this).setTitle("Taalvoorkeur").setSingleChoiceItems(labels,available.indexOf(preferredLanguage),(d,n)->{preferredLanguage=available.get(n);prefs.edit().putString("preferred_language",preferredLanguage).apply();d.dismiss();if(player!=null){boolean audio=hasAudioLanguage(preferredLanguage);player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(preferredLanguage.equals("multi")?"en":preferredLanguage).build());if(!audio){resumePosition=player.getCurrentPosition();sortPlaybackSources();sourceIndex=0;retries=0;playCurrent();}}else{selectLanguageRepresentatives();}Toast.makeText(this,"Taalvoorkeur: "+CatalogEntry.languageName(preferredLanguage),Toast.LENGTH_SHORT).show();}).setNegativeButton("Terug",null).show();}
 private void selectLanguageRepresentatives(){final int generation=++languageGeneration;if(languageWorker instanceof ThreadPoolExecutor)((ThreadPoolExecutor)languageWorker).getQueue().clear();final String language=preferredLanguage;final Map<String,List<CatalogEntry>> snapshot=new HashMap<>(catalogs);final EpisodeCatalog.Result episodes=selectedEpisodes;final Map<Integer,List<CatalogEntry>> seasons=episodes==null?Collections.emptyMap():new TreeMap<>(episodes.seasons);languageWorker.execute(()->{Map<String,List<CatalogEntry>> selected=LanguageSelection.choose(snapshot,variants,language);Map<Integer,List<CatalogEntry>> selectedSeasons=episodes==null?Collections.emptyMap():LanguageSelection.choose(seasons,episodes.variants,language);handler.post(()->{if(destroyed||generation!=languageGeneration||!language.equals(preferredLanguage))return;catalogs.putAll(selected);if(episodes!=null&&episodes==selectedEpisodes){episodes.seasons.putAll(selectedSeasons);if(section.equals("episodes"))episodeList=episodes.seasons.get(selectedSeason);}if(player==null)filter();});});}
 private void chooseVersion(){if(player==null)return;String[] names=new String[playSources.size()];for(int i=0;i<names.length;i++)names[i]=(playSources.get(i).id>=1000000000?"Bron 2":"Bron 1")+" · "+CatalogEntry.languageName(playSources.get(i).languageCode())+" · "+playSources.get(i).title;new AlertDialog.Builder(this).setTitle("Beschikbare versies").setSingleChoiceItems(names,sourceIndex,(d,n)->{resumePosition=player.getCurrentPosition();sourceIndex=n;retries=0;playCurrent();d.dismiss();}).setNegativeButton("Terug",null).show();}
 private JSONObject entryJson(CatalogEntry e)throws Exception{return new JSONObject().put("id",e.id).put("title",e.title).put("extension",e.extension).put("poster",e.poster).put("category",e.category).put("type",e.type).put("movieId",e.movieId).put("groupId",e.groupId).put("language",e.languageHint);}
 private CatalogEntry jsonEntry(JSONObject e)throws Exception{return new CatalogEntry(e.getInt("id"),e.getString("title"),e.getString("extension"),e.optString("poster"),e.optString("category"),e.getString("type"),0,e.optString("movieId"),e.optString("groupId"),e.optString("language"));}
 private void rememberEntry(CatalogEntry e){try{JSONObject saved=new JSONObject(prefs.getString("saved_entries","{}"));JSONObject row=entryJson(e);JSONArray sources=new JSONArray();for(CatalogEntry x:variants.getOrDefault(e.key(),Collections.singletonList(e)))sources.put(entryJson(x));row.put("sources",sources);saved.put(e.key(),row);prefs.edit().putString("saved_entries",saved.toString()).apply();}catch(Exception ignored){}}
 private List<CatalogEntry> savedEntries(){List<CatalogEntry> out=new ArrayList<>();try{JSONObject saved=new JSONObject(prefs.getString("saved_entries","{}"));Iterator<String> keys=saved.keys();while(keys.hasNext()){JSONObject row=saved.getJSONObject(keys.next());CatalogEntry e=jsonEntry(row);out.add(e);JSONArray sources=row.optJSONArray("sources");if(sources!=null&&!variants.containsKey(e.key())){List<CatalogEntry> xs=new ArrayList<>();for(int i=0;i<sources.length();i++)xs.add(jsonEntry(sources.getJSONObject(i)));if(!xs.isEmpty())variants.put(e.key(),xs);}}}catch(Exception ignored){}return out;}
 private void storeProgress(){if(player==null||playing==null)return;rememberEntry(playing);long p=player.getCurrentPosition(),duration=player.getDuration();SharedPreferences.Editor edit=prefs.edit();if(player.getPlaybackState()==Player.STATE_ENDED||duration>0&&p>duration-15000)edit.remove("position:"+playing.key());else if(p>10000)edit.putLong("position:"+playing.key(),p).putLong("watched:"+playing.key(),System.currentTimeMillis()).putString("last_title",playing.title).putString("last_key",playing.key());edit.apply();}
 private void stopPlayback(){if(player==null)return;cancelAudioTrackCheck();storeProgress();cancelStall();if(progressTask!=null)handler.removeCallbacks(progressTask);playerView.setPlayer(null);player.release();player=null;playerView=null;playing=null;}
 private volatile boolean languageWorkerBlockedForTest;
 CountDownLatch pauseLanguageWorkerForTest(){if(!testMode)throw new IllegalStateException();CountDownLatch release=new CountDownLatch(1);languageWorker.execute(()->{languageWorkerBlockedForTest=true;try{release.await(60,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{languageWorkerBlockedForTest=false;}});return release;}
 boolean isLanguageWorkerBlockedForTest(){return testMode&&languageWorkerBlockedForTest;}
 boolean hasPosterForTest(String url){return testMode&&images.get(url)!=null;}
 boolean isPlaybackReadyForTest(){return testMode&&player!=null&&renderedFirstFrame&&player.isPlaying();}
 private String formatTime(long ms){long s=ms/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
 @Override public boolean dispatchKeyEvent(KeyEvent e){if(player==null&&e.getAction()==KeyEvent.ACTION_DOWN){View focused=getCurrentFocus();int key=e.getKeyCode();if(focused==grid&&key==KeyEvent.KEYCODE_DPAD_UP&&grid.getSelectedItemPosition()<grid.getNumColumns()){findViewById(1001).requestFocus();return true;}if(focused==search&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)){if(key==KeyEvent.KEYCODE_DPAD_UP)findViewById(1001).requestFocus();else grid.requestFocus();return true;}if(focused!=null){int id=focused.getId();int[] order={1001,1007,1006,1003,1004,1005};for(int i=0;i<order.length;i++)if(id==order[i]){if(key==KeyEvent.KEYCODE_DPAD_UP)return true;if(key==KeyEvent.KEYCODE_DPAD_DOWN){search.requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){int step=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;for(int next=i+step;next>=0&&next<order.length;next+=step){View candidate=findViewById(order[next]);if(candidate!=null&&candidate.getVisibility()==View.VISIBLE){candidate.requestFocus();break;}}return true;}}}}if(player!=null&&e.getAction()==KeyEvent.ACTION_DOWN){int k=e.getKeyCode();if(k==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(player.isPlaying())player.pause();else player.play();return true;}if(k==KeyEvent.KEYCODE_MEDIA_PLAY){player.play();return true;}if(k==KeyEvent.KEYCODE_MEDIA_PAUSE){player.pause();return true;}if(k==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){player.seekForward();return true;}if(k==KeyEvent.KEYCODE_MEDIA_REWIND){player.seekBack();return true;}}return playerView!=null&&playerView.hasFocus()&&playerView.dispatchKeyEvent(e)||super.dispatchKeyEvent(e);}
 @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(playerView!=null)playerView.requestLayout();else showCatalog();}
 @Override public void onBackPressed(){if(player!=null){showCatalog();return;}if(section.equals("episodes")){switchSection("series");return;}if(!query.isEmpty()){query="";search.setText("");filter();return;}super.onBackPressed();}
 @Override protected void onStop(){if(player!=null){storeProgress();player.pause();cancelStall();}super.onStop();}
 @Override protected void onResume(){super.onResume();if(appUpdater!=null)appUpdater.checkOnOpen();}
 @Override protected void onDestroy(){if(familyLicense!=null)familyLicense.close();
  if (appUpdater != null) appUpdater.close();destroyed=true;++loadGeneration;stopPlayback();handler.removeCallbacksAndMessages(null);languageWorker.shutdownNow();catalogWorker.shutdownNow();episodeWorker.shutdownNow();imageWorker.shutdownNow();super.onDestroy();}
 private void cancelPosterRequests(){posterGeneration++;if(imageWorker instanceof ThreadPoolExecutor)((ThreadPoolExecutor)imageWorker).getQueue().clear();}
 private void loadPoster(ImageView image,CatalogEntry entry){
  final int generation=posterGeneration;String tag=entry.type+":"+entry.id;image.setTag(tag);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
  LinkedHashSet<String> urls=new LinkedHashSet<>();String first=PosterMetadata.resolve(entry.poster,"");if(!first.isEmpty())urls.add(first);
  List<CatalogEntry> found=variants.get(entry.key());final List<CatalogEntry> versions=new ArrayList<>(found==null?Collections.singletonList(entry):found);
  for(CatalogEntry x:versions){String url=PosterMetadata.resolve(x.poster,"");if(!url.isEmpty())urls.add(url);}
  for(String url:urls){Bitmap cached=images.get(url);if(cached!=null){image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(cached);return;}}
  if(testMode&&urls.isEmpty())return;
  try{imageWorker.execute(()->{try{
   if(generation!=posterGeneration)return;Bitmap bitmap=null;
   for(String url:urls){if(generation!=posterGeneration)return;bitmap=posterBitmap(url);if(bitmap!=null)break;}
   if(bitmap==null&&!testMode)for(CatalogEntry x:versions){if(generation!=posterGeneration)return;try{for(String url:repo.posters(x)){if(generation!=posterGeneration)return;if(!urls.add(url))continue;bitmap=posterBitmap(url);if(bitmap!=null)break;}}catch(Exception ignored){}if(bitmap!=null)break;}
   final Bitmap loaded=bitmap;if(loaded!=null)handler.post(()->{if(!destroyed&&generation==posterGeneration&&tag.equals(image.getTag())){image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(loaded);}});
  }catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
 }
 private Bitmap posterBitmap(String url){
  Bitmap cached=images.get(url);if(cached!=null)return cached;Long failed=failedPosters.get(url);if(failed!=null&&System.currentTimeMillis()-failed<60000)return null;
  HttpURLConnection connection=null;try{connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(7000);connection.setRequestProperty("User-Agent","Mozilla/5.0 (Android) TheOneSeries/1.1.3");connection.setRequestProperty("Accept","image/*");
   Bitmap bitmap;try(InputStream input=connection.getInputStream()){BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=2;bitmap=BitmapFactory.decodeStream(input,null,opts);}
   if(bitmap!=null){images.put(url,bitmap);failedPosters.remove(url);}else failedPosters.put(url,System.currentTimeMillis());return bitmap;
  }catch(Exception ignored){failedPosters.put(url,System.currentTimeMillis());return null;}finally{if(connection!=null)connection.disconnect();}
 }
 private final class Posters extends BaseAdapter {public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return visible.get(p).id;}public View getView(int position,View recycled,ViewGroup parent){CatalogEntry e=visible.get(position);LinearLayout box=new LinearLayout(MainActivity.this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(8),dp(8),dp(8));box.setBackground(background(false));box.setContentDescription(e.title);box.setLayoutParams(new AbsListView.LayoutParams(-1,dp(264)));ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(Color.rgb(21,40,60));image.setImageResource(R.drawable.series_logo);box.addView(image,new LinearLayout.LayoutParams(-1,dp(194)));TextView name=label((favorites.contains(e.key())?"★ ":"")+e.title,18,Color.WHITE);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);box.addView(name,new LinearLayout.LayoutParams(-1,-2));loadPoster(image,e);return box;}}
}

