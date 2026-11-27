package io.github.bi2qfa.alphasync.ptpip

import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Bytes actually read from camera data sockets, including small protocol headers.
 * Counts partial reads and cancelled transfers, but never local cache reads or phone traffic.
 * Only the phone performs sampling; receiving a chunk performs one atomic addition.
 */
object CameraReceiveMeter {
    private val received = AtomicLong()
    fun totalBytes(): Long = received.get()
    fun input(delegate: InputStream): InputStream = CountingCameraInput(delegate) {
        received.addAndGet(it.toLong())
    }
    fun format(bytesPerSecond: Double): String = when {
        bytesPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.2f MB/s", bytesPerSecond / 1_000_000)
        bytesPerSecond >= 1_000 -> String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1_000)
        else -> String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond.coerceAtLeast(0.0))
    }
}

internal class CountingCameraInput(private val delegate: InputStream, private val count: (Int) -> Unit) : InputStream() {
    override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { if (it > 0) count(it) }
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

/** Three-second rolling average; independent windows permit headers during page transitions. */
internal class ReceiveRateWindow(total: Long, time: Long) {
    private data class Sample(val total: Long, val time: Long)
    private val samples = ArrayDeque<Sample>().apply { add(Sample(total, time)) }
    fun sample(nextTotal: Long, nowNanos: Long): Double {
        val last = samples.last()
        if (nextTotal < last.total || nowNanos <= last.time || nowNanos - last.time > 3_000_000_000L) {
            samples.clear()
            samples.add(Sample(nextTotal, nowNanos))
            return 0.0
        }
        samples.add(Sample(nextTotal, nowNanos))
        val cutoff = nowNanos - 3_000_000_000L
        while (samples.size > 2 && samples.elementAt(1).time <= cutoff) samples.removeFirst()
        val first = samples.first()
        val start = maxOf(first.time, cutoff)
        val second = samples.elementAt(1)
        val fraction = (start - first.time).toDouble() / (second.time - first.time)
        val startTotal = first.total + (second.total - first.total) * fraction
        val elapsed = nowNanos - start
        val bytes = nextTotal - startTotal
        return if (elapsed > 0 && bytes >= 0) bytes * 1_000_000_000.0 / elapsed else 0.0
    }
}
