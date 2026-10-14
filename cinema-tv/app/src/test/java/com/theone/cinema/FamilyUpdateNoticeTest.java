package com.theone.cinema;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FamilyUpdateNoticeTest {
    @Test public void newerPatchVersionIsDetected() {
        assertTrue(FamilyUpdateNotice.compareVersions("1.1.7", "1.1.4") > 0);
    }
    @Test public void multidigitVersionComponentsUseNumericComparison() {
        assertTrue(FamilyUpdateNotice.compareVersions("1.3.10", "1.3.9") > 0);
    }
    @Test public void equalAndOlderVersionsAreNotOffered() {
        assertEquals(0, FamilyUpdateNotice.compareVersions("1.0.3", "1.0.3"));
        assertTrue(FamilyUpdateNotice.compareVersions("1.0.2", "1.0.3") < 0);
    }
    @Test public void invalidVersionLabelsAreRejected() {
        assertTrue(FamilyUpdateNotice.compareVersions("1.0.3-debug", "1.0.2") < 0);
    }
}
