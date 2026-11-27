package io.github.bi2qfa.alphasync

import io.github.bi2qfa.alphasync.transfer.AutoSyncPolicy
import org.junit.Assert.*
import org.junit.Test

class AutoSyncPolicyTest {
    @Test fun jpgOnlyIncludesJpegAndExcludesRaw() {
        assertTrue(AutoSyncPolicy.accepts("/DCIM/A.JPG", true, false))
        assertTrue(AutoSyncPolicy.accepts("/DCIM/A.JpEg", true, false))
        assertFalse(AutoSyncPolicy.accepts("/DCIM/A.ARW", true, false))
    }
    @Test fun rawOnlyExcludesJpg() {
        assertTrue(AutoSyncPolicy.accepts("/DCIM/A.arw", false, true))
        assertFalse(AutoSyncPolicy.accepts("/DCIM/A.JPG", false, true))
    }
    @Test fun bothOnIncludeBothAndBothOffIncludeNeither() {
        for (path in listOf("A.JPG", "A.ARW")) {
            assertTrue(AutoSyncPolicy.accepts(path, true, true))
            assertFalse(AutoSyncPolicy.accepts(path, false, false))
        }
        assertFalse(AutoSyncPolicy.accepts("A.MP4", true, true))
        assertFalse(AutoSyncPolicy.accepts("A", true, true))
    }
    @Test fun pendingConfirmationIsFastWithoutIncreasingFullCardProbeFrequency() {
        assertEquals(1000L, AutoSyncPolicy.tickInterval(true, true))
        assertEquals(2000L, AutoSyncPolicy.discoveryInterval(true))
        assertEquals(2000L, AutoSyncPolicy.tickInterval(true, false))
        assertEquals(4000L, AutoSyncPolicy.discoveryInterval(false))
    }
}
