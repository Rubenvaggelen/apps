const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync(__dirname+'/../index.html','utf8');
const start=source.indexOf('const nativeBackgroundYields=');
const yieldCode=source.slice(start,source.indexOf('\n});',start)+4);
const pumpStart=source.indexOf('window.theOneDjBackgroundTick=');
const pump=source.slice(pumpStart,source.indexOf('async function updateWake',pumpStart));
const wakeStart=source.indexOf('async function updateWake');
const wake=source.slice(wakeStart,source.indexOf('document.addEventListener("visibilitychange"',wakeStart));
let fills=0,ticks=0,states=[],callbacks=[];
const s={window:{TheOneNative:{setBackgroundPlayback:a=>states.push(a)}},document:{hidden:true},
 setTimeout:f=>{callbacks.push(f);return 1},clearTimeout(){},Date,Set,navigator:{},wakeLock:null,
 decks:[{playing:false,loading:true,done:false,tick(){ticks++}}],lib:[],autoMix:false,
 mediaRecoveryUntil:0,mediaRecoveryTimer:null,ctx:{},autoFill(){fills++},tickFade(){},tickAutoMix(){}};
vm.createContext(s);vm.runInContext(yieldCode+'\nlet nativePlaybackActive=null;\n'+pump+wake,s);
(async()=>{
 await s.updateWake();assert.deepEqual(states,[true]);
 let settled=false;const p=vm.runInContext('nextFrame()',s).then(()=>settled=true);
 await Promise.resolve();assert.equal(settled,false);
 s.window.theOneDjBackgroundTick();await p;assert.equal(settled,true);assert.equal(ticks,1);
 callbacks.forEach(f=>f());await Promise.resolve(); // fallback timer must not resolve twice
 s.decks[0].loading=false;await s.updateWake();assert.deepEqual(states,[true,false]);
 s.autoMix=true;s.lib.push({status:'queued'});await s.updateWake();assert.deepEqual(states,[true,false,true]);
 s.mediaRecoveryUntil=Date.now()-1;s.window.theOneDjBackgroundTick();assert.equal(fills,1);assert.equal(s.mediaRecoveryUntil,0);
 s.document.hidden=false;s.lib=[];const before=ticks;s.window.theOneDjBackgroundTick();assert.equal(ticks,before);assert.equal(states.at(-1),false);
 console.log('PASS: service protects loading and queued Auto DJ, hidden native tick releases throttled yields and recovery, foreground avoids duplicate mixer ticks, idle service stops');
})().catch(e=>{console.error(e);process.exit(1)});
