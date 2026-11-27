package io.github.bi2qfa.alphasync.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewThumbnailPolicyTest {
    @Test fun landscapeKeepsAspectRatio() {
        assertEquals(640 to 427, PreviewThumbnailPolicy.dimensions(1616, 1080))
    }
    @Test fun portraitKeepsOrientation() {
        assertEquals(427 to 640, PreviewThumbnailPolicy.dimensions(1080, 1616))
    }
    @Test fun smallImagesAreNotUpscaled() {
        assertEquals(160 to 120, PreviewThumbnailPolicy.dimensions(160, 120))
    }
    @Test fun extremeAspectRatioNeverProducesZeroDimension() {
        assertEquals(640 to 1, PreviewThumbnailPolicy.dimensions(10000, 1))
    }
}
