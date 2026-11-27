package io.github.bi2qfa.alphasync.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoRotationDirectionTest {
    @Test fun exifClockwiseTurnIsNotDiscarded() {
        assertEquals(90f, PhotoRotate.rotationDegrees(-1), 0f)
    }
    @Test fun manualCounterClockwiseTurnKeepsDirection() {
        assertEquals(-90f, PhotoRotate.rotationDegrees(1), 0f)
    }
    @Test fun fullRevolutionsLeaveImageUnchanged() {
        for (turns in listOf(0, 4, -4, 8, -8)) assertEquals(0f, PhotoRotate.rotationDegrees(turns), 0f)
    }
    @Test fun repeatedTurnsReduceToEquivalentQuarterTurn() {
        assertEquals(90f, PhotoRotate.rotationDegrees(-5), 0f)
        assertEquals(-90f, PhotoRotate.rotationDegrees(5), 0f)
    }
}
