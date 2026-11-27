package io.github.bi2qfa.alphasync.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteControlTargetTest {
    @Test fun acceptsCameraAddressesAndExplicitPort() {
        assertEquals(RemoteControlTarget("192.168.43.100"), RemoteControlTarget.parse("192.168.43.100"))
        assertEquals(RemoteControlTarget("192.168.1.10", 80), RemoteControlTarget.parse(" http://192.168.1.10:80/ "))
    }
    @Test fun rejectsMalformedAndNonCameraTargets() {
        for (value in listOf("", "file:///sdcard/photo", "https://192.168.1.1", "user@192.168.1.1", "192.168.1.1/api/call", "256.1.1.1", "192.168.1.1:0", "192.168.1.1:65536", "192.168.1", "192.168.1.1:8080:2")) {
            assertNull(value, RemoteControlTarget.parse(value))
        }
    }
}
