package com.lab.adfilter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 主界面：开关、规则分组、实时统计与拦截日志。
 * 界面完全用代码生成，不依赖任何第三方库。
 */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;
    private static final int REQ_NOTIFY = 1002;
    /** 桌面图标其实是这个别名提供的，启用/禁用它就能控制图标显隐 */
    private static final String ALIAS_LAUNCHER = "com.lab.adfilter.LauncherAlias";
    private static final String RESTORE_CMD =
            "adb shell pm enable com.lab.adfilter/.LauncherAlias";

    private Button btnToggle;
    private TextView tvStatus;
    private TextView tvStats;
    private TextView tvError;
    private TextView tvLog;
    private CheckBox[] checkBoxes;
    private Button btnMode;
    private Button btnAllow;
    private Button btnBlockMode;
    private int logMode = 0;      // 0=实时日志  1=域名排行
    private TextView cbHideHint;
    private Handler handler = new Handler();
    private Runnable refresher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupCrashHandler();
        // 设置与白名单必须先于界面就绪，否则复选框读到的是默认值
        AppSettings.init(this);
        AllowList.init(this);
        AppSettings.loadGroups(AdFilterVpnService.activeGroups);
        AdFilterVpnService.blockMode = AppSettings.getInt(AppSettings.KEY_BLOCK_MODE, 0);
        // Android 13+ 通知权限要运行时申请，光写在 Manifest 里没用。
        // 通知是隐藏图标之后唯一的入口，没有它用户会把 App 弄丢，必须早要。
        requestNotifyPermissionIfNeeded();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("广告过滤（DNS 拦截）");
        title.setTextSize(22f);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("接管本机的 DNS 查询，命中广告域名就返回 0.0.0.0，让广告加载失败。\n"
                + "不解密流量、不需要 root、不装证书，正常上网不受影响。");
        desc.setTextSize(13f);
        desc.setPadding(0, dp(8), 0, dp(14));
        root.addView(desc);

        btnToggle = new Button(this);
        btnToggle.setTextSize(16f);
        root.addView(btnToggle);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14f);
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        tvStats = new TextView(this);
        tvStats.setTextSize(14f);
        tvStats.setPadding(0, dp(4), 0, dp(10));
        root.addView(tvStats);

        tvError = new TextView(this);
        tvError.setTextSize(12f);
        tvError.setTextColor(0xFFB00020);
        tvError.setPadding(0, dp(4), 0, dp(8));
        root.addView(tvError);

        TextView ruleTitle = new TextView(this);
        ruleTitle.setText("拦截规则");
        ruleTitle.setTextSize(16f);
        ruleTitle.setPadding(0, dp(8), 0, dp(6));
        root.addView(ruleTitle);

        checkBoxes = new CheckBox[BlockList.GROUP_NAMES.length];
        for (int i = 0; i < BlockList.GROUP_NAMES.length; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(this);
            cb.setText(BlockList.GROUP_NAMES[i] + "（" + BlockList.GROUP_DOMAINS[i].length + " 条）");
            cb.setTextSize(14f);
            cb.setChecked(AdFilterVpnService.activeGroups[i]);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton v, boolean checked) {
                    AdFilterVpnService.activeGroups[idx] = checked;
                    AppSettings.saveGroups(AdFilterVpnService.activeGroups);
                    // 已运行时立刻下发新规则
                    if (AdFilterVpnService.running.get()) {
                        startVpnService();
                    }
                }
            });
            checkBoxes[i] = cb;
            root.addView(cb);
        }

        // ---------------- 防误杀 ----------------
        TextView safeTitle = new TextView(this);
        safeTitle.setText("防误杀");
        safeTitle.setTextSize(16f);
        safeTitle.setPadding(0, dp(14), 0, dp(6));
        root.addView(safeTitle);

        btnAllow = new Button(this);
        btnAllow.setTextSize(14f);
        btnAllow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AllowActivity.class));
            }
        });
        root.addView(btnAllow);

        btnBlockMode = new Button(this);
        btnBlockMode.setTextSize(13f);
        btnBlockMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int m = AdFilterVpnService.blockMode == 0 ? 1 : 0;
                AdFilterVpnService.blockMode = m;
                AppSettings.setInt(AppSettings.KEY_BLOCK_MODE, m);
                updateBlockModeText();
            }
        });
        root.addView(btnBlockMode);
        updateBlockModeText();

        CheckBox cbBoot = new CheckBox(this);
        cbBoot.setText("开机自动启动过滤");
        cbBoot.setTextSize(13f);
        cbBoot.setChecked(AppSettings.getBool(AppSettings.KEY_AUTOSTART, false));
        cbBoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                AppSettings.setBool(AppSettings.KEY_AUTOSTART, checked);
            }
        });
        root.addView(cbBoot);

        CheckBox cbVerbose = new CheckBox(this);
        cbVerbose.setText("全量日志（省电模式下关闭）");
        cbVerbose.setTextSize(13f);
        cbVerbose.setChecked(AdFilterVpnService.verboseLog);
        cbVerbose.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                AdFilterVpnService.verboseLog = checked;
            }
        });
        root.addView(cbVerbose);

        TextView logTip = new TextView(this);
        logTip.setText("默认只记拦截和白名单放行。要排查漏网域名时再打开全量日志，"
                + "平时关着更省电。");
        logTip.setTextSize(11f);
        logTip.setPadding(0, dp(2), 0, 0);
        root.addView(logTip);

        TextView tip = new TextView(this);
        tip.setText("下拉状态栏把它加进快捷开关，出问题点一下就关掉；\n"
                + "常驻通知里也有「停止」按钮。");
        tip.setTextSize(11f);
        tip.setPadding(0, dp(4), 0, 0);
        root.addView(tip);

        // ---------------- 隐身 ----------------
        TextView hideTitle = new TextView(this);
        hideTitle.setText("隐身");
        hideTitle.setTextSize(16f);
        hideTitle.setPadding(0, dp(14), 0, dp(6));
        root.addView(hideTitle);

        CheckBox cbHide = new CheckBox(this);
        cbHide.setText("隐藏桌面图标");
        cbHide.setTextSize(14f);
        cbHide.setChecked(isLauncherHidden());
        cbHide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean hidden) {
                setLauncherHidden(hidden);
            }
        });
        root.addView(cbHide);

        cbHideHint = new TextView(this);
        cbHideHint.setText("⚠ 当前没有通知权限。请先在系统设置里打开通知权限，"
                + "否则隐藏图标后就再也进不来这个界面了。");
        cbHideHint.setTextColor(0xFFB00020);
        cbHideHint.setTextSize(11f);
        cbHideHint.setPadding(0, dp(2), 0, 0);
        cbHideHint.setVisibility(View.GONE);
        root.addView(cbHideHint);

        TextView hideTip = new TextView(this);
        hideTip.setTextSize(11f);
        hideTip.setPadding(0, dp(2), 0, 0);
        hideTip.setText("已经不在「最近任务」里显示了（后台干净）。\n"
                + "勾上这一项会连桌面图标也藏掉，之后从这里进：\n"
                + "① 通知栏那条「广告拦截正在运行」\n"
                + "② 系统设置 → 应用 → 广告过滤 → 打开\n"
                + "改不回来时用数据线执行（或本机工具的 adb）：\n" + RESTORE_CMD);
        root.addView(hideTip);

        TextView logTitle = new TextView(this);
        logTitle.setText("诊断信息（看哪些域名没被拦住）");
        logTitle.setTextSize(16f);
        logTitle.setPadding(0, dp(14), 0, dp(6));
        root.addView(logTitle);

        btnMode = new Button(this);
        btnMode.setTextSize(13f);
        btnMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logMode = (logMode + 1) % 2;
                updateUi();
            }
        });
        root.addView(btnMode);

        tvLog = new TextView(this);
        tvLog.setTextSize(12f);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvLog.setBackgroundColor(0xFFF2F4F7);

        ScrollView sv = new ScrollView(this);
        sv.addView(tvLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, lp);

        setContentView(root);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AdFilterVpnService.running.get()) {
                    AdFilterVpnService.stop(MainActivity.this);
                } else {
                    requestVpn();
                }
            }
        });

        refresher = new Runnable() {
            @Override
            public void run() {
                updateUi();
                // 界面可见时 1 秒够用了。原来 700ms 纯粹是无谓唤醒 ——
                // 每次唤醒都要跨进程读静态字段并重绘，长期开着积少成多。
                handler.postDelayed(this, 1000);
            }
        };
    }

    private void requestVpn() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            // 首次使用需要用户授权 VPN 权限（系统弹窗）
            startActivityForResult(prepare, REQ_VPN);
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                tvStatus.setText("未授权 VPN 权限，无法启动");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            String perm = android.Manifest.permission.POST_NOTIFICATIONS;
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, REQ_NOTIFY);
            }
        } catch (Throwable ignored) { }
    }

    private boolean hasNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 桌面图标当前是不是被藏起来了 */
    private boolean isLauncherHidden() {
        try {
            ComponentName cn = new ComponentName(this, ALIAS_LAUNCHER);
            int st = getPackageManager().getComponentEnabledSetting(cn);
            return st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 动态启用/禁用 activity-alias 来切换桌面图标。
     * 用 DONT_KILL_APP 很重要：否则会把正在运行的过滤服务一起杀掉。
     */
    private void setLauncherHidden(boolean hidden) {
        try {
            ComponentName cn = new ComponentName(this, ALIAS_LAUNCHER);
            getPackageManager().setComponentEnabledSetting(
                    cn,
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                           : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            tvError.setText("切换图标失败: " + t.getMessage());
        }
    }

    private void startVpnService() {
        AdFilterVpnService.start(this, AdFilterVpnService.activeGroups);
    }

    private void updateBlockModeText() {
        boolean nx = AdFilterVpnService.blockMode == 0;
        btnBlockMode.setText("拦截返回方式：" + (nx ? "域名不存在(推荐)" : "解析到 0.0.0.0")
                + "（点此切换）");
    }

    private void updateUi() {
        boolean run = AdFilterVpnService.running.get();
        btnToggle.setText(run ? "停止过滤" : "启动过滤");
        tvStatus.setText(run ? "状态：运行中" : "状态：已停止");
        tvStats.setText("已拦截 " + AdFilterVpnService.blockedCount.get()
                + " 次　放行 " + AdFilterVpnService.allowedCount.get() + " 次");

        if (btnAllow != null) {
            btnAllow.setText("白名单管理（自定义 " + AllowList.customCount()
                    + " 条 / 内置 " + AllowList.BUILTIN.length + " 条）");
        }

        StringBuilder sb = new StringBuilder();
        synchronized (AdFilterVpnService.recentLog) {
            int n = 0;
            for (String s : AdFilterVpnService.recentLog) {
                sb.append(s).append('\n');
                if (++n >= 40) break;
            }
        }
        if (logMode == 0) {
            btnMode.setText("当前：实时日志（点此看域名排行）");
            tvLog.setText(sb.length() == 0 ? "（暂无记录）" : sb.toString());
        } else {
            btnMode.setText("当前：域名排行（点此看实时日志）");
            List<String> top = AdFilterVpnService.topDomains(40);
            StringBuilder tb = new StringBuilder();
            tb.append("按查询次数排序。[拦←规则来源] 是被谁拦的，")
              .append("[放行] 是没拦住的（要补规则就看这些）：\n\n");
            if (top.isEmpty()) {
                tb.append("（暂无数据，先启动过滤并打开目标 App）");
            } else {
                for (String s : top) tb.append(s).append('\n');
            }
            tvLog.setText(tb.toString());
        }

        // 把错误直接显示在界面上，不用连电脑也能看到崩溃原因
        StringBuilder err = new StringBuilder();
        if (AdFilterVpnService.lastError != null) {
            err.append("服务错误：").append(AdFilterVpnService.lastError).append('\n');
        }
        try {
            String crash = getSharedPreferences("crash", MODE_PRIVATE).getString("last", null);
            if (crash != null) {
                int cut = Math.min(crash.length(), 900);
                err.append("上次崩溃：\n").append(crash.substring(0, cut));
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                        .putString("last_shown", crash).apply();
            }
        } catch (Throwable ignored) { }
        tvError.setText(err.length() == 0 ? "" : err.toString());

        // 没有通知权限时，隐藏桌面图标等于把 App 弄丢，这里明确拦一道
        cbHideHint.setVisibility(hasNotifyPermission() ? View.GONE : View.VISIBLE);
    }

    /** 捕获未处理异常，存起来供下次打开时查看 */
    private void setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    getSharedPreferences("crash", MODE_PRIVATE)
                            .edit().putString("last", sw.toString()).apply();
                } catch (Throwable ignored) { }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
