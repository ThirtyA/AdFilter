package com.lab.adfilter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * 开机自启。
 *
 * 默认关闭 —— 因为 Android 各版本对"开机后启动前台服务"的限制一直在收紧
 * （Android 12+ 会直接抛 ForegroundServiceStartNotAllowedException），
 * 贸然自启可能让用户莫名其妙。用户在界面里勾选后才会生效，
 * 且启动过程已经 try-catch 兜住，失败也只写在日志里，不影响系统。
 *
 * 监听三个时机：
 *   BOOT_COMPLETED              —— 正常开机
 *   LOCKED_BOOT_COMPLETED       —— 开机但还没解锁（直接启动模式加密的设备）
 *   MY_PACKAGE_REPLACED         —— 自己这个 App 升级完成
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            if (action == null) return;
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                return;
            }

            AppSettings.init(ctx);
            AllowList.init(ctx);

            if (!AppSettings.getBool(AppSettings.KEY_AUTOSTART, false)) return;
            if (AdFilterVpnService.running.get()) return;
            // 没授权 VPN 就静默放弃：这里没法弹 UI，等用户下次打开 App 处理
            if (VpnService.prepare(ctx) != null) return;

            boolean[] g = AdFilterVpnService.activeGroups;
            AppSettings.loadGroups(g);
            AdFilterVpnService.start(ctx, g);
        } catch (Throwable t) {
            // 广播里抛异常会让系统判定 App 行为异常，务必吞掉
            AdFilterVpnService.lastError =
                    "开机自启失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }
}
