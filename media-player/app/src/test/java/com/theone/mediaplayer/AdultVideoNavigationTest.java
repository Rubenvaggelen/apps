package com.theone.mediaplayer;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class AdultVideoNavigationTest {
    @Test public void previousAndNextSelectAdjacentCatalogEntries(){
        assertEquals(0,AdultVideoNavigation.adjacent(1,3,-1));
        assertEquals(2,AdultVideoNavigation.adjacent(1,3,1));
    }
    @Test public void boundariesNeverLoopOrOpenUnfilteredSuggestions(){
        assertEquals(-1,AdultVideoNavigation.adjacent(0,3,-1));
        assertEquals(-1,AdultVideoNavigation.adjacent(2,3,1));
        assertEquals(-1,AdultVideoNavigation.adjacent(-1,3,1));
        assertEquals(-1,AdultVideoNavigation.adjacent(3,3,-1));
        assertEquals(-1,AdultVideoNavigation.adjacent(0,0,1));
        assertEquals(-1,AdultVideoNavigation.adjacent(0,3,0));
    }
    @Test public void singleResultCannotNavigateOutsideSearch(){
        assertEquals(-1,AdultVideoNavigation.adjacent(0,1,-1));
        assertEquals(-1,AdultVideoNavigation.adjacent(0,1,1));
    }
}
