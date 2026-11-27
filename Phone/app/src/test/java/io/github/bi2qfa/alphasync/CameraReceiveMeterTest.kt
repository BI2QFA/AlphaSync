package io.github.bi2qfa.alphasync

import io.github.bi2qfa.alphasync.ptpip.CountingCameraInput
import io.github.bi2qfa.alphasync.ptpip.ReceiveRateWindow
import io.github.bi2qfa.alphasync.ptpip.CameraReceiveMeter
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class CameraReceiveMeterTest {
    @Test fun partialBulkAndSingleReadsCountExactlyOnceAndEofDoesNotCount() {
        var counted = 0
        val input = CountingCameraInput(ByteArrayInputStream(ByteArray(10))) { counted += it }
        assertEquals(0, input.read())
        assertEquals(4, input.read(ByteArray(4)))
        assertEquals(5, counted)
        assertEquals(5, input.read(ByteArray(20), 2, 10))
        assertEquals(-1, input.read())
        assertEquals(-1, input.read(ByteArray(2)))
        assertEquals(10, counted)
    }
    @Test fun actualElapsedTimeAndIdleAndResetAreHandled() {
        val window = ReceiveRateWindow(100, 0)
        assertEquals(500.0, window.sample(1100, 2_000_000_000), 0.0001)
        assertEquals(1000.0 / 3, window.sample(1100, 3_000_000_000), 0.0001)
        assertEquals(0.0, window.sample(0, 4_000_000_000), 0.0)
        assertEquals(1000.0, window.sample(1000, 5_000_000_000), 0.0001)
    }
    @Test fun burstIsSmoothedAndExpiresAfterThreeSeconds() {
        val window = ReceiveRateWindow(0, 0)
        assertEquals(3000.0, window.sample(3000, 1_000_000_000), 0.0)
        assertEquals(1500.0, window.sample(3000, 2_000_000_000), 0.0)
        assertEquals(1000.0, window.sample(3000, 3_000_000_000), 0.0)
        assertEquals(0.0, window.sample(3000, 4_000_000_000), 0.0)
        // A long suspension must not include bytes from outside the window.
        assertEquals(0.0, window.sample(9000, 10_000_000_000), 0.0)
    }
    @Test fun headersDoNotConsumeEachOthersSamples() {
        val first = ReceiveRateWindow(0, 0)
        val second = ReceiveRateWindow(0, 0)
        assertEquals(first.sample(100, 1_000_000_000), second.sample(100, 1_000_000_000), 0.0)
        assertEquals("1.00 MB/s", CameraReceiveMeter.format(1_000_000.0))
        assertEquals("1.0 KB/s", CameraReceiveMeter.format(1000.0))
        assertEquals("0 B/s", CameraReceiveMeter.format(0.0))
    }
    @Test fun schedulerJitterStillUsesThreeSecondsRatherThanDroppingAWholeSample() {
        val window = ReceiveRateWindow(0, 0)
        window.sample(3000, 1_010_000_000)
        window.sample(3000, 2_020_000_000)
        val speed = window.sample(3000, 3_030_000_000)
        assertEquals((3000.0 - 3000.0 * 30 / 1010) / 3, speed, 0.0001)
    }
}
