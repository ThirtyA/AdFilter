package com.lab.adfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DNS 广告过滤 VPN。
 *
 * 工作方式：
 *   1. 建立 VpnService，但只声明一个虚拟 DNS 服务器（10.0.0.1），
 *      不把全部流量路由进 VPN —— 所以正常上网完全不受影响，只有 DNS 查询会经过这里。
 *   2. 从 tun 接口读出 DNS 请求（UDP 53），取出里面的域名。
 *   3. 域名命中黑名单 -> 伪造一个 DNS 响应，A 记录指向 0.0.0.0，应用连不上广告服务器。
 *      未命中       -> 转发给真实 DNS（223.5.5.5），拿到响应后原样回写。
 *
 * 为什么这样设计：
 *   不需要 root、不需要安装 CA 证书、不需要解密 HTTPS，开销极小，
 *   而绝大多数广告 SDK 都是靠域名去拉广告的，拦住解析就拦住了广告。
 */
public class AdFilterVpnService extends VpnService {

    private static final String TAG = "AdFilterVpn";
    private static final String CHANNEL_ID = "adfilter";
    private static final int NOTIFY_ID = 1;
    private static final String REAL_DNS = "223.5.5.5";   // 阿里公共 DNS
    private static final String VIRTUAL_DNS = "10.0.0.1"; // 我们伪造的 DNS 地址
    private static final int DNS_PORT = 53;

    /** 通知里的"停止"按钮要走这条路 */
    public static final String ACTION_STOP = "com.lab.adfilter.ACTION_STOP";
    /** 通知刷新节流间隔：每 6 秒最多重建一次，避免高频 notify 唤醒系统服务 */
    private static final long NOTIFY_THROTTLE_MS = 6000;

    /**
     * 日志开关。DNS 查询量很大（一晚上几千次），每条都拼字符串会产生
     * 大量垃圾对象、增加 GC 压力。默认只记"有意思的"（拦截、白名单放行），
     * 普通放行不记，需要排障时再打开全量。
     */
    public static volatile boolean verboseLog = false;

    /** 域名统计表容量上限，防止长期运行无限膨胀 */
    private static final int MAX_STAT_DOMAINS = 3000;

    // ---- 供界面读取的运行状态 ----
    public static final AtomicBoolean running = new AtomicBoolean(false);
    public static final AtomicInteger blockedCount = new AtomicInteger(0);
    public static final AtomicInteger allowedCount = new AtomicInteger(0);
    public static final LinkedList<String> recentLog = new LinkedList<String>();
    public static boolean[] activeGroups = new boolean[BlockList.GROUP_NAMES.length];
    /** 最近一次错误，界面会显示出来，方便不用 adb 也能排查 */
    public static volatile String lastError = null;

    /** 每个域名被查询的次数（含放行的）—— 用来发现漏网的广告域名 */
    public static final Map<String, Integer> domainStats =
            Collections.synchronizedMap(new HashMap<String, Integer>());

    /** 最近被拦住的域名 -> 命中规则。白名单界面靠它做"一键放行"。 */
    public static final LinkedHashMap<String, String> blockedRecent =
            new LinkedHashMap<String, String>();

    /** 拦截返回方式：0=NXDOMAIN（推荐） 1=解析到 0.0.0.0。见 buildBlockResponse。 */
    public static volatile int blockMode = 0;

    private ParcelFileDescriptor mInterface;
    private Thread mWorker;
    private final AtomicBoolean mStop = new AtomicBoolean(false);
    private long mLastNotify = 0;

