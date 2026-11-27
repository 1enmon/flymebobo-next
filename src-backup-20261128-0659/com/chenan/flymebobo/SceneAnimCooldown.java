package com.chenan.flymebobo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

// v541: 场景模式动画 30 分钟内最多播一次（wei 定）。开启与
// 退出合并计次，共用一个「最近播放时间」；触发点有三处且跨
// 两个进程——SystemUI 里的真实进/出场景（BubbleWindow）和
// App 设置里拨场景模式开关（MainActivity）。共享办法跟
// FileLog 一个路子：同一个小文件写多个路径，读时取各路径
// 的最大值，写时尽力全写；再加进程内静态值兜底（同一进程
// 里进/出合并计次不依赖文件成败）。App 侧播放后还会发
// ACTION_SCENE_ANIM 广播把时间戳推给 SystemUI，双保险。
// SystemUI 重启后静态值清零，但文件还在，冷却不丢。
// 只有动画确实开始播放才 markPlayed；因息屏/锁屏/开关关
// 等既有规则跳过的不消耗机会。静默动画不走这里。
public class SceneAnimCooldown {
    public static final long COOLDOWN_MS = 30L * 60L * 1000L;

    private static final String[] PATHS = {
            "/sdcard/flymebobo/scene_anim_last.txt",
            "/data/local/tmp/flymebobo_scene_anim_last.txt",
            "/sdcard/Android/data/com.chenan.flymebobo/files/"
                    + "scene_anim_last.txt",
            "/sdcard/flymebobo_scene_anim_last.txt",
    };

    private static long sMemLast = 0;

    /** 最近一次场景动画开始播放的时间（各来源取最大）。 */
    public static synchronized long lastPlayedMs() {
        long best = sMemLast;
        for (String path : PATHS) {
            long v = readOne(path);
            if (v > best) {
                best = v;
            }
        }
        if (best > sMemLast) {
            sMemLast = best;
        }
        return best;
    }

    /** 还要等多久才能再播（<=0 表示可以播）。 */
    public static long remainingMs() {
        long last = lastPlayedMs();
        if (last <= 0) {
            return 0;
        }
        return COOLDOWN_MS - (System.currentTimeMillis() - last);
    }

    /** 动画确实开始播放时调用：记下现在，返回时间戳。 */
    public static synchronized long markPlayed() {
        long now = System.currentTimeMillis();
        if (now > sMemLast) {
            sMemLast = now;
        }
        writeAll(sMemLast);
        return sMemLast;
    }

    /** 另一进程播过了（广播/文件同步进来）：合并时间戳。 */
    public static synchronized void noteExternalPlayed(long ts) {
        if (ts > sMemLast) {
            sMemLast = ts;
            writeAll(ts);
        }
    }

    private static long readOne(String path) {
        BufferedReader r = null;
        try {
            File f = new File(path);
            if (!f.exists()) {
                return 0;
            }
            r = new BufferedReader(new FileReader(f));
            String line = r.readLine();
            if (line == null) {
                return 0;
            }
            return Long.parseLong(line.trim());
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeAll(long ts) {
        for (String path : PATHS) {
            FileWriter w = null;
            try {
                File f = new File(path);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                w = new FileWriter(f, false);
                w.write(String.valueOf(ts));
                w.flush();
            } catch (Throwable ignored) {
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }
}
