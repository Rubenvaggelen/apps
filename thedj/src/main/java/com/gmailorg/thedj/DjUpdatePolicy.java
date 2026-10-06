package com.gmailorg.thedj;

final class DjUpdatePolicy {
    static int version(String tag){
        if(tag==null||!tag.matches("dj-v[0-9]+"))return 0;
        try{return Integer.parseInt(tag.substring(4));}catch(NumberFormatException e){return 0;}
    }
    static boolean validUrl(int version,String url){
        return version>0&&("https://github.com/Rubenvaggelen/apps/releases/download/dj-v"+version+"/thedj-debug.apk").equals(url);
    }
}
