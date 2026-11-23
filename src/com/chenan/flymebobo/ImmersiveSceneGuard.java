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

    /** 立即重算一次场景, 变化时回调 Listener。可在主线程安全调用。 */
    public synchronized void refresh() {
        boolean now = detect();
        if (now == immersive) return;
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

            // 3a) 游戏: 系统分类, 最可靠
            if (isGameApp(pkg)) return true;

            // 3a2) 游戏: 引擎 Activity 特征兜底 (没声明分类的游戏靠这行:
            // Unity/Unreal/Cocos/Godot 的主 Activity 类名都带这些特征)
            String cls = top.getClassName();
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
