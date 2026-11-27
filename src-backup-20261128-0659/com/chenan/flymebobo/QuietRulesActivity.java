package com.chenan.flymebobo;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * flymebobo v527 — 安静规则页（wei 定：三个规则合并一页）。
 *
 * 上半 = 临时静默全套（原 TempSilenceActivity 的状态/开启取消/
 * 时长三档/勿扰联动/来电突破，语义一行不改，全部走 TempSilence
 * 的静态逻辑与 prefs）；下半 = 应用名单合表：应用规则（平时待遇
 * 跟随全局/只贴边/永不弹，读写 AppRules 存储）与场景例外（场景
 * 里 排队/突破，读写 SceneExceptions 存储）本来就是两张按应用
 * 的名单，合为一行两设。判定链不变：应用规则 → 场景 → 临时
 * 静默（BubbleWindow 里一行没动）。页面用主页同款卡片语言：
 * 彩色圆角卡 + 白图标盘 + 白字。
 */
public class QuietRulesActivity extends Activity {

    private static final int INDIGO = Color.rgb(99, 102, 241);
    private static final int SKY = Color.rgb(14, 165, 233);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int GRAY = Color.rgb(107, 114, 128);

    // ---- 临时静默区 ----
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private TextView statusTitle;
    private TextView statusSub;
    private TextView countdown;
    private TextView actionBtn;
    private LinearLayout chipsBox;
    private LinearLayout dndHintBox;
    private SwitchRow linkRow;
    private SwitchRow callRow;
    private int selectedMinutes = TempSilence.DEFAULT_MINUTES;
    private boolean ticking;
    private String chipsKey = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshSilence();
            if (ticking) {
                ticker.postDelayed(this, 1000);
            }
        }
    };

    /** 卡内开关行（半透明白底 + 白字 + 绿开关，与主页子开关同款）。 */
    private final class SwitchRow {
        final LinearLayout row;
        final FrameLayout track;
        final View dot;
        boolean on;
        final Runnable onFlip;

        SwitchRow(String title, String sub, boolean initial,
                Runnable onFlip) {
            this.on = initial;
            this.onFlip = onFlip;
            row = new LinearLayout(QuietRulesActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(Color.argb(60, 255, 255, 255),
                    dp(16)));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout texts = new LinearLayout(
                    QuietRulesActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp =
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            texts.setLayoutParams(tlp);
            TextView t = new TextView(QuietRulesActivity.this);
            t.setText(title);
            t.setTextSize(14);
            t.setTextColor(Color.WHITE);
            t.setTypeface(t.getTypeface(),
                    android.graphics.Typeface.BOLD);
            texts.addView(t);
            TextView s = new TextView(QuietRulesActivity.this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(Color.argb(230, 255, 255, 255));
            texts.addView(s);
            row.addView(texts);

            track = new FrameLayout(QuietRulesActivity.this);
            track.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(48), dp(28)));
            dot = new View(QuietRulesActivity.this);
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
            track.setBackground(rounded(on ? GREEN
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

    // ---- 应用名单区 ----
    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        boolean system;
    }

    /** 平时待遇三段的显示顺序 → AppRules 模式值。 */
    private static final int[] RULE_SEG_MODES = {
            AppRules.DEFAULT, AppRules.EDGE, AppRules.NEVER};
    private static final String[] RULE_SEG_LABELS = {
            "跟随", "收纳", "永不弹"};
    /** v560: 「全部应用」行专用标签——它是顶层默认，
     * 没有谁可跟随，第一档是「弹出」（存储值仍是
     * AppRules.DEFAULT，语义不变）。 */
    private static final String[] RULE_SEG_LABELS_DEFAULT = {
            "弹出", "收纳", "永不弹"};
    /** 场景里两段的显示顺序 → SceneExceptions 模式值。 */
    private static final int[] SCENE_SEG_MODES = {
            SceneExceptions.FOLLOW, SceneExceptions.BREAK};
    private static final String[] SCENE_SEG_LABELS = {
            "排队", "突破"};

    private final Map<String, Integer> myRules = new HashMap<>();
    private final Map<String, Integer> myScene = new HashMap<>();
    private final Map<String, TextView[]> ruleSegs = new HashMap<>();
    private final Map<String, TextView[]> sceneSegs = new HashMap<>();
    private final List<View> rows = new ArrayList<>();
    private final List<String> rowKeys = new ArrayList<>();
    private final List<AppEntry> loaded = new ArrayList<>();

    private ScrollView scrollView;
    private LinearLayout silenceCard;
    private LinearLayout appsCard;
    private LinearLayout listBox;
    private TextView countText;
    private EditText searchBox;
    /** v559: 「全部应用」行的「场景里」分段行，供场景模式
     * 总开关置灰联动（与主页卡那行同口径）。 */
    private View defaultSceneSegRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyEdgeToEdge();
        myRules.putAll(AppRules.readAll(this));
        myScene.putAll(SceneExceptions.readAll(this));
        TempSilence.State st = TempSilence.readState(this);
        selectedMinutes = st.minutes;

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.WHITE);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(16) + statusBarHeight(),
                dp(16), dp(24));
        scrollView.addView(root);

        // ---- 头部胶囊（与主页同款） ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(rounded(INDIGO, dp(999)));
        header.setPadding(dp(10), dp(12), dp(20), dp(12));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        header.setLayoutParams(hlp);
        TextView hIcon = new TextView(this);
        hIcon.setText("静");
        hIcon.setTextSize(16);
        hIcon.setTextColor(INDIGO);
        hIcon.setGravity(Gravity.CENTER);
        hIcon.setBackground(rounded(Color.WHITE, dp(10)));
        hIcon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(36), dp(36)));
        header.addView(hIcon);
        LinearLayout hTexts = new LinearLayout(this);
        hTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        htlp.leftMargin = dp(10);
        hTexts.setLayoutParams(htlp);
        TextView hName = new TextView(this);
        hName.setText("安静规则");
        hName.setTextSize(20);
        hName.setTextColor(Color.WHITE);
        hName.setTypeface(hName.getTypeface(),
                android.graphics.Typeface.BOLD);
        hTexts.addView(hName);
        TextView hSub = new TextView(this);
        hSub.setText("临时静默 · 应用名单");
        hSub.setTextSize(11);
        hSub.setTextColor(Color.argb(230, 255, 255, 255));
        hTexts.addView(hSub);
        header.addView(hTexts);
        root.addView(header);

        root.addView(buildSilenceCard(st));
        root.addView(buildAppsCard());

        TextView note = new TextView(this);
        note.setText("判定顺序：先看应用名单（永不弹一票否决）→ "
                + "再看场景（不在突破名单就排队）→ 最后临时静默"
                + "（静默中一律排队、到点补弹）。");
        note.setTextSize(12);
        note.setLineSpacing(0, 1.6f);
        note.setTextColor(Color.rgb(154, 160, 168));
        LinearLayout.LayoutParams nLp = lp();
        nLp.topMargin = dp(12);
        nLp.leftMargin = dp(4);
        nLp.rightMargin = dp(4);
        note.setLayoutParams(nLp);
        root.addView(note);

        setContentView(scrollView);
        refreshSilence();

        // 搜索跳转定位：highlight=silence/apps。
        final String hl = getIntent() == null ? null
                : getIntent().getStringExtra("highlight");
        if (hl != null) {
            final View target = "apps".equals(hl) ? appsCard
                    : silenceCard;
            if (target != null) {
                target.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollToView(target);
                        flashView(target);
                    }
                });
            }
        }
    }

    // ================= 临时静默卡 =================

    private View buildSilenceCard(TempSilence.State st) {
        silenceCard = new LinearLayout(this);
        silenceCard.setOrientation(LinearLayout.VERTICAL);
        silenceCard.setBackground(rounded(INDIGO, dp(22)));
        silenceCard.setPadding(dp(12), dp(12), dp(12), dp(14));
        LinearLayout.LayoutParams clp = lp();
        clp.topMargin = dp(14);
        silenceCard.setLayoutParams(clp);

        silenceCard.addView(cardHead("静", INDIGO, "临时静默",
                "开会、午睡时用 · 到点自动恢复"));
        silenceCard.addView(cardDesc("开启后普通通知先排队、不弹胶囊，"
                + "结束时按顺序补弹；音乐、下载、实况不停。"
                + "跟系统勿扰双向联动：开静默时同步打开勿扰，"
                + "勿扰被打开时气泡也自动排队；静默期间手动关掉"
                + "勿扰，算提前取消。"));

        // 状态区
        statusTitle = new TextView(this);
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTextSize(15);
        statusTitle.setTypeface(statusTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stLp = lp();
        stLp.topMargin = dp(12);
        statusTitle.setLayoutParams(stLp);
        silenceCard.addView(statusTitle);
        countdown = new TextView(this);
        countdown.setTextColor(Color.WHITE);
        countdown.setTextSize(30);
        countdown.setTypeface(countdown.getTypeface(),
                android.graphics.Typeface.BOLD);
        silenceCard.addView(countdown);
        statusSub = new TextView(this);
        statusSub.setTextColor(Color.argb(230, 255, 255, 255));
        statusSub.setTextSize(12);
        statusSub.setLineSpacing(0, 1.5f);
        silenceCard.addView(statusSub);
        actionBtn = new TextView(this);
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setTextSize(14);
        actionBtn.setTextColor(INDIGO);
        actionBtn.setTypeface(actionBtn.getTypeface(),
                android.graphics.Typeface.BOLD);
        actionBtn.setBackground(rounded(Color.WHITE, dp(12)));
        actionBtn.setPadding(0, dp(11), 0, dp(11));
        LinearLayout.LayoutParams abLp = lp();
        abLp.topMargin = dp(12);
        actionBtn.setLayoutParams(abLp);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TempSilence.State s = TempSilence.readState(
                        QuietRulesActivity.this);
                if (s.active()) {
                    TempSilence.end(QuietRulesActivity.this,
                            "cancel");
                } else {
                    TempSilence.start(QuietRulesActivity.this,
                            selectedMinutes);
                    if (s.linkDnd && !policyGranted()) {
                        Toast.makeText(QuietRulesActivity.this,
                                "已静默；勿扰没同步开（还没授权，"
                                        + "见下方提示）",
                                Toast.LENGTH_LONG).show();
                    }
                }
                refreshSilence();
            }
        });
        silenceCard.addView(actionBtn);

        // 时长
        silenceCard.addView(zoneLabel("静默时长"));
        chipsBox = new LinearLayout(this);
        chipsBox.setOrientation(LinearLayout.HORIZONTAL);
        silenceCard.addView(chipsBox);
        buildChips();

        // 联动与突破
        silenceCard.addView(zoneLabel("联动与突破"));
        linkRow = new SwitchRow("与系统勿扰联动",
                "开静默 → 勿扰同步开；勿扰被打开 → 气泡自动排队",
                st.linkDnd, new Runnable() {
                    @Override
                    public void run() {
                        persistSettings();
                        refreshSilence();
                    }
                });
        silenceCard.addView(linkRow.row);
        callRow = new SwitchRow("静默时来电突破",
                "开着：来电照常弹；关了：来电也排队，真全静默",
                st.callBreak, new Runnable() {
                    @Override
                    public void run() {
                        persistSettings();
                    }
                });
        LinearLayout.LayoutParams callLp = new LinearLayout
                .LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        callLp.topMargin = dp(8);
        callRow.row.setLayoutParams(callLp);
        silenceCard.addView(callRow.row);

        // 勿扰授权提示
        dndHintBox = new LinearLayout(this);
        dndHintBox.setOrientation(LinearLayout.VERTICAL);
        dndHintBox.setBackground(rounded(
                Color.rgb(254, 242, 242), dp(14)));
        dndHintBox.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams hintLp = lp();
        hintLp.topMargin = dp(12);
        dndHintBox.setLayoutParams(hintLp);
        TextView hintText = new TextView(this);
        hintText.setText("还没有「勿扰访问」权限：开静默时没法同步"
                + "打开系统勿扰（气泡静默本身不受影响，勿扰那一半"
                + "不生效）。授权一次即可。");
        hintText.setTextSize(12);
        hintText.setLineSpacing(0, 1.5f);
        hintText.setTextColor(Color.rgb(153, 27, 27));
        dndHintBox.addView(hintText);
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
        LinearLayout.LayoutParams gbLp = lp();
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
        dndHintBox.addView(grantBtn);
        silenceCard.addView(dndHintBox);
        return silenceCard;
    }

    private void buildChips() {
        TempSilence.State s = TempSilence.readState(this);
        boolean active = s.active();
        String key = active + "|" + selectedMinutes;
        if (key.equals(chipsKey) && chipsBox.getChildCount() > 0) {
            return;
        }
        chipsKey = key;
        chipsBox.removeAllViews();
        for (int i = 0; i < TempSilence.DURATIONS.length; i++) {
            final int min = TempSilence.DURATIONS[i];
            TextView chip = new TextView(this);
            chip.setText(min >= 60 ? (min / 60) + " 小时"
                    : min + " 分钟");
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(14);
            boolean sel = min == selectedMinutes;
            chip.setTextColor(sel ? INDIGO : Color.WHITE);
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
                        selectedMinutes = min;
                        persistSettings();
                        buildChips();
                        refreshSilence();
                    }
                });
            }
            chipsBox.addView(chip);
        }
    }

    private void persistSettings() {
        TempSilence.updateSettings(this, selectedMinutes,
                linkRow.on, callRow.on);
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

    private void refreshSilence() {
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
            statusTitle.setText("正在静默 · 到点自动恢复");
            countdown.setText(formatRemain(remainMs));
            countdown.setVisibility(View.VISIBLE);
            String endAt = new SimpleDateFormat("HH:mm",
                    Locale.CHINA).format(new Date(s.until));
            statusSub.setText("恢复时间 " + endAt + " · 消息先排队，"
                    + "恢复后按顺序补弹"
                    + (s.linkDnd ? " · 系统勿扰联动中" : ""));
            actionBtn.setText("提前取消，马上恢复");
        } else {
            statusTitle.setText("临时静默未开启");
            countdown.setVisibility(View.GONE);
            statusSub.setText("按一下开启，"
                    + (selectedMinutes >= 60
                            ? (selectedMinutes / 60) + " 小时"
                            : selectedMinutes + " 分钟")
                    + "后自动恢复，随时能提前取消。");
            actionBtn.setText("开启临时静默");
        }
        dndHintBox.setVisibility(s.linkDnd && !policyGranted()
                ? View.VISIBLE : View.GONE);
        linkRow.setOn(s.linkDnd);
        callRow.setOn(s.callBreak);
        if (selectedMinutes != s.minutes) {
            selectedMinutes = s.minutes;
        }
        buildChips();
    }

    private static String formatRemain(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long ss = sec % 60;
        if (h > 0) {
            return h + ":" + two(m) + ":" + two(ss);
        }
        return two(m) + ":" + two(ss);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    // ================= 应用名单卡 =================

    private View buildAppsCard() {
        appsCard = new LinearLayout(this);
        appsCard.setOrientation(LinearLayout.VERTICAL);
        appsCard.setBackground(rounded(SKY, dp(22)));
        appsCard.setPadding(dp(12), dp(12), dp(12), dp(14));
        LinearLayout.LayoutParams clp = lp();
        clp.topMargin = dp(12);
        appsCard.setLayoutParams(clp);

        appsCard.addView(cardHead("名", SKY, "应用名单",
                "每个应用一行：平时待遇 + 场景里让不让"));
        appsCard.addView(cardDesc("「场景里」指游戏、视频全屏时："
                + "突破＝照常弹，排队＝先收着、退出场景再补弹。"
                + "永不弹最大：设了永不弹，场景里突破了也不弹。"
                + "「全部应用」设成收纳，单个应用的中间档变"
                + "「弹出」；设成永不弹，第三档变「弹出」"
                + "——点它可让这个应用照常弹。"));

        searchBox = new EditText(this);
        searchBox.setHint("搜索应用（没设过的默认跟随、场景里排队）");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(13.5f);
        searchBox.setTextColor(Color.rgb(17, 24, 39));
        searchBox.setHintTextColor(Color.rgb(156, 163, 175));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.WHITE);
        sb.setCornerRadius(dp(999));
        searchBox.setBackground(sb);
        searchBox.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams seLp = lp();
        seLp.topMargin = dp(12);
        searchBox.setLayoutParams(seLp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a,
                    int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a,
                    int b, int c) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        appsCard.addView(searchBox);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        appsCard.addView(listBox);
        // v559: 「全部应用」行常驻置顶——不进 rows/rowKeys，
        // 搜索过滤动不了它；也不进 loaded，底部计数不算它。
        listBox.addView(buildDefaultRow());

        countText = new TextView(this);
        countText.setTextSize(12);
        countText.setTextColor(Color.argb(230, 255, 255, 255));
        LinearLayout.LayoutParams ctLp = lp();
        ctLp.topMargin = dp(10);
        countText.setLayoutParams(ctLp);
        appsCard.addView(countText);

        final TextView loading = new TextView(this);
        loading.setText("正在读取应用列表…");
        loading.setTextSize(13);
        loading.setTextColor(Color.argb(230, 255, 255, 255));
        loading.setPadding(0, dp(16), 0, dp(8));
        loading.setGravity(Gravity.CENTER);
        listBox.addView(loading);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> apps = loadApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        listBox.removeView(loading);
                        loaded.addAll(apps);
                        for (AppEntry e : apps) {
                            View row = buildRow(e);
                            rows.add(row);
                            rowKeys.add((e.label + "\n" + e.pkg)
                                    .toLowerCase(Locale.ROOT));
                            listBox.addView(row);
                        }
                        refreshCount();
                        applyFilter(searchBox.getText() == null
                                ? "" : searchBox.getText()
                                        .toString());
                    }
                });
            }
        }).start();
        return appsCard;
    }

    /** 全部已安装应用（用户 + 系统，同旧两页）：已设过名单的
     * 排前面，再用户应用、系统应用，组内按名称。 */
    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        List<AppEntry> apps = new ArrayList<>();
        try {
            List<ApplicationInfo> ais =
                    pm.getInstalledApplications(0);
            for (ApplicationInfo ai : ais) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                AppEntry e = new AppEntry();
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
        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int ra = rank(a);
                int rb = rank(b);
                if (ra != rb) {
                    return ra - rb;
                }
                return collator.compare(a.label, b.label);
            }

            private int rank(AppEntry e) {
                if (myRules.containsKey(e.pkg)
                        || myScene.containsKey(e.pkg)
                        || SceneExceptions.effective(myScene, e.pkg)
                                == SceneExceptions.BREAK) {
                    return 0;
                }
                return e.system ? 2 : 1;
            }
        });
        return apps;
    }

    /** v559: 「全部应用」行——全局默认规则，与主页安静规则
     * 卡里那行（v558）同款：图标盘「全」字、副文案、平时/
     * 场景里两段控件。不新造逻辑：直接复用本页 segRow /
     * setRuleMode / setSceneMode / paint* 全套按 key 泛化的
     * 读写，key 用 AppRules / SceneExceptions 的 KEY_DEFAULT
     * （同一条目），落盘与回退语义与主页卡那行完全一致。 */
    private View buildDefaultRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = lp();
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
        TextView glyph = new TextView(this);
        glyph.setText("全");
        glyph.setTextSize(15);
        glyph.setTextColor(SKY);
        tile.addView(glyph);
        head.addView(tile);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        texts.setLayoutParams(tlp);
        TextView name = new TextView(this);
        name.setText("全部应用");
        name.setTextSize(14.5f);
        name.setTextColor(Color.WHITE);
        name.setTypeface(name.getTypeface(),
                android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = new TextView(this);
        sub.setText("全局默认 · 单独设过的应用不跟这一行");
        sub.setTextSize(10.5f);
        sub.setTextColor(Color.argb(230, 255, 255, 255));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);
        head.addView(texts);
        box.addView(head);

        box.addView(segRow("平时", RULE_SEG_LABELS_DEFAULT,
                AppRules.KEY_DEFAULT, true));
        View sceneRow = segRow("场景里", SCENE_SEG_LABELS,
                SceneExceptions.KEY_DEFAULT, false);
        defaultSceneSegRow = sceneRow;
        box.addView(sceneRow);
        paintRuleSeg(AppRules.KEY_DEFAULT);
        paintSceneSeg(SceneExceptions.KEY_DEFAULT);
        applyDefaultSceneLinkage();
        return box;
    }

    /** v559: 场景模式总开关关 → 「全部应用」行的「场景里」
     * 分段置灰且不可点（与主页卡 refreshSceneLinkage 同
     * 口径：分段 item 禁用 + 整行 alpha 0.45）。开关值读
     * 与 MainActivity.isOn 同一处 prefs（flymebobo_features
     * 的 sceneMode 键，默认开）。 */
    private void applyDefaultSceneLinkage() {
        boolean on = getSharedPreferences("flymebobo_features",
                Context.MODE_PRIVATE)
                .getBoolean("sceneMode", true);
        TextView[] items = sceneSegs.get(
                SceneExceptions.KEY_DEFAULT);
        if (items != null) {
            for (TextView item : items) {
                if (item != null) {
                    item.setEnabled(on);
                }
            }
        }
        if (defaultSceneSegRow != null) {
            defaultSceneSegRow.setAlpha(on ? 1f : 0.45f);
        }
    }

    private View buildRow(final AppEntry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(16)));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = lp();
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
            tv.setTextColor(SKY);
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

        box.addView(segRow("平时", RULE_SEG_LABELS, e.pkg, true));
        box.addView(segRow("场景里", SCENE_SEG_LABELS, e.pkg, false));
        paintRuleSeg(e.pkg);
        paintSceneSeg(e.pkg);
        return box;
    }

    /** 一行分段：左侧小标签 + 胶囊分段控件。 */
    private View segRow(String label, String[] labels,
            final String pkg, final boolean isRule) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = lp();
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(11.5f);
        lab.setTextColor(Color.argb(230, 255, 255, 255));
        lab.setLayoutParams(new LinearLayout.LayoutParams(
                dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(lab);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(10)));
        seg.setPadding(dp(2), dp(2), dp(2), dp(2));
        seg.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextSize(11.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(6), 0, dp(6));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isRule) {
                        if (idx == 1 && !AppRules.KEY_DEFAULT
                                .equals(pkg)) {
                            // v559: 单个应用中间档是「与当前
                            // 默认相反的例外」：默认收纳→写
                            // POP（弹出），默认非收纳→写
                            // EDGE（收纳）。全部应用行自己
                            // 不翻面，走原三档。
                            setRuleMode(pkg,
                                    defaultRuleMode()
                                            == AppRules.EDGE
                                            ? AppRules.POP
                                            : AppRules.EDGE);
                        } else if (idx == 2
                                && !AppRules.KEY_DEFAULT
                                        .equals(pkg)
                                && defaultRuleMode()
                                        == AppRules.NEVER
                                && ruleModeOf(pkg)
                                        != AppRules.NEVER) {
                            // v562: 默认永不弹时第三档是
                            // 「弹出」，点它写 POP；这一行
                            // 本身已是永不弹时，第三档仍是
                            // 永不弹，走原三档。全部应用行
                            // 自己不翻面。
                            setRuleMode(pkg, AppRules.POP);
                        } else {
                            setRuleMode(pkg,
                                    RULE_SEG_MODES[idx]);
                        }
                    } else {
                        setSceneMode(pkg, SCENE_SEG_MODES[idx]);
                    }
                }
            });
            items[i] = tv;
            seg.addView(tv);
        }
        if (isRule) {
            ruleSegs.put(pkg, items);
        } else {
            sceneSegs.put(pkg, items);
        }
        row.addView(seg);
        return row;
    }

    private void setRuleMode(String pkg, int mode) {
        if (mode == AppRules.DEFAULT) {
            myRules.remove(pkg);
        } else {
            myRules.put(pkg, mode);
        }
        AppRules.setRule(this, pkg, mode);
        paintRuleSeg(pkg);
        if (AppRules.KEY_DEFAULT.equals(pkg)) {
            // v559: 默认翻面后，名单里所有行的中间档标签
            // 都要跟着刷新（存量是跟随的行最明显）。
            for (String k : ruleSegs.keySet()) {
                if (!AppRules.KEY_DEFAULT.equals(k)) {
                    paintRuleSeg(k);
                }
            }
        }
        refreshCount();
    }

    /** v559: 「全部应用」当前的平时值（没设过＝弹出，
     * 即 AppRules.DEFAULT；v560 起第一档文案叫「弹出」）。 */
    private int defaultRuleMode() {
        Integer d = myRules.get(AppRules.KEY_DEFAULT);
        return d == null ? AppRules.DEFAULT : d;
    }

    /** v562: 某应用当前的存量平时值（没设过＝跟随）。 */
    private int ruleModeOf(String pkg) {
        Integer cur = myRules.get(pkg);
        return cur == null ? AppRules.DEFAULT : cur;
    }

    /** v562: POP 的显示档位：默认是永不弹时 POP 在第三
     * 档（它顶替的是永不弹那档），其余情况在中间档
     * （顶替收纳那档）。 */
    private int popSlot() {
        return defaultRuleMode() == AppRules.NEVER ? 2 : 1;
    }

    /** v559/v562: 单个应用中间档的显示文字：存量是
     * EDGE 显示「收纳」；存量是 POP 且 POP 在中间档时
     * 显示「弹出」；其余情况（跟随、永不弹、POP 在第
     * 三档）中间档是待选的例外——默认收纳显示
     * 「弹出」，否则「收纳」。 */
    private String middleRuleLabel(int storedMode) {
        if (storedMode == AppRules.EDGE) {
            return "收纳";
        }
        if (storedMode == AppRules.POP && popSlot() == 1) {
            return "弹出";
        }
        return defaultRuleMode() == AppRules.EDGE
                ? "弹出" : "收纳";
    }

    /** v562: 单个应用第三档的显示文字：存量是 NEVER
     * 显示「永不弹」；存量是 POP 且 POP 在第三档时
     * 显示「弹出」；其余情况默认是永不弹则第三档是
     * 待选的「弹出」，否则是「永不弹」。 */
    private String thirdRuleLabel(int storedMode) {
        if (storedMode == AppRules.NEVER) {
            return "永不弹";
        }
        if (storedMode == AppRules.POP && popSlot() == 2) {
            return "弹出";
        }
        return defaultRuleMode() == AppRules.NEVER
                ? "弹出" : "永不弹";
    }

    /** v562: 单个应用「平时」分段的选中档：NEVER 在
     * 第三档、EDGE 在中间档、POP 按 popSlot、跟随在
     * 第一档。 */
    private int ruleSel(int storedMode) {
        if (storedMode == AppRules.NEVER) {
            return 2;
        }
        if (storedMode == AppRules.EDGE) {
            return 1;
        }
        if (storedMode == AppRules.POP) {
            return popSlot();
        }
        return 0;
    }

    private void setSceneMode(String pkg, int mode) {
        myScene.put(pkg, mode);
        SceneExceptions.setMode(this, pkg, mode);
        paintSceneSeg(pkg);
        refreshCount();
    }

    private void paintRuleSeg(String pkg) {
        TextView[] items = ruleSegs.get(pkg);
        if (items == null) {
            return;
        }
        Integer cur = myRules.get(pkg);
        int mode = cur == null ? AppRules.DEFAULT : cur;
        if (AppRules.KEY_DEFAULT.equals(pkg)) {
            // 全部应用行自己不翻面：原三档原样画。
            paintSeg(items, RULE_SEG_MODES, mode);
            return;
        }
        // v559/v562: 档位翻面——文字与选中态按存量/默认
        // 决定（见 middleRuleLabel / thirdRuleLabel /
        // ruleSel）。
        items[1].setText(middleRuleLabel(mode));
        items[2].setText(thirdRuleLabel(mode));
        paintSegSel(items, ruleSel(mode));
    }

    /** 按下标画分段选中态（paintSeg 的下标版，样式同）。 */
    private void paintSegSel(TextView[] items, int sel) {
        for (int i = 0; i < items.length; i++) {
            if (i == sel) {
                items[i].setBackground(rounded(Color.WHITE, dp(8)));
                items[i].setTextColor(SKY);
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

    private void paintSceneSeg(String pkg) {
        TextView[] items = sceneSegs.get(pkg);
        if (items == null) {
            return;
        }
        int mode = SceneExceptions.effective(myScene, pkg);
        paintSeg(items, SCENE_SEG_MODES, mode);
    }

    private void paintSeg(TextView[] items, int[] modes, int mode) {
        for (int i = 0; i < items.length; i++) {
            if (modes[i] == mode) {
                items[i].setBackground(rounded(Color.WHITE, dp(8)));
                items[i].setTextColor(SKY);
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

    private void refreshCount() {
        int breaks = 0;
        for (AppEntry e : loaded) {
            if (SceneExceptions.effective(myScene, e.pkg)
                    == SceneExceptions.BREAK) {
                breaks++;
            }
        }
        if (loaded.isEmpty()) {
            countText.setText("场景里照常弹出：微信、QQ（默认）"
                    + " · 其余先排队");
            return;
        }
        // v559: 「全部应用」默认条目（KEY_DEFAULT）也在
        // myRules 里，但它是默认规则不是名单成员，不计数。
        int ruleCount = myRules.size()
                - (myRules.containsKey(AppRules.KEY_DEFAULT)
                        ? 1 : 0);
        countText.setText("已单独设置 " + ruleCount
                + " 个应用的平时待遇 · 场景里突破 " + breaks
                + " 个，其余排队、退出场景补弹");
    }

    private void applyFilter(String q) {
        String needle = q == null ? "" : q.trim()
                .toLowerCase(Locale.ROOT);
        for (int i = 0; i < rows.size(); i++) {
            boolean show = needle.isEmpty()
                    || rowKeys.get(i).contains(needle);
            rows.get(i).setVisibility(
                    show ? View.VISIBLE : View.GONE);
        }
    }

    // ================= 通用小件 =================

    private View cardHead(String iconText, int color, String title,
            String sub) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(this);
        icon.setText(iconText);
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
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        texts.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(Color.argb(230, 255, 255, 255));
        texts.addView(s);
        head.addView(texts);
        return head;
    }

    private View cardDesc(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setLineSpacing(0, 1.7f);
        t.setTextColor(Color.argb(242, 255, 255, 255));
        t.setBackground(rounded(Color.argb(25, 0, 0, 0), dp(12)));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private View zoneLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.argb(200, 255, 255, 255));
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void applyEdgeToEdge() {
        try {
            android.view.Window w = getWindow();
            w.setStatusBarColor(Color.TRANSPARENT);
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id)
                : dp(24);
    }

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

    @Override
    protected void onResume() {
        super.onResume();
        refreshSilence();
        applyDefaultSceneLinkage();
        ticking = true;
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;
        ticker.removeCallbacks(tick);
        SettingsStore.saveAll(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics()
                .density);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }
}
