package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class JpgPreviewOrderTest {
    @Test fun jpgPrecedesPairedRawEvenWhenRawHasLaterFileTimestamp() {
        val photos = listOf(
            PhotoWarmupOrder.Photo("/DCIM/new/002.ARW", 202),
            PhotoWarmupOrder.Photo("/DCIM/new/002.JPG", 200),
            PhotoWarmupOrder.Photo("/DCIM/new/001.JPG", 100),
        )
        assertEquals(listOf("/DCIM/new/002.JPG", "/DCIM/new/002.ARW", "/DCIM/new/001.JPG"),
            PhotoWarmupOrder.order(photos, "/DCIM/new", "").map { it.path })
    }
    @Test fun unrelatedJpgDoesNotJumpAheadOfNewRawAndDirectoriesDoNotPair() {
        val paths = listOf("/new/002.ARW", "/old/002.JPG", "/new/001.JPG")
        assertEquals(paths, JpgPreviewOrder.order(paths) { it })
        assertNull(JpgPreviewOrder.preferred(paths[0], paths) { it })
    }
    @Test fun laterRawNotificationCannotOvertakeAlreadyQueuedJpg() {
        val priority = LivePreviewPriority<String> { it }
        val queue = ArrayDeque(listOf("/new/002.JPG"))
        val queued = hashSetOf("/new/002.JPG")
        priority.mark(listOf("/new/002.JPG", "/new/002.ARW"))
        priority.promote(listOf("/new/002.ARW"), queue, queued) { false }
        assertEquals("/new/002.JPG", priority.poll(queue, queued,
            prefer = { item, items -> JpgPreviewOrder.preferred(item, items) { it } }) { false })
        assertEquals(listOf("/new/002.ARW"), queue.toList())
        assertEquals("/new/002.ARW", priority.poll(queue, queued) { false })
    }
    @Test fun missingOrCachedJpgDoesNotBlockRawOrCrossPriorityLevels() {
        val priority = LivePreviewPriority<String> { it }
        val raw = "/new/002.ARW"
        val jpg = "/new/002.JPG"
        priority.mark(listOf(raw, jpg))
        val queue = ArrayDeque(listOf(raw, jpg))
        assertEquals(raw, priority.poll(queue, hashSetOf(raw, jpg),
            prefer = { item, items -> JpgPreviewOrder.preferred(item, items) { it } }) { it == jpg })
        assertEquals(raw, JpgPreviewOrder.order(listOf(raw)) { it }.single())
        priority.clear()
        priority.mark(listOf(raw))
        val otherPriorityQueue = ArrayDeque(listOf(raw, jpg))
        assertEquals(raw, priority.poll(otherPriorityQueue, hashSetOf(raw, jpg),
            prefer = { item, items -> JpgPreviewOrder.preferred(item, items) { it } }) { false })
    }
}
