package com.theone.cinema.tv;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class BootstrapCatalogTest {
 private InputStream input(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
 @Test public void starterLoadsWithoutNetworkAndKeepsStableSourceIds()throws Exception{List<CatalogEntry> rows=BootstrapCatalog.read(input("[{\"id\":42,\"title\":\"EN - Test\",\"type\":\"movies\",\"poster\":\"https://example.org/p.jpg\"}]"),"movies");assertEquals(42,rows.get(0).id);assertEquals("en",rows.get(0).languageCode());assertEquals("https://example.org/p.jpg",rows.get(0).poster);}
 @Test public void adultEntryCannotAppearInStarter()throws Exception{try{BootstrapCatalog.read(input("[{\"id\":42,\"title\":\"[XXX] Test\",\"type\":\"movies\"}]"),"movies");fail();}catch(IOException expected){}}
 @Test public void incorrectCatalogTypeIsRejected()throws Exception{try{BootstrapCatalog.read(input("[{\"id\":42,\"title\":\"EN - Test\",\"type\":\"series\"}]"),"movies");fail();}catch(IOException expected){}}
}
