package com.gmailorg.thedj;

import org.junit.Test;
import static org.junit.Assert.*;

public class DjUpdatePolicyTest {
    @Test public void onlyIndependentAndroidDjTags(){
        assertEquals(1049,DjUpdatePolicy.version("dj-v1049"));
        for(String tag:new String[]{"main-v977","run-v1012","dj-windows-v240","v1049","dj-v1049-beta","dj-v999999999999"})assertEquals(0,DjUpdatePolicy.version(tag));
    }
    @Test public void onlyExactDjApkAsset(){
        String base="https://github.com/Rubenvaggelen/apps/releases/download/dj-v1049/";
        assertTrue(DjUpdatePolicy.validUrl(1049,base+"thedj-debug.apk"));
        assertFalse(DjUpdatePolicy.validUrl(1049,base+"app-debug.apk"));
        assertFalse(DjUpdatePolicy.validUrl(1048,base+"thedj-debug.apk"));
        assertFalse(DjUpdatePolicy.validUrl(1049,base.replace("github.com","github.com.evil.example")+"thedj-debug.apk"));
    }
}
