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
    public static volatile boolean manualRecognize = true;
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
    // v539: 回看胶囊——手动划掉的普通气泡收进屏幕左边缘的
    // 回看栈，点边缘把手逐个原样重弹（BubbleWindow 实现）。
    // 默认关（wei 特别要求：主动开关，用户自己在设置里开启
    // 才有这功能）；关着时不入栈、不出把手。开→关立即清栈
    // 收把手（XposedEntry 把拨动转给 BubbleWindow
    // .onRecallCapsuleDisabled）。
    public static volatile boolean recallCapsule = false;
    // v540: 自动复制验证码——识别出验证码时自动写剪贴板
    // （v244 起的老行为）。默认开（= 现状）；关掉后验证码
    // 只显示、不复制（BubbleWindow.autoCopyVerifyCode 入口
    // 查这个开关）。取件码本来就不自动复制，不归它管。
    // 开关嵌在「验证码与隐私」卡里（parent=code）。
    public static volatile boolean autoCopyCode = true;
    // v546: 长按菜单——长按普通胶囊约 0.4 秒弹出菜单（快捷
    // 回复为核心，菜单项按通知实际能力自适应出现）。默认开
    // （= 新功能即生效；关掉时长按无反应——v454 起长按本来
    // 就无动作）。开关嵌在「通知气泡」卡里（parent=notif）。
    public static volatile boolean longPressMenu = true;
    // v555: 安静规则三件套（wei 定：整合进安静规则卡，不另开
    // 卡片）——连发突破 / @我突破 / 关键词过滤。三个开关都只
    // 影响场景/静默两个排队闸门的决定（关键词过滤影响弹出
    // 前的落点），别的路径一律不碰。
    // 连发突破：同一发送者（pkg + 会话标题）5 分钟内连发到
    // 第 3 条时，本要排队的改为直接弹，并把该发送者已排队
    // 的旧条目一并补弹。默认开。计数在 BubbleWindow（SystemUI
    // 侧滚动时间窗，按通知 key 去重防双投递重复计数）。
    public static volatile boolean burstBreak = true;
    // @我突破：通知标题+正文命中 @ 模式（@我/有人@我/@了你
    // 等，含全角＠）时，本要排队的直接弹。默认开。
    public static volatile boolean mentionBreak = true;
    // 关键词过滤：普通通知标题+正文命中词表任一词 → 不弹，
    // 直接收进扇子（同 AppRules「只贴边」的落点）。默认关
    // （词表是用户自己的口径，得他自己开）。特殊类型（音乐/
    // 下载/实况/导航/来电/验证码/取件码）一律不碰。
    public static volatile boolean keywordFilter = false;
    // 关键词词表：字符串 prefs "filterWords"，换行分隔（词里
    // 可能有标点，换行最稳）。与 recallTtlMin 同路：字符串
    // prefs + 文件镜像通道（fileOrPrefsString，v545 教训），
    // SettingsStore 恢复按字符串回。改词表走 FEATURE_TOGGLE
    // 广播带 words extra 即时生效（XposedEntry）。
    // v564 (2.5.0): 轻气泡设计模式总开关——默认关（= 经典
    // 模式，行为与 2.4.6 完全一致）；开启后普通通知不弹胶囊、
    // 静默收进边缘小签（简洁模式形态），音乐进收纳面板置顶卡、
    // 系统音乐通知隐藏，App 设置页换轻气泡设计。模式值走
    // flymebobo_features 的布尔键 "lightMode"，文件镜像同
    // 其他开关（fileOrPrefsBool）。SystemUI 侧即时生效点在
    // BubbleWindow.onLightModeChanged（XposedEntry 转发）。
    public static volatile boolean lightMode = false;
    // v572: 轻气泡黑 / 白双模式（wei 定）——false = 黑色
    // 模式（深色面板/药丸，现行长相）；true = 白色模式
    // （白底 + 黑色描边，文字转深色）。布尔键
    // "lightPanelWhite"，文件镜像同 lightMode 口径。
    public static volatile boolean lightPanelWhite = false;
    // v578: 轻气泡「音乐卡片」总开关（wei 定名）——默认开：
    // 开了音乐显示在收纳面板的卡片里；关了不显示卡片，
    // 音乐按普通通知收纳。布尔键 "lightMusicCard"，文件
    // 镜像同 lightMode 口径。
    public static volatile boolean lightMusicCard = true;
    // v591: 呼吸灯边缘发光（wei 点名小功能）——默认开：
    // 小签呼吸时屏幕边缘泛起同色光晕，放歌时跟签一
    // 起流转颜色。布尔键 "lightDotGlow"，文件镜像同
    // lightMode 口径。
    public static volatile boolean lightDotGlow = true;
    // v596: 呼吸灯常亮开关（wei 点名）——v597 定正语
    // 义：常亮 = 一直显示（栈空也不隐藏），不是不呼
    // 吸；呼吸与发光都照旧。默认关（栈空隐藏，现行口
    // 径）。布尔键 "lightDotSteady"，文件镜像同
    // lightDotGlow 口径。
    public static volatile boolean lightDotSteady = false;
    // v631: 消息提醒（wei 定版）——消息类应用到达时形
    // 象药丸报「X 给你发消息了」，取代该应用的到达闪；
    // msgRemindContent 子开关决定带不带内容（默认不带）。
    public static volatile boolean msgRemind = true;
    public static volatile boolean msgRemindContent = false;
    // v653: 保留原通知（wei 转达用户需求）——开了之后
    // 被收纳的通知在通知栏也留一份，音乐/下载同理；
    // 默认关，保持现行「收进来就藏」的干净口径。
    public static volatile boolean keepOriginal = false;
    // v655: 今日卡（面板顶部常驻）与边缘滑动呼出。
    public static volatile boolean lightTodayCard = true;
    public static volatile boolean lightSummonSwipe = true;

    // v600: 轻气泡 AI 助手（wei 定：收纳摘要 + 语义打
    // 标进轻气泡，DeepSeek 预设、自带 Key）——总开关
    // 默认关；摘要/打标子开关默认开（依附总开关）。
    // Key 只存 App 进程私有 prefs（flymebobo_ai），
    // 不进本类（SystemUI 不碰 Key）。
    public static volatile boolean aiEnabled = false;
    public static volatile boolean aiSummary = true;
    public static volatile boolean aiTags = true;

    // v601: 信息卡片（本地解析火车票/机票/快递/账单，
    // 只在轻气泡面板成卡）——本地功能无隐私成本，总
    // 开关与自动提醒默认都开。
    public static volatile boolean infoCards = true;
    public static volatile boolean infoRemind = true;
    // v564: 轻气泡小签位置——与已移除的两点同格式同刻度
    // （字符串 prefs + 文件镜像，fileOrPrefsString）：首字
    // L/R=贴左边/右边，尾数 0/1/2=靠上/中间/靠下，默认
    // "L1"（左·中间，试验田通知收纳 v0.6 定版值）。改位置
    // 走 FEATURE_TOGGLE 带 pos extra（设置页）或拖动经
    // NotificationService.ACTION_SET_LIGHT_DOT_POS 落盘回推。
    public static volatile String lightDotPos = "L1";

    // v567: 收纳面板外观（wei 定：面板要能调整大小——
    // 圆角、长宽、透明度、比例都要能调）。五项打包成一个
    // 字符串键 "lightPanelStyle" = "宽dp,可见行数,圆角dp,
    // 不透明%,比例%"，走字符串 prefs + 文件镜像（同
    // lightDotPos 口径）；设置页滑条经 FEATURE_TOGGLE 的
    // style extra 即时同步 SystemUI，下次开面板生效。
    public static volatile String lightPanelStyle =
            "292,5,18,94,100";

    // v595: 呼吸灯颜色（wei 点名）——小签签身底色六色可
    // 选，默认 "blue"（现行蓝）。字符串键 "lightDotColor"，
    // 文件镜像同 lightDotPos 口径；设置页色点经
    // FEATURE_TOGGLE 的 color extra 即时同步。放歌时签
    // 色仍按色相流转（v564 口径），不受此设置影响。
    public static volatile String lightDotColor = "blue";

    /** v595: 呼吸灯颜色解析：坏值回默认蓝。 */
    public static String parseLightDotColor(String raw) {
        if (raw != null) {
            String s = raw.trim().toLowerCase(
                    java.util.Locale.US);
            if ("blue".equals(s) || "green".equals(s)
                    || "purple".equals(s) || "orange".equals(s)
                    || "pink".equals(s) || "cyan".equals(s)) {
                return s;
            }
        }
        return "blue";
    }

    /** v595: 颜色 id → 签身底色（App 进程画色点也用）。 */
    public static int lightDotColorValue(String id) {
        if ("green".equals(id)) {
            return android.graphics.Color.rgb(34, 197, 94);
        }
        if ("purple".equals(id)) {
            return android.graphics.Color.rgb(168, 85, 247);
        }
        if ("orange".equals(id)) {
            return android.graphics.Color.rgb(249, 115, 22);
        }
        if ("pink".equals(id)) {
            return android.graphics.Color.rgb(236, 72, 153);
        }
        if ("cyan".equals(id)) {
            return android.graphics.Color.rgb(6, 182, 212);
        }
        return android.graphics.Color.rgb(59, 130, 246);
    }

    /** v595: 当前呼吸灯底色。 */
    public static int lightDotColorInt() {
        return lightDotColorValue(lightDotColor);
    }

    /** v567: 面板外观解析+钳位，返回 [宽,可见行数,圆角,
     * 不透明%,比例%]；坏值逐项回默认。纯函数，App 进程
     * 读 prefs 时也可直接用。 */
    public static int[] lightPanelStyleValues(String raw) {
        int[] out = {292, 5, 18, 94, 100};
        int[] min = {240, 3, 0, 30, 80};
        int[] max = {360, 8, 36, 100, 120};
        if (raw != null) {
            String[] parts = raw.split(",");
            for (int i = 0; i < out.length && i < parts.length;
                    i++) {
                try {
                    out[i] = Integer.parseInt(parts[i].trim());
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = 0; i < out.length; i++) {
            if (out[i] < min[i]) {
                out[i] = min[i];
            }
            if (out[i] > max[i]) {
                out[i] = max[i];
            }
        }
        return out;
    }

    /** v567: 规范化打包（解析→钳位→重组），落盘/广播
     * 都用这个形态。 */
    public static String parseLightPanelStyle(String raw) {
        int[] v = lightPanelStyleValues(raw);
        return v[0] + "," + v[1] + "," + v[2] + "," + v[3]
                + "," + v[4];
    }

    public static int lightPanelWidthDp() {
        return lightPanelStyleValues(lightPanelStyle)[0];
    }

    public static int lightPanelRows() {
        return lightPanelStyleValues(lightPanelStyle)[1];
    }

    public static int lightPanelRadiusDp() {
        return lightPanelStyleValues(lightPanelStyle)[2];
    }

    public static int lightPanelAlphaPct() {
        return lightPanelStyleValues(lightPanelStyle)[3];
    }

    public static int lightPanelScalePct() {
        return lightPanelStyleValues(lightPanelStyle)[4];
    }

    /** v564: 小签位置解析：坏值回默认 "L1"。 */
    public static String parseLightDotPos(String raw) {
        if (raw != null) {
            String s = raw.trim().toUpperCase(
                    java.util.Locale.US);
            if (s.length() == 2
                    && (s.charAt(0) == 'L' || s.charAt(0) == 'R')
                    && s.charAt(1) >= '0' && s.charAt(1) <= '2') {
                return s;
            }
        }
        return "L1";
    }

    /** v564: 小签贴右边？ */
    public static boolean lightDotRight() {
        return lightDotPos != null && lightDotPos.startsWith("R");
    }

    /** v564: 小签高度档位：0=靠上、1=中间、2=靠下。 */
    public static int lightDotBand() {
        return parseLightDotPos(lightDotPos).charAt(1) - '0';
    }

    /** v564: 档位 → 小签顶边占屏高的比例（与两点共用刻度）。 */
    public static float posYFrac(int band) {
        switch (band) {
            case 0:
                return 0.16f;
            case 2:
                return 0.72f;
            default:
                return 0.46f;
        }
    }

    /** v564: 小签顶边占屏高比例。 */
    public static float lightDotYFrac() {
        return posYFrac(lightDotBand());
    }

    public static final String DEFAULT_FILTER_WORDS =
            "促销\n秒杀\n领券\n限时抢购\n返现\n优惠券";
    public static volatile String filterWords = DEFAULT_FILTER_WORDS;

    /** v555: 词表解析：按换行切，trim、去空、去重，保持顺序。 */
    public static java.util.List<String> filterWordList() {
        return parseFilterWords(filterWords);
    }

    /** v555: 同上，解析任意原始字符串（App 侧读 prefs 用——
     * App 进程里的静态字段没被 load 过，不能直接调
     * filterWordList）。 */
    public static java.util.List<String> parseFilterWords(
            String raw) {
        java.util.ArrayList<String> out =
                new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String w : raw.split("\n")) {
            String t = w == null ? "" : w.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** v555: 词表拼回存储格式（换行分隔）。 */
    public static String joinFilterWords(
            java.util.List<String> words) {
        StringBuilder sb = new StringBuilder();
        if (words != null) {
            for (String w : words) {
                if (w == null) {
                    continue;
                }
                String t = w.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    // v543: 回看保留时长（分钟）——回看栈/边缘把手没人点时
    // 保留多久自动清掉。v539 时写死 3 分钟，wei 要求可自定义。
    // 存 flymebobo_features prefs 的 "recallTtlMin"（字符串，
    // 与 bubbleSoundId 同路：SettingsStore 恢复按字符串回，
    // 不混布尔恢复防 ClassCast）。默认 3（= v539 现状），
    // 范围 1–120，所有写入/读入都过 clampRecallTtlMin。
    public static final int RECALL_TTL_MIN_DEFAULT = 3;
    public static final int RECALL_TTL_MIN_FLOOR = 1;
    public static final int RECALL_TTL_MIN_CEIL = 120;
    public static volatile int recallTtlMin = RECALL_TTL_MIN_DEFAULT;

    /** v543: 回看保留时长夹紧到 1–120 分钟。 */
    public static int clampRecallTtlMin(int minutes) {
        if (minutes < RECALL_TTL_MIN_FLOOR) {
            return RECALL_TTL_MIN_FLOOR;
        }
        if (minutes > RECALL_TTL_MIN_CEIL) {
            return RECALL_TTL_MIN_CEIL;
        }
        return minutes;
    }

    /** v543: 解析 prefs 里的回看保留时长字符串；坏值回默认 3。 */
    public static int parseRecallTtlMin(String raw) {
        if (raw != null) {
            try {
                return clampRecallTtlMin(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return RECALL_TTL_MIN_DEFAULT;
    }

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
        } else if ("manualRecognize".equals(key)) {
            manualRecognize = enabled;
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
        } else if ("recallCapsule".equals(key)) {
            recallCapsule = enabled;
            FileLog.i("RecallCapsule -> " + (enabled ? "on" : "off"));
        } else if ("autoCopyCode".equals(key)) {
            autoCopyCode = enabled;
            FileLog.i("AutoCopyCode -> " + (enabled ? "on" : "off"));
        } else if ("longPressMenu".equals(key)) {
            longPressMenu = enabled;
            FileLog.i("LongPressMenu -> " + (enabled ? "on" : "off"));
        } else if ("burstBreak".equals(key)) {
            burstBreak = enabled;
            FileLog.i("BurstBreak -> " + (enabled ? "on" : "off"));
        } else if ("mentionBreak".equals(key)) {
            mentionBreak = enabled;
            FileLog.i("MentionBreak -> " + (enabled ? "on" : "off"));
        } else if ("keywordFilter".equals(key)) {
            keywordFilter = enabled;
            FileLog.i("KeywordFilter -> " + (enabled ? "on" : "off"));
        } else if ("lightMode".equals(key)) {
            lightMode = enabled;
            FileLog.i("Light mode -> " + (enabled ? "on" : "off"));
        } else if ("lightPanelWhite".equals(key)) {
            lightPanelWhite = enabled;
            FileLog.i("Light panelWhite -> "
                    + (enabled ? "on" : "off"));
        } else if ("lightMusicCard".equals(key)) {
            lightMusicCard = enabled;
            FileLog.i("Light musicCard -> "
                    + (enabled ? "on" : "off"));
        } else if ("lightDotGlow".equals(key)) {
            lightDotGlow = enabled;
            FileLog.i("Light dotGlow -> "
                    + (enabled ? "on" : "off"));
        } else if ("lightDotSteady".equals(key)) {
            lightDotSteady = enabled;
            FileLog.i("Light dotSteady -> "
                    + (enabled ? "on" : "off"));
        } else if ("msgRemind".equals(key)) {
            msgRemind = enabled;
            FileLog.i("Light msgRemind -> "
                    + (enabled ? "on" : "off"));
        } else if ("msgRemindContent".equals(key)) {
            msgRemindContent = enabled;
            FileLog.i("Light msgRemindContent -> "
                    + (enabled ? "on" : "off"));
        } else if ("keepOriginal".equals(key)) {
            keepOriginal = enabled;
            FileLog.i("Light keepOriginal -> "
                    + (enabled ? "on" : "off"));
        } else if ("lightTodayCard".equals(key)) {
            lightTodayCard = enabled;
            FileLog.i("Light todayCard -> "
                    + (enabled ? "on" : "off"));
        } else if ("lightSummonSwipe".equals(key)) {
            lightSummonSwipe = enabled;
            FileLog.i("Light summonSwipe -> "
                    + (enabled ? "on" : "off"));
        } else if ("aiEnabled".equals(key)) {
            aiEnabled = enabled;
            FileLog.i("Light ai -> "
                    + (enabled ? "on" : "off"));
        } else if ("aiSummary".equals(key)) {
            aiSummary = enabled;
            FileLog.i("Light aiSummary -> "
                    + (enabled ? "on" : "off"));
        } else if ("aiTags".equals(key)) {
            aiTags = enabled;
            FileLog.i("Light aiTags -> "
                    + (enabled ? "on" : "off"));
        } else if ("infoCards".equals(key)) {
            infoCards = enabled;
            FileLog.i("Light infoCards -> "
                    + (enabled ? "on" : "off"));
        } else if ("infoRemind".equals(key)) {
            infoRemind = enabled;
            FileLog.i("Light infoRemind -> "
                    + (enabled ? "on" : "off"));
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
            manualRecognize = fileOrPrefsBool(
                    "manualRecognize", prefs, true);
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
            // v539: 回看胶囊——默认关（主动开关，wei 定）。
            recallCapsule = fileOrPrefsBool("recallCapsule", prefs, false);
            // v540: 自动复制验证码——默认开（= v244 起的老行为）。
            autoCopyCode = fileOrPrefsBool("autoCopyCode", prefs, true);
            // v546: 长按菜单——默认开。
            longPressMenu = fileOrPrefsBool("longPressMenu", prefs, true);
            // v555: 安静规则三件套——连发/@我突破默认开，
            // 关键词过滤默认关。
            burstBreak = fileOrPrefsBool("burstBreak", prefs, true);
            mentionBreak = fileOrPrefsBool("mentionBreak", prefs, true);
            keywordFilter = fileOrPrefsBool("keywordFilter", prefs, false);
            // v555: 关键词词表（字符串，默认六个促销词）——
            // 与 recallTtlMin 同路走文件优先（v545 教训）。
            filterWords = fileOrPrefsString("filterWords", prefs,
                    DEFAULT_FILTER_WORDS);
            FileLog.i("FilterWords load -> "
                    + filterWordList().size() + " words");
            // v543: 回看保留时长（字符串 prefs，默认 "3" 分钟）。
            // v545: 跟布尔开关同路改走文件优先——只读 prefs 时
            // SystemUI 重启后读到的是旧快照（wei 实测：界面显
            // 示 1 分钟、实际按默认 3 分钟过期）。
            recallTtlMin = parseRecallTtlMin(
                    fileOrPrefsString("recallTtlMin", prefs, "3"));
            // v564: 轻气泡设计模式——默认关（经典）；小签位置
            // 是字符串键，走文件优先（同 recallTtlMin 口径）。
            lightMode = fileOrPrefsBool("lightMode", prefs, false);
            lightPanelWhite = fileOrPrefsBool(
                    "lightPanelWhite", prefs, false);
            lightMusicCard = fileOrPrefsBool(
                    "lightMusicCard", prefs, true);
            lightDotGlow = fileOrPrefsBool(
                    "lightDotGlow", prefs, true);
            lightDotSteady = fileOrPrefsBool(
                    "lightDotSteady", prefs, false);
            msgRemind = fileOrPrefsBool(
                    "msgRemind", prefs, true);
            msgRemindContent = fileOrPrefsBool(
                    "msgRemindContent", prefs, false);
            keepOriginal = fileOrPrefsBool(
                    "keepOriginal", prefs, false);
            lightTodayCard = fileOrPrefsBool(
                    "lightTodayCard", prefs, true);
            lightSummonSwipe = fileOrPrefsBool(
                    "lightSummonSwipe", prefs, true);
            aiEnabled = fileOrPrefsBool(
                    "aiEnabled", prefs, false);
            aiSummary = fileOrPrefsBool(
                    "aiSummary", prefs, true);
            aiTags = fileOrPrefsBool("aiTags", prefs, true);
            infoCards = fileOrPrefsBool(
                    "infoCards", prefs, true);
            infoRemind = fileOrPrefsBool(
                    "infoRemind", prefs, true);
            lightDotPos = parseLightDotPos(
                    fileOrPrefsString("lightDotPos", prefs, "L1"));
            lightPanelStyle = parseLightPanelStyle(
                    fileOrPrefsString("lightPanelStyle", prefs,
                            "292,5,18,94,100"));
            lightDotColor = parseLightDotColor(
                    fileOrPrefsString("lightDotColor", prefs,
                            "blue"));
            FileLog.i("Light load -> mode=" + lightMode
                    + " pos=" + lightDotPos
                    + " style=" + lightPanelStyle
                    + " dotColor=" + lightDotColor);
            // v534: 当前音效 id（字符串 prefs，默认 "soft" 轻木）。
            // v535: 自定义文件路径/名也是字符串——三个字符串键都
            // 走逐键安全读：某个键万一被旧备份写坏成别的类型，
            // 只丢它自己，不连累整张开关表（load 外层还有兜底
            // try，但那会把所有开关一起重置，能免则免）。
            // v545: 三个键同样改走文件优先、prefs 兜底。
            String sid = fileOrPrefsString(
                    "bubbleSoundId", prefs, "soft");
            bubbleSoundId = sid.isEmpty() ? "soft" : sid;
            bubbleCustomPath = fileOrPrefsString(
                    "bubbleCustomPath", prefs, "");
            bubbleCustomName = fileOrPrefsString(
                    "bubbleCustomName", prefs, "");
            // v545: 载入值打一行日志，下轮日志核对重启后读对没。
            FileLog.i("RecallTtl load -> " + recallTtlMin
                    + " min");
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

    /** v545: 字符串键的文件优先版（与 fileOrPrefsBool 同路）：
     * 文件里有原始字符串就用文件的，没有再逐键安全读 prefs。 */
    private static String fileOrPrefsString(String key,
            SharedPreferences prefs, String def) {
        String v = SettingsStore.fileFeatureString(key);
        if (v != null) {
            return v;
        }
        return stringPref(prefs, key, def);
    }
}
