package com.lab.adfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置持久化。统一用一个 SharedPreferences 文件，
 * 让主界面、快捷开关磁贴、开机广播看到的是同一份配置。
 */
public final class AppSettings {

    /** 开机自动启动过滤（默认关，避免系统限制导致启动失败时用户不知道） */
    public static final String KEY_AUTOSTART = "autostart";
    /** 拦截返回方式：0 = NXDOMAIN（推荐），1 = 解析到 0.0.0.0 */
    public static final String KEY_BLOCK_MODE = "block_mode";
    /** 规则分组开关，按位存成一个 long */
    public static final String KEY_GROUPS = "groups";

    private static Context appCtx;

    public static void init(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    private static SharedPreferences sp() {
        return appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean getBool(String key, boolean def) {
        if (appCtx == null) return def;
        return sp().getBoolean(key, def);
    }

    public static void setBool(String key, boolean v) {
        if (appCtx == null) return;
        sp().edit().putBoolean(key, v).apply();
    }

    public static int getInt(String key, int def) {
        if (appCtx == null) return def;
        return sp().getInt(key, def);
    }

    public static void setInt(String key, int v) {
        if (appCtx == null) return;
        sp().edit().putInt(key, v).apply();
    }

    // ---- 规则分组开关：用 bitmask 存，省事又不会和数组长度变化打架 ----
    public static void saveGroups(boolean[] g) {
        long bits = 0;
        for (int i = 0; i < g.length && i < 64; i++) {
            if (g[i]) bits |= (1L << i);
        }
        if (appCtx == null) return;
        sp().edit().putLong(KEY_GROUPS, bits).apply();
    }

    public static void loadGroups(boolean[] g) {
        long bits = sp().getLong(KEY_GROUPS, ~0L);   // 没存过就是全开
        for (int i = 0; i < g.length; i++) {
            g[i] = (bits & (1L << i)) != 0;
        }
    }
}
