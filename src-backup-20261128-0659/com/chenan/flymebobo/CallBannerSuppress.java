package com.chenan.flymebobo;

import android.app.Application;
import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

// Suppresses the system incoming-call heads-up banner in SystemUI so
// only the call bubble shows (v104, following 1.x CallBannerSuppress).
//
// The notification itself is NOT cancelled here — it stays in the
// shade and in the listener, so the bubble still triggers. Only the
// floating banner is dropped. This matters because cancelling the
// notification from the listener races the heads-up: the banner can
// already be on screen before the cancel lands. Dropping it at the
// heads-up layer removes the race.
//
// Identification: the banner belongs to the in-call UI package with
// no channel or a "phone_*" channel; a call-category notification or
// a fullscreen-intent notification from a dialer/phone/telecom
// package counts too.
public final class CallBannerSuppress {
    private static final String TAG = "flymebobo";

    private final XposedInterface bridge;
    private final Application app;
    private boolean done;

    public CallBannerSuppress(XposedInterface xposed, Application app) {
        this.bridge = xposed;
        this.app = app;
    }

    public void install() {
        if (done) {
            return;
        }
        try {
            Class<?> headsUp = Class.forName(
                    "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl",
                    false, app.getClassLoader());
            int count = 0;
            for (Method m : headsUp.getDeclaredMethods()) {
                if (!"showNotification".equals(m.getName())) {
                    continue;
                }
                if (attach(m)) {
                    count++;
                }
            }
            if (count == 0) {
                log("CallBannerSuppress: showNotification not found");
                return;
            }
            done = true;
            log("CallBannerSuppress: HeadsUpManagerImpl.showNotification hooked ("
                    + count + " overloads)");
        } catch (Throwable th) {
            log("CallBannerSuppress install failed: " + th);
        }
    }

    // Every showNotification overload takes the entry as its first
    // argument, so one handler covers all of them.
    private boolean attach(final Method target) {
        try {
            bridge.hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    if (shouldDrop(chain.getArg(0))) {
                        log("incoming-call banner suppressed");
                        return null;
                    }
                    return chain.proceed();
                }
            });
            return true;
        } catch (Throwable th) {
            log("CallBannerSuppress: hook failed for " + target + ": " + th);
            return false;
        }
    }

    private boolean shouldDrop(Object entry) {
        if (entry == null) {
            return false;
        }
        try {
            Object sbnObj = entry.getClass().getMethod("getSbn")
                    .invoke(entry);
            if (!(sbnObj instanceof StatusBarNotification)) {
                return false;
            }
            return isCallHeadsUp((StatusBarNotification) sbnObj);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCallHeadsUp(StatusBarNotification sbn) {
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
