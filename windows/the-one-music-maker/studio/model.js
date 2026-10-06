(function(root){
'use strict';
const finite=(v,d=0)=>Number.isFinite(Number(v))?Number(v):d;
function cleanClip(t,duration){
 const trimStart=Math.min(Math.max(0,finite(t.trimStart)),duration);
 const trimEnd=Math.min(duration,Math.max(trimStart,finite(t.trimEnd,duration)));
 return {...t,start:Math.max(0,finite(t.start)),trimStart,trimEnd,gain:Math.min(2,Math.max(0,finite(t.gain,1))),pan:Math.min(1,Math.max(-1,finite(t.pan))),fadeIn:Math.max(0,finite(t.fadeIn)),fadeOut:Math.max(0,finite(t.fadeOut)),mute:!!t.mute,solo:!!t.solo};
}
function plan(t,position){
 const length=t.trimEnd-t.trimStart,end=t.start+length;
 if(length<=0||position>=end)return null;
 const elapsed=Math.max(0,position-t.start);
 return {delay:Math.max(0,t.start-position),offset:t.trimStart+elapsed,duration:length-elapsed,elapsed,length};
}
function audible(tracks){const solo=tracks.some(t=>t.solo);return tracks.filter(t=>!t.mute&&(!solo||t.solo));}
function fadeAt(t,elapsed,length){return Math.max(0,Math.min(1,t.fadeIn>0?elapsed/t.fadeIn:1,t.fadeOut>0?(length-elapsed)/t.fadeOut:1));}
function envelope(t,p){
 const points=[p.elapsed,p.length];
 if(t.fadeIn>p.elapsed&&t.fadeIn<p.length)points.push(t.fadeIn);
 if(t.fadeOut>0){const x=p.length-t.fadeOut;if(x>p.elapsed&&x<p.length)points.push(x);}
 if(t.fadeIn+t.fadeOut>p.length&&t.fadeIn>0&&t.fadeOut>0){const x=p.length*t.fadeIn/(t.fadeIn+t.fadeOut);if(x>p.elapsed&&x<p.length)points.push(x);}
 return [...new Set(points)].sort((a,b)=>a-b).map(x=>({time:x-p.elapsed,value:fadeAt(t,x,p.length)*t.gain}));
}
function wav(buffer){
 const n=buffer.length,channels=buffer.numberOfChannels,bytes=new ArrayBuffer(44+n*channels*2),v=new DataView(bytes);
 const str=(at,s)=>{for(let i=0;i<s.length;i++)v.setUint8(at+i,s.charCodeAt(i));};
 str(0,'RIFF');v.setUint32(4,36+n*channels*2,true);str(8,'WAVE');str(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,channels,true);v.setUint32(24,buffer.sampleRate,true);v.setUint32(28,buffer.sampleRate*channels*2,true);v.setUint16(32,channels*2,true);v.setUint16(34,16,true);str(36,'data');v.setUint32(40,n*channels*2,true);
 const data=Array.from({length:channels},(_,c)=>buffer.getChannelData(c));let at=44;
 for(let i=0;i<n;i++)for(let c=0;c<channels;c++){const x=Math.max(-1,Math.min(1,data[c][i]));v.setInt16(at,Math.round(x<0?x*32768:x*32767),true);at+=2;}
 return bytes;
}
const api={cleanClip,plan,audible,fadeAt,envelope,wav};if(typeof module!=='undefined')module.exports=api;else root.MusicModel=api;
})(globalThis);

