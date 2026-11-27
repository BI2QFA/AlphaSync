package io.github.bi2qfa.alphasync.ptpip

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import io.github.bi2qfa.alphasync.data.IdentityRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.math.min

/**
 * 相机发现（UDP Probe，方案 §3/§4）——**取代旧 `protocol.Scanner` 的 TCP 2122 探活**。
 *
 * 相机端把 UDP 探测套接字绑在**协议端口**上（`DatagramSocket.bind(protoPort)`），
 * 收到 `Probe Request` 后：
 * - 请求的友好名带厂商标记（`... AlphaSync/2.0`）→ 回**扩展应答**，
 *   含真实 protoPort / filePort / pairingMode / paired；
 * - 否则回**紧凑应答**（保第三方标准客户端互操作）。
 *
 * 因此本端发探测时必须把友好名写成 `<本机名> AlphaSync/2.0`，
 * 才能拿到端口与配对状态。
 *
 * 扫描策略（三段，都在毫秒级）：
 * <ol>
 *   <li><b>已知地址快扫</b>：配对表里记过的相机 IP + 网关 + 本机 IP，每个地址发一个
 *       探测包，一个短窗口。相机（尤其热点/Direct 模式）地址基本固定，绝大多数
 *       情况下这一步就打中 —— 一进软件就能看到设备。</li>
 *   <li><b>广播补扫</b>：①没命中才发（子网广播 + 受限广播），几百毫秒出结果。</li>
 *   <li><b>整段单播兜底</b>：广播被 AP 过滤时才走 —— 这条很慢，见 scan 的注释。</li>
 * </ol>
 * 同时探测相机端支持的四个协议端口，避免服务重启后使用备用端口时无法发现。
 */
object Discovery {

    private const val TAG = "Discovery"

    /** 与相机端 PtpIpServer.PROTO_PORT_CANDIDATES 保持一致，一次发送、统一收包。 */
    val CANDIDATE_PORTS = intArrayOf(15740, 15741, 25740, 25741)

    /** 全子网补扫的等待窗口。 */
    private const val SWEEP_TIMEOUT_MS = 420L

    /** 已知地址快扫的等待窗口：直连 Wi-Fi 的往返是个位数毫秒，不必等满。 */
    private const val FAST_TIMEOUT_MS = 260L

    /** 静默期：收到过应答后，连续这么久没有新应答就提前收工。 */
    private const val QUIET_MS = 150L

    /** 接收阻塞粒度（要明显小于 [QUIET_MS]，静默判定才灵敏）。 */
    private const val RECV_SLICE_MS = 50

    /** 一台被发现的相机。 */
    data class DiscoveredCamera(
        val host: String,
        /** 实得协议端口（扩展应答回报）。 */
        val protoPort: Int,
        /** 实得文件端口。 */
        val filePort: Int,
        val cameraGuid16: ByteArray,
        /** 相机友好名（已去掉厂商标记尾）。 */
        val name: String,
        /** 是否处于配对模式（供手机端筛选"可配对"相机）。 */
        val pairingMode: Boolean,
    ) {
        val guidHex: String get() = PtpCodec.hex(cameraGuid16)

        override fun equals(other: Any?): Boolean =
            other is DiscoveredCamera && cameraGuid16.contentEquals(other.cameraGuid16)

        override fun hashCode(): Int = cameraGuid16.contentHashCode()
    }

