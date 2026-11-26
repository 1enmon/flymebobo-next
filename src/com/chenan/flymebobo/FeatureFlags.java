package com.chenan.flymebobo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Feature switches for the module APP (v259).
 *
 * The APP (MainActivity) stores each switch in its own
 * SharedPreferences ("flymebobo_features") and broadcasts
 * ACTION_FEATURE_TOGGLE when one flips. This class lives in the
 * SystemUI process: it holds the live flags, reads the initial
 * state from the APP's prefs on start, and updates a flag when a
 * toggle broadcast arrives. BubbleWindow.popCapsule checks these
 * flags before popping anything, so a flipped switch takes effect
 * immediately with no SystemUI restart.
 *
 * All flags default to true: if the prefs cannot be read, every
 * feature keeps working exactly as before.
 */
public class FeatureFlags {

    public static volatile boolean notif = true;
    public static volatile boolean music = true;
    public static volatile boolean dl = true;
    public static volatile boolean code = true;
    public static volatile boolean live = true;
    public static volatile boolean card = true;
    public static volatile boolean scroll = true;
    public static volatile boolean miniwindow = true;
    public static volatile boolean blankDissipate = true;
    // v457: 自动销毁——“自动消散”的子开关。默认关。开着时，点
    // 空白处胶囊消散，系统通知也一起撤销（从通知栏消失）；关
    // 着时只消散胶囊，通知还留在通知栏里。音乐通知不撤销——
    // 撤销会停掉音乐（v276 同款）。
    public static volatile boolean autoDestroy = false;
    public static volatile boolean musicCard = true;
    // v334: QQ音乐 Flyme 版免费听（自动补时长到 4 小时）。
    // v345: 开关已去掉，永远打开。
    public static volatile boolean qqFree = true;
    // v357: 大卡片模式——通知直接弹卡片，不经过小胶囊。
    // 默认关（保持原来的胶囊行为）；开着时 popCapsule 里直接
    // 调 openCard。来电不走卡片（openCard 本来就 return）。
    public static volatile boolean bigCard = false;
    // v376: 来电胶囊放右边——开着时来电胶囊不在左贴边胶囊堆里，
    // 而是单独放右手边、屏幕中间偏下一点，滑动方向镜像。
    // 默认关（和其它胶囊一样在左边）。
    public static volatile boolean callRight = false;
    // v443: 模拟来电（测试用）——开着时弹一颗假来电胶囊，
    // 让用户看着调来电高度。默认关；只响应开关翻动，
    // SystemUI 启动时不自动弹（BubbleWindow 里 simCallShowing
    // 默认 false，不读这个值来自动弹）。
    public static volatile boolean simCall = false;
    // v459: 模拟音乐（测试用）——和模拟来电一个路子。开着时弹一颗
    // 假音乐胶囊（晴天 - 周杰伦），让用户看着调音乐胶囊/卡片
    // 大小；点它展开播放器卡片看卡片大小。默认关；只响应开关翻动。
    public static volatile boolean simMusic = false;
    // v459: 扇子自动变淡——扇子出现后 5 秒没点，自动变半透明。
    // 默认开；只响应开关翻动，SystemUI 启动时不自动变淡（扇子
    // 出现时才开始计时）。
    public static volatile boolean fanAutoDim = true;
    // v465: 胶囊自动取色——开着：胶囊底色取 App 图标的主色
    // （半透明 80%）；关着：全部用默认白色 + 黑色描边，不跟
    // 图标走。来电胶囊不受管（本来就是绿/红，不取图标色）。
    // v467: 用户 2026-11-23 “反了”“默认开启”——v466 改成默认
    // 关是反了，默认应该是开。
    public static volatile boolean autoTint = true;
    // v478: 反方向的钟——音乐跑马灯（用户 2026-11-23 定名）。
    // 放音乐时屏幕边缘一圈 LED 灯珠，像 00 年代金立/中天/天语
    // 老按键机机身上的跑马灯：灯珠密密嵌满一圈，追逐、齐闪、
    // 交替、随机闪几个花样轮着来。默认关（用户 2026-11-23
    // “默认关闭”）。只响应开关翻动 + 播放状态；音乐停了就灭。
    public static volatile boolean musicLed = false;
    // v524: 敏感打码——锁屏/录屏/投屏时验证码、取件码只显示
    // 应用名 +「验证码 ••••••」，不露码（用户 2026-11-26
    // 早上定：先做打码，总开关 + 锁屏、录屏/投屏分开关）。
    // 默认全开：隐私功能，开着才是它的本意。
    public static volatile boolean mask = true;
    public static volatile boolean maskLock = true;
    public static volatile boolean maskRecord = true;
    // v526: 普通应用也打码——开启后，敏感场景下普通应用通知
    // 的内容也隐藏（只显示应用名 +「内容已隐藏」）。默认关
    // （用户 2026-11-26 定）：影响面比验证码大得多（聊天内容
    // 也会被藏），得用户自己明确打开才生效。验证码/取件码
    // 不受这个开关管，走它们自己的逻辑。
    public static volatile boolean maskApps = false;
    // v530: 场景模式总开关——游戏/视频全屏时通知排队（场景门）。
    // 默认开（保持一直以来的行为）；关掉后 BubbleWindow 的场景
    // 门整体跳过，全屏里通知照常弹，场景例外名单同时失效。
    public static volatile boolean sceneMode = true;
    // v532: 视频App开关——视频 App（B站/抖音/腾讯视频等）播放
    // 时的播放器通知要不要收成音乐胶囊。音乐识别链路靠的是
    // CATEGORY_TRANSPORT / MediaSession token 这类通用信号，
    // 不分音频视频，所以视频 App 播放目前也会进音乐胶囊。
    // 默认开（与现状一致，升级行为不变）；关掉后视频 App 的
    // 播放不进音乐胶囊，按普通通知走（判别口径见
    // NotifCollectionHook.isVideoApp / NotificationService
    // .isVideoApp 的内置视频包名集合）。
    public static volatile boolean musicVideo = true;
    // v534: 气泡提示音——普通气泡弹出时轻响一声。默认开（wei
    // 明确要的）。只管普通气泡：音乐/下载/实况/导航/来电不响
    // （BubbleWindow.popCapsule 里按类型排除后才调
    // BubbleSound.play）。播放走系统通知音量，系统静音/勿扰
    // 时不响（BubbleSound 里判断后安静跳过）。
    public static volatile boolean bubbleSound = true;
    // v534: 当前音效 id——存 flymebobo_features prefs 的
    // "bubbleSoundId"（字符串），播放时 BubbleSound 按 id 查
    // 音效目录拿素材，不写死路径。默认 "soft"（5 号「轻木」，
    // wei 2026-11-26 试听后改选；最初选的 1 号「啵」是 "pop"）。
    // v535 的音效选择 UI 只改这个 id，不动播放代码。
    public static volatile String bubbleSoundId = "soft";
    // v535: 自定义音效文件的公开副本路径（/sdcard/flymebobo/，
    // SystemUI 能直读；App 选文件时写入，见 MainActivity 提示音卡）
    // 和显示文件名。id 为 "custom" 时 BubbleSound 按这个路径播；
    // 文件丢了/坏了回退默认轻木。都是字符串 prefs（features），
    // SettingsStore 恢复时按字符串回（不能混进布尔恢复——v482
    // musicLed_taps 同款坑：写成布尔后 getString 直接崩）。
    public static volatile String bubbleCustomPath = "";
    public static volatile String bubbleCustomName = "";

