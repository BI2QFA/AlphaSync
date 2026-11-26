package io.github.bi2qfa.alphasync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoLightRatioTest {
    @Test
    fun linearizesSrgbBeforeAveraging() {
        val middleGray = PhotoLightRatio.meanLuminance(intArrayOf(0xff808080.toInt()))!!
        assertEquals(0.21586, middleGray, 0.00001)
        val mixed = PhotoLightRatio.meanLuminance(intArrayOf(0xff000000.toInt(), -1, 0))!!
        assertEquals(0.5, mixed, 0.00001)
    }

    @Test
    fun comparesInBrighterToDarkerOrder() {
        val result = PhotoLightRatio.compare(0.125, 0.5)!!
        assertEquals('B', result.brighter)
        assertEquals(4.0, result.ratio, 0.00001)
        assertEquals(2.0, result.stops, 0.00001)
        assertEquals(1.0, PhotoLightRatio.compare(0.5, 0.5)!!.ratio, 0.00001)
        assertNull(PhotoLightRatio.compare(0.5, 0.0))
        assertNull(PhotoLightRatio.meanLuminance(intArrayOf(0)))
    }
}
