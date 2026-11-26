package io.github.bi2qfa.alphasync.remote

import android.net.Network
import io.github.bi2qfa.alphasync.ptpip.CameraReceiveMeter
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.*

/** A bounded, loopback-only proxy. RPC, event stream and MJPEG use the camera's chosen network. */
internal class RemoteControlProxy(private val target: RemoteControlTarget, private val network: Network?) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val secret = "/${UUID.randomUUID()}"
    val url = "http://127.0.0.1:${server.localPort}$secret/"
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val pool = ThreadPoolExecutor(0, 8, 20, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { job -> Thread(job, "CameraWebProxy").apply { isDaemon = true } })
    @Volatile private var closed = false

    init {
        Thread({
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.add(socket)
                try { pool.execute { handle(socket) } }
                catch (_: RejectedExecutionException) { sockets.remove(socket); socket.close() }
            }
        }, "CameraWebListener").apply { isDaemon = true; start() }
    }

    fun resolveDownload(value: String): URL? = runCatching {
        val local = URI(value)
        val own = URI(url)
        if (local.scheme != own.scheme || local.host != own.host || local.port != own.port ||
            !local.rawPath.startsWith("$secret/api/photos/")) return null
        URL(target.origin + local.rawPath.removePrefix(secret) + (local.rawQuery?.let { "?$it" } ?: ""))
    }.getOrNull()

    fun copyDownload(value: String, output: OutputStream) {
        val destination = resolveDownload(value) ?: throw IOException("无效的相机下载地址")
        val connection = open(destination)
        try {
            connection.readTimeout = 30_000
            CameraReceiveMeter.input(connection.inputStream).use { it.copyTo(output, 32 * 1024) }
        } finally { connections.remove(connection); connection.disconnect() }
    }

    private fun open(url: URL): HttpURLConnection =
        ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            // The camera serves its embedded UI only as gzip. Explicit negotiation keeps
            // HttpURLConnection from transparently changing the bytes/headers we relay.
            setRequestProperty("Accept-Encoding", "gzip")
            connections.add(this)
        }

    private fun handle(socket: Socket) {
        var remote: HttpURLConnection? = null
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val first = readLine(input)?.split(' ') ?: return
            if (first.size < 2) return
            val method = first[0]
            val path = first[1]
            if (method !in setOf("GET", "POST", "HEAD", "OPTIONS") || !path.startsWith("$secret/")) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return
            }
            val headers = hashMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headerBytes > 16_384) throw IOException("请求头过长")
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val relative = path.removePrefix(secret)
            if (relative != "/" && !relative.startsWith("/api/")) throw IOException("不支持的相机路径")
            val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (bodyLength !in 0..262_144 || "transfer-encoding" in headers) throw IOException("请求体不支持")
            remote = open(URL(target.origin + relative))
            remote.requestMethod = method
            headers["content-type"]?.let { remote.setRequestProperty("Content-Type", it) }
            headers["accept"]?.let { remote.setRequestProperty("Accept", it) }
            if (bodyLength > 0) {
                remote.doOutput = true
                remote.setFixedLengthStreamingMode(bodyLength)
                remote.outputStream.use { destination ->
                    var remaining = bodyLength
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n < 0) throw EOFException()
                        destination.write(buffer, 0, n); remaining -= n
                    }
                }
            }
            val status = remote.responseCode
            val responseHeaders = StringBuilder("HTTP/1.1 $status Response\r\nConnection: close\r\nCache-Control: no-store\r\n")
            for (name in listOf("Content-Type", "Content-Encoding", "Content-Length", "Content-Disposition")) {
                remote.getHeaderField(name)?.takeIf { '\r' !in it && '\n' !in it }?.let { responseHeaders.append("$name: $it\r\n") }
            }
            responseHeaders.append("\r\n")
            output.write(responseHeaders.toString().toByteArray(Charsets.ISO_8859_1)); output.flush()
            if (method != "HEAD") {
                val stream = if (status >= 400) remote.errorStream else remote.inputStream
                stream?.let { source -> CameraReceiveMeter.input(source).use {
                    val buffer = ByteArray(32 * 1024)
                    while (!closed) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n); output.flush()
                    }
                } }
            }
        } catch (_: Exception) {
            runCatching { socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        } finally {
            remote?.let { connections.remove(it); it.disconnect() }
            sockets.remove(socket); runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= 8192) {
            val next = input.read()
            if (next < 0) return null
            if (next == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
            bytes.write(next)
        }
        throw IOException("请求行过长")
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        connections.forEach { it.disconnect() }
        pool.shutdownNow()
    }
}
