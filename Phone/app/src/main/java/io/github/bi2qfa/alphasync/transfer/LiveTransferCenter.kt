package io.github.bi2qfa.alphasync.transfer

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bi2qfa.alphasync.core.ConnectionCenter
import io.github.bi2qfa.alphasync.data.ThumbStore
import io.github.bi2qfa.alphasync.ptpip.ObjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 边拍边传（2.7 / 2.8）核心：连着相机时持续巡检"卡上有没有新照片"，
 * 发现即按**先来后到**自动加入当前设备的传输队列，并保证排空线程在跑。
 *
 * <h3>★★ 2.8 重写：变化检测从"目录 mtime"改为"目录条目计数"</h3>
 * 原设计（2.7）假设"FAT 目录 mtime 随内容变化"——真机实测这台相机**不成立**：
 * 目录 mtime 恒为目录**创建时间**，新增/删除照片都不更新。于是"mtime 变过的
 * 子目录才翻页"永远不触发（同一目录里连拍 → 永远识别不到新照片，用户实测主诉）；
 * 顺带发现 2.7 的"stat("/") 根指纹换卡闸"也从未生效——相机端 `OP_STAT` 只受理
 * **文件**（目录一律 NOT_FOUND），旧代码把探测失败静默跳过了。
 *
 * 2.8 起改用 [ObjectRepository.dirCount] **计数探针**（零协议改动、任何相机端
 * 版本可用，见其注释）：每 [POLL_INTERVAL_MS] 一轮：
 * ```
 * 1. listPage("/DCIM", 0)  → 子目录清单；新子目录 = 有新内容（滚目录）
 * 2. 每个子目录 dirCount   → 条目数比上次多 = 有新照片；少了 = 删除/换卡
 * 3. 变多的目录先验"锚点"（首条目名，一条小包）：锚没变 = 正常增长 → 只翻
 *    尾页增量；锚变了 = 疑似换卡 → 全量对账裁决（[fullRecheck]）
 * 4. 新条目先进"稳定确认"名单：下一轮 stat 的 size 不再变（相机写完卡）才入队
 *    —— 相机写卡是"先建条目后写数据"，立刻拉会拿到 size 还在涨的半成品
 * 5. 入队按 mtime 升序（先来后到）→ DownloadService.start 保证排空
 * ```
 * 稳定态每轮 = 1 条 /DCIM 首页 + N 条计数探针（N = 子目录数，通常 1~2 条，
 * 每条几十字节），控制面几乎零占用。
 *
 * <h3>换卡闸（2.8 真正生效）</h3>
 * [fullRecheck] 全量对账时，某目录的已知图片**大量失踪**（≥ [CARD_SWAP_MIN_MISSING]
 * 且过半）→ 判定换卡 → 暂停。不挡的话会把"另一张卡上的全部旧照片"当成新照片
 * 全拉下来（换卡后同名目录、计数恰好变多、锚点必变 → 对账后旧面孔全失踪）。
 *
 * <h3>生命周期（与 2.7 相同）</h3>
 * - 开关（[enabled]）是**内存态**、不落盘（换卡/换相机的安全窗口比方便重要）。
 * - 相机后台化声明（[onCameraBackgroundDeclared]）后的断开 → 保持开关、起
 *   **有界自动重连**（热点模式 AP 要拆要重建，断链是预期行为）；
 *   其余断开（用户手动 / 相机退出 / 意外失联）→ 开关弹回关闭，绝不自动重连。
 * - 断连清基准：重连后重建（不落盘、不留旧基准）。
 *
 * <h3>★★ 2.10（D4 用户定版）：巡检常开 —— 检测与下载解耦</h3>
 * 巡检/稳定确认/新目录发现随**连接**常开（连上即启动，不需要开任何开关），产物分流两条：
 * - 检测产物（永远执行）：确认稳定的新照片立即 [ThumbStore.injectNew] 注入缩略图预取 ——
 *   无论用户开没开边拍边传、在不在文件页，"新照片无感预取"都生效；
 * - 下载产物（开关控制）：[enabled] 只额外决定"要不要把原图自动入队"（[enqueueNew]）——
 *   开关语义从此退化为纯粹的"自动下载原图"开关。
 * 断连即停巡检（[stopPatrol]），连接建立/重连后重建（[startPatrol]）；换卡暂停闸、
 * 后台化自动重连等原有边界全部保留。
 */
object LiveTransferCenter {

    private const val TAG = "LiveTransfer"

    /** 巡检周期：4 秒一轮（对拍照节奏足够灵敏；几条小包对控制面几乎零占用）。 */
    private const val POLL_INTERVAL_MS = 4_000L

    /** 目录与目录之间的让出间隔（与 ConnectionCenter 全树扫描的 F6 纪律同款）。 */
    private const val SCAN_DIR_GAP_MS = 50L

    /** 待确认新照片最多等几轮（每轮 stat 一次 size；超龄丢弃——相机端删了或写卡异常）。 */
    private const val PENDING_MAX_ROUNDS = 3

    /** 换卡判定：全量对账时已知图片失踪数下限（少于这个按"用户删了几张"处理）。 */
    private const val CARD_SWAP_MIN_MISSING = 4

    /** 自动重连：轮数 / 轮间隔 / 每几轮做一次全子网补扫（相机换 IP 只有这条能找回来）。 */
    // ★ 第三批（§6）：12 → 6 —— 重连窗口定版"半分钟"（6 轮 × 5s ≈ 30s；超时提示可手动重连）。
    private const val RECONNECT_ROUNDS = 6
    private const val RECONNECT_INTERVAL_MS = 5_000L
    private const val RECONNECT_SWEEP_EVERY = 4

