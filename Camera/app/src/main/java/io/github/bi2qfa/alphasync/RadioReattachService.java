package io.github.bi2qfa.alphasync;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import io.github.bi2qfa.alphasync.radio.RadioWrapper;
import io.github.bi2qfa.alphasync.radio.RadioWrapperFactory;

/**
 * 后台无线电重建服务（2.7 边拍边传）。
 *
 * <p>"进入后台运行"把界面交还相机取景、无线电按路线 A 纪律先拆净（交接必须在
 * Wi-Fi 关净状态下发生，否则整机热重启）—— 拆完的无线电由本服务重建：
 *
 * <pre>
 * 启动（MainActivity 在 moveTaskToBack 之前 startService）
 *   → 等"会话已交还"信号（ExitCompleted，250ms 起搏；8 秒兜底）
 *   → 按当前连接方式重建：
 *       · Wi-Fi 模式：setWifiEnabled(true)（600ms 起搏 ×5）→ 等 DHCP IP
 *       · 热点模式：setWifiEnabled(true) → setDirectEnabled(true) → startGo
 *         → 等 getLiveGroup() != null（GO 就绪判据）
 *   → 成功：MainActivity.onRadioReattached()（startServices 幂等 → PH_RUNNING → 补 APO）
 *     失败：MainActivity.onRadioReattachFailed()（goFatal 语义：拉回应用可见"重试"）
 *   → stopForeground + stopSelf（进程靠"已回到取景界面"的相机生命周期继续存活）
 * </pre>
 *
 * <p>设计纪律（全部来自路线 A 与现有代码的同款判据）：
 * <ul>
 *   <li><b>不重注册 radio receiver、不走广播状态机</b>：后台期间状态机各标志
 *       （{@code isEnableActionFiltered} 等）不可靠；本服务只调 RadioWrapper 的
 *       **同步应答式 API** + 轮询判据（{@code getLiveGroup()} 与
 *       {@code radiosConfirmedDown()} 同一判据的对称使用）。</li>
 *   <li><b>正常退出 / 切换连接方式优先</b>：每个循环都查
 *       {@link MainActivity#isShuttingDownNow()}，一让路就立刻放弃重试并停止 ——
 *       否则会出现"退出链刚把 Wi-Fi 关掉、本服务又把它打开"的赛跑，
 *       而退出时开着 Wi-Fi 交接＝整机热重启（必须堵死）。</li>
 *   <li><b>前台服务 + PARTIAL_WAKE_LOCK</b>（路线 A 的 EnablerService 手法），
 *       提高"交接窗口"内的存活率。</li>
 *   <li>Java 1.6 语法（相机老工具链）：匿名内部类、不用钻石与 try-with-resources。</li>
 * </ul>
 *
 * <p>失败语义：不重试到天荒地老 —— 上限内（起搏 5 次 / startGo 2 轮）失败即
 * 放弃 + stopSelf，由 {@code goFatal} 把界面切到"启动失败 + [确定键] 重试"；
 * 手机侧的断链由心跳失联兜底（与今天的意外断连行为一致）。
 */
public class RadioReattachService extends Service {

    // ===== 路线 A 原样继承的两个可调常量 =====
    /** "会话已交还"信号的轮询间隔。 */
    private static final long SESSION_POLL_MS = 250L;
    /** 等不到信号时的兜底开始时间。 */
    private static final long REENABLE_FALLBACK_MS = 8000L;

