package com.theone.cinema.tv;
import java.io.*;import java.util.*;
final class PlaybackPriority {
 private boolean paused;private final Set<Ticket> active=new HashSet<>();
 synchronized Ticket register(Runnable cancel)throws IOException{ensureAllowed();Ticket ticket=new Ticket(cancel);active.add(ticket);return ticket;}
 synchronized void ensureAllowed()throws IOException{if(paused||Thread.currentThread().isInterrupted())throw new IOException("Background work suspended");}
 synchronized boolean isPaused(){return paused;}
 synchronized List<Runnable> pause(){paused=true;List<Runnable> cancellations=new ArrayList<>();for(Ticket ticket:active)cancellations.add(ticket.cancel);active.clear();return cancellations;}
 synchronized void resume(){paused=false;}
 InputStream checked(InputStream input){return new FilterInputStream(input){@Override public int read()throws IOException{ensureAllowed();return in.read();}@Override public int read(byte[] buffer,int offset,int length)throws IOException{ensureAllowed();return in.read(buffer,offset,length);}};}
 final class Ticket implements AutoCloseable {private final Runnable cancel;Ticket(Runnable cancel){this.cancel=cancel;}public void close(){synchronized(PlaybackPriority.this){active.remove(this);}}}
}
