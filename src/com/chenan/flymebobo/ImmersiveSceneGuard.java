package com.chenan.flymebobo;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景识别：横屏打游戏 / 横屏看视频时，不显示气泡通知。
 *
 * 判定规则 (v1, 三个条件同时满足):
 *   1. 当前是横屏 (Configuration.ORIENTATION_LANDSCAPE)
 *   2. 前台不是桌面 / 不是 SystemUI / 不是模块自己
 *   3. 前台应用是游戏 (ApplicationInfo.CATEGORY_GAME, API 26+),
 *      或是已知视频应用, 或前台 Activity 名字带 Player/Video/Cinema 等特征
 *
 * 为什么不只看横屏: 横屏刷微博、横屏看文档也会横屏, 只看方向会误伤。
 * 为什么不只维护名单: 游戏靠系统 category 判断, 不用逐个加包名;
 *                  视频应用长尾太多, 名单 + Activity 特征兜底。
 *
 * 使用方式见 INTEGRATION.md: 在 BubbleWindow 里持有一个本类实例,
 * onNotification 入口问一次 isSuppressed(), 再靠 Listener 处理
 * "进入场景收起已有气泡 / 退出场景恢复并补弹"。
 *
 * 本类只做识别, 不直接操作气泡 View, 方便单独调试:
 * 把 DEBUG_LOG 打开后, 每次场景变化都会写 FileLog。
 */
public class ImmersiveSceneGuard {

    public interface Listener {
        /** @param immersive true = 进入游戏/视频场景, 气泡应隐藏; false = 退出, 应恢复 */
        void onSceneChanged(boolean immersive);

        /** v557: 顶层切到桌面 / 最近任务（概览）时回调。
         * 全屏浮层（长按菜单、瞄一眼/收纳面板）在概览里会
         * 吃掉任务卡片的左右滑，一进壳界面就先把它们收掉。 */
        void onShellTopShown(String pkg, String cls);
    }

    private static final boolean DEBUG_LOG = true;
    private static final long POLL_MS = 800L;

    /** 已知视频应用包名 (兜底名单, 可自行增删)。短视频竖屏播放不在本场景范围内。 */
    private static final Set<String> VIDEO_PACKAGES = new HashSet<>(Arrays.asList(
            "tv.danmaku.bili",              // 哔哩哔哩
            "com.bilibili.app.in",          // 哔哩哔哩国际版
            "com.qiyi.video",               // 爱奇艺
            "com.tencent.qqlive",           // 腾讯视频
            "com.youku.phone",              // 优酷
            "com.google.android.youtube",   // YouTube
            "tv.danmaku.bilibilihd",        // 哔哩哔哩 HD
            "com.netflix.mediaclient",      // Netflix
            "com.letv.android.client",      // 乐视视频
            "com.sohu.sohuvideo",           // 搜狐视频
            "com.baidu.video"               // 百度视频
    ));

