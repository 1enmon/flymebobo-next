package com.chenan.flymebobo;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v534 建，v535 扩：气泡提示音——普通气泡弹出时轻响一声。
 *
 * 当前音效由 FeatureFlags.bubbleSoundId 决定（存 prefs 的音效
 * id，默认 "soft" = 5 号「轻木」，wei 2026-11-26 试听后选定；
 * 最初选的 1 号「啵」是 "pop"）。v535 起设置页能换音效：
 * 5 个内置（目录见 CATALOG）+ "custom" 自定义文件；还能按
 * 应用单独定（SoundRules）。v538 起按应用默认不响：没设
 * 规则的应用不响，设了 follow / 指定音效才响；只有默认
 * 名单（微信 / QQ / 默认短信应用 / 钉钉 / TIM / 企业
 * 微信）没设也按 follow 响。
 * 播放代码不写死素材路径，一律按 id 查目录。
 *
 * 只由 BubbleWindow.popCapsule 在真弹出段调用，且调用前已按
 * 类型排除音乐/下载/实况/导航/来电——这边只管「怎么响」：
 *
 * - 通道：AudioAttributes USAGE_NOTIFICATION，走系统通知
 *   音量，跟随系统音量大小。
 * - 礼貌：系统静音/振动（ringer mode 不是 NORMAL）不响；
 *   勿扰压制中（interruption filter 不是 ALL）不响。判断
 *   不过就安静跳过，绝不强行出声。
 * - 节流：1 秒内最多响一次，同一波连弹多条只响第一声。
 * - 素材：运行在 SystemUI 进程，assets 先试本进程 context，
 *   不行再 createPackageContext 回模块包读。v536 起不再
 *   openFd 直读：wav 在包里是压缩存储的，AssetFileDescriptor
 *   按偏移喂给 MediaPlayer 播不了（App 内试听同病，wei 实测
 *   v535 点试听弹「试听失败」）——稳妥路子是素材先落 cache
 *   文件、再按文件路径播（见 resolveAssetFile）。自定义文件
 *   放在 /sdcard/flymebobo/（公开目录，SystemUI 能直读，
 *   路径存 prefs 的 bubbleCustomPath）；文件丢了/坏了回退
 *   默认轻木并记日志。读不到就安静跳过（没声音也不能影响
 *   气泡本身）。
 */
public class BubbleSound {

    private static final long THROTTLE_MS = 1000;

    /** 自定义音效的保留 id（素材不是 assets，是用户选的文件）。 */
    public static final String CUSTOM_ID = "custom";

    /** 音效目录：{id, 显示名, assets 文件名, 一句描述}。
     * v535：5 个内置补齐（v534 只有 soft/pop 两行）。
     * 设置页的音效列表直接读这张表渲染。 */
    public static final String[][] CATALOG = {
            {"pop", "气泡啵", "bubble_pop.wav", "短促 · 气泡破裂声"},
            {"ding", "清脆叮", "bubble_ding.wav", "单音铃 · 清亮"},
            {"dingdong", "叮咚", "bubble_dingdong.wav", "双音 · 存在感强"},
            {"drop", "水滴", "bubble_drop.wav", "柔和下滑音"},
            {"soft", "轻木", "bubble_soft.wav", "木质轻敲 · 默认"},
    };
    private static final String DEFAULT_ID = "soft";
    private static final String DEFAULT_ASSET = "bubble_soft.wav";

