package com.chenan.flymebobo;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * flymebobo 2.0 — 设置本地备份（v464 建，v469 修）。
 *
 * 用户 2026-11-23 “保证每次更新都不需要再重新设置一次” /
 * “能不能写入本地”：开关在 App 里显示记住了（prefs 还在），
 * 但实际功能没记住——SystemUI 那边的静态状态没跟上，或者
 * 卸载重装把 prefs 清掉了。所以设置除了写 SharedPreferences，
 * 再另外写一份到本地文件，两边都记。
 *
 * v469 根因（用户 2026-11-23 “写入本地配置没有生效”“更新安装
 * 还是要重新设置”）：v464 只写了 /data/local/tmp/，但这条路在
 * 用户手机上是 SELinux-blocked 的——FileLog 的注释早就记着：
 * “/data/local/tmp is SELinux-blocked”，日志文件在那条路就从来
 * 没出现过。SettingsStore 写过去静默失败（catch 吃掉），文件
 * 根本没建起来，恢复当然没生效。修法跟 FileLog 一个路子：多
 * 位置写、按顺序读。主位置改成 App 专属外部文件目录
 * （/sdcard/Android/data/com.chenan.flymebobo/files/）。
 * v473 再换位置（用户 2026-11-23 “覆盖安装”“你换个位置写文件”
 * “/sdcard/flymebobo”“自动创建这个文件夹保存配置”）：用户是
 * 覆盖安装（不卸载），文件没被删，但 SystemUI 读不到 App 专属
 * 目录（分区存储限制，别的进程进不去），所以“写了但不生效、
 * 每次安装都重置”。主位置改成 /sdcard/flymebobo/（公开目录，
 * 卸载不删，SystemUI 也能读；App 自动建文件夹）。
 * v475 su 兜底（用户 2026-11-23 “已经没有效果”“没有文件”）：
 * 普通 FileWriter 写 /sdcard/flymebobo/ 被 Android 11+ 分区存储
 * 拦住，文件根本没建起来。普通写失败就走 su（用户是 root 机，
 * App 里已有 su）：先写缓存里的临时文件，再 su 拷过去并 chmod
 * 644，SystemUI 和文件管理器都能读。
 *
 * 文件格式：一行一个 `f.<key>=<true|false>`（功能开关）或
 * `a.<key>=<int>`（外观值）。解析时跳过坏行，不炸。
 */
public class SettingsStore {
    /** v464 的旧位置（SELinux-blocked，大概率写不进去，仍顺手写一份）。 */
    public static final String FILE_TMP =
            "/data/local/tmp/flymebobo_settings.txt";
    /** 备份文件名。 */
    public static final String FILE_NAME = "flymebobo_settings.txt";
    /** v473: 主位置改成 /sdcard/flymebobo/（用户 2026-11-23
     * “/sdcard/flymebobo”“自动创建这个文件夹保存配置”）。
     * 之前放在 App 专属外部目录（/sdcard/Android/data/…）不行：
     * 用户是覆盖安装（不卸载），文件没被删，但 SystemUI 读不到
     * 那个目录（分区存储限制，别的进程进不去 App 专属目录），
     * 所以“写了但不生效、每次安装都重置”。/sdcard/flymebobo/
     * 是公开目录，卸载不删，SystemUI 也能读；App 启动时自动
     * 建这个文件夹（mkdirs），不用用户手动建。 */
    public static final String DIR_PUBLIC = "/sdcard/flymebobo";
    public static final String DIR_PUBLIC_ALT =
            "/storage/emulated/0/flymebobo";
    /** App 专属外部文件目录（兜底位置，v469）。 */
    public static final String EXT_DIR =
            "/sdcard/Android/data/com.chenan.flymebobo/files";
    public static final String EXT_DIR_ALT =
            "/storage/emulated/0/Android/data/com.chenan.flymebobo/files";