    /**
     * "相机后台化声明"的有效期：过期后普通断链不再算后台化（不自动重连）。
     * Wi-Fi 模式下会话可能一直活着、始终没有断开事件 —— 这个 TTL 就是它的退场机制。
     */
    private const val EXPECT_RECONNECT_TTL_MS = 90_000L

    // ===== UI 可见状态（主界面"边拍边传"区块绑定） =====

    /** 开关（内存态）：跨"相机后台化"的断连保持；普通断开弹回关闭。 */
    var enabled by mutableStateOf(false)
        private set

    /** 巡检是否真的在跑（基准已建立、轮询在转）。 */
    var active by mutableStateOf(false)
        private set

    /** 状态行文案（开关打开时显示）。 */
    var statusText by mutableStateOf("")
        private set

    /** 本次会话自动入队的张数（断连清零重计）。 */
    var autoCount by mutableStateOf(0)
        private set

    /**
     * ★ 2.11 §9：**同步页面板**（SyncScreen 绑定）——自动同步开启后、巡检**确认稳定**
     * 的新照片，按到达序（旧→新 = 先来后到）追加在这里；显示顺序（最新在前 / 倒序）
     * 由同步页自己排，入队顺序永远是这份到达序。
     *
     * <p>生命周期与开关同寿：`disable()`（用户关开关 / 普通断开）清空；相机后台化
     * 断连（开关保持、等待重连）与换卡暂停**保留**（那是"这次开了同步后到过哪些"
     * 的记录）。注意：与 [enqueueNew] 同一批 [confirmPending] 产物，天然一致。
     */
    val panelEntries = mutableStateListOf<ObjectRepository.FtpEntry>()

    // ===== 内部状态（全部只在主线程/自身 scope 上读写） =====

    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var resuming = false

    /**
     * ★ 2.10（D4）：常驻巡检自身的运行标志（与 [pollJob] 共用同一套轮询循环）。
     * 与 [active] 的区别：巡检随**连接**常开、不受 [enabled] 开关控制。
     */
    private var patrolling = false

    /** 监控的目标相机（会话建立时钉住；重连后必须还是它）。 */
    private var sessionGuid: String? = null

    /** "相机声明过要进后台" —— 此后一次断开按预期处理（保持开关 + 有界自动重连）。 */
    @Volatile
    private var expectReconnect = false

    /**
     * 单个监控目录的账本（2.8）：
     * - [count] 条目数 —— 变化信号（替代失效的"目录 mtime"）；
     * - [anchor] 首条目名（目录全序 = 字母序）—— 正常增长时永不变；
     *   换卡后几乎必然不同（两张卡恰好同序同名的概率可忽略），
     *   一条小包就能把"增长"和"换卡"分开。
     */
    private class DirWatch(var count: Int, var anchor: String?)

    /** 基准：图片 path → (size, mtime)。只在内存，断连即清。 */
    private val knownFiles = HashMap<String, Pair<Long, Long>>()

    /** 各监控目录的账本（/DCIM 的一级子目录）。 */
    private val knownDirs = HashMap<String, DirWatch>()

    /**
     * 待确认的新照片：path → size/mtime/已等轮数（2.8 稳定确认）。
     *
     * 相机写卡是"先建目录条目、后写文件数据"——发现新条目那一刻 size 还在涨。
     * 每轮 stat 一遍名单：size 与上轮一致 = 写完了 → 入队；变了 → 更新再等；
     * 连续 [PENDING_MAX_ROUNDS] 轮 stat 不到 → 丢弃（相机端删了/写卡异常）。
     */
    private class Pending(var size: Long, var mtime: Long, var rounds: Int)

    private val pendingNew = HashMap<String, Pending>()

    /**
     * 入队幂等册（★ 2.10 / F2）：`path\0size\0mtime` 三元组身份 → 已入队登记。
     * 防**任何调用路径**的重复入队；同路径重拍（size/mtime 变化）身份不同、
     * 仍会合法重传，语义保留。清理点与 [knownFiles] 同步：disable /
     * [onConnectionLost] / [buildBaseline] 三处 + [stopPatrol]。
     */
    private val enqueuedIds = HashSet<String>()

    /**
     * ★ 2.11 第二轮（§5.1）**同步待发池**：确认稳定、按到达序排队的新照片。
     *
     * <p>面板（[panelEntries]）显示全部；入队走**串行水位 = 1**（[topUpQueue]）——
     * "上一个传完，下一个才入队"。生命周期与 [panelEntries] 同寿（disable 清空）。
     */
    private val pendingEnqueue = ArrayList<ObjectRepository.FtpEntry>()

    init {
        // ★ 2.11 第二轮（§5.1）：条目落终态（DONE/FAILED）→ 补下一张（水位 = 1）。
        //   回调来自传输线程：切回主线程再碰池/面板（类内"只在主线程读写"的约定）。
        TransferStore.onItemSettled = { guid, _ ->
            val sc = scope
            if (enabled && active && sc != null) {
                sc.launch { if (enabled && active) topUpQueue(guid) }
            }
        }
    }

    /** 连续无变化轮数（只进日志，用于判断"安定下来了"）。 */
    private var stableRounds = 0

    // ============================================================
    // 对外入口（主界面开关 / ConnectionCenter 钩子）
    // ============================================================

    /** 主界面开关打开。调用方负责挡掉"未连接"（这里是最后一道）。 */
    fun enable(context: Context) {
        if (enabled) return
        if (ConnectionCenter.state != ConnectionCenter.State.CONNECTED) return
        val host = ConnectionCenter.host ?: return
        val guid = ConnectionCenter.camera?.guidHex ?: return
        appContext = context.applicationContext
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        enabled = true
        active = false
        expectReconnect = false
        resuming = false
        autoCount = 0
        sessionGuid = guid
        statusText = "正在启动同步…"
        Log.d(TAG, "开启：基准扫描开始（$guid）")
        scope?.launch {
            val ok = buildBaseline(host)
            if (!enabled) return@launch
            if (!ok) {
                statusText = "基准建立失败，请重试"
                Log.w(TAG, "开启失败：基准扫描未完成")
                return@launch
            }
            active = true
            statusText = "监控中 · 已自动接收 $autoCount 张"
            pollJob?.cancel()
            pollJob = scope?.launch { pollLoop(host, guid) }
        }
    }

