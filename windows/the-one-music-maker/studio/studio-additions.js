'use strict';
/* Additive Music Maker compatibility. The original Studio engine and screens stay intact. */
let studioDirty=false,studioBusy=false;
const redoStack=[];
const oldCommit=commit,oldSave=save,oldAudioEditor=renderAudioEditor,oldPlayClipAudio=playClipAudio;
commit=function(){redoStack.length=0;studioDirty=true;oldCommit();};
save=function(){studioDirty=true;oldSave();};
function historyState(){return JSON.stringify({P,sel});}
function historyRestore(value){const o=JSON.parse(value);if(playing)stop();P=o.P;Object.assign(sel,o.sel);syncGraph();renderAll();save();}
undo=function(){const value=undoStack.pop();if(!value){toast('Er is niets om ongedaan te maken.');return;}redoStack.push(historyState());historyRestore(value);};
function redo(){const value=redoStack.pop();if(!value){toast('Er is niets om opnieuw te doen.');return;}undoStack.push(historyState());historyRestore(value);}
$('#bUndo').onclick=undo;
const redoButton=el('button','pill');redoButton.id='bRedo';redoButton.textContent='Opnieuw';redoButton.title='Ctrl+Shift+Z / Ctrl+Y';redoButton.onclick=redo;$('#bUndo').after(redoButton);
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&(e.key.toLowerCase()==='y'||(e.shiftKey&&e.key.toLowerCase()==='z'))&&!e.target.matches('input:not([type=range]):not([type=checkbox]),textarea,select')){e.preventDefault();e.stopImmediatePropagation();redo();}},true);
let editSnapshot=null;
document.addEventListener('focusin',e=>{if(e.target.matches('input,select')&&!e.target.closest('[data-studio-fades]'))editSnapshot=historyState();});
document.addEventListener('change',e=>{if(editSnapshot&&e.target.matches('input,select')&&e.target.id!=='bpm'&&e.target.type!=='file'&&!e.target.closest('[data-studio-fades]')){undoStack.push(editSnapshot);redoStack.length=0;editSnapshot=null;}},true);

renderAudioEditor=function(ed,t,c){
  oldAudioEditor(ed,t,c);if(!c)return;
  const row=el('div','ed-bar');row.dataset.studioFades='true';
  const source=buffers[c.bufferId]?.buffer;
  if(source)for(const [key,label,value,max] of [['start','Startpositie (s)',c.start*spb(),86400],['offset','Begin bron (s)',c.offset,source.duration],['end','Einde bron (s)',c.offset+c.len*spb(),source.duration]]){
    const l=el('label','row2');l.textContent=label;const input=el('input');input.type='number';input.min='0';input.max=String(max);input.step='.01';input.value=String(value);input.style.width='85px';input.setAttribute('aria-label',label);
    input.onchange=()=>{commit();const v=clamp(Number(input.value)||0,0,max),end=c.offset+c.len*spb();if(key==='start')c.start=v/spb();else if(key==='offset'){c.offset=Math.min(v,end);c.len=(end-c.offset)/spb();}else c.len=Math.max(0,v-c.offset)/spb();renderAll();save();};l.append(input);row.append(l);
  }
  for(const [key,label] of [['fadeIn','Fade in (s)'],['fadeOut','Fade uit (s)']]){
    const l=el('label','row2');l.textContent=label;const input=el('input');input.type='number';input.min='0';input.max=String(c.len*spb());input.step='.01';input.value=String(c[key]||0);input.style.width='85px';input.setAttribute('aria-label',label);
    input.onchange=()=>{commit();c[key]=clamp(Number(input.value)||0,0,c.len*spb());input.value=String(c[key]);save();};l.append(input);row.append(l);
  }
  ed.querySelector('.ed-main').append(row);
};
playClipAudio=function(g,t,c,time,offBeats,maxBeats){
  const node=g.tracks[t.id];if(!node||(!c.fadeIn&&!c.fadeOut))return oldPlayClipAudio(g,t,c,time,offBeats,maxBeats);
  const length=c.len*spb(),elapsed=offBeats*spb(),duration=Math.min(c.len-offBeats,maxBeats)*spb();if(duration<=.003)return;
  const f={gain:1,fadeIn:c.fadeIn||0,fadeOut:c.fadeOut||0};
  const points=MusicModel.envelope(f,{elapsed,length}).filter(p=>p.time<=duration);
  points.push({time:duration,value:MusicModel.fadeAt(f,elapsed+duration,length)});
  const envelope=g.c.createGain();envelope.connect(node.in);
  envelope.gain.setValueAtTime(points[0].value,time);for(const p of points.slice(1))envelope.gain.linearRampToValueAtTime(p.value,time+p.time);
  const input=node.in;node.in=envelope;try{return oldPlayClipAudio(g,t,c,time,offBeats,maxBeats);}finally{node.in=input;}
};