    /**
     * 扫描子网内所有处于 AlphaSync 相机状态的设备。
     *
     * @param boundNetwork   相机网络（热点无互联网标记，绑上防流量甩到蜂窝）
     * @param preferredHosts 配对表里记过的相机地址，排在最前面快扫
     * @param thorough       手动"扫描设备"时传 true：**不做早退**，老老实实扫完
     *   整个子网 —— 快扫命中一台也照跑广播 + 整段单播两遍（10-01 修复：
     *   "只能扫描到一个"的根因就是快扫命中后广播被短路）。
     *   自动扫描要的是"最快的那个设备"，手动扫描要的是"列全"——
     *   若手动也早退，第二台相机（比如新买的那台、还没配对）就永远看不见了。
     * @param sweep          没命中已知地址时要不要补扫整个 /24。**这一扫很贵**：
     *   往 251 个没人应答的地址逐个发包（端口收成单个之前实测是 1004 个包 / **9.3 秒**，
     *   发送本身阻塞，见下），而"快扫已知地址"只要 0.3 秒。所以：
     *   - 相机地址没变的常规路径（配对表里有它的 IP）走 `sweep=false`，一次 0.3 秒；
     *   - 只有"找那台地址变了的相机 / 找从没见过的相机"才值得付这个代价。
     *   ⚠ 端口收成 15740 单个之后，这条路的包数降到约四分之一，但**新的耗时没实测过** ——
     *     别把上面那个 9.3 秒当成收窄后的数字引用。
     */
    /** Each route owns a socket and result map. A slow upstream Wi-Fi cannot delay hotspot discovery. */
    suspend fun scan(
        context: Context,
        boundNetwork: Network? = null,
        preferredHosts: List<String> = emptyList(),
        thorough: Boolean = false,
        sweep: Boolean = true,
        targetGuid: String? = null,
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val networks = runCatching {
            cm?.allNetworks.orEmpty().filter { network ->
                cm?.getNetworkCapabilities(network)?.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_WIFI,
                ) == true
            }
        }.getOrDefault(emptyList())
        val wifiNetworks = (listOfNotNull(boundNetwork) + networks).distinct()
        val visibleBroadcasts = localSubnetBroadcasts()
        val (localIp, gateway) = wifiEndpoints(context)
        val request = PtpCodec.probe(
            request = true,
            guid = IdentityRepo.guid16(),
            friendlyName = IdentityRepo.friendlyName + " " + PtpCodec.VENDOR_TAG,
            protoPort = 0, filePort = 0, pairingMode = false, paired = false,
        )
        // System routing reaches hotspot clients; never bind this socket to upstream Wi-Fi.
        val rawRoute = ScanRoute(
            network = null,
            known = (preferredHosts + if (wifiNetworks.isEmpty()) listOfNotNull(gateway, localIp) else emptyList()).distinct(),
            broadcasts = (preferredHosts.mapNotNull { subnetBroadcastOf(it) } + visibleBroadcasts +
                tetheringBroadcasts() + "255.255.255.255").distinct(),
            subnetHosts = (preferredHosts + visibleBroadcasts +
                if (wifiNetworks.isEmpty()) listOfNotNull(localIp, gateway) else emptyList())
                .map { subnetOf(it) }.filter { it.isNotEmpty() }.distinct().flatten().distinct(),
        )
        val wifiRoutes = wifiNetworks.map { network ->
            val properties = runCatching { cm?.getLinkProperties(network) }.getOrNull()
            val addresses = properties?.linkAddresses.orEmpty().filter {
                it.address is java.net.Inet4Address
            }
            val ips = addresses.mapNotNull { it.address.hostAddress }
            val gateways = properties?.routes.orEmpty().mapNotNull {
                (it.gateway as? java.net.Inet4Address)?.hostAddress
            }.filter { it != "0.0.0.0" }
            ScanRoute(
                network = network,
                known = (preferredHosts + gateways + ips).distinct(),
                broadcasts = (addresses.mapNotNull { link ->
                    ipv4Broadcast(link.address.hostAddress, link.prefixLength)
                } + "255.255.255.255").distinct(),
                subnetHosts = ips.flatMap { subnetOf(it) }.distinct(),
            )
        }
        val routes = listOf(rawRoute) + wifiRoutes
        val started = System.currentTimeMillis()
        val results = scanParallel(routes.map { route ->
            suspend { scanRoute(route, request, thorough, sweep, targetGuid, if (thorough) emptyList() else preferredHosts) }
        }, firstMatch = !thorough,
            repeatingRoutes = if (thorough || preferredHosts.isEmpty()) emptyList() else routes.map { route ->
                suspend { scanSavedRoute(route.network, preferredHosts, request, targetGuid) }
            },
        ).distinctBy { it.guidHex }
        Log.d(TAG, "并行扫描 ${routes.size} 条路径，命中 ${results.size} 台，耗时 ${System.currentTimeMillis() - started} ms")
        results
    }

    private data class ScanRoute(
        val network: Network?,
        val known: List<String>,
        val broadcasts: List<String>,
        val subnetHosts: List<String>,
    )

    private suspend fun scanRoute(
        route: ScanRoute,
        request: ByteArray,
        thorough: Boolean,
        sweep: Boolean,
        targetGuid: String?,
        savedHosts: List<String>,
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredCamera>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = RECV_SLICE_MS
                socket.broadcast = true
                // Failure to bind must not silently scan the wrong network.
                route.network?.bindSocket(socket)
                // Saved addresses are probed separately; this worker handles LAN discovery.
                // Each LAN path receives between batches and retains its own socket.
                sendTo(socket, route.known.filterNot { it in savedHosts }, request)
                collect(socket, found, FAST_TIMEOUT_MS, stopOnFirst = !thorough, targetGuid = targetGuid)
                if (thorough || found.isEmpty()) {
                    sendTo(socket, route.broadcasts, request)
                    collect(socket, found, FAST_TIMEOUT_MS, stopOnFirst = !thorough, targetGuid = targetGuid)
                }
                if (sweep && (thorough || found.isEmpty())) {
                    probeInBatches(socket, route.subnetHosts.filterNot { it in route.known }, request) {
                        collect(socket, found, 50L, stopOnFirst = !thorough, targetGuid = targetGuid)
                        !thorough && found.isNotEmpty()
                    }
                    if (thorough || found.isEmpty()) {
                        collect(socket, found, SWEEP_TIMEOUT_MS, stopOnFirst = !thorough, targetGuid = targetGuid)
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.d(TAG, "扫描路径 ${route.network ?: "热点/系统路由"} 失败：${failure.message}")
        }
        found.values.toList()
    }

    /** A saved-IP probe has its own socket, independent of long subnet sweeps. */
    private suspend fun scanSavedRoute(
        network: Network?,
        hosts: List<String>,
        request: ByteArray,
        targetGuid: String?,
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredCamera>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = RECV_SLICE_MS
                network?.bindSocket(socket)
                sendTo(socket, hosts, request)
                collect(socket, found, FAST_TIMEOUT_MS, stopOnFirst = true, targetGuid = targetGuid)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.d(TAG, "保存地址探测失败：${failure.message}")
        }
        found.values.toList()
    }

    internal fun ipv4Broadcast(host: String?, prefix: Int): String? {
        if (host == null || prefix !in 1..30) return null
        val octets = host.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val address = octets.fold(0L) { value, octet -> (value shl 8) or octet.toLong() }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val broadcast = address or (mask.inv() and 0xffffffffL)
        return listOf(24, 16, 8, 0).joinToString(".") { ((broadcast shr it) and 255).toString() }
    }

    /**
     * 往这些主机各发一个探测包。
     *
     * <p>循环 `CANDIDATE_PORTS` 的写法留着（现在只有一个元素）：将来若真需要多端口，
     * 这里天然支持"一次全发、只等一个窗口"，不用改结构。**别改成按端口顺序轮流等** ——
     * 那会把等待窗口乘以端口数。
     */
    internal fun CoroutineScope.sendTo(sock: DatagramSocket, hosts: Collection<String>, request: ByteArray) {
        for (h in hosts) {
            for (port in CANDIDATE_PORTS) {
                ensureActive()
                runCatching {
                    sock.send(DatagramPacket(request, request.size, InetSocketAddress(h, port)))
                }
            }
        }
    }

    /** Drain responses between small batches instead of sending an entire /24 before reading. */
    internal suspend fun CoroutineScope.probeInBatches(
        sock: DatagramSocket,
        hosts: Collection<String>,
        request: ByteArray,
        receivedTarget: suspend () -> Boolean,
    ) {
        for (batch in hosts.toList().chunked(8)) {
            ensureActive()
            sendTo(sock, batch, request)
            if (receivedTarget()) return
        }
    }

    /**
     * 广播探测：往子网广播地址与受限广播地址各发一个包（共 2 个）。
     *
     * ★ 为什么值得这么做：相机端的探测口绑的是**通配地址**（`bind(port)`），
     *   广播包它收得到，于是"整段 /24 逐个单播"那 251 个包可以省掉 ——
     *   那一条在这台手机上要 9.3 秒，且是纯等待。广播这一轮几百毫秒。
     *   ⚠ 广播可能被 AP 过滤，所以调用方**保留单播扫作兜底**（见 scan 的 ③）。
     */
    private fun CoroutineScope.sendBroadcast(sock: DatagramSocket, ip: String?, request: ByteArray) {
        runCatching { sock.broadcast = true }
        val targets = LinkedHashSet<String>()
        subnetBroadcastOf(ip)?.let { targets.add(it) }
        targets.add("255.255.255.255")
        sendTo(sock, targets, request)
    }

    /** `192.168.31.7` → `192.168.31.255`。非 IPv4 返回 null（发现本来只走 IPv4）。 */
    private fun subnetBroadcastOf(ip: String?): String? {
        val p = ip?.split(".") ?: return null
        if (p.size != 4) return null
        return p[0] + "." + p[1] + "." + p[2] + ".255"
    }

    /**
     * 收包窗口。
     *
     * @param stopOnFirst 已知地址快扫用 true —— 那本来就是"找我们认得的那台"，
     *   一旦命中就没必要再等（多台相机同时在的场景交给②列全）
     * @param windowMs    最长等待；收到过应答后进入静默期，连续 [QUIET_MS] 无新应答即收工
     */
    private suspend fun CoroutineScope.collect(
        sock: DatagramSocket,
        foundByGuid: MutableMap<String, DiscoveredCamera>,
        windowMs: Long,
        stopOnFirst: Boolean,
        targetGuid: String? = null,
    ) {
        val deadline = System.currentTimeMillis() + windowMs
        var lastHit = 0L
        val buf = ByteArray(1500)
        while (isActive && System.currentTimeMillis() < deadline) {
            val pkt = DatagramPacket(buf, buf.size)
            val got = try {
                sock.receive(pkt)
                true
            } catch (e: Exception) {
                false   // 超时/被关：走下面的静默判定
            }
            if (!got) {
                if (lastHit != 0L && System.currentTimeMillis() - lastHit >= QUIET_MS) {
                    return
                }
                continue
            }
            val raw = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val cam = parse(raw, pkt.address?.hostAddress ?: continue) ?: continue
            if (targetGuid != null && cam.guidHex != targetGuid) continue
            foundByGuid[cam.guidHex] = cam
            lastHit = System.currentTimeMillis()
            if (stopOnFirst) return
        }
    }

    /** 解析一条探测应答；非 AlphaSync 相机（无厂商标记）返回 null。 */
    internal fun parse(raw: ByteArray, fromHost: String): DiscoveredCamera? = try {
        val m = PtpCodec.read(raw.inputStream())
        if (m == null || m.type != PtpCodec.T_PROBE_RESP) {
            null
        } else {
            val pr = PtpCodec.parseProbe(m.body)
            // ★ 必须带厂商标记才算我们的相机，天然防误报（与旧 TCP 探活的用途一致）
            if (!PtpCodec.hasVendorTag(pr.name)) {
                null
            } else {
                // 把应答里的**原始字段**记下来：排查"扫到了但列表是空的"这类问题时，
                // 一眼就能看出是相机报的 pairingMode=false，还是本端解析错了。
                Log.d(
                    TAG,
                    "应答 from=$fromHost name=${pr.name} proto=${pr.protoPort} " +
                        "file=${pr.filePort} pairingMode=${pr.pairingMode} paired=${pr.paired}",
                )
                DiscoveredCamera(
                    host = fromHost,
                    protoPort = if (pr.protoPort > 0) pr.protoPort else CANDIDATE_PORTS[0],
                    filePort = pr.filePort,
                    cameraGuid16 = pr.guid,
                    name = pr.name.removeSuffix(" " + PtpCodec.VENDOR_TAG).trim(),
                    pairingMode = pr.pairingMode,
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    // ============================================================
    // 子网推导（与旧 Scanner 同源；旧协议下线后那侧一并删除）
    // ============================================================

    private fun wifiEndpoints(context: Context): Pair<String?, String?> = try {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo
        if (dhcp == null) {
            null to null
        } else {
            formatIp(dhcp.ipAddress) to formatIp(dhcp.gateway)
        }
    } catch (e: Exception) {
        null to null
    }

    /** 本机 IP → /24 全量地址（不含 .0 与 .255）。 */
    private fun subnetOf(localIp: String?): List<String> {
        if (localIp == null) return emptyList()
        val parts = localIp.split(".")
        if (parts.size != 4) return emptyList()
        val b = min(254, parts[2].toIntOrNull() ?: return emptyList())
        val prefix = "${parts[0]}.${parts[1]}.$b."
        return (1..254).map { prefix + it }
    }

    /**
     * **手机自己做热点**时的探测目标（用户实测：这种接法下手机端扫不到相机）。
     *
     * <p>病根：[wifiEndpoints] 取的是 `WifiManager.dhcpInfo`，那是**station 侧**的
     * DHCP 信息 —— 手机做热点时它要么是上一次连别人热点的陈旧值、要么是 0，
     * 于是"已知地址快扫/子网广播//24 兜底"三个目标全算错；再加上探测 socket 被
     * `bindSocket` 绑在 Wi-Fi 网络上，走热点的包根本收不到。结果就是扫不到。
     *
     * <p>解法刻意**不依赖热点网卡**（`ap0`/`wlan1` 这类接口对应用不可见）：
     * 直接往"常见热点网段的**广播地址**"发包。内核按**直连路由**把它们从热点网卡
     * 送出去（与默认路由无关），相机作为热点客户端收得到；它回包时发给本机在热点上的
     * 地址，而我们那个**未绑定网络**的 socket 监听所有网卡，所以收得到。
     * 每多一个网段只多一个几十字节的包，代价可忽略。
     */
    private fun tetheringBroadcasts(): List<String> = listOf(
        // AOSP 默认热点网段（绝大多数机型，含小米/红米实测值）
        "192.168.43.255",
        // 厂商常用的另外几个：42/44 段、以及把热点放在 0/1 段的机型
        "192.168.42.255",
        "192.168.44.255",
        "192.168.0.255",
        "192.168.1.255",
        "192.168.2.255",
        "10.0.0.255",
    )

    /**
     * 本机**所有网卡的 IPv4 子网广播地址**（含热点网卡，若它对应用可见）。
     * 与 [tetheringBroadcasts] 互补：那条兜底"网卡不可见"，这条兜底"网段不是常见值"。
     */
    private fun localSubnetBroadcasts(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { ia ->
                val a = ia.address
                if (a is java.net.Inet4Address) ia.broadcast?.hostAddress else null
            }
            .filter { it != null && it.isNotBlank() }
    } catch (e: Throwable) {
        emptyList()
    }

    /** Android DHCP 的 int 地址是小端：低字节在前。 */
    fun formatIp(ip: Int): String? {
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
