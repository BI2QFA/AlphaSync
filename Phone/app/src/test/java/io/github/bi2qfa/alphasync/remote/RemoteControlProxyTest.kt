package io.github.bi2qfa.alphasync.remote

import java.net.*
import java.io.*
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class RemoteControlProxyTest {
    @Test fun negotiatesCameraGzipUiAndKeepsEncodingHeaderAndBytesTogether() {
        val camera = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val encoded = ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write("<html>Camera UI</html>".toByteArray()) }
        }.toByteArray()
        val server = pool.submit {
            camera.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                assertEquals("GET / HTTP/1.1", reader.readLine())
                var gzip = false
                while (true) {
                    val line = reader.readLine()
                    if (line.isEmpty()) break
                    if (line.equals("Accept-Encoding: gzip", true)) gzip = true
                }
                assertTrue("camera requires gzip negotiation", gzip)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Encoding: gzip\r\nContent-Length: ${encoded.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(encoded); flush()
                }
            }
        }
        try {
            RemoteControlProxy(RemoteControlTarget("127.0.0.1", camera.localPort), null).use { proxy ->
                val connection = URL(proxy.url).openConnection() as HttpURLConnection
                connection.readTimeout = 5000
                assertEquals("gzip", connection.getHeaderField("Content-Encoding"))
                assertArrayEquals(encoded, connection.inputStream.use { it.readBytes() })
                connection.disconnect()
            }
            server.get(5, TimeUnit.SECONDS)
        } finally { camera.close(); pool.shutdownNow() }
    }

    @Test fun forwardsGetRpcPostAndBinaryPreviewOnlyToConfiguredCamera() {
        val camera = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val paths = mutableListOf<String>()
        val requestBodies = mutableListOf<String>()
        val server = pool.submit {
            repeat(3) {
                camera.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    paths += input.readLine()
                    var count = 0
                    while (true) {
                        val line = input.readLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) count = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(count)
                    var read = 0
                    while (read < count) read += input.read(body, read, count - read)
                    requestBodies += String(body)
                    val bytes = byteArrayOf(0, 1, 2, 127, -1)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        try {
            RemoteControlProxy(RemoteControlTarget("127.0.0.1", camera.localPort), null).use { proxy ->
                for (path in listOf("api/state", "api/call", "api/liveview.jpg?t=5")) {
                    val connection = URL(proxy.url + path).openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000; connection.readTimeout = 5000
                    if (path == "api/call") {
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write("{\"method\":\"capture\"}".toByteArray()) }
                    }
                    assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), connection.inputStream.use { it.readBytes() })
                    connection.disconnect()
                }
                val invalid = URL(URL(proxy.url).protocol, "127.0.0.1", URL(proxy.url).port, "/api/state").openConnection() as HttpURLConnection
                assertEquals(403, invalid.responseCode)
                invalid.disconnect()
                assertNull(proxy.resolveDownload("http://example.com/api/photos/latest"))
                assertNull(proxy.resolveDownload(proxy.url + "api/call"))
                assertEquals("http://127.0.0.1:${camera.localPort}/api/photos/latest", proxy.resolveDownload(proxy.url + "api/photos/latest").toString())
            }
            server.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("GET /api/state HTTP/1.1", "POST /api/call HTTP/1.1", "GET /api/liveview.jpg?t=5 HTTP/1.1"), paths)
            assertEquals("{\"method\":\"capture\"}", requestBodies[1])
        } finally { camera.close(); pool.shutdownNow() }
    }
}