    /** 关掉模式（用户关开关；或断连且非后台化 —— 由 [onConnectionLost] 调用）。 */
    fun disable() {
        // ★ 2.10（D4）：先收掉常驻巡检运行态（幂等；断连路径也会经过这里）
        stopPatrol()
        enabled = false
        active = false
        expectReconnect = false
        resuming = false
        pollJob?.cancel()
        pollJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        knownFiles.clear()
        knownDirs.clear()
        pendingNew.clear()
        enqueuedIds.clear()   // ★ 2.10（F2）：入队幂等册与账本同步清空
        panelEntries.clear()  // ★ 2.11 §9：同步页面板与开关同寿（关开关 = 重置记录）
        pendingEnqueue.clear()  // ★ 2.11 第二轮（§5.1）：待发池与面板同寿
        stableRounds = 0
        sessionGuid = null
        statusText = ""
        scope?.cancel()
        scope = null
        appContext = null
        Log.d(TAG, "已关闭")
    }

    /**
     * 相机声明"进入后台运行"（相机端按钮/事件推来）。
     *
     * 武装"下一次断开按预期处理"：热点模式下 Event 之后就是拆 AP（链路必断），
     * 断链由 [onConnectionLost] 接住并起有界自动重连；Wi-Fi 模式链路多半活着，
     * 由 [EXPECT_RECONNECT_TTL_MS] 让这条声明过期退场。
     *
     * ★ 第三批（§6）：声明驱动的重连**不依赖"边拍边传"开关** —— 即便从未开过开关，
     *   这里也把 scope/appContext 备好（只为"等待重新连接"的重连循环服务；用户随后
     *   真开开关时 [enable] 会直接复用，不重复创建）。
     */
    fun onCameraBackgroundDeclared(context: Context? = null) {
        expectReconnect = true
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        if (appContext == null) appContext = context?.applicationContext
        Log.d(TAG, "相机声明进入后台运行（此后一次断开按预期处理）")
        val sc = scope ?: return
        sc.launch {
            delay(EXPECT_RECONNECT_TTL_MS)
            if (reconnectJob?.isActive != true) {
                expectReconnect = false
            }
        }
    }

    /**
     * 会话断开（由 `ConnectionCenter.disconnect()` 钩子调用）。
     *
     * 三种归宿见类注释；关键分歧只有一个：**是不是相机后台化导致的断**。
     */
    fun onConnectionLost() {
        // ★ 2.10（D4）：断连即停常驻巡检（清基准；重连后由 [onConnectionEstablished] 重建）
        stopPatrol()
        val keepSwitch = enabled && expectReconnect
        active = false
        pollJob?.cancel()
        pollJob = null
        knownFiles.clear()
        knownDirs.clear()
        pendingNew.clear()
        enqueuedIds.clear()   // ★ 2.10（F2）：入队幂等册与账本同步清空
        stableRounds = 0
        autoCount = 0
        if (!keepSwitch) {
            // 普通断开（用户手动 / 相机退出 / 换方式 / 意外失联）：断开即停、开关弹回关闭
            if (enabled) disable()
            // ★ 第三批（§6）：**声明驱动**的重连不依赖开关 —— 收到过"后台声明"
            //   （expectReconnect 仍在 TTL 内）就起有界重连，兑现"等待重新连接"；
            //   无声明的普通断开维持手动重连（本分支直接返回）。
            if (expectReconnect) {
                statusText = "等待相机…"
                startBoundedReconnect(carrySwitch = false)
            }
            return
        }
        // 相机后台化：开关保持，起有界自动重连（相机重启无线电后自动回到监控）
        statusText = "等待相机…"
        startBoundedReconnect(carrySwitch = true)
    }

    /** 连接建立（`ConnectionCenter.connectTo` 成功后调用）：开关开着就恢复巡检。 */
    fun onConnectionEstablished() {
        // ★ 2.10（D4）：巡检常开——连接（重新）建立即启动检测（不等开关）。
        //   开关开着时**不走这里的巡检分支**：下方"enabled 恢复"（resumeMonitoring）
        //   会重建基准并接管同一套轮询（它本身就是一次完整巡检），再 startPatrol
        //   只会多付一次全树基线扫描；开关关着时由这里启动常驻巡检。
        if (!enabled && !patrolling) startPatrol()
        if (!enabled || active) return
        val sc = scope ?: return
        sc.launch { resumeMonitoring() }
    }

    /**
     * ★ 2.10（D4）巡检常开入口：连接期间持续检测"卡上有没有新照片"（不需要开任何开关），
     * 确认稳定的照片永远注入缩略图预取（产物分流见 [confirmPending]）。
     *
     * 骨架参照 [enable]，但**不碰**开关相关状态（enabled/active/statusText/autoCount）：
     * - 已在巡检 / 未连接 → 直接返回（幂等）；
     * - 基线失败 → 静默放弃（Log.d 一行），下次连接/重连再建。
     */
    fun startPatrol() {
        if (patrolling) return
        if (ConnectionCenter.state != ConnectionCenter.State.CONNECTED) return
        val host = ConnectionCenter.host ?: return
        val guid = ConnectionCenter.camera?.guidHex ?: return
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        patrolling = true
        sessionGuid = guid
        Log.d(TAG, "巡检常开：基准扫描开始（$guid）")
        scope?.launch {
            val ok = buildBaseline(host)
            if (!patrolling) return@launch   // 期间断连/关闭：不再启动轮询
            if (!ok) {
                patrolling = false
                Log.d(TAG, "巡检常开：基准建立失败，静默放弃（下次连接重建）")
                return@launch
            }
            pollJob?.cancel()
            pollJob = scope?.launch { pollLoop(host, guid) }
        }
    }

