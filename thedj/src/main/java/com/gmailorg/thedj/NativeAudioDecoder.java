package com.gmailorg.thedj;

import android.app.Activity;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Private, on-device audio conversion for formats Web Audio cannot decode. */
final class NativeAudioDecoder {
    private static final long MAX_INPUT=256L*1024*1024, MAX_OUTPUT=512L*1024*1024;
    private final File root;
    private final Map<String,Job> jobs=new HashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private static final class Job {
        File input,output;
        FileOutputStream upload;
        long bytes;
        Future<File> result;
    }
    NativeAudioDecoder(Activity activity) {
        root=new File(activity.getCacheDir(),"dj-audio-decode");
        root.mkdirs();
        File[] stale=root.listFiles();
        if(stale!=null)for(File file:stale)file.delete();
    }
    @JavascriptInterface public synchronized String begin() {
        if(closed||jobs.size()>=2)return "";
        try {
            String id=UUID.randomUUID().toString();
            Job job=new Job();
            job.input=new File(root,id+".source");
            job.output=new File(root,id+".wav");
            job.upload=new FileOutputStream(job.input);
            jobs.put(id,job);
            return id;
        } catch(IOException e){return "";}
    }
    @JavascriptInterface public synchronized boolean append(String id,String chunk) {
        Job job=jobs.get(id);
        if(closed||job==null||job.upload==null||chunk==null||chunk.length()>350000)return false;
        try {
            byte[] bytes=Base64.decode(chunk,Base64.NO_WRAP);
            if(job.bytes+bytes.length>MAX_INPUT)return false;
            job.upload.write(bytes);job.bytes+=bytes.length;return true;
        } catch(Exception e){return false;}
    }
    @JavascriptInterface public synchronized boolean finish(String id) {
        Job job=jobs.get(id);
        if(closed||job==null||job.upload==null||job.bytes==0)return false;
        try {
            job.upload.close();job.upload=null;
            job.result=worker.submit(()-> {
                try {
                    // Paths are generated internally; uploaded media cannot supply FFmpeg arguments.
                    String[] args={"-hide_banner","-loglevel","error","-nostdin","-y",
                        "-protocol_whitelist","file,pipe","-i",job.input.getAbsolutePath(),
                        "-map","0:a:0","-vn","-sn","-dn","-ac","2","-ar","44100",
                        "-c:a","pcm_s16le","-fs",Long.toString(MAX_OUTPUT),
                        "-f","wav",job.output.getAbsolutePath()};
                    if(!ReturnCode.isSuccess(FFmpegKit.executeWithArguments(args).getReturnCode())
                        ||job.output.length()<44||job.output.length()>=MAX_OUTPUT)
                        throw new IOException("Audio decode failed");
                    if(closed)throw new IOException("Decoder closed");
                    return job.output;
                } finally {
                    job.input.delete();
                    if(closed)job.output.delete();
                }
            });
            return true;
        }catch(Exception e){return false;}
    }
    @JavascriptInterface public synchronized void discard(String id) {
        Job job=jobs.remove(id);
        if(job==null)return;
        try{if(job.upload!=null)job.upload.close();}catch(IOException ignored){}
        if(job.result==null||job.result.isDone()){
            job.input.delete();job.output.delete();
        }else {
            // Remove files after the active native conversion completes.
            worker.submit(()->{job.input.delete();job.output.delete();});
        }
    }
    WebResourceResponse intercept(WebResourceRequest request) {
        Uri uri=request.getUrl();
        if(!"https".equals(uri.getScheme())||!"appassets.androidplatform.net".equals(uri.getHost())
            ||uri.getPath()==null||!uri.getPath().startsWith("/native-audio/"))return null;
        String id=uri.getLastPathSegment();
        if(!"GET".equals(request.getMethod())||id==null)return error(400);
        Job job;
        synchronized(this){job=jobs.get(id);}
        if(job==null||job.result==null)return error(404);
        try {
            File file=job.result.get(180,TimeUnit.SECONDS);
            InputStream in=new FilterInputStream(new FileInputStream(file)){
                private boolean released;
                private void release(){if(!released){released=true;discard(id);}}
                @Override public int read() throws IOException {int n=super.read();if(n<0)release();return n;}
                @Override public int read(byte[] b,int o,int l) throws IOException {
                    int n=super.read(b,o,l);if(n<0)release();return n;
                }
                @Override public void close() throws IOException {try{super.close();}finally{release();}}
            };
            Map<String,String> headers=new HashMap<>();
            headers.put("Content-Length",Long.toString(file.length()));
            headers.put("Cache-Control","no-store");
            return new WebResourceResponse("audio/wav",null,200,"OK",headers,in);
        }catch(Exception e){discard(id);return error(422);}
    }
    private WebResourceResponse error(int status) {
        byte[] bytes="{\"error\":\"Audio kon niet worden gedecodeerd\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new WebResourceResponse("application/json","UTF-8",status,"Unavailable",
            Collections.emptyMap(),new ByteArrayInputStream(bytes));
    }
    synchronized void close() {
        closed=true;
        for(Job job:jobs.values()){
            try{if(job.upload!=null)job.upload.close();}catch(IOException ignored){}
            if(job.result==null||job.result.isDone()){job.input.delete();job.output.delete();}
        }
        jobs.clear();worker.shutdown();
    }
}
