package com.chenan.flymebobo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * flymebobo 2.4.2 (v504) — 应用规则页（功能储备 #5）。界面按
 * 2026-11-23 批准的预览做，风格跟设置页的功能卡对齐：彩色
 * 圆角卡 + 白图标砖 + 白字标题/副标题；卡片底色取 App 图标
 * 主色（和“胶囊自动取色”一个路子）。每行右边三段切换：
 * 跟随全局 / 永不弹 / 只贴边，点一下立刻保存并广播给
 * SystemUI 生效（AppRules.setRule），不用重启。
 */
public class AppRulesActivity extends Activity {

    private static final String[] MODE_LABELS =
            {"跟随全局", "永不弹", "只贴边"};
    private static final int[] FALLBACK_COLORS = {
            Color.rgb(59, 130, 246), Color.rgb(236, 72, 153),
            Color.rgb(245, 158, 11), Color.rgb(34, 197, 94),
            Color.rgb(139, 92, 246), Color.rgb(20, 184, 166),
            Color.rgb(249, 115, 22), Color.rgb(14, 165, 233),
    };

    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        int color;
        boolean system;
    }

    private final Map<String, Integer> myRules = new HashMap<>();
    private final Map<String, LinearLayout> segBoxes =
            new HashMap<>();
    private final Map<String, Integer> cardColors = new HashMap<>();
    private final List<View> rows = new ArrayList<>();
    private final List<String> rowKeys = new ArrayList<>();

    private LinearLayout listBox;
    private TextView countText;
    private EditText searchBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myRules.putAll(AppRules.readAll(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(16), dp(16), 0);

        TextView title = new TextView(this);
        title.setText("应用规则");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setTypeface(title.getTypeface(),
                android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("给每个应用单独定气泡行为，改完即时生效");
        sub.setTextSize(12.5f);
        sub.setTextColor(Color.rgb(107, 114, 128));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        TextView explain = new TextView(this);
        explain.setText("永不弹：通知不进气泡（通知栏照常） · "
                + "只贴边：只进左侧贴边扇子，不弹胶囊 · "
                + "跟随全局：按总开关走");
        explain.setTextSize(12);
        explain.setLineSpacing(0, 1.5f);
        explain.setTextColor(Color.rgb(75, 85, 99));
        explain.setBackground(rounded(Color.rgb(243, 244, 246),
                dp(12)));
        explain.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        exLp.topMargin = dp(12);
        explain.setLayoutParams(exLp);
        root.addView(explain);

        searchBox = new EditText(this);
        searchBox.setHint("搜索应用");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(13.5f);
        searchBox.setTextColor(Color.rgb(17, 24, 39));
        searchBox.setHintTextColor(Color.rgb(156, 163, 175));
        searchBox.setBackground(roundedBorder());
        searchBox.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams seLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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
        root.addView(searchBox);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = dp(6);
        scroll.setLayoutParams(scLp);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        root.addView(scroll);

        countText = new TextView(this);
        countText.setTextSize(12);
        countText.setTextColor(Color.rgb(107, 114, 128));
        countText.setPadding(0, dp(10), 0, dp(14));
        root.addView(countText);

        setContentView(root);
        refreshCount();

        final TextView loading = new TextView(this);
        loading.setText("正在读取应用列表…");
        loading.setTextSize(13);
        loading.setTextColor(Color.rgb(156, 163, 175));
        loading.setPadding(0, dp(16), 0, dp(16));
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
                        for (AppEntry e : apps) {
                            View row = buildRow(e);
                            rows.add(row);
                            rowKeys.add((e.label + "\n" + e.pkg)
                                    .toLowerCase(Locale.ROOT));
                            listBox.addView(row);
                        }
                        applyFilter(searchBox.getText() == null
                                ? "" : searchBox.getText()
                                        .toString());
                    }
                });
            }
        }).start();
    }

    /** 全部已安装应用（用户 + 系统都包含）：清单里已声明
     * QUERY_ALL_PACKAGES（v505，用户“权限给够”“包涵系统和
     * 用户的”），getInstalledApplications 拿全量。排序：
     * 已设规则 > 用户应用 > 系统应用，组内按名称。 */
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
                e.color = colorFor(e.pkg, e.icon);
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
                if (myRules.containsKey(e.pkg)) {
                    return 0;
                }
                return e.system ? 2 : 1;
            }
        });
        return apps;
    }

    private View buildRow(final AppEntry e) {
        final int color = e.color;
        cardColors.put(e.pkg, color);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(color, dp(22)));
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

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
            tv.setTextColor(color);
            tile.addView(tv);
        }
        card.addView(tile);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        tlp.rightMargin = dp(8);
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
        card.addView(texts);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(Color.argb(60, 255, 255, 255),
                dp(10)));
        seg.setPadding(dp(2), dp(2), dp(2), dp(2));
        seg.setLayoutParams(new LinearLayout.LayoutParams(
                dp(168), ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int mode = 0; mode < MODE_LABELS.length; mode++) {
            final int m = mode;
            TextView tv = new TextView(this);
            tv.setText(MODE_LABELS[mode]);
            tv.setTextSize(11.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setPadding(0, dp(6), 0, dp(6));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setMode(e.pkg, m);
                }
            });
            seg.addView(tv);
        }
        segBoxes.put(e.pkg, seg);
        card.addView(seg);
        paintSegments(e.pkg);
        return card;
    }

    private void setMode(String pkg, int mode) {
        if (mode == AppRules.DEFAULT) {
            myRules.remove(pkg);
        } else {
            myRules.put(pkg, mode);
        }
        AppRules.setRule(this, pkg, mode);
        paintSegments(pkg);
        refreshCount();
    }

    private void paintSegments(String pkg) {
        LinearLayout seg = segBoxes.get(pkg);
        if (seg == null) {
            return;
        }
        Integer cur = myRules.get(pkg);
        int mode = cur == null ? AppRules.DEFAULT : cur;
        Integer c = cardColors.get(pkg);
        int color = c == null ? FALLBACK_COLORS[0] : c;
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView tv = (TextView) seg.getChildAt(i);
            if (i == mode) {
                tv.setBackground(rounded(Color.WHITE, dp(8)));
                tv.setTextColor(color);
                tv.setTypeface(tv.getTypeface(),
                        android.graphics.Typeface.BOLD);
            } else {
                tv.setBackground(null);
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(tv.getTypeface(),
                        android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void refreshCount() {
        countText.setText("已单独设置 " + myRules.size()
                + " 个应用 · 其余全部跟随全局");
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

    /** 卡片底色：取图标平均色再把饱和度/亮度拉到鲜艳区间；
     * 取不到就按包名哈希从设置页同款色板里挑一个。 */
    private int colorFor(String pkg, Drawable icon) {
        int avg = averageColor(icon);
        if (avg == 0) {
            return FALLBACK_COLORS[Math.abs(pkg.hashCode())
                    % FALLBACK_COLORS.length];
        }
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(avg), Color.green(avg),
                Color.blue(avg), hsv);
        hsv[1] = Math.max(0.45f, Math.min(0.85f, hsv[1]));
        hsv[2] = Math.max(0.45f, Math.min(0.72f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private int averageColor(Drawable icon) {
        if (icon == null) {
            return 0;
        }
        try {
            Bitmap bmp;
            if (icon instanceof BitmapDrawable
                    && ((BitmapDrawable) icon).getBitmap() != null) {
                bmp = Bitmap.createScaledBitmap(
                        ((BitmapDrawable) icon).getBitmap(),
                        16, 16, true);
            } else {
                bmp = Bitmap.createBitmap(16, 16,
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                icon.setBounds(0, 0, 16, 16);
                icon.draw(canvas);
            }
            long r = 0, g = 0, b = 0, n = 0;
            for (int x = 0; x < bmp.getWidth(); x++) {
                for (int y = 0; y < bmp.getHeight(); y++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 200) {
                        continue;
                    }
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            if (n == 0) {
                return 0;
            }
            return Color.rgb((int) (r / n), (int) (g / n),
                    (int) (b / n));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density
                + 0.5f);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable roundedBorder() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.WHITE);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), Color.rgb(209, 213, 219));
        return d;
    }
}