    /** v510: known game packages — the fallback for games that
     * declare no appCategory and whose top Activity carries no
     * engine keyword. 金铲铲之战 (com.tencent.jkchess) is the
     * proven case (user 2026-11-24): landscape, foreground for
     * long stretches, yet detect() never fired — no category,
     * no engine hint in its Activity names. */
    // v511: 与线上 games.txt 同步的内置名单 (断网兜底;
    // 线上名单由 GamePackages 叠加, 以线上为准持续扩充)。
    private static final Set<String> GAME_PACKAGES = new HashSet<>(Arrays.asList(
            // 腾讯
            "com.tencent.tmgp.sgame",       // 王者荣耀
            "com.tencent.tmgp.pubgmhd",     // 和平精英
            "com.tencent.jkchess",          // 金铲铲之战
            "com.tencent.lolm",             // 英雄联盟手游
            "com.tencent.tmgp.cf",          // 穿越火线手游
            "com.tencent.tmgp.cod",         // 使命召唤手游
            "com.tencent.tmgp.dfm",         // 三角洲行动
            "com.tencent.tmgp.codev",       // 无畏契约: 源能行动
            "com.tencent.mf.uam",           // 暗区突围
            "com.tencent.tmgp.dnf",         // 地下城与勇士: 起源
            "com.tencent.KiHan",            // 火影忍者
            "com.tencent.tmgp.speedmobile", // QQ飞车
            "com.tencent.tmgp.wuxia",       // 天涯明月刀
            "com.tencent.letsgo",           // 元梦之星
            "com.tencent.toaa",             // 黎明觉醒: 生机
            "com.tencent.tmgp.dragonraja",  // 龙族幻想
            "com.tencent.tmgp.hotta",       // 幻塔 (应用宝渠道)
            // 网易
            "com.netease.onmyoji",          // 阴阳师
            "com.netease.dwrg",             // 第五人格
            "com.netease.nshm",             // 逆水寒
            "com.netease.wyclx",            // 一梦江湖
            "com.netease.my",               // 梦幻西游
            "com.netease.party",            // 蛋仔派对
            "com.netease.sky",              // 光·遇
            "com.bilibili.sky",             // 光·遇 (B站版)
            "com.netease.moba",             // 决战! 平安京
            "com.netease.hyxd",             // 荒野行动
            "com.netease.mrzh",             // 明日之后
            "com.netease.pes",              // 实况足球
            "com.netease.harrypotter",      // 哈利波特: 魔法觉醒
            "com.netease.aceracer",         // 王牌竞速
            "com.netease.race",             // 巅峰极速
            "com.netease.x19",              // 我的世界
            // 米哈游
            "com.miHoYo.Yuanshen",          // 原神
            "com.miHoYo.bh3",               // 崩坏3
            "com.miHoYo.hkrpg",             // 崩坏: 星穹铁道
            "com.miHoYo.Nap",               // 绝区零
            // 其他国内
            "com.kurogame.haru.hero",       // 战双帕弥什
            "com.kurogame.wutheringwaves",  // 鸣潮
            "com.hypergryph.arknights",     // 明日方舟
            "com.bilibili.azurlane",        // 碧蓝航线
            "com.bilibili.fatego",          // 命运-冠位指定
            "com.bilibili.deadcells.mobile",// 重生细胞
            "com.shenlan.m.reverse1999",    // 重返未来: 1999
            "com.pwrd.hotta.laohu",         // 幻塔 (官服)
            "com.aligames.sgzzlb",          // 三国志·战略版
            "com.aligames.sgzzlb.aligames", // 三国志·战略版 (渠道)
            "com.sofunny.chicken",          // 香肠派对
            "com.sofunny.chickendinnerfirst", // 香肠派对 (先行服)
            "com.minitech.miniworld",       // 迷你世界
            "com.ztgame.bob",               // 球球大作战
            // 国际版
            "com.levelinfinite.sgameGlobal",
            "com.tencent.ig",
            "com.miHoYo.GenshinImpact",
            "com.HoYoverse.hkrpgoversea",
            "com.HoYoverse.Nap",
            "com.kurogame.wutheringwaves.global",
            "com.bluepoch.m.en.reverse1999",
            "com.levelinfinite.hotta.gp",
            "com.zloong.eu.dr.gp",
            "com.netease.racerna",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.teamfighttactics",
            "com.activision.callofduty.shooter",
            "com.epicgames.fortnite",
            "com.supercell.clashofclans",
            "com.supercell.brawlstars",
            "com.blizzard.wtcg.hearthstone",
            "com.blizzard.diablo.immortal",
            "com.innersloth.spacemafia",
            "com.chucklefish.stardewvalley",
            "com.playdigious.deadcells",
            "com.mojang.minecraftpe",
            "com.roblox.client",
            "com.mobile.legends",
            "com.dts.freefireth",
            "com.dts.freefiremax",
            "com.ea.gp.fifamobile",
            "com.konami.pesam"
    ));

