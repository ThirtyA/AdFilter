package com.lab.adfilter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 白名单：优先级高于一切黑名单，命中就无条件放行。
 *
 * 为什么必须有：
 *   合并进来的公共规则集有 18 万条，覆盖面是上去了，但社区规则里
 *   有大量纯统计/遥测域名涉嫌过度拦截，而且不同规则作者尺度不一，
 *   误杀几乎不可避免。白名单是这套方案唯一的安全阀。
 *
 * 优先级设计：
 *   白名单 > 手工分组黑名单 > 公共规则集
 *   也就是说白名单命中的域名，任何规则都拦不住它。
 *
 * 匹配方式：精确匹配，或后缀匹配（放行该域名及所有子域）。
 * 注意：后缀匹配是一把双刃剑 —— 白名单里写 "qq.com" 会连带放行
 * gdt.qq.com（腾讯广告），所以内置条目必须尽量精确，宁可少写。
 */
public final class AllowList {

    /**
     * 内置不可删除的保底白名单。
     * 只放"误杀代价远高于广告收益"的类别：支付、银行、地图导航、厂商推送。
     * 写新条目前先想清楚它的后缀会不会连带放行一整片商业域名。
     */
    public static final String[] BUILTIN = {
            // ---- 支付 / 金融：误杀 = 付不了钱，代价最高 ----
            "alipay.com", "alipayobjects.com", "alipaydev.com",
            "tenpay.com", "unionpay.com", "95516.com",
            "weixin.qq.com", "wxapp.qq.com", "mp.weixin.qq.com",
            // ---- 银行 ----
            "icbc.com.cn", "abchina.com", "ccb.com", "boc.cn",
            "cmbchina.com", "cmbimg.com", "bankcomm.com", "psbc.com",
            "cib.com.cn", "spdb.com.cn",
            // ---- 地图 / 导航（目标 App 强依赖高德，拦了直接没导航）----
            "amap.com", "autonavi.com",
            // ---- 厂商推送：拦掉表现为"收不到消息"，用户很难归因 ----
            "getui.com", "igexin.com", "jpush.cn", "jiguang.cn",
            "xmpush.xiaomi.com", "hpush.huawei.com", "push.honor.com",
    };

    private static final HashSet<String> builtinSet = new HashSet<String>();
    private static final HashSet<String> customSet = new HashSet<String>();

    private static Context appCtx;
    private static volatile boolean loaded = false;

    static {
        for (String s : BUILTIN) builtinSet.add(norm(s));
    }

    /** 去掉可能的协议头、路径和末尾的点，统一小写 */
    public static String norm(String s) {
        if (s == null) return "";
        String d = s.trim().toLowerCase();
        int p = d.indexOf("://");
        if (p >= 0) d = d.substring(p + 3);
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        int colon = d.indexOf(':');
        if (colon >= 0) d = d.substring(0, colon);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return d;
    }

    /** 保存 Application Context 并完成一次加载。越早调用越好。 */
    public static void init(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        load();
    }

    private static SharedPreferences sp() {
        return appCtx.getSharedPreferences("allowlist", Context.MODE_PRIVATE);
    }

    public static synchronized void load() {
        if (loaded || appCtx == null) return;
        customSet.clear();
        String raw = sp().getString("custom", "");
        if (raw != null) {
            // 分隔符放宽：换行 / 逗号 / 分号 / 空格都认，方便从别处整段粘贴
            for (String s : raw.split("[\r\n,;\\s]+")) {
                String d = norm(s);
                if (d.length() > 2) customSet.add(d);
            }
        }
        loaded = true;
    }

    private static synchronized void persist() {
        if (appCtx == null) return;
        List<String> all = new ArrayList<String>(customSet);
        Collections.sort(all);
        StringBuilder sb = new StringBuilder();
        for (String d : all) sb.append(d).append('\n');
        sp().edit().putString("custom", sb.toString()).apply();
    }

    public static synchronized void add(String domain) {
        String d = norm(domain);
        if (d.length() < 3) return;
        customSet.add(d);
        persist();
    }

    public static synchronized void remove(String domain) {
        customSet.remove(norm(domain));
        persist();
    }

    public static synchronized void clearCustom() {
        customSet.clear();
        persist();
    }

    public static synchronized List<String> customList() {
        List<String> all = new ArrayList<String>(customSet);
        Collections.sort(all);
        return all;
    }

    public static int customCount() {
        return customSet.size();
    }

    /** 后缀匹配：命中自身或其某个父域即放行 */
    private static boolean hit(HashSet<String> set, String d) {
        if (set.contains(d)) return true;
        int idx = 0;
        while ((idx = d.indexOf('.', idx + 1)) > 0) {
            if (set.contains(d.substring(idx + 1))) return true;
        }
        return false;
    }

    /**
     * 是否在白名单里。返回 null 表示不在；否则返回命中来源（"内置" / "自定义"），
     * 便于日志里区分是谁放行的。
     */
    public static String check(String domain) {
        if (domain == null) return null;
        String d = norm(domain);
        if (d.length() == 0) return null;
        load();
        if (hit(builtinSet, d)) return "内置";
        if (hit(customSet, d)) return "自定义";
        return null;
    }

    public static boolean isAllowed(String domain) {
        return check(domain) != null;
    }
}
