package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test

class PhotoWarmupOrderTest {
    @Test fun dateInFolderNameBeatsLaterModificationOfAnOlderFolderPhoto() {
        val calendar = java.util.GregorianCalendar(2026, 9, 10)
        val time = calendar.timeInMillis
        assertEquals("/DCIM/11461009", PhotoWarmupOrder.latestDirectory(listOf(
            PhotoWarmupOrder.Photo("/DCIM/11361008/edited.JPG", time),
            PhotoWarmupOrder.Photo("/DCIM/11461009/new.JPG", time - 86400000),
        )))
    }

    @Test fun folderDatesHandleNewYearAcrossDecade() {
        val december = java.util.GregorianCalendar(2029, 11, 31).timeInMillis
        val january = java.util.GregorianCalendar(2030, 0, 1).timeInMillis
        assertEquals("/DCIM/10100101", PhotoWarmupOrder.latestDirectory(listOf(
            PhotoWarmupOrder.Photo("/DCIM/10091231/a.JPG", december),
            PhotoWarmupOrder.Photo("/DCIM/10100101/b.JPG", january),
        )))
    }

    @Test fun newestPhotoDeterminesLatestNonemptyDcimFolder() {
        val photos = listOf(
            PhotoWarmupOrder.Photo("/DCIM/11361008/a.JPG", 100),
            PhotoWarmupOrder.Photo("/DCIM/11461009/b.JPG", 200),
            PhotoWarmupOrder.Photo("/OTHER/new.JPG", 999),
        )
        assertEquals("/DCIM/11461009", PhotoWarmupOrder.latestDirectory(photos))
        assertNull(PhotoWarmupOrder.latestDirectory(emptyList()))
    }

    @Test fun latestFolderBeatsViewedFolderAndPhotosFollowTimeRatherThanFilename() {
        val latest = "/DCIM/11461009"
        val viewed = "/DCIM/11361008"
        val photos = listOf(
            PhotoWarmupOrder.Photo("$viewed/z.JPG", 100),
            PhotoWarmupOrder.Photo("$latest/z.JPG", 200),
            PhotoWarmupOrder.Photo("$latest/a.JPG", 300),
            PhotoWarmupOrder.Photo("/DCIM/11261007/a.JPG", 50),
        )
        assertEquals(listOf("$latest/a.JPG", "$latest/z.JPG", "$viewed/z.JPG", "/DCIM/11261007/a.JPG"),
            PhotoWarmupOrder.order(photos, latest, viewed).map { it.path })
    }
}
