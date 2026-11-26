package com.chenan.flymebobo;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.AlertDialog;
import android.database.Cursor;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * flymebobo 2.0 — MainActivity (redesigned 2026-11-21, by Muse's own design).
 *
 * A clean settings page, not a copy of the notification overlay:
 * full screen, white background, scrollable. Top shows the module
 * status (LSPosed + notification access) and a green "煤气泡2.0"
 * header capsule. Below are the five feature bubbles — wide enough
 * that titles and subtitles never truncate — each with its own
 * colour, icon, and an on/off switch. Tapping a bubble expands it
 * in place (smooth height animation) to reveal the full description.
 * The Liu Jian Mao Cao "煤气泡2.0" image sits at the bottom as a
 * brand footer, with the log path and GitHub link under it.
 *
 * Switches are stored in SharedPreferences ("flymebobo_features").
 */
public class MainActivity extends Activity {

    private static final String PREFS = "flymebobo_features";

    private static class Feature {
        final String key;
        final String title;
        final String subtitle;
        final String body;
        final int color;
        final String icon;
        // v336: def = 默认开/关；parent = 嵌进哪个顶层卡片（null = 自己就是顶层卡片）。
        final boolean def;
        final String parent;
        // v346: noSwitch = 只展示、不设开关（一直开着，点一下展开介绍）。
        final boolean noSwitch;

        Feature(String key, String title, String subtitle, String body, int color, String icon) {
            this(key, title, subtitle, body, color, icon, true, null);
        }

        Feature(String key, String title, String subtitle, String body, int color, String icon,
                boolean def, String parent) {
            this(key, title, subtitle, body, color, icon, def, parent, false);
        }

        Feature(String key, String title, String subtitle, String body, int color, String icon,
                boolean def, String parent, boolean noSwitch) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.body = body;
            this.color = color;
            this.icon = icon;
            this.def = def;
            this.parent = parent;
            this.noSwitch = noSwitch;
        }
    }

    private static final Feature[] FEATURES = new Feature[]{
            new Feature("notif", "通知气泡", "普通通知的胶囊总开关",
                    "通知变成左贴边胶囊，点一下展开成卡片看全文。\n钉住的胶囊一直留着；没钉住的可以右滑消散、左滑收进扇子。\n永不自动展开。",
                    Color.rgb(59, 130, 246), "泡"),
            // v539: 回看胶囊——嵌进「通知气泡」卡片里。默认关
            // （wei 定：主动开关，用户自己开启才有这功能）。
            // 开着时手动左滑消散的普通气泡收进屏幕左边缘的
            // 回看栈（最多最近 5 条、停 3 分钟），点边缘把手
            // 逐个原样重弹；自动消散、点开、被新气泡顶掉的不
            // 收，音乐/下载/实况/导航/来电不收。重弹照样过
            // 场景/静默的门（排队），不绕过；重弹的再划掉不
            // 再入栈。实现在 BubbleWindow（回看栈 + 把手窗）。
            new Feature("recallCapsule", "回看胶囊", "划掉的气泡留个边缘入口，可重新弹出",
                    "默认关，自己开启才生效。\n开着时，手动划掉（左滑消散）的普通气泡会收进屏幕左边缘的回看胶囊：最多留最近 5 条，停 3 分钟没人点就自动清掉。\n点边缘把手，划掉的气泡逐个原样重新弹出；重弹的气泡再划掉不会再收。\n自动消散、点开、被新气泡顶掉的不收；音乐、下载、实况、导航、来电不收。",
                    Color.rgb(59, 130, 246), "回", false, "notif"),
            // v536: 提示音升为「通知气泡」卡下面的一张独立展开
            // 卡（buildSoundCard 手工挂，parent 记 "soundcard"
            // 只为不占顶层行、也不被任何顶层卡按 parent 自动
            // 收编），取代 v535 的 SoundSettingsActivity 子页
            // （wei 定：跟安静规则一样收回主页展开卡）。
            // v534: 气泡提示音——最初嵌进「通知气泡」卡片里。普通气泡
            // 弹出时轻响一声（默认音效是 wei 2026-11-26 试听选定
            // 的 5 号「轻木」）；音乐/下载/实况/导航/来电不响。
            // 默认开（wei 明确要的）。跟随系统通知音量，系统静音
            // 或勿扰时不响；同一波连弹只响第一声。通知气泡总开关
            // 关着时这行置灰禁用（refreshSoundLinkage 联动，同
            // 打码/销毁的套路）；行为层本来也双保险——总开关关了
            // 气泡根本不弹，自然不响。
            new Feature("bubbleSound", "气泡提示音", "气泡弹出时轻响一声",
                    "开着（默认）：普通气泡弹出来时轻响一声。\n音乐、下载、实况、导航、来电不响。\n跟着系统通知音量走：系统静音或勿扰时不响。\n同一波连弹多条只响第一声，不连珠炮。\n展开这张卡就能设置：换音效（5 个内置 + 自定义文件）、按应用单独设。",
                    Color.rgb(59, 130, 246), "音", true, "soundcard"),
            new Feature("music", "音乐胶囊", "音乐通知变播放器样式",
                    "歌名、歌手、实时歌词、专辑圆盘和进度条都在胶囊和播放器卡片上。\n同个 App 只保留一个音乐胶囊。\n钉住后胶囊上显示一行加大滚动歌词。",
                    Color.rgb(236, 72, 153), "乐"),
            new Feature("dl", "下载与安装", "下载进度实时胶囊",
                    "胶囊底部有细进度条，第二行显示已下/总量、速度和剩余时间。\n下载完成显示“下载完成”，3 秒后自动消散。",
                    Color.rgb(245, 158, 11), "下"),
            // v527: 验证取件码卡改名「验证码与隐私」（wei 定）：
            // 码本身（自动复制/取件钉住）+ 敏感打码同卡分区。
            new Feature("code", "验证码与隐私", "验证码、取件码和隐私打码",
                    "识别出验证码自动复制到剪贴板，取件码自动钉住不消散。\n胶囊只显示大字码、不带标题；智能柜的柜号一起显示，中英文都认。",
                    Color.rgb(34, 197, 94), "码"),
            // v540: 自动复制验证码开关（wei 要求）——v244 起
            // 验证码一直是无条件自动复制，这次给个开关。默认
            // 开（= 现状）；关掉后验证码只显示、不写剪贴板。
            // 排在「敏感打码」分区标题之前（码本身那一区）。
            // 「验证码与隐私」总开关（code）关时此行置灰
            // （refreshCodeLinkage）。
            new Feature("autoCopyCode", "自动复制验证码", "验证码一到就复制到剪贴板",
                    "开着（默认）：识别出验证码，胶囊弹出的同时把码复制到剪贴板，切过去直接粘贴。\n关掉：验证码只显示、不复制，剪贴板保持原样。\n取件码本来就不自动复制，不受这个开关管。",
                    Color.rgb(34, 197, 94), "复", true, "code"),
            // v524: 敏感打码——嵌进“验证取件码”卡片里。总开关 +
            // 锁屏、录屏/投屏两个分开关（用户 2026-11-26 “1得加
            // 开关”）。开着时锁屏/录屏/投屏状态下验证码、取件码
            // 只显示应用名 +「验证码 ••••••」，不露码；状态解除
            // 自动恢复全文。自动复制不受影响（剪贴板还是真码）。
            new Feature("mask", "敏感打码", "敏感场景下藏起内容",
                    "打码时验证码、取件码只显示应用名和「验证码 ••••••」，\n状态解除自动恢复全文。\n自动复制不受影响，剪贴板里还是真码。",
                    Color.rgb(244, 63, 94), "隐", true, "code"),
            new Feature("maskLock", "锁屏时", "手机锁屏时打码",
                    "锁屏时验证码/取件码打码，\n解锁后自动恢复全文。\n关掉后锁屏也显示真码。",
                    Color.rgb(244, 63, 94), "锁", true, "code"),
            new Feature("maskRecord", "录屏、投屏时", "录屏或投屏时打码",
                    "录屏或投屏时验证码/取件码打码，\n停录/停投后自动恢复全文。\n关掉后录屏投屏也显示真码。",
                    Color.rgb(244, 63, 94), "录", true, "code"),
            // v526: 普通应用也打码——同样嵌进“验证取件码”卡片，
            // 排在三个打码开关之后。默认关（用户 2026-11-26
            // 定）：开着时敏感场景下普通应用通知也隐藏内容，
            // 只显示应用名 +「内容已隐藏」；验证码/取件码不受
            // 它管，音乐/下载/实况/导航/来电也不受影响。
            new Feature("maskApps", "普通应用也打码", "普通通知也只显示应用名和「内容已隐藏」",
                    "开着时，锁屏/录屏/投屏状态下普通应用的通知也隐藏内容，只显示应用名 +「内容已隐藏」。\n默认关；关着只打码验证码/取件码。\n音乐、下载、实况、导航、来电不受影响。",
                    Color.rgb(244, 63, 94), "普", false, "code"),
            // v530: 场景模式总开关（wei 定）——场景识别一直常开，
            // 这次给它一个总开关，默认开不改变现有行为。排在
            // 安静规则卡里、应用名单上方（buildQuietRulesCard
            // 手工挂），parent 记 "quiet" 只为不占顶层行、也不被
            // 任何顶层卡按 parent 自动收编。备份走功能开关的
            // f. 前缀（f.sceneMode），SettingsStore 通用落盘。
            new Feature("sceneMode", "场景模式", "游戏、视频全屏时，通知先排队",
                    "开着（默认）：进游戏、看视频全屏时，普通通知先排队，\n退出场景再补弹；名单里设了「突破」的应用照常弹。\n关掉：全屏里通知也照常弹，场景例外同时失效。",
                    Color.rgb(99, 102, 241), "景", true, "quiet"),
            new Feature("live", "来电", "接听 · 挂断 · 扬声器",
                    "来电有专属胶囊：接听、挂断、\n扬声器切换都在上面。\n来电永不自动消散。",
                    Color.rgb(139, 92, 246), "电"),
            // v376: 来电放右边——嵌进“来电”卡片里。关着：来电胶囊
            // 和其它胶囊一样在左贴边；开着：来电胶囊单独放右手边、
            // 屏幕中间偏下一点，滑动方向镜像（往右滑钉住/收纳，
            // 往左滑挂断）。
            new Feature("callRight", "来电放右边", "来电胶囊放右手边 · 屏幕中间偏下",
                    "关着：来电胶囊和其它胶囊一样，\n在左贴边胶囊堆里。\n开着：来电胶囊单独放右手边，\n屏幕中间偏下一点。\n右边时滑动方向镜像：往右滑钉住/收纳，\n往左滑挂断。",
                    Color.rgb(139, 92, 246), "右", false, "live"),
            // v443: 模拟来电——嵌进“来电”卡片里。测试用的假来电：
            // 打开后弹一颗假来电胶囊，不用真打电话，就能看着调
            // 来电高度、测位置到没到位；关掉就收起来。真来电优先，
            // 不会顶掉真来电；假胶囊上的接听/挂断只收起预览，
            // 不碰真电话。
            new Feature("simCall", "模拟来电", "弹一颗假来电胶囊 · 调位置用",
                    "测试用的假来电：打开后弹一颗假来电胶囊，\n不用真打电话，就能看它落在哪个高度、\n调来电高度滑条时它跟着动。\n关掉就收起来。真来电优先，不会顶掉真来电。",
                    Color.rgb(139, 92, 246), "仿", false, "live"),
            // v527: card/bigCard 不再是顶层卡——「打开方式」卡里的
            // 呈现形式三模式（胶囊模式/展开卡片/大卡片模式）就是
            // 这两个标志位的组合，parent 记 "openway" 只为不占顶层行，
            // 标志位和默认值都不变。
            new Feature("card", "展开卡片", "先出胶囊，点一下展开看全文",
                    "呈现形式三模式之一：先出胶囊，点一下展开成卡片看全文。",
                    Color.rgb(20, 184, 166), "卡", true, "openway"),
            new Feature("bigCard", "大卡片模式", "通知一来就是大卡片",
                    "呈现形式三模式之一：通知一来就是大卡片依次排列。\n音乐出播放器卡片，来电仍是胶囊。",
                    Color.rgb(99, 102, 241), "大", false, "openway"),
            // v459: 扇子自动变淡——扇子出现后 5 秒没点，自动变半透明
            // （省得挡着屏幕）；点一下扇子恢复不透明并照常展开。
            // 默认开。关掉后扇子一直不透明。
            // v527: 扇子自动变淡迁进外观卡（parent "appearance"），
            // 在 buildAppearanceCard 里手工挂到卡内顶部。
            new Feature("fanAutoDim", "扇子自动变淡", "扇子闲置 5 秒变半透明",
                    "扇子出现后 5 秒没人点，自动变半透明，不挡屏幕。\n点一下扇子恢复并展开。关掉后一直不透明。",
                    Color.rgb(99, 102, 241), "淡", true, "appearance"),
            // v465: 胶囊自动取色——开着（默认）：胶囊底色取 App 图标
            // 的主色（半透明 80%），每个 App 的胶囊颜色都不一样；
            // 关着：全部用默认白色 + 黑色描边，不跟图标走。
            // 来电胶囊不受管（本来就是绿/红）。拨动立即生效，不用重启。
            // v467: 用户 2026-11-23 “反了”“默认开启”——默认开。
            // v527: 胶囊自动取色迁进外观卡（parent "appearance"）。
            new Feature("autoTint", "胶囊自动取色", "底色跟 App 图标主色走",
                    "开着（默认）：胶囊底色取 App 图标的主色，\n每个 App 的胶囊颜色都不一样。\n关掉后全部用默认白色底+黑描边。\n来电胶囊不受影响。拨动立即生效，不用重启。",
                    Color.rgb(16, 185, 129), "色", true, "appearance"),
            // v527: 胶囊滚动迁进外观卡（parent "appearance"）。
            new Feature("scroll", "胶囊滚动", "内容太长时滚动显示",
                    "胶囊里的标题和正文太长时会左右滚动显示完整内容。\n关掉后超长内容以 … 截断。",
                    Color.rgb(217, 70, 239), "滚", true, "appearance"),
            // v527: miniwindow 迁进「打开方式」卡，变成「点卡片打开
            // App」的单选（小窗/全屏），parent "openway"，标志位不变。
            new Feature("miniwindow", "点卡片打开 App", "点展开的卡片时，用哪种方式打开",
                    "点展开的卡片打开对应 App：小窗不离开当前界面，全屏直接跳过去。",
                    Color.rgb(14, 165, 233), "窗", true, "openway"),
            // v527: 自动消散迁进「打开方式」卡（wei 定），parent
            // "openway"，标志位不变。
            new Feature("blankDissipate", "自动消散", "点空白处，清掉所有胶囊",
                    "点一下屏幕空白处，所有没钉住的胶囊逐条消散。\n关掉后只能右滑逐个消散、或左滑收进扇子。",
                    Color.rgb(249, 115, 22), "散", true, "openway"),
            // v457: 自动销毁——嵌进“自动消散”卡片里。默认关。开着：
            // 点空白处胶囊消散时，系统通知也一起撤销（从通知栏
            // 消失）；关着：只消散胶囊，通知还留在通知栏里。
            // 音乐通知不撤销——撤销会停掉音乐（v276 同款）。
            new Feature("autoDestroy", "自动销毁", "消散时连系统通知一起撤销",
                    "开着时消散的通知同时从通知栏消失；\n关着（默认）通知还留在通知栏。\n音乐通知不撤销，撤销会停掉音乐。",
                    Color.rgb(249, 115, 22), "销", false, "openway"),
            // v336: 音乐卡片开关嵌进“音乐胶囊”卡片里，不再单独占一行。
            // v345: QQ 免费听开关已去掉，永远打开。
            // v346: 介绍留着、胶囊留着、不设开关——QQ 免费听只展示、不设开关。
            new Feature("musicCard", "音乐卡片", "点音乐胶囊展开播放器卡片",
                    "点一下音乐胶囊，展开成播放器卡片：\n专辑圆盘、歌词、进度条和播放控制。\n关掉后点音乐胶囊直接打开音乐 App，\n不再展开播放器卡片。",
                    Color.rgb(219, 39, 119), "音", true, "music"),
            // v532: 视频App开关——嵌进“音乐胶囊”卡片里。视频
            // App 的播放器通知带着和音乐一样的信号（媒体会话），
            // 一直也会被收成音乐胶囊；这个开关管收不收。默认开
            // （与现状一致）。关掉后视频 App 播放按普通通知走，
            // 正经音乐 App 不受影响。
            new Feature("musicVideo", "视频App", "B站等视频App播放时，也收成音乐胶囊",
                    "开着（默认）：B站、抖音、腾讯视频这些\n视频 App 播放时，播放器通知也收成音乐胶囊。\n关掉后：视频 App 的播放不再进音乐胶囊，\n按普通通知走；正经音乐 App 不受影响。",
                    Color.rgb(219, 39, 119), "视", true, "music"),
            // v459: 模拟音乐——嵌进“音乐胶囊”卡片里，和“模拟来电”
            // 一个路子。测试用的假音乐：打开后弹一颗假音乐胶囊
            // （晴天 - 周杰伦），不用真放音乐，就能看着调音乐胶囊/
            // 卡片大小；点它展开播放器卡片看卡片大小。关掉就收起来。
            // 假卡片上的播放控制是摆设，不碰真音乐。
            new Feature("simMusic", "模拟音乐", "弹一颗假音乐胶囊 · 调大小用",
                    "测试用的假音乐：打开后弹一颗假音乐胶囊\n（晴天 - 周杰伦），不用真放音乐，\n就能看着调音乐胶囊和卡片大小；\n点它展开播放器卡片看卡片大小。\n关掉就收起来。卡片上的播放控制是摆设，\n不碰真音乐。",
                    Color.rgb(219, 39, 119), "仿", false, "music"),
            // v478: 反方向的钟——嵌进“音乐胶囊”卡片里。音乐跑马灯：
            // 开着时放音乐，屏幕边缘一圈 LED 灯珠亮起来，像 00 年代
            // 金立/中天/天语老按键机机身上的跑马灯：灯珠密密嵌满一圈，
            // 追逐、齐闪、交替、随机闪几个花样轮着来；音乐停了就灭。
            // 默认关（用户 2026-11-23 “默认关闭”）。副标题（介绍）
            // 用户 2026-11-23 “把这个介绍去了”——只留名字，不写介绍。
            new Feature("musicLed", "反方向的钟", null,
                    "开着时放音乐，屏幕边缘一圈 LED 灯珠亮起来，\n像 00 年代老按键机机身上的跑马灯：\n灯珠密密嵌满一圈，追逐、齐闪、交替、\n随机闪几个花样轮着来。\n音乐停了灯就灭。默认关。",
                    Color.rgb(219, 39, 119), "钟", false, "music"),
            new Feature("qqFree", "QQ音乐flyme版免费听", "常开，无需设置",
                    "打开 QQ 音乐 Flyme 版，自动查剩余免费时长，\n低于 2 小时自动领奖励补到 4 小时。\n两次领取间隔不少于 4 小时。\n领满会弹提示；本来就 ≥2 小时也会提示。\n不支持主动领取，免费听一直开着。",
                    Color.rgb(190, 18, 60), "免", true, "music", true),
    };

    // v336: 顶层卡片（parent == null 的开关）。嵌套开关不单独占行，
    // 渲染和功能引导都只走这个列表；开关记忆和 isOn 仍走全部 FEATURES。
    private static final Feature[] TOP_FEATURES;
    static {
        java.util.ArrayList<Feature> tops = new java.util.ArrayList<>();
        for (Feature f : FEATURES) {
            if (f.parent == null) tops.add(f);
        }
        TOP_FEATURES = tops.toArray(new Feature[0]);
    }

    private SharedPreferences prefs;
    // v478: 反方向的钟——点满 N 次才开启（用户 2026-11-23 定）。
    // 点一下记一次数，计数器就显示在开关旁边（x/N）；点满 N 次
    // 开关自动打开，之后正常拨动。点数存 prefs（musicLed_taps），
    // 关掉 App 也不丢。N 本来是 100，用户 2026-11-23 “这个版本
    // 先设置10次”——先改成 10 方便测；用户 2026-11-23 09:43
    // “反方向的钟开关加到100次”——改回 100。
    private static final int MUSIC_LED_TAPS_TO_UNLOCK = 100;
    private TextView musicLedCounter;
    // v480: 反方向的钟——开关打开之后，标题变成用户那句话
    // “如果听一万次《反方向的钟》能否回到过去”（用户 2026-11-23
    // “反方向的钟开启之后，标题变成…”）。开关关着还是“反方向的钟”。
    private TextView musicLedTitle;
    private LinearLayout list;
    private ScrollView scrollView;

    // v527 设置页重构：搜索定位用的登记表。
    // cardBodies/cardExpanded：功能卡的展开区（搜索跳转要能程序展开）。
    private final Map<String, View[]> cardBodies = new HashMap<>();
    private final Map<String, boolean[]> cardExpanded = new HashMap<>();
    // subRowViews：卡内子开关行（按 feature key）。
    private final Map<String, View> subRowViews = new HashMap<>();
    // searchTargets：非子开关的可定位视图（滑条行、页脚链接等，按 id）。
    private final Map<String, View> searchTargets = new HashMap<>();
    // topCardViews：顶层功能卡视图（与 TOP_FEATURES 同序，功能引导用）。
    private final List<View> topCardViews = new ArrayList<>();
    private View appearanceBody;
    private TextView appearanceChev;
    private View openWayBody;
    private TextView openWayChev;
    private EditText searchBox;
    private LinearLayout searchResults;

    // v528: 安静规则展开卡（整块收回主页，替代 QuietRulesActivity）。
    private View quietBody;
    private TextView quietChev;
    private View quietSilenceAnchor;
    private View quietAppsAnchor;
    private TextView quietStatusTitle;
    private TextView quietStatusSub;
    private TextView quietCountdown;
    private TextView quietActionBtn;
    private LinearLayout quietChipsBox;
    private LinearLayout quietDndHint;
    private QuietSwitchRow quietLinkRow;
    private QuietSwitchRow quietCallRow;
    private int quietSelectedMinutes = TempSilence.DEFAULT_MINUTES;
    private String quietChipsKey = "";
    private final Handler quietTicker = new Handler(Looper.getMainLooper());

    /** v533: set by the quiet card's cancel tap just before
     * TempSilence.end() runs. The end flush happens in
     * SystemUI, which broadcasts the real flushed count back
     * (ACTION_TEMP_SILENCE_FLUSHED); the receiver below
     * consumes the flag and plays the in-app exit animation
     * with that count. A 1.5 s fallback plays with 0 if the
     * receipt never arrives (SystemUI not running — in which
     * case nothing could have been queued anyway). */
    private boolean silenceExitAnimPending;
    private long silenceExitAnimPendingAt;
    private final Runnable silenceExitAnimFallback =
            new Runnable() {
                @Override
                public void run() {
                    if (!silenceExitAnimPending) {
                        return;
                    }
                    silenceExitAnimPending = false;
                    showSilenceExitAnim(0);
                }
            };
    private final BroadcastReceiver silenceFlushedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (!silenceExitAnimPending) {
                        return;
                    }
                    if (System.currentTimeMillis()
                            - silenceExitAnimPendingAt > 3000) {
                        silenceExitAnimPending = false;
                        return;
                    }
                    silenceExitAnimPending = false;
                    quietTicker.removeCallbacks(
                            silenceExitAnimFallback);
                    int n = intent.getIntExtra(
                            TempSilence.EXTRA_FLUSHED, 0);
                    showSilenceExitAnim(n);
                }
            };
    private boolean quietTicking;
    private final Runnable quietTick = new Runnable() {
        @Override
        public void run() {
            refreshQuietSilence();
            if (quietTicking) {
                quietTicker.postDelayed(this, 1000);
            }
        }
    };
    private final Map<String, Integer> quietRules = new HashMap<>();
    private final Map<String, Integer> quietScene = new HashMap<>();
    private final List<QuietAppEntry> quietAllApps = new ArrayList<>();
    private final Map<String, View> quietRowViews = new HashMap<>();
    private final Set<String> quietPinned = new HashSet<>();
    private final Set<String> quietShownExtra = new HashSet<>();
    // v530: 场景模式行（搜索定位锚点）+ 名单「场景里」一列的
    // 分段引用（总开关关时整列置灰联动用）。
    private View quietSceneModeAnchor;
    private final Map<String, View> quietSceneSegRows = new HashMap<>();
    private final Map<String, SegControl> quietSceneSegs = new HashMap<>();
    private SegControl lastQuietSeg;
    private EditText quietAppSearch;
    private LinearLayout quietListBox;
    private TextView quietCountText;
    private boolean quietAppsLoaded;
    private boolean quietAppsLoading;
    // v528: 自动销毁联动（依附自动消散）用的行/开关引用。
    private View destroyRowView;
    private View destroySwitchView;
    // v528: 敏感打码联动（总开关 mask 依附关系）用的子行/子开关引用。
    private final Map<String, View> maskChildRows = new HashMap<>();
    private final Map<String, View> maskChildSwitches = new HashMap<>();
    // v540: 「自动复制验证码」行与开关——「验证码与隐私」
    // 总开关（code）关时置灰联动（refreshCodeLinkage）。
    private View autoCopyCodeRow;
    private View autoCopyCodeSwitch;
    // v534: 气泡提示音开关（通知气泡总开关联动置灰用）。
    // v537: 开关在展开区第一行（buildSoundMasterRow），
    // 还是这个引用。
    private View bubbleSoundSwitch;
    // v535: 气泡提示音副标题（显示当前音效）。v536: 搬到展开
    // 卡卡头，还是这个引用，refreshSoundSubtitle 照旧刷它。
    private TextView bubbleSoundSub;
    // v536: 提示音主页展开卡（代 SoundSettingsActivity 子页）。
    private View soundCardView;
    private LinearLayout soundBody;
    private TextView soundChev;
    private LinearLayout soundGlobalSection;
    private LinearLayout soundAppSection;
    private TextView soundCustomSub;
    private final Map<String, View> soundRadioDots = new HashMap<>();
    private final Map<String, TextView> soundNames = new HashMap<>();
    private final Map<String, String> soundRuleMap = new HashMap<>();
    private final List<QuietAppEntry> soundAllApps = new ArrayList<>();
    private final Map<String, View> soundRowViews = new HashMap<>();
    private final Set<String> soundShownExtra = new HashSet<>();
    private EditText soundAppSearch;
    private LinearLayout soundAppListBox;
    private TextView soundAppCount;
    private boolean soundAppsLoaded;
    private boolean soundAppsLoading;
    private MediaPlayer soundPreviewPlayer;
    private static final int REQ_PICK_SOUND = 5351;
    private static final String SOUND_CUSTOM_INTERNAL =
            "bubble_custom_sound";
    private static final long SOUND_MAX_CUSTOM_BYTES =
            20L * 1024 * 1024;

    private static final class SearchEntry {
        final String title;
        final String path;
        final String keywords;
        final View target;        // 主页内目标（与 activity 二选一）
        final String expandKey;   // 跳转前要展开的卡（feature key / "appearance" / "openway" / null）
        final Class<?> activity;  // 子页面目标
        final String highlight;   // 子页面 highlight 参数

        SearchEntry(String title, String path, String keywords,
                View target, String expandKey) {
            this.title = title;
            this.path = path;
            this.keywords = keywords;
            this.target = target;
            this.expandKey = expandKey;
            this.activity = null;
            this.highlight = null;
        }

        SearchEntry(String title, String path, String keywords,
                Class<?> activity, String highlight) {
            this.title = title;
            this.path = path;
            this.keywords = keywords;
            this.target = null;
            this.expandKey = null;
            this.activity = activity;
            this.highlight = highlight;
        }
    }

    private final List<SearchEntry> searchIndex = new ArrayList<>();

    // v442: 来电高度滑条（在「来电」卡片展开区、「来电放右边」下面）。
    // 跟开关联动：只有 callRight 开着才能滑，关着就置灰禁用。
    private SeekBar callHeightSeek;
    private View callHeightBox;

    // v456: 外观滑条登记表（extraKey → SeekBar），实时预览按 key
    // 读各滑条的当前值（拖动中 prefs 还没落盘，不能读 prefs）。
    private final java.util.HashMap<String, SeekBar> apSeeks =
            new java.util.HashMap<>();

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(silenceFlushedReceiver);
        } catch (Throwable ignored) {
        }
        releaseSoundPreview();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v533: the flushed-count receipt comes from SystemUI
        // (another process), so the receiver must be exported
        // — same reflective 3-arg registerReceiver (flag 2)
        // as the listener service's call-control receiver.
        try {
            java.lang.reflect.Method m = Context.class.getMethod(
                    "registerReceiver", BroadcastReceiver.class,
                    IntentFilter.class, int.class);
            m.invoke(this, silenceFlushedReceiver,
                    new IntentFilter(NotificationService
                            .ACTION_TEMP_SILENCE_FLUSHED), 2);
        } catch (Throwable t) {
            try {
                registerReceiver(silenceFlushedReceiver,
                        new IntentFilter(NotificationService
                                .ACTION_TEMP_SILENCE_FLUSHED));
            } catch (Throwable ignored) {
            }
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // v527: 状态栏沉浸（wei 追加）——状态栏透明、内容延伸到
        // 状态栏下方、图标深色（页面浅底）；顶部内边距在 root 上
        // 补状态栏高度，不遮内容。只改 App 自己的页面。
        applyEdgeToEdge();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // v464: 先从本地备份文件把设置恢复进 prefs（卸载重装后
        // prefs 被清了，文件还在）——必须在下面“缺省开关写默认
        // 值”之前，不然恢复的值会被默认值盖掉。
        // v471: 恢复了值就给 SystemUI 推一次全量重载（用户
        // 2026-11-23 “改成打开自动加载一次”）：文件写了但重启
        // 后不生效，就是因为 SystemUI 在 App 打开之前已经按旧
        // prefs 载完了；打开 App 时推一次，SystemUI 重新读一遍，
        // 开关和功能就对上了。没恢复到值不发（省一次重排）。
        boolean restored = SettingsStore.restoreToPrefs(this);
        if (restored) {
            try {
                android.content.Intent reload =
                        new android.content.Intent(
                                NotificationService.ACTION_RELOAD_SETTINGS);
                sendBroadcast(reload);
            } catch (Throwable ignored) {
            }
        }
        // v345: QQ 免费听开关已去掉，永远打开，不再写它的默认值/同步状态。
        // v335 开关记忆：只给还没有保存过的开关写各自的默认值，
        // 已保存的开关一律不动——更新 App 不会改动当前开关状态。
        java.util.ArrayList<Feature> missing = new java.util.ArrayList<>();
        for (int i = 0; i < FEATURES.length; i++) {
            if (!prefs.contains(FEATURES[i].key)) {
                missing.add(FEATURES[i]);
            }
        }
        if (!missing.isEmpty()) {
            SharedPreferences.Editor e = prefs.edit();
            for (Feature f : missing) {
                e.putBoolean(f.key, f.def);
            }
            e.commit();
            // 只把新补默认值的开关同步给 SystemUI。
            for (Feature f : missing) {
                Intent t = new Intent(
                        "com.chenan.flymebobo.FEATURE_TOGGLE");
                t.putExtra("key", f.key);
                t.putExtra("enabled", f.def);
                sendBroadcast(t);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scrollView = scroll;
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        // v527: 顶部多留状态栏高度（沉浸后内容从状态栏下方开始）。
        root.setPadding(dp(16), dp(16) + statusBarHeight(),
                dp(16), dp(24));
        scroll.addView(root);

        // ---- Status row ----
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(statusRow);
        statusRow.addView(statusPill("LSPosed", isModuleActive()));
        TextView gap1 = new TextView(this);
        gap1.setWidth(dp(8));
        statusRow.addView(gap1);
        statusRow.addView(statusPill("通知权限", isNotifEnabled()));
        // v354: 右上角“重启系统界面”按钮（用户 2026-11-22）。
        // 占位把按钮推到最右；点一下弹确认，确认后 root 杀掉
        // SystemUI 进程，系统会自动把它拉起来（等于重启系统界面，
        // 不用整机重启就能让模块新版生效）。
        View spacer = new View(this);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        spacer.setLayoutParams(splp);
        statusRow.addView(spacer);
        TextView restartBtn = new TextView(this);
        restartBtn.setText("↻ 重启系统界面");
        restartBtn.setTextSize(12);
        restartBtn.setTextColor(Color.rgb(22, 163, 74));
        restartBtn.setGravity(Gravity.CENTER);
        restartBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        restartBtn.setBackground(rounded(Color.rgb(220, 252, 231), dp(999)));
        restartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSystemUI();
            }
        });
        statusRow.addView(restartBtn);
        searchTargets.put("restart", restartBtn);

        // ---- Header capsule ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(rounded(Color.rgb(34, 197, 94), dp(999)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(16);
        header.setLayoutParams(hlp);
        header.setPadding(dp(10), dp(12), dp(20), dp(12));

        TextView hIcon = new TextView(this);
        hIcon.setText("泡");
        hIcon.setTextSize(16);
        hIcon.setTextColor(Color.rgb(34, 197, 94));
        hIcon.setGravity(Gravity.CENTER);
        hIcon.setBackground(rounded(Color.WHITE, dp(10)));
        LinearLayout.LayoutParams hilp = new LinearLayout.LayoutParams(dp(36), dp(36));
        hIcon.setLayoutParams(hilp);
        header.addView(hIcon);

        LinearLayout hTexts = new LinearLayout(this);
        hTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        htlp.leftMargin = dp(10);
        hTexts.setLayoutParams(htlp);

        TextView hName = new TextView(this);
        hName.setText("煤气泡 " + appVersionName());
        hName.setTextSize(20);
        hName.setTextColor(Color.WHITE);
        hName.setTypeface(hName.getTypeface(), android.graphics.Typeface.BOLD);
        hTexts.addView(hName);

        TextView hSub = new TextView(this);
        // v527: 与卡名对齐（卡叫「通知气泡」，不再写「通知胶囊」）。
        hSub.setText("通知气泡 · 音乐 · 下载 · 取件码 · 来电");
        hSub.setTextSize(11);
        hSub.setTextColor(Color.argb(230, 255, 255, 255));
        hTexts.addView(hSub);
        header.addView(hTexts);
        root.addView(header);

        // ---- v527: 设置搜索（常驻头部下方） ----
        searchBox = new EditText(this);
        searchBox.setHint("搜索设置，如：打码、游戏、安静");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(14);
        searchBox.setTextColor(Color.rgb(17, 24, 39));
        searchBox.setHintTextColor(Color.rgb(156, 163, 175));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.WHITE);
        searchBg.setCornerRadius(dp(999));
        searchBg.setStroke(dp(1), Color.rgb(216, 221, 227));
        searchBox.setBackground(searchBg);
        searchBox.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(14);
        searchBox.setLayoutParams(sbLp);
        root.addView(searchBox);
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setVisibility(View.GONE);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(6);
        searchResults.setLayoutParams(srLp);
        root.addView(searchResults);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a,
                    int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a,
                    int b, int c) {
                runSearch(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // ---- v527: 分组后的功能区 ----
        // 胶囊类型（5 张顶层功能卡）/ 呈现与打开（打开方式卡）/
        // 安静规则（合并页入口）/ 外观（外观设置卡）。
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        list.setLayoutParams(llp);
        root.addView(list);
        list.addView(buildGroupLabel("胶囊类型", true));
        for (int i = 0; i < TOP_FEATURES.length; i++) {
            View c = buildFeatureCard(TOP_FEATURES[i]);
            topCardViews.add(c);
            list.addView(c);
            // v536: 提示音独立展开卡，贴着「通知气泡」卡、
            // 归「胶囊类型」同一组（wei 定：跟安静规则一样
            // 收回主页，不开子页）。
            if ("notif".equals(TOP_FEATURES[i].key)) {
                list.addView(buildSoundCard());
            }
        }
        list.addView(buildGroupLabel("呈现与打开", false));
        list.addView(buildOpenWayCard());
        list.addView(buildGroupLabel("安静规则", false));
        list.addView(buildQuietRulesCard());
        list.addView(buildGroupLabel("外观", false));
        list.addView(buildAppearanceCard());
        TextView apHint = new TextView(this);
        apHint.setText("胶囊跟着扇子一起走，从扇子那里弹出来；改完立即生效，不用重启。点一下卡片可以展开说明。");
        apHint.setTextSize(11);
        apHint.setTextColor(Color.rgb(153, 153, 153));
        LinearLayout.LayoutParams apHlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apHlp.topMargin = dp(6);
        apHint.setLayoutParams(apHlp);
        list.addView(apHint);

        // ---- Footer links ----
        TextView log = new TextView(this);
        log.setText("日志：/sdcard/Android/data/com.chenan.flymebobo/files/BubbleNotify.log");
        log.setTextSize(11);
        log.setTextColor(Color.rgb(153, 153, 153));
        log.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams loglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loglp.topMargin = dp(12);
        log.setLayoutParams(loglp);
        root.addView(log);

        // ---- Footer links (v310): 开源仓库 · 酷安主页 · 请我喝咖啡 ----
        // Same row as 1.x (fixed122). Repo is the 2.0 source
        // (flymebobo-next); coffee shows the user's WeChat Pay QR
        // (assets/coffee_qr.png, decoded from 1.x's embedded copy).
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams linkRowLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        linkRowLp.topMargin = dp(10);
        linkRow.setLayoutParams(linkRowLp);
        root.addView(linkRow);

        TextView repoLink = new TextView(this);
        repoLink.setText("开源仓库");
        repoLink.setTextSize(12);
        repoLink.setTextColor(Color.rgb(14, 165, 233));
        repoLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/1enmon/flymebobo-next")));
                } catch (Throwable ignored) {
                }
            }
        });
        linkRow.addView(repoLink);

        TextView sep1 = new TextView(this);
        sep1.setText("  ·  ");
        sep1.setTextSize(12);
        sep1.setTextColor(Color.rgb(153, 153, 153));
        linkRow.addView(sep1);

        TextView coolapkLink = new TextView(this);
        coolapkLink.setText("酷安主页");
        coolapkLink.setTextSize(12);
        coolapkLink.setTextColor(Color.rgb(14, 165, 233));
        coolapkLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.coolapk.com/u/44111882")));
                } catch (Throwable ignored) {
                }
            }
        });
        linkRow.addView(coolapkLink);

        TextView sep2 = new TextView(this);
        sep2.setText("  ·  ");
        sep2.setTextSize(12);
        sep2.setTextColor(Color.rgb(153, 153, 153));
        linkRow.addView(sep2);

        TextView coffeeLink = new TextView(this);
        coffeeLink.setText("请我喝咖啡");
        coffeeLink.setTextSize(12);
        coffeeLink.setTextColor(Color.rgb(14, 165, 233));
        coffeeLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    InputStream in = getAssets().open("coffee_qr.png");
                    Bitmap qr = BitmapFactory.decodeStream(in);
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                    if (qr != null) {
                        ImageView iv = new ImageView(MainActivity.this);
                        iv.setImageBitmap(qr);
                        iv.setPadding(dp(16), dp(8), dp(16), dp(8));
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("请我喝咖啡")
                                .setMessage("微信扫码，感谢支持！")
                                .setView(iv)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        linkRow.addView(coffeeLink);

        TextView sep3 = new TextView(this);
        sep3.setText("  ·  ");
        sep3.setTextSize(12);
        sep3.setTextColor(Color.rgb(153, 153, 153));
        linkRow.addView(sep3);

        // v430: manual update check (user 2026-11-23 "提示一下有更新
        // 就行" — prompt only, never a silent install).
        TextView checkLink = new TextView(this);
        checkLink.setText("检查更新");
        checkLink.setTextSize(12);
        checkLink.setTextColor(Color.rgb(14, 165, 233));
        checkLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate(true);
            }
        });
        linkRow.addView(checkLink);
        searchTargets.put("update", checkLink);

        TextView gh = new TextView(this);
        gh.setText("GPL-3.0-only · 部分实现参考了 Flyme灵动岛");
        gh.setTextSize(11);
        gh.setTextColor(Color.rgb(153, 153, 153));
        gh.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams ghlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ghlp.topMargin = dp(4);
        gh.setLayoutParams(ghlp);
        root.addView(gh);

        // Notification permission button.
        TextView perm = new TextView(this);
        perm.setText("开启通知权限");
        perm.setTextSize(14);
        perm.setTextColor(Color.WHITE);
        perm.setTypeface(perm.getTypeface(), android.graphics.Typeface.BOLD);
        perm.setGravity(Gravity.CENTER);
        perm.setBackground(rounded(Color.rgb(34, 197, 94), dp(999)));
        perm.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(20);
        perm.setLayoutParams(plp);
        perm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        root.addView(perm);

        // Replay the feature guide.
        TextView guideBtn = new TextView(this);
        guideBtn.setText("功能引导");
        guideBtn.setTextSize(13);
        guideBtn.setTextColor(Color.rgb(14, 165, 233));
        guideBtn.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams gblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gblp.topMargin = dp(16);
        guideBtn.setLayoutParams(gblp);
        guideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGuide(0);
            }
        });
        root.addView(guideBtn);
        searchTargets.put("guide", guideBtn);

        setContentView(scroll);
        // v527: 全部视图建完后建搜索索引（索引引用真实视图）。
        buildSearchIndex();
        // v273: first-run feature guide (once). Post it so the
        // feature cards are laid out before we measure them.
        if (!prefs.getBoolean("guide_shown_v273", false)) {
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    showGuide(0);
                }
            });
        }
        // v430: auto update check on open — prompt only, never a
        // silent install (user 2026-11-23 "不要静默安装" /
        // "提示一下有更新就行").
        checkForUpdate(false);
    }

    /** The installed versionName (e.g. "2.4"), for the header. */
    private String appVersionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "2.4";
        }
    }

    /** v430: check GitHub for a newer release. When one is found,
     * prompt (dialog + notification) — never a silent install.
     * manual=true (the "检查更新" link) also toasts the result. */
    private void checkForUpdate(final boolean manual) {
        // v511: opening the app also refreshes the online game
        // list when stale (throttled inside fetchIfStale).
        GamePackages.fetchIfStale(this);
        if (manual) {
            Toast.makeText(this, "正在检查更新…",
                    Toast.LENGTH_SHORT).show();
        }
        UpdateChecker.checkAsync(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info) {
                if (info == null) {
                    if (manual) {
                        Toast.makeText(MainActivity.this,
                                "检查失败，请稍后再试",
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (info.available) {
                    UpdateChecker.notifyUpdate(MainActivity.this, info);
                    UpdateChecker.showUpdateDialog(MainActivity.this,
                            info);
                } else if (manual) {
                    if (info.error != null) {
                        Toast.makeText(MainActivity.this,
                                "检查失败，请稍后再试",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "已是最新版本",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    // ---- Feature guide (v273) ----
    // A dim overlay that spotlights one feature card at a time,
    // with a tooltip and 下一步 / 跳过. Shown once on first run;
    // replayable from the "功能引导" link at the bottom.
    private FrameLayout guideOverlay;
    private View guideHl;
    private TextView guideTip;
    private TextView guideStep;
    private int guideIdx;

    private void showGuide(int start) {
        guideIdx = start;
        if (guideOverlay != null) {
            ViewGroup parent = (ViewGroup) guideOverlay.getParent();
            if (parent != null) parent.removeView(guideOverlay);
        }
        final FrameLayout root = (FrameLayout) getWindow().getDecorView();
        guideOverlay = new FrameLayout(this);
        guideOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        guideOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        guideOverlay.setClickable(true);

        guideHl = new View(this);
        guideHl.setBackground(roundedStroke(Color.WHITE, dp(22), dp(3)));
        guideOverlay.addView(guideHl);

        LinearLayout tipBox = new LinearLayout(this);
        tipBox.setOrientation(LinearLayout.VERTICAL);
        tipBox.setBackground(rounded(Color.WHITE, dp(16)));
        tipBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        FrameLayout.LayoutParams tbp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbp.leftMargin = dp(20);
        tbp.rightMargin = dp(20);
        tbp.bottomMargin = dp(32);
        tbp.gravity = Gravity.BOTTOM;
        tipBox.setLayoutParams(tbp);

        guideStep = new TextView(this);
        guideStep.setTextSize(11);
        guideStep.setTextColor(Color.rgb(153, 153, 153));
        tipBox.addView(guideStep);

        guideTip = new TextView(this);
        guideTip.setTextSize(15);
        guideTip.setTextColor(Color.rgb(31, 41, 55));
        guideTip.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(4);
        guideTip.setLayoutParams(tipLp);
        tipBox.addView(guideTip);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(12);
        btnRow.setLayoutParams(brLp);

        TextView skip = new TextView(this);
        skip.setText("跳过");
        skip.setTextSize(14);
        skip.setTextColor(Color.rgb(107, 114, 128));
        skip.setPadding(dp(12), dp(8), dp(12), dp(8));
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissGuide();
            }
        });
        btnRow.addView(skip);

        TextView next = new TextView(this);
        next.setText("下一步");
        next.setTextSize(14);
        next.setTextColor(Color.WHITE);
        next.setTypeface(next.getTypeface(), android.graphics.Typeface.BOLD);
        next.setGravity(Gravity.CENTER);
        next.setBackground(rounded(Color.rgb(34, 197, 94), dp(999)));
        next.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout.LayoutParams nxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nxLp.leftMargin = dp(12);
        next.setLayoutParams(nxLp);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGuideStep(guideIdx + 1);
            }
        });
        btnRow.addView(next);
        tipBox.addView(btnRow);

        guideOverlay.addView(tipBox);
        root.addView(guideOverlay);
        showGuideStep(guideIdx);
    }

    private void showGuideStep(int idx) {
        if (idx >= TOP_FEATURES.length) {
            dismissGuide();
            return;
        }
        guideIdx = idx;
        final Feature f = TOP_FEATURES[idx];
        // v527: list 里混了组标题和合并卡，卡片视图单独登记。
        final View card = idx < topCardViews.size()
                ? topCardViews.get(idx) : null;
        if (card == null) {
            dismissGuide();
            return;
        }
        // Scroll the card near the top, then place the highlight
        // once the scroll has settled.
        int targetY = Math.max(0, card.getTop() - dp(120));
        if (scrollView != null) {
            scrollView.smoothScrollTo(0, targetY);
        }
        card.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (guideOverlay == null) return;
                int[] loc = new int[2];
                card.getLocationOnScreen(loc);
                int[] rootLoc = new int[2];
                guideOverlay.getLocationOnScreen(rootLoc);
                int x = loc[0] - rootLoc[0];
                int y = loc[1] - rootLoc[1];
                FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                        card.getWidth(), card.getHeight());
                hp.leftMargin = x;
                hp.topMargin = y;
                guideHl.setLayoutParams(hp);
                // Pop-in animation on the highlight.
                guideHl.setScaleX(0.96f);
                guideHl.setScaleY(0.96f);
                guideHl.animate().scaleX(1f).scaleY(1f)
                        .setDuration(180).start();
                guideStep.setText((guideIdx + 1) + " / " + TOP_FEATURES.length);
                guideTip.setText(f.title + " — " + f.subtitle);
            }
        }, 320);
    }

    private void dismissGuide() {
        prefs.edit().putBoolean("guide_shown_v273", true).apply();
        if (guideOverlay != null) {
            ViewGroup parent = (ViewGroup) guideOverlay.getParent();
            if (parent != null) parent.removeView(guideOverlay);
            guideOverlay = null;
        }
    }

    private android.graphics.drawable.Drawable roundedStroke(
            int color, int radius, int width) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(radius);
        d.setStroke(width, color);
        return d;
    }

    private View statusPill(String label, boolean on) {
        TextView pill = new TextView(this);
        pill.setText((on ? "● " : "○ ") + label + (on ? " 已开启" : " 未开启"));
        pill.setTextSize(12);
        pill.setTextColor(on ? Color.rgb(22, 163, 74) : Color.rgb(202, 138, 4));
        pill.setBackground(rounded(on ? Color.rgb(220, 252, 231) : Color.rgb(254, 249, 195), dp(999)));
        pill.setPadding(dp(10), dp(6), dp(10), dp(6));
        return pill;
    }

    private boolean isModuleActive() {
        // LSPosed modules can't easily self-detect from the app process;
        // treat "notification listener enabled" as the working signal.
        return isNotifEnabled();
    }

    private boolean isNotifEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    // v464: 离开 App 时再同步写一次本地备份文件（兜底——恢复默认
    // 按钮、扇子方向这类不走滑条/开关提交的改动也落进文件）。
    @Override
    protected void onPause() {
        super.onPause();
        stopQuietTicker();
        SettingsStore.saveAll(this);
    }

    // v528: 回到主页时把卡内临时静默状态对一遍（可能在外面
    // 到期、或被手动关勿扰提前取消）。
    @Override
    protected void onResume() {
        super.onResume();
        // v535/v536: 提示音当前音效和总开关涂色对一遍
        // （备份恢复或别处改动后，回主页同步过来）。
        refreshSoundSubtitle();
        repaintBubbleSoundSwitch();
        refreshSoundLinkage();
        refreshQuietSilence();
        if (quietBody != null
                && quietBody.getVisibility() == View.VISIBLE) {
            startQuietTicker();
        }
    }

    // One feature card: coloured bubble header + expandable body.
    // ================= v527 设置页重构 =================

    /** v527: 状态栏沉浸——透明状态栏 + 内容延伸到状态栏下方 +
     * 深色状态栏图标（页面浅底）。各子页面同款。 */
    void applyEdgeToEdge() {
        try {
            Window w = getWindow();
            w.setStatusBarColor(Color.TRANSPARENT);
            // v531: 底部同病同治——导航栏也透明、内容延伸到
            // 手势条下方，不然全屏动画（v2 渐变幕布）在 App
            // 内播时底部会露一条没盖住的边（SystemUI 侧同理
            // 已在 BubbleWindow.fullscreenAnimParams 修）。
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
    }

    int statusBarHeight() {
        int id = getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id)
                : dp(24);
    }

    /** v527: 分组标题（灰色小字）。 */
    private View buildGroupLabel(String text, boolean first) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Color.rgb(138, 143, 152));
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(first ? 16 : 20);
        lp.leftMargin = dp(4);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private Feature featureFor(String key) {
        for (Feature f : FEATURES) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    /** v527: 程序化拨一个功能开关（呈现形式/打开 App 单选用）：
     * 落盘 + 备份 + 广播，与 buildSwitch 的拨动同路。 */
    private void setFeatureFlag(String key, boolean on) {
        prefs.edit().putBoolean(key, on).commit();
        SettingsStore.saveAll(MainActivity.this);
        Intent i = new Intent(
                "com.chenan.flymebobo.FEATURE_TOGGLE");
        i.putExtra("key", key);
        i.putExtra("enabled", on);
        sendBroadcast(i);
    }

    /** v527: 分段单选控件（预览同款：半透明白底胶囊，选中项白底
     * 深字加粗）。accent = 选中项文字色。 */
    private final class SegControl {
        final LinearLayout box;
        final TextView[] items;
        final int accent;
        int sel;
        SegListener listener;

        SegControl(String[] labels, int selected, int accentColor) {
            this.accent = accentColor;
            this.sel = selected;
            box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setBackground(rounded(
                    Color.argb(60, 255, 255, 255), dp(999)));
            box.setPadding(dp(3), dp(3), dp(3), dp(3));
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView tv = new TextView(MainActivity.this);
                tv.setText(labels[i]);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(0, dp(7), 0, dp(7));
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (sel != idx) {
                            sel = idx;
                            paint();
                            if (listener != null) {
                                listener.onSelect(idx);
                            }
                        }
                    }
                });
                items[i] = tv;
                box.addView(tv);
            }
            paint();
        }

        void paint() {
            for (int i = 0; i < items.length; i++) {
                if (i == sel) {
                    items[i].setBackground(rounded(Color.WHITE,
                            dp(999)));
                    items[i].setTextColor(accent);
                    items[i].setTypeface(items[i].getTypeface(),
                            android.graphics.Typeface.BOLD);
                } else {
                    items[i].setBackground(null);
                    items[i].setTextColor(Color.WHITE);
                    items[i].setTypeface(items[i].getTypeface(),
                            android.graphics.Typeface.NORMAL);
                }
            }
        }
    }

    private interface SegListener {
        void onSelect(int idx);
    }

    /** v528: 展开箭头统一（wei 拍板用安静规则那个「›」字形）：
     * 同字形、同大小、同位置；未展开朝下、展开后朝上，同一个
     * 箭头转方向，不再用像字母 V 的 ∨。
     * v529: wei 实测截图里箭头挤在卡片右上角——右边距加大，
     * 让它离开圆角、往左挪一格（卡头本身有 12dp 内边距，
     * 再加 10dp 右边距，与左侧图标盘的呼吸感对齐）。三张可
     * 展开卡（打开方式/外观设置/安静规则）共用这个构建器，
     * 一处改、三处齐。 */
    private TextView buildExpandChevron() {
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(24);
        chev.setTextColor(Color.WHITE);
        chev.setRotation(90f);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.leftMargin = dp(8);
        chlp.rightMargin = dp(10);
        chev.setLayoutParams(chlp);
        return chev;
    }

    private void setChevronExpanded(TextView chev, boolean expanded) {
        if (chev == null) {
            return;
        }
        chev.animate().rotation(expanded ? -90f : 90f)
                .setDuration(180).start();
    }

    /** v528: 卡内开关行（临时静默的联动/突破用；半透明白底 +
     * 白字 + 绿开关，与主页子开关同款）。 */
    private final class QuietSwitchRow {
        final LinearLayout row;
        final FrameLayout track;
        final View dot;
        boolean on;
        final Runnable onFlip;

        QuietSwitchRow(String title, String sub, boolean initial,
                Runnable onFlip) {
            this.on = initial;
            this.onFlip = onFlip;
            row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(Color.argb(60, 255, 255, 255),
                    dp(16)));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t = new TextView(MainActivity.this);
            t.setText(title);
            t.setTextSize(14);
            t.setTextColor(Color.WHITE);
            t.setTypeface(t.getTypeface(),
                    android.graphics.Typeface.BOLD);
            texts.addView(t);
            TextView s = new TextView(MainActivity.this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(Color.argb(230, 255, 255, 255));
            texts.addView(s);
            row.addView(texts);

            track = new FrameLayout(MainActivity.this);
            track.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(48), dp(28)));
            dot = new View(MainActivity.this);
            dot.setBackground(rounded(Color.WHITE, dp(999)));
            track.addView(dot, new FrameLayout.LayoutParams(
                    dp(22), dp(22)));
            row.addView(track);
            paint();
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    on = !on;
                    paint();
                    if (onFlip != null) {
                        onFlip.run();
                    }
                }
            });
        }

        void setOn(boolean v) {
            on = v;
            paint();
        }

        private void paint() {
            track.setBackground(rounded(on ? Color.rgb(22, 163, 74)
                    : Color.argb(120, 255, 255, 255), dp(999)));
            FrameLayout.LayoutParams p =
                    (FrameLayout.LayoutParams) dot.getLayoutParams();
            p.gravity = Gravity.CENTER_VERTICAL
                    | (on ? Gravity.END : Gravity.START);
            p.leftMargin = on ? 0 : dp(3);
            p.rightMargin = on ? dp(3) : 0;
            dot.setLayoutParams(p);
        }
    }

    private static final class QuietAppEntry {
        String pkg;
        String label;
        Drawable icon;
        boolean system;
    }

    /** 平时待遇三段的显示顺序 → AppRules 模式值。 */
    private static final int[] QUIET_RULE_MODES = {
            AppRules.DEFAULT, AppRules.EDGE, AppRules.NEVER};
    private static final String[] QUIET_RULE_LABELS = {
            "跟随", "只贴边", "永不弹"};
    /** 场景里两段的显示顺序 → SceneExceptions 模式值。 */
    private static final int[] QUIET_SCENE_MODES = {
            SceneExceptions.FOLLOW, SceneExceptions.BREAK};
    private static final String[] QUIET_SCENE_LABELS = {
            "排队", "突破"};
    private static final int QUIET_INDIGO = Color.rgb(99, 102, 241);

    /**
     * v528: 安静规则展开卡（wei 实测 v527 后定：不开单独页，
     * 整块收回主页）。上半 = 临时静默全套（状态/开启取消/时长
     * 三档/勿扰联动/来电突破，全部走 TempSilence 的静态逻辑与
     * prefs，语义一行不改）；下半 = 应用名单——默认只展示已设
     * 过非默认规则的应用 + 微信/QQ 常驻，卡内搜索可搜全部已
     * 安装应用，点中/设了就留在名单里。卡底有判定顺序说明。
     * 原 QuietRulesActivity 已从 Manifest 和入口撤下。
     */
    private View buildQuietRulesCard() {
        final int color = QUIET_INDIGO;
        quietRules.putAll(AppRules.readAll(this));
        quietScene.putAll(SceneExceptions.readAll(this));
        TempSilence.State st = TempSilence.readState(this);
        quietSelectedMinutes = st.minutes;

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(color, dp(22)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

        // ---- 卡头 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView icon = new TextView(this);
        icon.setText("静");
        icon.setTextSize(15);
        icon.setTextColor(color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(10)));
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(36), dp(36)));
        head.addView(icon);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("安静规则");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(),
                android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText("临时静默和应用名单，都在这张卡里");
        sub.setTextSize(12);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(sub);
        head.addView(texts);
        final TextView chev = buildExpandChevron();
        head.addView(chev);
        card.addView(head);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(0, 0, 0, dp(12));
        card.addView(body);
        quietBody = body;
        quietChev = chev;
        final boolean[] expanded = new boolean[]{false};
        body.setTag(expanded);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0]
                        ? View.VISIBLE : View.GONE);
                setChevronExpanded(chev, expanded[0]);
                if (expanded[0]) {
                    refreshQuietSilence();
                    ensureQuietAppsLoaded();
                    startQuietTicker();
                } else {
                    stopQuietTicker();
                }
            }
        });

        // ================= 临时静默区 =================
        View silenceLabel = buildZoneLabel("临时静默");
        quietSilenceAnchor = silenceLabel;
        body.addView(silenceLabel);
        body.addView(cardDesc("开启后普通通知先排队、不弹胶囊，"
                + "结束时按顺序补弹；音乐、下载、实况不停。"
                + "跟系统勿扰双向联动：开静默时同步打开勿扰，"
                + "勿扰被打开时气泡也自动排队；静默期间手动关掉"
                + "勿扰，算提前取消。"));

        quietStatusTitle = new TextView(this);
        quietStatusTitle.setTextColor(Color.WHITE);
        quietStatusTitle.setTextSize(15);
        quietStatusTitle.setTypeface(quietStatusTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stLp = quietRowLp();
        stLp.topMargin = dp(12);
        quietStatusTitle.setLayoutParams(stLp);
        body.addView(quietStatusTitle);
        quietCountdown = new TextView(this);
        quietCountdown.setTextColor(Color.WHITE);
        quietCountdown.setTextSize(30);
        quietCountdown.setTypeface(quietCountdown.getTypeface(),
                android.graphics.Typeface.BOLD);
        quietCountdown.setLayoutParams(quietRowLp());
        body.addView(quietCountdown);
        quietStatusSub = new TextView(this);
        quietStatusSub.setTextColor(Color.argb(230, 255, 255, 255));
        quietStatusSub.setTextSize(12);
        quietStatusSub.setLineSpacing(0, 1.5f);
        quietStatusSub.setLayoutParams(quietRowLp());
        body.addView(quietStatusSub);
        quietActionBtn = new TextView(this);
        quietActionBtn.setGravity(Gravity.CENTER);
        quietActionBtn.setTextSize(14);
        quietActionBtn.setTextColor(color);
        quietActionBtn.setTypeface(quietActionBtn.getTypeface(),
                android.graphics.Typeface.BOLD);
        quietActionBtn.setBackground(rounded(Color.WHITE, dp(12)));
        quietActionBtn.setPadding(0, dp(11), 0, dp(11));
        LinearLayout.LayoutParams abLp = quietRowLp();
        abLp.topMargin = dp(12);
        quietActionBtn.setLayoutParams(abLp);
        quietActionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TempSilence.State s = TempSilence.readState(
                        MainActivity.this);
                if (s.active()) {
                    // v533: arm the exit receipt BEFORE end()
                    // — SystemUI flushes on the state broadcast
                    // and sends the real flushed count back;
                    // the receiver plays the exit animation
                    // with it (fallback: 1.5 s, count 0).
                    silenceExitAnimPending = true;
                    silenceExitAnimPendingAt =
                            System.currentTimeMillis();
                    quietTicker.removeCallbacks(
                            silenceExitAnimFallback);
                    quietTicker.postDelayed(
                            silenceExitAnimFallback, 1500);
                    TempSilence.end(MainActivity.this, "cancel");
                } else {
                    silenceExitAnimPending = false;
                    quietTicker.removeCallbacks(
                            silenceExitAnimFallback);
                    TempSilence.start(MainActivity.this,
                            quietSelectedMinutes);
                    TempSilence.State now = TempSilence.readState(
                            MainActivity.this);
                    if (now.active()) {
                        // 开启成功才播全屏动画（定稿版）。
                        showSilenceStartAnim(now.until);
                    }
                    if (s.linkDnd && !policyGranted()) {
                        Toast.makeText(MainActivity.this,
                                "已静默；勿扰没同步开（还没授权，"
                                        + "见下方提示）",
                                Toast.LENGTH_LONG).show();
                    }
                }
                refreshQuietSilence();
            }
        });
        body.addView(quietActionBtn);

        body.addView(buildZoneLabel("静默时长"));
        quietChipsBox = new LinearLayout(this);
        quietChipsBox.setOrientation(LinearLayout.HORIZONTAL);
        quietChipsBox.setLayoutParams(quietRowLp());
        body.addView(quietChipsBox);
        buildQuietChips();

        body.addView(buildZoneLabel("联动与突破"));
        quietLinkRow = new QuietSwitchRow("与系统勿扰联动",
                "开静默 → 勿扰同步开；勿扰被打开 → 气泡自动排队",
                st.linkDnd, new Runnable() {
                    @Override
                    public void run() {
                        persistQuietSettings();
                        refreshQuietSilence();
                    }
                });
        quietLinkRow.row.setLayoutParams(quietRowLp());
        body.addView(quietLinkRow.row);
        quietCallRow = new QuietSwitchRow("静默时来电突破",
                "开着：来电照常弹；关了：来电也排队，真全静默",
                st.callBreak, new Runnable() {
                    @Override
                    public void run() {
                        persistQuietSettings();
                    }
                });
        LinearLayout.LayoutParams callLp = quietRowLp();
        callLp.topMargin = dp(8);
        quietCallRow.row.setLayoutParams(callLp);
        body.addView(quietCallRow.row);

        // 勿扰授权提示
        quietDndHint = new LinearLayout(this);
        quietDndHint.setOrientation(LinearLayout.VERTICAL);
        quietDndHint.setBackground(rounded(
                Color.rgb(254, 242, 242), dp(14)));
        quietDndHint.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams hintLp = quietRowLp();
        hintLp.topMargin = dp(12);
        quietDndHint.setLayoutParams(hintLp);
        TextView hintText = new TextView(this);
        hintText.setText("还没有「勿扰访问」权限：开静默时没法同步"
                + "打开系统勿扰（气泡静默本身不受影响，勿扰那一半"
                + "不生效）。授权一次即可。");
        hintText.setTextSize(12);
        hintText.setLineSpacing(0, 1.5f);
        hintText.setTextColor(Color.rgb(153, 27, 27));
        quietDndHint.addView(hintText);
        TextView grantBtn = new TextView(this);
        grantBtn.setText("去授权");
        grantBtn.setGravity(Gravity.CENTER);
        grantBtn.setTextSize(13);
        grantBtn.setTextColor(Color.WHITE);
        grantBtn.setTypeface(grantBtn.getTypeface(),
                android.graphics.Typeface.BOLD);
        grantBtn.setBackground(rounded(
                Color.rgb(220, 38, 38), dp(10)));
        grantBtn.setPadding(0, dp(9), 0, dp(9));
        LinearLayout.LayoutParams gbLp = new LinearLayout
                .LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        gbLp.topMargin = dp(8);
        grantBtn.setLayoutParams(gbLp);
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings
                            .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (Throwable ignored) {
                }
            }
        });
        quietDndHint.addView(grantBtn);
        body.addView(quietDndHint);

        // ================= 场景模式总开关 =================
        // v530（wei 定）：排在应用名单上方。关掉后全屏游戏/
        // 视频里通知照常弹；名单里「场景里」一列同时置灰
        // 失效（refreshSceneLinkage 联动）。
        Feature sceneFeature = featureFor("sceneMode");
        if (sceneFeature != null) {
            View smRow = buildSubSwitch(sceneFeature);
            subRowViews.put(sceneFeature.key, smRow);
            quietSceneModeAnchor = smRow;
            body.addView(smRow);
        }
        body.addView(cardDesc("关掉后，游戏、视频全屏里通知也照常弹，"
                + "下面名单里的「场景里」设置同时失效。"));

        // ================= 应用名单区 =================
        View appsLabel = buildZoneLabel("应用名单");
        quietAppsAnchor = appsLabel;
        body.addView(appsLabel);
        body.addView(cardDesc("默认只列设过的应用和微信、QQ；"
                + "要设别的，用下面搜索找出来点开就能设。"
                + "「场景里」指游戏、视频全屏时：突破＝照常弹，"
                + "排队＝先收着、退出场景再补弹。"));

        quietAppSearch = new EditText(this);
        quietAppSearch.setHint("搜索应用（没设过的默认跟随、场景里排队）");
        quietAppSearch.setSingleLine(true);
        quietAppSearch.setTextSize(13.5f);
        quietAppSearch.setTextColor(Color.rgb(17, 24, 39));
        quietAppSearch.setHintTextColor(Color.rgb(156, 163, 175));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.WHITE);
        sb.setCornerRadius(dp(999));
        quietAppSearch.setBackground(sb);
        quietAppSearch.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams seLp = quietRowLp();
        seLp.topMargin = dp(10);
        quietAppSearch.setLayoutParams(seLp);
        quietAppSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a,
                    int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a,
                    int b, int c) {
                ensureQuietAppsLoaded();
                applyQuietFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        body.addView(quietAppSearch);

        quietListBox = new LinearLayout(this);
        quietListBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(quietListBox);

        quietCountText = new TextView(this);
        quietCountText.setTextSize(12);
        quietCountText.setTextColor(Color.argb(230, 255, 255, 255));
        LinearLayout.LayoutParams ctLp = quietRowLp();
        ctLp.topMargin = dp(10);
        quietCountText.setLayoutParams(ctLp);
        body.addView(quietCountText);

        body.addView(cardDesc("判定顺序：先看应用名单"
                + "（永不弹一票否决）→ 再看场景（不在突破名单"
                + "就排队）→ 最后临时静默（静默中一律排队、"
                + "到点补弹）。"));

        refreshQuietSilence();
        return card;
    }

    // ================= v536 提示音展开卡 =================

    /**
     * v536: 气泡提示音主页展开卡（wei 实测 v535 后定：子页
     * 改成跟安静规则一样的主页展开卡）。位置贴着「通知气泡」
     * 卡、归「胶囊类型」组。卡头 = 图标 + 标题 + 动态副标题
     * （当前：X）+ 统一「›」展开箭头（v537: 卡头不再放开关，
     * wei 定开关和展开只保留一个，跟安静规则卡头同款）。
     * 总开关（bubbleSound 本人）挪进展开区第一行。展开区
     * 上半 = 总开关行 + 全局音效
     * （5 个内置单选 + 每行试听 + 自定义文件行），下半 =
     * 按应用（照安静规则名单的做法：默认只列设过规则的，
     * 没有就一行空态；卡内搜索找任意已装应用设规则；标签
     * 点击循环）。行为层（BubbleSound / SoundRules / 自定
     * 义文件双份存储与回退）全照 v535，只搬家不改语义。
     * 原 SoundSettingsActivity 已删、Manifest 已注销。
     * v538: 按应用默认语义反转（wei 拍板）——没设的应用
     * 默认「无」（不响），循环改走有效值（无 → 跟随全局
     * → 音效…）；默认名单（微信 / QQ / 默认短信应用 /
     * 钉钉 / TIM / 企业微信）没设按跟随全局解析、直接
     * 列进名单，点成「无」时存显式 none 压住默认。
     */
    private View buildSoundCard() {
        final Feature f = featureFor("bubbleSound");
        final int color = f != null ? f.color
                : Color.rgb(59, 130, 246);
        soundRuleMap.putAll(SoundRules.readAll(this));

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(color, dp(22)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);
        soundCardView = card;

        // ---- 卡头 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView icon = new TextView(this);
        icon.setText(f != null ? f.icon : "音");
        icon.setTextSize(15);
        icon.setTextColor(color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(10)));
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(36), dp(36)));
        head.addView(icon);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("气泡提示音");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(),
                android.graphics.Typeface.BOLD);
        texts.addView(title);
        bubbleSoundSub = new TextView(this);
        bubbleSoundSub.setText("气泡弹出时轻响一声");
        bubbleSoundSub.setTextSize(12);
        bubbleSoundSub.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(bubbleSoundSub);
        head.addView(texts);
        // v537: 卡头只留展开箭头（wei 定：开关和展开保留
        // 一个）；总开关在展开区第一行（buildSoundMasterRow）。
        final TextView chev = buildExpandChevron();
        head.addView(chev);
        card.addView(head);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(0, 0, 0, dp(12));
        card.addView(body);
        soundBody = body;
        soundChev = chev;
        final boolean[] expanded = new boolean[]{false};
        body.setTag(expanded);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0]
                        ? View.VISIBLE : View.GONE);
                setChevronExpanded(chev, expanded[0]);
                if (expanded[0]) {
                    refreshSoundSelection();
                    ensureSoundAppsLoaded();
                }
            }
        });

        // ---- 展开区第一行：总开关 ----
        body.addView(buildSoundMasterRow(f));

        // ---- 展开区上半：全局音效 ----
        soundGlobalSection = new LinearLayout(this);
        soundGlobalSection.setOrientation(LinearLayout.VERTICAL);
        soundGlobalSection.addView(buildZoneLabel("全局音效"));
        // v538: 按应用默认不响之后，全局音效只对设了
        // 「跟随全局」的应用生效，加一行小字说清。
        soundGlobalSection.addView(cardDesc(
                "全局音效给设了『跟随全局』的应用用"));
        for (String[] row : BubbleSound.CATALOG) {
            soundGlobalSection.addView(buildSoundRow(row[0],
                    row[1], row[3]));
        }
        soundGlobalSection.addView(buildSoundCustomRow());
        body.addView(soundGlobalSection);

        // ---- 展开区下半：按应用 ----
        soundAppSection = new LinearLayout(this);
        soundAppSection.setOrientation(LinearLayout.VERTICAL);
        soundAppSection.addView(buildZoneLabel("按应用设置"));
        // v538: 默认语义反转后的说明（没设 = 不响；默认
        // 名单六个除外，它们默认跟随全局且直接列在名单里）。
        soundAppSection.addView(cardDesc("没设的应用默认不响；"
                + "微信、QQ、短信、钉钉、TIM、企业微信默认"
                + "跟随全局。要给别的应用设，用下面搜索找"
                + "出来，点它右边的标签切换。"));
        soundAppSearch = new EditText(this);
        soundAppSearch.setHint("搜索应用");
        soundAppSearch.setSingleLine(true);
        soundAppSearch.setTextSize(13.5f);
        soundAppSearch.setTextColor(Color.rgb(17, 24, 39));
        soundAppSearch.setHintTextColor(Color.rgb(156, 163, 175));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.WHITE);
        sb.setCornerRadius(dp(999));
        soundAppSearch.setBackground(sb);
        soundAppSearch.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams seLp = quietRowLp();
        seLp.topMargin = dp(10);
        soundAppSearch.setLayoutParams(seLp);
        soundAppSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a,
                    int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a,
                    int b, int c) {
                ensureSoundAppsLoaded();
                applySoundFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        soundAppSection.addView(soundAppSearch);
        soundAppListBox = new LinearLayout(this);
        soundAppListBox.setOrientation(LinearLayout.VERTICAL);
        soundAppSection.addView(soundAppListBox);
        soundAppCount = new TextView(this);
        soundAppCount.setTextSize(12);
        soundAppCount.setTextColor(Color.argb(230, 255, 255, 255));
        LinearLayout.LayoutParams ctLp = quietRowLp();
        ctLp.topMargin = dp(10);
        soundAppCount.setLayoutParams(ctLp);
        soundAppSection.addView(soundAppCount);
        body.addView(soundAppSection);

        body.addView(cardDesc("提示音走系统通知音量；"
                + "系统静音或勿扰时不响。"));

        refreshSoundSelection();
        refreshSoundSubtitle();
        refreshSoundLinkage();
        return card;
    }

    /** v537: 提示音总开关行——展开区第一行，在「全局音效」
     * 列表之上。样式照 v535 子页顶部那行：♪ 图标盘 +「提示
     * 音」+ 副标题「气泡弹出时轻响一声」+ 开关。开关本体走
     * buildSwitch 通用拨动（落盘 + 备份 + 广播 + 涂色，拨动
     * 钩子里会调 refreshSoundLinkage 刷展开区置灰）。第三个
     * 参数 card 只被点击处理拿去 setAlpha——置灰统一归
     * refreshSoundLinkage 管，所以传一个不挂载的空容器把那
     * 一下 alpha 吃掉，别跟联动打架。这一行不进
     * soundGlobalSection / soundAppSection，所以总开关关时
     * 它自己不置灰，方便再打开（refreshSoundLinkage 只灰
     * 它下面两个区段）。 */
    private View buildSoundMasterRow(Feature f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setLayoutParams(soundRowLp());
        TextView disc = new TextView(this);
        disc.setText("♪");
        disc.setTextSize(14);
        disc.setTextColor(Color.rgb(59, 130, 246));
        disc.setGravity(Gravity.CENTER);
        disc.setBackground(rounded(Color.WHITE, dp(999)));
        disc.setLayoutParams(new LinearLayout.LayoutParams(
                dp(30), dp(30)));
        row.addView(disc);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(10);
        texts.setLayoutParams(tLp);
        TextView nm = new TextView(this);
        nm.setText("提示音");
        nm.setTextSize(14.5f);
        nm.setTextColor(Color.WHITE);
        texts.addView(nm);
        TextView ds = new TextView(this);
        ds.setText("气泡弹出时轻响一声");
        ds.setTextSize(11.5f);
        ds.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(ds);
        row.addView(texts);
        if (f != null) {
            View sw = buildSwitch(f, new LinearLayout(this));
            sw.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(48), dp(28)));
            bubbleSoundSwitch = sw;
            row.addView(sw);
        }
        return row;
    }

    /** 卡内一行布局参数（左右各留 12dp，行距 8dp）。 */
    private LinearLayout.LayoutParams soundRowLp() {
        LinearLayout.LayoutParams lp = quietRowLp();
        lp.topMargin = dp(8);
        return lp;
    }

    private View buildSoundRow(final String id, String name,
            String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setLayoutParams(soundRowLp());
        TextView play = new TextView(this);
        play.setText("▶");
        play.setTextSize(12);
        play.setTextColor(Color.rgb(59, 130, 246));
        play.setGravity(Gravity.CENTER);
        play.setBackground(rounded(Color.WHITE, dp(999)));
        play.setLayoutParams(new LinearLayout.LayoutParams(
                dp(30), dp(30)));
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (soundUsable()) {
                    previewSound(id);
                }
            }
        });
        row.addView(play);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(10);
        texts.setLayoutParams(tLp);
        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextSize(14.5f);
        nm.setTextColor(Color.WHITE);
        texts.addView(nm);
        soundNames.put(id, nm);
        TextView ds = new TextView(this);
        ds.setText(desc);
        ds.setTextSize(11.5f);
        ds.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(ds);
        row.addView(texts);
        row.addView(buildSoundRadio(id));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (soundUsable()) {
                    selectSound(id);
                }
            }
        });
        return row;
    }

    private View buildSoundCustomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setLayoutParams(soundRowLp());
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tLp);
        TextView nm = new TextView(this);
        nm.setText("自定义音效");
        nm.setTextSize(14.5f);
        nm.setTextColor(Color.WHITE);
        texts.addView(nm);
        soundNames.put(BubbleSound.CUSTOM_ID, nm);
        soundCustomSub = new TextView(this);
        soundCustomSub.setTextSize(11.5f);
        soundCustomSub.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(soundCustomSub);
        row.addView(texts);
        TextView pick = new TextView(this);
        pick.setText("选择文件");
        pick.setTextSize(12.5f);
        pick.setTextColor(Color.rgb(59, 130, 246));
        pick.setBackground(rounded(Color.WHITE, dp(999)));
        pick.setPadding(dp(12), dp(6), dp(12), dp(6));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (soundUsable()) {
                    openSoundPicker();
                }
            }
        });
        row.addView(pick);
        View radio = buildSoundRadio(BubbleSound.CUSTOM_ID);
        LinearLayout.LayoutParams rLp =
                (LinearLayout.LayoutParams) radio.getLayoutParams();
        rLp.leftMargin = dp(10);
        radio.setLayoutParams(rLp);
        row.addView(radio);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!soundUsable()) {
                    return;
                }
                // 已有自定义文件就直接选中；还没有就去挑文件。
                if (soundCustomChosen()) {
                    selectSound(BubbleSound.CUSTOM_ID);
                } else {
                    openSoundPicker();
                }
            }
        });
        refreshSoundCustomSub();
        return row;
    }

    /** 单选圈（彩色卡版）：白圈描边 + 白点（选中才亮）。 */
    private View buildSoundRadio(String id) {
        FrameLayout ring = new FrameLayout(this);
        ring.setLayoutParams(new LinearLayout.LayoutParams(
                dp(20), dp(20)));
        View dot = new View(this);
        dot.setBackground(rounded(Color.WHITE, dp(999)));
        FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                dp(10), dp(10));
        dLp.gravity = Gravity.CENTER;
        dot.setLayoutParams(dLp);
        ring.addView(dot);
        soundRadioDots.put(id, dot);
        return ring;
    }

    private GradientDrawable soundRingDrawable(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(2), selected ? Color.WHITE
                : Color.argb(120, 255, 255, 255));
        return g;
    }

    private void refreshSoundSelection() {
        String cur = currentSoundId();
        for (Map.Entry<String, View> e : soundRadioDots
                .entrySet()) {
            boolean sel = e.getKey().equals(cur);
            View dot = e.getValue();
            dot.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            View ring = (View) dot.getParent();
            if (ring != null) {
                ring.setBackground(soundRingDrawable(sel));
            }
            TextView nm = soundNames.get(e.getKey());
            if (nm != null) {
                nm.setTypeface(nm.getTypeface(), sel
                        ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
            }
        }
        refreshSoundCustomSub();
    }

    private void refreshSoundCustomSub() {
        if (soundCustomSub == null) {
            return;
        }
        String name = soundCustomName();
        soundCustomSub.setText(name.isEmpty()
                ? "未选择 · 从手机里挑一个音频文件"
                : "已选：" + name);
    }

    private void selectSound(String id) {
        if (!BubbleSound.isSoundId(id)) {
            return;
        }
        prefs.edit().putString("bubbleSoundId", id).commit();
        SettingsStore.saveAll(this);
        Intent i = new Intent(
                "com.chenan.flymebobo.FEATURE_TOGGLE");
        i.putExtra("key", "bubbleSoundId");
        i.putExtra("soundId", id);
        sendBroadcast(i);
        refreshSoundSelection();
        refreshSoundSubtitle();
    }

    // ================= 试听 =================

    private void previewSound(String id) {
        // 礼貌判断与实际播放同一套：静音/勿扰照实不响，不绕。
        try {
            AudioManager am = (AudioManager) getSystemService(
                    Context.AUDIO_SERVICE);
            if (am != null && am.getRingerMode()
                    != AudioManager.RINGER_MODE_NORMAL) {
                Toast.makeText(this, "系统静音或振动中，试听不出声",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int filter = nm.getCurrentInterruptionFilter();
                if (filter
                        != NotificationManager.INTERRUPTION_FILTER_ALL
                        && filter
                                != NotificationManager
                                        .INTERRUPTION_FILTER_UNKNOWN) {
                    Toast.makeText(this, "勿扰开着，试听不出声",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        releaseSoundPreview();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (BubbleSound.CUSTOM_ID.equals(id)) {
                File f = new File(getFilesDir(),
                        SOUND_CUSTOM_INTERNAL);
                if (!f.isFile() || f.length() <= 0) {
                    Toast.makeText(this, "自定义文件不在了，重新选一个",
                            Toast.LENGTH_SHORT).show();
                    mp.release();
                    return;
                }
                mp.setDataSource(f.getAbsolutePath());
            } else {
                String asset = null;
                for (String[] row : BubbleSound.CATALOG) {
                    if (row[0].equals(id)) {
                        asset = row[2];
                        break;
                    }
                }
                if (asset == null) {
                    mp.release();
                    return;
                }
                // v536 修试听失败：wav 在包里是压缩存储的，
                // openFd + setDataSource(fd, offset, length)
                // 播不了（wei 实测 v535 点试听弹「试听失败」）。
                // 稳妥路子：素材先落 cache 文件，再按文件路径
                // 播——BubbleSound（SystemUI 侧）同款修法。
                File f = soundAssetCacheFile(asset);
                if (f == null) {
                    FileLog.i("SoundPreview no cache file ("
                            + id + "/" + asset + ")");
                    Toast.makeText(this, "试听失败",
                            Toast.LENGTH_SHORT).show();
                    mp.release();
                    return;
                }
                mp.setDataSource(f.getAbsolutePath());
            }
            mp.setOnCompletionListener(
                    new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer p) {
                            if (soundPreviewPlayer == p) {
                                soundPreviewPlayer = null;
                            }
                            try {
                                p.release();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            mp.prepare();
            mp.start();
            soundPreviewPlayer = mp;
        } catch (Throwable t) {
            // 失败要留痕：id + 异常写进日志，下次直接查因。
            FileLog.i("SoundPreview failed (" + id + "): " + t);
            Toast.makeText(this, "试听失败", Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /** assets 里的音效素材先复制到 cache 再播（压缩存储的
     * 资源不能 openFd 直读）。已落盘且大小对得上就复用。 */
    private File soundAssetCacheFile(String asset) {
        try {
            File dir = new File(getCacheDir(), "sound_preview");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File out = new File(dir, asset);
            InputStream in = getAssets().open(asset);
            try {
                int avail = 0;
                try {
                    avail = in.available();
                } catch (Throwable ignored) {
                }
                if (out.isFile() && out.length() > 0
                        && (avail <= 0
                                || out.length() == avail)) {
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
                    // rename 失败就直接拿 tmp 播，一样是文件路径。
                    return tmp.isFile() && tmp.length() > 0
                            ? tmp : null;
                }
                return out.isFile() && out.length() > 0
                        ? out : null;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            FileLog.i("SoundPreview asset copy failed ("
                    + asset + "): " + t);
            return null;
        }
    }

    private void releaseSoundPreview() {
        if (soundPreviewPlayer != null) {
            try {
                soundPreviewPlayer.release();
            } catch (Throwable ignored) {
            }
            soundPreviewPlayer = null;
        }
    }

    // ================= 自定义文件 =================

    private void openSoundPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_PICK_SOUND);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                startActivityForResult(i, REQ_PICK_SOUND);
            } catch (Throwable t2) {
                Toast.makeText(this, "打不开文件选择器",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_SOUND
                || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        Toast.makeText(this, "正在保存自定义音效…",
                Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = importSoundCustom(uri);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (err == null) {
                            refreshSoundSelection();
                            refreshSoundSubtitle();
                            Toast.makeText(MainActivity.this,
                                    "已设为自定义音效",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    err, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** 把选中的音频拷成两份并切到 custom（照 v535 子页版）：
     * App 私有目录给本卡试听，/sdcard/flymebobo/ 公开目录给
     * SystemUI 播放。成功返回 null，失败返回给用户看的文案。 */
    private String importSoundCustom(Uri uri) {
        String displayName = soundQueryDisplayName(uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "自定义音效";
        }
        File internal = new File(getFilesDir(),
                SOUND_CUSTOM_INTERNAL);
        try {
            InputStream in = getContentResolver()
                    .openInputStream(uri);
            if (in == null) {
                return "读不到这个文件";
            }
            try {
                OutputStream out = new FileOutputStream(internal);
                try {
                    byte[] buf = new byte[64 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > SOUND_MAX_CUSTOM_BYTES) {
                            return "文件太大了（上限 20MB）";
                        }
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            FileLog.i("SoundCustom import failed: " + t);
            return "保存失败：" + t.getMessage();
        }
        if (!internal.isFile() || internal.length() <= 0) {
            return "保存失败：文件是空的";
        }
        // 公开副本：SystemUI 播放走这份（App 私有目录它读不到）。
        String ext = "";
        int dot = displayName.lastIndexOf('.');
        if (dot > 0) {
            String e = displayName.substring(dot).toLowerCase(
                    Locale.US);
            if (e.equals(".mp3") || e.equals(".wav")
                    || e.equals(".ogg") || e.equals(".m4a")
                    || e.equals(".aac") || e.equals(".flac")) {
                ext = e;
            }
        }
        File pub = new File(SettingsStore.DIR_PUBLIC,
                SOUND_CUSTOM_INTERNAL + ext);
        boolean ok = soundCopyFile(internal, pub)
                && pub.isFile() && pub.length() > 0;
        if (!ok) {
            ok = soundCopyViaSu(internal, pub);
        }
        if (!ok) {
            return "保存失败：公开目录写不进去";
        }
        String pubPath = pub.getAbsolutePath();
        prefs.edit()
                .putString("bubbleCustomName", displayName)
                .putString("bubbleCustomPath", pubPath)
                .putString("bubbleSoundId", BubbleSound.CUSTOM_ID)
                .commit();
        SettingsStore.saveAll(this);
        Intent ci = new Intent(
                "com.chenan.flymebobo.FEATURE_TOGGLE");
        ci.putExtra("key", "bubbleSoundCustom");
        ci.putExtra("path", pubPath);
        ci.putExtra("name", displayName);
        sendBroadcast(ci);
        Intent si = new Intent(
                "com.chenan.flymebobo.FEATURE_TOGGLE");
        si.putExtra("key", "bubbleSoundId");
        si.putExtra("soundId", BubbleSound.CUSTOM_ID);
        sendBroadcast(si);
        return null;
    }

    private String soundQueryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private boolean soundCopyFile(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            InputStream in = new FileInputStream(src);
            try {
                OutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 普通写不进 /sdcard/flymebobo 时走 su（同 SettingsStore
     * 的兜底：root 机上一定能写，chmod 644 让 SystemUI 能读）。 */
    private boolean soundCopyViaSu(File src, File dst) {
        try {
            String cmd = "mkdir -p " + SettingsStore.DIR_PUBLIC
                    + " && cp " + src.getAbsolutePath() + " "
                    + dst.getAbsolutePath() + " && chmod 644 "
                    + dst.getAbsolutePath();
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", cmd});
            p.waitFor();
            return dst.isFile() && dst.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ================= 按应用（卡内） =================

    private void ensureSoundAppsLoaded() {
        if (soundAppsLoaded || soundAppsLoading
                || soundAppListBox == null) {
            return;
        }
        soundAppsLoading = true;
        final TextView loading = new TextView(this);
        loading.setText("正在读取应用列表…");
        loading.setTextSize(13);
        loading.setTextColor(Color.argb(230, 255, 255, 255));
        loading.setPadding(0, dp(14), 0, dp(8));
        loading.setGravity(Gravity.CENTER);
        soundAppListBox.addView(loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<QuietAppEntry> apps = loadSoundApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        soundAppListBox.removeView(loading);
                        soundAllApps.addAll(apps);
                        for (QuietAppEntry e : apps) {
                            View row = buildSoundAppRow(e);
                            soundRowViews.put(e.pkg, row);
                            soundAppListBox.addView(row);
                        }
                        soundAppsLoaded = true;
                        soundAppsLoading = false;
                        applySoundFilter();
                    }
                });
            }
        }).start();
    }

    /** 全部已装应用：设过规则的在前，再用户应用、系统应用，
     * 组内按名称（同安静规则名单的排法）。 */
    private List<QuietAppEntry> loadSoundApps() {
        PackageManager pm = getPackageManager();
        List<QuietAppEntry> apps = new ArrayList<>();
        try {
            List<ApplicationInfo> ais =
                    pm.getInstalledApplications(0);
            for (ApplicationInfo ai : ais) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                QuietAppEntry e = new QuietAppEntry();
                e.pkg = ai.packageName;
                CharSequence label = pm.getApplicationLabel(ai);
                e.label = label == null ? ai.packageName
                        : label.toString();
                e.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                        != 0;
                try {
                    e.icon = pm.getApplicationIcon(ai);
                } catch (Throwable ignored) {
                }
                apps.add(e);
            }
        } catch (Throwable ignored) {
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(apps, new Comparator<QuietAppEntry>() {
            @Override
            public int compare(QuietAppEntry a, QuietAppEntry b) {
                int ra = rank(a);
                int rb = rank(b);
                if (ra != rb) {
                    return ra - rb;
                }
                return collator.compare(a.label, b.label);
            }

            private int rank(QuietAppEntry e) {
                // v538: 设过规则的和默认名单（默认跟随全局）
                // 的都排最前。
                if (soundRuleMap.containsKey(e.pkg)
                        || SoundRules.isDefaultFollow(
                                MainActivity.this, e.pkg)) {
                    return 0;
                }
                return e.system ? 2 : 1;
            }
        });
        return apps;
    }

    private View buildSoundAppRow(final QuietAppEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams rlp = quietRowLp();
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);
        if (e.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(32), dp(32)));
            row.addView(iv);
        } else {
            View ph = new View(this);
            ph.setBackground(rounded(Color.WHITE, dp(9)));
            ph.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(32), dp(32)));
            row.addView(ph);
        }
        TextView nm = new TextView(this);
        nm.setText(e.label);
        nm.setTextSize(14.5f);
        nm.setTextColor(Color.WHITE);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nLp.leftMargin = dp(10);
        nm.setLayoutParams(nLp);
        row.addView(nm);
        final TextView chip = new TextView(this);
        chip.setTextSize(12.5f);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        paintSoundChip(chip, soundEffectiveValue(e.pkg));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!soundUsable()) {
                    return;
                }
                // v538: 循环走有效值（无 → 跟随全局 → 音效…），
                // 落盘时映射回存储值：点成「无」时，默认名单
                // 应用要存显式 none 压住默认 follow，普通
                // 应用删条目即可（没存本来就是无）。
                String next = nextSoundRuleValue(
                        soundEffectiveValue(e.pkg));
                String stored;
                if (next == null && SoundRules.isDefaultFollow(
                        MainActivity.this, e.pkg)) {
                    stored = SoundRules.VALUE_NONE;
                } else {
                    stored = next;
                }
                if (stored == null) {
                    soundRuleMap.remove(e.pkg);
                } else {
                    soundRuleMap.put(e.pkg, stored);
                }
                SoundRules.setRule(MainActivity.this, e.pkg,
                        stored);
                paintSoundChip(chip, next);
                if (soundSearching()) {
                    soundShownExtra.add(e.pkg);
                }
                applySoundFilter();
            }
        });
        row.addView(chip);
        // 搜索时点中这一行 → 清空搜索后它也留在名单里
        // （同安静规则的做法）。
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (soundSearching()) {
                    soundShownExtra.add(e.pkg);
                }
            }
        });
        return row;
    }

    /** 某应用的有效值：存了按存储值归一（none / 旧 mute
     * 都算「无」）；没存看默认名单（名单内 = follow，其余
     * = 无）。返回 follow / 音效 id / null（无）。 */
    private String soundEffectiveValue(String pkg) {
        String stored = soundRuleMap.get(pkg);
        if (stored != null) {
            return SoundRules.normalize(stored);
        }
        return SoundRules.isDefaultFollow(this, pkg)
                ? SoundRules.VALUE_FOLLOW : null;
    }

    /** chip 按有效值绘制：无（灰）/ 跟随全局（蓝）/ 指定
     * 音效（绿）。入参先归一，旧 mute 也画成「无」。 */
    private void paintSoundChip(TextView chip, String value) {
        value = SoundRules.normalize(value);
        if (value == null) {
            chip.setText("无");
            chip.setTextColor(Color.rgb(102, 112, 127));
            chip.setBackground(rounded(Color.rgb(238, 240, 244),
                    dp(999)));
        } else if (SoundRules.VALUE_FOLLOW.equals(value)) {
            chip.setText("跟随全局");
            chip.setTextColor(Color.rgb(37, 99, 235));
            chip.setBackground(rounded(Color.rgb(224, 235, 255),
                    dp(999)));
        } else {
            chip.setText(BubbleSound.nameFor(value));
            chip.setTextColor(Color.rgb(29, 158, 87));
            chip.setBackground(rounded(Color.rgb(231, 246, 236),
                    dp(999)));
        }
    }

    /** v538 循环顺序（走有效值）：无 → 跟随全局 → 轻木 →
     * 气泡啵 → 清脆叮 → 叮咚 → 水滴 →（设了自定义才有）
     * 自定义 → 回「无」。入参先归一，旧 mute 当「无」。 */
    private String nextSoundRuleValue(String cur) {
        List<String> cycle = new ArrayList<>();
        cycle.add(null);
        cycle.add(SoundRules.VALUE_FOLLOW);
        cycle.add("soft");
        cycle.add("pop");
        cycle.add("ding");
        cycle.add("dingdong");
        cycle.add("drop");
        if (soundCustomChosen()) {
            cycle.add(BubbleSound.CUSTOM_ID);
        }
        int idx = cycle.indexOf(SoundRules.normalize(cur));
        return cycle.get((idx + 1) % cycle.size());
    }

    private boolean soundSearching() {
        return soundAppSearch != null
                && soundAppSearch.getText() != null
                && !soundAppSearch.getText().toString().trim()
                        .isEmpty();
    }

    private void applySoundFilter() {
        if (soundAppListBox == null || !soundAppsLoaded) {
            return;
        }
        String q = soundAppSearch == null
                || soundAppSearch.getText() == null ? ""
                : soundAppSearch.getText().toString().trim()
                        .toLowerCase(Locale.ROOT);
        boolean searching = !q.isEmpty();
        int shown = 0;
        int ruledShown = 0;
        for (QuietAppEntry e : soundAllApps) {
            View row = soundRowViews.get(e.pkg);
            if (row == null) {
                continue;
            }
            boolean show;
            if (searching) {
                show = (e.label + "\n" + e.pkg)
                        .toLowerCase(Locale.ROOT).contains(q);
            } else {
                // 默认列设过规则的 + 默认名单应用（v538：
                // 微信 / QQ / 短信 / 钉钉 / TIM / 企业微信
                // 没设也是「跟随全局」，直接出现在名单里，
                // 同安静规则预置微信/QQ 的做法）。
                show = soundRuleMap.containsKey(e.pkg)
                        || soundShownExtra.contains(e.pkg)
                        || SoundRules.isDefaultFollow(this, e.pkg);
            }
            row.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                shown++;
                if (soundRuleMap.containsKey(e.pkg)) {
                    ruledShown++;
                }
            }
        }
        refreshSoundAppCount(searching, shown, ruledShown);
    }

    private void refreshSoundAppCount(boolean searching, int shown,
            int ruledShown) {
        if (soundAppCount == null) {
            return;
        }
        if (!soundAppsLoaded) {
            soundAppCount.setText("");
            return;
        }
        if (searching) {
            soundAppCount.setText("找到 " + shown
                    + " 个应用，点它右边的标签就能设");
        } else if (soundRuleMap.isEmpty()) {
            soundAppCount.setText("还没给任何应用设过 · "
                    + "没设的默认不响，用搜索找应用点标签设置");
        } else {
            soundAppCount.setText("已给 " + ruledShown
                    + " 个应用单独设了提示音，其余默认不响");
        }
    }

    // ================= 提示音状态读 =================

    /** 展开区可点的总闸：通知气泡开着、提示音也开着。 */
    private boolean soundUsable() {
        return isOn("notif") && isOn("bubbleSound");
    }

    private String currentSoundId() {
        try {
            String id = prefs.getString("bubbleSoundId", "soft");
            if (id != null && BubbleSound.isSoundId(id)) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        return "soft";
    }

    private String soundCustomName() {
        try {
            String n = prefs.getString("bubbleCustomName", "");
            return n == null ? "" : n;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean soundCustomChosen() {
        try {
            String p = prefs.getString("bubbleCustomPath", "");
            return p != null && !p.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }


    /** 卡内整行布局参数（左右各留 12dp，与其他卡内行对齐）。 */
    private LinearLayout.LayoutParams quietRowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        return lp;
    }

    // ================= 临时静默（卡内） =================

    private void startQuietTicker() {
        if (!quietTicking) {
            quietTicking = true;
            quietTicker.removeCallbacks(quietTick);
            quietTicker.postDelayed(quietTick, 1000);
        }
    }

    private void stopQuietTicker() {
        quietTicking = false;
        quietTicker.removeCallbacks(quietTick);
    }

    private void buildQuietChips() {
        if (quietChipsBox == null) {
            return;
        }
        TempSilence.State s = TempSilence.readState(this);
        boolean active = s.active();
        String key = active + "|" + quietSelectedMinutes;
        if (key.equals(quietChipsKey)
                && quietChipsBox.getChildCount() > 0) {
            return;
        }
        quietChipsKey = key;
        quietChipsBox.removeAllViews();
        for (int i = 0; i < TempSilence.DURATIONS.length; i++) {
            final int min = TempSilence.DURATIONS[i];
            TextView chip = new TextView(this);
            chip.setText(min >= 60 ? (min / 60) + " 小时"
                    : min + " 分钟");
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(14);
            boolean sel = min == quietSelectedMinutes;
            chip.setTextColor(sel ? QUIET_INDIGO : Color.WHITE);
            if (sel) {
                chip.setTypeface(chip.getTypeface(),
                        android.graphics.Typeface.BOLD);
            }
            chip.setBackground(rounded(sel ? Color.WHITE
                    : Color.argb(60, 255, 255, 255), dp(12)));
            chip.setPadding(0, dp(10), 0, dp(10));
            LinearLayout.LayoutParams p =
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                p.leftMargin = dp(8);
            }
            chip.setLayoutParams(p);
            chip.setAlpha(active ? 0.45f : 1f);
            if (!active) {
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        quietSelectedMinutes = min;
                        persistQuietSettings();
                        buildQuietChips();
                        refreshQuietSilence();
                    }
                });
            }
            quietChipsBox.addView(chip);
        }
    }

    private void persistQuietSettings() {
        TempSilence.updateSettings(this, quietSelectedMinutes,
                quietLinkRow.on, quietCallRow.on);
    }

    private boolean policyGranted() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null
                    && nm.isNotificationPolicyAccessGranted();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshQuietSilence() {
        if (quietStatusTitle == null) {
            return;
        }
        // 与 TempSilenceActivity.refresh 同语义：先对账（过期/
        // 手动关勿扰都会在这里收尾，两条路径幂等）。
        TempSilence.onInterruptionFilterChanged(this);
        TempSilence.State s = TempSilence.readState(this);
        if (s.until != 0 && !s.active()) {
            TempSilence.end(this, "expired");
            s = TempSilence.readState(this);
        }
        boolean active = s.active();
        if (active) {
            long remainMs = s.until - System.currentTimeMillis();
            quietStatusTitle.setText("正在静默 · 到点自动恢复");
            quietCountdown.setText(formatQuietRemain(remainMs));
            quietCountdown.setVisibility(View.VISIBLE);
            String endAt = new SimpleDateFormat("HH:mm",
                    Locale.CHINA).format(new Date(s.until));
            quietStatusSub.setText("恢复时间 " + endAt
                    + " · 消息先排队，恢复后按顺序补弹"
                    + (s.linkDnd ? " · 系统勿扰联动中" : ""));
            quietActionBtn.setText("提前取消，马上恢复");
        } else {
            quietStatusTitle.setText("临时静默未开启");
            quietCountdown.setVisibility(View.GONE);
            quietStatusSub.setText("按一下开启，"
                    + (quietSelectedMinutes >= 60
                            ? (quietSelectedMinutes / 60) + " 小时"
                            : quietSelectedMinutes + " 分钟")
                    + "后自动恢复，随时能提前取消。");
            quietActionBtn.setText("开启临时静默");
        }
        quietDndHint.setVisibility(s.linkDnd && !policyGranted()
                ? View.VISIBLE : View.GONE);
        quietLinkRow.setOn(s.linkDnd);
        quietCallRow.setOn(s.callBreak);
        if (quietSelectedMinutes != s.minutes) {
            quietSelectedMinutes = s.minutes;
        }
        buildQuietChips();
    }

    private static String formatQuietRemain(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long ss = sec % 60;
        if (h > 0) {
            return h + ":" + pad2(m) + ":" + pad2(ss);
        }
        return pad2(m) + ":" + pad2(ss);
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /**
     * v528 定稿、v529 抽成共享实现、v531 换 v2 设计：全屏
     * 开启回执动画（渐变幕布 + 圆形插画，构建与时间线在
     * SilenceAnim 里）。App 内走这里（decorView），SystemUI
     * 侧走同一个构建器，两条路一个样。只在开启成功时播。
     */
    private void showSilenceStartAnim(long untilMs) {
        showFullscreenAnim(SilenceAnim.SILENCE,
                SilenceAnim.subtitleTimed(untilMs));
    }

    /** v533: the silence EXIT receipt (wei: 跟场景退出同款)
     * — same builder, moon illustration, exit copy. Played
     * in-app only for this page's own cancel tap, with the
     * flushed count SystemUI broadcast back; expiry and the
     * DND-off road are receipted by SystemUI itself. */
    private void showSilenceExitAnim(int flushed) {
        showFullscreenAnim(SilenceAnim.SILENCE_EXIT,
                flushed > 0
                        ? SilenceAnim.subtitleSilenceExitFlushed(
                                flushed)
                        : SilenceAnim.subtitleSilenceExitEmpty());
    }

    /** v531: 在设置里拨开「场景模式」开关时播的 B 版全屏
     * 动画（wei 拍板：开关动画用全屏版；进游戏时 SystemUI
     * 侧播同款，两个触发点视觉完全一致）。关开关不播。
     * v541: 与 SystemUI 的进/出场景动画共用 30 分钟冷却
     * （SceneAnimCooldown），冷却中只跳动画、开关照常生效；
     * 真播了才记时间并广播给 SystemUI 同步。 */
    private void showSceneStartAnim() {
        long remain = SceneAnimCooldown.remainingMs();
        if (remain > 0) {
            FileLog.i("SceneAnim cooldown skip (settings), "
                    + (remain / 60000) + " min left");
            return;
        }
        boolean played = showFullscreenAnim(SilenceAnim.SCENE,
                SilenceAnim.subtitleScene());
        if (played) {
            long ts = SceneAnimCooldown.markPlayed();
            try {
                Intent i = new Intent(
                        NotificationService.ACTION_SCENE_ANIM);
                i.putExtra("ts", ts);
                sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }

    /** v541: 返回是否真的把动画播出去了（showSceneStartAnim
     * 靠它决定记不记冷却时间）；老调用方忽略返回值。 */
    private boolean showFullscreenAnim(SilenceAnim.Style style,
            String subtitle) {
        try {
            final ViewGroup decor =
                    (ViewGroup) getWindow().getDecorView();
            View old = decor.findViewWithTag(SilenceAnim.TAG);
            if (old != null) {
                decor.removeView(old);
            }
            final SilenceAnim.Handle[] hh =
                    new SilenceAnim.Handle[1];
            SilenceAnim.Handle h = SilenceAnim.create(this,
                    style, subtitle,
                    new Runnable() {
                        @Override
                        public void run() {
                            if (hh[0] != null
                                    && hh[0].veil.getParent()
                                            != null) {
                                decor.removeView(hh[0].veil);
                            }
                        }
                    });
            hh[0] = h;
            decor.addView(h.veil, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            h.play();
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ================= 应用名单（卡内） =================

    private void ensureQuietAppsLoaded() {
        if (quietAppsLoaded || quietAppsLoading
                || quietListBox == null) {
            return;
        }
        quietAppsLoading = true;
        final TextView loading = new TextView(this);
        loading.setText("正在读取应用列表…");
        loading.setTextSize(13);
        loading.setTextColor(Color.argb(230, 255, 255, 255));
        loading.setPadding(0, dp(16), 0, dp(8));
        loading.setGravity(Gravity.CENTER);
        quietListBox.addView(loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<QuietAppEntry> apps = loadQuietApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        quietListBox.removeView(loading);
                        quietAllApps.addAll(apps);
                        for (QuietAppEntry e : apps) {
                            View row = buildQuietAppRow(e);
                            quietRowViews.put(e.pkg, row);
                            quietListBox.addView(row);
                        }
                        quietAppsLoaded = true;
                        quietAppsLoading = false;
                        recomputeQuietPinned();
                        applyQuietFilter();
                        refreshSceneLinkage();
                    }
                });
            }
        }).start();
    }

    /** 全部已安装应用（用户 + 系统，同旧两页）：已设过名单的
     * 排前面，再用户应用、系统应用，组内按名称。 */
    private List<QuietAppEntry> loadQuietApps() {
        PackageManager pm = getPackageManager();
        List<QuietAppEntry> apps = new ArrayList<>();
        try {
            List<ApplicationInfo> ais =
                    pm.getInstalledApplications(0);
            for (ApplicationInfo ai : ais) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                QuietAppEntry e = new QuietAppEntry();
                e.pkg = ai.packageName;
                CharSequence label = pm.getApplicationLabel(ai);
                e.label = label == null ? ai.packageName
                        : label.toString();
                e.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                        != 0;
                try {
                    e.icon = pm.getApplicationIcon(ai);
                } catch (Throwable ignored) {
                }
                apps.add(e);
            }
        } catch (Throwable ignored) {
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(apps, new Comparator<QuietAppEntry>() {
            @Override
            public int compare(QuietAppEntry a, QuietAppEntry b) {
                int ra = rank(a);
                int rb = rank(b);
                if (ra != rb) {
                    return ra - rb;
                }
                return collator.compare(a.label, b.label);
            }

            private int rank(QuietAppEntry e) {
                if (quietRules.containsKey(e.pkg)
                        || quietScene.containsKey(e.pkg)
                        || SceneExceptions.effective(quietScene, e.pkg)
                                == SceneExceptions.BREAK) {
                    return 0;
                }
                return e.system ? 2 : 1;
            }
        });
        return apps;
    }

    private View buildQuietAppRow(final QuietAppEntry e) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = quietRowLp();
        blp.topMargin = dp(10);
        box.setLayoutParams(blp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tile = new LinearLayout(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(rounded(Color.WHITE, dp(10)));
        tile.setLayoutParams(new LinearLayout.LayoutParams(
                dp(40), dp(40)));
        if (e.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(30), dp(30)));
            tile.addView(iv);
        } else {
            TextView tv = new TextView(this);
            tv.setText(e.label.isEmpty() ? "?"
                    : e.label.substring(0, 1));
            tv.setTextSize(15);
            tv.setTextColor(QUIET_INDIGO);
            tile.addView(tv);
        }
        head.addView(tile);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(14.5f);
        name.setTextColor(Color.WHITE);
        name.setTypeface(name.getTypeface(),
                android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView pkg = new TextView(this);
        pkg.setText(e.pkg + (e.system ? " · 系统" : ""));
        pkg.setTextSize(10.5f);
        pkg.setTextColor(Color.argb(230, 255, 255, 255));
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(pkg);
        head.addView(texts);
        box.addView(head);

        int ruleSel = 0;
        Integer curRule = quietRules.get(e.pkg);
        int ruleMode = curRule == null ? AppRules.DEFAULT : curRule;
        for (int i = 0; i < QUIET_RULE_MODES.length; i++) {
            if (QUIET_RULE_MODES[i] == ruleMode) {
                ruleSel = i;
            }
        }
        box.addView(quietSegRow("平时", QUIET_RULE_LABELS, ruleSel,
                new SegListener() {
                    @Override
                    public void onSelect(int idx) {
                        setQuietRuleMode(e.pkg,
                                QUIET_RULE_MODES[idx]);
                    }
                }));
        int sceneMode = SceneExceptions.effective(quietScene, e.pkg);
        int sceneSel = sceneMode == SceneExceptions.BREAK ? 1 : 0;
        View sceneSegRow = quietSegRow("场景里", QUIET_SCENE_LABELS,
                sceneSel, new SegListener() {
                    @Override
                    public void onSelect(int idx) {
                        setQuietSceneMode(e.pkg,
                                QUIET_SCENE_MODES[idx]);
                    }
                });
        box.addView(sceneSegRow);
        // v530: 记下这一列，场景模式总开关关时整列置灰联动。
        quietSceneSegRows.put(e.pkg, sceneSegRow);
        quietSceneSegs.put(e.pkg, lastQuietSeg);
        // 搜索时点中这一行 → 清空搜索后它也留在名单里。
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (quietSearching()) {
                    quietShownExtra.add(e.pkg);
                }
            }
        });
        return box;
    }

    /** 一行分段：左侧小标签 + 胶囊分段控件（复用 SegControl）。 */
    private View quietSegRow(String label, String[] labels,
            int sel, SegListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(11.5f);
        lab.setTextColor(Color.argb(230, 255, 255, 255));
        lab.setLayoutParams(new LinearLayout.LayoutParams(
                dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(lab);
        SegControl seg = new SegControl(labels, sel, QUIET_INDIGO);
        seg.box.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seg.listener = listener;
        lastQuietSeg = seg;
        row.addView(seg.box);
        return row;
    }

    private boolean quietSearching() {
        return quietAppSearch != null
                && quietAppSearch.getText() != null
                && !quietAppSearch.getText().toString().trim()
                        .isEmpty();
    }

    private void setQuietRuleMode(String pkg, int mode) {
        if (mode == AppRules.DEFAULT) {
            quietRules.remove(pkg);
        } else {
            quietRules.put(pkg, mode);
        }
        AppRules.setRule(this, pkg, mode);
        afterQuietRuleChange(pkg);
    }

    private void setQuietSceneMode(String pkg, int mode) {
        quietScene.put(pkg, mode);
        SceneExceptions.setMode(this, pkg, mode);
        afterQuietRuleChange(pkg);
    }

    private void afterQuietRuleChange(String pkg) {
        if (quietSearching()) {
            quietShownExtra.add(pkg);
        }
        recomputeQuietPinned();
        applyQuietFilter();
    }

    /** 默认展示名单：设过非默认规则的 + 场景里突破的 +
     * 微信/QQ 常驻（wei 定）。 */
    private void recomputeQuietPinned() {
        quietPinned.clear();
        quietPinned.addAll(quietRules.keySet());
        quietPinned.addAll(quietScene.keySet());
        for (QuietAppEntry e : quietAllApps) {
            if (SceneExceptions.effective(quietScene, e.pkg)
                    == SceneExceptions.BREAK) {
                quietPinned.add(e.pkg);
            }
        }
        quietPinned.add("com.tencent.mm");
        quietPinned.add("com.tencent.mobileqq");
    }

    private void applyQuietFilter() {
        if (quietListBox == null || !quietAppsLoaded) {
            return;
        }
        String q = quietAppSearch == null
                || quietAppSearch.getText() == null ? ""
                : quietAppSearch.getText().toString().trim()
                        .toLowerCase(Locale.ROOT);
        boolean searching = !q.isEmpty();
        int shown = 0;
        for (QuietAppEntry e : quietAllApps) {
            View row = quietRowViews.get(e.pkg);
            if (row == null) {
                continue;
            }
            boolean show;
            if (searching) {
                show = (e.label + "\n" + e.pkg)
                        .toLowerCase(Locale.ROOT).contains(q);
            } else {
                show = quietPinned.contains(e.pkg)
                        || quietShownExtra.contains(e.pkg);
            }
            row.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                shown++;
            }
        }
        refreshQuietCount(searching, shown);
    }

    private void refreshQuietCount(boolean searching, int shown) {
        if (quietCountText == null) {
            return;
        }
        if (!quietAppsLoaded) {
            quietCountText.setText("");
            return;
        }
        if (searching) {
            quietCountText.setText("找到 " + shown
                    + " 个应用，点中任意一个就能设");
            return;
        }
        int pinnedShown = 0;
        int breaks = 0;
        for (QuietAppEntry e : quietAllApps) {
            if (quietPinned.contains(e.pkg)) {
                pinnedShown++;
            }
            if (SceneExceptions.effective(quietScene, e.pkg)
                    == SceneExceptions.BREAK) {
                breaks++;
            }
        }
        quietCountText.setText("名单里 " + pinnedShown
                + " 个应用 · 场景里突破 " + breaks
                + " 个，其余排队、退出场景补弹");
    }

    /** v527: 呈现形式三模式——落回 card/bigCard 两个现有标志位：
     * 0 胶囊模式＝card 关+bigCard 关；1 展开卡片＝card 开+bigCard
     * 关（默认）；2 大卡片模式＝bigCard 开。不新造存储。 */
    private int presentMode() {
        if (isOn("bigCard")) {
            return 2;
        }
        return isOn("card") ? 1 : 0;
    }

    /** v527: 「打开方式」合并卡（青色）：呈现形式三段单选 +
     * 点卡片打开 App 单选（小窗/全屏＝miniwindow）+ 自动消散 /
     * 自动销毁两行开关（从原顶层卡迁入，标志位不变）。 */
    private View buildOpenWayCard() {
        final int color = Color.rgb(20, 184, 166);
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(color, dp(22)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView icon = new TextView(this);
        icon.setText("开");
        icon.setTextSize(15);
        icon.setTextColor(color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(10)));
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(36), dp(36)));
        head.addView(icon);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("打开方式");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(),
                android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText("通知怎么出现、点开之后怎么打开");
        sub.setTextSize(12);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(sub);
        head.addView(texts);
        final TextView chev = buildExpandChevron();
        head.addView(chev);
        card.addView(head);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(0, 0, 0, dp(12));
        card.addView(body);
        openWayBody = body;
        openWayChev = chev;
        final boolean[] expanded = new boolean[]{false};
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0]
                        ? View.VISIBLE : View.GONE);
                setChevronExpanded(chev, expanded[0]);
            }
        });
        // 搜索展开钩子：直接改 visibility + 状态。
        body.setTag(expanded);

        // ---- 呈现形式 ----
        body.addView(cardRowLabel("呈现形式", "通知出现的样子，三选一"));
        final SegControl presentSeg = new SegControl(
                new String[]{"胶囊模式", "展开卡片", "大卡片模式"},
                presentMode(), Color.rgb(51, 51, 51));
        LinearLayout.LayoutParams psLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        psLp.leftMargin = dp(12);
        psLp.rightMargin = dp(12);
        psLp.topMargin = dp(8);
        presentSeg.box.setLayoutParams(psLp);
        presentSeg.listener = new SegListener() {
            @Override
            public void onSelect(int idx) {
                setFeatureFlag("card", idx == 1);
                setFeatureFlag("bigCard", idx == 2);
            }
        };
        body.addView(presentSeg.box);
        searchTargets.put("present", presentSeg.box);
        body.addView(cardDesc("胶囊模式：只出胶囊，点胶囊不展开。"
                + "展开卡片：先出胶囊，点一下展开看全文。"
                + "大卡片模式：通知一来就是大卡片依次排列。"
                + "音乐、来电不走这个设置。"));

        // ---- 点卡片打开 App ----
        body.addView(cardRowLabel("点卡片打开 App",
                "点展开的卡片时，用哪种方式打开"));
        final SegControl openSeg = new SegControl(
                new String[]{"小窗", "全屏"},
                isOn("miniwindow") ? 0 : 1, Color.rgb(51, 51, 51));
        LinearLayout.LayoutParams osLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        osLp.leftMargin = dp(12);
        osLp.rightMargin = dp(12);
        osLp.topMargin = dp(8);
        openSeg.box.setLayoutParams(osLp);
        openSeg.listener = new SegListener() {
            @Override
            public void onSelect(int idx) {
                setFeatureFlag("miniwindow", idx == 0);
            }
        };
        body.addView(openSeg.box);
        searchTargets.put("openapp", openSeg.box);
        body.addView(cardDesc("小窗不离开当前界面，全屏直接跳过去。"));

        // ---- 自动消散 / 自动销毁 ----
        Feature dissipate = featureFor("blankDissipate");
        if (dissipate != null) {
            View row = buildSubSwitch(dissipate);
            subRowViews.put(dissipate.key, row);
            body.addView(row);
        }
        body.addView(cardDesc("点一下屏幕空白处，所有没钉住的胶囊"
                + "逐条消散。关掉后只能右滑逐个消散、或左滑收进扇子。"));
        Feature destroy = featureFor("autoDestroy");
        if (destroy != null) {
            View row = buildSubSwitch(destroy);
            subRowViews.put(destroy.key, row);
            body.addView(row);
        }
        body.addView(cardDesc("开着时消散的通知同时从通知栏消失；"
                + "关着（默认）通知还留在通知栏。"
                + "音乐通知不撤销，撤销会停掉音乐。"));
        refreshDestroyLinkage();
        return card;
    }

    /** v527: 合并卡里的行标题（白字粗体 + 副标题）。 */
    private View cardRowLabel(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Color.WHITE);
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        box.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(Color.argb(230, 255, 255, 255));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(1);
            s.setLayoutParams(slp);
            box.addView(s);
        }
        return box;
    }

    /** v527: 卡内说明小字（与子开关行同宽对齐）。 */
    private View cardDesc(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.argb(210, 255, 255, 255));
        t.setLineSpacing(0, 1.6f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /** v527: 展开某张卡（搜索跳转用）。key 是 feature key 或
     * "appearance" / "openway"。 */
    private void expandCard(String key) {
        if (key == null) {
            return;
        }
        if ("appearance".equals(key)) {
            if (appearanceBody != null
                    && appearanceBody.getVisibility() != View.VISIBLE) {
                appearanceBody.setVisibility(View.VISIBLE);
                if (appearanceChev != null) {
                    setChevronExpanded(appearanceChev, true);
                }
            }
            return;
        }
        if ("openway".equals(key)) {
            if (openWayBody != null
                    && openWayBody.getVisibility() != View.VISIBLE) {
                openWayBody.setVisibility(View.VISIBLE);
                Object tag = openWayBody.getTag();
                if (tag instanceof boolean[]) {
                    ((boolean[]) tag)[0] = true;
                }
                if (openWayChev != null) {
                    setChevronExpanded(openWayChev, true);
                }
            }
            return;
        }
        if ("quiet".equals(key)) {
            if (quietBody != null
                    && quietBody.getVisibility() != View.VISIBLE) {
                quietBody.setVisibility(View.VISIBLE);
                Object tag = quietBody.getTag();
                if (tag instanceof boolean[]) {
                    ((boolean[]) tag)[0] = true;
                }
                setChevronExpanded(quietChev, true);
            }
            refreshQuietSilence();
            ensureQuietAppsLoaded();
            startQuietTicker();
            return;
        }
        if ("sound".equals(key)) {
            if (soundBody != null
                    && soundBody.getVisibility() != View.VISIBLE) {
                soundBody.setVisibility(View.VISIBLE);
                Object tag = soundBody.getTag();
                if (tag instanceof boolean[]) {
                    ((boolean[]) tag)[0] = true;
                }
                setChevronExpanded(soundChev, true);
            }
            ensureSoundAppsLoaded();
            return;
        }
        View[] b = cardBodies.get(key);
        boolean[] e = cardExpanded.get(key);
        if (b != null && e != null && !e[0]) {
            e[0] = true;
            if (b[0] != null) {
                b[0].setVisibility(View.VISIBLE);
            }
            if (b[1] != null) {
                b[1].setVisibility(View.VISIBLE);
            }
        }
    }

    // ================= v527 设置搜索 =================

    private void addSearch(String title, String path, String keywords,
            String targetId, String expandKey) {
        searchIndex.add(new SearchEntry(title, path, keywords,
                searchTargets.get(targetId), expandKey));
    }

    private void addSearch(String title, String path, String keywords,
            View target, String expandKey) {
        searchIndex.add(new SearchEntry(title, path, keywords,
                target, expandKey));
    }

    /** 索引 = 主页卡片 + 卡内子项 + 子页面项；keywords 含同义词
     * （搜「安静」命中临时静默、搜「验证码」命中打码等）。 */
    private void buildSearchIndex() {
        searchIndex.clear();
        // 胶囊类型
        addSearch("通知气泡", "胶囊类型", "通知 气泡 胶囊 贴边 总开关",
                topCardViews.size() > 0 ? topCardViews.get(0) : null,
                null);
        // v539: 回看胶囊是通知气泡卡里的子开关。
        addSearch("回看胶囊", "通知气泡 › 回看胶囊",
                "回看 错过 历史 找回 划掉 恢复",
                subRowViews.get("recallCapsule"), "notif");
        // v536: 提示音是主页展开卡（贴着通知气泡卡），搜索
        // 展开这张卡定位（同安静规则条目的做法）。
        addSearch("气泡提示音", "胶囊类型 › 气泡提示音",
                "提示音 声音 音效 响 啵 弹出声 自定义 按应用",
                soundCardView, "sound");
        addSearch("音乐胶囊", "胶囊类型", "音乐 播放器 歌词 歌手 专辑",
                topCardViews.size() > 1 ? topCardViews.get(1) : null,
                null);
        addSearch("音乐卡片", "音乐胶囊 › 音乐卡片",
                "音乐 展开 播放器卡片 点音乐",
                subRowViews.get("musicCard"), "music");
        addSearch("视频App", "音乐胶囊 › 视频App",
                "视频 B站 哔哩哔哩 播放",
                subRowViews.get("musicVideo"), "music");
        addSearch("模拟音乐", "音乐胶囊 › 模拟音乐",
                "模拟 假 调试 调大小",
                subRowViews.get("simMusic"), "music");
        addSearch("反方向的钟", "音乐胶囊 › 反方向的钟",
                "彩蛋 钟 跑马灯",
                subRowViews.get("musicLed"), "music");
        addSearch("QQ音乐flyme版免费听", "音乐胶囊 › QQ音乐免费听",
                "qq 音乐 免费 时长 补时长",
                subRowViews.get("qqFree"), "music");
        addSearch("下载与安装", "胶囊类型", "下载 安装 进度 速度",
                topCardViews.size() > 2 ? topCardViews.get(2) : null,
                null);
        addSearch("验证码与隐私", "胶囊类型",
                "验证码 取件码 复制 钉住 柜号 快递 短信码 隐私",
                topCardViews.size() > 3 ? topCardViews.get(3) : null,
                null);
        addSearch("自动复制验证码", "验证码与隐私 › 自动复制验证码",
                "复制 剪贴板 自动复制 验证码 粘贴",
                subRowViews.get("autoCopyCode"), "code");
        addSearch("敏感打码", "验证码与隐私 › 敏感打码",
                "打码 验证码 锁屏 录屏 投屏 隐私 隐藏 码 敏感",
                subRowViews.get("mask"), "code");
        addSearch("锁屏时", "验证码与隐私 › 锁屏时", "锁屏 打码 验证码",
                subRowViews.get("maskLock"), "code");
        addSearch("录屏、投屏时", "验证码与隐私 › 录屏、投屏时",
                "录屏 投屏 打码 验证码 录像",
                subRowViews.get("maskRecord"), "code");
        addSearch("普通应用也打码", "验证码与隐私 › 普通应用也打码",
                "普通应用 隐藏 内容 打码 微信 消息",
                subRowViews.get("maskApps"), "code");
        addSearch("来电", "胶囊类型", "电话 来电 接听 挂断 扬声器",
                topCardViews.size() > 4 ? topCardViews.get(4) : null,
                null);
        addSearch("来电放右边", "来电 › 来电放右边", "右边 位置 右手",
                subRowViews.get("callRight"), "live");
        addSearch("模拟来电", "来电 › 模拟来电", "模拟 假 调试 调位置",
                subRowViews.get("simCall"), "live");
        // 呈现与打开
        searchIndex.add(new SearchEntry("打开方式", "呈现与打开",
                "打开 呈现 大卡片 展开 小窗 全屏",
                searchTargets.get("present"), "openway"));
        addSearch("呈现形式", "打开方式 › 呈现形式",
                "大卡片 大卡片模式 胶囊模式 展开卡片 呈现 形式 模式 三选一",
                "present", "openway");
        addSearch("点卡片打开 App", "打开方式 › 点卡片打开 App",
                "小窗 全屏 打开 应用 app", "openapp", "openway");
        addSearch("自动消散", "打开方式 › 自动消散",
                "消散 空白 清除 点空白",
                subRowViews.get("blankDissipate"), "openway");
        addSearch("自动销毁", "打开方式 › 自动销毁",
                "销毁 撤销 通知栏 一起没",
                subRowViews.get("autoDestroy"), "openway");
        // 安静规则（v528：收回主页展开卡，点结果展开卡定位）
        addSearch("临时静默", "安静规则 › 临时静默",
                "安静 静默 勿扰 开会 午睡 午休 一段时间",
                quietSilenceAnchor, "quiet");
        addSearch("应用规则", "安静规则 › 应用名单",
                "应用 规则 不弹 只贴边 单个 每个应用 名单",
                quietAppsAnchor, "quiet");
        addSearch("场景例外", "安静规则 › 应用名单",
                "游戏 视频 全屏 突破 横屏 场景 例外 排队",
                quietAppsAnchor, "quiet");
        addSearch("场景模式", "安静规则 › 场景模式",
                "游戏 视频 全屏 场景 模式 排队 总开关",
                quietSceneModeAnchor, "quiet");
        // 外观
        searchIndex.add(new SearchEntry("外观设置", "外观",
                "外观 扇子 位置 大小 圆角 长相",
                searchTargets.get("appearanceCard"), null));
        addSearch("胶囊自动取色", "外观设置 › 胶囊自动取色",
                "颜色 取色 图标 底色 主色",
                subRowViews.get("autoTint"), "appearance");
        addSearch("胶囊滚动", "外观设置 › 胶囊滚动",
                "滚动 太长 省略 截断 跑马灯",
                subRowViews.get("scroll"), "appearance");
        addSearch("扇子自动变淡", "外观设置 › 扇子自动变淡",
                "扇子 变淡 半透明 挡屏幕 挡住",
                subRowViews.get("fanAutoDim"), "appearance");
        addSearch("扇子位置", "外观设置 › 扇子位置",
                "扇子 位置 高度 上下", "fanpos", "appearance");
        // 页脚 / 状态行
        addSearch("重启系统界面", "顶部状态行",
                "重启 系统界面 systemui 卡死", "restart", null);
        addSearch("检查更新", "页脚", "更新 版本 新版", "update", null);
        addSearch("功能引导", "页脚", "引导 教程 新手 重看",
                "guide", null);
    }

    private void runSearch(String q) {
        if (searchResults == null) {
            return;
        }
        searchResults.removeAllViews();
        String needle = q == null ? "" : q.trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            searchResults.setVisibility(View.GONE);
            return;
        }
        int shown = 0;
        for (final SearchEntry e : searchIndex) {
            if (shown >= 8) {
                break;
            }
            String hay = (e.title + " " + e.path + " " + e.keywords)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!hay.contains(needle)) {
                continue;
            }
            shown++;
            searchResults.addView(buildSearchResultRow(e));
        }
        if (shown == 0) {
            TextView none = new TextView(this);
            none.setText("没有找到，试试：");
            none.setTextSize(13);
            none.setTextColor(Color.rgb(107, 114, 128));
            none.setPadding(dp(14), dp(10), dp(14), dp(4));
            searchResults.addView(none);
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(dp(14), dp(2), dp(14), dp(10));
            for (final String word
                    : new String[]{"打码", "静默", "游戏"}) {
                TextView chip = new TextView(this);
                chip.setText(word);
                chip.setTextSize(12);
                chip.setTextColor(Color.rgb(74, 95, 217));
                chip.setBackground(rounded(
                        Color.rgb(238, 242, 255), dp(999)));
                chip.setPadding(dp(10), dp(4), dp(10), dp(4));
                LinearLayout.LayoutParams clp =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(6);
                chip.setLayoutParams(clp);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        searchBox.setText(word);
                        searchBox.setSelection(word.length());
                    }
                });
                chips.addView(chip);
            }
            searchResults.addView(chips);
        }
        searchResults.setVisibility(View.VISIBLE);
    }

    private View buildSearchResultRow(final SearchEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(rounded(Color.rgb(245, 247, 250), dp(12)));
        row.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(e.title);
        t.setTextSize(14);
        t.setTextColor(Color.rgb(17, 24, 39));
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        row.addView(t);
        TextView p = new TextView(this);
        p.setText(e.path);
        p.setTextSize(11);
        p.setTextColor(Color.rgb(138, 143, 152));
        row.addView(p);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpToSearchEntry(e);
            }
        });
        return row;
    }

    private void jumpToSearchEntry(final SearchEntry e) {
        if (e.activity != null) {
            try {
                Intent i = new Intent(MainActivity.this, e.activity);
                if (e.highlight != null) {
                    i.putExtra("highlight", e.highlight);
                }
                startActivity(i);
            } catch (Throwable ignored) {
            }
            return;
        }
        final View target = e.target;
        if (target == null) {
            return;
        }
        // 收起结果、收起键盘，先展开父卡，下一帧再滚（展开会改布局，
        // 位置要等布局完成才准——分析里点明的时序坑）。
        searchResults.setVisibility(View.GONE);
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(
                                    Context.INPUT_METHOD_SERVICE);
            if (imm != null && searchBox != null) {
                imm.hideSoftInputFromWindow(
                        searchBox.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
        expandCard(e.expandKey);
        target.post(new Runnable() {
            @Override
            public void run() {
                scrollToView(target);
                flashView(target);
            }
        });
    }

    /** 目标视图在 ScrollView 内容里的纵坐标（逐级累加 getTop）。 */
    private void scrollToView(View target) {
        if (scrollView == null || target == null) {
            return;
        }
        int y = 0;
        View v = target;
        while (v != null && v != scrollView) {
            y += v.getTop();
            Object parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        scrollView.smoothScrollTo(0, Math.max(0, y - dp(90)));
    }

    /** 高亮闪烁：透明度脉冲两下（不改背景色，彩色卡上也看得见）。 */
    private void flashView(final View v) {
        try {
            v.animate().cancel();
            final float orig = v.getAlpha();
            v.animate().alpha(0.35f).setDuration(200)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            v.animate().alpha(orig).setDuration(200)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            v.animate().alpha(0.35f)
                                                    .setDuration(200)
                                                    .withEndAction(
                                                            new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    v.animate()
                                                                            .alpha(orig)
                                                                            .setDuration(200)
                                                                            .start();
                                                                }
                                                            })
                                                    .start();
                                        }
                                    }).start();
                        }
                    }).start();
        } catch (Throwable ignored) {
        }
    }

    private View buildFeatureCard(final Feature f) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(f.color, dp(22)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);
        if (!isOn(f.key)) card.setAlpha(0.45f);

        // Header row: icon + titles + switch.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView icon = new TextView(this);
        icon.setText(f.icon);
        icon.setTextSize(15);
        icon.setTextColor(f.color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(10)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
        icon.setLayoutParams(ilp);
        head.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);

        TextView title = new TextView(this);
        title.setText(f.title);
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText(f.subtitle);
        sub.setTextSize(12);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(sub);
        head.addView(texts);

        final View sw = buildSwitch(f, card);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(dp(48), dp(28));
        swlp.leftMargin = dp(8);
        sw.setLayoutParams(swlp);
        head.addView(sw);

        card.addView(head);

        // Body: full description, collapsed by default.
        final TextView body = new TextView(this);
        body.setText(f.body);
        body.setTextSize(13);
        body.setTextColor(Color.argb(242, 255, 255, 255));
        body.setLineSpacing(0, 1.7f);
        body.setPadding(dp(12), 0, dp(12), dp(12));
        body.setVisibility(View.GONE);
        card.addView(body);

        // v336: 嵌套子开关（音乐胶囊里放音乐卡片 + QQ免费听）。
        // 点开卡片后和说明一起显示在卡片里面。
        final LinearLayout subBox = new LinearLayout(this);
        subBox.setOrientation(LinearLayout.VERTICAL);
        subBox.setVisibility(View.GONE);
        // v459 (user "把实时去掉吧"): 来电 / 音乐卡的实时预览 mock
        // 已撤掉——mock 尺寸不对（apMin 没覆盖来电/音乐键，读到的是
        // 滑条 progress 不是 dp 值），改成和来电一样的模拟（simMusic
        // 开关，见 FEATURES）。滑条 onDrag 传 null，只发广播直播。
        for (Feature s : FEATURES) {
            if (f.key.equals(s.parent)) {
                // v527: 验证码与隐私卡里，打码四开关前加「敏感打码」
                // 分区标题（码本身 / 打码两区，wei 定）。
                if ("code".equals(f.key) && "mask".equals(s.key)) {
                    subBox.addView(buildZoneLabel("敏感打码"));
                }
                View subRow = buildSubSwitch(s);
                subRowViews.put(s.key, subRow);
                subBox.addView(subRow);
                // v442: 来电高度滑条，紧贴在「来电放右边」下面
                // （用户 “就在那个来电放在右边下面”）。
                if ("callRight".equals(s.key)) {
                    subBox.addView(buildCallSliderRow());
                    // v456: 来电胶囊三项（用户 “来电的在来电那一栏”）——
                    // 圆角 / 高度 / 宽度，单独调，不走外观。
                    subBox.addView(buildAppearanceSliderRow(
                            "来电胶囊圆角", null,
                            0, Appearance.MAX_CALL_CORNER_RADIUS,
                            apInt(Appearance.KEY_CALL_CORNER_RADIUS,
                                    Appearance.DEF_CALL_CORNER_RADIUS),
                            Appearance.KEY_CALL_CORNER_RADIUS,
                            "callCornerRadius", "dp",
                            null));
                    subBox.addView(buildAppearanceSliderRow(
                            "来电胶囊高度", null,
                            Appearance.MIN_CALL_CAPSULE_HEIGHT,
                            Appearance.MAX_CALL_CAPSULE_HEIGHT,
                            apInt(Appearance.KEY_CALL_CAPSULE_HEIGHT,
                                    Appearance.DEF_CALL_CAPSULE_HEIGHT),
                            Appearance.KEY_CALL_CAPSULE_HEIGHT,
                            "callCapsuleHeight", "dp",
                            null));
                    subBox.addView(buildAppearanceSliderRow(
                            "来电胶囊宽度", "响铃时的宽，缩后计时器 96/108dp 不动",
                            Appearance.MIN_CALL_CAPSULE_WIDTH,
                            Appearance.MAX_CALL_CAPSULE_WIDTH,
                            apInt(Appearance.KEY_CALL_CAPSULE_WIDTH,
                                    Appearance.DEF_CALL_CAPSULE_WIDTH),
                            Appearance.KEY_CALL_CAPSULE_WIDTH,
                            "callCapsuleWidth", "dp",
                            null));
                }
                // v456: 音乐胶囊三项（用户 “音乐在音乐那一栏”）——
                // 圆角 / 高度 / 宽度，单独调，不走外观；播放卡不动。
                if ("musicCard".equals(s.key)) {
                    subBox.addView(buildAppearanceSliderRow(
                            "音乐胶囊圆角", null,
                            0, Appearance.MAX_MUSIC_CORNER_RADIUS,
                            apInt(Appearance.KEY_MUSIC_CORNER_RADIUS,
                                    Appearance.DEF_MUSIC_CORNER_RADIUS),
                            Appearance.KEY_MUSIC_CORNER_RADIUS,
                            "musicCornerRadius", "dp",
                            null));
                    subBox.addView(buildAppearanceSliderRow(
                            "音乐胶囊高度", null,
                            Appearance.MIN_MUSIC_CAPSULE_HEIGHT,
                            Appearance.MAX_MUSIC_CAPSULE_HEIGHT,
                            apInt(Appearance.KEY_MUSIC_CAPSULE_HEIGHT,
                                    Appearance.DEF_MUSIC_CAPSULE_HEIGHT),
                            Appearance.KEY_MUSIC_CAPSULE_HEIGHT,
                            "musicCapsuleHeight", "dp",
                            null));
                    subBox.addView(buildAppearanceSliderRow(
                            "音乐胶囊宽度", null,
                            Appearance.MIN_MUSIC_CAPSULE_WIDTH,
                            Appearance.MAX_MUSIC_CAPSULE_WIDTH,
                            apInt(Appearance.KEY_MUSIC_CAPSULE_WIDTH,
                                    Appearance.defCapsuleWidth(this)),
                            Appearance.KEY_MUSIC_CAPSULE_WIDTH,
                            "musicCapsuleWidth", "dp",
                            null));
                    // v458: 音乐卡片也要支持大小调整（用户 “音乐卡片
                    // 也要支持大小调整”）——播放卡的宽 / 圆角，跟普通
                    // 卡片分开调。高度跟内容走（播放器布局固定，行数
                    // 上限不截它，截了会把播放控制切掉），不设高度项。
                    subBox.addView(buildAppearanceSliderRow(
                            "音乐卡片宽度", "播放卡的宽，高度跟内容走",
                            Appearance.MIN_MUSIC_CARD_WIDTH,
                            Appearance.MAX_MUSIC_CARD_WIDTH,
                            apInt(Appearance.KEY_MUSIC_CARD_WIDTH,
                                    Appearance.defMusicCardWidth(this)),
                            Appearance.KEY_MUSIC_CARD_WIDTH,
                            "musicCardWidth", "dp",
                            null));
                    // v462（用户「改成卡片比例调整」）：歌词行数滑条
                    // 撤掉，改成卡片比例调整——整张播放卡按比例
                    // 缩放（60–100%，默认 100%），宽、高、圆盘、
                    // 字、按钮一起缩。歌词固定 1 行。
                    subBox.addView(buildAppearanceSliderRow(
                            "卡片比例", "整张播放卡按比例缩放",
                            Appearance.MIN_MUSIC_CARD_SCALE,
                            Appearance.MAX_MUSIC_CARD_SCALE,
                            apInt(Appearance.KEY_MUSIC_CARD_SCALE,
                                    Appearance.DEF_MUSIC_CARD_SCALE),
                            Appearance.KEY_MUSIC_CARD_SCALE,
                            "musicCardScale", "%",
                            null));
                    subBox.addView(buildAppearanceSliderRow(
                            "音乐卡片圆角", null,
                            0, Appearance.MAX_MUSIC_CARD_CORNER_RADIUS,
                            apInt(Appearance.KEY_MUSIC_CARD_CORNER_RADIUS,
                                    Appearance.DEF_MUSIC_CARD_CORNER_RADIUS),
                            Appearance.KEY_MUSIC_CARD_CORNER_RADIUS,
                            "musicCardCornerRadius", "dp",
                            null));
                }
            }
        }
        // v458: 来电 / 音乐卡的恢复默认（用户 “还有恢复默认”）——
        // 各卡自己的滑条值回默认值（来电还含来电高度）。
        if ("live".equals(f.key)) {
            subBox.addView(buildCallResetButton());
        }
        if ("music".equals(f.key)) {
            subBox.addView(buildMusicResetButton());
        }
        // v528: 验证码与隐私卡——子行都建完后，刷一次敏感打码
        // 总开关与三个子开关的初始联动状态。
        // v540: 同理刷一次自动复制行的初始联动状态。
        if ("code".equals(f.key)) {
            refreshMaskLinkage();
            refreshCodeLinkage();
        }
        // v534: 通知气泡卡——子行建完后刷一次提示音的初始联动。
        if ("notif".equals(f.key)) {
            refreshSoundLinkage();
        }
        if (subBox.getChildCount() > 0) {
            subBox.setPadding(0, 0, 0, dp(12));
            card.addView(subBox);
        }

        final boolean[] expanded = new boolean[]{false};
        // v527: 登记展开区，搜索跳转时能程序展开这张卡。
        cardBodies.put(f.key, new View[]{body, subBox});
        cardExpanded.put(f.key, expanded);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                subBox.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            }
        });
        return card;
    }

    /** v527: 卡内分区小标题（白字半透明）。 */
    private View buildZoneLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.argb(200, 255, 255, 255));
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    /** 卡片里嵌套的一行子开关（小图标 + 标题/副标题 + 开关）。
     * v346: noSwitch 的行不设开关（一直开着），点一下展开/收起介绍。 */
    private View buildSubSwitch(final Feature sub) {
        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(8);
        wlp.leftMargin = dp(12);
        wlp.rightMargin = dp(12);
        wrap.setLayoutParams(wlp);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!sub.noSwitch && !isOn(sub.key)) row.setAlpha(0.45f);

        TextView icon = new TextView(this);
        icon.setText(sub.icon);
        icon.setTextSize(13);
        icon.setTextColor(sub.color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(8)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);

        TextView title = new TextView(this);
        title.setText(sub.title);
        title.setTextSize(14);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);
        // v480: 反方向的钟——记下标题，开关打开之后标题要变成
        // “如果听一万次《反方向的钟》能否回到过去”。
        if ("musicLed".equals(sub.key)) {
            musicLedTitle = title;
            refreshMusicLedTitle();
        }

        // v478: 副标题为空（用户“把这个介绍去了”）就不加这一行，
        // 只留名字，不留空行占地方。
        if (sub.subtitle != null && !sub.subtitle.isEmpty()) {
            TextView sub2 = new TextView(this);
            sub2.setText(sub.subtitle);
            sub2.setTextSize(11);
            sub2.setTextColor(Color.argb(230, 255, 255, 255));
            texts.addView(sub2);
        }
        row.addView(texts);

        if (sub.noSwitch) {
            // v346: 不设开关。点行展开/收起介绍（body）。
            final TextView intro = new TextView(this);
            intro.setText(sub.body);
            intro.setTextSize(12);
            intro.setTextColor(Color.argb(242, 255, 255, 255));
            intro.setLineSpacing(0, 1.6f);
            intro.setPadding(dp(12), dp(4), dp(12), dp(10));
            intro.setVisibility(View.GONE);
            wrap.addView(row);
            wrap.addView(intro);
            final boolean[] expanded = new boolean[]{false};
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expanded[0] = !expanded[0];
                    intro.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                }
            });
            return wrap;
        }
        final View sw = buildSwitch(sub, row);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(dp(48), dp(28));
        swlp.leftMargin = dp(8);
        sw.setLayoutParams(swlp);
        row.addView(sw);
        // v478: 反方向的钟——计数器显示在开关旁边（x/100）。
        if ("musicLed".equals(sub.key)) {
            musicLedCounter = new TextView(this);
            musicLedCounter.setTextSize(12);
            musicLedCounter.setTextColor(Color.argb(230, 255, 255, 255));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(8);
            musicLedCounter.setLayoutParams(clp);
            refreshMusicLedCounter();
            row.addView(musicLedCounter);
        }
        if ("autoDestroy".equals(sub.key)) {
            destroyRowView = row;
        }
        // v536: 气泡提示音已升为独立展开卡（buildSoundCard），
        // 不再作为通知气泡卡的子行出现，这里没有它的特殊处理了。
        if ("maskLock".equals(sub.key)
                || "maskRecord".equals(sub.key)
                || "maskApps".equals(sub.key)) {
            maskChildRows.put(sub.key, row);
        }
        if ("autoCopyCode".equals(sub.key)) {
            autoCopyCodeRow = row;
        }
        wrap.addView(row);
        return wrap;
    }

    /** v480: 刷新反方向的钟的标题。开关开着，标题变成用户那句
     * “如果听一万次《反方向的钟》能否回到过去”；关着还是
     * “反方向的钟”。 */
    private void refreshMusicLedTitle() {
        if (musicLedTitle == null) {
            return;
        }
        if (isOn("musicLed")) {
            musicLedTitle.setText("如果听一万次《反方向的钟》能否回到过去");
        } else {
            musicLedTitle.setText("反方向的钟");
        }
    }

    /** v478: 刷新反方向的钟的计数器（x/N；满 N 显示“已开启”）。
     * v482: getInt 包一层 try——旧备份可能已经把 musicLed_taps
     * 写成了布尔（restoreToPrefs 当布尔恢复的 bug），直接 getInt
     * 会 ClassCastException，App 一打开就闪退。读到坏值就清掉
     * 当 0 处理，不崩。 */
    private void refreshMusicLedCounter() {
        if (musicLedCounter == null) {
            return;
        }
        int taps = 0;
        try {
            taps = prefs.getInt("musicLed_taps", 0);
        } catch (ClassCastException e) {
            prefs.edit().remove("musicLed_taps").commit();
            taps = 0;
        }
        if (taps >= MUSIC_LED_TAPS_TO_UNLOCK) {
            musicLedCounter.setText("已开启");
        } else {
            musicLedCounter.setText(taps + "/" + MUSIC_LED_TAPS_TO_UNLOCK);
        }
    }

    private View buildSwitch(final Feature f, final LinearLayout card) {
        final FrameLayout sw = new FrameLayout(this);
        final boolean on = isOn(f.key);
        sw.setBackground(rounded(on ? Color.rgb(22, 163, 74) : Color.argb(120, 255, 255, 255), dp(999)));
        final View dot = new View(this);
        dot.setBackground(rounded(Color.WHITE, dp(999)));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(22), dp(22));
        dlp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        dlp.leftMargin = on ? 0 : dp(3);
        dlp.rightMargin = on ? dp(3) : 0;
        dot.setLayoutParams(dlp);
        sw.addView(dot);
        if ("autoDestroy".equals(f.key)) {
            destroySwitchView = sw;
        }
        if ("maskLock".equals(f.key)
                || "maskRecord".equals(f.key)
                || "maskApps".equals(f.key)) {
            maskChildSwitches.put(f.key, sw);
        }
        if ("autoCopyCode".equals(f.key)) {
            autoCopyCodeSwitch = sw;
        }
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // v478: 反方向的钟——点满 N 次才开启（用户 2026-11-23
                // “点满100次开启”“点一下记一次数”；这个版本先设 10 次
                // 方便测）。没满 N 次时，点一下只记一次数（计数器在
                // 开关旁边显示 x/N），不拨开关；第 N 下记满，开关自动
                // 打开，之后正常拨动。
                if ("musicLed".equals(f.key)) {
                    // v482: getInt 包一层 try——旧备份可能已经把
                    // musicLed_taps 写成了布尔，直接 getInt 会
                    // ClassCastException。读到坏值就清掉当 0 处理。
                    int taps = 0;
                    try {
                        taps = prefs.getInt("musicLed_taps", 0);
                    } catch (ClassCastException e) {
                        prefs.edit().remove("musicLed_taps").commit();
                        taps = 0;
                    }
                    if (taps < MUSIC_LED_TAPS_TO_UNLOCK) {
                        taps++;
                        prefs.edit().putInt("musicLed_taps", taps).commit();
                        refreshMusicLedCounter();
                        if (taps < MUSIC_LED_TAPS_TO_UNLOCK) {
                            return;
                        }
                        // 第 N 下：点完了，先放一遍《反方向的钟》
                        // （用户 2026-11-23 “点完了就播放反方向的钟，
                        // 然后启用跑马灯”），再往下走正常拨动流程把
                        // 开关打开。放歌走系统搜歌意图，交给音乐 App 播。
                        try {
                            Intent play = new Intent(
                                    "android.media.action"
                                            + ".MEDIA_PLAY_FROM_SEARCH");
                            play.putExtra(
                                    android.app.SearchManager.QUERY,
                                    "反方向的钟 周杰伦");
                            play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            MainActivity.this.startActivity(play);
                        } catch (Throwable ignored) {
                        }
                        // 往下走：now 会算成 true（当前还是关着），开关打开。
                    }
                }
                boolean now = !isOn(f.key);
                // v343: commit() 同步落盘——apply() 异步，拨完立刻杀进程会丢，
                // 下次打开开关就回到旧值。
                prefs.edit().putBoolean(f.key, now).commit();
                // v464: 开关一变就同步写本地备份文件（重装不删），
                // 更新后不用重新拨开关。
                SettingsStore.saveAll(MainActivity.this);
                // v259: tell SystemUI at once so the switch is
                // real-time (no restart needed).
                Intent i = new Intent(
                        "com.chenan.flymebobo.FEATURE_TOGGLE");
                i.putExtra("key", f.key);
                i.putExtra("enabled", now);
                sendBroadcast(i);
                sw.setBackground(rounded(now ? Color.rgb(22, 163, 74)
                        : Color.argb(120, 255, 255, 255), dp(999)));
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) dot.getLayoutParams();
                p.gravity = Gravity.CENTER_VERTICAL | (now ? Gravity.END : Gravity.START);
                p.leftMargin = now ? 0 : dp(3);
                p.rightMargin = now ? dp(3) : 0;
                dot.setLayoutParams(p);
                card.setAlpha(now ? 1f : 0.45f);
                // v480: 反方向的钟——开关一拨，标题跟着变：开着是
                // “如果听一万次《反方向的钟》能否回到过去”，
                // 关着是“反方向的钟”。
                if ("musicLed".equals(f.key)) {
                    refreshMusicLedTitle();
                }
                // v442: 拨「来电放右边」开关时，来电高度滑条跟着
                // 启用/置灰（联动）。
                if ("callRight".equals(f.key)) {
                    refreshCallSliderEnabled();
                }
                // v528: 自动销毁依附自动消散——拨消散时同步
                // 刷新销毁行的置灰/可点状态（联动，wei 定）。
                if ("blankDissipate".equals(f.key)) {
                    refreshDestroyLinkage();
                }
                // v528: 敏感打码是总开关——拨它时同步刷新三个
                // 子开关行的置灰/可点状态（联动，wei 定；行为层
                // PrivacyMask 本来就是总开关 AND 子开关）。
                if ("mask".equals(f.key)) {
                    refreshMaskLinkage();
                }
                // v540: 「验证码与隐私」是自动复制的总开关——
                // 拨它时同步刷新自动复制行的置灰/可点状态。
                if ("code".equals(f.key)) {
                    refreshCodeLinkage();
                }
                // v534: 通知气泡是提示音的总开关——拨它时同步
                // 刷新提示音行的置灰/可点状态（联动，wei 定）。
                if ("notif".equals(f.key)) {
                    refreshSoundLinkage();
                }
                // v536: 提示音自己的总开关——拨它时同步刷新
                // 展开区的置灰状态（联动，wei 定）。
                if ("bubbleSound".equals(f.key)) {
                    refreshSoundLinkage();
                }
                // v530: 场景模式是总开关——拨它时同步刷新名单
                // 里「场景里」一列的置灰/可点状态（联动）。
                if ("sceneMode".equals(f.key)) {
                    refreshSceneLinkage();
                    // v531: 拨到开时播 B 版全屏动画当开启
                    // 回执（wei 拍板）；拨到关不播。
                    if (now) {
                        showSceneStartAnim();
                    }
                }
            }
        });
        return sw;
    }

    private boolean isOn(String key) {
        return prefs.getBoolean(key, defaultFor(key));
    }

    private boolean defaultFor(String key) {
        for (Feature f : FEATURES) {
            if (f.key.equals(key)) return f.def;
        }
        return true;
    }

    // v439: 外观卡片——只有一个滑块，扇子位置（整组的锚点）。
    // 胶囊和扇子是一个整体，胶囊从扇子那里弹出来、跟着走，
    // 所以只设这一个位置。滑块松手/点预设时存 prefs + 发广播
    // 给 SystemUI 重排，立即生效不用重启。
    /** v456: 读外观 prefs 里的 int（滑条初值用）。 */
    private int apInt(String key, int def) {
        return getSharedPreferences(Appearance.PREFS,
                Context.MODE_PRIVATE).getInt(key, def);
    }

    /** v456: 各外观滑条的最小值（SeekBar progress = value - min）。 */
    private int apMin(String extraKey) {
        if ("capsuleHeight".equals(extraKey)) return Appearance.MIN_CAPSULE_HEIGHT;
        if ("capsuleWidth".equals(extraKey)) return Appearance.MIN_CAPSULE_WIDTH;
        if ("cardWidth".equals(extraKey)) return Appearance.MIN_CARD_WIDTH;
        if ("cardMaxRows".equals(extraKey)) return Appearance.MIN_CARD_MAX_ROWS;
        // v462: 卡片比例 min 是 60，不是 0。
        if ("musicCardScale".equals(extraKey)) return Appearance.MIN_MUSIC_CARD_SCALE;
        // v484: 展开卡片比例 min 也是 60。
        if ("cardScale".equals(extraKey)) return Appearance.MIN_CARD_SCALE;
        return 0;
    }

    /** v456: 读某外观滑条的当前值（拖动中从 apSeeks 读，没登记
     * 的读 prefs 默认值）。实时预览用。 */
    private int seekVal(String extraKey, int def) {
        SeekBar s = apSeeks.get(extraKey);
        if (s != null) {
            return s.getProgress() + apMin(extraKey);
        }
        return def;
    }

    /** v456: 把某外观滑条拨到指定值（恢复默认用；setProgress 会
     * 触发 onProgressChanged 更新数值和直播广播）。 */
    private void setApSeek(String extraKey, int value) {
        SeekBar s = apSeeks.get(extraKey);
        if (s != null) {
            s.setProgress(value - apMin(extraKey));
        }
    }


    /** v458: 来电卡的恢复默认——来电胶囊三项 + 来电高度回默认值，
     * 滑条拨回去，发一条全量广播。（v459：实时预览已撤。） */
    private TextView buildCallResetButton() {
        TextView btn = new TextView(this);
        btn.setText("恢复默认");
        btn.setTextSize(14);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(12)));
        btn.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences apPrefs = getSharedPreferences(
                        Appearance.PREFS, Context.MODE_PRIVATE);
                SharedPreferences.Editor e = apPrefs.edit();
                e.putInt(Appearance.KEY_CALL_CORNER_RADIUS,
                        Appearance.DEF_CALL_CORNER_RADIUS);
                e.putInt(Appearance.KEY_CALL_CAPSULE_HEIGHT,
                        Appearance.DEF_CALL_CAPSULE_HEIGHT);
                e.putInt(Appearance.KEY_CALL_CAPSULE_WIDTH,
                        Appearance.DEF_CALL_CAPSULE_WIDTH);
                e.putInt(Appearance.KEY_CALL_PCT, Appearance.DEF_CALL_PCT);
                e.commit();
                setApSeek("callCornerRadius", Appearance.DEF_CALL_CORNER_RADIUS);
                setApSeek("callCapsuleHeight", Appearance.DEF_CALL_CAPSULE_HEIGHT);
                setApSeek("callCapsuleWidth", Appearance.DEF_CALL_CAPSULE_WIDTH);
                if (callHeightSeek != null) {
                    callHeightSeek.setProgress(Appearance.DEF_CALL_PCT);
                }
                Intent i = new Intent("com.chenan.flymebobo.APPEARANCE");
                i.putExtra("callCornerRadius", Appearance.DEF_CALL_CORNER_RADIUS);
                i.putExtra("callCapsuleHeight", Appearance.DEF_CALL_CAPSULE_HEIGHT);
                i.putExtra("callCapsuleWidth", Appearance.DEF_CALL_CAPSULE_WIDTH);
                i.putExtra("callPct", Appearance.DEF_CALL_PCT);
                sendBroadcast(i);
            }
        });
        return btn;
    }

    /** v458: 音乐卡的恢复默认——音乐胶囊三项 + 音乐卡片两项回默认
     * 值，滑条拨回去，发一条全量广播。（v459：实时预览已撤。） */
    private TextView buildMusicResetButton() {
        TextView btn = new TextView(this);
        btn.setText("恢复默认");
        btn.setTextSize(14);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(12)));
        btn.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences apPrefs = getSharedPreferences(
                        Appearance.PREFS, Context.MODE_PRIVATE);
                SharedPreferences.Editor e = apPrefs.edit();
                e.putInt(Appearance.KEY_MUSIC_CORNER_RADIUS,
                        Appearance.DEF_MUSIC_CORNER_RADIUS);
                e.putInt(Appearance.KEY_MUSIC_CAPSULE_HEIGHT,
                        Appearance.DEF_MUSIC_CAPSULE_HEIGHT);
                e.putInt(Appearance.KEY_MUSIC_CAPSULE_WIDTH,
                        Appearance.defCapsuleWidth(MainActivity.this));
                e.putInt(Appearance.KEY_MUSIC_CARD_WIDTH,
                        Appearance.defMusicCardWidth(MainActivity.this));
                e.putInt(Appearance.KEY_MUSIC_CARD_CORNER_RADIUS,
                        Appearance.DEF_MUSIC_CARD_CORNER_RADIUS);
                // v462: 卡片比例回默认 100%。
                e.putInt(Appearance.KEY_MUSIC_CARD_SCALE,
                        Appearance.DEF_MUSIC_CARD_SCALE);
                e.commit();
                setApSeek("musicCornerRadius", Appearance.DEF_MUSIC_CORNER_RADIUS);
                setApSeek("musicCapsuleHeight", Appearance.DEF_MUSIC_CAPSULE_HEIGHT);
                setApSeek("musicCapsuleWidth",
                        Appearance.defCapsuleWidth(MainActivity.this));
                setApSeek("musicCardWidth",
                        Appearance.defMusicCardWidth(MainActivity.this));
                setApSeek("musicCardCornerRadius",
                        Appearance.DEF_MUSIC_CARD_CORNER_RADIUS);
                setApSeek("musicCardScale",
                        Appearance.DEF_MUSIC_CARD_SCALE);
                Intent i = new Intent("com.chenan.flymebobo.APPEARANCE");
                i.putExtra("musicCornerRadius", Appearance.DEF_MUSIC_CORNER_RADIUS);
                i.putExtra("musicCapsuleHeight", Appearance.DEF_MUSIC_CAPSULE_HEIGHT);
                i.putExtra("musicCapsuleWidth",
                        Appearance.defCapsuleWidth(MainActivity.this));
                i.putExtra("musicCardWidth",
                        Appearance.defMusicCardWidth(MainActivity.this));
                i.putExtra("musicCardCornerRadius",
                        Appearance.DEF_MUSIC_CARD_CORNER_RADIUS);
                i.putExtra("musicCardScale",
                        Appearance.DEF_MUSIC_CARD_SCALE);
                sendBroadcast(i);
            }
        });
        return btn;
    }

    // v456: 通用外观滑条行（外观胶囊 / 来电卡 / 音乐卡共用）——
    // 透明白底圆角 16，白字白滑条。min>0 时 SeekBar 用偏移
    // （progress = value - min）。拖动中发广播直播（SystemUI
    // 实时改），松手落盘 + 再发一次。onDrag 是可选回调（外观卡
    // 的实时预览用），拖动时跟着调。
    private View buildAppearanceSliderRow(final String labelText,
            final String hintText, final int min, final int max,
            final int cur, final String key, final String extraKey,
            final String unit, final Runnable onDrag) {
        final SharedPreferences apPrefs = getSharedPreferences(
                Appearance.PREFS, Context.MODE_PRIVATE);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        blp.leftMargin = dp(12);
        blp.rightMargin = dp(12);
        box.setLayoutParams(blp);

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(14);
        label.setTextColor(Color.WHITE);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(llp);
        labelRow.addView(label);
        final TextView val = new TextView(this);
        val.setTextSize(13);
        val.setTextColor(Color.WHITE);
        labelRow.addView(val);
        box.addView(labelRow);

        if (hintText != null) {
            TextView hint = new TextView(this);
            hint.setText(hintText);
            hint.setTextSize(11);
            hint.setTextColor(Color.argb(230, 255, 255, 255));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = dp(2);
            hint.setLayoutParams(hlp);
            box.addView(hint);
        }

        final SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(cur - min);
        // v456: 登记起来，实时预览按 extraKey 读各滑条的当前值。
        apSeeks.put(extraKey, seek);
        try {
            android.content.res.ColorStateList white =
                    android.content.res.ColorStateList.valueOf(Color.WHITE);
            seek.setProgressTintList(white);
            seek.setThumbTintList(white);
            seek.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            Color.argb(90, 255, 255, 255)));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        seek.setLayoutParams(slp);
        box.addView(seek);

        final Runnable updateVal = new Runnable() {
            @Override
            public void run() {
                val.setText((seek.getProgress() + min) + unit);
            }
        };
        final Runnable preview = new Runnable() {
            @Override
            public void run() {
                int v = seek.getProgress() + min;
                Intent i = new Intent(
                        "com.chenan.flymebobo.APPEARANCE");
                i.putExtra(extraKey, v);
                sendBroadcast(i);
                if (onDrag != null) {
                    onDrag.run();
                }
            }
        };
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                int v = seek.getProgress() + min;
                apPrefs.edit().putInt(key, v).commit();
                // v464: 滑条一松手就同步写本地备份文件（重装不删），
                // 更新后不用重新调外观。
                SettingsStore.saveAll(MainActivity.this);
                Intent i = new Intent(
                        "com.chenan.flymebobo.APPEARANCE");
                i.putExtra(extraKey, v);
                sendBroadcast(i);
                if (onDrag != null) {
                    onDrag.run();
                }
            }
        };
        updateVal.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress,
                    boolean fromUser) {
                updateVal.run();
                if (fromUser) {
                    preview.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                if (onDrag != null) {
                    onDrag.run();
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                apply.run();
            }
        });
        return box;
    }

    /** v442: 来电高度滑条——右边来电胶囊的上下位置（整颗一起移）。
     * 放在「来电」卡片展开区、「来电放右边」开关下面（用户
     * “就在那个来电放在右边下面”）。跟开关联动（用户 “和开关联动” /
     * “不打开就无法滑动”）：只有 callRight 开着才能滑，关着置灰禁用。
     * v443: Toast 拿掉（用户 “不是让你加个提示”）——位置由
     * 「模拟来电」的假胶囊直接显示。拖动时只发广播（假胶囊实时跟
     * 着动），松手才落盘。样式跟子开关一行：透明白底圆角 16，
     * 白字白滑条，贴在紫色来电卡里。 */
    private View buildCallSliderRow() {
        final int max = Appearance.MAX_CALL_PCT;
        final String key = Appearance.KEY_CALL_PCT;
        final int def = Appearance.DEF_CALL_PCT;
        final SharedPreferences apPrefs = getSharedPreferences(
                Appearance.PREFS, Context.MODE_PRIVATE);
        final int cur = apPrefs.getInt(key, def);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        blp.leftMargin = dp(12);
        blp.rightMargin = dp(12);
        box.setLayoutParams(blp);

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("来电高度");
        label.setTextSize(14);
        label.setTextColor(Color.WHITE);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(llp);
        labelRow.addView(label);
        final TextView val = new TextView(this);
        val.setTextSize(13);
        val.setTextColor(Color.WHITE);
        labelRow.addView(val);
        box.addView(labelRow);

        TextView hint = new TextView(this);
        hint.setText("右边来电胶囊在这个高度，整颗一起移");
        hint.setTextSize(11);
        hint.setTextColor(Color.argb(230, 255, 255, 255));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(2);
        hint.setLayoutParams(hlp);
        box.addView(hint);

        final SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(cur);
        try {
            android.content.res.ColorStateList white =
                    android.content.res.ColorStateList.valueOf(Color.WHITE);
            seek.setProgressTintList(white);
            seek.setThumbTintList(white);
            seek.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            Color.argb(90, 255, 255, 255)));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        seek.setLayoutParams(slp);
        box.addView(seek);

        callHeightSeek = seek;
        callHeightBox = box;

        final Runnable updateVal = new Runnable() {
            @Override
            public void run() {
                int pct = seek.getProgress();
                int dpv = Appearance.pctToDp(MainActivity.this, pct);
                val.setText(pct + "% · 约 " + dpv + "dp");
            }
        };
        // 存 prefs + 发广播（SystemUI 立即重排）。只在松手/点预设时
        // 发一次，拖动中途不发，免得广播刷屏。
        // v443: Toast 不要了（用户 “不是让你加个提示”）——位置由
        // 「模拟来电」的假胶囊直接显示。拖动时只发广播不落盘，
        // 假胶囊跟着滑条实时动；松手才落盘 + 再发一次。
        final Runnable preview = new Runnable() {
            @Override
            public void run() {
                int progress = seek.getProgress();
                Intent i = new Intent(
                        "com.chenan.flymebobo.APPEARANCE");
                i.putExtra("callPct", progress);
                sendBroadcast(i);
            }
        };
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                int progress = seek.getProgress();
                apPrefs.edit().putInt(key, progress).commit();
                Intent i = new Intent(
                        "com.chenan.flymebobo.APPEARANCE");
                i.putExtra("callPct", progress);
                sendBroadcast(i);
            }
        };
        updateVal.run();
        refreshCallSliderEnabled();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateVal.run();
                if (fromUser) {
                    preview.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                apply.run();
            }
        });
        return box;
    }

    /** v442: 来电高度滑条跟「来电放右边」开关联动——开着才能滑，
     * 关着置灰禁用（用户 “和开关联动”“不打开就无法滑动”）。 */
    /** v528: 敏感打码是总开关（wei 定，与消散→销毁同理）：
     * 总开关关 → 锁屏时/录屏、投屏时/普通应用也打码三行置灰
     * 且开关不可点；总开关开 → 按各自标志位恢复显示、可点。
     * 行为层 PrivacyMask.shouldMask 本来就先查总开关
     * （关了直接不打码），这里只补 UI 联动。 */
    private void refreshMaskLinkage() {
        boolean link = isOn("mask");
        for (String k : new String[]{
                "maskLock", "maskRecord", "maskApps"}) {
            View sw = maskChildSwitches.get(k);
            if (sw != null) {
                sw.setEnabled(link);
            }
            View row = maskChildRows.get(k);
            if (row != null) {
                row.setAlpha(link
                        ? (isOn(k) ? 1f : 0.45f) : 0.45f);
            }
        }
    }

    /** v540: 「验证码与隐私」总开关（code）是「自动复制
     * 验证码」的总开关（wei 的联动规矩，与打码同理）：
     * 总开关关 → 自动复制行置灰且开关不可点；总开关开 →
     * 按自动复制自己的标志位恢复。行为层 BubbleWindow
     * 本来就先过 code 功能门（关了验证码根本不识别），
     * 这里只补 UI 联动。 */
    private void refreshCodeLinkage() {
        boolean link = isOn("code");
        if (autoCopyCodeSwitch != null) {
            autoCopyCodeSwitch.setEnabled(link);
        }
        if (autoCopyCodeRow != null) {
            autoCopyCodeRow.setAlpha(link
                    ? (isOn("autoCopyCode") ? 1f : 0.45f) : 0.45f);
        }
    }

    /** v530: 场景模式是名单「场景里」一列的总开关（wei 定，
     * 与打码/销毁同理）：关 → 每行的场景分段置灰且不可点；
     * 开 → 恢复可点。「平时」一列不受影响。行为层 BubbleWindow
     * 的场景门直接查 FeatureFlags.sceneMode，关了整体跳过。 */
    private void refreshSceneLinkage() {
        boolean on = isOn("sceneMode");
        for (Map.Entry<String, SegControl> en
                : quietSceneSegs.entrySet()) {
            SegControl seg = en.getValue();
            if (seg != null) {
                for (TextView item : seg.items) {
                    if (item != null) {
                        item.setEnabled(on);
                    }
                }
            }
            View row = quietSceneSegRows.get(en.getKey());
            if (row != null) {
                row.setAlpha(on ? 1f : 0.45f);
            }
        }
    }

    /** v534 建，v536 改：气泡提示音的联动置灰（wei 定，与
     * 打码/销毁同理）。提示音现在是独立展开卡：
     * - 通知气泡总开关关 → 整张提示音卡置灰、总开关不可点；
     * - 提示音自己的总开关关 → 它下面的展开区（音效列表 +
     *   按应用段）置灰，总开关行自己保持亮着方便再打开
     *   （v537: 开关已从卡头挪进展开区第一行）。
     * 展开区里的点击处理另外都过 soundUsable() 双保险。
     * 行为层本来也双保险：总开关关了气泡根本不弹，
     * BubbleSound 也不会被调用。 */
    private void refreshSoundLinkage() {
        if (soundCardView == null) {
            return;
        }
        boolean link = isOn("notif");
        if (bubbleSoundSwitch != null) {
            bubbleSoundSwitch.setEnabled(link);
        }
        soundCardView.setAlpha(link ? 1f : 0.45f);
        boolean bodyOn = isOn("bubbleSound");
        if (soundGlobalSection != null) {
            soundGlobalSection.setAlpha(bodyOn ? 1f : 0.38f);
        }
        if (soundAppSection != null) {
            soundAppSection.setAlpha(bodyOn ? 1f : 0.38f);
        }
        if (soundAppSearch != null) {
            soundAppSearch.setEnabled(bodyOn);
        }
    }

    /** v535: 提示音副标题显示当前音效（卡头副标题，换了音效
     * 一眼看得出来）。读字符串 prefs 包 try——旧备份万一
     * 把键写坏成别的类型，只回默认名，不崩。 */
    private void refreshSoundSubtitle() {
        if (bubbleSoundSub == null || prefs == null) {
            return;
        }
        String id = "soft";
        try {
            String v = prefs.getString("bubbleSoundId", "soft");
            if (v != null && BubbleSound.isSoundId(v)) {
                id = v;
            }
        } catch (Throwable ignored) {
        }
        bubbleSoundSub.setText("气泡弹出时轻响一声 · 当前："
                + BubbleSound.nameFor(id));
    }

    /** v535: 提示音总开关涂色/圆点位置按 prefs 重涂一遍
     * （buildSwitch 只在建卡时涂一次，备份恢复等外部改动后
     * 不刷会显示旧状态）。v537: 开关在展开区第一行。 */
    private void repaintBubbleSoundSwitch() {
        if (!(bubbleSoundSwitch instanceof FrameLayout)) {
            return;
        }
        FrameLayout sw = (FrameLayout) bubbleSoundSwitch;
        boolean on = isOn("bubbleSound");
        sw.setBackground(rounded(on ? Color.rgb(22, 163, 74)
                : Color.argb(120, 255, 255, 255), dp(999)));
        if (sw.getChildCount() > 0) {
            View dot = sw.getChildAt(0);
            if (dot.getLayoutParams()
                    instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams p =
                        (FrameLayout.LayoutParams)
                                dot.getLayoutParams();
                p.gravity = Gravity.CENTER_VERTICAL
                        | (on ? Gravity.END : Gravity.START);
                p.leftMargin = on ? 0 : dp(3);
                p.rightMargin = on ? dp(3) : 0;
                dot.setLayoutParams(p);
            }
        }
    }

    /** v528: 自动销毁依附自动消散（wei 定，原有联动）：
     * 消散关 → 销毁行置灰且开关不可点；消散开 → 销毁行按
     * 自身标志位正常显示、可点。与来电高度依附来电放右边
     * 是同一套路（refreshCallSliderEnabled）。 */
    private void refreshDestroyLinkage() {
        if (destroyRowView == null || destroySwitchView == null) {
            return;
        }
        boolean link = isOn("blankDissipate");
        destroySwitchView.setEnabled(link);
        destroyRowView.setAlpha(link
                ? (isOn("autoDestroy") ? 1f : 0.45f) : 0.45f);
    }

    private void refreshCallSliderEnabled() {
        boolean on = isOn("callRight");
        if (callHeightSeek != null) {
            callHeightSeek.setEnabled(on);
        }
        if (callHeightBox != null) {
            callHeightBox.setAlpha(on ? 1f : 0.45f);
        }
    }
    // v442: 外观卡片改风格——跟上面功能卡统一（用户 2026-11-23
    // “最后这个重新设计下”“和其他风格统一一下”）：红底圆角 22，
    // 白图标 + 白字，跟功能卡一个路子，不再是深灰卡。
    // v443: 改成「外观胶囊」（用户 “改成外观胶囊”“点开后是扇子
    // 调整滑块”“后面还有其他外观还没加上呢”）：平时收起只剩头，
    // 点一下展开，里面才是扇子调整滑块；body 是个竖容器，
    // 以后加别的外观项就往里再塞一行。 */
    // v442: 外观卡片改风格——跟上面功能卡统一（用户 2026-11-23
    // “最后这个重新设计下”“和其他风格统一一下”）：红底圆角 22，
    // 白图标 + 白字，跟功能卡一个路子，不再是深灰卡。
    // v443: 改成「外观胶囊」（用户 “改成外观胶囊”“点开后是扇子
    // 调整滑块”“后面还有其他外观还没加上呢”）：平时收起只剩头，
    // 点一下展开，里面才是扇子调整滑块；body 是个竖容器，
    // 以后加别的外观项就往里再塞一行。
    // v456: 外观细调全家（用户 2026-11-23 “调整胶囊圆角”“胶囊
    // 卡片圆角”“大小也加上”“高度和宽度2项”“扇子图标重叠度”
    // “扇子图标透明度”“扇子图标排列方向改变”“卡片的高度和宽度
    // 圆角”“恢复默认”“调整的时候自动展开一个实时调整预览”）：
    // body 里按组排——实时预览（拖滑条自动展开）/ 扇子位置 /
    // 胶囊（圆角/高/宽）/ 卡片（宽/圆角/最大行数）/ 扇子图标
    // （重叠度/透明度/方向）/ 恢复默认。来电、音乐各在自己栏里调，
    // 不走这里。
    private View buildAppearanceCard() {
        final int max = Appearance.MAX_DOCK_PCT;
        final String key = Appearance.KEY_DOCK_PCT;
        final int def = Appearance.DEF_DOCK_PCT;
        final SharedPreferences apPrefs = getSharedPreferences(
                Appearance.PREFS, Context.MODE_PRIVATE);
        final int cur = apPrefs.getInt(key, def);
        final int cardColor = Color.rgb(220, 38, 38);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(cardColor, dp(22)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

        // Header：白图标 + 标题/副标题 + 右边数值（白字粗体）+ 展开箭头。
        // 点头展开/收起 body。
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView icon = new TextView(this);
        icon.setText("扇");
        icon.setTextSize(15);
        icon.setTextColor(cardColor);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(10)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
        icon.setLayoutParams(ilp);
        head.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView title = new TextView(this);
        // v464: 用户 2026-11-23 “外观胶囊改名外观设置”。
        title.setText("外观设置");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView sub = new TextView(this);
        // v527: 卡内吸收了取色/滚动，副标题跟着改。
        sub.setText("扇子位置 · 胶囊 · 卡片 · 取色与滚动");
        sub.setTextSize(12);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        // v464: 跟上面功能卡一样高——副标题只一行，不换行（原来
        // 标题栏右边那个数值把副标题挤成两行，卡片比别的卡高）。
        sub.setSingleLine(true);
        texts.addView(sub);
        head.addView(texts);

        final TextView chev = buildExpandChevron();
        head.addView(chev);
        card.addView(head);

        // Body：收起时 GONE，点开才出现。竖容器，以后加外观项往里塞。
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.leftMargin = dp(12);
        bodyLp.rightMargin = dp(12);
        bodyLp.bottomMargin = dp(12);
        body.setLayoutParams(bodyLp);
        card.addView(body);
        // v527: 搜索展开钩子 + 索引目标登记。
        appearanceBody = body;
        appearanceChev = chev;
        searchTargets.put("appearanceCard", card);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                setChevronExpanded(chev, open);
            }
        });

        // ---- v527: 胶囊长相（从主页迁入的三个开关，标志位不变）----
        body.addView(buildZoneLabel("胶囊长相"));
        Feature tintF = featureFor("autoTint");
        if (tintF != null) {
            View r = buildSubSwitch(tintF);
            subRowViews.put(tintF.key, r);
            body.addView(r);
        }
        body.addView(cardDesc("关掉后全部用默认白色底+黑描边。"
                + "来电胶囊不受影响。"));
        Feature scrollF = featureFor("scroll");
        if (scrollF != null) {
            View r = buildSubSwitch(scrollF);
            subRowViews.put(scrollF.key, r);
            body.addView(r);
        }
        body.addView(cardDesc("关掉后超长内容以 … 截断。"));
        Feature dimF = featureFor("fanAutoDim");
        if (dimF != null) {
            View r = buildSubSwitch(dimF);
            subRowViews.put(dimF.key, r);
            body.addView(r);
        }
        body.addView(cardDesc("点一下扇子恢复并展开。关掉后一直不透明。"));

        // ---- v456: 实时预览（拖滑条自动展开）----
        //  mock 胶囊 + mock 卡片 + mock 扇子，按当前滑条值画。
        //  平时收起（GONE）；任一滑条一上手拖就展开，边拖边变。
        final LinearLayout previewBox = new LinearLayout(this);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        previewBox.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        previewBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvLp.bottomMargin = dp(4);
        previewBox.setLayoutParams(pvLp);
        previewBox.setVisibility(View.GONE);
        body.addView(previewBox);

        TextView pvLabel = new TextView(this);
        pvLabel.setText("实时预览");
        pvLabel.setTextSize(13);
        pvLabel.setTextColor(Color.WHITE);
        pvLabel.setTypeface(pvLabel.getTypeface(), android.graphics.Typeface.BOLD);
        previewBox.addView(pvLabel);

        // mock 胶囊：深色 pill，图标 + 一行字。
        final LinearLayout pvCap = new LinearLayout(this);
        pvCap.setOrientation(LinearLayout.HORIZONTAL);
        pvCap.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pvCapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvCapLp.topMargin = dp(8);
        pvCap.setLayoutParams(pvCapLp);
        TextView pvCapIcon = new TextView(this);
        pvCapIcon.setText("微");
        pvCapIcon.setTextSize(13);
        pvCapIcon.setTextColor(Color.WHITE);
        pvCapIcon.setGravity(Gravity.CENTER);
        pvCapIcon.setBackground(rounded(Color.rgb(34, 197, 94), dp(14)));
        LinearLayout.LayoutParams pvCiLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        pvCiLp.leftMargin = dp(8);
        pvCapIcon.setLayoutParams(pvCiLp);
        pvCap.addView(pvCapIcon);
        TextView pvCapText = new TextView(this);
        pvCapText.setText("微信 · 张三：明天下午三点开会");
        pvCapText.setTextSize(13);
        pvCapText.setTextColor(Color.WHITE);
        pvCapText.setSingleLine(true);
        LinearLayout.LayoutParams pvCtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvCtLp.leftMargin = dp(8);
        pvCtLp.rightMargin = dp(12);
        pvCapText.setLayoutParams(pvCtLp);
        pvCap.addView(pvCapText);
        previewBox.addView(pvCap);

        // mock 卡片：深色，头 + 标题 + 正文。
        final LinearLayout pvCard = new LinearLayout(this);
        pvCard.setOrientation(LinearLayout.VERTICAL);
        pvCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams pvCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvCardLp.topMargin = dp(8);
        pvCard.setLayoutParams(pvCardLp);
        TextView pvCardHead = new TextView(this);
        pvCardHead.setText("微信 · 10:24");
        pvCardHead.setTextSize(12);
        pvCardHead.setTextColor(Color.argb(230, 255, 255, 255));
        pvCard.addView(pvCardHead);
        TextView pvCardTitle = new TextView(this);
        pvCardTitle.setText("张三");
        pvCardTitle.setTextSize(15);
        pvCardTitle.setTextColor(Color.WHITE);
        pvCardTitle.setTypeface(pvCardTitle.getTypeface(), android.graphics.Typeface.BOLD);
        pvCard.addView(pvCardTitle);
        final TextView pvCardBody = new TextView(this);
        pvCardBody.setText("明天下午三点开会，记得带上上周的方案，会议室在三楼，迟到的人请客喝奶茶。");
        pvCardBody.setTextSize(13);
        pvCardBody.setTextColor(Color.argb(230, 255, 255, 255));
        pvCardBody.setLineSpacing(0, 1.4f);
        pvCard.addView(pvCardBody);
        previewBox.addView(pvCard);

        // mock 扇子：5 颗彩色图标。
        final FrameLayout pvFan = new FrameLayout(this);
        LinearLayout.LayoutParams pvFanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvFanLp.topMargin = dp(8);
        pvFan.setLayoutParams(pvFanLp);
        previewBox.addView(pvFan);
        final int[] fanColors = new int[]{
                Color.rgb(34, 197, 94), Color.rgb(22, 119, 255),
                Color.rgb(255, 106, 0), Color.rgb(247, 181, 0),
                Color.rgb(0, 0, 0)};
        final String[] fanLetters = new String[]{"微", "支", "淘", "美", "抖"};
        final java.util.ArrayList<TextView> pvFanIcons = new java.util.ArrayList<>();
        for (int i = 0; i < fanColors.length; i++) {
            TextView ic = new TextView(this);
            ic.setText(fanLetters[i]);
            ic.setTextSize(13);
            ic.setTextColor(Color.WHITE);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(rounded(fanColors[i], dp(17)));
            FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(dp(34), dp(34));
            ic.setLayoutParams(icLp);
            pvFan.addView(ic);
            pvFanIcons.add(ic);
        }

        // 读当前滑条值（拖动中 prefs 还没落盘，从 apSeeks 读；没登记
        // 的（扇子位置）读 prefs）。预览更新器：按值重画 mock。
        final Runnable updatePreview = new Runnable() {
            @Override
            public void run() {
                int r = seekVal("cornerRadius", Appearance.DEF_CORNER_RADIUS);
                int h = seekVal("capsuleHeight", Appearance.DEF_CAPSULE_HEIGHT);
                int w = seekVal("capsuleWidth", Appearance.defCapsuleWidth(MainActivity.this));
                int cw = seekVal("cardWidth", Appearance.defCardWidth(MainActivity.this));
                int cr = seekVal("cardCornerRadius", Appearance.DEF_CARD_CORNER_RADIUS);
                int rows = seekVal("cardMaxRows", Appearance.DEF_CARD_MAX_ROWS);
                // v484: 展开卡片比例——预览卡片也跟着缩。
                int cs = seekVal("cardScale", Appearance.DEF_CARD_SCALE);
                float csf = cs / 100f;
                int ov = seekVal("fanOverlap", Appearance.DEF_FAN_OVERLAP);
                int dir = apInt(Appearance.KEY_FAN_DIRECTION, Appearance.DEF_FAN_DIRECTION);
                // 胶囊：高/宽/圆角（贴边头不圆，圆角钳到高一半）。
                int effR = Math.min(r, h / 2);
                android.graphics.drawable.GradientDrawable capBg =
                        new android.graphics.drawable.GradientDrawable();
                capBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                capBg.setCornerRadii(new float[]{0, 0, dp(effR), dp(effR), dp(effR), dp(effR), 0, 0});
                capBg.setColor(Color.argb(217, 30, 32, 38));
                pvCap.setBackground(capBg);
                LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(dp(w), dp(h));
                capLp.topMargin = dp(8);
                pvCap.setLayoutParams(capLp);
                // 卡片：宽/圆角/最大行数（正文按行数截）。
                android.graphics.drawable.GradientDrawable cardBg =
                        new android.graphics.drawable.GradientDrawable();
                cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                cardBg.setCornerRadii(new float[]{0, 0, dp(cr), dp(cr), dp(cr), dp(cr), 0, 0});
                cardBg.setColor(Color.argb(217, 30, 32, 38));
                pvCard.setBackground(cardBg);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        Math.round(dp(cw) * csf),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.topMargin = dp(8);
                pvCard.setLayoutParams(cardLp);
                // v484: 预览卡片按比例缩着画（左上角为轴），跟真卡一个路子。
                pvCard.setPivotX(0f);
                pvCard.setPivotY(0f);
                pvCard.setScaleX(csf);
                pvCard.setScaleY(csf);
                pvCardBody.setMaxLines(Math.max(1, rows - 2));
                // 扇子：重叠度/透明度/方向。
                int step = dp(22 - ov);
                boolean vertical = dir == Appearance.FAN_DIR_VERTICAL;
                for (int i = 0; i < pvFanIcons.size(); i++) {
                    TextView ic = pvFanIcons.get(i);
                    FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(dp(34), dp(34));
                    if (vertical) {
                        icLp.topMargin = i * step;
                    } else {
                        icLp.leftMargin = i * step;
                    }
                    ic.setLayoutParams(icLp);
                    // v459: 手动透明度已撤，mock 扇子一直不透明。
                    ic.setAlpha(1f);
                }
                int fanW = vertical ? dp(34) : dp(22) + 4 * step + dp(12);
                int fanH = vertical ? dp(22) + 4 * step + dp(12) : dp(34);
                LinearLayout.LayoutParams fanLp = new LinearLayout.LayoutParams(fanW, fanH);
                fanLp.topMargin = dp(8);
                pvFan.setLayoutParams(fanLp);
            }
        };
        // 拖任一滑条就自动展开预览（用户 “调整的时候自动展开一个
        // 实时调整预览”）；松手不收，点头部收起整个 body 时一起收。
        final Runnable showPreview = new Runnable() {
            @Override
            public void run() {
                if (previewBox.getVisibility() != View.VISIBLE) {
                    previewBox.setVisibility(View.VISIBLE);
                }
                updatePreview.run();
            }
        };

        // 扇子位置行：透明白底圆角 16，贴在红卡里（跟子开关一个路子）。
        LinearLayout fanBox = new LinearLayout(this);
        fanBox.setOrientation(LinearLayout.VERTICAL);
        fanBox.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        fanBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        fanBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(fanBox);
        searchTargets.put("fanpos", fanBox);

        // v464: 扇子位置的数值原来显示在标题栏右边（val），把副标题
        // 挤成两行、卡片比别的卡高；现在数值挪进 body，跟滑条放一块。
        LinearLayout fanTitleRow = new LinearLayout(this);
        fanTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        fanTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        fanTitleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fanBox.addView(fanTitleRow);
        TextView fanLabel = new TextView(this);
        fanLabel.setText("扇子位置");
        fanLabel.setTextSize(14);
        fanLabel.setTextColor(Color.WHITE);
        fanLabel.setTypeface(fanLabel.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fanLabel.setLayoutParams(flLp);
        fanTitleRow.addView(fanLabel);
        final TextView fanVal = new TextView(this);
        fanVal.setTextSize(13);
        fanVal.setTextColor(Color.WHITE);
        fanVal.setTypeface(fanVal.getTypeface(), android.graphics.Typeface.BOLD);
        fanTitleRow.addView(fanVal);
        TextView fanHint = new TextView(this);
        fanHint.setText("整组（胶囊 + 扇子）在这个高度，胶囊从扇子那里弹出来");
        fanHint.setTextSize(11);
        fanHint.setTextColor(Color.argb(230, 255, 255, 255));
        LinearLayout.LayoutParams fhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fhLp.topMargin = dp(2);
        fanHint.setLayoutParams(fhLp);
        fanBox.addView(fanHint);

        final SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(cur);
        // 白色滑条，贴在红底上看得清（跟卡片白字一个路子）。
        try {
            android.content.res.ColorStateList white =
                    android.content.res.ColorStateList.valueOf(Color.WHITE);
            seek.setProgressTintList(white);
            seek.setThumbTintList(white);
            seek.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            Color.argb(90, 255, 255, 255)));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekLp.topMargin = dp(6);
        seek.setLayoutParams(seekLp);
        fanBox.addView(seek);

        // 预设：顶部 0 / 中部 40 / 下部 70。透明白底，跟子开关一个路子。
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(8);
        presetRow.setLayoutParams(prLp);
        final int[] presets = new int[]{0, 40, 70};
        final String[] presetNames = new String[]{"顶部", "中部", "下部"};
        for (int i = 0; i < presets.length; i++) {
            TextView b = new TextView(this);
            b.setText(presetNames[i]);
            b.setTextSize(12);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(8)));
            b.setPadding(0, dp(6), 0, dp(6));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                blp.leftMargin = dp(6);
            }
            b.setLayoutParams(blp);
            presetRow.addView(b);
        }
        fanBox.addView(presetRow);

        final Runnable updateVal = new Runnable() {
            @Override
            public void run() {
                int pct = seek.getProgress();
                int dpv = Appearance.pctToDp(MainActivity.this, pct);
                fanVal.setText(pct + "% · 约 " + dpv + "dp");
            }
        };
        // 存 prefs + 发广播（SystemUI 立即重排）。只在松手/点预设时
        // 发一次，拖动中途不发，免得广播刷屏。
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                int progress = seek.getProgress();
                // v343 同款：commit() 同步落盘，拨完立刻杀进程也不丢。
                apPrefs.edit().putInt(key, progress).commit();
                // v464: 扇子位置一变就同步写本地备份文件。
                SettingsStore.saveAll(MainActivity.this);
                Intent i = new Intent(
                        "com.chenan.flymebobo.APPEARANCE");
                i.putExtra("dockPct", progress);
                sendBroadcast(i);
            }
        };
        updateVal.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateVal.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                apply.run();
            }
        });
        // 预设按钮：setProgress 只改数值和显示，保存/广播走 apply。
        for (int i = 0; i < presets.length; i++) {
            final int pv = presets[i];
            final TextView b = (TextView) presetRow.getChildAt(i);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seek.setProgress(pv);
                    apply.run();
                }
            });
        }

        // ---- v456: 胶囊三项 ----
        body.addView(buildAppearanceSliderRow(
                "胶囊圆角", "贴边那头永远不圆角，圆角最大不超过高度一半",
                0, Appearance.MAX_CORNER_RADIUS,
                apInt(Appearance.KEY_CORNER_RADIUS, Appearance.DEF_CORNER_RADIUS),
                Appearance.KEY_CORNER_RADIUS, "cornerRadius", "dp", showPreview));
        body.addView(buildAppearanceSliderRow(
                "胶囊高度", null,
                Appearance.MIN_CAPSULE_HEIGHT, Appearance.MAX_CAPSULE_HEIGHT,
                apInt(Appearance.KEY_CAPSULE_HEIGHT, Appearance.DEF_CAPSULE_HEIGHT),
                Appearance.KEY_CAPSULE_HEIGHT, "capsuleHeight", "dp", showPreview));
        body.addView(buildAppearanceSliderRow(
                "胶囊宽度", null,
                Appearance.MIN_CAPSULE_WIDTH, Appearance.MAX_CAPSULE_WIDTH,
                apInt(Appearance.KEY_CAPSULE_WIDTH, Appearance.defCapsuleWidth(this)),
                Appearance.KEY_CAPSULE_WIDTH, "capsuleWidth", "dp", showPreview));

        // ---- v456: 卡片三项（独立的，不跟胶囊走）----
        body.addView(buildAppearanceSliderRow(
                "卡片宽度", null,
                Appearance.MIN_CARD_WIDTH, Appearance.MAX_CARD_WIDTH,
                apInt(Appearance.KEY_CARD_WIDTH, Appearance.defCardWidth(this)),
                Appearance.KEY_CARD_WIDTH, "cardWidth", "dp", showPreview));
        body.addView(buildAppearanceSliderRow(
                "卡片圆角", null,
                0, Appearance.MAX_CARD_CORNER_RADIUS,
                apInt(Appearance.KEY_CARD_CORNER_RADIUS, Appearance.DEF_CARD_CORNER_RADIUS),
                Appearance.KEY_CARD_CORNER_RADIUS, "cardCornerRadius", "dp", showPreview));
        body.addView(buildAppearanceSliderRow(
                "卡片最多行数", "卡片高度跟内容走，这个是行数上限，超了截断",
                Appearance.MIN_CARD_MAX_ROWS, Appearance.MAX_CARD_MAX_ROWS,
                apInt(Appearance.KEY_CARD_MAX_ROWS, Appearance.DEF_CARD_MAX_ROWS),
                Appearance.KEY_CARD_MAX_ROWS, "cardMaxRows", "行", showPreview));
        // v484（用户「给展开卡片也加个比例」）：展开卡片比例调整——
        // 整张展开卡按比例缩放（60–100%，默认 100%），宽、高、
        // 字、按钮一起缩。跟音乐卡片的「卡片比例」一个路子。
        body.addView(buildAppearanceSliderRow(
                "卡片比例", "整张展开卡按比例缩放",
                Appearance.MIN_CARD_SCALE, Appearance.MAX_CARD_SCALE,
                apInt(Appearance.KEY_CARD_SCALE,
                        Appearance.DEF_CARD_SCALE),
                Appearance.KEY_CARD_SCALE, "cardScale", "%", showPreview));

        // ---- v456: 扇子图标三项 ----
        body.addView(buildAppearanceSliderRow(
                "扇子图标重叠度", "0 不重叠，16 重叠最多",
                0, Appearance.MAX_FAN_OVERLAP,
                apInt(Appearance.KEY_FAN_OVERLAP, Appearance.DEF_FAN_OVERLAP),
                Appearance.KEY_FAN_OVERLAP, "fanOverlap", "dp", showPreview));
        // v459 (user "取消扇子透明度调整功能"): 手动透明度滑条已撤——
        // 拖了不生效（见下）。改成「扇子自动变淡」开关（FEATURES 里
        // fanAutoDim）：扇子出现后 5 秒没点自动变半透明，点一下恢复。

        // 扇子图标排列方向：横向 / 纵向两个按钮。
        LinearLayout dirBox = new LinearLayout(this);
        dirBox.setOrientation(LinearLayout.VERTICAL);
        dirBox.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        dirBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams dirLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dirLp.topMargin = dp(8);
        dirLp.leftMargin = dp(12);
        dirLp.rightMargin = dp(12);
        dirBox.setLayoutParams(dirLp);
        body.addView(dirBox);
        TextView dirLabel = new TextView(this);
        dirLabel.setText("扇子图标排列方向");
        dirLabel.setTextSize(14);
        dirLabel.setTextColor(Color.WHITE);
        dirLabel.setTypeface(dirLabel.getTypeface(), android.graphics.Typeface.BOLD);
        dirBox.addView(dirLabel);
        TextView dirHint = new TextView(this);
        dirHint.setText("横向往中心扇开，纵向往下叠");
        dirHint.setTextSize(11);
        dirHint.setTextColor(Color.argb(230, 255, 255, 255));
        dirBox.addView(dirHint);
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dirRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dirRowLp.topMargin = dp(8);
        dirRow.setLayoutParams(dirRowLp);
        dirBox.addView(dirRow);
        final TextView dirH = new TextView(this);
        dirH.setText("横向");
        dirH.setTextSize(13);
        dirH.setTextColor(Color.WHITE);
        dirH.setGravity(Gravity.CENTER);
        dirH.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams dirHLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dirH.setLayoutParams(dirHLp);
        dirRow.addView(dirH);
        final TextView dirV = new TextView(this);
        dirV.setText("纵向");
        dirV.setTextSize(13);
        dirV.setTextColor(Color.WHITE);
        dirV.setGravity(Gravity.CENTER);
        dirV.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams dirVLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dirVLp.leftMargin = dp(8);
        dirV.setLayoutParams(dirVLp);
        dirRow.addView(dirV);
        final Runnable paintDir = new Runnable() {
            @Override
            public void run() {
                int d = apInt(Appearance.KEY_FAN_DIRECTION, Appearance.DEF_FAN_DIRECTION);
                boolean v = d == Appearance.FAN_DIR_VERTICAL;
                dirH.setBackground(rounded(v ? Color.argb(60, 255, 255, 255) : Color.WHITE, dp(8)));
                dirH.setTextColor(v ? Color.WHITE : cardColor);
                dirV.setBackground(rounded(v ? Color.WHITE : Color.argb(60, 255, 255, 255), dp(8)));
                dirV.setTextColor(v ? cardColor : Color.WHITE);
            }
        };
        paintDir.run();
        View.OnClickListener dirClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int d = v == dirV ? Appearance.FAN_DIR_VERTICAL : Appearance.FAN_DIR_HORIZONTAL;
                apPrefs.edit().putInt(Appearance.KEY_FAN_DIRECTION, d).commit();
                Intent i = new Intent("com.chenan.flymebobo.APPEARANCE");
                i.putExtra("fanDirection", d);
                sendBroadcast(i);
                paintDir.run();
                showPreview.run();
            }
        };
        dirH.setOnClickListener(dirClick);
        dirV.setOnClickListener(dirClick);

        // ---- v456: 恢复默认（用户 “记得加上恢复默认”）----
        TextView resetBtn = new TextView(this);
        resetBtn.setText("恢复默认");
        resetBtn.setTextSize(14);
        resetBtn.setTextColor(Color.WHITE);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setTypeface(resetBtn.getTypeface(), android.graphics.Typeface.BOLD);
        resetBtn.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(12)));
        resetBtn.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = dp(12);
        resetLp.leftMargin = dp(12);
        resetLp.rightMargin = dp(12);
        resetBtn.setLayoutParams(resetLp);
        body.addView(resetBtn);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 全部回默认值（宽按设备：手机 180/300，平板 260/380）。
                SharedPreferences.Editor e = apPrefs.edit();
                e.putInt(Appearance.KEY_DOCK_PCT, Appearance.DEF_DOCK_PCT);
                e.putInt(Appearance.KEY_CORNER_RADIUS, Appearance.DEF_CORNER_RADIUS);
                e.putInt(Appearance.KEY_CAPSULE_HEIGHT, Appearance.DEF_CAPSULE_HEIGHT);
                e.putInt(Appearance.KEY_CAPSULE_WIDTH, Appearance.defCapsuleWidth(MainActivity.this));
                e.putInt(Appearance.KEY_CARD_WIDTH, Appearance.defCardWidth(MainActivity.this));
                e.putInt(Appearance.KEY_CARD_CORNER_RADIUS, Appearance.DEF_CARD_CORNER_RADIUS);
                e.putInt(Appearance.KEY_CARD_MAX_ROWS, Appearance.DEF_CARD_MAX_ROWS);
                // v484: 展开卡片比例回默认 100%。
                e.putInt(Appearance.KEY_CARD_SCALE, Appearance.DEF_CARD_SCALE);
                e.putInt(Appearance.KEY_FAN_OVERLAP, Appearance.DEF_FAN_OVERLAP);
                e.putInt(Appearance.KEY_FAN_DIRECTION, Appearance.DEF_FAN_DIRECTION);
                e.commit();
                // 滑条拨回默认值（会触发各自的 updateVal + preview 广播；
                // apply 只在松手时跑，setProgress 不触发 onStopTracking，
                // 所以这里手动再发一次全量广播）。
                seek.setProgress(Appearance.DEF_DOCK_PCT);
                setApSeek("cornerRadius", Appearance.DEF_CORNER_RADIUS);
                setApSeek("capsuleHeight", Appearance.DEF_CAPSULE_HEIGHT);
                setApSeek("capsuleWidth", Appearance.defCapsuleWidth(MainActivity.this));
                setApSeek("cardWidth", Appearance.defCardWidth(MainActivity.this));
                setApSeek("cardCornerRadius", Appearance.DEF_CARD_CORNER_RADIUS);
                setApSeek("cardMaxRows", Appearance.DEF_CARD_MAX_ROWS);
                // v484: 展开卡片比例滑条拨回默认 100%。
                setApSeek("cardScale", Appearance.DEF_CARD_SCALE);
                setApSeek("fanOverlap", Appearance.DEF_FAN_OVERLAP);
                Intent i = new Intent("com.chenan.flymebobo.APPEARANCE");
                i.putExtra("dockPct", Appearance.DEF_DOCK_PCT);
                i.putExtra("cornerRadius", Appearance.DEF_CORNER_RADIUS);
                i.putExtra("capsuleHeight", Appearance.DEF_CAPSULE_HEIGHT);
                i.putExtra("capsuleWidth", Appearance.defCapsuleWidth(MainActivity.this));
                i.putExtra("cardWidth", Appearance.defCardWidth(MainActivity.this));
                i.putExtra("cardCornerRadius", Appearance.DEF_CARD_CORNER_RADIUS);
                i.putExtra("cardMaxRows", Appearance.DEF_CARD_MAX_ROWS);
                // v484: 展开卡片比例回默认 100%。
                i.putExtra("cardScale", Appearance.DEF_CARD_SCALE);
                i.putExtra("fanOverlap", Appearance.DEF_FAN_OVERLAP);
                i.putExtra("fanDirection", Appearance.DEF_FAN_DIRECTION);
                sendBroadcast(i);
                updateVal.run();
                paintDir.run();
                showPreview.run();
            }
        });
        updatePreview.run();
        return card;
    }
    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // v354: 重启系统界面。弹确认，确认后 root 杀掉 SystemUI 进程，
    // 系统会自动把它拉起来（状态栏/导航栏黑几秒后恢复），不用整机重启。
    private void restartSystemUI() {
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("重启系统界面")
                .setMessage("状态栏和导航栏会黑几秒后恢复。确定重启吗？")
                .setPositiveButton("重启", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Process p = Runtime.getRuntime().exec(
                                            new String[]{"su", "-c",
                                                    "pkill -f com.android.systemui"});
                                    p.waitFor();
                                    if (p.exitValue() != 0) {
                                        Runtime.getRuntime().exec(
                                                new String[]{"su", "-c",
                                                        "killall com.android.systemui"});
                                    }
                                } catch (Throwable e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(MainActivity.this,
                                                    "重启失败：没有拿到 root 权限",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            }
                        }).start();
                        Toast.makeText(MainActivity.this, "正在重启系统界面…",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