function audioBase64(buf){const bytes=new Uint8Array(MusicModel.wav(buf));let s='';for(let i=0;i<bytes.length;i+=16384)s+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(s);}
function referencedAudio(project){return [...new Set(project.tracks.filter(t=>t.type==='audio').flatMap(t=>t.clips.map(c=>c.bufferId)))];}
async function portableProject(){
  const media={};for(const id of referencedAudio(P)){if(!buffers[id])throw new Error('Audio ontbreekt: '+id);media[id]={name:buffers[id].name,wav:audioBase64(buffers[id].buffer)};}
  return {app:'The One Studio',version:2,project:JSON.parse(JSON.stringify(P)),media};
}
async function savePortable(){
  if(studioBusy||recording)return;studioBusy=true;
  try{await saveFile('the-one-studio-project.json',new Blob([JSON.stringify(await portableProject())],{type:'application/json'}));studioDirty=false;toast('Projectbestand inclusief audio gemaakt.');}
  catch(e){toast('Opslaan mislukt: '+e.message,5000);}finally{studioBusy=false;}
}
async function decodeEmbedded(base64){if(typeof base64!=='string')throw new Error('Ongeldige projectaudio.');const bin=atob(base64),bytes=Uint8Array.from(bin,c=>c.charCodeAt(0));ensureCtx();return ctx.decodeAudioData(bytes.buffer);}
async function stageProject(o){
  if(!o||typeof o!=='object')throw new Error('Geen geldig projectbestand.');
  const staged={};let project;
  if(o.format==='the-one-music-maker'){
    if(o.version!==1||!Array.isArray(o.tracks)||o.tracks.length>200)throw new Error('Geen ondersteund Music Maker-project.');
    const bpm=clamp(Number(o.bpm)||120,40,240),beats=bpm/60;project=baseProject(bpm);project.loop.on=false;project.master.vol=clamp(Number(o.master)||0,0,1);project.master.limiter=false;project.tracks=[];
    const ids=new Set();
    for(const source of o.tracks){
      if(typeof source.id!=='string'||ids.has(source.id))throw new Error('Ongeldige spoor-ID.');ids.add(source.id);
      const audio=await decodeEmbedded(o.media?.[source.id]),clean=MusicModel.cleanClip(source,audio.duration),id='mm-'+source.id;
      staged[id]={buffer:audio,name:String(source.name||'Audio'),peaks:computePeaks(audio)};
      const track=mkTrack('audio',String(source.name||'Audio'));Object.assign(track,{vol:clean.gain,pan:clean.pan,mute:clean.mute,solo:clean.solo,rev:0,dly:0});
      track.clips=[{id:uid(),name:track.name,start:clean.start*beats,len:(clean.trimEnd-clean.trimStart)*beats,bufferId:id,offset:clean.trimStart,gain:1,reversed:false,fadeIn:clean.fadeIn,fadeOut:clean.fadeOut}];project.tracks.push(track);
    }
  }else{
    const input=o.project||o;if(!Array.isArray(input.tracks)||input.tracks.length>200)throw new Error('Geen geldig Studio-project.');project=normalize(JSON.parse(JSON.stringify(input)));
    for(const id of referencedAudio(project)){
      if(typeof id!=='string'||['__proto__','constructor','prototype'].includes(id))throw new Error('Ongeldige audiobron-ID.');
      if(o.media?.[id]){const audio=await decodeEmbedded(o.media[id].wav);staged[id]={buffer:audio,name:o.media[id].name||'Audio',peaks:computePeaks(audio)};}
      else if(buffers[id])staged[id]=buffers[id];
      else{const old=await idb.get(id);if(!old?.ch?.length)throw new Error('Audio ontbreekt. Open het oude project in de oorspronkelijke browser en sla het inclusief audio op.');const audio=new AudioBuffer({length:old.ch[0].length,numberOfChannels:old.ch.length,sampleRate:old.sr});old.ch.forEach((ch,n)=>audio.copyToChannel(ch,n));staged[id]={buffer:audio,name:old.name,peaks:computePeaks(audio)};}
    }
  }
  return {project,staged};
}
async function openPortable(file){
  if(!file||studioBusy||recording)return;studioBusy=true;
  try{
    const result=await stageProject(JSON.parse(await file.text()));
    if(playing)stop();commit();Object.assign(buffers,result.staged);P=result.project;
    for(const id of Object.keys(result.staged))persistBuffer(id);
    colorIdx=P.tracks.length;sel.track=P.tracks[0]?.id||null;sel.clip=P.tracks[0]?.clips[0]?.id||null;curBeat=0;syncGraph();renderAll();save();studioDirty=false;toast('Project inclusief audio geopend.');
  }catch(e){toast('Openen mislukt: '+e.message,6000);}finally{studioBusy=false;}
}
$('#fProj').accept='.json,.onemusic,application/json';$('#fProj').onchange=e=>openPortable(e.target.files?.[0]);
$('#projMenu').addEventListener('click',e=>{if(e.target.closest('[data-p="save"]')){e.stopImmediatePropagation();$('#projMenu').open=false;savePortable();}},true);

