package io.github.bi2qfa.alphasync.core

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** TCP reachability does not queue behind camera directory requests or claim a PTP session. */
internal fun controlPortReachable(host: String, port: Int, factory: SocketFactory? = null): Boolean =
    runCatching {
        (factory?.createSocket() ?: Socket()).use { socket ->
            socket.connect(InetSocketAddress(host, port), 700)
        }
        true
    }.getOrDefault(false)

internal suspend fun watchConnection(
    intervalMs: Long = 1_500,
    minSilenceMs: Long = 6_000,
    maxMisses: Int = 3,
    probe: suspend () -> Boolean,
    responseAgeMs: () -> Long,
    onLost: () -> Unit,
) {
    var misses = 0
    while (true) {
        delay(intervalMs)
        val reachable = probe()
        currentCoroutineContext().ensureActive()
        misses = if (reachable) 0 else misses + 1
        if (misses >= maxMisses && responseAgeMs() >= minSilenceMs) {
            onLost()
            return
        }
    }
}
