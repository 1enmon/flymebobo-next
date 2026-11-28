package com.chenan.flymebobo;

import android.app.Application;
import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

// Drops the system call notification from SystemUI's own collection
// (v136) so it never renders anywhere — not as a heads-up, not in the
// shade, not as the ongoing-call card in the control center (the
// screenshot: 188 8320 4388 / 00:01 with hang-up + speaker buttons).
//
// This is the "intercept everything" the diagnostic build does: the
// system call UI is replaced by the bubble entirely, instead of only
// the incoming banner being suppressed (CallBannerSuppress) while the
// ongoing notification still shows up after answering.
//
// Only SystemUI's display is affected. The module's own
// NotificationListenerService runs in a different process and reads
// notifications straight from NotificationManagerService, so the
// bubble, the answer button and the speaker toggle (which uses the
// listener's getActiveNotifications() / pre-extracted PendingIntents)
// keep working. The notification itself is NOT cancelled.
//
// Identification matches CallBannerSuppress: the in-call UI package
// with no channel or a "phone_*" channel; a call-category
// notification; or a fullscreen-intent notification from a
// dialer/phone/telecom package.
public final class CallNotifSuppress {
    private static final String TAG = "flymebobo";

    private final XposedInterface bridge;
    private final Application app;
    private boolean done;

    public CallNotifSuppress(XposedInterface xposed, Application app) {
        this.bridge = xposed;
        this.app = app;
    }

    public void install() {
        if (done) {
            return;
        }
        try {
            Class<?> collection = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.NotifCollection",
                    false, app.getClassLoader());
            int count = 0;
            for (Method m : collection.getDeclaredMethods()) {
                if (!"onNotificationPosted".equals(m.getName())) {
                    continue;
                }
                if (attach(m)) {
                    count++;
                }
            }
            if (count == 0) {
                log("CallNotifSuppress: onNotificationPosted not found");
                return;
            }
            done = true;
            log("CallNotifSuppress: NotifCollection.onNotificationPosted hooked ("
                    + count + " overloads)");
        } catch (Throwable th) {
            log("CallNotifSuppress install failed: " + th);
        }
    }

    // Every onNotificationPosted overload takes the
    // StatusBarNotification as its first argument, so one handler
    // covers all of them.
    private boolean attach(final Method target) {
        try {
            bridge.hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    if (shouldDrop(chain.getArg(0))) {
                        log("call notification dropped from SystemUI collection");
                        return null;
                    }
                    return chain.proceed();
                }
            });
            return true;
        } catch (Throwable th) {
            log("CallNotifSuppress: hook failed for " + target + ": " + th);
            return false;
        }
    }

    private boolean shouldDrop(Object arg0) {
        // v656: 轻气泡不碰来电（wei 定）；经典来电
        // 总开关关着时也不碰——系统来电界面全量放行。
        // 此前这里没有任何开关门，来电通知在轻气泡
        // 下也被吞，电话没法接（wei 实测成未接来电）。
        if (FeatureFlags.lightMode || !FeatureFlags.live) {
            return false;
        }
        if (!(arg0 instanceof StatusBarNotification)) {
            return false;
        }
        return isCallNotif((StatusBarNotification) arg0);
    }

    private boolean isCallNotif(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null) {
            return false;
        }
        Notification n = sbn.getNotification();
        String channel = n == null ? null : n.getChannelId();
        if ("com.android.incallui".equals(pkg)) {
            return channel == null || channel.startsWith("phone_");
        }
        if (n == null) {
            return false;
        }
        if ("call".equals(n.category)) {
            return true;
        }
        if (n.fullScreenIntent == null) {
            return false;
        }
        String lower = pkg.toLowerCase();
        return lower.contains("dialer") || lower.contains("phone")
                || lower.contains("telecom") || lower.contains("incallui")
                || lower.contains("call");
    }

    private void log(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
