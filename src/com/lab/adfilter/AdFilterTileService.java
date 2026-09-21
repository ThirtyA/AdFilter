package com.lab.adfilter;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉状态栏的快捷开关磁贴。
 *
 * 这是第三方应用能做到的、最接近"写进系统"的形态：
 * 手机下拉快捷设置面板里会出现一个「广告过滤」瓦片，点一下就能开关，
 * 完全不需要打开 App 界面，用户感知上就是"系统自带的功能"。
 *
 * （严格意义上第三方 App 无法往系统「设置」里插入自己的页面，
 *  那是系统签名 / SettingsProvider 才有的权限。快捷磁贴是官方留给
 *  三方 App 的标准入口，也是体验最接近的替代方案。）
 *
 * 另一个好处：磁贴点击属于「用户主动操作」，因此 Android 12+ 对
 * 后台启动前台服务的限制在这里是豁免的，比在广播里自启稳得多。
 */
public class AdFilterTileService extends TileService {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        AppSettings.init(this);
        refresh();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        AppSettings.init(this);
        refresh();
    }

    @Override
    public void onClick() {
        AppSettings.init(this);
        AllowList.init(this);

        if (AdFilterVpnService.running.get()) {
            AdFilterVpnService.stop(this);
            // 服务是异步停的，稍等再刷图标，避免状态显示超前
            postRefresh(600);
            return;
        }

        if (VpnService.prepare(this) != null) {
            // 还没授权 VPN。后台没法直接弹系统授权框，引导到主界面点一次即可；
            // 授权状态系统会记住，今后磁贴就能直接开关。
            try {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable ignored) { }
            return;
        }

        boolean[] g = AdFilterVpnService.activeGroups;
        AppSettings.loadGroups(g);
        AdFilterVpnService.start(this, g);
        postRefresh(2000);   // 规则是异步加载的，稍后回来再确认真实状态
    }

    private void postRefresh(long delayMs) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, delayMs);
    }

    /** 把磁贴状态同步到服务的真实状态 */
    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean run = AdFilterVpnService.running.get();
        t.setState(run ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(run ? "广告过滤(开)" : "广告过滤(关)");
        t.updateTile();
    }
}
