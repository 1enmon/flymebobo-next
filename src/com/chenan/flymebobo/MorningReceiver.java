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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

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
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        reschedule(ctx);
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                fire(app);
            }
        }, "morning-report").start();
    }

    private void fire(Context ctx) {
        String city = cityOf(ctx);
        String[] w = fetchWeather(ctx, city);
        if (w == null) {
            FileLog.i("Morning fetch fail city=" + city);
            return;
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
        boolean light = false;
        try {
            light = ctx.getSharedPreferences(
                    "flymebobo_features",
                    Context.MODE_PRIVATE)
                    .getBoolean("lightMode", false);
        } catch (Throwable ignored) {
        }
        if (light) {
            Intent it = new Intent(ACTION_REPORT);
            it.putExtra("line", line);
            it.putExtra("body", body.toString());
            it.putExtra("dateStr", dateStr);
            it.putExtra("city", city);
            ctx.sendBroadcast(it);
            FileLog.i("Morning report sent (light)");
        } else {
            postNotification(ctx, line, body.toString());
            FileLog.i("Morning report posted (classic)");
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
