package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class ViewportPreviewPriorityTest {
    @Test fun scrollingToOldPhotosReplacesViewportAndKeepsScreenOrder() {
        val priority = LivePreviewPriority<String> { it }
        val queue = ArrayDeque(listOf("new1", "new2", "old2", "old1"))
        val queued = queue.toHashSet()
        priority.mark(listOf("new1", "new2"))
        priority.clear()
        priority.mark(listOf("old1", "old2"))
        priority.promote(listOf("old1", "old2"), queue, queued) { false }
        assertEquals("old1", priority.poll(queue, queued) { false })
        assertEquals("old2", priority.poll(queue, queued) { false })
        assertNull(priority.poll(queue, queued) { false })
        assertEquals(listOf("new1", "new2"), queue.toList())
    }
    @Test fun repeatedViewportUpdatesDoNotDuplicateOrReloadCachedPhotos() {
        val priority = LivePreviewPriority<String> { it }
        val queue = ArrayDeque<String>()
        val queued = hashSetOf<String>()
        repeat(10) {
            priority.clear()
            priority.mark(listOf("cached", "visible"))
            priority.promote(listOf("cached", "visible", "visible"), queue, queued) { it == "cached" }
        }
        assertEquals(listOf("visible"), queue.toList())
        assertEquals(setOf("visible"), queued)
    }
}
