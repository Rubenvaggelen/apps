package com.theone.tv.phone;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FamilyLeaseClockTest {
    @Test public void expiresAfterExactly24Hours() {
        assertTrue(FamilyLeaseClock.isValid(86400000L, 0L, 24, 24));
        assertFalse(FamilyLeaseClock.isValid(86400001L, 0L, 24, 24));
    }
    @Test public void cannotRollbackMonotonicTime() {
        assertFalse(FamilyLeaseClock.isValid(100L, 101L, 24, 24));
    }
    @Test public void rebootFailsClosedWithoutTrustedClock() {
        assertFalse(FamilyLeaseClock.isValid(500L, 300L, 25, 24));
        assertFalse(FamilyLeaseClock.isValid(500L, 300L, -1, -1));
    }
    @Test public void neverApprovedFailsClosed() {
        assertFalse(FamilyLeaseClock.isValid(1000L, -1L, 10, 10));
    }
}
