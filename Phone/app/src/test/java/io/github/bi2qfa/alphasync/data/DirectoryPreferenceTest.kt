package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class DirectoryPreferenceTest {
    @Test fun normalizesSavedDirectoryAndRejectsTraversal() {
        assertEquals("/DCIM/100MSDCF", DirectoryPreference.normalize("/DCIM//100MSDCF/"))
        for (path in listOf(null, "DCIM", "/DCIM/../x", "/DCIM/./x")) {
            assertEquals("/", DirectoryPreference.normalize(path))
        }
    }
    @Test fun prioritizesOnlyFilesDirectlyInTheSelectedDirectory() {
        assertTrue(DirectoryPreference.containsFile("/DCIM/100MSDCF/", "/DCIM/100MSDCF/a.JPG"))
        assertFalse(DirectoryPreference.containsFile("/DCIM/100MSDCF", "/DCIM/100MSDCF2/a.JPG"))
        assertFalse(DirectoryPreference.containsFile("/DCIM", "/DCIM/100MSDCF/a.JPG"))
        assertTrue(DirectoryPreference.containsFile("/", "/a.JPG"))
    }
}
