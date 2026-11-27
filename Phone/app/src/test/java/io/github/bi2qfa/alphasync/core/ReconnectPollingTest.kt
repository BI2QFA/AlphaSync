package io.github.bi2qfa.alphasync.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPollingTest {
    @Test fun cameraReturningAfterSeveralFailuresReconnectsWithoutUserAction() = runBlocking {
        var attempts = 0
        withTimeout(2_000) {
            pollReconnect(1, { attempts == 4 }, { true }, { true }) { attempts++ }
        }
        assertEquals(4, attempts)
    }

    @Test fun busyDiscoveryIsSkippedAndCancellationStopsRetries() = runBlocking {
        var busy = true
        var attempts = 0
        val skipped = CompletableDeferred<Unit>()
        val tried = CompletableDeferred<Unit>()
        val job = launch {
            pollReconnect(1, { false }, { true }, {
                if (busy) skipped.complete(Unit)
                !busy
            }) {
                attempts++
                tried.complete(Unit)
            }
        }
        withTimeout(2_000) { skipped.await() }
        assertEquals(0, attempts)
        busy = false
        withTimeout(2_000) { tried.await() }
        job.cancelAndJoin()
        val stoppedAt = attempts
        kotlinx.coroutines.delay(10)
        assertEquals(stoppedAt, attempts)
    }

    @Test fun removedPairDoesNotReconnect() = runBlocking {
        pollReconnect(1, { false }, { false }, { true }) {
            throw AssertionError("Unpaired camera must not be contacted")
        }
    }
}
