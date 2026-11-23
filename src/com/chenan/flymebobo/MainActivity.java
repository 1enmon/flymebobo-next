package com.chenan.flymebobo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

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
            new Feature("notif", "通知气泡", "左贴边胶囊 · 点开变卡片",
                    "通知变成左贴边的胶囊，点一下展开成卡片。\n钉住、消散、扇子收纳都在，永不自动展开。\n点屏幕空白，所有胶囊逐条向右消散。",
                    Color.rgb(59, 130, 246), "泡"),
            new Feature("music", "音乐胶囊", "歌名 · 歌手 · 歌词 · 专辑图",
                    "音乐通知变成一张播放器卡片：\n歌名、歌手、实时歌词、专辑圆盘、进度条。\n钉住后胶囊上显示一行加大滚动歌词。\n同个 App 只保留一个音乐胶囊。",
                    Color.rgb(236, 72, 153), "乐"),
            new Feature("dl", "下载与安装", "进度条 · 速度 · 剩余时间",
                    "下载和安装都有实时胶囊：\n胶囊底部有一条细进度条，第二行显示\n已下 / 总量、速度和剩余时间。\n下完显示“下载完成”，3 秒后自动收起。",
                    Color.rgb(245, 158, 11), "下"),
            new Feature("code", "验证取件码", "验证码自动复制 · 取件码自动钉住",
                    "验证码和取件码会被自动识别出来：\n验证码自动复制到剪贴板，取件码自动钉住。\n胶囊只显示大字码，不带标题；\n智能柜的柜号也会一起显示，中英文都认。",
                    Color.rgb(34, 197, 94), "码"),
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
            new Feature("card", "展开卡片", "点胶囊展开卡片（不含音乐）",
                    "点一下胶囊，展开成卡片看全文。\n关掉后点胶囊不再展开卡片。\n音乐胶囊不受这个开关管，\n由“音乐卡片”开关单独控制。",
                    Color.rgb(20, 184, 166), "卡"),
            new Feature("bigCard", "大卡片模式", "通知直接弹大卡片，不经过小胶囊",
                    "通知一来直接以大卡片依次排列，\n不用先点小胶囊再展开。\n点空白处大卡片自动右滑消散成图标。\n音乐弹的是播放器卡片；\n来电还是胶囊（没有大卡片）。",
                    Color.rgb(99, 102, 241), "大", false, null),
            // v459: 扇子自动变淡——扇子出现后 5 秒没点，自动变半透明
            // （省得挡着屏幕）；点一下扇子恢复不透明并照常展开。
            // 默认开。关掉后扇子一直不透明。
            new Feature("fanAutoDim", "扇子自动变淡", "扇子 5 秒没点自动变半透明",
                    "扇子出现后 5 秒没人点，\n自动变半透明，不挡屏幕。\n点一下扇子恢复不透明，\n并照常展开。关掉后扇子一直不透明。",
                    Color.rgb(99, 102, 241), "淡", true, null),
            // v465: 胶囊自动取色——开着（默认）：胶囊底色取 App 图标
            // 的主色（半透明 80%），每个 App 的胶囊颜色都不一样；
            // 关着：全部用默认白色 + 黑色描边，不跟图标走。
            // 来电胶囊不受管（本来就是绿/红）。拨动立即生效，不用重启。
            // v467: 用户 2026-11-23 “反了”“默认开启”——默认开。
            new Feature("autoTint", "胶囊自动取色", "按 App 图标主色给胶囊上色",
                    "开着（默认）：胶囊底色取 App 图标的主色，\n每个 App 的胶囊颜色都不一样。\n关着：全部用默认白色 + 黑色描边，\n不跟图标走。来电胶囊不受管。\n拨动立即生效，不用重启。",
                    Color.rgb(16, 185, 129), "色", true, null),
            new Feature("scroll", "胶囊滚动", "内容太长滚动显示",
                    "胶囊里的标题和正文太长时，\n会左右滚动显示完整内容。\n关掉后超长内容用 … 截断。",
                    Color.rgb(217, 70, 239), "滚"),
            new Feature("miniwindow", "小窗打开", "点卡片小窗打开 App",
                    "点一下展开的卡片，打开这个通知的 App。\n开着：小窗打开；关着：全屏打开。",
                    Color.rgb(14, 165, 233), "窗"),
            new Feature("blankDissipate", "自动消散", "点空白处胶囊自动消散",
                    "点一下屏幕空白处，所有胶囊逐条向右消散。\n关掉后点空白处胶囊留在屏幕上，\n只能手动右滑消散或左滑收进扇子。",
                    Color.rgb(249, 115, 22), "散"),
            // v457: 自动销毁——嵌进“自动消散”卡片里。默认关。开着：
            // 点空白处胶囊消散时，系统通知也一起撤销（从通知栏
            // 消失）；关着：只消散胶囊，通知还留在通知栏里。
            // 音乐通知不撤销——撤销会停掉音乐（v276 同款）。
            new Feature("autoDestroy", "自动销毁", "消散时同时撤销系统通知",
                    "开着：点空白处胶囊消散时，\n系统通知也一起撤销（从通知栏消失）。\n关着（默认）：只消散胶囊，\n通知还留在通知栏里，扇子图标还在。\n音乐通知不撤销——撤销会停掉音乐。",
                    Color.rgb(249, 115, 22), "销", false, "blankDissipate"),
            // v336: 音乐卡片开关嵌进“音乐胶囊”卡片里，不再单独占一行。
            // v345: QQ 免费听开关已去掉，永远打开。
            // v346: 介绍留着、胶囊留着、不设开关——QQ 免费听只展示、不设开关。
            new Feature("musicCard", "音乐卡片", "点音乐胶囊展开播放器卡片",
                    "点一下音乐胶囊，展开成播放器卡片：\n专辑圆盘、歌词、进度条和播放控制。\n关掉后点音乐胶囊直接打开音乐 App，\n不再展开播放器卡片。",
                    Color.rgb(219, 39, 119), "音", true, "music"),
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
            new Feature("qqFree", "QQ音乐flyme版免费听", "低于2小时自动补时长到4小时",
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

    // v442: 来电高度滑条（在「来电」卡片展开区、「来电放右边」下面）。
    // 跟开关联动：只有 callRight 开着才能滑，关着就置灰禁用。
    private SeekBar callHeightSeek;
    private View callHeightBox;

    // v456: 外观滑条登记表（extraKey → SeekBar），实时预览按 key
    // 读各滑条的当前值（拖动中 prefs 还没落盘，不能读 prefs）。
    private final java.util.HashMap<String, SeekBar> apSeeks =
            new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
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
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
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
        hSub.setText("通知胶囊 · 音乐 · 下载 · 取件码 · 来电");
        hSub.setTextSize(11);
        hSub.setTextColor(Color.argb(230, 255, 255, 255));
        hTexts.addView(hSub);
        header.addView(hTexts);
        root.addView(header);

        // ---- Section label ----
        TextView section = new TextView(this);
        section.setText("功能开关（点一下展开说明）");
        section.setTextSize(12);
        section.setTextColor(Color.rgb(153, 153, 153));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(20);
        section.setLayoutParams(slp);
        root.addView(section);

        // ---- Feature bubbles ----
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        list.setLayoutParams(llp);
        root.addView(list);
        for (int i = 0; i < TOP_FEATURES.length; i++) {
            list.addView(buildFeatureCard(TOP_FEATURES[i]));
        }
        // v504 (2.4.2): 应用规则入口（功能储备 #5，用户点名先做；
        // 界面先预览后落地）。和功能卡同款式，点一下进
        // AppRulesActivity 给每个应用单独设规则。
        list.addView(buildAppRulesCard());

        // ---- Appearance (v439): 一个滑块，扇子位置 ----
        // 胶囊和扇子是一个整体（用户 “他们是一个整体” / “胶囊始终
        // 是从扇子的地方弹出来的”），所以只设扇子（整组）的锚点，
        // 胶囊跟着扇子走。滑块松手才生效，不用重启。
        // v443: 改成「外观胶囊」（用户 “改成外观胶囊”）——小标题
        // “外观”不要了，卡片自己就是标题，点开里面是扇子滑块，
        // 以后更多外观往卡里加。
        LinearLayout apList = new LinearLayout(this);
        apList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams apLlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apLlp.topMargin = dp(20);
        apList.setLayoutParams(apLlp);
        root.addView(apList);
        apList.addView(buildAppearanceCard());
        TextView apHint = new TextView(this);
        apHint.setText("胶囊跟着扇子一起走，从扇子那里弹出来；改完立即生效，不用重启。");
        apHint.setTextSize(11);
        apHint.setTextColor(Color.rgb(153, 153, 153));
        LinearLayout.LayoutParams apHlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apHlp.topMargin = dp(6);
        apHint.setLayoutParams(apHlp);
        root.addView(apHint);

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

        setContentView(scroll);
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
        final View card = list.getChildAt(idx);
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
        SettingsStore.saveAll(this);
    }

    // One feature card: coloured bubble header + expandable body.
    /** v504 (2.4.2): 应用规则入口卡。和功能卡同款式（彩色圆角
     * 卡 + 白图标砖 + 白字标题/副标题），右侧箭头，点一下进
     * AppRulesActivity。 */
    private View buildAppRulesCard() {
        final int color = Color.rgb(6, 182, 212);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(color, dp(22)));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

        TextView icon = new TextView(this);
        icon.setText("规");
        icon.setTextSize(15);
        icon.setTextColor(color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.WHITE, dp(10)));
        icon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(36), dp(36)));
        card.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("应用规则");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(),
                android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText("每个应用单独设：永不弹 / 只贴边");
        sub.setTextSize(12);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(sub);
        card.addView(texts);

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(24);
        chev.setTextColor(Color.WHITE);
        card.addView(chev);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this,
                            AppRulesActivity.class));
                } catch (Throwable ignored) {
                }
            }
        });
        return card;
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
                subBox.addView(buildSubSwitch(s));
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
        if (subBox.getChildCount() > 0) {
            subBox.setPadding(0, 0, 0, dp(12));
            card.addView(subBox);
        }

        final boolean[] expanded = new boolean[]{false};
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
        sub.setText("扇子位置 · 胶囊 · 卡片 · 扇子图标");
        sub.setTextSize(12);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        // v464: 跟上面功能卡一样高——副标题只一行，不换行（原来
        // 标题栏右边那个数值把副标题挤成两行，卡片比别的卡高）。
        sub.setSingleLine(true);
        texts.addView(sub);
        head.addView(texts);

        final TextView chev = new TextView(this);
        chev.setText("∨");
        chev.setTextSize(14);
        chev.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.leftMargin = dp(8);
        chev.setLayoutParams(chlp);
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
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                chev.setText(open ? "∧" : "∨");
            }
        });

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
                "卡片高度（最大行数）", "卡片高度跟内容走，这个是行数上限，超了截断",
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
