package com.chenan.flymebobo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** v635: 记忆本查看页（AI 卡进入）——没了结的事 +
 * 记住的习惯两组，逐条可删、顶部可清空。只读本机
 * ai_memory.json，删改立即落盘。 */
public class MemoryActivity extends Activity {

    private LinearLayout box;
    private JSONObject mem;

    private int dp(int v) {
        return Math.round(v * getResources()
                .getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("记忆本");
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(20));
        sv.addView(box);
        setContentView(sv);
        render();
    }

    private void render() {
        box.removeAllViews();
        mem = AiMemory.load(this);
        TextView head = new TextView(this);
        head.setText("它记住的事都在这儿，只存本机。"
                + "记错了随手删掉就行。");
        head.setTextSize(13);
        head.setTextColor(Color.rgb(100, 116, 139));
        box.addView(head);
        if (AiMemory.isEmpty(mem)) {
            TextView empty = new TextView(this);
            empty.setText("\n还什么都没记住。开着「记忆本」"
                    + "用几天 AI 写早报/回顾，它会慢慢攒。");
            empty.setTextSize(14);
            empty.setTextColor(Color.rgb(51, 65, 85));
            box.addView(empty);
            addManual();
            addLocationRow();
            return;
        }
        TextView clear = new TextView(this);
        clear.setText("清空记忆本");
        clear.setTextSize(14);
        clear.setTextColor(Color.rgb(220, 38, 38));
        clear.setPadding(0, dp(12), 0, dp(4));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MemoryActivity.this)
                        .setTitle("清空记忆本")
                        .setMessage("记下的事会全部删掉，"
                                + "删了找不回来。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空",
                                new DialogInterface
                                        .OnClickListener() {
                                    @Override
                                    public void onClick(
                                            DialogInterface d,
                                            int w) {
                                        AiMemory.save(
                                                MemoryActivity.this,
                                                AiMemory.empty());
                                        render();
                                        Toast.makeText(
                                                MemoryActivity.this,
                                                "已清空",
                                                Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                })
                        .show();
            }
        });
        box.addView(clear);
        addSection("没了结的事", "pending");
        addSection("记住的习惯", "habits");
        addSection("记住的位置", "places");
        addSection("记住的日常", "daily");
        addManual();
        addLocationRow();
    }

    /** 手动教它记一条（先进「习惯」组，AI 更新时会
     * 自己归类到位置/日常等组）。 */
    private void addManual() {
        TextView h = new TextView(this);
        h.setText("教它记一条");
        h.setTextSize(13);
        h.setTextColor(Color.rgb(37, 99, 235));
        h.setTypeface(h.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams hlp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(18);
        h.setLayoutParams(hlp);
        box.addView(h);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText in =
                new android.widget.EditText(this);
        in.setHint("比如：家在渝北区，离公司两站地铁");
        in.setSingleLine(true);
        in.setTextSize(14);
        row.addView(in, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = new TextView(this);
        add.setText("记下来");
        add.setTextSize(14);
        add.setTextColor(Color.rgb(37, 99, 235));
        add.setPadding(dp(10), dp(4), 0, dp(4));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = in.getText().toString().trim();
                if (t.isEmpty()) {
                    return;
                }
                try {
                    JSONArray a = mem.optJSONArray("habits");
                    if (a == null) {
                        a = new JSONArray();
                        mem.put("habits", a);
                    }
                    JSONObject e = new JSONObject();
                    e.put("text", t);
                    e.put("since", new java.text
                            .SimpleDateFormat("yyyy-MM-dd",
                                    java.util.Locale.US)
                            .format(new java.util.Date()));
                    a.put(e);
                    AiMemory.save(MemoryActivity.this, mem);
                    render();
                    Toast.makeText(MemoryActivity.this,
                            "记下了", Toast.LENGTH_SHORT)
                            .show();
                } catch (Throwable ignored) {
                }
            }
        });
        row.addView(add);
        box.addView(row);
    }

    /** 位置记忆要定位权限（粗定位就够，只用来学
     * 「家在哪、常去哪」，不开就学不了位置）。 */
    private void addLocationRow() {
        boolean granted = checkSelfPermission(
                android.Manifest.permission
                        .ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager
                        .PERMISSION_GRANTED;
        TextView t = new TextView(this);
        t.setText(granted
                ? "定位权限：已开（位置记忆靠它，早晚各看一次大概位置）"
                : "定位权限：未开——开了它才能记住家和常去的地方");
        t.setTextSize(13);
        t.setTextColor(Color.rgb(100, 116, 139));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        t.setLayoutParams(lp);
        box.addView(t);
        if (!granted) {
            TextView btn = new TextView(this);
            btn.setText("去开定位权限");
            btn.setTextSize(14);
            btn.setTextColor(Color.rgb(37, 99, 235));
            btn.setPadding(0, dp(6), 0, 0);
            btn.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestPermissions(
                                    new String[]{
                                            android.Manifest
                                                    .permission
                                                    .ACCESS_COARSE_LOCATION},
                                    6351);
                        }
                    });
            box.addView(btn);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req,
            String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms,
                results);
        if (req == 6351) {
            render();
        }
    }

    private void addSection(String label, final String key) {
        JSONArray arr = mem.optJSONArray(key);
        if (arr == null || arr.length() == 0) {
            return;
        }
        TextView h = new TextView(this);
        h.setText(label);
        h.setTextSize(13);
        h.setTextColor(Color.rgb(37, 99, 235));
        h.setTypeface(h.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams hlp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        h.setLayoutParams(hlp);
        box.addView(h);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) {
                continue;
            }
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            TextView t = new TextView(this);
            String since = e.optString("since", "");
            t.setText(e.optString("text", "")
                    + (since.isEmpty() ? ""
                            : "\n" + since + " 记下"));
            t.setTextSize(14);
            t.setTextColor(Color.rgb(30, 41, 59));
            row.addView(t, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f));
            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setTextColor(Color.rgb(220, 38, 38));
            del.setPadding(dp(10), dp(4), 0, dp(4));
            del.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            JSONArray a = mem.optJSONArray(
                                    key);
                            if (a != null) {
                                a.remove(idx);
                                AiMemory.save(
                                        MemoryActivity.this,
                                        mem);
                                render();
                            }
                        }
                    });
            row.addView(del);
            box.addView(row);
        }
    }
}