    /**
     * 统一入口：主界面、快捷设置磁贴、开机广播都走这里，保证行为一致。
     * 重复调用安全 —— 服务已在跑就只把新的分组配置推下去。
     */
    public static void start(Context ctx, boolean[] groups) {
        AppSettings.init(ctx);
        AllowList.init(ctx);
        Intent i = new Intent(ctx, AdFilterVpnService.class);
        i.putExtra("groups", groups);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // Android 12+ 后台启动前台服务受限时抛 ForegroundServiceStartNotAllowedException，
            // 兜住，别让调用方崩
            lastError = "启动服务失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
            addLog(lastError);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, AdFilterVpnService.class));
        } catch (Throwable ignored) { }
    }

    static {
        // 默认全开
        for (int i = 0; i < activeGroups.length; i++) activeGroups[i] = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 越早越好：白名单和设置在各个入口（界面 / 磁贴 / 开机广播）都要用到
        AppSettings.init(this);
        AllowList.init(this);
        AppSettings.loadGroups(activeGroups);
        blockMode = AppSettings.getInt(AppSettings.KEY_BLOCK_MODE, 0);
        startForegroundWithNotification();
    }

    private void startForegroundWithNotification() {
        createChannelIfNeeded();
        // 通知只是"防止服务被系统回收"的辅助手段，不是核心功能。
        // Android 13+ 需要 POST_NOTIFICATIONS 权限、14+ 需要声明 foregroundServiceType，
        // 任一不满足都会让 startForeground 抛异常。这里兜住，保证过滤功能照常工作。
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Throwable t) {
            lastError = "通知启动失败(不影响过滤): " + t.getClass().getSimpleName()
                    + " " + t.getMessage();
            addLog(lastError);
        }
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "广告过滤", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("显示当前已拦截的广告请求数量");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /**
     * 构造常驻通知。
     * 标题固定，正文是实时统计 —— 这是用户在桌面/后台看不到 App 之后唯一的可见入口。
     */
    private Notification buildNotification() {
        Intent ui = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, ui,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        int blocked = blockedCount.get();
        int allowed = allowedCount.get();
        nb.setContentTitle("广告拦截正在运行")
          .setContentText("已拦截 " + blocked + " 次　放行 " + allowed + " 次")
          .setSmallIcon(android.R.drawable.ic_menu_view)
          .setContentIntent(pi)
          .setOngoing(true)
          .setOnlyAlertOnce(true)     // 每次刷新都响一声就烦人了
          .setWhen(System.currentTimeMillis());

        // 通知里直接给个停止按钮：App 藏起来之后靠它紧急关闭
        Intent stop = new Intent(this, AdFilterVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        // 注：Notification.Action.Builder 只有 (int icon, CharSequence, PendingIntent)
        // 这一个可用签名，没有不带图标的两参版本
        nb.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        return nb.build();
    }

    /** 拦截/放行计数变化时刷新通知，带节流 */
    private void refreshNotification() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastNotify < NOTIFY_THROTTLE_MS) return;
        mLastNotify = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID, buildNotification());
        } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏的"停止"按钮
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            addLog("收到停止指令（来自通知栏）");
            mStop.set(true);
            running.set(false);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.get()) {
            // 已经在跑，只更新规则
            return START_STICKY;
        }
        if (intent != null) {
            boolean[] g = intent.getBooleanArrayExtra("groups");
            if (g != null && g.length == activeGroups.length) {
                activeGroups = g;
            }
        }
        running.set(true);
        mStop.set(false);
        mWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                // 公共规则集有十几万条，必须放在工作线程加载，
                // 否则几秒的读取会阻塞主线程直接 ANR。
                BlockList.loadPublicRules(AdFilterVpnService.this);
                addLog("规则加载完成，共 " + BlockList.publicRuleCount() + " 条");
                try {
                    establishVpn();
                } catch (Exception e) {
                    Log.e(TAG, "建立 VPN 失败", e);
                    lastError = "建立 VPN 失败: " + e.getMessage();
                    addLog(lastError);
                    running.set(false);
                    stopSelf();
                    return;
                }
                loop();
            }
        }, "AdFilterWorker");
        mWorker.start();
        addLog("正在加载规则，请稍候...");
        return START_STICKY;
    }

    private void establishVpn() throws Exception {
        Builder b = new Builder();
        b.setSession("AdFilter")
         .addAddress("10.0.0.2", 24)      // VPN 自身地址
         .addRoute("10.0.0.0", 24)        // 显式加路由，确保 DNS 流量一定进 tun
         .addDnsServer(VIRTUAL_DNS)       // 系统 DNS 会发往这个虚拟地址
         .setMtu(1500);
        mInterface = b.establish();
    }

    private void loop() {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(mInterface.getFileDescriptor());
            out = new FileOutputStream(mInterface.getFileDescriptor());
            byte[] buf = new byte[32767];
            while (!mStop.get() && !Thread.interrupted()) {
                int len;
                try {
                    len = in.read(buf);
                } catch (IOException e) {
                    if (mStop.get()) break;
                    continue;
                }
                if (len <= 0) continue;
                byte[] packet = Arrays.copyOf(buf, len);
                try {
                    handlePacket(packet, out);
                } catch (Exception e) {
                    Log.w(TAG, "处理包出错", e);
                }
            }
        } catch (Exception e) {
            lastError = "循环异常: " + e.getClass().getSimpleName() + " " + e.getMessage();
            Log.e(TAG, "循环异常", e);
            addLog(lastError);
        } finally {
            running.set(false);
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 解析 IPv4 -> UDP -> DNS，决定拦截还是放行 */
    private void handlePacket(byte[] packet, FileOutputStream out) throws IOException {
        // 边界检查必须做全：tun 里可能读到各种畸形/过短的包，
        // 少一个判断就会 ArrayIndexOutOfBoundsException 直接把服务打崩。
        if (packet == null || packet.length < 28) return;   // IP头20 + UDP头8
        if ((packet[0] >> 4) != 4) return;                  // 只要 IPv4

        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || ihl + 8 > packet.length) return;

        int protocol = packet[9] & 0xFF;
        if (protocol != 17) return;                         // 非 UDP

        int srcIp = readInt(packet, 12);
        int dstIp = readInt(packet, 16);

        int udpOff = ihl;
        if (udpOff + 8 > packet.length) return;
        int srcPort = readShort(packet, udpOff);
        int dstPort = readShort(packet, udpOff + 2);
        if (dstPort != DNS_PORT) return;                    // 非 DNS

        int udpLen = readShort(packet, udpOff + 4);
        if (udpLen < 8 || udpOff + udpLen > packet.length) {
            udpLen = packet.length - udpOff;                // 长度字段不可信时按实际裁剪
        }
        int dnsOff = udpOff + 8;
        int dnsLen = udpLen - 8;
        if (dnsLen <= 12 || dnsOff + dnsLen > packet.length) return;

        String domain = parseDomain(packet, dnsOff);
        if (domain == null || domain.length() == 0) return;

        recordDomain(domain);

        // 判定顺序很重要：白名单第一，其次才是黑名单。
        // 白名单命中的域名无条件放行，任何规则都拦不住 —— 这是防误杀的安全阀。
        String allowSrc = AllowList.check(domain);
        if (allowSrc != null) {
            forwardReal(packet, dnsOff, dnsLen, out, dstIp, srcIp, srcPort);
            allowedCount.incrementAndGet();
            addLog("[白] " + domain + "  (" + allowSrc + "白名单，强制放行)");
            return;
        }

        String rule = BlockList.matchRule(domain, activeGroups);
        if (rule != null) {
            byte[] resp = buildBlockResponse(packet, dnsOff, dnsLen, blockMode);
            if (resp.length > 0) {
                writeUdp(out, dstIp, srcIp, DNS_PORT, srcPort, resp);
            }
            blockedCount.incrementAndGet();
            recordBlocked(domain, rule);
            addLog("[拦] " + domain + "  ← " + rule);
            refreshNotification();
        } else {
            forwardReal(packet, dnsOff, dnsLen, out, dstIp, srcIp, srcPort);
            allowedCount.incrementAndGet();
            // 普通放行量大且没诊断价值，只在全量日志模式下才记
            if (verboseLog) addLog("[放] " + domain);
        }
    }

    /** 转发给真实 DNS 并把响应写回 tun */
    private void forwardReal(byte[] packet, int dnsOff, int dnsLen, FileOutputStream out,
                             int dstIp, int srcIp, int srcPort) throws IOException {
        byte[] payload = Arrays.copyOfRange(packet, dnsOff, dnsOff + dnsLen);
        byte[] resp = queryRealDns(payload);
        if (resp != null && resp.length > 0) {
            writeUdp(out, dstIp, srcIp, DNS_PORT, srcPort, resp);
        }
    }

    /** 记下最近被拦的域名及其命中规则，供白名单界面一键放行（只留最近 80 条） */
    private static void recordBlocked(String domain, String rule) {
        synchronized (blockedRecent) {
            blockedRecent.remove(domain);
            blockedRecent.put(domain, rule);
            while (blockedRecent.size() > 80) {
                java.util.Iterator<String> it = blockedRecent.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    private static void recordDomain(String d) {
        if (d == null) return;
        synchronized (domainStats) {
            // 加容量上限：长期运行不能让统计表无限长，否则内存和 GC 都会恶化。
            // 到上限就整体清空重来 —— 排行本来就是"最近"的诊断视图，清空可接受。
            if (domainStats.size() >= MAX_STAT_DOMAINS) domainStats.clear();
            Integer c = domainStats.get(d);
            domainStats.put(d, c == null ? 1 : c + 1);
        }
    }

    /** 返回按查询次数排序的域名列表，格式 "域名 (次数,拦/放)" */
    public static List<String> topDomains(int n) {
        List<Map.Entry<String, Integer>> list;
        synchronized (domainStats) {
            list = new ArrayList<Map.Entry<String, Integer>>(domainStats.entrySet());
        }
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        List<String> out = new ArrayList<String>();
        boolean[] g = activeGroups;
        for (int i = 0; i < list.size() && i < n; i++) {
            Map.Entry<String, Integer> e = list.get(i);
            String d = e.getKey();
            String tag;
            String allowSrc = AllowList.check(d);
            if (allowSrc != null) {
                tag = "白名单放行";
            } else {
                String rule = BlockList.matchRule(d, g);
                tag = rule == null ? "放行" : "拦←" + rule;
            }
            out.add(d + "  x" + e.getValue() + "  [" + tag + "]");
        }
        return out;
    }

    /** 从 DNS 报文里读出查询的域名 */
    private static String parseDomain(byte[] data, int off) {
        StringBuilder sb = new StringBuilder();
        int pos = off + 12;               // 跳过 12 字节 DNS 头
        if (pos >= data.length) return null;
        int len = data[pos] & 0xFF;
        int guard = 0;
        while (len != 0 && guard++ < 128) {
            if ((len & 0xC0) == 0xC0) break;   // 压缩指针，不处理
            pos++;
            if (pos + len > data.length) return null;
            sb.append(new String(data, pos, len)).append('.');
            pos += len;
            if (pos >= data.length) return null;
            len = data[pos] & 0xFF;
        }
        if (sb.length() == 0) return null;
        sb.setLength(sb.length() - 1);           // 去掉最后一个点
        return sb.toString();
    }

    /**
     * 构造被拦截的 DNS 响应，支持两种方式：
     *
     * mode = 0 → NXDOMAIN（推荐）：告诉应用"这个域名根本不存在"。
     *   应用会立刻失败并走自己的错误处理，反应最快，也最省资源。
     *
     * mode = 1 → A 记录指向 0.0.0.0：告诉应用"域名存在，但地址是 0.0.0.0"。
     *   有些 App 拿到地址后会一直重试连接直到超时，表现为页面转圈、卡顿。
     *   所以默认不用它，但个别 App 在 NXDOMAIN 下会有异常状态，可切过来试试。
     */
    private static byte[] buildBlockResponse(byte[] req, int dnsOff, int dnsLen, int mode) {
        int qStart = dnsOff + 12;
        int qLen = dnsLen - 12;
        if (qLen <= 0) return new byte[0];

        if (mode == 1) {
            byte[] resp = new byte[12 + qLen + 16];
            System.arraycopy(req, dnsOff, resp, 0, 12);   // 复制并改写头部
            resp[2] = (byte) 0x81;                        // QR=1 (响应), RD=1
            resp[3] = (byte) 0x80;                        // RA=1, RCODE=0
            resp[6] = 0; resp[7] = 1;                     // ANCOUNT = 1

            System.arraycopy(req, qStart, resp, 12, qLen); // 原样带上问题段

            int p = 12 + qLen;
            resp[p++] = (byte) 0xC0; resp[p++] = 0x0C;     // 名字指针，指向偏移 12
            resp[p++] = 0; resp[p++] = 1;                  // TYPE = A
            resp[p++] = 0; resp[p++] = 1;                  // CLASS = IN
            resp[p++] = 0; resp[p++] = 0; resp[p++] = 0; resp[p++] = 60;  // TTL = 60
            resp[p++] = 0; resp[p++] = 4;                  // RDLENGTH = 4
            resp[p++] = 0; resp[p++] = 0; resp[p++] = 0; resp[p++] = 0;   // 0.0.0.0
            return resp;
        }

        // NXDOMAIN：RCODE = 3，不返回任何答案记录
        byte[] resp = new byte[12 + qLen];
        System.arraycopy(req, dnsOff, resp, 0, 12);
        resp[2] = (byte) 0x81;                            // QR=1, RD=1
        resp[3] = (byte) 0x83;                            // RA=1, RCODE=3 (NXDOMAIN)
        resp[4] = 0; resp[5] = 0;                          // ARCOUNT = ANCOUNT = 0
        resp[6] = 0; resp[7] = 0;
        resp[8] = 0; resp[9] = 0;
        resp[10] = 0; resp[11] = 0;
        System.arraycopy(req, qStart, resp, 12, qLen);     // 原样带上问题段
        return resp;
    }

    /**
     * 转发给真实 DNS —— 常驻 socket 版本（省电的关键改造）。
     *
     * 旧实现每来一个查询就 new 一个 DatagramSocket、查一次 InetAddress、
     * 发完立刻 close。一台手机一晚上几千次 DNS 查询，就是几千次
     * socket 创建/销毁 —— 每次都拉起内核网络栈、重走路由选择，
     * 在 VPN 场景下还要再穿一遍 tun。这些开销全烧在软件中断和上下文切换上，
     * 跟流量大小无关，所以"只过滤 DNS 应该很省电"的直觉是错的。
     *
     * 现在：一个 socket 常驻复用，上游地址解析一次缓存成常量。
     * 注意 sock 是复用的，接收缓冲区必须在调用方并发时各用各的 ——
     * 见 forwardAsync 里的处理（每个任务独立缓冲区 + synchronized 保护收发）。
     */
    private static DatagramSocket sDnsSocket;
    private static InetAddress sDnsAddr;
    private static final Object sDnsLock = new Object();

    private static boolean ensureDnsSocket() {
        if (sDnsSocket != null && !sDnsSocket.isClosed() && sDnsAddr != null) return true;
        try {
            InetAddress addr = InetAddress.getByName(REAL_DNS);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(5000);
            sDnsAddr = addr;
            sDnsSocket = s;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeDnsSocket() {
        synchronized (sDnsLock) {
            if (sDnsSocket != null) {
                try { sDnsSocket.close(); } catch (Exception ignored) { }
                sDnsSocket = null;
            }
            sDnsAddr = null;
        }
    }

    /**
     * 一次 DNS 往返。收发都加锁（UDP 收发本身很快，锁竞争极小），
     * 这样多个并发转发共用一个 socket 也不会把响应串包。
     */
    private static byte[] queryRealDns(byte[] payload) {
        synchronized (sDnsLock) {
            if (!ensureDnsSocket()) return null;
            try {
                DatagramPacket req = new DatagramPacket(payload, payload.length, sDnsAddr, DNS_PORT);
                sDnsSocket.send(req);
                byte[] rb = new byte[4096];
                DatagramPacket rp = new DatagramPacket(rb, rb.length);
                sDnsSocket.receive(rp);
                return Arrays.copyOf(rb, rp.getLength());
            } catch (Exception e) {
                // 出过错就丢弃这个 socket，下次重建，避免一直用坏掉的
                try { if (sDnsSocket != null) sDnsSocket.close(); } catch (Exception ignored) { }
                sDnsSocket = null;
                return null;
            }
        }
    }
    /** 把 DNS 响应封装成 UDP/IP 包写回 tun（源和目的对调） */
    private static void writeUdp(FileOutputStream out, int srcIp, int dstIp,
                                 int srcPort, int dstPort, byte[] payload) throws IOException {
        int udpLen = 8 + payload.length;
        int total = 20 + udpLen;
        byte[] ip = new byte[total];

        ip[0] = 0x45; ip[1] = 0x00;
        writeShort(ip, 2, total);
        writeShort(ip, 4, 0);
        writeShort(ip, 6, 0x4000);
        ip[8] = 64;                 // TTL
        ip[9] = 17;                 // UDP
        writeInt(ip, 12, srcIp);
        writeInt(ip, 16, dstIp);

        writeShort(ip, 20, srcPort);
        writeShort(ip, 22, dstPort);
        writeShort(ip, 24, udpLen);
        writeShort(ip, 26, 0);
        System.arraycopy(payload, 0, ip, 28, payload.length);

        writeShort(ip, 26, udpChecksum(ip, udpLen, srcIp, dstIp));
        writeShort(ip, 10, 0);
        writeShort(ip, 10, ipChecksum(ip, 20));

        out.write(ip);
    }

    // ---------------- 字节读写与校验和 ----------------
    private static int readShort(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
    private static int readInt(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
    private static void writeShort(byte[] d, int o, int v) { d[o] = (byte) (v >> 8); d[o + 1] = (byte) v; }
    private static void writeInt(byte[] d, int o, int v) {
        d[o] = (byte) (v >> 24); d[o + 1] = (byte) (v >> 16); d[o + 2] = (byte) (v >> 8); d[o + 3] = (byte) v;
    }

    private static int ipChecksum(byte[] d, int headerLen) {
        long sum = 0;
        for (int i = 0; i < headerLen; i += 2) sum += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        while ((sum >> 16) > 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    private static int udpChecksum(byte[] ip, int udpLen, int srcIp, int dstIp) {
        long sum = 0;
        sum += (srcIp >> 16) & 0xFFFF; sum += srcIp & 0xFFFF;
        sum += (dstIp >> 16) & 0xFFFF; sum += dstIp & 0xFFFF;
        sum += 17;              // protocol
        sum += udpLen;
        int off = 20;
        for (int i = 0; i < udpLen - 1; i += 2) {
            sum += ((ip[off + i] & 0xFF) << 8) | (ip[off + i + 1] & 0xFF);
        }
        if (udpLen % 2 == 1) sum += (ip[off + udpLen - 1] & 0xFF) << 8;
        while ((sum >> 16) > 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int r = (int) (~sum & 0xFFFF);
        return r == 0 ? 0xFFFF : r;
    }

    private static void addLog(String s) {
        synchronized (recentLog) {
            recentLog.addFirst(s);
            while (recentLog.size() > 60) recentLog.removeLast();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        mStop.set(true);
        running.set(false);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_ID);
        } catch (Throwable ignored) { }
        if (mWorker != null) mWorker.interrupt();
        closeDnsSocket();
        try {
            if (mInterface != null) mInterface.close();
        } catch (Exception ignored) { }
        addLog("已停止");
        super.onDestroy();
    }
}
