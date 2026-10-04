package io.github.bi2qfa.alphasync.transfer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.bi2qfa.alphasync.core.StorageSink
import io.github.bi2qfa.alphasync.data.PairingStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 传输队列（**按设备隔离**）。
 *
 * ## 隔离这件事是硬要求，不是整洁癖
 *
 * 条目主键是**相机内路径**（`/DCIM/100MSDCF/DSC00001.ARW`），两台相机上完全一样。
 * 所以一旦让"设备 A 的队列"和"设备 B 的队列"混在一起，后果不只是看着乱：
 * - 续传会拿 B 上同名文件的字节接到 A 的 `.part` 后面，产出的文件前半段是另一台相机的
 *   像素，且没有任何校验和能发现；
 * - 用 A 的路径去 B 上取文件，取回来的是 B 的同名文件，还写进 A 的下载目录。
 *
 * ## 结构：一台设备一份队列，列表对象身份固定
 *
 * [DeviceQueue] 是"设备码 → 该设备那一份 SnapshotStateList"的载体。切设备**只换
 * [current] 指向哪一份，永不换列表对象本身** —— 正在跑的那个批次钉着自己那份引用，
 * 所以中途切设备既不会把它的进度写进别人的桶，也不会让它的循环去搬新设备的条目。
 *
 * ## 批次的归属
 *
 * [markBatchStarted] 记下"这一批钉的是哪一份队列"（[runningQueue]）。批次统计
 * （[batchRunning] / [batchDoneBytes] / 速度）和 [onConnectionLost] 都认这一份 ——
 * 它们是**那个正在跑的批次**的属性，不是"当前界面上那台设备"的属性：
 * 用户切到另一台设备时，后台那一批还在实实在在地读写原来那台相机，统计不能跟着界面清零。
 */
object TransferStore {

    /**
     * 一台设备的队列。
     *
     * 列表对象**身份固定**：首次创建后一直用同一个 SnapshotStateList，
     * 切走再切回来还是它（进度、顺序、断点都在）。
     */
    class DeviceQueue internal constructor(val guid: String) {
        internal val items: SnapshotStateList<TransferItem> =
            mutableListOf<TransferItem>().toMutableStateList()
    }

    private val queues = HashMap<String, DeviceQueue>()

    /** 已经装载过磁盘内容的设备码（避免切回来时用磁盘覆盖内存里更新的进度）。 */
    private val loaded = HashSet<String>()

    /**
     * ★ 审修复（H4）：队列列表是 SnapshotStateList，Compose 要求**写入串行化**。
     * 传输线程（DownloadService 的 TransferDrain）高频写 [update]/[updateProgress]，
     * UI 线程同时可能入队/删除/清空 —— 两线程并发写会出现 lost-update（进度回退、
     * 刚入队的条目被旧版本覆盖）。所有触碰列表与批次集合（batchIds/batchBase）的
     * 函数统一走这把锁；`synchronized` 可重入，内部互调（persist/requeueRunning）安全。
     * 读取（Compose 重组）不加锁：Snapshot 读是版本化快照，最坏只看到一帧旧值。
     */
    private val lock = Any()

    private var current = mutableStateOf(DeviceQueue(""))

    /** 当前设备的队列（UI 与"入队/删除"这类用户动作都认它）。 */
    val currentQueue: DeviceQueue get() = current.value

    /** 当前设备的条目列表（UI 直接读它）。 */
    val items: SnapshotStateList<TransferItem> get() = current.value.items

    var batchRunning by mutableStateOf(false)
        private set

    var batchDoneBytes by mutableLongStateOf(0L)
        private set
    var batchTotalBytes by mutableLongStateOf(0L)
        private set
    var batchStartedAt by mutableLongStateOf(0L)
        private set
    var batchSpeedBps by mutableLongStateOf(0L)
        private set

    private lateinit var prefs: android.content.SharedPreferences

    /** 当前队列归属的相机设备码（多设备适配后队列按它分桶）。 */
    private const val KEY_CAMERA_GUID = "cameraGuid"

