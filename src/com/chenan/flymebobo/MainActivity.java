package com.chenan.flymebobo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

        Feature(String key, String title, String subtitle, String body, int color, String icon) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.body = body;
            this.color = color;
            this.icon = icon;
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
            new Feature("card", "展开卡片", "点胶囊展开卡片",
                    "点一下胶囊，展开成卡片看全文。\n关掉后点胶囊不再展开卡片。",
                    Color.rgb(20, 184, 166), "卡"),
            new Feature("scroll", "胶囊滚动", "内容太长滚动显示",
                    "胶囊里的标题和正文太长时，\n会左右滚动显示完整内容。\n关掉后超长内容用 … 截断。",
                    Color.rgb(217, 70, 239), "滚"),
            new Feature("miniwindow", "小窗打开", "点卡片小窗打开 App",
                    "点一下展开的卡片，打开这个通知的 App。\n开着：小窗打开；关着：全屏打开。",
                    Color.rgb(14, 165, 233), "窗"),
    };

    private SharedPreferences prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
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
        hName.setText("煤气泡 2.0");
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
        for (int i = 0; i < FEATURES.length; i++) {
            list.addView(buildFeatureCard(FEATURES[i]));
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

        TextView gh = new TextView(this);
        gh.setText("github.com/1enmon/flymebobo · GPL-3.0-only");
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

        setContentView(scroll);
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

        final boolean[] expanded = new boolean[]{false};
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            }
        });
        return card;
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
                prefs.edit().putBoolean(f.key, now).apply();
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
        return prefs.getBoolean(key, true);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
