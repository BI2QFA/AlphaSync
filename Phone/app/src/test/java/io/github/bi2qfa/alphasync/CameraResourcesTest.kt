package io.github.bi2qfa.alphasync

import io.github.bi2qfa.alphasync.data.CameraResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraResourcesTest {
    @Test fun unavailableReadingsAreNotZero() {
        val r = CameraResources(-1, -1, -1, -1, false, -1, -1, -1)
        assertEquals("不可用", r.cpuText(r.appCpu10))
        assertEquals("不可用", r.bytesText(r.appRss))
        assertEquals("不可用", r.memoryText())
    }
    @Test fun scopesUsePercentAndMib() {
        val r = CameraResources(500, 25, 10485760, 5242880, true, 1048576, 0, 1048576)
        assertEquals("2.5%", r.cpuText(r.appCpu10))
        assertEquals("1.0 MiB", r.bytesText(r.appRss))
        assertTrue(r.memoryText().endsWith("(50.0%)"))
        assertEquals("0.0 MiB", r.bytesText(r.heapUsed))
    }
}
