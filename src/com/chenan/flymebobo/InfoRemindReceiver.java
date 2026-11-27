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
import android.os.Build;

import java.util.Map;

/**
 * v601: 信息卡片票类自动提醒——SystemUI 解析出带时
 * 间的票卡后广播给 App 进程（NotificationService
 * 接收），这里用精确闹钟排在发车/起飞前（提前量读
 * 设置 infoLeadMin，默认 60 分钟）；到点发一条高优
 * 通知。待触发清单存 flymebobo_inforemind prefs，
 * 开机后重排。清单格式：key=卡 id，value=事件时间
 * \u0001标题\u0001副行\u0001种类。
 */
public class InfoRemindReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE =
            "com.chenan.flymebobo.INFO_REMIND_FIRE";
    private static final String STORE = "flymebobo_inforemind";
    private static final String CHANNEL = "info_remind";

    private static SharedPreferences store(Context ctx) {
        return ctx.getSharedPreferences(STORE,
                Context.MODE_PRIVATE);
    }

    private static int leadMinutes(Context ctx) {
        try {
            SharedPreferences f = ctx.getSharedPreferences(
                    "flymebobo_features", Context.MODE_PRIVATE);
            int v = f.getInt("infoLeadMin", 60);
            return v > 0 ? v : 60;
        } catch (Throwable t) {
            return 60;
        }
    }

    private static PendingIntent pending(Context ctx,
            String id) {
        Intent i = new Intent(ctx, InfoRemindReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, id.hashCode(),
                i, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context ctx, String id,
            String title, String sub, String kind,
            long eventTime) {
        if (eventTime <= 0) {
            return;
        }
        long trigger = eventTime - leadMinutes(ctx) * 60_000L;
        store(ctx).edit().putString(id, eventTime + "\u0001"
                + title + "\u0001" + (sub == null ? "" : sub)
                + "\u0001" + kind).apply();
        if (trigger <= System.currentTimeMillis()) {
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(
                Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger,
                    pending(ctx, id));
            FileLog.i("Light info remind set id=" + id);
        } catch (Throwable t) {
            try {
                am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, trigger,
                        pending(ctx, id));
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cancel(Context ctx, String id) {
        try {
            AlarmManager am = (AlarmManager)
                    ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pending(ctx, id));
            }
        } catch (Throwable ignored) {
        }
        store(ctx).edit().remove(id).apply();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) {
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(
                intent.getAction())) {
            Map<String, ?> all = store(ctx).getAll();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String[] parts = String.valueOf(e.getValue())
                        .split("\u0001", -1);
                if (parts.length < 4) {
                    continue;
                }
                try {
                    long event = Long.parseLong(parts[0]);
                    if (event > now) {
                        schedule(ctx, e.getKey(), parts[1],
                                parts[2], parts[3], event);
                    }
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        String id = intent.getStringExtra("id");
        if (id == null) {
            return;
        }
        String saved = store(ctx).getString(id, null);
        store(ctx).edit().remove(id).apply();
        if (saved == null) {
            return;
        }
        String[] parts = saved.split("\u0001", -1);
        if (parts.length < 4) {
            return;
        }
        String title = parts[1];
        String sub = parts[2];
        String kind = parts[3];
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "行程提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        String head = "train".equals(kind) ? "发车提醒"
                : "flight".equals(kind) ? "起飞提醒"
                : "行程提醒";
        Intent open = new Intent();
        open.setClassName(ctx, "com.chenan.flymebobo.MainActivity");
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,
                id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(head + " · " + title)
                .setContentText(sub)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(title + "\n" + sub))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id.hashCode(), n);
        FileLog.i("Light info remind fired id=" + id);
    }
}
