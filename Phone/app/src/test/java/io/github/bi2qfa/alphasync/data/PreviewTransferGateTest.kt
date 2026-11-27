package io.github.bi2qfa.alphasync.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PreviewTransferGateTest {
    @Test fun originalStaysPausedBetweenPreviewBatchesAndThenResumes() {
        val gate = PreviewTransferGate()
        val previews = java.util.concurrent.atomic.AtomicBoolean(true)
        val waiting = CountDownLatch(1)
        val originalEntered = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit {
                gate.withOriginal(
                    shouldYield = { waiting.countDown(); previews.get() },
                    cancelled = { false },
                ) { originalEntered.countDown() }
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            repeat(2) {
                gate.acquireUninterruptibly()
                gate.release()
                // The stream is idle between batches but preview work has not finished.
                assertFalse(originalEntered.await(75, TimeUnit.MILLISECONDS))
            }
            previews.set(false)
            task.get(2, TimeUnit.SECONDS)
            assertEquals(0L, originalEntered.count)
        } finally { previews.set(false); pool.shutdownNow() }
    }

    @Test fun cancellingDownloadWhileWaitingDoesNotStartAnotherSegment() {
        val gate = PreviewTransferGate()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiting = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit {
                gate.withOriginal(
                    shouldYield = { waiting.countDown(); true },
                    cancelled = { cancelled.get() },
                ) { fail("cancelled download started a segment") }
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            task.get(2, TimeUnit.SECONDS)
            gate.acquireUninterruptibly()
            gate.release()
        } finally { cancelled.set(true); pool.shutdownNow() }
    }

    @Test fun manualPreviewWaitsForBatchAndReleasesGateOnFailure() {
        val gate = PreviewTransferGate()
        val started = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        gate.acquireUninterruptibly()
        try {
            val task = pool.submit {
                started.countDown()
                try {
                    gate.withForeground {
                        entered.countDown()
                        throw IllegalStateException("decode failed")
                    }
                } catch (_: IllegalStateException) { }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(entered.await(50, TimeUnit.MILLISECONDS))
            assertTrue(gate.foregroundPending)
            gate.release()
            task.get(2, TimeUnit.SECONDS)
            assertEquals(0L, entered.count)
            assertFalse(gate.foregroundPending)
            gate.acquireUninterruptibly()
            gate.release()
        } finally { pool.shutdownNow() }
    }
}
