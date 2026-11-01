package com.theone.series;
final class PlaybackSessionGuard {
 private static final long HOUR_MS=3600000L,WINDOW_MS=120000L;
 private PlaybackSessionGuard(){}
 static boolean suspectedHourlyCutoff(long positionMs,long connectionAgeMs){return nearHour(positionMs)||nearHour(connectionAgeMs);}
 private static boolean nearHour(long x){return x>=HOUR_MS-WINDOW_MS&&x<=HOUR_MS+WINDOW_MS;}
 static long retryPosition(long x){return Math.max(0L,x-5000L);}
 static long safeProgress(long current,long saved,boolean unstable){long p=Math.max(0,current);return unstable&&p<10000?Math.max(p,Math.max(0,saved)):p;}
}