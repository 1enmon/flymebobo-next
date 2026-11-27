package com.chenan.flymebobo;

import android.app.Application;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

// v565: 轻气泡开启时，Flyme 控制中心（下拉面板）里的系统
// 音乐卡也要藏掉——音乐只活在收纳面板的置顶卡里。
//
// v564 只在通知集合层藏了音乐通知，但控制中心的音乐卡是
// 按活跃 MediaSession 渲染的、不走通知，所以藏通知藏不住
// 它（wei 实测截图）。本钩子在 SystemUI 的媒体数据层下手：
// MediaDataManager.onMediaDataLoaded 是 AOSP 媒体管线的总
// 入口，QS 媒体轮播（含 Flyme 控制中心的音乐卡）的数据都
// 从这里分发。轻气泡开时把 loaded 回调吞掉，轮播没有播放
// 者可绑，音乐卡就不出现/不占位；快捷开关区不受影响。
// 经典模式完全不碰（每次调用先判 lightMode）。
//
// 本模块自己的音乐管线（NotificationService 在 App 进程
// 里读 MediaSession、广播给 BubbleWindow）与 SystemUI 的
// MediaDataManager 无关，吞掉这里不影响收纳面板音乐卡。
//
// Flyme 若改了类名/管线，第一版先探针：候选类逐个打
// hit/miss 进 FileLog（Light music qs probe），数据层和
// 轮播/宿主类都探，日志回来就能定下一版挂哪。
public final class LightQsMediaSuppress {
    private static final String TAG = "flymebobo";

    private static final String[] DATA_MANAGERS = {
            "com.android.systemui.media.controls.pipeline"
                    + ".MediaDataManager",
            "com.android.systemui.media.MediaDataManager",
    };
    private static final String[] PROBE_ONLY = {
            "com.android.systemui.media.controls.ui.controller"
                    + ".MediaCarouselController",
            "com.android.systemui.media.controls.ui"
                    + ".MediaCarouselController",
            "com.android.systemui.media.controls.ui"
                    + ".MediaControlPanel",
            "com.android.systemui.qs.QSMediaHost",
            "com.android.systemui.qs.media.QSMediaHost",
    };

    private final XposedInterface bridge;
    private final Application app;
    private boolean done;
    private String lastBlockedKey;

    public LightQsMediaSuppress(XposedInterface xposed,
            Application app) {
        this.bridge = xposed;
        this.app = app;
    }

    public void install() {
        if (done) {
            return;
        }
        ClassLoader cl = app.getClassLoader();
        for (String name : PROBE_ONLY) {
            probe(cl, name);
        }
        boolean hooked = false;
        for (String name : DATA_MANAGERS) {
            Class<?> cls = probe(cl, name);
            if (cls == null) {
                continue;
            }
            int count = 0;
            try {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!"onMediaDataLoaded".equals(
                            m.getName())) {
                        continue;
                    }
                    if (attach(m)) {
                        count++;
                    }
                }
            } catch (Throwable th) {
                FileLog.i("Light music qs probe scan fail "
                        + name + ": " + th);
            }
            if (count > 0) {
                FileLog.i("Light music qs hooked " + name
                        + " onMediaDataLoaded x" + count);
                hooked = true;
                break;
            }
        }
        if (!hooked) {
            // 没挂上任何数据入口：只留探针日志，别硬挂错类。
            FileLog.i("Light music qs probe none hooked");
        }
        done = true;
    }

    private Class<?> probe(ClassLoader cl, String name) {
        try {
            Class<?> cls = Class.forName(name, false, cl);
            FileLog.i("Light music qs probe hit " + name);
            return cls;
        } catch (Throwable th) {
            FileLog.i("Light music qs probe miss " + name);
            return null;
        }
    }

    private boolean attach(final Method target) {
        try {
            bridge.hook(target).intercept(
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            if (FeatureFlags.lightMode) {
                                Object a0 = chain.getArg(0);
                                String key = a0 instanceof String
                                        ? (String) a0 : "?";
                                if (!key.equals(lastBlockedKey)) {
                                    lastBlockedKey = key;
                                    FileLog.i("Light music qs"
                                            + " blocked key="
                                            + key);
                                }
                                return null;
                            }
                            return chain.proceed();
                        }
                    });
            return true;
        } catch (Throwable th) {
            FileLog.i("Light music qs hook fail " + target
                    + ": " + th);
            return false;
        }
    }

    private void log(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
