package io.github.bi2qfa.alphasync.data

import kotlinx.coroutines.delay

/** Release the image channel between attempts so camera writing and prefetch can finish. */
internal suspend fun <T> loadPreviewWithRetry(
    maxAttempts: Int,
    retryDelayMs: Long = 1500,
    windowMs: Long = 15000,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    canRetry: () -> Boolean = { true },
    load: suspend () -> T?,
): T? {
    val began = nowMs()
    repeat(maxAttempts) { attempt ->
        load()?.let { return it }
        if (attempt == maxAttempts - 1 || !canRetry() || nowMs() - began + retryDelayMs >= windowMs) return null
        delay(retryDelayMs)
    }
    return null
}
