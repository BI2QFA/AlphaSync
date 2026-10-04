package io.github.bi2qfa.alphasync.ptpip

import android.net.Network
import android.util.Log
import io.github.bi2qfa.alphasync.data.IdentityRepo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 对象访问层（PTP/IP 版）—— **取代 `ftp/FtpRepository`**。
 *
 * 对外 API 形状刻意与 FTP 版保持一致（`FtpEntry` / `list` / `fetchVirtualPreview` /
 * `PumpResult` / `pumpToStream` / `joinPath` / `parentOf` / `breadcrumbOf`），
 * 使 UI 与下载服务只需把 `FtpRepository.` 换成 `ObjectRepository.`，
 * 再补一个 `connect(...)` 的相机参数即可迁移。
 *
 * 内部机制：
 * - 控制面：长连接 [PtpIpClient]（已认证会话 + 事件连接），按 host 登记在 [sessions]；
 * - 数据面：每次对象传输新建 [DataChannel] 走**独立文件端口**，用完即关；
 * - 元数据（LIST_DIR / STAT / PING / DEVICE_INFO）：内联在控制面响应里（方案 §4.5）。
 *
 * ## LIST_DIR 的 blob 契约（厂商 JSON，UTF-8）
 * 相机端 `Handler.listDir()` 须返回如下结构（`dir` 冗余回显便于排障）：
 * ```json
 * {"dir":"/DCIM","entries":[
 *   {"name":"100MSDCF","dir":true,"size":0,"mtime":0},
 *   {"name":"DSC00001.JPG","dir":false,"size":5242880,"mtime":1757500000000}
 * ]}
 * ```
 * 为兼容早期实现，也接受**裸数组** `[{...},{...}]`。条目只回 `name`，
 * 完整路径由本层用 [joinPath] 拼出（与 FTP `LIST` 语义一致）。
 */
object ObjectRepository {

    private const val TAG = "ObjectRepository"

    /** 建连接超时（含握手 + 双向认证）。 */
    private const val CONNECT_TIMEOUT_MS = 5000

    /**
     * 配对超时。比建连接宽松：配对里有一次 PBKDF2 两万轮（两端各算一次），
     * 相机那边的单核弱 CPU 可能要几百毫秒，再加上用户刚输完码时的网络抖动。
     */
    private const val PAIR_TIMEOUT_MS = 20_000

    /** 控制面请求超时；缩略图队列等慢操作另计。 */
    private const val IO_TIMEOUT_MS = 30_000

    /**
     * 交互翻页（文件页 load/loadMore）的 listPage 超时（F6）。
     *
     * <p>相机端真分页（每页 ≤256 项、只对切片 stat）后单页通常几百 ms；
     * 15s 只为兜住"控制面排队 + 冷缓存首触"的极端情况 —— 旧值 8s 在
     * 大目录 + 弱机上必吃穿（"文件特别多时目录读取失败"的直接机制之一）。
     */
    private const val LIST_TIMEOUT_MS = 15_000

    /**
     * 后台**全树扫描**（预热名单收集 / 传输前目录收集）的 listPage 超时（F6）。
     * 扫描不抢用户交互的时延预算，给足余量。
     */
    const val SCAN_LIST_TIMEOUT_MS = 30_000

    /**
     * [dirCount] 的探针 offset（2.8）：必超任何真实目录的条目数，让相机端把
     * start 钳制到 files.length —— 回包就是"空 entries + nextOffset=总数"。
     */
    private const val DIR_COUNT_PROBE_OFFSET = 1_000_000_000

    /**
     * 手机连相机热点（无互联网标记）时，不绑定就可能把流量甩到蜂窝。
     * 由 ConnectionCenter 在连上前写入，等价于 FTP 版 `boundNetwork`。
     */
    @Volatile
    var boundNetwork: Network? = null

