package com.chenan.flymebobo;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

// Hides the system incoming-call UI so only the call bubble shows
// (v104, following 1.x InCallUiHide).
//
// Runs in the com.android.incallui process. Three interception points
// cover every way the system call UI can appear:
//   1. InCallActivity.onCreate — the fullscreen call page. We let the
//      activity create itself, then finish it right away when the
//      bubble owns the call.
//   2. NotificationBroadcastReceiver.performFullscreenIntent — the
//      broadcast that launches the fullscreen page. We drop it.
//   3. InCallPresenter.showInCall — the entry that brings up the
//      in-call UI. We drop it when a real incoming call is present.
//
// The bubble yields only when all of these hold: the screen is
// interactive, the keyguard is NOT locked, and an incoming call
// exists. On the lock screen the system page still shows, so the user
// can answer from there.
//
// Note: 1.x has a request-token escape hatch (tapping the call card
// lets the system page open on demand). 2.0 has no call card yet
// (user 2026-11-19 "胶囊展开先不设计"), so there is no token here —
// it comes back with the card.
public final class InCallUiHide {
    private static final String TAG = "flymebobo";
    private static final int RETRIES = 24;
    private static final long RETRY_DELAY_MS = 5000;

    private static final int HOOK_PAGE = 1;
    private static final int HOOK_BROADCAST = 2;
    private static final int HOOK_PRESENTER = 4;
    private static final int HOOK_ALL = HOOK_PAGE | HOOK_BROADCAST | HOOK_PRESENTER;

    private final XposedInterface bridge;
    private final Application app;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int installed;
    private Method callListInstance;
    private Method callListIncoming;

    public InCallUiHide(XposedInterface xposed, Application app) {
        this.bridge = xposed;
        this.app = app;
        this.loader = app.getClassLoader();
    }

    // v127: the speaker toggle needs the route set from the
    // incallui process (the process that owns the call). SystemUI
    // sends ACTION_CALL_AUDIO with extra "speaker"; this receiver
    // applies it here with the incallui Application context.
    // (Restored v123 mechanism — the v125 notification-action
    // "speaker_on" never fires on this Flyme.)
    public static final String ACTION_CALL_AUDIO =
            "com.chenan.flymebobo.ACTION_CALL_AUDIO";

    public void install() {
        tryInstall(0);
        registerAudioReceiver();
    }

