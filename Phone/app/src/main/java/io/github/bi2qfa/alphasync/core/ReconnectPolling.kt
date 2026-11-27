package io.github.bi2qfa.alphasync.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retry without competing with discovery, pairing or an active handshake. */
internal suspend fun pollReconnect(
    intervalMs: Long,
    connected: () -> Boolean,
    keepTrying: () -> Boolean,
    canAttempt: () -> Boolean,
    attempt: () -> Unit,
) {
    while (!connected() && keepTrying()) {
        delay(intervalMs)
        currentCoroutineContext().ensureActive()
        if (!connected() && keepTrying() && canAttempt()) attempt()
    }
}
