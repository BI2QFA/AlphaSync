package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class LocalPhotoDecodePolicyTest {
    @Test fun highResolutionCameraImageKeepsMoreDetail() {
        val (w, h) = LocalPhotoDecodePolicy.dimensions(7952, 5304)
        assertEquals(4096, w)
        assertTrue(h > 2700)
        assertTrue(w.toLong() * h <= 12_000_000)
    }
    @Test fun smallPhotoIsNotUpscaled() {
        assertEquals(1200 to 1800, LocalPhotoDecodePolicy.dimensions(1200, 1800))
    }
    @Test fun squareAndPortraitRespectBudgetAndAspectRatio() {
        for ((w, h) in listOf(8000 to 8000, 5304 to 7952, 10000 to 1000)) {
            val (a, b) = LocalPhotoDecodePolicy.dimensions(w, h)
            assertTrue(maxOf(a, b) <= 4096)
            assertTrue(a.toLong() * b <= 12_000_000)
            // Integer output dimensions may differ from the ideal aspect by less than one pixel.
            assertTrue(kotlin.math.abs(a.toLong() * h - b.toLong() * w) < maxOf(w, h).toLong())
        }
    }
    @Test fun invalidBoundsRemainSafe() {
        assertEquals(1 to 1, LocalPhotoDecodePolicy.dimensions(0, -1))
    }
}