    private void registerAudioReceiver() {
        try {
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(ACTION_CALL_AUDIO);
            android.content.BroadcastReceiver receiver =
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(android.content.Context ctx,
                                android.content.Intent intent) {
                            if (intent == null) {
                                return;
                            }
                            boolean speaker = intent.getBooleanExtra(
                                    "speaker", false);
                            applySpeakerFromInCallUi(speaker);
                        }
                    };
            try {
                java.lang.reflect.Method m =
                        android.content.Context.class.getMethod(
                                "registerReceiver",
                                android.content.BroadcastReceiver.class,
                                android.content.IntentFilter.class,
                                int.class);
                m.invoke(app, receiver, filter, 2);
            } catch (Throwable t) {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applySpeakerFromInCallUi(boolean speaker) {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) app.getSystemService(
                            android.content.Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    int wantType = speaker
                            ? android.media.AudioDeviceInfo
                                    .TYPE_BUILTIN_SPEAKER
                            : android.media.AudioDeviceInfo
                                    .TYPE_BUILTIN_EARPIECE;
                    java.util.List<android.media.AudioDeviceInfo> devs =
                            am.getAvailableCommunicationDevices();
                    for (android.media.AudioDeviceInfo d : devs) {
                        if (d.getType() == wantType) {
                            am.setCommunicationDevice(d);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            am.setSpeakerphoneOn(speaker);
        } catch (Throwable ignored) {
        }
    }
    private void tryInstall(final int attempt) {
        if ((installed & HOOK_PAGE) == 0 && attachPageHook()) {
            installed |= HOOK_PAGE;
        }
        if ((installed & HOOK_BROADCAST) == 0 && attachBroadcastHook()) {
            installed |= HOOK_BROADCAST;
        }
        if ((installed & HOOK_PRESENTER) == 0 && attachPresenterHook()) {
            installed |= HOOK_PRESENTER;
        }
        if (installed == HOOK_ALL) {
            log("InCallUiHide: all 3 call hooks installed");
            return;
        }
        if (attempt < RETRIES) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tryInstall(attempt + 1);
                }
            }, RETRY_DELAY_MS);
            return;
        }
        log("InCallUiHide: gave up, hooked: fullscreen-page="
                + ((installed & HOOK_PAGE) != 0)
                + " fullscreen-broadcast=" + ((installed & HOOK_BROADCAST) != 0)
                + " showInCall=" + ((installed & HOOK_PRESENTER) != 0));
    }

    private boolean attachPageHook() {
        try {
            Method target = lookup("com.android.incallui.InCallActivity",
                    "onCreate", Bundle.class);
            if (target == null) {
                return false;
            }
            bridge.hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    boolean own = shouldYield(null, true);
                    Object result = chain.proceed();
                    if (own) {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            ((Activity) self).finish();
                        }
                    }
                    return result;
                }
            });
            return true;
        } catch (Throwable th) {
            log("hook InCallActivity.onCreate failed: " + th);
            return false;
        }
    }

    private boolean attachBroadcastHook() {
        try {
            Method target = lookup(
                    "com.android.incallui.notification.NotificationBroadcastReceiver",
                    "performFullscreenIntent", Context.class, Intent.class);
            if (target == null) {
                return false;
            }
            bridge.hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    Object first = chain.getArg(0);
                    Context ctx = first instanceof Context ? (Context) first : null;
                    if (shouldYield(ctx, true)) {
                        return null;
                    }
                    return chain.proceed();
                }
            });
            return true;
        } catch (Throwable th) {
            log("hook performFullscreenIntent failed: " + th);
            return false;
        }
    }

    private boolean attachPresenterHook() {
        try {
            Method target = lookup("com.android.incallui.InCallPresenter",
                    "showInCall", Boolean.TYPE, Boolean.TYPE, Boolean.TYPE,
                    Boolean.TYPE, Boolean.TYPE, Boolean.TYPE, Boolean.TYPE);
            if (target == null) {
                return false;
            }
            bridge.hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    if (shouldYield(null, hasIncomingCall())) {
                        return null;
                    }
                    return chain.proceed();
                }
            });
            return true;
        } catch (Throwable th) {
            log("hook InCallPresenter.showInCall failed: " + th);
            return false;
        }
    }

    private Method lookup(String className, String name, Class<?>... params) {
        try {
            Class<?> cls = Class.forName(className, false, loader);
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable th) {
            return null;
        }
    }

    // The bubble owns the call when the screen is up, the device is
    // unlocked, and a call is actually ringing.
    private boolean shouldYield(Context context, boolean incoming) {
        if (!incoming) {
            return false;
        }
        Context ctx = context != null ? context : app;
        return screenUsable(ctx) && !deviceLocked(ctx);
    }

    private boolean screenUsable(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(
                    Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean deviceLocked(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(
                    Context.KEYGUARD_SERVICE);
            if (km == null) {
                return true;
            }
            return Build.VERSION.SDK_INT >= 33 ? km.isKeyguardLocked()
                    : km.inKeyguardRestrictedInputMode();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean hasIncomingCall() {
        try {
            if (callListInstance == null) {
                Class<?> cls = Class.forName(
                        "com.android.incallui.call.CallList", false, loader);
                callListInstance = cls.getDeclaredMethod("getInstance");
                callListIncoming = cls.getDeclaredMethod("getIncomingCall");
            }
            Object list = callListInstance.invoke(null);
            return callListIncoming.invoke(list) != null;
        } catch (Throwable th) {
            return false;
        }
    }

    private void log(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
