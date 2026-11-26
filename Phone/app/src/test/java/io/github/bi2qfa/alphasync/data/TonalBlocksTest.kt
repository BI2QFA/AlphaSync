package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class TonalBlocksTest {
    @Test fun allSupportedModesHaveExactNumberOfMonotonicLevels() {
        for (mode in TonalBlocks.Mode.entries) for (levels in TonalBlocks.supportedLevels) {
            val output = (0..255).map { TonalBlocks.map(gray(it), levels, mode) and 255 }
            assertEquals(levels, output.toSet().size)
            assertEquals(0, output.first()); assertEquals(255, output.last())
            assertTrue(output.zipWithNext().all { (a, b) -> a <= b })
        }
    }
    @Test fun cieModeUsesLinearLuminanceAndPerceptualGrayOutput() {
        val mode = TonalBlocks.Mode.CIE_LIGHTNESS
        assertEquals(gray(162), TonalBlocks.map(0xffff0000.toInt(), 4, mode))
        assertEquals(gray(255), TonalBlocks.map(0xff00ff00.toInt(), 4, mode))
        assertEquals(gray(78), TonalBlocks.map(0xff0000ff.toInt(), 4, mode))
        assertEquals(0x80ffffff.toInt(), TonalBlocks.map(0x80ffffff.toInt(), 4, mode))
    }
    @Test fun invalidSavedLevelsFallBackToFour() {
        assertEquals(4, TonalBlocks.validLevels(0))
        assertEquals(4, TonalBlocks.validLevels(7))
        assertEquals(8, TonalBlocks.validLevels(8))
    }
    private fun gray(v: Int) = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
    @Test fun everyGrayValueUsesExpectedBand() {
        for (v in 0..255) assertEquals(gray((v / 64) * 85), TonalBlocks.map(gray(v)))
    }
    @Test fun coloredPixelsUseWeightedBrightness() {
        assertEquals(gray(0), TonalBlocks.map(0xffff0000.toInt()))
        assertEquals(gray(170), TonalBlocks.map(0xff00ff00.toInt()))
        assertEquals(gray(0), TonalBlocks.map(0xff0000ff.toInt()))
    }
    @Test fun transparencyIsPreserved() {
        assertEquals(0x80000000.toInt(), TonalBlocks.map(0x80000000.toInt()))
        assertEquals(0x00ffffff, TonalBlocks.map(0x00ffffff))
    }
    @Test fun mappingNeverMutatesInputOrProducesOtherLevels() {
        val originals = intArrayOf(0xff123456.toInt(), 0xffaabbcc.toInt())
        val snapshot = originals.copyOf()
        for (pixel in originals) {
            val result = TonalBlocks.map(pixel)
            val level = result and 255
            assertTrue(level in listOf(0, 85, 170, 255))
            assertEquals(level, (result ushr 8) and 255)
            assertEquals(level, (result ushr 16) and 255)
        }
        assertArrayEquals(snapshot, originals)
    }
}