    /** id 是否合法（内置目录 + custom）。 */
    public static boolean isSoundId(String id) {
        if (id == null) {
            return false;
        }
        if (CUSTOM_ID.equals(id)) {
            return true;
        }
        for (String[] row : CATALOG) {
            if (row[0].equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** id 的显示名（设置页/副标题用）；未知 id 回默认名。 */
    public static String nameFor(String id) {
        if (CUSTOM_ID.equals(id)) {
            return "自定义";
        }
        if (id != null) {
            for (String[] row : CATALOG) {
                if (row[0].equals(id)) {
                    return row[1];
                }
            }
        }
        return "轻木";
    }

    /** 按音效 id 查素材文件名；未知 id 回默认（轻木）。 */
    private static String assetFor(String id) {
        if (id != null) {
            for (String[] row : CATALOG) {
                if (row[0].equals(id)) {
                    return row[2];
                }
            }
        }
        return DEFAULT_ASSET;
    }

    private static long lastPlayAt = 0;
    private static MediaPlayer player;

    /** 无包名入口（保留 v534 签名，走全局音效）。 */
    public static void play(Context ctx) {
        play(ctx, null);
    }

    /**
     * v535：带包名入口——先查按应用规则（SoundRules）。
     * v538 语义反转（wei 拍板：应用默认不响，设了才响）：
     * 按 SoundRules.effectiveValue 解析——有效值是音效 id
     * 就按其 id 响；是 follow 就响全局 bubbleSoundId；
     * 是 null（没设规则的普通应用 / 显式「无」/ 旧 mute）
     * 不响。默认名单应用（微信 / QQ / 默认短信应用 /
     * 钉钉 / TIM / 企业微信）没存规则时 effectiveValue
     * 给 follow，所以照样响。总开关关着一律不响。
     */
    public static synchronized void play(Context ctx, String pkg) {
        if (ctx == null || !FeatureFlags.bubbleSound) {
            return;
        }
        String id = FeatureFlags.bubbleSoundId;
        if (pkg != null) {
            String eff = SoundRules.effectiveValue(ctx, pkg);
            if (eff == null) {
                return;
            }
            if (!SoundRules.VALUE_FOLLOW.equals(eff)) {
                id = eff;
            }
        }
        playId(ctx, id);
    }

    private static void playId(Context ctx, String id) {
        long now = System.currentTimeMillis();
        if (now - lastPlayAt < THROTTLE_MS) {
            return;
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(
                    Context.AUDIO_SERVICE);
            if (am != null && am.getRingerMode()
                    != AudioManager.RINGER_MODE_NORMAL) {
                // 静音 / 振动模式：不响。
                return;
            }
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int filter = nm.getCurrentInterruptionFilter();
                if (filter != NotificationManager.INTERRUPTION_FILTER_ALL
                        && filter
                                != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    // 勿扰压制中（优先/仅闹钟/完全静默）：不响。
                    return;
                }
            }
            boolean played;
            try {
                played = startPlayer(ctx, id);
            } catch (Throwable t) {
                // 损坏的自定义文件 prepare 会抛到这里——同样算
                // 播不成，走下面的回退判断（内置音效播不成则
                // 维持 v534 的安静跳过）。
                played = false;
                FileLog.i("BubbleSound play failed (" + id
                        + "): " + t);
            }
            if (!played && CUSTOM_ID.equals(id)) {
                // 自定义文件丢了/坏了：回退默认轻木，别哑巴。
                FileLog.i("BubbleSound custom unavailable,"
                        + " fallback soft");
                try {
                    startPlayer(ctx, DEFAULT_ID);
                } catch (Throwable ignored) {
                }
            }
            if (!played && !CUSTOM_ID.equals(id)) {
                // 内置音效没播出来也要留痕（异常路径上面已记，
                // 这里记「没抛异常但没播成」的口子）。
                FileLog.i("BubbleSound not played (" + id + ")");
            }
            lastPlayAt = now;
        } catch (Throwable t) {
            // 响不了就安静跳过——提示音是锦上添花，绝不拖累气泡。
            FileLog.i("BubbleSound skip: " + t);
        }
    }

    /** 真正起播：成功返回 true。custom 走文件，其余走 assets。 */
    private static boolean startPlayer(Context ctx, String id)
            throws Exception {
        MediaPlayer mp = new MediaPlayer();
        boolean ok = false;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (CUSTOM_ID.equals(id)) {
                String path = FeatureFlags.bubbleCustomPath;
                if (path == null || path.isEmpty()) {
                    return false;
                }
                File f = new File(path);
                if (!f.isFile() || f.length() <= 0) {
                    return false;
                }
                mp.setDataSource(path);
            } else {
                String asset = assetFor(id);
                File f = resolveAssetFile(ctx, id, asset);
                if (f == null) {
                    return false;
                }
                mp.setDataSource(f.getAbsolutePath());
            }
            if (player != null) {
                try {
                    player.release();
                } catch (Throwable ignored) {
                }
                player = null;
            }
            mp.setOnCompletionListener(
                    new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer p) {
                            try {
                                p.release();
                            } catch (Throwable ignored) {
                            }
                            synchronized (BubbleSound.class) {
                                if (player == p) {
                                    player = null;
                                }
                            }
                        }
                    });
            mp.prepare();
            mp.start();
            player = mp;
            ok = true;
            FileLog.i("BubbleSound play (" + id + ")");
            return true;
        } finally {
            if (!ok) {
                try {
                    mp.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * v536: 内置素材先落 cache 文件再播。wav 在 APK 里是
     * 压缩存储的，openFd + setDataSource(fd, offset, length)
     * 喂不动 MediaPlayer（v535 试听实测失败，本体播放同路
     * 同病，一并换掉）。素材源还是 assets：先试调用方
     * context，不行 createPackageContext 回模块包（同
     * SilenceAnim 读素材的路子）。落盘文件按素材名缓存，
     * 大小对得上就复用。失败一律记 FileLog（带音效 id），
     * 返回 null 让上层安静跳过/走回退。
     */
    private static File resolveAssetFile(Context ctx, String id,
            String asset) {
        InputStream in = null;
        try {
            try {
                in = ctx.getAssets().open(asset);
            } catch (Throwable ignored) {
                // SystemUI 的 context 读不到模块 assets，
                // 走包 context。
            }
            if (in == null) {
                Context appCtx = ctx.createPackageContext(
                        "com.chenan.flymebobo",
                        Context.CONTEXT_IGNORE_SECURITY);
                in = appCtx.getAssets().open(asset);
            }
            File dir = new File(ctx.getCacheDir(),
                    "bubble_sounds");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File out = new File(dir, asset);
            int avail = 0;
            try {
                avail = in.available();
            } catch (Throwable ignored) {
            }
            if (out.isFile() && out.length() > 0
                    && (avail <= 0 || out.length() == avail)) {
                return out;
            }
            File tmp = new File(dir, asset + ".tmp");
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
            if (!tmp.renameTo(out)) {
                return tmp.isFile() && tmp.length() > 0
                        ? tmp : null;
            }
            return out.isFile() && out.length() > 0 ? out : null;
        } catch (Throwable t) {
            FileLog.i("BubbleSound asset resolve failed (" + id
                    + "/" + asset + "): " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
