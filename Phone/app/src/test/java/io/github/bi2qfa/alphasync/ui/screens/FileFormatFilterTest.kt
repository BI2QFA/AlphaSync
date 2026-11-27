package io.github.bi2qfa.alphasync.ui.screens

import org.junit.Assert.*
import org.junit.Test

class FileFormatFilterTest {
    @Test fun jpegIncludesBothExtensionsIgnoringCase() {
        for (name in listOf("DSC00001.JPG", "image.jpeg", "image.JpEg")) {
            assertTrue(FileFormatFilter.Jpg.accepts(name, false))
            assertFalse(FileFormatFilter.Arw.accepts(name, false))
        }
        assertFalse(FileFormatFilter.Jpg.accepts("image.jpg.arw", false))
    }

    @Test fun rawFilterOnlyAcceptsArwFiles() {
        assertTrue(FileFormatFilter.Arw.accepts("DSC00001.ARW", false))
        assertTrue(FileFormatFilter.Arw.accepts("image.arw", false))
        for (name in listOf("image.jpg", "image.dng", "image.arw.jpg", "ARW")) {
            assertFalse(FileFormatFilter.Arw.accepts(name, false))
        }
    }

    @Test fun directoriesRemainVisibleForEveryFilter() {
        for (filter in FileFormatFilter.entries) {
            assertTrue(filter.accepts("100MSDCF", true))
        }
        assertTrue(FileFormatFilter.All.accepts("movie.mp4", false))
    }
}
