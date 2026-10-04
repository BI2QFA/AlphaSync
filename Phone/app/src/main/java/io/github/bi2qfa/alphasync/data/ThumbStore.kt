package io.github.bi2qfa.alphasync.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import io.github.bi2qfa.alphasync.ptpip.PtpCodec
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图 / 预览管线。
 *
 * 相机端预取（OP_THUMB_QUEUE_BEGIN，**★ 2.10 起手机端已停发**）只是热身；真正的数据通路
 * 是手机端按需经 `OP_GET_OBJECT` 拉取内嵌 JPEG：列表用 KIND_THUMB（160x120 ~6KB），
 * 点开全屏用 KIND_PREVIEW（1616x1080 ~0.4MB）。两层缓存：内存 Lru + 磁盘
 * （键 = path+size+mtime 的 MD5，**不含设备维度** —— 见 {@link ObjectCacheKey}）。
 *
 * 文件传输开始时 paused=true（DownloadService 调 pauseForTransfer），拉取
 * 线程挂起等待；传输结束自动恢复（resumeAfterTransfer）。进度（done/total）
 * 供顶栏芯片显示。
 *
 * ★ 2.6 新增两块（用户定版，见 PLAN-AutoPreview-Cache.md）：
 * <ul>
 *   <li><b>自动传输预览图</b>：设置开启后，连接完成的预热名单会进
 *       [startAutoFetch] 的独立队列（[autoFetchLoop] 线程），按"文件周期"推进：
 *       一个周期内小图与大预览**并发**拉取，两者都收场才进入下一个文件；
 *       用户点开某张时自动队列在周期边界让位（[userOpenAt]）。</li>
 *   <li><b>持久缓存可配置</b>：字节上限改为 [SettingsRepo.cacheLimitBytes]
 *       （设置页可调），两目录**合并**按 LRU 修剪（[pruneAll]）；
 *       所有落盘走原子写（[atomicWrite]）；解码失败的缓存文件删掉回源。</li>
 * </ul>
 *
 * ★ 2.10 新增（用户定版，见《缩略图无感预取与文件页改造-方案-2026-10-03.md》§五/§七）：
 * <ul>
 *   <li><b>无感两段式通道</b>：[startSeamlessFetch] 取代原"自动传输预览图"队列 ——
 *       [thumbQueue] / [previewQueue] **物理分离**，取件器 [pollSeamless] 先小图后
 *       预览（"小图绝对优先"是取件顺序而非排序），每批只装同一种 kind；通道随连接
 *       常开（"自动传输预览图"开关已废）。</li>
 *   <li><b>新照片注入</b>：[injectNew] 按显示序**逆序 pushFront** 到 [thumbQueue] ——
 *       队头 = 列表最上方，新照片小图**绝对优先**于所有剩余项；同一张的预览追加到
 *       [previewQueue] 尾部（小图批完成后自然进入预览段，不打断小图优先）。</li>
 * </ul>
 */
object ThumbStore {

    enum class ThumbState { NONE, LOADING, READY, FAILED }

    /**
     * ★ 2.7 修复（方案 A"按文件指纹对应"，用户定版）：缓存身份**不含相机 GUID**。
     *
     * <p>原来键里带 cameraGuid：在 A 相机缓存的图，拔卡插到 B 相机（SD 内容没变）
     * 全部 miss、整库重拉。现在同一台相机**换卡**由 size+mtime 这层指纹挡住
     * （新卡的同名文件 size/mtime 必然不同 → miss → 回源，安全），
     * 设备维度纯属多余；去掉后换机即命中。
     *
     * <p>残留风险：两张卡上 `path+size+mtime` 全同的**不同照片**会串图 ——
     * 概率极低（需同目录同名同字节数同时间戳），见《缩略图缓存按文件指纹对应方案》§五。
     */
    data class ObjectCacheKey(
        val path: String,
        val size: Long,
        val mtime: Long,
    ) {
        val token: String
            get() = path + "\u0000" + size + "\u0000" + mtime
    }

    // ★ 2.6 修复（用户定版）：**取消大小缩略图的条数上限**。
    //   历史上小图 600 / 预览 2000 两个条数闸会把缓存"削短"——实测 714 张的库，
    //   小图目录被修剪到恰好 600，永远有 114 张不在盘上，每次启动都要重拉，
    //   用户看到的就是"明明加载完了，下次启动又走一遍加载流程"。
    //   现在唯一的约束是**字节总闸**（[SettingsRepo.cacheLimitBytes]，见 [pruneAll]），
    //   自动通道再叠加"预算内拉取"（见 [cacheBudgetExceeded]）防止把闸填满后回环。

    /** 磁盘预扫的批大小（每批让出一次，见 [startAutoFetch] / [startSeamlessFetch]）。 */
    private const val SCAN_CHUNK = 500
    private const val SCAN_CHUNK_GAP_MS = 10L

    /** 缓存预算的安全系数：占用达到上限的 90% 即停自动传输（留 10% 给点开/翻页）。 */
    private const val CACHE_BUDGET_NUM = 9
    private const val CACHE_BUDGET_DEN = 10

    /** 预算暂停提示的冷却时长：同一进程内最多每 60s 弹一次 Toast。 */
    private const val BUDGET_TOAST_COOLDOWN_MS = 60_000L

    /**
     * 自动传输的**让位窗口**：用户点开大预览后的这段时间里，自动队列不发起新请求。
     *
     * <p>为什么需要它：所有网络拉取（点开、自动、小图队列）在 ObjectRepository 里
     * 共用**单线程** xferExecutor 排队 —— 不做让位的话，用户点开一张没缓存的图，
     * 要排在自动传输当前那张（1-2 秒）后面。窗口取 5 秒：够覆盖"翻页看下一张"
     * 的间隔，又不会让自动传输被偶尔点一下永久卡住。
     */
    private const val YIELD_WAIT_MS = 5_000L

    /**
     * 写盘路径的联合修剪节流阈值（字节）：累计写入达到这个量才真正遍历一遍
     * 目录做 LRU 修剪（见 [noteWritten]）。取 32 MB —— 相对最小档位 256 MB
     * 是 1/8 的粒度，短暂超额无感，而目录遍历次数降到 1/60。
     */
    private const val PRUNE_EVERY_BYTES = 32L * 1024 * 1024

    /**
     * 小图通道**同时允许的批数**（2.7.0 批量流式后 = 1）。
     *
     * ★ 并发不再是手段：延迟已由“批量 + 单流闸”解决（每批 8 张摊薄固定开销），
     * 而**多条流在 2.4GHz 弱电台上会互相争抢** —— 实测 3 条并发时单批速率被压到
     * 318KB/s、聚合反降。故两个通道统一为 **1 批在飞**，跨通道由 [bulkGate] 互斥。
     */
    private const val SMALL_CONCURRENCY = 1

    /**
     * 自动通道**同时允许的批数**（2.7.0 批量流式后 = 1）。
     *
     * 同上：批量已把固定开销摊销，真正要保的是**单流**（多流争抢实测更慢）。
     * 批内不会空转：相机端的向前看解析把下一项的解析藏在发送背后。
     */
    private const val AUTO_CONCURRENCY = 1

    /**
     * 无感通道 **小图批大小**（★ 2.11-fix：从原 `AUTO_BATCH_K` 拆出，小图/预览各自独立）。
     *
     * <p>为什么批量：实测每对象的固定成本 = 控制往返 + TCP 建连/慢启动 + DATA_OPEN
     * 往返；且 3~4 条并发流在 2.4GHz 弱电台上互相争抢，聚合反而**低于**单流。
     * 批量把固定开销摊销到 K 项上，让数据面回到单流。**回退开关**：改 1 = 逐项老路径。
     *
     * <p>取 16 的依据：无感通道的并发是 [AUTO_CONCURRENCY] = 1（同时只有**一批**在飞），
     * "一批装多少项"就是这条通道唯一的在飞水位杠杆；相机端**逐张流式**下发
     * （`PtpIpServer.transferBatch` 按 txId 对账），首项仍在单张解析（相机上 ~88ms）
     * 后到达，**不增加首屏延迟**。内存 16 × ~6.4KB ≈ 100KB，可忽略。
     */
    private const val SEAMLESS_THUMB_K = 16

    /**
     * 无感通道 **大预览批大小**（★ 2.11-fix：8 → **16**，用户要求"适当增加预览图批量单组数量"）。
     *
     * <p>为什么能加：批内每张仍在**同一条连接**上逐张流式下发（首项不因批大而变慢），
     * 加大只是把"批间空隙"（让位窗口检查、[bulkGate] 获取、批调度）摊得更薄 ——
     * 收益递减但方向正确。
     *
     * <p>已核实的两个边界：
     * <ul>
     *   <li><b>不闸住"点开大图"</b>：用户实时取件（[fetchPreviewBlocking]）**不经过**
     *       [bulkGate]，不会被预取批堵在闸外；只是空口上与预取批竞争。</li>
     *   <li><b>内存</b>：16 × ~400KB ≈ 6.4MB 进 [writeQueue]，可接受。</li>
     * </ul>
     * 代价：单批在飞时间变长（16 × 相机端 ~350ms/张 ≈ 5.6s）—— 属预取，用户不直接等它。
     */
    private const val SEAMLESS_PREVIEW_K = 16

    /**
     * 小图通道**批大小**（2.7.0 批量流式）。
     *
     * <p>⚠ ★ 2.11-fix：**本通道已退役**（见 [init] 不再启动 [fetchLoop] 的说明）——
     * 小图取件统一由无感通道（[SEAMLESS_THUMB_K]）承担。此常量只服务于保留待清理的
     * 旧路径，改它不会再影响线上行为。
     */
    private const val THUMB_BATCH_K = 16

    /**
     * F2：单条目**连续失败上限**（小图 / 自动两条通道各自计）。
     *
     * <p>失败不再"一次即终态"：回队头重试，连续失败到这个数才标终态（小图 FAILED）。
     * 3 次足以覆盖"断连瞬间的整批失败""相机端短暂拒绝"这类瞬时故障，
     * 又不会让真正的坏对象无限重试。
     */
    private const val ITEM_RETRY_LIMIT = 3

    /** F2：重试退避步长（第 n 次连续失败后退避 n×步长，封顶 [ITEM_RETRY_BACKOFF_MAX_MS]）。 */
    private const val ITEM_RETRY_BACKOFF_MS = 400L
    private const val ITEM_RETRY_BACKOFF_MAX_MS = 2000L

    /**
     * F8：暂停兜底时长。DownloadService 的 finally 未必一定执行（前台服务被系统
     * 杀掉），那样 `paused` 会永久卡住、两条队列永久挂起 —— 超过这个时长自动解除。
     */
    private const val PAUSE_MAX_MS = 10 * 60 * 1000L

    /** F7：request() 分批入队的批大小与批间隔（见 [request]）。 */
    private const val ENQUEUE_CHUNK = 500
    private const val ENQUEUE_CHUNK_GAP_MS = 10L

    /** F8：prune 只清"陈年" `.tmp`（新 `.tmp` 可能正是在途的原子写，删了会丢这次落盘）。 */
    private const val TMP_STALE_MS = 60_000L

    private lateinit var appContext: Context
    private lateinit var smallDiskDir: File
    private lateinit var previewDiskDir: File

    /** 完整对象身份 → 状态 */
    val states = mutableStateMapOf<String, ThumbState>()

    var paused by mutableStateOf(false)
        private set

    /** 批次进度：本批已处理数 / 批次总数 */
    var batchDone by mutableStateOf(0)
        private set
    var batchTotal by mutableStateOf(0)
        private set

    private val queue = ArrayDeque<ObjectCacheKey>()
    private val queuedSet = HashSet<String>()
    private val lock = Object()
    private val started = AtomicBoolean(false)
    private var host: String? = null

    /**
     * F2：小图通道的条目重试计数（token → 连续失败次数）。
     * 交付成功 / 磁盘命中即清零；达到 [ITEM_RETRY_LIMIT] 清零并标终态。
     */
    private val thumbRetryCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** F2：自动通道的条目重试计数（语义同上）。 */
    private val autoRetryCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** F8：进入暂停的时刻（elapsedRealtime；0 = 未暂停），见 [pauseExpiredLocked]。 */
    @Volatile
    private var pausedAtMs = 0L

    /**
     * **进度计数的"已计入"集合**（缓存识别修复）：小图/自动各一份，同一 token 只计一次。
     *
     * <p>起因：进度原先在"批次收尾"里按批大小统计 —— 磁盘命中的条目既可能被
     * [request] 提前标完成、又可能是批次里的命中，两条路都计就会重复；只计一次
     * 才能让"启动预扫 + 增量拉取"的合计等于名单数（"N/N 完成"才是真的完成）。
     */
    private val countedSmall = HashSet<String>()
    private val countedAuto = HashSet<String>()

    /** 小图进度 +1（幂等；**须持有 [lock]**）。 */
    private fun countSmallLocked(token: String) {
        if (countedSmall.add(token)) {
            batchDone = (batchDone + 1).coerceAtMost(batchTotal)
        }
    }

    /** 自动/预览进度 +1（幂等；**须持有 [lock]**，且调用方已确认是当前代次）。 */
    private fun countAutoLocked(token: String) {
        if (countedAuto.add(token)) {
            autoDone = (autoDone + 1).coerceAtMost(autoTotal)
        }
    }

    /**
     * 磁盘缓存判定（存在且非空）：命中即视为"已加载"。
     *
     * <p>不在这里解码 —— 解码留给真正要显示的路径（[smallThumb] 按需解），
     * 否则"启动时把整库缓存过一遍"就又变成一次全量解码（正是要修掉的加载流程）。
     * 原子写（[atomicWrite]）保证落盘文件要么完整要么不存在，所以"存在"即可信。
     */
    private fun thumbOnDisk(key: ObjectCacheKey): Boolean =
        smallDiskFile(key).let { it.isFile && it.length() > 0L }

