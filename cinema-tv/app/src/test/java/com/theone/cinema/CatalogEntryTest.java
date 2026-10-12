package com.theone.cinema;
import org.junit.Test;import static org.junit.Assert.*;
public class CatalogEntryTest {
 @Test public void qualityVariantsShareKey(){CatalogEntry a=new CatalogEntry(1,"Film (2026) 1080p","mp4","","","movies",1);CatalogEntry b=new CatalogEntry(2,"Film (2026) 4K","mp4","","","movies",2);assertEquals(a.key(),b.key());assertTrue(b.rank>a.rank);}
 @Test public void languageVersionsRemainSeparate(){assertNotEquals(CatalogEntry.normalize("Film NL 1080p"),CatalogEntry.normalize("Film EN 1080p"));}
 @Test public void unsafeExtensionFallsBack(){CatalogEntry e=new CatalogEntry(1,"Film","../../bad","","","movies",0);assertEquals("mp4",e.extension);}
 @Test public void filmAndSeriesAreSeparate(){assertNotEquals(new CatalogEntry(1,"Film","mp4","","","movies",0).key(),new CatalogEntry(1,"Film","mp4","","","series",0).key());}
}
