package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class LivePreviewPriorityTest {
    @Test fun completedPreviewRemovesPriorityWithoutTouchingOtherNewFiles() {
        val priority = LivePreviewPriority<String> { it }
        priority.mark(listOf("done", "waiting"))
        priority.forget("done")
        assertFalse(priority.contains("done"))
        assertTrue(priority.contains("waiting"))
    }

    @Test fun promotionMovesExistingTaskWithoutDuplicatingAndKeepsNewestFirst() {
        val priority = LivePreviewPriority<String> { it }
        val queue = ArrayDeque(listOf("old", "new"))
        val queued = hashSetOf("old", "new")
        priority.mark(listOf("newest", "new"))
        priority.promote(listOf("newest", "new"), queue, queued) { false }
        assertEquals(listOf("newest", "new", "old"), queue.toList())
        assertEquals(3, queued.size)
    }

    @Test fun livePreviewCanRunWhileOldThumbnailsRemain() {
        val priority = LivePreviewPriority<String> { it }
        priority.mark(listOf("new"))
        val thumbs = ArrayDeque(listOf("old"))
        val previews = ArrayDeque(listOf("old", "new"))
        assertNull(priority.poll(thumbs, hashSetOf("old")) { false })
        assertEquals("new", priority.poll(previews, hashSetOf("old", "new")) { false })
        assertEquals(listOf("old"), thumbs.toList())
        assertEquals(listOf("old"), previews.toList())
    }

    @Test fun cachedLiveTasksAreSkippedAndResetRemovesPriority() {
        val priority = LivePreviewPriority<String> { it }
        priority.mark(listOf("cached", "new"))
        val queue = ArrayDeque(listOf("cached", "old", "new"))
        val queued = hashSetOf("cached", "old", "new")
        assertEquals("new", priority.poll(queue, queued) { it == "cached" })
        assertEquals(setOf("old"), queued)
        priority.clear()
        assertFalse(priority.contains("new"))
    }
}
