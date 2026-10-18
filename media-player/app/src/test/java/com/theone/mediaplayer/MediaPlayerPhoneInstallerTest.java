package com.theone.mediaplayer;
import org.junit.Test;
import static org.junit.Assert.*;
public class MediaPlayerPhoneInstallerTest {
 @Test public void acceptsOnlyPrivateHttpsDevHub() {
  assertTrue(MediaPlayerPhoneInstaller.permittedUrl("https://dev.rubenvanaggelen.com/family-download-20261013.php?app=media-player"));
  assertFalse(MediaPlayerPhoneInstaller.permittedUrl("http://dev.rubenvanaggelen.com/family-download-20261013.php?app=media-player"));
  assertFalse(MediaPlayerPhoneInstaller.permittedUrl("https://dev.rubenvanaggelen.com.evil.example/"));
  assertFalse(MediaPlayerPhoneInstaller.permittedUrl("https://example.net/"));
 }
}