    private fun previewOnDisk(key: ObjectCacheKey): Boolean =
        previewDiskFile(key).let { it.isFile && it.length() > 0L }

    /** 按 kind 判磁盘（自动通道的进闸重查用）。 */
    private fun onDisk(key: ObjectCacheKey, kind: Int): Boolean =
        if (kind == PtpCodec.KIND_PREVIEW) previewOnDisk(key) else thumbOnDisk(key)

    /**
     * 磁盘**预扫线程**（缓存识别）：启动时把"已经在磁盘上"的名单一次扫完，
     * 直接标完成、不入队 —— 避免逐批把整库重走一遍（用户反馈的"下一次启动
     * 仍然走一遍加载流程"）。
     */
    private val diskScanPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ThumbDiskScan").apply { isDaemon = true }
    }

    /**
     * 两条管线**是否仍在推进**（大队列非空或有在飞批次）。
     *
     * <p>★ F1 连带（2.6）：进度计数改为"只计成功"后，`done < total` 不再是
     * "还在传"的判据 —— 连续失败达到上限的条目不再计数，done 会**永远小于**
     * total。顶栏进度环的"进行中"判定必须改看这个信号（否则一直挂在未完成态）。
     */
    var thumbBusy by mutableStateOf(false)
        private set
    var autoBusy by mutableStateOf(false)
        private set

    // ===== 2.6 自动传输预览图（见类注释） =====

    /** 自动队列：连接后按预热名单顺序推进，每个文件一个周期（小图+大预览并发，都完成才下一张）。 */
    private val autoQueue = ArrayDeque<ObjectCacheKey>()
    private val autoQueuedSet = HashSet<String>()

    // ===== 2.10 无感两段式通道（方案 §五/§七；见类注释）=====

    /**
     * **两段式队列**（★ 2.10）：小图绝对优先 —— 取件器（[pollSeamless]）只在
     * [thumbQueue] **取空后**才碰 [previewQueue]；"先小图、后大图"是**取件顺序**
     * 而不是批内排序。两队列与旧 [autoQueue] 无关（旧容器保留待 B 阶段清理）。
     */
    private val thumbQueue = ArrayDeque<ObjectCacheKey>()      // 阶段 A：小图
    private val previewQueue = ArrayDeque<ObjectCacheKey>()    // 阶段 B：大预览
    private val thumbQueuedSet = HashSet<String>()             // 去重（token）
    private val previewQueuedSet = HashSet<String>()

    /**
     * 无感通道当前阶段的**日志标记**：SWEEP = 小图段，PREVIEWS = 预览段，IDLE = 空闲。
     * 只服务日志与注入语义判断，**不影响取件优先级**（阶段推进靠 [pollSeamless] 的
     * 队列自然取空，见方案 §7.1）。
     */
    private enum class FetchPhase { SWEEP, PREVIEWS, IDLE }

    @Volatile
    private var seamlessPhase = FetchPhase.IDLE

    /**
     * 自动/无感队列的"代次"：每次 [startAutoFetch]/[startSeamlessFetch]/[stopAutoFetch]/[reset] 递增。
     *
     * <p>线程在取到条目时记住当时的代次；让位等待中被唤醒后发现代次变了，
     * 说明重连/重启换了名单 —— 本条作废直接跳过。
     * （不能用"队列是否为空"判断：正常处理最后一条时队列本来就是空的。）
     */
    @Volatile
    private var autoGen = 0

    /** 距上次联合修剪的累计写入量（字节，见 [noteWritten]）。写盘路径多线程访问。 */
    private val writtenSincePrune = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 自动传输的**并发下载池**：一个周期里小图与大预览各占一条线程，
     * 各自开自己的数据连接（相机端每连接一个服务线程，真并行）。
     *
     * <p>为什么池里恰好 2 条：周期内最多两个任务（小图+预览）；池大了也只是
     * 排给同一周期的任务，不会更快。守护线程，进程退出不拖。
     */
    /**
     * **全局单流闸**（2.7.0 批量流式）：所有批量下载（自动通道 + 小图通道）都从这里过，
     * 同一时刻**只有一条数据连接**在搬 —— 这就是“回到单流”的实现。
     *
     * 实测：25MB 单流 2.23MB/s，而 3~4 条并发流聚合反而掉到 ~0.9MB/s（弱电台上
     * 多流互相争抢/重传）。批量 + 单流闸让链路跑在单流最优点上。
     */
    private val bulkGate = java.util.concurrent.Semaphore(1)

    /** 待落盘项（[batchWriteLoop] 消费）。 */
    private class PendingWrite(val key: ObjectCacheKey, val kind: Int, val bytes: ByteArray)

    /**
     * **落盘队列**（2.7.0）：收包回调只把字节排进来就立刻回去收下一项 ——
     * 实测收包节奏正好卡在 ~200ms/项（与项大小无关），就是"收包时停下写 460KB +
     * listFiles 修剪目录"把 TCP 流反压住了。落盘交给 [batchWriteLoop] 后，
     * "收"与"写"流水进行。
     */
    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<PendingWrite>()

    /**
     * **"已交付、等落盘"登记**（M4 补强，2.6）：一条通道把字节排进 [writeQueue]
     * 之后、[batchWriteLoop] 真正写盘（解码 + 原子写，几十 ms）之前，磁盘上还看不到
     * 这个文件 —— 另一条通道此刻重查磁盘会扑空、**重复下载同一张**
     * （2026-10-01 实测：同一张小图在 29ms 内被自动通道与小图通道各拉一遍）。
     * 这里按 (token, kind) 登记在途写，另一条通道的重查把它当"已满足"。
     */
    private val pendingWrites = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun writeId(key: ObjectCacheKey, kind: Int): String = key.token + "#" + kind

    private fun markPendingWrite(key: ObjectCacheKey, kind: Int) {
        pendingWrites[writeId(key, kind)] = true
    }

    private fun clearPendingWrite(key: ObjectCacheKey, kind: Int) {
        pendingWrites.remove(writeId(key, kind))
    }

    private fun isPendingWrite(key: ObjectCacheKey, kind: Int): Boolean =
        pendingWrites.containsKey(writeId(key, kind))

    /**
     * **落盘线程**（2.7.0）：逐项做原来的后处理 —— 小图：拆捆绑包 + 写小图 sidecar（★ 1.0）
     * + 解码 + 内存缓存 + 写盘（纯 JPEG）+ READY；预览：拆捆绑包 + 写 JPEG + 写 EXIF sidecar。
     * 异常只记日志，绝不带死线程。
     */
    private fun batchWriteLoop() {
        while (true) {
            val w = runCatching { writeQueue.take() }.getOrNull() ?: continue
            try {
                if (w.kind == PtpCodec.KIND_THUMB) {
                    // ★ 小图 = 捆绑包 [4B 大端 JSON 长][JSON][JPEG]（唯一格式；解不出即 FAILED）
                    // 先 sidecar 后解码：learnOrientation 先行，第一帧就按新方向转正
                    val bundle = splitPreviewBundle(w.bytes)
                    if (bundle == null) {
                        states[w.key.token] = ThumbState.FAILED
                        continue
                    }
                    val (jpg, json) = bundle
                    writeThumbExifSidecar(w.key, json)
                    val bmp = runCatching { decodeSmallKeyed(jpg, w.key) }.getOrNull()
                    if (bmp == null) {
                        states[w.key.token] = ThumbState.FAILED
                        continue
                    }
                    memoryCache.put(w.key.token, bmp)
                    runCatching {
                        atomicWrite(smallDiskFile(w.key), jpg)   // 磁盘仍存纯 JPEG：格式不变、好回滚
                        // 条数闸已取消（见类顶部说明）：容量只由字节总闸 + 预算闸管
                        noteWritten(jpg.size)                    // 记账用小图大小（不含 JSON）
                    }
                    states[w.key.token] = ThumbState.READY
                } else {
                    runCatching {
                        // 捆绑包拆开：JPEG 进预览缓存，EXIF JSON 进 sidecar
                        //（sidecar 一到 writeExifSidecar 就发方向信号 → 小图跟着转正）
                        val bundle = splitPreviewBundle(w.bytes) ?: return@runCatching
                        val (jpg, json) = bundle
                        atomicWrite(previewDiskFile(w.key), jpg)
                        writeExifSidecar(w.key, json)
                        noteWritten(jpg.size)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("ThumbStore", "batch write failed", t)
            } finally {
                // 在途写登记摘除（含解码失败/异常路径）：此后"是否已缓存"以磁盘为准
                clearPendingWrite(w.key, w.kind)
            }
        }
    }

    /** 周期调度池（2.7.0）：批数由 [AUTO_CONCURRENCY] 限，跨通道再由 [bulkGate] 互斥。 */

    /** 周期调度池（2.7.0）：N 个文件周期同时在飞，各自等自己的两个下载收场。 */
    private val autoCyclePool = java.util.concurrent.Executors.newFixedThreadPool(AUTO_CONCURRENCY) { r ->
        Thread(r, "AutoPreviewCycle").apply { isDaemon = true }
    }

    /** 小图下载池（2.7.0 并发化，见 [fetchLoop]）。 */
    private val smallDlPool = java.util.concurrent.Executors.newFixedThreadPool(SMALL_CONCURRENCY) { r ->
        Thread(r, "ThumbFetchDl").apply { isDaemon = true }
    }

    /** 自动传输进度（控制中心面板显示）。 */
    var autoDone by mutableStateOf(0)
        private set
    var autoTotal by mutableStateOf(0)
        private set

    /**
     * 用户最近一次**点开大预览**的时刻（elapsedRealtime）。
     *
     * <p>自动队列在每张开拉前检查：`now - userOpenAt < YIELD_WAIT_MS` 就让位等待。
     * 点开行为即"用户注意力信号"，不区分这次点开是否命中缓存 —— 命中也说明
     * 用户正在看图，自动传输不该再抢单线程的网络队列。
     */
    @Volatile
    private var userOpenAt = 0L

    /**
     * 最近一次连接时提交的**完整预热名单**（[rememberWarmup] 存，断开清空）。
     *
     * <p>用途：**"以当前状态重启传输流程"**（用户定版）—— 传输过程中（清缓存等）要能
     * 立刻按当前名单重新跑一遍，而不必等重连。
     */
    @Volatile
    private var lastWarmupKeys: List<ObjectCacheKey> = emptyList()

    /**
     * 名单在册索引（★ 2.11-fix）：本会话**已交给无感通道**的图片路径集合。
     *
     * <p>用途 = [request] 分流。名单内的条目**完全交给无感通道的自上而下流水**
     * （预扫按"新→旧"入队，[request] 不插队）—— 这样加载顺序严格 = 列表顺序；
     * 名单外的（超出预热软上限、/DCIM 以外的目录、断连后新名单未到）则插队头优先，
     * 它们没有"列表次序"可言，不能让它们排在全量名单后面干等。
     */
    @Volatile
    private var warmupPaths: Set<String> = emptySet()

    /** 连接预热时登记名单（[io.github.bi2qfa.alphasync.core.ConnectionCenter.startThumbBatch]）。 */
    fun rememberWarmup(keys: List<ObjectCacheKey>) {
        lastWarmupKeys = keys
        warmupPaths = keys.mapTo(HashSet(keys.size)) { it.path }
    }

    /**
     * **以当前状态重启传输流程**（用户定版；★ 2.10 起 = 无感两段式通道重启，**无开关分支**）：
     * <ul>
     *   <li>已连接 + 有名单 → [startSeamlessFetch] 整体替换两段队列（磁盘已缓存的条目
     *       在预扫里瞬间跳过，只补缺口：清了缓存就真拉、没清就是秒过）；</li>
     *   <li>断开 / 无名单 → 等价 [stopAutoFetch]（清空两段队列与进度、归零）。</li>
     * </ul>
     *
     * 调用点：设置里"清除缓存"（见 SettingsRepo / SettingsScreen）。
     * 批次原子性：正在跑的那个批按既有纪律传到完，随后队列按新状态继续。
     */
    fun restartAutoFetch() {
        val keys = lastWarmupKeys
        val connected = !(host ?: io.github.bi2qfa.alphasync.core.ConnectionCenter.host).isNullOrBlank()
        if (keys.isEmpty() || !connected) {
            stopAutoFetch()
            return
        }
        // ★ 2.10：语义平移 —— 不再有"自动传输预览图"开关分支（该设置已废），
        //   一律按无感通道重启（小图缺的进 thumbQueue、预览缺的进 previewQueue）。
        startSeamlessFetch(keys)
    }

    /**
     * 小图内存缓存（24MB）。
     *
     * <p>★ 2.6：预热名单不再截断（全量名单可能上千张），小图位图解码后总量可能超过
     * 本容量（160×120 ARGB_8888 ≈ 75 KB/张，361 张 ≈ 27 MB）—— 超出部分由 LruCache
     * 自动淘汰，miss 时 [smallThumb] 从磁盘缓存读并回填。功能与内存都安全，属预期行为，
     * **不需要**跟着调大（调大只会挤占其余内存，淘汰本就是它的职责）。
     */
    private val memoryCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** token → 已学到的方向（见 [orientationOf]）。 */
    private val orientCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * "方向已学到"的信号（2.7.0 需求 7）：大预览 sidecar 落盘后 +1。
     *
     * <p>方向是**解码时**施加的（小图字节里没有 EXIF），而 `memoryCache` 不是可观察状态 ——
     * FilesScreen 的两处缩略图调用点读一下本值订阅它，epoch 一变那一行重组、
     * [smallThumb] 按新方向重解重画。
     */
    var orientationEpoch by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        // v3（2026-10-01，方案 A）：缓存身份去掉设备维度（按文件指纹对应，换机可命中），
        // 旧 v2 目录按设备 guid 分子目录、新格式平铺不再读取；v1 当年已实锤串图 ——
        // 两代旧目录都一次性清掉，避免永久占空间。
        smallDiskDir = File(appContext.cacheDir, "thumbs-v3").apply { mkdirs() }
        previewDiskDir = File(appContext.cacheDir, "previews-v3").apply { mkdirs() }
        runCatching { File(appContext.cacheDir, "thumbs").deleteRecursively() }
        runCatching { File(appContext.cacheDir, "previews").deleteRecursively() }
        runCatching { File(appContext.cacheDir, "thumbs-v2").deleteRecursively() }
        runCatching { File(appContext.cacheDir, "previews-v2").deleteRecursively() }
        if (started.compareAndSet(false, true)) {
            // ★ 2.11-fix：**旧小图通道（ThumbFetch/[fetchLoop]）不再启动** ——
            //   它曾与无感通道**并发拉同一批小图**，且两者取件顺序相反（旧通道按
            //   path 升序、无感通道按"新→旧"），交错出来就是用户看到的"加载顺序像随机"。
            //   现在小图取件**只有一个取件者**（无感通道的 [autoFetchLoop]，[request]
            //   只做登记 + 名单外插队头，不再往旧 queue 里放东西）。
            //   [fetchLoop]/[pollNext]/[processThumbBatch]/[queue]/[queuedSet]/`THUMB_BATCH_K`
            //   一并保留**待清理**（本次不动其代码，避免与并发修复混在一起改）。
            // 自动/无感传输线程**常驻**（daemon，空队列时挂在 lock 上零开销）：
            // 是否真正干活由 ConnectionCenter 决定要不要调 startSeamlessFetch —— 
            // 队列恒空时线程只是睡着。
            Thread({
                autoFetchLoop()
            }, "AutoPreviewFetch").apply {
                isDaemon = true
                start()
            }
            Thread({
                batchWriteLoop()
            }, "BatchWrite").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 相机端预取只是让相机先做 CPU/IO 热身；手机端仍按完整对象身份按需拉取和
     * 落缓存，避免不知道 size/mtime 时写出一个可能串图的缓存。
     */
    fun markBatchQueued(paths: List<String>) {
        // 只更新批次计数；实际入队由 FilesScreen 携带完整身份调用 request()。
        synchronized(lock) {
            batchTotal = paths.size
            batchDone = 0
            countedSmall.clear()
        }
    }

    /**
     * 列表按需请求：必须带远端对象身份，防止同名新文件命中旧缓存。
     *
     * <p>★ 2.7.0：原本在此处按 path 排序 —— 与相机端的**微批顺序读**同构。
     * <p>★ 2.11-fix（顺序修复 R2）：**撤掉这个升序重排**。入参顺序 = 调用方分页时
     * 服务端按 `reverse=sortDesc` 返回的顺序 = **用户看到的从上到下**；在这里重排成
     * path 升序，恰好把默认显示序（新→旧 = path 降序）**整条翻过来**。
     * "同目录连续文件名"的顺序读红利改由取件侧 [orderBatchForSd] 在**批内**保证
     * （它按队列方向排，既保显示序又保局部性）。
     */
    fun request(keys: List<ObjectCacheKey>) {
        val ordered = keys
        synchronized(lock) { host = io.github.bi2qfa.alphasync.core.ConnectionCenter.host }
        // ★ F7（2.6 修复）：**分批入队**（每 [ENQUEUE_CHUNK] 条之间让出 10ms）——
        //   全量名单（可达上万条）一次写完 [states]/[queue] 会把启动/清缓存后的
        //   UI 挤成一大坨。主线程调用（设置页清缓存后的 restartAutoFetch）不睡：
        //   分批只为给渲染让帧，在主线程上睡就是冻结 UI，反而更糟。
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        // ★ 2.11-fix：待插队头的键**跨分块累积**，全部分块处理完再一次性逆序插入 ——
        //   若按分块各自插，后一块会插到前一块之上，块与块之间顺序倒置
        //   （[ENQUEUE_CHUNK] = 500，名单外目录单次 > 500 张时可复现）。
        val headInsert = ArrayList<ObjectCacheKey>()
        var i = 0
        while (i < ordered.size) {
            val endIdx = minOf(i + ENQUEUE_CHUNK, ordered.size)
            val chunk = ordered.subList(i, endIdx)
            // ① 锁外做磁盘判定：stat 不进锁（锁只保护队列/计数的落地，
            //    持锁做几百次 stat 会把拉取线程憋住）
            val cached = BooleanArray(chunk.size)
            for (ci in chunk.indices) {
                cached[ci] = states[chunk[ci].token] != ThumbState.READY && thumbOnDisk(chunk[ci])
            }
            // ② 锁内落地。★ 缓存识别（2.6 修复）：磁盘已有 → 直接标 READY 并计入进度、
            //    **不入队** —— 旧行为是照常入队、等批次轮到时再逐张解码判命中，
            //    于是"缓存完的列表每次重启都要重走一遍加载流程"。
            synchronized(lock) {
                for (ci in chunk.indices) {
                    val key = chunk[ci]
                    val st = states[key.token]
                    if (st == ThumbState.READY) continue
                    if (cached[ci]) {
                        states[key.token] = ThumbState.READY
                        thumbRetryCount.remove(key.token)
                        countSmallLocked(key.token)
                        continue
                    }
                    if (st == null || st == ThumbState.FAILED) {
                        states[key.token] = ThumbState.NONE
                        // ★ 2.11-fix（顺序修复 R1）：小图**只由无感通道取件**。
                        //
                        //   原实现把这些键放进旧通道的 queue —— 那是**另一条线程**、
                        //   且按 path 升序取件；而无感通道的队列是"新→旧"。
                        //   两条流顺序相反地并发推进，交错出来就是用户看到的
                        //   "加载顺序像随机"。
                        //
                        //   分两路，且**都不碰旧 queue**：
                        //   ① 名单在册且**状态正常**（预热会覆盖它）→ **什么都不做**：
                        //      预扫会把它按"新→旧"放进 thumbQueue，全局严格自上而下，
                        //      这里插队反而会乱序；
                        //   ② 名单外（超出预热软上限、/DCIM 以外的目录、新名单未到）→
                        //      插**队头**：它们是当前可见页，没有"列表次序"可言，
                        //      不能让它们排在全量名单后面干等。
                        //
                        //   ★ 2.11-fix（自愈回归修复）：**`FAILED` 必须无条件重排** ——
                        //   终态失败（连续 3 次，见 [requeueForRetry]）或落盘解码失败
                        //   （[batchWriteLoop]）之后，这一支是它**唯一**的自动重试入口；
                        //   若不重排，"名单在册"的图片一旦进过终态就**永久不出小图**，
                        //   只剩重连/清缓存才能救 —— 这正是"有的照片一直不出小图"。
                        //   重排是安全的：[requeueForRetry] 在给终态时会**清掉计数器**，
                        //   所以这里是给一整轮全新的 3 次机会；且终态条目早已出队，
                        //   插队头不会与"自上而下"的流水冲突（数量极少）。
                        if (st == ThumbState.FAILED || !warmupPaths.contains(key.path)) {
                            headInsert.add(key)
                        }
                    }
                }
            }
            i = endIdx
            if (i < ordered.size && !onMain) runCatching { Thread.sleep(ENQUEUE_CHUNK_GAP_MS) }
        }
        // ③ 全部分块处理完 → 一次性**逆序插队头**（队头 = 入参第一条；
        //    [thumbQueuedSet] 去重兜住"其实已在队列里"的重复）
        if (headInsert.isNotEmpty()) {
            synchronized(lock) {
                var added = false
                for (idx in headInsert.indices.reversed()) {
                    val k = headInsert[idx]
                    if (thumbQueuedSet.add(k.token)) {
                        thumbQueue.addFirst(k)
                        added = true
                    }
                }
                if (added) lock.notifyAll()
            }
        }
    }

    // ===== 传输暂停联动 =====

    fun pauseForTransfer() {
        pausedAtMs = android.os.SystemClock.elapsedRealtime()
        paused = true
    }

    fun resumeAfterTransfer() {
        paused = false
        pausedAtMs = 0L
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * ★ 2.11 第二轮（§3）：**同步页在屏旁路** —— 用户正盯着同步面板看新照片，
     * 小图批不受传输暂停限制（预览批照旧让位：大图带宽大，不与下载抢）。
     *
     * <p>由 SyncScreen 的 DisposableEffect 置位/清位（横滑容器下离开视口即
     * onDispose 回收）；@Volatile 读写无需锁，但置位时唤醒可能挂在暂停上的调度线程。
     */
    @Volatile
    private var syncVisible = false

    fun setSyncVisible(v: Boolean) {
        syncVisible = v
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * 暂停是否已可解除（F8，调用方须持有 [lock]）。
     *
     * <p>DownloadService 的 finally 未必一定执行（异常/进程被杀），不兜底的话
     * `paused` 可能永久为真、各条队列在 [pollNext]/[autoPollNext]/[pollSeamless] 上永久挂起。
     *
     * <p>但**超时本身不足以解除**：整卡传输（几百个文件）跑一两个小时是正常的，
     * 那段时间的暂停是正当的。所以还要求"确实没有传输在跑"
     * （[io.github.bi2qfa.alphasync.transfer.TransferStore.batchRunning]）——
     * 长传输期间纹丝不动，只有"暂停了却没人传"的悬空状态才被解除。
     */
    private fun pauseExpiredLocked(): Boolean {
        val t = pausedAtMs
        if (t <= 0) return false
        if (android.os.SystemClock.elapsedRealtime() - t <= PAUSE_MAX_MS) return false
        return !io.github.bi2qfa.alphasync.transfer.TransferStore.batchRunning
    }

    /**
     * 取件前的暂停检查（含 F8 超时自动解除）；true = 现在应当挂起。
     * 调用方须持有 [lock]（pollNext / autoPollNext / pollSeamless 内部）。
     */
    private fun pausedNow(): Boolean {
        if (!paused) return false
        if (pauseExpiredLocked()) {
            paused = false
            pausedAtMs = 0L
            lock.notifyAll()
            return false
        }
        return true
    }

    // ===== 位图获取（UI 调用） =====

    /**
     * **纯内存查询**（O1）：只查内存 Lru，**不做磁盘解码** —— UI 组合期调用它，
     * 命中即时出图；未命中由调用方在 IO 线程走 [smallThumb]（解码 + 回填）。
     * 桌面列表快速滚动时，解码不再发生在主线程。
     */
    fun peekSmallThumb(key: ObjectCacheKey): ImageBitmap? =
        memoryCache.get(key.token)?.asImageBitmap()

    /** 取小图位图（**阻塞，须在 IO 线程**）：内存 → 磁盘解码 → 回填内存。 */
    fun smallThumb(key: ObjectCacheKey): ImageBitmap? {
        memoryCache.get(key.token)?.let { return it.asImageBitmap() }
        val f = smallDiskFile(key)
        if (f.isFile) {
            // ★ 2.7.0 需求 7：小图字节里没有 EXIF，方向来自大预览 sidecar
            val bmp = runCatching { decodeFileOriented(f) }.getOrNull()
                ?.let { rotateByOrientation(it, orientationOf(key)) }
            if (bmp != null) {
                memoryCache.put(key.token, bmp)
                return bmp.asImageBitmap()
            }
            // 解码失败 = 这个缓存文件坏了（半截/被外部改坏）。删掉它，让调用方
            // 的下一轮 request 走网络回源；留着的话每次点开都先白试一遍解码。
            runCatching { f.delete() }
            // ★ 缓存识别修复：文件已删，READY 状态必须撤销 —— 否则"标了 READY
            //   但文件不存在"会卡住占位图（request() 见 READY 就跳过），只能靠重启。
            //   置 FAILED 后：占位可见，且下一次 request() 会重新入队自愈。
            if (states[key.token] == ThumbState.READY) {
                states[key.token] = ThumbState.FAILED
            }
        } else if (states[key.token] == ThumbState.READY) {
            // ★ 2.11-fix（自愈补齐）：磁盘文件**整个不见了** —— 典型来源是字节预算 LRU
            //   修剪（[pruneAll] 只删文件、**不动 [states]**），或 [atomicWrite] 半途失败。
            //   内存里也没有（上面 miss）→ 这条 READY 是**假的**。撤销它。
            //   不撤销的后果：`request()` 见 READY 就 `continue` → 这张图**永久空白**，
            //   只有重连/清缓存才回来（用户症状正是"有的照片一直不出图"）。
            //   置 FAILED 后由 `request()` 重排自愈（见那里的 ★ 2.11-fix 说明）。
            states[key.token] = ThumbState.FAILED
        }
        return null
    }

    /** 全屏大预览：磁盘缓存 → PTP/IP 拉取（阻塞，须在 IO 线程） */
    fun fetchPreviewBlocking(key: ObjectCacheKey): ImageBitmap? {
        // 用户点开的"注意力信号"（自动队列让位用）：入口就记，不区分命不命中。
        userOpenAt = android.os.SystemClock.elapsedRealtime()
        val f = previewDiskFile(key)
        if (f.isFile) {
            val bmp = runCatching { decodeFileOriented(f) }.getOrNull()
            // ★ 2.7.0 需求 6：按 sidecar 的方向自动转正（缓存 jpg 自身没有 EXIF）
            if (bmp != null) return rotateByOrientation(bmp, orientationOf(key)).asImageBitmap()
            runCatching { f.delete() }     // 坏缓存文件：删掉回源（见 smallThumb 同款说明）
        }
        val h = host ?: io.github.bi2qfa.alphasync.core.ConnectionCenter.host ?: return null
        val bytes = ObjectRepository.fetchVirtualPreview(h, key.path) ?: return null
        // ★ 预览是捆绑包（JPEG + EXIF JSON）——拆开后各写各的，解码只用 JPEG 段；
        //   解不出捆绑包 → 本次取预览失败（唯一格式，无兜底）
        val bundle = splitPreviewBundle(bytes) ?: return null
        val (jpg, json) = bundle
        runCatching {
            atomicWrite(f, jpg)
            writeExifSidecar(key, json)
            noteWritten(jpg.size)
        }
        val bmp = decodeOriented(jpg) ?: return null
        return rotateByOrientation(bmp, orientationOf(key)).asImageBitmap()
    }

    private fun smallDiskFile(key: ObjectCacheKey) =
        File(smallDiskDir, cacheKey(key.token) + ".jpg")

    private fun previewDiskFile(key: ObjectCacheKey) =
        File(previewDiskDir, cacheKey(key.token) + ".jpg")

    // ===== 大预览捆绑包 + EXIF sidecar（2.7.0，用户定版：EXIF 随大预览一并传输/缓存） =====

    /**
     * 拆相机端下发的捆绑包 `[4B 大端 JSON 长][JSON][JPEG]`（预览/小图共用；
     * 相机端唯一格式定义 = `ThumbnailExtractor.bundleJsonJpeg`）。
     *
     * <p>捆绑包是**唯一格式**：解不出合法结构（长度越界 / JPEG SOI 缺失）→ 返回 null，
     * 调用方按失败处理（不坏图、不误读）。
     */
    private fun splitPreviewBundle(b: ByteArray): Pair<ByteArray, String?>? {
        if (b.size <= 4) return null
        val n = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        if (n < 0 || n > b.size - 4) return null
        val jpg = b.copyOfRange(4 + n, b.size)
        if (jpg.size <= 2 || jpg[0] != 0xFF.toByte() || jpg[1] != 0xD8.toByte()) return null
        return jpg to (if (n > 0) String(b, 4, n, Charsets.UTF_8) else null)
    }

    /** EXIF sidecar（预览侧）：与预览 jpg 同目录同键（`<md5>.json`），同刻原子写 → LRU 成对淘汰。 */
    private fun exifDiskFile(key: ObjectCacheKey) =
        File(previewDiskDir, cacheKey(key.token) + ".json")

    /**
     * EXIF sidecar（小图侧，★ 1.0）：与**小图** jpg 同目录同键 —— 预览未到时方向已可用，
     * 与小图成对生灭（pruneAll 孤儿规则 / 成对淘汰原生兼容，修剪代码零改动）。
     */
    private fun smallExifDiskFile(key: ObjectCacheKey) =
        File(smallDiskDir, cacheKey(key.token) + ".json")

    /** 弹窗用：EXIF 原始 JSON 文本（预览 sidecar 优先、小图 sidecar 兜底；都没有 → null）。 */
    fun readExifJson(key: ObjectCacheKey): String? = runCatching {
        exifDiskFile(key).takeIf { it.isFile }?.readText()
            ?: smallExifDiskFile(key).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** 从 sidecar JSON 里取方向（标准 EXIF 1..8）；未知 → 0。 */
    private fun orientationIn(json: String?): Int = runCatching {
        if (json.isNullOrBlank()) 0 else org.json.JSONObject(json).optInt("o", 0)
    }.getOrDefault(0)

    /**
     * 某张图的方向（标准 EXIF 1..8；未知 0）。
     *
     * <p>内存 [orientCache] 挡热路径：小图每次解码都要问方向（需求 7），已学到方向的
     * 不再重复读/解析 sidecar。**只缓存非 0 值** —— 没 sidecar 时不缓存 0，
     * 否则大预览稍后落地后就永远读不到新方向了。
     */
    fun orientationOf(key: ObjectCacheKey): Int {
        orientCache[key.token]?.let { return it }
        val o = orientationIn(readExifJson(key))
        if (o != 0) orientCache[key.token] = o
        return o
    }

    /** 学到新方向：更新缓存 + 发信号（需求 7：外显小图跟着大预览一起转正）。 */
    private fun learnOrientation(key: ObjectCacheKey, o: Int) {
        if (o == 0) return
        if (orientCache.put(key.token, o) != o) {
            // 小图内存里那份可能是旧方向的：evict 让它按新方向重解；
            // epoch 让 UI（FilesScreen 两个缩略图调用点）重画。
            memoryCache.remove(key.token)
            orientationEpoch++
        }
    }

    /** 原子写 sidecar + 学方向 + 节流记账（预览两条写盘路径共用）。 */
    private fun writeExifSidecar(key: ObjectCacheKey, json: String?) {
        writeSidecarAt(exifDiskFile(key), key, json)
    }

    /** ★ 1.0：小图捆绑包到达时写（与 [writeExifSidecar] 同体、仅目录不同）。 */
    private fun writeThumbExifSidecar(key: ObjectCacheKey, json: String?) {
        writeSidecarAt(smallExifDiskFile(key), key, json)
    }

    /** sidecar 写入公共体：原子写 + 学方向 + 节流记账。 */
    private fun writeSidecarAt(f: File, key: ObjectCacheKey, json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            atomicWrite(f, json.toByteArray(Charsets.UTF_8))
            learnOrientation(key, orientationIn(json))
            noteWritten(json.length)
        }
    }

    private fun cacheKey(value: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * **缓存修剪执行器**（O3，2.6 修复）：[pruneAll] 要遍历两个缓存目录、给全部
     * 文件排序 —— 取消条数闸后单目录可能上万文件，就地跑（原先是写盘线程
     * `batchWriteLoop` 里直接调）会把落盘队列憋住几秒、反压收包。
     * 单线程串行执行，天然合并重复请求。
     */
    private val pruneExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CachePrune").apply { isDaemon = true }
    }

    /** 修剪请求的合并标志（正在排队/执行时后续请求直接丢弃，跑完必然收敛）。 */
    private val pruneQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 提交一次后台修剪（去重）。所有需要"立刻收敛"的路径都用它，绝不就地跑。 */
    private fun requestPrune() {
        if (!pruneQueued.compareAndSet(false, true)) return
        pruneExecutor.execute {
            pruneQueued.set(false)
            runCatching { pruneAll() }
        }
    }

    /**
     * 缓存占用的**记账基线**（字节，O2）：[pruneAll] 实测校准；写入路径只在
     * [bytesSinceCalibration] 上累加增量 —— 预算检查因此**零 IO**。
     */
    @Volatile
    private var baseCacheBytes = 0L
    private val bytesSinceCalibration = java.util.concurrent.atomic.AtomicLong(0)

    /** 当前缓存占用估算（字节；软值，修剪后收敛到实测）。 */
    private fun cacheBytesUsed(): Long = baseCacheBytes + bytesSinceCalibration.get()

    /**
     * 自动通道的**预算闸**（O2，用户定版"预算内拉取"）。
     *
     * <p>问题：取消条数闸后，大库预览（0.5MB/张）会把字节闸填满，[pruneAll]
     * 按 LRU 淘汰**最早拉的**，下次启动又重拉 —— 缓存回环、流量白烧。
     * 现在占用达到上限的 [CACHE_BUDGET_NUM]/[CACHE_BUDGET_DEN]（90%）就**停自动传输**
     * （留 10% 给用户点开大图/翻页的按需写入，这些不受闸）。
     *
     * <p>恢复路径：设置页调大上限 / 清缓存 / 重连 / 重启 —— 任一发生即清除暂停、
     * 按新预算继续（见 [applyCacheBudgetPaused] 的调用点）。
     */
    private fun cacheBudgetExceeded(): Boolean {
        val limit = SettingsRepo.cacheLimitBytes
        if (limit <= 0) return false
        return cacheBytesUsed() * CACHE_BUDGET_DEN >= limit * CACHE_BUDGET_NUM
    }

    /**
     * 预算暂停状态（UI 提示用，用户要求"超过缓存上限应该有提示"）。
     *
     * <p>面板（FloatingNav）在自动进度行后缀"（已达缓存上限）"；
     * 首次触发另弹一次性 Toast（见 [applyCacheBudgetPaused]）。
     */
    var cacheBudgetPaused by mutableStateOf(false)
        private set

    @Volatile
    private var lastBudgetToastAt = 0L

    private fun applyCacheBudgetPaused(paused: Boolean) {
        if (cacheBudgetPaused == paused) return
        cacheBudgetPaused = paused
        synchronized(lock) { lock.notifyAll() }     // 唤醒挂起的自动调度器重查
        if (!paused) return
        android.util.Log.i(
            "ThumbStore",
            "缓存预算已满（${cacheBytesUsed()}/${SettingsRepo.cacheLimitBytes} B），自动预览传输暂停",
        )
        // 一次性 Toast：跨"重连→重摆→再暂停"的循环要有冷却，避免刷屏
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBudgetToastAt < BUDGET_TOAST_COOLDOWN_MS) return
        lastBudgetToastAt = now
        if (!::appContext.isInitialized) return
        val ctx = appContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    ctx,
                    "缓存已达上限，自动预览传输已暂停",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * **联合字节闸**（2.6；v3 起目录平铺、无设备子目录）：小图目录 + 预览目录
     * **合并成一个 LRU 池**，总占用超过 [SettingsRepo.cacheLimitBytes] 就从最旧开始删。
     *
     * <p>★ 为什么是联合而不是各算各的：历史上小图 24MB / 预览 96MB 各自为政，
     * 换一台相机（新子目录）时上限**无声翻倍**；总量也不受控（120MB 起步、
     * 每多一台相机再加一份）。合并成一个池之后，"这台手机最多为图像缓存花多少
     * 空间"才真正等于设置里那个数。
     *
     * <p>顺带做两件卫生/记账：清掉原子写的游离 `.tmp`；
     * **校准预算记账基线**（[baseCacheBytes]，O2 的零 IO 预算检查靠它）。
     *
     * <p>★ O3（2.6）：本函数只允许在 [pruneExecutor] 上跑（经 [requestPrune] 提交）——
     * 全量遍历 + 排序是重活，就地跑（原先是写盘线程直接调）会把落盘队列憋住。
     */
    private fun pruneAll() {
        val limit = SettingsRepo.cacheLimitBytes
        // 先取走增量：之后发生的写入只有一种归属（要么被本次实测的 total 含住、
        // 要么留给增量），不会两头都算
        bytesSinceCalibration.getAndSet(0)
        val all = ArrayList<File>(256)
        // v3（2026-10-01，方案 A）：缓存平铺、无设备子目录 —— 直接遍历两个根目录
        for (base in arrayOf(smallDiskDir, previewDiskDir)) {
            val fs = runCatching { base.listFiles() }.getOrNull() ?: continue
            for (f in fs) {
                if (f.isDirectory) {
                    // 旧格式的设备子目录残留（理论上 init 已清，防御性一并扫平）
                    runCatching { f.deleteRecursively() }
                } else if (f.name.endsWith(".tmp")) {
                    // ★ F8（2.6 修复）：只清**陈年**残留（进程被杀留下的）。
                    //   新 .tmp 可能正是在途的原子写 —— 删了它，[atomicWrite]
                    //   的 renameTo 失败路径也救不回来（tmp 已不存在），
                    //   这次落盘会静默丢失。
                    val age = System.currentTimeMillis() - f.lastModified()
                    if (age > TMP_STALE_MS) {
                        runCatching { f.delete() }
                    }
                } else if (f.name.endsWith(".json")) {
                    // 2.7.0：EXIF sidecar —— 只在其 jpg 还在时保留（孤儿一并清掉），
                    // 字节也计入总闸（每张几百 B，可忽略）
                    if (File(base, f.nameWithoutExtension + ".jpg").isFile) {
                        all.add(f)
                    } else {
                        runCatching { f.delete() }
                    }
                } else if (f.isFile) {
                    all.add(f)
                }
            }
        }
        if (all.isEmpty()) {
            baseCacheBytes = 0L
            return
        }
        var total = all.sumOf { it.length() }
        baseCacheBytes = total          // ★ O2：实测校准（含 .json sidecar 的字节）
        if (total <= limit) return
        val ordered = runCatching { all.sortedBy { it.lastModified() } }.getOrNull() ?: return
        for (f in ordered) {
            if (total <= limit) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                // 2.7.0：jpg 被淘汰时它的 EXIF sidecar 一并删（成对进出）
                if (f.name.endsWith(".jpg")) {
                    runCatching { File(f.parentFile, f.nameWithoutExtension + ".json").delete() }
                }
            }
        }
        baseCacheBytes = total          // 修剪后的实测值
    }

    /**
     * 写盘路径用的**节流记账**：累计写入超过 [PRUNE_EVERY_BYTES] 才真跑一次
     * [pruneAll]。
     *
     * <p>节流的安全性：闸是"总量上限"，滞后一点修剪只意味着**短暂超额**（最多
     * 多存一个阈值的量），下一次必然收敛；而换来的是自动传输全程只做几次
     * 目录遍历而不是几百次。小图单张 6 KB、预览单张约 500 KB —— 攒到 32 MB
     * 大约要 60 张预览，即"自动传输平均每 60 张才修剪一次"。
     * 上限变更（[onLimitChanged]）与启动收敛不走节流，直接全量跑。
     */
    private fun noteWritten(bytes: Int) {
        // 预算记账增量（O2）：与修剪节流各自独立计
        bytesSinceCalibration.addAndGet(bytes.toLong())
        if (writtenSincePrune.addAndGet(bytes.toLong()) >= PRUNE_EVERY_BYTES) {
            writtenSincePrune.set(0)
            requestPrune()      // ★ O3：重活丢给 CachePrune 线程，不阻塞落盘
        }
    }

    /**
     * 设置页改上限后立刻收敛（由 [SettingsRepo.updateCacheLimitBytes] 触发）。
     *
     * <p>上限**调大**时顺带解除预算暂停（O2 的恢复路径之一）：清暂停标志 → 唤醒
     * 挂起的自动调度器 → 按新预算继续拉。调小时由 [requestPrune] 收敛占用。
     */
    fun onLimitChanged() {
        applyCacheBudgetPaused(false)
        requestPrune()
    }

    /**
     * **原子写**：先写同目录 `.tmp` 再改名。
     *
     * <p>直接 `writeBytes` 的话，进程在写一半时被杀/断电会留下一个**永久半截文件**，
     * 而缓存键（path+size+mtime）仍指向它 —— 下次点开解码失败，还得先白试一遍。
     * 改名在同一文件系统内是原子的，要么旧文件要么新文件，不存在中间态。
     */
    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                // renameTo 在目标已存在时个别实现会失败（Android 上通常覆盖成功）：
                // 删掉目标再来一次；再失败就把临时文件也清掉，不占地。
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
    }

    /** 缓存占用（小图 + 预览，字节），设置页显示用。 */
    fun cacheSizeBytes(): Long {
        fun dirSize(d: File): Long =
            runCatching { d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        return dirSize(smallDiskDir) + dirSize(previewDiskDir)
    }

    /** 清空全部图像缓存（设置页"清除缓存"）：内存 + 磁盘，队列状态复位。 */
    fun clearCache() {
        synchronized(lock) {
            queue.clear(); queuedSet.clear()
            autoQueue.clear(); autoQueuedSet.clear()
            // ★ 2.10：两段式队列同清（"清缓存"= 队列状态复位；随后 restartAutoFetch 全量重建）
            thumbQueue.clear(); thumbQueuedSet.clear()
            previewQueue.clear(); previewQueuedSet.clear()
            seamlessPhase = FetchPhase.IDLE
            batchDone = 0; batchTotal = 0
            autoDone = 0; autoTotal = 0
            autoGen++
            countedSmall.clear()
            countedAuto.clear()
        }
        thumbRetryCount.clear()
        autoRetryCount.clear()
        pendingWrites.clear()
        thumbBusy = false
        autoBusy = false
        // O2 恢复路径：缓存清空 → 账归零、解除预算暂停
        bytesSinceCalibration.set(0)
        baseCacheBytes = 0L
        applyCacheBudgetPaused(false)
        states.clear()
        memoryCache.evictAll()
        orientCache.clear()
        orientationEpoch++   // 方向随缓存归零：发信号让 UI 重画（未转正的原始朝向）
        runCatching { smallDiskDir.deleteRecursively() }
        runCatching { previewDiskDir.deleteRecursively() }
        smallDiskDir.mkdirs()
        previewDiskDir.mkdirs()
    }

    fun reset() {
        synchronized(lock) {
            queue.clear()
            queuedSet.clear()
            autoQueue.clear()
            autoQueuedSet.clear()
            // ★ 2.10（方案 §5.5）：断开 = 两段式队列同清（重连后会重新全量扫描、
            //   磁盘已缓存的跳过，只补缺口）+ 阶段归 IDLE。
            thumbQueue.clear()
            thumbQueuedSet.clear()
            previewQueue.clear()
            previewQueuedSet.clear()
            seamlessPhase = FetchPhase.IDLE
            batchDone = 0
            batchTotal = 0
            autoDone = 0
            autoTotal = 0
            autoGen++          // 断开重连：在途的自动/无感条目作废（见 autoFetchLoop）
            paused = false
            pausedAtMs = 0L
            // ★ F7（2.6 修复）：**清掉 host** —— 旧实现只清队列，断连后旧相机的
            //   地址仍留在 host 里；重连到另一台相机（或同一台换会话）时，
            //   小图/自动批会拿着过期 host 去拉（`processThumbBatch` 里
            //   `host ?: ConnectionCenter.host` 永远走不到兜底分支）。
            host = null
            // ★ 审修复（M1）：进度"已计入"集合必须与 countSmallLocked/countAutoLocked
            //   （契约要求持锁）同步清 —— 原来放在锁外清，会与在途批次竞争：
            //   batchDone 已 +1 但 token 被清掉 → 同一 token 回来再计一次（进度虚高）。
            countedSmall.clear()
            countedAuto.clear()
            lock.notifyAll()
        }
        states.clear()
        memoryCache.evictAll()
        thumbRetryCount.clear()
        autoRetryCount.clear()
        pendingWrites.clear()
        thumbBusy = false
        autoBusy = false
        applyCacheBudgetPaused(false)    // O2：新会话按预算重新评估（缓存文件本身保留）
        lastWarmupKeys = emptyList()   // 断开：名单作废，restartAutoFetch 会走"清空"分支
        warmupPaths = emptySet()       // ★ 2.11-fix：名单在册索引同步作废（见 request 分流）
    }

    // ===== 拉取线程 =====

    private fun fetchLoop() {
        // 启动时先做一次全量收敛（O3：丢给 CachePrune 线程，不在本线程就地进行）。
        // 顺带校准预算记账基线（O2）——几万文件的遍历不挡首轮拉取。
        requestPrune()
        // ★ 2.7.0 批量流式：**成批取**（[THUMB_BATCH_K] 张/批，一条数据连接一次取完）。
        //   只有本线程做"入飞"，inFlight.size 判定无竞争；完成由池线程自己摘除。
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
        while (true) {
            // F1 连带：UI 的"是否仍在推进"信号（进度只计成功后不能再拿 done<total 判定）
            thumbBusy = inFlight.isNotEmpty() || synchronized(lock) { queue.isNotEmpty() }
            if (inFlight.size >= SMALL_CONCURRENCY) {
                Thread.sleep(10)                       // 在飞满：等一个收尾腾位
                continue
            }
            val batch = ArrayList<ObjectCacheKey>(THUMB_BATCH_K)
            while (batch.size < THUMB_BATCH_K) {
                val k = pollNext() ?: break
                states[k.token] = ThumbState.LOADING
                batch.add(k)
            }
            if (batch.isEmpty()) {
                if (inFlight.isEmpty()) idleWait() else Thread.sleep(10)
                continue
            }
            val id = "b" + batch[0].token
            inFlight[id] = smallDlPool.submit {
                try {
                    processThumbBatch(batch)
                } finally {
                    inFlight.remove(id)
                }
                // 进度在 [processThumbBatch] 内按**成功项**逐条计入（countSmallLocked，
                // 幂等）—— 磁盘命中与下载交付共用同一个计数函数，识别/拉取两条路
                // 不会把同一张算两次（F1 + 缓存识别修复）。
            }
        }
    }

    /**
     * 取一批小图（在 [smallDlPool] 线程上跑，2.7.0）：
     * 逐张先查磁盘（命中即 READY）→ 缺口拼成**一次批量请求** → 一条连接取回 →
     * 逐张解码/写盘/收敛状态。加固：任何异常只记日志，绝不带死线程。
     *
     * <p>★ F1/F2（2.6 修复）：
     * <ul>
     *   <li>返回**真正成功**的条数（磁盘命中 + 字节已交付落盘队列）——进度不再
     *       把失败计成"完成"；</li>
     *   <li>未交付的条目**放回队头重试**（带退避），连续失败 ≥ [ITEM_RETRY_LIMIT]
     *       次才标 FAILED —— 旧实现"整批失败即全部终态、永不重试"，这是
     *       "提示加载完成但小图没加载"的另一半根因。</li>
     * </ul>
     *
     * @return 本批成功（磁盘命中或已交付下载）的条目数
     */
    private fun processThumbBatch(batch: List<ObjectCacheKey>): Int {
        var ok = 0
        val need = ArrayList<ObjectCacheKey>(batch.size)
        for (key in batch) {
            // ★ 磁盘命中检查（2.6 修复，原样保留）：重启/重连后 states 为空、
            //   request() 会把整份名单重新入队 —— 没有这道检查时磁盘里已有的缓存
            //   会被原样重拉重写。**快判存在性**（[thumbOnDisk]），存在才解码校验：
            //   解不开=坏文件，落回网络自愈；解开了就进内存缓存。
            val cached = if (!thumbOnDisk(key)) {
                null
            } else {
                runCatching { decodeFileOriented(smallDiskFile(key)) }.getOrNull()
                    ?.let { rotateByOrientation(it, orientationOf(key)) }
            }
            if (cached != null) {
                memoryCache.put(key.token, cached)
                thumbRetryCount.remove(key.token)
                synchronized(lock) {
                    states[key.token] = ThumbState.READY
                    countSmallLocked(key.token)
                }
                ok++
            } else {
                need.add(key)
            }
        }
        if (need.isEmpty()) return ok
        val done = BooleanArray(need.size)
        try {
            val h = host ?: io.github.bi2qfa.alphasync.core.ConnectionCenter.host
            if (h == null) {
                // 无 host：本会话已收场（reset 会清队列），重试也无处可去 → 终态
                for (k in need) states[k.token] = ThumbState.FAILED
                return ok
            }
            bulkGate.acquireUninterruptibly()
            try {
                // ★ M4（2.6 修复）：**进闸后重查磁盘** —— 等闸期间自动通道可能已把
                //   同一张小图写入磁盘；不重查就会重复下载（logcat 实测：一次会话里
                //   同一张被两条通道各拉一遍，~240 项）。快判存在性即可（原子写落定）。
                val dlIdx = ArrayList<Int>(need.size)
                val dlKeys = ArrayList<ObjectCacheKey>(need.size)
                val lateHits = ArrayList<ObjectCacheKey>()
                for (i in need.indices) {
                    if (thumbOnDisk(need[i])) {
                        done[i] = true
                        lateHits.add(need[i])
                    } else if (isPendingWrite(need[i], PtpCodec.KIND_THUMB)) {
                        // 另一条通道已交付、在途落盘（写盘几十 ms 的窗口）：算满足，
                        // 状态由写盘侧自己收敛（READY/FAILED），这里不重复下载
                        done[i] = true
                    } else {
                        dlIdx.add(i)
                        dlKeys.add(need[i])
                    }
                }
                if (lateHits.isNotEmpty()) {
                    synchronized(lock) {
                        for (k in lateHits) {
                            states[k.token] = ThumbState.READY
                            thumbRetryCount.remove(k.token)
                        }
                    }
                }
                if (dlKeys.isNotEmpty()) {
                    val items = dlKeys.map { it.path to PtpCodec.KIND_THUMB }
                    val t = ObjectRepository.openBatchTicket(h, items)
                    if (t != null) {
                        val labels = dlKeys.map { it.path }
                        ObjectRepository.downloadBatch(
                            t, dlKeys.size, labels = labels,
                            // ★ O4：重试**换新票**（一次性令牌用后即废，同票重试必撞"令牌无效"）
                            refill = { ObjectRepository.openBatchTicket(h, items) },
                        ) { di, bytes ->
                            if (bytes == null || di !in dlIdx.indices) return@downloadBatch
                            // ★ 2.7.0：解码/写盘挪到 [batchWriteLoop]，收包线程立刻回去收下一项
                            //   （put 在标记 done 之前：put 抛异常时该项按"未交付"进重试）
                            markPendingWrite(dlKeys[di], PtpCodec.KIND_THUMB)
                            writeQueue.put(PendingWrite(dlKeys[di], PtpCodec.KIND_THUMB, bytes))
                            done[dlIdx[di]] = true
                        }
                    }
                }
            } finally {
                bulkGate.release()
            }
        } catch (t: Throwable) {
            android.util.Log.w("ThumbStore", "thumb batch failed", t)
        }
        // 收尾（唯一出口）：交付计数（幂等）+ 未交付进重试判定
        val unresolved = ArrayList<ObjectCacheKey>()
        val successTokens = ArrayList<String>()
        for (i in need.indices) {
            val st = states[need[i].token]
            if (done[i]) {
                thumbRetryCount.remove(need[i].token)
                successTokens.add(need[i].token)
                ok++
            } else if (st != ThumbState.READY && st != ThumbState.FAILED) {
                unresolved.add(need[i])
            }
        }
        if (successTokens.isNotEmpty()) {
            synchronized(lock) { for (tok in successTokens) countSmallLocked(tok) }
        }
        val backoff = requeueForRetry(unresolved, queue, queuedSet, thumbRetryCount) {
            states[it.token] = ThumbState.FAILED
        }
        if (backoff > 0) runCatching { Thread.sleep(backoff) }
        return ok
    }

    /**
     * F2：把"未交付"的条目放回**队头**重试（带连续失败计数与退避）。
     *
     * <p>纪律：同一 token 连续失败 < [ITEM_RETRY_LIMIT] 次 → 放回队头（下批优先重试，
     * 同时天然形成"先重试旧的、再拉新的"的顺序）；达到上限 → 交 [giveUp] 标终态。
     * 计数在成功/命中时清零，所以"偶发失败几次后成功"不会累积到上限。
     *
     * @return 建议退避时长（ms；0 = 无需退避）。调用方在**释放并发闸之后**再睡，
     *   否则会白白占着全局单流闸。
     */
    private fun requeueForRetry(
        keys: List<ObjectCacheKey>,
        queue: ArrayDeque<ObjectCacheKey>,
        queuedSet: HashSet<String>,
        counter: java.util.concurrent.ConcurrentHashMap<String, Int>,
        giveUp: (ObjectCacheKey) -> Unit,
    ): Long {
        if (keys.isEmpty()) return 0L
        var maxAttempt = 0
        val back = ArrayList<ObjectCacheKey>(keys.size)
        synchronized(lock) {
            for (k in keys) {
                val n = (counter[k.token] ?: 0) + 1
                if (n >= ITEM_RETRY_LIMIT) {
                    counter.remove(k.token)
                    giveUp(k)
                    continue
                }
                counter[k.token] = n
                if (n > maxAttempt) maxAttempt = n
                if (queuedSet.add(k.token)) back.add(k)
            }
            // 队头放回（倒序 addFirst 保持原顺序）
            for (i in back.indices.reversed()) queue.addFirst(back[i])
            if (back.isNotEmpty()) lock.notifyAll()
        }
        return if (maxAttempt > 0) {
            minOf(ITEM_RETRY_BACKOFF_MS * maxAttempt, ITEM_RETRY_BACKOFF_MAX_MS)
        } else {
            0L
        }
    }

    /** 非阻塞取一条小图任务：队列空或暂停中返回 null（等待逻辑在 [fetchLoop] 调度器里）。 */
    private fun pollNext(): ObjectCacheKey? = synchronized(lock) {
        if (pausedNow()) return@synchronized null
        val next = queue.removeFirstOrNull() ?: return@synchronized null
        queuedSet.remove(next.token)
        next
    }

    /**
     * 调度器无事可做时的等待（队列空 + 无在飞，或暂停中）：由
     * `request()/resume()/reset()` 的 `notifyAll` 唤醒；1 秒超时是兜底 ——
     * 万一哪条唤醒路径漏了 notify，也不会永久睡死。
     */
    private fun idleWait() {
        synchronized(lock) {
            runCatching { lock.wait(1000) }
        }
    }

    // ===== 自动传输预览图（2.6，独立线程，见类注释） =====

    /**
     * 连接预热时调用（[io.github.bi2qfa.alphasync.core.ConnectionCenter.startThumbBatch]）：
     * 整体替换自动队列。
     *
     * <p>这里**不做**已缓存过滤：`autoTotal` 要如实等于列表图片数（进度"N 张全部
     * 就绪"才是用户能理解的语义），已缓存的条目在 [autoFetchLoop] 里靠磁盘存在性
     * 判定"瞬间跳过"（两次 `isFile`，可忽略）。
     *
     * <p>★ 2.10：本函数（旧单队列 + 开关守卫）已被 [startSeamlessFetch] 取代，**不再
     * 被调用**（保留待 B 阶段清理，见工单）。原"自动传输预览图"开关守卫已删 ——
     * 无感通道随连接常开，不再有开关可判。
     */
    fun startAutoFetch(keys: List<ObjectCacheKey>) {
        // 新一轮名单：上一轮的重试计数作废（否则"上轮失败过 2 次"的条目
        // 在新一轮里一失败就直接到上限，平白少一次机会）
        autoRetryCount.clear()
        val gen0: Int
        synchronized(lock) {
            host = io.github.bi2qfa.alphasync.core.ConnectionCenter.host
            autoQueue.clear()
            autoQueuedSet.clear()
            countedAuto.clear()
            autoTotal = keys.size
            autoDone = 0
            autoGen++
            gen0 = autoGen
        }
        // ★ 缓存识别（2.6 修复，用户定版语义）：**先扫磁盘再建队列** ——
        //   上一次会话已经缓存掉的条目直接标 READY / **随扫描进度计入 autoDone**
        //   （"识别过程也走进度条"：500 张、前 200 已缓存 → 进度条快速冲到 200/500），
        //   队列里只留真正缺的（缺小图或缺预览），网络从 201 开始。
        //   典型情形：整库已缓存 → 重启后面板立刻 N/N，零网络、零解码。
        //   ★ 分块推进（[SCAN_CHUNK]）：识别是纯本地 stat，不占网络；但几万条名单
        //   一次性扫完才落地会让进度条"憋住不动"，所以每块扫完就落地一次。
        val sorted = keys.sortedBy { it.path }   // 排序放扫描线程：路径序 = 相机顺序读
        diskScanPool.execute {
            val chunkReady = ArrayList<ObjectCacheKey>(SCAN_CHUNK)
            val chunkPending = ArrayList<ObjectCacheKey>(SCAN_CHUNK)
            for ((idx, key) in sorted.withIndex()) {
                val t = thumbOnDisk(key)
                val p = previewOnDisk(key)
                if (t) states[key.token] = ThumbState.READY   // 小图已有：列表可直接显示
                if (t && p) chunkReady.add(key) else chunkPending.add(key)
                if ((idx + 1) % SCAN_CHUNK == 0 || idx == sorted.lastIndex) {
                    synchronized(lock) {
                        if (autoGen != gen0) return@execute   // 已被新一轮（开关/重连）取代：丢弃
                        for (k in chunkReady) countAutoLocked(k.token)
                        for (k in chunkPending) {
                            if (autoQueuedSet.add(k.token)) autoQueue.add(k)
                        }
                        lock.notifyAll()
                    }
                    chunkReady.clear()
                    chunkPending.clear()
                    if (idx < sorted.lastIndex) {
                        runCatching { Thread.sleep(SCAN_CHUNK_GAP_MS) }
                    }
                }
            }
        }
    }

    /**
     * 停掉无感通道（★ 2.10：原"关掉开关时调用"的语义平移 —— 现在由 [restartAutoFetch]
     * 的"断开/无名单"分支等调用）：丢弃两段队列（正在拉的那批让它拉完，不用中断）。
     */
    fun stopAutoFetch() {
        synchronized(lock) {
            autoQueue.clear()
            autoQueuedSet.clear()
            thumbQueue.clear()
            thumbQueuedSet.clear()
            previewQueue.clear()
            previewQueuedSet.clear()
            seamlessPhase = FetchPhase.IDLE
            autoTotal = 0
            autoDone = 0
            autoGen++
            lock.notifyAll()
        }
        autoRetryCount.clear()
        countedAuto.clear()
        // ★ 2.11-fix：通道停 = 名单作废（调用点只有"断开/无名单"分支）→ 同步清在册索引，
        //   [request] 之后会走"名单外插队头"那一支，小图不会没人拉。
        warmupPaths = emptySet()
        autoBusy = false
    }

    /** 非阻塞取一条自动任务：队列空或暂停中返回 null（等待逻辑在 [autoFetchLoop] 调度器里）。 */
    private fun autoPollNext(): ObjectCacheKey? = synchronized(lock) {
        if (pausedNow()) return@synchronized null
        val next = autoQueue.removeFirstOrNull() ?: return@synchronized null
        autoQueuedSet.remove(next.token)
        next
    }

    /**
     * 无感缩略图**调度器**（★ 2.10：两段式；2.7.0 起批量单流）。
     *
     * <p>取件 = [pollSeamless]（先 [thumbQueue] 后 [previewQueue]），**每批只装同一种
     * kind**（小图批全 T、预览批全 P）：小图段（SWEEP）自然取空后进入预览段（PREVIEWS），
     * 阶段推进免维护（[seamlessPhase] 仅作日志，见方案 §7.1）。
     *
     * <p>纪律（每条都对应一个真实约束，改这里之前先读）：
     * <ul>
     *   <li><b>让位</b>：用户点开大图（[userOpenAt] 5 秒窗口）→ 调度器**不再放新批**，
     *       在飞的批照常收场（"让位只在批边界生效"）。</li>
     *   <li><b>暂停</b>：[paused] 同上 —— 只挡新批入飞。</li>
     *   <li><b>预算闸</b>：缓存占用到上限 90%（[cacheBudgetExceeded]）→ 不再放新批，
     *       队列/进度保持不变，等恢复动作（调大上限/清缓存/重连/重启）后继续 ——
     *       绝不"拉满再淘汰再重拉"地回环。</li>
     *   <li><b>批量单流</b>：每批 [SEAMLESS_THUMB_K]（预览批 [SEAMLESS_PREVIEW_K]）个条目
     *       在**一条连接**上取回；跨通道由 [bulkGate] 互斥 —— 同一时刻只有一条数据流
     *       （多流在 2.4GHz 上争抢实测更慢）。</li>
     *   <li><b>批内顺序</b>：取件后按**队列方向**重排（[orderBatchForSd]）—— 既让批内顺序
     *       = 列表顺序（用户要求"从上到下"），又保住"同目录连续文件名"的顺序读局部性。</li>
     *   <li><b>失败回队头</b>（F2）：未就绪条目由 [runSeamlessBatch] 放回**它自己的队列头**
     *       重试（连续失败 ≥ [ITEM_RETRY_LIMIT] 次才收场），不静默丢失。</li>
     *   <li><b>孤立</b>：任何异常都只影响这一个批（带不死线程）。</li>
     *   <li><b>代次</b>：停通道/清缓存/重连（autoGen 变）→ 在飞批不计进度、不接着跑。</li>
     * </ul>
     */
    private fun autoFetchLoop() {
        // 只有本线程做"入飞"，inFlight.size 判定无竞争；完成由批线程自己摘除。
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
        while (true) {
            // F1 连带：UI 的"是否仍在推进"信号（进度只计成功后不能再拿 done<total 判定）
            // ★ 2.10：推进信号改看无感两段队列（旧 autoQueue 已不参与取件）
            autoBusy = inFlight.isNotEmpty() ||
                synchronized(lock) { thumbQueue.isNotEmpty() || previewQueue.isNotEmpty() }
            if (inFlight.size >= AUTO_CONCURRENCY) {
                Thread.sleep(10)                       // 在飞满：等一个批收尾腾位
                continue
            }
            // ★ O2 预算闸（用户定版"预算内拉取"）：缓存占用到上限 90% → 不再放新批
            //   （在飞的批照常收场）。队列/进度**保持不变**，等恢复动作（调大上限/
            //   清缓存/重连/重启）后从这里继续 —— 绝不"拉满再淘汰再重拉"地回环。
            //   ★ 只在"确实还有活要干"时才算/提示"预算暂停"：两段队列都空时不该再挂提示。
            if (cacheBudgetExceeded() &&
                synchronized(lock) { thumbQueue.isNotEmpty() || previewQueue.isNotEmpty() }
            ) {
                applyCacheBudgetPaused(true)
                synchronized(lock) { runCatching { lock.wait(2000) } }
                continue
            }
            applyCacheBudgetPaused(false)
            // ===== 让位窗口：窗口内不放新批（在飞的照常收场） =====
            val rest = YIELD_WAIT_MS - (android.os.SystemClock.elapsedRealtime() - userOpenAt)
            if (rest > 0) {
                synchronized(lock) { runCatching { lock.wait(rest) } }
                continue
            }
            // ★ 代次必须在**取任务前**快照：stopAutoFetch/新一轮会 gen++ 并清队列，
            //   若先取后读，这个刚取出的条目会被当成"新一轮"的任务跑下去。
            //   先读后取：期间代次一旦变了，runSeamlessBatch 的边界检查会立刻作废它。
            // ★ 2.10 两段式：攒够**本 kind 的批大小**（小图 [SEAMLESS_THUMB_K] /
            //   预览 [SEAMLESS_PREVIEW_K]，★ 2.11-fix 起两者独立）个同 kind 条目
            //   （或队列取空）成一批 ——
            //   取件靠 [pollSeamless]（先 thumbQueue 后 previewQueue）；kind 混了就把异种
            //   条目放回它自己队列头、本批到此为止（先小图批、后预览批，不存在 T+P 混批）。
            val gen = autoGen
            // ★ 2.11-fix：批大小**按 kind 各自取**（原先是小图/预览共用一个 AUTO_BATCH_K）。
            val batch = ArrayList<ObjectCacheKey>(
                if (SEAMLESS_PREVIEW_K > SEAMLESS_THUMB_K) SEAMLESS_PREVIEW_K else SEAMLESS_THUMB_K,
            )
            var batchKind = 0
            var batchK = SEAMLESS_THUMB_K
            while (batch.size < batchK) {
                val polled = pollSeamless() ?: break
                val (k, kind) = polled
                if (batch.isEmpty()) {
                    batchKind = kind
                    batchK = if (kind == PtpCodec.KIND_PREVIEW) SEAMLESS_PREVIEW_K else SEAMLESS_THUMB_K
                } else if (kind != batchKind) {
                    pushBackSeamless(polled)
                    break
                }
                batch.add(k)
            }
            if (batch.isEmpty()) {
                if (inFlight.isEmpty()) idleWait() else Thread.sleep(10)
                continue
            }
            // ★ 2.11-fix（顺序修复 R3）：批内排序改为**方向感知**（见 [orderBatchForSd]）——
            //   原实现无条件 `sortBy { it.path }`（升序），而队列是"新→旧"（path 降序），
            //   于是每一批都被**翻成老→新**：每 K 张一组、组内倒序，用户看到的就是
            //   "加载顺序像随机"。方向感知后组内顺序 = 列表顺序，且仍是连续文件名。
            orderBatchForSd(batch)
            seamlessPhase =
                if (batchKind == PtpCodec.KIND_PREVIEW) FetchPhase.PREVIEWS else FetchPhase.SWEEP
            val id = "b" + batch[0].token
            inFlight[id] = autoCyclePool.submit {
                try {
                    runSeamlessBatch(batch, batchKind, gen)
                } finally {
                    inFlight.remove(id)
                }
                // 进度在 [runSeamlessBatch] 内按**成功条目**逐条计入（countAutoLocked，
                // 幂等，且带代次校验）—— 磁盘预扫与下载共用同一计数函数，不重复计。
            }
        }
    }

    /**
     * 跑**一批**文件周期（在 [autoCyclePool] 线程上；纪律见 [autoFetchLoop]，2.7.0 批量流式）。
     *
     * <p>★ 2.10：本函数已被 [runSeamlessBatch]（同 kind 两段式批）取代，**不再被调用**
     * （保留待 B 阶段清理，见工单）。下面的 [startAutoFetch]/[autoPollNext] 同此。
     *
     * <p>批内把各文件缺的对象（小图/预览）拼成**一次批量请求**，在一条数据连接上
     * 连续取回；逐项落盘与单文件路径完全一致（小图 → 缓存 + READY；预览 → 拆捆绑包
     * + sidecar + 方向信号）。
     *
     * <p>★ F1/F2（2.6 修复）：
     * <ul>
     *   <li>返回**真正就绪**的文件数（需求项全部交付，或本来就全命中磁盘）——
     *       进度只计成功；</li>
     *   <li>未就绪的文件**放回队头重试**（带退避），连续失败 ≥ [ITEM_RETRY_LIMIT]
     *       次才收场。旧实现"失败即终态 + 进度照加"，正是"提示加载完成、
     *       实际部分预览图没加载"的根。</li>
     * </ul>
     *
     * @return 本批成功（该取的都取到）的文件数
     */
    private fun runAutoBatch(batch: List<ObjectCacheKey>, gen: Int): Int {
        // 组装请求项：逐文件判磁盘命中，只把缺的放进批量请求（命中即算就绪）
        val reqKeys = ArrayList<ObjectCacheKey>()
        val reqKinds = ArrayList<Int>()
        val reqOwner = ArrayList<Int>()
        val fileOk = BooleanArray(batch.size) { true }
        for (fi in batch.indices) {
            val key = batch[fi]
            if (!smallDiskFile(key).isFile) {
                reqKeys.add(key)
                reqKinds.add(PtpCodec.KIND_THUMB)
                reqOwner.add(fi)
            }
            if (!previewDiskFile(key).isFile) {
                reqKeys.add(key)
                reqKinds.add(PtpCodec.KIND_PREVIEW)
                reqOwner.add(fi)
            }
        }
        val done = BooleanArray(reqKeys.size)
        try {
            if (reqKeys.isNotEmpty()) {
                // ===== 批边界：让位窗口（窗口内等待；开关被关/重连换名单则作废） =====
                while (true) {
                    if (autoGen != gen) return fileOk.count { it }
                    val rest = YIELD_WAIT_MS - (android.os.SystemClock.elapsedRealtime() - userOpenAt)
                    if (rest <= 0) break
                    synchronized(lock) { runCatching { lock.wait(rest) } }
                }
                val h = host ?: io.github.bi2qfa.alphasync.core.ConnectionCenter.host
                if (h != null) {
                    bulkGate.acquireUninterruptibly()
                    try {
                        // ★ M4（2.6 修复）：**进闸后重查磁盘** —— 等闸期间小图通道可能
                        //   已把同一项写入；不重查就是重复下载（logcat 实测 ~240 项/会话）。
                        val dl = ArrayList<Int>(reqKeys.size)
                        for (i in reqKeys.indices) {
                            if (onDisk(reqKeys[i], reqKinds[i]) ||
                                isPendingWrite(reqKeys[i], reqKinds[i])
                            ) {
                                // 已落盘，或另一条通道已交付、在途落盘 → 都算满足
                                done[i] = true
                            } else {
                                dl.add(i)
                            }
                        }
                        if (dl.isNotEmpty()) {
                            val items = dl.map { reqKeys[it].path to reqKinds[it] }
                            val labels = dl.map { i ->
                                (if (reqKinds[i] == PtpCodec.KIND_PREVIEW) "P " else "T ") + reqKeys[i].path
                            }
                            val t = ObjectRepository.openBatchTicket(h, items)
                            if (t != null) {
                                ObjectRepository.downloadBatch(
                                    t, items.size, labels = labels,
                                    // ★ O4：重试换新票（一次性令牌用后即废）
                                    refill = { ObjectRepository.openBatchTicket(h, items) },
                                ) { di, bytes ->
                                    if (bytes == null || di !in dl.indices) return@downloadBatch
                                    // ★ 2.7.0：解码/写盘挪到 [batchWriteLoop]（见 [writeQueue] 说明）
                                    //   （put 在标记 done 之前：put 抛异常时该项按"未交付"进重试）
                                    markPendingWrite(reqKeys[dl[di]], reqKinds[dl[di]])
                                    writeQueue.put(PendingWrite(reqKeys[dl[di]], reqKinds[dl[di]], bytes))
                                    done[dl[di]] = true
                                }
                            }
                        }
                    } finally {
                        bulkGate.release()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("ThumbStore", "auto batch failed", t)
        }
        // 逐文件收尾：任一需求项未交付 → 该文件未就绪
        for (i in reqKeys.indices) {
            if (!done[i]) fileOk[reqOwner[i]] = false
        }
        val unresolved = ArrayList<ObjectCacheKey>()
        val successTokens = ArrayList<String>()
        for (fi in batch.indices) {
            if (fileOk[fi]) {
                autoRetryCount.remove(batch[fi].token)
                successTokens.add(batch[fi].token)
            } else {
                unresolved.add(batch[fi])
            }
        }
        // 进度计数（幂等）：只对"本代"的成功条目计 —— 旧代条目不能给新一轮记数
        if (successTokens.isNotEmpty()) {
            synchronized(lock) {
                if (autoGen == gen) {
                    for (tok in successTokens) countAutoLocked(tok)
                }
            }
        }
        var backoff = 0L
        // 代次已变（断开重连/开关切换）：旧条目作废，**不进新队列**
        if (unresolved.isNotEmpty() && autoGen == gen) {
            backoff = requeueForRetry(unresolved, autoQueue, autoQueuedSet, autoRetryCount) { k ->
                // 终态：该文件的小图不在磁盘才标 FAILED（清掉 LOADING 语义，
                // 列表显示占位）；已拿到小图、只缺预览的不动小图状态。
                if (!smallDiskFile(k).isFile) states[k.token] = ThumbState.FAILED
            }
        }
        if (backoff > 0) runCatching { Thread.sleep(backoff) }
        return fileOk.count { it }
    }

    // ===== 无感两段式通道（★ 2.10，方案 §五/§七；见类注释）=====

    /**
     * 连接预热时调用（[io.github.bi2qfa.alphasync.core.ConnectionCenter.startThumbBatch]）：
     * **两段式**接管整份名单 —— 小图缺的进 [thumbQueue]（小图段，绝对优先），
     * 预览缺的进 [previewQueue]（预览段，小图段取空后才轮到）。
     *
     * <p>★ 2.10（无感）：不再判"自动传输预览图"开关（该设置已废）—— 通道随连接常开。
     * 整库已缓存时两队列皆空（预扫全部计入 autoDone），零网络。
     */
    fun startSeamlessFetch(keys: List<ObjectCacheKey>) {
        // 新一轮名单：上一轮的重试计数作废（否则"上轮失败过 2 次"的条目
        // 在新一轮里一失败就直接到上限，平白少一次机会）—— 既有纪律，同 startAutoFetch。
        autoRetryCount.clear()
        val gen0: Int
        synchronized(lock) {
            host = io.github.bi2qfa.alphasync.core.ConnectionCenter.host
            autoQueue.clear()          // 旧单队列（B 阶段清理）：保留容器但不参与取件
            autoQueuedSet.clear()
            thumbQueue.clear()
            thumbQueuedSet.clear()
            previewQueue.clear()
            previewQueuedSet.clear()
            countedAuto.clear()
            autoTotal = keys.size      // 仅记账（A3 后无 UI 消费，日志用）
            autoDone = 0
            autoGen++                  // 代次递增：在飞的旧批作废（见 autoFetchLoop）
            gen0 = autoGen
            seamlessPhase = FetchPhase.SWEEP
        }
        // 排序：**新→旧**（列表上方优先；同 mtime 按文件名倒序稳定化）——
        // 用户定版"预取遵循列表排序、从列表上方开始"，默认序即新→旧。
        val sorted = keys.sortedWith(
            compareByDescending<ObjectCacheKey> { it.mtime }.thenByDescending { it.path },
        )
        // ★ 缓存识别 + 分块落地（沿用既有模式）：**先扫磁盘再建队列** ——
        //   上一次会话已经缓存掉的条目直接标 READY（列表可直接显示）；
        //   小图缺 → [thumbQueue] 尾插、预览缺 → [previewQueue] 尾插。两个判定
        //   **彼此独立**：既缺小图又缺预览的条目在小图段拉小图、到预览段再拉预览
        //   （方案 §5.5：SWEEP 拉小图、PREVIEWS 再拉预览，绝不批内 T+P 混拉）；
        //   全有 → countAutoLocked 计已就绪。
        //   ★ 分块推进（[SCAN_CHUNK]）：识别是纯本地 stat，不占网络；几万条名单
        //   一次性扫完才落地会憋住落地，所以每块扫完就落地一次（锁内 + 代次校验）。
        diskScanPool.execute {
            val chunkThumb = ArrayList<ObjectCacheKey>(SCAN_CHUNK)
            val chunkPreview = ArrayList<ObjectCacheKey>(SCAN_CHUNK)
            val chunkReady = ArrayList<ObjectCacheKey>(SCAN_CHUNK)
            for ((idx, key) in sorted.withIndex()) {
                val t = thumbOnDisk(key)
                val p = previewOnDisk(key)
                if (t) states[key.token] = ThumbState.READY   // 小图已有：列表可直接显示
                if (!t) chunkThumb.add(key)
                if (!p) chunkPreview.add(key)
                if (t && p) chunkReady.add(key)
                if ((idx + 1) % SCAN_CHUNK == 0 || idx == sorted.lastIndex) {
                    synchronized(lock) {
                        if (autoGen != gen0) return@execute   // 已被新一轮（重连/重启）取代：丢弃
                        for (k in chunkReady) countAutoLocked(k.token)
                        for (k in chunkThumb) {
                            if (thumbQueuedSet.add(k.token)) thumbQueue.add(k)
                        }
                        for (k in chunkPreview) {
                            if (previewQueuedSet.add(k.token)) previewQueue.add(k)
                        }
                        lock.notifyAll()
                    }
                    chunkThumb.clear()
                    chunkPreview.clear()
                    chunkReady.clear()
                    if (idx < sorted.lastIndex) {
                        runCatching { Thread.sleep(SCAN_CHUNK_GAP_MS) }
                    }
                }
            }
        }
    }

    /**
     * **新照片注入**（★ 2.10，方案 §6.1/§7.2；调用点 = 常驻巡检 / 文件页 diff，见工单 A）。
     *
     * <p>语义：**列表上方绝对优先** —— 按入参顺序（= 当时显示序，从上到下）**逆序
     * pushFront** 到 [thumbQueue]，队头最终 = 列表第一条；当前在飞批**不打断**
     * （批尾切换），下一批取件（[pollSeamless] 先 thumbQueue）立刻拉这些新照片的小图。
     *
     * <p>预览兜底（设计说明）：注入只把小图插到**队头**，同一张的预览**追加到
     * [previewQueue] 尾部** —— "新照片小图绝对优先"不被破坏（预览在另一队列尾部），
     * 而它的预览在小图批完成后自然排队（进入预览段），不漏任何一张。
     *
     * @param keys 新照片（调用方按显示序传入，队头 = 最上方）
     */
    fun injectNew(keys: List<ObjectCacheKey>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            var added = 0
            // 小图：**逆序** pushFront —— 逐条插队头后，队头 = 入参第一条（列表最上方）
            for (i in keys.indices.reversed()) {
                val key = keys[i]
                if (thumbOnDisk(key)) continue        // 已缓存：不占队列（快查）
                if (thumbQueuedSet.add(key.token)) {
                    thumbQueue.addFirst(key)
                    added++
                }
            }
            // 预览：**正序**尾插（与小图状态解耦 —— 即便小图已在盘，缺预览也要补；
            // 追加在尾部 = 不打断"新照片小图优先"，预览段按显示序自然排队）
            for (key in keys) {
                if (previewOnDisk(key)) continue      // 已缓存：不占队列（快查）
                if (previewQueuedSet.add(key.token)) previewQueue.add(key)
            }
            autoTotal += added            // 仅记账（日志用）
            seamlessPhase = FetchPhase.SWEEP   // 注入后回到"小图段"语义
            lock.notifyAll()              // 唤醒空闲中的调度器
        }
    }

    /**
     * **预览补漏**（★ 2.10-fix）：把"仍缺预览"的条目补进 [previewQueue]（去重 + 磁盘快查）。
     *
     * <p>为什么需要它：`runSeamlessBatch` 对连续失败 ≥ ITEM_RETRY_LIMIT 的条目会给终态
     * （不再自动重试），而"全量名单"只在连接时进过一次 —— 没有本方法时，一个批内
     * 失败潮（如相机端并发争抢）留下的洞**永远不会被重新填上**（用户实测"有的照片
     * 一直不出大图 / 时好时坏"）。FilesScreen 每次 load/loadMore 调一次，进目录 /
     * 停留自动刷新 / 翻页都会自然补漏。
     *
     * <p>纯"补队列"语义：与 [startSeamlessFetch] 的全量替换互不冲突（previewQueuedSet
     * 去重保证同一 token 只排一次）；已落盘的快查跳过。
     */
    fun ensurePreviews(keys: List<ObjectCacheKey>) {
        if (keys.isEmpty()) return
        var added = false
        synchronized(lock) {
            for (key in keys) {
                if (previewOnDisk(key)) continue
                if (previewQueuedSet.add(key.token)) {
                    previewQueue.add(key)
                    added = true
                }
            }
            if (added) lock.notifyAll()
        }
    }

    /**
     * **队头迭代器**（★ 2.10，方案 §7.1）：从队头取一条**真待办** —— 出队即同步摘除
     * 去重标记；对应类型的盘上产物已存在（本会话/注入时快查后落盘）的项**丢弃继续**。
     *
     * <p>三重语义合一：小图优先（[pollSeamless] 先小图队列）、幂等（已完成的不再拉）、
     * 淘汰自愈（被预算淘汰的 onDisk 变 false → 照常出队重拉）。调用方须持有 [lock]
     * （[pollSeamless] 内部）。
     */
    private fun pollSkipOnDisk(
        queue: ArrayDeque<ObjectCacheKey>,
        queuedSet: HashSet<String>,
        onDiskCheck: (ObjectCacheKey) -> Boolean,
    ): ObjectCacheKey? {
        while (true) {
            val next = queue.removeFirstOrNull() ?: return null
            queuedSet.remove(next.token)
            if (onDiskCheck(next)) continue
            return next
        }
    }

    /**
     * 无感通道**取件**（★ 2.10）：**小图绝对优先** —— [thumbQueue] 取空才轮到
     * [previewQueue]；两队列皆空（或暂停中）返回 null。带回 kind 供批组装
     * （每批只装同一种 kind）。阶段推进免维护：SWEEP → PREVIEWS = thumbQueue 自然取空。
     *
     * <p>★ 2.11 第二轮（§3）：暂停**按 kind 放行** —— 同步页在屏（[syncVisible]）时
     * 小图批照取（用户正盯着同步面板看新照片），预览批仍让位（见函数内注释）。
     */
    private fun pollSeamless(): Pair<ObjectCacheKey, Int>? = synchronized(lock) {
        // ★ 2.11 第二轮（§3）：**按 kind 放行** —— 同步页在屏时小图批不受传输暂停限制；
        //   预览批仍让位（大图带宽大，不抢下载）。非同步页维持原语义：暂停挡住全部。
        val pausedFlag = pausedNow()
        if (pausedFlag && !syncVisible) return@synchronized null
        val thumb = pollSkipOnDisk(thumbQueue, thumbQueuedSet) { thumbOnDisk(it) }
        if (thumb != null) {
            // ★ 2.10-fix（修复 2：加载中转圈）：出队小图置 LOADING（与 fetchLoop 同款），
            //   UI 的 st == LOADING 判断才能亮转圈。
            if (states[thumb.token] != ThumbState.READY) states[thumb.token] = ThumbState.LOADING
            return@synchronized (thumb to PtpCodec.KIND_THUMB)
        }
        if (pausedFlag) return@synchronized null     // 预览批：暂停时让位
        val preview = pollSkipOnDisk(previewQueue, previewQueuedSet) { previewOnDisk(it) }
        if (preview != null) return@synchronized (preview to PtpCodec.KIND_PREVIEW)
        null
    }

    /**
     * 批组装时 kind 混了（小图队列恰好取空、预览条目已出队）→ 把这条**未装批**的条目
     * 放回**它自己队列的头**（去重标记同步恢复），下一批继续 —— 保证每批同 kind，
     * 且队列元素只出不丢。
     */
    private fun pushBackSeamless(entry: Pair<ObjectCacheKey, Int>) {
        val (key, kind) = entry
        synchronized(lock) {
            if (kind == PtpCodec.KIND_PREVIEW) {
                if (previewQueuedSet.add(key.token)) previewQueue.addFirst(key)
            } else {
                if (thumbQueuedSet.add(key.token)) thumbQueue.addFirst(key)
            }
        }
    }

    /**
     * 批内**顺序读排序**（★ 2.11-fix，顺序修复 R3）。
     *
     * <p><b>问题</b>：队列按"新→旧"填充（见 [startSeamlessFetch]/[injectNew]，= 列表从上到下）。
     * 相机文件名（DSC0xxxx）随时间单调递增，所以"新→旧" = **path 降序**。
     * 原实现无条件 `sortBy { it.path }`（升序）→ 每一批在传输前被**整批翻过来**，
     * 表现为"每 K 张一组、组内倒序"的锯齿 —— 用户描述为"加载顺序不像从上到下、像随机"。
     *
     * <p><b>做法</b>：按**本批的队列方向**排 —— 批是从队列头连续取的，所以比较
     * "队首 vs 队尾"的 path 就能判出方向，无需外部传入排序设置（用户切"旧→新"时
     * 队列本身也变方向，这里自动跟随）。
     *
     * <p><b>为什么不损失 SD 顺序读</b>：顺序读的红利来自**同目录连续文件名的局部性**
     * （相邻文件在卡上相邻、寻道距离同量级），与行进方向无关 —— 升序读与降序读
     * 的局部性相同。所以"保显示序"和"保顺序读"在这里不矛盾。
     */
    private fun orderBatchForSd(batch: MutableList<ObjectCacheKey>) {
        if (batch.size < 2) return
        if (batch.first().path >= batch.last().path) {
            batch.sortByDescending { it.path }
        } else {
            batch.sortBy { it.path }
        }
    }

    /**
     * 跑**一批同 kind 条目**（★ 2.10，在 [autoCyclePool] 线程上；纪律见 [autoFetchLoop]）。
     *
     * <p>批内条目**同 kind 成批**（小图批全 T、预览批全 P —— 不存在 T+P 混批）：缺项拼成
     * **一次批量请求**，在一条数据连接上连续取回；逐项落盘与既有路径完全一致
     * （小图 → 缓存 + READY；预览 → 拆捆绑包 + sidecar + 方向信号）。
     *
     * <p>★ F1/F2（2.6 纪律原样保留）：
     * <ul>
     *   <li>返回**真正就绪**的条目数（本条目的 kind 已交付，或本来就命中磁盘）——
     *       进度只计成功；</li>
     *   <li>未就绪的条目**放回对应队列头**重试（带退避），连续失败 ≥ [ITEM_RETRY_LIMIT]
     *       次才收场。</li>
     * </ul>
     *
     * @return 本批成功的条目数
     */
    private fun runSeamlessBatch(batch: List<ObjectCacheKey>, kind: Int, gen: Int): Int {
        // 组装请求项：只装本批 kind 的缺项（命中磁盘的直接算就绪）
        val reqKeys = ArrayList<ObjectCacheKey>(batch.size)
        val reqOwner = ArrayList<Int>(batch.size)
        val fileOk = BooleanArray(batch.size) { true }
        for (fi in batch.indices) {
            val key = batch[fi]
            if (!onDisk(key, kind)) {
                reqKeys.add(key)
                reqOwner.add(fi)
            }
        }
        val done = BooleanArray(reqKeys.size)
        try {
            if (reqKeys.isNotEmpty()) {
                // ===== 批边界：让位窗口（窗口内等待；代次变了/重连换名单则作废） =====
                while (true) {
                    if (autoGen != gen) return fileOk.count { it }
                    val rest = YIELD_WAIT_MS - (android.os.SystemClock.elapsedRealtime() - userOpenAt)
                    if (rest <= 0) break
                    synchronized(lock) { runCatching { lock.wait(rest) } }
                }
                val h = host ?: io.github.bi2qfa.alphasync.core.ConnectionCenter.host
                if (h != null) {
                    bulkGate.acquireUninterruptibly()
                    try {
                        // ★ M4（2.6 修复）：**进闸后重查磁盘** —— 等闸期间另一条通道可能
                        //   已把同一项写入；不重查就是重复下载（logcat 实测 ~240 项/会话）。
                        val dl = ArrayList<Int>(reqKeys.size)
                        for (i in reqKeys.indices) {
                            if (onDisk(reqKeys[i], kind) || isPendingWrite(reqKeys[i], kind)) {
                                // 已落盘，或另一条通道已交付、在途落盘 → 都算满足
                                done[i] = true
                            } else {
                                dl.add(i)
                            }
                        }
                        if (dl.isNotEmpty()) {
                            val items = dl.map { reqKeys[it].path to kind }
                            val labels = dl.map { i ->
                                (if (kind == PtpCodec.KIND_PREVIEW) "P " else "T ") + reqKeys[i].path
                            }
                            val t = ObjectRepository.openBatchTicket(h, items)
                            if (t != null) {
                                ObjectRepository.downloadBatch(
                                    t, items.size, labels = labels,
                                    // ★ O4：重试换新票（一次性令牌用后即废）
                                    refill = { ObjectRepository.openBatchTicket(h, items) },
                                ) { di, bytes ->
                                    if (bytes == null || di !in dl.indices) return@downloadBatch
                                    // ★ 2.7.0：解码/写盘挪到 [batchWriteLoop]（见 [writeQueue] 说明）
                                    //   （put 在标记 done 之前：put 抛异常时该项按"未交付"进重试）
                                    markPendingWrite(reqKeys[dl[di]], kind)
                                    writeQueue.put(PendingWrite(reqKeys[dl[di]], kind, bytes))
                                    done[dl[di]] = true
                                }
                            }
                        }
                    } finally {
                        bulkGate.release()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("ThumbStore", "seamless batch failed", t)
        }
        // 逐条目收尾：未交付 → 该条目未就绪
        for (i in reqKeys.indices) {
            if (!done[i]) fileOk[reqOwner[i]] = false
        }
        val unresolved = ArrayList<ObjectCacheKey>()
        val successTokens = ArrayList<String>()
        for (fi in batch.indices) {
            if (fileOk[fi]) {
                autoRetryCount.remove(batch[fi].token)
                successTokens.add(batch[fi].token)
            } else {
                unresolved.add(batch[fi])
            }
        }
        // 进度计数（幂等）：只对"本代"的成功条目计 —— 旧代条目不能给新一轮记数
        if (successTokens.isNotEmpty()) {
            synchronized(lock) {
                if (autoGen == gen) {
                    for (tok in successTokens) countAutoLocked(tok)
                }
            }
        }
        // ★ 2.10-fix（修复 4：小图后自动拉大图）：小图批成功后，把同文件的预览推进
        //   previewQueue —— 两段分离后这里必须手动接上（旧通道 T+P 混批天然覆盖）。
        if (kind == PtpCodec.KIND_THUMB) {
            for (fi in batch.indices) {
                if (!fileOk[fi]) continue   // 小图本身没成的，预览也不推
                val key = batch[fi]
                if (!previewOnDisk(key)) {
                    synchronized(lock) {
                        if (previewQueuedSet.add(key.token)) previewQueue.add(key)
                    }
                }
            }
        }
        var backoff = 0L
        // 代次已变（断开重连/重启）：旧条目作废，**不进新队列**
        if (unresolved.isNotEmpty() && autoGen == gen) {
            val isPreview = kind == PtpCodec.KIND_PREVIEW
            backoff = requeueForRetry(
                unresolved,
                if (isPreview) previewQueue else thumbQueue,     // F2：放回**本 kind** 队列头
                if (isPreview) previewQueuedSet else thumbQueuedSet,
                autoRetryCount,
            ) { k ->
                // 终态：该条目的小图不在磁盘才标 FAILED（清掉 LOADING 语义，列表显示
                // 占位）；已拿到小图、只缺预览的不动小图状态（既有语义原样）。
                if (!smallDiskFile(k).isFile) {
                    states[k.token] = ThumbState.NONE
                    states[k.token] = ThumbState.FAILED
                }
            }
        }
        if (backoff > 0) runCatching { Thread.sleep(backoff) }
        return fileOk.count { it }
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= 320 && h / 2 >= 320) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return decodeOriented(bytes, sample)
    }

    /**
     * 小图解码 + 方向（2.7.0 需求 7；★ 1.0：方向**随小图到达即学**）：
     * 1.0 起小图对象是捆绑包，EXIF 在写盘线程已先落**小图 sidecar** —— 第一帧即按
     * 新方向解码。sidecar 未就绪（旧缓存 / 旧相机）→ 方向 0 → 行为与旧版一致（不转），
     * 待大预览 sidecar 到达后再转正。
     */
    private fun decodeSmallKeyed(bytes: ByteArray, key: ObjectCacheKey): Bitmap? {
        val bmp = decodeSmall(bytes) ?: return null
        return rotateByOrientation(bmp, orientationOf(key))
    }

    /**
     * 解码并按 **EXIF 方向**转正（用户要求：竖屏照片在缩略图与预览里都正着显示）。
     *
     * 相机生成的缩略图 / 预览图是**传感器横向**的：竖拍的图它也按横着画，
     * 只在 EXIF 的 Orientation 标记里写"该转 90°/270°"。不解码这一标记，
     * 竖拍照片在界面上就是横躺的。
     */
    private fun decodeOriented(bytes: ByteArray, sample: Int = 1): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return applyExifRotation(bmp, bytes)
    }

    private fun decodeFileOriented(f: File): Bitmap? {
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return runCatching {
            // ★ 审修复（M2）：只读文件头取 EXIF（ExifInterface 按路径打开、内部只读头部），
            //   不再 f.readBytes() 整读 —— 旧实现为取一个方向把整个文件再读进内存，
            //   位图 + 完整字节数组双份峰值（大预览尤其明显）。
            val orientation = androidx.exifinterface.media.ExifInterface(f.absolutePath)
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )
            rotateByOrientation(bmp, orientation)
        }.getOrDefault(bmp)
    }

    /**
     * 按**标准 EXIF orientation 值**（1..8）旋转/镜像位图；无需变换时原样返回。
     *
     * <p>数值就是 TIFF 0x0112 的原始值（= androidx ExifInterface 的 ORIENTATION_* 常量值）：
     * 3=180°、6=顺 90°、8=顺 270°、2/4=纯镜像、5/7=镜像+旋转。相机端 JSON 的 "o"
     * 与从字节里读出的 EXIF 都直接喂这里 —— **预览（需求 6）与小图（需求 7）共用这一份变换**。
     */
    private fun rotateByOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = android.graphics.Matrix()
        when (orientation) {
            6 -> m.postRotate(90f)
            3 -> m.postRotate(180f)
            8 -> m.postRotate(270f)
            2 -> m.setScale(-1f, 1f)
            4 -> m.setScale(1f, -1f)
            5 -> {
                // ★ 审修复（H1）：原来是 setScale —— set 语义会**重置矩阵**，把前面的
                //   postRotate 整个丢掉（最终只有镜像没有旋转）。必须用 postScale 才能
                //   与旋转叠加：复合 = R(90|270) ∘ S(-1,1)，与 Glide/AndroidX 标准一致。
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            7 -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return if (out != bmp) out else bmp
    }

    /** 按**字节里**的 EXIF Orientation 旋转/镜像（旧入口，内部转调 [rotateByOrientation]）。 */
    private fun applyExifRotation(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )
        }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
        return rotateByOrientation(bmp, orientation)
    }
}
