'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync(__dirname+'/../index.html','utf8');
const a=html.indexOf('async function decodeWithWindowsWma(');
const b=html.indexOf('async function decodeAnyAudio(',a);
assert(a>=0&&b>a,'Windows WMA decoder must exist');
const body=html.slice(a,b);
async function scenario({name,host='127.0.0.1',native=false,ok=true}){
  let calls=0,seen;
  const input=new ArrayBuffer(24),mp3=new ArrayBuffer(12);
  const fakeContext={decodeAudioData:async (data)=>{assert.equal(data,mp3);return {duration:8}}};
  const scope={
    window:{TheOneAudioDecoder:native?{begin:()=>''}:undefined},
    location:{hostname:host},
    fetch:async (url,opts)=>{calls++;seen={url,opts};return {ok,status:ok?200:422,arrayBuffer:async()=>mp3}},
    Error
  };
  vm.createContext(scope);
  vm.runInContext(body,scope);
  const file={name};
  if(!ok){
    await assert.rejects(()=>scope.decodeWithWindowsWma(file,input,fakeContext),/Local WMA decoder failed/);
    assert.equal(calls,1);return;
  }
  const output=await scope.decodeWithWindowsWma(file,input,fakeContext);
  if(name.toLowerCase().endsWith('.wma')&&host==='127.0.0.1'&&!native){
    assert.equal(calls,1);
    assert.equal(seen.url,'/decode-audio');
    assert.equal(seen.opts.method,'POST');
    assert.equal(seen.opts.body,input);
    assert.equal(output.duration,8);
  }else{assert.equal(calls,0);assert.equal(output,null)}
}
(async()=>{
  await scenario({name:'Kaseko.wma'});
  await scenario({name:'NUMMER.WMA'});
  await scenario({name:'Bestaande muziek.mp3'});
  await scenario({name:'Kaseko.wma',host:'example.org'});
  await scenario({name:'Kaseko.wma',native:true});
  await scenario({name:'Kaseko.wma',ok:false});
  console.log('PASS: Windows WMA decoder, MP3 unchanged, non-loopback denied, Android bridge untouched, failures surfaced');
})().catch(e=>{console.error(e);process.exitCode=1});
