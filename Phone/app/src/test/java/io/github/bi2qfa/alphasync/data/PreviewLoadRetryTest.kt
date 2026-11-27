package io.github.bi2qfa.alphasync.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewLoadRetryTest {
    @Test fun previewBecomesAvailableAfterInitialFailures() = runBlocking {
        var attempts = 0
        val image = loadPreviewWithRetry(8, retryDelayMs = 0) {
            attempts++
            if (attempts == 3) "jpeg" else null
        }
        assertEquals("jpeg", image)
        assertEquals(3, attempts)
    }
    @Test fun successDoesNotRepeatDownload() = runBlocking {
        var attempts = 0
        assertEquals("cached", loadPreviewWithRetry(8, retryDelayMs = 0) { attempts++; "cached" })
        assertEquals(1, attempts)
    }
    @Test fun disconnectStopsRetries() = runBlocking {
        var attempts = 0
        assertNull(loadPreviewWithRetry<String>(8, retryDelayMs = 0, canRetry = { false }) { attempts++; null })
        assertEquals(1, attempts)
    }
    @Test fun slowFailureExhaustsRetryWindow() = runBlocking {
        var clock = 0L
        var attempts = 0
        assertNull(loadPreviewWithRetry<String>(8, retryDelayMs = 0, windowMs = 100,
            nowMs = { clock }) { attempts++; clock += 100; null })
        assertEquals(1, attempts)
    }
}