    /** 各设备的队列桶：JSON 对象 `{设备码: [条目…]}`。 */
    private const val KEY_QUEUES = "queues"

    /** 1.0 的全局单队列（只在升级后的第一次装载时当"待认领队列"用，迁移用，不删）。 */
    private const val KEY_LEGACY_ITEMS = "items"

    /**
     * 1.0 升级上来的"无主队列"：那时只有一个全局队列，且 KEY_CAMERA_GUID 可能还不存在，
     * 所以此刻**无法知道它属于哪台相机**。先攥着，等第一台相机连上来交给它
     * （见 [onCameraChanged]）—— 丢掉就等于用户升级一次、待传任务全没了。
     */
    private var legacyPending: List<TransferItem> = emptyList()

    fun load(context: Context) {
        prefs = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        val guid = storedGuid()
        // ★ 归属已失效（KEY_CAMERA_GUID 那台相机**已不在配对表里**，说明它的设备码换过了
        //   —— 相机端清过数据/重装就会换码）时，这份队列按"无主"处理：交给下一台连上来的
        //   设备认领。挂在那个永远连不上的设备码下面，用户在界面上就再也看不到这些任务，
        //   等于丢了（实测本机就是这样：队列还在、键指向一个已经消失的设备码）。
        if (guid.isEmpty() || !PairingStore.contains(guid)) {
            legacyPending = restoreItems(prefs.getString(KEY_LEGACY_ITEMS, null))
            if (legacyPending.isNotEmpty()) {
                android.util.Log.d("TransferStore", "队列无主（$guid），待下一台设备认领 ${legacyPending.size} 条")
            }
            return
        }
        current.value = queueFor(guid)
    }

    private fun storedGuid(): String =
        if (!::prefs.isInitialized) "" else (prefs.getString(KEY_CAMERA_GUID, "") ?: "").lowercase()

