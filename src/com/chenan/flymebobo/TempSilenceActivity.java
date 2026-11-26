package com.chenan.flymebobo;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * flymebobo v525 — 临时静默设置页（功能储备 #4，2026-11-25
 * 预览、2026-11-26 上午定版：双向联动、排队补弹、来电突破
 * 加开关）。本页就是临时静默的全部设置区：开启/取消 + 倒计时、
 * 时长三档、勿扰联动开关、来电突破开关同页同组（用户
 * 2026-11-26 追加：来电开关必须放在这个设置区里，不进其他
 * 功能卡）。点开关立刻保存并广播给 SystemUI 生效。
 */
public class TempSilenceActivity extends Activity {

    private static final int INDIGO = Color.rgb(99, 102, 241);
    private static final int DARK = Color.rgb(17, 24, 39);
    private static final int GRAY = Color.rgb(107, 114, 128);

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
    /** Last chips state rendered ("active|minutes") — the 1s
     * countdown refresh must not rebuild the chips every tick. */
    private String chipsKey = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (ticking) {
                ticker.postDelayed(this, 1000);
            }
        }
    };

    /** A settings row with a capsule switch on the right. */
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
            row = new LinearLayout(TempSilenceActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(Color.rgb(243, 244, 246),
                    dp(14)));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout texts = new LinearLayout(
                    TempSilenceActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp =
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            texts.setLayoutParams(tlp);
            TextView t = new TextView(TempSilenceActivity.this);
            t.setText(title);
            t.setTextSize(14);
            t.setTextColor(DARK);
            t.setTypeface(t.getTypeface(),
                    android.graphics.Typeface.BOLD);
            texts.addView(t);
            TextView s = new TextView(TempSilenceActivity.this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(GRAY);
            texts.addView(s);
            row.addView(texts);

            track = new FrameLayout(TempSilenceActivity.this);
            track.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(46), dp(27)));
            dot = new View(TempSilenceActivity.this);
            dot.setBackground(rounded(Color.WHITE, dp(999)));
            track.addView(dot, new FrameLayout.LayoutParams(
                    dp(21), dp(21)));
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
            track.setBackground(rounded(on ? INDIGO
                    : Color.rgb(209, 213, 219), dp(999)));
            FrameLayout.LayoutParams p =
                    (FrameLayout.LayoutParams) dot.getLayoutParams();
            p.gravity = Gravity.CENTER_VERTICAL
                    | (on ? Gravity.END : Gravity.START);
            p.leftMargin = on ? 0 : dp(3);
            p.rightMargin = on ? dp(3) : 0;
            dot.setLayoutParams(p);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v527: 状态栏沉浸（与主页/安静规则页统一）。
        applyEdgeToEdge();
        TempSilence.State st = TempSilence.readState(this);
        selectedMinutes = st.minutes;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(16) + statusBarHeight(),
                dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("临时静默");
        title.setTextSize(20);
        title.setTextColor(DARK);
        title.setTypeface(title.getTypeface(),
                android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("开会、午睡时用 · 到点自动恢复");
        sub.setTextSize(12.5f);
        sub.setTextColor(GRAY);
        LinearLayout.LayoutParams subLp = lp();
        subLp.topMargin = dp(3);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        TextView explain = new TextView(this);
        explain.setText("开启后普通通知先排队、不弹气泡，恢复时按"
                + "顺序补弹；音乐、下载、实况不受影响。与系统勿扰"
                + "双向联动：开静默时同步打开勿扰，勿扰被打开时"
                + "气泡也自动排队；静默期间手动关掉勿扰，算提前"
                + "取消。");
        explain.setTextSize(12);
        explain.setLineSpacing(0, 1.5f);
        explain.setTextColor(Color.rgb(75, 85, 99));
        explain.setBackground(rounded(Color.rgb(243, 244, 246),
                dp(12)));
        explain.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams exLp = lp();
        exLp.topMargin = dp(12);
        explain.setLayoutParams(exLp);
        root.addView(explain);

        // ---- status / action card ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(INDIGO, dp(22)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = lp();
        cardLp.topMargin = dp(12);
        card.setLayoutParams(cardLp);
        statusTitle = new TextView(this);
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTextSize(15);
        statusTitle.setTypeface(statusTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        card.addView(statusTitle);
        countdown = new TextView(this);
        countdown.setTextColor(Color.WHITE);
        countdown.setTextSize(30);
        countdown.setTypeface(countdown.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams cdLp = lp();
        cdLp.topMargin = dp(2);
        countdown.setLayoutParams(cdLp);
        card.addView(countdown);
        statusSub = new TextView(this);
        statusSub.setTextColor(Color.argb(230, 255, 255, 255));
        statusSub.setTextSize(12);
        statusSub.setLineSpacing(0, 1.5f);
        card.addView(statusSub);
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
                        TempSilenceActivity.this);
                if (s.active()) {
                    TempSilence.end(TempSilenceActivity.this,
                            "cancel");
                } else {
                    TempSilence.start(TempSilenceActivity.this,
                            selectedMinutes);
                    if (s.linkDnd && !policyGranted()) {
                        Toast.makeText(TempSilenceActivity.this,
                                "已静默；勿扰没同步开（还没授权，"
                                        + "见下方提示）",
                                Toast.LENGTH_LONG).show();
                    }
                }
                refresh();
            }
        });
        card.addView(actionBtn);
        root.addView(card);

        // ---- duration ----
        root.addView(sectionLabel("静默时长"));
        chipsBox = new LinearLayout(this);
        chipsBox.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(chipsBox);
        buildChips();

        // ---- switches (same page, same group) ----
        root.addView(sectionLabel("联动与突破"));
        linkRow = new SwitchRow("与系统勿扰联动",
                "开静默 → 勿扰同步开；勿扰被打开 → 气泡自动排队",
                st.linkDnd, new Runnable() {
                    @Override
                    public void run() {
                        persistSettings();
                        refresh();
                    }
                });
        LinearLayout.LayoutParams linkLp = lp();
        linkLp.topMargin = dp(2);
        linkRow.row.setLayoutParams(linkLp);
        root.addView(linkRow.row);

        callRow = new SwitchRow("静默时来电突破",
                "开着：来电照常弹；关了：来电也排队，真全静默",
                st.callBreak, new Runnable() {
                    @Override
                    public void run() {
                        persistSettings();
                    }
                });
        LinearLayout.LayoutParams callLp = lp();
        callLp.topMargin = dp(8);
        callRow.row.setLayoutParams(callLp);
        root.addView(callRow.row);

        // ---- DND grant hint ----
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
        root.addView(dndHintBox);

        setContentView(scroll);
        refresh();
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(GRAY);
        t.setTypeface(t.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(18);
        p.leftMargin = dp(4);
        p.bottomMargin = dp(8);
        t.setLayoutParams(p);
        return t;
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
            chip.setTextColor(sel ? Color.WHITE : DARK);
            if (sel) {
                chip.setTypeface(chip.getTypeface(),
                        android.graphics.Typeface.BOLD);
            }
            chip.setBackground(rounded(sel ? INDIGO
                    : Color.rgb(243, 244, 246), dp(12)));
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
                        refresh();
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

    private void refresh() {
        // Reconcile first: an expiry or a manual DND-off that
        // landed while this page was away ends the silence here
        // too (the alarm / listener usually beat us to it; both
        // paths are idempotent).
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

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ticking = true;
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;
        ticker.removeCallbacks(tick);
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