    /**
     * ★ 2.10（D4）停巡检：只收巡检自身的运行标志与账本。
     * 不碰 [enabled]/[active]/状态行（开关语义独立）；[sessionGuid] 亦保留
     * （供"后台化重连必须还是同一台"的既有守卫使用）。
     */
    fun stopPatrol() {
        patrolling = false
        pollJob?.cancel()
        pollJob = null
        knownFiles.clear()
        knownDirs.clear()
        pendingNew.clear()
        enqueuedIds.clear()
        stableRounds = 0
    }

    // ============================================================
    // 巡检（2.8：计数探针 + 锚点 + 稳定确认）
    // ============================================================

    private suspend fun pollLoop(host: String, guid: String) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            // 断连 / 目标变了：由 onConnectionLost / onConnectionEstablished 接管
            if (ConnectionCenter.state != ConnectionCenter.State.CONNECTED ||
                ConnectionCenter.camera?.guidHex != guid
            ) return

            // ① 稳定确认上一轮发现的新照片（size 不再变 = 相机写完卡）
            confirmPending(host, guid)

            // ①' ★ 2.11 第二轮（§5.1）兜底：同步串行补位（幂等；覆盖终态回调丢失、
            //    进程内状态错位等边角 —— 池空或水位已占时是空操作）。
            if (enabled && active) topUpQueue(guid)

            // ② /DCIM 首页：子目录清单（不依赖 mtime）+ DCIM 层散落图片
            val page = listPageSafe(host, "/DCIM", 0, scan = false)
            if (page == null) {
                Log.d(TAG, "巡检读目录失败（交给心跳/重连处理）")
                continue
            }
            val freshDirs = ArrayList<ObjectRepository.FtpEntry>()
            for (e in page.entries) {
                if (e.isDir) {
                    if (!knownDirs.containsKey(e.path)) freshDirs.add(e)
                } else if (isImage(e) && knownFiles[e.path] == null) {
                    // DCIM 层直接放的照片（少见但存在）：走稳定确认
                    markPending(e)
                }
            }

            // ③ 监控期间新出现的子目录（如 100MSDCF 滚到 101MSDCF）：
            //    它在开关打开时还不存在，里面的内容全是此后拍的 → 走待确认入队
            for (d in freshDirs) {
                // ★ 2.10（D4）：巡检常开—— [patrolling] 期间不看开关；
                //   非巡检（纯边拍边传旧路径）语义原样保留
                if (!patrolling && (!enabled || !active)) return
                Log.d(TAG, "新目录出现：${d.path}")
                scanNewDir(host, d.path, asBaseline = false)
                delay(SCAN_DIR_GAP_MS)   // F6 纪律：目录之间让出控制面
            }

            // ④ 每个已知目录计数探针 —— 2.8 的核心变化信号
            for ((path, watch) in knownDirs.toList()) {
                // ★ 2.10（D4）：巡检常开——同上，[patrolling] 拥有独立运行标志
                if (!patrolling && (!enabled || !active)) return
                val count = dirCountSafe(host, path) ?: continue   // 探测失败：下轮再试
                if (count == watch.count) {
                    continue
                }
                if (count > watch.count && anchorUnchanged(host, path, watch.anchor)) {
                    // 正常增长：只翻尾页增量（[旧count, 新count)）
                    growDir(host, path, watch, count)
                } else {
                    // 条目变少 / 锚点变了：删除 or 换卡 —— 全量对账裁决
                    fullRecheck(host, path, watch)
                }
                delay(SCAN_DIR_GAP_MS)
            }