    /**
     * **控制面**执行器（单线程）：目录 / 元数据 / 心跳 / 队列控制这些轻量往返走这里。
     *
     * 相机按 deviceId 识别 initiator，同一 deviceId 的**第二条控制连接会踢掉前一条**
     * （§9.4），所以控制面必须串行 —— 本层所有控制请求都走同一条控制连接。
     * （大对象的搬运不在这个线程上，见 [xferExecutor]。）
     */
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PtpIpIo").apply { isDaemon = true }
    }

    /**
     * **数据面**执行器（单线程）：整对象搬运（原图下载 / 缩略图 / 大预览）走这里。
     *
     * ★ 与控制面分成两条线程是**必须的**，不是性能取舍：一个 25 MB 的对象在这条线程上
     *   要跑几十秒，两者合一时用户"传着文件点开文件页"会看到界面转圈到底、什么都刷不出来
     *   —— 因为 `list()` 一直排在对象后面（用户实测反馈"传输文件时点击文件页面无法加载
     *   文件"）。分开之后控制面永远有空，传输期间目录照读、心跳照跳。
     *
     * ★ 数据面自己仍保持**单线程**：[pumpToStream]（ORIGINAL 大文件续传）在这条
     *   线程上跑 —— 它要"stat 校验 + 换令牌 + 落盘"三步同拍，串行是正确性要求。
     *   （取预览/小图的令牌**不走**这里，见 [openObjectTicket] 的说明。）
     */
    private val xferExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PtpIpXfer").apply { isDaemon = true }
    }

    /** 一台相机的已认证会话。 */
    class Session internal constructor(
        val host: String,
        val protoPort: Int,
        val client: PtpIpClient,
    ) : Closeable {
        val guid16: ByteArray get() = client.cameraGuid16
        val cameraName: String get() = client.cameraName
        override fun close() = client.close()
    }

    /** [pair] 的结果。 */
    enum class PairOutcome {
        /** 握手/验码失败（码不对、相机没开配对窗口、网络不通等）。 */
        FAILED,

        /** 相机被占用：另一台在配对中，或已连着别的设备。 */
        BUSY,

        /** 正常配对：相机刚把本机记进它的配对表，6 位码已被核对过。 */
        PAIRED,
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * 控制连接在读线程发现 EOF/IOException 时立即上报。只移除“仍是同一个
     * client 实例”的会话，避免旧连接的迟到回调误删刚建立的新会话。
     */
    private val disconnectListeners = ConcurrentHashMap<String, () -> Unit>()

    fun setOnDisconnectedListener(host: String, listener: (() -> Unit)?) {
        if (listener == null) disconnectListeners.remove(host)
        else disconnectListeners[host] = listener
    }

    private fun installDisconnectCallback(host: String, client: PtpIpClient, session: Session) {
        client.onDisconnected = callback@{
            val current = sessions[host] ?: return@callback
            if (current !== session || current.client !== client) return@callback
            Log.d(TAG, "control disconnected: $host")
            // ★ F3：控制面已断 → 批量数据通道随即作废（它可能还"socket 未关"，
            //   但会话已经不在了；复用它会撞相机端的静默拒绝）
            dropBatchChannel()
            sessions.remove(host, session)
            disconnectListeners[host]?.invoke()
        }
    }

    private fun socketFactoryOrNull() =
        runCatching { boundNetwork?.socketFactory }.getOrNull()

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * [connect] 的结果。
     *
     * 区分原因不是洁癖：三种失败要对用户说三种不同的话，而且 NOT_PAIRED 还要**动本地
     * 数据**（清掉那条过期记录，否则 UI 会因为"本地认为已配对"而永远只给"连接"按钮，
     * 用户怎么点都连不上）。
     */
    enum class ConnectOutcome {
        OK,

        /** 相机不认识本机设备码 —— 相机侧已解除配对（或本机记录是旧的）。清本地记录。 */
        NOT_PAIRED,

        /** 相机正被占用：另一台手机在配对中，或已连着别的设备。 */
        BUSY,

        /** 其它失败：网络不通、握手异常、超时。 */
        FAILED,
    }

    /**
     * 建立并认证一条到相机的会话。幂等：同 host 已连接直接返回 true。
     *
     * @param onEvent 事件回调（缩略图进度等），可为 null；在事件读线程触发，勿做重活
     * @return [ConnectOutcome]；网络类失败不抛
     */
    fun connect(
        host: String,
        protoPort: Int,
        guid16: ByteArray,
        friendlyName: String,
        onEvent: PtpIpClient.EventListener? = null,
    ): ConnectOutcome {
        sessions[host]?.let { if (it.client.isConnected) return ConnectOutcome.OK else disconnect(host) }
        return try {
            ioExecutor.submit<ConnectOutcome> {
                // 协议代次（握手报给相机的那个版本号）：排障时一眼能看出是哪一版在说话
                Log.d(TAG, "连接 " + host + "：本机协议版本=" +
                    PtpIpClient.formatProtoVersion(PtpIpClient.PROTO_VERSION))
                val client = PtpIpClient(
                    host = host,
                    protoPort = protoPort,
                    guid16 = guid16,
                    friendlyName = friendlyName,
                    socketFactory = socketFactoryOrNull(),
                )
                try {
                    client.connect(CONNECT_TIMEOUT_MS)
                    if (onEvent != null) {
                        // 与 connect() 同一写法：没给回调就不开（本类不假设调用方一定给）
                        runCatching { client.openEventChannel(onEvent, CONNECT_TIMEOUT_MS) }
                    }
                    val session = Session(host, protoPort, client)
                    sessions[host] = session
                    installDisconnectCallback(host, client, session)
                    ConnectOutcome.OK
                } catch (e: InitFailedException) {
                    // 相机在 Init 阶段就把门关上了 —— 拒绝码正是它想告诉我们的话
                    Log.d(TAG, "connect rejected: reason=0x${Integer.toHexString(e.reason)}")
                    runCatching { client.close() }
                    when (e.reason) {
                        PtpCodec.FAIL_NOT_PAIRED -> ConnectOutcome.NOT_PAIRED
                        PtpCodec.FAIL_BUSY -> ConnectOutcome.BUSY
                        else -> ConnectOutcome.FAILED
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "connect failed: ${e.javaClass.simpleName} ${e.message}")
                    runCatching { client.close() }
                    ConnectOutcome.FAILED
                }
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "connect submit failed: ${e.message}")
            ConnectOutcome.FAILED
        }
    }


    fun sessionOf(host: String): Session? = sessions[host]
    fun isConnected(host: String): Boolean = sessions[host]?.client?.isConnected == true

    // ============================================================
    // 两阶段配对（相机端待机不亮码，必须分两步）
    // ============================================================
    //
    // 用户定版流程：
    //   ① 相机进配对待机（**不显示码**，只声明配对状态、等手机）；
    //   ② 手机扫描到它 → 发 PAIR_BEGIN → **配对连接建立**；
    //   ③ 相机收到 PAIR_BEGIN **这时才生成并显示配对码**；
    //   ④ 用户看着相机屏输码 → 手机发 PAIR_EXCHANGE → 配对完成。
    //
    // 所以手机端**不能**像原来那样"连上就立刻把码交过去" —— 那时的码还没生成。
    // 必须把连接**留着**，等用户看完相机屏再发第二步。这个"留着的连接"就是
    // pairingLink 指向的那条 client。

    /**
     * 进行中的配对连接（已发 PAIR_BEGIN、等用户输码）。
     *
     * <p>读写跨线程：`pairBegin` 在 ioExecutor 上写、`pairAbort` 在调用方线程上
     * **取走**、`pairExchange` 在调用方线程上读。所以引用本身 @Volatile，并且
     * 所有"读引用 / 置空"都用 [pairingLock] 包成原子操作 —— 否则
     * `pairAbort` 与 `pairBegin` 交错时会漏掉刚建好的那条连接（连接没人管、
     * 相机端配对窗口一直被占），或者 abort 帧与 exchange 帧在同一条连接上交错。
     * **锁内只做引用赋值/取值，绝不做网络 IO**。
     */
    @Volatile
    private var pairingLink: PtpIpClient? = null

    /** 只保护 [pairingLink] / [pairingHost] 这两个引用的读与写，临界区是纯粹的赋值/取值。 */
    private val pairingLock = Any()

    /** 进行中的配对目标相机。 */
    @Volatile
    var pairingHost: String? = null
        private set

    /**
     * 在锁内清掉配对连接引用；**仅当当前指向的正是 [client] 时才清** ——
     * 避免清掉后来新建的那一条（收尾的旧连接不该动新连接的状态）。
     */
    private fun clearPairingLink(client: PtpIpClient) {
        synchronized(pairingLock) {
            if (pairingLink === client) {
                pairingLink = null
                pairingHost = null
            }
        }
    }

    /**
     * **第一步**：连上相机并发 `PAIR_BEGIN`，使相机亮出配对码；连接**保持打开**。
     *
     * @return [PairOutcome]：PAIRED = 相机已亮码、可以输码了；BUSY / FAILED 见枚举
     */
    fun pairBegin(
        host: String,
        protoPort: Int,
        guid16: ByteArray,
        friendlyName: String,
    ): PairOutcome {
        pairAbort()   // 上一次没收尾的配对连接先清掉，避免两条并存
        sessions[host]?.let { if (it.client.isConnected) disconnect(host) }
        return try {
            ioExecutor.submit<PairOutcome> {
                val client = PtpIpClient(
                    host = host,
                    protoPort = protoPort,
                    guid16 = guid16,
                    friendlyName = friendlyName,
                    socketFactory = socketFactoryOrNull(),
                )
                try {
                    client.connectForPairing(PAIR_TIMEOUT_MS)
                    PairingClient(
                        sendReq = { op, tx, blob -> client.sendRaw(PtpCodec.opReqBlob(op, tx, blob)) },
                        recvRsp = { client.recvPlainOpBlob() },
                    ).begin(friendlyName)
                    synchronized(pairingLock) {
                        pairingLink = client
                        pairingHost = host
                    }
                    PairOutcome.PAIRED
                } catch (e: PairingClient.DeviceBusyException) {
                    Log.d(TAG, "pairBegin busy: ${e.message}")
                    runCatching { client.close() }
                    PairOutcome.BUSY
                } catch (e: Exception) {
                    Log.d(TAG, "pairBegin failed: ${e.javaClass.simpleName} ${e.message}")
                    runCatching { client.close() }
                    PairOutcome.FAILED
                }
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "pairBegin submit failed: ${e.message}")
            PairOutcome.FAILED
        }
    }

    /**
     * **第二步**：把用户看着相机屏输入的码交给相机核对；成功后这条连接转为正式会话。
     *
     * @return [PairOutcome]，失败时内部已 abort 并关掉配对连接
     */
    fun pairExchange(host: String, code: String, onEvent: PtpIpClient.EventListener? = null): PairOutcome {
        // 取引用要在锁内：本方法与 pairBegin 的写入、pairAbort 的取走并发时，
        // 必须保证读到的是"当前那一条"，且不会读到写了一半的状态。
        val client = synchronized(pairingLock) {
            val c = pairingLink
            if (c == null || pairingHost != host) null else c
        }
        if (client == null) {
            Log.d(TAG, "pairExchange 没有进行中的配对连接（host=$host）")
            return PairOutcome.FAILED
        }
        return try {
            ioExecutor.submit<PairOutcome> {
                try {
                    PairingClient(
                        sendReq = { op, tx, blob -> client.sendRaw(PtpCodec.opReqBlob(op, tx, blob)) },
                        recvRsp = { client.recvPlainOpBlob() },
                    ).exchange(code)
                    clearPairingLink(client)
                    // 配对期结束 → 起控制读线程，把这条连接转成正式会话
                    client.finishPairing(onEvent)
                    // 再开事件通道（相机主动推事件那条链路）。★ 与 connect() 一致：
                    // 少了它，相机侧的"解除配对/切换方式/退出/断连声明"全都收不到。
                    if (onEvent != null) {
                        runCatching { client.openEventChannel(onEvent, CONNECT_TIMEOUT_MS) }
                    }
                    val session = Session(host, client.protoPort, client)
                    sessions[host] = session
                    installDisconnectCallback(host, client, session)
                    PairOutcome.PAIRED
                } catch (e: Exception) {
                    Log.d(TAG, "pairExchange failed: ${e.javaClass.simpleName} ${e.message}")
                    runCatching { client.close() }
                    clearPairingLink(client)
                    PairOutcome.FAILED
                }
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "pairExchange submit failed: ${e.message}")
            PairOutcome.FAILED
        }
    }

    /**
     * 放弃进行中的配对连接（用户取消输码、超时、离开配对页）。
     *
     * <p>会尽力发一条 `PAIR_ABORT`：相机端收到就**立刻退回待机**（收起配对码），
     * 不用等它自己发现连接断了。
     */
    fun pairAbort() {
        // "取走引用 + 置空"必须在同一个临界区里完成：分两步做的话，与 pairBegin
        // 的赋值交错时会漏掉刚刚建好的那条连接 —— 连接没人管、相机端配对窗口
        // 一直被占，直到下一次配对才被清掉。
        val client = synchronized(pairingLock) {
            val c = pairingLink
            pairingLink = null
            pairingHost = null
            c
        } ?: return
        // 网络 IO（ABORT 帧 + 关闭）一律丢给 ioExecutor：既不在锁内做，也不占用
        // 调用方线程（通常是主线程的 UI 取消操作）。ioExecutor 是单线程队列，
        // 所以它一定排在本方法之后提交的 pairBegin 任务之前执行 —— 顺序不变。
        ioExecutor.execute {
            runCatching {
                PairingClient(
                    sendReq = { op, tx, blob -> client.sendRaw(PtpCodec.opReqBlob(op, tx, blob)) },
                    recvRsp = { client.recvPlainOpBlob() },
                ).abort()
            }
            runCatching { client.close() }
        }
    }

    fun disconnect(host: String) {
        // ★ F3：控制会话收走时，批量数据通道一并作废 —— 死连接不允许跨会话存活
        dropBatchChannel()
        sessions.remove(host)?.let { runCatching { it.close() } }
    }

    fun closeAll() {
        for (h in sessions.keys.toList()) disconnect(h)
        dropBatchChannel()   // 兜底：sessions 为空但通道仍在（异常路径）时也要收掉
    }

    // ============================================================
    // 目录浏览 / 元数据
    // ============================================================

    data class FtpEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val timestamp: Long,
    ) {
        val ext: String
            get() = name.substringAfterLast('.', "").lowercase()
    }

    data class DirPage(
        val entries: List<FtpEntry>,
        val nextOffset: Int,
        val hasMore: Boolean,
    )

    /**
     * 读取一页目录；旧相机没有分页字段时按"满页即可能还有"处理（F9）。
     *
     * @param reverse ★ 2.10：true = 倒序分页（从字母尾/最新端向头部，每页条目倒序返回；
     *   服务于默认排序"新→旧"的增量翻页），[offset] 语义为"已消费条目数"；
     *   false/缺省 = 旧正序行为 —— 现有调用点全部无需改动。
     * @param timeoutMs 单次请求超时；默认 [LIST_TIMEOUT_MS]（交互档），
     *   后台全树扫描传 [SCAN_LIST_TIMEOUT_MS]（见 F6 的分档说明）。
     */
    @Throws(IOException::class)
    fun listPage(
        host: String,
        dir: String,
        offset: Int,
        limit: Int = 256,
        timeoutMs: Int = LIST_TIMEOUT_MS,
        reverse: Boolean = false,
    ): DirPage = try {
        ioExecutor.submit<DirPage> {
            val client = requireClient(host)
            val json = String(client.listDir(dir, offset, limit, reverse, timeoutMs), Charsets.UTF_8)
            parsePage(json, dir, offset, limit)
        }.get()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "listPage failed: ${e.message}")
        throw IOException("目录读取失败", e)
    }

    /** 目录列表（元数据内联，一次往返）。失败抛 [IOException]（与 FTP 版一致）。 */
    @Throws(IOException::class)
    fun list(host: String, dir: String): List<FtpEntry> = try {
        ioExecutor.submit<List<FtpEntry>> {
            val client = requireClient(host)
            val all = ArrayList<FtpEntry>()
            var offset = 0
            while (true) {
                // 全量读目录是"批量"场景：超时按扫描档给（F6）
                val json = String(client.listDir(dir, offset, 256, false, IO_TIMEOUT_MS), Charsets.UTF_8)
                val page = parsePage(json, dir, offset, 256)
                all.addAll(page.entries)
                if (!page.hasMore || page.nextOffset <= offset) break
                offset = page.nextOffset
            }
            all.sortedWith(compareByDescending<FtpEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }.get()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "list failed: ${e.message}")
        throw IOException("目录读取失败", e)
    }

    /** 对象大小与修改时间（对应 FTP 的 SIZE / MDTM）。 */
    @Throws(IOException::class)
    fun stat(host: String, path: String): PtpIpClient.StatInfo = try {
        ioExecutor.submit<PtpIpClient.StatInfo> { requireClient(host).stat(path) }.get()
    } catch (e: Exception) {
        Log.d(TAG, "stat failed: ${e.message}")
        throw IOException("对象信息读取失败", e)
    }

    /**
     * **目录计数探针**（2.8）：一次往返拿回"目录现在有多少个条目"。
     *
     * <p>★ 背景（2.8 修复的根因）：这台相机的目录 mtime **不随内容变化**（实测恒为
     *   目录创建时间，新增/删除照片都不更新）—— 2.6/2.7 建立在"FAT 目录 mtime 随
     *   内容变"上的两套机制（文件列表的指纹缓存、边拍边传的变化检测）全部失效，
     *   表现为"拍新照片后列表不刷新、边拍边传识别不到新照片"。改用**条目计数**当
     *   变化信号。
     *
     * <p>实现**零协议改动**：相机端 `listDir` 对超界 offset 会钳制到 `files.length`
     *   （`start = min(offset, length)`），返回**空 entries + nextOffset=总数 +
     *   hasMore=false**。于是请求 `offset=1_000_000_000`（必超界）就等价于一次
     *   "只报总数"的探针 —— 服务端只付一次 listFiles + 排序（3s 快照缓存可复用），
     *   回包只有几十字节，任何版本相机端都支持。
     *
     * <p>★ 2.10 倒序分页不影响本探针：超界 offset 的"钳制到总数"语义与切片方向无关
     *   （正/倒序都回 nextOffset=总数、空 entries），且本探针保持正序调用，口径不变。
     *
     * @return 总条目数；目录不存在 / 网络失败返回 null（调用方按"探测不到"处理）
     */
    fun dirCount(host: String, dir: String): Int? = try {
        ioExecutor.submit<Int> {
            val client = requireClient(host)
            val json = String(client.listDir(dir, DIR_COUNT_PROBE_OFFSET, 1), Charsets.UTF_8)
            val total = org.json.JSONObject(json).optInt("nextOffset", -1)
            if (total < 0) null else total
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "dirCount failed: ${e.message}")
        null
    }

    /**
     * 心跳：一次往返同时拿电量与镜头（替代旧 HEARTBEAT + INFO 双轮询）。
     *
     * <p>★ F6（2.6 修复）：**不再经 ioExecutor 排队**，直接在调用线程发。
     * 旧写法让心跳排在单线程控制队列的队尾 —— 后台全树扫描（几百次 listPage）
     * 一跑，心跳被饿死，3 次失联（9s）就触发 disconnect("失去与相机的连接")，
     * 会话被收走 → 用户侧"文件特别多时目录读取失败 + 一连串雪崩"的起点。
     * PING 是 DP_NONE 小包，本就该有最高优先级。
     *
     * <p>线程安全依据：`PtpIpClient.request` 是**流水线化**的 —— `pending` 按 txId
     * 分派、发送端 `sendLock` 只保护单帧原子性，多线程直接发起请求是既有设计
     * （见 `openObjectTicket` 的去串行化说明）。
     */
    @Throws(IOException::class)
    fun ping(host: String): PtpIpClient.PingInfo = try {
        requireClient(host).ping()
    } catch (e: Exception) {
        Log.d(TAG, "ping failed: ${e.message}")
        throw IOException("心跳失败", e)
    }

    /** 相机设备信息（厂商 JSON，字段并入现有 DeviceInfo）。 */
    fun deviceInfo(host: String): JSONObject? = try {
        ioExecutor.submit<JSONObject?> {
            val bytes = requireClient(host).deviceInfo()
            runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "deviceInfo failed: ${e.message}")
        null
    }

    /**
     * 解除配对：请相机删掉本机那条配对记录（已认证会话内执行）。
     * 返回是否受理；真正的本地记录由调用方清。
     */
    fun pairRemove(host: String): Boolean = try {
        ioExecutor.submit<Boolean> { requireClient(host).pairRemove() }.get()
    } catch (e: Exception) {
        Log.d(TAG, "pairRemove failed: ${e.message}")
        false
    }

    /** 缩略图队列控制（begin/pause/resume/cancel）。 */
    fun thumbQueue(host: String, op: Int, paths: List<String>): Boolean = try {
        ioExecutor.submit<Boolean> { requireClient(host).thumbQueue(op, paths) }.get()
    } catch (e: Exception) {
        Log.d(TAG, "thumbQueue failed: ${e.message}")
        false
    }

    internal fun parsePage(
        json: String,
        dir: String,
        requestedOffset: Int,
        requestedLimit: Int = 0,
    ): DirPage {
        val text = json.trim()
        if (text.isEmpty()) return DirPage(emptyList(), requestedOffset, false)
        val root: JSONObject?
        val arr: JSONArray = if (text.startsWith("[")) {
            root = null
            JSONArray(text)
        } else {
            root = JSONObject(text)
            root.optJSONArray("entries") ?: JSONArray()
        }
        val out = ArrayList<FtpEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isEmpty() || name == "." || name == "..") continue
            out.add(
                FtpEntry(
                    name = name,
                    path = joinPath(dir, name),
                    isDir = o.optBoolean("dir", false),
                    size = o.optLong("size", 0L),
                    timestamp = o.optLong("mtime", 0L),
                )
            )
        }
        val sorted = out.sortedWith(
            compareByDescending<FtpEntry> { it.isDir }.thenBy { it.name.lowercase() }
        )
        // ★ F9（2.6 修复）：裸数组（旧固件）没有分页字段 —— 旧实现固定 hasMore=false，
        //   目录超过一页时**后面的文件永远看不见**。改为"满页即认为可能还有"：
        //   调用方再请求一次 nextOffset，靠"空页/不满页"自然终止（多一次无害往返）。
        val hasMore = root?.optBoolean("hasMore", false)
            ?: (requestedLimit > 0 && out.size >= requestedLimit)
        val next = root?.optInt("nextOffset", requestedOffset + out.size)
            ?: (requestedOffset + out.size)
        return DirPage(sorted, next, hasMore)
    }

    // ============================================================
    // 缩略图 / 预览（GET_OBJECT + 文件端口，一次性令牌）
    // ============================================================

    /** 大预览（1616×1080 内嵌 JPEG，~0.4MB）。失败返回 null。 */
    fun fetchVirtualPreview(host: String, cameraPath: String): ByteArray? =
        fetchObject(host, normalize(cameraPath), PtpCodec.KIND_PREVIEW, IO_TIMEOUT_MS)

    private fun normalize(cameraPath: String): String =
        if (cameraPath.startsWith("/")) cameraPath else "/$cameraPath"

    /** 一次性取件凭据（换令牌的产物）。见 [openObjectTicket] / [downloadObject]。 */
    class ObjectTicket internal constructor(
        internal val host: String,
        internal val path: String,
        internal val kind: Int,
        internal val token: Long,
        internal val filePort: Int,
        internal val connNo: Int,
        /**
         * 签发这张票的**控制连接实例**（F3）。
         *
         * <p>批量数据通道跨批复用时必须确认"这张通道还属于同一条控制会话"：
         * 每次重连都会产生新的 [PtpIpClient] 实例，因此用引用相等即可精确判定
         * 会话是否已更替（比 connNo 可靠 —— 相机重启后 connNo 计数器会从头开始，
         * 不同会话可能撞出同一个号）。
         */
        internal val client: PtpIpClient,
    )

    /**
     * 两步式取图的**第一步**：在控制通道上换取一次性令牌。
     *
     * <p>★ 2.7.0（提速）：**不再经 [xferExecutor] 串行**。旧写法给这一步套了单线程
     * 执行器，理由是"控制通道单连接请求-应答必须串行"—— 那是**过时的错误认知**：
     * `PtpIpClient` 的请求本来就是**流水线化**的（`pending[txId]` 分派 + 发送端
     * `sendLock` 只保护单帧原子性），多个 OP 可以同时在飞、按 txId 各自取回应答。
     * 套上单线程后，小图 4 路 + 周期 3 路的所有令牌往返被串成"一次一个"，
     * 实测把整条管线压到 **~5.4 次/秒**（273 小图 + 163×2 预览令牌 / 110 秒），
     * 数据通道 44% 时间在空转等令牌 —— 聚合吞吐因此只有 0.66MB/s。
     *
     * <p>现在直接在当前线程（调用方的池线程）发起：控制面并发度由池大小天然限住，
     * 相机端控制循环本来就是"读一个、答一个"，多几个排队请求照单全收。
     *
     * <p>★ 为什么拆两步：自动传输要"同一张图的小图+大预览**同时**传输"（用户定版）。
     * 数据通道是各自独立的 TCP 连接（相机端每连接一个服务线程），本来就**可以并行**。
     *
     * <p>令牌 TTL 15 秒（相机端 issueToken）：换完令牌要尽快下载。
     */
    fun openObjectTicket(host: String, cameraPath: String, kind: Int): ObjectTicket? = try {
        val p = normalize(cameraPath)
        val client = requireClient(host)
        val t = client.getObjectWhole(p, kind)
        ObjectTicket(host, p, kind, t.token, t.filePort, client.connNo, client)
    } catch (e: Exception) {
        Log.d(TAG, "openObjectTicket failed kind=$kind path=$cameraPath: ${e.message}")
        null
    }

    /**
     * 两步式取图的**第二步**：开数据连接下载（阻塞，在**调用者线程**上跑）。
     *
     * <p>短收判失败（绝不把半张图交出去）——语义与原来的单函数版完全一致。
     * [ObjectTicket] 一次性，不要复用。
     */
    fun downloadObject(t: ObjectTicket, timeoutMs: Int = IO_TIMEOUT_MS): ByteArray? = try {
        // ★ 2.7.0 提速诊断：与相机端"传输完成"日志同款口径 —— 两边速率一对照，
        //   瓶颈在相机发送、网络、还是手机接收（解码/写盘）立判。
        val beganAt = android.os.SystemClock.elapsedRealtime()
        val out = ByteArrayOutputStream(if (t.kind == PtpCodec.KIND_THUMB) 16 * 1024 else 512 * 1024)
        var total = -1L
        val received = DataChannel(
            host = t.host,
            filePort = t.filePort,
            // ★ 必须是**本机自己的 guid16**：相机端用 `token.deviceId == dataOpen.guid`
            //   校验这条数据连接归属哪个控制会话，而令牌是拿控制会话的 deviceId 签发的
            //   （那个值来自控制连接 INIT 里的手机身份，见 ConnectionCenter 的说明）。
            guid16 = IdentityRepo.guid16(),
            connNo = t.connNo,
            socketFactory = socketFactoryOrNull(),
        ).use { ch ->
            ch.download(
                token = t.token,
                expectedTxId = 1,
                expectedBytes = -1L,
                timeoutMs = timeoutMs,
                onTotal = { total = it },
            ) { buf, off, len ->
                out.write(buf, off, len)
            }
        }
        // ★ **收不满就当失败，绝不把半张图交出去**：缩略图/预览图会被写到磁盘缓存
        //   （cache/thumbs、cache/previews），而缓存是持久的 —— 一次短收就留下一个
        //   永久坏图，用户会一直看到"坏块 / 糊"（2026-09-14 实测：63 个缓存文件里
        //   30 个是这样留下的，尺寸恰好是收发块大小的整数倍）。
        //   返回 null 的代价只是这一次显示"无法获取预览"，比缓存一张坏图好得多。
        // ★ 2.9：判据补上 total==0 空占位（相机端"对象取不到"回 0 字节 Start/End 对）——
        //   0/0 不满足短收条件，旧判据会当"成功"交出空数组、被上层写进缓存。
        //   现在与批量路径同一条纪律：**必须 total > 0 且收满**才交付。
        if (total <= 0 || received != total) {
            Log.d(TAG, "downloadObject 短收/空占位 kind=${t.kind} path=${t.path} 收到 $received / 共 $total")
            null
        } else {
            val bytes = out.toByteArray()
            logRate("download kind=${t.kind} ${t.path}", bytes.size,
                android.os.SystemClock.elapsedRealtime() - beganAt)
            bytes
        }
    } catch (e: Exception) {
        Log.d(TAG, "downloadObject failed kind=${t.kind} path=${t.path}: ${e.message}")
        null
    }

    /** 一步式取图（先换令牌、随即下载），语义与拆分前完全一致。 */
    private fun fetchObject(host: String, path: String, kind: Int, timeoutMs: Int): ByteArray? {
        val t = openObjectTicket(host, path, kind) ?: return null
        return downloadObject(t, timeoutMs)
    }

    /** 取图速率日志（2.7.0 提速诊断，与相机端"传输完成"行同款口径，logcat 过滤 ObjRep）。 */
    private fun logRate(what: String, bytes: Int, ms: Long) {
        if (ms > 0 && bytes > 0) {
            Log.d(TAG, "$what ${bytes}B ${ms}ms ${bytes * 1000L / 1024L / ms}KB/s")
        }
    }

    /**
     * **批量取件凭据**（2.7.0）：一个令牌对应一批对象，顺序与 [items] 一致。
     *
     * <p>为什么批量：实测每对象的固定成本（控制往返 + TCP 建连/慢启动 + DATA_OPEN 往返）
     * 把 423KB 的预览切碎；且多流并发在 2.4GHz 弱电台上互相争抢，聚合反低于单流。
     */
    fun openBatchTicket(host: String, items: List<Pair<String, Int>>): ObjectTicket? = try {
        val client = requireClient(host)
        val norm = items.map { (p, k) -> normalize(p) to k }
        val t = client.getObjectBatch(norm)
        // path/kind 对批量无意义（置空），真正的内容在相机端按 1:1 顺序回
        ObjectTicket(host, "", -1, t.token, t.filePort, client.connNo, client)
    } catch (e: Exception) {
        Log.d(TAG, "openBatchTicket failed: ${e.message}")
        null
    }

    /**
     * **批量下载**（2.7.0）：在**一条数据连接**上把 [count] 项连续取回。
     *
     * <p>[onObject] 按 0 基下标回调（null = 该项不可用/短收，与单对象路径同一纪律）；
     * 返回是否完整收到 [count] 项。
     */
    /** 复用中的批量数据通道（2.7.0）：跨批共用一条 TCP —— 省掉每批的建连与慢启动。 */
    @Volatile
    private var batchChannel: DataChannel? = null

    /** [batchChannel] 当前归属的**控制会话实例**（F3）：与通道同时登记、同时丢弃。 */
    @Volatile
    private var batchChannelSession: PtpIpClient? = null

    /**
     * 取（或重建）批量数据通道。
     *
     * <p>★ F3（2.6 修复）：**复用必须校验会话一致性**。旧实现只判"非空"就复用 ——
     * 断连重连后，残留的旧会话数据连接（socket 已死或半死）会被新批拿去发
     * DATA_OPEN，相机端因 connNo/设备不匹配**静默关流**，手机端看到的就是
     * "文件端口未回 DATA_OPEN_ACK"刷屏、整批整批地失败（日志实锤的现场）。
     * 现在通道必须同时满足：同一条控制连接实例、同 connNo、本端未关闭；
     * 任一不满足立即丢弃重建（重建的代价只是一次 TCP 建连）。
     *
     * <p>★ 审修复（H3）：本方法与 [dropBatchChannel] 都对本对象加锁 ——
     * `batchChannel`/`batchChannelSession` 是**成对**登记/清空的两个字段，
     * 断连回调线程（disconnect → dropBatchChannel）与传输线程（batchChannelFor）
     * 并发时，无锁的读-改-写可能把已死连接当活的复用、或配错会话实例。
     * 当前批量路径被 bulkGate 单许可隐式串行，但这层自保不该依赖调用方约定。
     */
    @Synchronized
    private fun batchChannelFor(t: ObjectTicket): DataChannel {
        batchChannel?.let { ch ->
            if (batchChannelSession === t.client && ch.connNo == t.connNo && !ch.isClosed) {
                return ch
            }
            Log.d(TAG, "批量通道跨会话/已关闭，丢弃重建（connNo ${ch.connNo} → ${t.connNo}）")
            dropBatchChannel()
        }
        val ch = DataChannel(
            host = t.host,
            filePort = t.filePort,
            guid16 = IdentityRepo.guid16(),
            connNo = t.connNo,
            socketFactory = socketFactoryOrNull(),
        )
        batchChannel = ch
        batchChannelSession = t.client
        return ch
    }

    /** 丢弃批量通道（与 [batchChannelFor] 同锁：成对字段的一致性见其注释）。 */
    @Synchronized
    private fun dropBatchChannel() {
        runCatching { batchChannel?.close() }
        batchChannel = null
        batchChannelSession = null
    }

    fun downloadBatch(
        t: ObjectTicket,
        count: Int,
        timeoutMs: Int = IO_TIMEOUT_MS,
        labels: List<String> = emptyList(),
        /**
         * 重试用的**换票回调**（O4，2.6 修复）：相机端令牌是**一次性**的
         * （`consumeToken` 用后即废），传输中途断开时旧令牌已被消费 ——
         * 拿同一张票重试必撞"令牌无效/过期 → 断开"（白付一次往返）。
         * 传入的话，重试前先换新票；为 null 时保持旧行为（同票重试）。
         */
        refill: (() -> ObjectTicket?)? = null,
        onObject: (Int, ByteArray?) -> Unit,
    ): Boolean {
        // ① 先试复用通道（相机端 serveFile 本来就支持一条连接连续服务多个 DATA_OPEN）；
        // ② 复用失败（相机端空闲超时关流等）→ 丢弃旧通道，**换新票**再试一次。
        //    重试可能把已回调过的项再回调一遍（写盘幂等、states 重设，无副作用）。
        if (runBatch(t, count, timeoutMs, labels, true, onObject)) return true
        dropBatchChannel()
        val t2 = refill?.invoke() ?: t
        return runBatch(t2, count, timeoutMs, labels, false, onObject)
    }

    private fun runBatch(
        t: ObjectTicket,
        count: Int,
        timeoutMs: Int,
        labels: List<String>,
        reuse: Boolean,
        onObject: (Int, ByteArray?) -> Unit,
    ): Boolean = try {
        val beganAt = android.os.SystemClock.elapsedRealtime()
        var bytes = 0L
        val got = batchChannelFor(t).downloadMany(t.token, count, timeoutMs, labels, reuse) { i, b ->
            if (b != null) bytes += b.size
            onObject(i, b)
        }
        logRate("batch ${got}项" + (if (reuse) " 复用" else " 新建"), bytes.toInt(),
            android.os.SystemClock.elapsedRealtime() - beganAt)
        got == count
    } catch (e: Exception) {
        Log.d(TAG, "runBatch failed(reuse=$reuse): ${e.message}")
        false
    }

    // ============================================================
    // 下载（偏移起点 → 断点续传）
    // ============================================================

    enum class PumpResult { COMPLETED, FAILED, CANCELLED }

    /**
     * 从 [startOffset] 续传下载到 [out]；[cancelled] 每块检查。
     *
     * 语义对齐 FTP 版：
     * - 收满请求切片 → `COMPLETED`
     * - 中途取消（断开数据 socket 表达）→ `CANCELLED`，**已写字节保留**，可带同一 offset 续传
     * - 其余异常 / 收不满 → `FAILED`
     *
     * ★ 续传前调用方应先 `stat()` 校验 size/mtime 未变（相机端也按 token TTL 兜底）。
     */
    fun pumpToStream(
        host: String,
        path: String,
        startOffset: Long,
        out: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): PumpResult {
        var total = -1L
        return try {
            xferExecutor.submit<PumpResult> {
                val client = requireClient(host)
                val stat = client.stat(path)
                if (startOffset > stat.size) {
                    throw IOException("续传位置超过对象大小")
                }
                val ticket = client.getObject(path, PtpCodec.KIND_ORIGINAL, startOffset, -1)
                val expectedRemaining = stat.size - startOffset
                // ★ 2.7.0 提速诊断：原图传输（ORIGINAL 通道**不做任何解析**，纯 SD 读 +
                //   发送）的端到端速率 = "链路 + 相机发送"的上限。与预览通道的速率
                //   一对照即可分辨瓶颈：两者都掉 = 链路饱和；只有预览掉 = 相机解图 CPU 饱和。
                val beganAt = android.os.SystemClock.elapsedRealtime()

                var written = startOffset
                // ★ 进度回调**合并上报**（原来是每 128 KiB 一次）：上层每个回调都要
                //   `indexOfFirst` 找条目、替换一次 SnapshotStateList、再把整个队列扫一遍
                //   汇总 —— 25 MB 的文件就是 200 次 O(队列长度) 的活，全压在这条
                //   "边收边写"的线程上，还顺带触发 Compose 重组。
                //   现在攒够 512 KiB 或隔了 120 ms 才报一次；最后一帧必报（进度条要落终点）。
                var lastReported = written
                var lastTick = 0L
                fun report(now: Long) {
                    if (now - lastReported < 512 * 1024 &&
                        System.currentTimeMillis() - lastTick < 120
                    ) {
                        return
                    }
                    lastReported = now
                    lastTick = System.currentTimeMillis()
                    onProgress(now)
                }
                val received = DataChannel(
                    host = host,
                    filePort = ticket.filePort,
                    // ★ 同 fetchObject：数据连接的身份必须与主控制会话一致（本机自己的 guid16）
                    guid16 = IdentityRepo.guid16(),
                    connNo = client.connNo,
                    socketFactory = socketFactoryOrNull(),
                ).use { ch ->
                    ch.download(
                        token = ticket.token,
                        expectedTxId = 1,
                        expectedBytes = expectedRemaining,
                        timeoutMs = IO_TIMEOUT_MS,
                        onTotal = { total = it },
                        cancelled = cancelled,
                        onChunk = { buf, off, len ->
                            out.write(buf, off, len)
                            written += len
                            report(written)
                        },
                    )
                }
                // 收尾补一次：合并之后最后一小截可能没够阈值，进度条要落到终点
                onProgress(written)

                val pumpResult = when {
                    cancelled() -> PumpResult.CANCELLED
                    total >= 0 && received == total -> PumpResult.COMPLETED
                    else -> {
                        Log.d(TAG, "pump 短收: received=$received total=$total")
                        PumpResult.FAILED
                    }
                }
                // ★ 2.7.0 提速诊断：本条原图传输的端到端速率（见上面 beganAt 的说明）
                logRate("pump ${path}", (received - startOffset).toInt(),
                    android.os.SystemClock.elapsedRealtime() - beganAt)
                pumpResult
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "pump failed: ${e.message}")
            if (cancelled()) PumpResult.CANCELLED else PumpResult.FAILED
        }
    }

    // ============================================================
    // 内部
    // ============================================================

    @Throws(IOException::class)
    private fun requireClient(host: String): PtpIpClient {
        val s = sessions[host] ?: throw IOException("未连接相机 $host")
        if (!s.client.isConnected) {
            disconnect(host)
            throw IOException("相机连接已断开 $host")
        }
        return s.client
    }

    // ============================================================
    // 路径小件（与 FTP 版逐字一致，调用方无感）
    // ============================================================

    fun joinPath(dir: String, name: String): String {
        val d = if (dir.endsWith("/")) dir else "$dir/"
        return "$d$name"
    }

    fun parentOf(path: String): String {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    fun breadcrumbOf(path: String): List<Pair<String, String>> {
        // [("根", "/"), ("DCIM", "/DCIM"), ...]
        val out = mutableListOf<Pair<String, String>>("/" to "/")
        var acc = ""
        path.trim('/').split("/").filter { it.isNotBlank() }.forEach { seg ->
            acc += "/$seg"
            out.add(seg to acc)
        }
        return out
    }
}