    /** 功能开关在文件里的前缀。 */
    public static final String PREFIX_FEATURE = "f.";
    /** 外观值在文件里的前缀。 */
    public static final String PREFIX_APPEARANCE = "a.";
    /** v504 (2.4.2): 按应用气泡规则在文件里的前缀
     * （r.<包名>=never|edge，见 AppRules）。 */
    public static final String PREFIX_RULE = "r.";
    /** v508: 场景例外在文件里的前缀
     * （x.<包名>=break|follow，见 SceneExceptions）。 */
    public static final String PREFIX_SCENE_EXC = "x.";
    /** v511: 线上游戏名单在文件里的前缀
     * （g.<包名>=1，见 GamePackages）。 */
    public static final String PREFIX_GAME = "g.";
    /** v525: 临时静默状态/设置在文件里的前缀
     * （t.until=<ms> 等，见 TempSilence）。 */
    public static final String PREFIX_TEMPSILENCE = "t.";
    /** v535: 按应用提示音规则在文件里的前缀
     * （s.<包名>=mute|<音效id>，见 SoundRules）。 */
    public static final String PREFIX_SOUND = "s.";

    /** 读的候选位置（按顺序，第一个有内容的胜出）。 */
    private static String[] readPaths() {
        return new String[]{
                DIR_PUBLIC + "/" + FILE_NAME,
                DIR_PUBLIC_ALT + "/" + FILE_NAME,
                EXT_DIR + "/" + FILE_NAME,
                EXT_DIR_ALT + "/" + FILE_NAME,
                FILE_TMP,
        };
    }

    /** 把两份 prefs 的全部内容写进本地文件（App 侧调用）。
     * 主位置是 /sdcard/flymebobo/（v473，用户指定；自动建文件夹，
     * 公开目录，卸载不删，SystemUI 也能读）。App 专属外部目录
     * 和 /data/local/tmp 顺手也写一份（能写就写，写不上拉倒，
     * 不报错）。 */
    public static void saveAll(Context appCtx) {
        if (appCtx == null) {
            return;
        }
        String content = buildContent(appCtx);
        if (content == null) {
            return;
        }
        // 主位置：/sdcard/flymebobo/（自动建文件夹）。
        // v475: 普通写失败就走 su（用户 2026-11-23 “已经没有效果”——
        // v473 的 /sdcard/flymebobo/ 写不进去，Android 11+ 分区存储
        // 拦住了 App 直接写 /sdcard/ 下的自定义目录；用户是 root
        // 机，App 里已有 su（重启 SystemUI 按钮就走 su），用 su
        // 建文件夹+拷文件一定能写进去）。
        try {
            File dir = new File(DIR_PUBLIC);
            dir.mkdirs();
            File dest = new File(dir, FILE_NAME);
            boolean ok = writeFile(dest, content);
            if (!ok) {
                writeFileViaSu(appCtx, dest, content);
            }
        } catch (Throwable ignored) {
        }
        // 兜底位置 1：App 专属外部文件目录。
        try {
            File extDir = appCtx.getExternalFilesDir(null);
            if (extDir != null) {
                extDir.mkdirs();
                writeFile(new File(extDir, FILE_NAME), content);
            }
        } catch (Throwable ignored) {
        }
        // 兜底位置 2：/data/local/tmp（能写就写，写不上拉倒）。
        try {
            File tmp = new File(FILE_TMP);
            File parent = tmp.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            writeFile(tmp, content);
        } catch (Throwable ignored) {
        }
    }

