package io.github.bi2qfa.alphasync.data

import io.github.bi2qfa.alphasync.ptpip.ObjectRepository

/**
 * 文件列表的**进程级内存缓存**（2.6 L2/L3；2.8 指纹换计数）。
 *
 * <p>动机：`MainActivity` 用 `AnimatedContent` 切页，切走再回来 FilesScreen 整个
 * 销毁重建 —— 列表与滚动位置全部归零，每次回来都要把目录**全量分页重拉**
 * （714 项的目录 = 3 次往返，弱机真机档要几秒）。现在：
 * <ul>
 *   <li><b>进目录先验指纹</b>（见 FilesScreen.dirFingerprint）：SD 内容没变 →
 *       直接用缓存（零分页拉取）；变了 → 全量重载并回写缓存；</li>
 *   <li><b>滚动位置按"设备 + 目录 + 视图模式"各记一份</b>，回到该层级时恢复。</li>
 * </ul>
 *
 * <p>★ **纯内存、不落盘**（用户点名"内存缓存"）：进程重启即空，冷启动流程
 * 与旧版一致（至少校验一次）。
 *
 * <p>★ key 必须含**设备 guid**：`/DCIM/100MSDCF` 在两台相机上是两个不同目录。
 *
 * <p>★★ **2.8：指纹从"目录 mtime"改为"条目计数"**。2.6 的指纹 = 目录自身
 *   mtime，前提是"FAT 目录 mtime 随内容变"—— 真机实测这台相机**不成立**
 *   （目录 mtime 恒为创建时间），于是"拍了新照片 → 缓存指纹仍命中 → 列表永远
 *   是旧的"。改用 [ObjectRepository.dirCount] 计数探针：条目数变了 = 内容变了。
 *   边界：同数换名（删一张又拍一张）探针看不出 —— 由 FilesScreen 停留期间的
 *   周期性校验兜底（那里每轮真验，不只看计数）。
 *
 * <p>线程安全：写方是主线程（FilesScreen）与 IO 线程（load/loadMore 的结果落地前后
 * 都在主线程，指纹探测在 IO 但只读 map？不——get/put 都可能从不同线程发生），
 * 统一用 [lock] 包住 map 的存取；DirCache 的**字段读写**不额外加锁
 * （单写者=主线程，读到的旧值最坏只是"位置差一行"，不值得为它上锁）。
 */
object FileListCache {

    /**
     * 一层目录的缓存条目：列表 + 指纹 + 滚动位置。
     *
     * @param entries     该目录**已拉到的全部条目**（load 的首页 + loadMore 的后续页）
     * @param nextOffset  已拉到的页偏移：回到该目录继续往下翻时从这里开始
     * @param hasMore     相机端是否还有下一页
     * @param dirStamp    指纹（2.8 起 = **目录条目计数**，见 FilesScreen.dirFingerprint）。
     *                    [STAMP_UNKNOWN] = 探测不到（根目录 / 探测失败）——
     *                    此时"命中"判定恒为 miss，走全量加载（安全侧）
     * @param listScroll  列表模式的**首个可见项 index**（offset 交给 Compose 微调）
     * @param gridScroll  网格模式的首个可见项 index（两种模式各记各的）
     */
    class DirCache(
        var entries: List<ObjectRepository.FtpEntry>,
        var nextOffset: Int,
        var hasMore: Boolean,
        var dirStamp: Int,
        /**
         * 写入这份游标时的**分页方向**（2.10）：true = 当时按 新→旧（reverse）翻的页。
         *
         * <p>nextOffset 的语义是"从某一端已消费的条目数"，**方向不同就是不同的端** ——
         * 倒序游标（已消费最新 N 条）喂给正序请求会从最旧端跳过 N 条。方向不匹配时
         * 调用方必须**不信任缓存的 nextOffset/hasMore**（entries 本身与方向无关，
         * 仍然可以即时铺屏）。
         */
        var pagedDesc: Boolean = true,
        var listScroll: Int = 0,
        var gridScroll: Int = 0,
        var lastUsedAt: Long = System.currentTimeMillis(),
    )

    /** 指纹未知（探测不到）时的哨兵值：任何真实计数（≥0）都不等于它。 */
    const val STAMP_UNKNOWN = -1

    /** 条数闸：一层一目录，64 个目录（跨设备合计）足够回溯多层目录树。 */
    private const val MAX_DIRS = 64

    /** 总条目闸：与 F10 同款软上限量级，防"很多大目录"把内存吃爆。 */
    private const val MAX_ENTRIES = 20_000

    private val lock = Any()
    private val map = HashMap<String, DirCache>()

    /** key = 设备guid + NUL + 目录路径（NUL 不会出现在路径里，无歧义）。 */
    fun cacheKey(guid: String, dir: String): String = "$guid\u0000$dir"

    /** 取某目录的缓存；命中即刷新 [DirCache.lastUsedAt]（LRU 触达）。null = 无缓存。 */
    fun get(guid: String, dir: String): DirCache? = synchronized(lock) {
        map[cacheKey(guid, dir)]?.also { it.lastUsedAt = System.currentTimeMillis() }
    }

    /**
     * 写入（或替换）某目录的缓存并做 LRU 淘汰。
     *
     * <p>★ 调用方通常**复用**已有的 DirCache 对象（只改 entries/nextOffset/hasMore/
     * dirStamp）而不是新建：滚动位置记在同一对象上，重建对象会把用户的位置抹掉。
     */
    fun put(guid: String, dir: String, c: DirCache) {
        synchronized(lock) {
            val key = cacheKey(guid, dir)
            c.lastUsedAt = System.currentTimeMillis()
            map[key] = c
            evictLocked()
        }
    }

    /** 断连 / 切设备 / 清缓存时调用（见 ConnectionCenter.disconnect 旁的说明）。 */
    fun clear() {
        synchronized(lock) { map.clear() }
    }

    /** 当前缓存的目录数（排障用）。 */
    fun size(): Int = synchronized(lock) { map.size }

    /**
     * LRU 淘汰（锁内）：条数与总条目**双闸**，超了就从最久未用的开始删。
     *
     * <p>只可能删到"别的目录"：调用方刚 put 的那条 lastUsedAt 是当前时刻，
     * 除非它是唯一一条（那也不会超闸）。
     */
    private fun evictLocked() {
        var total = map.values.sumOf { it.entries.size }
        while (map.size > MAX_DIRS || total > MAX_ENTRIES) {
            val oldest = map.entries.minByOrNull { it.value.lastUsedAt } ?: return
            total -= oldest.value.entries.size
            map.remove(oldest.key)
        }
    }
}
