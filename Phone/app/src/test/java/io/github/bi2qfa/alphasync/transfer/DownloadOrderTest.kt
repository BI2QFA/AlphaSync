package io.github.bi2qfa.alphasync.transfer

import org.junit.Assert.*
import org.junit.Test

class DownloadOrderTest {
    private fun item(id: String, priority: Boolean = false, state: TransferState = TransferState.QUEUED) =
        TransferItem(id, "$id.JPG", "/$id.JPG", 100, state = state, priorityDownload = priority)

    @Test fun priorityOvertakesNormalAndRemainsFifo() {
        val items = mutableListOf(item("normal1"), item("normal2"), item("urgent1", true), item("urgent2", true))
        val order = mutableListOf<String>()
        while (true) {
            val next = DownloadOrder.next(items) ?: break
            order += next.id
            items[items.indexOf(next)] = next.copy(state = TransferState.DONE)
        }
        assertEquals(listOf("urgent1", "urgent2", "normal1", "normal2"), order)
    }
    @Test fun runningAndFailedPriorityAreNotPickedOrAllowedToBlockNormals() {
        val normal = item("normal")
        assertEquals(normal, DownloadOrder.next(listOf(item("running", true, TransferState.RUNNING), item("failed", true, TransferState.FAILED), normal)))
    }
    @Test fun onlyAnotherWaitingPriorityPreemptsANormal() {
        val current = item("current", state = TransferState.RUNNING)
        assertTrue(DownloadOrder.shouldPreempt(current, listOf(item("urgent", true))))
        assertFalse(DownloadOrder.shouldPreempt(current, listOf(item("normal"))))
        assertFalse(DownloadOrder.shouldPreempt(current.copy(priorityDownload = true), listOf(item("urgent", true))))
        assertFalse(DownloadOrder.shouldPreempt(current, listOf(item("current", true))))
    }
    @Test fun interruptedNormalRetainsResumeOffsetAndRunsAfterPriority() {
        val normal = item("normal").copy(doneBytes = 65)
        val urgent = item("urgent", true)
        assertEquals(urgent, DownloadOrder.next(listOf(normal, urgent)))
        assertEquals(65L, DownloadOrder.next(listOf(normal, urgent.copy(state = TransferState.DONE)))!!.doneBytes)
    }
}