    /** Wi-Fi 起搏：单次检查窗口 / 起搏间隔 / 最多次数。 */
    private static final long WIFI_ENABLE_PULSE_WAIT_MS = 1500L;
    private static final long WIFI_PULSE_MS = 600L;
    private static final int WIFI_PULSE_MAX = 5;
    /** 起搏用完后的长等（使能广播迟到时的兜底）。 */
    private static final long WIFI_ENABLE_WAIT_MS = 15000L;
    /** Wi-Fi 模式等 DHCP IP 的上限（500ms × 120 = 60s，与前台 stationPoll 同一预算）。 */
    private static final int IP_POLLS_MAX = 120;
    /** 关联后等 SSID 名称的上限（与前台"SSID 偶发滞后最多再等 3 秒"同款）。 */
    private static final long SSID_WAIT_MS = 3000L;
    /** 热点重建：Direct 使能尝试次数 / startGo 轮数 / 每轮等组上限。 */
    private static final int DIRECT_TRIES = 2;
    private static final int GO_TRIES = 2;
    private static final long GO_WAIT_MS = 15000L;
    /** 通用轮询步长。 */
    private static final long STEP_MS = 500L;

    private static final int NOTIFY_ID = 1;

    private Thread worker;
    private volatile boolean stopped;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Throwable t) {
            AppLog.e("Bg", "startForeground 失败", t);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlphaSyncBg");
            wakeLock.acquire();
        } catch (Throwable t) {
            wakeLock = null;
        }
        AppLog.i("Bg", "重建服务启动（前台 + 唤醒锁）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 可重入：重复 start 不重开线程（一次后台化只需一次重建）
        if (worker == null) {
            worker = new Thread(new Runnable() {
                public void run() {
                    work();
                }
            }, "RadioReattach");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        releaseWakeLock();
        AppLog.i("Bg", "重建服务退出");
        super.onDestroy();
    }

    // ===== 主流程 =====

    private void work() {
        boolean reported = false;
        try {
            // ① 等"会话已交还"信号：信号到了立刻开始（通常几秒），收不到 8 秒兜底
            long began = SystemClock.elapsedRealtime();
            while (!stopped && !MainActivity.isShuttingDownNow()
                    && !MainActivity.isSessionHandedOver()
                    && (SystemClock.elapsedRealtime() - began) < REENABLE_FALLBACK_MS) {
                sleepQuietly(SESSION_POLL_MS);
            }
            if (stopped) return;
            if (MainActivity.isShuttingDownNow()) {
                AppLog.w("Bg", "退出优先级更高：重建让路（不碰无线电）");
                return;
            }
            String mode = MainActivity.currentMode();
            AppLog.i("Bg", "开始重建无线电（方式=" + mode + "）");
            Reattach r = MainActivity.MODE_HOTSPOT.equals(mode) ? reattachHotspot() : reattachWifi();
            if (stopped || MainActivity.isShuttingDownNow()) {
                AppLog.w("Bg", "重建结果被退出链接管，丢弃（ok=" + r.ok
                        + " reason=" + r.reason + "）");
                return;
            }
            if (r.ok) {
                AppLog.i("Bg", "重建完成："
                        + (r.ssid == null ? "" : "SSID=" + r.ssid + " ")
                        + (r.ip == null ? "" : "IP=" + r.ip));
                MainActivity.onRadioReattached(mode, r.ssid, r.ip);
            } else {
                MainActivity.onRadioReattachFailed(r.reason);
            }
            reported = true;
        } catch (Throwable t) {
            AppLog.e("Bg", "重建服务异常", t);
            if (!MainActivity.isShuttingDownNow()) {
                MainActivity.onRadioReattachFailed("后台重建服务异常");
                reported = true;
            }
        } finally {
            if (!reported && !MainActivity.isShuttingDownNow()) {
                // 服务被系统停掉 / 走到任何未上报的出口（非退出路径）：也要把主界面的
                // "重建进行中"落闸 —— 否则无线电状态机会一直让路，永远不响应广播。
                AppLog.w("Bg", "重建未完成即收摊 → 按失败收尾");
                MainActivity.onRadioReattachFailed("后台重建服务未完成");
            }
            finishQuietly();
        }
    }

    /** 重建结果（Java 1.6：一个私有静态小类最清楚）。 */
    private static final class Reattach {
        boolean ok;
        String reason;
        String ssid;
        String ip;
    }

    // ===== Wi-Fi 客户端模式（路线 A 原样：重开总开关 + 等 IP）=====

    private Reattach reattachWifi() {
        Reattach r = new Reattach();
        if (!enableWifiPulse("Wi-Fi 模式")) {
            r.reason = "Wi-Fi 未能重新打开";
            return r;
        }
        WifiManager wifi = wifiManager();
        // 服务就绪条件与前台 stationPoll 一致：总开关已开 **且** DHCP 已拿到 IP
        for (int i = 0; i < IP_POLLS_MAX && !stopped; i++) {
            if (MainActivity.isShuttingDownNow()) {
                r.reason = "退出链接管";
                return r;
            }
            String ip = stationIp(wifi);
            if (ip != null) {
                r.ok = true;
                r.ip = ip;
                r.ssid = waitStationSsid(wifi);
                return r;
            }
            sleepQuietly(STEP_MS);
        }
        r.reason = "未检测到 Wi-Fi 连接";
        return r;
    }

    // ===== 热点模式（拆净的反向操作：开 Wi-Fi → 使能 Direct → startGo）=====

    private Reattach reattachHotspot() {
        Reattach r = new Reattach();
        if (!enableWifiPulse("热点模式")) {
            r.reason = "Wi-Fi 未能重新打开";
            return r;
        }
        RadioWrapper radio = RadioWrapperFactory.getInstance(this);
        // Direct 使能（同步应答式，与前台 issueDirectEnable 同一语义；两次尝试）
        boolean direct = false;
        for (int i = 0; i < DIRECT_TRIES && !stopped; i++) {
            if (MainActivity.isShuttingDownNow()) {
                r.reason = "退出链接管";
                return r;
            }
            try {
                direct = radio.setDirectEnabled(true);
            } catch (Throwable t) {
                direct = false;
            }
            if (direct) break;
            sleepQuietly(WIFI_PULSE_MS);
        }
        if (!direct) {
            r.reason = "无法启用Direct模式";
            return r;
        }
        // startGo（与前台 startGroupOwner 同一组调用；GO 就绪以 getLiveGroup() != null 为准）
        String deviceName = android.os.Build.MODEL != null ? android.os.Build.MODEL : "AlphaSync";
        for (int attempt = 0; attempt < GO_TRIES && !stopped; attempt++) {
            if (MainActivity.isShuttingDownNow()) {
                r.reason = "退出链接管";
                return r;
            }
            int netId = RadioWrapper.NET_ID_PERSISTENT_GO;
            try {
                radio.configureIdentity(deviceName, deviceName, deviceName);
                RadioWrapper.GroupConfig live = radio.getLiveGroup();
                if (live != null) {
                    netId = live.networkId;
                }
            } catch (Throwable t) {
            }
            boolean acked = false;
            try {
                acked = radio.startGo(netId);
            } catch (Throwable t) {
            }
            AppLog.i("Bg", "热点重建：startGo 第 " + (attempt + 1) + " 轮，应答=" + acked);
            long waitEnd = SystemClock.elapsedRealtime() + GO_WAIT_MS;
            while (!stopped && SystemClock.elapsedRealtime() < waitEnd) {
                if (MainActivity.isShuttingDownNow()) {
                    r.reason = "退出链接管";
                    return r;
                }
                RadioWrapper.GroupConfig live = null;
                try {
                    live = radio.getLiveGroup();
                } catch (Throwable t) {
                }
                if (live != null) {
                    r.ok = true;
                    r.ssid = live.ssid;
                    r.ip = MainActivity.HOTSPOT_IP;
                    return r;
                }
                sleepQuietly(STEP_MS);
            }
            try {
                String detail = radio.getLastError();
                if (detail != null && detail.length() > 0) {
                    AppLog.w("Bg", "热点重建：radio 报 " + detail);
                }
            } catch (Throwable t) {
            }
        }
        r.reason = "热点创建失败";
        return r;
    }

    // ===== Wi-Fi 总开关：起搏重开 =====
    //
    // 路线 A 实测"会话刚收回就开"有失败窗口（相机 Diadem 栈还在收回 WLAN），
    // 所以不是开关一次就算：每 600ms 补发一次 setWifiEnabled(true)，最多 5 次；
    // 每次给 1.5s 的观察窗，最后再长等 15s（只等了使能广播迟到那种情况）。

    private boolean enableWifiPulse(String what) {
        WifiManager wifi = wifiManager();
        if (wifi == null) {
            AppLog.w("Bg", what + "：WifiManager 不可用");
            return false;
        }
        for (int i = 0; i < WIFI_PULSE_MAX && !stopped; i++) {
            if (MainActivity.isShuttingDownNow()) return false;
            try {
                wifi.setWifiEnabled(true);
            } catch (Throwable t) {
            }
            if (waitWifiEnabled(wifi, WIFI_ENABLE_PULSE_WAIT_MS)) {
                AppLog.i("Bg", what + "：Wi-Fi 已重新打开（第 " + (i + 1) + " 次起搏）");
                return true;
            }
            sleepQuietly(WIFI_PULSE_MS);
        }
        if (waitWifiEnabled(wifi, WIFI_ENABLE_WAIT_MS)) {
            AppLog.i("Bg", what + "：Wi-Fi 已重新打开（长等兜底）");
            return true;
        }
        AppLog.w("Bg", what + "：Wi-Fi 未能重新打开");
        return false;
    }

    private boolean waitWifiEnabled(WifiManager wifi, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!stopped && SystemClock.elapsedRealtime() < deadline) {
            if (MainActivity.isShuttingDownNow()) return false;
            int state;
            try {
                state = wifi.getWifiState();
            } catch (Throwable t) {
                state = WifiManager.WIFI_STATE_DISABLED;
            }
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                return true;
            }
            sleepQuietly(SESSION_POLL_MS);
        }
        return false;
    }

    // ===== 小工具（与前台同款算法；服务不能引用 Activity 的私有方法）=====

    private WifiManager wifiManager() {
        try {
            return (WifiManager) getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 与前台 stationIpAddress() 同一算法（DHCP 未下发时回 null）。 */
    private String stationIp(WifiManager wifi) {
        if (wifi == null) return null;
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) {
                try {
                    android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                    if (dhcp != null) ip = dhcp.ipAddress;
                } catch (Throwable t) {
                }
            }
            if (ip == 0) return null;
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                    + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 等 SSID 名称（关联刚完成时固件可能回占位串；拿不到就回 null，不阻塞重建）。 */
    private String waitStationSsid(WifiManager wifi) {
        long deadline = SystemClock.elapsedRealtime() + SSID_WAIT_MS;
        while (!stopped && SystemClock.elapsedRealtime() < deadline) {
            String s = stationSsid(wifi);
            if (s != null) return s;
            sleepQuietly(STEP_MS);
        }
        return null;
    }

    private String stationSsid(WifiManager wifi) {
        if (wifi == null) return null;
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getSSID() == null) return null;
            String s = info.getSSID();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
            }
            if (s.length() == 0 || "<unknown ssid>".equals(s)) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 前台通知（Gingerbread 形态；文案为方案建议稿，以用户定版为准）=====

    private Notification buildNotification() {
        Notification n = new Notification(R.drawable.ic_appicon,
                getString(R.string.app_name) + " 正在后台运行", System.currentTimeMillis());
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
        n.setLatestEventInfo(this, getString(R.string.app_name), "正在后台运行", pi);
        return n;
    }

    private void finishQuietly() {
        try {
            stopForeground(true);
        } catch (Throwable t) {
        }
        releaseWakeLock();
        try {
            stopSelf();
        } catch (Throwable t) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable t) {
        } finally {
            wakeLock = null;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 不重放中断：各循环自己按 stopped / 退出标志收敛
        }
    }
}
