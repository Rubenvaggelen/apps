package com.theone.series;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class BootstrapCatalogTest {
 private InputStream row(String type,String title){return new ByteArrayInputStream(("{\"id\":42,\"title\":\""+title+"\",\"type\":\""+type+"\"}").replaceFirst("^","[").concat("]").getBytes(StandardCharsets.UTF_8));}
 @Test public void starterLoadsWithoutNetwork()throws Exception{List<CatalogEntry> rows=BootstrapCatalog.read(row("series","EN - Test"),"series");assertEquals(42,rows.get(0).id);assertEquals("en",rows.get(0).languageCode());}
 @Test public void adultEntryCannotEnterStarter()throws Exception{try{BootstrapCatalog.read(row("series","[XXX] Test"),"series");fail();}catch(IOException expected){}}
 @Test public void catalogTypesRemainSeparate()throws Exception{try{BootstrapCatalog.read(row("movies","EN - Test"),"series");fail();}catch(IOException expected){}}
}
