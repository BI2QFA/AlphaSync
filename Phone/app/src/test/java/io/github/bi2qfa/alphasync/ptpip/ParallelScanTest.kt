package io.github.bi2qfa.alphasync.ptpip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelScanTest {
    @Test fun savedAddressKeepsRetryingWhileSubnetScanIsStillBusy() = runBlocking {
        var attempts = 0
        var subnetClosed = false
        val result = withTimeout(2_000) {
            scanParallel(
                routes = listOf(suspend {
                    try { awaitCancellation() } finally { subnetClosed = true }
                }),
                firstMatch = true,
                repeatingRoutes = listOf(suspend {
                    attempts++
                    if (attempts == 3) listOf("saved-camera") else emptyList()
                }),
                repeatIntervalMs = 1,
            )
        }
        assertEquals(3, attempts)
        assertEquals(listOf("saved-camera"), result)
        assertTrue(subnetClosed)
    }

    @Test fun finishedSubnetAndEmptySavedProbeDoNotLeaveAnEndlessSearch() = runBlocking {
        val result = withTimeout(2_000) {
            scanParallel(listOf(suspend { emptyList<String>() }), true,
                repeatingRoutes = listOf(suspend { emptyList<String>() }))
        }
        assertTrue(result.isEmpty())
    }

    @Test fun hotspotResultDoesNotWaitForStalledWifiAndClosesOtherRoute() = runBlocking {
        val wifiStarted = CompletableDeferred<Unit>()
        var wifiClosed = false
        val result = withTimeout(2_000) {
            scanParallel(listOf(
                suspend {
                    wifiStarted.await()
                    listOf("hotspot-camera")
                },
                suspend {
                    try {
                        wifiStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        wifiClosed = true
                    }
                },
            ), firstMatch = true)
        }
        assertEquals(listOf("hotspot-camera"), result)
        assertTrue(wifiClosed)
    }

    @Test fun emptyOrFailedRouteDoesNotHideCameraOnOtherNetwork() = runBlocking {
        val result = withTimeout(2_000) {
            scanParallel(listOf(
                suspend { emptyList<String>() },
                suspend { throw java.io.IOException("Wi-Fi unavailable") },
                suspend { listOf("camera") },
            ), firstMatch = true)
        }
        assertEquals(listOf("camera"), result)
    }

    @Test fun manualScanCollectsResultsFromBothNetworks() = runBlocking {
        val result = withTimeout(2_000) {
            scanParallel(listOf(
                suspend { listOf("hotspot-camera") },
                suspend { listOf("wifi-camera") },
            ), firstMatch = false)
        }
        assertEquals(setOf("hotspot-camera", "wifi-camera"), result.toSet())
    }

    @Test fun upstreamBroadcastUsesActualPrefix() {
        assertEquals("192.168.31.255", Discovery.ipv4Broadcast("192.168.31.8", 24))
        assertEquals("192.168.15.255", Discovery.ipv4Broadcast("192.168.9.8", 20))
        assertEquals(null, Discovery.ipv4Broadcast("not-an-ip", 24))
    }
}