    public static void set(String key, boolean enabled) {
        if (key == null) {
            return;
        }
        if ("notif".equals(key)) {
            notif = enabled;
        } else if ("music".equals(key)) {
            music = enabled;
        } else if ("dl".equals(key)) {
            dl = enabled;
        } else if ("code".equals(key)) {
            code = enabled;
        } else if ("live".equals(key)) {
            live = enabled;
        } else if ("card".equals(key)) {
            card = enabled;
        } else if ("scroll".equals(key)) {
            scroll = enabled;
        } else if ("miniwindow".equals(key)) {
            miniwindow = enabled;
        } else if ("blankDissipate".equals(key)) {
            blankDissipate = enabled;
        } else if ("autoDestroy".equals(key)) {
            autoDestroy = enabled;
        } else if ("musicCard".equals(key)) {
            musicCard = enabled;
        } else if ("bigCard".equals(key)) {
            bigCard = enabled;
        } else if ("callRight".equals(key)) {
            callRight = enabled;
        } else if ("simCall".equals(key)) {
            simCall = enabled;
        } else if ("simMusic".equals(key)) {
            simMusic = enabled;
        } else if ("fanAutoDim".equals(key)) {
            fanAutoDim = enabled;
        } else if ("autoTint".equals(key)) {
            autoTint = enabled;
        } else if ("musicLed".equals(key)) {
            musicLed = enabled;
        } else if ("mask".equals(key)) {
            mask = enabled;
        } else if ("maskLock".equals(key)) {
            maskLock = enabled;
        } else if ("maskRecord".equals(key)) {
            maskRecord = enabled;
        } else if ("maskApps".equals(key)) {
            maskApps = enabled;
        } else if ("sceneMode".equals(key)) {
            sceneMode = enabled;
            FileLog.i("SceneMode -> " + (enabled ? "on" : "off"));
        } else if ("musicVideo".equals(key)) {
            musicVideo = enabled;
            FileLog.i("MusicVideo -> " + (enabled ? "on" : "off"));
        } else if ("bubbleSound".equals(key)) {
            bubbleSound = enabled;
            FileLog.i("BubbleSound -> " + (enabled ? "on" : "off"));
        }
        // v345: qqFree 开关已去掉，永远打开，不再接受拨动。
    }

