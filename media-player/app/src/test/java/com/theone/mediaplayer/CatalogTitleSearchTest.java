package com.theone.mediaplayer;

import static org.junit.Assert.*;
import org.junit.Test;

public class CatalogTitleSearchTest {
    @Test public void queensMenMatchesStraightApostrophe() {
        assertTrue(CatalogTitleSearch.matches("All the Queen's Men","all the queens men"));
        assertEquals("all the queens men",CatalogTitleSearch.normalize("All the Queen's Men"));
    }

    @Test public void queensMenMatchesCurlyApostrophe() {
        assertTrue(CatalogTitleSearch.matches("All the Queen’s Men","All The Queens Men"));
        assertTrue(CatalogTitleSearch.matches("All the Queen’s Men (2021)","queen's men"));
        assertEquals("all the queens men",CatalogTitleSearch.normalize("All the Queen’s Men"));
    }

    @Test public void insensitiveToCaseAndAccents() {
        assertTrue(CatalogTitleSearch.matches("La Reine d’Amour","la reine damour"));
        assertTrue(CatalogTitleSearch.matches("Élite","elite"));
    }

    @Test public void unrelatedSeriesStayExcluded() {
        assertFalse(CatalogTitleSearch.matches("All the King's Men","all the queens men"));
        assertFalse(CatalogTitleSearch.matches("All the Queen’s Men","all the queen of hearts"));
    }
}
