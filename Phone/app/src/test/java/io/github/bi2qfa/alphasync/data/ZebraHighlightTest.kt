package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class ZebraHighlightTest {
    @Test fun everyThresholdMatchesPercentageBoundary() {
        for (threshold in ZebraHighlight.thresholds) {
            val boundary = (threshold * 255 + 99) / 100
            assertEquals(0, ZebraHighlight.overlay(gray(boundary - 1), 0, 0, 8, threshold))
            assertNotEquals(0, ZebraHighlight.overlay(gray(boundary), 0, 0, 8, threshold))
        }
    }
    @Test fun invalidSavedThresholdFallsBackAndEveryModeHasHelp() {
        assertEquals(95, ZebraHighlight.validThreshold(0))
        for (threshold in ZebraHighlight.thresholds) {
            assertEquals(threshold, ZebraHighlight.validThreshold(threshold))
            assertTrue(ZebraHighlight.usage(threshold).isNotBlank())
        }
    }
    private fun gray(v: Int) = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
    @Test fun thresholdUsesPreviewLuminance() {
        assertEquals(0, ZebraHighlight.overlay(gray(242), 0, 0, 8))
        assertNotEquals(0, ZebraHighlight.overlay(gray(243), 0, 0, 8))
        assertNotEquals(0, ZebraHighlight.overlay(gray(255), 0, 0, 8))
    }
    @Test fun stripesAlternateDiagonally() {
        val pixel = gray(255)
        assertEquals(ZebraHighlight.overlay(pixel, 8, 0, 8), ZebraHighlight.overlay(pixel, 0, 8, 8))
        assertNotEquals(ZebraHighlight.overlay(pixel, 0, 0, 8), ZebraHighlight.overlay(pixel, 8, 0, 8))
        assertEquals(ZebraHighlight.overlay(pixel, 0, 0, 8), ZebraHighlight.overlay(pixel, 16, 0, 8))
    }
    @Test fun darkAndTransparentPixelsHaveNoOverlay() {
        assertEquals(0, ZebraHighlight.overlay(gray(0), 0, 0, 8))
        assertEquals(0, ZebraHighlight.overlay(0x00ffffff, 0, 0, 8))
        assertEquals(0, ZebraHighlight.overlay(0xffff0000.toInt(), 0, 0, 8))
    }
}
