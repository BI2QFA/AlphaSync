package io.github.bi2qfa.alphasync.ptpip

import org.junit.Assert.*
import org.junit.Test

class OriginalTransferSegmentsTest {
    @Test fun resumedRangesCoverRemainingFileWithoutOverlap() {
        val size = 81_600_003L
        val resume = 127_013L
        var offset = resume
        var received = 0L
        while (offset < size) {
            val length = OriginalTransferSegments.length(size, offset)
            assertTrue(length in 1..(2 * 1024 * 1024))
            offset += length
            received += length
        }
        assertEquals(size, offset)
        assertEquals(size - resume, received)
        assertEquals(0, OriginalTransferSegments.length(size, offset))
    }
    @Test fun largeFilesDoNotOverflowRangeLength() {
        assertEquals(2 * 1024 * 1024, OriginalTransferSegments.length(Long.MAX_VALUE, 1))
        assertEquals(7, OriginalTransferSegments.length(Long.MAX_VALUE, Long.MAX_VALUE - 7))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsResumeBeyondEnd() { OriginalTransferSegments.length(123, 124) }
}
