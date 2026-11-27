package io.github.bi2qfa.alphasync.data

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** One image stream at a time. A user request waiting at a batch boundary goes first. */
internal class PreviewTransferGate {
    private val gate = Semaphore(1, true)
    private val foreground = AtomicInteger()
    val foregroundPending: Boolean get() = foreground.get() > 0

    fun acquireUninterruptibly() = gate.acquireUninterruptibly()
    fun release() = gate.release()

    /** Downloads wait for the entire preview workload, including gaps between batches. */
    fun withOriginal(shouldYield: () -> Boolean, cancelled: () -> Boolean, block: () -> Unit) {
        while (!cancelled()) {
            if (shouldYield()) {
                Thread.sleep(50)
                continue
            }
            gate.acquireUninterruptibly()
            try {
                if (cancelled()) return
                if (shouldYield()) continue
                block()
                return
            } finally { gate.release() }
        }
    }

    fun <T> withForeground(block: () -> T): T {
        foreground.incrementAndGet()
        gate.acquireUninterruptibly()
        try { return block() }
        finally { gate.release(); foreground.decrementAndGet() }
    }
}
