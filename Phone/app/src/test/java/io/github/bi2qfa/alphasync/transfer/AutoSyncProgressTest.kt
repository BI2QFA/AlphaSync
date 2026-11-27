package io.github.bi2qfa.alphasync.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncProgressTest {
    @Test fun poolAndQueueAreWaitingNotCompletedDownloads() {
        assertEquals(AutoSyncProgress(2, 1, 1, 1), AutoSyncProgress.from(listOf(
            null, TransferState.QUEUED, TransferState.RUNNING,
            TransferState.DONE, TransferState.FAILED,
        )))
    }
    @Test fun emptySessionHasNoDownloads() {
        assertEquals(AutoSyncProgress(0, 0, 0, 0), AutoSyncProgress.from(emptyList()))
    }
}
