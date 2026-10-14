package com.theone.series;
final class PlaybackRecovery {
 static final long STALL_MS=45000;
 private int retries;private long observedPosition=-1,progressAt;
 void reset(){retries=0;observedPosition=-1;progressAt=0;}
 void progress(long position,long now){if(observedPosition<0||position>observedPosition+1000||position<observedPosition){observedPosition=position;progressAt=now;}}
 boolean stalled(long position,long now){progress(position,now);return now-progressAt>=STALL_MS;}
 long retryDelay(){return retries<3?1500L<<retries++:-1;}
 void stable(){retries=0;}
}
