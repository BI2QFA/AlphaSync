package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class PhotoHistogramTest {
    @Test fun blackAndWhiteOccupyEndpoints() {
        val h = PhotoHistogram.fromPixels(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()))
        assertEquals(1, h.luminance[0]); assertEquals(1, h.luminance[255])
        assertEquals(2, h.red.sum())
    }
    @Test fun primaryColorsHaveDistinctBrightnessAndChannels() {
        val h = PhotoHistogram.fromPixels(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()))
        assertEquals(1, h.luminance[54]); assertEquals(1, h.luminance[182]); assertEquals(1, h.luminance[18])
        for (c in listOf(h.red, h.green, h.blue)) {
            assertEquals(1, c[255]); assertEquals(2, c[0]); assertEquals(3, c.sum())
        }
    }
    @Test fun transparentPixelsAreExcluded() {
        assertEquals(0, PhotoHistogram.fromPixels(intArrayOf(0x00ffffff)).luminance.sum())
    }
    @Test fun pixelOrderAndRotationDoNotChangeDistribution() {
        val pixels = intArrayOf(0xff123456.toInt(), 0xff808080.toInt(), 0xff123456.toInt())
        val a = PhotoHistogram.fromPixels(pixels)
        val b = PhotoHistogram.fromPixels(pixels.reversedArray())
        assertArrayEquals(a.luminance, b.luminance)
        assertArrayEquals(a.red, b.red); assertArrayEquals(a.green, b.green); assertArrayEquals(a.blue, b.blue)
        assertEquals(3, a.luminance.sum())
    }
}