    /** 前台 Activity 类名里出现这些片段, 按视频播放场景处理 (名单外的播放器靠它兜底)。 */
    private static final String[] VIDEO_ACTIVITY_HINTS = {
            "Player", "Video", "Cinema", "Playback", "FullScreen", "Landscape"
    };

    /** 这些包名前台时永远不算游戏/视频场景。 */
    private static final Set<String> NEVER_SUPPRESS = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.chenan.flymebobo"
    ));

    private final Context app;
    private final Handler handler;
    private Listener listener;
    private boolean running;
    private boolean immersive;
    private String lastTopPkg;
    // v510 diagnostics (see detect() / refresh()).
    private String lastTopCls;
    private int lastCat = -999;
    private String peekedPkg;
    // v557: 壳界面（桌面/概览）监测的上次顶层（pkg/cls 拼串）。
    private String lastShellTop;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 旋转是最快的场景变化信号, 不等下一轮轮询
            refresh();
        }
    };

    public ImmersiveSceneGuard(Context appContext, Handler handler) {
        this.app = appContext.getApplicationContext() != null
                ? appContext.getApplicationContext() : appContext;
        this.handler = handler;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            app.registerReceiver(configReceiver,
                    new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
        } catch (Throwable ignored) {
        }
        refresh();
        handler.postDelayed(pollTask, POLL_MS);
    }

    public synchronized void stop() {
        running = false;
        handler.removeCallbacks(pollTask);
        try {
            app.unregisterReceiver(configReceiver);
        } catch (Throwable ignored) {
        }
    }

    /** 气泡显示入口调用: true = 当前在游戏/视频场景, 这条先别弹。 */
    public boolean isSuppressed() {
        return immersive;
    }

    // v507: exit debounce — leaving a scene must be stable for
    // this long before it counts. Games flicker out of
    // "immersive" for portrait ads / menus / cutscenes; without
    // the hold, every flicker restored the windows and flushed
    // the queue, and frequently-reposting music notifications
    // visibly re-popped each time (user 2026-11-24). Entering
    // a scene stays instant — hiding fast is the point.
    private static final long EXIT_HOLD_MS = 1600L;
    private long exitCandidateSince = 0L;

    /** 立即重算一次场景, 变化时回调 Listener。可在主线程安全调用。 */
    public synchronized void refresh() {
        peekShellTop();
        boolean now = detect();
        // v510 diagnostics: one line per foreground-app change,
        // so the next detection miss is self-explaining.
        if (lastTopPkg != null && !lastTopPkg.equals(peekedPkg)) {
            peekedPkg = lastTopPkg;
            try {
                FileLog.i("ScenePeek pkg=" + lastTopPkg
                        + " cls=" + lastTopCls
                        + " cat=" + lastCat
                        + " land=" + (app.getResources()
                                .getConfiguration().orientation
                                == Configuration.ORIENTATION_LANDSCAPE));
            } catch (Throwable ignored) {
            }
        }
        if (now == immersive) {
            exitCandidateSince = 0L;
            return;
        }
        if (immersive && !now) {
            long t = android.os.SystemClock.uptimeMillis();
            if (exitCandidateSince == 0L) {
                exitCandidateSince = t;
                return;
            }
            if (t - exitCandidateSince < EXIT_HOLD_MS) {
                return;
            }
            exitCandidateSince = 0L;
        } else {
            exitCandidateSince = 0L;
        }
        immersive = now;
        if (DEBUG_LOG) {
            try {
                FileLog.i("ImmersiveSceneGuard scene -> " + (now ? "immersive(game/video)" : "normal")
                        + " topPkg=" + lastTopPkg);
            } catch (Throwable ignored) {
            }
        }
        if (listener != null) listener.onSceneChanged(now);
    }

    /** v557: 壳界面监测——借 refresh 的 800ms 轮询这条现成
     * 的链，不另起监测。detect() 只在横屏跑、还会被场景判定
     * 短路，最近任务多为竖屏，所以这里单独 peek 一次顶层：
     * 顶层（pkg/cls）变化且新顶层是桌面或最近任务/概览时，
     * 回调监听者收起全屏浮层。拿不准的宁可多收——收浮层
     * 没有副作用。 */
    private void peekShellTop() {
        try {
            ComponentName top = topActivity();
            if (top == null) {
                return;
            }
            String pkg = top.getPackageName();
            String cls = top.getClassName();
            if (pkg == null) {
                return;
            }
            String flat = pkg + "/" + cls;
            if (flat.equals(lastShellTop)) {
                return;
            }
            lastShellTop = flat;
            String clsLow = cls == null ? ""
                    : cls.toLowerCase(java.util.Locale.US);
            boolean shell = clsLow.contains("recents")
                    || clsLow.contains("overview")
                    || isLauncher(pkg);
            if (shell && listener != null) {
                FileLog.i("ShellTop pkg=" + pkg + " cls=" + cls);
                listener.onShellTopShown(pkg, cls);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 识别本体
    // ------------------------------------------------------------------

    private boolean detect() {
        try {
            // 1) 必须是横屏
            Configuration cfg = app.getResources().getConfiguration();
            if (cfg == null || cfg.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                return false;
            }

            // 2) 拿前台包名 / Activity
            ComponentName top = topActivity();
            if (top == null) return false;
            String pkg = top.getPackageName();
            lastTopPkg = pkg;
            if (pkg == null || NEVER_SUPPRESS.contains(pkg)) return false;
            if (isLauncher(pkg)) return false;

            // v510 diagnostics: stash what the guard sees, so a
            // detection miss explains itself in the log (see the
            // ScenePeek line in refresh()).
            String cls = top.getClassName();
            lastTopCls = cls;
            int cat = -999;
            try {
                ApplicationInfo ai = app.getPackageManager()
                        .getApplicationInfo(pkg, 0);
                if (ai != null) cat = ai.category;
            } catch (Throwable ignored) {
            }
            lastCat = cat;

            // 3a) 游戏: 系统分类, 最可靠
            if (cat == ApplicationInfo.CATEGORY_GAME) return true;

            // 3a1) v510: 游戏包名兜底 (没声明分类的游戏)
            if (GAME_PACKAGES.contains(pkg)) return true;

            // 3a1b) v511: 线上游戏名单 (games.txt, 见 GamePackages)
            if (GamePackages.contains(pkg)) return true;

            // 3a2) 游戏: 引擎 Activity 特征兜底 (没声明分类的游戏靠这行:
            // Unity/Unreal/Cocos/Godot 的主 Activity 类名都带这些特征)
            if (cls != null && (cls.contains("UnityPlayer") || cls.contains("GameActivity") || cls.contains("Cocos2dx") || cls.contains("Unreal") || cls.contains("Godot"))) return true;

            // 3b) 视频: 名单命中
            if (VIDEO_PACKAGES.contains(pkg)) return true;

            // 3c) 视频: Activity 特征兜底 (名单外的播放器 / 浏览器全屏播放等)
            if (cls != null) {
                for (String hint : VIDEO_ACTIVITY_HINTS) {
                    if (cls.contains(hint)) return true;
                }
            }
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    private ComponentName topActivity() {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            // 模块运行在 SystemUI 进程, 有权限拿到真实前台任务;
            // 普通应用进程里 getRunningTasks 只能拿到自己, 这也是本类必须放在
            // SystemUI 侧 (BubbleWindow 所在进程) 的原因。
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            return tasks.get(0).topActivity;
        } catch (Throwable th) {
            return null;
        }
    }

    private boolean isLauncher(String pkg) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ComponentName resolved = home.resolveActivity(app.getPackageManager());
            return resolved != null && pkg.equals(resolved.getPackageName());
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean isGameApp(String pkg) {
        try {
            if (Build.VERSION.SDK_INT < 26) return false;
            ApplicationInfo info = app.getPackageManager().getApplicationInfo(pkg, 0);
            return info != null && info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (Throwable th) {
            return false;
        }
    }
}
