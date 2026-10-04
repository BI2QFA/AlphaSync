package io.github.bi2qfa.alphasync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

/**
 * 相机平台会话结束广播（DAConnectionManagerService.ExitCompleted）的最后兜底：
 * Activity.onDestroy 在相机上可能根本不执行（电源拨杆/会话强收），这里按同样的
 * 顺序重做一遍关键清理，确认后才自杀进程。绝不先 killProcess——残留状态会
 * 污染整个系统（触发"数据修复"）。顺序与 MainActivity 的有序退出链一致：
 * PTP/IP（有序关闭：数据连接→协议/事件连接→监听→令牌表）
 * → 服务链收尾（含缩略图预取线程，释放 SD 句柄）→ 无线归位 → 自杀。
 * （"清除痕迹"一步已删——SD 卡写入铁律恢复后，卡上残留扫描机制不再需要。）
 *
 * <p>★ 2.7 门控（边拍边传）：**"会话收回"不等于"进程该死"**。菜单"进入后台运行"
 * 把界面交还相机时，这条广播同样会到 —— 后台化期间进程必须活着
 * （RadioReattachService 正等着这个信号重建无线电）。判据是 MainActivity 上的
 * **进程级静态标志**（{@code isBackgroundRequested()}，receiver 是清单静态注册、
 * Activity 实例可能已死，不能用实例字段）。只有正常退出链（beginOrderlyExit →
 * daFinishOnly）那条路的广播才走下面的清理 + 自杀；后台化那条路在
 * {@code markSessionHandedOver()} 之后**原样返回**。正常退出的行为与 2.6 逐字节一致。
 */
public class ExitCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (MainActivity.isBackgroundRequested()) {
            // 后台化（2.7）：会话交还相机 ≠ 退出 —— 进程保持存活，
            // 信号转记给 RadioReattachService（它据此立刻重建无线电）。
            AppLog.i("Exit", "后台化会话收回 → 进程保持存活（等待无线电重建）");
            MainActivity.markSessionHandedOver();
            try {
                // 抑制标志用完即撤：这是"本次拆链的说明"，到此送达使命结束。
                PtpIpServer.clearBackgroundNotifiedOnActive();
            } catch (Throwable t) {
            }
            return;
        }
        try {
            PtpIpServer.killActiveInstance();
        } catch (Throwable t) {
        }
        try {
            MainActivity.killServicesQuietly();
        } catch (Throwable t) {
        }
        try {
            MainActivity.killRadioQuietly(context);
        } catch (Throwable t) {
        }
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable t) {
        }
    }
}
