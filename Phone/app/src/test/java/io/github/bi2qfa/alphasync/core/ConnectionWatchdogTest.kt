package io.github.bi2qfa.alphasync.core

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionWatchdogTest {
    @Test fun portProbeWorksWithoutSendingProtocolOrPairingMessages() {
        val server = ServerSocket(0)
        val port = server.localPort
        try {
            assertTrue(controlPortReachable("127.0.0.1", port))
            server.accept().use { socket ->
                assertEquals(-1, socket.getInputStream().read())
            }
        } finally { server.close() }
        assertFalse(controlPortReachable("127.0.0.1", port))
    }

    @Test fun temporaryMissesRecoverAndRecentCameraDataPreventsFalseDisconnect() = runBlocking {
        var attempts = 0
        var lost = false
        withTimeout(2_000) {
            watchConnection(intervalMs = 1, minSilenceMs = 6_000,
                probe = { attempts++; attempts == 3 },
                responseAgeMs = { if (attempts < 7) 0 else 10_000 },
                onLost = { lost = true },
            )
        }
        assertTrue(lost)
        assertEquals(7, attempts)
    }
}
