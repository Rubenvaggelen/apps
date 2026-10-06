const {chromium}=require('playwright'),assert=require('node:assert/strict'),path=require('node:path');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--autoplay-policy=no-user-gesture-required']});
 const page=await browser.newPage({viewport:{width:1400,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file://'+path.resolve(__dirname,'../studio/index.html'));
 await page.waitForFunction(()=>window.TheOneStudioAdditions&&document.querySelectorAll('.row').length>0);
 assert.equal(await page.locator('.row').count(),4);assert.equal(await page.locator('#bRedo').count(),1);assert.equal(await page.locator('#bWav').count(),1);
 const result=await page.evaluate(async()=>{
  ensureCtx();const b=ctx.createBuffer(1,44100,44100);b.getChannelData(0).fill(.4);
  const track=mkTrack('audio','Roundtrip');track.rev=0;track.dly=0;P=baseProject(120);P.loop.on=false;P.tracks=[track];
  addAudioClip(track,b,'Sound',2);const c=track.clips[0];c.fadeIn=.2;c.fadeOut=.3;sel.track=track.id;sel.clip=c.id;renderAll();
  const doc=await TheOneStudioAdditions.portableProject();const stage=await TheOneStudioAdditions.stageProject(doc);
  const imported=stage.project.tracks[0].clips[0],audio=stage.staged[imported.bufferId].buffer;
  const prior=P;let rejected=false;try{await TheOneStudioAdditions.stageProject({project:{tracks:[{id:'bad',type:'audio',clips:[{id:'badclip',bufferId:'missing'}]}]}})}catch(e){rejected=true}const atomic=P===prior;
  const legacy={format:'the-one-music-maker',version:1,bpm:120,master:.7,tracks:[{id:'old',name:'Old',start:3,trimStart:.1,trimEnd:.8,gain:.5,pan:-.4,fadeIn:.1,fadeOut:.2,solo:true}],media:{old:audioBase64(b)}};
  const converted=await TheOneStudioAdditions.stageProject(legacy),t=converted.project.tracks[0],cl=t.clips[0];
  commit();c.name='Changed';save();undo();const afterUndo=P.tracks[0].clips[0].name;redo();const afterRedo=P.tracks[0].clips[0].name;
  return {version:doc.version,samples:audio.length,value:audio.getChannelData(0)[100],fade:imported.fadeOut,rejected,atomic,afterUndo,afterRedo,legacy:{start:cl.start,len:cl.len,offset:cl.offset,vol:t.vol,pan:t.pan,solo:t.solo,fade:cl.fadeIn}};
 });
 assert.equal(result.version,2);assert.equal(result.samples,44100);assert(Math.abs(result.value-.4)<.0001);assert.equal(result.fade,.3);assert.equal(result.rejected,true);assert.equal(result.atomic,true);assert.equal(result.afterUndo,'Sound');assert.equal(result.afterRedo,'Changed');
 assert.equal(result.legacy.start,6);assert(Math.abs(result.legacy.len-1.4)<1e-8);assert.equal(result.legacy.offset,.1);assert.equal(result.legacy.vol,.5);assert.equal(result.legacy.pan,-.4);assert.equal(result.legacy.solo,true);assert.equal(result.legacy.fade,.1);
 const fading=await page.evaluate(async()=>{
  P=baseProject(120);P.loop.on=false;const t=mkTrack('audio','Fade');t.rev=0;t.dly=0;t.vol=1;P.tracks=[t];const b=new AudioBuffer({length:44100,numberOfChannels:1,sampleRate:44100});b.getChannelData(0).fill(.4);addAudioClip(t,b,'Fade',0);t.clips[0].fadeIn=.4;t.clips[0].fadeOut=.4;
  const oc=new OfflineAudioContext(2,44100,44100),g=buildGraph(oc,false);playClipAudio(g,t,t.clips[0],0,0,Infinity);const out=await oc.startRendering(),d=out.getChannelData(0);const mean=(a,z)=>{let s=0;for(let i=a;i<z;i++)s+=Math.abs(d[i]);return s/(z-a)};return {early:mean(400,1000),middle:mean(21000,22000),late:mean(42000,43000)};
 });
 assert(fading.middle>fading.early*3);assert(fading.middle>fading.late*3);assert.deepEqual(errors,[]);
 await page.evaluate(()=>studioDirty=false);await browser.close();console.log('PASS Studio: original demo, redo, portable audio, atomic failure, legacy project timing/mix, real rendered fades');
})().catch(e=>{console.error(e);process.exit(1)});
