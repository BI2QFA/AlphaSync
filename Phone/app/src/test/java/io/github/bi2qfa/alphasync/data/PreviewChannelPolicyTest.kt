package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class PreviewChannelPolicyTest {
    @Test fun historicalBacklogDoesNotBlockOriginalsAtBatchBoundary() {
        assertFalse(PreviewChannelPolicy.shouldYield(false, false, false, false))
    }
    @Test fun newPhotoPreviewsPreemptOriginals() {
        assertTrue(PreviewChannelPolicy.shouldYield(false, true, false, false))
    }
    @Test fun failedPreviewRetryDelayAllowsOriginalsToProgress() {
        assertFalse(PreviewChannelPolicy.shouldYield(false, true, false, true))
    }
    @Test fun budgetPauseCannotFreezeDownloads() {
        assertFalse(PreviewChannelPolicy.shouldYield(false, true, true, false))
    }
    @Test fun userPreviewHasPriorityEvenDuringAutomaticCooldown() {
        assertTrue(PreviewChannelPolicy.shouldYield(true, true, true, true))
    }
    @Test fun consumedPriorityTokensDoNotKeepDownloadsPaused() {
        val priority = LivePreviewPriority<String> { it }
        priority.mark(listOf("new"))
        assertFalse(priority.hasQueued(setOf("old")))
        assertTrue(priority.hasQueued(setOf("new", "old")))
        priority.forget("new")
        assertFalse(priority.hasQueued(setOf("new", "old")))
    }
}
