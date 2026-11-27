package io.github.bi2qfa.alphasync.ptpip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DiscoveryReconnectTest {
    @Test
    fun sweepStopsAtFirstRespondingBatchRatherThanProbingEntireSubnet() = kotlinx.coroutines.runBlocking {
        var sends = 0
        val sender = object : DatagramSocket() {
            override fun send(packet: DatagramPacket) { sends++ }
        }
        sender.use {
            with(Discovery) {
                probeInBatches(sender, (1..254).map { "192.168.43.$it" }, byteArrayOf(1)) {
                    true // The first batch produced the target camera's reply.
                }
            }
        }
        assertEquals(8 * Discovery.CANDIDATE_PORTS.size, sends)
    }

    @Test
    fun discoversCameraAfterServerMovesToFallbackPort() {
        // The camera may restart on any of its four PtpIpServer candidate ports.
        val receivers = listOf(15740, 15741, 25740, 25741).map { port ->
            DatagramSocket(null).apply {
                bind(InetSocketAddress("127.0.0.1", port))
                soTimeout = 1000
            }
        }
        try {
            val scope = CoroutineScope(Job())
            val probe = byteArrayOf(1, 2, 3, 4)
            DatagramSocket().use { sender ->
                with(Discovery) { scope.sendTo(sender, listOf("127.0.0.1"), probe) }
            }
            for (receiver in receivers) {
                val packet = DatagramPacket(ByteArray(32), 32)
                receiver.receive(packet)
                assertArrayEquals(probe, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
            }
        } finally {
            receivers.forEach { it.close() }
        }
    }

    @Test
    fun cancellingSweepStopsBeforeSendingMorePackets() {
        val job = Job()
        val scope = CoroutineScope(job)
        var sends = 0
        val sender = object : DatagramSocket() {
            override fun send(packet: DatagramPacket) {
                sends++
                job.cancel()
            }
        }
        sender.use {
            try {
                with(Discovery) {
                    scope.sendTo(sender, (1..254).map { "192.168.43.$it" }, byteArrayOf(1))
                }
                fail("Cancelled sweep must propagate cancellation")
            } catch (_: CancellationException) {
                assertEquals(1, sends)
            }
        }
    }
}