    /**
     * 取某台设备的队列（没有就建一个空桶；首次取时从磁盘装载）。
     *
     * 磁盘装载只在**首次**做：切回来时内存里那份才是最新的（进度是高频内存更新，
     * 落盘只有状态变化时才写），重新装载会把刚传的进度吞回去。
     */
    fun queueFor(guidHex: String): DeviceQueue {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            val key = guidHex.lowercase()
            queues[key]?.let { if (key in loaded) return it }
            val q = queues.getOrPut(key) { DeviceQueue(key) }
            if (loaded.add(key)) {
                // 只读这个设备自己的桶；桶还不存在（从没入过队）就是空队列。
                // 注意**不能**拿 1.0 的全局队列兜底：那会把同一份老队列并进每一台设备的桶里
                // （点到谁谁有一份），正是"两台相机的任务串在一起"。无主队列的认领在
                // onCameraChanged 里做，只认第一台。
                q.items.addAll(restoreItems(bucketJson(key)))
            }
            return q
        }
    }

    /** RUNNING → QUEUED（.part 保留、进度可续）；按 id 去重（重复 key 会让 LazyColumn 闪退）。 */
    private fun restoreItems(json: String?): List<TransferItem> =
        TransferJson.listFromJson(json)
            .map { if (it.state == TransferState.RUNNING) it.copy(state = TransferState.QUEUED) else it }
            .distinctBy { it.id }

    private fun bucketJson(guid: String): String? {
        if (!::prefs.isInitialized) return null
        val all = runCatching { JSONObject(prefs.getString(KEY_QUEUES, "{}") ?: "{}") }.getOrNull() ?: return null
        return if (all.has(guid)) all.optString(guid, "") else null
    }

    private fun persist(q: DeviceQueue) {
        if (!::prefs.isInitialized) return
        if (q.guid.isEmpty()) return   // 还没有归属：等认领（见 onCameraChanged）
        // 加固：队列大时序列化/写盘异常不允许带死传输线程
        runCatching {
            val all = runCatching { JSONObject(prefs.getString(KEY_QUEUES, "{}") ?: "{}") }
                .getOrDefault(JSONObject())
            all.put(q.guid, JSONArray(TransferJson.listToJson(q.items)))
            prefs.edit().putString(KEY_QUEUES, all.toString()).apply()
        }
    }

    private fun persist() = persist(current.value)

    /** 异常兜底路径的立即持久化（如 RUNNING→QUEUED 收回后落盘）。 */
    fun persistNow() {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            persist(runningQueue ?: current.value)
        }
    }

    /**
     * 连接的相机变了 → **切到那台设备的队列**（不是清空）。
     *
     * 多设备适配：每台相机一套队列，切设备就是换一套。清空是不行的 —— 那等于
     * 用户切一圈设备回来，待传任务与断点全没了。
     *
     * ★ 这里**只换当前指向哪一份**，不碰任何批次统计：正在跑的那一批钉在另一份队列上、
     *   还在实实在在地读写那台相机，把统计清零就是让进度环假装"没有传输"。
     */
    fun onCameraChanged(guidHex: String) {
        if (guidHex.isBlank()) return
        if (!::prefs.isInitialized) return
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            val key = guidHex.lowercase()
            if (current.value.guid == key) return
            val prev = current.value.guid
            persist(current.value)   // 先把上一台的任务写回它自己的桶
            prefs.edit().putString(KEY_CAMERA_GUID, key).apply()
            val q = queueFor(key)
            // 认领 1.0 的无主队列：第一台连上来的设备就是它的主人（去重后并入）
            if (legacyPending.isNotEmpty()) {
                val add = legacyPending.filterNot { p -> q.items.any { it.id == p.id } }
                q.items.addAll(add)
                legacyPending = emptyList()
                persist(q)
            }
            current.value = q
            // 留痕：队列切换是"隔离"这件事的落点，出错时只看到"列表没变"，必须能查
            android.util.Log.d(
                "TransferStore",
                "队列切换：$prev → $key（${q.items.size} 条）",
            )
        }
    }

    // ============================================================
    // 变更（UI 用"当前队列"那一版；下载批次用带 DeviceQueue 的那一版）
    // ============================================================

    /**
     * 入队：同 id 已在队列（等待/传输中）则忽略；已完成/失败的原位重排为
     * 等待（进度保留可续传）。绝不允许产生第二条同 id 条目——重复 key 会
     * 直接闪退传输页。
     *
     * ★ 入队时就把 [TransferItem.deviceDir] 钉死成**这份队列所属设备**的目录名：
     *   条目一旦落进 A 的桶，"文件该进 A 的目录"这件事就定了，之后不管用户切到哪台
     *   相机、不管下载发生在什么时候，都不再重新推导（现算必然出事，见 StorageSink 注释）。
     */
    fun enqueue(q: DeviceQueue, item: TransferItem) {
        enqueueBatch(q, listOf(item), persistOnce = true)
    }

    /**
     * 批量入队：先在内存中一次合并、去重，最后只序列化/落盘一次。
     *
     * 大目录几千项时，旧实现的每个 enqueue 都全量写一次 SharedPreferences，实际
     * 是 O(n²)；这里保持“同 id 只保留一条”的约束，但不再逐项写盘。
     */
    fun enqueueBatch(q: DeviceQueue, newItems: List<TransferItem>, persistOnce: Boolean = true) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            val index = HashMap<String, Int>(q.items.size + newItems.size)
            for ((i, old) in q.items.withIndex()) index[old.id] = i
            var changed = false
            val fresh = ArrayList<TransferItem>(newItems.size)
            val freshIndex = HashMap<String, Int>(newItems.size)
            for (raw in newItems) {
                val item = raw
                val stamped = if (item.deviceDir.isBlank()) {
                    item.copy(deviceDir = StorageSink.deviceFolderFor(q.guid))
                } else item
                val idx = index[stamped.id]
                if (idx == null) {
                    freshIndex[stamped.id] = fresh.size
                    fresh.add(stamped)
                    // 同批内去重时以最后一个为准，避免同一目录遍历结果重复入队。
                    index[stamped.id] = -1
                    changed = true
                    continue
                }
                if (idx < 0) {
                    // 同一批中已经接受过这个 id，后出现的刷新身份即可。
                    val freshIdx = freshIndex[stamped.id] ?: -1
                    if (freshIdx >= 0) fresh[freshIdx] = stamped
                    continue
                }
                val ex = q.items[idx]
                val identityChanged = ex.path != stamped.path ||
                    ex.size != stamped.size ||
                    ex.mtime != stamped.mtime
                if (ex.state == TransferState.QUEUED || ex.state == TransferState.RUNNING) {
                    if (identityChanged) {
                        q.items[idx] = ex.copy(
                            name = stamped.name,
                            path = stamped.path,
                            size = stamped.size,
                            mtime = stamped.mtime,
                            deviceDir = stamped.deviceDir.ifBlank { ex.deviceDir },
                            doneBytes = 0L,
                        )
                        changed = true
                    }
                    continue
                }
                q.items[idx] = ex.copy(
                    name = stamped.name,
                    path = stamped.path,
                    size = stamped.size,
                    mtime = stamped.mtime,
                    deviceDir = stamped.deviceDir.ifBlank { ex.deviceDir },
                    state = TransferState.QUEUED,
                    doneBytes = if (identityChanged || ex.state == TransferState.DONE) 0L
                    else ex.doneBytes.coerceIn(0, stamped.size),
                )
                changed = true
            }
            if (fresh.isNotEmpty()) {
                // 保持“新任务在上”的历史顺序。
                for (item in fresh.asReversed()) q.items.add(0, item)
            }
            if (changed && persistOnce) persist(q)
        }
    }

    /**
     * 只读防线（★ 2.10 / F3）：该队列里是否已有一条"同一身份且还没跑完"的条目。
     *
     * 身份 = id + size + mtime；只认 QUEUED/RUNNING（DONE/FAILED 不算——重拍/重传
     * 语义不受影响）。调用方（LiveTransferCenter.enqueueNew）先查后入，与
     * [enqueueBatch] 的同 id 约束互为最后一道保险。
     */
    fun hasActive(q: DeviceQueue, id: String, size: Long, mtime: Long): Boolean =
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            q.items.any {
                it.id == id &&
                    (it.state == TransferState.QUEUED || it.state == TransferState.RUNNING) &&
                    it.size == size &&
                    it.mtime == mtime
            }
        }

    /**
     * ★ 2.11 第二轮（§5.1）：按**身份**（id+size+mtime）取条目当前状态（没有 = null）。
     * 同步页串行水位（LiveTransferCenter.topUpQueue）用它区分
     * "在队列活跃 / 已落终态 / 未入队"三种情况。
     */
    fun stateOf(q: DeviceQueue, id: String, size: Long, mtime: Long): TransferState? =
        synchronized(lock) {
            q.items.firstOrNull { it.id == id && it.size == size && it.mtime == mtime }?.state
        }

    /**
     * ★ 2.11 第二轮（§5.1）：条目**落终态**（DONE/FAILED）时的回调 —— 由 DownloadService
     * 的排空循环在**传输线程**上调用；同步页串行补位（"上一个传完 → 下一个才入队"）挂它。
     * 参数 = (队列归属设备码 guid, 落定条目)。**挂载方负责线程切换**（回调不在主线程）。
     */
    var onItemSettled: ((String, TransferItem) -> Unit)? = null

    fun enqueue(item: TransferItem) = enqueue(current.value, item)

    fun enqueueAll(q: DeviceQueue, newItems: List<TransferItem>) {
        enqueueBatch(q, newItems)
    }

    fun enqueueAll(newItems: List<TransferItem>) = enqueueAll(current.value, newItems)

    fun remove(q: DeviceQueue, item: TransferItem) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            q.items.removeAll { it.id == item.id }
            persist(q)
        }
    }

    /**
     * ★ 2.11 第二轮（§5.4）：把指定条目**收回 QUEUED 并提到队头**（"立刻开始传输"）。
     *
     * <p>三种现状统一处理：
     * - QUEUED：原位摘出、刷新身份、插到队头；
     * - RUNNING：收回 QUEUED 提头（`.part` 断点在磁盘上、与内存状态无关，续传天然保留）；
     * - DONE/FAILED：重置为 QUEUED 提头（重传；已完成项磁盘无 .part，从头传）。
     *
     * <p>队列里**没有**的条目（从未入队）跳过 —— 调用方负责先入队。
     * 只改队列位置与状态，磁盘一律不碰；调用后由调用方"打断 + 重启"让排空从队头起跑。
     */
    fun prioritize(q: DeviceQueue, items: List<TransferItem>) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            var changed = false
            for (raw in items) {
                val idx = q.items.indexOfFirst { it.id == raw.id }
                if (idx < 0) continue
                val ex = q.items[idx]
                val sameIdentity = ex.size == raw.size && ex.mtime == raw.mtime
                val re = ex.copy(
                    name = raw.name,
                    path = raw.path,
                    size = raw.size,
                    mtime = raw.mtime,
                    state = TransferState.QUEUED,
                    // 保留断点：身份相同且未完成（含失败可续）时沿用原进度；
                    // 重拍（身份变了）或重传已完成项 → 归零。
                    doneBytes = if (sameIdentity && ex.state != TransferState.DONE) {
                        ex.doneBytes.coerceIn(0, raw.size)
                    } else 0L,
                    deviceDir = ex.deviceDir.ifBlank { raw.deviceDir },
                )
                q.items.removeAt(idx)
                q.items.add(0, re)
                changed = true
            }
            if (changed) persist(q)
        }
    }

    fun remove(item: TransferItem) = remove(current.value, item)

    fun update(q: DeviceQueue, item: TransferItem) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            val idx = q.items.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                q.items[idx] = item
                persist(q)
            }
        }
    }

    fun update(item: TransferItem) = update(current.value, item)

    /** 进度高频更新：仅内存，不落盘（崩溃恢复用 .part 长度）；按文件大小钳制 */
    fun updateProgress(q: DeviceQueue, id: String, doneBytes: Long) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            val idx = q.items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val it = q.items[idx]
                val d = if (it.size > 0) doneBytes.coerceIn(0, it.size) else doneBytes
                q.items[idx] = it.copy(doneBytes = d)
            }
        }
    }

    fun updateProgress(id: String, doneBytes: Long) = updateProgress(current.value, id, doneBytes)

    fun clearFinished(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            q.items.removeAll { it.state == TransferState.DONE }
            persist(q)
        }
    }

    fun clearFinished() = clearFinished(current.value)

    /**
     * ★ 第三批（§7）：删全部"进行中"任务（QUEUED/RUNNING；已完成/失败保留）。
     * 只删任务记录，绝不动已下载的文件；正在跑的那条由调用方配 `DownloadService.stopCurrent`
     * 立刻打断（与 [clearAll] 同款处理）。
     */
    fun clearActive(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            q.items.removeAll {
                it.state == TransferState.QUEUED || it.state == TransferState.RUNNING
            }
            persist(q)
        }
    }

    fun clearActive() = clearActive(current.value)

    /** 清空全部任务条目（用户定版：只删任务记录，绝不动已下载的文件） */
    fun clearAll(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            q.items.clear()
            persist(q)
        }
    }

    fun clearAll() = clearAll(current.value)

    /** 批次启动前调用：FAILED → QUEUED，doneBytes 按合理性截断（≤size） */
    fun resetFailedForStart(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            for ((idx, it) in q.items.withIndex()) {
                if (it.state == TransferState.FAILED) {
                    q.items[idx] = it.copy(
                        state = TransferState.QUEUED,
                        doneBytes = it.doneBytes.coerceIn(0, it.size),
                    )
                }
            }
            persist(q)
        }
    }

    fun resetFailedForStart() = resetFailedForStart(current.value)

    fun countBy(q: DeviceQueue, state: TransferState): Int =
        synchronized(lock) { q.items.count { it.state == state } }   // ★ 审修复（H4）：见 [lock] 注释

    fun countBy(state: TransferState): Int = countBy(current.value, state)

    // ===== 批次统计（属于"正在跑的那一批"，钉在某一台设备上） =====

    /** 正在跑的那个批次钉住的队列；null = 当前没有批次。 */
    var runningQueue: DeviceQueue? = null
        private set

    private val batchIds = HashSet<String>()
    private val batchBase = HashMap<String, Long>()
    private var lastSampleAt = 0L
    private var lastSampleBytes = 0L

    fun markBatchStarted(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            runningQueue = q
            batchRunning = true
            batchIds.clear()
            batchBase.clear()
            lastSampleAt = 0
            lastSampleBytes = 0
            batchStartedAt = System.currentTimeMillis()
            batchDoneBytes = 0
            batchTotalBytes = 0
            batchSpeedBps = 0
            for (it in q.items) {
                if (it.state == TransferState.DONE) continue
                val base = it.doneBytes.coerceIn(0, it.size)
                batchIds.add(it.id)
                batchBase[it.id] = base
                batchTotalBytes += it.size - base
            }
        }
    }

    /** 每个进度回调调用：done = Σ(当前落盘 - 批次基线)；新入队条目动态纳入 */
    fun updateBatchProgress(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            var done = 0L
            for (it in q.items) {
                if (it.id !in batchIds) {
                    // 批次期间新入队：以入队时进度为基线，剩余量计入总数
                    if (it.state == TransferState.DONE) continue
                    val base = it.doneBytes.coerceIn(0, it.size)
                    batchIds.add(it.id)
                    batchBase[it.id] = base
                    batchTotalBytes += it.size - base
                    continue
                }
                val base = batchBase[it.id] ?: 0L
                done += it.doneBytes.coerceIn(0, it.size) - base
            }
            batchDoneBytes = done.coerceAtLeast(0)

            val now = System.currentTimeMillis()
            if (lastSampleAt == 0L) {
                lastSampleAt = now
                lastSampleBytes = done
                return
            }
            val dt = now - lastSampleAt
            if (dt >= 1000) {
                val instant = (done - lastSampleBytes).coerceAtLeast(0) * 1000 / dt
                batchSpeedBps = if (batchSpeedBps == 0L) instant
                else (batchSpeedBps * 3 + instant) / 4 // EMA 平滑
                lastSampleAt = now
                lastSampleBytes = done
            }
        }
    }

    fun updateBatchProgress() = updateBatchProgress(runningQueue ?: current.value)

    fun markBatchEnded() {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            batchRunning = false
            runningQueue = null
            batchIds.clear()
            batchBase.clear()
        }
    }

    /** 把某个队列里 RUNNING 的条目收回 QUEUED（进度保留）。 */
    fun requeueRunning(q: DeviceQueue) {
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            for ((idx, it) in q.items.withIndex()) {
                if (it.state == TransferState.RUNNING) {
                    q.items[idx] = it.copy(state = TransferState.QUEUED)
                }
            }
        }
    }

    /**
     * 连接丢失（心跳失联 / 切设备 / 相机端退出）时把 RUNNING 收回 QUEUED，进度保留。
     *
     * ★ 认的是**正在跑的那个批次**所属的队列（[runningQueue]），不是"当前界面那台"：
     *   用户切到另一台设备后连接才丢的话，要收回的是原来那批的条目。
     */
    fun onConnectionLost() {
        if (!batchRunning) return
        synchronized(lock) {   // ★ 审修复（H4）：见 [lock] 注释
            (runningQueue ?: current.value).let { q ->
                requeueRunning(q)
                persist(q)
            }
            markBatchEnded()
        }
    }
}