    private static String buildContent(Context appCtx) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# flymebobo settings backup (v469)\n");
            SharedPreferences fPrefs = appCtx.getSharedPreferences(
                    "flymebobo_features", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : fPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                // v482: musicLed_taps 是 int 计数器（反方向的钟点了
                // 几下），不是布尔开关——不能跟开关一起备成 f.*。
                // 不然下次 restoreToPrefs 会把它当布尔读回来
                // （putBoolean），再 getInt 就 ClassCastException，
                // App 一打开就闪退（用户 2026-11-23 “打开了之后
                // 关上，然后 APP 就会进不去”就是这个：开关开→关，
                // saveAll 把 f.musicLed_taps=10 写进备份，下次打开
                // restore 把它写成布尔，计数器一读就崩）。
                if ("musicLed_taps".equals(e.getKey())) {
                    continue;
                }
                sb.append(PREFIX_FEATURE).append(e.getKey()).append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            SharedPreferences aPrefs = appCtx.getSharedPreferences(
                    Appearance.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : aPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(PREFIX_APPEARANCE).append(e.getKey()).append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            // v504 (2.4.2): 按应用规则一并备份（r.<包名>=never|edge）。
            SharedPreferences rPrefs = appCtx.getSharedPreferences(
                    AppRules.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : rPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(PREFIX_RULE).append(e.getKey()).append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            // v508: 场景例外一并备份（x.<包名>=break|follow）。
            SharedPreferences xPrefs = appCtx.getSharedPreferences(
                    SceneExceptions.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : xPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(PREFIX_SCENE_EXC).append(e.getKey())
                        .append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            // v511: 线上游戏名单一并备份（g.<包名>=1）。
            SharedPreferences gPrefs = appCtx.getSharedPreferences(
                    GamePackages.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : gPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(PREFIX_GAME).append(e.getKey())
                        .append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            // v525: 临时静默一并备份（t.until=<ms>、t.minutes=60、
            // t.linkDnd=true…，见 TempSilence）。
            SharedPreferences tPrefs = appCtx.getSharedPreferences(
                    TempSilence.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : tPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(PREFIX_TEMPSILENCE).append(e.getKey())
                        .append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            // v535: 按应用提示音规则一并备份。prefs 键是
            // "soundrule_<包名>"，落盘剥掉前缀写成 s.<包名>=<值>
            // （同 r./x. 的口径：前缀后面就是包名）。
            SharedPreferences sPrefs = appCtx.getSharedPreferences(
                    SoundRules.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sPrefs.getAll().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                if (!e.getKey().startsWith(
                        SoundRules.KEY_PREFIX)) {
                    continue;
                }
                String pkg = e.getKey().substring(
                        SoundRules.KEY_PREFIX.length());
                if (pkg.isEmpty()) {
                    continue;
                }
                sb.append(PREFIX_SOUND).append(pkg).append('=')
                        .append(e.getValue().toString()).append('\n');
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeFile(File file, String content) {
        FileWriter w = null;
        try {
            w = new FileWriter(file, false);
            w.write(content);
            w.close();
            w = null;
            return file.exists() && file.length() > 0;
        } catch (Throwable ignored) {
            // 写不上拉倒，不影响主流程，prefs 本身还在。
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** v475: 用 su 把备份写到 /sdcard/flymebobo/（用户 2026-11-23
     * “没有文件”——普通 FileWriter 写不进去，Android 11+ 分区存储
     * 拦住了 App 直接写 /sdcard/ 下的自定义目录；用户是 root 机，
     * App 里已有 su（重启 SystemUI 按钮就走 su）。先把内容写到
     * App 缓存里的临时文件，再 su 拷过去并改成 644（SystemUI 和
     * 文件管理器都能读）。 */
    private static void writeFileViaSu(Context appCtx, File dest,
            String content) {
        try {
            File tmp = new File(appCtx.getCacheDir(),
                    "flymebobo_settings.tmp");
            if (!writeFile(tmp, content)) {
                return;
            }
            String destPath = dest.getAbsolutePath();
            String tmpPath = tmp.getAbsolutePath();
            String cmd = "mkdir -p /sdcard/flymebobo && cp "
                    + tmpPath + " " + destPath
                    + " && chmod 644 " + destPath;
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", cmd});
            p.waitFor();
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // su 也写不上就拉倒，prefs 本身还在。
        }
    }

    /** 读本地文件，返回全部 `key=value`（App 和 SystemUI 都能调）。
     * 按顺序试三个位置，第一个有内容的胜出。 */
    public static Map<String, String> readAll() {
        for (String path : readPaths()) {
            Map<String, String> map = readOne(path);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return new HashMap<>();
    }

    private static Map<String, String> readOne(String path) {
        Map<String, String> map = new HashMap<>();
        try {
            File file = new File(path);
            if (!file.exists()) {
                return map;
            }
            BufferedReader r = new BufferedReader(new FileReader(file));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if (!k.isEmpty()) {
                        map.put(k, v);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
            // 读不到就当没有文件，走 prefs。
        }
        return map;
    }

    /** 把本地文件的内容恢复进两份 prefs（App 打开时调用）。
     * 文件没有就不做事；文件里有的键才写，没的键不动。
     * 返回 true = 恢复了至少一个值（调用方据此给 SystemUI 发
     * 一次重载广播，让恢复的值立刻生效——v471 用户定：
     * “改成打开自动加载一次”。文件写了但重启后不生效，就是
     * 因为 SystemUI 在 App 打开之前已经按旧 prefs 载完了，
     * App 只恢复了 prefs 没推给 SystemUI；打开 App 时推一次
     * 重载，SystemUI 重新读一遍，开关和功能就对上了）。 */
    public static boolean restoreToPrefs(Context appCtx) {
        if (appCtx == null) {
            return false;
        }
        try {
            Map<String, String> map = readAll();
            if (map.isEmpty()) {
                return false;
            }
            SharedPreferences.Editor fEd = appCtx.getSharedPreferences(
                    "flymebobo_features", Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor aEd = appCtx.getSharedPreferences(
                    Appearance.PREFS, Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor rEd = appCtx.getSharedPreferences(
                    AppRules.PREFS, Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor xEd = appCtx.getSharedPreferences(
                    SceneExceptions.PREFS, Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor gEd = appCtx.getSharedPreferences(
                    GamePackages.PREFS, Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor tEd = appCtx.getSharedPreferences(
                    TempSilence.PREFS, Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor sEd = appCtx.getSharedPreferences(
                    SoundRules.PREFS, Context.MODE_PRIVATE).edit();
            boolean fChanged = false;
            boolean aChanged = false;
            boolean rChanged = false;
            boolean xChanged = false;
            boolean gChanged = false;
            boolean tChanged = false;
            boolean sChanged = false;
            for (Map.Entry<String, String> e : map.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k.startsWith(PREFIX_FEATURE)) {
                    String key = k.substring(PREFIX_FEATURE.length());
                    // v482: musicLed_taps 是 int 计数器，不是布尔开关。
                    // 旧备份文件里可能已经有 f.musicLed_taps=10，
                    // 不能把它当布尔恢复（会把 int 写成布尔，下次
                    // getInt 就 ClassCastException 闪退）。直接跳过。
                    if ("musicLed_taps".equals(key)) {
                        continue;
                    }
                    // v535: 提示音的三个键是字符串（当前音效 id、
                    // 自定义文件名/公开路径），必须按字符串恢复
                    // ——混进布尔恢复的话，下次 getString 直接
                    // ClassCastException（v482 同款坑）。
                    // v543: recallTtlMin（回看保留时长，分钟数）
                    // 也是字符串存的，同理按字符串恢复。
                    // v555: filterWords（关键词过滤词表，换行分隔）
                    // 同样是字符串，同理按字符串恢复。
                    if ("bubbleSoundId".equals(key)
                            || "bubbleCustomName".equals(key)
                            || "bubbleCustomPath".equals(key)
                            || "recallTtlMin".equals(key)
                            || "filterWords".equals(key)
                            || "lightDotPos".equals(key)
                            || "lightPanelStyle".equals(key)) {
                        fEd.putString(key, v);
                        fChanged = true;
                        continue;
                    }
                    fEd.putBoolean(key, "true".equalsIgnoreCase(v));
                    fChanged = true;
                } else if (k.startsWith(PREFIX_APPEARANCE)) {
                    String key = k.substring(PREFIX_APPEARANCE.length());
                    try {
                        aEd.putInt(key, Integer.parseInt(v));
                        aChanged = true;
                    } catch (NumberFormatException ignored) {
                        // 坏行跳过。
                    }
                } else if (k.startsWith(PREFIX_RULE)) {
                    // v504 (2.4.2): 按应用规则是字符串，必须按
                    // 字符串恢复，不能混进 f.* 的布尔恢复里
                    // （v482 musicLed_taps 同款坑）。
                    String key = k.substring(PREFIX_RULE.length());
                    if (!key.isEmpty()
                            && AppRules.parse(v) != AppRules.DEFAULT) {
                        rEd.putString(key, v);
                        rChanged = true;
                    }
                } else if (k.startsWith(PREFIX_SCENE_EXC)) {
                    // v508: 场景例外也是字符串；break / follow
                    // 都是有效存值（follow 是用户对默认突破应用
                    // 的明确改判，不能丢）。
                    String key = k.substring(
                            PREFIX_SCENE_EXC.length());
                    if (!key.isEmpty()
                            && SceneExceptions.parse(v) >= 0) {
                        xEd.putString(key, v);
                        xChanged = true;
                    }
                } else if (k.startsWith(PREFIX_GAME)) {
                    // v511: 线上游戏名单，值恒为 "1"。
                    String key = k.substring(
                            PREFIX_GAME.length());
                    if (!key.isEmpty()) {
                        gEd.putString(key, "1");
                        gChanged = true;
                    }
                } else if (k.startsWith(PREFIX_TEMPSILENCE)) {
                    // v525: 临时静默——混合类型（until 是 long、
                    // minutes/dndFilter 是 int、其余是 boolean），
                    // 必须按 key 分型恢复，不能混进 f.* 的布尔
                    // 恢复里（v482 同款坑）。
                    String key = k.substring(
                            PREFIX_TEMPSILENCE.length());
                    try {
                        if (TempSilence.KEY_UNTIL.equals(key)) {
                            tEd.putLong(key, Long.parseLong(v));
                            tChanged = true;
                        } else if (TempSilence.KEY_MINUTES
                                .equals(key)
                                || TempSilence.KEY_DND_FILTER
                                        .equals(key)) {
                            tEd.putInt(key, Integer.parseInt(v));
                            tChanged = true;
                        } else if (TempSilence.KEY_LINK_DND
                                .equals(key)
                                || TempSilence.KEY_CALL_BREAK
                                        .equals(key)
                                || TempSilence.KEY_DND_BY_US
                                        .equals(key)) {
                            tEd.putBoolean(key,
                                    "true".equalsIgnoreCase(v));
                            tChanged = true;
                        }
                    } catch (NumberFormatException ignored) {
                        // 坏行跳过。
                    }
                } else if (k.startsWith(PREFIX_SOUND)) {
                    // v535: 按应用提示音规则是字符串（v538:
                    // follow / none / mute（旧）或音效 id），
                    // 按字符串恢复进专用 prefs，键补回
                    // "soundrule_" 前缀；值不合法就跳过。
                    String pkg = k.substring(
                            PREFIX_SOUND.length());
                    String sv = SoundRules.parseValue(v);
                    if (!pkg.isEmpty() && sv != null) {
                        sEd.putString(
                                SoundRules.KEY_PREFIX + pkg, sv);
                        sChanged = true;
                    }
                }
            }
            if (fChanged) {
                fEd.commit();
            }
            if (aChanged) {
                aEd.commit();
            }
            if (rChanged) {
                rEd.commit();
            }
            if (xChanged) {
                xEd.commit();
            }
            if (gChanged) {
                gEd.commit();
            }
            if (tChanged) {
                tEd.commit();
            }
            if (sChanged) {
                sEd.commit();
            }
            return fChanged || aChanged || rChanged || xChanged
                    || gChanged || tChanged || sChanged;
        } catch (Throwable ignored) {
            // 恢复失败不影响主流程。
            return false;
        }
    }

    /** 从文件里取一个功能开关（SystemUI 的 load() 用）。
     * 文件里没有返回 null（调用方再读 prefs）。 */
    public static Boolean fileFeature(String key) {
        try {
            String v = readAll().get(PREFIX_FEATURE + key);
            if (v == null) {
                return null;
            }
            return "true".equalsIgnoreCase(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从文件里取一个外观值（SystemUI 的 load() 用）。
     * 文件里没有或解析失败返回 null（调用方再读 prefs）。 */
    public static Integer fileAppearance(String key) {
        try {
            String v = readAll().get(PREFIX_APPEARANCE + key);
            if (v == null) {
                return null;
            }
            return Integer.parseInt(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** v545: 从文件里取一个功能字符串的原始值（SystemUI 的
     * load() 用）——回看时长、音效 id、自定义音效路径/名这类
     * 字符串开关跟布尔开关同路：文件里没有返回 null（调用方
     * 再读 prefs）。 */
    public static String fileFeatureString(String key) {
        try {
            return readAll().get(PREFIX_FEATURE + key);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
