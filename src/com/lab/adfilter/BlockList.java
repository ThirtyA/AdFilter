package com.lab.adfilter;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;

/**
 * 广告域名黑名单。
 *
 * 两部分组成：
 *  1. 手工整理的核心分组（穿山甲 / 腾讯 / 快手 / 百度 / 华为荣耀），
 *     这些是从目标 APK 里静态提取出来的，针对性强。
 *  2. 公共规则集：合并自 AdAway、AdGuard 中文规则、AdGuard DNS 等公开社区规则，
 *     放在 assets/blocklist.txt，启动时加载。
 *
 * 为什么公共规则集要放文件而不是写进代码：
 *   Java 常量池有 64KB 上限，几千条域名写进源码会编译不过；
 *   放 assets 还便于以后直接换文件更新，不用重新编译。
 *
 * 匹配规则：精确匹配，或结尾匹配（拦截该域名及其所有子域）。
 */
public class BlockList {

    public static final String[] GROUP_NAMES = {
            "穿山甲 Pangle（字节）",
            "腾讯优量汇 GDT",
            "快手联盟 KSAD",
            "百度联盟",
            "华为 / 荣耀广告",
            "公共广告规则集",
    };

    public static final String[][] GROUP_DOMAINS = {
            // 穿山甲（字节）—— pangle.cn/io 是穿山甲海外与开发者平台，
            // oceanengine 是巨量引擎，都是广告专用域
            { "snssdk.com", "pstatp.com", "bdurl.net", "pglstatp-toutiao.com",
              "pangle.cn", "pangle.io", "oceanengine.com", "csjplatform.com",
              "pangolin-toutiao.com" },
            // 腾讯优量汇 —— e.qq.com 是腾讯社交广告
            { "gdt.qq.com", "e.qq.com", "gdtimg.com" },
            // 快手 —— ksapisrv 是快手对外 API
            { "kuaishou.com", "kuaishou.cn", "kuaishouzt.com", "kwaizt.com",
              "gifshow.com", "kskwai.com", "gskwai.com", "ksapisrv.com" },
            // 百度 —— bdstatic 与 mobads 都是百度联盟资源域
            { "baidustatic.com", "bdstatic.com", "mobads.baidu.com",
              "pos.baidu.com", "cbjs.baidu.com", "nsclick.baidu.com",
              "cpro.baidu.com" },
            // 华为 / 荣耀
            { "cloud.honor.com", "hihonorcloud.com", "hicloud.com" },
            // 公共规则集（内容在 assets/blocklist.txt，此处为占位）
            { },
    };

    public static final int GROUP_PUBLIC = 5;

    private static final HashSet<String> publicRules = new HashSet<String>();
    private static volatile boolean publicLoaded = false;
    private static volatile int publicCount = 0;

    /** 从 assets 读取公共规则集 */
    public static synchronized void loadPublicRules(Context ctx) {
        if (publicLoaded) return;
        try {
            InputStream is = ctx.getAssets().open("blocklist.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (line.length() > 3 && !line.startsWith("#")) {
                    publicRules.add(line);
                }
            }
            br.close();
            publicCount = publicRules.size();
        } catch (Exception e) {
            publicCount = 0;   // 文件不存在也不影响核心功能
        }
        publicLoaded = true;
    }

    public static int publicRuleCount() {
        return publicCount;
    }

    /**
     * 判断域名是否需要拦截。
     * activeGroups 指明哪些分组处于启用状态。
     */
    public static boolean isBlocked(String domain, boolean[] activeGroups) {
        return matchRule(domain, activeGroups) != null;
    }

    /**
     * 拦截溯源：返回"是哪一条规则拦住了这个域名"。
     *
     * 为什么需要它：规则合并到十几万条以后，光看"[拦] xxx.com"没法判断
     * 是正常拦截还是误杀。打出命中来源，用户一眼就能看出来——
     * 比如 "公共规则集: doubleclick.net" 基本没问题，
     * 但如果某个正常服务被 "公共规则集: xxx" 挡了，加白名单就有据可依。
     *
     * @return 命中的规则描述，未命中返回 null
     */
    public static String matchRule(String domain, boolean[] activeGroups) {
        if (domain == null || domain.length() == 0) return null;
        String d = domain.toLowerCase();
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);

        for (int g = 0; g < GROUP_DOMAINS.length; g++) {
            if (g == GROUP_PUBLIC) continue;
            if (activeGroups == null || !activeGroups[g]) continue;
            for (String rule : GROUP_DOMAINS[g]) {
                if (d.equals(rule) || d.endsWith("." + rule)) return GROUP_NAMES[g] + ": " + rule;
            }
        }

        if (activeGroups != null && activeGroups.length > GROUP_PUBLIC
                && activeGroups[GROUP_PUBLIC]) {
            String r = matchPublicRule(d);
            if (r != null) return "公共规则集: " + r;
        }
        return null;
    }

    /** 公共规则集是否命中，返回命中的那条规则 */
    private static String matchPublicRule(String d) {
        if (publicRules.isEmpty()) return null;
        if (publicRules.contains(d)) return d;
        int idx = 0;
        while ((idx = d.indexOf('.', idx + 1)) > 0) {
            String parent = d.substring(idx + 1);
            if (publicRules.contains(parent)) return parent;
        }
        return null;
    }

    /** 启用的规则总数 */
    public static int activeRuleCount(boolean[] activeGroups) {
        int n = 0;
        for (int g = 0; g < GROUP_DOMAINS.length; g++) {
            if (g == GROUP_PUBLIC) {
                if (activeGroups != null && activeGroups[g]) n += publicCount;
                continue;
            }
            if (activeGroups != null && activeGroups[g]) n += GROUP_DOMAINS[g].length;
        }
        return n;
    }
}
