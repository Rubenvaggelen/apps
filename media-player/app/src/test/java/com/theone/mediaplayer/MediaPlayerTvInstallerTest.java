package com.theone.mediaplayer;
import org.junit.Test;
import static org.junit.Assert.*;
public class MediaPlayerTvInstallerTest {
 @Test public void acceptsOnlyDevHubHttps(){
   assertTrue(MediaPlayerTvInstaller.permittedUrl("https://dev.rubenvanaggelen.com/family-download-20261013.php?app=media-player-tv"));
   assertFalse(MediaPlayerTvInstaller.permittedUrl("http://dev.rubenvanaggelen.com/unsafe"));
   assertFalse(MediaPlayerTvInstaller.permittedUrl("https://dev.rubenvanaggelen.com.evil.org/"));
   assertFalse(MediaPlayerTvInstaller.permittedUrl("https://example.net/"));
   assertFalse(MediaPlayerTvInstaller.permittedUrl("https://dev.rubenvanaggelen.com:444/"));
 }
}
