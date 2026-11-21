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
            new Feature("card", "展开卡片", "点胶囊展开卡片（不含音乐）",
                    "点一下胶囊，展开成卡片看全文。\n关掉后点胶囊不再展开卡片。\n音乐胶囊不受这个开关管，\n由“音乐卡片”开关单独控制。",
                    Color.rgb(20, 184, 166), "卡"),
            new Feature("bigCard", "大卡片模式", "通知直接弹大卡片，不经过小胶囊",
                    "通知一来直接以大卡片依次排列，\n不用先点小胶囊再展开。\n点空白处大卡片自动右滑消散成图标。\n音乐弹的是播放器卡片；\n来电还是胶囊（没有大卡片）。",
                    Color.rgb(99, 102, 241), "大", false, null),
            new Feature("scroll", "胶囊滚动", "内容太长滚动显示",
                    "胶囊里的标题和正文太长时，\n会左右滚动显示完整内容。\n关掉后超长内容用 … 截断。",
                    Color.rgb(217, 70, 239), "滚"),
            new Feature("miniwindow", "小窗打开", "点卡片小窗打开 App",
                    "点一下展开的卡片，打开这个通知的 App。\n开着：小窗打开；关着：全屏打开。",
                    Color.rgb(14, 165, 233), "窗"),
            new Feature("blankDissipate", "点击空白自动消散", "点空白处胶囊自动消散",
                    "点一下屏幕空白处，所有胶囊逐条向右消散。\n关掉后点空白处胶囊留在屏幕上，\n只能手动右滑消散或左滑收进扇子。",
                    Color.rgb(249, 115, 22), "散"),
            // v336: 音乐卡片开关嵌进“音乐胶囊”卡片里，不再单独占一行。
            // v345: QQ 免费听开关已去掉，永远打开。
            // v346: 介绍留着、胶囊留着、不设开关——QQ 免费听只展示、不设开关。
            new Feature("musicCard", "音乐卡片", "点音乐胶囊展开播放器卡片",
                    "点一下音乐胶囊，展开成播放器卡片：\n专辑圆盘、歌词、进度条和播放控制。\n关掉后点音乐胶囊直接打开音乐 App，\n不再展开播放器卡片。",
                    Color.rgb(219, 39, 119), "音", true, "music"),
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
    private LinearLayout list;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        hName.setText("煤气泡 2.2");
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

    // One feature card: coloured bubble header + expandable body.
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
        for (Feature s : FEATURES) {
            if (f.key.equals(s.parent)) {
                subBox.addView(buildSubSwitch(s));
            }
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

        TextView sub2 = new TextView(this);
        sub2.setText(sub.subtitle);
        sub2.setTextSize(11);
        sub2.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(sub2);
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
        wrap.addView(row);
        return wrap;
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
                boolean now = !isOn(f.key);
                // v343: commit() 同步落盘——apply() 异步，拨完立刻杀进程会丢，
                // 下次打开开关就回到旧值。
                prefs.edit().putBoolean(f.key, now).commit();
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