    /** Read the APP's saved switches (best effort; on any failure
     * the flags stay at their defaults, i.e. everything on).
     * v464: 先读本地备份文件（/data/local/tmp/，重装不删），文件
     * 里有值就用文件的，没值才读 prefs——文件是普通文件，
     * SystemUI 直接读，不走 createPackageContext，更新后开关
     * 显示记住了、功能却没记住的问题就出在这层读不到。 */
    public static void load(Context systemUiContext) {
        try {
            Context app = systemUiContext.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences prefs = app.getSharedPreferences(
                    "flymebobo_features", Context.MODE_PRIVATE);
            notif = fileOrPrefsBool("notif", prefs, true);
            music = fileOrPrefsBool("music", prefs, true);
            dl = fileOrPrefsBool("dl", prefs, true);
            code = fileOrPrefsBool("code", prefs, true);
            live = fileOrPrefsBool("live", prefs, true);
            card = fileOrPrefsBool("card", prefs, true);
            scroll = fileOrPrefsBool("scroll", prefs, true);
            miniwindow = fileOrPrefsBool("miniwindow", prefs, true);
            blankDissipate = fileOrPrefsBool("blankDissipate", prefs, true);
            autoDestroy = fileOrPrefsBool("autoDestroy", prefs, false);
            musicCard = fileOrPrefsBool("musicCard", prefs, true);
            // v345: qqFree 开关已去掉，永远打开，不读旧的 prefs 值。
            qqFree = true;
            bigCard = fileOrPrefsBool("bigCard", prefs, false);
            callRight = fileOrPrefsBool("callRight", prefs, false);
            simCall = fileOrPrefsBool("simCall", prefs, false);
            simMusic = fileOrPrefsBool("simMusic", prefs, false);
            fanAutoDim = fileOrPrefsBool("fanAutoDim", prefs, true);
            autoTint = fileOrPrefsBool("autoTint", prefs, true);
            // v478: 反方向的钟——默认关（用户 2026-11-23 “默认关闭”）。
            musicLed = fileOrPrefsBool("musicLed", prefs, false);
            // v524: 敏感打码——总开关 + 锁屏、录屏/投屏分开关，默认全开。
            mask = fileOrPrefsBool("mask", prefs, true);
            maskLock = fileOrPrefsBool("maskLock", prefs, true);
            maskRecord = fileOrPrefsBool("maskRecord", prefs, true);
            // v526: 普通应用也打码——默认关。
            maskApps = fileOrPrefsBool("maskApps", prefs, false);
            // v530: 场景模式总开关——默认开。
            sceneMode = fileOrPrefsBool("sceneMode", prefs, true);
            // v532: 视频App进不进音乐胶囊——默认开（与现状一致）。
            musicVideo = fileOrPrefsBool("musicVideo", prefs, true);
            // v534: 气泡提示音——默认开（wei 明确要的）。
            bubbleSound = fileOrPrefsBool("bubbleSound", prefs, true);
            // v534: 当前音效 id（字符串 prefs，默认 "soft" 轻木）。
            // v535: 自定义文件路径/名也是字符串——三个字符串键都
            // 走逐键安全读：某个键万一被旧备份写坏成别的类型，
            // 只丢它自己，不连累整张开关表（load 外层还有兜底
            // try，但那会把所有开关一起重置，能免则免）。
            String sid = stringPref(prefs, "bubbleSoundId", "soft");
            bubbleSoundId = sid.isEmpty() ? "soft" : sid;
            bubbleCustomPath = stringPref(
                    prefs, "bubbleCustomPath", "");
            bubbleCustomName = stringPref(
                    prefs, "bubbleCustomName", "");
        } catch (Throwable ignored) {
            // Defaults (all on) stand.
        }
    }

    /** v535: 逐键安全读字符串 prefs：类型不对（被旧备份写成
     * 布尔等）就回默认值，不抛、不连累其它键。 */
    private static String stringPref(SharedPreferences prefs,
            String key, String def) {
        try {
            String v = prefs.getString(key, def);
            return v == null ? def : v;
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** v464: 文件优先，文件里没有再读 prefs。 */
    private static boolean fileOrPrefsBool(String key,
            SharedPreferences prefs, boolean def) {
        Boolean v = SettingsStore.fileFeature(key);
        if (v != null) {
            return v;
        }
        return prefs.getBoolean(key, def);
    }
}