async function exportDirectWav(){
  if(studioBusy||recording)return;studioBusy=true;
  try{
    const end=Math.max(0,...P.tracks.flatMap(t=>t.clips.map(c=>c.start+c.len)));if(!end)throw new Error('Er staan nog geen clips in het project.');
    if(end*spb()>1800)throw new Error('Deze versie exporteert maximaal 30 minuten per mix.');
    if(playing)stop();toast('Stereo WAV renderen…',60000);await new Promise(r=>setTimeout(r,40));
    const sr=44100,oc=new OfflineAudioContext(2,Math.ceil(sr*(end*spb()+3)),sr),g=buildGraph(oc,false);
    for(let b=0;b<end-1e-6;b+=.25)scheduleTick(g,b,b*spb()+.02,false);
    const rendered=await oc.startRendering();let peak=0;
    for(let ch=0;ch<rendered.numberOfChannels;ch++)for(const x of rendered.getChannelData(ch))peak=Math.max(peak,Math.abs(x));
    if(peak>1)for(let ch=0;ch<rendered.numberOfChannels;ch++){const data=rendered.getChannelData(ch);for(let i=0;i<data.length;i++)data[i]/=peak;}
    await saveFile('the-one-studio-mix.wav',new Blob([MusicModel.wav(rendered)],{type:'audio/wav'}));
  }catch(e){toast('Exporteren mislukt: '+e.message,5000);}finally{studioBusy=false;}
}
const wavButton=el('button','pill');wavButton.id='bWav';wavButton.textContent='WAV opslaan';wavButton.onclick=exportDirectWav;$('#bExport').after(wavButton);
window.addEventListener('beforeunload',e=>{if(studioDirty||recording){e.preventDefault();e.returnValue='';}});
window.TheOneStudioAdditions={version:'1.1.0',portableProject,stageProject,openPortable,savePortable,exportDirectWav,redo};