            if (pendingNew.isEmpty() && freshDirs.isEmpty()) {
                stableRounds++
                if (stableRounds == 1) {
                    Log.d(TAG, "巡检安定：${knownDirs.size} 个目录 · 基准 ${knownFiles.size} 张")
                }
            } else {
                stableRounds = 0
            }
        }
    }

    /** 待确认名单结算：size 稳定的产出（注入预取 + 按开关入队，mtime 升序），还在写的继续等。 */
    private suspend fun confirmPending(host: String, guid: String) {
        if (pendingNew.isEmpty()) return
        val ready = ArrayList<ObjectRepository.FtpEntry>()
        val dead = ArrayList<String>()
        // ★ 2.10 修复（F1）：确认成功的名单也要从 [pendingNew] 摘除。绝不能在 for-in
        //   遍历里直接 remove（HashMap 结构修改 → ConcurrentModificationException），
        //   照 dead 列表同款：先记账、循环后统一摘。
        val confirmed = ArrayList<String>()
        for ((path, p) in pendingNew) {
            val st = withContext(Dispatchers.IO) {
                runCatching { ObjectRepository.stat(host, path) }.getOrNull()
            }
            if (st == null) {
                // stat 不到（网络抖动 / 文件已删）：等满上限再丢，不急着判死
                if (++p.rounds >= PENDING_MAX_ROUNDS) dead.add(path)
                continue
            }
            if (st.size == p.size) {
                // size 稳定 = 写卡完成 → 记入基准并进本轮产物名单
                knownFiles[path] = st.size to st.mtime
                ready.add(ObjectRepository.FtpEntry(nameOf(path), path, false, st.size, st.mtime))
                confirmed.add(path)   // ★ 2.10（F1）
            } else {
                // 还在写：更新 size 继续等
                p.size = st.size
                p.mtime = st.mtime
                p.rounds = 0
            }
        }
        for (p in dead) {
            Log.d(TAG, "待确认照片 $PENDING_MAX_ROUNDS 轮未读到，丢弃：$p")
            pendingNew.remove(p)
        }
        // ★ 2.10 修复（F1）：成功确认的从待办摘除——此前只进 knownFiles/ready、从不摘除，
        //   于是每轮巡检都把同一批照片重复当"新照片"入队（用户主诉重复入队的根因）。
        for (p in confirmed) pendingNew.remove(p)
        if (ready.isNotEmpty()) {
            ready.sortBy { it.timestamp }
            // ★ 2.10（D4）：巡检产物分流——检测产物（注入缩略图预取）**永远**执行；
            //   下载产物（入队原图）只受"边拍边传"开关控制（enabled && active）。
            // ★ 注入序 = 新→旧（降序副本）：injectNew 的契约是"按列表顺序逆序 pushFront
            //   → 队头 = 列表第一条"，显示列表（新→旧）的第一条 = 最新那张 —— 传降序
            //   才能让预取从"列表上方（最新）"开始（用户需求口径）；上面的升序排序
            //   是给下载入队用的（先来后到），两者各取所需。
            val injectOrder = ready.map {
                ThumbStore.ObjectCacheKey(path = it.path, size = it.size, mtime = it.timestamp)
            }.asReversed()
            ThumbStore.injectNew(injectOrder)
            if (enabled && active) {
                // ★ 2.11 第二轮（§5.1）：产物**全进面板 + 待发池**（不再整批入队）；
                //   入队交串行水位（[topUpQueue]）——"上一个传完，下一个才入队"。
                //   面板 = 全部（显示），队列 = 逐张推进（传输），两者共用一份产物。
                panelEntries.addAll(ready)
                pendingEnqueue.addAll(ready)
                topUpQueue(guid)
            }
        }
    }

    /** 正常增长的目录：只翻 [watch.count, newCount) 增量区间，新图片进待确认。 */
    private suspend fun growDir(host: String, path: String, watch: DirWatch, newCount: Int) {
        Log.d(TAG, "目录有新增：$path（${watch.count} → $newCount）")
        var offset = watch.count
        var last = offset
        while (true) {
            val p = listPageSafe(host, path, offset, scan = false) ?: break
            for (e in p.entries) {
                if (!e.isDir && isImage(e) && knownFiles[e.path] == null) {
                    markPending(e)
                }
            }
            if (!p.hasMore || p.nextOffset <= last) break
            last = p.nextOffset
            offset = p.nextOffset
        }
        watch.count = newCount
    }

    /**
     * 全量对账（条目变少 / 锚点变了时）：重翻整个目录，与 [knownFiles] 对账。
     *
     * 裁决规则：
     * - 该目录的已知图片**大量失踪**（≥ [CARD_SWAP_MIN_MISSING] 且过半）→ 换卡 →
     *   暂停巡检。★ 这是 2.8 真正生效的换卡闸（2.7 的 stat("/") 根指纹从未生效，
     *     见类注释）。
     * - 少量失踪（用户在相机上删了几张）→ 从基准移除、账本照当前现状更新，继续。
     */
    private suspend fun fullRecheck(host: String, path: String, watch: DirWatch) {
        Log.d(TAG, "目录计数/锚点异常，全量对账：$path（记 ${watch.count}）")
        val files = readAllPages(host, path)
        if (files == null) {
            Log.w(TAG, "全量对账读目录失败：$path")
            return
        }
        val live = HashMap<String, ObjectRepository.FtpEntry>()
        for (e in files) {
            if (!e.isDir && isImage(e)) live[e.path] = e
        }
        var known = 0
        var missing = 0
        val it = knownFiles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (ObjectRepository.parentOf(entry.key) == path) {
                known++
                if (!live.containsKey(entry.key)) {
                    missing++
                    it.remove()
                }
            }
        }
        if (known > 0 && missing >= CARD_SWAP_MIN_MISSING && missing * 2 >= known) {
            Log.w(TAG, "疑似换卡：$path 已知 $known 失踪 $missing → 暂停巡检")
            pause("检测到存储卡变化，自动同步已暂停")
            return
        }
        // 正常删除/变动：接受现状，没见过的面孔进待确认
        watch.count = files.size
        watch.anchor = files.firstOrNull()?.name
        for (e in live.values) {
            if (knownFiles[e.path] == null) markPending(e)
        }
    }

    /** 目录初见：asBaseline=true 记为基准（不传）；false = 监控期间出现的 → 内容全走待确认。 */
    private suspend fun scanNewDir(host: String, path: String, asBaseline: Boolean) {
        val files = readAllPages(host, path) ?: return
        for (e in files) {
            if (!e.isDir && isImage(e)) {
                if (asBaseline) knownFiles[e.path] = e.size to e.timestamp
                else markPending(e)
            }
        }
        knownDirs[path] = DirWatch(files.size, files.firstOrNull()?.name)
    }

    /** 新照片入队 + 保证排空线程在跑。 */
    private fun enqueueNew(files: List<ObjectRepository.FtpEntry>, guid: String) {
        // ★ 2.10 修复（F2）：入队幂等保险——按 path\0size\0mtime 身份过滤，已在册的丢弃；
        //   入队成功的登记。防任何调用路径的重复入队；同路径重拍（size/mtime 变化）
        //   身份不同，仍会合法重传，语义保留。
        val fresh = files.filterNot { enqueuedIds.contains(identityKey(it.path, it.size, it.timestamp)) }
        if (fresh.isEmpty()) return
        val raw = fresh.map {
            TransferItem(
                id = it.path,
                name = it.name,
                path = it.path,
                size = it.size,
                mtime = it.timestamp,
            )
        }
        // ★ 2.10 修复（F3）：队列侧只读防线——同身份且还在队（QUEUED/RUNNING）的不再入队。
        //   enqueueNew 拿不到 DeviceQueue：先取目标队列（与下方 enqueueBatch 同一对象），再过滤。
        val q = TransferStore.queueFor(guid)
        val items = raw.filterNot { TransferStore.hasActive(q, it.id, it.size, it.mtime) }
        if (items.isEmpty()) return
        TransferStore.enqueueBatch(q, items)
        for (it in items) enqueuedIds.add(identityKey(it.id, it.size, it.mtime))
        autoCount += items.size
        statusText = "监控中 · 已自动接收 $autoCount 张"
        Log.d(TAG, "自动入队 ${items.size} 张（累计 $autoCount）：首张=${items.first().path}")
        val ctx = appContext
        if (ctx != null) {
            // 可重入：已经在排空时这次调用无害（保证"队列非空时排空线程一定在"）
            runCatching { DownloadService.start(ctx) }
        }
    }

    /**
     * ★ 2.11 第二轮（§5.1）**串行补位（水位 = 1）**：保证队列里**最多 1 条**
     * 来自待发池的条目处于活跃（QUEUED/RUNNING）——"上一个传完，下一个才入队"。
     *
     * <p>**幂等**，可随时重复调用（终态回调 [TransferStore.onItemSettled] + 每轮巡检兜底）：
     * <ul>
     *   <li>池内条目已落终态（DONE/FAILED）→ 从池中摘除（流水线消化完毕）；</li>
     *   <li>池内条目"曾入队、现查无记录"（用户删了任务）→ 同样摘除，不重传；</li>
     *   <li>池内仍有活跃条目 → 水位被占（"上一个还没传完"）→ 空操作；</li>
     *   <li>否则从池头（到达序）补 1 条入队。</li>
     * </ul>
     *
     * <p>只碰池 + 队列查询；队列里的用户手动任务不受影响（同步条目按 FIFO 排在后面）。
     */
    private fun topUpQueue(guid: String) {
        if (pendingEnqueue.isEmpty()) return
        val q = TransferStore.queueFor(guid)
        var active = false
        val settled = ArrayList<ObjectRepository.FtpEntry>()
        for (e in pendingEnqueue) {
            when (TransferStore.stateOf(q, e.path, e.size, e.timestamp)) {
                TransferState.QUEUED, TransferState.RUNNING -> active = true
                TransferState.DONE, TransferState.FAILED -> settled.add(e)
                null ->
                    // 队列里查无此条：若曾在册（入队过又没了记录，如用户删了任务）
                    // 视为已处理，摘除；从未入队才是真正的"待补"。
                    if (enqueuedIds.contains(identityKey(e.path, e.size, e.timestamp))) {
                        settled.add(e)
                    }
            }
        }
        if (settled.isNotEmpty()) pendingEnqueue.removeAll(settled)
        if (active) return   // 水位占着：等它落终态（回调/兜底会再来）
        val next = pendingEnqueue.firstOrNull() ?: return
        enqueueNew(listOf(next), guid)
    }

    // ============================================================
    // 同步页操作（★ 2.11 第二轮 §5.4）：移出 / 立刻开始传输
    // ============================================================

    /**
     * ★ 2.11 第二轮（§5.4）"**移出同步列表**"（同步页选择模式 / 长按操作）。
     *
     * <p>语义 = "这张我不要了"：
     * - 面板（[panelEntries]）与待发池（[pendingEnqueue]）里摘除；
     * - 已入队且**活跃**（QUEUED/RUNNING）的连队列记录一起移除（Q2 定版）；
     * - DONE/FAILED 只移面板 —— 动已传完的文件无意义，任务记录留在传输页；
     * - 摘走的若是"正在传的那张"（RUNNING），打断当前批次：半途的 `.part` 留在
     *   磁盘上不再续传，队列剩余条目在新一批里继续。
     *
     * <p>主线程调用；移出后顺手补一次水位（下一个立刻顶上，不留空窗）。
     */
    fun removeFromPanel(entries: List<ObjectRepository.FtpEntry>) {
        if (entries.isEmpty()) return
        val guid = sessionGuid ?: ConnectionCenter.camera?.guidHex ?: return
        val q = TransferStore.queueFor(guid)
        var hitRunning = false
        for (e in entries) {
            panelEntries.removeAll { it.path == e.path && it.size == e.size && it.timestamp == e.timestamp }
            pendingEnqueue.removeAll { it.path == e.path && it.size == e.size && it.timestamp == e.timestamp }
            when (TransferStore.stateOf(q, e.path, e.size, e.timestamp)) {
                TransferState.QUEUED -> {
                    q.items.firstOrNull { it.id == e.path }?.let { TransferStore.remove(q, it) }
                }
                TransferState.RUNNING -> {
                    hitRunning = true
                    q.items.firstOrNull { it.id == e.path }?.let { TransferStore.remove(q, it) }
                }
                else -> Unit   // DONE/FAILED/查无：只移面板（任务记录留在传输页）
            }
        }
        val ctx = appContext
        if (hitRunning && ctx != null && DownloadService.isActive()) {
            // 正在传的那张被摘走了：打断 + 旧线程退出后补位/重启
            interruptAndRestart(ctx, guid) { topUpQueue(guid) }
        } else {
            topUpQueue(guid)
        }
    }

    /**
     * ★ 2.11 第二轮（§5.4）"**立刻开始传输**"（打断式，Q3 定版）：
     * 把选中条目提为队头并打断当前批次 —— 插队项先传，被打断项从断点自动续传。
     *
     * <p>流程（主线程）：摘出待发池 → 入队（幂等）→ 提头 + 收回 QUEUED（磁盘
     * `.part` 原样，断点天然保留）→ 打断旧批次、等其退出后从队头起跑。
     */
    fun startNow(entries: List<ObjectRepository.FtpEntry>) {
        if (entries.isEmpty()) return
        val ctx = appContext ?: return
        val guid = sessionGuid ?: ConnectionCenter.camera?.guidHex ?: return
        val q = TransferStore.queueFor(guid)
        val wanted = entries.map {
            TransferItem(id = it.path, name = it.name, path = it.path, size = it.size, mtime = it.timestamp)
        }
        // 摘出待发池（身份精确匹配：同 path 重拍不算同一条）——避免它们再走正常补位
        pendingEnqueue.removeAll { pool ->
            entries.any { it.path == pool.path && it.size == pool.size && it.timestamp == pool.timestamp }
        }
        TransferStore.enqueueBatch(q, wanted)   // 幂等：已入队原位刷新，不产生重复条目
        TransferStore.prioritize(q, wanted)     // 提头 + 收回 QUEUED
        interruptAndRestart(ctx, guid)          // 打断 + 等旧线程退出后从队头起跑
    }

    /**
     * ★ 2.11 第二轮（§5.4）：打断当前传输批次，**等旧排空线程真正退出后**再执行
     * [then]（补位等）并按需把排空重新拉起来。
     *
     * <p>**为什么不能 stopCurrent 后立刻 start**：`start` 的 onStartCommand 会把
     * `stopAll` 重置回 false（新批次语义）—— 若旧线程还没检查到停止，会当作无事发生
     * 继续传当前文件（打断失效）；且旧线程未退出时 `runningFlag` 的 CAS 会直接吞掉
     * 那次 start（新批次不启动）。所以必须等 [DownloadService.isActive] 变 false。
     *
     * <p>[then] 在**主线程**执行。只有队列里确实还有 QUEUED 才重新 start ——
     * 空跑一次会白弹一条"传输结束 完成 0 · 失败 0"的通知。
     */
    private fun interruptAndRestart(ctx: Context, guid: String, then: (() -> Unit)? = null) {
        DownloadService.stopCurrent(ctx)
        val sc = scope ?: run { then?.invoke(); return }
        sc.launch {
            var waited = 0L
            while (DownloadService.isActive() && waited < 10_000L) {
                delay(100L)
                waited += 100L
            }
            then?.invoke()
            val q = TransferStore.queueFor(guid)
            if (q.items.any { it.state == TransferState.QUEUED }) {
                DownloadService.start(ctx)
            }
            Log.d(
                TAG,
                "打断重启：等待 ${waited}ms（旧线程${if (waited >= 10_000L) "超时仍在运行" else "已退出"}）",
            )
        }
    }

    /** 卡变化暂停：开关保持开、巡检停 —— 用户关掉再开 = 重建基准。 */
    private fun pause(msg: String) {
        active = false
        // ★ 2.10（D4）：暂停也必须收掉常驻巡检标志——否则 pollLoop 会在下一轮把
        //   换卡后的目录再全量对账一遍（每 4s 重读一遍，纯浪费）；重连后自动重启。
        patrolling = false
        pollJob = null
        statusText = msg
    }

    // ============================================================
    // 基准
    // ============================================================

    /**
     * 建立基准：`/DCIM` 全量 + 每个一级子目录全量；记图片身份与各目录账本
     * （条目数 + 锚点）。开关打开那一刻已存在的照片**不传**（只拍新拍的）。
     */
    private suspend fun buildBaseline(host: String): Boolean {
        knownFiles.clear()
        knownDirs.clear()
        pendingNew.clear()
        enqueuedIds.clear()   // ★ 2.10（F2）：重建基准 = 重新认账，入队幂等册同步清空
        stableRounds = 0
        val dcim = readAllPages(host, "/DCIM") ?: return false
        for (e in dcim) {
            if (e.isDir) {
                scanNewDir(host, e.path, asBaseline = true)
                delay(SCAN_DIR_GAP_MS)
            } else if (isImage(e)) {
                knownFiles[e.path] = e.size to e.timestamp
            }
        }
        Log.d(TAG, "基准建立：${knownDirs.size} 个目录 · ${knownFiles.size} 张")
        return true
    }

    private suspend fun readAllPages(host: String, dir: String): List<ObjectRepository.FtpEntry>? {
        val all = ArrayList<ObjectRepository.FtpEntry>()
        var offset = 0
        while (true) {
            val page = listPageSafe(host, dir, offset, scan = true) ?: return if (all.isEmpty()) null else all
            all.addAll(page.entries)
            if (!page.hasMore || page.nextOffset <= offset) break
            offset = page.nextOffset
        }
        return all
    }

    // ============================================================
    // 有界自动重连（仅"相机后台化"声明后的断开）
    // ============================================================

    /**
     * 每 5s 一轮、最多 [RECONNECT_ROUNDS] 轮（≈半分钟）；每 [RECONNECT_SWEEP_EVERY]
     * 轮补一次全子网扫描（相机换 IP 只有这条能找回来，9 秒级）。
     * 全程**静默**（不写 lastError）：相机拆 AP 重建期间探测不到是预期行为。
     *
     * <p>★ 第四批（项2）：窗口期间主界面连接卡片显示"正在连接 + 等待重新连接"
     * （[ConnectionCenter.awaitingReconnect]）；收尾（成功/放弃/被打断）统一在
     * finally 里复位——"正在连接"不会赖着不走。
     *
     * @param carrySwitch true = "边拍边传"开关保持中（重连成功后恢复监控、用户关开关即停）；
     *   false = 开关没开、**纯声明驱动**（重连成功后只恢复连接，不建基准 / 不巡检；
     *   退出条件是"连上"而不是 enabled）。
     */
    private fun startBoundedReconnect(carrySwitch: Boolean) {
        if (reconnectJob?.isActive == true) return
        val ctx = appContext ?: return
        val sc = scope ?: return
        // 进窗口（同步置位：主界面同一帧内从"已连接"切到"正在连接"，不留闪缝）
        ConnectionCenter.updateAwaitingReconnect(true)
        reconnectJob = sc.launch {
            try {
                // ★ 第三批（§6）：目标 = 本次会话那台；从没开过开关（无会话）时退到"要连哪台"
                val guid = sessionGuid ?: ConnectionCenter.targetGuid
                for (round in 1..RECONNECT_ROUNDS) {
                    // 开关开着时：用户关掉开关即停止；纯声明驱动时不看 enabled
                    if (active || (carrySwitch && !enabled)) return@launch
                    statusText = "等待相机…"
                    val sweep = round % RECONNECT_SWEEP_EVERY == 0
                    ConnectionCenter.silentReconnectAttempt(ctx, sweep)
                    // 等一轮（每 500ms 看一眼是否已经连上）
                    val steps = (RECONNECT_INTERVAL_MS / 500L).toInt()
                    for (i in 0 until steps) {
                        delay(500L)
                        if (active || (carrySwitch && !enabled)) return@launch
                        if (ConnectionCenter.state == ConnectionCenter.State.CONNECTED &&
                            (guid == null || ConnectionCenter.camera?.guidHex == guid)
                        ) {
                            if (carrySwitch) {
                                resumeMonitoring()
                                if (active) return@launch
                            } else {
                                // 开关没开：连上即完成（不建基准、不恢复监控 —— 那是开关的事）
                                expectReconnect = false
                                statusText = ""
                                Log.d(TAG, "静默重连成功（无开关会话），连接已恢复")
                                return@launch
                            }
                        }
                    }
                }
                // 轮数用尽：停止尝试，开关保持开 —— 用户可手动"重新连接"或关掉开关
                expectReconnect = false
                statusText = "等待相机…（已停止自动重连）"
                Log.w(TAG, "自动重连 $RECONNECT_ROUNDS 轮未成功，停止")
            } finally {
                // 成功 / 轮尽 / 被打断统一收尾：退出"等待重连"显示
                ConnectionCenter.updateAwaitingReconnect(false)
            }
        }
    }

    /** 重连成功后重建基准、恢复巡检（幂等）。 */
    private suspend fun resumeMonitoring() {
        if (!enabled || active || resuming) return
        val host = ConnectionCenter.host ?: return
        val guid = ConnectionCenter.camera?.guidHex ?: return
        val wanted = sessionGuid
        if (wanted != null && guid != wanted) {
            Log.w(TAG, "重连到的是另一台相机（$guid ≠ $wanted）：关掉边拍边传")
            disable()
            return
        }
        resuming = true
        try {
            sessionGuid = guid
            expectReconnect = false
            statusText = "正在启动同步…"
            Log.d(TAG, "重连成功，重建基准（$guid）")
            val ok = buildBaseline(host)
            if (!enabled) return
            if (!ok) {
                statusText = "基准建立失败，请重试"
                return
            }
            active = true
            statusText = "监控中 · 已自动接收 $autoCount 张"
            pollJob?.cancel()
            pollJob = scope?.launch { pollLoop(host, guid) }
        } finally {
            resuming = false
        }
    }

    // ============================================================
    // 小工具
    // ============================================================

    private fun isImage(e: ObjectRepository.FtpEntry): Boolean {
        val ext = e.ext
        return ext == "jpg" || ext == "jpeg" || ext == "arw"
    }

    private fun markPending(e: ObjectRepository.FtpEntry) {
        pendingNew[e.path] = Pending(e.size, e.timestamp, 0)
    }

    /** 路径最后一段（confirmPending 用 stat 结果重建 FtpEntry 时取文件名）。 */
    private fun nameOf(path: String): String = path.substringAfterLast('/')

    /** 入队幂等身份（★ 2.10 / F2）：`path\0size\0mtime` 三元组（与缩略图缓存键同构）。 */
    private fun identityKey(path: String, size: Long, mtime: Long): String =
        path + "\u0000" + size + "\u0000" + mtime

    /** 锚点检查：目录第一条（字母序最小）还是不是原来那个名字。 */
    private suspend fun anchorUnchanged(host: String, path: String, anchor: String?): Boolean {
        if (anchor == null) return true
        // ★ 2.10 修复（P1）：探针失败改为按"未变"处理（return true）。原先 return false 会被
        //   当成"锚点变了"→ 增长轮里一次网络抖动就触发 fullRecheck（全目录重读，纯浪费）。
        //   growDir 走增量且自带 knownFiles 逐项守卫，安全；网络恢复后自然自愈。
        //   换卡检测不受影响：探针成功路径的分支语义原样保留。
        val page = listPageSafe(host, path, 0, scan = false) ?: return true
        return page.entries.firstOrNull()?.name == anchor
    }

    /** 读一页目录；失败回 null（调用方决定跳过或中止）。 */
    private suspend fun listPageSafe(
        host: String,
        dir: String,
        offset: Int,
        scan: Boolean,
    ): ObjectRepository.DirPage? = withContext(Dispatchers.IO) {
        try {
            if (scan) {
                ObjectRepository.listPage(host, dir, offset, 256, ObjectRepository.SCAN_LIST_TIMEOUT_MS)
            } else {
                ObjectRepository.listPage(host, dir, offset, 256)
            }
        } catch (e: Exception) {
            Log.d(TAG, "listPage($dir @$offset) 失败：${e.message}")
            null
        }
    }

    /** 计数探针；失败回 null（网络抖动不当作"变化"）。 */
    private suspend fun dirCountSafe(host: String, dir: String): Int? =
        withContext(Dispatchers.IO) {
            runCatching { ObjectRepository.dirCount(host, dir) }.getOrNull()
        }
}
