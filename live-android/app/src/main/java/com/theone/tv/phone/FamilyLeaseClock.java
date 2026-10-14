package com.theone.tv.phone;

final class FamilyLeaseClock {
    static final long MAX_OFFLINE_MILLIS = 24L * 60L * 60L * 1000L;
    private FamilyLeaseClock() {}
    static boolean isValid(long currentElapsed, long approvedElapsed, int currentBoot, int approvedBoot) {
        return currentBoot >= 0 && currentBoot == approvedBoot
            && approvedElapsed >= 0 && currentElapsed >= approvedElapsed
            && currentElapsed - approvedElapsed <= MAX_OFFLINE_MILLIS;
    }
}
