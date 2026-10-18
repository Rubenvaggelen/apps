package com.theone.mediaplayer;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;

public class MediaFavoritesTest {
    @Test public void sameSourceIdMayRepresentThreeDifferentMediaTypes() {
        List<MediaFavorites.Item> items = Arrays.asList(
            new MediaFavorites.Item("live", "123", "NL - NPO 1", "ts", ""),
            new MediaFavorites.Item("movie", "123", "Film", "mkv", "2024"),
            new MediaFavorites.Item("series", "123", "Serie", "", "")
        );
        List<MediaFavorites.Item> decoded=MediaFavorites.decode(MediaFavorites.encode(items));
        assertEquals(3,decoded.size());
        assertNotEquals(decoded.get(0).key(),decoded.get(1).key());
        assertEquals("Serie",decoded.get(2).label());
    }

    @Test public void secondarySourceIdSurvivesSaveAndRestore() {
        MediaFavorites.Item sourceTwo =
            new MediaFavorites.Item("series","source2:876","Seizoen 1", "", "2025");
        assertTrue(MediaFavorites.valid(sourceTwo));
        List<MediaFavorites.Item> decoded = MediaFavorites.decode(MediaFavorites.encode(Arrays.asList(sourceTwo)));
        assertEquals(1,decoded.size());
        assertEquals("source2:876",decoded.get(0).id);
        assertEquals("series:source2:876",decoded.get(0).key());
    }

    @Test public void invalidEntriesAndDuplicatesAreIgnored() {
        String json="["+
            "{\"kind\":\"live\",\"id\":\"1\",\"name\":\"NL - NPO 1\"},"+
            "{\"kind\":\"live\",\"id\":\"1\",\"name\":\"Dupe\"},"+
            "{\"kind\":\"adult\",\"id\":\"2\",\"name\":\"Adult\"},"+
            "{\"kind\":\"movie\",\"id\":\"https://example.com/token\",\"name\":\"Unsafe\"}"+
        "]";
        List<MediaFavorites.Item> decoded = MediaFavorites.decode(json);
        assertEquals(1, decoded.size());
        assertEquals("NL - NPO 1",decoded.get(0).name);
    }

    @Test public void malformedStorageDoesNotCrash() {
        assertTrue(MediaFavorites.decode("not-json").isEmpty());
        assertTrue(MediaFavorites.decode(null).isEmpty());
    }
}
