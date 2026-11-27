package io.github.bi2qfa.alphasync.transfer

import org.junit.Assert.*
import org.junit.Test

class CompleteDirectoryScanTest {
    @Test fun partialDirectoryCannotBecomeBaseline() {
        val scan = CompleteDirectoryScan<String>()
        assertTrue(scan.accept(listOf("old1", "old2"), true, 2))
        assertNull(scan.result()) // Next page failed: do not publish the first page.
    }
    @Test fun allPagesArePublishedTogether() {
        val scan = CompleteDirectoryScan<String>()
        scan.accept(listOf("old1", "old2"), true, 2)
        assertEquals(2, scan.offset)
        scan.accept(listOf("old3"), false, 3)
        assertEquals(listOf("old1", "old2", "old3"), scan.result())
    }
    @Test fun emptyDirectoryIsValid() {
        val scan = CompleteDirectoryScan<String>()
        assertTrue(scan.accept(emptyList(), false, 0))
        assertEquals(emptyList<String>(), scan.result())
    }
    @Test fun NonAdvancingPageIsRejected() {
        val scan = CompleteDirectoryScan<String>()
        scan.accept(listOf("old1"), true, 1)
        assertFalse(scan.accept(listOf("old1"), true, 1))
        assertNull(scan.result())
    }
    @Test fun emptyIntermediatePageCannotHideMissingPhotos() {
        val scan = CompleteDirectoryScan<String>()
        assertFalse(scan.accept(emptyList(), true, 256))
        assertNull(scan.result())
    }
}
