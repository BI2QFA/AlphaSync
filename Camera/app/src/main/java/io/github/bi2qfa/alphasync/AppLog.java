package io.github.bi2qfa.alphasync;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 调试日志：**纯内存环形缓冲**（日志屏）+ **logcat 镜像**（镜像由 MainActivity
 * 注入，见 {@link #setMirror}）。
 *
 * <h3>不落盘（SD 卡写入铁律，已恢复）</h3>
 * 本类与全工程**不写任何文件** —— 不写 SD 卡、也不写应用私有目录。往卡上写陌生
 * 文件会让相机下次开机报"正在修复数据"（那条坑本项目踩过）。诊断靠两条**都不落盘**
 * 的通道：① logcat（tag {@code AlphaSync}，Diadem shell 里 {@code logcat -d} 可读）；
 * ② 「关于页连按十下确定键」的日志屏（内存环，当场做、当场看）。
 *
 * <h3>为什么是纯 Java（零 android.* 依赖）</h3>
 * 与 {@link PairingStore} 同理：桌面回归那道门用**空 classpath** 编译固定文件列表，
 * 零 Android 依赖的类才进得去。日志也确实不需要 Context —— logcat 镜像因此走
 * {@link Mirror} 接口注入，而不是在类里 import android.util.Log。
 *
 * <h3>线程安全</h3>
 * 所有公开方法都是 {@code synchronized}：PTP/IP 的连接线程、预取线程、UI 线程都会写。
 * 写一行就是拼一个字符串 + 存进数组，不做 IO，开销可忽略。
 */
public final class AppLog {

    /** 内存里最多保留多少行（超出丢最老的）。800 行约 80KB 字符串，对相机那点堆内存可接受。 */
    private static final int MAX = 800;

    private static final String[] RING = new String[MAX];
    private static int appended;      // 累计写入行数（算环形先后用）
    private static int cursor;        // 下一个写入位置

    private static SimpleDateFormat fmt;

    private AppLog() {
    }

    public static synchronized void i(String tag, String msg) {
        append("I", tag, msg);
    }

    public static synchronized void w(String tag, String msg) {
        append("W", tag, msg);
    }

    public static synchronized void e(String tag, String msg) {
        append("E", tag, msg);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        String extra = "";
        if (t != null) {
            extra = " " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        }
        append("E", tag, msg + extra);
    }

    /**
     * logcat 镜像接口（2.7.1）。
     *
     * <p>AppLog 是纯 Java（见类注释），不能在类里直接 import android.util.Log ——
     * 由 MainActivity 在 onCreate 注入一个真实现；未注入时镜像为空操作。
     */
    public interface Mirror {
        void line(String level, String tag, String msg);
    }

    private static volatile Mirror mirror;

    /** 注入 logcat 镜像（幂等；传 null 可撤）。 */
    public static void setMirror(Mirror m) {
        mirror = m;
    }

    private static void append(String level, String tag, String msg) {
        if (fmt == null) {
            fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        }
        RING[cursor] = fmt.format(new Date()) + " " + level + "/" + tag + " " + msg;
        cursor = (cursor + 1) % MAX;
        appended++;
        // logcat 镜像：让"后台假死/崩溃"的现场在进程死后仍可回溯（不落盘，
        // 系统日志缓冲；镜像异常吞掉，绝不影响主流程）。
        Mirror m = mirror;
        if (m != null) {
            try {
                m.line(level, tag, msg);
            } catch (Throwable t) {
            }
        }
    }

    /** 内存里现有的行数。 */
    public static synchronized int size() {
        return appended < MAX ? appended : MAX;
    }

    /**
     * 按时间先后拼成多行文本。
     *
     * @param maxLines 最多返回多少行（0 = 全部）。给屏幕用时要限量 —— 相机上 TextView
     *                 的排版开销与行数成正比，几百行足够看清一个回合，再多只是变慢。
     */
    public static synchronized String dump(int maxLines) {
        int total = size();
        int from = (maxLines > 0 && total > maxLines) ? total - maxLines : 0;
        StringBuilder sb = new StringBuilder(Math.max(16, total * 64));
        for (int k = from; k < total; k++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(at(k));
        }
        return sb.toString();
    }

    /** 第 k 行（0 = 最老的一行）。 */
    private static String at(int k) {
        int total = size();
        int start = appended > MAX ? cursor : 0;   // 环形里最老那行的下标
        return RING[(start + k) % MAX];
    }
}
