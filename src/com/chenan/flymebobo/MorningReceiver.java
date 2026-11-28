package com.chenan.flymebobo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** v631: 每日早报（wei 预览定版）——App 进程精确闹钟
 * 到点拉 Open-Meteo 天气（免费无 Key），组装一句话 +
 * 卡片正文：轻气泡开着广播给 SystemUI 成早报卡 + 小
 * 签闪；经典模式发一条普通通知。设置存
 * flymebobo_morning（on/time/city），改完由设置页调
 * reschedule 立即重排；开机自动重排。 */
public class MorningReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE =
            "com.chenan.flymebobo.MORNING_FIRE";
    public static final String ACTION_REPORT =
            "com.chenan.flymebobo.MORNING_REPORT";
    // v634: 托 NS 用当前 AI 通道富化早报（NS 回广播
    // ACTION_REPORT 为正式版；30 秒没回就发规则版）。
    // v640: AI 管家——傍晚巡检（挑一件没了结的事
    // 主动提醒）三件套广播。
    public static final String ACTION_BUTLER_CHECK =
            "com.chenan.flymebobo.BUTLER_CHECK";
    public static final String ACTION_NUDGE_PREP =
            "com.chenan.flymebobo.NUDGE_PREP";
    public static final String ACTION_NUDGE =
            "com.chenan.flymebobo.BUTLER_NUDGE";
    public static final String ACTION_MORNING_PREP =
            "com.chenan.flymebobo.MORNING_PREP";
    public static final String PREFS = "flymebobo_morning";
    private static final String CHANNEL = "morning_report";
    private static final int REQ = 7717;

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE);
    }

    public static boolean isOn(Context ctx) {
        return prefs(ctx).getBoolean("on", false);
    }

    public static String timeOf(Context ctx) {
        return prefs(ctx).getString("time", "07:30");
    }

    public static String cityOf(Context ctx) {
        String c = prefs(ctx).getString("city", "重庆");
        return c == null || c.trim().isEmpty()
                ? "重庆" : c.trim();
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, MorningReceiver.class);
        i.setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(ctx, REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void reschedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(
                Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(ctx);
        try {
            am.cancel(pi);
        } catch (Throwable ignored) {
        }
        if (!isOn(ctx)) {
            FileLog.i("Morning off");
            return;
        }
        long at = nextTrigger(timeOf(ctx));
        try {
            am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable t) {
            try {
                am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
            }
        }
        FileLog.i("Morning set at " + at);
    }

    private static long nextTrigger(String hhmm) {
        int h = 7, m = 30;
        try {
            String[] parts = hhmm.split(":");
            h = Integer.parseInt(parts[0]);
            m = Integer.parseInt(parts[1]);
        } catch (Throwable ignored) {
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis()
                <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (intent == null) {
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(
                intent.getAction())) {
            reschedule(ctx);
            scheduleNudge(ctx);
            return;
        }
        if (ACTION_BUTLER_CHECK.equals(
                intent.getAction())) {
            scheduleNudge(ctx);
            final Context app2 =
                    ctx.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    butlerCheck(app2);
                }
            }, "butler-check").start();
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        reschedule(ctx);
        scheduleNudge(ctx);
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                fire(app);
            }
        }, "morning-report").start();
    }

    /** v640: 傍晚巡检闹钟（18:30，每天）——只负责
     * 敲 NS 来挑提醒，自己不说话。 */
    public static void scheduleNudge(Context ctx) {
        try {
            java.util.Calendar c =
                    java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 18);
            c.set(java.util.Calendar.MINUTE, 30);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis()
                    <= System.currentTimeMillis()) {
                c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            android.app.AlarmManager am =
                    (android.app.AlarmManager)
                            ctx.getSystemService(
                                    Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(ctx,
                    MorningReceiver.class);
            i.setAction(ACTION_BUTLER_CHECK);
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getBroadcast(
                            ctx, 7718, i,
                            android.app.PendingIntent
                                    .FLAG_UPDATE_CURRENT
                                    | android.app.PendingIntent
                                            .FLAG_IMMUTABLE);
            try {
                am.setExactAndAllowWhileIdle(
                        android.app.AlarmManager
                                .RTC_WAKEUP,
                        c.getTimeInMillis(), pi);
            } catch (Throwable t) {
                am.setAndAllowWhileIdle(
                        android.app.AlarmManager
                                .RTC_WAKEUP,
                        c.getTimeInMillis(), pi);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 巡检门槛：AI 开着、主动提醒开着、轻气泡开着、
     * 记忆里真有没了结的事——才值得敲 NS 问一句。 */
    private void butlerCheck(Context ctx) {
        try {
            android.content.SharedPreferences features =
                    ctx.getSharedPreferences(
                            "flymebobo_features",
                            Context.MODE_PRIVATE);
            if (!features.getBoolean("aiEnabled", false)
                    || !features.getBoolean("aiNudge",
                            false)
                    || !features.getBoolean("lightMode",
                            false)) {
                return;
            }
            org.json.JSONObject mem = AiMemory.load(ctx);
            if (AiMemory.count(mem, "pending") == 0) {
                return;
            }
            ctx.sendBroadcast(new Intent(ACTION_NUDGE_PREP));
            FileLog.i("Butler check: prep sent");
        } catch (Throwable ignored) {
        }
    }

    private void fire(Context ctx) {
        String city = cityOf(ctx);
        String[] w = fetchWeather(ctx, city);
        if (w == null) {
            FileLog.i("Morning fetch fail city=" + city);
            return;
        }
        // v655: 天气快照落盘给轻气泡今日卡读（当天
        // 有效；SystemUI 读 /sdcard/flymebobo）。
        try {
            java.io.FileWriter wr = new java.io.FileWriter(
                    new java.io.File(
                            SettingsStore.DIR_PUBLIC
                                    + "/weather_cache.txt"),
                    false);
            wr.write(city + "\u0001" + w[0] + "\u0001"
                    + w[1] + "\u0001" + w[2] + "\u0001"
                    + new SimpleDateFormat("yyyy-MM-dd",
                            Locale.CHINA)
                                    .format(new java.util.Date()));
            wr.close();
        } catch (Throwable ignored) {
        }
        int code = Integer.parseInt(w[5]);
        int pp = Integer.parseInt(w[3]);
        int aqi = Integer.parseInt(w[4]);
        int tmin = Integer.parseInt(w[1]);
        boolean rain = rainLikely(code, pp);
        String line = w[0] + "，" + w[1] + "~" + w[2] + "°C";
        StringBuilder body = new StringBuilder();
        body.append(city).append("今天").append(w[0])
                .append("，").append(w[1]).append("~")
                .append(w[2]).append("°C");
        if (code == 45 || code == 48) {
            body.append("，早晨有雾");
        }
        body.append("。");
        if (rain) {
            body.append("出门记得带伞。");
        }
        if (aqi > 150) {
            body.append("空气质量偏差，戴个口罩。");
        } else if (aqi > 100) {
            body.append("空气一般，敏感的话戴个口罩。");
        }
        if (tmin <= 6) {
            body.append("早晚凉，加件外套。");
        }
        int dow = Calendar.getInstance()
                .get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY
                || dow == Calendar.SUNDAY) {
            body.append("今天是周末，节奏放慢点。");
        }
        String dateStr = new SimpleDateFormat(
                "M月d日 E", Locale.CHINA)
                .format(new java.util.Date());
        android.content.SharedPreferences features =
                ctx.getSharedPreferences(
                        "flymebobo_features",
                        Context.MODE_PRIVATE);
        // v634: 昨日回顾（本地统计；开关在 AI 卡）。
        // v635: 流水先全量读出来——回顾关着时，AI 记
        // 忆也要靠它更新。
        Digest dgAll = buildDigest(ctx);
        Digest dg = features.getBoolean("digestOn", true)
                ? dgAll : null;
        // v635: 位置采样（学家里/常去地，权限没给就跳过）。
        samplePlace(ctx);
        // v641: 每周回顾（周一才有；开关在 AI 卡
        // 「AI 管家」节，AI 写与否都先出本地统计版）。
        Weekly wk = null;
        if (java.util.Calendar.getInstance().get(
                java.util.Calendar.DAY_OF_WEEK)
                == java.util.Calendar.MONDAY
                && features.getBoolean("aiWeekly",
                        false)) {
            wk = buildWeekly(ctx);
        }
        boolean light = false;
        try {
            light = features.getBoolean("lightMode",
                    false);
        } catch (Throwable ignored) {
        }
        if (!light) {
            String full = body.toString();
            if (dg != null) {
                full += "\n" + dg.head + "：" + dg.text;
            }
            if (wk != null) {
                full += "\n" + wk.head + "：" + wk.text;
            }
            postNotification(ctx, line, full);
            FileLog.i("Morning report posted (classic)");
            return;
        }
        Intent report = new Intent(ACTION_REPORT);
        report.putExtra("line", line);
        report.putExtra("body", body.toString());
        report.putExtra("dateStr", dateStr);
        report.putExtra("city", city);
        if (dg != null) {
            report.putExtra("digestHead", dg.head);
            report.putExtra("digestText", dg.text);
            report.putExtra("digestChips", dg.chips);
        }
        if (wk != null) {
            report.putExtra("weeklyHead", wk.head);
            report.putExtra("weeklyText", wk.text);
        }
        boolean aiMaster = features.getBoolean(
                "aiEnabled", false);
        boolean wantMAi = aiMaster && features.getBoolean(
                "morningAi", false);
        boolean wantDAi = aiMaster && dg != null
                && features.getBoolean("digestAi", false);
        // v635: 记忆本开关（AI 卡）——有流水才更新。
        boolean wantMem = aiMaster && dgAll != null
                && features.getBoolean("aiMemory", false);
        boolean wantWeekly = aiMaster && wk != null;
        if (!wantMAi && !wantDAi && !wantMem
                && !wantWeekly) {
            ctx.sendBroadcast(report);
            FileLog.i("Morning report sent (light)");
            return;
        }
        // 要 AI 富化：先托 NS，等它回正式 REPORT；
        // 30 秒没等到就自己发规则版，不开天窗。
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        android.content.BroadcastReceiver once =
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c,
                            Intent i) {
                        latch.countDown();
                    }
                };
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(
                            ACTION_REPORT);
            try {
                java.lang.reflect.Method m =
                        Context.class.getMethod(
                                "registerReceiver",
                                android.content.BroadcastReceiver
                                        .class,
                                android.content.IntentFilter
                                        .class,
                                Integer.TYPE);
                m.invoke(ctx, once, f, 2);
            } catch (Throwable t) {
                ctx.registerReceiver(once, f);
            }
        } catch (Throwable ignored) {
        }
        Intent prep = new Intent(ACTION_MORNING_PREP);
        prep.putExtra("line", line);
        prep.putExtra("body", body.toString());
        prep.putExtra("dateStr", dateStr);
        prep.putExtra("city", city);
        prep.putExtra("cond", w[0]);
        prep.putExtra("tmin", w[1]);
        prep.putExtra("tmax", w[2]);
        prep.putExtra("pp", String.valueOf(pp));
        prep.putExtra("aqi", String.valueOf(aqi));
        if (dg != null) {
            prep.putExtra("digestHead", dg.head);
            prep.putExtra("digestText", dg.text);
            prep.putExtra("digestChips", dg.chips);
        }
        if (wk != null) {
            prep.putExtra("weeklyHead", wk.head);
            prep.putExtra("weeklyText", wk.text);
            prep.putExtra("weeklyBlob", wk.aiBlob);
        }
        prep.putExtra("wantWeekly", wantWeekly);
        if (dgAll != null) {
            prep.putExtra("aiBlob", dgAll.aiBlob);
        }
        prep.putExtra("wantMorningAi", wantMAi);
        prep.putExtra("wantDigestAi", wantDAi);
        prep.putExtra("wantMemory", wantMem);
        prep.putExtra("wantTodo", aiMaster
                && features.getBoolean("aiTodo", false));
        prep.putExtra("yday", new SimpleDateFormat(
                "yyyy-MM-dd", Locale.US)
                .format(new java.util.Date(
                        System.currentTimeMillis()
                                - 86400000L)));
        android.content.SharedPreferences places =
                ctx.getSharedPreferences("flymebobo_places",
                        Context.MODE_PRIVATE);
        prep.putExtra("placeNow",
                places.getString("placeNow", ""));
        prep.putExtra("homeText",
                places.getString("homeText", ""));
        ctx.sendBroadcast(prep);
        boolean got = false;
        try {
            // v635: 带记忆时多一步更新调用，多等一会。
            got = latch.await(wantMem ? 75 : 30,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        try {
            ctx.unregisterReceiver(once);
        } catch (Throwable ignored) {
        }
        if (got) {
            FileLog.i("Morning report via ai prep");
        } else {
            ctx.sendBroadcast(report);
            FileLog.i("Morning report sent (prep timeout)");
        }
    }

    /** v634: 昨日回顾的一份数据（本地统计产出）。 */
    private static class Digest {
        String head;
        String text;
        String chips;
        String aiBlob;
    }

    /** v634: 读昨天的按日流水做统计回顾：总数、应用
     * 排行、验证码次数、待取快递、今日行程；同时攒
     * 一份给 AI 的流水文本（带码条目只计数不入文本）。 */
    private Digest buildDigest(Context ctx) {
        try {
            String yday = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.US)
                    .format(new java.util.Date(
                            System.currentTimeMillis()
                                    - 86400000L));
            File f = new File(SettingsStore.DIR_PUBLIC
                    + "/journal-" + yday + ".txt");
            if (!f.exists()) {
                return null;
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f), "UTF-8"));
            Map<String, Integer> counts =
                    new LinkedHashMap<>();
            int total = 0;
            int codeCount = 0;
            int courierPending = 0;
            int blobLines = 0;
            List<String> chips = new ArrayList<>();
            StringBuilder blob = new StringBuilder();
            String today = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.US)
                    .format(new java.util.Date());
            SimpleDateFormat dayFmt =
                    new SimpleDateFormat("yyyy-MM-dd",
                            Locale.US);
            String ln;
            while ((ln = r.readLine()) != null) {
                String[] parts = ln.split("\u0001", -1);
                if (parts.length < 6) {
                    continue;
                }
                total++;
                String label = parts[2];
                String title = parts[3];
                String text = parts[4];
                Integer c0 = counts.get(label);
                counts.put(label, c0 == null ? 1
                        : c0 + 1);
                if ("1".equals(parts[5])) {
                    codeCount++;
                    continue;
                }
                if (blobLines < 50) {
                    blob.append(label).append("｜")
                            .append(title).append("｜")
                            .append(text.length() > 80
                                    ? text.substring(0, 80)
                                    : text)
                            .append("\n");
                    blobLines++;
                }
                try {
                    long when = Long.parseLong(parts[0]);
                    InfoParse.Card card =
                            InfoParse.tryParse(parts[1],
                                    title, text, when);
                    if (card != null) {
                        if ("courier".equals(card.kind)) {
                            courierPending++;
                            if (chips.size() < 3) {
                                chips.add("待取 · "
                                        + (card.title != null
                                                ? card.title
                                                : label));
                            }
                        } else if (("train".equals(card.kind)
                                || "flight".equals(
                                        card.kind))
                                && card.eventTime > 0
                                && today.equals(dayFmt.format(
                                        new java.util.Date(
                                                card.eventTime)))
                                && chips.size() < 3) {
                            chips.add("今日出行 · "
                                    + (card.title != null
                                            ? card.title
                                            : ""));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            r.close();
            if (total == 0) {
                return null;
            }
            StringBuilder tx = new StringBuilder();
            for (int k = 0; k < 3; k++) {
                String best = null;
                int bestN = 0;
                for (Map.Entry<String, Integer> e
                        : counts.entrySet()) {
                    if (e.getValue() > bestN) {
                        bestN = e.getValue();
                        best = e.getKey();
                    }
                }
                if (best == null) {
                    break;
                }
                counts.put(best, 0);
                if (k > 0) {
                    tx.append("、");
                }
                tx.append(best).append(" ").append(bestN)
                        .append(" 条");
            }
            tx.append("。");
            if (codeCount > 0) {
                tx.append("验证码 ").append(codeCount)
                        .append(" 次，已自动复制。");
            }
            if (courierPending > 0) {
                tx.append("快递 ").append(courierPending)
                        .append(" 件待取。");
            }
            Digest dg = new Digest();
            dg.head = "昨日回顾 · 共 " + total + " 条";
            dg.text = tx.toString();
            StringBuilder cb = new StringBuilder();
            for (int i = 0; i < chips.size(); i++) {
                if (i > 0) {
                    cb.append("\u0001");
                }
                cb.append(chips.get(i));
            }
            dg.chips = cb.toString();
            dg.aiBlob = blob.toString();
            return dg;
        } catch (Throwable t) {
            FileLog.i("Morning digest fail " + t);
            return null;
        }
    }

    private void postNotification(Context ctx, String line,
            String body) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "每日早报",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(ctx,
                    CHANNEL)
                    .setSmallIcon(
                            android.R.drawable.ic_dialog_info)
                    .setContentTitle("每日早报")
                    .setContentText(line)
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(body))
                    .setAutoCancel(true)
                    .build();
            nm.notify(REQ, n);
        } catch (Throwable t) {
            FileLog.i("Morning post fail " + t);
        }
    }

    /** 拉天气：{状况, 最低, 最高, 降雨概率, AQI, 代码}。 */
    private String[] fetchWeather(Context ctx, String city) {
        try {
            SharedPreferences p = prefs(ctx);
            double lat, lon;
            if (p.contains("lat:" + city)
                    && p.contains("lon:" + city)) {
                lat = Double.longBitsToDouble(
                        p.getLong("lat:" + city, 0));
                lon = Double.longBitsToDouble(
                        p.getLong("lon:" + city, 0));
            } else {
                JSONObject g = new JSONObject(get(
                        "https://geocoding-api.open-meteo.com"
                                + "/v1/search?name="
                                + URLEncoder.encode(
                                        city, "UTF-8")
                                + "&count=1&language=zh"
                                + "&format=json"));
                JSONObject first = g.getJSONArray("results")
                        .getJSONObject(0);
                lat = first.getDouble("latitude");
                lon = first.getDouble("longitude");
                p.edit()
                        .putLong("lat:" + city,
                                Double.doubleToLongBits(lat))
                        .putLong("lon:" + city,
                                Double.doubleToLongBits(lon))
                        .apply();
            }
            JSONObject f = new JSONObject(get(
                    "https://api.open-meteo.com/v1/forecast"
                            + "?latitude=" + lat
                            + "&longitude=" + lon
                            + "&daily=temperature_2m_max,"
                            + "temperature_2m_min,weather_code,"
                            + "precipitation_probability_max"
                            + "&timezone=auto&forecast_days=1"));
            JSONObject daily = f.getJSONObject("daily");
            int code = daily.getJSONArray("weather_code")
                    .optInt(0, -1);
            double tmax = daily
                    .getJSONArray("temperature_2m_max")
                    .optDouble(0);
            double tmin = daily
                    .getJSONArray("temperature_2m_min")
                    .optDouble(0);
            int pp = daily.getJSONArray(
                    "precipitation_probability_max")
                    .optInt(0, -1);
            int aqi = -1;
            try {
                JSONObject a = new JSONObject(get(
                        "https://air-quality-api.open-meteo"
                                + ".com/v1/air-quality"
                                + "?latitude=" + lat
                                + "&longitude=" + lon
                                + "&current=us_aqi"
                                + "&forecast_days=1"));
                aqi = a.getJSONObject("current")
                        .optInt("us_aqi", -1);
            } catch (Throwable ignored) {
            }
            return new String[]{condText(code),
                    String.valueOf(Math.round(tmin)),
                    String.valueOf(Math.round(tmax)),
                    String.valueOf(pp),
                    String.valueOf(aqi),
                    String.valueOf(code)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean rainLikely(int code, int pp) {
        if (pp >= 50) {
            return true;
        }
        switch (code) {
            case 51: case 53: case 55:
            case 61: case 63: case 65:
            case 80: case 81: case 82:
            case 95: case 96: case 99:
                return true;
            default:
                return false;
        }
    }

    private static String condText(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "晴间多云";
            case 2: return "多云";
            case 3: return "阴";
            case 45: case 48: return "有雾";
            case 51: case 53: case 55: return "毛毛雨";
            case 56: case 57: case 66: case 67:
                return "冻雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "雪粒";
            case 80: case 81: return "阵雨";
            case 82: return "暴雨";
            case 85: case 86: return "阵雪";
            case 95: return "雷阵雨";
            case 96: case 99: return "冰雹";
            default: return "多云";
        }
    }

    /** v641: 一周统计（本地版）：过去 7 天流水的
     * 总数/应用 Top3/验证码次数 + 采样流水给 AI。 */
    static class Weekly {
        String head;
        String text;
        String aiBlob;
    }

    private static Weekly buildWeekly(Context ctx) {
        try {
            SimpleDateFormat df = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.US);
            int total = 0;
            int codeCount = 0;
            Map<String, Integer> byLabel =
                    new LinkedHashMap<>();
            StringBuilder blob = new StringBuilder();
            int blobLines = 0;
            long dayMs = 86400000L;
            long now = System.currentTimeMillis();
            for (int back = 7; back >= 1; back--) {
                String ds = df.format(new java.util.Date(
                        now - back * dayMs));
                File f = new File(SettingsStore.DIR_PUBLIC
                        + "/journal-" + ds + ".txt");
                if (!f.exists()) {
                    continue;
                }
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(f),
                                "UTF-8"));
                String ln;
                while ((ln = br.readLine()) != null) {
                    String[] parts = ln.split("\u0001",
                            -1);
                    if (parts.length < 6) {
                        continue;
                    }
                    total++;
                    if ("1".equals(parts[5])) {
                        codeCount++;
                        continue;
                    }
                    Integer c = byLabel.get(parts[2]);
                    byLabel.put(parts[2],
                            c == null ? 1 : c + 1);
                    if (blobLines < 60) {
                        blob.append(parts[2]).append("｜")
                                .append(parts[3])
                                .append("｜")
                                .append(parts[4].length()
                                        > 60
                                        ? parts[4]
                                                .substring(0,
                                                        60)
                                        : parts[4])
                                .append("\n");
                        blobLines++;
                    }
                }
                br.close();
            }
            if (total == 0) {
                return null;
            }
            Weekly w = new Weekly();
            java.text.SimpleDateFormat md =
                    new java.text.SimpleDateFormat(
                            "M月d日", Locale.US);
            w.head = "上周小结 · " + md.format(
                    new java.util.Date(now - 7 * dayMs))
                    + "–" + md.format(new java.util.Date(
                            now - dayMs));
            StringBuilder t = new StringBuilder();
            t.append("上周共收 ").append(total)
                    .append(" 条通知。");
            java.util.List<Map.Entry<String, Integer>>
                    top = new ArrayList<>(
                            byLabel.entrySet());
            for (int i = 0; i < 3 && i < top.size();
                    i++) {
                int best = i;
                for (int j = i + 1; j < top.size(); j++) {
                    if (top.get(j).getValue()
                            > top.get(best).getValue()) {
                        best = j;
                    }
                }
                Map.Entry<String, Integer> tmp =
                        top.get(i);
                top.set(i, top.get(best));
                top.set(best, tmp);
            }
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < 3 && i < top.size();
                    i++) {
                if (i > 0) {
                    names.append("、");
                }
                names.append(top.get(i).getKey())
                        .append(" ")
                        .append(top.get(i).getValue())
                        .append(" 条");
            }
            if (!top.isEmpty()) {
                t.append(names).append(" 最多。");
            }
            if (codeCount > 0) {
                t.append("验证码 ").append(codeCount)
                        .append(" 次。");
            }
            w.text = t.toString();
            w.aiBlob = blob.toString();
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v635: 位置采样——取一次最近位置（粗定位、
     * 约百米精度），攒样本学「家」：凌晨 0-5 点出现
     * 最多的点（≥3 次）算家。结果存 flymebobo_places
     * （placeNow/homeText），喂给 AI 记忆更新。权限
     * 没给或拿不到位置就静默跳过。NS 的 AI 分析路径
     * 也会调（3 小时节流），样本才攒得起来。 */
    public static void samplePlace(Context ctx) {
        try {
            if (ctx.checkSelfPermission(
                    android.Manifest.permission
                            .ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager
                            .PERMISSION_GRANTED) {
                return;
            }
            android.content.SharedPreferences places =
                    ctx.getSharedPreferences(
                            "flymebobo_places",
                            Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (now - places.getLong("lastSampleAt", 0)
                    < 3L * 3600000L) {
                return;
            }
            android.location.LocationManager lm =
                    (android.location.LocationManager)
                            ctx.getSystemService(
                                    Context.LOCATION_SERVICE);
            if (lm == null) {
                return;
            }
            android.location.Location loc = null;
            try {
                loc = lm.getLastKnownLocation(
                        android.location.LocationManager
                                .NETWORK_PROVIDER);
            } catch (Throwable ignored) {
            }
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(
                            android.location.LocationManager
                                    .GPS_PROVIDER);
                } catch (Throwable ignored) {
                }
            }
            if (loc == null || now - loc.getTime()
                    > 24L * 3600000L) {
                return;
            }
            double la = Math.round(
                    loc.getLatitude() * 1000.0) / 1000.0;
            double lo = Math.round(
                    loc.getLongitude() * 1000.0) / 1000.0;
            String key = la + "," + lo;
            org.json.JSONArray arr;
            try {
                arr = new org.json.JSONArray(
                        places.getString("samples", "[]"));
            } catch (Throwable t) {
                arr = new org.json.JSONArray();
            }
            org.json.JSONObject one =
                    new org.json.JSONObject();
            one.put("t", now);
            one.put("k", key);
            arr.put(one);
            while (arr.length() > 80) {
                arr.remove(0);
            }
            android.content.SharedPreferences.Editor ed =
                    places.edit();
            ed.putString("samples", arr.toString());
            ed.putLong("lastSampleAt", now);
            // 现在的地名（按点缓存反查）
            String geoKey = "geo:" + key;
            String placeText = places.getString(geoKey,
                    "");
            if (placeText.isEmpty()) {
                placeText = reversePlace(la, lo);
                if (!placeText.isEmpty()) {
                    ed.putString(geoKey, placeText);
                }
            }
            if (!placeText.isEmpty()) {
                ed.putString("placeNow", placeText);
            }
            // 学家：凌晨样本最多的点
            java.util.Map<String, Integer> night =
                    new java.util.HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o =
                        arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                java.util.Calendar c =
                        java.util.Calendar.getInstance();
                c.setTimeInMillis(o.optLong("t", 0));
                int h = c.get(
                        java.util.Calendar.HOUR_OF_DAY);
                if (h >= 0 && h < 5) {
                    String k = o.optString("k", "");
                    Integer n = night.get(k);
                    night.put(k, n == null ? 1 : n + 1);
                }
            }
            String homeKey = null;
            int homeN = 2;
            for (java.util.Map.Entry<String, Integer> e
                    : night.entrySet()) {
                if (e.getValue() > homeN) {
                    homeN = e.getValue();
                    homeKey = e.getKey();
                }
            }
            if (homeKey != null && !homeKey.equals(
                    places.getString("homeKey", ""))) {
                String[] parts = homeKey.split(",");
                String ht = reversePlace(
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]));
                ed.putString("homeKey", homeKey);
                if (!ht.isEmpty()) {
                    ed.putString("homeText", ht);
                    FileLog.i("Morning learned home " + ht);
                }
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 经纬度反查区县名（BigDataCloud 免费接口）。 */
    private static String reversePlace(double la,
            double lo) {
        try {
            JSONObject o = new JSONObject(get(
                    "https://api.bigdatacloud.net/data/"
                            + "reverse-geocode-client"
                            + "?latitude=" + la
                            + "&longitude=" + lo
                            + "&localityLanguage=zh"));
            String city = o.optString("city", "");
            String loc = o.optString("locality", "");
            if (loc.isEmpty()) {
                loc = o.optString(
                        "principalSubdivision", "");
            }
            String t = city + (loc.equals(city) ? "" : loc);
            return t == null ? "" : t.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent",
                "flymebobo-morning");
        BufferedReader in = new BufferedReader(
                new InputStreamReader(c.getInputStream(),
                        "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = in.readLine()) != null) {
            sb.append(ln);
        }
        in.close();
        return sb.toString();
    }
}
