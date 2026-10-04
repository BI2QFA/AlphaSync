package io.github.bi2qfa.alphasync;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 缩略图预取器（纯 Java，桌面可测）。
 *
 * 手机下发小图清单后，**两条常驻 worker** 从队列里一次领一小批（{@link #MICRO_BATCH} 张）
 * 顺序提取进有界 LRU 缓存；{@link PtpCameraHandler} 取小图时先查这里，命中即省去重复解析。
 * 未命中仍会实时提取，功能不受影响。
 *
 * <h3>为什么是 2 条 worker + 微批（2.6）</h3>
 * 相机是单核弱 CPU + SD 卡：纯并发不会更快，但**一个线程干等 IO 时另一个能顶上**，
 * 实测比单线程有明显收益；而"一次领一批顺序解"又比"一次领一张来回抢锁"少很多
 * 锁竞争与随机寻道。两者叠加就是"2 路 × 8 张微批"。
 *
 * <p>状态机：IDLE → RUNNING ⇄ PAUSED → DONE/CANCELLED。
 * 提取过程即开即关文件句柄；{@link #stop()} 逐线程 join（铁律：不留 SD 占用）。
 *
 * <h3>★ 2.10-fix：预览预取队列</h3>
 * 小图缓存之外再加一条**预览队列**（{@link #beginPreview}）：大预览（~350ms/张）也由
 * 同两条 worker 在后台预先解好，手机端取大图时 {@link #lookupPreview} 命中即发。
 * 领取顺序**小图绝对优先**（小图队列非空就不碰预览队列），预览只是补充，
 * 不会把小图阶段拖慢。缓存上限 {@link #PREVIEW_MAX_ENTRIES} 条（防爆堆）。
 */
public class ThumbPrefetcher {

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_DONE = "done";

    /**
     * 缓存条目上限。
     *
     * <p>典型目录是 300–400 张照片；清单不再截断后可能上千张（2.6：手机端去掉了 200 上限），
     * 所以这里放到 512：一支小缩略图**捆绑包**（JPEG ~6 KB + EXIF JSON ~150 B，
     * ★ 1.0：见 {@link ThumbnailExtractor#extractSmallBundle}），
     * 512 条约 3.2 MB —— 相机堆能承受，换来的是一次预热覆盖整个目录，用户滚到哪都命中。
     */
    private static final int MAX_ENTRIES = 512;

    /** 常驻 worker 数（2.6：单线程 → 2；★ 2.10-fix：回到 **1** —— 单核相机上
     *  两路解析只会自相争抢 SD/CPU，且与请求路径的现解析叠加时更易触发长读窗冲突；
     *  单 worker = 解析完全串行，配合 SD_LOCK 让路，稳态吞吐反而更高）。 */
    private static final int PREFETCH_WORKERS = 1;

    /** 一次领多少张（顺序解，减少锁竞争与随机寻道）。回退改 1 即"一次一张"。 */
    private static final int MICRO_BATCH = 8;

    /**
     * 预览缓存条目上限（★ 2.10-fix）。
     *
     * <p>一张大预览捆绑包约 500 KB，48 条 ≈ 24 MB —— 相机默认堆 48–64 MB，
     * 留一半给系统与相机服务。比小图缓存（{@link #MAX_ENTRIES} 条 × ~6 KB ≈ 3 MB）
     * 大得多：大预览才是传输主力（手机端点开/自动传输都走它）。
     * ★ 2.10-fix：48 → 16 条（≈8 MB）—— 48 条 + memo 16 条 + 小图缓存 +
     *   解析峰值（1 MB 尾扫 × 2 worker）在最坏情况下逼近相机默认堆上限，
     *   实测 106MSDCF 目录全部预览返回 null（OOM 被 Throwable 吞掉）；
     *   砍到 16 条把驻留压到 ~15 MB，给解析峰值留足空间。
     */
    private static final int PREVIEW_MAX_ENTRIES = 16;

    private final File rootDir;
    private final LinkedHashMap<String, byte[]> cache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private final Object lock = new Object();
    private final List<Thread> workers = new ArrayList<Thread>();
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
    /** 已领出、正在解的张数（队列空了但还有在飞的，状态不能判 IDLE）。 */
    private int inFlight = 0;
    private String state = STATE_IDLE;
    private int doneCount = 0;
    private int totalCount = 0;
    private boolean cancelled = false;

    /**
     * 预览缓存（★ 2.10-fix）：access-order LRU，键与小图缓存同款（相对路径）。
     *
     * <p>值不是裸 JPEG，而是**已打好的捆绑包**（[4B 大端 JSON 长][JSON][JPEG]），
     * 与 {@code PtpCameraHandler.mediaBytes} 的产物逐字节同款 —— 取件侧命中后
     * 无差别下发，手机端 {@code splitPreviewBundle} 同一套拆包逻辑。
     * 所有读写都在 {@link #lock} 下（access-order 的 get 也会动结构，必须互斥）。
     */
    private final LinkedHashMap<String, byte[]> previewCache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, byte[]> eldest) {
                    return size() > PREVIEW_MAX_ENTRIES;
                }
            };

    /** 预览待解队列（★ 2.10-fix）。小图未取空时 worker 不碰它（小图优先）。 */
    private final ConcurrentLinkedQueue<String> previewQueue = new ConcurrentLinkedQueue<String>();
    private int previewDoneCount = 0;
    private int previewTotalCount = 0;

    /**
     * 微批解图结果回调：进锁计数 + 入缓存（解不出来也要计数，进度才会走）。
     *
     * <p>★ 1.0：{@code jpg} 实际是**小图捆绑包**字节
     * （[4B 大端 JSON 长][JSON][JPEG]，见 {@link ThumbnailExtractor#bundleJsonJpeg}）——
     * 与预览/媒体通道同格式，缓存原样字节、取件侧无差别下发。
     */
    private final ThumbnailExtractor.SmallCallback onThumb =
            new ThumbnailExtractor.SmallCallback() {
                public void onThumb(String relPath, byte[] jpg) {
                    synchronized (lock) {
                        doneCount++;
                        if (jpg != null) {
                            cache.put(relPath, jpg);
                        }
                    }
                }
            };

    public ThumbPrefetcher(File rootDir) {
        this.rootDir = rootDir;
    }

    /** 命中返回缓存字节；未命中返回 null（调用方回落实时提取） */
    public byte[] lookup(String relPath) {
        synchronized (lock) {
            return cache.get(relPath);
        }
    }

    /**
     * 预览命中返回**已打好的捆绑包**字节（[4B 大端 JSON 长][JSON][JPEG]，
     * ★ 2.10-fix）；未命中返回 null（调用方回落实时解析 + 回填）。
     */
    public byte[] lookupPreview(String relPath) {
        synchronized (lock) {
            return previewCache.get(relPath);
        }
    }

    /**
     * 回填预览缓存（★ 2.10-fix）：{@code PtpCameraHandler} 实时解析出捆绑包后放进来的，
     * 下次同一张直接命中。传入的必须是已打好的捆绑包（与 {@link #lookupPreview} 同格式）。
     */
    public void putPreview(String relPath, byte[] bundled) {
        if (relPath == null || bundled == null) {
            return;
        }
        synchronized (lock) {
            previewCache.put(relPath, bundled);
        }
    }

    // ===== 协议命令 =====

    public void begin(List<String> relPaths) {
        synchronized (lock) {
            cancelled = false;
            queue.clear();
            if (relPaths != null) {
                // ★ 2.6：去重用 HashSet。名单不再截断后可能上千条，
                //   原来的 List.contains 是 O(n²)，上千条会明显卡住这条线程。
                HashSet<String> seen = new HashSet<String>();
                for (String p : relPaths) {
                    if (p != null && p.length() > 0 && seen.add(p)) {
                        queue.add(p);
                    }
                }
            }
            totalCount = queue.size();
            doneCount = 0;
            state = totalCount == 0 ? STATE_DONE : STATE_RUNNING;
            AppLog.i("Thumb", "预取开始：共 " + totalCount + " 张（" + PREFETCH_WORKERS + " 路解图）");
            ensureWorkersLocked();
            lock.notifyAll();
        }
    }

    /**
     * 预览预取开始（★ 2.10-fix）：与小图 {@link #begin} 同款骨架，但**追加语义**——
     * 队列里已有内容就往后加，**不清空、不整体替换**。
     *
     * <p>与小图 begin 的"替换"刻意区分：小图 begin 是"当前这份清单就是全部"，
     * 而预览是"补充"——手机端可能在小图名单之后再来新的预览名单，
     * 替换会把上一轮还没轮到的预览丢掉（worker 是"小图先、预览后"，
     * 预览被消费得晚，替换窗口很大）。代价只是重复路径最多白解一次，结果幂等。
     *
     * <p>去重与 begin 同款：只对**本次传入的名单**用 HashSet 去重（O(n)），
     * 不与队列里已有的条目比对（ConcurrentLinkedQueue.contains 是 O(n)，
     * 上千条会退化成 O(n²) 卡住调用线程）。
     */
    public void beginPreview(List<String> relPaths) {
        synchronized (lock) {
            cancelled = false;
            int added = 0;
            if (relPaths != null) {
                HashSet<String> seen = new HashSet<String>();
                for (String p : relPaths) {
                    if (p != null && p.length() > 0 && seen.add(p)) {
                        previewQueue.add(p);
                        added++;
                    }
                }
            }
            previewTotalCount += added;
            if (!previewQueue.isEmpty()) {
                state = STATE_RUNNING;
            }
            AppLog.i("Thumb", "预览预取追加：" + added + " 张（累计 " + previewTotalCount
                    + "，待解 " + previewQueue.size() + "）");
            ensureWorkersLocked();
            lock.notifyAll();
        }
    }

    /**
     * 保证有 {@link #PREFETCH_WORKERS} 条 worker 在跑（调用方须持有 lock）。
     *
     * <p>常驻而非"每次 begin 新建"：省掉反复建线程的开销，也让 stop() 的
     * "逐线程 join"有明确的清单可依；死掉的（理论上只有被中断的）顺手剔掉。
     */
    private void ensureWorkersLocked() {
        for (int i = workers.size() - 1; i >= 0; i--) {
            if (!workers.get(i).isAlive()) {
                workers.remove(i);
            }
        }
        while (workers.size() < PREFETCH_WORKERS) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    workLoop();
                }
            }, "ThumbPrefetch-" + (workers.size() + 1));
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    public void pause() {
        synchronized (lock) {
            if (STATE_RUNNING.equals(state)) state = STATE_PAUSED;
        }
    }

    public void resume() {
        synchronized (lock) {
            if (STATE_PAUSED.equals(state)) state = STATE_RUNNING;
            lock.notifyAll();
        }
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            queue.clear();
            // ★ 2.10-fix：预览队列一并取消（否则"取消"只停小图、预览照跑）。
            //   缓存不清 —— 与小图缓存同款：已解好的字节留着，重连后还能命中。
            previewQueue.clear();
            doneCount = 0;
            totalCount = 0;
            previewDoneCount = 0;
            previewTotalCount = 0;
            inFlight = 0;
            state = STATE_IDLE;
            lock.notifyAll();
        }
    }

    /** 退出流程用：停全部 worker、清队列（extractor 自身即开即关句柄，无句柄残留） */
    public void stop() {
        AppLog.i("Thumb", "预取停止（已提取 " + doneCount + "/" + totalCount + "）");
        List<Thread> toJoin;
        synchronized (lock) {
            cancelled = true;
            queue.clear();
            // ★ 2.10-fix：预览队列也不留 —— 退出后绝不能再有 worker 去碰 SD 卡
            //   （预览解析每张 ~350ms，比小图长得多，残留条目会被误当"还有活"）。
            previewQueue.clear();
            state = STATE_IDLE;
            lock.notifyAll();
            toJoin = new ArrayList<Thread>(workers);
            workers.clear();
        }
        // ★ 必须等它们**真的退出**再返回。
        //   某条 worker 可能正卡在 SD 卡的一次读取里（extractSmall 期间的
        //   RandomAccessFile 由 finally 关），而退出链最后会调
        //   DAConnectionManager.finish() 结束进程 —— 进程若在这个窗口被收掉，
        //   卡上就留下一个没归位的占用，相机下次开机会挂"正在修复数据"。
        //   等的是"读完手上这一张"（解析循环已逐张检查 SHUTTING_DOWN/cancelled）。
        //   ★ 2.10-fix：2 秒 → **6 秒**。单张**大预览**解析 ~350ms，冷卡/坏簇上实测
        //   最坏可逼近 2 秒；原来的 join(2000) 上限与"单张解析耗时"同量级，
        //   2.7 只有小图（~0.3s/张）所以从没撞上，2.10 加了预览预取后
        //   退出瞬间总有预览在解 → join 超时返回 → 退出链继续往下收进程 →
        //   卡上留下未归位的 SD 占用 → 相机下次开机挂"正在修复数据"。
        //   6 秒 = 单张预览最坏耗时 ×3 的余量；真的卡死也不强求，超时后照常返回，
        //   绝不让退出流程被它拖死（退出链后段还有 ExitCompletedReceiver 兜底）。
        for (int i = 0; i < toJoin.size(); i++) {
            Thread t = toJoin.get(i);
            t.interrupt();
            try {
                t.join(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ===== 工作线程 =====

    /**
     * 领微批（持锁）→ 锁外解图 → 回锁结账。
     *
     * <p>★ 解图**绝不能持锁**：那是 SD IO，持锁期间另一条 worker 只能干等，
     * 2 路并发就退化成串行了。所以是"领批（快）→ 出锁 → 解 → 回锁"。
     */
    private void workLoop() {
        while (true) {
            List<String> batch;
            boolean previewBatch;
            ConcurrentLinkedQueue<String> src;
            synchronized (lock) {
                while (true) {
                    if (cancelled) return;
                    // ★ 2.10-fix：SHUTTING_DOWN 也要能**凭自身**让 worker 退出。
                    //   只靠下面解析循环里的逐张检查是不够的 —— 退出链在
                    //   shutdownServicesAndRadio 开头打标，到 stopPtpServer→stop()
                    //   之间还有一段路要走；这期间 worker 解完当前张回到这里，
                    //   若只看 cancelled，它会立刻又领一批、被逐张检查整批放回、
                    //   再领一批…… 变成**空转自旋**啃满单核 CPU，反过来拖住退出链。
                    //   这里直接退出线程；下一次 begin()/beginPreview() 的
                    //   ensureWorkersLocked() 会把死掉的剔掉并重新拉起（重启路径已覆盖）。
                    if (ThumbnailExtractor.SHUTTING_DOWN) return;
                    if (STATE_PAUSED.equals(state)) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return; // stop()
                        }
                        continue;
                    }
                    if (queue.isEmpty() && previewQueue.isEmpty()) {
                        // 两条队列都空：在飞的收完才算真闲下来（★ 2.10-fix：预览队列同样算活）
                        if (inFlight == 0) state = STATE_IDLE;
                        try {
                            lock.wait(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    // ★ 2.10-fix：小图绝对优先 —— 小图队列非空就绝不领预览批
                    //   （预览每张 ~350ms，抢在小图前面会把小图阶段整体拖慢）。
                    //   预览批与小图批同用 MICRO_BATCH：批大小只影响 inFlight 粒度，
                    //   产物是逐张入缓存的，不会攒着一堆大图才落袋。
                    previewBatch = queue.isEmpty();
                    src = previewBatch ? previewQueue : queue;
                    batch = new ArrayList<String>(MICRO_BATCH);
                    for (int i = 0; i < MICRO_BATCH; i++) {
                        String p = src.poll();
                        if (p == null) break;
                        batch.add(p);
                    }
                    inFlight += batch.size();
                    break;
                }
            }

            // 锁外解（文件 IO）——★ 2.10-fix：全程持**全局 SD 解析锁**，但与请求
            // 路径相反，这里用 **tryLock 让路**：拿不到（现解析正在跑）就整批放回
            // 队尾稍后重试，绝不与现解析并发读 SD（真机教训：并发长读窗 → 整批 null）。
            if (previewBatch) {
                extractPreviewBatch(batch, src);
            } else {
                extractSmallBatchLocked(batch, src);
            }

            synchronized (lock) {
                inFlight -= batch.size();
                if (inFlight < 0) inFlight = 0;
                // ★ 2.10-fix：两条队列都空、且没有在飞的，才算收工
                if (queue.isEmpty() && previewQueue.isEmpty() && inFlight == 0) state = STATE_IDLE;
                lock.notifyAll();
            }
        }
    }

    /**
     * 请求路径最近是否在解析（2 秒窗口，★ 2.10-fix）。
     *
     * <p>批量通道的 batch-parse 在"等槽位"的间隙不持锁、也不在等待队列上 —— 光靠
     * tryLock/hasQueuedThreads 看不出来；有了"活跃时间戳"，预取就能在请求干活期间
     * 整体退避（宁慢不抢）。
     */
    private static boolean requestBusyRecently() {
        long t = ThumbnailExtractor.LAST_REQUEST_EXTRACT_AT;
        return t > 0 && (System.currentTimeMillis() - t) < 2000L;
    }

    /** 并发让路：请求在等锁、或最近 2 秒有请求在解析 → 退避一小段。 */
    private static void yieldToRequests(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 预览批（★ 2.10-fix，锁外解）：逐张 {@link ThumbnailExtractor#extractPreviewWithExif}
     * → 打成捆绑包 → 入 {@link #previewCache}。
     *
     * <p>用 {@code extractPreviewWithExif} 而不是别的路径：它把大预览与 EXIF 放在
     * **同一次会话**里取（2.6 提速点），与 {@code PtpCameraHandler.mediaBytes} 的实时
     * 路径完全同源 —— 无论命中缓存还是回落实时，手机端拆包结果一致。
     * 单张失败只记计数不入缓存（调用方届时回落实时解析），绝不中断整条队列。
     */
    private void extractPreviewBatch(List<String> batch, ConcurrentLinkedQueue<String> src) {
        for (int i = 0; i < batch.size(); i++) {
            synchronized (lock) {
                // ★ 2.10-fix：stop()/cancel() 后**逐张**检查，不再往下碰 SD 卡。
                //   预览单张 ~350ms（小图 ~24ms），一整批 8 张近 3 秒 —— 不查的话
                //   stop() 的 join 等不到 worker，退出链可能在"还在读卡"时收进程。
                //   ★ 2.10-fix：还要查 SHUTTING_DOWN —— 它在退出链**最前段**
                //   （MainActivity.shutdownServicesAndRadio 开头）就置位，比 stop() 里的
                //   cancelled 更早，补上"已开始退出但 stop() 还没走到"的整段窗口；
                //   期间每个 worker 都会在**下一张开句柄之前**收手，绝不留 SD 占用。
                //   （pause 不在这里查：批量传输的 PAUSE 让 worker 在批边界停下即可，
                //   中途丢弃未解的预览会白白降低预取命中率。）
                if (cancelled || ThumbnailExtractor.SHUTTING_DOWN) {
                    for (int k = i; k < batch.size(); k++) src.add(batch.get(k));   // 剩余放回
                    return;
                }
            }
            // ★ 2.10-fix：时间窗让路 —— 最近 2 秒有请求在解析（batch-parse 的
            //   "等槽位"间隙不在锁队列上，光靠 tryLock 看不出来）→ 剩余放回、退避。
            if (requestBusyRecently()) {
                for (int k = i; k < batch.size(); k++) src.add(batch.get(k));
                yieldToRequests(400);
                return;
            }
            // ★ 2.10-fix：逐张 tryLock 让路 —— 拿不到锁（请求路径的现解析正在跑）
            //   就把**剩余**条目放回队尾、结束本批，稍后重试。
            //   ★ 这是"预览预取不搞废功能"的关键：绝不与现解析并发读 SD。
            if (!ThumbnailExtractor.SD_LOCK.tryLock()) {
                for (int k = i; k < batch.size(); k++) {
                    src.add(batch.get(k));
                }
                yieldToRequests(200);
                return;
            }
            try {
                String rel = batch.get(i);
                byte[] bundled = null;
                try {
                    ThumbnailExtractor.PreviewBundle pb =
                            ThumbnailExtractor.extractPreviewWithExif(new File(rootDir, rel));
                    if (pb != null && pb.jpeg != null) {
                        bundled = ThumbnailExtractor.bundleJsonJpeg(
                                pb.jpeg, pb.exifJson == null ? "{}" : pb.exifJson);
                    }
                } catch (Throwable t) {
                    bundled = null;   // 与 mediaBytes 同款兜底：坏图/IO 失败只算这一张没解出来
                }
                synchronized (lock) {
                    previewDoneCount++;
                    if (bundled != null) {
                        previewCache.put(rel, bundled);
                    }
                }
            } finally {
                ThumbnailExtractor.SD_LOCK.unlock();
            }
            // ★ 2.10-fix：解锁后若**有请求路径在等锁**（hasQueuedThreads），主动让路
            //   一小段 —— 保证现解析永远排在预取前面（用户可感的速度优先）。
            if (ThumbnailExtractor.SD_LOCK.hasQueuedThreads()) {
                yieldToRequests(300);
                synchronized (lock) {
                    if (cancelled) return;
                }
            }
        }
    }

    /**
     * 小图批（★ 2.10-fix **逐张版**）：逐张 tryLock 解析 + 逐张让路。
     *
     * <p>原实现"整批一次持锁"（8 张 × 相机上 ~0.3s ≈ 2.4s 持锁窗）——请求路径的
     * 现解析可能要等满一整批。逐张后**持锁窗降到单张**，且每张之间都有让路检查
     * （时间窗 + 等锁队列）。无竞争时逐张紧挨着解析，"同目录文件名连续 = SD 顺序读"
     * 的红利保持（sleep 只在有竞争时发生）。
     *
     * <p>每张**必回调**（解不出来给 null）—— 与 extractSmallBatch 的进度语义一致。
     *
     * <p>★ 1.0：每张交付**小图捆绑包**（{@link ThumbnailExtractor#extractSmallBundle}），
     * 与预览对象/媒体通道同款格式。
     */
    private void extractSmallBatchLocked(List<String> batch, ConcurrentLinkedQueue<String> src) {
        for (int i = 0; i < batch.size(); i++) {
            synchronized (lock) {
                // ★ 2.10-fix：与预览批同款 —— cancelled 之外还要查 SHUTTING_DOWN
                //   （退出链最前段即置位，早于 stop() 的 cancelled）。
                if (cancelled || ThumbnailExtractor.SHUTTING_DOWN) {
                    for (int k = i; k < batch.size(); k++) src.add(batch.get(k));
                    return;
                }
            }
            // 时间窗让路（同预览批）
            if (requestBusyRecently()) {
                for (int k = i; k < batch.size(); k++) src.add(batch.get(k));
                yieldToRequests(300);
                return;
            }
            if (!ThumbnailExtractor.SD_LOCK.tryLock()) {
                for (int k = i; k < batch.size(); k++) src.add(batch.get(k));
                yieldToRequests(200);
                return;
            }
            try {
                String rel = batch.get(i);
                byte[] bundled = null;
                try {
                    File f = new File(rootDir, rel);
                    if (f.isFile()) {
                        // ★ 1.0：小图 = 捆绑包（JPEG + EXIF JSON）
                        bundled = ThumbnailExtractor.extractSmallBundle(f);
                    }
                } catch (Throwable t) {
                    bundled = null;
                }
                onThumb.onThumb(rel, bundled);   // 自带 synchronized(lock)：计数 + 入缓存
            } finally {
                ThumbnailExtractor.SD_LOCK.unlock();
            }
            if (ThumbnailExtractor.SD_LOCK.hasQueuedThreads()) {
                yieldToRequests(200);
                synchronized (lock) {
                    if (cancelled) return;
                }
            }
        }
    }
}
