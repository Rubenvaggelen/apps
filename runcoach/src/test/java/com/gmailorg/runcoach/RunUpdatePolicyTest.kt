package com.gmailorg.runcoach
import org.junit.Assert.*
import org.junit.Test
class RunUpdatePolicyTest {
    @Test fun selectsOnlyRunReleases() {
        assertEquals(1011, RunUpdatePolicy.version("run-v1011"))
        listOf("main-v971", "dj-v1011", "run-v1011-beta", "run-v999999999999", "run-v").forEach {
            assertNull(RunUpdatePolicy.version(it))
        }
    }
    @Test fun acceptsOnlyPhoneAssetFromExpectedRelease() {
        assertTrue(RunUpdatePolicy.validUrl(1011, "https://github.com/Rubenvaggelen/apps/releases/download/run-v1011/runcoach-debug.apk"))
        assertFalse(RunUpdatePolicy.validUrl(1011, "https://github.com/Rubenvaggelen/apps/releases/download/run-v1011/runcoach-wear-debug.apk"))
        assertFalse(RunUpdatePolicy.validUrl(1011, "https://github.com/Rubenvaggelen/apps/releases/download/main-v1011/runcoach-debug.apk"))
        assertFalse(RunUpdatePolicy.validUrl(1011, "http://github.com/Rubenvaggelen/apps/releases/download/run-v1011/runcoach-debug.apk"))
    }
}

