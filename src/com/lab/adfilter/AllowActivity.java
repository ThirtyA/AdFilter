package com.lab.adfilter;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 白名单管理 —— 防误杀的主战场。
 *
 * 三个区块：
 *  1. 自定义白名单：用户自己加的，任何时候都无条件放行。
 *  2. 最近被拦的域名：直接从运行记录里捞出来的，点一下就加进白名单。
 *     这是实际排障时最常用的一条路径：某个 App 用不了 → 打开这里 →
 *     找到它刚才被拦的域名 → 点一下 → 立刻恢复。
 *  3. 内置保底白名单：支付 / 银行 / 地图 / 推送，只读，防止用户误删。
 */
public class AllowActivity extends Activity {

    private LinearLayout listCustom;
    private LinearLayout listRecent;
    private TextView tvCustomCount;
    private EditText etInput;
    private Handler handler = new Handler();
    private Runnable refresher;
    private int lastCustomSize = -1;
    private int lastRecentSize = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AllowList.init(this);
        AppSettings.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("白名单 / 防误杀");
        title.setTextSize(21f);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("白名单优先级高于一切拦截规则，命中即无条件放行。\n"
                + "匹配方式：精确或后缀（放行该域名及其所有子域）。");
        desc.setTextSize(12f);
        desc.setPadding(0, dp(8), 0, dp(10));
        root.addView(desc);

        // ---- 添加 ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        etInput = new EditText(this);
        etInput.setHint("域名，如 wxpay.example.com");
        etInput.setSingleLine(true);
        etInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etInput.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(etInput, lp);

        Button btnAdd = new Button(this);
        btnAdd.setText("添加");
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { doAdd(etInput.getText().toString()); }
        });
        row.addView(btnAdd);
        root.addView(row);

        // ---- 自定义白名单 ----
        tvCustomCount = new TextView(this);
        tvCustomCount.setTextSize(14f);
        tvCustomCount.setPadding(0, dp(14), 0, dp(4));
        root.addView(tvCustomCount);

        listCustom = new LinearLayout(this);
        listCustom.setOrientation(LinearLayout.VERTICAL);
        root.addView(listCustom);

        Button btnClear = new Button(this);
        btnClear.setText("清空自定义白名单");
        btnClear.setTextSize(12f);
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AllowList.clearCustom();
                lastCustomSize = -1;
                renderCustom();
                toast("已清空");
            }
        });
        root.addView(btnClear);

        // ---- 最近被拦 ----
        TextView t2 = new TextView(this);
        t2.setText("最近被拦住的域名（点一下加入白名单）");
        t2.setTextSize(14f);
        t2.setPadding(0, dp(16), 0, dp(4));
        root.addView(t2);

        TextView tip = new TextView(this);
        tip.setText("需要先启动过滤，并打开出问题的 App，这里才会有记录。");
        tip.setTextSize(11f);
        tip.setPadding(0, 0, 0, dp(4));
        root.addView(tip);

        listRecent = new LinearLayout(this);
        listRecent.setOrientation(LinearLayout.VERTICAL);
        root.addView(listRecent);

        // ---- 内置白名单 ----
        TextView t3 = new TextView(this);
        t3.setText("内置保底白名单（支付 / 银行 / 地图 / 推送，不可删除）");
        t3.setTextSize(14f);
        t3.setPadding(0, dp(16), 0, dp(4));
        root.addView(t3);

        TextView builtin = new TextView(this);
        builtin.setTextSize(11f);
        builtin.setBackgroundColor(0xFFF2F4F7);
        builtin.setPadding(dp(8), dp(8), dp(8), dp(8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AllowList.BUILTIN.length; i++) {
            sb.append(AllowList.BUILTIN[i]);
            if (i < AllowList.BUILTIN.length - 1) sb.append("、");
        }
        builtin.setText(sb.toString());
        root.addView(builtin);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        renderCustom();
        renderRecent();

        refresher = new Runnable() {
            @Override
            public void run() {
                if (AllowList.customCount() != lastCustomSize) renderCustom();
                int recentSize;
                synchronized (AdFilterVpnService.blockedRecent) {
                    recentSize = AdFilterVpnService.blockedRecent.size();
                }
                if (recentSize != lastRecentSize) renderRecent();
                // 白名单变化很慢，2 秒一次足够；这个界面也不常驻前台
                handler.postDelayed(this, 2000);
            }
        };
    }

    private void doAdd(String raw) {
        String d = AllowList.norm(raw);
        if (d.length() < 3) {
            toast("请输入有效域名");
            return;
        }
        AllowList.add(d);
        lastCustomSize = -1;
        renderCustom();
        etInput.setText("");
        toast("已加入白名单：" + d);
    }

    private void renderCustom() {
        listCustom.removeAllViews();
        List<String> all = AllowList.customList();
        lastCustomSize = all.size();
        tvCustomCount.setText("自定义白名单（" + all.size() + " 条）");
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（空）");
            empty.setTextSize(12f);
            listCustom.addView(empty);
            return;
        }
        for (String d : all) {
            final String domain = d;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            TextView tv = new TextView(this);
            tv.setText(domain);
            tv.setTextSize(13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(tv, lp);

            Button rm = new Button(this);
            rm.setText("移除");
            rm.setTextSize(11f);
            rm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AllowList.remove(domain);
                    lastCustomSize = -1;
                    renderCustom();
                }
            });
            row.addView(rm);
            listCustom.addView(row);
        }
    }

    private void renderRecent() {
        listRecent.removeAllViews();
        // 倒序：最近拦住的排最上面
        List<Map.Entry<String, String>> entries;
        synchronized (AdFilterVpnService.blockedRecent) {
            entries = new ArrayList<Map.Entry<String, String>>(
                    AdFilterVpnService.blockedRecent.entrySet());
        }
        lastRecentSize = entries.size();

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无记录）");
            empty.setTextSize(12f);
            listRecent.addView(empty);
            return;
        }

        List<Map.Entry<String, String>> rev = new LinkedList<Map.Entry<String, String>>(entries);
        java.util.Collections.reverse(rev);
        int shown = 0;
        for (Map.Entry<String, String> e : rev) {
            final String d = e.getKey();
            final String rule = e.getValue();
            if (AllowList.isAllowed(d)) continue;      // 已经放行过的不再显示
            TextView tv = new TextView(this);
            tv.setText(d + "\n  ← " + rule);
            tv.setTextSize(12f);
            tv.setPadding(0, dp(6), 0, dp(6));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AllowList.add(d);
                    lastCustomSize = -1;
                    lastRecentSize = -1;
                    renderCustom();
                    renderRecent();
                    toast("已放行：" + d + "\n下次查询起生效");
                }
            });
            listRecent.addView(tv);
            if (++shown >= 40) break;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("（暂无记录）");
            empty.setTextSize(12f);
            listRecent.addView(empty);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
